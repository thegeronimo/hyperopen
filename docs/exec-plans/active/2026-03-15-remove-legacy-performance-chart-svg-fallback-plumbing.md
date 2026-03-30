# Remove Legacy Performance Chart SVG Fallback Plumbing

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`. The live `bd` issue for this work is `hyperopen-1lrp`. This plan builds on `/hyperopen/docs/exec-plans/completed/2026-03-15-d3-performance-chart-migration.md`, which moved Portfolio and Vault detail performance charts onto the shared D3 runtime by default but intentionally kept the inline SVG rollback path. This ticket removes that rollback path now that the D3 runtime is the intended production contract.

## Purpose / Big Picture

After this change, the performance charts on `/portfolio`, `/portfolio/trader/<address>`, and `/vaults/<vaultAddress>` always render through the shared D3 runtime. Users should still see the same chart shell, benchmark selectors, fills, legend rows, tooltip content, and pointer-driven crosshair behavior they see today, but the codebase should no longer keep a hidden second renderer or store chart hover position in application state just to support the removed SVG path.

Someone verifying the result should be able to load those routes, switch between Returns, Account Value, and PNL where applicable, move the pointer across the chart, and observe that the D3 host is the only renderer in use. The cleanup is complete only when the fallback-only config, hover actions, runtime registrations, schema contracts, and SVG-only tests are gone, while the existing D3 behavior remains intact.

## Progress

- [x] (2026-03-30 16:42 EDT) Claimed `hyperopen-1lrp` and confirmed there was no existing active ExecPlan for this issue.
- [x] (2026-03-30 16:44 EDT) Re-read `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md` to align this specification with the repo workflow.
- [x] (2026-03-30 16:45 EDT) Audited the current fallback surfaces across config, chart views, view-models, actions, state defaults, runtime collaborators, schema registrations, and contract tests.
- [x] (2026-03-30 16:46 EDT) Collected acceptance criteria and edge-case coverage with parallel subagent support focused on production surfaces and regression risks.
- [x] (2026-03-30 16:47 EDT) Authored this active ExecPlan and wrote the manager-compatible spec artifact at `/hyperopen/tmp/multi-agent/hyperopen-1lrp/spec.json`.
- [x] (2026-03-30 17:54 EDT) Worker milestone 1: collapsed the Portfolio and Vault detail chart rendering contract to D3-only and removed the inline SVG branches.
- [x] (2026-03-30 17:56 EDT) Worker milestone 2: simplified the Portfolio and Vault chart view-model contracts so they no longer compute fallback-only hover or SVG path data.
- [x] (2026-03-30 17:58 EDT) Worker milestone 3: deleted the dead hover action, state, public-action, collaborator, runtime-registration, and schema-contract surfaces left behind by the fallback path.
- [x] (2026-03-30 18:16 EDT) Worker milestone 4: refreshed deterministic tests, tightened focused Playwright coverage for Portfolio and Vault detail, and reran the available quality gates.
- [x] (2026-03-30 21:41 EDT) Closed out the validation bookkeeping for the implementation itself: `npm run check` and `npm run test:websocket` passed, the fallback-contract grep is clean, the current Playwright failures were classified as unrelated baselines, and the governed browser-QA artifact state was recorded.
- [x] (2026-03-30 22:12 EDT) Investigated the earlier `npm test` failure and confirmed it came from corrupted `shadow-cljs` `test` build output, not from `hyperopen-1lrp`; after clearing `.shadow-cljs/builds/test/dev/out`, recompiling `test`, and rerunning the suite, `npm test` passed cleanly.
- [ ] (2026-03-30 22:12 EDT) Decide issue closeout after the remaining unrelated browser-validation blockers are adjudicated: the committed Playwright suites still carry unrelated red cases, and managed design-review hangs after `portfolio-route` despite a clean port state.

## Surprises & Discoveries

- Observation: the fallback path is not limited to two view namespaces; it still exists as a public contract in config, action ids, runtime registration tables, collaborator maps, and action-argument schemas.
  Evidence: `/hyperopen/src/hyperopen/views/chart/renderer.cljs`, `/hyperopen/src/hyperopen/core/public_actions.cljs`, `/hyperopen/src/hyperopen/runtime/collaborators/chart.cljs`, `/hyperopen/src/hyperopen/runtime/collaborators/vaults.cljs`, `/hyperopen/src/hyperopen/schema/runtime_registration/portfolio.cljs`, `/hyperopen/src/hyperopen/schema/runtime_registration/vaults.cljs`, and `/hyperopen/src/hyperopen/schema/contracts/action_args.cljs` all still mention the fallback-only hover actions or renderer selector.

- Observation: both chart view-model pipelines still compute fallback-only data even though the D3 runtime already owns pointer tracking and tooltip rendering locally.
  Evidence: `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` reads `[:portfolio-ui :chart-hover-index]` and passes `:include-svg-paths?` into `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs`, while `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` passes `:hover-index` and `:include-svg-paths?` into `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs`.

- Observation: a large share of existing test coverage is already D3-specific, and the remaining fallback coverage is concentrated in a small set of tests that explicitly stub the renderer switch or assert fallback tooltip layout.
  Evidence: D3 host and DOM behavior are already covered in `/hyperopen/test/hyperopen/views/portfolio/chart_view_test.cljs`, `/hyperopen/test/hyperopen/views/vaults/detail/chart_view_test.cljs`, and `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`, while fallback-specific assertions still rely on `with-redefs` of `chart-renderer/d3-performance-chart?` or on `:include-svg-paths? false` branches in `/hyperopen/test/hyperopen/views/portfolio/vm/chart_helpers_test.cljs` and `/hyperopen/test/hyperopen/views/vaults/detail/chart_test.cljs`.

- Observation: governed browser QA for `vault-detail-route` has pre-existing interaction blind spots and should not silently redefine the scope of this cleanup ticket.
  Evidence: `/hyperopen/docs/exec-plans/completed/2026-03-20-decrap-vault-and-funding-hotspots.md` and live `bd` issues `hyperopen-ksuk` and `hyperopen-nct1` already document `vault-detail-route` interaction and jank follow-up work that predates this cleanup.

- Observation: the current post-change browser failures still do not exercise the renderer-selector or hover-plumbing code deleted by this ticket.
  Evidence: the latest rerun of `/hyperopen/tools/playwright/test/portfolio-regressions.spec.mjs` passed the two portfolio-route assertions relevant to this cleanup (`portfolio route exposes deterministic interaction states` and `trader portfolio route stays read-only while reusing stable controls`) before the managed Playwright web server died and the later unrelated route tests failed with `ERR_CONNECTION_REFUSED`. The remaining Vault Playwright failure in `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs` is still `vault startup preview row click reuses inflight list bootstrap`, which asserts list-bootstrap request deduping. The `hyperopen-1lrp` diff only removed chart fallback branches and hover-reset saves from `/hyperopen/src/hyperopen/views/portfolio/**`, `/hyperopen/src/hyperopen/views/vaults/detail/**`, `/hyperopen/src/hyperopen/portfolio/actions.cljs`, and `/hyperopen/src/hyperopen/vaults/application/{detail_commands,list_commands,route_loading}.cljs`; it did not change request dedupe, startup-preview orchestration, or the failing later-suite route navigation surfaces.

- Observation: the managed design-review harness now gets past the local-app startup conflict after stale `shadow-cljs` and preview browser processes are killed, but it still stalls after partially capturing `portfolio-route`.
  Evidence: `/hyperopen/tmp/browser-inspection/design-review-2026-03-30T21-32-59-719Z-718609b4/manifest.json` captured the earlier `shadow-cljs already running` app-manager failure. After killing the stale `shadow-cljs` JVM on port `9630`, `/hyperopen/tmp/browser-inspection/design-review-2026-03-30T21-35-17-240Z-b8be9d90/manifest.json` still remained `in_progress` with only a single `portfolio-route` `review-375` snapshot written and no active listeners left on `9630` or `4173`.

- Observation: the `npm test` orderbook failure was a corrupted incremental `shadow-cljs` `test` artifact, not a source regression and not assertion drift.
  Evidence: the broken generated file `/hyperopen/.shadow-cljs/builds/test/dev/out/cljs-runtime/hyperopen.websocket.orderbook_policy_test.js` referenced nonexistent assertion temp vars at the previously failing sites, while the corresponding `/hyperopen/.shadow-cljs/builds/ws-test/dev/out/cljs-runtime/hyperopen.websocket.orderbook_policy_test.js` artifact used the correct temp vars for the same source assertions. After removing `/hyperopen/.shadow-cljs/builds/test/dev/out`, recompiling `test`, and rerunning `npm test`, the regenerated `test` artifact matched the sane `ws-test` pattern and the suite passed.

## Decision Log

- Decision: remove the renderer selector contract entirely instead of keeping a one-value abstraction for Portfolio and Vault detail charts.
  Rationale: `hyperopen-1lrp` exists specifically to remove rollback-only code. Leaving `:performance-chart-renderer` or `d3-performance-chart?` behind after the SVG path is gone would preserve dead complexity without reducing risk.
  Date/Author: 2026-03-30 / Codex

- Decision: treat this as a cross-layer contract cleanup, not just a view refactor.
  Rationale: deleting the inline SVG branches alone would leave dead action ids, dead app-state keys, stale schema bindings, and misleading public-action exports. The correct end state is that the fallback path no longer exists anywhere in production code.
  Date/Author: 2026-03-30 / Codex

- Decision: keep existing non-fallback chart behavior unchanged and preserve the D3 runtime contract as the single renderer.
  Rationale: the ticket is about deleting rollback-only plumbing, not redesigning chart behavior, reworking benchmark math, or changing fill semantics.
  Date/Author: 2026-03-30 / Codex

- Decision: require governed browser QA, but treat pre-existing `vault-detail-route` interaction blind spots as out of scope unless this cleanup worsens them.
  Rationale: this work is UI-facing and changes the integrated chart surfaces, so browser QA is mandatory. At the same time, the plan should not pretend that this ticket owns unrelated vault-detail browser blockers already tracked elsewhere.
  Date/Author: 2026-03-30 / Codex

## Outcomes & Retrospective

Implementation landed across the chart views, view-models, action plumbing, and deterministic tests. Portfolio and Vault detail performance charts now mount only the D3 host, app-state hover indexes are gone, the renderer selector contract was deleted, and the fallback-only hover action ids disappeared from runtime registration and schema surfaces. The cleanup also reduced namespace-size exceptions by dropping `chart_view.cljs` and `vaults/actions_test.cljs` below their temporary exception thresholds. A final repo-root grep under `src/hyperopen` and `test/hyperopen` found no remaining matches for `views.chart.renderer`, `d3-performance-chart?`, `include-svg-paths?`, `chart-hover-index`, `detail-chart-hover-index`, or the deleted hover action ids.

Validation was mixed but high-signal:

- `npm run check`
- `npm run test:websocket`
- `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs`
- `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "vault"`
- `npm run qa:design-ui -- --targets portfolio-route,trader-portfolio-route,vault-detail-route --manage-local-app`
- `npm test`

Observed results:

- `npm run check` passed end-to-end, including lint, release-assets, and all required Shadow compiles.
- `npm run test:websocket` passed with `430` tests and `0` failures.
- `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs` finished `2 passed, 2 failed`; the two assertions relevant to this ticket passed, then the managed Playwright web server died and the later unrelated tests failed with `page.goto: net::ERR_CONNECTION_REFUSED at http://127.0.0.1:4173/index.html`.
- `npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "vault"` finished `3 passed, 1 failed`; the remaining failure is `vault startup preview row click reuses inflight list bootstrap @regression`, which asserts vault list bootstrap request reuse outside the chart cleanup surface.
- `npm run qa:design-ui -- --targets portfolio-route,trader-portfolio-route,vault-detail-route --manage-local-app` no longer fails immediately on port conflicts after stale local processes are killed, but the latest managed rerun still hangs after writing only the `portfolio-route` `review-375` snapshot to `/hyperopen/tmp/browser-inspection/design-review-2026-03-30T21-35-17-240Z-b8be9d90/`.
- `npm test` passed after clearing the corrupted incremental `test` build output, recompiling `test`, and rerunning the suite; current result was `2919` tests, `15641` assertions, `0` failures, and `0` errors.

Remaining blockers are external to this ticket:

- The committed Playwright suites still carry unrelated red browser validation: the portfolio suite currently loses its local web server mid-run, and the vault suite still fails the startup-preview request-reuse assertion.
- Governed `qa:design-ui` still does not complete all three targets in this environment; the most recent managed run hangs after `portfolio-route` even after the stale local-app processes on `9630` and `4173` are cleared.

## Context and Orientation

In this repository, a "performance chart fallback path" means the older inline SVG chart branch that lives directly in the Hiccup view tree and stores pointer hover location in app state so the view can render a hover line and tooltip. The D3 runtime path is the newer approach where a host `div` mounts an imperative chart runtime that owns its own SVG DOM, pointer listeners, crosshair, and tooltip. The D3 runtime already exists at `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs` and is the default production path for both Portfolio and Vault detail charts.

The remaining fallback selector lives in `/hyperopen/src/hyperopen/views/chart/renderer.cljs`, backed by `/hyperopen/src/hyperopen/config.cljs`. Portfolio currently branches inside `/hyperopen/src/hyperopen/views/portfolio/chart_view.cljs`, and Vault detail does the same in `/hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs`. Those view branches are the most visible part of the cleanup, but they are not the whole task.

Portfolio chart data is assembled in `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` and `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs`. Those namespaces still read `[:portfolio-ui :chart-hover-index]`, include it in cache keys, and optionally generate SVG-only `:path` data. Vault detail chart data is assembled in `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` and `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs`, where the model still accepts `:hover-index` and `:include-svg-paths?` and can generate inline line and area paths for the removed branch.

The fallback hover actions are still wired as first-class runtime actions. Portfolio owns `set-portfolio-chart-hover` and `clear-portfolio-chart-hover` in `/hyperopen/src/hyperopen/portfolio/actions.cljs`; Vault detail owns `set-vault-detail-chart-hover` and `clear-vault-detail-chart-hover` in `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs`, with resets and re-exports in `/hyperopen/src/hyperopen/vaults/application/list_commands.cljs`, `/hyperopen/src/hyperopen/vaults/application/route_loading.cljs`, and `/hyperopen/src/hyperopen/vaults/actions.cljs`. Those actions are then reflected into `/hyperopen/src/hyperopen/core/macros.clj`, `/hyperopen/src/hyperopen/core/public_actions.cljs`, `/hyperopen/src/hyperopen/runtime/collaborators/chart.cljs`, `/hyperopen/src/hyperopen/runtime/collaborators/vaults.cljs`, `/hyperopen/src/hyperopen/schema/runtime_registration/portfolio.cljs`, `/hyperopen/src/hyperopen/schema/runtime_registration/vaults.cljs`, and `/hyperopen/src/hyperopen/schema/contracts/action_args.cljs`.

The relevant tests already show the split between the intended D3 contract and the old fallback contract. D3 host and tooltip behavior are covered in `/hyperopen/test/hyperopen/views/portfolio/chart_view_test.cljs`, `/hyperopen/test/hyperopen/views/vaults/detail/chart_view_test.cljs`, and `/hyperopen/test/hyperopen/views/portfolio_view_test.cljs`. Fallback-only or hover-state-only expectations still appear in `/hyperopen/test/hyperopen/portfolio/actions_test.cljs`, `/hyperopen/test/hyperopen/vaults/actions_test.cljs`, `/hyperopen/test/hyperopen/state/app_defaults_test.cljs`, `/hyperopen/test/hyperopen/schema/contracts/action_args_test.cljs`, `/hyperopen/test/hyperopen/runtime/collaborators/action_maps_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm_test.cljs`, `/hyperopen/test/hyperopen/views/portfolio/vm/chart_helpers_test.cljs`, and `/hyperopen/test/hyperopen/views/vaults/detail/chart_test.cljs`.

For browser-level verification, the committed Playwright surfaces are `/hyperopen/tools/playwright/test/portfolio-regressions.spec.mjs` and `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`, and the governed design-review targets are defined in `/hyperopen/tools/browser-inspection/config/design-review-routing.json`. The relevant browser-QA targets for this ticket are `portfolio-route`, `trader-portfolio-route`, and `vault-detail-route`.

## Plan of Work

Start by collapsing the renderer-selection contract. Remove `:ui :performance-chart-renderer` from `/hyperopen/src/hyperopen/config.cljs` and update `/hyperopen/test/hyperopen/config_test.cljs` accordingly. Then delete `/hyperopen/src/hyperopen/views/chart/renderer.cljs` if no remaining caller needs it. The Portfolio and Vault chart views should stop computing `d3-mode?` entirely and should always mount the existing D3 host. Rewrite the view tests that currently stub `chart-renderer/d3-performance-chart?` so they assert the D3-only behavior directly instead of toggling a removed selector.

Once the views no longer branch, simplify the chart model contracts. In `/hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs`, remove the optional `:include-svg-paths?` argument and any `:path`, `:hover`, or `:hover-tooltip` work that existed only for the inline SVG branch. In `/hyperopen/src/hyperopen/views/vaults/detail/chart.cljs`, remove `:include-svg-paths?`, stop generating fallback-only line or area paths, and keep only the data the D3 runtime still needs, especially the series points, y-axis ticks, and the fill metadata used for account-value and PNL rendering. Then simplify `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` and `/hyperopen/src/hyperopen/views/vaults/detail_vm.cljs` so their cache keys and inputs no longer include hover indexes or fallback flags.

After the models are simplified, delete the old hover app-state plumbing. Remove `:chart-hover-index` from `/hyperopen/src/hyperopen/state/app_defaults.cljs` and remove `:detail-chart-hover-index` from the vault UI defaults. Delete the hover setter and clearer functions from the Portfolio and Vault action namespaces and remove any route, tab, or list resets that only existed to keep those fields in sync. That deletion must continue through `/hyperopen/src/hyperopen/core/macros.clj`, `/hyperopen/src/hyperopen/core/public_actions.cljs`, `/hyperopen/src/hyperopen/runtime/collaborators/chart.cljs`, `/hyperopen/src/hyperopen/runtime/collaborators/vaults.cljs`, `/hyperopen/src/hyperopen/schema/runtime_registration/portfolio.cljs`, `/hyperopen/src/hyperopen/schema/runtime_registration/vaults.cljs`, and `/hyperopen/src/hyperopen/schema/contracts/action_args.cljs` so the dead action ids disappear cleanly.

Then refresh the deterministic test contract. Convert any test that only proves fallback tooltip layout or hover-index state mutation into D3-only assertions that still matter after the cleanup. Portfolio tests should continue to prove that benchmark rows, colors, and tooltip content survive in the D3 host. Vault detail tests should continue to prove that Returns exposes benchmark controls, Account Value uses a single under-fill, and PNL uses split positive and negative fills. Update action-map, state-default, and action-arg tests so they verify the new smaller public surface instead of pinning removed action ids.

Finally, add or update narrow browser coverage where it materially increases confidence. Portfolio already has a committed Playwright surface, so add or tighten a chart-focused regression there rather than creating a new browser suite. Vault detail already lives in `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`, so keep vault detail browser coverage there unless a clear split becomes necessary. After the smallest relevant Playwright commands pass, run governed browser QA for `portfolio-route`, `trader-portfolio-route`, and `vault-detail-route`. The portfolio targets should finish `PASS`. For `vault-detail-route`, a pre-existing interaction blind spot is acceptable only if it remains unchanged and is explicitly called out with the existing issue linkage; any new visual, layout-regression, native-control, or jank-perf failures are not acceptable.

## Concrete Steps

Work from `/Users/barry/.codex/worktrees/8a6e/hyperopen`.

Use this repo-root grep to confirm the fallback contract before editing:

    rg -n "performance-chart-renderer|d3-performance-chart\\?|set-portfolio-chart-hover|clear-portfolio-chart-hover|set-vault-detail-chart-hover|clear-vault-detail-chart-hover|chart-hover-index|detail-chart-hover-index|include-svg-paths\\?" src/hyperopen test/hyperopen

As implementation progresses, keep this ExecPlan current after every meaningful milestone and update the `Decision Log` whenever scope or design choices change.

Run the smallest relevant committed browser suites before broader QA:

    npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs
    npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "vault"

Then run the governed browser review for the affected routes:

    npm run qa:design-ui -- --targets portfolio-route,trader-portfolio-route,vault-detail-route --manage-local-app

Finish with the required repository gates:

    npm run check
    npm test
    npm run test:websocket

If browser QA reproduces only the already-documented `vault-detail-route` interaction blind spot, record that result in `Surprises & Discoveries` and keep the cleanup scoped. If any new browser failure appears, stop broadening the deletion and fix the regression before removing more contract surface.

## Validation and Acceptance

Acceptance is behavioral first.

1. Visiting `/portfolio` or `/portfolio/trader/<address>` still shows the existing chart shell, benchmark controls, legend, and D3 tooltip behavior, but the route no longer depends on any renderer selector or fallback hover actions.
2. Visiting `/vaults/<vaultAddress>` still shows Returns, Account Value, and PNL charts with the expected benchmark controls and fill behavior, and the route no longer depends on `detail-chart-hover-index` or fallback SVG paths.
3. Hovering the Portfolio or Vault detail chart in a real browser still produces the expected tooltip content and crosshair behavior through the D3 runtime, including benchmark rows with the correct series colors.

Acceptance is also structural.

1. A repo-root search under `src/hyperopen` no longer finds `:actions/set-portfolio-chart-hover`, `:actions/clear-portfolio-chart-hover`, `:actions/set-vault-detail-chart-hover`, `:actions/clear-vault-detail-chart-hover`, `:performance-chart-renderer`, or `include-svg-paths?`.
2. Portfolio and Vault chart model code no longer computes SVG-only path payloads or app-state hover payloads just to support a removed fallback branch.
3. Deterministic tests continue to prove the D3 host contract for Portfolio and Vault detail, and any remaining tests that referenced removed hover actions or hover-index state have been rewritten or deleted.
4. `npx playwright test tools/playwright/test/portfolio-regressions.spec.mjs` and the vault-focused Playwright command both pass.
5. `npm run qa:design-ui -- --targets portfolio-route,trader-portfolio-route,vault-detail-route --manage-local-app` explicitly accounts for all six passes on all required widths. `portfolio-route` and `trader-portfolio-route` should pass. `vault-detail-route` may retain the pre-existing known interaction limitation only if the run shows no new failures beyond the already-tracked baseline.
6. `npm run check`, `npm test`, and `npm run test:websocket` pass.

## Idempotence and Recovery

This cleanup is safe to apply in stages if each stage leaves the D3 runtime as the only renderer. If a partial deletion breaks compilation, prefer restoring only the missing D3-side data or contract binding long enough to complete the cleanup. Do not restore the inline SVG view branch as the default recovery path unless there is fresh proof that the D3 runtime cannot satisfy the existing user-visible behavior.

If browser QA re-surfaces the known `vault-detail-route` interaction blind spot without introducing any new regression, record the evidence and keep the issue scoped. If a new regression appears in tooltip behavior, fills, or benchmark rendering, first restore the minimum missing D3 view-model field or test fixture necessary to bring the D3 path back to parity before reconsidering any broader rollback.

## Artifacts and Notes

Planning artifacts for this ticket:

    bd issue: hyperopen-1lrp
    Active ExecPlan: /hyperopen/docs/exec-plans/active/2026-03-15-remove-legacy-performance-chart-svg-fallback-plumbing.md
    Spec artifact: /hyperopen/tmp/multi-agent/hyperopen-1lrp/spec.json
    Related completed plan: /hyperopen/docs/exec-plans/completed/2026-03-15-d3-performance-chart-migration.md

Most relevant baseline surfaces discovered during planning:

    Renderer selector:
      /hyperopen/src/hyperopen/config.cljs
      /hyperopen/src/hyperopen/views/chart/renderer.cljs

    Portfolio fallback and hover plumbing:
      /hyperopen/src/hyperopen/views/portfolio/chart_view.cljs
      /hyperopen/src/hyperopen/views/portfolio/vm.cljs
      /hyperopen/src/hyperopen/views/portfolio/vm/chart.cljs
      /hyperopen/src/hyperopen/portfolio/actions.cljs

    Vault fallback and hover plumbing:
      /hyperopen/src/hyperopen/views/vaults/detail/chart_view.cljs
      /hyperopen/src/hyperopen/views/vaults/detail_vm.cljs
      /hyperopen/src/hyperopen/views/vaults/detail/chart.cljs
      /hyperopen/src/hyperopen/vaults/application/detail_commands.cljs

    Cross-layer contract cleanup:
      /hyperopen/src/hyperopen/core/macros.clj
      /hyperopen/src/hyperopen/core/public_actions.cljs
      /hyperopen/src/hyperopen/runtime/collaborators/chart.cljs
      /hyperopen/src/hyperopen/runtime/collaborators/vaults.cljs
      /hyperopen/src/hyperopen/schema/runtime_registration/portfolio.cljs
      /hyperopen/src/hyperopen/schema/runtime_registration/vaults.cljs
      /hyperopen/src/hyperopen/schema/contracts/action_args.cljs

Plan revision note: 2026-03-30 16:47 EDT - Created the active ExecPlan after auditing the current fallback branches, contract wiring, deterministic tests, and browser-validation surfaces with parallel subagent support.

## Interfaces and Dependencies

The only supported performance-chart renderer after this change should be the shared D3 runtime in `/hyperopen/src/hyperopen/views/chart/d3/runtime.cljs`. The stable view anchors remain `portfolio-chart-d3-host` and `vault-detail-chart-d3-host`, and the D3 runtime should continue to receive a spec with `:surface`, `:axis-kind`, `:time-range`, `:points`, `:series`, `:y-ticks`, `:theme`, and a `:build-tooltip` function that returns the existing tooltip copy shape.

Portfolio chart selection, benchmark selection, and account-tab behavior stay on the current action ids such as `:actions/select-portfolio-chart-tab`, `:actions/select-portfolio-returns-benchmark`, and `:actions/set-portfolio-account-info-tab`. Vault detail keeps the existing chart-series, timeframe, and benchmark action ids such as `:actions/set-vault-detail-chart-series` and the benchmark selector actions. The action ids that must disappear entirely from production code are the fallback-only hover ids for Portfolio and Vault detail.

For the data contract, keep the D3-relevant series metadata intact. Portfolio still needs point arrays, legend labels, benchmark colors, and tooltip-building inputs. Vault detail still needs the strategy and benchmark point sets plus the D3 fill metadata used by account-value and PNL rendering. Removing the fallback does not authorize changing chart math, benchmark sourcing, or tooltip copy.

The deterministic verification surface should remain concentrated in the existing chart and route tests rather than inventing a second test architecture. Prefer updating the current chart-view, view-model, action-map, and Playwright files named in this plan over creating parallel test namespaces unless the existing files become unreasonably hard to read.
