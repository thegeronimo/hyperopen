# Formally Verify Vault Transfer Preview and Order Request Construction

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The live `bd` issue for this work is `hyperopen-8k7a`.

## Purpose / Big Picture

Hyperopen already keeps money-moving and exchange-action logic in pure ClojureScript seams, but the repo still does not have machine-checked proofs for those seams. After this work is actually complete, contributors will be able to run a repo-local formal workflow that proves two business kernels in sequence: vault transfer preview first, then order request construction. The visible result will be an executable Lean reference model for each kernel, deterministic generated ClojureScript vector namespaces emitted from those models, and ordinary repo tests that prove the production ClojureScript implementation still conforms to the verified model.

The first track protects `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`, which decides whether Hyperopen may build a `vaultTransfer` action and with what canonical address, direction, and USDC micros amount. The second track protects `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs` plus its arithmetic and canonicalization helpers in `/hyperopen/src/hyperopen/domain/trading/core.cljs` and `/hyperopen/src/hyperopen/domain/trading/market.cljs`, which together decide what exchange actions Hyperopen can emit. Product behavior should not change unless the proof work exposes a real bug.

## Progress

- [x] (2026-03-26 15:43 EDT) Created and claimed `bd` issue `hyperopen-8k7a` for this formal-verification work.
- [x] (2026-03-26 15:43 EDT) Re-read `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, and the relevant boundary docs before drafting the original plan.
- [x] (2026-03-26 15:43 EDT) Audited the target kernels in `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`, `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`, `/hyperopen/src/hyperopen/domain/trading/core.cljs`, and `/hyperopen/src/hyperopen/domain/trading/market.cljs`, plus their current test anchors.
- [x] (2026-03-26 16:24 EDT) Bootstrapped the repo-local formal tooling surface under `/hyperopen/tools/formal/`, including Lean workspace wiring, `formal:verify`, `formal:sync`, and documentation in `/hyperopen/docs/tools.md`.
- [x] (2026-03-26 16:24 EDT) Added ClojureScript-side contract namespaces, committed vector corpora, conformance coverage, and property tests for vault transfer and order request surfaces; fixed route-fallback address normalization inside `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`.
- [x] (2026-03-26 16:24 EDT) Passed the current bootstrap validation slice: `npm run formal:verify -- --surface vault-transfer`, `npm run formal:sync -- --surface vault-transfer`, `npm run formal:verify -- --surface order-request-standard`, `npm run formal:sync -- --surface order-request-standard`, `npm run formal:verify -- --surface order-request-advanced`, `npm run formal:sync -- --surface order-request-advanced`, `npm test`, `npm run test:websocket`, and `npm run check`.
- [x] (2026-03-26 18:26 EDT) Refreshed this ExecPlan after a post-bootstrap audit confirmed the Lean surfaces are still manifest-only scaffolding and `formal:sync` still does not regenerate the checked-in ClojureScript vector namespaces.
- [x] (2026-03-26 18:55 EDT) Replaced `/hyperopen/tools/formal/lean/Hyperopen/Formal/VaultTransfer.lean` with a real vault reference model for canonical address normalization, exact-decimal USDC micros parsing, deposit eligibility, preview precedence, generated vault vectors, and proof theorems for precedence and success-shape invariants.
- [x] (2026-03-26 18:55 EDT) Extended `/hyperopen/tools/formal/core.clj` into a modeled-surface export pipeline for vault transfer, so `formal:sync` now refreshes `/hyperopen/test/hyperopen/formal/vault_transfer_vectors.cljs`, `formal:verify` now fails on stale vault generated source, and `/hyperopen/dev/formal_tooling_test.clj` covers the wrapper behavior. Added the explicit runtime effect-order assertion for `:actions/submit-vault-transfer`.
- [x] (2026-03-26 18:55 EDT) Validated the current vault slice with `npm run test:formal-tooling`, `npm run formal:verify -- --surface vault-transfer`, `npm run formal:verify -- --surface order-request-standard`, `npm run formal:verify -- --surface order-request-advanced`, `npm test`, `npm run test:websocket`, `npm run check`, and `npm run lint:docs`.
- [x] (2026-03-26 19:20 EDT) Replaced `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Standard.lean` with a real submit-ready and raw-builder standard-order reference model for standard orders, TP/SL, canonical price formatting, asset identity via canonical `asset-idx`, and leverage pre-actions. `/hyperopen/tools/formal/core.clj`, `/hyperopen/tools/formal/README.md`, `/hyperopen/docs/tools.md`, and `/hyperopen/dev/formal_tooling_test.clj` now treat `order-request-standard` as a modeled surface, `formal:sync` now regenerates `/hyperopen/test/hyperopen/formal/order_request_standard_vectors.cljs`, and `formal:verify` now fails on stale standard generated source.
- [ ] Replace the bootstrap-only advanced order Lean surface with a real exact-arithmetic model for scale ladders and TWAP requests, and make `formal:sync` regenerate `/hyperopen/test/hyperopen/formal/order_request_advanced_vectors.cljs`.
- [ ] Run mutation follow-ups for `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`, `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`, `/hyperopen/src/hyperopen/domain/trading/market.cljs`, and `/hyperopen/src/hyperopen/domain/trading/core.cljs`, then close `hyperopen-8k7a` and move this plan to `/hyperopen/docs/exec-plans/completed/` once acceptance is fully met.

## Surprises & Discoveries

- Observation: the repository initially had no formal verification scaffold or proof-tool wrapper.
  Evidence: `which lean lake elan dafny tlc dotnet` returned no installed proof tools on 2026-03-26, while `java`, `node`, and `bb` were present before the tooling bootstrap landed.

- Observation: `vault-transfer-preview` is already a compact pure kernel, but its original tests did not fully pin down the decision table.
  Evidence: `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs` is pure and bounded, and the expanded suites now cover liquidator blocking, leader override, merged-row fallback, route normalization, and preview precedence.

- Observation: order request construction is not one proof problem; it is a cluster of kernels with different risk profiles.
  Evidence: `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs` owns standard orders, TP/SL attachments, scale, TWAP, and leverage pre-actions, while `/hyperopen/src/hyperopen/domain/trading/market.cljs` and `/hyperopen/src/hyperopen/domain/trading/core.cljs` own canonicalization and arithmetic.

- Observation: production validation and construction are intentionally not equivalent.
  Evidence: `/hyperopen/src/hyperopen/state/trading.cljs` includes the explicit `:request-unavailable` branch in `submit-policy`, so the proof work must distinguish “submit-ready normalized input” from “raw builder observational contract.”

- Observation: proving current JavaScript number behavior directly would be brittle and low-value.
  Evidence: `/hyperopen/src/hyperopen/domain/trading/core.cljs` and `/hyperopen/src/hyperopen/domain/trading/market.cljs` rely on `parseFloat`, `Math.floor`, `toFixed`, and ordinary JavaScript stringification. The reference model must therefore use exact integers or exact decimal encodings and compare production behavior to that model with conformance tests.

- Observation: route-derived vault addresses needed normalization inside the proof kernel, not only at the router edge.
  Evidence: mixed-case and padded route addresses could bypass the prior fallback path; `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs` now normalizes `route-vault-address-fn` output before selection, and the committed vectors pin both normalized and invalid-route cases.

- Observation: the only remaining bootstrap Lean surface is the advanced order model.
  Evidence: `/hyperopen/tools/formal/lean/Hyperopen/Formal/VaultTransfer.lean` and `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Standard.lean` now contain executable business-rule models, generated-source emitters, and proof theorems, while `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Advanced.lean` still stops at manifest-only scaffolding.

- Observation: the remaining hand-maintained vector namespace is the advanced-order corpus.
  Evidence: `/hyperopen/tools/formal/core.clj` and `/hyperopen/tools/formal/README.md` now regenerate and freshness-check `/hyperopen/test/hyperopen/formal/vault_transfer_vectors.cljs` and `/hyperopen/test/hyperopen/formal/order_request_standard_vectors.cljs`, while `/hyperopen/test/hyperopen/formal/order_request_advanced_vectors.cljs` is still bootstrap-era fixture data.

- Observation: order-request export cannot use ordinary unordered EDN maps.
  Evidence: `/hyperopen/src/hyperopen/schema/order_request_contracts.cljs` and `/hyperopen/test/hyperopen/state/trading/order_request_test.cljs` assert exact field order for signing-sensitive request maps, so the export bridge must render deterministic `array-map`-shaped ClojureScript.

- Observation: emitting generated ClojureScript source directly from Lean is the cheapest reliable way to preserve ordered request maps today.
  Evidence: the vault surface now writes transient generated source under `/hyperopen/target/formal/vault-transfer-vectors.cljs`, and `/hyperopen/tools/formal/core.clj` can compare or copy that file directly without losing `array-map` order through an intermediate unordered map format.

- Observation: localized decimal input remains an adapter contract, not a proof-model contract, even after vault export went live.
  Evidence: the generated vault vectors now cover normalized decimal input only, while the localized comma-decimal case remains an ordinary unit test in `/hyperopen/test/hyperopen/vaults/domain/transfer_policy_test.cljs`.

## Decision Log

- Decision: use Lean 4 under `/hyperopen/tools/formal/lean/` as the proof language for these first two tracks, with repo-local wrapper commands exposed through `npm`.
  Rationale: Lean 4 can be installed locally with `elan`, supports executable reference models plus proofs over integers and finite data, and keeps the proof environment close to the repo instead of introducing a remote or container dependency.
  Date/Author: 2026-03-26 / Codex

- Decision: keep proof execution out of `npm run check` in v1.
  Rationale: the first delivery must keep normal development friction low. The stable requirement is that committed vectors let `npm test` prove ClojureScript conformance even when Lean is unavailable.
  Date/Author: 2026-03-26 / Codex

- Decision: treat the current contracts, vector corpora, and property tests as conformance scaffolding, not proof completion.
  Rationale: they are valuable and should stay, but the current Lean surfaces do not yet encode business rules. This plan must not claim the kernels are formally verified until the reference models and exporter are real.
  Date/Author: 2026-03-26 / Codex

- Decision: keep this work on the existing active ExecPlan instead of creating a second follow-on plan.
  Rationale: the current repository state is one partially completed program, not two separate tickets. The right move is to refresh the same living plan so a future contributor can restart from one document.
  Date/Author: 2026-03-26 / Codex

- Decision: sequence the remaining implementation as vault model first, then exporter/tooling hardening, then standard-order proofs, then advanced arithmetic proofs, then mutation close-out.
  Rationale: vault transfer is smaller and money-moving, the exporter is required to keep later order proofs honest, and scale/TWAP arithmetic is the highest-complexity slice and should come last.
  Date/Author: 2026-03-26 / Codex

- Decision: keep locale normalization and full submit-policy orchestration out of the proof core for this first program.
  Rationale: the proof kernels remain `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs` and the order-request builders plus arithmetic helpers. Locale parsing and submission orchestration remain caller contracts backed by ordinary tests.
  Date/Author: 2026-03-26 / Codex

- Decision: require mutation evidence for the arithmetic and canonicalization helpers as well as the top-level builders.
  Rationale: the safety claims for order construction rely on `/hyperopen/src/hyperopen/domain/trading/market.cljs` and `/hyperopen/src/hyperopen/domain/trading/core.cljs`, not only `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`. Acceptance should match that claim.
  Date/Author: 2026-03-26 / Codex

- Decision: have modeled surfaces emit deterministic ClojureScript source into `/hyperopen/target/formal/**`, and let the Babashka wrapper compare or copy that source into the checked-in vector namespace.
  Rationale: this keeps the first export pipeline simple, avoids losing key order for signing-sensitive request maps, and still gives `formal:verify` a clean transient-versus-committed freshness check.
  Date/Author: 2026-03-26 / Codex

- Decision: keep localized decimal parsing outside the generated vault vector corpus even after vault formalization.
  Rationale: the vault proof model is scoped to normalized decimal input. Locale adaptation still matters, but it belongs in adapter tests rather than the first Lean kernel.
  Date/Author: 2026-03-26 / Codex

## Outcomes & Retrospective

The first implementation slice succeeded as tooling bootstrap and executable-spec scaffolding. The repository now has a Lean workspace, repo-local wrapper commands, contract namespaces, committed vector corpora, conformance tests, property tests, and one real production bug fix in `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`. That reduced future startup cost for the proof work because the repo now has a stable surface to build on.

The bootstrap slice also created a new risk: the commands named `formal:verify` and `formal:sync` currently pass without proving the business kernels. That slightly increased conceptual complexity because the repo now has a formal-looking surface that still stops at manifests and hand-maintained fixtures. This refreshed plan corrects that by demoting the current state to scaffolding and making the real remaining work explicit.

The second implementation slice resolved that risk for vault transfer. The vault surface is now a real Lean model with generated vectors, wrapper freshness checks, and fast Babashka tests around the export path. That increased tooling complexity slightly because the wrapper now manages a transient generated-source artifact under `/hyperopen/target/formal/**`, but it reduced product risk by making the vault kernel materially machine-checked instead of just labeled “formal.”

The third implementation slice did the same for standard order requests. `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Standard.lean` is now a real Lean model for standard orders, TP/SL attachments, canonical price truncation, canonical `asset-idx` usage, and leverage pre-actions, and the wrapper now treats `order-request-standard` as a modeled generated-source surface. That further reduced the “formal in name only” risk and leaves the advanced arithmetic surface as the only major unfinished proof/export track.

If the remaining milestones succeed, overall complexity should still go down. Hyperopen will trade a modest amount of optional proof-tooling complexity for a much clearer and reviewable definition of what vault and order actions are legal to emit.

## Context and Orientation

This plan uses four terms repeatedly.

A “proof kernel” is the smallest pure function set that carries the safety rule we care about. In this repository, the first proof kernel is `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`. The second proof kernel is the combination of `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`, `/hyperopen/src/hyperopen/domain/trading/core.cljs`, and `/hyperopen/src/hyperopen/domain/trading/market.cljs`.

A “reference model” is an executable copy of the business rule written in the proof language. It is not the production implementation. It should stay small, typed, and explicit, and it must use exact integers or exact decimal encodings where the production code currently relies on JavaScript numbers.

A “conformance vector” is a checked-in test case emitted from the reference model and consumed by the ClojureScript test suite. The vectors bridge formal verification and normal repo validation. They let `npm test` prove that the current production implementation still matches the verified model without forcing every contributor machine to run Lean on every test command.

A “submit-ready contract” is a normalized input contract that holds after the existing preparation code has done its work. For orders, that includes asset identity resolution, market-price preparation, and deterministic form normalization. A “raw builder observational contract” is weaker: it records how the public builder behaves when called directly, including nil or fail-closed outputs, but does not assume the caller came through `submit-policy`.

The current repo state matters. `/hyperopen/tools/formal/lean/Hyperopen/Formal/Common.lean` now owns command parsing, surface metadata, manifest helpers, and deterministic Clojure rendering helpers. `/hyperopen/tools/formal/lean/Hyperopen/Formal/VaultTransfer.lean` emits `/hyperopen/target/formal/vault-transfer-vectors.cljs`, and `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Standard.lean` now emits `/hyperopen/target/formal/order-request-standard-vectors.cljs`. `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Advanced.lean` is still bootstrap scaffolding. `/hyperopen/tools/formal/core.clj` now verifies and syncs both modeled generated namespaces in addition to manifests, while the advanced surface still remains manifest-only.

The ClojureScript side is stronger than the Lean side today. `/hyperopen/src/hyperopen/schema/vault_transfer_contracts.cljs` and `/hyperopen/src/hyperopen/schema/order_request_contracts.cljs` define exact-shape adapters for the proof surfaces. `/hyperopen/test/hyperopen/formal/vault_transfer_vectors.cljs`, `/hyperopen/test/hyperopen/formal/order_request_standard_vectors.cljs`, and `/hyperopen/test/hyperopen/formal/order_request_advanced_vectors.cljs` hold committed corpora. The test anchors already exist in `/hyperopen/test/hyperopen/vaults/domain/transfer_policy_test.cljs`, `/hyperopen/test/hyperopen/vaults/domain/transfer_policy_properties_test.cljs`, `/hyperopen/test/hyperopen/vaults/application/transfer_commands_test.cljs`, `/hyperopen/test/hyperopen/api/gateway/orders/commands_test.cljs`, `/hyperopen/test/hyperopen/state/trading/order_request_test.cljs`, `/hyperopen/test/hyperopen/state/trading/identity_and_submit_policy_test.cljs`, `/hyperopen/test/hyperopen/state/trading/validation_and_scale_test.cljs`, and `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_failures_test.cljs`.

The exporter has one special requirement for order requests: deterministic map order. Hyperopen’s signing-sensitive order actions depend on exact field order, so the generated bridge must render `array-map`-shaped ClojureScript in the same order the production builders and tests expect.

The required repository gates remain `npm run check`, `npm test`, and `npm run test:websocket`. Proof execution stays optional, but conformance tests on committed vectors must keep working without Lean installed.

## Plan of Work

### Milestone 1: Replace the vault bootstrap surface with a real proof model

This milestone is complete. `/hyperopen/tools/formal/lean/Hyperopen/Formal/VaultTransfer.lean` is now the real Lean model for `parse-usdc-micros`, `vault-transfer-deposit-allowed?`, and `vault-transfer-preview`, and the shared helpers it needed now live under `/hyperopen/tools/formal/lean/Hyperopen/Formal/`.

The model must prove the decision precedence that production code actually uses: invalid address before deposit-disabled, deposit-disabled before invalid amount, and withdraw-all bypassing amount parsing only in withdraw mode. It must also prove the existing identity asymmetry: leader and name may fall back from merged rows, but `:allow-deposits?` comes only from details. Locale normalization remains outside this proof track; the Lean model should consume normalized decimal input and the ClojureScript tests should continue covering locale adaptation separately.

The vault track is only complete when `formal:sync` emits `/hyperopen/test/hyperopen/formal/vault_transfer_vectors.cljs` from the model, `formal:verify` fails if that namespace is stale, and the existing vault tests become consumers of generated output rather than hand-edited fixtures. This milestone should also add an explicit assertion that the emitted `:actions/submit-vault-transfer` path still satisfies `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`.

### Milestone 2: Turn the wrapper into a real export and freshness pipeline

This milestone is complete for vault transfer and still open for the order surfaces. `/hyperopen/tools/formal/core.clj` no longer treats manifests as the only freshness target for modeled surfaces. It now knows the vault Lean module, transient export file under `/hyperopen/target/formal/**`, committed generated namespace path, and the copy-versus-compare behavior needed for sync and verify.

`formal:sync` should run the Lean entrypoint, capture stable intermediate data under `/hyperopen/target/formal/**`, and then render the checked-in `test/hyperopen/formal/*.cljs` namespace for the selected surface. `formal:verify` should perform the same export into a temporary location, compare it against the committed namespace, and fail if either the namespace or the manifest metadata is stale. The exporter must preserve exact key order for order-request surfaces by emitting `array-map` forms, not plain unordered maps.

This milestone should also add focused wrapper tests, for example under a new fast Babashka namespace such as `/hyperopen/dev/formal_tooling_test.clj`. Those tests should cover missing-Lean install messaging, bad surface parsing, sync idempotence, stale-vector detection, and the guarantee that `verify` is read-only apart from transient files under `/hyperopen/target/formal/**`.

### Milestone 3: Replace the standard order bootstrap surface with a real model

This milestone is complete. `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Standard.lean` is now an executable model of the standard-order high-value core: standard order types, TP and SL attachments, canonical price truncation, canonical `asset-idx` usage, and `updateLeverage` pre-actions. The model keeps the split between submit-ready and raw-builder behavior by emitting both successful and fail-closed vectors from the same builder semantics.

The current model proves representative theorems for post-only TIF override, fail-closed TP/SL behavior, isolated-only leverage forcing, and stop-market price truncation without rounding up. `formal:sync` now emits `/hyperopen/test/hyperopen/formal/order_request_standard_vectors.cljs`, the existing order tests consume that generated namespace, and `formal:verify` fails on stale vectors or stale proof metadata for the standard-order surface.

### Milestone 4: Replace the advanced order bootstrap surface with exact arithmetic proofs

Once the standard-order surface is real, turn `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Advanced.lean` into the exact-arithmetic model for scale ladders and TWAP requests. The production risk here lives in `/hyperopen/src/hyperopen/domain/trading/core.cljs` and the special-order builders in `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`, so the Lean model must express those rules with exact arithmetic and then compare production serialization after final canonicalization.

For scale orders, prove count bounds, weight normalization, endpoint behavior, arithmetic progression from start to end, flooring to `szDecimals`, and the invariant that total leg size never exceeds the requested total after flooring. For TWAP, prove runtime bounds, total-minutes calculation, suborder-count calculation, and positive suborder-notional requirements for valid inputs. If any exact-model versus production mismatch appears, record the counterexample in this plan and either patch production code or narrow the proof domain explicitly. Do not silently weaken the model to “whatever JavaScript did.”

This milestone is only done when `formal:sync` emits `/hyperopen/test/hyperopen/formal/order_request_advanced_vectors.cljs`, the advanced order tests consume those vectors, and the current regression anchors in `/hyperopen/test/hyperopen/state/trading/validation_and_scale_test.cljs` remain green.

### Milestone 5: Validate the proof pipeline and close the work honestly

After all three Lean surfaces are real and all three generated vector namespaces are emitted by the wrapper, run the remaining confidence passes. The targeted mutation follow-up must cover the vault kernel and all order kernels that the proof claims rely on: `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`, `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`, `/hyperopen/src/hyperopen/domain/trading/market.cljs`, and `/hyperopen/src/hyperopen/domain/trading/core.cljs`.

Do not close `hyperopen-8k7a` until the proof pipeline itself is demonstrated, not just the ClojureScript tests. The close-out evidence must show generated vectors are deterministic, stale vectors are detected, repo gates still pass without Lean installed, and the targeted mutation runs kill meaningful regressions in the claimed proof kernels.

## Concrete Steps

From `/Users/barry/.codex/worktrees/57c2/hyperopen`:

1. Confirm the current baseline and keep it recorded in this plan.

       export PATH="$HOME/.elan/bin:$PATH"
       npm run formal:verify -- --surface vault-transfer
       npm run formal:verify -- --surface order-request-standard
       npm run formal:verify -- --surface order-request-advanced

   Today these commands prove only the bootstrap workspace and manifest freshness. Keep that fact explicit in the plan until the Lean surfaces are replaced.

2. Replace the vault Lean scaffold with a real model and emitted vectors.

   Edit:

   - `/hyperopen/tools/formal/lean/Hyperopen/Formal/Common.lean`
   - `/hyperopen/tools/formal/lean/Hyperopen/Formal/VaultTransfer.lean`
   - `/hyperopen/tools/formal/core.clj`
   - `/hyperopen/tools/formal/README.md`
   - `/hyperopen/src/hyperopen/schema/vault_transfer_contracts.cljs`
   - `/hyperopen/test/hyperopen/formal/vault_transfer_vectors.cljs`
   - `/hyperopen/test/hyperopen/vaults/domain/transfer_policy_test.cljs`
   - `/hyperopen/test/hyperopen/vaults/domain/transfer_policy_properties_test.cljs`
   - `/hyperopen/test/hyperopen/vaults/application/transfer_commands_test.cljs`

   Then run:

       npm run formal:sync -- --surface vault-transfer
       npm run formal:verify -- --surface vault-transfer

   Expect `sync` to rewrite the committed vector namespace deterministically and `verify` to fail if the namespace is stale.

3. Add wrapper and exporter tests before expanding the order models.

   Create a focused fast test namespace such as:

   - `/hyperopen/dev/formal_tooling_test.clj`

   Wire a stable command for it if needed, then run it alongside the vault surface:

       bb -m dev.formal-tooling-test
       npm run formal:verify -- --surface vault-transfer

4. Replace the standard order Lean scaffold with the real submit-ready and raw-builder model.

   Edit:

   - `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Common.lean`
   - `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Standard.lean`
   - `/hyperopen/tools/formal/core.clj`
   - `/hyperopen/tools/formal/README.md`
   - `/hyperopen/src/hyperopen/schema/order_request_contracts.cljs`
   - `/hyperopen/test/hyperopen/formal/order_request_standard_vectors.cljs`
   - `/hyperopen/test/hyperopen/api/gateway/orders/commands_test.cljs`
   - `/hyperopen/test/hyperopen/state/trading/order_request_test.cljs`
   - `/hyperopen/test/hyperopen/state/trading/identity_and_submit_policy_test.cljs`
   - `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_failures_test.cljs`

   Then run:

       npm run formal:sync -- --surface order-request-standard
       npm run formal:verify -- --surface order-request-standard

5. Replace the advanced order Lean scaffold with the real scale and TWAP model.

   Edit:

   - `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Advanced.lean`
   - `/hyperopen/tools/formal/core.clj`
   - `/hyperopen/tools/formal/README.md`
   - `/hyperopen/test/hyperopen/formal/order_request_advanced_vectors.cljs`
   - `/hyperopen/test/hyperopen/state/trading/validation_and_scale_test.cljs`
   - `/hyperopen/test/hyperopen/api/gateway/orders/commands_test.cljs`

   Then run:

       npm run formal:sync -- --surface order-request-advanced
       npm run formal:verify -- --surface order-request-advanced

6. Run the final validation and mutation passes.

       npm test
       npm run test:websocket
       npm run check
       bb tools/mutate.clj --module src/hyperopen/vaults/domain/transfer_policy.cljs --suite test --mutate-all
       bb tools/mutate.clj --module src/hyperopen/api/gateway/orders/commands.cljs --suite test --mutate-all
       bb tools/mutate.clj --module src/hyperopen/domain/trading/market.cljs --suite test --mutate-all
       bb tools/mutate.clj --module src/hyperopen/domain/trading/core.cljs --suite test --mutate-all

   If the full mutation runs are too slow, rerun by line subsets, but record the narrowed command and the reason in this plan before closing the work.

## Validation and Acceptance

Acceptance is met only when both the proof workflow and the normal repo workflow are green, and when the proof workflow is proving the business kernels rather than only proving wrapper metadata.

For the vault-transfer track, `/hyperopen/tools/formal/lean/Hyperopen/Formal/VaultTransfer.lean` must define executable domain functions equivalent in meaning to `parse-usdc-micros`, `vault-transfer-deposit-allowed?`, and `vault-transfer-preview`, plus theorems for their key invariants. `npm run formal:sync -- --surface vault-transfer` must regenerate `/hyperopen/test/hyperopen/formal/vault_transfer_vectors.cljs` deterministically, `npm run formal:verify -- --surface vault-transfer` must fail when that namespace is stale, and the existing vault tests must pass while consuming the generated vectors. The vault adapter tests must still show the `:actions/submit-vault-transfer` path respects `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`.

For the standard order track, `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Standard.lean` must define executable domain functions equivalent in meaning to canonical price formatting, standard order request construction, and leverage pre-action construction. The model must prove exact type dispatch, no named-DEX fallback to generic `idx`, TP and SL opposite-side attachment with `:r true`, and isolated-mode forcing when cross margin is forbidden. `formal:sync` must regenerate `/hyperopen/test/hyperopen/formal/order_request_standard_vectors.cljs` deterministically, and `formal:verify` must fail when the generated namespace or proof metadata is stale.

For the advanced order track, `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Advanced.lean` must define executable exact-arithmetic functions equivalent in meaning to scale-ladder and TWAP request construction. The model must prove floor-to-`szDecimals` behavior, leg-total safety, runtime bounds, total-minutes and suborder-count formulas, and positive suborder-notional behavior for valid inputs. `formal:sync` must regenerate `/hyperopen/test/hyperopen/formal/order_request_advanced_vectors.cljs` deterministically, and `formal:verify` must fail when that namespace or its proof metadata is stale.

For the wrapper itself, focused tool tests must cover missing-Lean install messaging, bad argument handling, sync idempotence, stale-vector detection, and the guarantee that `verify` is read-only apart from transient files under `/hyperopen/target/formal/**`.

For the repository as a whole, `npm test`, `npm run test:websocket`, and `npm run check` must pass after the proof work lands. Normal test execution must not require Lean to be installed. The committed vector namespaces are what tie the proof model back into ordinary ClojureScript validation.

Mutation evidence is also part of acceptance. The transfer-policy mutation run must not regress versus the current hotspot baseline, and the order-request mutation runs must demonstrate meaningful killed mutants in `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`, `/hyperopen/src/hyperopen/domain/trading/market.cljs`, and `/hyperopen/src/hyperopen/domain/trading/core.cljs`, not only green proofs in isolation.

## Idempotence and Recovery

The proof workflow must be safe to rerun. `npm run formal:sync` should overwrite generated vector namespaces deterministically instead of appending or scrambling key order. `npm run formal:verify` should be read-only apart from transient files under `/hyperopen/target/formal/**`. If Lean is missing or misconfigured, the wrapper should print the exact install or PATH repair step and exit before touching tracked files.

The production behavior should remain stable unless the proofs expose a bug. If a generated vector disagrees with production code, first record the failing case in this plan and the corresponding tests. Only then decide whether to patch production code, narrow the proof domain, or split a follow-up `bd` issue. Do not silently weaken the model or delete failing cases to make the pipeline green.

If a contributor needs to work without Lean, they should still be able to run `npm test`, `npm run test:websocket`, and `npm run check` on the committed vectors. The only unavailable commands in that situation should be `formal:verify` and `formal:sync`.

## Artifacts and Notes

Important current-state evidence that should remain true while this plan is active:

- `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs` is already a pure kernel with public functions for parser, deposit eligibility, and preview construction.
- `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs` is the actual order-action builder and already owns standard-order shapes, TP/SL attachments, scale, TWAP, and leverage pre-actions.
- `/hyperopen/test/hyperopen/state/trading/order_request_test.cljs` already protects canonical key order and named-DEX asset-id behavior.
- `/hyperopen/test/hyperopen/state/trading/validation_and_scale_test.cljs` already anchors many of the arithmetic properties the Lean model should formalize instead of replacing.
- `/hyperopen/tools/formal/lean/Hyperopen/Formal/VaultTransfer.lean`, `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Standard.lean`, and `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Advanced.lean` are still bootstrap-only as of this refresh. Do not mark this work complete until those surfaces encode domain logic.

The generated proof vectors should be treated the same way Hyperopen treats signing known vectors: small, committed, reviewable, deterministic, and emitted from the model rather than maintained by hand. If the wire shape changes for vault transfers or orders, update the generated vectors and document why the shape change is safe.

## Interfaces and Dependencies

The final implementation must expose these stable interfaces and paths:

- `npm run formal:verify -- --surface <vault-transfer|order-request-standard|order-request-advanced>`
- `npm run formal:sync -- --surface <vault-transfer|order-request-standard|order-request-advanced>`
- `/hyperopen/tools/formal.clj` as the repo-local command wrapper that shells out to Lean, manages exports, and checks freshness
- `/hyperopen/tools/formal/lean/` as the only proof-language workspace for this first program
- `/hyperopen/test/hyperopen/formal/*.cljs` as the committed generated-vector bridge consumed by ordinary tests
- `/hyperopen/src/hyperopen/schema/vault_transfer_contracts.cljs` and `/hyperopen/src/hyperopen/schema/order_request_contracts.cljs` as exact-shape adapter contracts for proof inputs and outputs

The Lean model should define, at minimum, executable functions equivalent in meaning to:

- `Hyperopen.Formal.VaultTransfer.parseUsdcMicros`
- `Hyperopen.Formal.VaultTransfer.depositAllowed`
- `Hyperopen.Formal.VaultTransfer.preview`
- `Hyperopen.Formal.OrderRequest.canonicalPrice`
- `Hyperopen.Formal.OrderRequest.buildStandard`
- `Hyperopen.Formal.OrderRequest.buildPreActions`
- `Hyperopen.Formal.OrderRequest.buildScale`
- `Hyperopen.Formal.OrderRequest.buildTwap`

The wrapper layer should define one stable export format for generated vectors. Intermediate files under `/hyperopen/target/formal/**` may use plain EDN-shaped data, but the committed ClojureScript bridge for order requests must preserve deterministic `array-map` field order.

This plan depends on the existing Hyperopen toolchain plus Lean 4 installed through `elan`. It deliberately does not depend on Dafny, TLA+, Docker, or a remote service for these first two proof tracks.

Revision note (2026-03-26): Initial ExecPlan created for `hyperopen-8k7a` after auditing the vault transfer and order request kernels, the existing test anchors, repo planning rules, and the current absence of a formal-methods toolchain in the workspace.
Plan update note (2026-03-26 19:20 EDT): Vault transfer and standard order requests are now both modeled surfaces with generated namespaces and freshness checks. The only remaining proof/export implementation track is `order-request-advanced`, followed by the targeted mutation close-out.
Plan update note (2026-03-26 18:55 EDT): Completed the vault-transfer milestone by replacing the Lean stub with a real model, wiring `formal:sync` and `formal:verify` to a generated vault vector namespace, adding fast Babashka wrapper tests, updating formal-tool docs, and adding an explicit runtime effect-order assertion for `:actions/submit-vault-transfer`. The remaining open work is the order-request standard and advanced proof surfaces plus the targeted mutation follow-ups.
