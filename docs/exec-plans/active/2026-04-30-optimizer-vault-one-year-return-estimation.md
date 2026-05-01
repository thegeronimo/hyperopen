---
owner: portfolio
status: active
created: 2026-04-30
source_of_truth: false
tracked_issue: hyperopen-0amu
---

# Optimizer Vault One-Year Return Estimation

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current while work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, and `/hyperopen/src/hyperopen/portfolio/optimizer/BOUNDARY.md`. The live `bd` issue for this work is `hyperopen-0amu`, and `bd` remains the lifecycle source of truth until this plan moves out of `active/`.

## Purpose / Big Picture

The Portfolio Optimizer already accepts Hyperliquid vaults as return legs, but its vault history assembly still picks the first direct bounded summary that exists. When a vault detail payload omits a direct `:one-year` summary, the optimizer currently falls through to direct `:six-month`, `:three-month`, or `:month` data even if the same payload contains enough `:all-time` account-value and PnL history to reconstruct an honest one-year return window. That divergence is visible in the reported HLP regression: the vault detail page showed about `20.95%` for `1Y`, while the standalone optimizer expected return for the same vault skewed toward about `-6%` because the optimizer used the direct month tail instead of the richer one-year-derived history.

After this change, optimizer vault return estimation should follow the same bounded-window semantics already used by the vault detail page for returns. When a vault lacks a direct `:one-year` summary but its `:all-time` portfolio history can reconstruct that window honestly, the optimizer should derive the one-year return path from `:all-time` data instead of falling back to the shorter direct summary. The optimizer must also annualize sparse vault histories by elapsed time, not by treating each vault observation as a daily bar. The observable proof is a focused regression fixture where the direct month slice is negative but the all-time-derived one-year window is positive: the optimizer history request should carry the one-year-derived vault return series, attach the return intervals for that sparse common calendar, and downstream expected-return calculation should annualize those intervals to a one-year result rather than the month-only tail or an inflated daily estimate.

## Progress

- [x] (2026-04-30 18:25Z) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/MULTI_AGENT.md`, and the repo-local `spec-writer` skill before drafting this plan.
- [x] (2026-04-30 18:25Z) Audited the current optimizer vault history path in `src/hyperopen/portfolio/optimizer/application/history_loader.cljs`, related optimizer tests, and the existing vault-detail semantics in `src/hyperopen/vaults/detail/performance.cljs` plus `src/hyperopen/views/portfolio/vm/summary.cljs`.
- [x] (2026-04-30 18:25Z) Confirmed from `bd show hyperopen-0amu --json` that the intended scope is “Use derived one-year vault returns in optimizer” and that the desired behavior is to align optimizer selection semantics with the vault detail page.
- [x] (2026-04-30 18:25Z) Created this active ExecPlan for `hyperopen-0amu`.
- [x] (2026-04-30 18:31Z) Added RED regressions in `history_loader_test.cljs` and `request_builder_test.cljs`. The focused command failed with 6 expected assertions showing the optimizer emits the negative direct month tail instead of the all-time-derived one-year series.
- [x] (2026-04-30 18:43Z) Implemented the bounded one-year derivation inside `src/hyperopen/portfolio/metrics/history.cljs` and switched optimizer vault selection in `src/hyperopen/portfolio/optimizer/application/history_loader.cljs` to prefer a derived one-year summary only when no direct `:one-year` exists, the all-time window has a valid cutoff anchor, and the derived window is not sparser than the shorter direct fallback.
- [x] (2026-04-30 18:43Z) Re-ran the focused optimizer command. `history-loader-test` and `request-builder-test` passed with 0 failures and proved the optimizer now carries the derived one-year vault return series.
- [x] (2026-04-30 18:43Z) Ran `npm test` and `npm run test:websocket`; both passed. Ran `npm run check`; the new `history_loader.cljs` size regression was eliminated by moving selection helpers into `metrics/history.cljs`, but the command still fails on pre-existing namespace-size issues in `src/hyperopen/views/portfolio/optimize/frontier_chart.cljs` and `test/hyperopen/views/portfolio/optimize/results_panel_test.cljs`, which are outside this ticket’s write scope.
- [x] (2026-04-30 18:43Z) Browser QA explicitly skipped because the landed change is internal-only and does not alter a visible UI surface or browser-test tooling path.
- [x] (2026-04-30 18:48Z) Reproduced the strengthened follow-up RED command and confirmed `preferred-vault-summary` still rejected a complete derived `:one-year` window when the shorter direct fallback summary had more observations.
- [x] (2026-04-30 18:48Z) Narrowed `src/hyperopen/portfolio/metrics/history.cljs` so a complete derived `:one-year` summary with at least the minimum useful observations now beats shorter direct fallback summaries regardless of fallback row count, while direct `:one-year` still wins and sparse or incomplete derivations still fall back.
- [x] (2026-04-30 18:48Z) Re-ran `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.application.history-loader-test --test=hyperopen.portfolio.optimizer.application.request-builder-test`; it passed with 0 failures and 0 errors.
- [x] (2026-04-30 18:49Z) Re-ran `npm run check` after refreshing the active ExecPlan; docs lint passed and the command now fails only on the known pre-existing namespace-size issues in `src/hyperopen/views/portfolio/optimize/frontier_chart.cljs` and `test/hyperopen/views/portfolio/optimize/results_panel_test.cljs`, both outside this ticket’s write scope.
- [x] (2026-04-30 18:49Z) Re-ran `npm test` and `npm run test:websocket`; both passed after the follow-up patch.
- [x] (2026-04-30 18:53Z) Parent thread independently re-ran the focused optimizer command; it passed with 16 tests, 92 assertions, 0 failures, and 0 errors.
- [x] (2026-04-30 18:53Z) Parent thread independently re-ran `npm test`, `npm run test:websocket`, `npm run lint:namespace-boundaries`, and `npm run lint:namespace-sizes`. `npm test`, websocket tests, and namespace boundaries passed. `lint:namespace-sizes` still fails only on unrelated namespace-size guardrails outside this ticket.
- [x] (2026-04-30 18:53Z) Created follow-up `bd` issue `hyperopen-qgmq` for the unrelated namespace-size gate failures that keep `npm run check` from completing.
- [x] (2026-04-30 19:00Z) Added a second RED layer for sparse-calendar annualization. The focused command now fails because `align-history-inputs` does not yet emit `:return-intervals`, and `estimate-expected-returns` still annualizes two half-year `+10%` vault observations to `36.5` instead of about `0.21`.
- [x] (2026-04-30 19:13Z) Implemented `:return-intervals` on aligned optimizer history and elapsed-time-aware geometric annualization for sparse valid intervals. Added a guard regression proving one-day candle histories keep the established arithmetic daily estimator.
- [x] (2026-04-30 19:13Z) Re-ran focused optimizer coverage with `history-loader-test`, `request-builder-test`, `domain.returns-test`, and `engine-test`; it passed with 36 tests, 186 assertions, 0 failures, and 0 errors.
- [x] (2026-04-30 19:13Z) Re-ran the required gates after the final sparse-interval patch. `npm test`, `npm run test:websocket`, and `npm run lint:namespace-boundaries` passed. `npm run check` and direct `npm run lint:namespace-sizes` still fail only on the known unrelated namespace-size blocker tracked by `hyperopen-qgmq`.
- [ ] Close `hyperopen-0amu` only after the unrelated namespace-size gate blocker is resolved or explicitly accepted as a blocker for this ticket.
- [ ] Move this ExecPlan out of `active/` after ticket close-out so docs lint no longer treats it as stale completed work.

## Surprises & Discoveries

- Observation: the optimizer bug is not in solver math. It is upstream in vault history selection.
  Evidence: `src/hyperopen/portfolio/optimizer/application/history_loader.cljs` defines `vault-summary-preference` as `[:one-year :six-month :three-month :month :week :day :all-time]`, and `selected-vault-summary` simply picks the first present summary before `normalize-vault-history` turns it into optimizer price-like rows.

- Observation: the desired semantics already exist elsewhere in the repository, but not in a place the optimizer application can import directly.
  Evidence: `src/hyperopen/vaults/detail/performance.cljs` delegates vault return-window assembly to `hyperopen.views.portfolio.vm.summary/returns-history-context`, and the helper tests in `test/hyperopen/views/portfolio/vm/summary_helpers_test.cljs` and `test/hyperopen/vaults/detail/performance_test.cljs` already prove the “prefer richer all-time-derived one-year window” behavior.

- Observation: the optimizer boundary explicitly forbids `hyperopen.views.*` dependencies inside the optimizer domain and application namespaces.
  Evidence: `src/hyperopen/portfolio/optimizer/BOUNDARY.md` states that optimizer domain and application namespaces under that directory “must not depend on `hyperopen.views.*`.”

- Observation: the optimizer must still avoid fabricating a one-year vault window when all-time history cannot honestly reconstruct one.
  Evidence: `test/hyperopen/portfolio/optimizer/application/history_loader_test.cljs` now contains `align-history-inputs-falls-back-to-direct-month-vault-summary-when-one-year-cannot-be-derived-test`.

- Observation: the new RED tests reproduce the exact failure mode in both the lower-level history loader and the request-builder integration boundary.
  Evidence: `npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.application.history-loader-test --test=hyperopen.portfolio.optimizer.application.request-builder-test` failed with 6 assertions; actual calendars were the direct month dates `[2026-02-28, 2026-03-31, 2026-04-30]` and actual vault returns were about `-5.0%` and `-5.26%` instead of the expected one-year-derived `+10%` and `+10%`.

- Observation: keeping the fix inside the optimizer namespace would have tripped the repository namespace-size lint even after the behavior was correct.
  Evidence: an intermediate `npm run check` run failed with `[size-exception-exceeded] src/hyperopen/portfolio/optimizer/application/history_loader.cljs - namespace has 591 lines; exception allows at most 545`. Moving the pure summary-selection helpers into `src/hyperopen/portfolio/metrics/history.cljs` reduced `history_loader.cljs` back to 531 lines and cleared that new regression.

- Observation: the stronger follow-up regression exposed that the first fix still encoded the wrong tie-breaker, not the wrong derivation.
  Evidence: before the follow-up patch, `preferred-vault-summary` required the derived one-year summary to have at least as many observations as the shorter direct fallback. The strengthened focused command failed with the direct month calendar `[1772236800000 1773446400000 1774656000000 1776124800000 1777507200000]` and about `-2%` vault returns instead of the expected derived one-year rows.

- Observation: the remaining `npm run check` blocker is unrelated namespace-size governance, not source correctness for this fix.
  Evidence: parent-thread verification reached `npm run lint:namespace-sizes` and reported `[missing-size-exception] src/hyperopen/views/portfolio/optimize/frontier_chart.cljs - namespace has 502 lines` and `[size-exception-exceeded] test/hyperopen/views/portfolio/optimize/results_panel_test.cljs - namespace has 544 lines; exception allows at most 540`. Follow-up `bd` issue `hyperopen-qgmq` tracks that blocker.

- Observation: choosing the correct one-year vault window is necessary but insufficient when that window is sparse.
  Evidence: with a derived one-year vault calendar of `[2025-04-30, 2025-10-30, 2026-04-30]`, the optimizer return series contains two half-year returns. `src/hyperopen/portfolio/optimizer/domain/returns.cljs` still multiplies their mean by `365`, which treats each half-year observation like one daily return and can inflate expected returns by roughly two orders of magnitude.

- Observation: attaching interval metadata to every aligned history would have silently changed normal one-day candle histories unless the return estimator checked for sparse intervals.
  Evidence: the guard regression `historical-mean-preserves-daily-arithmetic-estimator-test` initially failed with an expected value of `0.2` and an actual value of about `1358.9555`. The final implementation only activates elapsed-time annualization when the interval metadata is valid and at least one interval is sparse.

## Decision Log

- Decision: keep the implementation scope on optimizer history assembly and pure shared history helpers, not on solver logic, optimizer UI copy, or vault detail rendering.
  Rationale: the user-reported regression is caused by the vault input series entering the optimizer with the wrong time window. Fixing that seam is the smallest safe change that addresses the expected-return discrepancy.
  Date/Author: 2026-04-30 / Codex

- Decision: do not direct optimizer application code to depend on `hyperopen.views.portfolio.vm.summary`.
  Rationale: `src/hyperopen/portfolio/optimizer/BOUNDARY.md` forbids `hyperopen.views.*` dependencies inside optimizer application code. Matching vault detail semantics must therefore happen through a neutral shared helper or equivalent pure logic that remains inside allowed dependency boundaries.
  Date/Author: 2026-04-30 / Codex

- Decision: require RED tests for both the positive case and the “do not fabricate earlier history” boundary case.
  Rationale: the bug report is about selecting the wrong shorter summary when an honest one-year window can be derived. A safe fix must also preserve truthful behavior when no point exists at or before the one-year cutoff.
  Date/Author: 2026-04-30 / Codex

- Decision: browser QA is out of scope by default for this ticket.
  Rationale: the planned change is internal to history assembly and request construction. Browser validation becomes necessary only if the implementation introduces visible optimizer UI changes such as new warnings, labels, or readiness states.
  Date/Author: 2026-04-30 / Codex

- Decision: drop the direct-fallback observation-count comparison from `preferred-vault-summary` and keep only the minimum useful observation threshold on the derived one-year path.
  Rationale: the contract for `hyperopen-0amu` is “prefer a complete derived one-year summary over shorter direct fallback summaries.” Comparing row counts against a shorter fallback silently reintroduced the HLP failure mode even when the derived one-year window was complete and useful.
  Date/Author: 2026-04-30 / Codex

- Decision: carry common-calendar return intervals through the optimizer history contract and use them only when they are complete and aligned with each instrument return series.
  Rationale: this preserves the existing daily candle behavior for normal histories while giving sparse vault calendars enough elapsed-time context to annualize one-year return estimates correctly.
  Date/Author: 2026-04-30 / Codex

## Outcomes & Retrospective

Implemented the selection layer as planned with a small boundary-safe extraction, then tightened by follow-up regressions. The optimizer now derives a bounded one-year vault summary from all-time account/PnL history when there is no direct `:one-year`, the cutoff has a real anchor, and the derived window meets the minimum useful observation threshold. Direct `:one-year` still wins, but shorter direct fallback summaries no longer override a complete derived one-year window merely because they have more rows. The optimizer history contract now also carries common-calendar `:return-intervals`, and expected-return estimation uses elapsed-time geometric annualization for sparse valid intervals so two half-year `+10%` vault observations estimate about `+21%` annualized rather than `+3650%`.

The focused regressions pass after the final sparse-interval patch, including the daily-estimator guard. Parent-thread verification also passed `npm test`, `npm run test:websocket`, and `npm run lint:namespace-boundaries`. `npm run check` is blocked by unrelated namespace-size failures in `src/hyperopen/views/portfolio/optimize/frontier_chart.cljs` and `test/hyperopen/views/portfolio/optimize/results_panel_test.cljs`; follow-up `hyperopen-qgmq` tracks that existing gate issue. The implementation itself no longer adds a validation blocker of its own.

## Context and Orientation

The optimizer request path relevant to this bug starts in `src/hyperopen/portfolio/optimizer/application/history_loader.cljs`. That namespace has two jobs here. First, `build-history-request-plan` decides which remote histories to fetch for each optimizer instrument. Second, `align-history-inputs` converts the fetched raw histories into a common calendar and a `:return-series-by-instrument` map that later feeds expected-return estimation.

For vault instruments, `align-history-inputs` calls `normalize-vault-history`, which currently calls `selected-vault-summary`. A “direct summary” here means one of the bounded summaries already present in the vault detail payload, such as `:one-year`, `:six-month`, `:three-month`, or `:month`. An “all-time summary” means the `:all-time` portfolio entry that contains long-running `:accountValueHistory` and `:pnlHistory`. The current optimizer logic chooses the first direct summary that exists, then computes cumulative return rows from that summary. That is why a missing direct `:one-year` summary can accidentally downgrade the optimizer to month-only history even when the all-time portfolio path contains enough information to build an honest one-year window.

The vault detail page uses different semantics. Its return path goes through `src/hyperopen/vaults/detail/performance.cljs` and ultimately `src/hyperopen/views/portfolio/vm/summary.cljs`. That logic aligns account-value and PnL points, computes a one-year cutoff from the end of the all-time series, inserts a cutoff anchor from the latest valid point at or before the cutoff when one exists, rebases PnL from that anchor, and marks the window incomplete when no such earlier point exists. The tests `returns-history-context-prefers-richer-all-time-window-and-synthesizes-cutoff-anchor-test` and `returns-history-context-prefers-all-time-when-it-can-reconstruct-a-bounded-window-test` already prove this behavior elsewhere in the repo.

The optimizer cannot simply import those view-layer helpers because `src/hyperopen/portfolio/optimizer/BOUNDARY.md` forbids `hyperopen.views.*` dependencies in optimizer application code. That constraint matters: the implementation plan must preserve the vault-detail semantics while keeping optimizer application code pure and worker-safe.

## Scope and Non-Goals

This ticket fixes vault return-series selection for optimizer history assembly. It does not change solver selection, objective math, funding carry math, covariance estimation, scenario persistence, or the vault detail page itself.

The only acceptable scope expansion is extracting a neutral pure helper into an allowed non-view namespace if that is the cleanest way to share the bounded-window derivation semantics. The implementation must not solve this by adding an optimizer dependency on `hyperopen.views.*`.

Visible UI work is not part of the plan. If a later implementation step discovers that user-facing warning text or readiness copy must change to surface incomplete one-year vault coverage honestly, that UI change must be treated as a scope expansion and must add the smallest relevant Playwright or governed browser-QA step before claiming completion.

## Likely Touched Files

The primary implementation file is `src/hyperopen/portfolio/optimizer/application/history_loader.cljs`, because that is where vault summaries are selected and converted into optimizer history rows today.

The primary RED/green regression file is `test/hyperopen/portfolio/optimizer/application/history_loader_test.cljs`.

The most likely integration-level test surface is `test/hyperopen/portfolio/optimizer/application/request_builder_test.cljs`, because `request-builder` is the first stable boundary that proves the optimizer request receives the corrected vault return series.

If the cleanest implementation extracts pure bounded-window logic into a neutral seam, likely shared-helper files are `src/hyperopen/portfolio/metrics/history.cljs` and either `test/hyperopen/portfolio/metrics/history_test.cljs` or `test/hyperopen/portfolio/metrics/history_normalization_test.cljs`. Use one of those only if the extraction materially reduces duplication without broadening the change.

No UI or route files are expected to change for the planned fix.

## TDD Plan

Start with a focused RED regression in `test/hyperopen/portfolio/optimizer/application/history_loader_test.cljs` that models the reported failure mode. Build a vault fixture with no direct `:one-year` summary, a direct `:month` summary that produces a negative short-tail series, and an `:all-time` portfolio history that can reconstruct a positive one-year window. Assert that `align-history-inputs` produces a vault return series and calendar derived from the all-time one-year window instead of the direct month tail. Anchor the expected behavior to concrete rows, not only to a label like “uses all-time.”

Add a second RED regression in the same namespace for the truthful boundary case. Build a vault fixture where the all-time history starts after the one-year cutoff, so no earlier anchor point exists. Assert that the optimizer does not fabricate an earlier one-year point. It may continue to use the shorter available tail, but the test must prove no synthetic earlier return row is invented.

If implementation extracts a neutral helper, add helper-level RED coverage in `test/hyperopen/portfolio/metrics/history_test.cljs` or `test/hyperopen/portfolio/metrics/history_normalization_test.cljs` for the cutoff-anchor behavior itself. That helper test should prove the same two core behaviors: derive a bounded window from all-time points when an anchor exists, and refuse to invent earlier history when it does not.

After the history-loader RED tests are in place, add one integration-level regression in `test/hyperopen/portfolio/optimizer/application/request_builder_test.cljs`. Use the same synthetic vault fixture and assert that `build-engine-request` carries the corrected vault return series into `[:history :return-series-by-instrument <vault-id>]`. This ensures the corrected history path reaches the optimizer request boundary that downstream expected-return estimation consumes.

## Plan of Work

First, replace the current `selected-vault-summary` preference behavior in `src/hyperopen/portfolio/optimizer/application/history_loader.cljs` with a bounded-window selection strategy that matches vault-detail return semantics. The implementation must consider both the direct selected summary and the `:all-time` summary, compute the requested one-year cutoff from the end of the available all-time data, and prefer the all-time-derived window when that path yields an honest one-year series with a valid anchor at or before the cutoff.

Second, preserve the existing optimizer behavior that falls back to shorter direct summaries when the all-time path cannot honestly reconstruct the requested window. The regression `align-history-inputs-falls-back-to-direct-month-vault-summary-when-one-year-cannot-be-derived-test` is the guardrail for this. The new fix is not “always use all-time.” It is “use all-time only when it can honestly reconstruct the requested one-year window better than the direct fallback.”

Third, keep the implementation inside allowed dependency seams. If the bounded-window logic can be shared cleanly, extract a pure helper into a neutral namespace such as `src/hyperopen/portfolio/metrics/history.cljs`. If extraction would ripple too broadly, implement an optimizer-local pure helper in `history_loader.cljs` that mirrors the vault-detail semantics closely enough for the tested behavior. Do not import `hyperopen.views.portfolio.vm.summary` into the optimizer application namespace.

Fourth, update `normalize-vault-history` so it converts the corrected selected summary into price-like rows on the existing optimizer calendar path. The surrounding alignment, daily normalization, funding handling, and return-series construction should remain unchanged unless a new RED test proves otherwise.

Fifth, add common-calendar return interval metadata to the history contract and teach expected-return estimation to use that metadata for sparse calendars. Valid interval metadata must match the return series length and have positive elapsed-year values. When it is absent, incomplete, or mismatched, the old daily-series arithmetic annualization remains the fallback.

Sixth, add an integration proof at the request-builder boundary so future regressions cannot silently reintroduce month-tail expected returns for vaults that actually have enough all-time history to support a one-year estimate.

## Concrete Steps

Run commands from `/Users/barry/.codex/worktrees/e4b0/hyperopen`.

Add the RED history-loader regression first and run:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.application.history-loader-test

Expected RED result before implementation: the new vault regression fails because the current optimizer vault path selects the direct month summary and therefore emits the wrong vault calendar or return series.

If a neutral shared helper is introduced, run the smallest additional helper namespace command:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.metrics.history-test

Then add the request-builder integration regression and run:

    npm run test:runner:generate && npx shadow-cljs --force-spawn compile test && node out/test.js --test=hyperopen.portfolio.optimizer.application.history-loader-test --test=hyperopen.portfolio.optimizer.application.request-builder-test

After the focused tests pass, run the required repository gates for code changes:

    npm run check
    npm test
    npm run test:websocket

Do not add browser commands unless the implementation changes a visible optimizer UI surface. If that happens, run the smallest relevant Playwright command first, then broaden only if the changed UI surface requires it.

## Validation and Acceptance

Acceptance is satisfied only when all of the following observable behaviors are true.

- Running `node out/test.js --test=hyperopen.portfolio.optimizer.application.history-loader-test` includes a regression where a vault payload has no direct `:one-year` summary, has a negative direct `:month` tail, and has an `:all-time` portfolio history that can honestly reconstruct one year. That regression must pass by proving `align-history-inputs` emits the all-time-derived one-year vault series instead of the direct month-tail series.

- Running the same focused history-loader test command includes a boundary regression where no aligned point exists at or before the one-year cutoff. That regression must pass by proving the optimizer does not invent a synthetic earlier point.

- Running `node out/test.js --test=hyperopen.portfolio.optimizer.application.request-builder-test` includes a regression that proves `build-engine-request` carries the corrected vault return series into `[:history :return-series-by-instrument <vault-id>]`, which is the concrete input used later for expected-return estimation.

- Running `node out/test.js --test=hyperopen.portfolio.optimizer.domain.returns-test` includes a regression where sparse half-year vault observations annualize by elapsed time to about `21%`, not by a daily multiplier to `3650%`.

- The fallback regression `align-history-inputs-falls-back-to-direct-month-vault-summary-when-one-year-cannot-be-derived-test` still passes, proving the fix did not blindly switch all vaults to `:all-time`.

- `npm test` and `npm run test:websocket` pass from the repo root after the implementation lands. `npm run check` must either pass or be explicitly reported as blocked by unrelated namespace-size failures.

- Browser QA is explicitly recorded as skipped because this ticket is internal-only. If visible UI changes are introduced during implementation, acceptance must be amended to include the smallest relevant browser validation command before completion.

## Idempotence and Recovery

This work is safe to stage incrementally because the first milestone is failing focused tests. If the first implementation attempt causes vault history alignment to lose observations or breaks the existing dense-direct-summary regression, keep the new RED tests and revert only the bounded-window selection logic until the focused tests isolate the issue.

Do not “fix” this bug by hard-coding `:all-time` selection for every vault. That would regress the existing density preference and could reduce observation count for optimizer calendars that currently rely on a denser bounded summary.

Do not introduce a `hyperopen.views.*` dependency into `src/hyperopen/portfolio/optimizer/application/history_loader.cljs`. If sharing logic becomes unavoidable, extract it downward into an allowed pure namespace instead of reaching upward into the view layer.

## Artifacts and Notes

The synthetic regression fixture should intentionally separate the month-tail story from the one-year story. A representative shape is:

    Requested vault window: :one-year
    Direct summaries present: :month only
    Direct month return shape: negative over the last two points
    All-time account/PnL history: spans at least five dated points across the one-year cutoff
    Expected post-fix optimizer behavior: vault return series spans the all-time-derived one-year window and not only the direct month tail

Important reference tests already proving the target semantics outside the optimizer boundary are:

    test/hyperopen/vaults/detail/performance_test.cljs
    test/hyperopen/views/portfolio/vm/summary_helpers_test.cljs

Those tests are guidance for semantics, not a license to import `hyperopen.views.*` into optimizer application code.

Plan revision note (2026-04-30 18:25Z): Initial ExecPlan created after auditing the optimizer vault history path, the vault-detail return-window semantics, and the optimizer boundary rule that forbids `hyperopen.views.*` dependencies.

## Interfaces and Dependencies

At completion, `hyperopen.portfolio.optimizer.application.history-loader/align-history-inputs` must still return the current top-level contract keys, including `:calendar`, `:return-calendar`, `:eligible-instruments`, `:excluded-instruments`, `:return-series-by-instrument`, `:funding-by-instrument`, `:warnings`, and `:freshness`.

If a new helper is extracted, keep it pure and worker-safe. A likely contract is a function that accepts a vault `:portfolio` summary map plus a requested time range and returns a chosen summary or bounded-window context that exposes at least the derived summary rows and whether the one-year window was complete. The helper must not depend on browser state, route state, or any `hyperopen.views.*` namespace.

`hyperopen.portfolio.optimizer.application.request-builder/build-engine-request` should remain the first stable integration boundary that proves the optimizer request receives the corrected vault return series without any UI involvement.
