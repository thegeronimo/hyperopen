# Websocket User Runtime Adapter Validation (2026-03-07)

## Scope

Browser-inspection QA for the websocket user adapter refactor in:

- `/hyperopen/src/hyperopen/websocket/user.cljs`
- `/hyperopen/src/hyperopen/websocket/user_runtime/common.cljs`
- `/hyperopen/src/hyperopen/websocket/user_runtime/subscriptions.cljs`
- `/hyperopen/src/hyperopen/websocket/user_runtime/refresh.cljs`
- `/hyperopen/src/hyperopen/websocket/user_runtime/fills.cljs`
- `/hyperopen/src/hyperopen/websocket/user_runtime/handlers.cljs`

## Environment

- Local app: `npm run dev` managed by browser-inspection
- Session id: `sess-1772853983552-0a819b`
- Browser: headless Chrome via browser-inspection
- Trade route: `http://localhost:8080/trade`
- Visual snapshot run:
  `/hyperopen/tmp/browser-inspection/inspect-2026-03-07T03-26-58-505Z-b82e9452/`

## Checks

### 1. Trade page loads and renders normally

Captured a desktop snapshot of the local trade route after the refactor.

- Screenshot:
  `/hyperopen/tmp/browser-inspection/inspect-2026-03-07T03-26-58-505Z-b82e9452/local/desktop/screenshot.png`
- Page metadata from browser eval:
  - `title`: `Hyperopen`
  - `href`: `http://localhost:8080/trade`
- Sample rendered parity ids:
  - `app-root`
  - `header`
  - `trade-root`
  - `trade-chart-panel`
  - `trade-orderbook-panel`
- Sample visible text:
  - `HyperOpen`
  - `Trade`
  - `Portfolio`
  - `Connect Wallet`
  - `BTC-USDC`

Result:

- The trade page loaded successfully.
- Core trade layout regions were present.
- No obvious hydration or rendering regressions were observed in the captured browser session.

### 2. Browser-runtime smoke: `subscribe-user!` suppresses already-covered streams

Using browser eval, I temporarily:

1. replaced `ws-client/runtime-view` stream state with one active `openOrders` subscription for a synthetic address,
2. stubbed `hyperopen.websocket.client.send_message_BANG_` to capture outbound messages,
3. invoked `hyperopen.websocket.user.subscribe_user_BANG_(address)`,
4. restored the original runtime-view and send function.

Observed outbound calls:

- `subscribe userFills`
- `subscribe userFundings`
- `subscribe userNonFundingLedgerUpdates`

Observed omission:

- no duplicate `subscribe openOrders`

Interpretation:

- The browser runtime confirms the refactor now reads canonical websocket runtime-view state before emitting subscription intents, instead of depending on a second `user-state` registry.

### 3. Browser-runtime smoke: per-dex sync diffs against runtime-view state

Using browser eval, I temporarily:

1. replaced `ws-client/runtime-view` stream state with one active `clearinghouseState` subscription for dex `dex-a`,
2. stubbed `hyperopen.websocket.client.send_message_BANG_` to capture outbound messages,
3. invoked `hyperopen.websocket.user.sync_perp_dex_clearinghouse_subscriptions_BANG_(address, [\"dex-a\" \"dex-b\"])`,
4. restored the original runtime-view and send function.

Observed outbound calls:

- `subscribe clearinghouseState dex-b`

Observed omissions:

- no duplicate `subscribe clearinghouseState dex-a`
- no incorrect unsubscribe while the active dex remained desired

Interpretation:

- The browser runtime confirms the new per-dex subscription diff logic is keyed off canonical websocket runtime state and only emits the missing subscription intent.

## Findings

No verified defects were found in this QA pass.

The local trade page still loads, and the refactor-specific browser smokes confirmed that user stream duplicate suppression now derives from canonical websocket runtime-view state without reintroducing regressions in the visible app shell.
