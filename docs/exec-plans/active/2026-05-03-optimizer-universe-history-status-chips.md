---
owner: product+platform
status: active
source_of_truth: true
tracked_issue: hyperopen-cr4r
---

# Optimizer Universe History Status Chips

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while the work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. It is self-contained so an engineer can execute the work without relying on the conversation that produced it.

Tracked issue: `hyperopen-cr4r` ("Correct optimizer universe history status chips").

## Purpose / Big Picture

On `/portfolio/optimize/new`, the universe search dropdown currently shows a green `sufficient` chip for assets such as BTC before the user has added the asset and before optimizer history has been fetched or validated. That is incorrect because the optimizer cannot know whether the asset has sufficient usable return history until the selected universe is evaluated against cached or freshly fetched history.

After this change, search dropdown candidates will no longer claim validated optimizer history and will not show a history-status chip at all. Selected universe rows will show the real state derived from optimizer readiness: `pending`, `loading`, `missing`, `insufficient`, or `sufficient`. A user can see the fix by opening `/portfolio/optimize/new`, typing `btc`, and confirming the dropdown displays the asset candidate without any history-status chip before an optimization/history pass.

## Progress

- [x] (2026-05-03 18:39Z) Created and claimed tracked issue `hyperopen-cr4r`.
- [x] (2026-05-03 18:39Z) Traced the inaccurate dropdown label to `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs`.
- [x] (2026-05-03 18:39Z) Wrote this active ExecPlan.
- [x] (2026-05-03 19:58Z) Added RED tests for candidate rows and selected-row `pending`, `loading`, `missing`, `insufficient`, and `sufficient` states.
- [x] (2026-05-03 20:03Z) Verified RED with `npm test`: 5 expected failures in `setup_v4_layout_test.cljs` showed candidates still rendered `sufficient`, pending/loading selected rows rendered `missing`, and one-row history rendered `sufficient`.
- [x] (2026-05-03 20:08Z) Implemented candidate `pending` default, selected-row status derivation, and `readiness` / `history-load-state` threading through the setup control rail.
- [x] (2026-05-03 20:12Z) Verified GREEN with `npm test`: `3753 tests`, `20722 assertions`, `0 failures`, `0 errors`.
- [x] (2026-05-03 20:14Z) Ran `npm run test:websocket`: `524 tests`, `3043 assertions`, `0 failures`, `0 errors`.
- [x] (2026-05-03 20:20Z) Added a focused namespace-size exception for `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs` after the new regression tests brought it to 575 lines; `npm run lint:namespace-sizes` and `npm run lint:namespace-sizes:test` passed.
- [x] (2026-05-03 20:18Z) Ran relevant browser QA with `PLAYWRIGHT_REUSE_EXISTING_SERVER=true npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer (setup|universe|manual universe)"`: `7 passed`.
- [x] (2026-05-03 20:18Z) Ran `npm run browser:cleanup`: returned `ok: true` with no tracked sessions stopped.
- [x] (2026-05-03 20:44Z) Added RED follow-up coverage proving search candidates render no `pending`, `unchecked`, or `sufficient` history-status chip.
- [x] (2026-05-03 20:45Z) Verified RED with `npm test`: 1 expected failure in `setup-v4-universe-search-candidates-do-not-render-history-status-chip-test` because candidates still rendered `pending`.
- [x] (2026-05-03 20:47Z) Removed the candidate history-status chip and its dropdown grid column while preserving candidate add behavior.
- [x] (2026-05-03 20:50Z) Verified GREEN with `npm test`: `3753 tests`, `20723 assertions`, `0 failures`, `0 errors`.
- [x] (2026-05-03 20:53Z) Ran `npm run test:websocket`: `524 tests`, `3043 assertions`, `0 failures`, `0 errors`.
- [x] (2026-05-03 20:53Z) Ran `npm run lint:namespace-sizes` and `npm run lint:namespace-sizes:test`: both passed.
- [x] (2026-05-03 20:55Z) Ran relevant browser QA with `PLAYWRIGHT_REUSE_EXISTING_SERVER=true npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer (setup|universe|manual universe)"`: `7 passed`.
- [x] (2026-05-03 20:56Z) Ran `npm run browser:cleanup`: returned `ok: true` with no tracked sessions stopped.
- [ ] `npm run check` remains blocked by unrelated active ExecPlan guardrail `docs/exec-plans/active/2026-05-03-outcome-no-market-order-book-subscription.md` having no unchecked progress items.

## Surprises & Discoveries

- Observation: Search dropdown candidates default to `sufficient` even when the candidate has no `:history-label`.
  Evidence: In `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs`, `market-row` binds `history` with `(:history-label market "sufficient")`, and `candidate-markets` returns asset/vault candidates without that key.

- Observation: Selected rows already read optimizer history cache, but only as a binary "some rows exist" check.
  Evidence: `history-label` in `setup_v4_universe.cljs` returns `sufficient` when `[:portfolio :optimizer :history-data :candle-history-by-coin coin]` or `[:portfolio :optimizer :history-data :vault-details-by-address vault-address]` has any sequence. It does not check the optimizer's minimum observation count or shared calendar eligibility.

- Observation: The actual optimizer eligibility model already exists and should be reused.
  Evidence: `src/hyperopen/portfolio/optimizer/application/history_loader/alignment.cljs` emits `:missing-candle-history`, `:insufficient-candle-history`, `:missing-vault-history`, `:insufficient-vault-history`, and `:insufficient-common-history`. `src/hyperopen/portfolio/optimizer/application/setup_readiness.cljs` exposes those warnings through `build-readiness`.

- Observation: Adding a universe instrument does not fetch history immediately.
  Evidence: `src/hyperopen/portfolio/optimizer/actions/universe.cljs` appends the instrument to `[:portfolio :optimizer :draft :universe]` and clears search UI state only. The run pipeline in `src/hyperopen/runtime/effect_adapters/portfolio_optimizer_pipeline.cljs` fetches history when readiness is not runnable.

- Observation: This worktree initially had no `node_modules`, so the first `npm test` run failed before reaching assertions.
  Evidence: Shadow compiled the test build, then Node failed to resolve `lucide/dist/esm/icons/external-link.js`. Running `npm ci` installed the locked dependencies and allowed tests to run.

- Observation: `npm run check` currently fails before reaching ClojureScript compilation because of an unrelated active ExecPlan hygiene issue.
  Evidence: `npm run check` stopped at `lint:docs` with `[active-exec-plan-no-unchecked-progress] docs/exec-plans/active/2026-05-03-outcome-no-market-order-book-subscription.md - active ExecPlan has no remaining unchecked progress items; move it out of active`.

- Observation: The broad Playwright grep for all portfolio optimizer regressions includes scenario-detail and execution-modal cases unrelated to this setup-search chip fix that fail against the reused local server.
  Evidence: `PLAYWRIGHT_REUSE_EXISTING_SERVER=true npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer"` passed the setup/universe cases through test 11, then failed tests expecting `Funding Decomposition` and execution modal controls. The narrower setup/universe subset passed with `7 passed`.

- Observation: The added regression coverage pushed `setup_v4_layout_test.cljs` over the namespace-size threshold.
  Evidence: `wc -l` reported 575 lines. A targeted exception was added to `dev/namespace_size_exceptions.edn`, and both namespace-size commands passed afterward.

## Decision Log

- Decision: Superseded: candidate rows initially showed `pending`, not `sufficient`.
  Rationale: A dropdown candidate is selectable metadata from the asset selector or vault index. It is not an optimizer-ready instrument until selected history has been fetched and validated. User feedback clarified that even `pending` implies an in-flight or known validation state, so the later decision removes the chip entirely.
  Date/Author: 2026-05-03 / Codex

- Decision: Search dropdown candidate rows will show no history-status chip, rather than `pending` or another pre-validation label.
  Rationale: The dropdown is not fetching or validating optimizer history at search time, so any status-like chip implies a validation state that does not exist yet. Selected universe rows retain status chips because they are the rows evaluated by readiness and history-load state.
  Date/Author: 2026-05-03 / Codex

- Decision: Selected-row `sufficient` will mean the instrument is present in `readiness[:request :universe]`, not merely that some cached history rows exist.
  Rationale: `readiness[:request :universe]` is produced by `request-builder/build-engine-request` after `history-loader/align-history-inputs`; it reflects the same eligible universe the optimizer would run.
  Date/Author: 2026-05-03 / Codex

- Decision: Per-row chips will not try to explain `:insufficient-common-history`.
  Rationale: Common-history failures are universe-level failures caused by the overlap across selected assets. The existing readiness panel is the right place to show that explanation; assigning it to one row would be misleading.
  Date/Author: 2026-05-03 / Codex

- Decision: Missing-history selected-row chips are shown only after a matching history load has succeeded; idle rows with no cached validation show `pending`.
  Rationale: `setup-readiness/build-readiness` can report missing history from an empty initial cache before the user has run the optimizer. Showing `missing` at that moment repeats the original problem in selected-row form. A matching succeeded history-load signature distinguishes a validated miss from a not-yet-validated asset.
  Date/Author: 2026-05-03 / Codex

## Outcomes & Retrospective

Implemented the UI correctness fix without changing optimizer execution, history loading, or candidate ranking behavior. Search dropdown candidates now render no history-status chip instead of `sufficient` or `pending`. Selected rows now consume optimizer readiness and history-load state so they can display `pending`, `loading`, `missing`, `insufficient`, or `sufficient` based on validation state.

The implementation slightly increases view-layer complexity by adding a small status derivation helper in `setup_v4_universe.cljs`, but it reduces product ambiguity. The helper keeps side effects out of render code and consumes only state already computed by `workspace_view.cljs`. The only remaining blocker is external to this task: `npm run check` cannot pass until the unrelated completed-looking active ExecPlan is moved or given a valid unchecked item.

## Context and Orientation

The affected route is `/portfolio/optimize/new`. It renders through `src/hyperopen/views/portfolio/optimize/workspace_view.cljs`, which computes `draft`, `readiness`, `history-load-state`, run status, and other setup state. It passes setup data into `src/hyperopen/views/portfolio/optimize/setup_v4_sections.cljs`, which renders the left control rail. That control rail calls `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs` to render the universe section, including the search box, dropdown candidates, and selected universe table.

A "candidate" is an asset or vault row shown in the search dropdown before the user has added it to the optimizer universe. Candidates come from `src/hyperopen/portfolio/optimizer/application/universe_candidates.cljs`. This namespace combines market data from `[:asset-selector :markets]` and vault rows from `[:vaults :merged-index-rows]`. It does not fetch optimizer history and does not know whether the candidate can pass optimizer history validation.

A "selected row" is an instrument already present in `[:portfolio :optimizer :draft :universe]`. Selected rows can be evaluated by the optimizer readiness model because `setup-readiness/build-readiness` builds an engine request from the draft, current portfolio, cached history data, market-cap prior data, and runtime timestamps. That readiness request contains `:requested-universe` for all selected instruments and `:universe` for the eligible subset with usable aligned history. It also contains `:blocking-warnings` with missing or insufficient history warnings.

The current incorrect UI is in `setup_v4_universe.cljs`. The private `market-row` function renders dropdown candidates and defaults missing `:history-label` to `sufficient`. The private `selected-row` function calls `history-label`, which checks whether any cached history exists for the coin or vault address. Both paths are too optimistic for the labels they display.

## Plan of Work

First, add focused tests in `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs`. The test suite already renders the v4 setup route and has helpers such as `node-by-role`, `collect-strings`, `click-actions`, and `keydown-actions`. Add tests that render a non-blank search query and assert that a candidate row does not include history-status terms such as `pending`, `unchecked`, or `sufficient`. Add selected-row tests that render `setup-v4-universe/universe-section` directly with crafted `readiness` and `history-load-state` inputs. These tests should prove the five selected statuses: pending before a completed validation, loading while history is in flight, missing after a missing-history warning, insufficient after an insufficient-history warning, and sufficient when the instrument is in the eligible request universe with no blocking warning for that instrument.

Second, change `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs`. Remove the default `sufficient` from `market-row` and do not render a candidate history-status chip. Add a helper that derives selected-row history status from state plus readiness. The helper should use the instrument id as the primary key. It should return `loading` when `history-load-state[:status]` is `:loading` and the loading request signature includes that instrument. It should return `missing` for warning codes `:missing-history-coin`, `:missing-candle-history`, `:missing-vault-address`, or `:missing-vault-history`. It should return `insufficient` for warning codes `:insufficient-candle-history` or `:insufficient-vault-history`. It should return `sufficient` when the selected instrument id is in `readiness[:request :universe]`. It should return `pending` when none of those cases apply.

Third, thread `readiness` and `history-load-state` into the universe section. Modify `src/hyperopen/views/portfolio/optimize/setup_v4_sections.cljs` so `control-rail` accepts `:readiness` and `:history-load-state` in its argument map and passes both into `setup-v4-universe/universe-section`. Modify `src/hyperopen/views/portfolio/optimize/workspace_view.cljs` so the existing call to `setup-v4/control-rail` includes the `readiness` and `history-load-state` values it already computes. Keep the previous `universe-section` arity or default behavior if practical so direct tests and any existing call sites remain simple; there is currently only one production call site.

Fourth, keep the display compact and consistent with the current v4 rail. The existing `tag` helper can render the new labels. Use warning tone for `pending`, `loading`, `missing`, and `insufficient` unless a more precise existing tone is already appropriate. Use success tone only for `sufficient`. Do not add new actions, new effects, history prefetch behavior, or optimizer request behavior in this plan.

Finally, run focused tests first, then broaden to the required gates and browser verification. Because this touches a UI route and browser-visible labels, run a small Playwright check for the optimizer setup route after ClojureScript tests pass. If a browser-inspection session or Playwright browser remains open, run `npm run browser:cleanup`.

## Concrete Steps

Work from `/Users/barry/.codex/worktrees/7048/hyperopen`.

1. Edit `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs` and add the candidate-row regression. Use a state similar to the existing `setup-v4-universe-search-renders-vault-candidates-test`, but search for `btc` against an asset-selector market candidate. Assert the candidate row exists, the add action still dispatches correctly, and the candidate row's strings do not contain `pending`, `unchecked`, or `sufficient`.

2. In the same test file, add selected-row status tests against `setup-v4-universe/universe-section`. Build a BTC instrument map like:

       {:instrument-id "perp:BTC"
        :market-type :perp
        :coin "BTC"
        :symbol "BTC-USDC"
        :name "Bitcoin"}

   For pending, pass `{:portfolio-ui {:optimizer {:universe-search-query ""}}}` and draft `{:universe [btc]}` with no readiness map or an empty request universe. Expect `pending` and not `sufficient`.

   For loading, pass `{:portfolio {:optimizer {:history-load-state {:status :loading :request-signature {:universe [btc]}}}}}`. Expect `loading`.

   For missing, pass a readiness map with `:request {:requested-universe [btc] :universe []}` and `:blocking-warnings [{:code :missing-candle-history :instrument-id "perp:BTC" :coin "BTC"}]`. Expect `missing`.

   For insufficient, use the same shape with `:code :insufficient-candle-history`, `:observations 1`, and `:required 2`. Expect `insufficient`.

   For sufficient, pass `:request {:requested-universe [btc] :universe [btc]}` and no blocking warnings. Expect `sufficient`.

3. Run the focused ClojureScript suite. If the project runner does not support namespace targeting, use the smallest available command already used by the repository. Start with:

       npm test

   Expected before implementation: the new assertions fail because candidate rows show `sufficient` and selected rows derive history status from the old binary cache check.

4. Edit `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs`. Remove the candidate-row history label and status chip. The old defect started from:

       history (:history-label market "sufficient")

   Do not replace this with another default status. Search candidates should render symbol/name/type/ADV/add affordances only. Do not add a `:history-label` to `candidate-markets`; candidate generation should remain presentation-agnostic.

5. In `setup_v4_universe.cljs`, replace the old selected-row `history-label` helper with a status helper that accepts `state`, `readiness`, `history-load-state`, and `instrument`. Keep the function private. Use warning-code sets so the mapping is explicit:

       missing-history-warning-codes
       #{:missing-history-coin
         :missing-candle-history
         :missing-vault-address
         :missing-vault-history}

       insufficient-history-warning-codes
       #{:insufficient-candle-history
         :insufficient-vault-history}

   Add small helpers to collect instrument ids from the loading request signature, readiness eligible universe, and warning list. Return string labels because the existing `tag` function expects display text.

6. Update `selected-row`, `selected-table`, and `universe-section` in `setup_v4_universe.cljs` so selected rows receive the derived status context. Preserve existing `data-role` values and add/remove actions. Preserve the search input action vectors and keyboard behavior.

7. Edit `src/hyperopen/views/portfolio/optimize/setup_v4_sections.cljs`. Change `control-rail` from destructuring `{:keys [state draft highlighted-controls]}` to include `readiness` and `history-load-state`. Pass an options map or extra arguments into `setup-v4-universe/universe-section` using the final signature chosen in step 6.

8. Edit `src/hyperopen/views/portfolio/optimize/workspace_view.cljs`. In the call to `setup-v4/control-rail`, pass `:readiness readiness` and `:history-load-state history-load-state`.

9. Run focused tests again, then the full required gates:

       npm run check
       npm test
       npm run test:websocket

10. Because the route is browser-visible UI, run the smallest relevant optimizer Playwright check. Prefer the existing optimizer setup regression:

       npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer"

    If browser or Browser MCP sessions remain open, run:

       npm run browser:cleanup

## Validation and Acceptance

The fix is accepted when `/portfolio/optimize/new` no longer shows `SUFFICIENT`, `PENDING`, or any other history-status chip on search dropdown candidates before selection/history validation. Typing `btc` should show BTC as a selectable asset with no history-status chip, and the row should still dispatch `[:actions/add-portfolio-optimizer-universe-instrument "perp:BTC"]` or the market key produced by the candidate source.

Selected universe rows must show honest status. A newly added asset with no completed history validation should show `pending`. While a matching history load is in flight, it should show `loading`. After readiness reports `:missing-candle-history` or `:missing-vault-history`, it should show `missing`. After readiness reports `:insufficient-candle-history` or `:insufficient-vault-history`, it should show `insufficient`. When the instrument id is present in `readiness[:request :universe]`, it should show `sufficient`.

The implementation must not change optimizer run behavior, history loading behavior, candidate ranking, selected universe add/remove actions, or the readiness panel copy. Required repository gates are `npm run check`, `npm test`, and `npm run test:websocket`. Browser QA must be explicitly accounted for before concluding UI work is complete.

## Idempotence and Recovery

The work is a narrow view-layer correction. Re-running tests is safe. If the component signature change breaks a call site, search for `universe-section` and `control-rail`; production usage should be limited to the optimizer setup surface. If the selected-row helper accidentally marks every row `pending`, inspect the shape of `readiness` from `workspace_view.cljs` and confirm it is passed through `setup_v4_sections.cljs` into `setup_v4_universe.cljs`.

No data migration or destructive command is required. Do not reset unrelated worktree changes. If browser verification leaves sessions open, run `npm run browser:cleanup`.

## Artifacts and Notes

The user-provided screenshot from 2026-05-03 showed `/portfolio/optimize/new` with `btc` typed into the universe search input and a BTC-USDC candidate row displaying `PERP`, `SUFFICIENT`, `$961M`, and `+ add`. The central defect is that the `SUFFICIENT` chip appears before an optimizer history fetch or readiness validation has happened.

Relevant current snippets:

    src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs
    market-row:
      history (:history-label market "sufficient")

    src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs
    history-label:
      returns "sufficient" when cached candle or vault detail data has any rows.

    src/hyperopen/portfolio/optimizer/application/history_loader/alignment.cljs
      emits missing and insufficient history warnings after normalizing selected universe history.

    src/hyperopen/runtime/effect_adapters/portfolio_optimizer_pipeline.cljs
      fetches history when initial readiness is not runnable, then builds a ready request before running the worker.

## Interfaces and Dependencies

No new third-party dependency is required.

Preserve these existing public or test-observed interfaces:

    hyperopen.views.portfolio.optimize.setup-v4-universe/universe-section
    [state draft] or a backward-compatible extension such as [state draft opts] -> Hiccup

    hyperopen.views.portfolio.optimize.setup-v4-sections/control-rail
    [{:keys [state draft highlighted-controls readiness history-load-state]}] -> Hiccup

    hyperopen.portfolio.optimizer.application.setup-readiness/build-readiness
    [state] -> readiness map with :request, :blocking-warnings, :warnings, :status, and :reason

The view code should consume readiness data only. It must not call history effects, run optimizer effects, or mutate state while rendering.
