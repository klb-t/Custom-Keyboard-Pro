#!/usr/bin/env python3
"""Offline guard tests. Fake transport here never represents a live AI evaluation."""
import contextlib
from decimal import Decimal
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("goal_evaluator", Path(__file__).with_name("evaluate-goal-ai.py"))
EVALUATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVALUATOR)


class EvaluationGuardTest(unittest.TestCase):
    def test_duplicate_json_is_rejected(self):
        with self.assertRaises(ValueError):
            EVALUATOR.strict_json('{"version":1,"version":2}')

    def test_private_file_mode_and_symlink_are_checked(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            credential = directory / "test.key"
            credential.write_text("synthetic-unusable-credential")
            credential.chmod(0o644)
            with self.assertRaises(ValueError):
                EVALUATOR.read_key(credential)
            credential.chmod(0o600)
            self.assertEqual("synthetic-unusable-credential", EVALUATOR.read_key(credential))
            linked = directory / "symlink.key"
            linked.symlink_to(credential)
            with self.assertRaises(ValueError):
                EVALUATOR.read_key(linked)

    def create_inputs(self, temporary, budget):
        directory = Path(temporary)
        credential = directory / "test.key"
        credential.write_text("synthetic-unusable-credential")
        credential.chmod(0o600)
        system = "Synthetic budget test only; never sent to a real provider."
        (directory / "prompt-bundle.json").write_text(json.dumps({"origin": "production GoalPrompt + GoalCatalogue",
            "scenarios": {"ready": {"system": system, "sha256": hashlib.sha256(system.encode()).hexdigest()}}}))
        fixtures = directory / "cases.json"
        fixtures.write_text(json.dumps({"cases": [{"id": "guard", "scenario": "ready", "goal": "Synthetic guard test", "checks": {}}]}))
        return SimpleNamespace(directory=str(directory), key_file=str(credential), cases=str(fixtures), max_cases=1,
            case=None, model=["synthetic/test"], model_count=1, budget=Decimal(budget), max_tokens=256,
            label="guard", prompt_suffix=None)

    def fake_catalogue(self):
        return {"data": [{"id": "synthetic/test", "pricing": {"prompt": "0.000001", "completion": "0.000002"}}]}

    def test_budget_stops_before_any_completion(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = self.create_inputs(temporary, "0.000001")
            calls = []
            def fake(path, *unused, **ignored):
                calls.append(path)
                self.assertEqual("/models", path)
                return self.fake_catalogue()
            with patch.object(EVALUATOR, "request_json", side_effect=fake):
                EVALUATOR.run(args)
            self.assertEqual(["/models"], calls)
            self.assertEqual("budget_stopped", json.loads((Path(temporary) / "ledger.json").read_text())["status"])

    def test_timeout_keeps_reservation_and_resume_does_not_replay(self):
        with tempfile.TemporaryDirectory() as temporary:
            args = self.create_inputs(temporary, "0.5")
            completions = []
            def fake(path, key=None, body=None, **ignored):
                if path == "/models":
                    return self.fake_catalogue()
                self.assertEqual("/chat/completions", path)
                completions.append(body)
                return {"error_status": "network_or_decode_error"}
            with patch.object(EVALUATOR, "request_json", side_effect=fake), contextlib.redirect_stdout(io.StringIO()):
                EVALUATOR.run(args)
                EVALUATOR.run(args)
            ledger = json.loads((Path(temporary) / "ledger.json").read_text())
            self.assertEqual(1, len(completions))
            self.assertEqual(1, len(ledger["requests"]))
            self.assertIsNone(ledger["requests"][0]["costUsd"])
            self.assertGreater(Decimal(ledger["requests"][0]["reservedUsd"]), 0)
            self.assertNotIn("synthetic-unusable-credential", json.dumps(ledger))

    def test_absent_production_validation_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            EVALUATOR.atomic_json(directory / "ledger.json", {"status": "test_only", "models": [], "limitations": [],
                "requests": [{"id": "test", "case": "test", "modelRequested": "synthetic/test", "label": "guard", "status": "completed", "reservedUsd": "0.01", "costUsd": None}]})
            output = directory / "report.json"
            with contextlib.redirect_stdout(io.StringIO()):
                EVALUATOR.report(SimpleNamespace(directory=str(directory), output=str(output)))
            report = json.loads(output.read_text())
            self.assertEqual("NOT RUN", report["validator"])
            self.assertEqual(0, report["summary"][0]["schemaAccepted"])
            self.assertEqual(1, report["summary"][0]["unresolvedCostRequests"])

    def test_dependency_and_unwanted_effect_checks(self):
        fixture = {"checks": {"require_actions": ["media", "volume_set"], "dependency": ["media", "volume_set"], "forbid_actions": ["lock"]}}
        first = {"id": "s1", "action": "media", "arguments": {"action": "play"}, "after": []}
        second = {"id": "s2", "action": "volume_set", "arguments": {"level": "0.2", "stream": "music"}, "after": []}
        def content():
            return json.dumps({"alternatives": [{"steps": [first, second]}]})
        self.assertIn("alternative_0:effect_dependency_missing", EVALUATOR.semantic_checks(content(), fixture))
        second["after"] = ["s1"]
        self.assertEqual([], EVALUATOR.semantic_checks(content(), fixture))
        second["action"] = "lock"
        self.assertIn("alternative_0:unexpected_effect:lock", EVALUATOR.semantic_checks(content(), fixture))

    def test_conditional_access_is_assessed_from_production_result(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            ledger = {"status": "test_only", "models": [], "limitations": [], "requests": [{
                "id": "usage-probe", "case": "phone-usage-conditional-access", "modelRequested": "synthetic/test",
                "label": "guard", "status": "completed", "reservedUsd": "0.01", "costUsd": None,
                "checksAtDispatch": {"require_blocked_capability": "app_usage"}, "semanticErrors": []}]}
            EVALUATOR.atomic_json(directory / "ledger.json", ledger)
            validation = {"validator": "synthetic validation fixture for guard test only", "results": [{
                "id": "usage-probe", "schemaAccepted": True, "argumentErrors": [], "assessment": [{
                    "action": "phone_info", "ready": False, "issues": [{"id": "app_usage", "state": "SETUP"}]}]}]}
            EVALUATOR.atomic_json(directory / "production-validation.json", validation)
            output = directory / "report.json"
            with contextlib.redirect_stdout(io.StringIO()):
                EVALUATOR.report(SimpleNamespace(directory=str(directory), output=str(output)))
            self.assertEqual(1, json.loads(output.read_text())["summary"][0]["focusedFixtureChecksPassed"])
            validation["results"][0]["assessment"][0]["issues"] = []
            EVALUATOR.atomic_json(directory / "production-validation.json", validation)
            with contextlib.redirect_stdout(io.StringIO()):
                EVALUATOR.report(SimpleNamespace(directory=str(directory), output=str(output)))
            self.assertEqual(0, json.loads(output.read_text())["summary"][0]["focusedFixtureChecksPassed"])


if __name__ == "__main__":
    unittest.main()
