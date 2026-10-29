# Vol Range live fills: WebSocket order and fill evidence, monitoring freshness, resync before placement, and shared fill records

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. It is maintained in accordance with `/hyperopen/docs/PLANS.md` and the detailed template in `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Vol Range places a ladder of resting post-only spot orders (a "grid" in code, a "ladder" in the UI) and shows it in the Vol ladders tab of the trade dock. Today the dock only learns about fills and order changes from REST reads: a 15-second background reconciliation while the page is visible, the Refresh status button, and the sync that runs when the account or market changes. A fill can therefore take up to 15 seconds to appear, and nothing notices when the browser lost its live connection or was asleep, so a user could place a new ladder while fills that happened during the gap are still unknown to the app.

After this plan lands, in a dev build that includes placement:

- Fills and open-order changes that the app already receives over its existing account WebSocket streams (`userFills`, `openOrders`) are fed to the matching ladders immediately, from the raw rows, before the display list truncates or the optimistic-cancel filter prunes them. A fill shows in the dock's "Execution detail" within a second, and the same fill later read over REST is never counted twice.
- The dock shows whether live monitoring is on ("Live updates on"), paused ("Live updates paused while reconnecting…"), or catching up ("Catching up on fills and orders…").
- When the connection drops, the account streams are re-subscribed, the account changes, or the tab was suspended, monitoring is marked stale. When the streams are back, the app reconciles every open ladder (journal, raw open orders, balances, order statuses and the missed-fill window) before it lets a new placement or repair through. Placement is refused with a clear message while that is pending, both at the click and at the final pre-send guard. Cancel and Refresh status are never blocked.
- Every raw `userFills` row is offered, as a spec 9.1 envelope, to registered consumers, and one shared pure normalizer (`fill-records/normalize-fill`) turns a WebSocket or REST fill row into the canonical fill record with the same fill key the ladders use. The Reporting item builds its durable ledger, coverage and campaign accounting on these two seams; this plan writes no ledger store of its own (integration decision, see Decision Log).

How to see it working: run the focused unit build (`npm run test:vol-range`) and the WebSocket suite; then run the new Playwright spec `tools/playwright/test/vol-range-live.spec.mjs`, which owns the page's WebSocket through a simulator, places a ladder against the debug exchange simulator, pushes a `userFills` frame and sees the dock update with no REST fill read in between, drops the socket and sees "Live updates paused", sees placement refused while catching up, and sees "Live updates on" again after the resync, with the fill made during the gap shown exactly once.

Hard safety rules for everyone executing this plan: this feature places real-money orders. No agent, script or test may ever place, cancel or modify a live order or call the real exchange endpoint; unit tests use fakes and the simulator in `test/hyperopen/vol_range/simulator.cljs`, Playwright uses the debug exchange simulator and `page.route`/`page.routeWebSocket` stubs. Never `git push`, never `git pull --rebase`, never a bare `git stash` (use a WIP commit instead), and no attribution lines in commit messages. Respect namespace size limits by splitting namespaces, never by raising an exception ceiling.

## Context References

Public refs:
- Direct user request in this session (2026-10-28): plan and then implement the next Vol Range items through a workflow ("once you have the plan in place, you can just immediately move to a workflow that implements the plans … report back to me once it's implemented for testing"). This plan is the live WebSocket fills item.

Repo artifacts:
- Product spec: `/hyperopen/docs/product-specs/vol-range-implementation-spec.md` (HO-SPEC-014 v2.1). Sections in scope: 9 (9.1 event envelope, 9.2 fill identity and retention, 9.3 reconnect and suspended browser, 9.4 rate policy), plus the parts of 7.4 (durable fills and deduplication keys), 8.1 step 8 (final guard), 11.2 (stale data source flag) and VR-14, VR-18, VR-19 that this work touches.
- Parent ExecPlan (A0 preview, still active): `/hyperopen/docs/exec-plans/active/2026-10-21-vol-range-release-a0-preview.md`.
- Completed stage plans this builds on: `/hyperopen/docs/exec-plans/completed/2026-10-27-vol-range-execution-foundations.md`, `/hyperopen/docs/exec-plans/completed/2026-10-27-vol-range-order-lifecycle.md`, `/hyperopen/docs/exec-plans/completed/2026-10-27-vol-range-local-placement.md`.
- ADR: `/hyperopen/docs/design-docs/vol-range-execution-coordination.md` (this plan adds section 9 to it).
- Runbook: `/hyperopen/docs/runbooks/vol-range-local-money-test.md`.
- Boundaries: `/hyperopen/src/hyperopen/websocket/BOUNDARY.md`, `/hyperopen/docs/BROWSER_STORAGE.md`.

Local scratch refs (non-authoritative):
- None.

## Progress

- [x] (2026-10-28) Read spec section 9 and related sections, the lifecycle, executor, effect adapter, watcher, dock, journal, IndexedDB platform, WebSocket user runtime, health projection and runtime reducer; wrote this ExecPlan.
- [x] (2026-10-28) Stage 1: pure evidence envelope, routing and fill identity (`vol_range/application/live_evidence.cljs`). `fills-envelope`, `open-orders-envelope`, `route`, `apply-envelope` plus the public `informative-rows`; `lifecycle-evidence/fill-key` canonicalizes time and tid through a private `int-text`; synthetic frames in `test/hyperopen/vol_range/live_fixtures.cljs`; `live_evidence_test.cljs` (16 tests); `live_evidence.cljs` classified in `a0_boundary_test`.
- [x] (2026-10-28) Stage 2: monotonic store merge for ladders in the execution adapter. New pure `vol_range/application/grid_store.cljs` (`merge-grid-into-state`, `merge-account-grids`), classified in `a0_boundary_test`; the adapter's `put-grid` delegates to it (so `finish!`, `reconcile-open!` and the sync's recovered grids all merge) and `sync-once!` uses `merge-account-grids`; `reconcile-open!` resolves to the vector of `reconcile!` results. Tests: `grid_store_test.cljs` (2 tests) and the new `runtime/effect_adapters/vol_range_execution_test.cljs` (3 tests, each shown to fail against the old replace-wholesale code).
- [x] (2026-10-28) Stage 3: raw user-stream taps in the account runtime (no Vol Range behavior yet). `user-fills-handler` and `open-orders-handler` gained a two-arity `(handler store {:on-raw-message f})` whose guarded tap runs first for every message on the channel (before the live-address filter, empty-snapshot skip, `novel-fills`/200-row truncation and cancel-guard pruning); `websocket.user/init!` gained `(init! store {:on-raw-user-fills :on-raw-open-orders})`; startup passes `(vol-range-live-ingress/user-stream-taps)`, which returns `{}` in this stage. Tests: 3 new in `handlers_test.cljs`; the 2 `init!` tests live in the new `test/hyperopen/websocket/user_taps_test.cljs` (see Decision Log).
- [x] (2026-10-28) Stage 4: live ingress applies WebSocket evidence to ladders in memory. `live_ingress.cljs` now has `install!` (called from the Vol Range slot in `app/bootstrap.cljs`), `observe-user-fills!`, `observe-open-orders!` and `user-stream-taps` (the two taps only when the preview and placement-built flags are both on); one store write per frame through `grid-store/merge-grid-into-state`, skipped when nothing applied; Checking-ladder and guarded-absence hints dispatch `:actions/reconcile-open-vol-ladders` at most once per 5 s per account. Simulator helpers `ws-fills-frame` and `ws-open-orders-frame`; `live_ingress_test.cljs` (11 tests); `live_ingress.cljs` classified in `a0_boundary_test`.
- [x] (2026-10-28) Stage 5: pure monitoring model (`vol_range/application/monitoring.cljs`). `initial`, `observe`, `on-fills-snapshot`, `on-tick`, `on-resync-started`, `on-resync-finished`, `resync-due?`, `placement-blocker`, `view-blocker` (added here, not in Stage 8) and `revision`; `contracts/live-monitoring-policy` (the ingress's `hint-min-interval-ms` now reads it); `monitoring_test.cljs` (16 tests, including a source scan and a run with the platform clock and random source stubbed to throw). The namespace requires only `contracts` and the pure `websocket.health-projection`, so it needs no A0 boundary classification.
- [ ] Stage 6: monitoring wiring and the resync operation.
- [ ] Stage 7: placement and repair gate on monitoring, plus the Playwright WebSocket simulator.
- [ ] Stage 8: dock and review UI for live monitoring; narrower order-form slice.
- [ ] Stage 9: shared fill record normalizer and the fills consumer seam for Reporting (replaces the earlier ledger-store stage; see Decision Log).
- [ ] Stage 10: live Playwright spec, ADR and runbook updates, full gates, move this plan to completed.

## Surprises & Discoveries

- Observation: the lifecycle already has idempotent fill ingestion and a durable fill key; only the WebSocket path is missing.
  Evidence: `hyperopen.vol-range.application.lifecycle-evidence/ingest-fills` keys fills with `fill-key` = network | account | coin | time | tid | (oid or CLOID) and skips keys already in `(:fills grid)`; `leg-quantities` floors the ingested sum by the venue-reported filled size, so an order response, a REST window and a stream increment can never double count.
- Observation: the display path truncates and de-duplicates in ways that are wrong for a strategy ledger.
  Evidence: `websocket/user_runtime/handlers.cljs` keeps only 200 fills (`upsert-seq`), `fills.cljs` `novel-fills` de-duplicates by `tid` alone across all coins, and `open-orders-handler` prunes optimistically canceled orders (`cancel-guard/prune-open-order-payload`) before storing.
- Observation: the execution adapter replaces store copies of grids wholesale (`put-grid` assoc, and `sync-once!` resets the whole `:grids` map). Once WebSocket evidence is applied in memory, a reconciliation that started earlier would erase it from the store when it finishes.
- Observation: WebSocket health is projected to `[:websocket :health]` with `:transport {:state :freshness :last-close {:code :reason :was-clean? :at-ms}}` and per-subscription `:streams`. After a reconnect the runtime replays subscriptions with `:first-payload-at-ms nil`, so a stream is not "usable" (`health-projection/topic-stream-usable?`) until its first payload after the reconnect. `userFills` and `openOrders` have no stale threshold, so they show `:n-a` (usable) once a payload arrived.
- Observation: user streams follow `account-context/live-user-stream-address`, which is the spectated or trader-portfolio address when those are active, and is nil on the trader portfolio route. Vol Range ladders belong to the active trading account, so in those situations the trading account has no live stream at all.
- Observation: the runtime sends no pings. Transport freshness is `:live` while frames arrive within 10 s (or when no subscribed stream expects traffic). A WebSocket simulator in Playwright must push a frame at least every few seconds or the transport goes `:delayed`.
- Observation: the committed Playwright specs live under `tools/playwright/test/` (not `tests/playwright/`): `vol-range.spec.mjs` (A0 preview) and `vol-range-execution.spec.mjs` (placement against the debug exchange simulator, `**/info` answered by a stateful `world` stub).
- Observation (to verify in Stage 1 and during the local money test, not yet confirmed): whether Hyperliquid `userFills` WebSocket frames carry `data.user`, whether fill rows carry `cloid`, whether the default-dex `openOrders` frame includes spot orders with `cloid`, `origSz` and `oid`, and whether `time`/`tid` are numbers in both WebSocket and REST rows. The plan is written so that each answer only changes coverage, never safety (see Decision Log).

- Observation (Stage 1): a leg whose placement is `:unknown` has no oid yet, so a stream row can only identify it by CLOID. An `openOrders` row or a fill row without `cloid` cannot resolve an unknown leg; whether the venue's stream rows carry `cloid` (open question above) therefore decides how fast the stream resolves Checking ladders, never safety.
  Evidence: `presence-resolves-an-unknown-leg-test` and `checking-ladders-are-reported-test` in `test/hyperopen/vol_range/application/live_evidence_test.cljs`.
- Observation (Stage 1): `lifecycle-evidence/ingest-fills` and `apply-orders-snapshot` end with `refresh-operations`, which can return a different grid even when no row applied (an operation still marked `:unresolved` whose legs all resolved). `apply-envelope` therefore returns the input grid untouched when no informative row is left, and also when the result is `=` to it, so "nothing applied" is always `identical?`.
- Observation (Stage 1): the Repair Playwright test "repairing the rejected buy sends one new order and the ladder becomes live" failed once at its last assertion ("Repair sent. 7 of 7 orders active.") in a first run of the three Vol Range specs, then passed in a full rerun (17/17) and in `--repeat-each=4` (20/20). Nothing from Stage 1 runs in the app yet. The likely cause is the known 15 s background reconcile writing `{:op :reconcile-open :status :ok}` to `[:vol-range :execution :last]` between the repair and the assertion, which Stage 6 removes.
  Evidence: the build-order plan's Surprises ("every successful background reconcile writes ... `:last`") and the spec's assertion order (counts first, message last).
- Observation (Stage 2): the simulator's account `0xabc` is not a 40-hex address, so `account-context/active-trading-account-address` (which normalizes and validates) returns nil for a store built around it, and an adapter-level `:sync` would see no trading account. `vol_range_execution_test.cljs` stubs that one function to the simulator account for the duration of each test (restored in `finally`); the pure merges compare addresses lower-cased without validation, like `lifecycle-evidence`.
  Evidence: the first run of `sync-keeps-store-evidence-and-drops-other-accounts-test` left `:grids` empty.

- Observation (Stage 3): `user-ws/init!` is now multi-arity and startup calls its two-arity, which ClojureScript compiles to a direct `cljs$core$IFn$_invoke$arity$2` call. A test that `with-redefs` the var with a one-arity or variadic `fn` therefore throws "arity$2 is not a function". `core_bootstrap/runtime_startup_test.cljs` now stubs it with `(fn ([_] nil) ([_ _] nil))`. Later stages that make a redefined function multi-arity need the same care.
  Evidence: the first `npm test` run after the change had 1 error in `initialize-remote-data-streams-keeps-full-selector-expansion-demand-driven-test`.

- Observation (Stage 4): the Repair Playwright test's final "Repair sent. 7 of 7 orders active." assertion failed again with the dock showing "Ladder ok." (first run 16/17; full rerun 17/17). An A/B run with `user-stream-taps` forced to `{}` failed the same way 3 of 6 times, so the taps are not the cause: the test waits up to 15 s for "7 live", long enough for the 15 s background reconcile to overwrite `[:vol-range :execution :last]`. Stage 6 removes that overwrite. Until Stage 7 installs the WebSocket simulator the Playwright page's account streams carry no frames for the fixture ladder, so the ingress applies nothing there.
  Evidence: `error-context.md` of each failed run shows `status: Ladder ok.`; the served `live_ingress.js` was checked before and after the A/B run.
- Observation (Stage 5): the Repair Playwright test's final "Repair sent. 7 of 7 orders active." assertion failed once more with "Ladder ok." shown (first run 16/17, full rerun 17/17). `monitoring.cljs` has no caller in the app yet, and the only app-reachable change (the ingress hint interval read from `live-monitoring-policy`) keeps the value 5000, so this is again the background-reconcile overwrite of `:last` that Stage 6 removes.
  Evidence: `error-context.md` of the failed run shows `status: Ladder ok.`.
- Observation (Stage 5): ticking a failed resync straight to its retry time (no ticks between) is itself a tick gap over 45 s for the 60 s and 120 s backoffs, which starts a new epoch and resets the attempt count. The watcher's 15 s ticks prevent that in the app; `monitoring_test.cljs` steps 15 s ticks through each backoff.


## Decision Log

- Decision: WebSocket evidence is applied to the in-memory (store) copy of a ladder only. It is not written to the grid journal from the WebSocket path. Durable grid state continues to come from the existing REST reconciliation, placement, cancel and resync flows, which already persist under the per-grid lock with a monotonic merge.
  Rationale: the REST fills window (`executor/reconcile!`) re-reads everything since `:fills-through-ms` minus 60 s, and `:fills-through-ms` advances only on complete REST reads, so nothing the stream delivered can be lost across a reload. Persisting from the stream would add a journal `load-all!` plus lock acquisition per frame on the real-money path for no safety gain.
  Date/Author: 2026-10-28, Claude (planning agent).
- Decision: WebSocket open-order frames are presence-only evidence (`:complete? false`). Absence of a known resting leg from a frame never changes the leg; it only triggers a rate-limited REST reconciliation ("absence hint"), and only for coins the same connection has already shown in an `openOrders` frame for that account.
  Rationale: spec 9.1 (unknown completeness is false; never union stale REST orders with fresh stream orders). The coin guard prevents a reconcile storm if the default-dex frame turns out not to include spot orders.
  Date/Author: 2026-10-28, Claude.
- Decision: WebSocket open-order rows are applied only when informative and only in two cases: (a) the leg's placement is `:unknown` (presence proves acceptance), or (b) the leg is already `:accepted` and `:resting` and the row's remaining size is strictly smaller than the leg's current `:observed-remaining` (or the leg has none). A frame never touches a leg whose order state is `:unconfirmed` (absent from a complete REST snapshot; only a status lookup decides it), never raises a remaining size, and never touches `:pending` or terminal legs. Repeats are dropped before touching the store.
  Rationale: every `openOrders` frame would otherwise bump `:last-evidence-at-ms` on every resting leg, repaint the dock, and keep `lifecycle/fills-covered?` false between reconciliations. A frame generated before a fill but delivered after the REST snapshot that missed the order must not resurrect an `:unconfirmed` leg to Live or move its remaining backward (spec 9.1: unknown completeness is false; stale stream rows are not current state). (Integration review tightening.)
  Date/Author: 2026-10-28, Claude.
- Decision: The durable fill key stays `lifecycle-evidence/fill-key` (network | lowercased account | coin | time | tid | oid-or-CLOID). Only its inputs are canonicalized: time and tid render as integer strings whether they arrive as numbers or numeric strings. Existing keys for numeric inputs are byte-identical.
  Rationale: changing the key format would double count fills already stored in journaled grids. Spec 9.2's "plus order/side where needed" is met by the oid component.
  Date/Author: 2026-10-28, Claude.
- Decision: The message address is required for routing. When a frame has no address field, the envelope falls back to the live user-stream address at receipt time and records `:address-source :subscription`; evidence still must match the ladder's account, coin and a known oid or CLOID.
  Rationale: venue oids are unique, so a misattributed late frame cannot match another account's legs; refusing all address-less frames would leave monitoring stuck if the venue omits the field.
  Date/Author: 2026-10-28, Claude.
- Decision: Monitoring is per active trading account and lives at `[:vol-range :execution :monitoring]`. It is `:live` only after (a) the transport is live and both `userFills` and `openOrders` are usable for that account, (b) a `userFills` snapshot for that account was received after the last socket close, and (c) a resync completed with complete reads in the current monitoring epoch. Any disconnect, socket close, re-subscription snapshot, account change, loss of the account's streams, or a watcher tick gap over 45 s starts a new epoch.
  Rationale: spec 9.3; subscribing first and back-filling second guarantees no gap between the REST window and the stream.
  Date/Author: 2026-10-28, Claude.
- Decision: Placement and repair are refused while monitoring is not `:live` (codes `:monitoring/stale`, `:monitoring/resync-required`, `:monitoring/suspended`), through the existing single `refusal` function in `executor_place.cljs`, which runs at the click and again inside the final pre-HTTP guard. Cancel, Refresh status and sync never consult monitoring.
  Rationale: spec 9.3 "reconcile … before new placement"; one refusal seam covers place, repair and the final guard; spec 7.4/10 require cancellation to stay available.
  Date/Author: 2026-10-28, Claude.
- Decision: Transport freshness is taken as the existing health projection reports it (`topic-stream-usable?` requires a live transport). A `:delayed` transport therefore pauses monitoring and costs one resync when it recovers.
  Rationale: a silent socket is a real monitoring gap; the cost is one bounded REST pass. Revisit only if the local money test shows churn.
  Date/Author: 2026-10-28, Claude.
- Decision: No new timer, WebSocket or registry. The suspension detector reuses the execution watcher's existing 15 s interval (now recording a tick even while hidden, but dispatching REST work only while visible) plus one `visibilitychange` listener that runs a tick immediately on return. Resync retries ride the same tick with backoff 15, 30, 60, 120 s (cap) and ±20 % jitter from `platform/random-value`.
  Rationale: spec 9.3 and 9.4.
  Date/Author: 2026-10-28, Claude.
- Decision: The 15 s background reconciliation stays exactly as it is (fallback). WebSocket evidence adds REST work only through the absence hint and when evidence touches a ladder in Checking status, at most once per 5 s per account, through the existing busy-guarded `:actions/reconcile-open-vol-ladders`.
  Rationale: spec 9.4 (prioritize reconciliation, avoid per-rung loops); the task requires keeping the 15 s fallback.
  Date/Author: 2026-10-28, Claude.
- Decision (superseded by the integration review, 2026-10-28): this plan no longer creates a fill ledger store. The planned `vol-range-fills` store (IndexedDB version 9), its coverage records and the optional `:ledger!` executor dependency are dropped. The single durable ledger is Reporting's `vol-range-ledger` store (version 9), created in Reporting stage R2, which owns fill, ledger-update, coverage and balance-check records and the only coverage model (complete REST reads, per base token, with the retention-count rule). This plan instead ships (Stage 9) the shared pure normalizer `hyperopen.vol-range.application.fill-records/normalize-fill` and a fills consumer seam in `live_ingress.cljs`.
  Rationale: two stores holding the same fills under two record shapes and two coverage models (per-window here, merged per-account intervals there) would double the real-money-adjacent write paths and invite double counting or contradictory coverage. Coverage written from a grid's REST window would also claim account-wide completeness while persisting only that grid's coin, which Reporting cannot use for other pairs that share the base token. The arguments for keeping fills out of the grid journal (load-all on every grid write, quota failures must not block placement or cancel, fills that belong to no ladder) still hold and now apply to Reporting's store. Nothing is added to `executor/reconcile!`.
  Date/Author: 2026-10-28, Claude (integrator).
- Decision: REST reads keep their existing provenance maps (`reads.cljs`). The spec 9.1 envelope is introduced for WebSocket evidence; `live-evidence/apply-envelope` dispatches it into the same lifecycle-evidence functions the REST paths use. `:account-event-revision` is `"<epoch>:<seq>"` for diagnostics and race detection in tests; reservation release keeps its existing request-time barrier rule.
  Rationale: wrapping reviewed REST reads in a new envelope is churn on the real-money path with no behavior change. Deliberate deviation from 9.1's "normalize incoming evidence" for REST.
  Date/Author: 2026-10-28, Claude.
- Decision: The stale-data flag of spec 11.2 is shown once at the top of the dock (one line with a non-color ⚠ cue), not as per-row attention.
  Rationale: monitoring is per account, not per ladder; seven identical warnings help nobody.
  Date/Author: 2026-10-28, Claude.
- Decision: The `orderUpdates` stream is not added (optional in spec 9).
  Date/Author: 2026-10-28, Claude.
- Decision (integration review): the monotonic store merge lives in a new pure namespace `hyperopen.vol-range.application.grid-store` (`merge-grid-into-state [state grid]`, `merge-account-grids [state account journal-grids]`), used by the effect adapter and by `live_ingress.cljs`. `vol_range/infrastructure/*` never requires `hyperopen.runtime.effect-adapters.*`.
  Rationale: the adapter pulls in delivery, signing and the action coordinator; the ingress must not depend on them, and the merge is pure.
  Date/Author: 2026-10-28, Claude (integrator).
- Decision (integration review): background operations (`:sync`, `:reconcile-open`, `:resync`) no longer overwrite `[:vol-range :execution :last]` when they succeed; `finish!` writes `:last` for them only when the status is `:error` or `:journal-unavailable`. The monitoring line reports background state instead.
  Rationale: today every 15 s reconcile writes `{:op :reconcile-open :status :ok}` and the dock's polite live region announces "Ladder ok." (spec 11.4: do not announce ticks), and it erases the outcome of the user's last placement, cancel or repair. `:resync` would make this worse.
  Date/Author: 2026-10-28, Claude (integrator).
- Decision (integration review): `src/hyperopen/views/trade_view.cljs` is exactly 500 lines with no size exception, so Stage 8's slice change is net zero lines there: remove `:vol-range` from `order-form-view-state-keys` and add one `(vol-dock/order-form-slice state)` line to the `merge` in `order-form-view-state`. The helper lives in `views/trade/vol_range/dock.cljs`. It keeps everything under `:vol-range` except `:execution`, which it narrows to `:enabled? :max-ladder-usdc :busy :last` plus `:monitoring` WITHOUT `:last-tick-ms` (else the ticket repaints on every 15 s tick). The review button uses a view-level blocker that ignores the tick gap; the executor's `refusal` still enforces it with real time. (Replace stage P3 later extends the helper with the grid under update.)
  Date/Author: 2026-10-28, Claude (integrator).
- Decision (integration review): placement and repair stay refused while monitoring is not `:live`, including while disconnected. This is spec 9.3 ("On resume, reconcile ... before new placement"), not a product choice, so it is not an open question. The refusal copy says the orders already on the exchange are unaffected and Cancel stays available.
  Date/Author: 2026-10-28, Claude (integrator).
- Decision (integration review): this plan runs as stages 6-15 of the global order in `/hyperopen/docs/exec-plans/active/2026-10-28-vol-range-build-order.md`, after Repair (stages 1-5) and before Replace and Reporting. Repair stage R5 already created `tools/playwright/support/vol_range_world.mjs` with a shared `bootstrap`; Stage 7 installs the WebSocket simulator there, so every Vol Range spec (execution, repair-replace, live, report) gets it.
  Date/Author: 2026-10-28, Claude (integrator).

- Decision (Stage 1): a fill row is not routed to a leg whose placement is still `:pending` (sent, no HTTP response yet). The plan's routing rule excluded pending legs only for open-order rows; Stage 4's acceptance ("a fill arriving before the placement's HTTP response ... is not applied") needs the same for fills. The fill is counted once afterwards by the reconciliation that follows the response (the REST fills window re-reads it) or by the next stream frame.
  Rationale: the in-flight placement owns the leg until its delivery is recorded; VR-14 still holds through the shared fill key.
  Date/Author: 2026-10-28, Claude (live-S1).
- Decision (Stage 1): an open-order row is informative for an accepted, resting leg only when its `:sz` is strictly below the leg's EFFECTIVE remaining (`(:remaining (lifecycle/leg-quantities grid leg))`, the minimum of original minus filled and `:observed-remaining`), not merely below `:observed-remaining` "or when it has none". A row equal to the current remaining (every leg of an untouched ladder in every frame) is a repeat and is dropped, and a stream row from before a stream fill can never look informative.
  Rationale: tightens the plan's rule without changing its intent (never raise a remaining size, never bump evidence on repeats).
  Date/Author: 2026-10-28, Claude (live-S1).
- Decision (Stage 1): `route` reports `:order-coins` (and `:absent`) only for default-dex (`:dex` nil) open-order frames; a builder-dex frame says nothing about spot orders, so its coins must not arm the Stage 4 absence guard. Addresses are compared lower-cased and trimmed, like `lifecycle-evidence` (no 40-hex validation, so simulator accounts such as `0xabc` route). `informative-rows grid envelope` is public: `route` uses it per grid and `apply-envelope` re-checks the rows against the grid it is given, which may have moved on between routing and the store write.
  Date/Author: 2026-10-28, Claude (live-S1).
- Decision (Stage 2): `merge-account-grids state account journal-grids` takes ALL journal grids (`jr/grids records nil`) and filters them by account itself (lower-cased, trimmed), so the adapter's private `account-grids` helper is gone. A nil trading account yields no grids (before, it kept grids whose account was also nil, which never exist). The adapter keeps a private `put-grid` as a one-line delegate so every existing writer (`finish!`, `reconcile-open!`, the sync's recovered grids) merges without call-site churn. The merge order follows the plan: store write = `(merge-grids store-copy incoming)`, sync = `(merge-grids journal-copy store-copy)`.
  Rationale: one place decides which grids belong to the account; behavior for real (40-hex) accounts is unchanged apart from the merge.
  Date/Author: 2026-10-28, Claude (live-S2).

- Decision (Stage 3): a failing tap is logged through `hyperopen.telemetry/log!` once per failed call (channel name and error text), not once per session. `telemetry/log!` is dev-only, so a release build never logs, and a dev build that breaks a tap sees every failure. The tap receives `received-at-ms` from `platform/now-ms` at the handler, and the ingress stub stays out of the A0 boundary test's `execution-foundations` until Stage 4 gives it requires.
  Date/Author: 2026-10-28, Claude (live-S3).

- Decision (Stage 3): the plan put the `init!` tap tests in `test/hyperopen/websocket/user_test.cljs`, but that namespace sits exactly at its 781-line size exception. They live in the new `test/hyperopen/websocket/user_taps_test.cljs` (picked up by the `ws-test` ns-regexp) instead of raising the ceiling.
  Date/Author: 2026-10-28, Claude (live-S3).

- Decision (Stage 4): hint timing uses the tap's `received-at-ms` (not a clock read in the ingress), and a hint is also allowed when that time is earlier than the last hint (a clock that moved back never silences hints). The hint is dispatched through `platform/queue-microtask!`, as the execution watcher does, so the reconcile action never runs inside the WebSocket handler. Seen order coins are forgotten when `[:websocket :health :transport :last-close :at-ms]` or the trading account changes; the hint clock is reset on an account change only. The envelope revision is `"<epoch>:<seq>"` with epoch 0 until Stage 6 writes `[:vol-range :execution :monitoring :epoch]`. `reset-for-tests!` clears the ingress atoms for tests.
  Rationale: deterministic tests without stubbing time; the busy-guarded reconcile action already prevents overlapping reads.
  Date/Author: 2026-10-28, Claude (live-S4).
- Decision (Stage 4): the "fill before the placement's HTTP response" test uses the simulator: the frame is a no-op while the ladder is not in the store and while its leg is `:pending`; after the response and a REST reconcile, the same frame and a snapshot repeating it leave exactly one fill. The "reconcile finishing after a stream fill" test merges `executor/reconcile!`'s older copy through `grid-store/merge-grid-into-state`, which is exactly what the adapter's `put-grid` does, so the adapter is not driven from this namespace.
  Date/Author: 2026-10-28, Claude (live-S4).

- Decision (Stage 5): `observe` folds a connection change and a loss of usability into one transition, so a socket close that leaves the transport down starts exactly one new epoch (stale), and a close seen while the streams are already usable again starts one new epoch in `:resyncing` (`:reconnected`). A stale account with usable streams and a valid marker moves to `:resyncing` with reason `:catching-up`; usable streams without a valid marker keep `[:awaiting-streams]` (Stage 8 shows "Connecting live updates…" for both).
  Rationale: the plan's step-by-step reading would bump the epoch twice for one close; epochs only need to increase, but one bump per event keeps diagnostics and tests readable.
  Date/Author: 2026-10-28, Claude (live-S5).
- Decision (Stage 5): `on-fills-snapshot` treats a snapshot with a valid marker as a re-subscription while `:live` OR `:resyncing` (the plan said only `:live`). A resync already running when the stream was re-subscribed may have read its fills window before the stream gap ended, so it must not count.
  Rationale: safety tightening within the plan's intent (spec 9.3); it costs at most one extra resync per re-subscription.
  Date/Author: 2026-10-28, Claude (live-S5).
- Decision (Stage 5): `on-fills-snapshot` ignores a snapshot whose account differs from the monitoring map's account (plan), so Stage 6's ingress must run `observe` with the current trading account BEFORE `on-fills-snapshot` (and `observe` again after it to promote to `:resyncing`), else a snapshot that beats the watcher's microtask after an account change would be dropped and monitoring would wait for the next re-subscription.
  Date/Author: 2026-10-28, Claude (live-S5).
- Decision (Stage 5): stream usability is `health-projection/topic-stream-usable?` with `{:user account}` for both topics; a nil account is never usable (a nil selector value is a wildcard in the projection). `:streams-elsewhere` is reported only when a topic has no subscription for the account AND a subscribed stream of that topic exists for another user, so a first load before the subscriptions are sent shows `:streams-not-ready`, not "another account is shown".
  Date/Author: 2026-10-28, Claude (live-S5).
- Decision (Stage 5): `on-resync-finished` counts only while that epoch's resync is `:running` (a duplicate finish or one never started is ignored); `placement-blocker` checks account, then the tick gap (a missing `:last-tick-ms` fails closed as `:monitoring/suspended`), then status. Jitter is clamped to the policy's plus or minus 0.2 and `:next-at-ms` is rounded to an integer. A retry keeps its attempt count; a new epoch (suspension, reconnect, re-subscription) resets it to 0. `view-blocker` ships in this stage.
  Date/Author: 2026-10-28, Claude (live-S5).

## Outcomes & Retrospective

- Stage 1 (live-S1, commit `feat(vol-range): normalize WebSocket order and fill evidence for ladders`): new pure `live_evidence.cljs` (envelopes, routing, application) and canonical fill-key inputs; numeric keys are byte-identical (asserted). Complexity: one new pure namespace under 250 lines that reuses `lifecycle-evidence` for every state change, so the WebSocket path adds no second set of leg rules. Gates and Playwright results are recorded in the build-order plan's Outcomes.
- Stage 2 (live-S2, commit `fix(vol-range): merge ladder copies in the store instead of replacing them`): no writer of `[:vol-range :execution :grids]` replaces a copy wholesale any more; a reconcile, cancel or placement that finishes with an older copy keeps evidence only the store holds, and a journal sync keeps it for the account's journal grids while still dropping other accounts' ladders. Pure merge in under 60 lines; the adapter shrank its own filtering. Gates and Playwright results are recorded in the build-order plan's Outcomes.
- Stage 3 (live-S3, commit `feat(websocket): raw evidence taps for user fills and open orders`): the account runtime now offers every raw `userFills` and `openOrders` message to an optional tap before any display filtering, truncation or pruning, guarded so a throwing tap changes nothing on the display path (asserted against the untapped handler). No Vol Range behavior yet: startup injects the empty taps from the new `live_ingress.cljs` stub. Gates and Playwright results are recorded in the build-order plan's Outcomes.
- Stage 4 (live-S4, commit `feat(vol-range): feed live fills and open orders to ladders`): in a build that includes placement, every raw `userFills` and `openOrders` message now reaches the store's ladders in memory (VR-14 once-only counting, VR-18 routing, VR-19 untruncated rows), with no journal write, REST read or signing from the stream path; stream evidence adds REST work only through the rate-limited hints. A release build injects no taps (asserted). Under 180 lines in the ingress. Gates and Playwright results are recorded in the build-order plan's Outcomes.
- Stage 5 (live-S5, commit `feat(vol-range): live monitoring model`): the pure model that decides whether live stream evidence is complete enough for placement now exists, with epochs, the 45 s suspension rule, backoff 15/30/60/120 s with bounded jitter and the `:monitoring/*` blockers. Nothing calls it yet (Stage 6 wires it). Under 270 lines; it reuses the health projection for stream usability instead of re-deriving it. Gates and Playwright results are recorded in the build-order plan's Outcomes.

## Context and Orientation

HyperOpen is a ClojureScript single-page trading client for the Hyperliquid exchange. Source lives under `/hyperopen/src/hyperopen/`, tests under `/hyperopen/test/hyperopen/`. State is one atom (the "store"). User interactions dispatch action vectors (`[:actions/x args…]`); action handlers are pure functions of state returning effect vectors; effect interpreters ("adapters") do I/O. Every action id must be registered in `/hyperopen/src/hyperopen/schema/runtime_registration/trade.cljs`, mapped to a handler in `/hyperopen/src/hyperopen/runtime/collaborators/vol_range.cljs`, and given an argument spec in `/hyperopen/src/hyperopen/schema/contracts/vol_range_action_args.cljs`; effect arguments are specified in `/hyperopen/src/hyperopen/schema/contracts/effect_args.cljs`. A missing registration throws at load ("drift detected") and is caught by `test/hyperopen/vol_range/registration_test.cljs`.

Terms used here:

- Ladder / grid: a set of Vol Range orders for one account and spot market. Code says grid; UI says ladder.
- Leg: one order of a grid, keyed by its CLOID (client order id, `0x484f5652` + 96 random bits). After the venue accepts it, the leg also knows its `oid` (venue order id, kept as a string).
- Evidence: anything the venue told us (an order response, an open-orders list, an order status, a fill). The lifecycle applies only positive evidence; absence never proves anything.
- Journal: the IndexedDB store `vol-range-journal` holding intents, send attempts and grid records. Written only through committed transactions.
- Reconcile: `executor/reconcile!` reads a raw account snapshot (balances and open orders), order statuses for unresolved legs, then the fills window, applies them, and persists the grid.
- Monitoring: this plan's per-account state saying whether live stream evidence is complete enough to allow new placement.
- Epoch: a counter that increases every time monitoring loses confidence (disconnect, re-subscription, suspension, account change). A resync result only counts for the epoch it started in.
- Resync: sync from the journal plus reconcile every open ladder of the account, run when monitoring is re-established.
- Fill record: the canonical normalized fill (Stage 9) that Reporting's durable ledger stores. The ledger store itself belongs to the Reporting plan.

Key existing files and what they do:

- `src/hyperopen/vol_range/application/lifecycle.cljs`: grid and leg model, `status`, `counts`, `leg-quantities`, `fills-covered?`, `note-fills-read`, reservation release rules.
- `src/hyperopen/vol_range/application/lifecycle_evidence.cljs`: `apply-orders-snapshot` (presence accepts and refreshes remaining; absence from a complete, later-requested snapshot makes a resting leg `:unconfirmed`), `apply-order-statuses`, `fill-key`, `ingest-fills`, delivery application.
- `src/hyperopen/vol_range/application/lifecycle_recovery.cljs`: `merge-grids stored ours`, a monotonic merge (membership, operations, fills and releases are unions; a leg keeps its most advanced evidence).
- `src/hyperopen/vol_range/infrastructure/executor.cljs`: `persist-grid!`, `refresh-grid!`, `placement-blockers`, `reconcile!` (with the private `read-fills!`), `recover!`.
- `src/hyperopen/vol_range/infrastructure/executor_place.cljs`: `place!`, `repair!`, the private `refusal` (execution flag, read-only, agent readiness, scope) used at the start and inside the final pre-HTTP guard.
- `src/hyperopen/vol_range/infrastructure/reads.cljs`: barrier reads, including `read-fills-window!` (`userFillsByTime`, pages of 2000, truncation and no-progress guards).
- `src/hyperopen/runtime/effect_adapters/vol_range_execution.cljs`: `[:effects/vol-range-execute op params]` with ops `:place :cancel :refresh :sync :reconcile-open`; `live-inputs` (what placement re-reads), `executor-deps`, `put-grid`, `finish!`, `sync!`, `reconcile-open!`.
- `src/hyperopen/vol_range/execution_actions.cljs`: pure busy-guarded actions; `sync-key`.
- `src/hyperopen/vol_range/infrastructure/execution_watcher.cljs`: installed from `app/bootstrap.cljs`; a store watch dispatches `:actions/sync-vol-ladders` on account or market change; a 15 s interval dispatches `:actions/reconcile-open-vol-ladders` while visible. Active only when `config/vol-range-preview-enabled?` and `config/vol-range-execution-built?` (dev build).
- `src/hyperopen/vol_range/application/dock_model.cljs` and `src/hyperopen/views/trade/vol_range/dock.cljs`: the Vol ladders tab; `review-commit` drives the review's Place button (`src/hyperopen/views/trade/vol_range/review.cljs`).
- `src/hyperopen/websocket/user.cljs` `init!` registers per-topic handlers from `src/hyperopen/websocket/user_runtime/handlers.cljs` through `ws-client/register-handler!`; it is called from startup through `:init-user-ws!` in `src/hyperopen/startup/collaborators.cljs` (483 lines; limit 500).
- `src/hyperopen/websocket/health_projection.cljs`: `topic-stream-usable?`, `topic-stream-subscribed?` over `[:websocket :health]`.
- `src/hyperopen/platform/indexed_db.cljs`: `app-db-version` 8, store list `app-store-names`, `put-many-committed!` (resolves on transaction complete), `get-all-json-strict!`. `open-db!` upgrades past the browser's version when a listed store is missing.
- `src/hyperopen/views/trade_view.cljs`: memoized panel slices; the order-form slice includes the whole `:vol-range` root today; the account-info slice gets `[:vol-range :execution]` through `vol-dock/view-state`.
- `test/hyperopen/vol_range/simulator.cljs`: deterministic venue, journal and clock for executor scenarios; `test/hyperopen/vol_range/a0_boundary_test.cljs` lists execution namespaces the A0 preview must never require.

Gates: `npm run setup:worktree` once per worktree; `npm run test:vol-range` (focused), `npm run test:websocket`, `npm test`, `npm run check` (34 gates including `lint:namespace-sizes`, `lint:namespace-boundaries`, docs checks), or `npm run gates` for all three required ones; `npm run test:runner:generate` after adding test namespaces (never hand-edit `test/test_runner_generated.cljs`).

## Plan of Work

The work goes bottom-up: pure evidence handling first, then the store-merge fix it depends on, then the WebSocket seam, then in-memory ingestion, then the monitoring model, its wiring and the placement gate, then UI, the shared fill records and consumer seam for Reporting, and finally browser coverage and documentation. Every stage is one commit that leaves `npm run gates` green.

### Stage 1: pure evidence envelope, routing and fill identity

Create `src/hyperopen/vol_range/application/live_evidence.cljs` (pure, no I/O, under 300 lines). It turns a raw WebSocket message into the spec 9.1 envelope, routes the envelope's rows to ladders, and applies routed evidence through the existing lifecycle-evidence functions.

Functions:

- `(fills-envelope msg {:received-at-ms :network :fallback-address :revision})` returns an envelope or nil. Rows come from `(:fills (:data msg))` or a vector `(:data msg)`; `:snapshot?` from `(:isSnapshot (:data msg))` or `(:isSnapshot msg)`. Address from the same fields `websocket.user-runtime.common/message-address` checks (copy the field list; do not require the websocket namespace from application code), normalized lowercase; when absent use `:fallback-address` and set `:address-source :subscription`; when both absent return nil. `:observed-at-ms` is the largest numeric row `:time`.
- `(open-orders-envelope msg opts)` the same for `openOrders`: rows from `:orders`, `:openOrders`, `:data` (inside the data map) or a vector; `:dex` from the data map (nil when blank); `:kind :orders-snapshot`; `:snapshot? true`; `:complete? false`.
- `(route envelope grids)` where `grids` is the store's `{grid-id grid}` map. Returns `{:by-grid {grid-id {:fills [rows] :orders [rows]}} :absent {grid-id #{cloid}} :checking #{grid-id} :order-coins #{coin}}`. A grid is a candidate only when its scope network equals the envelope network and its lowercased `:account-address` equals the envelope account. A fill row goes to a grid when its `:coin` equals the grid's scope coin and its oid (via `lifecycle/oid-string`) or lowercased `:cloid` belongs to one of the grid's legs. An order row goes to a grid on the same rule and only when informative: the matched leg's placement is `:unknown`, or it is `:accepted`/`:resting` and the row's `:sz` is strictly below its `:observed-remaining` (or it has none). Rows for `:unconfirmed`, `:pending` or terminal legs are never routed (see Decision Log). `:absent` lists, for open-order envelopes with nil `:dex`, CLOIDs of legs that are `:accepted` and `:resting` and whose oid and CLOID are missing from the rows, and whose `:last-evidence-at-ms` is at least 2000 ms older than `:received-at-ms`; the caller decides whether the coin guard allows acting on it. `:checking` is the set of routed grids whose `lifecycle/status` is `:checking`.
- `(apply-envelope grid envelope rows)` applies `rows` for that grid: fills through `evidence/ingest-fills` with `{:account-address :rows :received-at-ms}`; orders through `evidence/apply-orders-snapshot` with `{:account-address :requested-at-ms received-at :received-at-ms received-at :complete? false :open-orders rows}`. Returns the grid unchanged (identical) when nothing applied, so the caller can skip a store write.

In `lifecycle_evidence.cljs`, change `fill-key` to canonicalize time and tid (a private `int-text` that renders a finite number with `(.toFixed n 0)` after `Math.trunc`, passes a string of digits through, else `str`). Numeric inputs produce exactly the same key as today.

Add `test/hyperopen/vol_range/live_fixtures.cljs` (synthetic frames shaped after the documented Hyperliquid WebSocket and REST rows; mark them synthetic in the namespace docstring) with: a `userFills` snapshot frame for account `0xabc` on coin `SYN/USDC` (the simulator's coin) holding a partial fill of the grid's buy rung 0 (`:oid 9000`), a fill on another coin with the same `:tid`, and a same-coin manual fill on an unknown oid; an increment frame repeating the partial fill; the REST `userFillsByTime` row for the same partial fill (identical `:time`, `:tid`, `:oid`, string `:px`/`:sz`); a frame with 250 fills where the grid's fill is the last row; a frame without any address; an `openOrders` frame `{:user :dex "" :orders [...]}` with legs by CLOID and oid, and one with `:dex "xyz"`; and a 2000-row open-orders frame with no informative rows.

Tests in `test/hyperopen/vol_range/application/live_evidence_test.cljs`: envelope extraction for nested and vector payloads and both `isSnapshot` placements; address required or fallback with `:address-source`; routing ignores other accounts, other networks, other coins and unknown oids (VR-18); the cross-coin same-`tid` fill is routed only to its own coin; the 250th fill is routed (display truncation does not apply); snapshot plus increment plus REST row yields one fill and `leg-quantities` `:filled` equal to the single fill (VR-14); `fill-key` is equal for numeric and numeric-string time/tid and unchanged for the existing numeric fixture; open-order presence resolves an `:unknown` leg to accepted and resting; a frame listing an `:unconfirmed` leg leaves it `:unconfirmed`; a row with a larger `:sz` than the leg's `:observed-remaining` is ignored; a repeat frame is not informative and `apply-envelope` returns the identical grid; `:absent` respects `:dex`, the 2000 ms margin and terminal legs; the 2000-row frame produces empty `:by-grid`.

Add `src/hyperopen/vol_range/application/live_evidence.cljs` to the `execution-foundations` path set (and its namespace to the namespace list) in `test/hyperopen/vol_range/a0_boundary_test.cljs` in this stage: it requires `lifecycle` and `lifecycle-evidence`, which the A0 static scan rejects in unclassified files.

Acceptance: `npm run test:runner:generate`, `npm run test:vol-range`, `npm run gates` green. Commit: `feat(vol-range): normalize WebSocket order and fill evidence for ladders`.

### Stage 2: monotonic store merge for ladders

Create the pure `src/hyperopen/vol_range/application/grid_store.cljs` (add it to the A0 test's `execution-foundations`; it requires `lifecycle-recovery`):

- `(merge-grid-into-state state grid)`: `(update-in state [:vol-range :execution :grids gid] #(recovery/merge-grids % grid))` (stored = current store copy, ours = the incoming grid); nil grid returns state.
- `(merge-account-grids state account journal-grids)`: the new `:grids` map built from the journal's grids for `account`, each merged with the store's copy of the same grid id when that copy belongs to the same account (`(recovery/merge-grids journal-copy store-copy)`), dropping everything else (other accounts, ids not in the journal).

In `src/hyperopen/runtime/effect_adapters/vol_range_execution.cljs`, `put-grid` becomes `grid-store/merge-grid-into-state` and `sync-once!` uses `grid-store/merge-account-grids`; Stage 4 reuses the same namespace. Also in the adapter, `reconcile-open!` resolves to a vector of the `reconcile!` results (it currently resolves to nil), so Stage 6 can judge completeness; the `:reconcile-open` op still finishes with `{:status :ok}`.

Tests: new `test/hyperopen/vol_range/application/grid_store_test.cljs` for the pure merges, and new `test/hyperopen/runtime/effect_adapters/vol_range_execution_test.cljs` for the adapter (no such namespace exists yet; Repair stage R3 already added `runtime\\.effect-adapters\\.vol-range.*` to the focused build's ns-regexp): a reconcile result whose grid predates a fill applied to the store keeps the fill and the leg's terminal state after `finish!`; `sync-once!` on the same account keeps store-only evidence on journal grids and drops a previous account's grids; `reconcile-open!` returns one result per open grid. Run the lifecycle scenario and safety suites unchanged.

Acceptance: `npm run test:vol-range`, `npm run gates`. Commit: `fix(vol-range): merge ladder copies in the store instead of replacing them`.

### Stage 3: raw user-stream taps in the account runtime

Give the account runtime an explicit seam for raw evidence, without any Vol Range behavior yet.

- `src/hyperopen/websocket/user_runtime/handlers.cljs`: `user-fills-handler` and `open-orders-handler` gain a two-arity `(handler store {:keys [on-raw-message]})`. The tap is called with `(on-raw-message store msg received-at-ms)` first thing for a message on the right channel, before the live-address filter, before `(when (seq rows))` (empty snapshots matter for monitoring), before `novel-fills`/`upsert-seq` truncation and before `cancel-guard/prune-open-order-payload`. The call is wrapped in `try`/`catch`; a thrown error is logged once through `hyperopen.telemetry/log!` and never prevents the display path. One-arity behavior is unchanged.
- `src/hyperopen/websocket/user.cljs`: `init!` gains `(init! store {:keys [on-raw-user-fills on-raw-open-orders]})`; the one-arity calls it with `{}`.
- `src/hyperopen/startup/collaborators.cljs`: `:init-user-ws!` becomes `(fn [store] (user-ws/init! store (vol-range-live-ingress/user-stream-taps)))`. In this stage create `src/hyperopen/vol_range/infrastructure/live_ingress.cljs` with only `(defn user-stream-taps [] {})` and a docstring; Stage 4 fills it in. Keep `startup/collaborators.cljs` under 500 lines.

Tests: extend `test/hyperopen/websocket/user_runtime/handlers_test.cljs` and `test/hyperopen/websocket/user_test.cljs`: the tap receives the exact raw message (identical) for a snapshot with zero rows, for a 250-row increment (before truncation), for an `openOrders` frame containing a recently canceled oid (before pruning), and for a message addressed to another user (the display path still ignores it); a tap that throws leaves the display store update and toast behavior exactly as without a tap; with no tap configured all existing assertions hold. Check `test/hyperopen/app/startup_test.cljs` and `test/hyperopen/startup/runtime_test.cljs` still pass (they stub `:init-user-ws!`).

Acceptance: `npm run test:websocket`, `npm run gates`. Commit: `feat(websocket): raw evidence taps for user fills and open orders`.

### Stage 4: live ingress applies WebSocket evidence to ladders in memory

Fill in `src/hyperopen/vol_range/infrastructure/live_ingress.cljs` (under 250 lines):

- `(install! {:store :dispatch!})` keeps `dispatch!` in a `defonce` atom; called from the Vol Range slot in `src/hyperopen/app/bootstrap.cljs` (next to the execution watcher install).
- `(user-stream-taps)` returns `{:on-raw-user-fills observe-user-fills! :on-raw-open-orders observe-open-orders!}` when `config/vol-range-preview-enabled?` and `config/vol-range-execution-built?` are both true, else `{}`.
- `observe-user-fills!` and `observe-open-orders!` build the envelope (`:network (contracts/current-network)`, `:fallback-address (account-context/live-user-stream-address @store)` normalized, `:revision` from the ingress counter), call `live-evidence/route` with `(get-in @store [:vol-range :execution :grids])`, and in one `swap!` apply each routed grid's rows with `live-evidence/apply-envelope` and `grid-store/merge-grid-into-state` (skip the swap when every grid came back identical). `live_ingress.cljs` never requires the runtime effect adapter. Then: if `:checking` is non-empty, or the open-order envelope has `:absent` grids whose coin is in the connection's seen-order-coins set, dispatch `[[:actions/reconcile-open-vol-ladders]]` at most once per 5000 ms per account (ingress atom `{:account :last-close-at-ms :seen-order-coins #{} :last-hint-at-ms :seq}`; reset seen coins when `[:websocket :health :transport :last-close :at-ms]` changes). Record the envelope's `:order-coins` into seen coins after acting. Nothing here reads REST, signs, or writes IndexedDB.

Add the simulator helpers `ws-fills-frame` and `ws-open-orders-frame` to `test/hyperopen/vol_range/simulator.cljs` (build frames from the simulator's venue state, optionally only fills after a given tid).

Tests in `test/hyperopen/vol_range/infrastructure/live_ingress_test.cljs`: taps are `{}` when either config flag is off (release build has no effect); a fill frame updates the store grid's `:fills` and the dock model's side fills; another account's frame is a no-op; a repeat frame causes no store write (identity); a fill arriving before the placement's HTTP response (grid not yet in the store, or leg still `:pending`) is not applied, and after the response plus a REST reconcile the fill is counted once (VR-14, using the simulator); evidence on a Checking grid dispatches one reconcile and a second within 5 s dispatches none; absence hints require a seen coin; a reconcile finishing after a stream fill keeps the fill (Stage 2 merge). Add `live_ingress.cljs` to the `execution-foundations` path set in `test/hyperopen/vol_range/a0_boundary_test.cljs` (Stage 1 added `live_evidence.cljs`, Stage 2 `grid_store.cljs`).

Acceptance: `npm run test:vol-range`, `npm run test:websocket`, `npm run gates`. Commit: `feat(vol-range): feed live fills and open orders to ladders`.

### Stage 5: pure monitoring model

Create `src/hyperopen/vol_range/application/monitoring.cljs` (pure, under 300 lines) and add a versioned policy map to `src/hyperopen/vol_range/contracts.cljs`:

    (def live-monitoring-policy
      {:version 1
       :suspend-gap-ms 45000
       :hint-min-interval-ms 5000
       :resync-backoff-ms [15000 30000 60000 120000]
       :resync-jitter 0.2})

Functions (all take and return the monitoring map; time and jitter are arguments):

- `(initial account now-ms)` → `{:account-address a :status :stale :reasons [:awaiting-streams] :epoch 1 :stale-since-ms now :live-since-ms nil :fills-snapshot-at-ms nil :last-close-at-ms nil :last-tick-ms now :resync {:state :idle :attempt 0}}`.
- `(observe m {:account :health :now-ms})`: a different account replaces `m` with `(initial account now)` keeping a higher epoch. A changed `[:transport :last-close :at-ms]` is a connection change: record it and, unless already stale, start a new epoch in `:resyncing` with `:resync :required` and reason `:reconnected`. Then compute usability: transport live and `topic-stream-usable?` for both `"userFills"` and `"openOrders"` with `{:user account}`. Not usable → stale with reasons `[:transport-down]` (transport not connected/live), `[:streams-elsewhere]` (no subscription for this account, e.g. spectate or trader route) or `[:streams-not-ready]`; going stale from `:live`/`:resyncing` starts a new epoch and sets resync `:idle`. Usable and stale and a valid fills marker (received after the last close) → `:resyncing` with resync `:required`. Usable and already `:live` → unchanged.
- `(on-fills-snapshot m {:account :now-ms})`: ignored for another account. With a valid marker already present while `:live`, this is a re-subscription: new epoch, `:resyncing`, `:required`, reason `:resubscribed`. Always sets `:fills-snapshot-at-ms now`.
- `(on-tick m {:now-ms})`: a gap over `:suspend-gap-ms` since `:last-tick-ms` while `:live` or `:resyncing` starts a new epoch in `:resyncing` (`:required`, reason `:suspended`); a negative gap (clock moved back) is ignored. A `:failed` resync whose `:next-at-ms` passed becomes `:required`. Sets `:last-tick-ms`.
- `(on-resync-started m {:epoch :now-ms})` → `:running` with `:started-at-ms`, only when the epoch matches and the state is `:required`.
- `(on-resync-finished m {:epoch :account :ok? :error :now-ms :jitter})`: ignored unless account and epoch match. ok → `:live`, `:live-since-ms`, `:stale-since-ms nil`, `:reasons []`, resync `{:state :idle :attempt 0}`. not ok → resync `{:state :failed :attempt n :error e :next-at-ms (+ now (* backoff(n) (+ 1 jitter)))}` with backoff from the policy (index min(n-1, 3)).
- `(resync-due? m)` → true when status is `:resyncing` and resync state is `:required`.
- `(placement-blocker m {:account :now-ms})` → nil only when `m` exists, its account equals `account`, status is `:live`, and `now - :last-tick-ms` is at most `:suspend-gap-ms`; otherwise `:monitoring/suspended` (tick gap), `:monitoring/resync-required` (status `:resyncing`) or `:monitoring/stale`.
- `(revision m seq)` → `"<epoch>:<seq>"`.

Example monitoring maps (the exact shape Stage 8 reads):

    {:account-address "0x1234567890abcdef1234567890abcdef12345678"
     :status :live :reasons [] :epoch 4
     :stale-since-ms nil :live-since-ms 1761660012000
     :fills-snapshot-at-ms 1761660010400 :last-close-at-ms 1761660009000
     :last-tick-ms 1761660025000
     :resync {:state :idle :attempt 0}}

    {:account-address "0x1234…5678" :status :resyncing :reasons [:reconnected] :epoch 5
     :stale-since-ms 1761660040000 :live-since-ms nil
     :fills-snapshot-at-ms 1761660041200 :last-close-at-ms 1761660039900
     :last-tick-ms 1761660040000
     :resync {:state :failed :attempt 1 :error "Fill page cap reached" :next-at-ms 1761660058000}}

Tests in `test/hyperopen/vol_range/application/monitoring_test.cljs`: a table of transitions covering first load (stale → resyncing after usable + snapshot → live after ok finish), disconnect, reconnect with the snapshot arriving before and after the health change (no deadlock: the marker is valid when newer than the close), re-subscription snapshot, account switch mid-resync (old result ignored), spectate/trader route (`:streams-elsewhere`), suspension gap, backward clock, backoff and jitter bounds, `placement-blocker` for every status and for a stale tick at call time, and that no function reads a clock.

Acceptance: `npm run test:vol-range`, `npm run gates`. Commit: `feat(vol-range): live monitoring model`.

### Stage 6: monitoring wiring and the resync operation

- `src/hyperopen/vol_range/infrastructure/execution_watcher.cljs`: on install, write `(monitoring/initial account now)`. The existing store watch additionally schedules one coalesced microtask (a boolean flag in a `defonce` atom) whenever `[:websocket :health]` or the trading account changed (`identical?` checks), which runs `observe` on the current state, writes the result only when it differs, and dispatches `[[:actions/resync-vol-ladders]]` when `resync-due?` and the document is visible. The interval callback now always runs `on-tick` (and the same due check) and keeps dispatching `:actions/reconcile-open-vol-ladders` only while visible. Add a guarded `visibilitychange` listener (stored in a `defonce` so reinstall removes the previous one) that runs the tick logic immediately when the page becomes visible. Keep the file under 200 lines; move helpers to a new `vol_range/infrastructure/live_monitor.cljs` if needed.
- `live_ingress.cljs`: `observe-user-fills!` calls `observe` (current trading account), `monitoring/on-fills-snapshot`, then `observe` again for snapshot frames of the trading account before routing (Stage 5 Decision Log: a snapshot for an account the map does not hold yet is ignored); the envelope revision uses the monitoring epoch.
- New action `:actions/resync-vol-ladders` (no args) in `src/hyperopen/vol_range/execution_actions.cljs`: when not busy `[:resync]`, returns `[[:effects/vol-range-execute :resync {:epoch (get-in state [:vol-range :execution :monitoring :epoch])}]]`, else `[]`. Register: handler key `:resync-vol-ladders` in `execution-action-deps` (`runtime/collaborators/vol_range.cljs`), binding row `[:actions/resync-vol-ladders :resync-vol-ladders]` in `post-order-form-action-binding-rows` right after `[:actions/reconcile-open-vol-ladders ...]` (`schema/runtime_registration/trade.cljs`; the Vol Range rows live there, not in the pre-order-form group), `:actions/resync-vol-ladders ::common/no-args` in `vol_range_action_args.cljs`, and `:resync` in the op set of `::vol-range-execute-args` in `effect_args.cljs`. No effect-order policy is required (the execution actions are not in `effect-order-policy-required-action-ids`).
- Adapter op `:resync` in `vol_range_execution.cljs`: capture the trading account; `on-resync-started`; run `sync!` then `reconcile-open!`; ok when the sync status is `:ok`, the recover result (when one ran) has `:orders-complete? true`, and every reconcile result is `:persisted?` with `:orders-complete?` and `:fills-complete?` true; then `on-resync-finished` with `:jitter (- (* 0.4 (platform/random-value)) 0.2)`. Any rejection or the 60 s bound finishes with `:ok? false`. A resync never sends anything; it only reads and persists through the existing flows. If the adapter would pass 300 lines, move `resync!` and its helpers to a new `runtime/effect_adapters/vol_range_resync.cljs` whose `(resync! store {:sync! f :reconcile-open! g :epoch n})` receives the adapter's `sync!` and `reconcile-open!` as arguments; it must NOT require `vol_range_execution` (the adapter requires it, and ClojureScript rejects the cycle). This is the same pattern Repair stage R3 used for `vol_range_repair_replace.cljs`.
- `finish!` in the adapter stops overwriting `[:vol-range :execution :last]` for successful `:sync`, `:reconcile-open` and `:resync` results (it still clears their busy keys, refreshes `:enabled?`/`:max-ladder-usdc`, and records errors in `:last`). Update any dock or adapter test that expected a background op in `:last`.

Tests: adapter test that a successful `:reconcile-open` leaves a previous `{:op :repair ...}` `:last` untouched and an erroring one replaces it; watcher tests (extend `test/hyperopen/vol_range/infrastructure/` with `execution_watcher_test.cljs`) using a fake store and stubbed `platform` timers: a health change to usable plus a snapshot dispatches one resync; hidden pages do not dispatch; a tick gap triggers resync; reinstall removes the old listener. Action and registration tests (`execution_actions_test.cljs`, `registration_test.cljs`). Adapter tests with simulator deps: resync ok → monitoring `:live`; an incomplete fills window → `:failed` with backoff; an epoch change during resync → result ignored; account switch during resync → ignored.

Acceptance: `npm run test:runner:generate`, `npm run test:vol-range`, `npm run test:websocket`, `npm run gates`; run both Vol Range Playwright suites once to confirm nothing changed for them (monitoring does not gate yet). Commit: `feat(vol-range): mark monitoring stale on disconnect or suspend and resync on resume`.

### Stage 7: placement and repair gate on monitoring, and the Playwright WebSocket simulator

- `live-inputs` (adapter) adds `:monitoring-blocker (monitoring/placement-blocker (get-in state [:vol-range :execution :monitoring]) {:account (account-context/active-trading-account-address state) :now-ms now-ms})`.
- `executor_place.cljs` `refusal`: after the agent-readiness check add `(:monitoring-blocker live) (:monitoring-blocker live)`. Because `refusal` runs in `place!`, `repair!` and the final guard, a placement admitted while live but whose socket drops before HTTP is refused before sending and recorded as provably not sent. Executor test fakes that do not set the key keep their behavior.
- Playwright: add `tools/playwright/support/ws_simulator.mjs` exporting `installWsSimulator(page, {address})`. It uses `page.routeWebSocket("wss://api.hyperliquid.xyz/ws", …)` (confirm the URL in `src/hyperopen/config.cljs`), answers each `subscribe` for `userFills` with `{"channel":"userFills","data":{"isSnapshot":true,"user":<address>,"fills":[]}}` and for `openOrders` with `{"channel":"openOrders","data":{"user":<address>,"dex":"","orders":[]}}`, ignores market subscriptions, pushes a harmless frame every 3 s so transport freshness stays `:live` (check `websocket/health.cljs`; adjust the frame if it does not update the receive time), and exposes `push(frame)` and `drop()` (close the socket; accept the reconnect). Install it from the shared `bootstrap(page, world)` in `tools/playwright/support/vol_range_world.mjs` (created by Repair stage R5), so `vol-range-execution.spec.mjs` and `vol-range-repair-replace.spec.mjs` both get it before navigation, and add `"userFillsByTime"`/`"frontendOpenOrders"` answers to the shared `world` stub if the resync reaches them before they are already answered. Prior art for `page.routeWebSocket` on this URL: `routeHyperCoreAccountStreams` in `tools/playwright/support/hyperevm_fixtures.mjs`.

Tests: in `test/hyperopen/vol_range/infrastructure/lifecycle_safety_test.cljs` (or a new `monitoring_gate_test.cljs` if that file is near its size limit) with the simulator: a `:monitoring/resync-required` blocker refuses placement with nothing journaled; a blocker that appears between admission and the final guard yields delivery `:not-sent`, no HTTP call in the simulator, and the reservation released; repair is refused the same way; cancel with a blocker present still sends. Adapter test: `live-inputs` reports each blocker.

Acceptance: `npm run test:vol-range`, `npm run gates`, and `npx playwright test tools/playwright/test/vol-range-execution.spec.mjs tools/playwright/test/vol-range-repair-replace.spec.mjs tools/playwright/test/vol-range.spec.mjs` all green (repair is gated too), then `npm run browser:cleanup`. If port 8080 is taken, use the static-server variant in Concrete Steps. Commit: `feat(vol-range): refuse placement until live monitoring has caught up`.

### Stage 8: dock and review UI for live monitoring; narrower order-form slice

- `dock_model.cljs`: `dock` adds `:monitoring (monitoring-line state)` → `{:status :live|:stale|:resyncing :reason kw :text "…" :attention? bool}` with the copy below; `review-commit` sets `:enabled?` false and `:note` to the monitoring note when `(monitoring/placement-blocker …)` is non-nil; `status-message` maps refusals and not-sent results carrying any `:monitoring/*` code to the refusal copy instead of printing codes. Keep the file under 300 lines (split a `dock_copy.cljs` if needed).
- `views/trade/vol_range/dock.cljs`: render the monitoring line at the top of `dock-panel`, above the status message, as `[:p {:data-role "vol-ladders-monitoring" :data-status (name status) :data-reason (some-> reason name)} …]`, with an `aria-hidden` "⚠" glyph and `text-ho-warn` when `:attention?`, muted text when live. It is not a live region (spec 11.4: do not announce ticks); outcome announcements stay in `vol-ladder-status-message`.
- `views/trade/vol_range/dock.cljs`: new `(order-form-slice state)` -> `{:vol-range (assoc vol-range :execution (-> execution (select-keys [:enabled? :max-ladder-usdc :busy :last]) (assoc :monitoring (dissoc monitoring :last-tick-ms))))}`; every other key under `:vol-range` (review, frozen context, ui, repair, replace) passes through unchanged.
- `views/trade_view.cljs` (exactly 500 lines, no size exception): net-zero edit. Remove the `:vol-range` line from `order-form-view-state-keys` and add one `(vol-dock/order-form-slice state)` line to the `merge` in `order-form-view-state`. Stream fills then no longer repaint the ticket. Confirm `npm run lint:namespace-sizes` still reports 500.
- The repair dialog (Repair stage R4) uses the same view-level monitoring blocker: its submit button is disabled with the monitoring note while monitoring is not live.
- `dock_model/review-commit` uses `(monitoring/view-blocker m account)`, which applies the status and account rules of `placement-blocker` but not the tick-gap rule (the slice has no `:last-tick-ms`); the executor still applies the full `placement-blocker` with real time.

UI copy (exact strings):

- Live: "Live updates on"
- Stale, transport down: "Live updates paused while reconnecting. Your orders stay on the exchange."
- Stale, streams for another account: "Live updates paused while another account is shown."
- Stale, streams not ready or awaiting the first snapshot: "Connecting live updates…"
- Resyncing (required or running): "Catching up on fills and orders…"
- Resyncing after a failed attempt: "Couldn't catch up yet. Retrying automatically."
- Review note while blocked (`vol-range-submit-note`): "Placement waits for live order and fill updates to catch up."
- Refusal or not-sent with a monitoring code (`vol-ladder-status-message` and the review error): "Nothing was placed: live order and fill updates were catching up. Try again in a moment."

Tests: `dock_model_test.cljs` for each line and the review commit; `test/hyperopen/views/trade/vol_range/dock_test.cljs` for the data-roles, attributes and glyph; `review_commit_test.cljs` for the disabled button and note; the repair dialog test for its disabled submit and note; `monitoring_test.cljs` for `view-blocker`; extend `test/hyperopen/views/trade_view/vol_range_slice_repaint_test.cljs` asserting the order-form slice is `=` before and after a grid fill and a watcher tick, and changes when monitoring status changes. Run `npm run lint:hiccup`, `lint:class-attrs`, `lint:style-keys`, `lint:theme-colors`.

Acceptance: `npm run test:vol-range`, `npm run gates`, both Vol Range Playwright suites. Commit: `feat(vol-range): show live monitoring in the Vol ladders dock and review`.

### Stage 9: shared fill record normalizer and the fills consumer seam for Reporting

This stage replaces the earlier "durable fill ledger store" stage (integration review). It writes nothing to IndexedDB and adds nothing to the executor.

- New pure `src/hyperopen/vol_range/application/fill_records.cljs` (add it to the A0 test's `execution-foundations`; it requires `lifecycle-evidence`):
  - `(normalize-fill {:network :account-address :row :source :received-at-ms})` -> the canonical fill record below, or nil when `:coin`, `:time`, `:tid`, `:px` or `:sz` is missing, `:side` is not `"B"`/`"A"`, or both oid and CLOID are missing. `:fill-key` is exactly `lifecycle-evidence/fill-key` for the row (Stage 1 canonicalization), so a WebSocket row and the REST row for the same fill produce equal records except for `:source` and `:received-at-ms`. Money fields keep the venue strings (`str` of the venue value, never re-rounded); `:fee` is the venue's signed fee, which already includes `builderFee` (spec 13.5 row 4); `:oid` goes through `lifecycle/oid-string`; `:cloid` is lower-cased.
  - `(same-fill? a b)` compares fill keys.
  - Grid records keep their existing `:fills` map and its private shape; nothing in the lifecycle changes.
- `src/hyperopen/vol_range/infrastructure/live_ingress.cljs`: `(register-fills-consumer! k f)` and `(unregister-fills-consumer! k)` over a `defonce` atom. After grid routing, `observe-user-fills!` calls every consumer with the envelope `(f envelope)`, each in its own `try`/`catch` (errors are logged once through `hyperopen.telemetry/log!` and never affect grid ingestion or the display path). The envelope is the spec 9.1 envelope from Stage 1 (all rows, all coins, before any truncation). Consumers must be fast and must not `await` on the tap thread; Reporting's consumer queues its own IndexedDB write.

Canonical fill record (the shape Reporting's `vol-range-ledger` store embeds under `:fill`):

    {:fill-key "hyperliquid-mainnet|0x1234567890abcdef1234567890abcdef12345678|@107|1761660000123|884422|90110022"
     :network :hyperliquid-mainnet
     :account-address "0x1234567890abcdef1234567890abcdef12345678"   ; lower-case
     :coin "@107"
     :time 1761660000123                 ; venue ms, integer
     :tid "884422"                       ; integer text
     :oid "90110022"                     ; lossless string, or nil
     :cloid "0x484f5652..."              ; lower-case, or nil
     :side :buy                          ; "B" -> :buy, "A" -> :sell
     :px "76.4" :sz "3"                  ; venue strings, gross size
     :fee "0.0012"                       ; signed; negative = rebate; includes builder fee
     :fee-token "HYPE"
     :builder-fee nil                    ; informational, or the venue string
     :crossed false
     :hash "0x..." :dir "Buy" :start-position "150.0"
     :source :websocket                  ; or :rest
     :received-at-ms 1761660000400}

Tests in `test/hyperopen/vol_range/application/fill_records_test.cljs` (using `live_fixtures.cljs`): the WebSocket increment row and the REST `userFillsByTime` row for the same fill give equal records apart from `:source`/`:received-at-ms`, and the same `:fill-key` as `lifecycle-evidence/fill-key`; numeric and numeric-string `time`/`tid` agree; side mapping and rejection of unknown sides; missing fields give nil; money strings are not re-rounded; fee with `builderFee` keeps the fee as is. In `live_ingress_test.cljs`: a registered consumer receives the 250-row frame whole; a throwing consumer does not stop grid ingestion or other consumers; unregister removes it; no consumer is called for `openOrders` frames.

Acceptance: `npm run test:vol-range`, `npm run gates`. Commit: `feat(vol-range): shared fill records and a fills consumer seam`.

### Stage 10: live Playwright spec, documentation, close-out

- New `tools/playwright/test/vol-range-live.spec.mjs` (tag `@regression`; imports `{ expect, test }` from `../support/guarded_test.mjs`, which `spec_imports.test.mjs` enforces), using the shared `tools/playwright/support/vol_range_world.mjs` (`bootstrap`, `world`, created in Repair stage R5) and the `ws_simulator.mjs` handle it installs:
  1. Live fill: place the seven-order fixture ladder; push a `userFills` increment for buy rung 0 (partial); expect `[data-role="vol-ladder-filled-buy"]` to show the filled size within 3 s and no `userFillsByTime` request between the push and the assertion; let the world stub return the same fill on the next REST reconcile; the filled size is unchanged (counted once).
  2. Reconnect: `drop()`; expect `[data-role="vol-ladders-monitoring"]` with `data-status="stale"`; open a new review: the submit button is disabled with the monitoring note; the simulator accepts the reconnect and sends snapshots; the world stub now returns a fill made during the gap; expect `data-status="resyncing"` then `"live"`, a `frontendOpenOrders` and a `userFillsByTime` request after the reconnect, and the gap fill shown once.
  3. Truncation: a snapshot frame with 250 fills whose last row is the ladder's fill is counted.
  4. Route change (VR-19): navigate to the own-account Portfolio route and back; a fill pushed meanwhile is shown on return.
  Assert in every test that no request reached the real exchange (`/exchange` only through the debug simulator) and run `npm run browser:cleanup` at the end.
- `docs/design-docs/vol-range-execution-coordination.md`: add "### 9. Live evidence, monitoring and shared fill records" summarizing the decisions above (including that the durable ledger and coverage belong to Reporting); bump `last_reviewed`.
- `docs/runbooks/vol-range-local-money-test.md`: add a "Live updates" section (what the monitoring line should show, how to watch a fill arrive, how to save WebSocket frames from DevTools as protocol fixtures with the address replaced, and the open protocol questions to answer).
- Update this plan's Progress, Surprises, Outcomes; move it to `docs/exec-plans/completed/`.

Acceptance: `npx playwright test tools/playwright/test/vol-range-live.spec.mjs` then `vol-range-execution.spec.mjs`, `vol-range-repair-replace.spec.mjs` and `vol-range.spec.mjs`, `npm run browser:cleanup`, `npm run gates`. Commit: `test(vol-range): browser coverage for live fills and monitoring`.

## Concrete Steps

All commands run from the worktree root (`/hyperopen` in repo-root notation).

    npm run setup:worktree
    npm run test:runner:generate          # after adding test namespaces
    npm run test:vol-range                # focused Vol Range build
    npm run test:websocket                # stages 3, 4, 6
    npm run gates                         # check + test + test:websocket, PASS/FAIL matrix

A single focused namespace:

    npx shadow-cljs --force-spawn compile vol-range-test && node out/vol-range-test.js --test=hyperopen.vol-range.application.live-evidence-test

Expected tail of a passing run:

    Ran N tests containing M assertions.
    0 failures, 0 errors.

Browser suites (stages 7, 8, 10):

    npx playwright test tools/playwright/test/vol-range-execution.spec.mjs tools/playwright/test/vol-range.spec.mjs
    npx playwright test tools/playwright/test/vol-range-live.spec.mjs
    npm run browser:cleanup

If port 8080 is held by another checkout, build once and serve statically:

    npm run css:build && npx shadow-cljs --force-spawn compile app portfolio-worker portfolio-optimizer-worker vault-detail-worker
    PLAYWRIGHT_BASE_URL=http://127.0.0.1:4180 PLAYWRIGHT_WEB_PORT=4180 \
      PLAYWRIGHT_WEB_SERVER_COMMAND="node tools/playwright/static_server.mjs" \
      npx playwright test tools/playwright/test/vol-range-live.spec.mjs

Lints to keep green in UI stages: `npm run lint:namespace-sizes`, `lint:namespace-boundaries`, `lint:hiccup`, `lint:class-attrs`, `lint:style-keys`, `lint:theme-colors`, `lint:docs`.

## Validation and Acceptance

Behavioral acceptance, each tied to a test named in its stage:

- VR-14: a fill delivered by the stream, then repeated by a stream snapshot, the order response and a REST window is counted once; a fill before the HTTP acknowledgment is counted once after reconciliation.
- VR-18: stream evidence for another account, network or coin, or an unknown oid, never changes a ladder.
- VR-19: the taps run in the account runtime regardless of route and dock tab; the 250-row truncation case and the cancel-guard pruning case still reach the ladder; after a reconnect, the missed-fill window is read before placement is allowed; a route that removes the trading account's stream (trader portfolio, spectate) marks monitoring stale and resyncs on return.
- Spec 9.3: disconnect, socket close, re-subscription snapshot, account change and a tick gap over 45 s each mark monitoring stale or resyncing; placement and repair are refused (also at the final guard, recorded as not sent) until a resync with complete reads finishes in the same epoch; cancel and Refresh status always work.
- Spec 9.4: no new polling loop; stream-triggered reconciliation at most once per 5 s per account; resync retries back off 15 → 120 s with jitter and run only while the page is visible; the 15 s fallback is unchanged.
- Spec 9.2 / 7.4: `fill-records/normalize-fill` keeps `crossed`, `fee`, `feeToken`, `builderFee`, `hash`, the venue money strings and the shared fill key, identically for WebSocket and REST rows; fills consumers get every raw row before truncation, and a throwing consumer never affects grid ingestion. (Durable ledger, coverage and their failure isolation are Reporting stage R2.)
- VR-24 still holds: the A0 boundary test passes with the new namespaces classified as execution code.
- Browser: `vol-range-live.spec.mjs` passes with zero requests to the real exchange.

## Idempotence and Recovery

Every stage is additive and safe to rerun. Ingestion is idempotent by fill key and by the monotonic grid merge, so replaying frames or rerunning a resync never double counts. This plan adds no IndexedDB store. If a registration edit is incomplete, the build fails at load naming the action id; add the missing row and rerun. If a stage overruns a namespace size limit, split the namespace (the plan names the fallback files). Never hand-edit `test/test_runner_generated.cljs`.

## Artifacts and Notes

The spec 9.1 envelope as produced for a `userFills` increment:

    {:kind :fills
     :scope {:network :hyperliquid-mainnet :account-address "0x1234567890abcdef1234567890abcdef12345678"}
     :source :websocket
     :channel "userFills"
     :request-id nil
     :received-at-ms 1761660000400
     :observed-at-ms 1761660000123
     :snapshot? false
     :complete? false
     :address-source :message
     :account-event-revision "4:41"
     :dex nil
     :rows [{:coin "@107" :px "76.4" :sz "3" :side "B" :time 1761660000123 :tid 884422
             :oid 90110022 :crossed false :fee "0.0012" :feeToken "HYPE" :builderFee nil
             :hash "0x…" :dir "Buy" :startPosition "150.0"}]}

`live-evidence/route` result:

    {:by-grid {"grid-7f3a" {:fills [row] :orders []}}
     :absent {}
     :checking #{}
     :order-coins #{}}

Cross-item shared contracts (other Vol Range items must use these, not parallel copies): the fill key (`lifecycle-evidence/fill-key`), the canonical fill record from `fill-records/normalize-fill` (Stage 9), the fills consumer seam `live-ingress/register-fills-consumer!`, the `:monitoring/*` refusal codes and the `[:vol-range :execution :monitoring]` shape, the pure `grid-store/merge-grid-into-state`, the `:resync` effect op, and `vol-dock/order-form-slice`. The durable ledger store (`vol-range-ledger`, IndexedDB version 9) is owned by Reporting stage R2.

## Interfaces and Dependencies

No new third-party libraries. At the end of the named stage these must exist:

Stage 1, `hyperopen.vol-range.application.live-evidence`:

    (fills-envelope msg {:received-at-ms :network :fallback-address :revision}) -> envelope | nil
    (open-orders-envelope msg {:received-at-ms :network :fallback-address :revision}) -> envelope | nil
    (route envelope grids-by-id) -> {:by-grid :absent :checking :order-coins}
    (apply-envelope grid envelope rows) -> grid (identical? when nothing applied)

Stage 2, `hyperopen.vol-range.application.grid-store`: `(merge-grid-into-state state grid)`, `(merge-account-grids state account journal-grids)`; in the adapter, `reconcile-open!` resolves to a vector of `reconcile!` results.

Stage 3: `(hyperopen.websocket.user/init! store {:on-raw-user-fills f :on-raw-open-orders g})`, tap signature `(f store msg received-at-ms)`; `(hyperopen.vol-range.infrastructure.live-ingress/user-stream-taps)`.

Stage 4, `hyperopen.vol-range.infrastructure.live-ingress`: `install!`, `observe-user-fills!`, `observe-open-orders!`.

Stage 5, `hyperopen.vol-range.application.monitoring`: `initial`, `observe`, `on-fills-snapshot`, `on-tick`, `on-resync-started`, `on-resync-finished`, `resync-due?`, `placement-blocker`, `view-blocker` (status and account rules only, no tick gap; used by views, added in Stage 8 if not here), `revision`; `hyperopen.vol-range.contracts/live-monitoring-policy`.

Stage 6: action `:actions/resync-vol-ladders`; effect `[:effects/vol-range-execute :resync {:epoch n}]`.

Stage 7: `live-inputs` key `:monitoring-blocker`; refusal codes `:monitoring/stale`, `:monitoring/resync-required`, `:monitoring/suspended`; `tools/playwright/support/ws_simulator.mjs` `installWsSimulator`.

Stage 8: `dock-model/dock` key `:monitoring`; data-role `vol-ladders-monitoring` with `data-status` and `data-reason`.

Stage 9: `hyperopen.vol-range.application.fill-records` (`normalize-fill`, `same-fill?`); `hyperopen.vol-range.infrastructure.live-ingress` (`register-fills-consumer!`, `unregister-fills-consumer!`).

## Risks and Mitigations

- Double-counted fills inflate filled quantities or execution detail. Prevented by the single shared fill key, the lifecycle's key check, and `leg-quantities` flooring by the venue-reported size; Stage 1 and Stage 4 tests replay the same fill through every source.
- Evidence attributed to the wrong account, market or network. Routing requires network, account, coin and a known oid or CLOID; VR-18 tests.
- A new ladder placed while fills from a gap are unknown. The monitoring gate in the single `refusal` seam, checked at the click and at the final guard; Stage 7 tests prove a not-sent outcome when the gate closes mid-flight.
- The gate blocks placement indefinitely. Resync success needs complete reads, not resolved legs; retries back off and continue; the dock says what is happening; cancel and Refresh status never depend on it.
- REST storms from a flaky socket or a high-fill account. Hints at most once per 5 s per account and only for Checking ladders or guarded absences; resync only on epoch change, visible-only, with backoff and jitter; all reads use the shared `post-info!` scheduler.
- A reconcile result erasing stream evidence in the store. Stage 2 monotonic merge with tests.
- A Vol Range defect breaking the account display path or toasts. Taps run inside `try`/`catch` with a test that a throwing tap changes nothing.
- Ticket repaints on every fill. Stage 8 narrows the order-form slice, with a test.
- A Reporting consumer slowing or breaking ingestion. Consumers run after grid routing, each in its own `try`/`catch`, and must not await on the tap thread.
- Two ledgers with different coverage rules. This plan writes no store and no coverage; Reporting owns both.
- "Ladder ok." announced on every background tick, erasing outcome messages. Successful background ops no longer write `[:vol-range :execution :last]` (Stage 6).
- A stale stream frame making an unconfirmed order look Live. Open-order frames never touch `:unconfirmed` legs and never raise remaining sizes (Stage 1).
- Growing `trade_view.cljs` past its 500-line limit. Stage 8's slice change is net zero there; the helper lives in `dock.cljs`.
- Breaking VR-24. Each new execution-dependent namespace (`live_evidence`, `grid_store`, `live_ingress`, `fill_records`) is added to the A0 test's `execution-foundations` in its stage.
- Existing Playwright placement tests failing once the gate needs a live stream. Stage 7 installs the WebSocket simulator in that suite in the same commit.
- Unverified protocol shapes. Defensive parsing and fallbacks are chosen so each unknown only reduces coverage; the runbook asks the local money test to capture real frames as fixtures.
- Real-money safety during development. No test or agent touches the real exchange: unit tests use the simulator and fakes, Playwright uses the debug exchange simulator, `page.route` and `page.routeWebSocket`.

## Validation

Required gates when code changes: `npm run check`, `npm test`, `npm run test:websocket` (together `npm run gates`), plus `npm run test:vol-range`, and the three Vol Range Playwright specs for stages that change browser behavior, followed by `npm run browser:cleanup`. Each stage records its command results in Progress and Outcomes.

Revision note (2026-10-28): initial version written from the spec, the current branch (head `dd19bd749`), and the completed stage 4-6 plans.

Revision note (2026-10-28, integration review): the fill-ledger store, its coverage records and the `:ledger!` executor dep were dropped in favour of Reporting's single `vol-range-ledger` store; Stage 9 now ships the shared `fill-records/normalize-fill` and the fills consumer seam. Open-order frames no longer touch `:unconfirmed` legs or raise remaining sizes. The store merge moved to the pure `grid_store.cljs`. The resync helper takes `sync!`/`reconcile-open!` as arguments (no require cycle). Successful background ops stop overwriting `:last`. The binding row goes in `post-order-form-action-binding-rows`. `trade_view.cljs` gets a net-zero edit through `vol-dock/order-form-slice`, whose monitoring copy omits `:last-tick-ms`. A0 boundary classification is listed per stage. The WebSocket simulator installs through the shared Playwright `bootstrap` from Repair R5, and every spec imports the guarded `test`. Refusal while not live is recorded as spec-mandated.
