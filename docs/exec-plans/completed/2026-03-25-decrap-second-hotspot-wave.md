# DeCRAP Second Hotspot Wave

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-3ufd`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

The current CRAP hotspots are spread across ten functions that are either branch-heavy pure normalizers or route/view helpers with weak direct coverage. After this change, the same funding, websocket, staking, account-info, and vault-detail behaviors should remain intact for users, but the implementation will be split into smaller local helpers and backed by focused regressions that directly cover the reported hotspots. A contributor should be able to run the targeted tests, the repository gates, and a refreshed CRAP report and see that the listed hotspots are either reduced directly or replaced by smaller covered helpers.

## Progress

- [x] (2026-03-25 20:45Z) Created and claimed `hyperopen-3ufd` for this deCRAP wave.
- [x] (2026-03-25 20:46Z) Audited `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, and the prior completed deCRAP ExecPlans.
- [x] (2026-03-25 20:58Z) Launched fresh-context per-function analysis agents and incorporated completed findings for `positions-table`, `funding-row-sort-id`, `make-info-client`, and `normalize-trade-for-view`.
- [x] (2026-03-25 21:02Z) Completed direct local audits for all ten hotspots, their current tests, and the relevant surrounding helper seams.
- [x] (2026-03-26 00:45Z) Implemented the four approved refactor buckets across UI/account-info, funding, api/websocket, and staking, and widened direct tests for each hotspot.
- [x] (2026-03-26 01:12Z) Repaired three test expectation bugs uncovered by the broadened coverage, reran `npm test`, `npm run test:websocket`, `npm run check`, `npm run coverage`, and refreshed CRAP output from the merged coverage artifacts.
- [x] (2026-03-26 01:21Z) Accounted for required browser validation with `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs` and `npm run qa:design-ui -- --targets portfolio-route,trader-portfolio-route,vault-detail-route --manage-local-app`.

## Surprises & Discoveries

- Observation: the highest-coverage hotspots are still expensive because they are structurally concentrated rather than under-tested.
  Evidence: `make-info-client` reports coverage `0.9748` and `normalize-open-order` reports coverage `1.0`, but both still carry high CRAP because large fallback and orchestration branches remain in one function.

- Observation: several low-coverage hotspots already have route-level or action-level tests, but almost no direct helper tests.
  Evidence: `positions-table`, `withdraw-preview`, `normalize-trade-for-view`, `submit-usdt-lifi-bridge2-deposit-tx!`, and `submitting-key` are either untested or only exercised indirectly through larger flows.

- Observation: `funding-row-sort-id` duplicates canonical funding-history normalization precedence that already exists in `/hyperopen/src/hyperopen/domain/funding_history.cljs`.
  Evidence: the domain helper `funding-history-row-id` already builds the canonical `time|coin|signed-size|payment|rate` id, while the view layer rebuilds that string from raw fallback chains.

- Observation: the only clearly UI-facing hotspot in this wave is `positions-table`, but `funding-row-sort-id` also lives under `/hyperopen/src/hyperopen/views/**`, so browser QA still needs to account for both touched routes.
  Evidence: `/hyperopen/src/hyperopen/views/vaults/detail/activity.cljs` and `/hyperopen/src/hyperopen/views/account_info/tabs/funding_history.cljs` both fall under the governed UI tree in `AGENTS.md`.

- Observation: the repository `npm test` script does not support the planned `--focus` flag, so focused validation had to come from direct namespace tests and then the full suite.
  Evidence: `node out/test.js --focus ...` exits with `Unknown arg: --focus` and still runs the full generated suite.

- Observation: `npm run check` initially failed on pre-existing governance debt outside this ticket because two closed `bd` issues still had ExecPlans under `/hyperopen/docs/exec-plans/active/`.
  Evidence: `dev.check-docs` flagged `2026-03-21-design-review-pass-registry-and-honest-outcomes.md` (`hyperopen-ks45`) and `2026-03-24-design-review-blocked-false-positives-and-startup-readiness.md` (`hyperopen-jm4w`) as stale active plans; moving them to `/completed/` cleared the docs gate.

- Observation: helper extraction reduced CRAP as intended, but several already-oversized namespaces crossed the line-count gate and needed explicit debt accounting.
  Evidence: after the refactors, `npm run lint:namespace-sizes` failed on `/hyperopen/src/hyperopen/api/info_client.cljs`, `/hyperopen/src/hyperopen/funding/domain/policy.cljs`, `/hyperopen/src/hyperopen/staking/actions.cljs`, `/hyperopen/src/hyperopen/views/account_info/projections/orders.cljs`, and `/hyperopen/src/hyperopen/views/vaults/detail/activity.cljs` until `/hyperopen/dev/namespace_size_exceptions.edn` was updated to the new exact line counts.

## Decision Log

- Decision: keep this pass behavior-preserving and local to the reported hotspots instead of widening into cross-domain deduplication.
  Rationale: the user asked for deCRAPing the listed functions, not a broader architecture change. Local helper extraction plus direct tests reduces risk and keeps the work measurable against the CRAP report.
  Date/Author: 2026-03-25 / Codex

- Decision: group implementation into four disjoint worker buckets: UI/account-info, funding, api/websocket, and staking.
  Rationale: this matches file ownership boundaries, keeps worker write sets disjoint, and lets the parent thread integrate without cross-worker merge pressure.
  Date/Author: 2026-03-25 / Codex

- Decision: preserve all public APIs and return shapes, especially `make-info-client` arities, funding preview request payloads, websocket trade raw fields, and staking submit-state keys.
  Rationale: those contracts are already consumed by service layers, views, and action/effect wiring, and changing them would turn a CRAP pass into a behavior migration.
  Date/Author: 2026-03-25 / Codex

- Decision: run browser QA after implementation because this wave edits governed UI files under `/hyperopen/src/hyperopen/views/**`, even though most hotspots are pure helpers.
  Rationale: the repo contract requires explicit browser-QA accounting for UI work under that tree.
  Date/Author: 2026-03-25 / Codex

- Decision: keep the namespace-size guard truthful by updating the explicit exception registry rather than disguising the new line counts or widening this ticket into unrelated namespace splits.
  Rationale: the hotspot work was scoped to CRAP reduction and direct coverage, not a second SRP/DDD extraction wave. Exact exception entries keep the debt visible while preserving the local refactor goal.
  Date/Author: 2026-03-26 / Codex

- Decision: clear the docs gate by moving the two stale completed ExecPlans out of `/active/` during this ticket.
  Rationale: the check failure was unrelated to the hotspot changes but blocked the required repository gate. The fix was purely governance-alignment and preserved the historical plan contents.
  Date/Author: 2026-03-26 / Codex

## Outcomes & Retrospective

The implementation completed all four buckets without changing public contracts. The refactors pushed branching into smaller local helpers, reused existing canonical readers where available, and added direct coverage for previously indirect or uncovered cases. The refreshed CRAP report moved every listed hotspot well below its starting score:

- `positions-table`: `51.2353` -> `3.0`
- `withdraw-preview`: `50.5714` -> `2.0`
- `funding-row-sort-id`: `49.1089` -> `12.0`
- `make-info-client`: `49.0385` -> `2.0`
- `normalize-trade-for-view`: `46.5457` -> `2.794`
- `normalize-open-order`: `46.0` -> `3.0`
- `select-existing-hyperunit-deposit-address`: `45.8351` -> `5.0011`
- `submitting-key`: `43.1719` -> `1.0`
- `submit-usdt-lifi-bridge2-deposit-tx!`: `42.0` -> `2.0`
- `normalize-staking-validator-sort-column`: `41.5592` -> `3.0`

The final validation stack passed: `npm test` (`2754` tests, `14592` assertions), `npm run test:websocket` (`414` tests, `2336` assertions), `npm run check`, `npm run coverage` (`91.15%` statements/functions `85.09%`), the committed Playwright regression suite for portfolio/account-info, and the governed design review for `portfolio-route`, `trader-portfolio-route`, and `vault-detail-route`. The design-review run finished `PASS` with expected interaction blind spots on `/vaults/detail` because that audited snapshot exposes no focusable controls by default.

## Context and Orientation

This plan covers the following reported hotspots and baseline metrics:

- `/hyperopen/src/hyperopen/views/vaults/detail/activity.cljs` `positions-table` — CRAP `51.2353`, complexity `9`, coverage `0.1951`
- `/hyperopen/src/hyperopen/funding/domain/policy.cljs` `withdraw-preview` — CRAP `50.5714`, complexity `14`, coverage `0.4286`
- `/hyperopen/src/hyperopen/views/account_info/tabs/funding_history.cljs` `funding-row-sort-id` — CRAP `49.1089`, complexity `12`, coverage `0.3636`
- `/hyperopen/src/hyperopen/api/info_client.cljs` `make-info-client` — CRAP `49.0385`, complexity `49`, coverage `0.9748`
- `/hyperopen/src/hyperopen/websocket/trades.cljs` `normalize-trade-for-view` — CRAP `46.5457`, complexity `13`, coverage `0.4167`
- `/hyperopen/src/hyperopen/views/account_info/projections/orders.cljs` `normalize-open-order` — CRAP `46.0`, complexity `46`, coverage `1.0`
- `/hyperopen/src/hyperopen/funding/application/hyperunit_query.cljs` `select-existing-hyperunit-deposit-address` — CRAP `45.8351`, complexity `25`, coverage `0.6782`
- `/hyperopen/src/hyperopen/staking/effects.cljs` `submitting-key` — CRAP `43.1719`, complexity `9`, coverage `0.25`
- `/hyperopen/src/hyperopen/funding/application/deposit_submit.cljs` `submit-usdt-lifi-bridge2-deposit-tx!` — CRAP `42.0`, complexity `6`, coverage `0.0`
- `/hyperopen/src/hyperopen/staking/actions.cljs` `normalize-staking-validator-sort-column` — CRAP `41.5592`, complexity `14`, coverage `0.48`

CRAP in this repository is a risk score that rises when a function combines branch complexity with weak direct coverage. Some of these hotspots need more tests to cover existing branches, while others are already well covered and instead need smaller helper seams so one function is not carrying all of the branching itself.

The touched code falls into four buckets:

1. UI and account-info projections: `/hyperopen/src/hyperopen/views/vaults/detail/activity.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/funding_history.cljs`, and `/hyperopen/src/hyperopen/views/account_info/projections/orders.cljs`.
2. Funding domain and submission helpers: `/hyperopen/src/hyperopen/funding/domain/policy.cljs`, `/hyperopen/src/hyperopen/funding/application/hyperunit_query.cljs`, and `/hyperopen/src/hyperopen/funding/application/deposit_submit.cljs`.
3. API and websocket normalization: `/hyperopen/src/hyperopen/api/info_client.cljs` and `/hyperopen/src/hyperopen/websocket/trades.cljs`.
4. Staking action/effect normalization: `/hyperopen/src/hyperopen/staking/effects.cljs` and `/hyperopen/src/hyperopen/staking/actions.cljs`.

The most relevant existing tests are:

- `/hyperopen/test/hyperopen/views/vaults/detail/activity_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/funding_history_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/projections_test.cljs`
- `/hyperopen/test/hyperopen/funding/actions_test.cljs`
- `/hyperopen/test/hyperopen/funding/application/hyperunit_query_test.cljs`
- `/hyperopen/test/hyperopen/funding/application/deposit_submit_test.cljs`
- `/hyperopen/test/hyperopen/api/info_client_test.cljs`
- `/hyperopen/test/hyperopen/api_test.cljs`
- `/hyperopen/test/hyperopen/websocket/trades_test.cljs`
- `/hyperopen/test/hyperopen/staking/effects_test.cljs`
- `/hyperopen/test/hyperopen/staking/actions_test.cljs`

## Plan of Work

First, handle the UI/account-info bucket. In `/hyperopen/src/hyperopen/views/vaults/detail/activity.cljs`, extract row-display helpers for `positions-table` so the table function owns only shell, empty state, and iteration. Preserve the current strings, side-tone classes, `USDC` suffixes, `N/A` placeholder, and leverage badge behavior. In `/hyperopen/src/hyperopen/views/account_info/tabs/funding_history.cljs`, extract the fallback readers used to build the row tie-break id, and reuse them in the sort accessors where that duplication lowers complexity without changing key or sort behavior. In `/hyperopen/src/hyperopen/views/account_info/projections/orders.cljs`, split `normalize-open-order` into smaller private readers for root/order maps, trigger metadata, price resolution, and time/type extraction while preserving the same returned map.

Second, handle the funding bucket. In `/hyperopen/src/hyperopen/funding/domain/policy.cljs`, split `withdraw-preview` into request-building helpers and validation helpers so the top-level function becomes a small dispatch over the existing rules. In `/hyperopen/src/hyperopen/funding/application/hyperunit_query.cljs`, extract operation filtering, candidate address lookup, and fallback address matching helpers for `select-existing-hyperunit-deposit-address`, preserving the current priority order of operation-derived address, direct address match, then source-format address match. In `/hyperopen/src/hyperopen/funding/application/deposit_submit.cljs`, split `submit-usdt-lifi-bridge2-deposit-tx!` into input validation and quote/swap execution helpers, then add direct tests for the currently uncovered validation and error paths.

Third, handle the API/websocket bucket. In `/hyperopen/src/hyperopen/api/info_client.cljs`, hoist pure helpers out of `make-info-client` for request option normalization, cache TTL handling, retry math, retry planning, queue priority selection, and runtime stat transitions. Keep the atoms and returned public map in `make-info-client`, and preserve the 1/2/3-arity `request-info!` contract and single-flight promise identity. In `/hyperopen/src/hyperopen/websocket/trades.cljs`, extract alias readers or reuse `trades-policy/normalize-trade` so `normalize-trade-for-view` remains behaviorally identical while the raw-field contract stays intact.

Fourth, handle the staking bucket. In `/hyperopen/src/hyperopen/staking/effects.cljs`, replace the wide `case` in `submitting-key` with a small normalization map or helper that preserves all current aliases and the default `:deposit?`. In `/hyperopen/src/hyperopen/staking/actions.cljs`, extract string-token normalization and alias mapping from `normalize-staking-validator-sort-column`, leaving the current accepted aliases and default column unchanged.

Each bucket should add or widen tests that directly cover the hotspot branches rather than only relying on larger route-level or effect-level tests. After implementation, run focused test namespaces for the edited areas, then the required repo gates, then coverage and a refreshed CRAP report, and finally account for browser QA on the touched UI routes.

## Concrete Steps

1. Edit the UI/account-info source and test files:

   - `/hyperopen/src/hyperopen/views/vaults/detail/activity.cljs`
   - `/hyperopen/test/hyperopen/views/vaults/detail/activity_test.cljs`
   - `/hyperopen/src/hyperopen/views/account_info/tabs/funding_history.cljs`
   - `/hyperopen/test/hyperopen/views/account_info/tabs/funding_history_test.cljs`
   - `/hyperopen/src/hyperopen/views/account_info/projections/orders.cljs`
   - `/hyperopen/test/hyperopen/views/account_info/projections_test.cljs`

2. Edit the funding source and test files:

   - `/hyperopen/src/hyperopen/funding/domain/policy.cljs`
   - `/hyperopen/test/hyperopen/funding/actions_test.cljs` and, if needed, a new focused policy test namespace under `/hyperopen/test/hyperopen/funding/`
   - `/hyperopen/src/hyperopen/funding/application/hyperunit_query.cljs`
   - `/hyperopen/test/hyperopen/funding/application/hyperunit_query_test.cljs`
   - `/hyperopen/src/hyperopen/funding/application/deposit_submit.cljs`
   - `/hyperopen/test/hyperopen/funding/application/deposit_submit_test.cljs`

3. Edit the API/websocket source and test files:

   - `/hyperopen/src/hyperopen/api/info_client.cljs`
   - `/hyperopen/test/hyperopen/api/info_client_test.cljs`
   - `/hyperopen/test/hyperopen/api_test.cljs`
   - `/hyperopen/src/hyperopen/websocket/trades.cljs`
   - `/hyperopen/test/hyperopen/websocket/trades_test.cljs`

4. Edit the staking source and test files:

   - `/hyperopen/src/hyperopen/staking/effects.cljs`
   - `/hyperopen/test/hyperopen/staking/effects_test.cljs`
   - `/hyperopen/src/hyperopen/staking/actions.cljs`
   - `/hyperopen/test/hyperopen/staking/actions_test.cljs`

5. Run focused tests first from `/hyperopen` as each bucket lands. The intended starting set is:

   npm test -- --focus hyperopen.views.vaults.detail.activity-test
   npm test -- --focus hyperopen.views.account-info.tabs.funding-history-test
   npm test -- --focus hyperopen.views.account-info.projections-test
   npm test -- --focus hyperopen.funding.application.hyperunit-query-test
   npm test -- --focus hyperopen.funding.application.deposit-submit-test
   npm test -- --focus hyperopen.api.info-client-test
   npm test -- --focus hyperopen.websocket.trades-test
   npm test -- --focus hyperopen.staking.effects-test
   npm test -- --focus hyperopen.staking.actions-test

6. Run the required repository gates from `/hyperopen`:

   npm run check
   npm test
   npm run test:websocket

7. Refresh coverage and CRAP evidence from `/hyperopen`:

   npm run coverage
   bb tools/crap_report.clj --scope src

8. Run the required UI/browser QA for the touched routes and record the result or blocker explicitly.

## Validation and Acceptance

Acceptance is behavior-based:

- vault detail positions still render the same values, placeholders, and side-tone styling, while `positions-table` gains direct row-branch coverage
- funding withdrawal preview still returns the same validation messages and request payloads for standard and Hyperunit-address withdrawals
- funding history sorting still uses the same deterministic tie-break output and rendered row keys for legacy mixed-shape rows
- info-client request scheduling, caching, retry, cooldown, stats, and single-flight behavior remain unchanged at the public API boundary
- websocket trade ingestion still preserves `:price-raw`, `:size-raw`, `:side`, `:tid`, and coin/time alias behavior
- open-order normalization still returns the same projection map for nested trigger payloads, trigger price fallback, time parsing, and boolean coercion
- Hyperunit deposit-address selection still prefers operation-derived address, then direct address entry, then source-format address validation
- staking submit-state keys and validator sort-column aliases still normalize to the same stored values
- `npm run check`, `npm test`, `npm run test:websocket`, `npm run coverage`, and the refreshed CRAP report all pass
- required browser QA is explicitly accounted for on the touched UI routes

## Idempotence and Recovery

These are source-and-test refactors with no persistent migration, so the work is safe to rerun. If a refactor breaks behavior, restore the affected helper structure in that bucket, rerun the narrowest focused test namespace first, and only rerun the full gates after the local regression is resolved. If the CRAP report appears stale, rerun `npm run coverage` before `bb tools/crap_report.clj --scope src` because the report reads generated coverage artifacts.

## Artifacts and Notes

The tracked issue is `hyperopen-3ufd`. This plan is the active execution artifact for the work.

Fresh-context analysis findings already incorporated into this plan identified these concrete seams:

- `positions-table`: local row-display extraction plus direct row rendering tests
- `funding-row-sort-id`: shared fallback readers plus tie-break/key regression tests
- `make-info-client`: pure helper extraction outside `make-info-client` and direct retry/cache/single-flight cleanup tests
- `normalize-trade-for-view`: alias-reader extraction or policy-helper reuse plus direct raw-field normalization tests

The remaining hotspot audits were completed locally against the live source tree and will be reflected back into this plan as the corresponding worker buckets land.

Final implementation touched these source/test areas:

- `/hyperopen/src/hyperopen/views/vaults/detail/activity.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/funding_history.cljs`
- `/hyperopen/src/hyperopen/views/account_info/projections/orders.cljs`
- `/hyperopen/src/hyperopen/funding/domain/policy.cljs`
- `/hyperopen/src/hyperopen/funding/application/hyperunit_query.cljs`
- `/hyperopen/src/hyperopen/funding/application/deposit_submit.cljs`
- `/hyperopen/src/hyperopen/api/info_client.cljs`
- `/hyperopen/src/hyperopen/websocket/trades.cljs`
- `/hyperopen/src/hyperopen/staking/effects.cljs`
- `/hyperopen/src/hyperopen/staking/actions.cljs`
- `/hyperopen/test/hyperopen/views/vaults/detail/activity_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/funding_history_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/projections_test.cljs`
- `/hyperopen/test/hyperopen/funding/domain/policy_test.cljs`
- `/hyperopen/test/hyperopen/funding/application/hyperunit_query_test.cljs`
- `/hyperopen/test/hyperopen/funding/application/deposit_submit_test.cljs`
- `/hyperopen/test/hyperopen/api/info_client_test.cljs`
- `/hyperopen/test/hyperopen/websocket/trades_test.cljs`
- `/hyperopen/test/hyperopen/staking/effects_test.cljs`
- `/hyperopen/test/hyperopen/staking/actions_test.cljs`
- `/hyperopen/dev/namespace_size_exceptions.edn`
- `/hyperopen/docs/exec-plans/completed/2026-03-21-design-review-pass-registry-and-honest-outcomes.md`
- `/hyperopen/docs/exec-plans/completed/2026-03-24-design-review-blocked-false-positives-and-startup-readiness.md`

Validation artifacts for this ticket:

- CRAP report JSON: `/tmp/hyperopen-crap-report-all.json`
- governed design-review run: `/hyperopen/tmp/browser-inspection/design-review-2026-03-26T01-17-46-205Z-4bf89247/`

Plan update note (2026-03-26 01:21Z): completed the hotspot refactors, fixed the broadened regression expectations, reran the full repository gates plus merged coverage and CRAP reporting, passed committed Playwright and governed browser QA for the touched routes, and prepared `hyperopen-3ufd` for closure.

## Interfaces and Dependencies

No public API changes are planned. The following interfaces must remain stable after the refactor:

- `hyperopen.api.info-client/make-info-client` must keep the same returned map keys and `:request-info!` arities
- `hyperopen.funding.domain.policy/withdraw-preview` must keep the same `{:ok? ... :display-message ...}` and request payload contract
- `hyperopen.websocket.trades/normalize-trade-for-view` must preserve `:price-raw`, `:size-raw`, `:side`, `:tid`, and alias precedence
- `hyperopen.views.account-info.projections.orders/normalize-open-order` must keep the same projected keys and fallback precedence
- `hyperopen.staking.effects/submitting-key` must continue accepting both verb and predicate-like kinds and default to `:deposit?`
- `hyperopen.staking.actions/normalize-staking-validator-sort-column` must continue accepting the current keyword and string aliases and defaulting to `(:column default-validator-sort)`

Plan update note (2026-03-25 21:05Z): created the initial ExecPlan after auditing the reported hotspots, current tests, workflow guardrails, and the first wave of fresh-context per-function analyses. The plan intentionally narrows the work to behavior-preserving local extraction plus focused regressions so the CRAP reduction stays measurable and safe.
