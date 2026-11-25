# Model limits: automatic detection and learning

Branch `model-limits-detect`, based on `origin/batch/surface-fix` at
`0729dabcfb01b052c77ec6d5af3e0a82aab8a9f3`. Implementation HEAD before this
report: `3fcc4c98`. Nothing was merged, tagged, released, or version-bumped.

The acceptance target was that a user can add a normal provider and model and
Rivet fills both the context window and the maximum output itself. The resolver
now does that for the known providers in the bundled table, for providers that
report model metadata, and for limits learned from a provider error. The safe
fallback is 32K, not the old 256K guess.

## 1. Resolution order and the default

Commits `53c1be28`, `6e8fa6f1`, `3fcc4c98`.

Files · `provider/ModelLimits.kt`, `provider/ProviderConfig.kt`,
`agent/ContextBudget.kt`, `agent/ContextBudgetTest.kt`.

The resolver applies a local hard ceiling, then a manual value, a learned cap,
provider metadata, the known-model table, an observed usage floor, and finally
32,768 tokens. A learned cap is scoped to provider id, endpoint and model and
is ignored after expiry. Manual output wins for planning; the wire value is
clamped to a provider or learned maximum so the user is not sent a request the
provider already rejected. Manual context below a local ceiling is allowed;
above it is clamped with a visible warning.

The default reserve is proportional: `min(16K, max(2K, window/4))` when the
window is only assumed. On 32K that gives 24,576 allowed input tokens,
21,504 compact-at, and 18,432 compact-to. A learned prompt cap is applied after
the reserve so it is not subtracted twice.

## 2. Provider metadata and the bundled table

Commits `53c1be28`, `ffc91952`.

Files · `provider/OpenAiCompatibleClient.kt`, `provider/AnthropicClient.kt`,
`provider/GeminiClient.kt`, `provider/ModelIdNormalizer.kt`,
`provider/KnownModelLimits.kt`, `app/src/main/resources/model-limits.tsv`.

Anthropic's `max_input_tokens` and `max_tokens`, Gemini's
`inputTokenLimit`/`outputTokenLimit`, and the OpenAI-compatible spellings
`context_length`, `context_window`, `contextLength`, `max_context_length`,
`max_model_len`, `max_completion_tokens`, `maxOutputTokens`,
`maxCompletionTokens`, and `outputTokenLimit` are parsed. OpenRouter's
`top_provider` values still win within a listing because they describe the
specific route. Generic gateways only get a completion cap when the user set
it or an error taught it; documented first-party clients also send provider
metadata. The OpenRouter request ceiling is unchanged for unknown routes.

The table is a 5,188-row models.dev snapshot from 2026-11-25. It is loaded from
resources, not fetched at runtime. `ModelIdNormalizer` strips GGUF quantisation
for local ids, one owner prefix, a routing suffix after `:`, Fireworks'
`accounts/fireworks/models/` path, and date stamps. It deliberately leaves
`-mini`, `-flash`, `-pro`, and `-32k` alone. Ambiguous normalised stems return
no table answer rather than guessing. `scripts/generate_model_limits.py`
regenerates the table; models.dev is MIT-licensed and the notice and licence are
in `THIRD_PARTY_NOTICES.md` and `licenses/models.dev-MIT.txt`.

## 3. Error learning

Commit `6e8fa6f1`.

Files · `provider/ModelLimitLearningPolicy.kt`, `provider/Http.kt`,
`provider/ProviderError.kt`, `agent/AgentLoop.kt`,
`chat/ChatViewModel.kt`, `chat/ProviderRuntimeSource.kt`,
`subagent/WorkerRunner.kt`, `storage/ProviderStore.kt`.

Numeric context and output limits are parsed from HTTP 400/413/422 bodies and
structured codes. Byte limits, rate limits, and unrelated 400s are rejected.
A context observation must be between 512 and 10M, must be below the current
estimate, and must be plausible against the attempted token count. The first
observation is provisional for 24 hours; an identical second error confirms it
for 90 days. Output learning is downward-only. A successful request that
reports more input tokens than a learned cap clears that cap and records the
observed floor. Nothing overwrites a manual value.

The same-turn path stores a prompt cap in `ContextPreparation` before the loop
compacts again. Output learning is persisted after the error and used on the
next turn; it does not retry generation in the same turn. The editor exposes
"Reset learned limit" whenever a learning exists and persists the reset
immediately.

## 4. Local models

Commit `fedd6a2a`.

Files · `local/GgufMetadata.kt`, `local/LocalModelManager.kt`,
`local/LocalProviderClient.kt`, `local/GgufMetadataTest.kt`,
`local/LocalLimitMergeTest.kt`.

GGUF v2/v3 metadata is read up to 32 MB. The reader takes the architecture's
`*.context_length`, falling back to the rope scaling original context, and
returns null on malformed or truncated input. The effective local context is
the smaller of the training context and the device budget. Old installs are
parsed lazily when first needed. New installs get a detected
`ModelContextLimit` instead of a manual value, and `syncProviders` merges
manual values, reasoning, headers and learning forward rather than overwriting
them. A manual lower context is passed to the native load as the requested
size; it cannot raise the GGUF/device ceiling.

## 5. Editor and meter

Commit `f1cb6a4b`.

Files · `ui/provider/ProviderEditor.kt`, `ui/provider/ProviderEditorState.kt`,
`ui/provider/ProvidersViewModel.kt`, `ui/chat/ContextMeter.kt`,
`subagent/SubagentPool.kt`.

The editor shows the resolved source (manual, learned, provider, known model,
lower bound, default, local) under both fields. Detected output is shown as
detected but is not described as a sent request cap. Endpoint changes clear
detected and learned data. Saved providers whose model has no known limit are
auto-detected when the model list is fetched; a normalised match keeps the
user's typed model id. The meter says "assumed context window" when the number
came from the fallback rather than a measurement. Subagent pool entries keep
the live config's limit instead of overriding it with the snapshot taken when
the model was ticked.

## Tests actually run

- `:app:compileDebugKotlin` — **BUILD SUCCESSFUL** on the final implementation
  (offline Gradle 8.13, JDK 17).
- Manual JVM JUnit run of the touched suites — **100 tests, 0 failures**. The
  classes were `ModelLimitsTest`, `ModelLimitEvidenceTest`,
  `KnownModelLimitsTest`, `ProviderConfigLimitsTest`,
  `ModelLimitLearningPolicyTest`, `GgufMetadataTest`, `LocalLimitMergeTest`,
  `ModelLimitLoopTest`, `ContextBudgetTest`, `ProviderConfigTest`,
  `HttpErrorTest`, `ProviderErrorClassificationTest`, and
  `SubagentPoolTest`.
- `AgentLoopTest` — **60 of 66 passed** in the same harness. The 6 failures all
  fail at `AgentToolExecutor` static initialisation with
  `NoClassDefFoundError: android/content/pm/PackageManager$NameNotFoundException`;
  the manual JVM classpath has no Android runtime. The new `ModelLimitLoopTest`
  covers the overflow/retry and output-observer paths and passed 3/3.
- Python generator tests — **100 tests, 0 failures** via
  `python3 -m unittest discover -s scripts -p 'test_*.py'`.
- `:app:processDebugJavaRes` — **BUILD SUCCESSFUL**; the 211 KB
  `model-limits.tsv` was present in the processed Java resources.

I could not run the full Gradle unit suite offline: `:app:compileDebugUnitTestKotlin`
failed to resolve `androidx.compose.ui:ui-test-junit4`, Robolectric,
MockWebServer, `okhttp-tls`, and `kotlinx-coroutines-test`. That is why the
suites above were compiled and run directly with the cached production classes.
No GitHub Actions run has been started from this machine.

The new tests exercise APIs that do not exist on the parent, so the red side is
a parent compile failure rather than a completed failing test. I did not claim
a parent test run I could not perform.

## Honest gaps

- No real provider was called. Limit parsing, request shapes, and learning are
  covered by local tests, not live Anthropic/Gemini/DeepSeek/Mistral/Groq/
  Together/OpenRouter traffic.
- No Android device or Robolectric run. The editor layout, the clickable reset,
  and the GGUF read on a real downloaded model remain device/CI verification.
- `ContextPreparationTest` is Robolectric-based and did not compile in the
  manual harness; it is left for CI.
- Provider input-limit kind is stored (`InputOnly` for Anthropic/Gemini) but
  planning still treats it as a total window. That is deliberately
  conservative: it may reserve output space twice rather than send a request
  that overflows.
- Output-limit learning is next-turn only; there is no same-turn generation
  retry after `OutputLimitExceeded`.
- Output limits carried inside a streamed provider error are not learned; only
  HTTP/body evidence is.
- The table is a point-in-time snapshot. A model newer than 2026-11-25 falls
  back to provider metadata, usage observations, or 32K.
- The GGUF parser is tested with synthetic v2/v3 headers. A real model whose
  metadata sits beyond the 32 MB header cap will be treated as having no
  training ceiling and fall back to the device budget.
