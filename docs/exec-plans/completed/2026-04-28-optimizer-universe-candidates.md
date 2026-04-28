---
owner: platform
status: completed
source_of_truth: true
tracked_issue: hyperopen-6ct2
based_on:
  - /hyperopen/docs/exec-plans/active/2026-04-27-portfolio-optimizer-v4-visual-parity-remediation.md
  - /hyperopen/src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs
  - /hyperopen/src/hyperopen/views/portfolio/optimize/universe_panel.cljs
  - /hyperopen/test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs
  - /hyperopen/test/hyperopen/views/portfolio/optimize/universe_panel_test.cljs
  - /hyperopen/test/hyperopen/portfolio/optimizer/application/universe_candidates_test.cljs
---

# Extract Optimizer Universe Candidate Search Logic

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while the work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. It is self-contained so an engineer can execute the work without relying on the conversation that produced it.

Tracked issue: `hyperopen-6ct2` ("Extract optimizer universe candidate search logic").

## Purpose / Big Picture

The optimizer setup route at `/portfolio/optimize/new` already lets a user search the tradeable market list, add unused spot or perp instruments to the optimization universe, remove them again, and navigate the result list with the keyboard. The problem is that the data logic behind that experience is split across view namespaces. The live v4 universe view owns candidate filtering, ranking, label shaping, and search-state derivation directly inside its render namespace, while the older `universe_panel.cljs` still carries a partial copy of the same behavior. That makes small search changes risky because there is no single tested source of truth for candidate behavior.

After this change, both universe renderers should call one pure, tested namespace for selected-instrument IDs, candidate filtering and ordering, display labels, and active keyboard index bounds. A contributor should be able to change candidate search behavior by reading one small namespace and its tests instead of reading both renderers. The observable user behavior stays the same: searching `hype` still shows unused HYPE markets, raw spot IDs such as `@107` still display their symbols rather than raw IDs, exact ticker matches still sort before fuzzy matches, spot markets still sort before perps for the same match priority, selected instruments are not offered again, and keyboard active row metadata remains bounded to the rendered candidate list.

## Progress

- [x] (2026-04-28 16:38Z) Created and claimed tracked issue `hyperopen-6ct2`, linked it to this ExecPlan path, and scoped the task to pure universe candidate/search extraction.
- [x] (2026-04-28 16:38Z) Inspected the duplicated search logic in `/hyperopen/src/hyperopen/views/portfolio/optimize/universe_panel.cljs` and `/hyperopen/src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs`.
- [x] (2026-04-28 16:39Z) Reviewed `/hyperopen/AGENTS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/WORK_TRACKING.md`, and `/hyperopen/docs/BROWSER_TESTING.md`.
- [x] (2026-04-28 16:43Z) Observed the pre-existing focused red-phase test file at `/hyperopen/test/hyperopen/portfolio/optimizer/application/universe_candidates_test.cljs`; it is intentionally ahead of production code and currently requires the missing namespace `hyperopen.portfolio.optimizer.application.universe-candidates`.
- [x] (2026-04-28 16:39Z) Refreshed this active ExecPlan with concrete touched files, planned tests, browser accounting, and validation commands.
- [x] (2026-04-28 16:47Z) Ran `npm run check` far enough to verify docs and active-plan guardrails passed. The command then failed in the final `:test` compile because the red-phase test file above requires the missing production namespace. That failure is the expected starting state until implementation creates the shared helper.
- [x] (2026-04-28 16:51Z) Implemented `/hyperopen/src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs` and rewired both universe renderers to consume the shared helper. `candidate-markets` now defaults to exact-match plus spot-first ordering while still accepting `{:ranking :asset-query}` so `universe_panel.cljs` can preserve its older ordering path.
- [x] (2026-04-28 16:51Z) Ran `npm test` red before implementation and confirmed the missing-namespace failure from `hyperopen.portfolio.optimizer.application.universe-candidates-test`.
- [x] (2026-04-28 16:51Z) Ran `npm test` green after implementation. Result: `Ran 3603 tests containing 19744 assertions. 0 failures, 0 errors.`
- [x] (2026-04-28 16:58Z) Fixed the namespace-boundary failure by making `universe_candidates.cljs` self-contained instead of importing the view-only `instrument-display` namespace.
- [x] (2026-04-28 17:01Z) Ran deterministic browser coverage: `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer manual universe"` passed 2/2 tests, and `npx playwright test tools/playwright/test/optimizer-history-network.qa.spec.mjs` passed 1/1 test.
- [x] (2026-04-28 17:04Z) Ran required repo gates after the boundary fix: `npm test`, `npm run check`, and `npm run test:websocket` all exited 0.
- [x] (2026-04-28 17:05Z) Updated this plan with final validation evidence and prepared it to move from `active` to `completed`.

## Surprises & Discoveries

- Observation: `setup_v4_universe.cljs` and `universe_panel.cljs` both define `normalized-text`, `selected-instrument-ids`, `market-label`, and `candidate-markets`, but only the v4 namespace adds exact-match ranking and richer candidate labels.
  Evidence: `rg -n "candidate-markets|selected-instrument-ids|market-label|display-name|history-label|liquidity-label" src/hyperopen/views/portfolio/optimize`.

- Observation: The runtime route no longer requires `universe_panel.cljs`; `/portfolio/optimize/new` renders `workspace_view.cljs -> setup_v4_sections.cljs -> setup_v4_universe.cljs`.
  Evidence: `rg -n "setup-v4-universe|universe-panel" src/hyperopen/views/portfolio`.

- Observation: The current route/view tests assert rendered strings, `data-role` anchors, and action vectors, but they do not directly test candidate derivation as pure data.
  Evidence: `/hyperopen/test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs` and `/hyperopen/test/hyperopen/views/portfolio/optimize/universe_panel_test.cljs` both render `portfolio-view/portfolio-view`; neither requires a candidate helper namespace directly.

- Observation: The focused red-phase test file already enters the generated test runner automatically, so `npm run check` and `npm test` will stay red until the production namespace exists.
  Evidence: `test/test_runner_generated.cljs` gained `hyperopen.portfolio.optimizer.application.universe-candidates-test` when `npm run test:runner:generate` ran, and the final `:test` compile failed with `The required namespace "hyperopen.portfolio.optimizer.application.universe-candidates" is not available`.

- Observation: This refactor touches UI namespaces, so browser coverage still needs to be accounted for even though no visual change is intended.
  Evidence: `/hyperopen/AGENTS.md` and `/hyperopen/docs/BROWSER_TESTING.md` require deterministic browser verification for UI changes and explicit browser-QA accounting when UI-facing work lands.

- Observation: The first post-implementation `npm run check` found a boundary violation because the new application namespace imported `hyperopen.views.portfolio.optimize.instrument-display`.
  Evidence: `npm run check` failed at `lint:namespace-boundaries` with `[missing-boundary-exception] src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs - non-view namespace imports hyperopen.views.portfolio.optimize.instrument-display`.

## Decision Log

- Decision: Create `/hyperopen/src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs` instead of another view-specific helper namespace.
  Rationale: The logic is pure application behavior over optimizer state and market data. Keeping it under `portfolio/optimizer/application` makes it reusable by both legacy and v4 renderers without coupling the rule to either Hiccup file.
  Date/Author: 2026-04-28 / Codex

- Decision: Preserve the v4 candidate ordering as the unified behavior.
  Rationale: The v4 setup route is the active optimizer surface and already encodes the more deterministic ordering: valid unused markets only, `asset-query/filter-and-sort-assets` first, exact symbol or coin matches ahead of fuzzier matches, then spot before perp within the same match priority. The legacy renderer should inherit that behavior rather than keep the looser older ordering.
  Date/Author: 2026-04-28 / Codex

- Decision: Keep `universe_panel.cljs` in scope and rewire it to the shared helper instead of deleting it during the same task.
  Rationale: The namespace still exists in the tree and describes the same domain behavior. Reusing the helper removes duplicate logic without turning this refactor into a broader dead-code retirement task.
  Date/Author: 2026-04-28 / Codex

- Decision: Treat full Browser MCP design-review passes as conditional rather than default for this task.
  Rationale: The intended change is behavior-preserving extraction with no styling or layout delta. Deterministic Playwright coverage for the manual universe builder is the required browser gate. If the implementation accidentally changes rendered structure or style, the broader browser-QA matrix becomes mandatory and must be recorded in this plan.
  Date/Author: 2026-04-28 / Codex

- Decision: Keep `universe_candidates.cljs` under `portfolio/optimizer/application` and duplicate the tiny label primitives it needs instead of importing the view-only `instrument-display` namespace.
  Rationale: The boundary checker correctly blocks application code from depending on view namespaces. Keeping the helper in application preserves the intended ownership seam, and the copied primitives are pure display normalization rules needed to keep raw spot and HIP-3 labels stable.
  Date/Author: 2026-04-28 / Codex

## Outcomes & Retrospective

Changed files:
- `/hyperopen/src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/optimize/universe_panel.cljs`
- `/hyperopen/src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs`
- `/hyperopen/test/hyperopen/portfolio/optimizer/application/universe_candidates_test.cljs`
- `/hyperopen/test/test_runner_generated.cljs`

Complexity change: lower overall complexity. The duplicated candidate/search helpers are gone from both renderers, the new pure namespace owns filtering, ranking, display shaping, and active-index clamping, and the legacy panel now preserves its older ordering through an explicit `{:ranking :asset-query}` option instead of local duplication. The application helper does carry a small copy of raw-asset display primitives to respect namespace boundaries, but this is smaller and safer than a new boundary exception or keeping candidate logic duplicated in views.

Validation evidence:
- Red verification before implementation: `npm test` failed because `hyperopen.portfolio.optimizer.application.universe-candidates` did not exist.
- Green verification after implementation and boundary fix: `npm test` passed with `Ran 3603 tests containing 19744 assertions. 0 failures, 0 errors.`
- Browser verification: `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer manual universe"` passed 2/2 tests, and `npx playwright test tools/playwright/test/optimizer-history-network.qa.spec.mjs` passed 1/1 test.
- Required repo gates: `npm run check` exited 0 after compiling `app`, `portfolio`, `portfolio-worker`, `portfolio-optimizer-worker`, `vault-detail-worker`, and `test` with 0 warnings; `npm run test:websocket` passed with `Ran 461 tests containing 2798 assertions. 0 failures, 0 errors.`

Remaining risk: no broad Browser MCP design-review pass was run because the change is a pure extraction with no intended styling or layout delta. The deterministic Playwright flow coverage above accounts for the UI-facing behavior that changed.

## Context and Orientation

The optimizer universe is the list of instruments that the portfolio optimizer is allowed to allocate into. An instrument has an `:instrument-id` such as `perp:BTC` or `spot:@107`, a `:market-type` such as `:perp` or `:spot`, and labels such as `:coin`, `:symbol`, `:base`, and `:quote`.

The market search input reads the current query from `[:portfolio-ui :optimizer :universe-search-query]`, reads candidate markets from `[:asset-selector :markets]`, excludes instruments already present in the draft `:universe`, and passes candidate keys to `hyperopen.portfolio.optimizer.actions/handle-portfolio-optimizer-universe-search-keydown`. The keyboard handler in `/hyperopen/src/hyperopen/portfolio/optimizer/universe_keyboard.cljs` already owns arrow and enter behavior once the renderer passes a bounded ordered vector of market keys.

Two renderers currently duplicate the pure search logic. `/hyperopen/src/hyperopen/views/portfolio/optimize/universe_panel.cljs` renders the older universe card. `/hyperopen/src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs` renders the v4 control-rail section used by `/hyperopen/src/hyperopen/views/portfolio/optimize/setup_v4_sections.cljs`. Both files contain local `normalized-text`, `selected-instrument-ids`, `market-label`, `candidate-markets`, and active-index bounding logic. The v4 renderer also contains `display-name` and `market-display-name` helpers used by its richer table rows.

The focused red-phase tests already exist at `/hyperopen/test/hyperopen/portfolio/optimizer/application/universe_candidates_test.cljs`. They expect a new pure namespace at `/hyperopen/src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs` exposing `selected-instrument-ids`, `candidate-markets`, `market-display`, and `active-index`. `npm test` regenerates `test/test_runner_generated.cljs`, compiles the `test` build, and runs `node out/test.js`, so once the production namespace exists those tests will enter the normal repo validation flow automatically.

This ExecPlan does not change the action names, their argument shapes, or the route-level `data-role` anchors that current view tests and Playwright rely on. Effects remain in `/hyperopen/src/hyperopen/portfolio/optimizer/actions.cljs`, keyboard behavior remains in `/hyperopen/src/hyperopen/portfolio/optimizer/universe_keyboard.cljs`, and visual layout remains in the two renderer namespaces.

## Plan of Work

Milestone 1 is the pure namespace. Create `/hyperopen/src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs` and move the reusable candidate-search logic there. The helper should own query normalization, selected-instrument ID collection, candidate filtering, invalid-market rejection, selected-instrument exclusion, `asset-query/filter-and-sort-assets` reuse, exact-match ranking, spot-before-perp tie-breaking, result limiting to six, display-label shaping, and active-index clamping.

Milestone 2 rewires the two renderers. Update `/hyperopen/src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs` so it stops implementing `candidate-markets` locally and instead consumes the shared helper output. Update `/hyperopen/src/hyperopen/views/portfolio/optimize/universe_panel.cljs` to do the same. Keep view-local markup helpers such as tag rendering, ADV formatting, liquidity/history chips, Hiccup layout, and action vectors in the renderer namespaces.

Milestone 3 finishes the test contract. Keep `/hyperopen/test/hyperopen/portfolio/optimizer/application/universe_candidates_test.cljs` as the direct behavior suite for the new pure namespace. Update `/hyperopen/test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs` and `/hyperopen/test/hyperopen/views/portfolio/optimize/universe_panel_test.cljs` only where they need to prove that route-level action vectors, `aria-activedescendant`, and rendered `data-role` anchors remain stable after the extraction.

Milestone 4 is deterministic validation. Because this work touches UI namespaces, run the smallest relevant Playwright optimizer universe regressions first. Then finish with the repo gates required by `/hyperopen/AGENTS.md`. If the implementation causes any visible layout or styling drift, stop and add governed browser-QA PASS / FAIL / BLOCKED accounting before considering the task complete.

## Concrete Steps

All commands run from `/Users/barry/.codex/worktrees/2709/hyperopen`.

1. Reconfirm the duplication and live route seam before editing:

       rg -n "candidate-markets|selected-instrument-ids|market-label|display-name|history-label|liquidity-label" src/hyperopen/views/portfolio/optimize
       rg -n "setup-v4-universe|universe-panel" src/hyperopen/views/portfolio
       wc -l src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs src/hyperopen/views/portfolio/optimize/universe_panel.cljs

2. Keep the existing focused test file as the red contract:

       test/hyperopen/portfolio/optimizer/application/universe_candidates_test.cljs

   Expected current red state before implementation:

       npm test

   The expected failure is that Shadow CLJS or the test compile reports that `hyperopen.portfolio.optimizer.application.universe-candidates` cannot be found.

3. Create the production namespace:

       src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs

4. Wire both renderers to the shared helper:

       src/hyperopen/views/portfolio/optimize/universe_panel.cljs
       src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs

5. Run the green test cycle:

       npm test

   If debugging the generated runner is necessary, confirm the focused namespace is present:

       rg -n "universe-candidates-test" test/test_runner_generated.cljs

6. Run the smallest relevant deterministic browser regressions:

       npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer manual universe"
       npx playwright test tools/playwright/test/optimizer-history-network.qa.spec.mjs

7. Finish with the required repo gates:

       npm run check
       npm run test:websocket

8. If any Browser MCP or browser-inspection session is opened during implementation, close it before stopping:

       npm run browser:cleanup

## Validation and Acceptance

Acceptance is behavior-based and must be observable from commands, tests, or the live optimizer route.

- The new production namespace `/hyperopen/src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs` exists and exposes `selected-instrument-ids`, `candidate-markets`, `market-display`, and `active-index`. Running `npm test` must compile it successfully and execute `/hyperopen/test/hyperopen/portfolio/optimizer/application/universe_candidates_test.cljs`.

- The focused test suite must prove that already selected instruments are excluded, markets missing `:key`, `:coin`, or `:market-type` are discarded, exact symbol or coin matches rank ahead of fuzzier matches, spot candidates win ties against perps, display labels prefer symbol/base data over raw `@` IDs, and the active keyboard row index clamps into the visible candidate range.

- The route-level renderer contract must remain stable. Running `npm test` must keep `/hyperopen/test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs` and `/hyperopen/test/hyperopen/views/portfolio/optimize/universe_panel_test.cljs` green, proving that `portfolio-optimizer-universe-search-input` still dispatches `:actions/set-portfolio-optimizer-universe-search-query`, the keyboard handler still receives the ordered `market-keys` vector, `portfolio-optimizer-universe-use-current` still dispatches the current-holdings action, and add/remove buttons keep their existing `data-role` names.

- Rendering `/portfolio/optimize/new` through `hyperopen.views.portfolio-view/portfolio-view` must still expose `data-role="portfolio-optimizer-universe-panel"`, the integrated search shell, and the same visible candidate row ordering for the existing raw-spot and symbol-first cases already covered in the route/view tests.

- Running `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer manual universe"` must pass, proving that the manual universe builder still supports search, add, remove, and keyboard-selection flows in a real browser. Running `npx playwright test tools/playwright/test/optimizer-history-network.qa.spec.mjs` must also pass, proving that adding an asset still does not trigger history fetches before the intended history-load/run path.

- Running `npm run check` and `npm run test:websocket` from `/Users/barry/.codex/worktrees/2709/hyperopen` must pass after the extraction. Because no visual change is intended, no broader Browser MCP design-review bundle is required unless implementation produces a rendered layout or styling delta. If such a delta appears, this plan must be updated with explicit browser-QA PASS / FAIL / BLOCKED accounting before the task can close.

## Idempotence and Recovery

The refactor is additive first: the red tests already exist before the production namespace, and the shared helper can be added before either renderer is rewired. Re-running `npm run test:runner:generate` is safe because it regenerates `test/test_runner_generated.cljs` from test files. If the renderer refactor fails, restore the local helper bodies temporarily while keeping the focused test file as the contract, then re-run `npm test` before broadening validation again.

If the implementation introduces regressions, recovery is local. Revert the shared-helper `require` in the affected renderer, restore the removed local helper bodies from git history, and rerun `npm test`. Do not revert unrelated optimizer UI or action-layer files. Do not run `git pull --rebase` or `git push` during this plan unless the user explicitly requests remote sync.

## Artifacts and Notes

Useful audit commands during implementation:

    rg -n "portfolio optimizer manual universe|optimizer-history-network" tools/playwright/test/portfolio-regressions.spec.mjs tools/playwright/test/optimizer-history-network.qa.spec.mjs
    rg -n "portfolio-optimizer-universe-search-input|portfolio-optimizer-universe-add-|portfolio-optimizer-universe-remove-" test/hyperopen/views/portfolio/optimize

Current line-count baseline recorded during planning:

    324 src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs
    161 src/hyperopen/views/portfolio/optimize/universe_panel.cljs

Those counts are not acceptance criteria by themselves, but they are useful for checking that the live v4 universe namespace actually becomes more presentation-focused after the extraction.

## Interfaces and Dependencies

The new namespace is:

    hyperopen.portfolio.optimizer.application.universe-candidates

It must expose:

    (selected-instrument-ids universe)
    (candidate-markets state universe query)
    (market-display market-or-instrument)
    (active-index state markets)

The namespace may depend on:

    clojure.string
    hyperopen.asset-selector.query

It must not depend on `hyperopen.views.*` namespaces. The boundary checker enforces that application namespaces cannot import view namespaces.

Revision note 2026-04-28 17:05Z: Final evidence recorded after implementation, namespace-boundary fix, Playwright browser regressions, `npm test`, `npm run check`, and `npm run test:websocket`.

It must not dispatch actions, mutate state, read browser storage, or render Hiccup. Effects remain in `/hyperopen/src/hyperopen/portfolio/optimizer/actions.cljs`, keyboard behavior remains in `/hyperopen/src/hyperopen/portfolio/optimizer/universe_keyboard.cljs`, and visual layout remains in the two renderer namespaces.

Plan revision note: 2026-04-28 16:47Z - Refreshed the existing active plan after auditing repo planning rules, browser-testing policy, the live v4 route seam, and the pre-existing red-phase test file so the implementation can proceed without parallel plan documents.
