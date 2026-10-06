# Include HyperEVM in the classic Total Account Value

This ExecPlan is a living document. Keep `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` current under [`.agents/PLANS.md`](../../../.agents/PLANS.md).

## Purpose / Big Picture

The user corrected the Total Account Value definition: **classic Total Account Value includes HyperEVM**, even though HyperEVM remains explicitly marked **Not margin** and must not affect collateral, leverage, liquidation, maintenance-margin, or any other risk calculation. For the supplied snapshot, the panel must display:

    Spot       $224,892.08
    Perps      $0.01
    Vaults     $23,028.52
    HyperEVM   $56,538.39  Not margin
    --------------------------------
    Total Account Value $304,459.00

The prior grouped-total work deliberately excluded HyperEVM. This follow-up supersedes that display-total decision only; its completed plan remains the historical record at [`2026-10-05-classic-account-equity-vaults.md`](../completed/2026-10-05-classic-account-equity-vaults.md).

## Context References

Public refs:

- Direct user correction, 2026-10-05: “include HyperEVM in classic Total Account Value,” with supplied values Spot `$224,892.08`, Perps `$0.01`, Vaults `$23,028.52`, and HyperEVM `$56,538.39`, yielding `$304,459.00`.

Repo artifacts:

- Completed predecessor: [`2026-10-05-classic-account-equity-vaults.md`](../completed/2026-10-05-classic-account-equity-vaults.md), whose exclusion of HyperEVM from the classic display total is superseded by this request.

## Scope and Non-Goals

This change is limited to the classic Account Equity display metric and its grouped-total copy/tests. It uses the existing account-owned HyperEVM funds projection, including its retained-last-successful-read behavior during refresh/error; it adds no API client, websocket subscription, poller, transport cache, or pricing algorithm.

The HyperEVM line stays visible separately with its **Not margin** chip and its existing Move/action behavior. `:account-value-display` keeps its public Spot + Perps semantics. Unified Account Summary, Portfolio totals, collateral, cross margin ratio, leverage, maintenance margin, liquidation rules, and transfer mechanics are unchanged.

This is correctness work, not performance work. Preserve the existing projected `/trade` HyperEVM slice and memoization boundary; do not pass raw `:hyperevm` state merely to calculate the total. Existing repaint/cost tests are the regression guard rather than a new performance target.

## Progress

- [x] (2026-10-05) Captured the user's corrected definition and exact snapshot arithmetic in this active follow-up plan.
- [x] (2026-10-05) Materialized RED coverage for 22 intended HyperEVM total/projection behaviors, including the real `/trade` cached-slice path. Evidence: `tmp/multi-agent/account-equity-vaults/hyperevm-total-red-network-runtime.log`.
- [x] (2026-10-05) Froze production behavior after guard/cache/lazy-export checks and production review passed. The reviewer verified canonical full projection, slim four-field `/trade` input, cache-before-hit ordering, explicit nil for absent input, retained last-ready behavior, and unchanged Unified/risk semantics. Namespace-size, boundary, and diff checks pass; the two stale identity docstrings were subsequently corrected without behavior change.
- [x] (2026-10-05) Corrected the two stale identity docstrings and refroze production behavior. First source GREEN passed new HyperEVM tests but exposed eight legacy Vault-total fixtures without an independently confirmed HyperEVM read; their corrected scoped state now includes a true ready/current zero summary. The final compiler-backed `npm test` passed.
- [x] (2026-10-05) Passed the HyperEVM correction unit gate: `npm test` ran 6,971 tests / 39,287 assertions with 0 failures and 0 errors. Coverage includes four-component snapshot arithmetic; zero, unknown, mismatch, partial, unpriced, no-account, and dust states; classic-only invariance; and `/trade` cached-slice reactivity without a metadata-only repaint. Evidence: `tmp/multi-agent/account-equity-vaults/hyperevm-total-final-network-runtime.log`.
- [x] (2026-10-05) Materialized the deterministic named-dex footer-clearance RED after three focused browser cases passed. At 1280, the actual panel bottom was 866 and footer top was 857; the required 8px clearance means a maximum bottom of 849, so the panel was 17px beyond target. The final classic-only spacing correction and browser QA restore 9px clearance.
- [x] (2026-10-05) Froze the two-class classic spacing correction: outer spacing `2 → 1` and grouped breakdown spacing `1 → 0.5`. It retains `p-3`, all controls, trade-shell geometry, and Unified layout. Delimiter preflight (2,282 files) and `git diff --check` pass. The new visible **Not margin** browser assertion is static-clean. An initial focused 4/4 browser pass exposed that the Hiccup shorthand emitted `space-y-0` and `5`, not the intended fractional class; the correction now uses explicit `:class ["space-y-0.5"]` and awaits a DOM-verified rerun.
- [x] (2026-10-05) Passed the post-repair focused browser suite: 4/4 in 1.3 minutes. It verifies the explicit fractional class in the actual final source, all four widths, exact `$304,459.00` arithmetic, confirmed-empty and unpriced states, visible **Not margin**, and footer clearance. Reviewer also confirms the explicit-class repair follows established repository patterns and passes diff, namespace-size, and boundary checks. The final focused rerun and governed six-pass QA subsequently passed.
- [x] (2026-10-05) Passed direct governed browser QA for all four widths: the DOM has `space-y-0.5` with 2px row gaps; at desktop the HyperEVM line bottom is 848 and footer top is 857, proving 9px clearance. Exact `$304,459.00`, confirmed-empty `$247,920.61` with no phantom row, unpriced `--`, **Not margin**/**Move**, console/network, and seven-tab geometry pass. Evidence: `tmp/browser-inspection/account-equity-all-assets-direct-qa-2026-10-05T23-18-00-000Z/browser-report.json` and `positive-{375,768,1280,1440}.png`. Subsequent visual validation found that **Move** had an inadequate ~31×12px target and no captured keyboard-focus evidence; the narrow repair and its fresh browser proof are now complete.
- [x] (2026-10-05) Froze the narrow Move repair: `min-h-6` produces a 24px target; fresh QA verifies its existing focus styling. To retain footer clearance, the classic outer wrapper uses explicit `:class ["space-y-0.5"]`; the grouped breakdown remains 2px, and `p-3`, funding control height, and trade-shell geometry are unchanged. Delimiter preflight (2,282 files) and `git diff --check` pass. Final browser evidence confirms its rendered behavior.
- [x] (2026-10-05) Fresh visual measurements confirm the repaired **Move** action is 30.98×24px, keyboard focus opens the modal and returns correctly, and the HyperEVM bottom is 848 against a footer top of 857 (9px clearance). The report aggregation/contrast repair is complete and supports final governed acceptance; this was evidence work, not a product behavior failure.
- [x] (2026-10-05) Passed final browser acceptance with a consistent report: focused Playwright 4/4 in 1.1 minutes and all six governed QA passes at 375, 768, 1280, and 1440. **Move** is at least 24px at every width (30.98×24px at 1440); Enter opens the EVM→Spot modal and Escape returns focus; row gaps are 2px; and desktop clearance is 9px (848/857). Contrast is Total 17.68:1, label 5.75:1, and Move 9.54:1. Browser cleanup confirms no sessions and no watcher. The 15%-translucent **Not margin** chip preserves its unchanged visual composition; exact composited contrast is an explicitly recorded supplementary measurement blind spot. `npm run gates` then passed 34/34.
- [x] (2026-10-05) Implemented a classic-only HyperEVM total input from the existing account-owned funds projection and updated the total/UI copy without changing margin or risk semantics.
- [x] (2026-10-05) Added focused RED/GREEN coverage for known, empty, unknown, partial, error, unpriced, dust, address, and no-account HyperEVM states while preserving public and risk semantics.
- [x] (2026-10-05) Passed focused Playwright, governed browser QA at 375, 768, 1280, and 1440, cleanup, and aggregate gates; this plan is ready to move to `completed/`.

## Surprises & Discoveries

- Observation: the current classic panel explicitly excludes HyperEVM from its total while rendering the value below Vaults.
  Evidence: `src/hyperopen/views/account_equity/panels.cljs` says the total is Spot, Perps, and Vaults and comments that HyperEVM is excluded.

- Observation: the existing `hyperevm-funds` projection is already account-owned and distinguishes ready/priced, unpriced, partial, unavailable, and empty reads; its own contract says unknown is never zero.
  Evidence: `src/hyperopen/views/account_info/projections/hyperevm_funds.cljs` returns `:status`, `:address`, `:usd`, `:token-count`, and `:unpriced-count`; `hyperevm-line-model` uses it for the visible line.

- Observation: `/trade` computes the HyperEVM line from full state because the Account Equity panel itself receives a reduced, memoized state slice.
  Evidence: `src/hyperopen/views/trade_view.cljs` documents that the panel slice has no identity, wallet, or raw HyperEVM state and passes a precomputed line model.

- Observation: the broad first RED run reported 24 failures and one error, rather than only the 22 intended behavior failures, because a new test eagerly required the account-surfaces module and published Google exports globally, affecting header/module-mobile setup.
  Evidence: TDD isolation trace. The test setup is being isolated before implementation validation; this record does not classify the extra failures as an application or pre-existing UI defect.

- Observation: the smallest safe integration evaluates canonical `hyperevm-funds` from full state, then transfers only `{:status :address :usd :unpriced-count}` across the `/trade` memo boundary; metrics retain their full-state fallback for direct callers.
  Evidence: production review of the frozen implementation. It also verified that absent slim input is explicit unknown rather than numeric zero and that metric cache inputs are examined before cache hits.

- Observation: eight established Vault-total assertions initially failed after the new total operand was correct because their scoped fixtures supplied no independent HyperEVM confirmation, which now truthfully makes Total Account Value unknown.
  Evidence: first source GREEN run. TDD amended only the fixture seam with a current ready `$0.00` HyperEVM projection; it did not alter production behavior or weaken the unknown-value contract. The eager lazy-export isolation issue is also resolved.

- Observation: the first focused browser run passed the exact `$304,459.00` snapshot, while the named-dex UI correctly rendered `$225,039.85` but its test compared raw JavaScript floating-point addition (`225039.84999999998`) to display text.
  Evidence: focused two-test run. The assertion is being changed to reconcile rendered integer cents; it is a test-precision defect, not an application defect. Missing browser zero/unknown/unpriced cases and the footer-width loop remain before the final rerun.

- Observation: after rendered-cents correction, the focused exact-snapshot, confirmed-empty, and unpriced-total-unknown browser cases pass, but the named-dex positive-HyperEVM layout check fails the established footer-clearance assertion at 1280.
  Evidence: panel bottom 866, footer top 857, maximum allowed panel bottom 849. The implementation will adjust only two classic Account Equity spacing classes, retaining `p-3`, funding button height, trade-shell geometry, and Unified layout. The regression assertion remains frozen until the rerun passes.

- Observation: the final visual correction changes only the classic outer spacing from `2` to `1` and grouped breakdown spacing from `1` to `0.5`; no new ClojureScript behavior or unit class assertion is required.
  Evidence: frozen worker diff, delimiter preflight across 2,282 files, and `git diff --check`. Browser automation adds a visible **Not margin** assertion; the subsequent final proof records 9px clearance.

- Observation: a Hiccup dot-shorthand form for the fractional group class split `space-y-0.5` into `space-y-0` and `5`, so it emitted no intended 2px gap even though the first focused browser run passed.
  Evidence: post-run source/DOM review. The only follow-up is an explicit `:class ["space-y-0.5"]` attribute; browser QA is paused until it verifies the actual DOM class, exact `$304,459.00` math, and the 1280 footer clearance again.

- Observation: the explicit class repair passes the final focused 4/4 browser suite in 1.3 minutes, covering its actual DOM output rather than source text alone.
  Evidence: browser/TDD handoff. It covers the exact four-part total, confirmed-empty and unpriced states, the visible **Not margin** label, footer behavior, and all four target widths; direct six-pass QA and aggregate gates continue as final release evidence.

- Observation: direct QA confirms the intended `space-y-0.5` DOM class produces 2px grouped-row gaps, and the compact classic panel meets the 8px footer requirement with 9px clearance on desktop.
  Evidence: `tmp/browser-inspection/account-equity-all-assets-direct-qa-2026-10-05T23-18-00-000Z/browser-report.json`, plus `positive-{375,768,1280,1440}.png`. At 1280/1440 the seven tabs preserve stable account rectangles of 960×288 and 1120×288. The visual validator subsequently found an unrelated-to-total but in-scope rendered **Move** affordance problem: ~31×12px hit target and no focus indication. The direct QA artifact is therefore not final accessibility acceptance; a narrow repair and new evidence are required before cleanup and gates.

- Observation: after the visual assessment of the completed browser artifact, the HyperEVM **Move** action fails the governed interactive-control criterion: its actual target is about 31×12px, below the 24×24px minimum, and it has no visible keyboard focus indicator.
  Evidence: visual-validator review. Parent approved a narrow accessibility repair with materialized TDD assertions; browser validation is reopened for RED/GREEN, direct QA, and cleanup. This does not change four-component arithmetic, margin/risk semantics, or the grouped-total layout contract.

- Observation: the frozen Move repair adds a 24px minimum target and focus styling without enlarging into another hit area; the outer fractional spacing is made explicit again to preserve the fixed footer clearance after the target height increases.
  Evidence: worker freeze: `min-h-6`, explicit `:class ["space-y-0.5"]`, unchanged 2px grouped breakdown, delimiter preflight over 2,282 files, and `git diff --check`. Fresh browser GREEN remains the acceptance evidence.

- Observation: the 24px Move target produces a new deterministic footer-clearance RED: HyperEVM bottom is 852 and footer top is 857, leaving 5px rather than the required 8px.
  Evidence: post-target browser measurement. The approved correction reduces only the classic outer top padding by 4px while retaining the 24px target and 2px grouped gaps; an exploratory Perps Overview padding change was caught as downstream/ineffective and will not be part of the final patch. Do not claim the former 9px clearance after this size change; fresh DOM/browser proof is required.

- Observation: fresh direct measurements after the outer top-padding repair satisfy the actual product criteria: **Move** is 30.98×24px, keyboard focus opens the modal and returns to the control, and HyperEVM bottom 848 versus footer top 857 restores 9px clearance.
  Evidence: UI-validator run. The browser report harness aggregates an inconsistent FAIL and records null contrast despite the product checks passing; it is being repaired before final QA is accepted, so no full browser PASS is claimed yet.

- Observation: the repaired browser report is consistent and final QA passes: focused Playwright 4/4 in 1.1 minutes, all six governed passes at all four widths, ≥24px Move targets, Enter/Escape modal focus round-trip, 2px groups, and 9px desktop clearance.
  Evidence: final browser report and native-card review. Measured contrast is 17.68:1 for the total, 5.75:1 for the label, and 9.54:1 for Move. The unchanged 15%-translucent **Not margin** chip has no exact composited contrast value, a supplementary blind spot that does not block acceptance. Browser sessions are empty and the watcher is stopped; aggregate gates are running.

## Decision Log

- Decision: Include a known, fully priced HyperEVM value in the classic display total while retaining the **Not margin** label.
  Rationale: this is the user's corrected definition of account value. The label continues to communicate that including a balance in the display total does not make it collateral.
  Date/Author: 2026-10-05, user correction.

- Decision: Treat HyperEVM as known only when the existing projection supplies a ready, complete, finite value for the effective account and reports no unpriced holdings. A ready confirmed-empty read contributes numeric zero; a ready nonempty priced amount, including dust, contributes its actual finite amount. No entry, no shown account, partial, address mismatch, non-finite USD, or any unpriced holding makes the classic total unknown. A refresh/error that retains the last successful ready projection retains that known amount; a first/unavailable read without one remains unknown.
  Rationale: a total that silently treats unread or unpriced funds as zero would understate the account. Ready empty is the only appropriate zero exclusion.
  Date/Author: 2026-10-05, planning.

- Decision: Keep the existing HyperEVM line model and read lifecycle as the single source of truth; add only the minimal projected total input required by Account Equity metrics.
  Rationale: it preserves account ownership, avoids duplicate fetching/pricing, and prevents raw polling timestamps or gas state from causing needless `/trade` panel repaints.
  Date/Author: 2026-10-05, planning.

## Outcomes & Retrospective

Production implementation, compiler-backed unit validation, browser QA, visual assessment, cleanup, and repository gates are complete. The result is a classic Total Account Value that reconciles Spot + Perps + Vaults + known priced HyperEVM, while all collateral and risk semantics remain unchanged.

### Follow-up change inventory and validation record

Follow-up production paths are `src/hyperopen/surface_modules.cljs`, `src/hyperopen/views/account_equity/metrics.cljs`, `src/hyperopen/views/account_equity/panels.cljs`, `src/hyperopen/views/account_equity/hyperevm_line.cljs`, `src/hyperopen/views/account_equity_view.cljs`, `src/hyperopen/views/account_surfaces_module.cljs`, `src/hyperopen/views/trade_view.cljs`, and `src/hyperopen/views/account_info/projections/hyperevm_funds.cljs`. The predecessor plan holds the broad Vault-fetch/poller inventory; this plan records only the later HyperEVM display and narrow accessibility follow-up.

The worktree setup preflight was completed before gates. The standalone compiler-backed `npm test` passed 6,971 tests / 39,287 assertions / 0 failures / 0 errors (`tmp/multi-agent/account-equity-vaults/hyperevm-total-final-network-runtime.log`). `PLAYWRIGHT_REUSE_EXISTING_SERVER=true npx playwright test tools/playwright/test/account-equity-classic-named-dex.spec.mjs --workers=1` passed 4/4 in 1.1 minutes. Final `npm run gates` exited 0 with 34/34 checks passing in 1 minute 42 seconds: 7,761 tests / 42,806 assertions / 123 Node tests; it includes the 6,971-test unit run, 593 websocket tests / 3,300 assertions with no failures, and all application, portfolio, and worker compiles. Evidence: `tmp/multi-agent/account-equity-vaults/hyperevm-total-final-gates.log`.

Follow-up test paths are the new `test/hyperopen/views/account_equity_hyperevm_total_test.cljs`; `test/hyperopen/views/account_equity/hyperevm_line_test.cljs`; `test/hyperopen/views/account_info/hyperevm_equity_invariance_test.cljs`; `test/hyperopen/views/trade_view/hyperevm_line_repaint_test.cljs`; `test/hyperopen/views/trade_view/hyperevm_funds_cost_test.cljs`; `tools/playwright/test/account-equity-classic-named-dex.spec.mjs`; and regenerated `test/test_runner_generated.cljs`. The Move accessibility RED was deterministic: focused Playwright had 1/4 failure at 375 because the button height was 12px, while exact, confirmed-empty, and unpriced cases passed. The final 24px target/focus repair avoids hit-area overlap and passes browser acceptance.

Residual evidence limits: a 768px header/logo-and-Portfolio overlap was seen outside the changed Account Equity panel without a before-state baseline, so it is not attributed to this work; jank evidence uses sampled long-task/browser observations, not continuous recording or a formal performance profile; and exact composited contrast of the unchanged 15%-translucent **Not margin** chip was not measured. The work adds no performance mechanism, and the existing memoized slim projection plus repaint tests remain the regression guard.

Final review found no actionable findings. Its weighted completion assessment is 99.0% (Testing 40/40, Review 30/30, Logical 29/30), exceeding the 84.7% threshold; the final logical point remains reserved for the documented evidence limits rather than a product defect.

## Context and Orientation

`src/hyperopen/views/account_equity/metrics.cljs` currently computes `:total-account-value-display` only from Spot, Perps, and Vaults; its cache keys do not include a HyperEVM total input. `src/hyperopen/views/account_equity/panels.cljs` renders the total and calls `hyperevm-line` after Vaults. `src/hyperopen/views/account_equity/hyperevm_line.cljs` provides the shown-account line and **Not margin** chip from `hyperopen.views.account-info.projections.hyperevm-funds/hyperevm-funds`.

`src/hyperopen/views/trade_view.cljs` renders the Account Equity panel from a reduced state. Its cached slice must receive the slim `:account-equity/hyperevm-funds` value `{:status :address :usd :unpriced-count}`, computed from canonical full-state `hyperevm-funds` through an additive lazy account-surfaces export. `account-equity-metrics` retains a full-state fallback for direct callers. Do not route rendered text such as `"Unpriced"` into arithmetic, and do not pass raw HyperEVM polling timestamps or gas state into the memoized slice.

### Actual implementation boundary

The follow-up changes `src/hyperopen/surface_modules.cljs`, `src/hyperopen/views/account_equity/metrics.cljs`, `src/hyperopen/views/account_equity/panels.cljs`, `src/hyperopen/views/account_equity/hyperevm_line.cljs`, `src/hyperopen/views/account_equity_view.cljs`, `src/hyperopen/views/account_surfaces_module.cljs`, `src/hyperopen/views/trade_view.cljs`, and `src/hyperopen/views/account_info/projections/hyperevm_funds.cljs`. The exact test and generated-runner inventory is recorded in Outcomes & Retrospective. No new client, polling, or risk namespace is part of this boundary.

## Plan of Work

### Milestone 1 — Derive a truthful classic HyperEVM contribution

Create a classic-only `:hyperevm-equity` (or equivalent internal total operand) from the existing `hyperevm-funds` projection. It is a finite number only for the current effective account with `:status :ready`, finite `:usd`, and `:unpriced-count` zero. A ready confirmed-empty projection contributes `0`; a known priced dust amount remains its actual finite amount rather than being manufactured as zero. No entry, no shown account, partial read, unpriced token, stale address, or non-finite value returns nil. Retain an existing last successful ready projection across a refresh/error exactly as the canonical projection does; a first/unavailable read without that retained value is unknown.

Include this operand in `:total-account-value-display` only when Spot, Perps, Vaults, and HyperEVM are all known. Preserve `:account-value-display = Spot + Perps`, all existing unified metric values, and every risk/collateral calculation. Add only the projected HyperEVM identity required to invalidate Account Equity metrics when the display contribution changes. Keep `hyperevm-line-model`, its Not margin chip, and its Move/link behavior independent of whether the number appears in the total.

Update the classic total tooltip/copy to say it includes Spot, Perps, Vaults, and HyperEVM, and that HyperEVM is shown as Not margin. Keep the grouped breakdown order Spot, Perps, Vaults, then the existing HyperEVM line. The line remains out of Perps Overview and all risk calculations.

### Milestone 2 — Prove value truth, ownership, and unchanged risk semantics

Add focused unit/view tests beside the existing Account Equity and HyperEVM suites. Assert the supplied values produce `$304,459.00` exactly in its own screenshot fixture as well as in the existing named-dex sum fixture, and preserve the old public identity `:account-value-display = Spot + Perps`. Assert a current, ready empty HyperEVM read includes zero; a current ready unpriced token, partial response, first/unavailable read with no retained success, missing response, invalid/non-finite USD, foreign address, and no-account case renders Total Account Value `--` rather than omitting HyperEVM as zero. Assert that the existing retained-last-successful ready projection remains included during a refresh/error, and that a hidden positive dust amount remains included even when the existing visible HyperEVM line suppresses it. Verify a change to the current projected HyperEVM value invalidates the total, while polling changes that do not change the projected total do not redraw the panel unnecessarily. Keep any new module test isolated so loading it cannot publish global exports or disturb unrelated header/mobile fixtures. Existing Vault-total fixtures must add a current ready zero summary where they intend to assert a known three-part amount.

Assert the HyperEVM line continues to show **Not margin**, its existing Move/read-only/disabled behavior, and the unchanged amount. Assert cross margin ratio, maintenance margin, leverage, liquidation/collateral metrics, Unified Account Summary, Portfolio display, and `:account-value-display` retain existing fixture values. Do not add HyperEVM to Vaults, risk metrics, or unified totals.

Use the established `hyperevm-funds` fixtures and the minimal `/trade` Account Equity slice integration test. Expected touched areas are `views/account_equity/metrics.cljs`, `views/account_equity/panels.cljs`, `views/trade_view.cljs` or its existing projection boundary, existing Account Equity/HyperEVM tests, and the named-dex Playwright regression. The worker must select the smallest compatible set after RED coverage identifies the exact seam.

### Milestone 3 — Verify browser presentation and repository gates

Extend `tools/playwright/test/account-equity-classic-named-dex.spec.mjs` with deterministic known-HyperEVM, confirmed-empty, and unpriced/unknown scenarios. Assert the exact four-part arithmetic in rendered integer cents rather than raw JavaScript floating-point sums, and assert that an unpriced/unknown HyperEVM state produces `Total Account Value --` without changing known Spot/Perps/Vaults rows. Check the Not margin chip remains visible for a shown HyperEVM balance.

Run focused Playwright first, then record PASS, FAIL, or BLOCKED for visual, native-control, styling consistency, interaction, layout regression, and jank/perf at 375, 768, 1280, and 1440. Preserve the deterministic assertion that the panel bottom is at least 8px above the footer; the named-dex positive-HyperEVM RED at 1280 must pass after the minimal two-class classic spacing correction. Verify `space-y-0.5` in the rendered DOM through the explicit class attribute rather than a Hiccup dot shorthand. Verify the visible **Move** control has a rendered target of at least 24×24px and a visible keyboard-focus state. At 1280 and 1440 exercise all seven account tabs and retain account-panel geometry/footer-clearance evidence. Stop browser inspection sessions before completion. Finally run `npm run gates`; record aggregate check coverage and its `npm test`/websocket outcomes without claiming a separately invoked `npm run check`.

## Validation and Acceptance

- The supplied classic snapshot renders Total Account Value `$304,459.00`, equal to `$224,892.08 + $0.01 + $23,028.52 + $56,538.39`; the four visible component values reconcile to the cent.
- A confirmed ready empty HyperEVM read contributes `$0.00` to the arithmetic while the separate line may retain its existing hidden-empty presentation. A finite priced nonempty amount is included even if it rounds visually small.
- A no-entry/no-account, partial, unavailable-first-read, foreign, unpriced, or non-finite HyperEVM projection renders `Total Account Value --`. A refresh/error that retains a last successful ready projection keeps that known contribution. The total never treats the value as zero merely because the visible HyperEVM line is hidden or shows `Unpriced`.
- The HyperEVM line still carries **Not margin** and retains its Move/read-only/disabled behavior. Cross margin ratio, maintenance margin, leverage, liquidation/collateral metrics, Unified Account Summary, Portfolio totals, and classic `:account-value-display` remain unchanged for existing fixtures.
- The deterministic Playwright change and all six governed browser-QA passes succeed at 375, 768, 1280, and 1440, with seven-tab geometry evidence at 1280 and 1440; **Move** has at least a 24×24px rendered target and visible keyboard focus; and no browser session remains.
- `npm run gates` exits 0 and records successful constituent check coverage, `npm test`, and websocket validation.

## Idempotence and Recovery

The change is a pure display derivation over existing state. Recomputing it must not fetch, mutate HyperEVM balances, or change risk state. If a projection becomes unknown, total display returns to `--`; when the same current account later has a known ready projection, the total recomputes from that value. Revert only the targeted metric/panel/slice/test changes with a compensating patch if needed; do not reset unrelated workspace work.

## Interfaces and Dependencies

Existing public `:account-value-display` remains Spot + Perps. Classic Account Equity adds/updates only a dedicated HyperEVM operand and `:total-account-value-display` behavior:

    account-value-display       = spot-equity + perps-value       ; unchanged public meaning
    hyperevm-equity             = current, ready, fully priced HyperEVM funds or nil
    total-account-value-display = spot-equity + perps-value + vault-equity + hyperevm-equity

No new API, poller, or cache is introduced. `hyperevm-funds` remains the authoritative account-owned value projection, and Account Equity must consume only a minimal projected representation compatible with `/trade`'s memoization boundary.

Plan update note (2026-10-05): this plan supersedes only the completed Vaults plan's decision to exclude HyperEVM from the classic display total, following the user's corrected definition. It deliberately does not alter HyperEVM's Not margin/risk treatment. RED coverage established 22 intended HyperEVM total/projection failures, including the actual `/trade` cached-slice seam. A separate broad-run test-isolation artifact (24 failures/one error) came from eager account-surfaces module loading publishing global exports; test isolation resolved it and it was not a product regression. The frozen production implementation passed static review of canonical full projection, minimal four-field slice, direct-caller fallback, cache ordering, unknown handling, retained ready values, and unchanged Unified/risk behavior. First source GREEN required only legacy fixture correction: eight Vault-total tests now explicitly supply a ready/current zero HyperEVM summary rather than relying on absence as zero. Final compiler-backed GREEN passed at 6,971 tests / 39,287 assertions with 0 failures/errors, including no-account, positive-dust, and exact-snapshot coverage. Four focused browser cases proved the exact snapshot, confirmed-empty, unpriced-total-unknown, visible Not margin, and all target widths; rendered-cents comparison fixed the named-dex arithmetic assertion. Its positive-HyperEVM layout case was RED at 1280 (bottom 866 versus maximum 849). Although an initial post-spacing 4/4 pass followed the two-class correction, source/DOM inspection found Hiccup shorthand emitted `space-y-0` plus `5`, not the required fractional class. The worker replaced that form with explicit `:class ["space-y-0.5"]`. Direct six-pass QA passed at every target width: row gaps are 2px, desktop footer clearance is 9px (bottom 848; footer 857), exact/zero/unpriced/Not margin behaviors and seven-tab geometry pass. A later visual assessment found the rendered **Move** control was ~31×12px and lacked captured focus evidence. Adding its 24px target and capturing the existing focus behavior produced a second footer RED (bottom 852; footer 857; only 5px clearance). Reducing the classic outer top padding by 4px restored the actual criteria: Move is 30.98×24px, focus opens/returns from its modal, and clearance is 9px. Final report repair records contrast: total 17.68:1, label 5.75:1, Move 9.54:1. Cleanup confirms no active session or watcher. Exact composited contrast for the unchanged translucent Not margin chip remains a supplementary blind spot. `npm run gates` passed 34/34; its log is `tmp/multi-agent/account-equity-vaults/hyperevm-total-final-gates.log`.


## Typography refinement after user review

On 2026-10-05 the user requested less emphasis through font size and more separation between funding controls and the total. The only production change in this refinement is `src/hyperopen/views/account_equity/panels.cljs`: the total row and value use `text-sm` (12px with a 16px line height), matching the component values. Semibold weight and the divider retain hierarchy. The summary gains `pt-1.5`; combined with its 2px margin this gives an 8px gap after the buttons. Reducing the total row from 24px to 16px recovers sufficient room: desktop HyperEVM bottom is 846 and footer top is 857, leaving 11px clearance. All arithmetic and other account modes retain their established behavior.

Fresh browser validation: `PLAYWRIGHT_REUSE_EXISTING_SERVER=true npx playwright test tools/playwright/test/account-equity-classic-named-dex.spec.mjs --workers=1` passed 4/4 in 1.1 minutes. All six governed QA passes (visual, native controls, styling, interaction, layout, sampled jank) passed at 375, 768, 1280, and 1440px. Actual total typography is 12px/16px/600 versus component values 12px/16px/400. Exact, empty, unpriced, keyboard Move, and seven-tab geometry checks pass. Contrast measurements are Total 17.68:1, labels 5.75:1, Not margin 6.39:1, and Move 9.54:1; the earlier supplementary chip-contrast measurement gap is now resolved. Browser sessions and the worktree compiler were stopped before gates.

Fresh evidence and preview: `tmp/browser-inspection/account-equity-density-direct-qa-2026-10-05T23-59-00-000Z/browser-report.json` and `positive-1440-account-equity-card.png` in that directory. Source review and independent visual assessment passed without findings.

Final `npm run gates` exited 0: 34/34 checks passed, 7,761 tests / 42,806 assertions / 123 Node tests, in 2 minutes 7 seconds. This includes unit tests (6,971 / 39,287), websocket tests (593 / 3,300), and application/worker compiles. Evidence: `tmp/multi-agent/account-equity-vaults/account-equity-typography-final-gates.log`. No new tests were added for this reversible style adjustment; existing regression coverage was rerun. The prior sampled-performance and outside-panel tablet limitations remain; there are no remaining blockers for this refinement.
