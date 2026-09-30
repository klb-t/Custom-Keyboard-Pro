#!/usr/bin/env python3
"""Bounded, real OpenRouter evaluation of the production IO Matrix goal prompt.

No phone actions run. No private phone data is collected. The credential is read
from an owner-only file outside every Git repository and used in HTTP headers only.
Generate prompt-bundle.json with goal-eval-bridge.sh before making paid calls.
Run that same bridge afterward for production Kotlin parser/argument validation.
Python semantic checks are fixture-specific assertions, not an Android test or an
automatic proof that an arbitrary plan is useful. Human review remains necessary.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
import math
import os
from pathlib import Path
import re
import stat
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://openrouter.ai/api/v1"
MAX_RESPONSE = 2 * 1024 * 1024
IDENTIFIER = re.compile(r"[A-Za-z][A-Za-z0-9_.-]{0,79}\Z")
REPO = Path(__file__).resolve().parents[1]


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate_json_field")
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=lambda value: (_ for _ in ()).throw(ValueError("nonfinite_json_number")))


def atomic_json(path: Path, value):
    temporary = path.with_suffix(path.suffix + ".pending")
    with temporary.open("w", encoding="utf-8") as output:
        json.dump(value, output, ensure_ascii=False, indent=2, allow_nan=False)
        output.flush()
        os.fsync(output.fileno())
    temporary.replace(path)


def read_key(path: Path):
    path = path.absolute()
    if path.resolve() != path or any((parent / ".git").exists() for parent in (path.parent, *path.parents)):
        raise ValueError("credential_file_must_be_outside_git_and_not_a_symlink")
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), "rb") as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or info.st_uid != os.getuid():
            raise ValueError("credential_file_requires_owner_only_mode_600")
        raw = source.read(257)
    if not 1 <= len(raw) <= 256 or any(byte < 33 or byte > 126 for byte in raw):
        raise ValueError("invalid_credential_format")
    return raw.decode("ascii")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


HTTP = urllib.request.build_opener(NoRedirect())


def request_json(path, key=None, body=None, timeout=45):
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if key is not None:
        headers["Authorization"] = "Bearer " + key
    request = urllib.request.Request(API + path, headers=headers,
        data=None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8"))
    try:
        with HTTP.open(request, timeout=timeout) as response:
            raw = response.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE:
                return {"error_status": "response_too_large"}
            data = strict_json(raw)
            if not isinstance(data, dict):
                return {"error_status": "response_not_object"}
            return data
    except urllib.error.HTTPError as failure:
        # Never store provider error bodies: they can contain account information.
        return {"error_status": "http_error", "http_status": failure.code}
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        return {"error_status": "network_or_decode_error"}


def choose_models(catalogue, explicit, count):
    by_id = {model["id"]: model for model in catalogue["data"]}
    if explicit:
        unknown = set(explicit) - by_id.keys()
        if unknown:
            raise ValueError("requested_model_not_in_current_catalogue")
        selected = [by_id[name] for name in explicit]
    else:
        # Data-dependent policy, not hardcoded model names: three distinct authors,
        # newest text chat models with the parameters actually used by AiClient.
        candidates = []
        for model in by_id.values():
            pricing = model.get("pricing", {})
            if ":" in model["id"] or "/auto" in model["id"]:
                continue
            if "text" not in model.get("architecture", {}).get("input_modalities", []):
                continue
            if model.get("architecture", {}).get("output_modalities") != ["text"]:
                continue
            if not {"temperature", "max_tokens"} <= set(model.get("supported_parameters", [])):
                continue
            if not pricing.get("prompt") or not pricing.get("completion"):
                continue
            prompt, completion = Decimal(pricing["prompt"]), Decimal(pricing["completion"])
            if not (0 < prompt <= Decimal("0.000002") and 0 < completion <= Decimal("0.00001")):
                continue
            candidates.append(model)
        candidates.sort(key=lambda model: (-model.get("created", 0), model["id"]))
        selected, authors = [], set()
        for model in candidates:
            author = model["id"].split("/")[0]
            if author in authors:
                continue
            selected.append(model)
            authors.add(author)
            if len(selected) == count:
                break
    for model in selected:
        pricing = model["pricing"]
        if Decimal(pricing["prompt"]) < 0 or Decimal(pricing["completion"]) < 0:
            raise ValueError("unknown_model_price")
    if not selected:
        raise ValueError("no_eligible_current_models")
    return selected


def semantic_checks(content, case):
    """Focused output predicates only; production Kotlin supplies schema validation."""
    errors = []
    try:
        document = strict_json(content)
        alternatives = document["alternatives"]
        if not isinstance(alternatives, list) or not 1 <= len(alternatives) <= 4:
            return ["uninspectable_alternatives"]
        for index, alternative in enumerate(alternatives):
            steps = alternative["steps"]
            if not isinstance(steps, list) or not 1 <= len(steps) <= 32:
                errors.append(f"alternative_{index}:uninspectable_steps")
                continue
            actions = [step["action"] for step in steps]
            checks = case.get("checks", {})
            required = checks.get("require_actions", []) + ([checks["require_action"]] if checks.get("require_action") else [])
            for required_action in required:
                if required_action not in actions:
                    errors.append(f"alternative_{index}:missing_expected_action:{required_action}")
            if checks.get("require_missing") and not any(action.startswith("missing.") for action in actions):
                errors.append(f"alternative_{index}:integration_gap_not_retained")
            forbidden = checks.get("forbid_actions", [])
            patterns = checks.get("forbid_action_patterns", [])
            for action in actions:
                if action in forbidden or any(pattern in action for pattern in patterns):
                    errors.append(f"alternative_{index}:unexpected_effect:{action}")
            expected_arguments = dict(checks.get("arguments_by_action", {}))
            if checks.get("require_action"):
                expected_arguments[checks["require_action"]] = checks.get("required_arguments", {})
            for action, expected in expected_arguments.items():
                matching = [step for step in steps if step["action"] == action]
                if matching and not any(all(step["arguments"].get(key) == value for key, value in expected.items()) for step in matching):
                    errors.append(f"alternative_{index}:wrong_expected_arguments:{action}")
            if checks.get("dependency"):
                first, second = checks["dependency"]
                ids = {step["id"]: step for step in steps}
                def ancestors(step, visited):
                    found = set()
                    for identifier in step["after"]:
                        if identifier in visited or identifier not in ids:
                            continue
                        found.add(identifier)
                        found.update(ancestors(ids[identifier], visited | {identifier}))
                    return found
                first_ids = {step["id"] for step in steps if step["action"] == first}
                if not any(first_ids & ancestors(step, {step["id"]}) for step in steps if step["action"] == second):
                    errors.append(f"alternative_{index}:effect_dependency_missing")
        return errors
    except (ValueError, TypeError, KeyError, AttributeError, RecursionError):
        return ["json_not_semantically_inspectable"]


def run(args):
    directory = Path(args.directory).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "responses").mkdir(exist_ok=True)
    (directory / "prompts").mkdir(exist_ok=True)
    bundle = strict_json((directory / "prompt-bundle.json").read_text())
    if bundle.get("origin") != "production GoalPrompt + GoalCatalogue":
        raise ValueError("production_prompt_bridge_required")
    for scenario in bundle["scenarios"].values():
        if hashlib.sha256(scenario["system"].encode()).hexdigest() != scenario["sha256"]:
            raise ValueError("prompt_bundle_hash_mismatch")
    fixture = strict_json(Path(args.cases).read_text())
    cases = fixture["cases"][:args.max_cases]
    if args.case:
        cases = [case for case in fixture["cases"] if case["id"] in args.case]
        if len(cases) != len(set(args.case)):
            raise ValueError("unknown_or_duplicate_case")
    key = read_key(Path(args.key_file))
    catalogue = request_json("/models", timeout=20)
    if "data" not in catalogue:
        raise ValueError("current_model_catalogue_unavailable")
    models = choose_models(catalogue, args.model, args.model_count)
    ledger_file = directory / "ledger.json"
    if ledger_file.exists():
        ledger = strict_json(ledger_file.read_text())
    else:
        ledger = {"version": 1, "createdAt": datetime.now(timezone.utc).isoformat(),
                  "status": "running", "budgetUsd": str(args.budget), "requests": [],
                  "limitations": ["Synthetic capability evidence", "No phone execution", "Single samples, not reliability estimates",
                                   "Python predicates are not production schema validation", "Transport adds provider price caps only"]}
    ledger["modelCatalogueFetchedAt"] = datetime.now(timezone.utc).isoformat()
    current_models = [{name: model.get(name) for name in ("id", "created", "pricing", "supported_parameters", "context_length")} for model in models]
    ledger.setdefault("catalogueSnapshots", []).append({"fetchedAt": ledger["modelCatalogueFetchedAt"], "models": current_models})
    known_models = {model["id"]: model for model in ledger.get("models", [])}
    known_models.update({model["id"]: model for model in current_models})
    ledger["models"] = list(known_models.values())
    atomic_json(ledger_file, ledger)
    spent_or_reserved = sum(Decimal(str(record.get("costUsd") if record.get("costUsd") is not None else record["reservedUsd"])) for record in ledger["requests"])
    completed = {record["id"] for record in ledger["requests"]}
    for model in models:
        price = model["pricing"]
        prompt_price, output_price = Decimal(price["prompt"]), Decimal(price["completion"])
        request_price = Decimal(price.get("request") or "0")
        listed_prices = [price, *price.get("overrides", [])]
        reserved_prompt_price = max(Decimal(pricing.get(name) or "0") for pricing in listed_prices
                                    for name in ("prompt", "input_cache_read", "input_cache_write", "input_cache_write_1h"))
        reserved_output_price = max(Decimal(pricing.get("completion") or "0") for pricing in listed_prices)
        for case in cases:
            identifier = model["id"].replace("/", "__").replace(":", "_") + "--" + case["id"] + "--" + args.label
            if identifier in completed:
                continue
            system = bundle["scenarios"][case["scenario"]]["system"]
            if args.prompt_suffix:
                system += "\n" + Path(args.prompt_suffix).read_text()
            if key in system or key in case["goal"]:
                raise ValueError("credential_must_not_enter_model_context")
            # Conservatively reserve one token per UTF-8 input byte plus chat framing.
            input_upper = len(system.encode()) + len(case["goal"].encode()) + 1024
            reservation = input_upper * reserved_prompt_price + args.max_tokens * reserved_output_price + request_price
            if spent_or_reserved + reservation > args.budget:
                ledger["status"] = "budget_stopped"
                atomic_json(ledger_file, ledger)
                return
            record = {"id": identifier, "case": case["id"], "modelRequested": model["id"], "label": args.label,
                      "scenario": case["scenario"], "promptSha256": hashlib.sha256(system.encode()).hexdigest(),
                      "checksAtDispatch": case.get("checks", {}),
                      "pricingAtDispatch": price,
                      "reservationPrices": {"promptPerToken": str(reserved_prompt_price), "completionPerToken": str(reserved_output_price)},
                      "status": "reserved_before_dispatch", "reservedUsd": str(reservation), "costUsd": None}
            prompt_file = directory / "prompts" / (record["promptSha256"] + ".txt")
            if not prompt_file.exists():
                prompt_file.write_text(system, encoding="utf-8")
            ledger["requests"].append(record)
            atomic_json(ledger_file, ledger)
            spent_or_reserved += reservation
            # Same OpenAI-compatible messages/temperature/max_tokens as AiClient.
            # No structured-output option repairs invalid app output for this baseline.
            body = {"model": model["id"], "messages": [{"role": "system", "content": system}, {"role": "user", "content": case["goal"]}],
                    "temperature": 0.3, "max_tokens": args.max_tokens,
                    "provider": {"max_price": {"prompt": float(prompt_price * 1_000_000), "completion": float(output_price * 1_000_000), "request": float(request_price)}}}
            start = time.monotonic()
            response = request_json("/chat/completions", key, body)
            record["elapsedSeconds"] = round(time.monotonic() - start, 3)
            record["generationId"] = response.get("id")
            record["modelReturned"] = response.get("model")
            if isinstance(response.get("provider"), str):
                record["providerReturned"] = response["provider"]
            if response.get("error_status") or response.get("error"):
                record["status"] = response.get("error_status", "api_error")
                record["httpStatus"] = response.get("http_status")
                if record["httpStatus"] in (401, 402, 403):
                    ledger["status"] = "authorization_or_credit_stopped"
                    atomic_json(ledger_file, ledger)
                    return
            else:
                usage = response.get("usage", {})
                record["usage"] = {name: usage[name] for name in ("prompt_tokens", "completion_tokens", "total_tokens", "cost", "completion_tokens_details") if name in usage}
                if isinstance(usage.get("cost"), (int, float)) and math.isfinite(usage["cost"]) and usage["cost"] >= 0:
                    record["costUsd"] = usage["cost"]
                    spent_or_reserved += Decimal(str(usage["cost"])) - reservation
                choice = (response.get("choices") or [{}])[0]
                content = choice.get("message", {}).get("content")
                record["finishReason"] = choice.get("finish_reason")
                if not isinstance(content, str) or not content:
                    record["status"] = "empty_completion"
                elif key in content:
                    record["status"] = "credential_echo_discarded"
                else:
                    record["status"] = "completed"
                    record["semanticErrors"] = semantic_checks(content, case)
                    atomic_json(directory / "responses" / (identifier + ".json"),
                                {"id": identifier, "goal": case["goal"], "scenario": case["scenario"], "content": content})
            atomic_json(ledger_file, ledger)
            print(json.dumps({"id": identifier, "status": record["status"], "costUsd": record["costUsd"],
                              "elapsedSeconds": record["elapsedSeconds"], "semanticErrors": record.get("semanticErrors")}), flush=True)
    ledger["status"] = "calls_finished_pending_production_validation"
    atomic_json(ledger_file, ledger)


def refresh_metadata(args):
    directory = Path(args.directory).resolve()
    ledger_file = directory / "ledger.json"
    ledger = strict_json(ledger_file.read_text())
    key = read_key(Path(args.key_file))
    count = 0
    for record in ledger["requests"]:
        if count >= 48:
            break
        generation = record.get("generationId")
        if not isinstance(generation, str) or not generation or record.get("generationMetadata"):
            continue
        count += 1
        response = request_json("/generation?" + urllib.parse.urlencode({"id": generation}), key, timeout=8)
        data = response.get("data")
        if not isinstance(data, dict):
            record["metadataStatus"] = response.get("error_status", "unavailable")
        else:
            fields = ("total_cost", "provider_name", "model", "latency", "generation_time", "native_tokens_prompt",
                      "native_tokens_completion", "native_tokens_reasoning", "finish_reason", "is_byok")
            record["generationMetadata"] = {name: data[name] for name in fields if name in data}
            cost = data.get("total_cost")
            if isinstance(cost, (int, float)) and math.isfinite(cost) and cost >= 0:
                record["costUsd"] = cost
            record["metadataStatus"] = "received"
        atomic_json(ledger_file, ledger)
    print(json.dumps({"status": "metadata_refresh_finished", "lookups": count}))


def report(args):
    directory = Path(args.directory).resolve()
    ledger = strict_json((directory / "ledger.json").read_text())
    validation_file = directory / "production-validation.json"
    validation = strict_json(validation_file.read_text()) if validation_file.exists() else {"results": []}
    by_id = {record["id"]: record for record in validation["results"]}
    cases_by_id = {case["id"]: case for case in strict_json((REPO / "tools/goal-eval-cases.json").read_text())["cases"]}
    rows = []
    groups = defaultdict(list)
    for record in ledger["requests"]:
        validated = by_id.get(record["id"])
        record["productionValidation"] = validated
        blocked = record.get("checksAtDispatch", cases_by_id.get(record["case"], {}).get("checks", {})).get("require_blocked_capability")
        if blocked and validated and validated.get("schemaAccepted"):
            if not any(issue.get("id") == blocked and issue.get("state") != "READY"
                       for step in validated.get("assessment", []) for issue in step.get("issues", [])):
                errors = record.setdefault("semanticErrors", [])
                failure = "conditional_prerequisite_not_blocked:" + blocked
                if failure not in errors:
                    errors.append(failure)
        groups[(record["modelRequested"], record["label"])].append(record)
    for (model, label), records in groups.items():
        complete = [record for record in records if record["status"] == "completed"]
        schema = [record for record in complete if (record.get("productionValidation") or {}).get("schemaAccepted")]
        arguments = [record for record in schema if not record["productionValidation"].get("argumentErrors")]
        focused = [record for record in arguments if not record.get("semanticErrors")]
        costs = [Decimal(str(record["costUsd"])) for record in records if record.get("costUsd") is not None]
        rows.append({"model": model, "label": label, "attempted": len(records), "completed": len(complete), "schemaAccepted": len(schema),
                     "canonicalArgumentsAccepted": len(arguments), "focusedFixtureChecksPassed": len(focused),
                     "knownCostUsd": str(sum(costs)), "unresolvedCostRequests": len(records) - len(costs)})
    output = {"version": 1, "status": ledger["status"], "summary": rows, "requests": ledger["requests"],
              "models": ledger["models"], "limitations": ledger["limitations"], "validator": validation.get("validator", "NOT RUN")}
    atomic_json(Path(args.output), output)
    print(json.dumps({"status": output["status"], "summary": rows, "validator": output["validator"]}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    live = commands.add_parser("run")
    live.add_argument("--directory", required=True)
    live.add_argument("--key-file", required=True)
    live.add_argument("--cases", default=str(REPO / "tools/goal-eval-cases.json"))
    live.add_argument("--model", action="append")
    live.add_argument("--model-count", type=int, default=3, choices=range(1, 5))
    live.add_argument("--max-cases", type=int, default=15, choices=range(1, 33))
    live.add_argument("--case", action="append")
    live.add_argument("--max-tokens", type=int, default=4096, choices=range(256, 8193))
    live.add_argument("--budget", type=Decimal, default=Decimal("2"))
    live.add_argument("--label", default="baseline")
    live.add_argument("--prompt-suffix")
    metadata = commands.add_parser("refresh-metadata")
    metadata.add_argument("--directory", required=True)
    metadata.add_argument("--key-file", required=True)
    summary = commands.add_parser("report")
    summary.add_argument("--directory", required=True)
    summary.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        if args.command == "run":
            if not Decimal("0") < args.budget <= Decimal("2") or not IDENTIFIER.fullmatch(args.label):
                raise ValueError("invalid_budget_or_label")
            run(args)
        elif args.command == "refresh-metadata":
            refresh_metadata(args)
        else:
            report(args)
    except (OSError, ValueError, KeyError, TypeError):
        # Do not print exception strings; a supplied path/provider value could be private.
        print(json.dumps({"status": "blocked", "reason": "check_inputs_bridge_and_private_credential_file"}))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
