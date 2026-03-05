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
  - `true`: enable candle migration canary behavior (skip redundant REST fetch when candle cache is present; keep REST backfill when cache missing).
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

## Monitoring and Signals

- `/info` request volume by request type/source (from request telemetry).
- `/info` 429 rate-limit counts and attribution.
- websocket health projection statuses and gap-detected counters.
- chart blank-data reports during candle canary.

## Rollback

1. Disable all migration flows via runtime override or config:
   - set `:order-fill-ws-first? false`
   - set `:startup-bootstrap-ws-first? false`
   - set `:candle-subscriptions? false`
2. Keep `:auto-fallback-on-health-degrade? true`.
3. Verify order submit/cancel and startup paths return to legacy REST refresh behavior.
4. Capture telemetry and websocket health snapshots for incident follow-up.
5. Add regression test coverage for discovered failure mode before reattempting rollout.
