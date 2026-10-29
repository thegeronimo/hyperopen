# Vol Range reporting: tracking campaigns, fill ledger, coverage-aware P&L, Portfolio tab, export

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. It is maintained in accordance with `/hyperopen/docs/PLANS.md` and the detailed template in `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Release A lets a user place a Vol Range ladder (fixed post-only spot orders at volatility distances) and manage it in the trading dock's **Vol ladders** tab. The dock shows filled quantity and fill VWAP per side, explicitly not P&L. Users still cannot answer the question the feature exists for: "did running these ladders do better or worse than just holding my inventory?"

This plan delivers spec section 12 (Portfolio accounting and performance) and the 13.5 reporting fixtures. After it lands:

- The first admitted placement in a market records an immutable **tracking campaign baseline**: the complete spot base inventory `q0`, a fresh book-midpoint price `P0` with its source and time, the reconciled account-read boundary, `C0 = 0`, and the model versions. It is committed in the same IndexedDB transaction as the placement intent, before anything is signed or sent.
- A **fill ledger** collects every same-base fill and non-funding ledger change for the account through bounded REST backfill (with overlap de-duplication, truncation detection and a no-progress guard), plus WebSocket rows from the live-fills item. Coverage is proven only by complete REST windows.
- A new **Vol Range** tab in Portfolio shows, per market and for **All tracked markets**, the three headline figures for **Since tracking began**, **7D** and **30D**: **Net tracked asset P&L**, **Hold P&L** and **Ladder contribution versus hold**, using completed hourly closes and the half-open `[a,b)` convention. Coverage states and attribution breaks suppress the headlines while still showing known orders, fills, volumes and fees by token.
- A secondary **Gross spread on matched fills** diagnostic (FIFO, unmatched marks, fee and rebate impact), a cumulative **P&L chart** whose axis is never labeled equity, and **Export** (a reproducible JSON bundle and a human-readable fills CSV) through the existing Blob-and-anchor download pattern.

How to see it working: run `npm run test:vol-range` and watch the 13.5 fixtures reproduce (for example `q0=150, P0=80`, buy 7 at 76.4, mark 70 gives Net tracked P&L -1,544.8, Hold P&L -1,500, ladder contribution -44.8). Then run `npx playwright test tools/playwright/test/vol-range-report.spec.mjs`, which places a ladder through the debug exchange simulator (never a real exchange), opens `/portfolio?tab=vol-range`, sees the captured baseline, then loads a seeded fixture and checks the headline, a suppressed headline after an external transfer, the chart axis label and an export download.

## Context References

Public refs:
- Direct user request in this session (2026-10-28): plan the remaining Vol Range work with planning agents and then implement it through a workflow; this plan is the reporting item.

Repo artifacts:
- Product spec: `/hyperopen/docs/product-specs/vol-range-implementation-spec.md` (HO-SPEC-014 v2.1). Sections in scope: 1.2 (Release B), 7.1 (`:campaigns` under the `:vol-range` root), 7.4 (durable campaign, fill and coverage records), 7.6 (retention), 9.2 (fill identity, backfill limits), 11.2 (dock), 11.3 (Portfolio placement), 12.1-12.8, 13.5, 14.1 VR-19 to VR-21, 14.2 (Portfolio browser coverage), 15.1 PR 8, 15.2 (reporting flag), 16 (coverage-break telemetry).
- Parent ExecPlan: `/hyperopen/docs/exec-plans/active/2026-10-21-vol-range-release-a0-preview.md` (A0 and the Release A commits `5695b0d02`, `88834710b`, `ae6695477` that followed it).
- Sibling item this plan depends on: the Vol Range live-fills item (WebSocket `userFills` consumption and normalized fill records). See "Dependency on the live-fills item".

Local scratch refs (non-authoritative):
- None.

## Progress

- [x] (2026-10-28) Read spec sections 7, 9, 11-13 and the current journal, lifecycle, executor, reads, dock, watcher, effect-adapter, IndexedDB, Portfolio tab, download and registration seams; wrote this plan.
- [x] (2026-10-29) Stage R1: campaign records and baseline capture at the first admitted placement; dock "Tracking since" line.
- [x] (2026-10-29) Stage R2: ledger store, same-base classification, REST backfill readers, coverage intervals, balance checks (pure and infrastructure, no UI).
- [x] (2026-10-29) Stage R3: reporting flag, effect, actions, watcher, Start tracking, WebSocket ledger tap, Portfolio Vol Range tab with baseline, coverage and execution activity (no dollar figures yet).
- [x] (2026-10-29) Stage R4: hourly marks, report windows, headline equations, cross-asset aggregation and suppression UI.
- [x] (2026-10-29) Stage R5: FIFO matched-fill spread diagnostic and the cumulative P&L chart.
- [x] (2026-10-29) Stage R6: JSON and CSV export, end-to-end Playwright spec, docs and final gates. Plan moved to `docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: grid records already store fills in `:fills`, keyed by `lifecycle-evidence/fill-key` (`network|account|coin|time|tid|oid-or-cloid`), but only fills whose CLOID or oid belongs to that grid. Untracked same-base trades, other pairs that contain the base token, and non-funding ledger changes are not captured anywhere durable.
  Evidence: `src/hyperopen/vol_range/application/lifecycle_evidence.cljs` `ingest-fills` drops rows with no matching leg.
- Observation: `journal/load-all!` reads the whole `vol-range-journal` store and is called on every placement, persist and reconcile. Putting thousands of fill rows into that store would slow every real-money operation.
  Evidence: `src/hyperopen/vol_range/infrastructure/executor.cljs` `load!`, `persist-grid!`, `refresh-grid!`.
- Observation: `idb/put-many-committed!` writes one store per transaction. A campaign baseline must be committed atomically with the placement intent, so it must live in the journal store, not in a new store.
- Observation: `reads/read-fills-window!` already pages `userFillsByTime` forward with overlap, a no-progress guard and a 10-page cap, but its requests are always `:priority :high` and it cannot detect venue retention truncation (only the most recent 10,000 fills are served).
- Observation: the admission barrier in `executor_place.cljs` already has everything the baseline needs: `read` (raw balances with `:barrier-id`, `:requested-at-ms`, `:received-at-ms`) and `input` with a freshly read book (`with-fresh-book`). Revalidation already rejects a book older than 2 s, so an admitted placement always has a fresh BBO.
- Observation: there is no shared download helper. Funding history (`account/history/effects.cljs`), the optimizer and the spectate watchlist each build a `js/Blob`, an object URL and a temporary anchor. "Existing download infrastructure" therefore means that pattern inside an effect adapter.
- Observation: Portfolio tabs are composed in `views/portfolio/account_tabs.cljs` (`:extra-tabs`); the allowlist and aliases live in `portfolio/actions.cljs`, which is at 856 of its 861-line ceiling. `?tab=<id>` is already a shareable query parameter (`portfolio/query_state.cljs`).
- Observation: the Playwright specs live in `tools/playwright/test/` (not `tests/playwright/`); `vol-range-execution.spec.mjs` already drives placement through the debug exchange simulator with a stateful `**/info` stub. `HYPEROPEN_DEBUG.seedVolRangeFixture` accepts a config map, so new seeding can be added in `console_preload/vol_range_fixture.cljs` without touching `console_preload.cljs` (at its 554-line ceiling).
- Observation: `[:vol-range :campaigns]` is named in spec 7.1 and unused today.
- Observation (R1): the executor simulator's scope (`test/hyperopen/vol_range/simulator.cljs`) has `:base-token-id nil`, so its campaign key is `hyperliquid-mainnet|0xabc|spot||1` and `q0` is matched by symbol ("SYN"), exactly as the admission snapshot's `find-balance` does when balance rows carry no `:token`.
  Evidence: `campaign_capture_test.cljs`; real spot scopes always carry the base token id (`snapshots/current-scope`, `revalidation/identity-keys`).
- Observation (R1): a reference to a var that does not exist (for example a `contracts/...` version def that was never added) compiles in ClojureScript with only an `:undeclared-var` warning and reads as nil at run time. The R1 unit tests assert the version and skew values literally, so a missing def fails a test rather than producing a record with nil versions.

- Observation (R2): `reads/read-fills-window!` reported why a read was incomplete only as English error text, so coverage could not tell no progress from a page cap without matching strings.
  Evidence: `done` in `src/hyperopen/vol_range/infrastructure/reads.cljs` before R2.
- Observation (R2): `portfolio/request-user-non-funding-ledger-updates!` turns any unexpected payload into `[]` (and applies a 5 s cache), so a broken response would look like a complete, empty ledger.
  Evidence: `non-funding-ledger-updates-seq` in `src/hyperopen/api/endpoints/account/portfolio.cljs`. `report_reads/read-ledger-window!` therefore calls `post-info!` directly, uncached, and treats any non-vector as incomplete.
- Observation (R2): the plan's ambiguous inception window `[requested-at - skew, received-at + skew]` reaches up to 5 s after the barrier read, but the first placement's orders are sent a few ms after that read, so a post-only order filled within 5 s would have broken every such campaign at inception. The plan's note "nothing has been sent yet" holds only before `requested-at`.
  Evidence: `admit-placement!` commits and sends right after `barrier-account!`; operation `:created-at-ms` is the admission time, after the read.
- Observation (R2): the synthetic `@9001` VRT/USDC market existed only in `telemetry/console_preload/vol_range_fixture.cljs`; the unit fixtures used SYN/@107. `test/hyperopen/vol_range/report_fixtures.cljs` defines VRT (9001), VRS (9002) and the VRT/VRS pair `@9003`.

- Observation (R3): `:actions/navigate` normalizes its path and drops any query string, so the dock's "View report" cannot rely on `/portfolio?tab=vol-range`.
  Evidence: `router/normalize-path` (`split-path-from-query-fragment`) in `runtime/action_adapters/navigation.cljs` `navigate`.
- Observation (R3): the running `shadow-cljs watch` picked up the new `VOL_RANGE_REPORTING` closure define from `shadow-cljs.edn` without a restart (the dev `main.js` carries `"hyperopen.config.VOL_RANGE_REPORTING":true`).
- Observation (R3): in Playwright, starting new tracking a few seconds after a streamed fill of the running ladder gives the new campaign `:break/ambiguous-boundary` at once ("Outside activity: activity at the moment tracking began"). This is the designed R2 rule for a user-restart campaign with a ladder already running (a tracked fill of an operation admitted before T0 inside the skew window), not a defect.
- Observation (R3): `with-redefs` of a multi-arity function needs a multi-arity replacement in ClojureScript: callers are compiled to `cljs$core$IFn$_invoke$arity$N`, which a single-arity `fn` lacks (the live-ingress consumer guard then logs and swallows the TypeError).

- Observation (R4): the app's own spot metadata load (startup or the route's catalog load) can land after a fixture seed and replace `[:spot :meta]` with the venue's, which has no `@9001`/`@9002`. Every seeded fill then classifies as `:break/unknown-market-fill` and the headline is suppressed.
  Evidence: the first R4 Playwright run showed "a fill in a market HyperOpen couldn't identify" for the seeded VRT buy. `vol-range-report.spec.mjs` now repeats the seed (which commits the scenario only once) until the synthetic markets stay, as `seedTicket` already does for the ticket.
- Observation (R4): Replicant drops an attribute whose value is `false`, so `:aria-pressed false` renders no attribute at all. The window buttons and the covered-subset control pass the strings "true"/"false", like the funding modal and asset selector.
- Observation (R4): the Playwright world sent every `candleSnapshot` without a `user` to the real network (`route.continue`). The report's 1h reads are now answered by the world (`world.hourlyCloses`, coin -> flat close; no entry means no candles); other intervals are unchanged.

- Observation (R5): the `--ho-*` color tokens are RGB channel triplets (`--ho-accent: 80 210 193`), not colors, so `var(--ho-accent)` as an SVG `stroke` is invalid and the line draws nothing (the first R5 screenshot showed the tooltip but no lines). The chart uses `rgb(var(--ho-…))`, which `lint:theme-colors` already treats as a token.
  Evidence: `src/styles/themes/dark.css` ("Color tokens: RGB channel triplets").
- Observation (R5): the shared d3 runtime draws each series as one d3 line with no `defined` accessor, and `model/extend-single-point` stretches a one-point series to the right edge. Gaps are therefore separate segment series, and a lone point between gaps is sent twice (a zero-length line, a dot with the round cap) so it is never stretched.
- Observation (R5): the report scrolls inside the Portfolio account panel, so a Playwright hover of the chart needs `scrollIntoViewIfNeeded` first; otherwise the pointer lands outside the viewport and the tooltip stays hidden.

- Observation (R6): the attribution index had no ladder lineage, so the export could not name the ladder a replacement was built from from the loaded report alone.
  Evidence: `report-attribution/attribution-index` entries before R6. They now carry `:predecessor-grid-id` (from the grid's `:predecessor`).
- Observation (R6): evaluated to the export time, a campaign's coverage is usually `:partial` for its last few seconds (a complete read proves only up to `R - :read-settle-ms`), even while every window's coverage to its `b` is complete. The export records both honestly; the browser test asserts the window's `[T0 - skew, b)` is complete.
- Observation (R6): `shadow-cljs release app` writes to `resources/public/js`, the directory the running dev server serves. The bundle budget was measured from a release build into a scratch directory (`--config-merge '{:output-dir …}'`), with the same gzip measure as `check_bundle_budget.mjs`, so the user's dev server was untouched.

## Decision Log

- Decision: Campaign baselines and campaign closures are records in the existing `vol-range-journal` store, committed in the same transaction as the placement intent and grid (`commit-admitted!`). Fills, ledger updates, coverage intervals and balance checks go in a new `vol-range-ledger` store.
  Rationale: the baseline must be durable before send and atomic with the intent; bulky accounting data must not slow every journal load on the real-money path.
  Date/Author: 2026-10-28, Claude (planning agent).
- Decision: Baseline capture is unconditional in any build that includes placement (it does not depend on the reporting flag). Ledger backfill and the Portfolio tab run only when the reporting flag is on.
  Rationale: spec 1.2 requires capturing manifests during Release A so reporting never fabricates earlier history; REST backfill can catch up later within venue retention, and coverage states report honestly when it cannot.
  Date/Author: 2026-10-28, Claude.
- Decision: The baseline builder is total. It never throws and never changes the legs, action, digest, reservations or nonce path. If a price is somehow missing it records `:p0 nil` with `:p0-source :unavailable`, and coverage for that campaign is `:unavailable`. A failed commit still blocks placement exactly as today, because the record shares the intent's transaction.
  Date/Author: 2026-10-28, Claude.
- Decision: The inception boundary is `[requested-at-ms, received-at-ms]` of the admission barrier read, widened by `:boundary-skew-ms` (5,000 ms) on both sides to absorb local-versus-venue clock skew. Events with venue time before `requested-at-ms - skew` are in `q0`. Any same-base event (tracked or not) inside the widened window makes the boundary ambiguous, which is an attribution break at inception. The campaign start used for windows is `T0 = requested-at-ms`.
  Rationale: spec 12.4 says a timestamp alone cannot order same-millisecond events and an unestablished boundary keeps collection incomplete. Tracked fills of a first placement cannot fall in that window because nothing has been sent yet.
  Date/Author: 2026-10-28, Claude.
- Decision: Coverage is proven only by complete REST reads (`userFillsByTime` and `userNonFundingLedgerUpdates`). WebSocket rows are persisted idempotently by fill key so activity appears quickly, but they never extend coverage.
  Rationale: the stream has no venue sequence numbers (spec 9.1); continuity cannot be proven client-side, and this keeps the dependency on the live-fills item minimal.
  Date/Author: 2026-10-28, Claude.
- Decision: A complete fills read of `[s, now]` covers `[s, requested-at - :read-settle-ms)`, and only when the number of DISTINCT fill keys among ALL rows returned for `[s, now]` (every coin, before any relevance filter; the reader's overlap pages repeat rows) is below `:venue-fill-retention-count` (10,000). At or above that count the window is a `:gap` with reason `:coverage/retention`. Incomplete reads (`:complete? false`: page failure, no progress, page cap) record a failure interval.
  Rationale: the venue serves only its 10,000 most recent fills, so a large result can silently miss the oldest part of the window. Spec 9.2 forbids inferring completeness from a recent oldest fill or a matching balance.
  Date/Author: 2026-10-28, Claude.
- Decision: A needed interval with no complete read and no recorded failure is `:partial` ("Checking fills…"). One with a recorded failure is `:gap`. A gap heals only if a later complete read covers it.
  Date/Author: 2026-10-28, Claude.
- Decision: Any untracked same-base event at or after `T0 - skew` is an attribution break from `max(t, T0)` onward. This covers fills in the campaign's pair that no non-recovered grid of this campaign owns, fills in any other spot pair that contains the base token, ledger updates whose token is the base token, fills whose fee is in a third token, and fills in an unknown spot market. A break suppresses every window with `b > break-time` for that campaign, permanently. Windows ending at or before the break stay available.
  Rationale: spec 12.6 says do not reset automatically or stitch a broken campaign into a continuous history.
  Date/Author: 2026-10-28, Claude.
- Decision: Fills of a recovered grid (prefixed orders with no local manifest) are untracked.
  Rationale: spec 7.6 says do not infer historical performance from a recovered prefix alone. Campaigns are per device, which is a documented limitation.
  Date/Author: 2026-10-28, Claude.
- Decision: The balance reconciliation check compares the observed base `total` from a barrier read at time `R` with `q0 + sum(tracked base deltas for venue time < R - skew)`. It is evaluated only after a complete fills read requested at least `:balance-check-settle-ms` (30,000 ms) after `R` covers `[T0 - skew, R)`. It is inconclusive when any same-base event falls within `skew` of `R`, or when the campaign already has an untracked event. An exact decimal mismatch is a persisted `:break/unexplained-balance-change` over `(last-match-at, R]`. Matching balances never upgrade coverage.
  Date/Author: 2026-10-28, Claude.
- Decision: Window end `b = floor((now - :mark-settle-ms) / 1h) * 1h`, with a 60,000 ms settle. Hourly marks come from a dedicated `candleSnapshot` 1h read stored under `[:vol-range :report :marks coin]`, not from the chart's `[:candles ...]`. A missing boundary close makes that window's headline unavailable. It is never carried forward and never zero.
  Rationale: spec 12.4. Keeping report reads separate avoids repainting the trade chart.
  Date/Author: 2026-10-28, Claude.
- Decision: 7D and 30D are unavailable, not clipped, when `a < T0` ("7D needs tracking to have started by …"). Since tracking began uses `a = T0`, `P_a = P0` and `q_a = q0`, and is available once `b > T0`.
  Date/Author: 2026-10-28, Claude.
- Decision: **All tracked markets** sums dollar figures only for 7D and 30D, whose `[a,b)` is identical across campaigns. Since tracking began has a different `a` per campaign, so it shows no total ("Totals need the same window for every market. Choose 7D or 30D."). When any included campaign is not headline-eligible, the total is suppressed and an explicit "Show total for covered markets (HYPE, PURR)" control shows the named subset.
  Rationale: spec 12.7 requires the account, unit, interval and coverage to agree.
  Date/Author: 2026-10-28, Claude.
- Decision: Chart checkpoints are hourly for windows of at most 30 days. Since tracking began switches to one checkpoint per UTC day, using the hourly close at each midnight, once the campaign is older than 30 days. Each checkpoint `k` has Net P&L `[a,k)` and Hold P&L `[a,k)`. A missing mark, an uncovered interval or a break before `k` creates a gap, and the series ends at a break.
  Date/Author: 2026-10-28, Claude.
- Decision: The P&L chart reuses `hyperopen.views.chart.d3.runtime` with `:surface :vol-range-report`. If the runtime cannot render gaps, gaps are produced by splitting a series into contiguous segment series with the same stroke; the shared runtime is not changed.
  Date/Author: 2026-10-28, Claude.
- Decision: The dock shows no dollar figures. Each ladder row gains one tracking line ("Tracking since Oct 21, 13:42 UTC" or "Not tracked") and, when reporting is enabled, a **View report** button that selects the campaign and navigates to `/portfolio?tab=vol-range`.
  Rationale: spec 11.2 keeps the dock's fill summary as execution detail, not P&L, and spec 1.3 forbids headline-like figures outside the report.
  Date/Author: 2026-10-28, Claude.
- Decision: The Portfolio tab id is `:vol-range` (label "Vol Range"), ordered after Monte Carlo. It is hidden on `/portfolio/trader/<address>` and when the reporting flag is off. In Spectate it is read-only, with no Start tracking.
  Date/Author: 2026-10-28, Claude.
- Decision: **Start new tracking from now** is a user action that runs inside `action-coordinator/with-account-admission!`, so it cannot race a placement's baseline capture. It refuses while any send attempt for that market is unresolved. It needs a complete barrier read and a complete book read, and it commits the new campaign together with a closure record for the previous open campaign. A live ladder may keep running: its later fills belong to the new campaign by time. It never sends, signs or reserves a nonce.
  Date/Author: 2026-10-28, Claude.
- Decision: There is no pruning in this plan. Ledger and campaign data are retained indefinitely, which satisfies the 90-day retention in spec 7.6; the checkpoint format for future pruning is deferred.
  Date/Author: 2026-10-28, Claude.
- Decision: The reporting effect adapter and actions are registered in the main runtime, like the Vol ladders actions. Views live in the `portfolio_route` module through `account_tabs.cljs`. If `npm run lint:bundle-budget` fails in R6, the effect handlers move behind `portfolio/route_runtime_module.cljs`, following the optimizer runtime catalog.
  Date/Author: 2026-10-28, Claude.
- Decision: The report actions emit at most one heavy effect after their projection writes and are not added to `effect-order-policy-required-action-ids`, so `runtime/effect_order_contract.cljs` (at its ceiling) and the Lean corpus are unchanged.
  Date/Author: 2026-10-28, Claude.
- Decision (integration review): coverage is recorded per account, stream AND base token: record id `coverage:<account>|<stream>|<base-token-id>`. A backfill classifies rows with the set of tracked base tokens it loaded BEFORE its reads were requested, persists the relevant rows, and merges coverage only into those tokens' records. A campaign created while a read is in flight therefore gets no coverage from that read, and its first backfill starts at `T0 - skew - :fills-overlap-ms`.
  Rationale: a single per-account interval would claim that rows of a base token were persisted for a period when that token was not yet tracked (its rows were filtered out as irrelevant), so an untracked same-base trade in a new campaign's inception window could be missed. That would mean an ambiguous boundary not detected and a headline shown over an incomplete interval.
  Date/Author: 2026-10-28, Claude (integrator).
- Decision (integration review): there is ONE durable ledger store, `vol-range-ledger` (IndexedDB version 9), created here in R2. The live-fills item creates no store. Ledger fill records embed the canonical record from `hyperopen.vol-range.application.fill-records/normalize-fill` (live-fills stage 9) under `:fill`, keyed `fill:<fill-key>`. WebSocket rows reach R3's `ledger_ingest` through `hyperopen.vol-range.infrastructure.live-ingress/register-fills-consumer!`. Coverage is only ever written by this plan's REST backfill.
  Date/Author: 2026-10-28, Claude (integrator).
- Decision (integration review): `ledger_store.cljs` has its own small EDN codec (`*print-length*`/`*print-level*` nil, strict read, own `ledger-record-valid?`). `vol_range/infrastructure/journal.cljs` is not changed.
  Rationale: the journal codec is on the real-money path and its `decode` validates with journal rules; coupling the ledger to it adds risk for no gain.
  Date/Author: 2026-10-28, Claude (integrator).
- Decision (integration review): no new timer. Periodic backfill rides the execution watcher's existing 15 s tick: the watcher (or `live_monitor.cljs`, where live-fills stage 11 may have moved the tick) gains `register-tick-listener!`, and `report_watcher` registers one listener. It dispatches `:actions/refresh-vol-range-report` only when the page is visible, reporting is enabled, an open campaign exists for the trading account, and at least `:backfill-interval-ms` passed since the last backfill. Route, tab and account changes are observed with a store watch, as the execution watcher does.
  Rationale: spec 9.3 ("Do not run a separate feature WebSocket or timer registry") and 9.4.
  Date/Author: 2026-10-28, Claude (integrator).
- Decision (integration review): `start-tracking!` necessarily requires `hyperopen.api.trading.action-coordinator` (for `with-account-admission!`). The reporting boundary test therefore forbids `hyperopen.api.trading.*` except `action-coordinator` in `campaign_executor.cljs`, and always forbids `api.trading.delivery`, `api.trading.nonce-coordinator`, `order.*`, `utils.hl-signing` and `vol-range.infrastructure.executor-place`.
  Date/Author: 2026-10-28, Claude (integrator).
- Decision (integration review): every new namespace under `src/hyperopen/vol_range/**` that requires an execution foundation (`journal`, `journal-records`, `reads`, `entropy`, `executor*`, `lifecycle*`, `revalidation`, `wire`, `api.trading.*`) or mentions `hyperopen.platform.indexed-db` or the word `nonce` is added to `execution-foundations` in `test/hyperopen/vol_range/a0_boundary_test.cljs` in the stage that creates it. At minimum this covers `campaign.cljs`, `report_attribution.cljs`, `ledger_store.cljs`, `report_reads.cljs`, `report_executor.cljs`, `campaign_executor.cljs`, `ledger_ingest.cljs` and `report_watcher.cljs`. Views under `views/portfolio/vol_range/` are outside that scan but must not require executor or delivery namespaces (the reporting boundary test checks them).
  Date/Author: 2026-10-28, Claude (integrator).
- Decision (integration review): defaults for questions that do not need the product owner. (a) **All tracked markets** shows no total for Since tracking began. Spec 12.7 requires a common interval, and the copy points to 7D/30D. (b) The `userNonFundingLedgerUpdates` page size is treated as 2,000 and its retention as unknown. The reporting flag stays off in release builds until a protocol fixture captured during the local money test confirms both; the runbook gets that step in R6. (c) The 5 s boundary skew is kept as a versioned policy value (`:boundary-skew-ms`). (d) The bundle placement is decided by `lint:bundle-budget` in R6. (e) Campaigns are per browser, because the journal is per browser (spec 7.4, 7.6). A ladder placed from another device appears as recovered orders and breaks attribution, and the report says so.
  Date/Author: 2026-10-28, Claude (integrator).
- Decision (integration review): this plan runs as stages 20-25 of the global order in `/hyperopen/docs/exec-plans/active/2026-10-28-vol-range-build-order.md`, after Repair, Live fills and Replace. R1 therefore edits an `executor_place.cljs` that already has the settings, predecessor and monitoring changes. A replacement placement (with `:predecessor`) reuses the open campaign (spec 12.1: a campaign spans replacements).
  Date/Author: 2026-10-28, Claude (integrator).

- Decision (R1): the executor draws a campaign id (`random-id! "cmp"`) only when `campaign/campaign-needed?` is true for the journal read inside the admission section, and `baseline-records-for-placement` returns `[]` without an id. A placement that reuses a campaign therefore makes exactly the same id, CLOID and signing-counter calls as before R1.
  Rationale: the plan's sketch drew the id unconditionally; drawing it only when needed keeps the reuse path byte-identical to the pre-R1 path, which is what the snapshot test compares against.
  Date/Author: 2026-10-29, Claude (R1).
- Decision (R1): "reuse" means "an open campaign exists for the key". A replacement (with `:predecessor`) whose market has no open campaign (its predecessor was placed before R1) starts one, because it is then the first admitted placement under tracking. When the predecessor was placed under R1 the open campaign is reused, which is the plan's acceptance case.
  Date/Author: 2026-10-29, Claude (R1).
- Decision (R1): `campaign/campaign-key` returns nil, and no campaign is recorded, for a scope without an account, without a spot pair index or not of market type `:spot`. A missing base token id is an empty key segment rather than a refusal (the builder stays total; see Surprises).
  Date/Author: 2026-10-29, Claude (R1).
- Decision (R1): `contracts/report-policy` is created in R1 with only `:boundary-skew-ms 5000` (the baseline's `:boundary :skew-ms`); R2 adds the remaining keys listed under Data shapes. `ledger-schema-version` and `export-format-version` are left to R2 and R6, which first use them.
  Date/Author: 2026-10-29, Claude (R1).
- Decision (R1): the dock's tracking line uses `campaign/grid-campaign`: recovered orders are "Not tracked"; a ladder created before any campaign of its market is "Not tracked"; a ladder that is not closed shows its market's OPEN campaign (after an R3 restart its later fills belong there by time); a closed ladder shows the campaign that contained its creation. The text is `Tracking since Oct 21, 13:42 UTC` from the new `display/utc-date-minute`. `vol-dock/view-state` carries both `[:vol-range :campaigns]` and `[:vol-range :campaign-closures]`, since the line depends on closures too.
  Date/Author: 2026-10-29, Claude (R1).
- Decision (R1): the "unchanged versus pre-R1" snapshot is the 13.2 simulator digest `vr-act-178def3b035941-858` and reservations `{:usdc "2889.1552" :base "30"}`. The same test places once with an empty journal and once with an open campaign already journaled (the reuse path, which makes no new call compared with pre-R1 code) and asserts identical intents, identical sends and one signing-counter claim each.
  Date/Author: 2026-10-29, Claude (R1).

- Decision (R2): `reads/read-fills-window!` also returns `:reason` (`:fills/read-error`, `:fills/no-progress`, `:fills/page-cap`, nil when complete) next to the unchanged `:error` text, and takes the optional `:priority` (default `:high`). `report_reads/read-ledger-window!` mirrors it with `:ledger/...` reasons. Coverage maps them to `:coverage/no-progress`, `:coverage/page-cap` and `:coverage/read-error`.
  Rationale: additive keys only; the reconciliation caller and `fills_window_test` are unchanged.
  Date/Author: 2026-10-29, Claude (R2).
- Decision (R2): inside the skew-widened inception window, a TRACKED fill is ambiguous only when the operation that placed it was admitted before `T0` (its `:sent-after-ms`, the operation's `:created-at-ms`, is missing or `< T0`) or its venue time is before `T0`. Untracked same-base events, ledger updates of the base and unknown-market fills in the window stay `:break/ambiguous-boundary` (unknown-market fills keep their own reason). The attribution index entry gains `:sent-after-ms` for this.
  Rationale: smallest correction that keeps the plan's intent (a timestamp alone cannot order events at the boundary) without breaking every campaign whose first order fills within 5 s; an order admitted after the barrier read cannot be in `q0`. A user-restart campaign (R3) with a ladder already running still gets the ambiguous break, as planned.
  Date/Author: 2026-10-29, Claude (R2).
- Decision (R2): ledger fill records store the canonical fill only, never its classification. Backfill uses classification only to decide relevance (by base token, which is stable). Tracked versus untracked is recomputed at report time from the current attribution index (`report-attribution/campaign-findings`), so a ladder admitted while a read was in flight never leaves its fills permanently untracked. Ledger update records carry the classification and also `:base-token-id`; every ledger record carries `:account-address` so `load-account!` can filter.
  Date/Author: 2026-10-29, Claude (R2).
- Decision (R2): balance checks. A backfill creates a `:pending` check per open campaign from its complete barrier read, but only when the campaign has no pending check yet, so undecidable checks never pile up. Evaluation also requires LEDGER coverage of `[T0 - skew, R)` (a transfer changes the balance too), and is inconclusive when any break of the campaign (including an earlier mismatch) has a time at or before `R + skew`. A mismatch is a break dated at the last match's `R` (or `T0`), stored in the check as `:last-match-at-ms`. Results are written committed first; removing the pending or inconclusive row uses `idb/delete-key!` afterwards (best effort: a leftover pending check is simply re-evaluated).
  Date/Author: 2026-10-29, Claude (R2).
- Decision (R2): an incomplete or truncated read records the failure interval `[s, R)` (not trimmed by the settle), and a complete read covers `[s, R - :read-settle-ms)`. Backfill reads from the earliest uncovered point of `[earliest campaign start of the token, now)` minus `:fills-overlap-ms`, separately for fills and ledger.
  Date/Author: 2026-10-29, Claude (R2).
- Decision (R2): `backfill!` refuses (`:status :error`, nothing read) when spot metadata has no universe, because every spot fill would otherwise classify as an unknown market. It reads nothing when the ledger cannot be read (`:storage/unavailable`). Any rejection after the balance read is a storage failure (reads never reject) and is `:storage/unavailable`; coverage is merged only after the rows were committed.
  Date/Author: 2026-10-29, Claude (R2).
- Decision (R2): a campaign with no base token id (only the executor simulator's scope) is coverage `:unavailable` with `:baseline/missing`; `campaign/base-total` is made public for balance checks. `ledger_store` functions take an optional `io` map (default: the shared IndexedDB boundary) so tests use an in-memory store, and the executor takes the bound functions as `:ledger` deps.
  Date/Author: 2026-10-29, Claude (R2).
- Decision (R2): `report_coverage.cljs` is classified as an execution foundation too (it requires `lifecycle-evidence` for the one fill key), alongside `report_attribution`, `ledger_store`, `report_reads` and `report_executor`; all five are also listed in `execution-foundation-namespaces`. `report_intervals` and `report_ledger` are pure and stay scanned. A first `test/hyperopen/vol_range/report_boundary_test.cljs` checks that the R2 reporting namespaces require no trading, order, signing, executor, journal-write or entropy namespace and start no timer; R3 extends it with the `action-coordinator` allowance for `campaign_executor.cljs`.
  Date/Author: 2026-10-29, Claude (R2).

- Decision (R3): `campaign-executor/start-tracking!` takes the admission section as an injected `:admission!` dep (exactly like the executor's deps), and the journal and entropy as `:journal`/`:random-id!`. It therefore requires nothing from `hyperopen.api.trading.*`; only the adapter `runtime/effect_adapters/vol_range_report.cljs` wires `action-coordinator/with-account-admission!`. The reporting boundary test forbids every `api.trading` require in all reporting namespaces (no `action-coordinator` allowance needed) and has a separate check that the adapter's only trading require is `action-coordinator` and that it requires no delivery, nonce, order, signing, wire or executor namespace.
  Rationale: stricter than the plan's allowance and testable with the simulator's in-tab admission.
  Date/Author: 2026-10-29, Claude (R3).
- Decision (R3): reporting namespaces may not require `executor`, so `campaign-executor/restart-blockers` re-implements the unresolved-attempt rule of `executor/placement-blockers` (unreadable rows; an unresolved send attempt in any of the market's scope keys whose operation no grid resolved). An active ladder does not block. `campaign_executor_test` checks both functions agree on the same journal.
  Date/Author: 2026-10-29, Claude (R3).
- Decision (R3): a read-only account (Spectate) still loads: `:actions/load-vol-range-report` emits `:load` with `:backfill? false`, which reads only this browser's journal and ledger, never the venue. Refresh, Start tracking and the background backfill emit nothing in read-only mode. The plan's "nothing when read-only" would leave the read-only tab permanently empty.
  Date/Author: 2026-10-29, Claude (R3).
- Decision (R3): the report keeps its own copy of the loaded account's campaigns and closures (`[:vol-range :report :campaigns]`, `:closures`), because the shown account can differ from the trading account (Spectate). A load refreshes `[:vol-range :campaigns]` and `[:vol-range :campaign-closures]` only for the trading account and only from a readable journal, so the dock's tracking line follows a restart and an unreadable journal never empties it. A result for an account no longer shown is dropped; a load that never wrote (a timeout) marks the account loaded with nothing known, so the watcher does not retry on every store change.
  Date/Author: 2026-10-29, Claude (R3).
- Decision (R3): the dock's **View report** dispatches `[[:actions/select-vol-range-report-campaign k] [:actions/set-portfolio-account-info-tab :vol-range] [:actions/navigate "/portfolio"]]`: navigation drops a query string (Surprises), so the existing tab action selects the tab first. The market select's All tracked markets value is `"all"`, which the select action maps to nil (the arg spec `(s/nilable ::common/non-empty-string)` accepts both).
  Date/Author: 2026-10-29, Claude (R3).
- Decision (R3): R3 shows coverage without windows: each campaign is evaluated over `[T0 - skew, through)`, where `through` is the earlier of the two streams' last complete coverage end ("Fills checked through …"), capped at the closure time for an ended campaign. When either stream was never read completely, or a break lies at or after `through`, it is evaluated to now (so a break seen in stream rows shows at once). Evaluating to now always would show "Checking" permanently for the few seconds after every read. R4's headline windows evaluate to their own `b`.
  Date/Author: 2026-10-29, Claude (R3).
- Decision (R3): the R3 headline card shows only "Figures appear after the first completed hourly close (hh:00 UTC)." while every shown open campaign has `b <= T0` (with `b = floor((now - :mark-settle-ms)/1h)×1h`), and nothing otherwise; the view model's `:headline` is `{:status :pending|:unavailable :message}` and R4 extends it with figures.
  Date/Author: 2026-10-29, Claude (R3).
- Decision (R3): `contracts/reporting-enabled?` is the preview flag AND `VOL_RANGE_REPORTING` (on in `:app :dev`, `:test` and `:vol-range-test`; off in release). Without placement built there is no tick listener and no stream ingest; opening the tab still loads and backfills.
  Date/Author: 2026-10-29, Claude (R3).
- Decision (R3): R3 controls (the market select and Refresh) live in `views/portfolio/vol_range_report.cljs`; R4's `controls.cljs` takes them over together with the window buttons. `console_preload/vol_range_fixture.cljs` is not changed in R3: the R3 browser scenario places through the simulator and streams a fill, so seeded report scenarios arrive with R4, which first needs them.
  Date/Author: 2026-10-29, Claude (R3).
- Decision (R3): `ledger-ingest` keeps stream rows from the earliest open campaign start minus `:fills-overlap-ms` on, skips rows already in the loaded report ledger, and does nothing without spot metadata (the backfill classifies those rows later). The Portfolio view's hover cache key includes `[:vol-range :report]` (spec 11.3 cached-section invalidation). Activity rows of an ended tracking period read "HYPE/USDC, until …" so they are told apart from the current one.
  Date/Author: 2026-10-29, Claude (R3).
- Decision (Reporting 1 review): backfill coverage can always move forward, and one unhealable gap no longer holds every later read back. A read stopped by the page cap or no progress proves `[s, newest row)`, since it paged forward from `s`. A fills read with at least 10,000 distinct keys proves what follows its oldest row and records `[s, oldest + 1)` as a PERMANENT `:coverage/retention` failure (`:permanent? true`): the venue never serves those fills again, so `report-coverage/read-start` skips that time and a later read can never claim it complete (`merge-coverage` subtracts permanent time from new proofs only; what an earlier read proved stays proven). `read-start` moved to `report-coverage` and needs each campaign only up to its closure time. A coverage record keeps its newest 20 permanent and newest 20 other failures, and the permanent reason explains its time first.
  Rationale: R2 kept the whole read `[s, R)` as a retention failure and read every token from the earliest uncovered point of all campaigns, closed ones included, so a single retention or page-cap failure left every later read failing the same way.
  Date/Author: 2026-10-29, Claude (Reporting 1 review).
- Decision (Reporting 1 review): stored ledger rows that no longer decode are unresolved, as the journal treats them. `ledger-store/load-account!` keeps the ones whose stored envelope names the account or names none. `report-attribution/unreadable-row` reads the kind, time and token from the record id. `campaign-findings` returns the ones that could concern the campaign as `:unreadable [{:time :record-id}]`: a fill or ledger row of its base (or of an unknown market) inside the campaign at its time, the token's coverage record, or the campaign's balance check from `T0 - skew`, as is a row whose id cannot be read. From the earliest one, coverage is a `:storage/unreadable-rows` gap, and balance checks treat it as a break (inconclusive). Backfill reads again from the earliest unreadable fill or ledger row of a tracked token, which rewrites it from the venue.
  Date/Author: 2026-10-29, Claude (Reporting 1 review).
- Decision (Reporting 1 review): a REST fill row that `normalize-fill` rejects, in a spot market containing a tracked base (or an unknown or missing coin), and a ledger row without a numeric time, a delta map or a type (USDC-only types aside), end the read's proof for the tokens it could concern at the row's time (`:coverage/unparsed-row`, the read's start when the time itself is unusable). Perp and HIP-3 rows never matter.
  Date/Author: 2026-10-29, Claude (Reporting 1 review).
- Decision (Reporting 1 review): ledger fees count as base changes. For every non-USDC-only delta, a non-zero `fee` in a tracked `feeToken` and a non-zero `nativeTokenFee` (paid in `HYPE`, `report-attribution/native-token-name`) add a `:base-transfer` record for that token, once per token. A base-transfer type with no `:token` is classified by the tokens it names.
  Date/Author: 2026-10-29, Claude (Reporting 1 review).
- Decision (Reporting 1 review): `report-reads/read-ledger-window!` no longer treats a page shorter than 2,000 rows as the end. The venue's page size is unknown, so the read ends only on an empty page or one that adds no row it already read (the overlap repeat). This costs one more request per read. A page of at least `:ledger-page-limit` rows that all share the start time is still `:ledger/no-progress`. The residual risk is more rows in one millisecond than the true page size.
  Date/Author: 2026-10-29, Claude (Reporting 1 review).
- Decision (Reporting 1 review): a tracked fill inside the inception boundary stays a `:break/ambiguous-boundary` but is now also an activity event. The exception is a fill before `T0` of a campaign that superseded another (`:supersedes-campaign-id`), because the ended campaign's interval, which ends at this `T0`, already counts it. The break still suppresses figures and balance checks from `T0`.
  Date/Author: 2026-10-29, Claude (Reporting 1 review).
- Decision (Reporting 1 review): `report-executor/backfill!` resolves `:status :incomplete` with the reason as `:error` when the fills or ledger read did not complete, a relevant row could not be parsed, or retention dropped fills. The live region then says "Couldn't load fills: {error}." instead of "Report updated.". Every other key of the result is unchanged.
  Date/Author: 2026-10-29, Claude (Reporting 1 review).

- Decision (R4): the default window is **Since tracking began**, not 30D. The plan's data-shape example uses `:30d`, but a new campaign would then open on an unavailable window for 30 days, and the R3 first-hourly-close message belongs to Since tracking began.
  Date/Author: 2026-10-29, Claude (R4).
- Decision (R4): `:marks` takes `{:requests [{:coin :from-ms :to-ms}]}` (candle OPEN times), one request per coin, so All tracked markets needs one effect. `report-windows/mark-requests` asks only for the opens of `[hour-floor(a − 1), b)` that the stored marks have not proved (this already covers R5's hourly checkpoints). A load or backfill reads them after it reloads, inside the same operation, so `:actions/refresh-vol-range-report` still emits one effect. A new no-arg `:actions/refresh-vol-range-report-marks` is dispatched by the report watcher's existing tick listener (no new timer) when the visible window needs a newly completed hour, at most once a minute (`marks-retry-ms`); the window action emits the same effect after its write. A successful marks read does not overwrite `:last` (the live region keeps "Report updated."); a failed one says "Couldn't load hourly prices: …".
  Date/Author: 2026-10-29, Claude (R4).
- Decision (R4): stored marks are `{:closes {open close} :covered [[from to)] :fetched-at-ms :error}` instead of `:fetched-through-ms`. A complete read requested at R proves opens `[from, min(to, R − 1h + 1))`; only candles whose `open + 1h <= R`, with an hour-aligned open and a positive plain decimal close, inside the requested opens, are kept. `mark-at` distinguishes `:ok`, `:missing` (proven hour without a close: "No completed hourly price for …"), `:error` (the last read failed: "Couldn't load the hourly price for … Refresh to try again.") and `:pending` ("Loading hourly prices…"). Marks are public market data: they need no account and are read for read-only (Spectate) reports too, so R3's read-only load test now asserts no ACCOUNT read rather than no read.
  Date/Author: 2026-10-29, Claude (R4).
- Decision (R4): headline figures exist only for OPEN campaigns (an ended campaign keeps its activity and coverage). `report-windows/campaign-window-figures` adds `:status` and `:reason` to the plan's figures shape, in the order window (`:window/no-completed-mark` is `:pending`, `:window/before-tracking` `:unavailable`), coverage to `b` (`:unavailable` reasons, `:attribution-break` and `:gap` are `:suppressed`, `:partial` is `:pending`), then the two marks. `report-model` evaluates `campaign-coverage` to each window's `b` with the same findings as the campaign card.
  Date/Author: 2026-10-29, Claude (R4).
- Decision (R4): All tracked markets with exactly one open campaign shows that campaign as it is (the interval trivially agrees). With more than one: Since tracking began has no total; 7D/30D sum only when every campaign is `:ok` with the identical `[a,b)` and network; otherwise no total, naming the others ("Totals are hidden because VRS isn't fully covered for this window."), with the covered subset offered when at least one is covered and summed (titled "Covered markets: VRT") only when chosen. When every market is before tracking for a fixed window the before-tracking message of the most recent start is shown instead of the no-total note.
  Date/Author: 2026-10-29, Claude (R4).
- Decision (R4): the headline view model is `{:status :title :figures :benchmark-label :range-text :message :no-total :subset-offer}` with figures `{:net-pnl :hold-pnl :contribution}` each `{:label :display :tone}` and the offer `{:label :active?}`. Its formatting lives in a new pure `application/report_headline.cljs` (not in the plan) so `report_model.cljs` stays small. USDC is shown with two decimals, grouping, "+" for positive and U+2212 for negative, by the rounded value. Figure values carry `data-role` `vol-range-report-<figure>-value`; the card title `vol-range-report-headline-title`.
  Date/Author: 2026-10-29, Claude (R4).
- Decision (R4): report scenarios are seeded by a new `telemetry/console_preload/vol_range_report_fixture.cljs` (`seedVolRangeFixture({report})` delegates to it, so `vol_range_fixture.cljs` grows by two lines): "headline-buy", "external-transfer" and "two-markets", committed through `journal/commit!` and `ledger-store/put-records!` with coverage built by `report-coverage/merge-coverage`. Seeding again in the same browser only refreshes the spot metadata, because recommitting at a later `now` would add a second copy of the fills. The R4 browser tests seed without a wallet connection (the fixture sets `[:wallet :address]`), which avoids the wallet-connect setup flake.
  Date/Author: 2026-10-29, Claude (R4).

- Decision (R5): the chart caption is "Dollar P&L of the tracked inventory, not your account value. Hourly closes; gaps mark missing prices or unconfirmed fills." (daily: "Daily points at the 00:00 UTC hourly close; …"), not the plan's "Not account equity. …".
  Rationale: R5's acceptance says no "equity" string exists in the chart spec or DOM, and the R3/R4 browser tests assert the report text never matches `/equity/i`; the caption keeps the meaning without the word.
  Date/Author: 2026-10-29, Claude (R5).
- Decision (R5): the chart and the matched disclosure describe exactly what the headline describes. One market: its campaign; the chart is drawn whenever the window resolves and `P_a` exists, also while the headline is hidden, up to the first unconfirmed time or break (spec 12.6 "previous complete intervals"); the matched disclosure only when the headline figures are shown. All tracked markets: only when a total is shown (all markets, or the chosen covered subset), the chart summing the included markets checkpoint by checkpoint (a checkpoint only when every market has it) and one matched block per included market. Since tracking began under All tracked markets therefore has neither.
  Date/Author: 2026-10-29, Claude (R5).
- Decision (R5): checkpoints. The series starts at `a` with zero; then `a < k <= b` hourly, or (Since tracking began longer than 30 days) every UTC midnight plus `b`. A checkpoint is drawn when its hourly close is `:ok` and `k` is at or before the earliest non-complete coverage interval start or break of the headline's coverage to `b` (coverage need `[T0 − skew, k)`, break rule `b <= t`). Because P&L is cumulative, an unconfirmed interval ends the series rather than leaving an interior gap; only missing closes create interior gaps. `report-chart-model/states-at` reconstructs `{q c}` at all checkpoints in one pass and is tested equal to `state-before`. The last checkpoint equals the headline figures.
  Date/Author: 2026-10-29, Claude (R5).
- Decision (R5): the matched disclosure's formatting lives in a new pure `application/report_matched.cljs` (not in the plan), as R4 did with `report_headline.cljs`, so `report_model.cljs` stays small. Its "= Ladder contribution versus hold" row appears only when the matching total equals the headline contribution exactly; a fee in a third token makes the total nil (attribution already breaks there). Rows have data roles `vol-range-report-matched-<row>` and `…-value`; the fees line is `vol-range-report-matched-fees-paid`.
  Date/Author: 2026-10-29, Claude (R5).
- Decision (R5): the disclosure is a `<details>` whose `open` follows `[:vol-range :report :ui :matched-open?]` (default closed); its summary click dispatches `[:actions/set-vol-range-report-ui-flag :matched-open? (not open?)]`. There is no `:event.target/open` placeholder, and rendering is frame-batched, so the native toggle and the re-render agree. The action accepts only `:matched-open?`.
  Date/Author: 2026-10-29, Claude (R5).
- Decision (R5): the d3 spec is built in `views/portfolio/vol_range/pnl_chart.cljs` from the memoized view model with `:surface :vol-range-report`, `:update-key` from `chart-runtime/spec-update-key`, `:tooltip-key` = the view model's tooltips and `:time-range [cadence x-labels]`, so the cached `on-render` handler is reused until the chart changes and replaced (one cache entry per surface) when the window changes. Net uses `--ho-accent` and Hold `--ho-text-secondary`; the runtime has no dashes, so both legend swatches are solid lines of their colors.
  Date/Author: 2026-10-29, Claude (R5).
- Decision (R5): a "matched-fees" scenario in `console_preload/vol_range_report_fixture.cljs` (T0 3 h ago, buy 10 at 80 paying 0.1 VRT, sell 9.9 at 90; with closes of 100: contribution 91, Net 3,091) drives the R5 browser test.
  Date/Author: 2026-10-29, Claude (R5).

- Decision (R6): `report-export/json-document` takes `{:report :spot-meta :account-address :now-ms}` and exports every campaign of the loaded account (open and ended), not only the selected market; the UI selection is recorded under `:selection`. Each campaign carries its baseline, closure, coverage to the export time (or its closure), breaks, unreadable rows, tracked fill keys, activity and, for open campaigns, all three windows (`resolve-window` plus `campaign-window-figures` with coverage to each `b`: boundaries, `:a-source`, both marks with source and candle open or observation time, `q`/`C` at both ends, figures or the status and reason). Top level: format id `hyperopen.vol-range.report`, `:format-version` (`contracts/export-format-version` 1), `:versions` (export format, accounting model, campaign and ledger schema, model, precision, estimator), `:valuation` (the method in words and the three equations), `:policy`, `:storage`, `:orders` (one row per CLOID or oid of the attribution index: ladder, operation, logical leg, side, rung, campaign key, recovered, admission time, predecessor ladder), `:fills` (the canonical record, market label, tracked, campaign, order mapping, signed deltas and fee kind for tracked fills, and the breaks an untracked fill caused), `:ledger-updates`, `:coverage-records`, `:balance-checks` and `:marks` (closes by candle open, proved intervals, fetch time).
  Rationale: a reproducible bundle must hold everything the figures were computed from, independent of the current view.
  Date/Author: 2026-10-29, Claude (R6).
- Decision (R6): keys are whitelisted per record, and `report-export/sanitize` then removes, at every depth, any key whose normalized name (lower case, no `-`, `_` or spaces) is `privatekey`, `signature(s)`, `session`, `agentkey`, `secret`, `unlockedsession`, `nonce`, `signedaction`, `mnemonic`, `seedphrase`, `apikey` or `apisecret`. This also covers the raw venue delta of ledger updates, which is exported as stored otherwise. JSON text keeps keyword namespaces (`"break/base-transfer"`), unlike `clj->js`.
  Date/Author: 2026-10-29, Claude (R6).
- Decision (R6): the CSV has 22 columns: Time (UTC) as ISO 8601, Market, Coin, Side, Price, Size, Fee, Fee token, Builder fee, Liquidity (Maker/Taker), Tracking (Tracked/Not tracked), Tracking ID, Ladder, Operation, Order leg, Rung, Client order ID, Order ID, Trade ID, Transaction hash, Source and Outside activity (the break reasons the fill caused). Cells are RFC 4180 quoted with CRLF line ends; text starting with `=`, `+`, `-`, `@`, tab or CR gets a leading `'` unless it is a plain decimal, so negative fees stay numbers and the spot coin reads `'@9001`.
  Date/Author: 2026-10-29, Claude (R6).
- Decision (R6): file names are `hyperopen-vol-range-report-<first 8 address chars>-<YYYYMMDDTHHmmZ>.json` and `hyperopen-vol-range-fills-…csv`. `:actions/export-vol-range-report` emits `[:effects/vol-range-report :export {:format :account-address}]` only when the shown account's report is loaded with at least one campaign and no export runs; read-only (Spectate) accounts may export, since the export reads only this browser's stored report. The adapter builds the file from the store and downloads it with Blob, object URL and anchor (`download-text!`), never reads the venue, announces "Export downloaded." or "Couldn't export: …", and a failed export never marks the account as loaded (`finish!` skips `:export` like `:marks`).
  Date/Author: 2026-10-29, Claude (R6).
- Decision (R6): the view model's `:export` is always `{:enabled?}` (not nil), and the two buttons sit in the controls row after the coverage chip, in a labeled group, so the keyboard order is market, the three windows, Refresh, Export report (JSON), Export fills (CSV).
  Date/Author: 2026-10-29, Claude (R6).
- Decision (R6): the plan's "order and revision mappings" are the `:orders` rows (each CLOID and oid with its logical leg, so a repaired leg's CLOIDs map to one logical id) plus `:predecessor-grid-id`, added to the attribution index entry (one test expectation updated). No journal grid is copied into the report state.
  Date/Author: 2026-10-29, Claude (R6).
- Decision (R6): the bundle budget is recorded, and the route-runtime fallback is not applied. `lint:bundle-budget` is a soft, advisory target that never fails, and the release main module was already 251,123 bytes gzip over its 640,000 target before reporting (891,123 at `ee7cf93be`). Reporting R1 to R5 add 24,026 bytes (915,149 at `a21809a59`) and R6 8,776 (923,925). The fallback as planned (the report effect behind `portfolio/route_runtime_module.cljs`) would break the background backfill and stream ingest, which run on Trade while the Portfolio module is not loaded; only `:export` could move, for under 9 KB. Shrinking main is left to the bundle-diet plan.
  Date/Author: 2026-10-29, Claude (R6).
- Decision (R6): the local money-test runbook gains "Vol Range report (Portfolio)", "Export the report" and "Save ledger responses as protocol fixtures" (the `userNonFundingLedgerUpdates` page size and retention question from the integration review, with the reporting flag kept off in release until it is answered), plus two known limits.
  Date/Author: 2026-10-29, Claude (R6).

## Outcomes & Retrospective

- R1 (2026-10-29): new `vol_range/application/campaign.cljs` (key, total baseline builder, closure record, open and by-time queries, `grid-campaign`, `campaign-needed?`, `baseline-records-for-placement`); `executor_place/admit-placement!` adds the baseline to the intent commit (`commit-admitted!` takes optional extra records; repair unchanged); `contracts.cljs` gains `accounting-model-version`, `campaign-schema-version` and `report-policy`; `sync-once!` stores the account's campaigns and closures; the dock row has a `vol-ladder-tracking` line and `view-state` carries campaigns and closures. `campaign.cljs` is classified in `a0_boundary_test` (it requires `journal-records`) and listed as a namespace preview code may not require. Tests: `campaign_test`, `campaign_capture_test` (one commit holding grid, intent and campaign before `send!`; boundary equals the barrier read; second placement, repair and replacement add none; rejected commit sends nothing; monitoring and predecessor refusals commit nothing; digest snapshot), `dock_model_test`, `dock_test` and `vol_range_slice_repaint_test` additions. Gates: `npm run test:vol-range` 1281 tests 0 failures; `npm test` 7796 tests 0 failures; `npm run test:websocket` 609 tests 0 failures; `npm run check` PASS. Playwright (no spec required by R1; run because placement and the dock changed): `vol-range.spec.mjs`, `vol-range-execution.spec.mjs`, `vol-range-repair-replace.spec.mjs`, `vol-range-live.spec.mjs` 29/29 on the first run, no flakes. Deviations: see the R1 entries in the Decision Log.

- R2 (2026-10-29): new pure `domain/report_intervals.cljs` (half-open interval algebra) and `domain/report_ledger.cljs` (signed fill events, fee kinds, ordering, `state-before`, activity); `application/report_attribution.cljs` (attribution index from grids, spot-market lookups, `classify-fill`, `relevant-fill?`, `ledger-update-records`, per-campaign `campaign-findings` with every break reason); `application/report_coverage.cljs` (read-to-coverage deltas with the distinct-key retention rule, monotonic `merge-coverage`, per-stream and combined intervals with precedence, `campaign-coverage` with headline gating, balance-check creation and evaluation); `infrastructure/ledger_store.cljs` (own EDN codec and `ledger-record-valid?`, committed `put-records!`, `load-account!`, `merge-coverage!` and `put-balance-check!` under the account lock); `infrastructure/report_reads.cljs` (`read-ledger-window!`, `read-hourly-candles!`, low priority); `infrastructure/report_executor.cljs` (`load!`, `backfill!`). `indexed_db.cljs` adds the `vol-range-ledger` store at `app-db-version` 9; `reads/read-fills-window!` gains optional `:priority` and a `:reason` key; `contracts.cljs` gains `ledger-schema-version` and the full `report-policy`. Five new namespaces classified in `a0_boundary_test`; new `report_boundary_test`. Tests: `report_intervals_test`, `report_ledger_test` (13.5 rows 3, 4 and 6 at event level), `report_attribution_test`, `report_coverage_test` (retention, gaps, healing, row 8, ambiguous boundary, break suppression, balance checks), `report_reads_test`, `ledger_store_test`, `report_executor_test` (balance read first, idempotent persistence, gaps and healing through backfill, per-token coverage with a campaign created mid-read, balance checks end to end, storage failure, never rejects), `indexed_db_versioning_test` addition, and `report_fixtures.cljs`. Gates: `npm run test:vol-range` 1339 tests 0 failures; `npm test` 7855 tests 0 failures; `npm run test:websocket` 609 tests 0 failures; `npm run check` PASS. Playwright (no spec required by R2; run because the IndexedDB version and the fills reader changed): `vol-range.spec.mjs`, `vol-range-execution.spec.mjs`, `vol-range-repair-replace.spec.mjs`, `vol-range-live.spec.mjs` 29/29 on the first run, no flakes. Deviations: see the R2 entries in the Decision Log.

- R3 (2026-10-29): `config.cljs` `VOL_RANGE_REPORTING` (on in `:app :dev`, `:test`, `:vol-range-test`; off in release) and `contracts/reporting-enabled?`; `[:effects/vol-range-report op params]` (`runtime/effect_adapters/vol_range_report.cljs`, ops `:load` (then a backfill), `:backfill`, `:start-tracking`; the arg spec lists all five ops) with its registration file, arg specs, `report-action-deps` and `app/effects.cljs` entry; pure `vol_range/report_actions.cljs` (load, refresh, market select, request/cancel/confirm Start tracking); `infrastructure/campaign_executor.cljs` (`start-tracking!` inside the admission section, one commit of successor plus closure, never a send); `infrastructure/ledger_ingest.cljs` (stream fills into the ledger, never coverage); `infrastructure/report_watcher.cljs` (coalesced load on tab or account change, backfill on the execution watcher's tick via its new `register-tick-listener!`, the fills consumer); `application/report_model.cljs`, `report_copy.cljs`, `schema/vol_range_report_contracts.cljs`; views `views/portfolio/vol_range_report.cljs` and `vol_range/{coverage,activity,tracking}.cljs`; the `:vol-range` Portfolio tab after Monte Carlo (hidden on trader routes and with the flag off; alias `:volrange`; `portfolio/actions.cljs` 858 lines); the dock's View report; the Playwright world stubs `userNonFundingLedgerUpdates`. Tests: `report_actions_test`, `campaign_executor_test`, `ledger_ingest_test`, `report_watcher_test` (also the tick listeners), `report_model_test`, `views/portfolio/vol_range_report_test`, `runtime/effect_adapters/vol_range_report_test`, `registration_test`, `dock_model_test`, `dock_test`, `portfolio_view_test` (tab order) additions; `a0_boundary_test` classifies the four new execution-dependent namespaces and `report_boundary_test` scans every reporting namespace and view and the adapter. Gates: `npm run test:vol-range` 1379 tests 0 failures; `npm test` 7895 tests 0 failures; `npm run test:websocket` 609 tests 0 failures; `npm run check` PASS. Playwright: new `vol-range-report.spec.mjs` (place through the simulator, stream a fill, View report, baseline "with 150 VRT at 80.00 USDC (book midpoint)", Cost basis unavailable, the first-hourly-close message, activity 1 fill / 2 VRT / 0.0004 VRT, Start tracking with its confirmation, no new signed action, zero real exchange requests), then with `vol-range.spec.mjs`, `vol-range-execution.spec.mjs`, `vol-range-repair-replace.spec.mjs` and `vol-range-live.spec.mjs` 30/30 in one run, no flakes. Deviations: see the R3 entries in the Decision Log.

- R4 (2026-10-29): new pure `domain/report_equations.cljs` (`window-figures`, `sum-figures`, `identity-holds?`), `domain/report_marks.cljs` (`hour-floor`, `window-end`, `normalize-candles`, `merge-read`, `mark-at`, `needed-opens`, `hour-boundaries`), `application/report_windows.cljs` (`resolve-window`, `campaign-window-figures`, `mark-requests`), `application/report_aggregate.cljs` (`aggregate`), `application/report_headline.cljs` (headline formatting); views `views/portfolio/vol_range/{controls,headline}.cljs`; `report_model.cljs` computes each open campaign's window figures with coverage to `b` and adds `:window`; `report_copy.cljs` gains the window, figure, benchmark and suppression texts; `schema/vol_range_report_contracts.cljs` validates `:window` and the full headline; the adapter's `:marks` op (and marks after load/backfill); `report_actions.cljs` `select-vol-range-report-window`, `set-vol-range-report-covered-subset`, `refresh-vol-range-report-marks` with arg specs, registration rows and collaborators; `report_watcher.cljs` `marks-due?` on the existing tick; `console_preload/vol_range_report_fixture.cljs` with three seeded scenarios; the Playwright world answers 1h `candleSnapshot` (`world.hourlyCloses`). Tests: `report_equations_test` (13.5 rows 1 to 3, a subwindow, sums), `report_marks_test`, `report_windows_test` (rows 1 to 3, 6 and 7, 7D/30D before tracking, a missing close, coverage gating, mark requests), `report_aggregate_test` (row 9, the suppressed total and covered subset, no Since-tracking total), `report_headline_test`, plus additions to `report_actions_test`, `report_watcher_test`, `report_model_test`, `vol_range_report_test` (view), the adapter test and `registration_test`; `report_boundary_test` scans the five new namespaces. Gates: `npm run test:vol-range` 1423 tests 0 failures; `npm test` 7939 tests 0 failures; `npm run test:websocket` 609 tests 0 failures; `npm run check` PASS; `lint:class-attrs` and `lint:style-keys` clean. Playwright: `vol-range-report.spec.mjs` gains three R4 tests ("headline-buy": −1,544.80 / −1,500.00 / −44.80 for Since tracking began, 7D and 30D before tracking, layout at 375 and 1440; "external-transfer": the suppressed status with activity still shown; "two-markets": no Since-tracking total, the 7D total hidden for VRS and the covered subset named and summed, one market selected); then `vol-range.spec.mjs`, `vol-range-execution.spec.mjs`, `vol-range-repair-replace.spec.mjs`, `vol-range-live.spec.mjs` and `vol-range-report.spec.mjs` 33/33 in one run, no flakes; zero real exchange requests; `npm run browser:cleanup` clean. Deviations: see the R4 entries in the Decision Log and Surprises.

- R5 (2026-10-29): new pure `domain/report_matching.cljs` (`match-window` FIFO over gross quantities in either order with partial splits and pair counts, unmatched buys and sold inventory not reaccumulated at the end mark, USDC and base fee impact, `reconciles-with?`), `application/report_chart_model.cljs` (`checkpoints` hourly or daily, `clean-until`, one-pass `states-at`, `item-figures`, `chart-view` with segment series, y ticks, hover points and tooltips), `application/report_matched.cljs` (the disclosure's rows); views `views/portfolio/vol_range/{pnl_chart,matched}.cljs` on the shared d3 runtime (`:surface :vol-range-report`); `report_model.cljs` builds the headline, chart and matched disclosure from the same window items (`:chart`, `:matched`); `report_copy.cljs` gains the chart and matched texts; `schema/vol_range_report_contracts.cljs` validates `:chart` and `:matched`; `report_actions.cljs` `set-vol-range-report-ui-flag` (`:matched-open?`) with its arg spec, registration row and collaborator; the "matched-fees" fixture scenario. Tests: `report_matching_test` (13.5 row 5: 99 / 2 / −10 / 91 = 891 − 800; sell then buy; partial splits and pairs; sold inventory; a rebate; window membership; a third-token fee; a 150-case property that the reconciliation equals the headline contribution for random USDC/base-fee fill sequences), `report_chart_model_test` (hourly and daily checkpoints, `states-at` = `state-before`, a missing close as a gap with the last point equal to the headline, the series ending at a break and at an unconfirmed interval, no chart without a resolved window, All tracked markets sums, no "equity"), `report_matched_test` (row 5 in the view model, the disclosure and chart following the headline's market and window), plus additions to `report_actions_test`, `registration_test`, the view test (chart host, tooltip model, no equity in spec or DOM, the memoized `on-render` handler reused and replaced on a window change, the disclosure rows and toggle) and `report_boundary_test` (three new namespaces scanned); `report_fixtures` gains the row 5 fills. Gates: `npm run test:vol-range` 1442 tests 0 failures; `npm test` 7958 tests 0 failures; `npm run test:websocket` 609 tests 0 failures; `npm run check` PASS; `lint:class-attrs` and `lint:style-keys` clean. Playwright: new R5 test in `vol-range-report.spec.mjs` ("matched-fees": +91.00 / +3,091.00 headline; the chart's axis title "Cumulative P&L (USDC)", both series drawn, no "equity" in its text or HTML, the hover tooltip; the disclosure collapsed, then 9.9 VRT in 1 matched pair, +99.00 / +2.00 / −10.00 / +91.00 and the fees paid; 7D removes the chart and the disclosure and Since tracking began redraws it; 375 and 1440 with no horizontal scroll); then all five Vol Range specs in one run 33/34, the one failure `vol-range-live.spec.mjs` "a ladder the stream closes is persisted…" stopping in `startTrade` setup (the ticket market not yet loaded, before any Vol Range code ran), which passed when rerun alone: recorded as the setup flake. Zero real exchange requests. Deviations: see the R5 entries in the Decision Log and Surprises.

- R6 (2026-10-29): new pure `application/report_export.cljs` (`json-document`, `sanitize`/`forbidden-key?`, `json-text` keeping keyword namespaces, `fills-csv` with RFC 4180 and formula-safe cells, `filename`); `contracts/export-format-version`; the adapter's `:export` op (`export-file`, `download-text!` with Blob, object URL and anchor; never reads the venue; a failed export never marks the account loaded); `report_actions/export-vol-range-report` and `export-available?` with its arg spec, registration row and collaborator; the view model's `:export {:enabled?}` and its contract; the two export buttons in the controls row; "Export downloaded." / "Couldn't export: …"; `report-attribution/attribution-index` entries gain `:predecessor-grid-id`; `report_export` classified in `a0_boundary_test` and scanned by `report_boundary_test`; the runbook's report, export and ledger protocol-fixture sections. Tests: `report_export_test` (format, versions, valuation and top-level keys; baseline, coverage and the three windows with 13.5 row 1 figures, marks and boundary state; a closed campaign's closure; tracked and untracked fills with order mapping, signed fee and deltas and breaks; orders with a predecessor; marks, coverage records and a sanitized raw ledger delta; no forbidden key at any depth; JSON keeps namespaced keywords; the CSV header, rows, quoting and formula guard; file names; determinism), plus additions to `report_actions_test`, `registration_test`, the adapter test (both downloads with stubbed document, URL and Blob, no venue read, the error path), the view test (buttons, disabled while busy, read-only, none without tracking), `report_attribution_test` and `report_chart_model_test`. Gates: `npm run test:vol-range` 1458 tests 0 failures; `npm test` 7974 tests 0 failures; `npm run test:websocket` 609 tests 0 failures; `npm run check` PASS; `lint:docs`, `lint:namespace-sizes`, `lint:hiccup`, `lint:class-attrs`, `lint:style-keys`, `lint:theme-colors` clean. Bundle budget (soft) after a release build into a scratch directory: main 923,925 bytes gzip against 640,000 (already 891,123 before reporting; see the Decision Log). Playwright: two new R6 tests in `vol-range-report.spec.mjs` (both downloads: the JSON parses with `format-version` 1, the 13.5 row 1 figures, the order mapping and no forbidden key; the CSV header row and the tracked fill row; "Export downloaded."; the tab at 375, 768, 1280 and 1440 with no horizontal scroll; Tab order market, windows, Refresh, the two exports; Enter and Space on the window buttons; the polite live region after Refresh); then `vol-range-report.spec.mjs`, `vol-range.spec.mjs`, `vol-range-execution.spec.mjs`, `vol-range-repair-replace.spec.mjs` and `vol-range-live.spec.mjs` 36/36 in one run, no flakes; zero real exchange requests; `npm run browser:cleanup` clean. The plan moved to `docs/exec-plans/completed/`. Deviations: see the R6 entries in the Decision Log and Surprises.

## Scope and Non-goals

In scope:

- Campaign creation at the first admitted placement and on explicit user restart; campaign closure on restart.
- A durable same-base fill and ledger collection for accounts with campaigns, REST backfill with coverage intervals, and balance reconciliation checks.
- Coverage states (`:complete`, `:partial`, `:gap`, `:attribution-break`, `:unavailable`) with intervals and typed reasons, plus headline suppression.
- Headline equations for Since tracking began, 7D and 30D; hourly marks; cross-asset aggregation.
- The FIFO matched-fill spread diagnostic with unmatched marks and the fee reconciliation view.
- The cumulative P&L chart, export (JSON bundle and fills CSV), the Portfolio Vol Range tab, and the dock tracking line.
- A `VOL_RANGE_REPORTING` build flag (spec 15.2): on in dev, test and Playwright builds, off in `shadow-cljs release`.

Non-goals:

- Any change to order construction, admission rules, the nonce coordinator, signing, cancel or repair semantics. The only real-money-path change is adding baseline records to the existing intent commit (R1).
- Fill markouts and the Spectate lens (spec 12.8 optional extensions).
- Historical acquisition or tax cost basis. The report always says **Cost basis unavailable**.
- Pruning and accounting checkpoints (spec 7.6). Nothing is pruned.
- Percentage returns, equity curves, return denominators or capital-efficiency figures.
- Cross-device campaign sync. Campaigns live in this browser's journal.
- Non-USDC quotes, perps, and unified or portfolio-margin accounts (already excluded by Release A capability gates).

## Spec coverage and deliberate deviations

| Spec | How this plan implements it | Deviation |
| --- | --- | --- |
| 12.1 | `campaign.cljs` baseline at the first admitted placement, in the same transaction as the intent. The key is `network\|account\|spot\|base-token-id\|spot-pair-index`. | `P0` is the admission book midpoint (source `:book-midpoint`), not a separate mid read. |
| 12.2 | `report_ledger.cljs` signed deltas; the fee is subtracted from its actual token; `builderFee` is already included in `fee`; a third-token fee is a break. | None. |
| 12.3 | `report_equations.cljs` with the identity Net = Hold + Contribution checked in tests. | None. |
| 12.4 | `report_windows.cljs` and `report_marks.cljs`: half-open windows, completed hourly closes, `b` from completed hours. | `b` subtracts a 60 s settle before flooring. Missing marks make the headline unavailable rather than being carried forward. |
| 12.5 | `report_matching.cljs`: FIFO over gross quantities, explicit matched-pair counts, unmatched buys and "Sold inventory not reaccumulated", fee and rebate impact at the report mark, actual token quantities. | None. |
| 12.6 | `report_coverage.cljs`: five states, breaks and suppression; Start new tracking. | Coverage comes from REST only (see Decision Log). |
| 12.7 | `report_aggregate.cljs`. | No All-markets total for Since tracking began. |
| 12.8 | `report_export.cljs`; the adapter does Blob and anchor. | Two formats: a JSON bundle (reproducible) and a fills CSV (human-readable). |
| 11.2 | One dock tracking line and View report. | No P&L in the dock (by design). |
| 11.3 | `:vol-range` Portfolio tab and `views/portfolio/vol_range_report.cljs`. | None. |
| 7.6 | Baselines immutable; breaks derived from retained evidence; mismatch balance checks persisted. | No pruning. |
| 9.2 | Overlap de-duplication by fill key, the no-progress guard (existing), retention-count truncation detection. | None. |

## Dependency on the live-fills item

The live-fills item lands first (global build order stages 6-15). Reporting uses exactly these seams from it and never writes a parallel version. Everything else in reporting works without the stream, because REST backfill alone proves coverage.

1. The canonical fill record, `hyperopen.vol-range.application.fill-records/normalize-fill` (live-fills stage 9):

        {:fill-key "hyperliquid-mainnet|0xabc…|@107|1761040800000|123456789|9876543210"
         :network :hyperliquid-mainnet
         :account-address "0xabc…"          ; lower-case
         :coin "@107"
         :time 1761040800000                 ; venue ms, integer
         :tid "123456789"                    ; integer text
         :oid "9876543210"                   ; lossless string via lifecycle/oid-string, or nil
         :cloid "0x484f5652…"                ; lower-case, or nil
         :side :buy                          ; :buy for "B", :sell for "A"
         :px "76.4" :sz "7"                  ; venue decimal strings, gross size
         :fee "0.0028"                       ; signed; negative = rebate; already includes builder fee
         :fee-token "HYPE"
         :builder-fee nil                    ; venue string or nil, informational only
         :crossed false
         :hash "0x…" :dir "Buy" :start-position "150.0"
         :source :websocket                  ; or :rest
         :received-at-ms 1761040800350}

   `:fill-key` equals `lifecycle-evidence/fill-key` for the same row from either source (time and tid canonicalized in live-fills stage 1), so a WebSocket row and a REST row de-duplicate. The backfill normalizes REST rows with the same function (`:source :rest`). Money is parsed with `hyperopen.domain.trading.precision` only when computing.
2. The fills consumer seam `hyperopen.vol-range.infrastructure.live-ingress/register-fills-consumer!` (live-fills stage 9). It delivers every raw `userFills` envelope (spec 9.1, all rows, all coins, before the display list's 200-row truncation). R3's `ledger_ingest/on-fills-envelope!` registers there and normalizes, filters and persists relevant rows asynchronously. It never extends coverage.
3. `reads/read-fills-window!` keeps its result contract (`:rows :complete? :requested-at-ms :received-at-ms :error`, page limit 2,000). R2 adds an optional `:priority` key (default `:high`, unchanged).
4. Grid records keep `:fills` keyed by `fill-key` (the dock's per-side summary reads it). Reporting never computes from grid `:fills`.
5. Live-fills writes no IndexedDB store, no coverage, and nothing under `[:vol-range :campaigns]` or `[:vol-range :report]`. The single `vol-range-ledger` store (version 9) is created here in R2.

## Context and Orientation

Terms:

- Campaign: one tracking interval per network, account, base token and USDC pair. It starts at a baseline and ends when the user starts a new one (closure). Internal records say campaign; the UI says "tracking".
- Baseline: `q0` (base total including held units), `P0` (price), `C0 = 0`, and the boundary.
- Tracked event: a fill whose CLOID or oid belongs to a non-recovered grid of the campaign's market key, at or after the campaign start and before its end.
- Same-base event: any fill or ledger change that can alter the campaign's base-token balance.
- Window `[a,b)`: the events with `a <= time < b`. Inventory and cash "at `a`" include the events strictly before `a`.
- Mark: the close of the completed 1h candle whose open time is `boundary - 1h`.
- Ladder: the user-facing name for a grid. Never show "grid" in labels, toasts, live regions or CSV column names.

Existing files that matter (paths relative to `/hyperopen/src/hyperopen/`):

- `vol_range/infrastructure/executor_place.cljs`: `admit-placement!` runs inside the account admission section, does the awaited barrier read (`barrier-account!`, giving `read` and `input` with a fresh book), builds the intent and the new grid, and calls `commit-admitted!` -> `ex/persist-grid!` with extra records. R1 adds the campaign record there.
- `vol_range/infrastructure/executor.cljs`: `load!`, `commit!`, `persist-grid!` (per-grid web lock, read-merge-write), `placement-blockers`, `reconcile!`.
- `vol_range/infrastructure/journal.cljs`: `commit!`, which uses `idb/put-many-committed!` (resolves on transaction completion), and `load-all!`, which returns `{:records :unreadable}`. Rows store EDN. `encode` and `decode` are private today.
- `vol_range/application/journal_records.cljs`: record constructors and `record-valid?` (schema version, record id, kind, `:saved-at-ms`, no signing keys).
- `vol_range/application/lifecycle.cljs` and `lifecycle_evidence.cljs`: grids, legs, members, `fill-key`, `ingest-fills`.
- `vol_range/infrastructure/reads.cljs`: `read-account-snapshot!`, `read-fills-window!`, `read-book!` (fresh, uncached, `:priority :high`).
- `runtime/effect_adapters/vol_range_execution.cljs`: `[:effects/vol-range-execute op params]`, `sync-once!` loads account grids from the journal into `[:vol-range :execution :grids]`.
- `vol_range/infrastructure/execution_watcher.cljs`: syncs on account or market change; reconciles open ladders every 15 s while the page is visible.
- `vol_range/application/dock_model.cljs` and `views/trade/vol_range/dock.cljs`: the Vol ladders tab (`data-role` attributes such as `vol-ladder-row`).
- `platform/indexed_db.cljs`: `app-db-version` 8; store names; `open-db!` upgrades past the browser's version when a store is missing; `put-many-committed!`, `get-all-json-strict!`.
- `platform/locks.cljs`: `with-exclusive-lock!` (Web Locks, with an in-tab fallback).
- `api/trading/action_coordinator.cljs`: `with-account-admission!`.
- `api/endpoints/market.cljs`: `request-candle-snapshot! post-info! now-ms-fn coin {:interval :bars :priority :end-time-ms}`.
- `api/endpoints/account/portfolio.cljs`: `request-user-non-funding-ledger-updates! post-info! address start end opts`.
- `views/portfolio/account_tabs.cljs`: `account-info-options` (`:extra-tabs`, `:tab-order`). `portfolio/actions.cljs`: `account-info-tab-options` and aliases (856 of 861 lines). `portfolio/query_state.cljs`: `?tab=`.
- `views/chart/d3/runtime.cljs`: the shared chart host (`on-render spec`, `:surface`, `:series`, `:points`, `:y-ticks`).
- `account/history/effects.cljs` `export-funding-history-csv-effect`: the Blob, object URL and anchor download pattern.
- `vol_range/contracts.cljs`: versions, `product-policy`, `capability-matrix`, `freshness-policy`, `scope-key`, `current-network`.
- `config.cljs`: `VOL_RANGE_PREVIEW` and `VOL_RANGE_EXECUTION` goog-defines; `shadow-cljs.edn` sets them per build.
- `app/bootstrap.cljs` line ~103: installs the Vol Range watchers.
- Registration: `schema/contracts/vol_range_action_args.cljs` (merged into `action_args.cljs`), `schema/contracts/effect_args.cljs`, `schema/runtime_registration_catalog.cljs` with one file per feature (`pnl_share.cljs` is the small precedent), `runtime/collaborators/vol_range.cljs`, `app/effects.cljs`.
- Spot metadata: `[:spot :meta :universe]` entries `{:name "@107" :index 107 :tokens [base quote]}` and `[:spot :meta :tokens]` entries `{:name "HYPE" :index 150 :szDecimals 2}`. Spot balances in a barrier read are `{:coin :token :total :hold}`; `:total` includes held units.

## Data shapes

All money values are canonical decimal strings computed with `hyperopen.domain.trading.precision`. Times are integer ms.

### Contracts additions (`vol_range/contracts.cljs`)

    (def accounting-model-version 1)
    (def campaign-schema-version 1)
    (def ledger-schema-version 1)
    (def export-format-version 1)

    (def report-policy
      {:boundary-skew-ms 5000
       :read-settle-ms 5000
       :balance-check-settle-ms 30000
       :fills-overlap-ms 60000
       :venue-fill-retention-count 10000
       :ledger-page-limit 2000
       :max-ledger-pages 10
       :backfill-interval-ms 900000
       :hour-ms 3600000
       :mark-settle-ms 60000
       :candle-page-bars 5000
       :max-candle-pages 4
       :daily-checkpoints-after-ms 2592000000
       :windows [:since-start :7d :30d]})

    (defn reporting-enabled? [] ...)   ; reads config/vol-range-reporting-enabled?

### Campaign record (journal store, immutable)

    {:schema-version 1                       ; journal-records/schema-version
     :record-id "campaign:cmp-7f3a…"
     :kind :campaign
     :campaign-id "cmp-7f3a…"                ; entropy/random-id! "cmp"
     :campaign-key "hyperliquid-mainnet|0xabc…|spot|150|107"
     :campaign-schema-version 1
     :accounting-model-version 1
     :model-versions {:model 1 :precision 1 :estimator "parkinson-v1"}
     :scope {:network :hyperliquid-mainnet :account-address "0xabc…" :coin "@107"
             :spot-pair-index 107 :base-token-id "150" :quote-token-id "0"
             :symbol "HYPE" :quote-symbol "USDC" :sz-decimals 2}
     :started-by :first-placement            ; or :user-restart
     :first-operation-id "op-…"              ; nil for :user-restart
     :first-grid-id "grid-…"                 ; nil for :user-restart
     :baseline {:q0 "150"
                :p0 "80" :p0-source :book-midpoint   ; or :unavailable
                :p0-bid "79.99" :p0-ask "80.01" :p0-observed-at-ms 1761040000123
                :c0 "0"
                :boundary {:barrier-id "rb-…" :requested-at-ms 1761040000000
                           :received-at-ms 1761040000180 :skew-ms 5000}}
     :t0-ms 1761040000000                    ; = boundary :requested-at-ms
     :supersedes-campaign-id nil
     :saved-at-ms 1761040000200}

### Campaign closure record (journal store)

    {:schema-version 1 :record-id "campaign-closed:cmp-7f3a…" :kind :campaign-closed
     :campaign-id "cmp-7f3a…" :campaign-key "…" :ended-at-ms 1761300000000   ; successor :t0-ms
     :reason :user-restart :successor-campaign-id "cmp-91bc…" :saved-at-ms …}

The open campaign for a key is the one with no closure record. At most one is open per key; R1 tests enforce this.

### Ledger store records (`vol-range-ledger` store)

Ledger fill (one per fill key; idempotent put):

    {:schema-version 1 :record-id "fill:<fill-key>" :kind :ledger-fill
     :fill <normalized fill record from the live-fills normalizer>
     :saved-at-ms …}

Ledger update (non-funding ledger row touching a tracked base token, or unclassifiable with a token field):

    {:schema-version 1 :record-id "ledger:0xabc…|1761100000000|0xhash|spotTransfer|HYPE"
     :kind :ledger-update :account-address "0xabc…" :time 1761100000000 :hash "0x…"
     :delta-type "spotTransfer" :token "HYPE" :amount "-5" :classification :base-transfer
     :raw-delta {...}                ; the venue delta map, for export only
     :saved-at-ms …}

Coverage (one per account, stream and tracked base token; read-merge-write under the lock `hyperopen:vol-range-ledger:<account>`):

    {:schema-version 1 :record-id "coverage:0xabc…|fills|150" :kind :coverage
     :account-address "0xabc…" :stream :fills          ; or :ledger
     :base-token-id "150"                              ; rows of this token were classified and persisted
     :complete [[1761039995000 1761300000000]]         ; merged half-open intervals
     :failures [{:from-ms … :to-ms … :reason :coverage/no-progress :at-ms …}]   ; newest 20
     :saved-at-ms …}

Balance check:

    {:schema-version 1 :record-id "balance-check:cmp-7f3a…|1761300000000" :kind :balance-check
     :campaign-id "cmp-7f3a…" :requested-at-ms 1761300000000 :received-at-ms 1761300000150
     :observed-total "157" :expected "157" :state :pending  ; :match / :mismatch
     :evaluated-at-ms nil :saved-at-ms …}

Pending checks stay until evaluated. Mismatches are kept permanently. For matches, only the latest per campaign is kept (record id `balance-check:<campaign-id>|latest-match`). Inconclusive checks are deleted.

### Ledger events (pure, `report_ledger.cljs`)

    {:event-id "<fill-key>" :kind :fill :time 1761040900000 :tid "…" :tracked? true
     :campaign-id "cmp-…" :grid-id "grid-…" :operation-id "op-…" :logical-id "buy-0"
     :side :buy :px "76.4" :sz "7"
     :base-delta "7" :cash-delta "-534.8"     ; before fees
     :fee "0" :fee-token "USDC" :fee-kind :quote ; :base / :quote / :unsupported
     :net-base-delta "7" :net-cash-delta "-534.8"}

For a buy, `base-delta = +sz` and `cash-delta = -px*sz`. For a sell, `base-delta = -sz` and `cash-delta = +px*sz`. A base fee subtracts from `net-base-delta` and a quote fee from `net-cash-delta`; a negative fee credits. Events sort by `(time, tid, event-id)`. `(state-before events t)` returns `{:q (q0 + sum net-base-delta) :c (sum net-cash-delta)}` over campaign events with `T0 <= time < t`.

### Report state (`[:vol-range :report]`) and campaigns (`[:vol-range :campaigns]`)

    {:vol-range
     {:campaigns {"cmp-7f3a…" <campaign record>}       ; active account; loaded by sync and report load
      :campaign-closures {"cmp-7f3a…" <closure record>}
      :report {:ui {:window :30d                       ; :since-start / :7d / :30d
                    :selected-campaign-key nil         ; nil = All tracked markets
                    :covered-subset? false
                    :matched-open? false
                    :confirm-restart-key nil}
               :account-address "0xabc…"
               :attribution {:by-cloid {"0x484f…" {:grid-id … :operation-id … :logical-id …
                                                   :side :buy :rung-index 0
                                                   :campaign-key "…" :recovered? false}}
                             :by-oid {"9876543210" {...}}}
               :ledger {:fills {fill-key fill} :updates {record-id row}
                        :coverage {:fills {...} :ledger {...}} :balance-checks [...]}
               :marks {"@107" {:closes {1761037200000 "80.1"} :fetched-through-ms … :fetched-at-ms …}}
               :busy {[:load] true}
               :last {:op :backfill :status :ok :error nil :at-ms …}
               :loaded-at-ms …}}}

The attribution index is derived from journal grids. It is not a second copy of order state.

### Window figures (R4) and report view model

    {:campaign-id "cmp-…" :window :30d :a-ms … :b-ms …
     :a-mark {:price "80" :source :hourly-close :candle-open-ms …}  ; or :inception-midpoint for since-start
     :b-mark {:price "70" :source :hourly-close :candle-open-ms …}
     :q-a "150" :c-a "0" :q-b "157" :c-b "-534.8"
     :coverage {:state :complete :intervals [...] :breaks [] :headline-allowed? true}
     :figures {:net-pnl "-1544.8" :hold-pnl "-1500" :contribution "-44.8"}   ; nil when suppressed
     :activity {:fills 1 :bought-base "7" :bought-quote "534.8" :buy-vwap "76.4"
                :sold-base "0" :sold-quote "0" :sell-vwap nil :maker 1 :taker 0
                :fees-by-token {"USDC" "0"}}}

The view model (`report_model/report-view-model state now-ms`) has exact keys, specified in `schema/vol_range_report_contracts.cljs` and extended stage by stage:

    {:enabled? true :read-only? false :loading? false :account-address "0xabc…"
     :window {:selected :30d :options [{:id :since-start :label "Since tracking began" :selected? false} …]}
     :markets {:selected nil :options [{:id nil :label "All tracked markets"} {:id "…" :label "HYPE/USDC"}]}
     :headline {:status :ok            ; :pending / :suppressed / :unavailable / :no-total
                :figures {:net-pnl {:display "−1,544.80 USDC" :tone :negative} :hold-pnl {...} :contribution {...}}
                :benchmark-label "Hold inventory at the selected window start"
                :range-text "Oct 21, 14:00 UTC → Oct 28, 14:00 UTC · valued at hourly closes"
                :message nil :subset-offer nil}
     :coverage {:state :complete :label "Complete" :detail "…" :intervals [...] :can-restart? true}
     :campaigns [{:campaign-key … :market "HYPE/USDC" :baseline-text "…" :cost-basis-text "Cost basis unavailable"
                  :coverage {...} :activity {...}}]
     :matched nil        ; R5
     :chart nil          ; R5
     :export nil         ; R6 {:enabled? bool}
     :status-message nil :empty-message nil}

## Accounting rules (exact)

- Headline equations (`report_equations/window-figures {:q-a :c-a :p-a :q-b :c-b :p-b}`):
  `Net = (C_b − C_a) + q_b×P_b − q_a×P_a`, `Hold = q_a×(P_b − P_a)`, `Contribution = (C_b − C_a) + (q_b − q_a)×P_b`. Exact decimals; the identity `Net = Hold + Contribution` is asserted in tests for every fixture.
- Windows: `b = floor((now − 60 s)/1h)×1h`. Since tracking began: `a = T0`, `P_a = P0`, `q_a = q0`, `C_a = 0`. 7D and 30D: `a = b − 7d` or `b − 30d`, and `a >= T0` is required. `P_x` is the close of the candle with open time `x − 1h`. Event membership is `a <= time < b`, and boundary state uses `time < x`.
- Campaign interval: `[T0, ended-at-ms)` (open-ended when there is no closure). A fill belongs to the campaign whose key matches and whose interval contains its venue time.
- Coverage need for a window `[a,b)` of campaign C: fills and ledger coverage of `[T0 − skew, b)`. States are combined with precedence `:unavailable > :attribution-break > :gap > :partial > :complete`. The headline is allowed only when the combined state over `[T0 − skew, b)` is `:complete`, the `P_a` and `P_b` marks exist, and no break time is `< b`.
- `:unavailable` reasons: `:baseline/missing` (no campaign), `:baseline/price-unavailable`, `:storage/unavailable`, `:marks/missing-boundary-close`, `:window/before-tracking`, `:window/no-completed-mark`.
- `:attribution-break` reasons: `:break/ambiguous-boundary`, `:break/untracked-same-base-trade`, `:break/other-pair-trade`, `:break/base-transfer`, `:break/unsupported-fee-token`, `:break/unknown-market-fill`, `:break/unexplained-balance-change`, `:break/unrecognized-ledger-entry`.
- `:gap` reasons: `:coverage/retention`, `:coverage/no-progress`, `:coverage/page-cap`, `:coverage/read-error`.
- `:partial` reasons: `:coverage/backfill-pending`.
- Same-base classification (`report_attribution.cljs`). A fill's coin is spot when it starts with `@` or contains `/`; any other coin (perps, HIP-3 `dex:COIN`) is ignored. A spot coin resolves through the universe to `[base quote]`. It is relevant when either token id equals a tracked base token id, and an unresolvable spot coin is `:break/unknown-market-fill`. Ledger delta types:
  - Ignored (USDC only): `deposit`, `withdraw`, `internalTransfer`, `accountClassTransfer`, `subAccountTransfer`, `vaultCreate`, `vaultDeposit`, `vaultWithdraw`, `vaultDistribution`, `liquidation`, `rewardsClaim`.
  - A break when the token name matches the base (`:base-transfer`): `spotTransfer`, `send`, `spotGenesis`, `cStakingTransfer`, `borrowLend`, `deployGasAuction`.
  - Any other type: a break (`:unrecognized-ledger-entry`) when any field names the base token; otherwise ignored, and the balance check catches the effect.
- Cross-asset: sum `figures` across campaigns of one account and network only for 7D and 30D, and only when every included campaign's headline is allowed for the identical `[a,b)`. Every fill belongs to exactly one campaign, and no USDC balance enters any campaign.
- FIFO matching (R5): over tracked fills with `a <= time < b`, sorted by `(time, tid, event-id)`. Buy and sell queues start empty and hold gross quantities. Each incoming fill matches the oldest opposite-side unmatched quantity, splitting partials, and each split counts as one matched pair. `gross = Σ q×(sell_px − buy_px)`. An unmatched buy is marked `q×(P_b − px)`; an unmatched sell is marked `q×(px − P_b)` and labeled **Sold inventory not reaccumulated**. The reconciliation is `gross + unmatched − USDC fees − (net base fees)×P_b`, which must equal the headline contribution when the window's fees are only USDC or base.

## File-by-file changes

Paths are relative to `/hyperopen/src/hyperopen/` unless they start with `test/`, `tools/` or `docs/`. Every new namespace stays under 500 lines.

### New namespaces

| Namespace | Stage | Responsibility |
| --- | --- | --- |
| `vol_range/application/campaign.cljs` | R1 | `campaign-key`, `baseline-record` (total), `closure-record`, `campaigns-from-records` / `open-campaign`, `campaign-for-time`, `baseline-records-for-placement` (returns `[]` or `[record]`). Pure. |
| `vol_range/domain/report_intervals.cljs` | R2 | Half-open interval algebra: `normalize`, `union`, `subtract`, `covers?`, `uncovered`, `intersect`. |
| `vol_range/domain/report_ledger.cljs` | R2 | Fill events with signed deltas, fee kind, ordering, `state-before`, activity summary. |
| `vol_range/application/report_attribution.cljs` | R2 | Attribution index from grids; same-base classification of fills and ledger updates using spot meta; break candidates. |
| `vol_range/application/report_coverage.cljs` | R2 | Coverage from reads (`fills-read->coverage` with the retention rule), the combined state for a campaign or window, breaks, balance-check evaluation. |
| `vol_range/infrastructure/ledger_store.cljs` | R2 | `put-records!` (committed, `vol-range-ledger` store), `load-account!`, `merge-coverage!` (per account, stream and base token) and `put-balance-check!` under `with-exclusive-lock!`; `ledger-record-valid?`; its own EDN codec. |
| `vol_range/infrastructure/report_reads.cljs` | R2 | `read-ledger-window!` (paged, overlap, no-progress guard, page cap), `read-hourly-candles!` (paged backward by `end-time-ms`, at most 4 pages). Low priority, through the shared `post-info!`. |
| `vol_range/infrastructure/report_executor.cljs` | R2 | `load!`, `backfill!` (balance read first, then fills and ledger reads, persist, then evaluate pending checks). Injected deps; never rejects. |
| `vol_range/infrastructure/campaign_executor.cljs` | R3 | `start-tracking!` inside `with-account-admission!`. |
| `vol_range/infrastructure/report_watcher.cljs` | R3 | Store watch: dispatches `:actions/load-vol-range-report` when route, tab or account changes into the report. Registers a tick listener with the execution watcher (no own timer) that dispatches `:actions/refresh-vol-range-report` when at least `:backfill-interval-ms` passed since the last backfill, the page is visible, and an open campaign exists. Also registers `ledger_ingest/on-fills-envelope!` with `live-ingress/register-fills-consumer!` when reporting is enabled. |
| `vol_range/infrastructure/ledger_ingest.cljs` | R3 | WebSocket envelope consumer registered through `live-ingress/register-fills-consumer!`: normalizes rows with `fill-records/normalize-fill`, keeps rows relevant to the trading account's open campaigns, queues an idempotent put, and never touches coverage or awaits on the tap thread. |
| `vol_range/report_actions.cljs` | R3 | Pure report action handlers (see Actions). |
| `runtime/effect_adapters/vol_range_report.cljs` | R3 | `[:effects/vol-range-report op params]` with ops `:load :backfill :start-tracking` (R3), `:marks` (R4), `:export` (R6). Results go to `[:vol-range :report]`. |
| `vol_range/application/report_model.cljs` | R3 | The view model, memoized on report state, campaigns, spot meta and the minute. |
| `vol_range/application/report_copy.cljs` | R3 | All user-visible strings and reason texts, kept in one place for the copy review. |
| `schema/vol_range_report_contracts.cljs` | R3 | Exact-key specs for campaign, ledger records and the view model. |
| `schema/runtime_registration/vol_range_report.cljs` | R3 | Effect and action binding rows. |
| `views/portfolio/vol_range_report.cljs` | R3 | Tab entry: controls, sections, empty state. |
| `views/portfolio/vol_range/coverage.cljs`, `activity.cljs`, `tracking.cljs` | R3 | Coverage chip and detail; execution activity; baseline and Start tracking. |
| `vol_range/domain/report_equations.cljs` | R4 | `window-figures`. |
| `vol_range/domain/report_marks.cljs` | R4 | Candle normalization, `hour-floor`, `mark-at`, checkpoint boundaries. |
| `vol_range/application/report_windows.cljs` | R4 | Window resolution and per-campaign figure assembly. |
| `vol_range/application/report_aggregate.cljs` | R4 | All tracked markets and the covered subset. |
| `views/portfolio/vol_range/headline.cljs`, `controls.cljs` | R4 | Headline card, window and market controls. |
| `vol_range/domain/report_matching.cljs` | R5 | FIFO matching and reconciliation. |
| `vol_range/application/report_chart_model.cljs` | R5 | Checkpoints, series segments and the d3 spec. |
| `views/portfolio/vol_range/matched.cljs`, `pnl_chart.cljs` | R5 | Matched disclosure and the chart host. |
| `vol_range/application/report_export.cljs` | R6 | `json-document`, `fills-csv`, `filename`; key whitelist. |
| `test/hyperopen/vol_range/report_fixtures.cljs` | R2 | Shared 13.5 scenarios (campaigns, raw rows, candles). |
| `tools/playwright/test/vol-range-report.spec.mjs` | R3 | Created in R3 and extended in R4 to R6. |

### Modified namespaces

| File | Stage | Change |
| --- | --- | --- |
| `vol_range/infrastructure/executor_place.cljs` | R1 | In `admit-placement!`, compute `(campaign/baseline-records-for-placement {:records records :scope scope :read read :bbo (get-in input [:market :bbo]) :operation-id … :grid-id … :campaign-id ((:random-id! deps) "cmp") :now-ms at})` and pass `(into [intent] campaign-records)` to `commit-admitted!` (change its arity to accept extra records). Repair is unchanged. About +6 lines. |
| `vol_range/contracts.cljs` | R1, R2 | Versions; `report-policy` in R2; `reporting-enabled?` in R3. |
| `runtime/effect_adapters/vol_range_execution.cljs` | R1 | `sync-once!` also stores the account's campaigns and closures at `[:vol-range :campaigns]` and `[:vol-range :campaign-closures]` (pure helper `campaign/campaigns-for-account`). About +4 lines. |
| `vol_range/application/dock_model.cljs`, `views/trade/vol_range/dock.cljs` | R1, R3 | R1: a `:tracking` row key, `{:text "Tracking since Oct 21, 13:42 UTC"}` or `{:text "Not tracked"}`, and `vol-dock/view-state` also carries `[:vol-range :campaigns]` (without it the memoized account-info panel never repaints the tracking line). R3: `:view-report? (contracts/reporting-enabled?)` and the button. |
| `platform/indexed_db.cljs` | R2 | `vol-range-ledger-store "vol-range-ledger"`, added to `app-store-names`; `app-db-version` 9. |
| `vol_range/infrastructure/reads.cljs` | R2 | Optional `:priority` in `read-fills-window!` (default `:high`, unchanged). |
| `config.cljs` | R3 | `(goog-define VOL_RANGE_REPORTING false)`, `[:vol-range :reporting-enabled?]`, `vol-range-reporting-enabled?`. |
| `shadow-cljs.edn` | R3 | `VOL_RANGE_REPORTING true` in `:app :dev`, `:test` and `:vol-range-test`; extend the `:vol-range-test` `:ns-regexp` with `views\\.portfolio\\.vol-range.*` (Repair stage R3 already added `runtime\\.effect-adapters\\.vol-range.*`). |
| `schema/contracts/vol_range_action_args.cljs` | R3 to R6 | New action ids (see below). |
| `schema/contracts/effect_args.cljs` | R3 | `::vol-range-report-args (s/tuple #{:load :backfill :start-tracking :marks :export} map?)` plus the map entry. R4 and R6 add no new ids because all ops are listed in R3. |
| `schema/runtime_registration_catalog.cljs` | R3 | Require `vol-range-report` and add its rows to the effect and action groups. |
| `runtime/collaborators/vol_range.cljs` | R3 | `report-action-deps` map. |
| `app/effects.cljs` | R3 | `:vol-range-report vol-range-report/vol-range-report`. |
| `app/bootstrap.cljs` | R3 | One more install call in the existing Vol Range watcher lambda. |
| `views/portfolio/account_tabs.cljs` | R3 | `:vol-range` extra tab when `(and (contracts/reporting-enabled?) (not trader-portfolio-route?))`; tab order after `:monte-carlo`. |
| `portfolio/actions.cljs` | R3 | Add `:vol-range` to `account-info-tab-options` and the alias `:volrange`. If the 861 ceiling would be exceeded, move the options set and aliases to a new `portfolio/account_info_tabs.cljs` instead of raising it. |
| `vol_range/infrastructure/execution_watcher.cljs` (or `live_monitor.cljs` if live-fills moved the tick there) | R3 | `register-tick-listener!`/`unregister-tick-listener!` over a `defonce` atom; the existing 15 s callback calls each listener with `{:store :now-ms :visible?}` in its own `try`/`catch`. No new timer. |
| `test/hyperopen/vol_range/a0_boundary_test.cljs` | R1-R3 | Add each new execution-dependent `src/hyperopen/vol_range/**` namespace to `execution-foundations` in its stage (see Decision Log). |
| `telemetry/console_preload/vol_range_fixture.cljs` | R3, R4 | `seedVolRangeFixture({report: "<scenario>"})` commits campaign, grid and ledger fixtures through `journal/commit!` and `ledger_store/put-records!` and returns a promise. No change to `console_preload.cljs`. |
| `docs/product-specs/vol-range-implementation-spec.md` | R6 | None. The release-B status lives in this plan. |

Unchanged on purpose: `trading/vol_range_transitions.cljs`, `schema/vol_range_contracts.cljs` (ticket and review VMs), the submit policy, signing, the nonce coordinator, cancel and repair.

## Actions, effects and whitelist entries

Effect: `[:effects/vol-range-report op params]`

| op | params | Effect |
| --- | --- | --- |
| `:load` | `{:account-address}` | Load journal campaigns, closures and the attribution index, and the account's ledger store rows, into `[:vol-range :report]`; then run `:backfill`. |
| `:backfill` | `{:account-address}` | Balance read; fills and ledger reads from `min(uncovered start) − :fills-overlap-ms` to now; persist relevant rows and coverage; evaluate pending balance checks; reload. |
| `:start-tracking` | `{:campaign-key}` | `campaign_executor/start-tracking!`. |
| `:marks` | `{:coin :from-ms :to-ms}` | Hourly closes into `[:vol-range :report :marks coin]`, fetching only missing hours. |
| `:export` | `{:format :json\|:csv}` | Build with `report_export` from the current store, then download via Blob and anchor. |

Actions (ids, args spec in `vol_range_action_args.cljs`, collaborator key in brackets):

| Action | Args | Stage | Emits |
| --- | --- | --- | --- |
| `:actions/load-vol-range-report` [`:load-vol-range-report`] | `::common/no-args` | R3 | `[:effects/vol-range-report :load {:account-address …}]` unless busy, read-only, or no account. |
| `:actions/refresh-vol-range-report` | no-args | R3 | `:backfill`, plus `:marks` from R4. |
| `:actions/select-vol-range-report-campaign` | `(s/tuple (s/nilable ::common/non-empty-string))` | R3 | `save-many` of `[:vol-range :report :ui :selected-campaign-key]`. |
| `:actions/request-start-vol-range-tracking` | `(s/tuple ::common/non-empty-string)` | R3 | Sets `:confirm-restart-key`. |
| `:actions/cancel-start-vol-range-tracking` | no-args | R3 | Clears it. |
| `:actions/confirm-start-vol-range-tracking` | no-args | R3 | `[:effects/vol-range-report :start-tracking {:campaign-key k}]`; nothing in read-only mode. |
| `:actions/select-vol-range-report-window` | `(s/tuple #{:since-start :7d :30d "since-start" "7d" "30d"})` | R4 | `save-many` and `:marks` for the needed range. |
| `:actions/set-vol-range-report-covered-subset` | `(s/tuple boolean?)` | R4 | `save-many`. |
| `:actions/set-vol-range-report-ui-flag` | `(s/tuple #{:matched-open?} boolean?)` | R5 | `save-many`. |
| `:actions/export-vol-range-report` | `(s/tuple #{:json :csv "json" "csv"})` | R6 | `[:effects/vol-range-report :export {:format …}]`. |

The dock's **View report** dispatches `[[:actions/select-vol-range-report-campaign key] [:actions/navigate "/portfolio?tab=vol-range"]]`. It reuses `:actions/navigate`, so no new navigation coupling is introduced.

Registration rows go in `schema/runtime_registration/vol_range_report.cljs`: `effect-binding-rows [[:effects/vol-range-report :vol-range-report]]`, the action rows above, and `effect-order-policy-required-action-ids #{}`.

## UI placement, copy and data-roles

Placement: Portfolio, tab **Vol Range** (`:vol-range`), after Monte Carlo, inside the standard account panel height with internal scroll. Desktop layout from top to bottom: controls row, headline card, chart (R5), Gross spread on matched fills disclosure (R5), Execution activity, Tracking. At 375 px the controls wrap and the headline figures stack. The dock adds one tracking line per ladder.

| data-role | Element | Copy |
| --- | --- | --- |
| `vol-range-report` | tab root | — |
| `vol-range-report-empty` | empty state | "No Vol Range tracking for this account in this browser yet. Tracking starts when you place your first ladder." |
| `vol-range-report-market` | market select | "All tracked markets", "HYPE/USDC" |
| `vol-range-report-window` and `-since-start`, `-7d`, `-30d` | segmented buttons (`aria-pressed`) | "Since tracking began", "7D", "30D" |
| `vol-range-report-refresh` | button | "Refresh" |
| `vol-range-report-headline` | card | — |
| `vol-range-report-net-pnl` | figure | "Net tracked asset P&L" |
| `vol-range-report-hold-pnl` | figure | "Hold P&L" |
| `vol-range-report-contribution` | figure | "Ladder contribution versus hold" |
| `vol-range-report-benchmark` | note | "Benchmark: Hold inventory at the selected window start. A descriptive comparison, not a claim that every wallet action would still have been possible." |
| `vol-range-report-range` | line | "Oct 21, 14:00 UTC → Oct 28, 14:00 UTC · valued at hourly closes" (Since tracking began: "… · start valued at the book midpoint") |
| `vol-range-report-suppressed` | `role="status"` | For a break: "Dollar figures are hidden from {time}: {reason}. Orders, fills and fees are still shown below." For a gap: "Fills between {x} and {y} couldn't be confirmed, so dollar figures are hidden for windows that include this period." For partial: "Checking fills… Figures appear once every fill in this window is confirmed." For no mark yet: "Figures appear after the first completed hourly close ({hh}:00 UTC)." For before tracking: "{7D} needs tracking to have started by {a}. Tracking began {T0}." For a missing mark: "No completed hourly price for {b}, so figures for this window are unavailable." |
| `vol-range-report-no-total` | note | "Totals need the same window for every market. Choose 7D or 30D." or "Totals are hidden because {PURR} isn't fully covered for this window." |
| `vol-range-report-covered-subset` | button | "Show total for covered markets ({HYPE, UBTC})". When on, the headline title reads "Covered markets: HYPE, UBTC". |
| `vol-range-report-coverage` | chip | "Complete", "Checking", "Gap", "Outside activity", "Unavailable" (text plus icon, not color alone) |
| `vol-range-report-coverage-detail` | list | Each interval: "{from} → {to}: {state text}" |
| `vol-range-report-baseline` | line | "Tracking began Oct 21, 13:42 UTC with 150 HYPE at 80.00 USDC (book midpoint)." |
| `vol-range-report-cost-basis` | line | "Cost basis unavailable" |
| `vol-range-report-start-tracking` | button | "Start new tracking from now" |
| `vol-range-report-start-tracking-confirm` | inline confirm | "Start new tracking from now? Current tracking is kept as history and isn't combined with the new one. No orders are changed." Buttons: "Start tracking" and "Keep current tracking". |
| `vol-range-report-activity` | table | Columns: Market, Fills, Bought, Buy VWAP, Sold, Sell VWAP, Maker/Taker, Fees (by token) |
| `vol-range-report-status` | polite live region | "Report updated." or "Couldn't load fills: {error}." |
| `vol-range-report-chart` | figure (R5) | Title "Cumulative P&L (USDC)"; series "Net tracked asset P&L" and "Hold P&L"; caption "Dollar P&L of the tracked inventory, not your account value. Hourly closes; gaps mark missing prices or unconfirmed fills." (R5 Decision Log) |
| `vol-range-report-matched` | `<details>` (R5) | Summary "Gross spread on matched fills". Rows: "Matched quantity 9.9 HYPE in 1 matched pair", "Gross matched spread", "Unmatched buys marked at 100.00", "Sold inventory not reaccumulated", "Fee and rebate impact at the report mark", "= Ladder contribution versus hold", "Fees paid: 0.1 HYPE · 0 USDC". Note: "Pairs the oldest opposite fills within this window. It isn't a completed cycle or a cost basis and isn't added to the headline." |
| `vol-range-report-export-json`, `vol-range-report-export-csv` | buttons (R6) | "Export report (JSON)", "Export fills (CSV)" |
| `vol-ladder-tracking` | dock line | "Tracking since Oct 21, 13:42 UTC" or "Not tracked" |
| `vol-ladder-view-report` | dock button | "View report" |

Break reason texts (`report_copy.cljs`):

| Reason | Text |
| --- | --- |
| `:break/untracked-same-base-trade` | "a {HYPE} trade that wasn't a Vol Range order" |
| `:break/other-pair-trade` | "a {HYPE} trade in {PAIR}" |
| `:break/base-transfer` | "a {HYPE} transfer ({type})" |
| `:break/unsupported-fee-token` | "a fee paid in {TOKEN}" |
| `:break/unknown-market-fill` | "a fill in a market HyperOpen couldn't identify" |
| `:break/unexplained-balance-change` | "an unexplained {HYPE} balance change" |
| `:break/ambiguous-boundary` | "activity at the moment tracking began" |
| `:break/unrecognized-ledger-entry` | "an account update involving {HYPE}" |

Formatting: USDC with two decimals, thousands separators and a U+2212 minus sign; base quantities at the market's size decimals; times in UTC. Use the existing portfolio format helpers and theme tokens: positive and negative tones from the existing portfolio tokens, warning from `--ho-warn`. Replicant class vectors and keyword style keys. The word "grid", "equity" and percentage returns never appear.

## Test plan

Unit (all run in `npm run test:vol-range` and `npm test`):

- `test/hyperopen/vol_range/application/campaign_test.cljs` (R1): key format; baseline from a barrier read where `q0` is the total including hold and a missing row is "0"; `P0` is the 12-place half-up midpoint trimmed (79.99/80.01 gives "80"); the boundary fields; `C0 "0"`; versions; `record-valid?` true and no forbidden keys; an open campaign is reused; a closed campaign plus no open one creates a new campaign; `campaign-for-time` respects `[T0, ended-at)`; total on a missing bbo (`:p0 nil`, `:p0-source :unavailable`).
- `test/hyperopen/vol_range/infrastructure/campaign_capture_test.cljs` (R1, using `simulator.cljs` fakes): the first placement commits intent, grid and campaign in one `commit!` call before `send!`. A second placement in the same market commits no campaign. The action digest, legs, reservations and nonce calls are identical with and without an existing campaign. A rejecting journal commit means `send!` is never called. A repair commits no campaign. The final guard is unchanged.
- `test/hyperopen/vol_range/domain/report_intervals_test.cljs` (R2): union and merge, subtract, covers, half-open edges.
- `test/hyperopen/vol_range/domain/report_ledger_test.cljs` (R2): 13.5 rows 3 and 4 (a base fee of 0.004 gives net base +9.996 and cash −800; fee 0.4 with builderFee 0.1 debits 0.4); a negative fee credits; sort stability; `state-before` is half-open, and a fill at exactly `t` is excluded.
- `test/hyperopen/vol_range/application/report_attribution_test.cljs` (R2): tracked by CLOID and by oid; recovered grid fills are untracked; same pair untracked; another pair containing the base; perp and HIP-3 coins ignored; an unknown spot coin breaks; the ledger classification table; a third-token fee.
- `test/hyperopen/vol_range/application/report_coverage_test.cljs` (R2): a complete read covers `[s, R − 5 s)` for the base tokens tracked when it was requested and for no other token; 10,000 distinct fill keys across all coins give `:coverage/retention` while 10,000 rows with overlap duplicates below that count do not; no progress and page cap give `:gap`; never read gives `:partial`; a later complete read heals a gap; 13.5 row 8 (a feed gap with a possible missing round trip and an unchanged ending base stays a gap); the ambiguous boundary; a break at `t` suppresses windows with `b > t` but not those with `b <= t`; balance-check evaluation (match, mismatch, inconclusive within skew, waits for the settle).
- `test/hyperopen/vol_range/infrastructure/report_reads_test.cljs` (R2): ledger paging overlap and de-duplication, no progress, page cap; candle paging; priority `:low`; read failures resolve `:complete? false` and never reject.
- `test/hyperopen/vol_range/infrastructure/ledger_store_test.cljs` (R2): invalid records refused; coverage merge under the lock is monotonic; load filters by account. Follow the `journal_and_cloid_test.cljs` stubbing pattern.
- `test/hyperopen/vol_range/infrastructure/report_executor_test.cljs` (R2, R3): backfill order (balance read before the fills read, as in `reconcile!`); persisted rows are idempotent; storage failure leads to `:storage/unavailable`; `start-tracking!` runs inside admission, refuses on an unresolved attempt, an incomplete read or a busy section, and commits campaign plus closure atomically; it never calls `send!`, the nonce or signing deps (asserted by deps that throw).
- `test/hyperopen/platform/indexed_db_versioning_test.cljs` (R2): the new store is created when the browser already has version 8 or 9 without it.
- `test/hyperopen/vol_range/report_actions_test.cljs` (R3 onward): each action's effects; read-only emits no start-tracking; busy emits nothing.
- `test/hyperopen/vol_range/registration_test.cljs` (R3): action ids, arg specs, collaborators and the effect binding are consistent.
- `test/hyperopen/vol_range/application/report_model_test.cljs` (R3 onward): exact keys; copy strings; "grid" never appears; empty states.
- `test/hyperopen/views/portfolio/vol_range_report_test.cljs` (R3 onward): data-roles render; the tab is hidden when the flag is off and on the trader route; read-only hides Start tracking.
- `test/hyperopen/vol_range/application/dock_model_test.cljs` (R1, R3): tracking line and View report gating.
- `test/hyperopen/vol_range/domain/report_equations_test.cljs` (R4): 13.5 rows 1 to 3 and the identity.
- `test/hyperopen/vol_range/domain/report_marks_test.cljs` (R4): `b` floor with settle; the mark is the close of the candle with open `x − 1h`; a missing candle gives nil; numeric-string validation.
- `test/hyperopen/vol_range/application/report_windows_test.cljs` (R4): Since tracking began uses P0; 7D before tracking is unavailable; `a`/`b` exactness; 13.5 row 6 (a fill at `b` is excluded from `[a,b)` and included in `[b,c)`); row 7 (an external transfer makes the headlines unavailable while activity is still present).
- `test/hyperopen/vol_range/application/report_aggregate_test.cljs` (R4): 13.5 row 9 (two assets: sum of contributions, cash counted once, no USDC balance); one incomplete campaign suppresses the total; the covered subset names its markets; Since tracking began gives no total.
- `test/hyperopen/vol_range/domain/report_matching_test.cljs` (R5): 13.5 row 5 (gross 99, unmatched buy 2, base fee impact −10, total 91 = 891 − 800); sell-then-buy order; partial splits and pair counts; a negative fee reverses the sign; a property test that reconciliation equals the headline contribution over generated USDC/base-fee fill sequences.
- `test/hyperopen/vol_range/application/report_chart_model_test.cljs` (R5): checkpoints hourly or daily; gaps at missing marks and uncovered hours; series end at a break; no "equity" in any label.
- `test/hyperopen/vol_range/application/report_export_test.cljs` (R6): JSON keys and versions; fills carry attribution; marks and coverage are included; no forbidden keys at any depth; CSV header and escaping; human-readable columns say "Ladder", not grid; the filename format.

Fixtures (`test/hyperopen/vol_range/report_fixtures.cljs`): the synthetic market `@9001` (VRT/USDC, base token 9001, size decimals 2) from the existing fixtures; campaign `q0=150, P0=80, T0=2026-10-21T13:42:00Z`; raw fill rows for each 13.5 row (both REST and WebSocket shapes); 1h candle rows with the needed closes (70, 100, 80); a ledger `spotTransfer` VRT row; a coverage record with a failure interval; a two-asset account (VRT and a second synthetic token 9002).

Contract and registration: `schema/vol_range_report_contracts.cljs` exact keys for the VM and records, validated in the model and adapter tests; the existing action and effect drift checks in `schema/contracts.cljs` fail at load if any new id is unregistered.

Playwright (`tools/playwright/test/vol-range-report.spec.mjs`; imports `{ expect, test }` from `../support/guarded_test.mjs` (enforced by `spec_imports.test.mjs`); uses the shared `tools/playwright/support/vol_range_world.mjs` `bootstrap` from Repair R5, which installs the stateful `**/info` stub and the live-fills WebSocket simulator (placement is refused until monitoring is live), plus the wallet simulator and debug exchange simulator; `page.route('**/exchange', abort)` as a safety net, with an assertion that zero requests reached a real exchange):

- R3: place a ladder through the simulator, open `/portfolio?tab=vol-range`, see the baseline "Tracking began … 150 VRT at 80.00 USDC (book midpoint)", "Cost basis unavailable", the "Figures appear after the first completed hourly close" message, the dock "Tracking since" line and View report navigation. Flag-off build hiding is covered by the unit test.
- R4: `seedVolRangeFixture({report: "headline-buy"})` (T0 three hours ago, buy 7 at 76.4, closes 70) shows −1,544.80, −1,500.00 and −44.80 for Since tracking began; window switching; `report: "external-transfer"` shows the suppressed status and still shows activity; All tracked markets with the covered-subset control.
- R5: the chart renders with the axis title "Cumulative P&L (USDC)" and no "equity" text; the matched disclosure for `report: "matched-fees"` shows 99.00 / 2.00 / −10.00 / 91.00.
- R6: both export buttons trigger `page.waitForEvent('download')`; the JSON parses with `format-version 1`; the CSV header row matches.
- Viewports 375, 768, 1280 and 1440 for the tab; keyboard focus order through the controls; the live region text.

## Risks and how each is prevented

| Risk | Prevention |
| --- | --- |
| The R1 change alters a real-money placement (legs, digest, reservations, send ordering). | The baseline builder is pure, total and separate; only the extra-records vector changes. `campaign_capture_test` asserts the identical action digest, legs and reservations and that `send!` follows the single commit. Existing `lifecycle_scenarios_test` and `lifecycle_safety_test` must pass unchanged. No new send, cancel or signing path exists anywhere in this plan. |
| Start tracking races a placement and creates two open campaigns, or captures a baseline mid-placement. | It runs in `with-account-admission!` and refuses on unresolved attempts. R1 tests assert at most one open campaign per key; campaign creation in placement reads the journal inside the same admission section. |
| Accidental live orders from tests or agents. | All tests use injected fakes; Playwright uses the debug exchange simulator and aborts `**/exchange`; reporting effects call only `post-info!` reads and IndexedDB. A reporting boundary test asserts that no reporting namespace requires `api.trading.delivery`, `api.trading.nonce-coordinator`, `order.*`, `utils.hl-signing` or `vol-range.infrastructure.executor-place`, and that the only `api.trading.*` require is `action-coordinator` in `campaign_executor.cljs` (for the admission lock). |
| A headline over an interval where a newly tracked token's rows were never persisted. | Coverage is per base token and only from reads whose classification already included that token (Decision Log). |
| Two ledgers or two coverage models. | One `vol-range-ledger` store, created here; the live-fills item writes none and only offers the normalizer and the consumer seam. |
| A new background timer. | Periodic backfill is a tick listener on the execution watcher's existing interval. |
| Breaking VR-24. | Each new execution-dependent namespace is classified in `a0_boundary_test` in its stage. |
| A headline is shown over incomplete data (missed fills, retention truncation, a balance-only check). | Coverage requires complete REST windows over `[T0 − skew, b)`; the retention-count rule; the WebSocket never extends coverage; a balance match never upgrades coverage; the 13.5 row 8 test. |
| Double-counting fills (WebSocket plus REST, overlap pages, grid fills versus ledger fills). | One fill key everywhere; idempotent puts; the ledger computes from ledger fills only, never from grid `:fills`. |
| Clock skew misorders events at inception. | The skew-widened ambiguous window becomes a break, never a guess. |
| Journal slowdown on the real-money path. | Bulky data lives in the separate `vol-range-ledger` store; the journal gains one small record per campaign. |
| IndexedDB version churn across branches on the same origin (seen in `dd19bd749`). | Bump to 9 and rely on the existing missing-store upgrade path; the versioning test covers it. |
| Background reads compete with reconciliation or cancel. | `:priority :low`; backfill every 15 minutes only while visible and an open campaign exists; coalesced through the busy map. |
| Illiquid pairs with missing hourly candles leave headlines often unavailable. | An honest "No completed hourly price" message; owner decision recorded as an open question. |
| Namespace size limits. | New namespaces under 500 lines with the splits listed; `portfolio/actions.cljs` change of at most 2 lines with an extraction fallback; no edits to files at their ceiling (`effect_order_contract.cljs`, `action_args.cljs`, `console_preload.cljs`, `websocket/user_runtime/fills.cljs`). |
| Bundle growth of the main entry. | Views are in the portfolio route module; `lint:bundle-budget` is run in R6 with the route-runtime fallback. |
| Merge conflicts with sibling items (live-fills, dock and repair work) in `vol_range_execution.cljs`, `dock_model.cljs`, `dock.cljs` and `reads.cljs`. | Stages run strictly in the global build order in one worktree, so reporting edits land on top of the sibling stages; each change is a few lines in a stated place; the R1 and R3 dock changes are additive keys only. |
| Signing material leaking into exports. | Export whitelists keys; the test walks the document for forbidden keys; records are already checked by `record-valid?`. |

## Stage breakdown

Each stage is one commit on `feature/vol-range-a0` (or the workflow's branch) that leaves `npm test`, `npm run check` and `npm run test:websocket` green. Commit messages are `feat(vol-range): …` with no attribution lines. Never push, never run `git pull --rebase`, never use a bare `git stash`. Each stage updates this plan's Progress, Decision Log and Surprises.

### R1: Campaign records and baseline capture (Phase 1)

Files: new `vol_range/application/campaign.cljs`; modify `vol_range/infrastructure/executor_place.cljs`, `vol_range/contracts.cljs` (versions), `runtime/effect_adapters/vol_range_execution.cljs` (sync stores campaigns, after the `grid-store` merge), `vol_range/application/dock_model.cljs` and `views/trade/vol_range/dock.cljs` (tracking line; `view-state` adds `[:vol-range :campaigns]`), `test/hyperopen/vol_range/a0_boundary_test.cljs` (classify `campaign.cljs` if it requires a foundation). Tests: `campaign_test`, `campaign_capture_test` (new file; `lifecycle_scenarios_test.cljs` is near its size limit), `dock_model_test` additions, dock view test addition, `vol_range_slice_repaint_test` (the dock repaints when `[:vol-range :campaigns]` changes).

Acceptance:
- The first admitted placement on the seven-order fixture commits exactly one campaign record with `q0 "150"`, `p0 "80"`, `:p0-source :book-midpoint`, the boundary times from the barrier read, `c0 "0"` and the versions, in the same `commit!` call as the intent and grid, before `send!`.
- A second placement, a replacement placement (with `:predecessor`) or a repair adds no campaign. The action digest and reservations are unchanged versus the pre-R1 baseline (snapshot test). A rejecting commit means nothing is sent. A placement refused by the monitoring gate or the predecessor check commits no campaign (both refuse before the commit).
- The dock row shows "Tracking since …" for that ladder and "Not tracked" for a recovered ladder.
- `npm run test:vol-range`, `npm test`, `npm run check` and `npm run test:websocket` pass.

### R2: Ledger store, readers, classification and coverage (Phase 1)

Files: new `domain/report_intervals.cljs`, `domain/report_ledger.cljs`, `application/report_attribution.cljs`, `application/report_coverage.cljs`, `infrastructure/ledger_store.cljs` (own codec), `infrastructure/report_reads.cljs`, `infrastructure/report_executor.cljs` (`load!`, `backfill!`), `test/…/report_fixtures.cljs`; modify `platform/indexed_db.cljs` (`vol-range-ledger` store, version 9; the only store added by the three Vol Range items), `vol_range/infrastructure/reads.cljs` (optional priority), `vol_range/contracts.cljs` (`report-policy`), `test/hyperopen/vol_range/a0_boundary_test.cljs` (classify the new infrastructure and attribution namespaces). `journal.cljs` is not changed. Fills are normalized with `fill-records/normalize-fill`. Tests are listed under R2 in the test plan. No UI, no registration.

Acceptance:
- 13.5 rows 3, 4 and 8 and the ledger, coverage and classification tests pass.
- `backfill!` with fakes: balance read first; rows persisted once across two overlapping runs; retention (10,000 distinct fill keys across all coins, overlap duplicates not counted), no-progress and page-cap reads produce `:gap` intervals; a later complete read heals; coverage is merged only for the base tokens tracked when the read was requested, and a campaign created during a read starts with `:partial`.
- No change to the existing `fills_window_test` expectations (default priority stays `:high`).
- All gates green.

### R3: Reporting flag, wiring, Portfolio tab skeleton and Start tracking (Phase 1)

Files: new `infrastructure/campaign_executor.cljs`, `infrastructure/report_watcher.cljs`, `infrastructure/ledger_ingest.cljs`, `report_actions.cljs`, `runtime/effect_adapters/vol_range_report.cljs`, `application/report_model.cljs`, `application/report_copy.cljs`, `schema/vol_range_report_contracts.cljs`, `schema/runtime_registration/vol_range_report.cljs`, `views/portfolio/vol_range_report.cljs`, `views/portfolio/vol_range/{coverage,activity,tracking}.cljs`, `tools/playwright/test/vol-range-report.spec.mjs`; modify `config.cljs`, `shadow-cljs.edn`, `vol_range_action_args.cljs`, `effect_args.cljs`, `runtime_registration_catalog.cljs`, `runtime/collaborators/vol_range.cljs`, `app/effects.cljs`, `app/bootstrap.cljs`, `views/portfolio/account_tabs.cljs`, `portfolio/actions.cljs`, `dock_model.cljs` and `dock.cljs` (View report), `execution_watcher.cljs` or `live_monitor.cljs` (`register-tick-listener!`), `test/hyperopen/vol_range/a0_boundary_test.cljs`, and `console_preload/vol_range_fixture.cljs`. The WebSocket consumer is registered through `live-ingress/register-fills-consumer!`; no websocket file changes.

Acceptance:
- With the flag on, `/portfolio?tab=vol-range` shows the baseline, "Cost basis unavailable", coverage chip and detail, execution activity (fills, volumes, VWAPs, fees by token) and Start new tracking. There are no dollar figures yet.
- Opening the tab triggers one load (coalesced); rendering triggers none. Periodic backfill runs only from the execution watcher's existing tick (no new `set-interval!` call anywhere in reporting; asserted by a source scan in the reporting boundary test).
- Start tracking under a held admission section returns busy; with an unresolved attempt it is refused; on success the old campaign has a closure and the new one has `:started-by :user-restart`.
- Flag off or the trader route: no tab. Spectate: no Start tracking.
- Playwright R3 scenario passes, with zero real exchange requests.
- All gates green; `lint:hiccup`, `lint:class-attrs`, `lint:style-keys`, `lint:theme-colors` and `lint:input-parsing` green.

### R4: Marks, windows, headline figures and aggregation (Phase 2)

Files: new `domain/report_equations.cljs`, `domain/report_marks.cljs`, `application/report_windows.cljs`, `application/report_aggregate.cljs`, `views/portfolio/vol_range/{headline,controls}.cljs`; modify the adapter (`:marks` op), `report_actions.cljs` (window and subset actions), `vol_range_action_args.cljs`, `report_model.cljs` and `report_copy.cljs`, `schema/vol_range_report_contracts.cljs`, `vol_range_report.cljs`, the fixture seeding and the Playwright spec.

Acceptance:
- 13.5 rows 1, 2, 3, 6, 7 and 9 pass exactly; `Net = Hold + Contribution` holds for every case.
- 7D before tracking shows the unavailable message; Since tracking began before the first completed hour shows the baseline-only message; a missing boundary candle shows "No completed hourly price".
- The All tracked markets total is suppressed and the covered subset is offered and named.
- Playwright R4 scenarios pass at 375 and 1440.
- All gates green.

### R5: Matched-fill spread and P&L chart (Phase 3)

Files: new `domain/report_matching.cljs`, `application/report_chart_model.cljs`, `views/portfolio/vol_range/{matched,pnl_chart}.cljs`; modify `report_model.cljs`, `report_copy.cljs`, the contracts, `report_actions.cljs` (ui flag), `vol_range_action_args.cljs`, `vol_range_report.cljs`, and the Playwright spec.

Acceptance:
- 13.5 row 5 reproduces 99 / 2 / −10 / 91; the reconciliation-equals-contribution property holds.
- The chart uses `views/chart/d3/runtime.cljs` with `:surface :vol-range-report`; gaps render as breaks; the series ends at a break; no "equity" string exists in the chart spec or DOM.
- The chart re-renders on window change without leaking `on-render` handlers (memoized spec).
- All gates green.

### R6: Export, end-to-end browser coverage, final gates (Phase 3)

Files: new `application/report_export.cljs`; modify the adapter (`:export` op with Blob and anchor), `report_actions.cljs`, `vol_range_action_args.cljs`, `report_model.cljs` (`:export`), `vol_range_report.cljs` (buttons), the Playwright spec (downloads, viewports, keyboard), and this plan (Outcomes).

Acceptance:
- The JSON export contains normalized fills with attribution, order and revision mappings (grid, operation, logical leg, CLOID, oid), signed fees by token, campaign baselines, closures, coverage intervals, breaks, marks with source timestamps, window boundaries, valuation method and all versions. A recursive scan finds no forbidden keys. The CSV has human-readable columns.
- Playwright: full `vol-range-report.spec.mjs` passes, then `vol-range.spec.mjs` and `vol-range-execution.spec.mjs` still pass.
- `npm run gates` PASS; `npm run lint:bundle-budget` checked after a release build (record the result; apply the route-runtime fallback if it fails); `npm run browser:cleanup` leaves no sessions.

## Concrete Steps

From the worktree root:

    npm run setup:worktree
    npm run test:vol-range
    node out/vol-range-test.js --test=hyperopen.vol-range.domain.report-ledger-test

Per stage:

    npm run test:runner:generate
    npm run lint:namespace-sizes && npm run lint:namespace-boundaries && npm run lint:input-parsing
    npm run lint:hiccup && npm run lint:class-attrs && npm run lint:style-keys && npm run lint:theme-colors   # UI stages
    npm run gates

Browser (R3 onward). If port 8080 is held by another checkout, use the static-server recipe from the parent plan:

    npx playwright test tools/playwright/test/vol-range-report.spec.mjs
    npx playwright test tools/playwright/test/vol-range-execution.spec.mjs tools/playwright/test/vol-range.spec.mjs
    npm run browser:cleanup

Commit (each stage):

    git add -A && git commit -m "feat(vol-range): <stage summary>"

## Validation and Acceptance

Release B reporting is accepted when:

- Every 13.5 row passes as a unit test with exact decimal strings, and VR-19 to VR-21 are covered: route changes keep ingestion; backfill caps, pagination stalls and retention preserve uncertainty; equations, fee-token effects, unmatched trades, selected-window baselines and cross-asset sums reconcile exactly; external activity and incomplete evidence suppress headlines while known execution data stays visible.
- Baselines are captured at the first admitted placement in the intent's transaction, with no observable change to placement, which the unchanged lifecycle scenario suites demonstrate.
- No user-visible surface shows "grid", "equity", a percentage return or a cost basis; the chart axis reads "Cumulative P&L (USDC)".
- `npm run gates` is green, `vol-range-report.spec.mjs` passes, and the existing Vol Range specs still pass.

## Idempotence and Recovery

- Ledger puts are keyed by fill key, ledger key or check id, so re-running backfill is harmless. Coverage merges are monotonic under a per-account lock.
- Campaign records are immutable, and a closure record is written only together with its successor in one transaction.
- If the ledger store is unavailable, reports show `:storage/unavailable`. Placement is unaffected; the baseline lives in the journal.
- If a stage's registration is incomplete, the app or test build fails at load with a drift error naming the id; fix the registration and rerun.
- Never hand-edit `test/test_runner_generated.cljs`; regenerate it.

## Artifacts and Notes

13.5 expected values (all exact):

    q0=150 P0=80; buy 7 @ 76.4; mark 70           -> Net -1544.8  Hold -1500  Contribution -44.8
    q0=150 P0=80; sell 30 for 2652; mark 100      -> Net 2652     Hold 3000   Contribution -348
    buy 10 @ 80, fee 0.004 base; mark 80          -> net base 9.996, cash -800, contribution -0.32
    fee 0.4 USDC with builderFee 0.1              -> fee debit 0.4
    buy 10 @ 80 fee 0.1 base; sell 9.9 @ 90; P 100 -> gross matched 99, unmatched buy 2, base fee impact -10, contribution 91

## Interfaces and Dependencies

No new third-party libraries. Interfaces that must exist at the end of each stage:

- R1: `campaign/campaign-key scope`, `campaign/baseline-record {...}`, `campaign/closure-record {...}`, `campaign/open-campaign records campaign-key`, `campaign/campaigns-for-account records account`, `campaign/campaign-for-time campaigns closures campaign-key t`, `campaign/baseline-records-for-placement {...}`.
- R2: `report-intervals/{union subtract covers? uncovered intersect}`; `report-ledger/{fill-event events state-before activity}`; `report-attribution/{attribution-index classify-fill classify-ledger-row}`; `report-coverage/{fills-read->coverage ledger-read->coverage campaign-coverage evaluate-balance-check}`; `ledger-store/{put-records! load-account! merge-coverage! put-balance-check!}`; `report-reads/{read-ledger-window! read-hourly-candles!}`; `report-executor/{load! backfill!}`.
- R3: `campaign-executor/start-tracking!`; `report-model/report-view-model`; `[:effects/vol-range-report op params]`; the actions listed above.
- R4: `report-equations/window-figures`; `report-marks/{hour-floor window-end mark-at normalize-candles}`; `report-windows/{resolve-window campaign-window-figures}`; `report-aggregate/aggregate`.
- R5: `report-matching/match-window`; `report-chart-model/chart-spec`.
- R6: `report-export/{json-document fills-csv filename}`.

External: `userFillsByTime` (2,000 rows per page; the most recent 10,000 fills only), `userNonFundingLedgerUpdates` (page limit treated as 2,000 until a protocol fixture confirms it), `candleSnapshot` 1h (at most 5,000 bars per request).

## Open questions

Questions only the product owner can answer (everything else was resolved with a default recorded in the Decision Log):

1. Illiquid pairs: should a missing hourly close ever be valued by an explicit, labeled carry-forward method, or stay unavailable (current default, spec 12.4 "missing marks create gaps")?

Resolved by the integration review with defaults: All tracked markets shows no Since-tracking total (spec 12.7); ledger page size 2,000 and unknown retention, with the reporting flag kept off in release until a protocol fixture confirms both; the 5 s skew is a policy value; bundle placement is decided by `lint:bundle-budget` in R6; campaigns are per browser (spec 7.4, 7.6), and the report explains that ladders placed from another device break attribution.

Revision note (2026-10-28): initial version written from spec sections 7, 9, 11-13 and a read of the current branch (journal, executor, reads, dock, IndexedDB, Portfolio tabs, download pattern, registration seams).

Revision note (2026-10-28, integration review): adopted the live-fills seams as shipped (`fill-records/normalize-fill`, `live-ingress/register-fills-consumer!`) and made `vol-range-ledger` the only ledger store; coverage made per base token with the retention count over distinct fill keys of all rows; `ledger_store` gets its own codec and `journal.cljs` is untouched; periodic backfill rides the execution watcher's tick; the dock `view-state` carries campaigns; the A0 boundary classification and the reporting boundary test's `action-coordinator` allowance are explicit; Playwright uses the guarded `test` and the shared `bootstrap`; open questions narrowed to the one product decision; sequenced as stages 20-25 of the global build order.
