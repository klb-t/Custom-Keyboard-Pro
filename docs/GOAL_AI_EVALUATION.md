# Real model evaluation of goal proposals

The opt-in evaluator sends **synthetic Polish goals and the production action
projection**, not screen text, clipboard history, account data or a real device
snapshot. The credential belongs in an owner-only file outside Git, never in a
fixture, prompt, command argument value, report or configuration committed here.
The evaluator reads a file path; it never prints the key or provider error bodies.

Checkpoint on 2026-09-30: the bridge executed successfully with the real Android
SDK/Robolectric API 33 and exported 50 production actions and both prompts. The
official public model catalogue returned 464 records. The evaluator's Python
fixture/reporting smoke checks, seven offline guard tests and shell syntax checks
passed. The guard tests use a fake transport solely to check budget stopping,
private-file handling and suppression of duplicate potentially billed dispatch;
they are not model results. **No completion
request has run yet**: the old encrypted upload's matching private RSA identity was
not recoverable. A fresh public-key handoff was prepared; live evaluation requires
the resulting encrypted upload. This is a credential blocker, not a network,
fixture or schema-validation success disguised as a real model result.

`GoalEvaluationBridgeTest` exports the actual `GoalPrompt.system` and
`GoalCatalogue.actions` under two declared synthetic prerequisite scenarios.
After model calls it validates their unchanged content with the actual
`GoalJson.read`, `GoalAction.invalidArguments` and `GoalAssessment.step`. The bundle
includes structured argument rules, inter-argument constraints, base and conditional
prerequisites. The latter ensures a `phone_info section=usage` variant retains its
special-access requirement while ordinary memory/device snapshots stay independent.
Python's separate fixture
predicates check the requested effect, expected arguments, dependency order,
integration gaps and unwanted effects. Those predicates are not a second claim of
Kotlin parser compatibility and cannot assess every arbitrary goal.

## Run

Use the repository's Android/Gradle toolchain. Serialize Gradle with other builds.
`IO_GRADLE_BIN` may point to its installed binary.

```sh
tools/goal-eval-bridge.sh /absolute/evaluation-directory
python tools/evaluate-goal-ai.py run \
  --directory /absolute/evaluation-directory \
  --key-file /absolute/private/openrouter.key \
  --budget 2
tools/goal-eval-bridge.sh /absolute/evaluation-directory
python tools/evaluate-goal-ai.py refresh-metadata \
  --directory /absolute/evaluation-directory \
  --key-file /absolute/private/openrouter.key
python tools/evaluate-goal-ai.py report \
  --directory /absolute/evaluation-directory \
  --output /absolute/sanitized-report.json
```

Run the inexpensive offline evaluator guards separately with
`python tools/test-goal-ai-evaluator.py`.

The first bridge run writes `prompt-bundle.json`; the second writes
`production-validation.json` for the received outputs. No model output is repaired
or stripped of code fences to make the app parser pass. Reports separate completed
calls, schema acceptance, canonical argument acceptance and focused fixture checks.
Review raw synthetic responses before drawing conclusions about planning quality.

The HTTP body follows the existing OpenAI-compatible `AiClient` messages,
temperature (0.3) and goal host output budget (4096). It adds only provider price
caps to bound evaluation spend. It deliberately does not enable `response_format`
or tools absent from the app's baseline request. HTTP uses normal certificate
validation, rejects redirects, limits response size and uses a timeout. Calls do
not execute phone effects or install adapters.

## Selection, cost and resumability

The current official `/models` response supplies model identifiers, pricing,
modalities and supported parameters. Defaults select three distinct authors among
recent, positively priced text models compatible with the baseline parameters.
`--model` selects explicit identifiers only if present in that current catalogue;
no guessed identifier or silent fallback is accepted. `--case` can run a small
subset; `--label` and `--prompt-suffix` permit a recorded prompt comparison without
silently altering production source.

The ledger is saved before dispatch. Each request reserves a conservative input
byte/token bound plus the configured maximum completion cost, using matching
provider price caps. The reservation includes the highest published cached-write
and override rates rather than assuming the lowest headline rate. Reported
`usage.cost` replaces the reservation. Unknown cost,
timeout or interrupted dispatch retains its reservation; the evaluator does not
automatically retry a potentially billed request. Resuming the same directory
skips every existing request identifier. The cumulative default limit is USD 2,
not USD 2 per model or prompt label. Reported provider usage is exact when returned;
the reservation is explicitly not a claim about the bill. The optional bounded
`refresh-metadata` command reads generation metadata for exact reported cost,
provider and native token counts; it does not make additional completions.

The 15 fixtures cover volume, a missing settings grant, developer setup,
unidentified processes/apps, an unknown Wi-Fi bulb, incomplete capture/export,
quoted hostile instructions, diagnostics without changes, unavailable coordinates,
verified dependency order, Bluetooth restrictions, usage-specific special access,
consistent automatic-rotation arguments and selection before sensor monitoring.
These are small regression
probes, not a statistical reliability benchmark. Single samples do not establish
robustness. Live calls establish remote planning behavior only; Android permission,
assistant role, app execution and physical-device results need separate acceptance.

## Official API references

Checked 2026-09-30:

- [Model catalogue](https://openrouter.ai/docs/api/api-reference/models/list-all-models-and-their-properties)
- [Chat request and usage response](https://openrouter.ai/docs/api/api-reference/chat/create-a-chat-completion)
- [Provider price caps](https://openrouter.ai/docs/guides/routing/provider-selection#max-price)
- [Generation metadata and exact reported cost](https://openrouter.ai/docs/api/api-reference/generations/get-request-&-usage-metadata-for-a-generation)

An evaluator framework, fixture smoke check or successful catalogue request is not
a live model evaluation. A completed report must contain actual dispatched
generation identifiers and provider usage, and must say when cost or production
validation remains unresolved.
