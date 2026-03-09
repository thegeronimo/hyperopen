# Mobile Account Card Theme Parity Across Positions, Balances, and Trade History

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This plan follows `/hyperopen/.agents/PLANS.md` and must be maintained in accordance with that file.

## Purpose / Big Picture

After this change, the mobile cards in the account-info surface will present one consistent visual system across `Positions`, `Balances`, and `Trade History`. A user on a phone-sized viewport will no longer see Positions using one dark card shell and namespace-chip treatment while Balances and Trade History use different backgrounds, borders, hover fills, divider colors, and pill styling.

Users will be able to verify the change by opening the account tabs on mobile, expanding cards in all three tabs, and confirming that the cards share the same shell color, border color, divider color, padding rhythm, hover treatment, and namespace chip styling while preserving each tab's own data layout and actions.

## Progress

- [x] (2026-03-08 23:05Z) Audited `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`, and the frontend/work-tracking guardrails.
- [x] (2026-03-08 23:06Z) Created and claimed `bd` issue `hyperopen-kdf` to track this parity pass.
- [x] (2026-03-08 23:08Z) Authored this ExecPlan with the current implementation seam and QA approach.
- [x] (2026-03-08 23:10Z) Moved the Positions mobile shell, toggle, and expanded-container classes into `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs` as the shared mobile-only theme contract.
- [x] (2026-03-08 23:10Z) Rewired `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`, `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`, and `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs` to consume the shared theme, and replaced the ad hoc balances/trade-history mobile namespace chip classes with `/hyperopen/src/hyperopen/views/account_info/shared.cljs` chip tokens.
- [x] (2026-03-08 23:11Z) Updated mobile-card regression coverage in `/hyperopen/test/hyperopen/views/account_info/tabs/balances_test.cljs`, `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs`, and `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs`.
- [x] (2026-03-08 23:16Z) Ran required validation gates: `npm test`, `npm run check`, and `npm run test:websocket`.
- [x] (2026-03-08 23:18Z) Completed live mobile browser QA on `/trade?spectate=0x162cc7c861ebd0c06b3d72319201150482518185` using browser-inspection session `sess-1773011607698-0d7c34`; confirmed matching computed shell/toggle chrome across Positions, Balances, and Trade History.

## Surprises & Discoveries

- Observation: only three account-info tabs currently use the shared expandable mobile-card primitive: Positions, Balances, and Trade History.
  Evidence: `rg "mobile-cards/expandable-card" /hyperopen/src/hyperopen/views/account_info/tabs` returns matches only in `positions.cljs`, `balances.cljs`, and `trade_history.cljs`.

- Observation: Positions already defines a tighter and darker visual contract than the default shared `mobile_cards` helper, but it does so by keeping the classes local to `positions.cljs`.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs` defines `mobile-position-card-shell-classes`, `mobile-position-card-button-classes`, `mobile-position-card-summary-grid-classes`, and `mobile-position-card-expanded-container-classes`, while Balances and Trade History each duplicate their own versions inline.

- Observation: Balances and Trade History also duplicate their own namespace chip styling instead of using the shared account-info chip tokens that Positions already uses.
  Evidence: both `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs` and `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs` hardcode `rounded-md bg-[#0d5a51] ... text-emerald-300`, while Positions routes chips through `/hyperopen/src/hyperopen/views/account_info/shared.cljs`.

- Observation: browser-inspection `inspect --session-id ...` still navigates to a URL before capture, so it is not suitable for preserving already-expanded in-session account cards.
  Evidence: after a balances-state capture attempt, the session URL reset from the spectate route to plain `/trade`, which removed the already-open mobile account state; live QA had to use `eval`-driven DOM metrics instead of interactive-state captures.

- Observation: the live spectate dataset used for browser QA did not expose namespaced chips on the sampled Balances or Trade History cards even though Positions still showed leverage chips and the code paths are test-covered.
  Evidence: browser-inspection session `sess-1773011607698-0d7c34` returned `chipText: null` for the sampled balances/trade-history cards while Hiccup regression tests continued to assert the shared chip classes on synthetic namespaced fixtures.

## Decision Log

- Decision: use the existing mobile Positions card theme as the canonical mobile card contract for this parity pass.
  Rationale: the user explicitly identified the mobile Positions cards as the desired reference, and reusing that theme is lower risk than inventing a new shared style and then trying to restyle Positions to match it.
  Date/Author: 2026-03-08 / Codex

- Decision: centralize the shared mobile-only card theme in `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs` instead of copying Positions class vectors into each tab.
  Rationale: the `expandable-card` primitive is already the shared mobile composition point, so moving theme tokens there reduces duplication and makes future mobile parity changes easier to keep consistent.
  Date/Author: 2026-03-08 / Codex

- Decision: keep summary-grid column templates tab-local while centralizing shell, divider, button, spacing, and chip styling.
  Rationale: the tabs have different information density and need different grid proportions, but the surrounding card chrome and chip styling should be identical.
  Date/Author: 2026-03-08 / Codex

- Decision: rely on live DOM/computed-style QA for interactive browser verification instead of trying to force screenshot capture through `inspect --session-id`.
  Rationale: the capture command re-navigates the session and drops in-session expansion state, while browser `eval` let this task verify the exact computed shell, border, toggle, and footer classes on the expanded mobile cards without altering app behavior.
  Date/Author: 2026-03-08 / Codex

## Outcomes & Retrospective

This plan is complete.

Implemented outcome:

- `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs` now owns the mobile account card shell contract derived from Positions: `bg-[#08161f]`, `border-[#17313d]`, `px-3.5`, `py-3`, and `hover:bg-[#0c1b24]`.
- Positions now consumes that shared theme instead of carrying its own duplicate shell vectors.
- Balances and Trade History mobile cards now render with the same shell/toggle/divider treatment as Positions.
- Balances and Trade History mobile namespace chips now use the shared account-info chip token instead of their prior rounded green pill variant.
- Balances footer spacing and action presentation were adjusted to match the Positions footer rhythm more closely.

Validation outcome:

- `npm test`: pass
- `npm run check`: pass
- `npm run test:websocket`: pass

Live mobile QA outcome:

- Balances sampled card `mobile-balance-card-perps-usdc` rendered `backgroundColor: rgb(8, 22, 31)`, `borderColor: rgb(23, 49, 61)`, shared toggle classes (`px-3.5`, `hover:bg-[#0c1b24]`), and shared expanded-container classes (`border-[#17313d]`, `px-3.5`, `py-3`).
- Positions sampled card `mobile-position-card-BTC|default` rendered the same shell/toggle metrics and retained the shared chip classes (`rounded-lg`, `border`, `bg-[#242924]`).
- Trade History sampled card `mobile-trade-history-card-1007921215832344|1773011876132|@142|66386.0|0.00031` rendered the same shell/toggle metrics.

No follow-up issue was required from this pass. The remaining live-data chip gap was only a fixture availability issue during browser QA and is covered by the updated Hiccup tests.

## Context and Orientation

The account-info panel is rendered through `/hyperopen/src/hyperopen/views/account_info_view.cljs`, but the mobile card bodies relevant to this task live in three tab-specific files:

- `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`

Those files all use `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs`, which provides the shared `expandable-card`, `summary-item`, `detail-item`, and `detail-grid` building blocks. In this plan, “mobile card theme” means the card shell background, border, divider, hover fill, padding rhythm, and related summary/detail presentation classes used only when the mobile card layout is active (`lg:hidden` branches in these tabs).

The current problem is not data or behavior mismatch. It is visual drift inside mobile-only account-info cards:

1. Positions uses a darker shell (`bg-[#08161f]`) and border/divider (`border-[#17313d]`) with slightly roomier horizontal padding.
2. Balances uses a flatter gray shell (`bg-[#1b2429]`) and border/divider (`border-[#273035]`).
3. Trade History uses a shell close to Positions but still not identical (`bg-[#0f1920]`, `border-[#1c2d36]`) and different padding.
4. Balances and Trade History render namespace chips with ad hoc class vectors instead of the shared chip token used elsewhere in account-info.

The mobile-only scope matters. Desktop tables should not change. The refactor must preserve current tab-specific data formatting, filtering, sorting, actions, and expansion behavior.

## Plan of Work

Milestone 1 will move the Positions mobile shell contract into `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs` as reusable theme helpers or constants. That shared module will become the source of truth for the mobile card shell, default button treatment, default expanded divider container, and a namespace-chip helper or class token suitable for balances and trade history. Positions will be rewired to consume the shared theme rather than keeping private duplicates.

Milestone 2 will update `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs` so its mobile card uses the shared Positions-derived shell and the same namespace chip treatment. The summary grid proportions will remain tuned for balances content, but card chrome, divider color, button hover fill, and chip styling will match Positions. Footer action styling will be reviewed and adjusted only where the theme drift is obvious and mobile-specific.

Milestone 3 will update `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs` in the same way. The trade-history summary grid and content layout will stay tab-specific, but the surrounding shell and namespace chip treatment will become identical to Positions. Any mobile-only typography or padding mismatches that keep the cards visually inconsistent will be brought into parity while avoiding desktop changes.

Milestone 4 will add regression tests in:

- `/hyperopen/test/hyperopen/views/account_info/tabs/balances_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/trade_history_test.cljs`
- `/hyperopen/test/hyperopen/views/account_info/tabs/positions_test.cljs` if needed

These tests will assert the mobile card shell classes and namespace chip structure rather than relying on screenshots alone. The tests should prove that the three tabs share the same mobile shell contract and that balances and trade history no longer use their old ad hoc chip classes.

Milestone 5 will run required repo validation plus mobile QA. The QA will include a browser/mobile pass that exercises Positions, Balances, and Trade History on a phone viewport, expands representative cards, and verifies no desktop regression or mobile layout break was introduced.

## Concrete Steps

From repository root `/hyperopen`:

1. Extract shared mobile theme constants and helpers into `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs`.
2. Rewire mobile Positions, Balances, and Trade History card renderers to consume those helpers.
3. Update Hiccup tests to assert the shared shell/chip contract in mobile card branches.
4. Run targeted tests while iterating:

    npm test -- --namespace hyperopen.views.account-info.tabs.positions-test
    npm test -- --namespace hyperopen.views.account-info.tabs.balances-test
    npm test -- --namespace hyperopen.views.account-info.tabs.trade-history-test

5. Run full validation:

    npm run check
    npm test
    npm run test:websocket

6. Run browser/mobile QA against the account-info surface and inspect Positions, Balances, and Trade History cards for parity and regressions.

Expected result: the three mobile card families share the same visual shell and namespace chip styling, tests pass, and full validation exits successfully. This result was achieved on 2026-03-08.

## Validation and Acceptance

The change is accepted when all of the following are true:

1. Positions, Balances, and Trade History mobile cards use the same shell background, border color, divider color, button hover fill, and padding rhythm.
2. Namespace chips in Balances and Trade History visually match the Positions chip treatment on mobile.
3. Desktop tables remain unchanged.
4. Mobile expansion toggles, sorting, filtering, and tab-specific actions still behave as before.
5. Regression tests cover the shared mobile shell/chip contract and pass.
6. `npm run check`, `npm test`, and `npm run test:websocket` all pass.
7. Browser/mobile QA shows no obvious layout regressions in the three affected tabs.

## Idempotence and Recovery

This change is a source-level refactor of presentation classes and helpers. Re-running the steps is safe. If centralizing the theme in `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs` causes unexpected drift, the recovery path is to keep the shared helper but temporarily route Positions back through its prior explicit class vectors while preserving the new tests, then reapply the shared theme incrementally.

If a mobile-only class change leaks into desktop rendering, recovery means moving that class back behind the `lg:hidden` branches in the affected tab renderer and re-running the tab tests before continuing.

## Artifacts and Notes

Key current implementation anchors:

- `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs`
- `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`
- `/hyperopen/src/hyperopen/views/account_info/shared.cljs`

Issue tracking:

- `bd` issue `hyperopen-kdf` tracks this parity pass.

## Interfaces and Dependencies

No new library dependency is needed.

Interfaces expected after implementation:

- `/hyperopen/src/hyperopen/views/account_info/mobile_cards.cljs` exposes the shared mobile account card theme contract used by Positions, Balances, and Trade History.
- `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs` and `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs` no longer hardcode their old mobile shell/chip styling.
- Existing tab render entry points remain the same:
  - `balances-tab-content`
  - `trade-history-tab-content`
  - `positions-tab-content`

Plan revision note: 2026-03-08 23:08Z - Initial plan authored after auditing the current mobile card implementations and identifying the shared `mobile_cards.cljs` seam as the lowest-risk parity path.
Plan revision note: 2026-03-08 23:18Z - Updated after implementation, validation, and live browser QA; moved from `active` to `completed` because the parity pass shipped without remaining follow-up work.
