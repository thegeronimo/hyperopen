# Split Account Info View Tests into Domain-Focused Namespaces

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The account info view tests are currently concentrated in a single very large file, `/hyperopen/test/hyperopen/views/account_info_view_test.cljs`, which is difficult to navigate and costly for humans and coding agents to load into working context. After this change, the same behaviors will be covered by smaller, domain-focused test namespaces that mirror production modules (`tabs/*`, shell/navigation, and projection-focused seams). This improves maintainability through clearer boundaries and improves context efficiency for AI-assisted development by reducing unrelated test noise during targeted edits.

Users can verify the change by locating tests for a single domain (for example Trade History) in one dedicated file, running the existing required test gates, and seeing identical behavior coverage without regressions.

## Progress

- [x] (2026-02-20 01:44Z) Re-read `/hyperopen/.agents/PLANS.md` and local active ExecPlan examples to align structure, required sections, and validation expectations.
- [x] (2026-02-20 01:44Z) Audited `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` and mapped current scope: 2596 lines, 110 `deftest` forms, mixed UI/layout/action/sorting/pagination/projection concerns.
- [x] (2026-02-20 01:44Z) Audited relevant source boundaries in `/hyperopen/src/hyperopen/views/account_info_view.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/*`, and `/hyperopen/src/hyperopen/views/account_info/projections/*`.
- [x] (2026-02-20 01:44Z) Confirmed that `/hyperopen/test/test_runner.cljs` explicitly enumerates test namespaces, so split work must update both `:require` entries and `run-tests` namespace lists.
- [x] (2026-02-20 01:44Z) Authored this active ExecPlan before implementation.
- [x] (2026-02-20 01:57Z) Reviewed second-opinion split proposal and accepted its core recommendations (helper extraction, fixture extraction, per-tab split, dedicated cross-tab contract file) with repository-convention adjustments.
- [x] (2026-02-20 01:59Z) Extracted reusable hiccup inspection helpers into dedicated test-support namespace (`/hyperopen/test/hyperopen/views/account_info/test_support/hiccup.cljs`) and rewired `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` to consume it.
- [x] (2026-02-20 02:03Z) Extracted reusable account-info fixtures/builders into dedicated test-support namespace (`/hyperopen/test/hyperopen/views/account_info/test_support/fixtures.cljs`) and rewired `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` to consume fixture aliases.
- [x] (2026-02-20 13:09Z) Created per-tab domain test namespaces under `/hyperopen/test/hyperopen/views/account_info/tabs/` and moved tab-scoped `deftest` forms from `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` into the corresponding tab files without changing assertions.
- [x] (2026-02-20 13:15Z) Created dedicated navigation/shell test namespace (`/hyperopen/test/hyperopen/views/account_info/navigation_test.cljs`) and moved tab-navigation/freshness-cue tests out of `/hyperopen/test/hyperopen/views/account_info_view_test.cljs`.
- [x] (2026-02-20 13:29Z) Created dedicated cross-tab contract test namespace (`/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs`) and moved shared layout/styling invariants out of the facade monolith.
- [x] (2026-02-20 13:29Z) Reduced `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` to a thin facade integration slice focused on panel shell framing behavior.
- [x] (2026-02-20 13:30Z) Added per-namespace cache-reset fixtures (`use-fixtures :each`) for memoized tab tests in `positions`, `open_orders`, `trade_history`, `order_history`, and `funding_history`.
- [x] (2026-02-20 13:30Z) Updated `/hyperopen/test/test_runner.cljs` to include the new account-info namespaces (`account-info-view`, navigation, table-contract, tab files, projections, and vm).
- [x] (2026-02-20 13:30Z) Ran required validation gates on final state: `npm run check`, `npm test`, and `npm run test:websocket` all completed with `0 failures, 0 errors` (warning-only npm config notices).
- [x] (2026-02-20 13:30Z) Updated this plan with implementation discoveries, final decisions, and retrospective evidence.

## Surprises & Discoveries

- Observation: the monolith has no explicit section delimiters; concerns are interleaved.
  Evidence: `rg -n "^\(deftest" /hyperopen/test/hyperopen/views/account_info_view_test.cljs` yields 110 test names spanning unrelated domains from balances headers to funding filter controls.

- Observation: production code is already decomposed by bounded context, but tests are not.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/tabs/` contains six tab modules and `/hyperopen/src/hyperopen/views/account_info/projections/` contains focused projection modules, while their tests mostly remain in one facade test file.

- Observation: part of the monolith duplicates projection concerns that already have a dedicated projection test namespace.
  Evidence: `/hyperopen/test/hyperopen/views/account_info/projections_test.cljs` already validates `normalized-open-orders`, `build-balance-rows`, and `normalize-order-history-row`, while `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` also asserts related pure sorting/projection behavior through the view facade.

- Observation: test execution wiring is manual, not discovery-based.
  Evidence: `/hyperopen/test/test_runner.cljs` contains explicit namespace requires and a hard-coded `run-tests` list; adding new files without runner changes will silently omit them.

- Observation: a subset of assertions is intentionally cross-cutting and should stay centralized rather than being forced into tab files.
  Evidence: current tests in `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` iterate over multiple tab contents to verify shared contracts (`scroll viewport`, `row hover/no-divider`, `header divider removal`, compact density classes).

- Observation: helper extraction introduced an escaping regression that silently broke class parsing across many account-info tests.
  Evidence: first `npm test` run after runner wiring reported 102 failures with repeated `node-class-set` empty-set assertions; root cause was over-escaped regexes (`#"\\."`, `#"\\s+"`) in `/hyperopen/test/hyperopen/views/account_info/test_support/hiccup.cljs`, corrected to `#"\."` and `#"\s+"`.

## Decision Log

- Decision: execute this as a behavior-preserving refactor first (test-file topology and helper extraction only), with no intentional production logic changes.
  Rationale: this reduces migration risk and keeps failures attributable to organization, not feature drift.
  Date/Author: 2026-02-20 / Codex

- Decision: mirror source bounded contexts in test paths and namespaces (tabs, shell/facade, projections/vm), rather than splitting by arbitrary line count.
  Rationale: domain alignment improves discoverability for humans and agents and follows SOLID/DDD boundaries already present in source modules.
  Date/Author: 2026-02-20 / Codex

- Decision: create a dedicated shared helper namespace for hiccup node traversal and common fixtures used across account-info view tests.
  Rationale: this prevents copy-paste helper drift while keeping each test file focused on domain assertions.
  Date/Author: 2026-02-20 / Codex

- Decision: keep a minimal facade-level integration test slice that still validates `account-info-view` shell contracts (tab navigation, panel framing, cross-tab shared styling) after extraction.
  Rationale: module-focused tests should not remove confidence in top-level composition/wiring.
  Date/Author: 2026-02-20 / Codex

- Decision: represent cross-cutting UI invariants in a dedicated contract namespace (`table_contract_test.cljs`) and keep navigation behavior in its own namespace (`navigation_test.cljs`), instead of mixing both into one generic shell file.
  Rationale: this preserves cohesion while keeping true cross-tab behavior explicit and discoverable.
  Date/Author: 2026-02-20 / Codex

- Decision: during this split, do not require production `data-role` or `data-testid` additions unless necessary to preserve deterministic assertions; treat selector-hardening as an optional follow-up.
  Rationale: the immediate objective is topology refactor with behavior parity, not production markup churn.
  Date/Author: 2026-02-20 / Codex

- Decision: fix class-parsing breakage once in shared hiccup test support rather than touching each affected assertion.
  Rationale: this preserves behavior parity with the pre-split monolith semantics and avoids broad test churn unrelated to domain decomposition.
  Date/Author: 2026-02-20 / Codex

## Outcomes & Retrospective

Implementation completed as a behavior-preserving test-topology refactor.

- Files created: `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs`.
- Files reduced: `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` now contains only the thin facade panel shell test.
- Test mapping: cross-tab contracts moved from facade monolith to `table_contract_test.cljs`; navigation/freshness behavior is in `navigation_test.cljs`; tab-domain tests remain in per-tab files under `/hyperopen/test/hyperopen/views/account_info/tabs/`.
- Validation outcomes:
  - `npm run check`: pass (lint/docs/compile success)
  - `npm test`: pass (`Ran 1168 tests containing 5445 assertions. 0 failures, 0 errors.`)
  - `npm run test:websocket`: pass (`Ran 135 tests containing 587 assertions. 0 failures, 0 errors.`)
- Residual risk: low; facade coverage is intentionally thin by design, with cross-tab contracts and domain behavior now owned by dedicated namespaces and all included in the explicit runner.

## Context and Orientation

The production entrypoint `/hyperopen/src/hyperopen/views/account_info_view.cljs` is a facade that composes shared shell behavior (tab navigation, panel layout) and re-exports tab/projection helpers from module namespaces. The domain modules are split in `/hyperopen/src/hyperopen/views/account_info/tabs/*.cljs` and projection utilities in `/hyperopen/src/hyperopen/views/account_info/projections/*.cljs`.

The current large test file `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` combines many test types:

- shell/navigation/layout checks (panel height, tab controls, cross-tab density classes),
- balances/positions/open-orders/trade-history/order-history/funding-history tab content behavior,
- sorting, memoization, pagination, and formatter rules,
- projection-adjacent data normalization behavior asserted through the facade.

Repository-local focused tests already exist in `/hyperopen/test/hyperopen/views/account_info/projections_test.cljs` and `/hyperopen/test/hyperopen/views/account_info/vm_test.cljs`, which demonstrates the preferred decomposition pattern.

The test runner `/hyperopen/test/test_runner.cljs` is explicit. Every new namespace introduced by this split must be listed in both the namespace requires and the `run-tests` invocation list.

For this plan, “domain-focused” means one test namespace maps to one meaningful module boundary (for example balances tab, trade-history tab, or top-level shell) so that editing a domain generally requires opening one primary test file.

For this plan, “contract test” means a cross-tab invariant that intentionally spans multiple tab renderers (for example shared row density classes or scroll container behavior). Contract tests belong in a dedicated cross-cutting file, not in a single tab file.

## Plan of Work

Milestone 1 establishes shared scaffolding for split files. Extract generic hiccup-tree helpers from `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` into `/hyperopen/test/hyperopen/views/account_info/test_support/hiccup.cljs`, and extract reusable sample fixtures/builders into `/hyperopen/test/hyperopen/views/account_info/test_support/fixtures.cljs`. Keep helper APIs narrow and deterministic so tab-specific test files can depend on them without cross-domain leakage.

Milestone 2 performs domain migration by creating tab-scoped test files under `/hyperopen/test/hyperopen/views/account_info/tabs/` and moving relevant `deftest` forms intact. The target files are:

- `/hyperopen/test/hyperopen/views/account_info/tabs/balances_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/open_orders_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/order_history_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/funding_history_test.cljs`

Milestone 3 isolates non-tab responsibilities into explicit cross-cutting files:

- `/hyperopen/test/hyperopen/views/account_info/navigation_test.cljs` for tab-navigation actions/count labels/freshness cues.
- `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs` for cross-tab layout/styling invariants intentionally shared by all tab tables.

Milestone 4 reconciles projection and facade coverage. Move pure projection assertions currently routed through `view/*` aliases to `/hyperopen/test/hyperopen/views/account_info/projections_test.cljs` when they are truly projection behavior, retain facade-level checks where alias wiring itself is the behavior under test, and keep `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` as either a thin compatibility slice or remove it once redundant.

Milestone 5 hardens isolation semantics for memoized tests by adding per-file `use-fixtures :each` cache resets in relevant tab test namespaces (`open_orders`, `trade_history`, `order_history`, `funding_history`, `positions`) so no test depends on prior cache state.

Milestone 6 updates runner wiring and validates. Add all new namespaces to `/hyperopen/test/test_runner.cljs`, remove stale namespace references, run required gates, and update this plan with actual outcomes and discovery notes.

## Concrete Steps

1. From `/hyperopen`, create test-support and target namespace files.

   Create helper namespaces:

   - `/hyperopen/test/hyperopen/views/account_info/test_support/hiccup.cljs`
   - `/hyperopen/test/hyperopen/views/account_info/test_support/fixtures.cljs`

   Create domain test namespaces listed in Milestone 2, plus:

   - `/hyperopen/test/hyperopen/views/account_info/navigation_test.cljs`
   - `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs`

2. Move tests from `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` into the new files by concern, keeping assertions and test names stable unless a rename is required to resolve namespace-local collisions.

3. Update namespace `:require` forms so each file imports only the modules it exercises (for example, `hyperopen.views.account-info.tabs.trade-history` in trade-history tests), while shared traversal/fixture helpers come from `test_support` namespaces.

4. Update `/hyperopen/test/hyperopen/views/account_info/projections_test.cljs` where pure projection behavior is better tested directly than through facade aliases.

5. In memoization-heavy tab files, add `use-fixtures :each` cache reset hooks so each test starts from a known cache state.

6. Update `/hyperopen/test/test_runner.cljs`:

   - add new namespace requires,
   - include new namespaces in `run-tests`,
   - remove old monolith namespace references if retired.

7. Run required gates from `/hyperopen`:

       npm run check
       npm test
       npm run test:websocket

   Expected transcript shape:

       npm run check
       ...
       (completes without lint/compile failures)

       npm test
       ...
       0 failures, 0 errors

       npm run test:websocket
       ...
       0 failures, 0 errors

8. Record final evidence and plan updates (Progress, Surprises & Discoveries, Decision Log, Outcomes & Retrospective, revision note).

## Validation and Acceptance

The split is accepted when all of the following are true:

1. Domain-focused files exist under `/hyperopen/test/hyperopen/views/account_info/` and `/hyperopen/test/hyperopen/views/account_info/tabs/`, each containing tests for one primary concern.
2. Tests for a single concern (for example funding history pagination and controls) can be located without opening the former monolith.
3. Shared helper logic is centralized in dedicated test-support namespaces (`test_support/hiccup.cljs`, `test_support/fixtures.cljs`) instead of repeated private defs across many files.
4. `/hyperopen/test/test_runner.cljs` includes every new namespace so no tests are accidentally excluded.
5. Cross-tab invariants are represented in `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs`, not duplicated across per-tab files.
6. Navigation and freshness cue behavior is represented in `/hyperopen/test/hyperopen/views/account_info/navigation_test.cljs`.
7. Required gates pass: `npm run check`, `npm test`, and `npm run test:websocket`.
8. No intentional production behavior changes are introduced; any failures are either test wiring issues or uncover previously hidden regressions and are resolved before completion.

## Idempotence and Recovery

This migration is source-only and can be performed incrementally. A safe path is to move one domain at a time, run tests, then continue. If a partial move breaks compilation, reintroduce the affected namespace in `test_runner.cljs` and keep the old tests in place until the new file compiles; then remove duplicates in a follow-up step. Avoid destructive history rewrites; recovery should be normal file edits and reruns of the required gates.

## Artifacts and Notes

Initial scoping evidence captured during planning:

- `/hyperopen/test/hyperopen/views/account_info_view_test.cljs`: 2596 lines, 110 `deftest` entries.
- Existing focused tests: `/hyperopen/test/hyperopen/views/account_info/projections_test.cljs` and `/hyperopen/test/hyperopen/views/account_info/vm_test.cljs`.
- Source boundary map:
  - `/hyperopen/src/hyperopen/views/account_info_view.cljs`
  - `/hyperopen/src/hyperopen/views/account_info/tabs/*.cljs`
  - `/hyperopen/src/hyperopen/views/account_info/projections/*.cljs`
- Runner wiring file:
  - `/hyperopen/test/test_runner.cljs`

During implementation, this section should include concise migration evidence such as:

- counts of tests moved per new file,
- old/new namespace mapping snippets,
- and final gate outputs.

Current migration evidence:

- Moved 93 tab-scoped tests out of `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` into:
  `/hyperopen/test/hyperopen/views/account_info/tabs/balances_test.cljs` (31),
  `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs` (12),
  `/hyperopen/test/hyperopen/views/account_info/tabs/open_orders_test.cljs` (5),
  `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs` (18),
  `/hyperopen/test/hyperopen/views/account_info/tabs/order_history_test.cljs` (12),
  `/hyperopen/test/hyperopen/views/account_info/tabs/funding_history_test.cljs` (15).
- Moved remaining cross-tab shared contracts into `/hyperopen/test/hyperopen/views/account_info/table_contract_test.cljs` (7 tests) and navigation/freshness coverage into `/hyperopen/test/hyperopen/views/account_info/navigation_test.cljs` (9 tests).
- Reduced `/hyperopen/test/hyperopen/views/account_info_view_test.cljs` to 1 thin facade integration test.
- Updated `/hyperopen/test/test_runner.cljs` to run account-info namespaces explicitly: facade, navigation, table-contract, six tab namespaces, projections, and vm.
- Final gate evidence:
  - `npm run check` passed
  - `npm test` passed (0 failures, 0 errors)
  - `npm run test:websocket` passed (0 failures, 0 errors)

## Interfaces and Dependencies

No production interfaces are intentionally changed by this plan. The target interface changes are in test namespace topology and runner wiring only.

Expected new test namespaces (stable names at completion):

- `hyperopen.views.account-info.navigation-test`
- `hyperopen.views.account-info.table-contract-test`
- `hyperopen.views.account-info.tabs.balances-test`
- `hyperopen.views.account-info.tabs.positions-test`
- `hyperopen.views.account-info.tabs.open-orders-test`
- `hyperopen.views.account-info.tabs.trade-history-test`
- `hyperopen.views.account-info.tabs.order-history-test`
- `hyperopen.views.account-info.tabs.funding-history-test`
- `hyperopen.views.account-info.test-support.hiccup`
- `hyperopen.views.account-info.test-support.fixtures`

Dependencies that must remain consistent:

- `cljs.test` macros and assertion semantics.
- Existing account-info source modules in `/hyperopen/src/hyperopen/views/account_info/`.
- Explicit test namespace registration in `/hyperopen/test/test_runner.cljs`.

Plan revision note: 2026-02-20 01:44Z - Initial plan created from repository scan and test topology analysis; chosen strategy is bounded-context file split plus helper extraction with behavior-preserving migration.
Plan revision note: 2026-02-20 01:57Z - Incorporated second-opinion recommendations: explicit `test_support` helper/fixture extraction, dedicated `navigation` and `table_contract` namespaces, and fixture-based cache reset guidance; retained scope guard against unnecessary production markup changes in this phase.
Plan revision note: 2026-02-20 13:30Z - Completed remaining milestones: created `table_contract_test`, reduced facade test file to thin shell slice, added per-tab cache-reset fixtures, wired all account-info tests into `test_runner`, fixed shared helper regex regression, and passed required validation gates.
