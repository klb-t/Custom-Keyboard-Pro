# AI task request profiles

Status: source implementation; combined Android verification pending.

The repeated cases are writing, inline suggestions, rewriting, theme/panel generation, image OCR, voice-command interpretation and structured assistant planning. These
already use `AiClient`, but planning previously overrode the token limit with a
literal in its activity. A connection probe is the boundary case: it must retain
its explicit small request limit even when a task profile requests a long response.

`AiRequestTask` is the task registry; `AiTaskParameters` is a sparse typed override.
`aiTaskProfilesJson` persists the same data edited by the Expert JSON control and
the contextual AI settings section. Ownership is application-wide, with no
layout/panel/key override. Basic and Advanced do not show the new controls.

Resolution for tokens is explicit caller limit > task override > task preset > provider chat
parameters > writing default. Temperature uses task > provider >
writing defaults. The UI displays the effective value and source, and lets each
field resume inheritance without deleting another task's settings. Task presets retain the old per-operation limits (planning/OCR 4096, rewrite 800,
theme 900, panel 1200, inline 24, voice command 200); writing retains its configured
default. In particular, a larger writing-provider limit does not silently increase
automatic inline requests. Token limits are
8–8192 and temperature 0–2. Larger requests can increase provider cost; model-specific
limits are not inferred from this local range.

| Rule | Basis | Verification |
|---|---|---|
| Reuse the native client and one canonical settings value | User mechanism/policy/profile direction; existing request consumers | Production callers resolve through `AiConfig.from` |
| Keep task parameters out of layout and key policy | User global/element ownership distinction | Settings schema ownership and scope regression |
| A probe keeps its explicit small limit | Existing connection-test behavior | Caller/task/provider precedence regression |
| Reject ambiguous or invalid profiles before sending | Derived need to preserve reviewed request meaning | Strict JSON, bounds, unknown fields, duplicate names and fail-closed tests |
| Preserve a task's inherited values when its override is removed | User preference for reversible settings and profiles | Reset/inheritance and persistence regression |

Example (no credentials, prompts, endpoint or HTTP body overrides):

```json
{"writing":{"maxTokens":128},"goal_plan":{"maxTokens":4096,"temperature":0.2}}
```

This slice does not implement automatic reasoning effort, JSON response-format
selection, or provider-specific field mappings. Those depend on fresh model metadata
bound to the selected endpoint. Unknown support must remain unknown. No model name
heuristics or claim of a new live model score is introduced here.
