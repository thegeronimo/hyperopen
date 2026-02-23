# Fee Crossout Surface-Area Reduction and Market Metadata Boundary Refactor

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The fee crossout parity change delivered the correct behavior, but required edits across a broad slice of the codebase because perp DEX normalization, fee context assembly, compatibility parsing, and display modeling are spread across many modules. This plan reduces that change surface so the next fee-related tweak is localized to a small, intentional set of files.

After this work, a developer should be able to change fee display behavior by editing a dedicated fee-display module and its tests, without touching API compatibility, market-loader, order effects, and state wiring in multiple places.

## Progress

- [x] (2026-02-23 16:33Z) Authored this ExecPlan and mapped all six requested refactor items to concrete repository files.
- [x] (2026-02-23 16:41Z) Implemented canonical perp DEX normalization in `/hyperopen/src/hyperopen/api/market_metadata/perp_dexs.cljs` and removed duplicate parsing helpers from service/projections/fetch-compat/market-loader/order-effects/endpoints.
- [x] (2026-02-23 16:50Z) Introduced `/hyperopen/src/hyperopen/state/trading/fee_context.cljs`, delegated fee input extraction from `/hyperopen/src/hyperopen/state/trading.cljs`, and passed explicit fee-context into `/hyperopen/src/hyperopen/domain/trading/market.cljs` order-summary fee quote computation.
- [ ] Add API payload models and boundary contracts for market gateway perp DEX payloads.
- [ ] Collapse compatibility parsing/wiring behind one market-metadata facade.
- [ ] Remove manual test registration friction from `/hyperopen/test/test_runner.cljs`.
- [ ] Add a feature module boundary for order-summary display models.
- [x] (2026-02-23 16:51Z) Ran validation gates `npm run check`, `npm test`, and `npm run test:websocket`; all commands passed with zero failures.
- [x] (2026-02-23 16:41Z) Ran `npm test` after Milestone 1 refactor; suite passed with zero failures.
- [x] (2026-02-23 16:50Z) Ran `npm test` after Milestone 2 fee-context selector refactor; suite passed with zero failures.

## Surprises & Discoveries

- Observation: Perp DEX normalization/parsing logic is duplicated in several namespaces with near-identical shape handling.
  Evidence: `/hyperopen/src/hyperopen/api/service.cljs`, `/hyperopen/src/hyperopen/api/projections.cljs`, `/hyperopen/src/hyperopen/api/fetch_compat.cljs`, `/hyperopen/src/hyperopen/api/market_loader.cljs`, and `/hyperopen/src/hyperopen/order/effects.cljs`.
- Observation: Fee context assembly currently lives inside the broad `trading-context` constructor, coupling fee-specific inputs to unrelated order-form context fields.
  Evidence: `/hyperopen/src/hyperopen/state/trading.cljs` private function `trading-context`.
- Observation: Test registration requires duplicate manual edits for every new test namespace.
  Evidence: `/hyperopen/test/test_runner.cljs` keeps both a large `:require` list and a second `run-tests` namespace list.
- Observation: Order summary display formatting is mixed into presenter code, with VM selectors only forwarding through.
  Evidence: `/hyperopen/src/hyperopen/views/trade/order_form_presenter.cljs` and `/hyperopen/src/hyperopen/views/trade/order_form_vm_selectors.cljs`.
- Observation: Endpoint payload parsing was also a duplicate normalization branch and needed to be folded into the same canonical path to avoid drift.
  Evidence: `/hyperopen/src/hyperopen/api/endpoints/market.cljs` had a separate `dex-payload-from-response` implementation before this milestone.

## Decision Log

- Decision: Prioritize consolidation and boundary creation before behavior changes.
  Rationale: The user-visible fee behavior is already correct; reducing future change amplification requires structural cleanup first.
  Date/Author: 2026-02-23 / Codex
- Decision: Keep existing public API entry points stable and introduce facades/contracts behind those entry points.
  Rationale: This preserves backward compatibility while reducing internals duplication.
  Date/Author: 2026-02-23 / Codex
- Decision: Solve test-runner friction with deterministic generation, not another hand-maintained list.
  Rationale: Manual dual-list maintenance is the root cause; generation removes the repeated human step.
  Date/Author: 2026-02-23 / Codex
- Decision: Make endpoint `request-perp-dexs!` call the same canonical normalizer used by service/projections/compat layers.
  Rationale: Keeping one parser for both raw endpoint responses and downstream payload reuse eliminates branch drift and keeps behavior identical across call sites.
  Date/Author: 2026-02-23 / Codex

## Outcomes & Retrospective

Milestones 1 and 2 are complete. Perp DEX normalization now lives in one canonical module and duplicated payload-shape helpers were removed from the targeted API and order-effect call sites. The canonical module is now used by endpoint response parsing, service single-flight normalization, store projections, compat fetch helpers, market-loader dex extraction, and per-dex open-order refresh logic.

Fee inputs for order-summary are now extracted through dedicated selector `/hyperopen/src/hyperopen/state/trading/fee_context.cljs` and passed as an explicit fee-context object into `/hyperopen/src/hyperopen/domain/trading/market.cljs` order-summary computation. Validation at this checkpoint passed for `npm run check`, `npm test`, and `npm run test:websocket` with zero failures. Remaining milestones in this plan are still pending.

## Context and Orientation

The current fee pipeline crosses API payload shaping, app-state context assembly, domain summary computation, presenter formatting, and view rendering.

Perp DEX payloads originate at `/hyperopen/src/hyperopen/api/endpoints/market.cljs` (`request-perp-dexs!`), then are re-normalized in multiple places as either vectors of names or maps with fee metadata. Because each consumer has its own parsing helper, small payload-shape evolution propagates broadly.

Order summary now uses `/hyperopen/src/hyperopen/state/trading/fee_context.cljs` to extract fee-specific inputs (`:user-fees`, market growth mode, stable-pair flag, perp dex fee scale, and special quote adjustment), and `/hyperopen/src/hyperopen/domain/trading/market.cljs` consumes that explicit fee-context object during summary computation.

Display formatting for order summary lives in `/hyperopen/src/hyperopen/views/trade/order_form_presenter.cljs`, with selector passthrough in `/hyperopen/src/hyperopen/views/trade/order_form_vm_selectors.cljs`, and contracts in `/hyperopen/src/hyperopen/schema/order_form_contracts.cljs`.

The test runner at `/hyperopen/test/test_runner.cljs` currently requires manual namespace registration in two places, which amplifies operational toil for test additions.

## Plan of Work

### Milestone 1: Canonical Perp DEX Normalization Module Reused Everywhere

Create one canonical normalization namespace at `/hyperopen/src/hyperopen/api/market_metadata/perp_dexs.cljs` that owns parsing of raw perp DEX payloads into a single canonical model. Move payload-shape logic out of `/hyperopen/src/hyperopen/api/service.cljs`, `/hyperopen/src/hyperopen/api/projections.cljs`, `/hyperopen/src/hyperopen/api/fetch_compat.cljs`, `/hyperopen/src/hyperopen/api/market_loader.cljs`, and `/hyperopen/src/hyperopen/order/effects.cljs` and replace with calls to this shared module.

The canonical model must represent both normalized names and fee config map with deterministic defaults. Existing behavior must remain stable for map-form, vector-form, and empty payloads.

### Milestone 2: Dedicated Fee Context Selector from App State

Add `/hyperopen/src/hyperopen/state/trading/fee_context.cljs` with a pure selector that extracts only fee-relevant inputs from app state for the active market. Update `/hyperopen/src/hyperopen/state/trading.cljs` to delegate fee-specific context extraction to this selector and pass the resulting fee context into order-summary domain calls.

Update `/hyperopen/src/hyperopen/domain/trading/market.cljs` so fee quote computation consumes this explicit fee-context object rather than pulling fee-related fields ad hoc from the broader trading context.

### Milestone 3: API Payload Models and Contracts at Gateway Boundary

Add gateway boundary contracts in `/hyperopen/src/hyperopen/schema/api_market_contracts.cljs` for normalized perp DEX metadata payloads. The market gateway path in `/hyperopen/src/hyperopen/api/gateway/market.cljs` must validate or assert canonical payload shape before passing data further into compatibility/projection layers.

Add contract-focused tests at `/hyperopen/test/hyperopen/schema/api_market_contracts_test.cljs` and gateway integration checks at `/hyperopen/test/hyperopen/api/gateway/market_test.cljs`.

### Milestone 4: Collapse Compatibility Layers Behind One Market-Metadata Facade

Introduce `/hyperopen/src/hyperopen/api/market_metadata/facade.cljs` as the single compatibility seam for “ensure perp dex metadata, project to store, expose dex names for downstream loops.” Refactor `/hyperopen/src/hyperopen/api/fetch_compat.cljs`, `/hyperopen/src/hyperopen/api/market_loader.cljs`, and `/hyperopen/src/hyperopen/order/effects.cljs` to call this facade rather than duplicating payload handling and name extraction.

Keep public compatibility functions intact, but make them thin delegates into the new facade so payload-shape branching is centralized.

### Milestone 5: Remove Manual Test Registration Friction in `test/test_runner.cljs`

Add a deterministic generator script at `/hyperopen/tools/generate-test-runner.mjs` that scans `/hyperopen/test/hyperopen/**/*_test.cljs`, derives namespace names, and writes `/hyperopen/test/test_runner_generated.cljs` with sorted `:require` and `run-tests` entries. Reduce `/hyperopen/test/test_runner.cljs` to requiring the generated namespace and invoking a single exported runner function.

Wire generation into repository test workflow so adding a new test file no longer requires hand-editing the test runner.

### Milestone 6: Add Feature Module Boundary for Order-Summary Display Models

Create `/hyperopen/src/hyperopen/views/trade/order_form_summary_display.cljs` to own the order-summary display model construction and fee-row display formatting (effective and optional baseline crossout). Keep `/hyperopen/src/hyperopen/views/trade/order_form_presenter.cljs` as a compatibility facade or retire it after updating call sites.

Update `/hyperopen/src/hyperopen/views/trade/order_form_vm_selectors.cljs` and related tests to consume the new module directly. Keep `/hyperopen/src/hyperopen/schema/order_form_contracts.cljs` aligned to the same shape so display contracts remain explicit and local to this feature boundary.

## Concrete Steps

From `/Users//projects/hyperopen`:

1. Implement Milestone 1 canonical normalization module and migrate call sites.

   cd /Users//projects/hyperopen
   rg -n "normalize-perp-dexs-payload|perp-dex-names|deployer-fee-scale" src/hyperopen test/hyperopen
   npm test

   Expected indicator: all existing perp DEX payload consumers compile and tests pass using shared normalization.
   Result: completed (2026-02-23 16:41Z); duplicate helpers removed and `npm test` passed.

2. Implement Milestone 2 fee-context selector and wire order-summary fee quote consumption.

   cd /Users//projects/hyperopen
   npm test

   Expected indicator: trading summary tests stay green and fee behavior is unchanged.

3. Implement Milestone 3 gateway contracts and tests.

   cd /Users//projects/hyperopen
   npm test

   Expected indicator: contract tests and gateway tests fail before contract wiring and pass after.

4. Implement Milestones 4 and 5 (market-metadata facade and generated test runner flow).

   cd /Users//projects/hyperopen
   npm test

   Expected indicator: compatibility paths still pass, and adding a new `_test.cljs` file only requires running generator, not hand-editing `test_runner.cljs`.

5. Implement Milestone 6 summary display boundary extraction.

   cd /Users//projects/hyperopen
   npm test

   Expected indicator: order-form presenter/VM/view tests pass with no behavior regression.

6. Run required validation gates.

   cd /Users//projects/hyperopen
   npm run check
   npm test
   npm run test:websocket

   Expected indicator: all three commands exit successfully.

## Validation and Acceptance

Acceptance criteria for this plan:

1. Perp DEX payload normalization exists in one canonical module, and duplicate helper implementations are removed from prior call sites.
2. A dedicated fee-context selector exists and is the single state extraction path for fee quote inputs.
3. Market gateway boundary has explicit payload contracts for normalized perp DEX metadata and regression tests for contract enforcement.
4. Compatibility layers consume one market-metadata facade instead of duplicating parsing/projection branching.
5. `test/test_runner.cljs` no longer requires manual dual-list maintenance for each new test namespace.
6. Order-summary display model logic is isolated in a feature module with stable VM/view behavior and updated tests.
7. `npm run check`, `npm test`, and `npm run test:websocket` pass.

## Idempotence and Recovery

This plan is additive-first and can be executed incrementally. Each milestone can land independently with passing tests before moving on.

If regression appears during facade consolidation, keep the new canonical module and temporarily route the affected caller back to previous local behavior while preserving shared-model tests. For test-runner generation, generation output must be deterministic and safely re-runnable; recovery is to re-run generator and commit the regenerated file.

## Artifacts and Notes

Current duplication snapshot for this refactor:

    src/hyperopen/api/market_metadata/perp_dexs.cljs ; canonical normalize-perp-dex-payload + payload->dex-names

Milestone 1 migration notes:

    src/hyperopen/api/service.cljs           ; uses perp-dexs/normalize-perp-dex-payload
    src/hyperopen/api/projections.cljs       ; uses perp-dexs/normalize-perp-dex-payload
    src/hyperopen/api/fetch_compat.cljs      ; uses perp-dexs/payload->dex-names
    src/hyperopen/api/market_loader.cljs     ; uses perp-dexs/payload->dex-names
    src/hyperopen/order/effects.cljs         ; uses perp-dexs/payload->dex-names
    src/hyperopen/api/endpoints/market.cljs  ; uses perp-dexs/normalize-perp-dex-payload

Current friction snapshot for test registration:

    /hyperopen/test/test_runner.cljs has both:
      - namespace requires for each test namespace
      - run-tests arguments for each test namespace

## Interfaces and Dependencies

Target interfaces at completion:

- In `/hyperopen/src/hyperopen/api/market_metadata/perp_dexs.cljs`, define canonical helpers:

    (defn normalize-perp-dex-payload [payload]
      ;; => {:dex-names [string ...]
      ;;     :fee-config-by-name {string {:deployer-fee-scale number}}}
      ...)

    (defn payload->dex-names [payload] ...)

- In `/hyperopen/src/hyperopen/state/trading/fee_context.cljs`, define:

    (defn select-fee-context [state]
      ;; => {:market-type keyword
      ;;     :stable-pair? boolean
      ;;     :growth-mode? boolean
      ;;     :dex string-or-nil
      ;;     :deployer-fee-scale number-or-nil
      ;;     :special-quote-fee-adjustment? boolean
      ;;     :user-fees map-or-nil}
      ...)

- In `/hyperopen/src/hyperopen/api/market_metadata/facade.cljs`, define:

    (defn ensure-and-apply-perp-dex-metadata!
      [{:keys [store ensure-perp-dexs-data! apply-perp-dexs-success apply-perp-dexs-error]}
       opts]
      ;; resolves canonical payload and applies projections once
      ...)

- In `/hyperopen/src/hyperopen/schema/api_market_contracts.cljs`, define specs/assertions for normalized perp DEX payload shape used by market gateway.

- In `/hyperopen/src/hyperopen/views/trade/order_form_summary_display.cljs`, define:

    (defn summary-display [summary sz-decimals]
      ;; returns the display map currently consumed by order form view
      ...)

Dependencies to preserve:

- Public API call sites through `/hyperopen/src/hyperopen/api/default.cljs` and `/hyperopen/src/hyperopen/api/instance.cljs` must remain backward compatible.
- Existing order-form contracts in `/hyperopen/src/hyperopen/schema/order_form_contracts.cljs` must remain valid after display boundary extraction.

Plan revision note: 2026-02-23 16:33Z - Initial plan created to execute six-item refactor reducing change amplification from fee crossout parity follow-up work.
Plan revision note: 2026-02-23 16:41Z - Completed Milestone 1 by introducing canonical perp DEX normalization and migrating duplicate parser call sites; recorded passing `npm test` evidence.
