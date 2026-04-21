# Reduce CRAP Hotspots In Spectate, Action Args, And Fill Normalization

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan follows `/hyperopen/.agents/PLANS.md` and the public planning contract in `/hyperopen/docs/PLANS.md`. Live issue tracking for this work is `hyperopen-hor0` in `bd`.

## Purpose / Big Picture

The CRAP report identified four functions whose branching and coverage make future changes risky: `spectate-mode-modal-view`, `watchlist-row`, `unlock-agent-trading-args?`, and `normalized-fill-row`. This work keeps user-visible behavior the same while moving decisions into smaller pure helpers and adding characterization tests. A contributor can see the work operating by running the targeted ClojureScript tests first, then the repo-required gates.

The term CRAP here means a score that combines cyclomatic complexity and test coverage. Lowering CRAP can come from more coverage, lower complexity, or both. This plan uses both: add focused tests around current contracts, then extract helpers so each function has fewer branches.

## Progress

- [x] (2026-04-21 01:25Z) Created and claimed `bd` issue `hyperopen-hor0`.
- [x] (2026-04-21 01:26Z) Inspected the four reported hotspot functions and nearby test patterns.
- [x] (2026-04-21 01:27Z) Created this active ExecPlan.
- [x] (2026-04-21 01:38Z) Added characterization coverage for the spectate modal render contract, action argument validation, and fill normalization.
- [x] (2026-04-21 01:42Z) Verified characterization coverage with `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js`; result was 3344 tests, 18269 assertions, 0 failures, 0 errors.
- [x] (2026-04-21 01:47Z) Refactored `src/hyperopen/schema/contracts/action_args.cljs` into smaller helper predicates while preserving accepted and rejected payloads.
- [x] (2026-04-21 01:47Z) Refactored `src/hyperopen/websocket/user_runtime/fills.cljs` into smaller fill-normalization helpers while preserving normalized output and toast behavior.
- [x] (2026-04-21 01:51Z) Verified the schema and fill refactor slice with `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js`; result was 3344 tests, 18269 assertions, 0 failures, 0 errors.
- [x] (2026-04-21 01:55Z) Refactored `src/hyperopen/views/spectate_mode_modal.cljs` into a modal model, action specs, and section renderers while preserving data-role and action dispatch contracts.
- [x] (2026-04-21 01:56Z) Verified the full refactor slice with `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js`; result was 3344 tests, 18269 assertions, 0 failures, 0 errors.
- [x] (2026-04-21 02:06Z) Split watchlist rendering into `src/hyperopen/views/spectate_mode_modal/watchlist.cljs` and moved fill-normalization characterization coverage to `test/hyperopen/websocket/user_runtime/fills_normalization_test.cljs` after `npm run check` found namespace-size failures.
- [x] (2026-04-21 02:08Z) Addressed review feedback by wiring the extracted watchlist namespace and asserting the active-mode Stop button dispatch in the spectate modal test.
- [x] (2026-04-21 02:09Z) Verified the final split with `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js`; result was 3344 tests, 18271 assertions, 0 failures, 0 errors.
- [x] (2026-04-21 02:10Z) Ran `npm run check`; all checks and compiles passed, including namespace-size and namespace-boundary checks.
- [x] (2026-04-21 02:10Z) Ran `npm test`; result was 3344 tests, 18271 assertions, 0 failures, 0 errors.
- [x] (2026-04-21 02:11Z) Ran `npm run test:websocket`; result was 450 tests, 2708 assertions, 0 failures, 0 errors.
- [x] (2026-04-21 02:12Z) Ran `npm run coverage`; result was 3344 main tests and 450 websocket tests passing, with line coverage at 90.64%.
- [x] (2026-04-21 02:12Z) Ran `npm run crap:report`; result was `crappy_functions=0`, `project_crapload=0.00`, and none of the four requested hotspots appeared in the top functions.
- [x] (2026-04-21 02:54Z) Ran the Playwright smoke suite against the existing app server after the default smoke command was blocked by port 8080 already being occupied; result was 22 passed.
- [x] (2026-04-21 02:55Z) Moved this plan to `docs/exec-plans/completed/` and closed `hyperopen-hor0` after acceptance criteria passed.
- [x] (2026-04-21 02:18Z) Reopened `hyperopen-hor0` after the user requested the remaining-risk follow-up before commit.
- [x] (2026-04-21 02:21Z) Added effect-side unlock-agent-trading continuation contract coverage.
- [x] (2026-04-21 02:23Z) Moved unlock-agent-trading continuation validation into `src/hyperopen/schema/contracts/common.cljs` and wired both action and effect contracts to `::common/unlock-agent-trading-args`.
- [x] (2026-04-21 02:24Z) Verified the shared predicate refactor with `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js`; result was 3345 tests, 18281 assertions, 0 failures, 0 errors.
- [x] (2026-04-21 02:25Z) Re-ran `npm run check`, `npm test`, and `npm run test:websocket`; all passed.
- [x] (2026-04-21 02:27Z) Re-ran `npm run coverage` and `npm run crap:report`; coverage was 90.67% lines, `crappy_functions=0`, `project_crapload=0.00`, and top CRAP dropped to 28.09.
- [x] (2026-04-21 02:28Z) Re-closed `hyperopen-hor0` after the remaining-risk follow-up passed validation.

## Surprises & Discoveries

- Observation: The user-reported `fills.cljs` hotspot at line 183 is `src/hyperopen/websocket/user_runtime/fills.cljs`, not `src/hyperopen/vaults/adapters/fills.cljs`.
  Evidence: `rg -n "normalized-fill-row" src test` resolves the function to `src/hyperopen/websocket/user_runtime/fills.cljs:183`.
- Observation: Existing tests already reach private functions in `hyperopen.websocket.user-runtime.fills` through var deref, so helper extraction can be characterized without widening public APIs.
  Evidence: `test/hyperopen/websocket/user_runtime/fills_test.cljs` derefs `@#'fill-runtime/normalized-fill-row`.
- Observation: There is no direct spectate modal view test file yet, but the test suite has standard hiccup traversal helpers in `test/hyperopen/test_support/hiccup.cljs`.
  Evidence: `test/hyperopen/views/app_view_test.cljs` and `test/hyperopen/views/header_view_test.cljs` use data-role based assertions.
- Observation: The initial test run failed before assertions because `lucide` was declared but missing from `node_modules`.
  Evidence: `node out/test.js` raised `Cannot find module 'lucide/dist/esm/icons/external-link.js'`; `npm ls lucide --depth=0` showed an empty tree. Running `npm install` restored declared dependencies.
- Observation: A non-empty pending spectate label renders the label/add row even when the search text is invalid.
  Evidence: The corrected spectate modal characterization test expects `spectate-mode-label-input` with value `Pending label` and disabled `spectate-mode-add-watchlist`.
- Observation: The generated test runner is a tracked artifact that changes when a new test namespace is added.
  Evidence: `npm run test:runner:generate` updated `test/test_runner_generated.cljs` from 516 to 517 namespaces after adding `test/hyperopen/views/spectate_mode_modal_test.cljs`.
- Observation: `npm run check` enforces namespace-size budgets in both source and test files.
  Evidence: The first final-gate attempt failed because `src/hyperopen/views/spectate_mode_modal.cljs` exceeded its exception and `test/hyperopen/websocket/user_runtime/fills_test.cljs` crossed the default test namespace limit.
- Observation: A static review found the active Stop control was stated in acceptance criteria but not directly asserted.
  Evidence: The spectate modal characterization test now asserts the `spectate-mode-stop` control dispatches `[[:actions/stop-spectate-mode]]`.
- Observation: The action-side and effect-side unlock-agent-trading continuation contracts used duplicate predicates.
  Evidence: Before the follow-up, `src/hyperopen/schema/contracts/effect_args.cljs` still had its own `unlock-agent-trading-args?` and appeared at CRAP 30.00; after the shared helper refactor it no longer appears in the CRAP top functions.

## Decision Log

- Decision: Keep all public namespaces and exported functions stable, and test private helpers only where the existing test style already permits it.
  Rationale: The goal is deCRAPing, not changing runtime APIs or module boundaries. Private helper tests are already accepted locally for high-risk normalization paths.
  Date/Author: 2026-04-21 / Codex
- Decision: Treat the spectate modal as UI-facing but use deterministic hiccup tests rather than Browser MCP.
  Rationale: The requested change is behavior-preserving decomposition of rendered hiccup and dispatch data. No live browser selector discovery is needed, and Playwright is reserved for committed browser flows.
  Date/Author: 2026-04-21 / Codex
- Decision: Add coverage before production edits.
  Rationale: The work is a refactor and should preserve current acceptance/rejection contracts, so characterization tests must fail before implementation only for missing coverage surfaces and pass after helpers are extracted.
  Date/Author: 2026-04-21 / Codex
- Decision: Initially leave the duplicate effect-side unlock predicate out of the scoped hotspot work, then fold it into this commit when the user requested the remaining-risk follow-up before commit.
  Rationale: The original hotspot list named the action-side predicate only. Once the user asked to address the remaining risk, sharing the predicate in `common.cljs` reduced duplication and removed the effect-side CRAP item without changing the action/effect validation contract.
  Date/Author: 2026-04-21 / Codex
- Decision: Keep the spectate modal layout lookup behind the `open?` render guard after extraction.
  Rationale: This preserves rendered output and avoids reading DOM layout for a closed modal. The behavior is safer for tests and follows the same practical pattern as other closed modal render paths.
  Date/Author: 2026-04-21 / Codex
- Decision: Extract watchlist row rendering into `hyperopen.views.spectate-mode-modal.watchlist`.
  Rationale: The modal refactor cleared the CRAP hotspots but still exceeded the existing namespace-size budget. A narrow sibling namespace keeps the UI entry point stable and avoids broad ownership or API churn.
  Date/Author: 2026-04-21 / Codex
- Decision: Do not run Browser MCP for this task, but run the deterministic Playwright smoke suite.
  Rationale: The UI change was a behavior-preserving hiccup decomposition, not selector discovery or a visual layout investigation. Deterministic hiccup tests cover the modal data-role and dispatch contracts changed here; Playwright smoke provides browser-level route coverage without starting Browser MCP.
  Date/Author: 2026-04-21 / Codex

## Outcomes & Retrospective

All four requested hotspots were removed from the CRAP report. The user-requested remaining-risk follow-up also removed the duplicate effect-side unlock-agent-trading predicate from the CRAP top functions. The final CRAP report reported `crappy_functions=0`, `project_crapload=0.00`, and top CRAP at 28.09.

Coverage was added for the spectate modal closed/open contracts, active Stop/Switch behavior, edit-mode label saving, invalid search disabling, copy-feedback visibility, every watchlist row action, `:actions/unlock-agent-trading` and `:effects/unlock-agent-trading` valid and invalid continuation payloads, and fill normalization precedence/guard cases.

The required gates passed from the final state: `npm run check`, `npm test`, and `npm run test:websocket`. Coverage also passed with 90.67% line coverage. Browser QA accounting: the default `npm run test:playwright:smoke` command was blocked because another Shadow watch server already occupied port 8080; the same smoke suite was then run against that existing app server and passed 22 tests. Browser MCP was not started.

## Context and Orientation

The UI hotspot lives in `src/hyperopen/views/spectate_mode_modal.cljs`. It renders the floating Spectate Mode panel. `watchlist-row` builds one saved-address row with five icon buttons. `spectate-mode-modal-view` gathers state, decides whether the modal and form rows are visible, computes the anchored panel style, and renders the full panel. The important contracts are `:data-role` attributes and `:on` action vectors such as `[:actions/start-spectate-mode]`.

The action schema hotspot lives in `src/hyperopen/schema/contracts/action_args.cljs`. `unlock-agent-trading-args?` validates the argument vector for `:actions/unlock-agent-trading`. It currently accepts no args or one map containing only `:after-success-actions`, whose value must be a vector of action request vectors. An action request vector starts with an `:actions/...` keyword.

The fill-normalization hotspot lives in `src/hyperopen/websocket/user_runtime/fills.cljs`. `normalized-fill-row` converts user fill rows from multiple upstream shapes into the internal toast row shape with keys like `:coin`, `:display-coin`, `:id`, `:side`, `:qty`, `:price`, `:orderType`, `:ts`, and `:slippagePct`. It must reject incomplete fills, zero-size fills, rows with no side, or rows with no finite price.

The test suite is ClojureScript compiled by Shadow CLJS. The broad test command is `npm test`; the required gates for code changes are `npm run check`, `npm test`, and `npm run test:websocket`.

## Plan of Work

First, add `test/hyperopen/views/spectate_mode_modal_test.cljs`. It should require `hyperopen.views.spectate-mode-modal` and `hyperopen.test-support.hiccup`, render closed and open states, and assert the modal root only appears when `:modal-open?` is true. For the open state, assert search input value, start button disabled state for invalid input, label row visibility while editing, active summary text, copy feedback slot, and watchlist row actions. This gives coverage for the branches in `spectate-mode-modal-view` and `watchlist-row`.

Second, extend `test/hyperopen/schema/contracts/action_args_test.cljs` with valid and invalid `:actions/unlock-agent-trading` payloads. Valid cases include no args and a single `{:after-success-actions [[:actions/start-spectate-mode "0x123"]]}` payload. Invalid cases include unknown keys, non-vector `:after-success-actions`, and non-action request vectors.

Third, extend `test/hyperopen/websocket/user_runtime/fills_test.cljs` with more branch-specific assertions around `normalized-fill-row`: direct id preservation, timestamp fallbacks, order type normalization, slippage fallbacks, market display symbol resolution when `market-by-key` is supplied, and invalid row rejection. Keep the existing public `fill-toast-payloads` expectations unchanged.

Fourth, refactor `src/hyperopen/views/spectate_mode_modal.cljs`. Extract helpers for modal view-model derivation, close button rendering, header rendering, search row rendering, label row rendering, active summary rendering, search error rendering, watchlist collection rendering, and copy feedback slot rendering. Extract a data-driven `watchlist-actions` helper so `watchlist-row` maps action descriptors instead of spelling every button branch inline. Keep rendered class tokens, data roles, and dispatch vectors equivalent.

Fifth, refactor `src/hyperopen/schema/contracts/action_args.cljs`. Extract `only-after-success-actions-key?`, `after-success-actions?`, `unlock-agent-trading-options?`, and `single-unlock-options-arg?`. Keep `unlock-agent-trading-args?` as the registered predicate, but make it a shallow combination of those helpers.

Sixth, refactor `src/hyperopen/websocket/user_runtime/fills.cljs`. Extract token helpers for size, price, timestamp, id, and validity. `normalized-fill-row` should read as data assembly over precomputed normalized fields rather than owning every branch. Preserve private visibility unless a helper already needs public use.

## Concrete Steps

Work from `/Users/barry/.codex/worktrees/dc40/hyperopen`.

Run the targeted test compile after adding tests:

    npm run test:runner:generate
    npx shadow-cljs --force-spawn compile test
    node out/test.js

After each production refactor, rerun the same test command. Because the generated runner runs the whole `:test` build, success is reported by the final ClojureScript test summary with zero failures and zero errors.

When all refactors are complete, run the required gates:

    npm run check
    npm test
    npm run test:websocket

If browser QA is considered, record an explicit skip unless behavior changes beyond hiccup structure. This plan does not call for Browser MCP because no live browser state or visual parity decision is needed.

## Validation and Acceptance

Acceptance requires all of the following:

The new spectate modal tests prove that a closed modal renders nothing, an open modal renders the expected root, the invalid search disables the Spectate and Add buttons, edit mode shows Save Label and Cancel, active mode shows Stop and the active summary, copy feedback appears only when message text is present, and every watchlist action preserves its data-role and dispatch vector.

The action args tests prove `:actions/unlock-agent-trading` still accepts no args and valid `:after-success-actions`, and still rejects unknown option keys, non-vector actions, and non-`:actions` request vectors.

The fill tests prove `normalized-fill-row` preserves existing normalized outputs while separately covering fallback id generation, explicit id conversion to string, side and direction fallbacks, order type normalization, slippage parsing, timestamp fallbacks, and invalid-row rejection.

The repository gates `npm run check`, `npm test`, and `npm run test:websocket` must exit with code 0.

## Idempotence and Recovery

The edits are source and test changes only. If a refactor fails, restore behavior by comparing the failing test’s expected data-role, action vector, or normalized map against the pre-refactor code and adjust the helper composition rather than changing the tests. Do not run `git pull --rebase` or `git push`; remote sync is outside this task.

## Artifacts and Notes

Initial hotspot list from the user:

    src/hyperopen/views/spectate_mode_modal.cljs spectate-mode-modal-view CRAP 380.00 complexity 19 coverage 0.00
    src/hyperopen/views/spectate_mode_modal.cljs watchlist-row CRAP 56.00 complexity 7 coverage 0.00
    src/hyperopen/schema/contracts/action_args.cljs unlock-agent-trading-args? CRAP 42.00 complexity 6 coverage 0.00
    src/hyperopen/websocket/user_runtime/fills.cljs normalized-fill-row CRAP 32.05 complexity 32 coverage 0.96

## Interfaces and Dependencies

No public interfaces should change. `hyperopen.views.spectate-mode-modal/spectate-mode-modal-view` remains the public view entry point used by `src/hyperopen/views/spectate_mode_modal_module.cljs`. `:actions/unlock-agent-trading` remains registered in `action-args-spec-by-id` with the same predicate contract. `fill-toast-payloads` remains the public entry point for normalized fill toast payloads and continues to call a private `normalized-fill-row`.

Revision note 2026-04-21: Initial active plan created so implementation can proceed under the repo planning contract and `bd` issue `hyperopen-hor0`.
