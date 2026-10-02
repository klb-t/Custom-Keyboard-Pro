# IO Matrix and the ecosystem agent

IO Matrix's voice and text inputs use the local `GoalAgent` lifecycle and the same
typed Android performer. The optional adapter in `tools/agent_bridge.py` connects
that proposal format to the existing Loom `AgentRuntime` reference. IO Matrix
remains usable without Loom, Python or an external execution environment.

## Implemented boundary

| Stage | Concrete consumer | Authority and result |
| --- | --- | --- |
| Speech or text | `GoalAssistantActivity` → `GoalAgent.editGoal` | Local user goal; a transcript alone does not run an action |
| External agent proposal | `iomatrix.propose_plan`, a real injected Loom `Tool` | Validates and returns existing `GoalJson` version 1 definitions |
| Portable exchange | Assistant's **Save plan JSON / Import plan JSON** | Import binds definitions to the locally entered goal; no grants or execution state |
| Android review | `GoalJson.read` → `GoalAgent.offerPlans` → `GoalSession` | Current catalogue, arguments, prerequisites, dependencies and step approval |
| Effects and observation | Existing `GoalPerformer` and session receipts | Dispatch remains distinct from verification and user observation |

The tool returns a `proposal` payload containing the canonical JSON document, its
parsed form, the bound goal's SHA-256, `android_effects_dispatched: false` and
`requires_local_review: true`. These declarations describe this proposal tool;
they are not receipts establishing an Android effect. The Android importer reads
the `plan_json` document, not the agent's outer result/journal envelope.

The proposal schema is the existing `GoalJson` projection: version, alternatives,
title, steps, string arguments and dependency IDs. There is no parallel executable
schema. Unknown actions remain explicit capability gaps. API hints remain research
leads. The adapter rejects extra grant/state fields, duplicate decoded JSON names,
cycles, missing dependencies and the existing document/field limits. Its text
limits count UTF-16 units to respect Kotlin's limits. Python validation is an
additional preflight; the actual Android parser and device assessment remain
authoritative.

## Run locally

Validation/export needs only Python's standard library. Supply a goal separately
from the plan; the plan document cannot choose or replace it:

```sh
python3 tools/agent_bridge.py validate \
  --plan app/src/test/resources/agent-bridge/plan-v1.json \
  --goal-file /path/to/reviewed-goal.txt \
  --output /path/to/new-proposal.json
```

For the actual injected agent loop, select a local ChatADHD/Loom checkout that
contains `loom/tools/agent_runtime_v1/runtime.py` and its `jsonschema` dependency:

```sh
python3 tools/agent_bridge.py run \
  --loom-source /path/to/ChatADHD \
  --plan /path/to/proposed-plan.json \
  --goal-file /path/to/reviewed-goal.txt --input-source VOICE \
  --journal /path/to/retained-agent-journal --session chosen-session-id \
  --output /path/to/new-proposal.json
```

The deterministic CLI passes the supplied proposal through the real action and
observation loop. It does not ask a model to invent a plan. A caller can instead
register `proposal_tool(runtime_module, actual_goal)` with that runtime's own
planner. The selected environment must declare `iomatrix.proposals`; unavailable
capabilities never cause a fallback to another environment. The adapter imports
only an explicitly selected local runtime and records hashes of the bridge and
four runtime dependencies. Its recorded source identity is a code-content digest,
not an asserted Git commit.

Enter/review the same goal in the Android assistant, then select **Import plan
JSON** and choose the exported file. The imported alternatives enter the ordinary
review path. Recheck the plan's meaning against the local goal before approval:
portable definitions intentionally omit goal text and cannot authenticate intent.
Android's **Save plan JSON** produces the same definitions for the return direction.

Choose a retained journal location appropriate for the goal's privacy: the runtime
journal contains the goal and proposals. The portable plan omits goal text but its
arguments/reasons may still contain private task data. No credential or hidden app
context is read automatically. Output files use exclusive creation so an existing
proposal is not silently overwritten.

Completed proposals replay through Loom's receipt store without another tool
dispatch. Changing the goal, input source, supplied plan or selected source identity
within the same session is rejected by the existing session binding. A new session
is an explicit choice. This adapter's only dispatch produces data; it never causes
an Android effect.

## Verification and continuation

The reference was read from ChatADHD Git commit
`157762ef60be8b14fe75c6a9c922d6df7ffe97a1`
(`gpt/ecosystem-agent-research-2026-10-01`). It is an experimental local Python
runtime, not a deployed service. IO Matrix does not copy or replace its implementation.

Run the offline guards and optional actual-runtime tests:

```sh
python3 tools/test_agent_bridge.py
IOMATRIX_LOOM_SOURCE=/path/to/selected/ChatADHD python3 tools/test_agent_bridge.py
```

The first invocation visibly skips the three external-runtime tests. On the pinned
reference, **12/12 passed**: nine proposal guards plus three actual-runtime tests
covering proposal roundtrip, replay without a second callback, session binding and
missing capability denial. The shared fixture is
`app/src/test/resources/agent-bridge/plan-v1.json`.
`EcosystemAgentBridgeTest` adds three Robolectric tests using the production
`GoalJson` parser and `GoalAgent` voice review path; its result belongs to the
repository's Android validation gate, not the Python test count.

For Claude: the Android consumer already exists, so continue through `GoalJson`
and `GoalAgent` instead of adding another performer or restoring approval from an
external message. Automated phone/agent transport, authenticated remote sessions,
bidirectional observations, external cancellation and physical-device acceptance
remain unimplemented. File exchange and an in-process Python proposal Tool are the
current interoperability boundary. A runtime's completed proposal receipt must
never be relabelled as a completed phone task.
