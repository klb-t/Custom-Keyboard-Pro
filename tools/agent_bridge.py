#!/usr/bin/env python3
"""Optional Loom AgentRuntime adapter for the existing Android GoalJson v1 format.

The result is a proposal for local review. This module never dispatches Android
actions, loads credentials, connects to a phone or transfers execution authority.
The external runtime is injected; IO Matrix does not bundle or require Loom.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import importlib
import json
from pathlib import Path
import re
import sys


IDENTIFIER = r"[A-Za-z][A-Za-z0-9_.-]{0,79}"
MAX_DOCUMENT_UNITS = 65_536
MAX_DOCUMENT_BYTES = MAX_DOCUMENT_UNITS * 4
RUNTIME_FILES = (
    "loom/tools/agent_runtime_v1/runtime.py",
    "loom/tools/coordination/leases.py",
    "loom/tools/contracts/analysis_plan_ref.py",
    "loom/tools/contracts/validate.py",
)


def _text_schema(maximum, **extra):
    return {"type": "string", "maxLength": maximum, **extra}


# This is the existing GoalJson projection, not a second goal/permission schema.
PLAN_SCHEMA = {
    "type": "object", "additionalProperties": False,
    "required": ["version", "alternatives"],
    "properties": {"version": {"const": 1, "type": "integer"}, "alternatives": {
        "type": "array", "minItems": 1, "maxItems": 4,
        "items": {"type": "object", "additionalProperties": False,
            "required": ["title", "steps"], "properties": {
                "title": _text_schema(200, minLength=1), "steps": {
                    "type": "array", "minItems": 1, "maxItems": 32,
                    "items": {"type": "object", "additionalProperties": False,
                        "required": ["id", "action", "arguments", "after"],
                        "properties": {
                            "id": _text_schema(80, pattern="^" + IDENTIFIER + "$"),
                            "action": _text_schema(80, pattern="^" + IDENTIFIER + "$"),
                            "arguments": {"type": "object", "maxProperties": 16,
                                "propertyNames": {"pattern": "^" + IDENTIFIER + "$"},
                                "additionalProperties": _text_schema(8000)},
                            "after": {"type": "array", "maxItems": 32, "uniqueItems": True,
                                "items": _text_schema(80)},
                            "reason": _text_schema(2000), "expected": _text_schema(2000),
                            "apiHints": {"type": "array", "maxItems": 8,
                                "items": _text_schema(1000)},
                        }}}}}}}}


def _units(value):
    # Android/Kotlin String.length counts UTF-16 units, including emoji pairs.
    return len(value.encode("utf-16-le", errors="strict")) // 2


def _text(value, maximum, *, nonblank=False):
    if type(value) is not str or _units(value) > maximum or (nonblank and not value.strip()):
        raise ValueError("invalid_text")
    return value


def _fields(value, required, optional=()):
    if type(value) is not dict or not set(required) <= value.keys() or not value.keys() <= set(required) | set(optional):
        raise ValueError("invalid_or_unknown_fields")


def _array(value, maximum, *, minimum=0):
    if type(value) is not list or not minimum <= len(value) <= maximum:
        raise ValueError("array_limit")
    return value


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate_json_field")
        result[key] = value
    return result


def _constant(value):
    raise ValueError("nonfinite_json")


def _depth(value, level=0):
    if type(value) in (dict, list):
        if level >= 12:
            raise ValueError("json_depth_limit")
        for child in (value.values() if type(value) is dict else value):
            _depth(child, level + 1)


def validate_plan(plan, actual_goal):
    """Validate definitions; readiness, arguments and effects remain Android-owned.

    Unknown action IDs are preserved as capability gaps. A syntactically valid
    argument string is not evidence that a catalogue action accepts its value.
    """
    _text(actual_goal, 8000, nonblank=True)
    _fields(plan, ("version", "alternatives"))
    if type(plan["version"]) is not int or plan["version"] != 1:
        raise ValueError("unsupported_plan_version")
    _depth(plan)
    for alternative in _array(plan["alternatives"], 4, minimum=1):
        _fields(alternative, ("title", "steps"))
        _text(alternative["title"], 200, nonblank=True)
        steps = _array(alternative["steps"], 32, minimum=1)
        identifiers = set()
        for step in steps:
            _fields(step, ("id", "action", "arguments", "after"), ("reason", "expected", "apiHints"))
            for key in ("id", "action"):
                if not re.fullmatch(IDENTIFIER, _text(step[key], 80)):
                    raise ValueError("invalid_identifier")
            if step["id"] in identifiers:
                raise ValueError("duplicate_step_identifier")
            identifiers.add(step["id"])
            arguments = step["arguments"]
            if type(arguments) is not dict or len(arguments) > 16:
                raise ValueError("argument_limit")
            for key, value in arguments.items():
                if type(key) is not str or not re.fullmatch(IDENTIFIER, key):
                    raise ValueError("invalid_argument_name")
                _text(value, 8000)
            after = _array(step["after"], 32)
            for dependency in after:
                _text(dependency, 80)
            if len(set(after)) != len(after):
                raise ValueError("duplicate_dependency")
            for key in ("reason", "expected"):
                _text(step.get(key, ""), 2000)
            for hint in _array(step.get("apiHints", []), 8):
                _text(hint, 1000)
        if any(step["id"] in step["after"] or not set(step["after"]) <= identifiers for step in steps):
            raise ValueError("invalid_dependency")
        done = set()
        for _ in steps:
            done.update(step["id"] for step in steps if set(step["after"]) <= done)
        if done != identifiers:
            raise ValueError("cyclic_dependency")
    detached = deepcopy(plan)
    if _units(json.dumps(detached, ensure_ascii=False, allow_nan=False, separators=(",", ":"))) > MAX_DOCUMENT_UNITS:
        raise ValueError("plan_document_limit")
    return detached


def read_plan(raw, actual_goal):
    if type(raw) is not str or not 2 <= _units(raw) <= MAX_DOCUMENT_UNITS:
        raise ValueError("plan_document_limit")
    try:
        plan = json.loads(raw, object_pairs_hook=_pairs, parse_constant=_constant)
    except RecursionError:
        raise ValueError("json_depth_limit") from None
    return validate_plan(plan, actual_goal)


def proposal_json(plan, actual_goal):
    # Compact export fits the same document budget; formatting is not authority.
    return json.dumps(validate_plan(plan, actual_goal), ensure_ascii=False, allow_nan=False,
                      separators=(",", ":"), sort_keys=True)


def proposal_tool(runtime_api, actual_goal):
    """Create a real Tool for loom.tools.agent_runtime_v1.runtime.AgentRuntime.

    The caller binds a trusted goal independently of the planner's response. The
    tool returns the canonical import document; Android still requires local
    review, current capability evidence and its existing typed performer.
    """
    _text(actual_goal, 8000, nonblank=True)
    goal_hash = hashlib.sha256(actual_goal.encode("utf-8")).hexdigest()

    def invoke(environment, arguments):
        document = validate_plan(arguments, actual_goal)
        return runtime_api.observed({"kind": "proposal", "goal_sha256": goal_hash,
            "plan": document, "plan_json": proposal_json(document, actual_goal),
            "android_effects_dispatched": False, "requires_local_review": True})

    output_schema = {"type": "object", "additionalProperties": False,
        "required": ["kind", "goal_sha256", "plan", "plan_json", "android_effects_dispatched", "requires_local_review"],
        "properties": {"kind": {"const": "proposal"}, "goal_sha256": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
            "plan": deepcopy(PLAN_SCHEMA), "plan_json": {"type": "string"},
            "android_effects_dispatched": {"const": False}, "requires_local_review": {"const": True}}}
    return runtime_api.Tool("iomatrix.propose_plan", "goal-plan/1", deepcopy(PLAN_SCHEMA),
        output_schema, ("iomatrix.proposals",),
        {"kind": "proposal_only", "android_effects": False, "execution_authority_transferred": False},
        invoke, {"consumer": "GoalJson.read + GoalAgent.offerPlans", "goal_sha256": goal_hash,
                 "semantic_assessment": "Android canonical catalogue and current capability evidence"})


def load_runtime(source):
    """Load only an explicitly selected local Loom source; no fetch or fallback."""
    root = Path(source).resolve(strict=True)
    hashes = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in RUNTIME_FILES}
    hashes["iomatrix/tools/agent_bridge.py"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    sys.path.insert(0, str(root))
    runtime = importlib.import_module("loom.tools.agent_runtime_v1.runtime")
    for name in RUNTIME_FILES:
        module = sys.modules.get(name.removesuffix(".py").replace("/", "."))
        if module is None or Path(module.__file__).resolve() != root / name:
            raise ValueError("another_loom_runtime_dependency_is_already_loaded")
    # LeaseStore accepts a complete SHA-256 source identity as well as Git IDs.
    source_identity = hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()
    return runtime, {"source_identity_kind": "selected_source_files_sha256", "source_sha256": source_identity, "files": hashes}


def run_proposal(runtime, manifest, *, plan, actual_goal, journal, session_id, input_source="TEXT"):
    if input_source not in ("TEXT", "VOICE"):
        raise ValueError("invalid_input_source")
    document = validate_plan(plan, actual_goal)
    tool = proposal_tool(runtime, actual_goal)
    environment = runtime.Environment("iomatrix.proposal-host", "in_process_proposal_adapter",
        ("iomatrix.proposals",), {"source_manifest": deepcopy(manifest),
            "proposal_sha256": hashlib.sha256(proposal_json(document, actual_goal).encode()).hexdigest()},
        "no_android_transport_or_effects")
    engine = runtime.AgentRuntime(journal, tools=[tool], environments=[environment],
                                 source_commit=manifest["source_sha256"])

    def planner(context):
        if not context["observations"]:
            return {"kind": "action", "tool": tool.id, "arguments": deepcopy(document)}
        return {"kind": "final", "value": context["observations"][-1]["result"]}

    result = engine.run(session_id=session_id,
        goal={"text": actual_goal, "input_source": input_source, "kind": "user_goal"},
        environment_id=environment.id, planner_id="iomatrix.supplied-proposal/1",
        planner=planner, max_steps=2, lease_seconds=30, owner="iomatrix.agent-bridge")
    return result


def _read_bounded(path):
    with Path(path).open("rb") as stream:
        raw = stream.read(MAX_DOCUMENT_BYTES + 1)
    if len(raw) > MAX_DOCUMENT_BYTES:
        raise ValueError("input_byte_limit")
    return raw.decode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate", "run"))
    parser.add_argument("--plan", required=True, help="GoalJson v1 plan definitions")
    parser.add_argument("--goal-file", required=True, help="Explicit local goal text; never read from plan JSON")
    parser.add_argument("--output", required=True, help="New JSON file for Android's Import plan JSON")
    parser.add_argument("--loom-source", help="Explicit local ChatADHD/Loom source checkout (run only)")
    parser.add_argument("--journal", help="Caller-owned durable runtime directory (run only)")
    parser.add_argument("--session", help="Bound session identity; replay keeps the original specification (run only)")
    parser.add_argument("--input-source", choices=("TEXT", "VOICE"), default="TEXT")
    args = parser.parse_args()
    actual_goal = _text(_read_bounded(args.goal_file).strip(), 8000, nonblank=True)
    document = read_plan(_read_bounded(args.plan), actual_goal)
    if args.command == "run":
        if not all((args.loom_source, args.journal, args.session)):
            parser.error("run requires --loom-source, --journal and --session")
        runtime, manifest = load_runtime(args.loom_source)
        result = run_proposal(runtime, manifest, plan=document, actual_goal=actual_goal,
                              journal=args.journal, session_id=args.session, input_source=args.input_source)
        if result["state"] != "completed":
            raise ValueError("runtime_did_not_complete:" + result["state"])
        document = result["value"]["payload"]["plan"]
    else:
        manifest = None
    with Path(args.output).open("x", encoding="utf-8") as output:
        output.write(proposal_json(document, actual_goal) + "\n")
    print(json.dumps({"status": "proposal_exported", "android_effects_dispatched": False,
        "requires_local_review": True, "source_manifest": manifest}, ensure_ascii=False))


if __name__ == "__main__":
    main()
