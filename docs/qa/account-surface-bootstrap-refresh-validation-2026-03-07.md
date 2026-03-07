# Account-Surface Bootstrap and Refresh Validation (2026-03-07)

## Scope

Browser-inspection QA for the account-surface bootstrap/refresh consolidation in:

- `/hyperopen/src/hyperopen/account/surface_policy.cljs`
- `/hyperopen/src/hyperopen/account/surface_service.cljs`
- `/hyperopen/src/hyperopen/startup/runtime.cljs`
- `/hyperopen/src/hyperopen/websocket/user.cljs`
- `/hyperopen/src/hyperopen/order/effects.cljs`

## Environment

- Local app: `npm run dev` managed by browser-inspection
- Session id: `sess-1772850198211-ddab19`
- Browser: headless Chrome via browser-inspection
- Trade route: `http://localhost:8080/trade`
- Visual snapshot run:
  `/hyperopen/tmp/browser-inspection/inspect-2026-03-07T02-30-43-105Z-be10984e/`

## Checks

### 1. Trade page loads and renders normally

Captured a desktop snapshot of the local trade page after the refactor.

- Screenshot:
  `/hyperopen/tmp/browser-inspection/inspect-2026-03-07T02-30-43-105Z-be10984e/local/desktop/screenshot.png`
- Result:
  top navigation, chart, order book, order form, and account panels rendered without obvious layout or hydration regressions.

### 2. Startup bootstrap still hydrates account surfaces for a public address

Using browser eval, I:

1. set `[:wallet :address]` to public address `0x162cc7c861ebd0c06b3d72319201150482518185`,
2. reset request stats with `hyperopen.api.default$.reset_request_runtime_BANG_()`,
3. invoked `hyperopen.app.startup.bootstrap_account_data_BANG_(hyperopen.core.system, address)`,
4. waited `2200ms`, then inspected request stats and store slices.

Observed results:

- Effective address became `0x162cc7c861ebd0c06b3d72319201150482518185`
- `[:spot :clearinghouse-state]` present
- `[:portfolio :summary-by-key]` populated with keys:
  - `day`
  - `week`
  - `month`
  - `all-time`
  - `perp-day`
- `[:perp-dex-clearinghouse]` populated (sample keys: `xyz`, `flx`, `vntl`, `hyna`, `km`)
- `[:orders :open-orders-snapshot-by-dex]` populated (sample keys: `xyz`, `flx`)
- No rate-limited request types were observed

Started request types during the sampled bootstrap:

- `frontendOpenOrders`: `3`
- `userAbstraction`: `1`
- `spotClearinghouseState`: `1`
- `userFees`: `1`
- `userNonFundingLedgerUpdates`: `1`
- `historicalOrders`: `1`
- `userFills`: `1`
- `clearinghouseState`: `7`
- `userFunding`: `4`
- `portfolio`: `1`

Interpretation:

- Startup bootstrap still hydrates all expected account surfaces after delegating to the shared service.
- No request-rate limiter regressions were observed in this sampled browser run.

### 3. Controlled browser-runtime smoke for post-fill refresh coverage gating

To isolate the shared service inside a real browser runtime without background app noise, I created a temporary CLJS atom store in browser eval with:

- live websocket transport,
- usable `openOrders` and `webData2` streams (`:status :n-a`, `:subscribed? true`),
- a usable per-dex `clearinghouseState` stream for `xyz`,
- a ready per-dex snapshot already present in `[:perp-dex-clearinghouse "xyz"]`,
- `/trade` route active.

Then I invoked:

- `hyperopen.account.surface_service.refresh_after_user_fill_BANG_(deps)`

Observed results:

- `hyperopen.account.surface_policy.topic_usable_for_address_QMARK_` returned:
  - `openOrders`: `true`
  - `webData2`: `true`
- refresh calls emitted by the service:
  - `refresh-open-orders!`: `0`
  - `refresh-default-clearinghouse!`: `0`
  - `refresh-perp-dex-clearinghouse!`: `0`
  - `refresh-spot-clearinghouse!`: `1`

Interpretation:

- In browser runtime, the shared service correctly suppresses open-orders/default-clearinghouse/per-dex REST fallbacks when websocket coverage and snapshot readiness are already present.
- The remaining spot refresh is intentional for active trade-route balances surfaces.

## Findings

No verified defects were found in this QA pass.

One false negative occurred during initial fixture setup because the synthetic browser state used `subscribed` instead of the real health key `subscribed?`, which correctly caused the policy to treat the stream as unusable. After correcting the fixture to match production health shape, the browser-runtime smoke matched the expected service behavior.
