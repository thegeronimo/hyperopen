# Vol Range stage 6 and local Release A: placement wiring, commit action and Vol ladders dock

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. It is maintained in accordance with `/hyperopen/docs/PLANS.md`.

## Purpose / Big Picture

HO-SPEC-014 v2.1 section 15.1 PR 6, plus the parts of PR 7 a first local real-money test needs. After this lands, a developer running the dev build can arm placement in their own browser, place a reviewed Vol Range ladder, watch it in the Vol ladders dock, refresh it and cancel it. Release builds never include placement.

## Context References

- Direct user request in this session (2026-10-27): complete the remaining steps so the feature can be tested with real money as a local execution first, then report UI defects.
- Lifecycle: `/hyperopen/docs/exec-plans/completed/2026-10-27-vol-range-order-lifecycle.md`.
- Test guide: `/hyperopen/docs/runbooks/vol-range-local-money-test.md`.
- Product spec: `/hyperopen/docs/product-specs/vol-range-implementation-spec.md` (HO-SPEC-014 v2.1), sections 7.3, 8, 10, 11.2, 11.4, 15.2.

## Progress

- [x] (2026-10-27) Execution gate: `VOL_RANGE_EXECUTION` goog-define (dev build only) plus per-browser arming and a per-ladder notional cap (`vol_range/infrastructure/execution_gate.cljs`).
- [x] (2026-10-27) Runtime adapter `runtime/effect_adapters/vol_range_execution.cljs` and the `:effects/vol-range-execute` effect; actions in `vol_range/execution_actions.cljs`; ladder sync and 15 s background reconciliation (`execution_watcher.cljs`).
- [x] (2026-10-27) Fresh book read at admission and before HTTP; fee refresh before placing; raw exchange replies kept in the local journal.
- [x] (2026-10-27) Commit button in the review and the Vol ladders dock tab (`views/trade/vol_range/dock.cljs`, `vol_range/application/dock_model.cljs`).
- [x] (2026-10-27) Independent Opus review of the wiring (4 major, 6 minor) and fixes.
- [x] (2026-10-27) Browser suites: A0 suite and a new placement suite (`tools/playwright/test/vol-range-execution.spec.mjs`), real signing, IndexedDB and Web Locks with a simulated exchange.

## Surprises & Discoveries

- The app store only keeps order books that visibly changed, and loads fees once, so the spec 7.3 freshness checks (book 2 s, fees 5 min) would refuse nearly every real placement. Placement now reads the book and fees fresh.
- The planner context is memoized per minute on inputs without provenance times; a fee refresh with an unchanged payload kept the old load time. The executor's live input takes the fee load time from state.
- The A0 browser suite was intermittently failing because the app's account bootstrap asked the live endpoint for the synthetic fixture account's mode; its stub now answers `userAbstraction`.

## Decision Log

- Placement needs both the build flag and per-browser arming; cancel, refresh and sync never depend on the gate.
- Cancel compares only network, owner, account and vault with the active session, so a ladder can be canceled from any market view.
- The dock shows only the active trading account's ladders.
- Repair ("Review rejected orders") and replacement ("Preview update") screens are not in this pass; rejected orders show their reasons.

## Outcomes & Retrospective

Ready for a local, small, real-money test following the runbook. Remaining before Release A: repair and replacement screens, the WebSocket fill tap, a proven rule for expired unknown orders, Hyperliquid reply fixtures from the real test, a two-tab browser test, and the remaining spec 14.2 visual and accessibility passes.

## Validation

`npm test`, `npm run gates`, and both Vol Range Playwright suites.
