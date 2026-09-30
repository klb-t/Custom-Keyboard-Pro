# Capability-first model setup

The setup wizard and AI settings render the same capability setup component. A user
can search `Google Embedding 2` without an account or knowledge of the provider.
The search checks all capability profiles, so an embedding model is found even if
the operation selector still says writing. Selecting an operation or entering a
model triggers a debounced refresh of public model facts. The query is filtered
locally; no typed text or credential is sent to those public sources.

Wizard recommendations carry an explicit operation/provider/model setup target to
AI settings. An embedding recommendation opens that embedding route's account
steps without changing the writing provider. The internal navigation target and
policy survive Activity recreation when returning from a provider's account page;
credentials and free-form user notes are not part of that saved target.

The wizard's device, card, free-tier and training-policy filters use the same
authority for recommendations, model results, the direct provider selector and an
already open setup route. Changing a constraint invalidates an incompatible open
target. Displayed compromises require the user to change the constraint before
selection. Phone-only setup excludes LAN/self-hosted remote endpoints and disables
public model-list requests; provider endpoint overrides are resolved before filtering.
Free-tier filtering still uses the provider's declared tier: model-specific prices
and account entitlement must be checked on the linked provider pages.

Routes keep the model author separate from the provider that actually receives a
request. Bundled/custom routes show whether documentation has been checked and on
which date. Live routes identify their source and fetch date. Neither a catalogue
entry nor a model listing proves the user's inference entitlement.

## Profiles and execution

- `ProviderSpec.setupSteps` supplies account, project, key, billing and documentation
  guidance. Account creation and payment occur on the provider's own HTTPS pages.
- `CapabilitySpec` supplies endpoint, model list, default parameter values, parameter
  choices, optional input preparation, documentation and verification date.
- `ProviderProfile.capabilities[operation]` stores that route's model, parameters,
  selected state and successful sample timestamp. Chat model and key remain separate.
- `CapabilitySetup.select` atomically changes only the intended capability. Existing
  chat/completion/dictation/OCR/speech settings are updated for their consumers.
  Other described capabilities retain their route in the same profile map; the
  conversion runner now prefers that explicitly selected route over its old first-provider fallback.
- Dictation, OCR, completion, speech and conversion adapters resolve saved provider
  endpoint overrides and capability parameters through `ProviderProfiles`.
- `CapabilitySetup.runSelected` executes a saved described route using the existing
  `CallEngine`. This is available to embedding consumers; a downstream vector index
  or media semantic-search UI is not part of this change.
- Selecting a route does not enable continuous/background sending. The embedding
  sample test sends only the displayed sample and requires a user action.

Google Gemini direct and OpenRouter both have documented Gemini Embedding 2 routes.
Their text route returns a numeric vector; the sample test validates a nonempty
array of finite numbers. The catalogue does not imply that this text route sends
images/audio/video/PDF, although the Google model supports those input modalities.
The input-preparation template and parameters are data, so task prefixes and output
dimensions can be edited without changing the transport engine.

## Corrections and boundaries

Gemini Embedding 2 uses task instructions in text rather than the `taskType` field
used by Embedding 001. Dimensions are adjustable (Google documents 128–3072, with
768/1536/3072 recommended). Changing embedding models requires rebuilding indexes
because their vector spaces are incompatible.

Model discovery uses explicit capability metadata or a capability-scoped endpoint.
It does not mix every chat model into the embedding selector. Authentication success
and inference success are distinct. An unknown privacy policy does not satisfy a
no-training constraint. A local-only constraint never silently relaxes to a hosted
service, nor does a missing operation silently become chat. A self-hosted server on
another machine is still outside the phone.

Public model discovery is limited to the sources listed in `factsources.json`.
OpenRouter documents authentication for its embeddings model-list endpoint, so that
source is marked as requiring a key. Before signup the documented bundled routes
remain available; after entering a key the user can refresh the dedicated list.
There is no claim to discover every provider on the internet.

Credentialed HTTP requests do not follow redirects. A response-supplied polling URL
must share the original request's origin before credentials are attached. CDN result
downloads carry no provider credentials and may redirect. URLs containing query keys
are not written to CallEngine logs. String payloads stay strings even when their
contents look like numbers or booleans; numeric parameters retain numeric JSON types.

## Sources checked 2026-09-27

- https://ai.google.dev/gemini-api/docs/embeddings
- https://ai.google.dev/gemini-api/docs/api-key
- https://openrouter.ai/google/gemini-embedding-2
- https://openrouter.ai/docs/api/api-reference/embeddings/list-all-embeddings-models

`CapabilitySetupTest` covers the fresh-account search/route flow, isolation between
capabilities, old credential migration, explicit key clearing, model-capability
filtering, current Embedding 2 request shape, profile round trips, text type
preservation and same-origin polling. Network inference is not claimed to have been
validated without a user credential.

`SetupJourneyTest` covers target restoration, strict bounded navigation state,
policy retention, shared route filters, rejected stale targets and LAN exclusion.
The Compose settings regression opens the exact embedding account steps and
removes them when the user switches to phone-only setup. These checks do not
establish real browser/account creation or live embedding entitlement.

Generic Android speech has unknown processing locality. A distinct on-device-only
profile is offered only when Android 12+ reports an on-device recognizer; both its
initial and retry paths use `createOnDeviceSpeechRecognizer` and fail closed.
LiteLLM and Ollama are not treated as proof of local inference merely because their
API endpoint is localhost. Runtime profile endpoint overrides are resolved before
local-only recommendations. API key/endpoint edits invalidate capability test
status; asynchronous test results are matched against their original request snapshot.
Chat setup exposes temperature and token limit and routes those through AiConfig;
arbitrary unsupported chat JSON parameters are not presented as working controls.
