# `/info` Request Hotspot Baseline and WS Coverage (2026-03-05)

## Method

Baseline hotspots are derived from deterministic migration tests plus the new startup summary hotspot projection (`request-hotspots`) built from `:started-by-type-source`, `:rate-limited-by-type-source`, and `:latency-ms-by-type-source` telemetry.

Primary evidence:
- `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`
- `/hyperopen/test/hyperopen/websocket/user_test.cljs`
- `/hyperopen/test/hyperopen/startup/runtime_test.cljs`
- `/hyperopen/test/hyperopen/api_test.cljs`
- `/hyperopen/docs/qa/ws-migration-impact-validation-2026-03-05.md`

## Top 5 Hotspots (Prioritized)

| Rank | Request path (`type` + source family) | Burst evidence | WS subscription parity | Migration status |
|---|---|---|---|---|
| 1 | `frontendOpenOrders` (`order/mutation`, `websocket/user-fill-refresh`) | Legacy order submit path emitted 2 open-order refresh calls; user-ledger refresh emitted 1 more in fallback mode. | `openOrders` | WS-first live-stream gating implemented (`:order-fill-ws-first?`). |
| 2 | `clearinghouseState` (`order/mutation`, `websocket/user-fill-refresh`) | Legacy order submit path emitted 2 clearinghouse refresh calls; user-ledger refresh emitted 1 perp + 1 spot fallback call. | `webData2` (default user surface) | Default surface WS-first gating implemented; per-dex/spot remain bounded REST backstop. |
| 3 | `candleSnapshot` (`chart/timeframe` flows) | Timeframe/symbol interactions previously depended on repeated snapshot POSTs for active chart updates. | `candle` | WS-first candle stream implemented (`:candle-subscriptions?`) with cache-miss bounded REST backfill. |
| 4 | `userFills` (`startup/stage-a`) | Startup stage-A historically fetched this immediately for account bootstrap. | `userFills` | Stream-backed startup deferral implemented (`:startup-bootstrap-ws-first?`). |
| 5 | `userFundings` (`startup/stage-a`) | Startup stage-A historically fetched this immediately for account bootstrap. | `userFundings` | Stream-backed startup deferral implemented (`:startup-bootstrap-ws-first?`). |

## Non-Subscribable `/info` Surfaces

No direct WS parity remains for flows such as historical orders/funding history, portfolio summaries, and vault detail endpoints. These paths are now controlled by shared TTL + single-flight caching and inactive-route suppression.

References:
- `/hyperopen/src/hyperopen/api/request_policy.cljs`
- `/hyperopen/docs/runbooks/ws-migration-rollout.md`

## Operational Baseline Output

Startup summary logs now include:
- `request-stats`
- `request-hotspots` (top 5 `type+source` request paths)

This provides a repeatable baseline and regression signal for `/info` churn and rate-limit concentration after deployment.

