#!/usr/bin/env python3
"""Offline bridge guards plus optional tests against the actual selected Loom runtime.

IOMATRIX_LOOM_SOURCE enables the external-runtime tests. Without it, those tests
are visibly skipped; no synthetic Tool is described as cross-project validation.
"""
from copy import deepcopy
import json
import os
from pathlib import Path
import tempfile
import unittest

import agent_bridge as bridge


FIXTURE = Path(__file__).resolve().parents[1] / "app/src/test/resources/agent-bridge/plan-v1.json"
GOAL = "Make this bulb react to music"


class BridgeGuardTest(unittest.TestCase):
    def setUp(self):
        self.raw = FIXTURE.read_text()
        self.plan = bridge.read_plan(self.raw, GOAL)

    def test_shared_android_projection_roundtrips_without_goal_or_grants(self):
        raw = bridge.proposal_json(self.plan, GOAL)
        self.assertEqual(self.plan, bridge.read_plan(raw, "Different local goal"))
        self.assertNotIn(GOAL, raw)
        self.assertNotIn("approved", raw)

    def test_duplicate_decoded_fields_and_non_json_are_rejected(self):
        for raw in (self.raw.replace('"version": 1', '"version": 2, "version": 1'),
                    self.raw.replace('"version": 1', '"ver\\u0073ion": 2, "version": 1'),
                    self.raw + "{}", self.raw.replace('"version"', "'version'"),
                    self.raw.replace('"version": 1', '"version": NaN')):
            with self.subTest(raw=raw[:50]), self.assertRaises(ValueError):
                bridge.read_plan(raw, GOAL)

    def test_execution_state_goal_and_grant_fields_are_rejected(self):
        for key in ("goal", "approved", "permissions", "execution_state"):
            plan = deepcopy(self.plan)
            plan[key] = True
            with self.subTest(key=key), self.assertRaises(ValueError):
                bridge.validate_plan(plan, GOAL)
        plan = deepcopy(self.plan)
        plan["alternatives"][0]["steps"][0]["state"] = "VERIFIED"
        with self.assertRaises(ValueError):
            bridge.validate_plan(plan, GOAL)

    def test_unknown_action_is_preserved_as_a_gap(self):
        self.assertEqual("missing.device_observation", self.plan["alternatives"][0]["steps"][1]["action"])

    def test_arguments_remain_strings_and_version_remains_integer(self):
        for value in (True, {"run": "code"}, ["cmd"]):
            plan = deepcopy(self.plan)
            plan["alternatives"][0]["steps"][0]["arguments"]["target"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                bridge.validate_plan(plan, GOAL)
        for value in (True, 1.0, "1", 2):
            plan = deepcopy(self.plan)
            plan["version"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                bridge.validate_plan(plan, GOAL)

    def test_dependency_cycles_missing_ids_and_duplicate_ids_are_rejected(self):
        for change in ("cycle", "missing", "duplicate", "self"):
            plan = deepcopy(self.plan)
            first, second = plan["alternatives"][0]["steps"]
            if change == "cycle":
                first["after"] = [second["id"]]
            elif change == "missing":
                second["after"] = ["absent"]
            elif change == "duplicate":
                second["id"] = first["id"]
            else:
                first["after"] = [first["id"]]
            with self.subTest(change=change), self.assertRaises(ValueError):
                bridge.validate_plan(plan, GOAL)

    def test_android_utf16_limits_include_emoji_pairs(self):
        plan = deepcopy(self.plan)
        plan["alternatives"][0]["title"] = "😀" * 100
        bridge.validate_plan(plan, GOAL)
        plan["alternatives"][0]["title"] += "😀"
        with self.assertRaises(ValueError):
            bridge.validate_plan(plan, GOAL)
        with self.assertRaises(ValueError):
            bridge.read_plan(" " * bridge.MAX_DOCUMENT_UNITS + self.raw, GOAL)

    def test_empty_local_goal_and_extreme_nesting_fail_before_dispatch(self):
        with self.assertRaises(ValueError):
            bridge.read_plan(self.raw, " ")
        with self.assertRaises(ValueError):
            bridge.read_plan("[" * 2000 + "0" + "]" * 2000, GOAL)

    def test_validated_input_is_detached(self):
        detached = bridge.validate_plan(self.plan, GOAL)
        self.plan["alternatives"][0]["steps"][0]["arguments"]["target"] = "changed"
        self.assertEqual("iomatrix://colour-organ", detached["alternatives"][0]["steps"][0]["arguments"]["target"])


@unittest.skipUnless(os.environ.get("IOMATRIX_LOOM_SOURCE"), "Set IOMATRIX_LOOM_SOURCE for the actual external runtime")
class ActualLoomRuntimeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.runtime, cls.manifest = bridge.load_runtime(os.environ["IOMATRIX_LOOM_SOURCE"])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.plan = bridge.read_plan(FIXTURE.read_text(), GOAL)

    def run_proposal(self, **changes):
        arguments = dict(plan=self.plan, actual_goal=GOAL, journal=self.temp.name,
                         session_id="synthetic-voice-goal", input_source="VOICE")
        arguments.update(changes)
        return bridge.run_proposal(self.runtime, self.manifest, **arguments)

    def test_real_agent_roundtrip_records_proposal_and_replays_without_tool_dispatch(self):
        first = self.run_proposal()
        self.assertEqual("completed", first["state"])
        self.assertEqual(1, len(first["observations"]))
        proposal = first["value"]["payload"]
        self.assertFalse(proposal["android_effects_dispatched"])
        self.assertTrue(proposal["requires_local_review"])
        self.assertEqual(self.plan, bridge.read_plan(proposal["plan_json"], GOAL))
        original_factory = bridge.proposal_tool
        def replay_factory(runtime, goal):
            tool = original_factory(runtime, goal)
            def fail(*unused):
                self.fail("Completed proposal was dispatched again")
            return runtime.Tool(tool.id, tool.revision, tool.input_schema, tool.output_schema,
                                tool.required_capabilities, tool.effects, fail, tool.metadata)
        from unittest.mock import patch
        with patch.object(bridge, "proposal_tool", side_effect=replay_factory):
            self.assertEqual(first, self.run_proposal())
        session_file = next(Path(self.temp.name).glob("*/session_first.json"))
        self.assertEqual("VOICE", json.loads(session_file.read_text())["goal"]["input_source"])

    def test_same_session_cannot_silently_change_goal_or_supplied_proposal(self):
        self.run_proposal()
        with self.assertRaisesRegex(self.runtime.AgentError, "session_binding_changed"):
            self.run_proposal(actual_goal="Different goal")
        changed = deepcopy(self.plan)
        changed["alternatives"][0]["title"] = "Different proposed route"
        with self.assertRaisesRegex(self.runtime.AgentError, "session_binding_changed"):
            self.run_proposal(plan=changed)

    def test_runtime_requires_explicit_proposal_capability_and_never_falls_back(self):
        tool = bridge.proposal_tool(self.runtime, GOAL)
        environment = self.runtime.Environment("without-adapter", "fixture", (), {}, "none")
        runtime = self.runtime.AgentRuntime(self.temp.name, tools=[tool], environments=[environment],
                                           source_commit=self.manifest["source_sha256"])
        result = runtime.run(session_id="missing-adapter", goal={"text": GOAL}, environment_id=environment.id,
            planner_id="fixture", planner=lambda _: {"kind": "action", "tool": tool.id, "arguments": self.plan},
            max_steps=2, lease_seconds=30, owner="test")
        self.assertEqual("unavailable", result["state"])
        self.assertEqual("no_dispatch", result["dispatch"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
