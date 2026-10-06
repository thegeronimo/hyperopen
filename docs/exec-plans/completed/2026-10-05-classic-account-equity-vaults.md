# Add vault equity to the classic Account Equity total

This ExecPlan is a living document. Keep its `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` sections current as work proceeds. Maintain it under [`.agents/PLANS.md`](../../../.agents/PLANS.md).

> **Historical decision superseded (2026-10-05):** This completed plan's three-part-total and HyperEVM-exclusion statements record the accepted scope at the time. The user subsequently defined classic Total Account Value to include HyperEVM; see the active [HyperEVM total follow-up](../active/2026-10-05-account-equity-hyperevm-total.md). That follow-up retains HyperEVM's **Not margin** label and leaves all collateral/risk semantics unchanged.

## Purpose / Big Picture

The classic Account Equity panel currently shows a total that reconciles Spot and Perps, but it leaves a depositor's vault equity out of both the total and the visible breakdown. After this change, a classic-account user can read one prominent **Total Account Value** immediately below the funding actions, then see the three values that compose it: Spot, Perps, and Vaults. A confirmed empty vault position renders `$0.00`; an unresolved, missing, malformed, or stale value renders `--` and causes the total to render `--`, so the panel never quietly undercounts the account.

The panel's vault number must be refreshed for the effective account, including a newly selected wallet or a spectated account. The retired `webData2` provider stream no longer refreshes `totalVaultEquity`, so reuse the existing `userVaultEquities` API path and derive directly from its account-owned, current-response rows. While the document is visible and the `/trade` surface has the same active effective account, a service-owned 60-second refresh keeps the displayed vault NAV current between account events; it pauses while hidden, away from `/trade`, or accountless and cleans itself up on service reinstall or shutdown. This is not performance work and introduces no new websocket stream, cache, or aggregation algorithm.

## Context References

Public refs:

- Direct user request, 2026-10-05: "One thing missing on the account equity section is the vaults value. Come up with a design that includes vaults as a line item and as part of total account value," followed by approval to implement the **Grouped total** design.

Repo artifacts:

- `docs/exec-plans/completed/2026-08-21-classic-account-equity-hl-parity.md` established the classic invariant `Account Value = Spot + Perps` and the unified-account protections this work must retain.
- `docs/exec-plans/completed/2026-10-04-vault-deposit-position-band.md` documents the normalized vault-equity semantics used elsewhere in the application.
- `src/hyperopen/views/portfolio/vm/equity.cljs` is a separate consumer of the retired `[:webdata2 :totalVaultEquity]` compatibility value. It is out of scope; the Account Equity panel must not use that stale fallback.

Local scratch refs (non-authoritative):

- None.

## Scope

This plan changes the classic Account Equity panel only. It adds a current-account vault-equity projection; classic-only bootstrap, post-vault-event, and bounded visible-account refreshes; the reduced `/trade` account-equity state dependencies; a classic-only `:vault-equity` metric; and a classic-only `:total-account-value-display` metric. It changes the visible classic label from `Account Value` to `Total Account Value` and adds the Vaults breakdown row.

The existing public `:account-value-display` metric remains unchanged: for a classic account it stays Spot plus Perps, and for a unified account it remains the existing Portfolio Value calculation. All unified-account rows, ratios, maintenance-margin, collateral, isolated-position disclosure, and leverage calculations remain unchanged. The existing HyperEVM line stays visible where it is today and is excluded from both the Vaults row and Total Account Value because HyperEVM funds cannot collateralize HyperCore positions. Vault detail pages, Portfolio-page presentation, transfer mechanics, and vault performance calculations are outside this change.

## Progress

- [x] (2026-10-05) Captured the approved Grouped total design, current panel structure, metric compatibility requirements, and current vault-data investigation in this active ExecPlan.
- [x] (2026-10-05) Established the pre-change full-test baseline: under network-permitted execution, `node out/test.js` passed 6,930 tests and 39,087 assertions with 0 failures and 0 errors. The sandbox-only run's `fetch failed` was DNS isolation, not a code failure.
- [x] (2026-10-05) Ran the expanded RED suite under network-permitted `npm test`: 6,956 tests and 39,182 assertions reached five intended failures. They establish that bootstrap/post-event vault-equity fetches, poller wiring, and the new account-equity contract did not exist yet.
- [x] (2026-10-05) Added the classic-account-scoped `userVaultEquities` bridge, address marker and stale-response protection; the final integration is the startup collaborators/runtime, account-surface service, vault effect adapter, and interval-only poller recorded below.
- [x] (2026-10-05) Added the two classic-only vault metrics, minimal `/trade` state projection, and grouped Total Account Value / Spot / Perps / Vaults presentation while preserving the unified panel and existing `:account-value-display` contract.
- [x] (2026-10-05) Materialized and passed the initial unit coverage. The first deterministic compile/runner check passed 2,349 files / 37 compiled (two pre-existing warnings), then 6,959 tests / 39,195 assertions. After the first force-refresh and reversed-completion regression tests were added, network-permitted `npm test` passed 6,961 tests / 39,207 assertions with 0 failures and 0 errors. This precedes the outstanding real cache integration case. Evidence: `tmp/multi-agent/account-equity-vaults/final-unit-runtime.log`, `tmp/multi-agent/account-equity-vaults/final-npm-test.log`.
- [x] (2026-10-05) Materialized the real cache-generation RED. `npm run lint:delimiters && npx shadow-cljs --force-spawn compile test && node out/test.js` reached 6,967 tests / 39,230 assertions, 12 intended failures, and 0 errors. It covers the force/normal cache sequence plus current endpoint/startup preflight behavior. Evidence: `tmp/multi-agent/account-equity-vaults/cache-generation-red-runtime.log`.
- [x] (2026-10-05) Made the real request-cache integration regression GREEN. Source-inclusive `npm test` passed 6,967 tests / 39,230 assertions with 0 failures and 0 errors. Evidence: `tmp/multi-agent/account-equity-vaults/final-npm-test-cache-spacing.log`.
- [x] (2026-10-05) Corrected the deterministic 1280-pixel footer-clearance RED with two compact classic-only spacing changes in `account_equity/panels.cljs`: outer gap `4 → 2` and grouped breakdown gap `2 → 1`; retained `p-3` and left unified/global trade-shell geometry unchanged. Fresh browser evidence remains required.
- [x] (2026-10-05) Passed the focused deterministic Playwright regression: `PLAYWRIGHT_REUSE_EXISTING_SERVER=true npx playwright test tools/playwright/test/account-equity-classic-named-dex.spec.mjs --workers=1` — 1 passed in 19.1 seconds. It covers the scoped `/info` fixture, nonzero/zero/unknown values at all four widths, mobile containment, and at least 8-pixel Vaults-to-footer clearance at 1280 and 1440.
- [x] (2026-10-05) Completed governed browser QA. All six passes succeeded at 375, 768, 1280, and 1440; desktop evidence confirms seven-tab geometry invariance and 11px footer clearance. A live vault refresh occurred after 58.7 seconds; strict 60,000-ms cadence is covered by deterministic unit tests rather than this timing sample. The managed browser watcher confirmed cleanup.
- [x] (2026-10-05) Ran the consolidated repository gates: 33 of 34 checks passed. The remaining namespace-size lint found six size-limit failures, recorded below; behavior-preserving namespace extraction is in progress before a fresh gate run.
- [x] (2026-10-05) Split the two test-only size offenders without changing their coverage boundaries: new `test/hyperopen/vaults/equity_effects_test.cljs` holds five scoped fetch/transfer race/force cases, reducing `vaults/effects_test.cljs` to 1,067 lines (limit 1,105); new `test/hyperopen/views/trade_view/account_equity_slice_test.cljs` holds the reduced-state regression, reducing `render_cache_test.cljs` to 498 lines (limit 500). Regenerated runner has 976 namespaces; delimiter and diff checks pass.
- [x] (2026-10-05) Applied the behavior-preserving source extraction: new `api/projections/vaults/user_equities.cljs`, `startup/collaborators/api_ops.cljs`, `startup/collaborators/vault_equities.cljs`, `startup/runtime/account_state.cljs`, and `vaults/effects/user_equities.cljs` reduce the original namespaces to projections 432, collaborators 483, runtime 515, and effects 673 lines. Removed the stale collaborators allowance from `dev/namespace_size_exceptions.edn`; existing runtime/effects caps remain unchanged. Subsequent static checks and final test/gate validation pass, as recorded below.
- [x] (2026-10-05) Froze the source extraction after `git diff --check`, delimiter, namespace-size, and boundary checks passed. New namespace line counts are projections user-equities 78, collaborators api-ops 24, collaborators vault-equities 62, runtime account-state 65, and effects user-equities 43; the retained facades remain 432, 483, 515, and 673 lines. Final `npm test`, focused browser, and gates remain pending.
- [x] (2026-10-05) Passed post-split `npm test`: 6,967 tests / 39,230 assertions, 0 failures, 0 errors. `npm run lint:namespace-sizes`, `npm run lint:delimiters`, and `git diff --check` also pass. Evidence: `tmp/multi-agent/account-equity-vaults/final-npm-test-namespace-splits.log`.
- [x] (2026-10-05) Resolved all six namespace-size findings through coherent source/test extractions and passed the final full gate matrix: `npm run gates` exited 0 with 34 of 34 checks passing. It reports 7,757 tests / 42,749 assertions, 123 Node tests in 1m37s; `npm test` is 6,967 / 39,230 and websocket coverage is 593 / 3,300. All app, portfolio, and workers compiles pass. Evidence: `tmp/multi-agent/account-equity-vaults/final-gates-namespace-splits.log`.
- [x] (2026-10-05) Final static review accepted the contract with no findings. At the time of its assessment, its 99.0% confidence exceeded its 84.7% threshold: testing 40/40 draws on the aggregate gates, unit, websocket, and browser QA evidence; review is 30/30; logical is 29/30, reserving one point only for the then-pending post-extraction focused browser rerun. This is reviewer assessment, not a guarantee.
- [x] (2026-10-05) Passed the post-extraction focused Playwright rerun: `PLAYWRIGHT_REUSE_EXISTING_SERVER=true npx playwright test tools/playwright/test/account-equity-classic-named-dex.spec.mjs --workers=1` — 1 passed in 19.0 seconds. The native 1440 crop renders Total `$237,289.14`, Spot `$224,789.13`, Perps `$0.01`, and Vaults `$12,500.00`. Browser session `sess-1791234910388-d9bef4` and the managed watcher stopped; session inventory is empty.

## Surprises & Discoveries

- Observation: `[:webdata2 :totalVaultEquity]` is an established Portfolio-facing compatibility field, but the currently subscribed account streams refresh clearinghouse state rather than the retired `webData2` payload.
  Evidence: `src/hyperopen/account/surface_service.cljs` subscribes/fetches `clearinghouseState`; `src/hyperopen/websocket/user_runtime/subscriptions.cljs` states that `webData2` no longer exists provider-side; `src/hyperopen/views/portfolio/vm/equity.cljs` reads `[:webdata2 :totalVaultEquity]`.

- Observation: a working vault-equity request and projection already exist, but they currently run from vault and portfolio route loading rather than as part of the `/trade` account surface.
  Evidence: `src/hyperopen/vaults/application/route_loading.cljs` emits `:effects/api-fetch-user-vault-equities`; `src/hyperopen/vaults/effects.cljs`, `src/hyperopen/runtime/effect_adapters/vaults.cljs`, and `src/hyperopen/api/projections/vaults.cljs` implement the request and state writes.

- Observation: the classic panel presently uses `:account-value-display` as the visible label and its memoization cache does not include vault state.
  Evidence: `src/hyperopen/views/account_equity/metrics.cljs` memoizes `:webdata2`, `:spot`, `:account`, `:perp-dex-clearinghouse`, and markets, while `src/hyperopen/api/projections/vaults.cljs` writes the separate `:vaults` state bucket.

- Observation: the full test suite requires network access for an existing optimizer-pipeline test; the sandbox denies DNS but the unmodified suite passes when run with approved network access.
  Evidence: on 2026-10-05, a sandbox `npm test` compiled 971 namespaces and then hit `TypeError: fetch failed`. The same generated runner, `node out/test.js`, passed under network-permitted execution with 6,930 tests, 39,087 assertions, 0 failures, and 0 errors. The captured baseline is `tmp/multi-agent/baseline-test-network.log`.

- Observation: a high-priority post-transfer fetch can still reuse the existing five-second request cache and return a pre-transfer vault NAV; an address guard alone also cannot order two pending requests for the same address.
  Evidence: final source review of the request cache and transfer refresh path; focused Playwright passed its four viewport scenarios before this transport correction, so fresh browser QA follows the force-refresh/token change.

- Observation: the first force-refresh branch bypasses the stale transport entry, but preserves it; a subsequent normal request receives a new current token and can apply that old cached response over the forced fresh value.
  Evidence: final P1 cache-path review. The planned real cache integration regression must exercise this exact sequence before the feature is accepted.

- Observation: the first grouped-total browser regression at 1280 pixels did not clear the trade footer by the required 8 pixels: panel bottom was 878 while the maximum allowed bottom was 849, a 29-pixel overrun.
  Evidence: deterministic footer-clearance RED in `tools/playwright/test/account-equity-classic-named-dex.spec.mjs`. The source fix reduces only classic Account Equity's outer and grouped vertical gaps by a combined 32 pixels; new browser evidence must prove the clearance at every target viewport.

- Observation: the final consolidated gate matrix passed 33 of 34 checks, but namespace-size lint failed six files after the feature additions: `api/projections/vaults.cljs` (506 > 500), `startup/collaborators.cljs` (550 > 503), `startup/runtime.cljs` (578 > 572), `vaults/effects.cljs` (708 > 674), `views/trade_view/render_cache_test.cljs` (549 > 500), and `vaults/effects_test.cljs` (1,259 > 1,105).
  Evidence: `tmp/multi-agent/account-equity-vaults/final-gates.log`. The worker and TDD writer are extracting coherent namespaces/tests rather than suppressing the lint or changing feature behavior.

- Observation: the test-side split resolves both test namespace-size failures while retaining feature-specific scope.
  Evidence: `test/hyperopen/vaults/equity_effects_test.cljs` received five vault fetch/transfer/race/force cases and reduced its source module to 1,067 lines; `test/hyperopen/views/trade_view/account_equity_slice_test.cljs` received the Account Equity reduced-state regression and reduced its source module to 498 lines. The generated test runner now has 976 namespaces; delimiters and diff checks passed.

- Observation: the source-side extraction resolves all four production namespace-size failures and removes a now-obsolete exception instead of raising limits.
  Evidence: new focused namespaces `api/projections/vaults/user_equities`, `startup/collaborators/api_ops`, `startup/collaborators/vault_equities`, `startup/runtime/account_state`, and `vaults/effects/user_equities`; retained facade namespaces are respectively 432, 483, 515, and 673 lines. `dev/namespace_size_exceptions.edn` no longer carries the collaborators exception. Static checks, the test runner, and full gates validate the extraction.

## Decision Log

- Decision: Preserve `:account-value-display` as the established Spot + Perps value and add `:total-account-value-display` for the new three-part total.
  Rationale: existing classic parity tests and consumers rely on the old key's two-part meaning. Reusing it for a new total would silently invalidate the useful invariant from the prior classic-account-equity work.
  Date/Author: 2026-10-05, planning.

- Decision: Treat a present numeric zero as a confirmed zero, while an absent, nil, malformed, non-finite, loading, failed, or response-for-another-account vault amount is unknown.
  Rationale: `$0.00` is factual only after an account-scoped successful response confirms it. Presenting a missing value as zero would falsely claim that the total is complete.
  Date/Author: 2026-10-05, planning.

- Decision: Feed Account Equity directly from account-owned `[:vaults :user-equities]` only when `[:vaults :user-equities-for-address]` equals the current normalized effective address. Do not fall back to `[:webdata2 :totalVaultEquity]`.
  Rationale: the old WebData2 field is not live. The existing `userVaultEquities` endpoint is the authoritative current NAV source; attaching an address marker stops a late response for account A being read as account B. This avoids duplicating a network client without treating stale compatibility data as truth.
  Date/Author: 2026-10-05, planning.

- Decision: Keep the change classic-only.
  Rationale: the approved layout is for the screenshot's classic Account Equity panel. Unified Account Summary has different, previously validated collateral and liquidation semantics, which must not be redefined by an owned-vault display feature.
  Date/Author: 2026-10-05, planning.

- Decision: Do not include HyperEVM in Total Account Value.
  Rationale: the existing panel labels HyperEVM as a separate non-marginable balance. Including it would make the total look like collateral and contradict the established line's meaning.
  Date/Author: 2026-10-05, planning.

- Decision: Add a re-install-safe, service-owned 60-second `userVaultEquities` refresh for a resolved classic account while the `/trade` surface is active, its effective account is present, and the document is visible.
  Rationale: vault NAV can change from vault PnL without a wallet change, transfer, or clearinghouse event. There is no live vault-equity stream and no pre-existing account timer suitable for this value. A bounded one-minute request is the smallest way to make the displayed total current without querying inactive/hidden pages. Captured-address and in-flight guards retain the truthfulness rules above. A known unified mode skips poller installation and new fetches; while the asynchronous account-mode abstraction is unresolved, bootstrap may perform one provisional classic-style read, and a later resolved-unified tick makes no request. No async-mode await is introduced.
  Date/Author: 2026-10-05, planning.

- Decision: Make a successful direct post-transfer vault-equity fetch bypass the five-second response cache and order every request by a unique current token.
  Rationale: the transfer is user-visible balance-changing work, so a cache-hit can leave the new Account Equity total stale until the one-minute poll. The token complements the effective-address guard: it prevents an older same-address success or failure from clobbering the newer request's rows, error, or loading state. The force branch must also leave the normal cache coherent, so a later normal request cannot reapply a pre-transfer cached response.
  Date/Author: 2026-10-05, final source review.

## Outcomes & Retrospective

The source implementation and deterministic unit contract are green. The classic panel derives an account-owned vault total and presents `Total Account Value = Spot + Perps + Vaults`; unknown initial data still renders unknown, and a same-account background request retains its prior confirmed amount only while that request is pending. A matching active request error renders Vaults and the total unknown; beginning a retry clears that active error and displays the prior confirmed amount during the retry's pending state. The final post-split `npm test` increased the full runner from the pre-change baseline of 6,930 tests / 39,087 assertions to 6,967 tests / 39,230 assertions, with 0 failures and 0 errors. The final focused named-dex Playwright rerun passed all four viewports in 19.0 seconds, including mobile containment and the footer clearance check; its native crop confirms Total `$237,289.14 = Spot $224,789.13 + Perps $0.01 + Vaults $12,500.00`. Final gates pass 34 of 34 checks (7,757 tests / 42,749 assertions, with all app/portfolio/workers compiles), and final static review found no accepted-contract issue at 99.0% confidence. The user-approved implementation is complete.

The implementation adds one bounded freshness service and two display metrics, increasing code surface to keep a financial statement current. It avoids a second API client, stream, cache, or risk-calculation path: the existing endpoint/projection flow remains the single data source, and the existing `:account-value-display` contract remains intact.

## Context and Orientation

Hyperopen is a ClojureScript single-page trading application. State is one map; views under `src/hyperopen/views/**` are pure functions that turn that map into Hiccup markup. A **classic account** has `[:account :mode]` other than `:unified`. A **vault** is an account that a user deposits into; **vault equity** is the current USD value of all of the effective user's vault positions, including marked gains or losses. It is an owned balance, not HyperCore trading collateral.

`src/hyperopen/views/account_equity/panels.cljs` builds the right-side account panel. Its `classic-account-equity-view` currently renders the title and funding actions, then Account Value, Spot, Perps, and a separate HyperEVM line before Perps Overview. `src/hyperopen/views/account_equity/metrics.cljs` creates the pure values for both classic and unified panels. `:account-value-display` is currently the classic Spot + Perps reconciliation value. `account-equity-metrics` must add the `:vaults` state-bucket identity to its memoization inputs so vault-response changes recompute the panel.

`src/hyperopen/views/trade_view.cljs` deliberately passes a reduced state map to the account-equity panel. The new derivation needs address-owned vault rows and effective-account identity, so this projection must forward the minimal `:vaults` and account-context inputs. Omitting either makes an otherwise-correct vault total unavailable on `/trade`; the focused Playwright RED exposed this integration seam.

### Changed implementation and test surfaces

The implementation changes these source files: `src/hyperopen/account/surface_service.cljs`, `src/hyperopen/api/endpoints/vaults/details.cljs`, `src/hyperopen/api/info_client/flow.cljs`, `src/hyperopen/api/projections/vaults.cljs`, new `src/hyperopen/api/projections/vaults/user_equities.cljs`, `src/hyperopen/runtime/effect_adapters/vaults.cljs`, `src/hyperopen/startup/collaborators.cljs`, new `src/hyperopen/startup/collaborators/api_ops.cljs` and `src/hyperopen/startup/collaborators/vault_equities.cljs`, `src/hyperopen/startup/runtime.cljs`, new `src/hyperopen/startup/runtime/account_state.cljs`, `src/hyperopen/state/app_defaults.cljs`, `src/hyperopen/vaults/effects.cljs`, new `src/hyperopen/vaults/effects/user_equities.cljs`, `src/hyperopen/views/account_equity/metrics.cljs`, `src/hyperopen/views/account_equity/panels.cljs`, `src/hyperopen/views/trade_view.cljs`, and the new `src/hyperopen/vaults/infrastructure/user_equity_poller.cljs`. The tracked lint-registry change is `dev/namespace_size_exceptions.edn`, which removes its stale collaborators exception rather than relaxing a limit.

The changed unit/integration test surfaces are `test/hyperopen/api/endpoints/vaults_helpers_test.cljs`, `test/hyperopen/api/endpoints/vaults_test.cljs`, `test/hyperopen/api/info_client_cache_test.cljs`, `test/hyperopen/runtime/effect_adapters/vaults_test.cljs`, `test/hyperopen/startup/account_equity_vaults_test.cljs`, `test/hyperopen/vaults/effects_test.cljs`, `test/hyperopen/vaults/equity_effects_test.cljs`, `test/hyperopen/vaults/infrastructure/user_equity_poller_test.cljs`, `test/hyperopen/views/account_equity/hyperevm_line_test.cljs`, `test/hyperopen/views/account_equity_vaults_test.cljs`, `test/hyperopen/views/account_equity_view_test.cljs`, `test/hyperopen/views/trade_view/account_equity_slice_test.cljs`, `test/hyperopen/views/trade_view/render_cache_test.cljs`, `test/hyperopen/websocket/endpoints_coverage_test.cljs`, and generated registration in `test/test_runner_generated.cljs`. The deterministic browser regressions are `tools/playwright/test/account-equity-classic-named-dex.spec.mjs` and `tools/playwright/test/account-equity-venue-parity.live.spec.mjs`.

`src/hyperopen/views/account_equity/format.cljs` supplies `display-currency` and `metric-row`. The former renders a non-number as `--`, which is the required unknown presentation. Reuse its currency formatting rather than creating a second money formatter. The total row may use a small local presentational wrapper or a backward-compatible optional style argument, but it must preserve existing regular-row output and project tokenized classes.

The server request `userVaultEquities` returns one row per vault for a requested user. Endpoint normalization is in `src/hyperopen/api/endpoints/vaults/details.cljs`; request execution and stale-safe application are in `src/hyperopen/vaults/effects.cljs`; state projections are in `src/hyperopen/api/projections/vaults.cljs`; its existing runtime adapter is in `src/hyperopen/runtime/effect_adapters/vaults.cljs`. The existing route loader in `src/hyperopen/vaults/application/route_loading.cljs` invokes this only for vault/Portfolio routes. Account Equity adds the separate `/trade` lifecycle bridge through `src/hyperopen/startup/collaborators.cljs`, `src/hyperopen/startup/runtime.cljs`, and the post-account-event seam in `src/hyperopen/account/surface_service.cljs`; `src/hyperopen/app/startup.cljs` supplies the collaborators.

`[:vaults :user-equities]` holds normalized rows from `userVaultEquities`. Add `[:vaults :user-equities-for-address]`, set only by a successful request, to identify which effective account those rows describe. In Account Equity, vault equity is known only when that marker equals the current normalized effective address and the response is a valid vector: an empty successful vector denotes confirmed zero; otherwise every row must contain a finite numeric or numeric-string `:equity-raw` before their USD values are summed. Endpoint normalization rejects the entire payload when even one member cannot be normalized, so a partial row list can never become a partial total. A missing, nil, malformed, or non-finite response is unknown. A pending background refresh for the same marked effective account retains its confirmed rows and total only while it is loading and has no active error. A matching current-request error makes Vaults and Total Account Value unknown even when prior confirmed rows remain in state. Starting a retry clears that active error and shows the prior confirmed value only for the new pending interval. An initial, unmarked account or a non-current response remains unknown. A stale response is one started for account A that completes after the effective account becomes B; it must leave B's rows, address marker, and panel unchanged. Each request additionally owns a unique token, so an older completion for the same address cannot overwrite a newer completion or clear the newer request's loading/error state. The retired `[:webdata2 :totalVaultEquity]` field is never a fallback for this feature.

`src/hyperopen/vaults/infrastructure/user_equity_poller.cljs` is the new lifecycle-owned refresh service. `src/hyperopen/startup/collaborators.cljs` owns its start/stop collaborators; `src/hyperopen/startup/runtime.cljs` starts it from `bootstrap-account-data!` and stops it from `clear-disconnected-account-state!`; `src/hyperopen/app/startup.cljs` passes those collaborators through `startup-base-deps`. `src/hyperopen/account/surface_service.cljs` uses the same guarded request path after account events. The poller owns one re-install-safe interval only: a new install clears the prior interval, and stop clears that interval. On each tick it reads the current route, account, and document visibility; it registers no visibility listener. It makes an immediate high-priority request when bootstrap reads a classic account or an as-yet-unresolved mode, then a low-priority request no more than once per 60 seconds only while all of these conditions hold: the route is `/trade`, the account is resolved classic, `account-context/effective-account-address` still matches the captured address, and `document.visibilityState` is `"visible"`. A known or newly resolved unified account does not install/persist the poller and makes no dynamic-tick request; no await is imposed on the asynchronous mode read. It must not request for another route, a hidden document, or an accountless/mismatched state, and must not begin a second request for the same address while one is in flight. Each request still uses the captured-address completion guard described above. This timer is a data-freshness boundary, not a replacement for WebSocket account-state handling; no global runtime wiring is needed.

## Plan of Work

### Milestone 1 — Make current vault equity available to the active account panel

Extend the existing `userVaultEquities` path so it is requested when a resolved classic effective account becomes available for the standard account surface, including wallet changes and entering or leaving spectate mode. Reuse the existing endpoint, effect adapter, and projection rather than implementing another network client. A known unified account skips the initial request and poller; before the asynchronous mode abstraction resolves, bootstrap may perform one provisional classic-style initial read, but no async-mode await is added. `src/hyperopen/startup/runtime.cljs/bootstrap-account-data!` must call `start-user-vault-equity-poller!` supplied by `src/hyperopen/startup/collaborators.cljs` after its account-state reset and `account-surface-service/bootstrap-account-surfaces!`; `clear-disconnected-account-state!` must call its matching stop helper. `src/hyperopen/app/startup.cljs/startup-base-deps` supplies those helpers, so no global runtime or route-module wiring changes. The start helper uses the normalized effective address and existing safe route override, making the request available on `/trade` rather than only on vault and Portfolio routes. It must make an immediate eligible high-priority fetch, repeat no sooner than every 60 seconds at low priority only while `/trade` is visible with the same resolved-classic effective account, deduplicate a same-address in-flight request, and remove its interval on service stop/reinstall. `src/hyperopen/account/surface_service.cljs` must use the same guarded request after account events, when the current effective account still matches. After a successful vault transfer, `src/hyperopen/runtime/effect_adapters/vaults.cljs` must directly request a refresh after its existing route-change dispatch, only for a classic account whose submitting address still equals the effective address. That transfer request must use `:force-refresh? true`: the ordinary five-second request cache can otherwise reuse a pre-transfer response until the next poll. In the existing `src/hyperopen/api/info_client/flow.cljs` cache, the vault-only internal force flag must evict the stale entry, increment its `:generation`, and install a fresh current flight; a normal follow-up joins that fresh flight or reads its successful result. Every cache write is guarded by its current generation, so an older normal flight cannot restore pre-transfer data. The forced success becomes the normal cache result for later callers. Preserve other force clients by keeping this behavior opt-in to this vault endpoint rather than creating a second cache.

Capture the requested normalized effective address and generate one unique request token at request start. Clear the row ownership marker when the effective account changes or an initial account fetch begins; retain an already-confirmed same-address marker during a background refresh. On resolution, apply rows and `:user-equities-for-address` only if both the captured address still equals `account-context/effective-account-address` in the current state and the token is still the latest for that address. The same token guard applies to error completion, so an older failure cannot clear a later request's loading state or record its error. A success with an empty, valid response marks the empty vector for that account; a non-empty success preserves each raw equity so the metric can verify it. Initial/malformed/error/non-finite responses leave Account Equity's aggregate unknown. Retain the prior confirmed same-address value only while a background request is pending with no active error; a matching current error returns the aggregate to unknown, and beginning its retry clears the active error so the retained value can show only while that retry is pending. Do not let an address change, a rejected promise, or a late response preserve the previous account's total. Preserve existing vault list/detail mechanics apart from the direct post-transfer refresh while the one bounded service-owned poller supplies Account Equity freshness.

Update the endpoint/projection contract only as much as needed to preserve unknown-versus-zero truth. A payload with an unnormalizable member must reject as a whole rather than filtering that member or treating it as zero. Update focused endpoint, projection, effect, lifecycle, adapter, and poller tests to show the exact request address, loading/reset behavior, confirmed empty response, malformed input, initial error, same-address retention only during a pending background request, matching active-error unknown display, retry clearing the active error then pending retention, account switch, late response, 60-second cadence, hidden/route/account-mode suppression, same-address in-flight deduplication, interval cleanup on stop/reinstall, and guarded post-transfer direct refresh.

At the end of this milestone, running the focused ClojureScript test set demonstrates that a classic trade panel's state receives an explicit vault total only from the current effective account and no old account value survives an address switch.

### Milestone 2 — Derive and present the grouped total without changing risk metrics

In `src/hyperopen/views/account_equity/metrics.cljs`, read the effective account and the address-marked `[:vaults :user-equities]` response. Derive `:vault-equity` only from a matching, complete successful response and expose nil in every other case. Define `:total-account-value-display` only when both `:account-value-display` and `:vault-equity` are numbers, as their sum. Keep `:account-value-display`, `:spot-equity`, `:perps-value`, every Perps Overview metric, and every unified metric unchanged. A raw numeric string must produce the same result as a numeric input; numeric zero is valid; nil, blank, malformed, or non-finite input must produce nil rather than zero.

Add the `:vaults` bucket identity to the memoization cache and confirm it reruns when the matching vault response changes. Do not add a second cache; `reset-account-equity-metrics-cache!` remains the test seam.

Update `src/hyperopen/views/trade_view.cljs`'s reduced Account Equity state projection to include `:vaults` and the effective-account identity that the metrics require. Do not pass the full application state merely to satisfy this feature.

In `src/hyperopen/views/account_equity/panels.cljs`, change only `classic-account-equity-view`. Directly below the funding actions, render a visually prominent `Total Account Value` row with the tooltip `Total value across Spot, Perps, and Vaults. HyperEVM balances are shown separately and are not included.` Then render a subtle `border-base-300` divider followed by matching `metric-row` rows in this order: Spot, Perps, Vaults. The Vaults label is plain user language and its formatted unknown state is `--`. Keep the existing HyperEVM line after the three rows and outside the total. Preserve `p-3`, but reduce only classic vertical spacing to keep the added rows clear of the trade footer: outer gap `4 → 2` and grouped breakdown gap `2 → 1`. Keep Perps Overview, the unified-panel function, and global trade-shell geometry byte-for-byte equivalent unless an unrelated formatter signature requires a compatible call-site adjustment.

At the end of this milestone, a classic fixture with Spot `$100.00`, Perps `$155,901.46`, and Vaults `$12,500.00` visibly shows `Total Account Value $168,501.46`; changing Vaults to `0` shows `$0.00` and total `$156,001.46`; removing Vaults shows `Vaults --` and `Total Account Value --` while Spot and Perps still render their known values.

### Milestone 3 — Lock the behavior with deterministic and browser coverage

Update `test/hyperopen/views/account_equity_view_test.cljs` and the existing metric/parity suites. Assert the classic label is `Total Account Value`, the grouped order is total then divider then Spot, Perps, Vaults, and unified mode still contains `Portfolio Value` without `Vaults` or `Total Account Value`. Assert a nonzero vault number, numeric string, confirmed zero, nil/missing/malformed value, cache reset/reactivity, and HyperEVM exclusion. Add a `trade_view` integration test proving its reduced state preserves vault rows and account identity for the `/trade` Account Equity panel. Preserve the old classic identity by asserting `:account-value-display = :spot-equity + :perps-value`; add the new conditional identity `:total-account-value-display = :account-value-display + :vault-equity` only when all operands are known.

Expand the existing request/projection tests for account ownership. A late response for account A after account B becomes effective must not write account A's rows or ownership marker into B's state. For repeated requests of the same address, reverse their completions and assert the older success and older error cannot overwrite the newest rows, loading state, or error state. An initial rejected request must leave the total unknown. A same-address background request retains its confirmed value only while pending; its matching active error makes Vaults and the total unknown, and a retry clears that error then retains the prior confirmed value only during the retry's pending state. A successful empty response must mark an empty vector for the current address, from which the metric derives zero. Add a real `info_client` cache integration test that starts an ordinary stale flight, forces a post-transfer vault request, then starts a normal follow-up: prove the forced network result becomes the normal cache result and neither older nor normal cached work can restore the stale response. Add poller tests with injected clock/document/effect dependencies: immediate high-priority resolved-classic `/trade` bootstrap fetch, a permitted single provisional initial read only while account mode is unresolved, exactly one low-priority further fetch at 60 seconds, and no dynamic request while known or newly resolved unified, hidden, accountless, away from `/trade`, or address-mismatched. Also prove no duplicate same-address request in flight, address change correctness, and interval cleanup on stop or reinstall. Add startup-runtime tests proving that bootstrap starts the collaborator after its reset/surface work, disconnect stops it, and `startup-base-deps` supplies both helpers. Add effect-adapter tests proving a successful transfer directly refreshes only its submitting, current, classic account and requests a cache-bypassing `:force-refresh? true` response. This coverage belongs beside the existing vault effect/projection tests rather than in a view-only test, because it proves data truth before presentation.

Update `tools/playwright/test/account-equity-classic-named-dex.spec.mjs` to seed a classic named-dex book and deterministic vault equity. Its nonzero case must assert the three breakdown rows and the exact three-part total, and it must preserve the named-dex risk figures. Add deterministic confirmed-zero and missing-vault cases; the former asserts `$0.00` and a two-part total, the latter asserts `--` for both Vaults and Total Account Value. At each target viewport, it must also assert the newly grouped account panel clears the trade footer rather than overlapping it. Adapt `tools/playwright/test/account-equity-venue-parity.live.spec.mjs` so its retained two-part venue parity assertion reads `Spot + Perps` from the unchanged metric/row semantics, while any total assertion includes the rendered Vaults row instead of claiming that the venue's classic `Account Value` includes it.

Run the smallest changed Playwright spec first. Then complete design-system browser QA for `/trade` at widths 375, 768, 1280, and 1440. Record PASS, FAIL, or BLOCKED for visual, native-control, styling-consistency, interaction, layout-regression, and jank/perf. At desktop widths, exercise the seven standard account tabs and capture bounding-box evidence that the trade shell's account-panel geometry remains stable. Stop any Browser MCP/browser-inspection sessions with `npm run browser:cleanup` before reporting completion.

### Final browser-QA matrix

Record the fresh post-transport-correction run here. The desktop cells must link their result to the seven-tab geometry evidence; every cell needs PASS, FAIL, or BLOCKED and a concise evidence reference.

| Required pass | 375 | 768 | 1280 | 1440 |
| --- | --- | --- | --- | --- |
| Visual | PASS | PASS | PASS — seven-tab geometry invariant | PASS — seven-tab geometry invariant |
| Native control | PASS | PASS | PASS — seven-tab geometry invariant | PASS — seven-tab geometry invariant |
| Styling consistency | PASS | PASS | PASS — seven-tab geometry invariant | PASS — seven-tab geometry invariant |
| Interaction | PASS | PASS | PASS — seven-tab geometry invariant | PASS — seven-tab geometry invariant |
| Layout regression | PASS | PASS | PASS — Vaults bottom 846, footer top 857, 11px clearance | PASS — Vaults bottom 846, footer top 857, 11px clearance |
| Jank/perf | PASS — capture/long-task sample | PASS — capture/long-task sample | PASS — capture/long-task sample, seven tabs | PASS — capture/long-task sample, seven tabs |

Direct governed QA artifact: `tmp/browser-inspection/account-equity-direct-qa-2026-10-05T20-49-40-480Z`. It recorded error-free console/network evidence, all six required passes at each viewport, desktop seven-tab geometry invariance, and 11px Vaults-to-footer clearance at both desktop widths. A live vault refresh occurred after 58.7 seconds; the deterministic unit contract, rather than this live timing sample, proves the strict 60,000-ms cadence. The managed browser watcher confirmed cleanup. The observed 768-pixel global header logo/Portfolio overlap is outside the changed Account Equity card; no before-baseline capture was taken, so this work does not classify it as pre-existing. Jank evidence is sampled captures and long tasks, not continuous live recording, and there is no exact contrast or loading-transition video evidence. The final native visual crop is `native-account-equity-card-1440.png`, showing Total `$237,289.14`, Spot `$224,789.13`, Perps `$0.01`, and Vaults `$12,500.00`.

Focused deterministic Playwright and browser-inspection cleanup are complete. The managed browser watcher confirmed no remaining inspection session.

## Concrete Steps

Run every command from `/Users/barry/.codex/worktrees/7c56/hyperopen`.

Bootstrap this worktree before any gate:

    npm run setup:worktree

During implementation, regenerate the ClojureScript runner after adding a test namespace and run its focused suite through the project's normal test command:

    npm run test:runner:generate
    npm test

Run the smallest deterministic browser regression after the application build is available. If a worktree server is needed, do not reuse the main checkout's port; build the app and CSS, serve this worktree on a free SPA-fallback port, then run:

    npm run css:build
    PLAYWRIGHT_BASE_URL=http://127.0.0.1:8090 PLAYWRIGHT_REUSE_EXISTING_SERVER=true \
      npx playwright test tools/playwright/test/account-equity-classic-named-dex.spec.mjs --workers=1

Run the opted-in live parity spec only when public API access is available; it is manual evidence, not CI proof:

    RUN_VENUE_PARITY=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:8090 PLAYWRIGHT_REUSE_EXISTING_SERVER=true \
      npx playwright test tools/playwright/test/account-equity-venue-parity.live.spec.mjs --workers=1

After code and tests pass locally, run the mandatory repository gates:

    npm run check
    npm test
    npm run test:websocket

The expected result for each required gate is exit code 0. Under the repository contract, `npm run gates` is the consolidated non-short-circuit entrypoint: it runs the check constituents and records the `npm test` and `npm run test:websocket` outcomes in one matrix. When that aggregate is used, record its constituent results and do not represent `npm run check` as a separately invoked command. Update `Progress` with the actual test and assertion counts and any environment-caused blocker.

The full test suite makes an existing optimizer-pipeline network request. In this sandbox, run `npm test` and subsequent network-dependent gates with approved network execution; a local DNS `ENOTFOUND`/`fetch failed` does not indicate an application regression. The pre-change network baseline is 6,930 tests, 39,087 assertions, 0 failures, and 0 errors; compare the post-change result to it and record any expected increase from the new tests.

The post-implementation deterministic run completed as:

    npx shadow-cljs --force-spawn compile test
    # PASS: 2,349 files, 37 compiled (two pre-existing warnings)
    node out/test.js
    # PASS: 6,959 tests, 39,195 assertions, 0 failures, 0 errors

The final post-transport-correction unit gate completed as:

    npm test
    # PASS: 6,961 tests, 39,207 assertions, 0 failures, 0 errors
    # log: tmp/multi-agent/account-equity-vaults/final-npm-test.log

The final source-inclusive unit gate completed as:

    npm test
    # PASS: 6,967 tests, 39,230 assertions, 0 failures, 0 errors
    # log: tmp/multi-agent/account-equity-vaults/final-npm-test-cache-spacing.log

The final post-namespace-split unit gate completed as:

    npm test
    # PASS: 6,967 tests, 39,230 assertions, 0 failures, 0 errors
    # log: tmp/multi-agent/account-equity-vaults/final-npm-test-namespace-splits.log

The final full repository gate completed as:

    npm run gates
    # PASS: 34/34 checks, exit 0
    # 7,757 tests / 42,749 assertions; 123 Node tests in 1m37s
    # npm test: 6,967 tests / 39,230 assertions; websocket: 593 tests / 3,300 assertions
    # app, portfolio, and workers compiles: PASS
    # log: tmp/multi-agent/account-equity-vaults/final-gates-namespace-splits.log

## Validation and Acceptance

The implementation is accepted only when all of the following are observable.

- A classic account with Spot `$100.00`, Perps `$155,901.46`, and a current-account vault response of `$12,500.00` renders `Total Account Value $168,501.46`, then Spot `$100.00`, Perps `$155,901.46`, and Vaults `$12,500.00` after the divider. The displayed total equals the three rows to the cent.
- The same account with a confirmed empty `userVaultEquities` response renders `Vaults $0.00` and a total equal to Spot plus Perps. A numeric string has the same visible result as its numeric equivalent.
- Before an account has a confirmed response, or when a response is missing, malformed, non-finite, fails, or belongs to a previous effective account, the classic panel renders `Vaults --` and `Total Account Value --`. A same-account background refresh retains its already-confirmed value only while it is pending with no active error. Its matching active error renders both values `--`; starting a retry clears that error and temporarily retains the last confirmed value only while the retry is pending. It never renders `$0.00` or a partial total for an unknown initial account.
- Switching from effective account A to B clears A's row ownership immediately; A's later response cannot alter B's panel. B's current response alone supplies B's total.
- With a visible `/trade` resolved-classic account surface and an effective account, an immediate high-priority `userVaultEquities` request runs at startup and no more than one low-priority additional request runs after 60 seconds. A known unified mode starts no new fetch and installs no poller; while mode is unresolved, one provisional initial read is permitted, but a later resolved-unified tick makes no request. Hiding the document, leaving `/trade`, changing/removing the effective account, or resolving unified prevents another request at the next tick. Stopping or reinstalling the account service leaves no active interval. A successful vault transfer refreshes only the submitting current classic account after the route-change dispatch, bypasses the five-second response cache with `:force-refresh? true`, replaces that entry's active generation with a fresh flight, and makes the fresh result available to a normal follow-up; neither an older flight nor a stale cache read can overwrite it.
- Updating the current account's projected vault value changes the derived vault row and total without manual reload; resetting the metrics cache in a unit test yields the same current values.
- `:account-value-display` retains its prior classic Spot + Perps result, and the original classic parity invariant continues to pass. All unified-account labels and risk/collateral calculations retain their previous values and do not render the new Vaults or Total Account Value rows.
- The HyperEVM line still renders separately and changing it does not change Total Account Value.
- The changed deterministic Playwright spec passes. Browser QA records an explicit outcome for all six required passes at 375, 768, 1280, and 1440; at 1280 and 1440 it includes the required `/trade` shell geometry/tab-switch evidence. Any Browser MCP/browser-inspection session is explicitly cleaned up.
- The final `npm run gates` aggregate exits successfully and reports its check constituents plus successful `npm test` and `npm run test:websocket` results; do not require a separately invoked `npm run check` when the aggregate supplied that coverage.

## Idempotence and Recovery

The request is read-only. Repeating the effective-account refresh replaces only the active account's marked vault rows after its address-and-token guard succeeds. An initial or switched-account request safely clears ownership and makes the total unknown. A same-account background request retains its confirmed total only while it is pending with no active error; its matching active failure returns the display to unknown, and beginning the retry clears that error so the confirmed value can show only during the retry's new pending interval. The poller owns just one interval and no listener; reinstalling it is safe because it clears the previous interval first. The direct post-transfer request intentionally bypasses the short request cache so the refresh observes the transfer: it replaces the existing info-client cache generation, and its response becomes the ordinary cache result. An older success/error or a normal follow-up cannot restore the stale generation. Do not manufacture a zero fallback.

All presentation edits are contained in the Account Equity view and metrics. Reverting the targeted source and test changes restores the prior two-row classic panel. Do not use a destructive worktree reset: inspect the file-scoped diff, then apply a compensating patch if recovery is needed.

## Artifacts and Notes

The intended classic-panel arithmetic is:

    account-value-display       = spot-equity + perps-value       ; existing public meaning
    vault-equity                = current effective account's complete, address-marked userVaultEquities response
    total-account-value-display = account-value-display + vault-equity

    Spot   $100.00
    Perps  $155,901.46
    Vaults $12,500.00
    -----------------
    Total Account Value $168,501.46

When any term needed for the total is unknown, the complete calculation is unknown:

    Spot   $100.00
    Perps  $155,901.46
    Vaults --
    -----------------
    Total Account Value --

This is deliberate: the first example is a complete account-value statement, while the second truthfully reports only the known trading portions.

## Interfaces and Dependencies

At completion, `hyperopen.views.account-equity.metrics/account-equity-metrics` retains all existing map keys and additionally returns:

    :vault-equity                ; finite USD number or nil
    :total-account-value-display  ; finite USD number or nil

`total-account-value-display` is only defined when its existing classic base total and `vault-equity` are both known. No new public unified metric is required.

The existing `userVaultEquities` endpoint continues to return normalized rows with a vault address, parsed equity, and raw equity. Its account-surface bridge must accept the effective address that initiated the request, store `[:vaults :user-equities-for-address]` only after a current-address success, and expose only an address-matched aggregate to Account Equity. `api/endpoints/vaults/details.cljs`, `api/info_client/flow.cljs`, `api/projections/vaults.cljs` and `api/projections/vaults/user_equities.cljs`, `startup/collaborators.cljs` plus `startup/collaborators/api_ops.cljs` and `startup/collaborators/vault_equities.cljs`, `account/surface_service.cljs`, `startup/runtime.cljs` and `startup/runtime/account_state.cljs`, `vaults/effects.cljs` and `vaults/effects/user_equities.cljs`, `runtime/effect_adapters/vaults.cljs`, `vaults/infrastructure/user_equity_poller.cljs`, `views/trade_view.cljs`, and the account-equity metrics/panel namespaces are the implementation boundary; existing vault-route adapter behavior must remain intact.

`hyperopen.vaults.infrastructure.user-equity-poller` must be constructed with injectable clock, document visibility, route/account-state, and fetch dependencies so its cadence and cleanup can be deterministic in unit tests. Its lifecycle API must support `start-user-vault-equity-poller!` re-install and matching stop semantics, with no more than one active interval. It checks eligibility on each tick and owns no event listener. Bootstrap fetches use high priority and timer fetches use low priority. The poller may request the existing API/effect path but must not calculate vault equity itself or create a second cache; projections and `account-equity-metrics` remain the sole owners of state application and display derivation. The shared request/projection seam accepts `:force-refresh? true` for the direct post-transfer request, holds the latest generated request id in state and clears it on reset, and permits both success and error projection only when that id and its captured effective address remain current. The existing info-client cache remains the sole transport cache: an internal vault-only force option evicts the stale entry, increments its `:generation`, and replaces its current flight; all writes are generation-guarded and the forced success becomes the normal cache value.

Plan update note (2026-10-05): created from the approved Grouped total design and expanded after discovery that the retired `webData2` stream cannot by itself keep vault equity current. The plan therefore includes active-account `userVaultEquities` refresh and stale-response protection rather than a display-only change. Updated after the live-source design decision: Account Equity reads only address-marked `:vaults :user-equities` and never falls back to stale `:webdata2 :totalVaultEquity`. Updated after baseline validation: the sandbox's `fetch failed` was DNS isolation; network-permitted baseline passed 6,930 tests and 39,087 assertions, so later gates must use the same network-approved environment. Updated after source-lifecycle tracing: a re-install-safe 60-second visible-`/trade` poller is necessary because vault NAV can change without any account event; the exact startup integration is `startup/runtime` bootstrap/disconnect, `startup/collaborators`, and a guarded post-account-event request in `account/surface_service`. Updated after expanded RED: 6,956 tests and 39,182 assertions reached five intended failures; source now uses interval-only tick checks, rejects every malformed endpoint payload as a whole, retains confirmed values only during a same-address pending request, supports a known-unified bootstrap skip while permitting one unresolved-mode initial read, and adds guarded direct post-transfer refresh. Updated after browser RED: `trade_view.cljs` must pass vault data and effective-account identity through its reduced state, with an integration test guarding the seam. Updated after review clarified the settled error contract: a matching active error must render Vaults and the total unknown; only a pending same-address refresh retains confirmed data, and beginning a retry clears the error for that pending interval. Updated after final GREEN: the info-client generation-cache regression, compact classic spacing, and source-inclusive suite passed `npm test` with 6,967 tests and 39,230 assertions, no failures/errors. The focused browser regression passed all target widths; direct QA also passed every required six-pass/viewport cell with desktop seven-tab and 11px footer-clearance evidence. The live poller refresh was observed after 58.7 seconds, while unit tests prove strict cadence; managed browser cleanup completed. Consolidated gates first exposed six namespace-size limits, then coherent source/test namespace splits brought all affected facades under their limits and removed the stale collaborator exception. Final static checks, the post-split `npm test`, final `npm run gates` (34/34), final focused Playwright (1 passed in 19.0s), and browser cleanup all pass. The plan is complete; its documented QA caveats are the unbaselined 768 header observation and sampled rather than continuous visual/performance evidence.
