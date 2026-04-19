# Trade Fill Blotter Dollar Net Flow

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`. It is linked to `bd` issue `hyperopen-wbf6`, which is the issue lifecycle source of truth while implementation is active.

## Purpose / Big Picture

The expanded trade-fill activity blotter currently shows `Net Flow` as a raw token quantity. That is misleading when a burst contains different assets, because one million units of a cheap token can be economically smaller than one unit of an expensive token. After this change, the blotter will show `Net Flow` as a signed USD amount, while `Notional` remains the gross USD value traded. A user can verify the result by expanding a grouped fill notification and seeing `Net Flow` render as a currency value such as `+$2.4k` or `-$570.16` instead of an asset-unit count.

## Progress

- [x] (2026-04-19 18:52Z) Created and claimed `bd` issue `hyperopen-wbf6`.
- [x] (2026-04-19 18:54Z) Located the expanded fill blotter renderer in `src/hyperopen/views/trade_confirmation_toasts.cljs` and its renderer coverage in `test/hyperopen/views/notifications_view_trade_confirmation_test.cljs`.
- [x] (2026-04-19 18:56Z) Wrote `expanded-trade-confirmation-blotter-renders-net-flow-as-signed-usd-test` and observed it fail against the old token-unit behavior.
- [x] (2026-04-19 18:57Z) Implemented signed USD net flow in `BlotterCard`.
- [x] (2026-04-19 18:59Z) Ran `npm test`; observed 3291 tests, 17952 assertions, 0 failures, 0 errors.
- [x] (2026-04-19 18:59Z) Ran `npm run check`; observed exit 0 with all lint/tooling checks and CLJS target compiles passing.
- [x] (2026-04-19 18:59Z) Ran `npm run test:websocket`; observed 449 tests, 2701 assertions, 0 failures, 0 errors.
- [x] (2026-04-19 18:59Z) Ran `npm run qa:design-ui -- --targets trade-route --manage-local-app`; observed review outcome PASS across widths 375, 768, 1280, and 1440.
- [x] (2026-04-19 19:00Z) Ran `npm run browser:cleanup`; observed no leftover tracked sessions.
- [x] (2026-04-19 19:01Z) Moved this ExecPlan to `docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: The renderer already computes both gross notional and raw net token quantity in one local `let`, so the behavior can be changed without modifying websocket payload shape or state.
  Evidence: `src/hyperopen/views/trade_confirmation_toasts.cljs` has `total-notional` and `net-qty` inside `BlotterCard`.

- Observation: This worktree initially had no `node_modules`, which blocked the first test command before it reached the new assertion.
  Evidence: `node out/test.js` failed with `Cannot find module 'lucide/dist/esm/icons/external-link.js'`; running `npm ci` installed 333 packages from the lockfile.

- Observation: `npm test -- --focus hyperopen.views.notifications-view-trade-confirmation-test` is not supported by the generated test runner and falls back to running the full suite after printing unknown-argument messages.
  Evidence: The red run printed `Unknown arg: --focus` and then ran all tests; the run failed only on the two assertions in the new test, proving the regression.

## Decision Log

- Decision: Keep the visible label `Net Flow`, but change its value from signed token units to signed USD.
  Rationale: The user identified the unit-based number as useless, and a dollar-denominated net flow is the economically meaningful value. Renaming the label is unnecessary because "flow" is common trading UI language when the value is currency-denominated.
  Date/Author: 2026-04-19 / Codex

- Decision: Preserve `Notional` as gross traded USD notional.
  Rationale: Gross notional answers a different useful question: total traded volume in the grouped fills. Signed net flow answers directional dollar impact.
  Date/Author: 2026-04-19 / Codex

- Decision: Implement the change in the view renderer rather than adding fields to websocket fill payloads.
  Rationale: Every fill already carries `:qty`, `:price`, and `:side`; deriving display values at the render boundary keeps the payload contract stable.
  Date/Author: 2026-04-19 / Codex

## Outcomes & Retrospective

The blotter now renders `Net Flow` as signed USD instead of raw token units. The test data proves the change with mixed fills that would previously display `+ 1,000,090` token units but now displays `+$372.17` while preserving gross `Notional $874.67`. The implementation reduces user-facing conceptual complexity because both summary money columns are now economically meaningful; code complexity increased only by a small local formatting helper.

Remaining risk is limited to visual state coverage: the browser design review passed all six required passes at all four required widths, but it reported the standard state-sampling blind spot that hover, active, disabled, and loading states require targeted route actions when not present by default. This change does not introduce new hover, active, disabled, or loading states.

## Context and Orientation

Trade-fill notifications are rendered from normalized fill data. The normalization path is `src/hyperopen/websocket/user_runtime/fills.cljs`, where raw fill rows become maps with `:qty`, `:price`, `:side`, `:symbol`, and `:ts`. The visual components live in `src/hyperopen/views/trade_confirmation_toasts.cljs`. `BlotterCard` is the expanded grouped-fill surface shown after a user expands a stack or consolidated fill notification.

In `BlotterCard`, `total-notional` currently sums `qty * price` for every fill. This is gross notional and should stay positive. The same function computes `net-qty` by adding buy quantities and subtracting sell quantities. That net token quantity becomes nonsensical when different symbols are grouped together, because token units are not comparable across markets. The fix is to compute `net-flow-usd` as `(qty * price)` with a positive sign for buys and a negative sign for sells, then format that signed value as USD.

The existing renderer test file is `test/hyperopen/views/notifications_view_trade_confirmation_test.cljs`. It renders `notifications-view/notifications-view` with synthetic fill props, collects the Hiccup strings, and asserts user-visible text. That file is the narrowest stable test surface for this change.

## Plan of Work

First, add a failing test to `test/hyperopen/views/notifications_view_trade_confirmation_test.cljs`. The test should render four mixed fills with a large raw positive token-unit imbalance but a much smaller signed USD net flow. It should assert that the `Net Flow` summary contains a signed currency string such as `+$440.17`, and that the raw token quantity string is absent from the summary text. This proves the UI no longer displays cross-asset token units.

Second, update `src/hyperopen/views/trade_confirmation_toasts.cljs`. Add a small helper near `compact-notional-text` that formats signed compact currency. For values with absolute magnitude at least 1000, render `+$1.2k` or `-$1.2k`; for smaller values, reuse `notional-text` on the absolute value and add the sign separately. Then replace `net-qty` in `BlotterCard` with `net-flow-usd`, computed by summing `qty * price * side-sign`.

Third, run the focused renderer test. After it passes, run the repository-required gates for code changes: `npm run check`, `npm test`, and `npm run test:websocket`. Because this touches UI-facing code, account for browser QA. The intended browser command is `npm run qa:design-ui -- --targets trade-route --manage-local-app`; if the command is blocked by environment, record the blocker and residual risk explicitly.

## Concrete Steps

Run all commands from `/Users/barry/.codex/worktrees/e202/hyperopen`.

1. Write the failing test in `test/hyperopen/views/notifications_view_trade_confirmation_test.cljs`.

2. Run:

       npm test -- --focus hyperopen.views.notifications-view-trade-confirmation-test

   Observed before implementation: the generated test runner printed `Unknown arg: --focus`, ran the full suite, and failed only the new assertions. The failure text showed `Net Flow + 1,000,090 Notional $874.67` instead of the expected `Net Flow +$372.17 Notional $874.67`.

3. Implement the renderer change in `src/hyperopen/views/trade_confirmation_toasts.cljs`.

4. Run:

       npm test

   Observed after implementation: 3291 tests, 17952 assertions, 0 failures, 0 errors.

5. Run required gates:

       npm run check
       npm test
       npm run test:websocket

   Expected: each command exits 0.

   Observed: `npm run check` exited 0; `npm test` exited 0 with 3291 tests and 17952 assertions; `npm run test:websocket` exited 0 with 449 tests and 2701 assertions.

6. Run or account for UI browser QA:

       npm run qa:design-ui -- --targets trade-route --manage-local-app

   Expected if the local browser QA environment is available: report PASS/FAIL evidence for visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf passes across 375, 768, 1280, and 1440 widths. If blocked, document the exact command and failure mode.

   Observed: review outcome PASS for `trade-route` at 375, 768, 1280, and 1440. The six passes `visual-evidence-captured`, `native-control`, `styling-consistency`, `interaction`, `layout-regression`, and `jank-perf` all reported PASS with 0 issues. Artifacts were written under `tmp/browser-inspection/design-review-2026-04-19T18-59-21-334Z-f53dfb0d`.

## Validation and Acceptance

The new test must demonstrate red-green behavior: it fails before the production change and passes after. The user-facing acceptance criterion is that an expanded fill blotter with mixed symbols renders `Net Flow` as a signed USD value and keeps `Notional` as gross USD notional. In the synthetic test data, buys and sells are intentionally chosen so raw token units and signed USD differ, making the regression visible.

Code validation requires `npm run check`, `npm test`, and `npm run test:websocket` to exit 0. UI validation requires the browser-QA passes from `/hyperopen/docs/FRONTEND.md` and `/hyperopen/docs/BROWSER_TESTING.md` to be run or explicitly accounted for as blocked with evidence.

## Idempotence and Recovery

The test and renderer edits are local and can be reapplied safely. If the focused test command unexpectedly cannot target the namespace with `--focus`, run `npm test` and inspect the named test failure or pass result. If the browser QA command starts a managed app or browser-inspection session and then fails, run `npm run browser:cleanup` before finishing. Do not run `git pull --rebase` or `git push` unless the user explicitly requests remote sync.

## Artifacts and Notes

Red evidence:

    FAIL in (expanded-trade-confirmation-blotter-renders-net-flow-as-signed-usd-test)
    expected: Net Flow +$372.17 Notional $874.67
    actual text included: Net Flow + 1,000,090 Notional $874.67

Green evidence:

    npm test
    Ran 3291 tests containing 17952 assertions.
    0 failures, 0 errors.

    npm run test:websocket
    Ran 449 tests containing 2701 assertions.
    0 failures, 0 errors.

    npm run qa:design-ui -- --targets trade-route --manage-local-app
    reviewOutcome: PASS
    widths: 375, 768, 1280, 1440
    passes: visual-evidence-captured, native-control, styling-consistency, interaction, layout-regression, jank-perf

## Interfaces and Dependencies

`BlotterCard` continues to accept a vector of fill maps validated by `fills-props?`. No external API changes are introduced. The relevant fill fields remain:

- `:qty`, a positive numeric fill quantity.
- `:price`, a numeric USD price.
- `:side`, either `:buy` or `:sell`.
- `:symbol`, the display symbol used in grouped rows.

The final renderer should expose the same summary columns: `Fills`, `Net Flow`, and `Notional`. Only the `Net Flow` value unit changes from token quantity to signed USD.

Revision note 2026-04-19: Initial active ExecPlan created for `hyperopen-wbf6` to guide the implementation requested in the current session.

Revision note 2026-04-19: Updated with red/green test evidence, implementation outcome, required validation commands, browser QA outcome, and the generated test runner focus-argument discovery.

Revision note 2026-04-19: Moved the plan to completed after all validation gates passed.
