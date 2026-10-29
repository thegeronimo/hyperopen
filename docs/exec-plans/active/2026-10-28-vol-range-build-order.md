# Vol Range Release A/B completion: global build order for repair, live fills, replace and reporting

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. It is maintained in accordance with `/hyperopen/docs/PLANS.md` and the detailed template in `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Three item plans finish the remaining Vol Range work from HO-SPEC-014 v2.1:

- Repair rejected orders and replace a ladder: `/hyperopen/docs/exec-plans/active/2026-10-28-vol-range-repair-replace.md`
- Live WebSocket fills, monitoring freshness and resync before placement: `/hyperopen/docs/exec-plans/active/2026-10-28-vol-range-live-fills.md`
- Portfolio reporting (campaigns, ledger, coverage-aware P&L, export): `/hyperopen/docs/exec-plans/active/2026-10-28-vol-range-reporting.md`

This plan is the single global order in which their 25 stages run. They run strictly one after another in one worktree (`feature/vol-range-a0`). Each stage is one commit that leaves every gate green. It also records the contracts that more than one item shares, so an agent implementing a later stage knows which earlier stage defined each shape. When every stage is done, a user can repair a partially deployed ladder, see fills arrive live, update a ladder through cancel-then-replace, and open a Vol Range report in Portfolio. A user who now runs the local money test from `/hyperopen/docs/runbooks/vol-range-local-money-test.md` can see all of it.

Order: Repair (1-5), then Live fills (6-15), then Replace (16-19), then Reporting (20-25). This is the requested order with no change. Repair and Replace come from one plan file, split around Live fills.

## Context References

Public refs:
- Direct user request in this session (2026-10-28): run the planning workflow, then immediately implement the plans through a workflow and report back for testing.

Repo artifacts:
- Product spec: `/hyperopen/docs/product-specs/vol-range-implementation-spec.md` (HO-SPEC-014 v2.1).
- Parent ExecPlan: `/hyperopen/docs/exec-plans/active/2026-10-21-vol-range-release-a0-preview.md`.
- Item plans listed above. When an item plan closes it moves to `/hyperopen/docs/exec-plans/completed/` under the same file name; later stages read it there.

## Progress

- [x] (2026-10-28) Integration review of the three item plans against the code at `dd19bd749`; plan files corrected; this build order written.
- [x] (2026-10-28) Stage 1 `repair-R1`: record ladder settings at placement.
- [x] (2026-10-28) Stage 2 `repair-R2`: pure repair proposal and shared repair checks.
- [x] (2026-10-28) Stage 3 `repair-R3`: repair actions, effect ops, whitelists and adapter.
- [x] (2026-10-28) Stage 4 `repair-R4`: repair dialog, dock control and result messaging.
- [x] (2026-10-28) Stage 5 `repair-R5`: repair browser coverage.
- [x] (2026-10-28) Stage 6 `live-S1`: pure evidence envelope, routing and fill identity.
- [x] (2026-10-28) Stage 7 `live-S2`: monotonic store merge for ladders.
- [x] (2026-10-28) Stage 8 `live-S3`: raw user-stream taps in the account runtime.
- [x] (2026-10-28) Stage 9 `live-S4`: live ingress applies WebSocket evidence to ladders in memory.
- [x] (2026-10-28) Stage 10 `live-S5`: pure monitoring model.
- [ ] Stage 11 `live-S6`: monitoring wiring and the resync operation.
- [ ] Stage 12 `live-S7`: placement and repair gate on monitoring, and the Playwright WebSocket simulator.
- [ ] Stage 13 `live-S8`: dock and review UI for live monitoring; narrower order-form slice.
- [ ] Stage 14 `live-S9`: shared fill record normalizer and the fills consumer seam for Reporting.
- [ ] Stage 15 `live-S10`: live Playwright spec, documentation, close-out.
- [ ] Stage 16 `replace-P1`: replacement core.
- [ ] Stage 17 `replace-P2`: replace actions, effect ops, whitelists, adapter and planner hooks.
- [ ] Stage 18 `replace-P3`: replace dialog, ticket banner, review lineage and dock lineage.
- [ ] Stage 19 `replace-P4`: replace browser coverage, runbook, full gates and close-out.
- [ ] Stage 20 `report-R1`: campaign records and baseline capture.
- [ ] Stage 21 `report-R2`: ledger store, readers, classification and coverage.
- [ ] Stage 22 `report-R3`: reporting flag, wiring, Portfolio tab skeleton and Start tracking.
- [ ] Stage 23 `report-R4`: marks, windows, headline figures and aggregation.
- [ ] Stage 24 `report-R5`: matched-fill spread and P&L chart.
- [ ] Stage 25 `report-R6`: export, end-to-end browser coverage, final gates.
- [ ] Final: whole-branch gates and Playwright pass recorded below; this plan moved to completed; report to the user for testing.

## Global Stage Order

Each row names the plan file and the exact stage heading to implement. "Needs" lists the earlier stages whose contracts the stage builds on (beyond simple file order).

| # | Id | Plan file | Exact stage heading | Summary | Needs |
| --- | --- | --- | --- | --- | --- |
| 1 | repair-R1 | `2026-10-28-vol-range-repair-replace.md` | `### Stage R1: record ladder settings at placement` | Grids store `:settings` (normalized draft, limits, totals, outcomes); draft frozen at review. | — |
| 2 | repair-R2 | `2026-10-28-vol-range-repair-replace.md` | `### Stage R2: pure repair proposal and shared repair checks` | `repair_plan.cljs`: model-sigma repricing away from the book, full rejected size; inventory bounds added to `revalidate-repair` (final guard). | 1 |
| 3 | repair-R3 | `2026-10-28-vol-range-repair-replace.md` | `### Stage R3: repair actions, effect ops, whitelists and adapter` | `:repair-preview`/`:repair` ops (repair unbounded), frozen proposal, leg mismatch refusal, registration, focused build covers adapter tests. | 2 |
| 4 | repair-R4 | `2026-10-28-vol-range-repair-replace.md` | `### Stage R4: repair dialog, dock control and result messaging` | "Repair N rejected orders" button, "Review rejected orders" dialog, live-region text, contracts. | 3 |
| 5 | repair-R5 | `2026-10-28-vol-range-repair-replace.md` | `### Stage R5: repair browser coverage` | Shared `vol_range_world.mjs` with `bootstrap`; repair spec (one order at 76.16, survivors never resent). | 4 |
| 6 | live-S1 | `2026-10-28-vol-range-live-fills.md` | `### Stage 1: pure evidence envelope, routing and fill identity` | Spec 9.1 envelopes, routing by network/account/coin/oid/CLOID, conservative open-order rules, canonical `fill-key` inputs, synthetic fixtures. | — |
| 7 | live-S2 | `2026-10-28-vol-range-live-fills.md` | `### Stage 2: monotonic store merge for ladders` | Pure `grid_store.cljs` merge used by the adapter; `reconcile-open!` returns results. | 6 |
| 8 | live-S3 | `2026-10-28-vol-range-live-fills.md` | `### Stage 3: raw user-stream taps in the account runtime` | Guarded raw taps before truncation and cancel-guard pruning; no-op taps injected at startup. | 7 |
| 9 | live-S4 | `2026-10-28-vol-range-live-fills.md` | `### Stage 4: live ingress applies WebSocket evidence to ladders in memory` | WebSocket evidence merged into store ladders; rate-limited reconcile hints. | 6, 7, 8 |
| 10 | live-S5 | `2026-10-28-vol-range-live-fills.md` | `### Stage 5: pure monitoring model` | Epochs, stale/resyncing/live transitions, 45 s suspension, backoff with jitter, `placement-blocker`. | — |
| 11 | live-S6 | `2026-10-28-vol-range-live-fills.md` | `### Stage 6: monitoring wiring and the resync operation` | Watcher wiring, `:actions/resync-vol-ladders` and `:resync` op, background ops stop overwriting `:last`. | 9, 10 |
| 12 | live-S7 | `2026-10-28-vol-range-live-fills.md` | `### Stage 7: placement and repair gate on monitoring, and the Playwright WebSocket simulator` | Monitoring blocker in the single `refusal` (place, repair, final guard); WebSocket simulator in the shared `bootstrap`. | 5, 11 |
| 13 | live-S8 | `2026-10-28-vol-range-live-fills.md` | `### Stage 8: dock and review UI for live monitoring; narrower order-form slice` | Monitoring line, disabled review and repair submit, refusal copy, net-zero `trade_view.cljs` edit via `vol-dock/order-form-slice`. | 4, 12 |
| 14 | live-S9 | `2026-10-28-vol-range-live-fills.md` | `### Stage 9: shared fill record normalizer and the fills consumer seam for Reporting` | `fill-records/normalize-fill` and `live-ingress/register-fills-consumer!`; no ledger store here. | 6, 9 |
| 15 | live-S10 | `2026-10-28-vol-range-live-fills.md` | `### Stage 10: live Playwright spec, documentation, close-out` | `vol-range-live.spec.mjs`, ADR section 9, runbook "Live updates", plan to completed. | 12, 13 |
| 16 | replace-P1 | `2026-10-28-vol-range-repair-replace.md` | `### Stage P1: replacement core` | Frozen membership (journal-refreshed, `:filled-at-freeze`), whole-grid blocking, `replace_state`/`replace_flow`, predecessor admission, `confirm!`. | 1, 12 |
| 17 | replace-P2 | `2026-10-28-vol-range-repair-replace.md` | `### Stage P2: replace actions, effect ops, whitelists, adapter and planner hooks` | `:replace-cancel`/`:replace-confirm` ops, error -> `:blocked`, planner account override, review guard and lineage. | 7, 16 |
| 18 | replace-P3 | `2026-10-28-vol-range-repair-replace.md` | `### Stage P3: replace dialog, ticket banner, review lineage and dock lineage` | Update dialog, ticket banner, review lineage, dock lineage; order-form slice carries the grid under update. | 13, 17 |
| 19 | replace-P4 | `2026-10-28-vol-range-repair-replace.md` | `### Stage P4: replace browser coverage, runbook, full gates and close-out` | Full replace flow in Playwright (27 remainder, leave-after-cancel places nothing), runbook, plan to completed. | 15, 18 |
| 20 | report-R1 | `2026-10-28-vol-range-reporting.md` | `### R1: Campaign records and baseline capture (Phase 1)` | Campaign baseline committed in the intent transaction before send; dock tracking line; campaigns in dock view-state. | 16, 17 |
| 21 | report-R2 | `2026-10-28-vol-range-reporting.md` | `### R2: Ledger store, readers, classification and coverage (Phase 1)` | The single `vol-range-ledger` store (v9), per-token REST coverage with the distinct-key retention rule, classification, balance checks. | 14, 20 |
| 22 | report-R3 | `2026-10-28-vol-range-reporting.md` | `### R3: Reporting flag, wiring, Portfolio tab skeleton and Start tracking (Phase 1)` | Flag, `:effects/vol-range-report`, actions, tick-listener backfill, WebSocket consumer, Portfolio tab without dollar figures, Start tracking under admission. | 11, 14, 21 |
| 23 | report-R4 | `2026-10-28-vol-range-reporting.md` | `### R4: Marks, windows, headline figures and aggregation (Phase 2)` | Hourly marks, half-open windows, the three headline figures with suppression, All tracked markets. | 22 |
| 24 | report-R5 | `2026-10-28-vol-range-reporting.md` | `### R5: Matched-fill spread and P&L chart (Phase 3)` | FIFO matched spread reconciliation and the cumulative P&L chart (never "equity"). | 23 |
| 25 | report-R6 | `2026-10-28-vol-range-reporting.md` | `### R6: Export, end-to-end browser coverage, final gates (Phase 3)` | JSON and CSV export, four-viewport Playwright, bundle budget, plan to completed. | 24 |

## Rules for every stage

- Work only in `/hyperopen` (this worktree). Run `npm run setup:worktree` once before the first gate.
- Implement exactly the named stage heading of the named plan file, including its tests, A0 boundary classification and registration edits, and nothing from later stages.
- Required before the commit: the stage's focused tests (`npm run test:vol-range`, plus `npm run test:websocket` for stages 8, 9 and 11), `npm run test:runner:generate` when test namespaces were added (never hand-edit `test/test_runner_generated.cljs`), then `npm run gates` (check, test, test:websocket) PASS. Run the Playwright specs a stage lists, smallest first, then `npm run browser:cleanup`.
- Update the item plan's `Progress`, `Surprises & Discoveries`, `Decision Log` and `Outcomes & Retrospective`, and tick the stage in this plan's `Progress`, in the same commit.
- Commit message style `feat(vol-range): …`, `fix(vol-range): …` or `test(vol-range): …`, with no attribution lines. Never `git push`, never `git pull --rebase`, never a bare `git stash` (use a temporary WIP commit to set work aside).
- Real money: no agent, script or test ever places, cancels or modifies a live order or calls the real exchange. Unit tests use `test/hyperopen/vol_range/simulator.cljs` and fakes; Playwright uses the debug exchange simulator, `page.route` for `/info` and `page.routeWebSocket` for the socket, and asserts no request reached a real exchange.
- Every new send path goes through `executor-place/admit-and-send!` (account admission section, journal commit before send, the final pre-HTTP guard via `refusal`, strict per-leg interpretation, unknown outcomes kept unresolved). No stage adds another send path.
- Namespace size: default limit 500 lines; never raise a ceiling in `dev/namespace_size_exceptions.edn`, split instead. Files at or near a limit that these stages must not grow: `src/hyperopen/views/trade_view.cljs` (500, no exception; stage 13 makes a net-zero edit only), `src/hyperopen/runtime/effect_order_contract.cljs`, `src/hyperopen/schema/contracts/action_args.cljs`, `src/hyperopen/telemetry/console_preload.cljs`, `src/hyperopen/websocket/user_runtime/fills.cljs` (500), `src/hyperopen/portfolio/actions.cljs` (856 of 861; stage 22 adds at most 2 lines or extracts), `src/hyperopen/startup/collaborators.cljs` (483), `test/hyperopen/vol_range/infrastructure/lifecycle_scenarios_test.cljs` (413; put new scenarios in new files).
- VR-24 (A0 boundary): `test/hyperopen/vol_range/a0_boundary_test.cljs` scans every unclassified file under `src/hyperopen/vol_range/**` and every file under `src/hyperopen/views/trade/vol_range/**` for execution-foundation namespace names (a substring match, so `executor` also matches `executor-place`/`executor-replace` and `lifecycle` also matches `lifecycle-evidence`), `hyperopen.api.trading`, `hyperopen.platform.indexed-db` and the word `nonce`. A stage that creates an execution-dependent namespace under `src/hyperopen/vol_range/` adds its path to `execution-foundations` in the same commit. Views under `views/trade/vol_range/` are always scanned and must not require those namespaces.
- Every new Playwright spec imports `{ expect, test }` from `../support/guarded_test.mjs` (`tools/playwright/support/spec_imports.test.mjs`, run by `npm run check`, fails otherwise) and calls the shared `bootstrap` from `tools/playwright/support/vol_range_world.mjs`.

## Shared contracts (final state, and the stage that defines each)

Effect `[:effects/vol-range-execute op params]`, op set in `src/hyperopen/schema/contracts/effect_args.cljs` (`::vol-range-execute-args`), grown additively:

    #{:place :cancel :refresh :sync :reconcile-open      ; existing
      :repair-preview :repair                            ; stage 3
      :resync                                            ; stage 11
      :replace-cancel :replace-confirm}                  ; stage 17

`bounded` (60 s race) exempts `#{:place :repair}`. Busy keys are `[op grid-id]` or `[op]`. Successful `:sync`, `:reconcile-open` and `:resync` do not write `[:vol-range :execution :last]` (stage 11). `:last :op` values used by `dock-model/status-message`: the ops above plus `:replace-stop` (written by the pure Stop updating action, stage 17).

Effect `[:effects/vol-range-report op params]` (stage 22), ops `#{:load :backfill :start-tracking :marks :export}` in `::vol-range-report-args`, registered in `src/hyperopen/schema/runtime_registration/vol_range_report.cljs`.

`runtime/collaborators/vol_range.cljs` `execution-action-deps` (Release A group; the A0 `action-deps` group never changes) gains, in order: repair handlers (stage 3), `:resync-vol-ladders` (stage 11), replace handlers (stage 17). Reporting adds a separate `report-action-deps` (stage 22). Binding rows for Vol Range execution actions go in `post-order-form-action-binding-rows` of `schema/runtime_registration/trade.cljs`, after `[:actions/reconcile-open-vol-ladders ...]`.

Grid record additions (journal `:grid`, optional, schema version unchanged; `merge-grids` keeps the caller's top-level keys):

    :settings {:plan-id :draft :limits {:core :target :maximum} :totals :inventory-outcomes}   ; stage 1
    :predecessor {:grid-id :frozen-cloids :remaining {:buy :sell}
                  :filled-during-update {:buy :sell} :included-rejected []}                  ; stage 16

Store state:

    [:vol-range :repair]                    ; frozen repair proposal, stage 3
    [:vol-range :replace]                   ; in-memory update flow (never persisted), stages 16-17
    [:vol-range :execution :monitoring]     ; {:account-address :status :reasons :epoch :stale-since-ms
                                            ;  :live-since-ms :fills-snapshot-at-ms :last-close-at-ms
                                            ;  :last-tick-ms :resync {...}}, stages 10-11
    [:vol-range :campaigns] [:vol-range :campaign-closures] [:vol-range :report]          ; stages 20, 22

Refusal codes added to the single `executor-place/refusal` seam (place, repair and the final guard; never cancel): `:monitoring/stale`, `:monitoring/resync-required`, `:monitoring/suspended` (stage 12). Placement admission codes: `:replace/predecessor-missing`, `:replace/predecessor-scope`, `:replace/predecessor-not-settled`, `:replace/already-replaced` (stage 16). Repair adapter: `:repair/proposal-mismatch` (stage 3). Repair bounds: `:repair/max-inventory-breach`, `:repair/protected-core-breach` (stage 2).

Pure store merge: `hyperopen.vol-range.application.grid-store/merge-grid-into-state` and `merge-account-grids` (stage 7). Every writer of `[:vol-range :execution :grids]` uses them; nothing replaces a grid copy wholesale.

Fill identity and records: `lifecycle-evidence/fill-key` (inputs canonicalized in stage 6; byte-identical for numeric inputs) is the one fill key everywhere. `hyperopen.vol-range.application.fill-records/normalize-fill` (stage 14) is the one canonical fill record (shape in the live-fills plan, Stage 9). `hyperopen.vol-range.infrastructure.live-ingress/register-fills-consumer!` (stage 14) is the one way to receive raw `userFills` envelopes.

Durable stores: `vol-range-journal` (existing; stage 20 adds `:campaign` and `:campaign-closed` records in the intent's transaction) and `vol-range-ledger` (stage 21, IndexedDB `app-db-version` 9; the only new store). Coverage is written only by Reporting's REST backfill, per account, stream and base token.

View slices: `vol-dock/view-state` (account-info slice) carries `[:vol-range :execution]` plus `:repair`, `:replace`, the current scope key (stage 4) and `:campaigns` (stage 20). `vol-dock/order-form-slice` (stage 13) passes everything under `:vol-range` except a narrowed `:execution` (`:enabled? :max-ladder-usdc :busy :last`, `:monitoring` without `:last-tick-ms`), plus the grid under update (stage 18).

Playwright support: `tools/playwright/support/vol_range_world.mjs` (`newWorld`, `stubInfo` with `world.book`, `bootstrap`, `installExchange`, row helpers; stage 5) and `tools/playwright/support/ws_simulator.mjs` (`installWsSimulator`, installed by `bootstrap` from stage 12).

## Files touched by more than one item

| File | Stages |
| --- | --- |
| `src/hyperopen/vol_range/infrastructure/executor_place.cljs` | 1, 2, 12, 16, 20 |
| `src/hyperopen/runtime/effect_adapters/vol_range_execution.cljs` | 1, 3, 7, 11, 12, 17, 20 |
| `src/hyperopen/schema/contracts/effect_args.cljs` | 3, 11, 17, 22 |
| `src/hyperopen/schema/contracts/vol_range_action_args.cljs`, `schema/runtime_registration/trade.cljs`, `runtime/collaborators/vol_range.cljs` | 3, 11, 17, 22 |
| `src/hyperopen/vol_range/application/dock_model.cljs`, `src/hyperopen/views/trade/vol_range/dock.cljs` | 4, 11, 13, 18, 20, 22 |
| `src/hyperopen/vol_range/infrastructure/execution_watcher.cljs` (or `live_monitor.cljs`) | 11, 22 |
| `src/hyperopen/vol_range/infrastructure/live_ingress.cljs` | 8, 9, 11, 14, 22 (consumer registration only, via its public seam) |
| `src/hyperopen/vol_range/application/lifecycle.cljs` | 1, 16, 17 |
| `src/hyperopen/vol_range/infrastructure/reads.cljs` | 21 |
| `test/hyperopen/vol_range/a0_boundary_test.cljs` | 2, 3, 4, 6, 7, 9, 14, 16, 17, 18, 20, 21, 22 |
| `test/hyperopen/vol_range/simulator.cljs` | 1-2 (fixtures), 9, 16 |
| `shadow-cljs.edn` | 3 (`runtime\\.effect-adapters\\.vol-range.*` in `:vol-range-test`), 22 (reporting flag, `views\\.portfolio\\.vol-range.*`) |
| `tools/playwright/support/vol_range_world.mjs` | 5, 12, 15, 19, 22 |
| `tools/playwright/test/vol-range-execution.spec.mjs` | 5 (imports shared helpers), 12 (WebSocket simulator through `bootstrap`) |
| `docs/runbooks/vol-range-local-money-test.md` | 15, 19, 25 |

## Surprises & Discoveries

- Observation: two plans each created a fill store at IndexedDB version 9 (`vol-range-fills` in live fills, `vol-range-ledger` in reporting) with different record shapes and coverage models. Live-fills coverage came from a grid's account-wide REST window but persisted only that grid's coin.
  Evidence: live-fills Stage 9 and reporting R2 as first written. Resolved: one store, owned by reporting (Decision Log).
- Observation: the A0 boundary test classifies execution code by an explicit path set and scans everything else under `src/hyperopen/vol_range/**` and `src/hyperopen/views/trade/vol_range/**` by substring. The repair-replace plan claimed it would stay unchanged, although most of its new namespaces require `lifecycle` or `revalidation`.
  Evidence: `test/hyperopen/vol_range/a0_boundary_test.cljs` `execution-foundations` and `vol-range-sources-reference-no-order-signing-or-storage-test`.
- Observation: `vol-range-execute` races every op except `:place` against a 60 s timer; the repair plan routed the signed `:repair` op through it.
  Evidence: `bounded` in `src/hyperopen/runtime/effect_adapters/vol_range_execution.cljs`.
- Observation: every successful background reconcile writes `{:op :reconcile-open :status :ok}` to `[:vol-range :execution :last]`, which the dock's polite live region announces as "Ladder ok." every 15 s and which erases the outcome of the user's last action.
  Evidence: `finish!` in the adapter and `status-message` in `dock_model.cljs`.
- Observation: a planned `vol_range_resync.cljs` that requires the adapter while the adapter requires it would be a require cycle; a `live_ingress.cljs` requiring the runtime adapter would pull delivery and signing into the ingress.
  Evidence: live-fills Stage 2 and Stage 6 as first written.
- Observation: `trade_view.cljs` is exactly at the 500-line default with no exception; live fills planned to edit it.
- Observation: the reporting tracking line would never repaint, because the dock's memoized slice (`vol-dock/view-state`) only carries `[:vol-range :execution]`.
- Observation: reporting planned its own backfill interval timer, which spec 9.3 rules out ("Do not run a separate feature WebSocket or timer registry").
- Observation: every Playwright spec must import `test` from `../support/guarded_test.mjs`; none of the plans said so.

## Decision Log

- Decision: keep the requested order (repair, live fills, replace, reporting). Repair and Replace come from one plan but are split around Live fills.
  Rationale: Replace benefits from landing after Live fills. Its confirmation and placement then already run with the monitoring gate in `refusal`, the monotonic store merge and the narrowed order-form slice, so it is written once against the final seams. Reporting R1 edits the placement commit last, on top of the settings, predecessor and monitoring changes, and its R2/R3 consume the live-fills normalizer and consumer seam. No stage depends on a later one.
  Date/Author: 2026-10-28, Claude (integrator).
- Decision: one durable ledger store (`vol-range-ledger`, version 9, reporting R2). Live fills ships only the shared normalizer and the consumer seam (its Stage 9 was rewritten). No `:ledger!` executor dependency; nothing is added to `executor/reconcile!`.
  Rationale: one record shape, one coverage model, one owner; fewer writes near the real-money path.
  Date/Author: 2026-10-28, Claude (integrator).
- Decision: reporting coverage is per account, stream and base token, with the retention count over distinct fill keys of all returned rows.
  Rationale: prevents a headline over an interval in which a newly tracked token's rows were filtered out as irrelevant.
  Date/Author: 2026-10-28, Claude (integrator).
- Decision: replacement remainder uses the frozen membership taken from the journal-refreshed grid; blocking stays whole-grid; "filled while canceling" is measured against a freeze snapshot.
  Rationale: spec 10.2 "uniquely identified canceled working membership, adjusted for fills during cancellation". Whole-grid blocking means a leg the freeze missed can never let a replacement place over live orders.
  Date/Author: 2026-10-28, Claude (integrator).
- Decision: placement and repair stay refused while live monitoring is not `:live` (spec 9.3), recorded as spec-mandated rather than an open question.
  Date/Author: 2026-10-28, Claude (integrator).

## Outcomes & Retrospective

After each stage record: stage id, commit hash, gates result (`npm run gates` matrix), Playwright specs run, and any deviation from the item plan. At the end, summarize what the user can test and which protocol questions the local money test should answer.

- Stage 1 `repair-R1` (commit `feat(vol-range): record ladder settings at placement`): `npm run test:vol-range` 1021 tests 0 failures; `npm run gates` 34/34 PASS (8333 tests, 48375 assertions). Playwright: `vol-range-execution.spec.mjs` 7/7, `vol-range.spec.mjs` 5/5, no flakes; `npm run browser:cleanup` clean. Deviations: `merge-grids` keeps stored `:settings` when the caller's copy lacks them; `:settings :limits :target` is the plan's outcome (current holdings with tilt off). Both in the item plan's Decision Log.
- Stage 2 `repair-R2` (commit `feat(vol-range): pure repair proposal and inventory bounds in the repair guard`): `npm run test:vol-range` 1037 tests 0 failures; `npm run gates` 34/34 PASS (8349 tests, 48456 assertions). Playwright: `vol-range-execution.spec.mjs` 7/7, `vol-range.spec.mjs` 5/5, no flakes; `npm run browser:cleanup` clean. Deviations: `propose` returns the whole frozen proposal minus ids and status; leg checks gained `:repair/self-cross` with the notional checked last; unrepairable codes add `:repair/book-unavailable` (R4 needs a text); the 13.4 partial-grid builder lives in `repair_plan_test`. All in the item plan's Decision Log.
- Stage 3 `repair-R3` (commit `feat(vol-range): repair actions, effect ops and adapter`): `npm run test:vol-range` 1051 tests 0 failures; `npm test` 7561 tests 0 failures; `npm run check` PASS; `npm run test:websocket` 604 tests 0 failures. Playwright: `vol-range-execution.spec.mjs` 7/7, `vol-range.spec.mjs` 5/5, no flakes; `npm run browser:cleanup` clean. Deviations: one `:repair/proposal-mismatch` code for stale or edited proposals; preview failures written by `settle-preview!` from `finish!`; extra pure guards on open/toggle/submit; `:repair` refreshes fees first; simulator `:book`. All in the item plan's Decision Log.
- Stage 4 `repair-R4` (commit `feat(vol-range): repair dialog, dock control and result messaging`): `npm run test:vol-range` 1075 tests 0 failures; `npm test` 7585 tests 0 failures; `npm run check` PASS; `npm run test:websocket` 604 tests 0 failures. Playwright: `vol-range-execution.spec.mjs` 7/7, `vol-range.spec.mjs` 5/5, no flakes; `npm run browser:cleanup` clean. Deviations: `:repair` results carry `:proposal-id` (two R3 files); `repair-plan/inventory-outcomes`; the dialog's focus hook sits on the backdrop because Replicant skips nested unmount hooks, and the repair button is `aria-busy` rather than disabled while busy; sizes shown exactly. All in the item plan's Decision Log and Surprises.

- Stage 6 `live-S1` (commit `feat(vol-range): normalize WebSocket order and fill evidence for ladders`): `npm run test:vol-range` 1100 tests 0 failures; `npm run gates` 34/34 PASS (8412 tests, 48927 assertions; `npm test` 7610 tests, `npm run test:websocket` 604 tests). Playwright (no spec required by the stage; run because `fill-key` changed): `vol-range.spec.mjs`, `vol-range-execution.spec.mjs`, `vol-range-repair-replace.spec.mjs`. First run 16/17: the repair test failed at its final "Repair sent. 7 of 7 orders active." assertion (not the wallet-connect setup flake). A full rerun passed 17/17 and `--repeat-each=4` of the repair spec passed 20/20. Stage 6 code is not reachable from the app bundle (`live_evidence.cljs` has no caller yet; `fill-key` is byte-identical for numeric inputs), so this is the known background-reconcile overwrite of `:last` (Surprises above; fixed in stage 11), recorded in the live-fills plan's Surprises. `npm run browser:cleanup` clean. Deviations: fills are not routed to pending legs; open-order informativeness compares against the effective remaining; `:order-coins`/`:absent` only for default-dex frames. All in the live-fills plan's Decision Log.
- Stage 7 `live-S2` (commit `fix(vol-range): merge ladder copies in the store instead of replacing them`): `npm run test:vol-range` 1105 tests 0 failures; `npm run gates` 34/34 PASS (8417 tests, 48999 assertions; `npm test` 7615 tests, `npm run test:websocket` 604 tests). Playwright (no spec required by the stage; run because the adapter's store writes changed): `vol-range.spec.mjs`, `vol-range-execution.spec.mjs`, `vol-range-repair-replace.spec.mjs` 17/17 on the first run, no flakes. `npm run browser:cleanup` clean. Deviation: `merge-account-grids` filters the journal grids by account itself and yields nothing for a nil account (live-fills plan's Decision Log).
- Stage 8 `live-S3` (commit `feat(websocket): raw evidence taps for user fills and open orders`): `npm run test:websocket` 609 tests 0 failures; `npm test` 7620 tests 0 failures; `npm run test:vol-range` 1105 tests 0 failures; `npm run check` PASS. Playwright (no spec required by the stage; run because startup's user-stream wiring changed): `vol-range.spec.mjs`, `vol-range-execution.spec.mjs`, `vol-range-repair-replace.spec.mjs` 17/17 on the first run, no flakes. `npm run browser:cleanup` clean. Deviations: the `init!` tap tests live in the new `user_taps_test.cljs` because `user_test.cljs` is at its size exception; `runtime_startup_test.cljs` stubs the now multi-arity `user-ws/init!` with a multi-arity fn (live-fills plan's Decision Log and Surprises).
- Stage 9 `live-S4` (commit `feat(vol-range): feed live fills and open orders to ladders`): `npm run test:vol-range` 1116 tests 0 failures; `npm run test:websocket` 609 tests 0 failures; `npm test` 7631 tests 0 failures; `npm run check` PASS. Playwright (no spec required by the stage; run because the dev build now installs the taps): `vol-range.spec.mjs`, `vol-range-execution.spec.mjs`, `vol-range-repair-replace.spec.mjs`. First run 16/17: the repair test failed at its final "Repair sent. 7 of 7 orders active." assertion with "Ladder ok." shown (not the wallet-connect setup flake). It reproduces at the same or a higher rate with the taps disabled (3 of 6 with `user-stream-taps` forced to `{}`, 1 of 6 and 1 of 3 with them on), so it is the known background-reconcile overwrite of `:last` that stage 11 removes; a full rerun passed 17/17. `npm run browser:cleanup` clean. Deviations: hint timing uses the tap's receive time and is dispatched in a microtask; envelope revision epoch is 0 until stage 11 (live-fills plan's Decision Log).
- Stage 10 `live-S5` (commit `feat(vol-range): live monitoring model`): `npm run test:vol-range` 1132 tests 0 failures; `npm test` 7647 tests 0 failures; `npm run test:websocket` 609 tests 0 failures; `npm run check` PASS. Playwright (no spec required by the stage; run because the dev build's ingress now reads its hint interval from the new policy map, same value): `vol-range.spec.mjs`, `vol-range-execution.spec.mjs`, `vol-range-repair-replace.spec.mjs`. First run 16/17: the repair test failed at its final "Repair sent. 7 of 7 orders active." assertion with "Ladder ok." shown (not the wallet-connect setup flake); the new monitoring namespace has no caller in the app yet, so this is the known background-reconcile overwrite of `:last` that stage 11 removes. A full rerun passed 17/17. `npm run browser:cleanup` clean. Deviations: one epoch per connection event in `observe`; a re-subscription snapshot also restarts a running resync; Stage 6 must `observe` before `on-fills-snapshot`; `:streams-elsewhere` needs another user's subscription; `view-blocker` ships here (live-fills plan's Decision Log).

## Validation and Acceptance

The build is complete when:

- All 25 stages are committed in this order, each with `npm run gates` PASS recorded in its item plan.
- After stage 25, from the worktree root: `npm run gates` PASS; `npx playwright test tools/playwright/test/vol-range.spec.mjs tools/playwright/test/vol-range-execution.spec.mjs tools/playwright/test/vol-range-repair-replace.spec.mjs tools/playwright/test/vol-range-live.spec.mjs tools/playwright/test/vol-range-report.spec.mjs` passes with zero requests to a real exchange; `npm run browser:cleanup` leaves no sessions; `npm run lint:namespace-sizes` reports no new exception.
- The three item plans are in `/hyperopen/docs/exec-plans/completed/` and this plan follows them.
- The report to the user lists what to test locally (repair, replace, live updates, the Vol Range Portfolio tab) and the open product and protocol questions.

## Idempotence and Recovery

Stages are additive. If a stage fails its gates, fix it within the stage; if it must be abandoned, revert its uncommitted files with `git checkout -- <files>` or keep them in a temporary WIP commit, never a bare stash. A later stage that finds an earlier contract insufficient records the change in both item plans' Decision Logs and in this plan before editing the shared file. Never reorder stages without recording the reason here.
