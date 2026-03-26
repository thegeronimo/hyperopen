# Formally Verify Vault Transfer Preview and Order Request Construction

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The live `bd` issue for this work is `hyperopen-8k7a`.

## Purpose / Big Picture

Hyperopen already keeps its money-moving and exchange-action logic in pure ClojureScript seams, but those seams are currently defended only by example tests, mutation checks, and code review. After this change, contributors will be able to run a repo-local formal verification workflow for two safety-critical kernels in sequence: vault transfer preview first, then order request construction. The visible proof will be a checked-in proof model, committed conformance vectors, property tests, and repeatable local commands that verify the proofs and then show the production ClojureScript functions still match the verified model.

The first track protects vault transfer preview, which decides whether Hyperopen is allowed to build a `vaultTransfer` action at all and, if so, with what canonical address, direction, and USDC micros amount. The second track protects order request construction, which turns a normalized order form and market context into signed exchange actions, including leverage pre-actions, standard order shapes, TP/SL attachments, scale ladders, and TWAP requests. The user-visible product behavior should not change unless the proof work reveals a real bug. The intended outcome is stronger confidence that Hyperopen does not emit illegal money-moving or order-placement actions inside the modeled input domain.

## Progress

- [x] (2026-03-26 15:43 EDT) Created and claimed `bd` issue `hyperopen-8k7a` for this formal-verification planning work.
- [x] (2026-03-26 15:43 EDT) Re-read `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, and the relevant boundary docs before drafting the plan.
- [x] (2026-03-26 15:43 EDT) Audited the current proof targets in `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`, `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`, `/hyperopen/src/hyperopen/domain/trading/core.cljs`, `/hyperopen/src/hyperopen/domain/trading/market.cljs`, and the existing tests that already anchor these surfaces.
- [x] (2026-03-26 15:43 EDT) Confirmed the current workspace has no formal-methods toolchain installed (`lean`, `lake`, `elan`, `dafny`, `tlc`, and `dotnet` were all absent), so this plan includes explicit bootstrap work.
- [x] (2026-03-26 15:43 EDT) Authored this active ExecPlan for a sequential proof program: vault transfer preview first, order request construction second.
- [x] (2026-03-26 16:24 EDT) Bootstrapped a repo-local proof toolchain under `/hyperopen/tools/formal/`, added discoverable `formal:verify` and `formal:sync` npm scripts, and kept proof execution out of `npm run check`.
- [x] (2026-03-26 16:24 EDT) Implemented the vault transfer proof track with a dedicated contract namespace, committed formal vectors, route-normalization hardening in `vault-transfer-preview`, vector-backed conformance tests, and property tests for parser/eligibility/preview invariants.
- [x] (2026-03-26 16:24 EDT) Implemented the order request proof track with an exact-shape contract namespace, committed standard and advanced vector corpora, and conformance coverage for standard orders, leverage pre-actions, scale requests, and TWAP requests across builder and state-level callers.
- [ ] Run targeted mutation follow-ups for the proof kernels, reconcile any surviving mutants, and then move this plan to `/hyperopen/docs/exec-plans/completed/` once the remaining acceptance criteria are fully met.

## Surprises & Discoveries

- Observation: the repository does not currently have any formal verification scaffold or proof-tool wrapper.
  Evidence: `which lean lake elan dafny tlc dotnet` returned no installed proof tools on 2026-03-26, while `java`, `node`, and `bb` were present.

- Observation: `vault-transfer-preview` is already isolated as a compact pure kernel, but its current tests do not completely specify the decision table.
  Evidence: `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs` is pure and bounded, and `/hyperopen/test/hyperopen/vaults/domain/transfer_policy_test.cljs` already covers truncation, overflow, route fallback, withdraw-all, and smallest-positive-unit behavior, but it does not yet pin down liquidator blocking, leader override, merged-row fallback, or route-normalization assumptions.

- Observation: order request construction is not one proof problem; it is a cluster of related kernels with different risks.
  Evidence: `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs` covers standard orders, TP/SL attachments, scale requests, TWAP requests, and leverage pre-actions, while `/hyperopen/src/hyperopen/domain/trading/market.cljs` and `/hyperopen/src/hyperopen/domain/trading/core.cljs` own the string-canonicalization and arithmetic pieces those builders depend on.

- Observation: production validation and construction are intentionally not equivalent today.
  Evidence: `/hyperopen/src/hyperopen/state/trading.cljs` includes the explicit `:request-unavailable` branch in `submit-policy`, so the proof work must distinguish “submit-ready normalized input” from “raw builder observational contract” instead of pretending the builder is total for all forms that reach the view layer.

- Observation: proofing the current JavaScript number behavior directly would be brittle and low-value.
  Evidence: `/hyperopen/src/hyperopen/domain/trading/core.cljs` and `/hyperopen/src/hyperopen/domain/trading/market.cljs` rely on `parseFloat`, `Math.floor`, `toFixed`, and ordinary JS stringification. The proof plan must therefore use fixed-point or exact-decimal reference models and then compare the ClojureScript implementation to the model with bounded conformance tests, not try to “prove JavaScript doubles.”

- Observation: route-derived vault addresses needed normalization inside the proof kernel, not just at the router edge.
  Evidence: while wiring vector-backed preview cases, mixed-case and padded route addresses could bypass the prior `vault-transfer-preview` fallback path; the implementation now normalizes `route-vault-address-fn` output before selection in `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`, and the committed vectors pin both normalized and invalid-route fallback behavior.

- Observation: the first tooling slice validates Lean workspace health and per-surface manifests, but it does not yet regenerate the checked-in ClojureScript vector namespaces.
  Evidence: `tools/formal/core.clj` currently builds Lean and refreshes `tools/formal/generated/*.edn`, while the committed proof vectors live under `/hyperopen/test/hyperopen/formal/*.cljs` and were updated directly in this implementation slice.

## Decision Log

- Decision: use Lean 4 under `/hyperopen/tools/formal/lean/` as the proof language for these first two tracks, and use repo-local wrapper commands to emit committed ClojureScript vector namespaces from the verified model.
  Rationale: the repo has no existing proof toolchain, but Lean 4 can be bootstrapped with `elan` without adding a runtime dependency to the production app. It supports executable reference models plus proofs over integers and finite data, which is the right fit for `vault-transfer-preview` and order-request legality. Committed generated vectors let ordinary `npm test` continue to run even on machines that do not have Lean installed.
  Date/Author: 2026-03-26 / Codex

- Decision: keep proof execution out of the required `npm run check` gate in v1, but add explicit `formal:verify` and `formal:sync` commands and document them in repo tooling docs.
  Rationale: the current workspace does not have Lean installed, and the existing required repo gates are already stable and mandatory. The first delivery should establish a reliable local proof workflow and committed conformance artifacts before the project considers making proof execution mandatory in CI.
  Date/Author: 2026-03-26 / Codex

- Decision: scope the first proof track to normalized vault-transfer policy only, not to locale normalization, full modal flow, or runtime effect ordering.
  Rationale: `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs` is the pure decision owner. Locale normalization in `/hyperopen/src/hyperopen/utils/parse.cljs` and effect ordering in `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs` are real contracts, but they are adapter concerns and would dilute the first formalization effort.
  Date/Author: 2026-03-26 / Codex

- Decision: scope the second proof track to order request construction, not full `submit-policy`, read-only gating, spectate gating, or runtime submission sequencing.
  Rationale: the user asked for order request construction as the second target. The builder kernel is where Hyperopen emits exchange actions. `submit-policy`, `order/actions.cljs`, and effect-order enforcement remain caller/runtime concerns that already have direct tests and should be referenced as context rather than absorbed into the proof core.
  Date/Author: 2026-03-26 / Codex

- Decision: split the order-request work into two proof domains inside one track: a submit-ready contract and a raw public-builder observational contract.
  Rationale: normalized submit flows in `/hyperopen/src/hyperopen/state/trading.cljs` prepare inputs before calling the builder, but the builder is also used directly by other pure callers. A high-quality proof plan must state what is guaranteed for fully prepared inputs and what is merely fail-closed behavior for weaker or direct inputs.
  Date/Author: 2026-03-26 / Codex

- Decision: use fixed-point or exact-decimal proof models and treat string messages as adapter behavior.
  Rationale: exact integer/fixed-point arithmetic is the right abstraction for USDC micros, price truncation, size flooring, scale weights, and TWAP counts. Error strings are UI artifacts; reason keywords and structured decisions are easier to verify and can then be mapped back to the current strings at the adapter edge.
  Date/Author: 2026-03-26 / Codex

- Decision: normalize route-fallback vault addresses inside `vault-transfer-preview` before the kernel chooses between modal and route address sources.
  Rationale: the route fallback is part of the proofed decision surface in practice, and leaving normalization to outer callers would allow mixed-case or padded route values to behave differently from modal-provided addresses. Pulling normalization into the kernel makes the contract explicit and easier to test.
  Date/Author: 2026-03-26 / Codex

## Outcomes & Retrospective

This plan is now partially executed. The repo has a checked-in Lean bootstrap under `/hyperopen/tools/formal/`, committed formal vector namespaces under `/hyperopen/test/hyperopen/formal/`, new contract namespaces for vault transfer and order-request legality, and conformance/property coverage tied into the ordinary ClojureScript test suite.

If the implementation follows this plan successfully, the repository will gain a new analysis/tooling surface under `/hyperopen/tools/formal/`, new committed conformance artifacts, and tighter property coverage around two safety-critical business kernels. That will slightly increase local-tooling complexity in the short term because there will be a new optional proof toolchain to install, but it should reduce long-term product complexity by making “what is actually legal to emit” precise, reproducible, and machine-checked instead of relying only on example tests and reviewer memory.

The main thing to watch during implementation is scope creep. If the work starts drifting into websocket proofs, view-model behavior, locale parsing across all inputs, or full submit-flow proof obligations, stop and file follow-up `bd` work instead of widening this first proof program.

Validation completed in this slice:

- `npm run formal:verify -- --surface vault-transfer`
- `npm run formal:sync -- --surface vault-transfer`
- `npm run formal:verify -- --surface order-request-standard`
- `npm run formal:sync -- --surface order-request-standard`
- `npm run formal:verify -- --surface order-request-advanced`
- `npm run formal:sync -- --surface order-request-advanced`
- `npm test`
- `npm run test:websocket`
- `npm run check`

All of the commands above passed on 2026-03-26. The main remaining gap relative to the original plan is automation depth: `formal:sync` currently refreshes deterministic surface manifests, but not the checked-in ClojureScript vector namespaces, and the targeted module mutation runs for the proof kernels still need to be executed and evaluated before this plan should be marked complete.

## Context and Orientation

This plan uses four terms of art repeatedly.

A “proof kernel” is the smallest pure function set that carries the safety rule we care about. In this repository, the first proof kernel is `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`, and the second proof kernel is the combination of `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`, `/hyperopen/src/hyperopen/domain/trading/core.cljs`, and `/hyperopen/src/hyperopen/domain/trading/market.cljs`.

A “reference model” is an executable copy of the business rule written in the proof language. It is not the production implementation and should stay small, typed, and explicit. The model must use exact integers or exact decimal encodings where the production code currently relies on JavaScript numbers.

A “conformance vector” is a checked-in test case emitted from the reference model and consumed by the ClojureScript test suite. The vectors are the bridge between formal verification and normal repo validation. They let `npm test` prove that the current production implementation still matches the verified model without forcing every contributor machine to run Lean on every test command.

A “submit-ready contract” is a normalized input contract that holds after the existing preparation code has done its work. For orders, this includes asset identity resolution, market-price preparation, and deterministic form normalization. A “raw builder observational contract” is weaker: it records how the public builder behaves when called directly, including nil/fail-closed outputs, but does not assume the caller came through `submit-policy`.

The vault transfer track starts in `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs`. The parser `parse-usdc-micros`, the deposit predicate `vault-transfer-deposit-allowed?`, and the preview builder `vault-transfer-preview` are already pure. Route parsing, modal state management, and effect sequencing live elsewhere: `/hyperopen/src/hyperopen/vaults/application/transfer_commands.cljs`, `/hyperopen/src/hyperopen/vaults/detail/transfer.cljs`, `/hyperopen/src/hyperopen/vaults/infrastructure/routes.cljs`, and `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`. Those outer seams need conformance tests, not proof-core logic.

The order request track spans more files. `/hyperopen/src/hyperopen/state/trading.cljs` prepares forms and resolves market context. `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs` emits the actual request shapes, including TP/SL attachments, `updateLeverage` pre-actions, scale ladders, and TWAP requests. `/hyperopen/src/hyperopen/domain/trading/core.cljs` owns scale and TWAP arithmetic, and `/hyperopen/src/hyperopen/domain/trading/market.cljs` owns price and base-size canonicalization. Existing anchors already exist in `/hyperopen/test/hyperopen/api/gateway/orders/commands_test.cljs`, `/hyperopen/test/hyperopen/state/trading/order_request_test.cljs`, `/hyperopen/test/hyperopen/state/trading/identity_and_submit_policy_test.cljs`, `/hyperopen/test/hyperopen/state/trading/validation_and_scale_test.cljs`, and `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_failures_test.cljs`.

The current toolchain matters. This repository already uses Node, Shadow CLJS, Babashka, property testing via `org.clojure/test.check`, and mutation tooling via `bb tools/mutate.clj`. It does not currently have a proof language installed. The implementation therefore needs to bootstrap Lean 4, define repo-local commands, and keep proof execution opt-in until the workflow is stable. The required repository gates remain `npm run check`, `npm test`, and `npm run test:websocket`.

## Plan of Work

### Milestone 1: Bootstrap the proof workflow without destabilizing normal development

Create a new repo-local tool surface under `/hyperopen/tools/formal/`. This directory should contain a small Lean 4 project under `/hyperopen/tools/formal/lean/`, a Babashka or Node wrapper that exposes stable commands, and a short README that explains how the proof workflow fits into Hyperopen. The wrapper commands must do two distinct jobs: verify the Lean proofs and emit or refresh the committed conformance vector namespaces consumed by the ClojureScript tests.

The implementation should add package scripts such as `npm run formal:verify` and `npm run formal:sync` so contributors can discover the feature from the same surface they already use for mutation testing and browser tooling. These commands should fail fast with a clear message when Lean is missing and should print the exact install step rather than failing with a raw shell error. Do not add either command to `npm run check` in this first version.

Use `/hyperopen/target/formal/**` for transient output, logs, and intermediate vector exports. Commit only the generated ClojureScript vector namespaces or small checked-in data files that ordinary tests need. Keep everything else out of the tracked tree.

### Milestone 2: Verify the vault transfer decision kernel

Treat `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs` as the entire proof kernel for the first track. The verified model must cover three things.

First, the parser contract for `parse-usdc-micros`: for canonical decimal strings only, the result is either `nil` or an integer in the inclusive range `[0, 9007199254740991]`, and extra fractional digits are truncated rather than rounded. Locale normalization from `/hyperopen/src/hyperopen/utils/parse.cljs` is explicitly out of proof scope in this milestone. That normalization remains a caller contract backed by ordinary tests.

Second, the deposit-eligibility contract for `vault-transfer-deposit-allowed?`: deposit is permitted exactly when the vault address is canonical, the vault is not a liquidator, and either the wallet address matches the leader address or `:allow-deposits?` is exactly true. The proof plan must document the existing asymmetry in `/hyperopen/src/hyperopen/vaults/domain/identity.cljs`: name and leader can fall back from details to merged row, but `:allow-deposits?` currently comes only from details.

Third, the full decision-table contract for `vault-transfer-preview`: invalid vault address wins before deposit-disabled, deposit-disabled wins before invalid amount, and withdraw-all only bypasses amount parsing in withdraw mode. A successful preview must be self-consistent: the top-level vault address, request `:vaultAddress`, and canonical selected address are identical; `:isDeposit` matches the normalized mode; and `:usd` is positive unless this is a withdraw-all preview.

On the ClojureScript side, add an exact-shape contract namespace such as `/hyperopen/src/hyperopen/schema/vault_transfer_contracts.cljs` for normalized transfer proof inputs, preview decisions, and outbound request shape. This file should not be wired into runtime enforcement yet unless the implementation reveals a clear benefit; its main role in v1 is to keep adapters and property tests honest. Add a property suite such as `/hyperopen/test/hyperopen/vaults/domain/transfer_policy_properties_test.cljs` and extend the existing example suites to cover liquidator blocking, leader override, merged-row fallback, and explicit route-fallback assumptions.

The proof model should emit a committed vector namespace, for example `/hyperopen/test/hyperopen/formal/vault_transfer_vectors.cljs`. The production tests should compare `parse-usdc-micros` and `vault-transfer-preview` against that corpus and then separately exercise the wrapper flows in `/hyperopen/src/hyperopen/vaults/application/transfer_commands.cljs` and `/hyperopen/src/hyperopen/vaults/detail/transfer.cljs` to prove the UI-facing adapters still feed the kernel correctly.

### Milestone 3: Verify order request construction for standard orders and leverage pre-actions

Start the order-request track by locking down the narrowest high-value core: standard order types, TP/SL attachment shape, asset identity resolution, canonical price representability, and `updateLeverage` pre-actions. This phase should not attempt scale or TWAP yet.

Define a new exact-shape contract namespace such as `/hyperopen/src/hyperopen/schema/order_request_contracts.cljs` for normalized submit-ready proof input, legal wire-order shape, legal pre-action shape, and the fail-closed nil cases. This is not the same as the broad runtime effect contracts in `/hyperopen/src/hyperopen/schema/contracts/effect_args.cljs`; it should be specific to order-builder legality. The production builder remains `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`.

The Lean model for this phase must prove at least these invariants.

Type dispatch is table-driven and exact for all supported standard order types. Named DEX markets must use canonical `asset-id` and must not silently fall back to generic `idx`. Standard order shapes must preserve the current key order required for signing-sensitive maps, must attach TP/SL on the opposite side with `:r true`, and must fail closed when an enabled TP/SL leg is invalid. Canonical price formatting must be treated as its own proof kernel: positive output only, decimal cap derived from `szDecimals`, non-integers truncated to five significant figures, and no rounding up into an illegal venue price. `updateLeverage` pre-actions must only exist for perp-like markets and must force isolated mode when cross margin is not allowed.

This phase should add a generated vector namespace such as `/hyperopen/test/hyperopen/formal/order_request_standard_vectors.cljs`, plus conformance tests in the existing order-request suites. Keep the raw-builder observational contract separate from the submit-ready contract in the test names and documentation. The submit-ready contract may use normalized contexts prepared by helpers that mimic `/hyperopen/src/hyperopen/state/trading.cljs`, but the raw-builder contract must still record fail-closed behavior for weaker direct inputs because other pure callers use the builders directly.

### Milestone 4: Extend order request verification to scale and TWAP

After the standard-order phase is green, extend the proof model to the special-order arithmetic that makes order construction risky: scale ladders and TWAP sizing. The proof model for `/hyperopen/src/hyperopen/domain/trading/core.cljs` must use exact arithmetic and then compare the production string outputs after final canonicalization.

For scale orders, prove that valid counts stay within the supported range, weights sum to one, the endpoint ratio matches skew, prices form the expected arithmetic progression from start to end, and every generated leg size is floored to `szDecimals` rather than rounded. The conformance tests should also assert that the builder never creates an empty or negative leg and that the total of all leg sizes does not exceed the requested total after flooring. The existing regression anchors in `/hyperopen/test/hyperopen/state/trading/validation_and_scale_test.cljs` should remain and gain proof-backed vector coverage.

For TWAP, prove the runtime bounds, suborder-count formula, positive suborder notional requirement, and the final request-shape invariants in `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`. The vector corpus for this phase can either extend the standard-order namespace or live in a second generated namespace such as `/hyperopen/test/hyperopen/formal/order_request_advanced_vectors.cljs`.

This milestone should also capture any still-open mismatch between the exact-decimal proof model and the production JavaScript serialization. If conformance mismatches appear, record them in this plan, minimize the counterexample, and either patch the production code or narrow the proof domain explicitly. Do not silently weaken the model to “whatever JS did.”

### Milestone 5: Validate the proof pipeline, not just the proofs

Once both tracks are implemented, validate the whole workflow as a Hyperopen-owned tool. `npm run formal:verify` should prove the Lean model and confirm the committed vector namespaces are current. `npm run formal:sync` should regenerate the vector namespaces deterministically. `npm test` must consume the committed vectors and pass without Lean installed. `npm run check`, `npm test`, and `npm run test:websocket` must still pass after the new files land.

Also use the repo-local mutation tool on the proof kernels after the conformance suites exist. `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs` is already a known mutation hotspot, and the order-request work should target `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs`, `/hyperopen/src/hyperopen/domain/trading/market.cljs`, and the touched arithmetic helper lines in `/hyperopen/src/hyperopen/domain/trading/core.cljs`. The purpose is not to chase a perfect score. It is to prove that the new proof-backed tests actually kill meaningful regressions on the same kernels the proof work claims to defend.

## Concrete Steps

From `/Users/barry/.codex/worktrees/57c2/hyperopen`:

1. Install Lean 4 locally if it is missing.

       curl https://raw.githubusercontent.com/leanprover/elan/master/elan-init.sh -sSf | sh -s -- -y
       export PATH="$HOME/.elan/bin:$PATH"
       lean --version
       lake --version

   Expect `lean --version` and `lake --version` to print concrete version strings. If they do not, stop here and fix the local toolchain before adding repo files.

2. Add the proof workspace and wrappers.

   Create and wire:

   - `/hyperopen/tools/formal/README.md`
   - `/hyperopen/tools/formal/lean/lean-toolchain`
   - `/hyperopen/tools/formal/lean/lakefile.toml`
   - `/hyperopen/tools/formal/lean/Hyperopen/Formal/Common.lean`
   - `/hyperopen/tools/formal/lean/Hyperopen/Formal/VaultTransfer.lean`
   - `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Common.lean`
   - `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Standard.lean`
   - `/hyperopen/tools/formal/lean/Hyperopen/Formal/OrderRequest/Advanced.lean`
   - `/hyperopen/tools/formal/lean/Main.lean`
   - `/hyperopen/tools/formal.clj`
   - package scripts in `/hyperopen/package.json` for `formal:verify` and `formal:sync`
   - a short tooling entry in `/hyperopen/docs/tools.md`

3. Add vault-transfer contracts, vectors, and tests.

   Create or update:

   - `/hyperopen/src/hyperopen/schema/vault_transfer_contracts.cljs`
   - `/hyperopen/test/hyperopen/formal/vault_transfer_vectors.cljs`
   - `/hyperopen/test/hyperopen/vaults/domain/transfer_policy_properties_test.cljs`
   - `/hyperopen/test/hyperopen/vaults/domain/transfer_policy_test.cljs`
   - `/hyperopen/test/hyperopen/vaults/application/transfer_commands_test.cljs`
   - `/hyperopen/test/hyperopen/vaults/effects_test.cljs`

4. Add order-request contracts, vectors, and tests in two passes.

   Standard and leverage pass:

   - `/hyperopen/src/hyperopen/schema/order_request_contracts.cljs`
   - `/hyperopen/test/hyperopen/formal/order_request_standard_vectors.cljs`
   - `/hyperopen/test/hyperopen/api/gateway/orders/commands_test.cljs`
   - `/hyperopen/test/hyperopen/state/trading/order_request_test.cljs`
   - `/hyperopen/test/hyperopen/state/trading/identity_and_submit_policy_test.cljs`
   - `/hyperopen/test/hyperopen/core_bootstrap/order_effects/submit_failures_test.cljs`

   Scale and TWAP pass:

   - `/hyperopen/test/hyperopen/formal/order_request_advanced_vectors.cljs`
   - `/hyperopen/test/hyperopen/state/trading/validation_and_scale_test.cljs`
   - `/hyperopen/test/hyperopen/api/gateway/orders/commands_test.cljs`

5. Run the formal proof commands and sync the committed vectors.

       export PATH="$HOME/.elan/bin:$PATH"
       npm run formal:verify -- --surface vault-transfer
       npm run formal:sync -- --surface vault-transfer
       npm run formal:verify -- --surface order-request-standard
       npm run formal:sync -- --surface order-request-standard
       npm run formal:verify -- --surface order-request-advanced
       npm run formal:sync -- --surface order-request-advanced

   Expect each `formal:verify` run to report successful Lean verification and a clean freshness check for the corresponding committed vectors.

6. Run the repository gates and targeted mutation follow-ups.

       npm test
       npm run test:websocket
       npm run check
       bb tools/mutate.clj --module src/hyperopen/vaults/domain/transfer_policy.cljs --suite test --mutate-all
       bb tools/mutate.clj --module src/hyperopen/api/gateway/orders/commands.cljs --suite test --mutate-all

   If the order-request mutation run is too slow, rerun it on the touched builder lines after the first full pass and record the narrowed command in this plan.

## Validation and Acceptance

Acceptance is met only when both the proof workflow and the normal repo workflow are green.

For the vault-transfer track, `npm run formal:verify -- --surface vault-transfer` must pass, the committed vault vector namespace must refresh deterministically, and the ClojureScript suites must prove that `parse-usdc-micros` and `vault-transfer-preview` match the verified model for parser boundaries, deposit-eligibility truth-table cases, decision precedence, and success self-consistency. Existing wrapper behavior in transfer commands and vault detail must still work, and the runtime effect-order contract for `:actions/submit-vault-transfer` must remain satisfied.

For the order-request track, the standard-order proof phase must pass before the advanced phase begins. The standard phase must prove type dispatch, asset identity, canonical price representability, standard wire shape, TP/SL attachment legality, leverage pre-actions, and signing-sensitive key order. The advanced phase must prove scale and TWAP arithmetic invariants and verify that the production builders still match the model. Any mismatch between the exact-decimal model and the production JavaScript behavior must produce a minimized counterexample and either a production fix or a documented proof-domain restriction.

For the repository as a whole, `npm test`, `npm run test:websocket`, and `npm run check` must pass after the new proof tooling and test files land. Normal test execution must not require Lean to be installed. The committed vector namespaces are what tie the proof model back into ordinary ClojureScript validation.

Mutation evidence is also part of acceptance for these proof kernels. The transfer-policy mutation run must not regress versus the current known hotspot baseline, and the order-request mutation run must demonstrate that the new proof-backed tests kill meaningful mutants in the builder logic rather than only leaving the proof model green in isolation.

## Idempotence and Recovery

The proof workflow must be safe to rerun. `npm run formal:sync` should overwrite generated vector namespaces deterministically, not append to them. `npm run formal:verify` should be read-only apart from transient files under `/hyperopen/target/formal/**`. If Lean is missing or misconfigured, the wrapper should print the exact install or PATH repair step and exit before touching tracked files.

The production behavior should remain stable unless the proofs expose a bug. If a conformance vector disagrees with production code, first record the failing case in this plan and the corresponding tests. Only then decide whether to patch the product code, narrow the proof domain, or split a follow-up `bd` issue. Do not silently weaken the model or delete failing cases to make the pipeline green.

If a contributor needs to work without Lean, they should still be able to run `npm test`, `npm run test:websocket`, and `npm run check` on the committed vectors. The only unavailable commands in that situation should be `formal:verify` and `formal:sync`.

## Artifacts and Notes

Important current-state evidence that should remain true while this plan is active:

- `/hyperopen/src/hyperopen/vaults/domain/transfer_policy.cljs` is already a pure kernel with public functions for parser, deposit eligibility, and preview construction.
- `/hyperopen/test/hyperopen/vaults/domain/transfer_policy_test.cljs` already covers integer parsing, truncation, zero, max-safe bound, overflow, route fallback, withdraw-all, and smallest positive amount.
- `/hyperopen/src/hyperopen/api/gateway/orders/commands.cljs` is the actual order-action builder and already owns standard-order shapes, TP/SL attachments, scale, TWAP, and leverage pre-actions.
- `/hyperopen/test/hyperopen/state/trading/order_request_test.cljs` already protects canonical key order and HIP3 canonical asset-id behavior.
- `/hyperopen/test/hyperopen/state/trading/validation_and_scale_test.cljs` already anchors many of the arithmetic properties the proof model should formalize instead of replacing.

The generated proof vectors should be treated the same way Hyperopen treats signing known vectors: small, committed, reviewable, and deterministic. If the action wire shape changes for vault transfers or orders, update the vectors and document why the shape change is safe.

## Interfaces and Dependencies

The final implementation must expose these stable interfaces and paths:

- `npm run formal:verify -- --surface <vault-transfer|order-request-standard|order-request-advanced>`
- `npm run formal:sync -- --surface <vault-transfer|order-request-standard|order-request-advanced>`
- `/hyperopen/tools/formal.clj` as the repo-local command wrapper that shells out to Lean and manages vector freshness.
- `/hyperopen/tools/formal/lean/` as the only proof-language workspace for this first program.
- `/hyperopen/test/hyperopen/formal/*.cljs` as the committed vector bridge consumed by ordinary tests.
- `/hyperopen/src/hyperopen/schema/vault_transfer_contracts.cljs` and `/hyperopen/src/hyperopen/schema/order_request_contracts.cljs` as exact-shape adapter contracts for proof inputs and outputs.

The Lean model should define, at minimum, executable functions equivalent in meaning to:

- `Hyperopen.Formal.VaultTransfer.parseUsdcMicros`
- `Hyperopen.Formal.VaultTransfer.depositAllowed`
- `Hyperopen.Formal.VaultTransfer.preview`
- `Hyperopen.Formal.OrderRequest.canonicalPrice`
- `Hyperopen.Formal.OrderRequest.buildStandard`
- `Hyperopen.Formal.OrderRequest.buildPreActions`
- `Hyperopen.Formal.OrderRequest.buildScale`
- `Hyperopen.Formal.OrderRequest.buildTwap`

The wrapper layer should also define one stable export format for generated vectors. Prefer plain EDN-shaped data rendered as a small generated ClojureScript namespace so the normal test suite can require it directly without runtime file I/O.

This plan depends on the existing Hyperopen toolchain plus Lean 4 installed through `elan`. It deliberately does not depend on Dafny, TLA+, Docker, or a remote service for these first two proof tracks.

Revision note (2026-03-26): Initial ExecPlan created for `hyperopen-8k7a` after auditing the vault transfer and order request kernels, the existing test anchors, repo planning rules, and the current absence of a formal-methods toolchain in the workspace.
