---
owner: product+platform
status: completed
source_of_truth: true
tracked_issue: hyperopen-r3lj
related_issues:
  - hyperopen-cr4r
  - hyperopen-ejzz
  - hyperopen-rfdz
---

# Portfolio Optimizer Selection History Prefetch ExecPlan

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while the work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. It is self-contained so an engineer can execute the work without relying on the conversation that produced it.

Tracked issue: `hyperopen-r3lj` ("Prefetch optimizer history on universe selection").

## Purpose / Big Picture

Users build an optimizer universe one instrument at a time on `/portfolio/optimize/new`. Today, the app waits until the user clicks Run optimization before it fetches return history for the selected universe. That makes Run optimization feel slow and concentrates several candle, funding, and vault-detail API requests into one burst.

After this change, adding an instrument to the draft universe starts history loading for that instrument immediately. The requests are queued and drained one instrument at a time, so a user who adds BTC, waits briefly, then adds ETH causes a small sequence of API work instead of a large burst at run time. Run optimization still remains safe: if prefetch has not completed, failed, or missed something, the existing optimizer pipeline fetches the missing current-universe history before starting the worker.

The user-visible behavior is visible in the universe table. A selected row should move from pending or queued to loading and then sufficient, insufficient, or missing. If the user waits for the selected rows to become sufficient, clicking Run optimization should start the solver without making another candle, funding, or vault-detail request for those already-prefetched instruments.

## Progress

- [x] (2026-05-03 22:58Z) Created and claimed tracked issue `hyperopen-r3lj`.
- [x] (2026-05-03 23:02Z) Read the repo planning contract, active ExecPlan examples, browser testing policy, and frontend interaction policy.
- [x] (2026-05-03 23:08Z) Inspected the current optimizer universe actions, history loader, history effect adapter, run pipeline, setup readiness, setup universe UI, and existing unit and Playwright tests.
- [x] (2026-05-03 23:18Z) Wrote this active ExecPlan.
- [x] (2026-05-03 23:22Z) Incorporated independent `spec_writer` review findings about setup readiness copy, add-then-run overlap, and explicit reversal of the older no-fetch-until-run browser contract.
- [x] (2026-05-03 23:25Z) Self-reviewed the plan for banned incomplete-work markers and ran doc validation. `npm run lint:docs` and `npm run lint:docs:test` passed.
- [x] (2026-05-04 03:41Z) Added pure prefetch planning helpers and action-level tests.
- [x] (2026-05-04 03:57Z) Emitted queued selection-prefetch effects from universe-add and current-holdings selection actions.
- [x] (2026-05-04 04:24Z) Extended the history effect adapter to drain queued selection prefetches one instrument at a time and merge returned bundles into existing history data.
- [x] (2026-05-04 04:39Z) Made the run pipeline wait for an active prefetch to settle before deciding whether a full current-universe load is still required.
- [x] (2026-05-04 04:54Z) Updated setup universe UI status handling and copy so queued and loading selection-prefetch states are clear.
- [x] (2026-05-04 05:37Z) Updated deterministic unit tests and Playwright coverage for the new network behavior.
- [x] (2026-05-04 06:06Z) Ran required validation gates and recorded results.

## Surprises & Discoveries

- Observation: The existing committed browser tests intentionally assert the old behavior.
  Evidence: `tools/playwright/test/optimizer-history-network.qa.spec.mjs` records zero history requests after adding `perp:ETH`, then expects `candleSnapshot:ETH` and `fundingHistory:ETH` only after clicking `portfolio-optimizer-run-draft`.

- Observation: The old no-fetch-until-run behavior was recently and deliberately encoded, so changing it should be treated as a product decision rather than an accidental test update.
  Evidence: The current Playwright regression was added during the optimizer v4 setup work around April 27, 2026 and states "adding an asset does not fetch history until run".

- Observation: `run-portfolio-optimizer-pipeline-effect` already gives Run optimization a safe fallback by loading history before the worker when readiness is blocked.
  Evidence: `src/hyperopen/runtime/effect_adapters/portfolio_optimizer_pipeline.cljs` calls `load-history!` when `setup-readiness/build-readiness` is not runnable, then rebuilds readiness and requests the worker.

- Observation: The current history effect cannot safely be used for concurrent incremental prefetch without changes.
  Evidence: `src/hyperopen/runtime/effect_adapters/portfolio_optimizer/history.cljs` writes one global `:history-load-state` request signature, replaces `:history-data` on success, and ignores success when its signature is no longer the current signature.

- Observation: The existing request builder can already fetch a subset if given a subset universe.
  Evidence: `src/hyperopen/portfolio/optimizer/infrastructure/history_client.cljs` passes request `:universe` to `history-loader/build-history-request-plan`, which builds candle, funding, and vault-detail requests only for the provided instruments.

- Observation: The setup universe table already has most of the user-visible status vocabulary.
  Evidence: `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs` renders selected rows with pending, loading, missing, insufficient, and sufficient labels using readiness and history-load-state.

- Observation: Readiness copy also needs to change outside the universe table.
  Evidence: `src/hyperopen/views/portfolio/optimize/setup_readiness_panel.cljs` still says "Run Optimization will fetch history before computing", "Run Optimization will refresh history for this changed universe", and "History reload is in flight as part of the optimization run."

- Observation: A bulk "From holdings" action can add several instruments at once, so selection prefetch must not assume all entry points are single-add clicks.
  Evidence: `set-portfolio-optimizer-universe-from-current` in `src/hyperopen/portfolio/optimizer/actions/universe.cljs` replaces the draft universe with all current exposure instruments in one state write.

- Observation: Full repo check is currently blocked before it reaches doc lint and CLJS compilation in this worktree.
  Evidence: `npm run check` reached `npm run test:multi-agent` and failed because Node could not resolve declared packages `zod` and `smol-toml` from local `node_modules`; `package.json` and `package-lock.json` both declare those dependencies.

- Observation: Refreshing installed packages fixed the local dependency hole without changing package manifests.
  Evidence: `npm install` restored missing declared packages; `git status --short` showed no `package.json` or `package-lock.json` changes afterward.

- Observation: The default Playwright web-server port was owned by another Hyperopen worktree, so focused Playwright validation used an alternate port.
  Evidence: `lsof -nP -iTCP:8080 -sTCP:LISTEN` showed a Java process rooted at `/Users/barry/.codex/worktrees/ccbe/hyperopen`; focused Playwright commands used `PLAYWRIGHT_BASE_URL=http://127.0.0.1:4174` and `PLAYWRIGHT_WEB_PORT=4174`.

## Decision Log

- Decision: Prefetch only the newly included instruments, not the full draft universe after every selection.
  Rationale: Fetching the full universe on every add would refetch previously selected assets and still create bursts when the user adds several instruments. The current history client can request a subset universe, and history data is keyed by coin or vault address, so incremental merge is the smallest useful design.
  Date/Author: 2026-05-03 / Codex

- Decision: Do not adopt the simpler full-current-universe reload suggested as an implementation alternative during review.
  Rationale: A full-universe reload would be simpler because the existing effect already builds a request from the draft, but it weakens the user's stated API-shaping goal when a selected universe grows. The chosen queued incremental design is more complex, so this plan requires explicit merge, duplicate suppression, and add-then-run tests to make that complexity defensible.
  Date/Author: 2026-05-03 / Codex

- Decision: Queue prefetch work and drain it one instrument at a time.
  Rationale: The user explicitly wants to avoid overburdening the API by rapidly fetching multiple instruments' history at once. A queue also handles the current-holdings bulk path without launching requests for every holding in parallel.
  Date/Author: 2026-05-03 / Codex

- Decision: Keep Run optimization as an authoritative fallback path.
  Rationale: Prefetch is a latency and API-shaping improvement, not a new correctness dependency. If prefetch is still running, failed, stale, or incomplete, the existing pipeline must still fetch what the current draft needs before starting the solver.
  Date/Author: 2026-05-03 / Codex

- Decision: Store prefetch progress separately while preserving the existing global history-load-state for compatibility.
  Rationale: The global load state is already used by setup readiness and row status. A separate prefetch queue/by-instrument map lets the UI represent queued and per-row failures without losing the current full-load behavior.
  Date/Author: 2026-05-03 / Codex

- Decision: Add an effect-order policy entry for selection actions that emit prefetch I/O.
  Rationale: `/hyperopen/docs/FRONTEND.md` requires projection updates before heavy I/O. The add action must save the selected instrument into the draft before emitting `:effects/load-portfolio-optimizer-history`.
  Date/Author: 2026-05-03 / Codex

## Outcomes & Retrospective

Implemented selection-time optimizer history prefetch.

The implementation adds a small amount of runtime coordination complexity: the optimizer now owns a separate `:history-prefetch` queue, by-instrument status map, and active item marker in addition to the existing full-history load state. That complexity is bounded to pure queue helpers, universe actions, the history adapter, and the run pipeline. In exchange, adding an instrument now starts background history loading for only the missing selected instrument, queued one at a time, while Run optimization remains an authoritative fallback for failed, stale, incomplete, or still-missing history.

Validation evidence:

- `npm test` passed: 3758 tests, 20744 assertions, 0 failures, 0 errors.
- `npm run check` passed after the namespace-size and formal-vector updates.
- `npm run test:websocket` passed: 524 tests, 3043 assertions, 0 failures, 0 errors.
- `bb tools/formal.clj sync --surface effect-order-contract` passed and regenerated the effect-order conformance vector.
- Focused Playwright passed on alternate port 4174: `tools/playwright/test/optimizer-history-network.qa.spec.mjs`.
- Focused Playwright passed on alternate port 4174: `tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer selection prefetch"`.

Browser QA accounting: the focused Playwright checks cover the interaction and network behavior that changed. Native-control behavior is unchanged because the add controls and run button remain the existing controls. Styling and layout changes are limited to status text and readiness copy, covered by setup layout/readiness CLJS tests. No Browser MCP or browser-inspection sessions were opened, and Playwright exited cleanly.

## Context and Orientation

The optimizer universe is the list of selected instruments that the optimizer may allocate to. An instrument can be a perp, a spot market, or a vault. A perp requires candle history and funding history; a spot market requires candle history; a vault requires vault details from which return history is derived.

The main files involved in this change are:

- `src/hyperopen/portfolio/optimizer/actions/universe.cljs` owns adding and removing instruments from the draft universe and replacing the universe from current holdings.
- `src/hyperopen/portfolio/optimizer/actions/run.cljs` owns `load-portfolio-optimizer-history-from-draft`, `run-portfolio-optimizer-from-draft`, and route-loading actions.
- `src/hyperopen/portfolio/optimizer/actions/common.cljs` owns shared draft save helpers and instrument normalization helpers.
- `src/hyperopen/portfolio/optimizer/application/setup_readiness.cljs` builds the current optimizer request and decides whether the solver can run with currently loaded history.
- `src/hyperopen/portfolio/optimizer/application/history_loader/request_plan.cljs` maps a universe into candle, funding, and vault-detail request plans.
- `src/hyperopen/portfolio/optimizer/infrastructure/history_client.cljs` executes a history request plan and returns a bundle containing `:candle-history-by-coin`, `:funding-history-by-coin`, `:vault-details-by-address`, `:warnings`, and `:request-plan`.
- `src/hyperopen/runtime/effect_adapters/portfolio_optimizer/history.cljs` owns the store mutation for history loads.
- `src/hyperopen/runtime/effect_adapters/portfolio_optimizer_pipeline.cljs` owns the Run optimization pipeline that loads missing history and then starts the worker.
- `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs` renders search candidates and selected universe rows.
- `src/hyperopen/views/portfolio/optimize/setup_readiness_panel.cljs` renders readiness copy that currently describes history loading as something Run optimization does.
- `src/hyperopen/views/portfolio/optimize/workspace_view.cljs` passes readiness and history-load-state into the setup UI.
- `src/hyperopen/runtime/effect_order_contract.cljs` is the centralized interaction-order policy for actions that emit heavy I/O.

The existing flow is:

1. The user searches in `portfolio-optimizer-universe-search-input`.
2. Clicking a candidate row or `+ add` dispatches `[:actions/add-portfolio-optimizer-universe-instrument market-key]`.
3. `add-portfolio-optimizer-universe-instrument` saves the new draft universe and clears the search query. It does not load history.
4. Clicking `portfolio-optimizer-run-draft` dispatches `[:actions/run-portfolio-optimizer-from-draft]`.
5. The run action emits `[:effects/run-portfolio-optimizer-pipeline]`.
6. The pipeline loads history if readiness is blocked, then builds a request and starts the optimizer worker.

The new flow should be:

1. The user adds an instrument or chooses From holdings.
2. The action first saves the draft universe and queues any instruments whose required history is not already loaded or already queued.
3. If no selection prefetch is active, the action emits one `:effects/load-portfolio-optimizer-history` with selection-prefetch options.
4. The history effect drains the queue sequentially. Each queue item requests history for one instrument, merges the returned bundle into existing `:history-data`, marks that instrument succeeded or failed, and then starts the next queued item.
5. The setup UI reflects queued and loading state per selected row.
6. Run optimization waits for an active prefetch to settle if one is in progress, then uses readiness to decide whether a full current-universe load is still needed.

## Plan of Work

Milestone 1 adds pure planning helpers and action behavior. Create `src/hyperopen/portfolio/optimizer/application/history_prefetch.cljs`. This namespace should be pure: it must inspect state and instruments but emit no side effects itself. Include helpers with stable names such as `required-history-loaded?`, `prefetch-instrument?`, `enqueue-missing-instruments`, `prefetch-active?`, and `selection-prefetch-effect`. The helpers should treat an instrument as already loaded when its required keys exist in `:portfolio :optimizer :history-data`; they should treat it as not needing a new request when it is already queued or actively loading. For perps, consider both candle history and funding history required for prefetch so the prefetch path mirrors the current run-time request plan, even though missing funding is not currently a readiness blocker.

Update `src/hyperopen/portfolio/optimizer/actions/universe.cljs` so successful add and From holdings actions still return one leading `:effects/save-many` projection effect, but also queue history prefetch metadata and, when no queue is already active, append one `:effects/load-portfolio-optimizer-history` effect. The effect options should be a map with at least `{:source :selection-prefetch :queue? true :merge? true}`. Do not emit any prefetch effect when the market key is missing, the instrument is already in the universe, the history is already loaded, or no current holdings are available. Removing an instrument must not start a fetch; it should only clean draft constraints and any queued prefetch state for the removed instrument.

Add an effect-order policy entry in `src/hyperopen/runtime/effect_order_contract.cljs` for `:actions/add-portfolio-optimizer-universe-instrument` and `:actions/set-portfolio-optimizer-universe-from-current`. The required phase order should be projection before heavy I/O, and the heavy effect set should include only `:effects/load-portfolio-optimizer-history`. Update the formal effect-order vectors if the conformance tests require it by running `bb tools/formal.clj sync --surface effect-order-contract`.

Milestone 2 extends the history effect adapter. In `src/hyperopen/runtime/effect_adapters/portfolio_optimizer/history.cljs`, keep the existing full-load behavior for ordinary calls. Add a selection-prefetch branch for opts with `:source :selection-prefetch` and `:queue? true`. That branch should read the first queued instrument from the store, mark it active/loading in `:portfolio :optimizer :history-prefetch`, set the global `:history-load-state` to loading with a request signature for that one-instrument universe, call the existing `:request-history-bundle!` with `:universe [instrument]`, and on success merge returned maps into existing `:history-data` instead of replacing them. After success or failure, remove that instrument from the queue, mark its by-instrument status, clear the active id, and immediately drain the next queued instrument if one exists. The promise returned by the initial selection-prefetch effect should resolve only after the queue is empty or after an unrecoverable effect-level error is recorded.

The merge operation should be explicit. Merge `:candle-history-by-coin`, `:funding-history-by-coin`, and `:vault-details-by-address` as maps, preserving existing entries for unrelated instruments. Set `:loaded-at-ms` to the latest completed time. Keep warning information either in `:history-prefetch :by-instrument-id <id> :warnings` or in a bounded merged warning vector; do not let one failed prefetch erase previously loaded good data. A prefetch result should only apply if the selected instrument is still present in the current draft universe when the promise resolves.

Milestone 3 updates the run pipeline coordination. In `src/hyperopen/runtime/effect_adapters/portfolio_optimizer_pipeline.cljs`, before launching a full history load, detect whether `:portfolio :optimizer :history-load-state :status` is `:loading` due to a selection prefetch. If so, wait for that load to become non-loading, then rebuild readiness. If readiness becomes runnable, start the worker without another history call. If readiness is still blocked because history is missing or incomplete, run the existing full current-universe load. This preserves the fallback while avoiding duplicate API requests for the instrument that is already being prefetched.

The wait should be bounded and testable. Use a small polling helper, for example `wait-for-history-load-idle!`, that checks the store at a short interval and rejects with a clear timeout error if history remains loading beyond a reasonable limit. The pipeline should surface that timeout through the existing optimization progress error path. Keep this helper local to the pipeline adapter unless another owner needs it.

Milestone 4 updates the setup UI. In `src/hyperopen/views/portfolio/optimize/setup_v4_universe.cljs`, extend `selected-history-label` so it can read `:portfolio :optimizer :history-prefetch` and distinguish queued/loading status before falling back to readiness. Keep the existing labels pending, loading, missing, insufficient, and sufficient; add queued only if it is useful and covered by tests. Replace hidden stale copy that says history requires reload after adding assets with copy that matches the new behavior, such as "History starts loading after assets are included." In `src/hyperopen/views/portfolio/optimize/setup_readiness_panel.cljs`, update readiness copy so it no longer says Run optimization is the moment history fetch begins. Keep the language factual and status-oriented rather than promotional.

Milestone 5 updates tests. Start with focused unit tests:

- `test/hyperopen/portfolio/optimizer/history_prefetch_test.cljs` for pure queue planning, history-present checks, duplicate suppression, current-holdings bulk queueing, and removal cleanup.
- `test/hyperopen/portfolio/optimizer/universe_actions_test.cljs` for add and From holdings emitting save-many first and one selection-prefetch effect second.
- `test/hyperopen/portfolio/optimizer/actions_test.cljs` for preserving `load-portfolio-optimizer-history-from-draft` and `run-portfolio-optimizer-from-draft` fallback semantics.
- `test/hyperopen/runtime/effect_adapters/portfolio_optimizer_test.cljs` for selection-prefetch merge behavior, stale completion ignoring removed instruments, failure preserving existing data, and queue drain order.
- `test/hyperopen/runtime/effect_adapters/portfolio_optimizer_pipeline_test.cljs` for Run optimization waiting on an active selection prefetch and then skipping a duplicate full history load when readiness becomes runnable.
- `test/hyperopen/views/portfolio/optimize/setup_v4_layout_test.cljs` and setup readiness view coverage for queued/loading row labels and updated copy.
- `test/hyperopen/runtime/effect_order_contract_test.cljs` and, if required, `test/hyperopen/runtime/effect_order_contract_formal_conformance_test.cljs` for the new covered actions.

Update deterministic Playwright coverage:

- Replace `tools/playwright/test/optimizer-history-network.qa.spec.mjs` with a test that adds `perp:ETH`, observes `candleSnapshot:ETH` and `fundingHistory:ETH` before Run optimization, then clicks Run optimization and asserts no additional ETH history request is made.
- Update the optimizer section of `tools/playwright/test/portfolio-regressions.spec.mjs` so adding BTC then ETH expects sequential prefetch requests before Run optimization. The assertion should prove the app does not wait until Run optimization to fetch history and does not refetch already-prefetched BTC or ETH when Run optimization starts.
- Add one browser assertion for the bulk or fast-add path: selecting two instruments quickly should not launch both instruments' history requests at the same time. Use the request log ordering and the queue status, not timing-sensitive sleeps, as the proof.

Milestone 6 runs validation and records evidence. Because this touches UI interaction flow, effect ordering, runtime effects, and optimizer tests, run the smallest focused commands first, then the repo gates.

## Concrete Steps

1. From `/Users/barry/.codex/worktrees/4611/hyperopen`, write the pure prefetch helper tests and run:

   `npx shadow-cljs --force-spawn compile test && node out/test.js --include hyperopen.portfolio.optimizer.history-prefetch-test`

   If the test runner does not support `--include`, run the full CLJS test command and use the failing namespace names from the output.

2. Implement `src/hyperopen/portfolio/optimizer/application/history_prefetch.cljs` and wire it into `actions/universe.cljs`. Run:

   `npx shadow-cljs --force-spawn compile test && node out/test.js`

   Expected result after implementation is all CLJS tests passing. During RED phase, the new action tests should fail because the add actions do not yet emit prefetch effects.

3. Add effect-order policy coverage and sync formal vectors if conformance fails:

   `bb tools/formal.clj sync --surface effect-order-contract`

   Then rerun the CLJS test command. Expected result is no effect-order formal conformance failure.

4. Implement selection-prefetch queue draining and merge behavior in the history effect adapter. Run:

   `npx shadow-cljs --force-spawn compile test && node out/test.js`

   Expected result is that the new effect-adapter tests pass and existing full history load tests still pass.

5. Update pipeline wait behavior and UI row status handling. Run:

   `npx shadow-cljs --force-spawn compile test && node out/test.js`

   Expected result is that run-pipeline tests prove no duplicate fetch after a completed prefetch and setup layout tests show the correct status labels.

6. Update Playwright tests, then run the smallest relevant browser commands:

   `npx playwright test tools/playwright/test/optimizer-history-network.qa.spec.mjs`

   `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs -g "portfolio optimizer"`

   Expected result is that both commands pass. The network test should show selection-time history requests and no duplicate requests on Run optimization for already-prefetched instruments.

7. Run the required repo gates:

   `npm run check`

   `npm test`

   `npm run test:websocket`

   Expected result is all gates pass. If browser flows changed and the focused Playwright tests passed, decide whether the full `npm run test:playwright:ci` is warranted based on review risk and time; at minimum, record the focused Playwright evidence in this plan.

8. Run governed browser QA or explicitly record why it is blocked. Because this work touches `/hyperopen/src/hyperopen/views/**` and an interaction flow, the UI signoff must account for the six passes from `/hyperopen/docs/FRONTEND.md`: visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf across widths `375`, `768`, `1280`, and `1440`. If Browser MCP or browser-inspection sessions are started, stop them with `npm run browser:cleanup` before concluding.

## Validation and Acceptance

This work is accepted when all of the following are true:

On `/portfolio/optimize/new`, adding a single unused perp instrument starts history requests for that instrument before the Run optimization button is clicked. In the network test, adding ETH should produce one `candleSnapshot` request for ETH and one `fundingHistory` request for ETH before Run optimization.

After the ETH prefetch succeeds, clicking Run optimization should not issue another ETH candle or funding request. The optimizer should proceed to the worker using the prefetched data and show the existing progress/result surfaces.

Adding BTC and ETH in quick succession should not launch all four requests at the same instant. The queue should request one selected instrument's required history, merge it, then move to the next queued instrument. The assertion should be deterministic: use the queue state and request log order instead of wall-clock timing.

Choosing From holdings should queue missing histories rather than launching one request per holding in parallel. If current holdings are empty, no queue entries and no history effect should be emitted.

Removing an instrument before its queued prefetch begins should remove that queue item. Removing an instrument while its prefetch is active should prevent the returned bundle from making that removed instrument appear sufficient in the selected universe UI.

Existing Run optimization fallback behavior should remain intact. If prefetch fails or has not loaded enough data, Run optimization should still attempt the full current-universe history load before running the worker and should surface the existing incomplete-history warnings when data remains unusable.

The selected universe rows should honestly report queued/loading/sufficient/insufficient/missing status. They must not show sufficient before history has been fetched and validated.

The setup route must no longer show copy that says Run optimization is the moment history fetch begins. Observable proof: setup readiness and setup universe tests cover the new copy, and selected universe rows show loading after inclusion without a run click.

The required gates for code changes must pass: `npm run check`, `npm test`, and `npm run test:websocket`. Focused Playwright coverage for the optimizer history flow must pass, and browser QA must be accounted for because the implementation touches UI interaction flow.

## Idempotence and Recovery

The prefetch queue should be safe to process repeatedly. Enqueueing the same instrument more than once must collapse to one queue entry. A successful prefetch merge should be idempotent because it replaces map entries for the same coin or vault address with equivalent latest history rows and preserves unrelated entries.

If a prefetch request fails, keep existing history data intact, mark only that instrument's prefetch status as failed, and allow Run optimization to retry through the full history pipeline. If a full pipeline load starts while the queue is active, the pipeline should wait for active prefetch to settle or clearly take ownership of the load path; do not let two owner paths write contradictory load states for the same request.

If effect-order validation fails after adding heavy I/O to universe selection actions, update `src/hyperopen/runtime/effect_order_contract.cljs` and the formal vectors rather than weakening runtime validation. If Playwright becomes timing-sensitive, rewrite the test to observe deterministic store state or request ordering.

## Artifacts and Notes

Current old-browser-test assertion to replace:

    const afterAddCount = seen.length;
    await expect(page.locator("[data-role='portfolio-optimizer-load-history']")).toHaveCount(0);
    await page.locator("[data-role='portfolio-optimizer-run-draft']").click();
    expect(afterAddCount).toBe(0);
    expect(seen).toEqual([
      { type: "candleSnapshot", coin: "ETH" },
      { type: "fundingHistory", coin: "ETH" }
    ]);

New expected shape:

    await page.locator("[data-role='portfolio-optimizer-universe-add-perp:ETH']").click();
    await expect.poll(() => seen.map((entry) => `${entry.type}:${entry.coin}`))
      .toEqual(["candleSnapshot:ETH", "fundingHistory:ETH"]);
    seen.length = 0;
    await page.locator("[data-role='portfolio-optimizer-run-draft']").click();
    expect(seen.filter((entry) => entry.coin === "ETH")).toEqual([]);

The exact Playwright assertion can vary, but it must prove selection-time fetch and no duplicate run-time fetch for prefetched instruments.

## Interfaces and Dependencies

New internal state under `:portfolio :optimizer` should use this shape unless implementation discovers a simpler equivalent:

    :history-prefetch
    {:queue [{:instrument-id "perp:BTC"
              :instrument {:instrument-id "perp:BTC"
                           :market-type :perp
                           :coin "BTC"}}]
     :active-instrument-id nil
     :by-instrument-id {"perp:BTC" {:status :queued
                                    :started-at-ms nil
                                    :completed-at-ms nil
                                    :error nil
                                    :warnings []}}}

The selection-prefetch effect options should use this shape:

    {:source :selection-prefetch
     :queue? true
     :merge? true}

Ordinary full history loads should continue to work with no options or existing options such as `{:bars 180}`. The existing effect argument schema already accepts an optional map for `:effects/load-portfolio-optimizer-history`, so this feature should not need a new effect id unless implementation discovers that separating full loads and queued prefetches materially reduces risk.

The prefetch helper namespace should remain pure and should not depend on runtime effect adapters. Runtime adapters may depend on the helper namespace to choose queue items and merge state, but domain/application code must not call browser APIs or mutate the store directly.

Revision note, 2026-05-03 / Codex: Initial active ExecPlan created from the user's request to load optimizer history when instruments are selected rather than waiting for Run optimization. The plan records current code paths, design decisions, tests, and validation gates before implementation starts.

Revision note, 2026-05-03 / Codex: Incorporated independent `spec_writer` review input. Added `setup_readiness_panel.cljs` to the touched UI surfaces, explicitly called out the older no-fetch-until-run Playwright contract, and recorded the decision to prefer queued incremental prefetch over a simpler full-universe reload because the user's API-burst concern is central to the feature.

Revision note, 2026-05-03 / Codex: Recorded planning self-review and validation results. Doc-specific validation passed; full `npm run check` is blocked in this worktree by missing local installs for declared Node dependencies `zod` and `smol-toml`.
