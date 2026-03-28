# Formally Verify Trading Submit Policy and Pre-Submit Normalization

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The primary live `bd` issue for this work is `hyperopen-egx2`.

## Purpose / Big Picture

Hyperopen already has machine-checked proof surfaces for vault transfer preview and for raw order-request construction, but it still does not have a proof surface for the logic that decides whether a trade submission is allowed in the first place. Today `submit-policy` in `/hyperopen/src/hyperopen/state/trading.cljs` still mixes pure pre-submit normalization, validation, request-availability checks, and reason precedence in ordinary ClojureScript. After this work, a contributor will be able to run a dedicated Lean-backed surface for trading submit readiness, regenerate deterministic vector fixtures, and prove through ordinary repo tests that `effective-margin-mode`, `prepare-order-form-for-submit`, `validate-order-form`, and `submit-policy` still match the verified model.

This is an internal correctness feature, not a UI feature. The visible result is a trustworthy formal workflow for the missing half of item 2: one command verifies the submit-policy proof surface, existing order-request Lean surfaces remain green, and ordinary test suites prove that the real ClojureScript submit-gating logic still conforms to the verified model.

## Progress

- [x] (2026-03-27 20:40 EDT) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md` before drafting this plan.
- [x] (2026-03-27 20:40 EDT) Audited the current item-2 kernels in `/hyperopen/src/hyperopen/state/trading.cljs`, `/hyperopen/src/hyperopen/domain/trading/validation.cljs`, `/hyperopen/src/hyperopen/domain/trading/core.cljs`, `/hyperopen/src/hyperopen/domain/trading/market.cljs`, and `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`, plus the current Lean surfaces and their CLJS conformance tests.
- [x] (2026-03-27 20:38 EDT) Created and claimed `bd` issue `hyperopen-egx2` for the missing submit-policy formalization track.
- [x] (2026-03-27 20:40 EDT) Created this active ExecPlan and froze scope to submit-policy, pre-submit normalization, and validation soundness only. Existing raw order-request Lean surfaces remain in scope as dependencies but are not to be replaced or widened into a second submit-policy implementation.
- [x] (2026-03-27 21:06 EDT) Added the new Lean-backed formal surface `trading-submit-policy` across `/hyperopen/tools/formal/core.clj`, `/hyperopen/tools/formal/lean/Hyperopen/Formal/Common.lean`, `/hyperopen/tools/formal/lean/Hyperopen/Formal.lean`, `/hyperopen/tools/formal/README.md`, `/hyperopen/docs/tools.md`, and `/hyperopen/dev/formal_tooling_test.clj`.
- [x] (2026-03-27 21:08 EDT) Extracted the pure submit-policy kernel seam into `/hyperopen/src/hyperopen/trading/submit_policy.cljs` and rewired `/hyperopen/src/hyperopen/state/trading.cljs` into a compatibility facade over that kernel.
- [x] (2026-03-27 21:10 EDT) Added `/hyperopen/tools/formal/lean/Hyperopen/Formal/TradingSubmitPolicy.lean`, synced `/hyperopen/tools/formal/generated/trading-submit-policy.edn` and `/hyperopen/test/hyperopen/formal/trading_submit_policy_vectors.cljs`, added `/hyperopen/src/hyperopen/schema/trading_submit_policy_contracts.cljs`, and landed vector-backed CLJS conformance coverage in `/hyperopen/test/hyperopen/state/trading/submit_policy_formal_conformance_test.cljs` plus new human-readable anchors in the existing trading tests.
- [x] (2026-03-27 21:18 EDT) Ran `npm run formal:sync -- --surface trading-submit-policy`, `npm run formal:verify -- --surface trading-submit-policy`, `npm run formal:verify -- --surface vault-transfer`, `npm run formal:verify -- --surface order-request-standard`, `npm run formal:verify -- --surface order-request-advanced`, `npm run test:formal-tooling`, `npm test`, `npm run test:websocket`, `npm run check`, and `npm run lint:delimiters -- --changed`. All passed after installing local npm dependencies with `npm ci`.

## Surprises & Discoveries

- Observation: the existing Lean order-request surfaces intentionally stop at raw builder behavior, not submit gating.
  Evidence: `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Advanced.lean` proves `twap_suborder_too_small_builder_still_emits_request`, which is correct for the raw builder but explicitly not the same as submit-time validation.

- Observation: `prepare-order-form-for-submit` contains high-value business rules that are still only ordinary ClojureScript behavior.
  Evidence: `/hyperopen/src/hyperopen/state/trading.cljs` fills market orders from live market data and injects fallback prices for blank limit-like orders, but the current tests only pin representative examples rather than a generated proof corpus.

- Observation: the validation matrix is broader than the current direct validation tests.
  Evidence: `/hyperopen/src/hyperopen/domain/trading/validation.cljs` distinguishes market, limit, stop-market, stop-limit, take-market, take-limit, scale, TWAP, and enabled TP/SL trigger rules, while the current focused tests emphasize limit, market, stop-market, scale, and TWAP more heavily than the full stop/take and TP/SL-trigger matrix.

- Observation: the repo formal-tool wrapper currently knows only three surfaces.
  Evidence: `/hyperopen/tools/formal/core.clj`, `/hyperopen/tools/formal/README.md`, and `/hyperopen/docs/tools.md` list only `vault-transfer`, `order-request-standard`, and `order-request-advanced`.

## Decision Log

- Decision: add a new formal surface named `trading-submit-policy` instead of widening `order-request-standard` or `order-request-advanced`.
  Rationale: request construction and submit gating are intentionally different contracts in this repository. The existing Lean surfaces already prove raw request construction. Folding submit-policy into those files would blur the line between “can the builder serialize a request?” and “is the UI allowed to submit now?”
  Date/Author: 2026-03-27 / Codex

- Decision: introduce or extract a pure submit-policy kernel in `/hyperopen/src/**` and keep `/hyperopen/src/hyperopen/state/trading.cljs` as the compatibility facade.
  Rationale: the proof target should be a small pure seam with explicit inputs and outputs, not the entire application state map. Preserving the public API while moving the business kernel into a narrower pure module keeps the proof surface stable and reviewable.
  Date/Author: 2026-03-27 / Codex

- Decision: treat request well-formedness as a dependency satisfied by the existing order-request Lean surfaces, and make the new proof surface responsible for preparation, validation, and reason precedence.
  Rationale: that separation matches the current architecture. `submit-policy` should prove when a request must exist and when submission must be blocked; request serialization itself is already modeled elsewhere.
  Date/Author: 2026-03-27 / Codex

- Decision: keep JavaScript floating-point parity out of Lean and prove a clean arithmetic model plus generated-vector conformance instead.
  Rationale: this follows the existing vault and order-request proof pattern and keeps the proof surface stable even though production code still uses JavaScript numbers internally.
  Date/Author: 2026-03-27 / Codex

- Decision: add a dedicated contract namespace for generated submit-policy fixtures if the existing schemas are too VM-shaped for the proof surface.
  Rationale: `/hyperopen/src/hyperopen/schema/order_form_contracts.cljs` validates VM-facing submit maps, but the proof surface needs contracts for generated preparation results, validation error vectors, and submit-policy results that still include `:request` and `:identity`.
  Date/Author: 2026-03-27 / Codex

## Outcomes & Retrospective

The implementation landed. Hyperopen now has a fourth formal surface, `trading-submit-policy`, backed by `/hyperopen/tools/formal/lean/Hyperopen/Formal/TradingSubmitPolicy.lean`, a committed generated vector bridge under `/hyperopen/test/hyperopen/formal/trading_submit_policy_vectors.cljs`, and ordinary CLJS conformance tests that pin the pure submit kernel against those vectors. The missing half of item 2 is now covered at the repo-local tooling level instead of living only in example-based submit-policy tests.

The production seam also got smaller and cleaner. The pure kernel now lives at `/hyperopen/src/hyperopen/trading/submit_policy.cljs`, while `/hyperopen/src/hyperopen/state/trading.cljs` remains the public facade that gathers app-db context, normalizes forms, and delegates to the pure seam. That lowered the submit-path coupling without changing the public API shape consumed elsewhere in the app.

One practical surprise during validation was environmental, not behavioral: this worktree started without `node_modules`, so the required repo gates could not run until `npm ci` restored the local toolchain. After that install, the full validation stack passed cleanly, including the new formal surface, the old formal surfaces, `npm test`, `npm run test:websocket`, and `npm run check`.

## Context and Orientation

The completed proof work for `/hyperopen/docs/exec-plans/completed/2026-03-26-formal-verification-vault-transfer-and-order-requests.md` already covers two things. First, `/hyperopen/tools/formal/lean/Hyperopen/Formal/VaultTransfer.lean` models money-moving vault transfer preview and emits committed vectors. Second, `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Standard.lean` and `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Advanced.lean` model raw request construction for standard, scale, and TWAP order requests and emit committed vectors consumed by ordinary ClojureScript tests.

That earlier work deliberately stopped before submit gating. In Hyperopen, a “raw builder observational contract” means “what request shape or nil does the builder return when called directly?” The existing Lean order-request surfaces prove that contract. A “submit-ready contract” is stricter: it means the form has already passed normalization, market-price preparation, fallback-price insertion, validation, and request-availability checks, and the user is not blocked by spot-read-only, spectate mode, or agent readiness. Item 2 is incomplete because only the raw builder is modeled today.

The current production behavior lives mostly in `/hyperopen/src/hyperopen/state/trading.cljs`. `effective-margin-mode` decides whether `:cross` must collapse to `:isolated` when market metadata forbids cross margin. `prepare-order-form-for-submit` normalizes the form, injects market price for market orders, and fills blank limit-like prices from the current reference-price policy. `submit-policy` applies reason precedence for `:submitting`, `:spectate-mode-read-only`, `:spot-read-only`, `:market-price-missing`, `:validation-errors`, `:request-unavailable`, and `:agent-not-ready`. The state facade delegates to `/hyperopen/src/hyperopen/domain/trading/validation.cljs` for validation, `/hyperopen/src/hyperopen/domain/trading/market.cljs` for reference-price and canonical price policy, and `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs` for raw request construction.

The repository already has ordinary test anchors for this area. `/hyperopen/test/hyperopen/state/trading/identity_and_submit_policy_test.cljs` covers selected examples for cross-margin normalization, prepare-submit behavior, and submit-policy branches. `/hyperopen/test/hyperopen/state/trading/validation_and_scale_test.cljs` covers much of the validation matrix and scale/TWAP arithmetic. `/hyperopen/test/hyperopen/state/trading/market_summary_test.cljs` covers helper pricing and fallback behavior. Those tests matter even after Lean lands; the generated proof vectors are supposed to strengthen these suites, not replace human-readable regression tests.

The formal-tool wrapper is under `/hyperopen/tools/formal/**`. It currently parses supported surfaces in `/hyperopen/tools/formal/core.clj` and `/hyperopen/tools/formal/lean/Hyperopen/Formal/Common.lean`, routes verify and sync in `/hyperopen/tools/formal/lean/Hyperopen/Formal.lean`, writes manifests under `/hyperopen/tools/formal/generated/`, and writes transient generated sources under `/hyperopen/target/formal/`. Any new submit-policy surface must fit that existing pattern and must not break `vault-transfer`, `order-request-standard`, or `order-request-advanced`.

The proof target for this plan is intentionally narrow. It includes effective margin-mode normalization, market-price preparation, fallback-price preparation for limit-like orders, type-specific validation soundness, submit required-field derivation if needed for policy output, and submit-policy reason precedence. It does not include view-only order-form ownership rules, effect-order validation, or portfolio metrics gating. Those remain separate follow-on tracks.

## Plan of Work

### Milestone 1: Add a dedicated formal surface for submit gating without disturbing the existing order-request models

This milestone creates the repo-local tooling boundary for the missing proof surface. Extend `/hyperopen/tools/formal/core.clj`, `/hyperopen/tools/formal/README.md`, `/hyperopen/docs/tools.md`, `/hyperopen/tools/formal/lean/Hyperopen/Formal/Common.lean`, and `/hyperopen/tools/formal/lean/Hyperopen/Formal.lean` so the wrapper recognizes a fourth surface id, `trading-submit-policy`. The generated manifest should live at `/hyperopen/tools/formal/generated/trading-submit-policy.edn`, the transient export should live at `/hyperopen/target/formal/trading-submit-policy-vectors.cljs`, and the checked-in bridge should live at `/hyperopen/test/hyperopen/formal/trading_submit_policy_vectors.cljs`.

Do not widen or rename the existing order-request surfaces in this milestone. The architectural goal is to add one more proof boundary that depends on the existing ones conceptually, not to merge everything into a monolithic trading proof. Update `/hyperopen/dev/formal_tooling_test.clj` if needed so surface parsing, sync/verify behavior, and stale-vector detection cover the new surface too.

Milestone 1 is complete when `formal:verify` and `formal:sync` both recognize `trading-submit-policy`, the wrapper docs mention it explicitly, and the existing three surfaces still behave exactly as before.

### Milestone 2: Extract a pure submit-policy kernel from the current state facade

This milestone makes the business logic formally tractable. Introduce a pure production seam under `/hyperopen/src/hyperopen/domain/trading/submit_policy.cljs` or another comparably narrow pure trading namespace. That new kernel should own the rules that are currently embedded in `/hyperopen/src/hyperopen/state/trading.cljs`: effective margin-mode normalization, preparation of a submit-ready form, type-specific validation orchestration, and submit-policy reason selection. Keep `/hyperopen/src/hyperopen/state/trading.cljs` as the public compatibility facade that gathers context from app state and delegates into the new pure seam.

Define the new seam so Lean can model it directly. That means the kernel should take an explicit reduced context map rather than the whole app state. The reduced context should contain only the fields that actually affect item-2 behavior: active asset identity if needed for request availability, reduced market metadata, orderbook/reference-price inputs, whether spot mode is active, whether spectate mode blocks mutations, whether submission is already in progress, and whether the agent is ready. If an input is derivable outside the kernel and not part of the business rule itself, derive it in the facade and pass the plain result in.

Milestone 2 is complete when `state/trading.cljs` still exports the same public functions, but the core business logic is small enough to point at one pure namespace as the canonical proof target.

### Milestone 3: Model the submit-ready contract in Lean and emit deterministic vectors

This milestone adds `/hyperopen/tools/formal/lean/Hyperopen/Formal/TradingSubmitPolicy.lean` as the executable reference model for the missing item-2 rules. The Lean model must prove at least these invariants:

`EffectiveMarginModeSound`: cross margin never survives when market metadata forbids it.

`PrepareOrderFormMarketPriceSound`: if a market order returns `:market-price-missing? false`, the prepared form carries a positive canonical price string.

`PrepareOrderFormFallbackPriceSound`: if a limit-like order has a blank price and the domain can derive a fallback price, the prepared form includes that canonical fallback price.

`ValidationSoundByType`: market requires size; limit requires size plus price; stop/take market requires size plus trigger; stop/take limit requires size plus price plus trigger; scale requires valid endpoints, count, skew, and endpoint minimum notionals; TWAP requires runtime bounds and minimum suborder notional; enabled TP and SL require triggers.

`SubmitPolicyReasonSound`: in submit mode, `reason = nil` if and only if spectate mode is not blocking, the market is not spot-read-only, the market price is available when required, validation errors are empty, a request exists, and the agent is ready.

The Lean surface should reuse existing request-construction proofs by translation, not by duplication. If the easiest path is to encode a reduced “request available?” predicate and separately prove that it corresponds to the existing order-request surfaces for relevant forms, do that explicitly and record the mapping in this plan. The surface should emit at least three vector corpora into the generated CLJS bridge: preparation vectors, validation vectors, and submit-policy vectors. Use exact arithmetic or exact string encodings inside Lean; keep JavaScript-specific float quirks out of the proof and pin production parity through the generated vectors instead.

Milestone 3 is complete when `formal:sync` deterministically regenerates `/hyperopen/test/hyperopen/formal/trading_submit_policy_vectors.cljs`, `formal:verify` fails on stale generated output, and the Lean module contains representative theorems for the invariants above.

### Milestone 4: Land CLJS conformance tests and close the remaining validation gaps

This milestone ties the proof model back into normal repo validation. Add or extend CLJS tests so the generated vectors are exercised against the real production functions. The conformance layer should compare the generated expected outputs with the actual outputs of the pure production submit-policy kernel and, where appropriate, the public state facade.

The minimum required anchors are: submit-policy conformance in `/hyperopen/test/hyperopen/state/trading/identity_and_submit_policy_test.cljs` or a new focused submit-policy test namespace; validation-matrix conformance in `/hyperopen/test/hyperopen/state/trading/validation_and_scale_test.cljs` or a new focused validation namespace; and direct coverage for the preparation fallback path that is currently only indirectly tested. Add any missing example-based tests that make the modeled rules readable to humans, especially for blank limit-like price fallback, enabled TP/SL missing triggers, and stop/take validation branches that are underrepresented today.

If the generated vectors need runtime shape assertions, add `/hyperopen/src/hyperopen/schema/trading_submit_policy_contracts.cljs` and companion tests so the conformance harness can reject malformed vector output loudly. Keep these contracts proof-surface-specific; do not overload the VM-facing order-form schema with proof-only responsibilities.

Milestone 4 is complete when the generated vectors are consumed by ordinary CLJS tests, the previously underpinned branches have human-readable regression anchors, and the proof surface is no longer isolated from the normal test suite.

### Milestone 5: Validate the full proof pipeline and keep old proof surfaces green

This milestone is the close-out. Run the new submit-policy surface through `formal:sync` and `formal:verify`, re-run the existing order-request surfaces so the new work does not disturb them, and then run the standard repository gates. If the implementation introduces a new pure production seam or new test namespaces, regenerate the test runner if needed and keep the active ExecPlan updated with concrete evidence.

Do not close `hyperopen-egx2` until the new proof surface is demonstrated end to end: the Lean module builds, generated submit-policy vectors are deterministic, stale vectors are detected, the existing order-request surfaces still verify, and the standard repo gates remain green. If the work exposes a mismatch between production behavior and the intended invariant, record the counterexample in this plan and either fix production or narrow the invariant honestly before completion.

## Concrete Steps

From `/Users/barry/.codex/worktrees/b403/hyperopen`, begin by confirming the current proof baseline:

    export PATH="$HOME/.elan/bin:$PATH"
    npm run formal:verify -- --surface vault-transfer
    npm run formal:verify -- --surface order-request-standard
    npm run formal:verify -- --surface order-request-advanced

Those commands should pass before any submit-policy work begins. If one fails, fix the pre-existing regression first instead of layering new proof work on top of a broken formal baseline.

Next, add the new surface metadata and wrapper support:

    npm run test:formal-tooling
    npm run formal:verify -- --surface trading-submit-policy

At first, `trading-submit-policy` will likely fail because the Lean module and generated bridge do not exist yet. That is acceptable during implementation, but once Milestone 1 is finished the command must resolve the new surface correctly and fail only for real proof or stale-output reasons.

Then implement the pure production seam and the Lean model together. The intended edit set is:

    /hyperopen/src/hyperopen/state/trading.cljs
    /hyperopen/src/hyperopen/domain/trading/submit_policy.cljs
    /hyperopen/src/hyperopen/domain/trading/validation.cljs
    /hyperopen/src/hyperopen/schema/trading_submit_policy_contracts.cljs
    /hyperopen/tools/formal/core.clj
    /hyperopen/tools/formal/README.md
    /hyperopen/docs/tools.md
    /hyperopen/tools/formal/generated/trading-submit-policy.edn
    /hyperopen/tools/formal/lean/Hyperopen/Formal/Common.lean
    /hyperopen/tools/formal/lean/Hyperopen/Formal.lean
    /hyperopen/tools/formal/lean/Hyperopen/Formal/TradingSubmitPolicy.lean
    /hyperopen/test/hyperopen/formal/trading_submit_policy_vectors.cljs
    /hyperopen/test/hyperopen/state/trading/identity_and_submit_policy_test.cljs
    /hyperopen/test/hyperopen/state/trading/validation_and_scale_test.cljs
    /hyperopen/test/hyperopen/state/trading/market_summary_test.cljs
    /hyperopen/dev/formal_tooling_test.clj

If new test namespaces are added instead of extending the existing ones, regenerate the test runner before validation:

    npm run test:runner:generate

Once the new Lean surface exports vectors, refresh the generated bridge and verify it:

    npm run formal:sync -- --surface trading-submit-policy
    npm run formal:verify -- --surface trading-submit-policy

Finally, rerun the full local gates for this repo:

    npm run formal:verify -- --surface order-request-standard
    npm run formal:verify -- --surface order-request-advanced
    npm run test:formal-tooling
    npm test
    npm run test:websocket
    npm run check
    npm run lint:docs

Record the exact outcomes in the `Progress` and `Outcomes & Retrospective` sections as the work lands.

## Validation and Acceptance

Acceptance is not “the Lean file exists.” Acceptance is that Hyperopen gains a stable proof surface for trading submit readiness and ordinary tests prove the production code conforms to it.

The implementation is complete when a contributor can run:

    export PATH="$HOME/.elan/bin:$PATH"
    npm run formal:verify -- --surface trading-submit-policy

and see the Lean-backed submit-policy surface complete without stale-vector failures. It is also complete when the generated vectors are consumed by ordinary CLJS tests and those tests prove the following visible behaviors:

Market orders with available reference data become submit-ready with positive canonical price strings instead of remaining ambiguous.

Blank limit-like orders receive fallback prices only when the domain can derive them, and that behavior is pinned by generated vectors plus direct example-based tests.

Validation errors for stop, take, scale, TWAP, and enabled TP/SL paths are deterministic and match the modeled rules.

`submit-policy` returns `reason = nil` only when all blocking conditions are absent, and it returns the correct blocking reason and message when any required condition fails.

The existing order-request surfaces still verify after the new work, proving that submit-policy formalization did not silently change the raw builder contract.

## Idempotence and Recovery

All wrapper and proof commands in this plan must be rerunnable. `formal:sync` is allowed to overwrite the generated bridge for `trading-submit-policy`, but it must do so deterministically. `formal:verify` must remain read-only apart from transient files under `/hyperopen/target/formal/**`. If the new production seam extraction causes public-call-site regressions, restore `/hyperopen/src/hyperopen/state/trading.cljs` to a pure facade over the new kernel rather than reverting the proof boundary itself.

If Lean is missing or misconfigured, the repo must still be able to run `npm test`, `npm run test:websocket`, and `npm run check` against the checked-in generated vectors. Only `formal:sync` and `formal:verify` may require Lean. If a generated-vector diff looks suspicious, rerun `formal:sync`, inspect only the generated file under `/hyperopen/test/hyperopen/formal/trading_submit_policy_vectors.cljs`, and confirm the corresponding theorem or export change in `/hyperopen/tools/formal/lean/Hyperopen/Formal/TradingSubmitPolicy.lean` before accepting it.

## Artifacts and Notes

The most important evidence for this work should be kept concise inside the plan as it progresses. Capture short examples such as:

    npm run formal:verify -- --surface trading-submit-policy
    ...
    Verified trading-submit-policy surface and generated source freshness.

and, when useful, small vector excerpts that prove the surface shape is stable, for example a submit-policy vector showing a market order with `:market-price-missing? false` and `:reason nil`, or a blocked submit vector showing `:reason :validation-errors` with deterministic required fields.

If implementation exposes a mismatch between production and the model, record the smallest counterexample form and reduced context directly in this section so the next contributor can reproduce it without reconstructing state from scratch.

## Interfaces and Dependencies

The new formal surface must be named `trading-submit-policy` in both the Babashka wrapper and the Lean `Surface` enum. The Lean module should be `Hyperopen.Formal.TradingSubmitPolicy`. The generated bridge should be the namespace `hyperopen.formal.trading-submit-policy-vectors`, written to `/hyperopen/test/hyperopen/formal/trading_submit_policy_vectors.cljs`.

The pure production seam now lives at `/hyperopen/src/hyperopen/trading/submit_policy.cljs` and exposes stable functions for preparation and policy evaluation. The end state includes pure functions equivalent in meaning to:

    effective-margin-mode
    prepare-order-form-for-submit
    validate-order-form
    submit-policy

If the seam is split further, keep the facade in `/hyperopen/src/hyperopen/state/trading.cljs` stable and make the pure module names explicit in the final plan update.

The proof surface may depend on the existing order-request surfaces conceptually, but it must not duplicate their full request-shape proofs. Reuse their semantics by translation or by a narrow request-availability predicate. The conformance layer must continue to use ordinary ClojureScript tests and generated vectors, not a runtime Lean dependency.

Plan revision note (2026-03-27 20:40 EDT): Initial active ExecPlan created after auditing the current order-request proof surfaces, submit-policy production code, and the existing test anchors. The plan deliberately scopes item 2 to a new submit-policy surface rather than reopening the already-completed raw order-request formalization.

Plan revision note (2026-03-27 21:18 EDT): Implementation complete. The repo now contains the pure submit-policy seam, the `trading-submit-policy` Lean surface and generated vectors, CLJS conformance coverage, and passing validation evidence for the new surface plus the existing order-request and vault-transfer surfaces.
