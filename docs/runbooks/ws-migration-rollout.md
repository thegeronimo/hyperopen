# WS Migration Rollout Runbook

## Scope

Rollout and support guidance for websocket-first `/info` migration controls:

- post-order/post-fill account refresh fanout,
- startup account bootstrap stream-backed fallback,
- candle migration canary path.

## Preconditions

- CI and local gates pass:
- `npm run check`
- `npm test`
- `npm run test:websocket`
- `npx shadow-cljs compile app`
- `npx shadow-cljs compile test`
- WS migration regression suite includes flag on/off coverage:
- `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`
- `/hyperopen/test/hyperopen/startup/runtime_test.cljs`
- `/hyperopen/test/hyperopen/core_bootstrap/chart_menu_and_storage_test.cljs`
- `/hyperopen/test/hyperopen/websocket/migration_flags_test.cljs`

## Flag Map

Default config lives in `/hyperopen/src/hyperopen/config.cljs` under `:ws-migration`.

- `:order-fill-ws-first?`:
  - `true`: skip REST refresh fanout when equivalent user streams are live.
  - `false`: force legacy REST refresh fanout.
- `:startup-bootstrap-ws-first?`:
  - `true`: use stream-backed delayed fallback during startup bootstrap.
  - `false`: force immediate startup REST fetches for stream-backed surfaces.
- `:candle-subscriptions?`:
  - `false`: keep current REST candle snapshot behavior.
  - `true`: keep one live `candle` websocket subscription aligned to active asset + selected timeframe, with bounded REST backfill only when cache is missing.
- `:auto-fallback-on-health-degrade?`:
  - `true`: if websocket health degrades, force flow-level REST fallback automatically.
  - `false`: keep flow flags active even under degraded health.

Optional runtime override path (for staged canary or emergency rollback without rebuild):

- store path: `[:websocket :migration-flags]`

Example emergency rollback override payload:

    {:order-fill-ws-first? false
     :startup-bootstrap-ws-first? false
     :candle-subscriptions? false
     :auto-fallback-on-health-degrade? true}

## Staged Canary Sequence

1. Deploy release with defaults (`order/startup ws-first enabled`, `candle migration disabled`).
2. Verify baseline telemetry:
   - `/info` rate-limit count trend is flat or improving.
   - no startup/account regression reports.
3. Canary candle migration only for a small cohort by setting `:candle-subscriptions? true` for that cohort.
4. Monitor chart correctness and backfill behavior:
   - timeframe switches still populate candle series.
   - no blank chart on active-asset switch.
5. Expand candle migration cohort gradually.
6. Keep `:auto-fallback-on-health-degrade? true` during rollout; only disable for targeted debugging.

## Health Guardrails

With `:auto-fallback-on-health-degrade? true`, migration flows automatically fall back when relevant websocket health degrades:

- order/fill and startup flows: transport non-live or degraded `:orders_oms`/`:account` groups.
- candle flow: transport non-live or degraded `:market_data` group.

Operator expectation:

- temporary websocket degradation should shift affected paths back to bounded REST fallback without deploy rollback.

## REST Hardening Policy (Non-Subscribable `/info`)

When websocket parity is unavailable, `/info` calls now use shared TTL request policy plus single-flight coalescing in `info_client`.

Current default TTL policy (ms) in `/hyperopen/src/hyperopen/api/request_policy.cljs`:

- `:perp-dexs` / `:spot-meta`: `60000`
- `:public-webdata2`: `30000`
- `:portfolio`: `8000`
- `:user-fees`: `15000`
- `:user-funding-history` / `:historical-orders` / `:user-non-funding-ledger`: `5000`
- `:vault-summaries`: `15000`
- `:user-vault-equities`: `5000`
- `:vault-details` / `:vault-webdata2`: `8000`
- `:predicted-fundings`: `5000`
- `:market-funding-history`: `15000`

Operational notes:

- Endpoint callers can override with `:cache-ttl-ms` per request.
- Force bypass is available with `:force-refresh? true`.
- Route/tab-inactive effects (funding comparison, vault detail/list, account order/funding history refresh) now skip fetch execution to reduce background churn.

## Monitoring and Signals

- `/info` request volume by request type/source (from request telemetry).
- `/info` 429 rate-limit counts and attribution.
- startup summary `request-hotspots` (top `type+source` request paths).
- websocket health projection statuses and gap-detected counters.
- chart blank-data reports during candle canary.
- Deterministic before/after validation report:
  - `/hyperopen/docs/qa/ws-migration-impact-validation-2026-03-05.md`
  - `/hyperopen/docs/qa/info-post-hotspot-baseline-2026-03-05.md`

## Rollback

1. Disable all migration flows via runtime override or config:
   - set `:order-fill-ws-first? false`
   - set `:startup-bootstrap-ws-first? false`
   - set `:candle-subscriptions? false`
2. Keep `:auto-fallback-on-health-degrade? true`.
3. Verify order submit/cancel and startup paths return to legacy REST refresh behavior.
4. Capture telemetry and websocket health snapshots for incident follow-up.
5. Add regression test coverage for discovered failure mode before reattempting rollout.
