# Close the Vault-to-Trade `/info` 429 Regression

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The primary live `bd` issue for this follow-up is `hyperopen-lgva`, and this plan records the remaining trade -> vaults -> vault detail -> trade rate-limit investigation.

## Purpose / Big Picture

Users can currently navigate to the vault list, open the `Only Shorts` vault, then return to `/trade` and trigger a burst of Hyperliquid `429 Too Many Requests` responses from `https://api.hyperliquid.xyz/info`. The goal of this work is to make that navigation path safe again on the real localhost development runtime, not just in unit tests. After this change, the exact repro path should no longer produce stale vault request retries after the app has returned to `/trade`, and the investigation log should explain which theories were tested, which were discarded, and why the final patch resolved the issue.

## Progress

- [x] (2026-03-26 22:27Z) Created an active ExecPlan to serve as the investigation and research log for the remaining vault/trade `429` regression.
- [x] (2026-03-26 22:27Z) Recorded the already-landed guardrails from the previous iteration: trade-side `frontendOpenOrders` and `userFills` dedupe/TTL, vault detail lazy-loading for history tabs, route-scoped vault request guards, and candle snapshot dedupe/TTL.
- [x] (2026-03-26 22:27Z) Identified the highest-confidence remaining theory from code inspection: route-scoped vault requests were still allowed to retry because `/hyperopen/src/hyperopen/api/info_client.cljs` stripped `:active?-fn` before `request-attempt!` saw it.
- [x] (2026-03-26 22:27Z) Patched `/hyperopen/src/hyperopen/api/info_client.cljs` so `:active?-fn` survives request-flow normalization and is honored by the retry loop.
- [x] (2026-03-26 22:27Z) Added unit coverage in `/hyperopen/test/hyperopen/api/info_client_test.cljs` for inactive-request skipping and retry shutdown, plus supporting coverage updates in the market, vault effects, and runtime candle tests.
- [x] (2026-03-26 22:27Z) Added a browser regression in `/hyperopen/tools/playwright/test/trade-regressions.spec.mjs` that forces vault-detail `429`s, navigates back to `/trade`, and asserts the vault-scoped `/info` calls do not retry after the route change.
- [x] (2026-03-26 23:24Z) Replayed the exact localhost repro path against the dev runtime at `http://127.0.0.1:8081` with live request capture. The measured run returned to `/trade` without any post-return `/info` `429`s or repeated `/info` traffic in the four-second observation window.
- [x] (2026-03-26 23:46Z) Identified the remaining route-sensitive candidate: vault-detail benchmark `candleSnapshot` requests still lacked live vault-route activity and could keep retrying after navigation if they were already queued or cooling down.
- [x] (2026-03-27 00:12Z) Patched vault-detail benchmark `candleSnapshot` requests with a live vault-route guard and added adapter/schema coverage so those requests stop once the user leaves the vault detail route.
- [x] (2026-03-27 00:31Z) Replayed the localhost flow repeatedly and captured the remaining live `429`s after returning to `/trade`. The failing request type is now `metaAndAssetCtxs`, not vault detail metadata or vault benchmark candles.
- [x] (2026-03-27 00:41Z) Unified the nil-dex `metaAndAssetCtxs` selector request with the existing `:asset-contexts` single-flight, which removed the duplicate default `metaAndAssetCtxs` request on a single localhost trade-return pass.
- [x] (2026-03-27 01:03Z) Confirmed the app header links are hard navigations, not SPA transitions. Returning to `/trade` fully reloads the app and reruns startup work, which explained why the deferred full asset-selector warmup kept reappearing on later cycles.
- [x] (2026-03-27 01:15Z) Patched startup deferred bootstrap so cache-hydrated selector markets skip the expensive full warmup after a hard navigation back to `/trade`; the selector still refreshes on demand when opened.
- [x] (2026-03-27 01:28Z) Replayed the exact localhost repro after the startup-cache guard. Post-return `/trade` traffic dropped from 12 `/info` requests to 4 (`candleSnapshot`, `spotMeta`, default `metaAndAssetCtxs`, `webData2`) with zero `429`s.
- [x] (2026-03-27 01:31Z) Stress-ran three hard-navigation cycles of `/trade -> /vaults -> page size 25 -> Only Shorts -> /trade` in one browser session. All three trade returns stayed at four `/info` requests and zero `429`s.

## Surprises & Discoveries

- Observation: the route guard added in vault effects was not enough by itself because the request-normalization layer removed `:active?-fn` before the request reached the retry loop.
  Evidence: `/hyperopen/src/hyperopen/api/info_client.cljs` previously called `dissoc` on `:active?-fn` inside `request-flow-opts`, and the first round of new info-client tests showed inactive requests still reaching fetch and retrying.

- Observation: once `:active?-fn` stayed attached to request opts, the request-layer unit tests behaved the way the earlier code review assumed they already did.
  Evidence: after the `request-flow-opts` patch, `npm test` passed with the new `request-info-skips-inactive-requests-before-fetch-test` and `request-info-stops-retrying-once-request-becomes-inactive-test`.

- Observation: the remaining user report is now narrower than the first report.
  Evidence: the user no longer sees `429`s on the vault detail page itself. The current failure appears only after leaving the vault flow and returning to `/trade`, which is consistent with stale vault retries colliding with trade bootstrap work.

- Observation: the first live localhost replay after the request-layer fix came back clean even though the user had reproduced the bug manually moments earlier.
  Evidence: an instrumented Chromium run against `http://127.0.0.1:8081` reported `baselineInfoCount: 13`, `totalInfoAfterTradeReturn: 0`, and `total429AfterTradeReturn: 0` for the exact `/trade -> /vaults -> page size 25 -> Only Shorts -> /trade` path.

- Observation: vault-detail benchmark candle requests still had a plausible stale-retry path that the current regression did not cover.
  Evidence: `/hyperopen/src/hyperopen/runtime/app_effects.cljs` already honors `:active?-fn` for candle snapshots, but `/hyperopen/src/hyperopen/vaults/application/detail_commands.cljs` emitted those effects without route metadata and `/hyperopen/src/hyperopen/runtime/effect_adapters/websocket.cljs` did not derive a vault-detail route guard for them.

- Observation: after the vault-detail candle guard landed, the remaining live post-return `429`s shifted entirely onto the trade route and all observed failures were `metaAndAssetCtxs`.
  Evidence: repeated localhost browser runs against `http://127.0.0.1:8081/trade -> /vaults -> page size 25 -> Only Shorts -> /trade` logged `429` responses with request body type `metaAndAssetCtxs` while the browser location was already `/trade`.

- Observation: a successful single-pass trace still showed two default `metaAndAssetCtxs` requests on `/trade` after returning from the vault flow.
  Evidence: the captured `/info` sequence included two entries with `type: "metaAndAssetCtxs"` and no `dex`, alongside the expected named-dex `metaAndAssetCtxs` calls (`flx`, `xyz`, `vntl`, `abcd`, `km`, `hyna`, `cash`).

- Observation: the duplicate default request lines up with two different bootstrap entry points using different dedupe identities for the same Hyperliquid payload.
  Evidence: `/hyperopen/src/hyperopen/startup/runtime.cljs` still runs `fetch-asset-contexts!` during critical bootstrap, while `/hyperopen/src/hyperopen/api/market_loader.cljs` later runs full-phase selector hydration through `request-meta-and-asset-ctxs!`. Before the current patch, the endpoint defaulted nil-dex selector calls to `:meta-and-asset-ctxs-default` instead of the existing `:asset-contexts` key.

- Observation: the app header navigation is doing full document loads, so returning to `/trade` reruns startup instead of reusing the existing runtime.
  Evidence: a localhost browser probe that wrote `globalThis.__navProbe` before clicking `Vaults` and `Trade` observed that the probe disappeared after each click, while `performance.getEntriesByType('navigation')` still reported `type: "navigate"` after both transitions.

- Observation: the repeated-cycle `429`s were caused by startup's deferred full asset-selector warmup re-running after those hard navigations, not by another action-dispatched trade effect.
  Evidence: `HYPEROPEN_DEBUG.qaSnapshot()` after a trade return showed only startup route loader traces like `load-leaderboard-route`, `load-vault-route`, and `subscribe-to-asset`, with no `fetch-asset-selector-markets` action trace. The large request fan-out still appeared on the wire, which matches `/hyperopen/src/hyperopen/startup/runtime.cljs` calling `fetch-asset-selector-markets!` directly inside `run-deferred-bootstrap!`.

- Observation: skipping deferred full bootstrap when selector cache is already hydrated is enough to remove the remaining localhost repro.
  Evidence: after the startup patch, the exact localhost trade-return flow emitted only four `/info` calls (`candleSnapshot`, `spotMeta`, default `metaAndAssetCtxs`, `webData2`) and the three-cycle hard-navigation stress run stayed at zero `429`s.

## Decision Log

- Decision: keep this investigation theory-driven and record each theory before patching.
  Rationale: the previous passes already removed two earlier hot spots, so the remaining repro needs an explicit log to prevent circling back over already-fixed causes.
  Date/Author: 2026-03-26 / Codex

- Decision: add browser coverage that forces the exact failure class instead of relying only on unit tests.
  Rationale: this regression is route- and timing-sensitive. A deterministic browser test that injects `429`s for vault detail `/info` calls is a stronger lock than unit tests alone.
  Date/Author: 2026-03-26 / Codex

- Decision: prioritize real localhost repro after the request-layer fix instead of assuming the passing browser regression fully closes the user report.
  Rationale: the user explicitly asked for replay of the actual manual flow with browser/devtools, and the localhost runtime can still reveal request sources that the static Playwright harness does not exercise.
  Date/Author: 2026-03-26 / Codex

- Decision: patch the remaining vault-detail benchmark `candleSnapshot` path even though one localhost replay came back clean.
  Rationale: an intermittent repro disappearing on a single run is still consistent with stale retry pressure. This candle path is the remaining route-sensitive `/info` caller that can outlive the vault detail screen unless it carries a live activity guard.
  Date/Author: 2026-03-26 / Codex

- Decision: treat the remaining issue as a trade-bootstrap single-flight mismatch instead of a vault request leak unless new browser evidence contradicts it.
  Rationale: the latest live traces show the only remaining `429`s are `metaAndAssetCtxs` requests firing on `/trade` itself, and the duplicate nil-dex calls map directly onto the critical-bootstrap plus deferred-full-bootstrap split.
  Date/Author: 2026-03-27 / Codex

- Decision: keep the startup critical bootstrap intact but suppress deferred full selector warmup when cached selector markets were already restored.
  Rationale: the trade page still needs the narrow default market metadata path on load, but the named-dex selector fan-out is optional warmup. With hard navigations between top-level routes, rerunning that warmup on every return to `/trade` was the remaining rate-limit trigger.
  Date/Author: 2026-03-27 / Codex

## Outcomes & Retrospective

This work is now complete. The final fix came from combining two findings: first, nil-dex selector requests needed to share the existing `:asset-contexts` single-flight; second, returning to `/trade` was a full page navigation, so startup kept rerunning the deferred full asset-selector warmup after cache restore. Once startup skipped that warmup for cache-hydrated selector markets, the localhost repro stopped rate-limiting and the repeated hard-navigation stress run stayed clean.

## Context and Orientation

`/hyperopen/src/hyperopen/api/info_client.cljs` is the shared Hyperliquid `/info` client. It owns dedupe, caching, queueing, cooldowns after `429`s, and the retry loop. If a request should stop when the user leaves a route, the retry loop in this file must see that route activity signal.

`/hyperopen/src/hyperopen/vaults/effects.cljs` is where vault list and vault detail API calls are launched. Recent work added route-scoped `:active?-fn` guards here so a request can decide whether it still belongs to the active route.

`/hyperopen/src/hyperopen/api/endpoints/market.cljs` and `/hyperopen/src/hyperopen/runtime/app_effects.cljs` now give candle snapshots the same short-lived dedupe/caching treatment because vault-detail benchmark loading also uses `/info`.

`/hyperopen/tools/playwright/test/trade-regressions.spec.mjs` is the committed browser regression surface for trade-facing navigation behavior. The new test in that file injects `429`s for `vaultDetails` and vault `webData2` responses, navigates from `/trade` to `/vaults/<address>` and back, and asserts those vault requests do not retry after leaving the route.

The manual repro the user wants exercised is:

1. Start on `/trade`.
2. Navigate to `/vaults`.
3. Change the page-size dropdown to `25`.
4. Open `Only Shorts` at `/vaults/0x61b1cf5c2d7c4bf6d5db14f36651b2242e7cba0a`.
5. Navigate back to `/trade`.
6. Inspect the browser console and network activity for `429` responses from `https://api.hyperliquid.xyz/info`.

## Plan of Work

First, replay the real localhost path with instrumentation instead of relying only on the static Playwright server. Start the dev app, drive the exact navigation path in a browser-capable harness, and capture the request bodies and response statuses for `/info`. Record which request types, request sources, and route contexts are still present when the app has already returned to `/trade`.

Second, compare the live repro evidence against the theories already recorded here. If the browser evidence matches the stale vault retry theory and the repro is gone, close the investigation with the exact proof. If the browser evidence shows a different request source, add that source to `Surprises & Discoveries` and `Decision Log` before patching.

Third, keep the loop tight: patch one hypothesis at a time, rerun the exact browser repro, and update this document with the result. If a theory fails, mark it explicitly as rejected so later work does not silently retry the same approach.

## Concrete Steps

From `/Users/barry/.codex/worktrees/005c/hyperopen`:

1. Start or reuse a local dev runtime that serves the app with the current worktree.

       npm run dev:browser-inspection

   Expect the app and Tailwind watchers to start and a local HTTP server to be available, usually on `http://localhost:8081`.

2. Replay the exact user flow in a browser-capable harness with request capture.

       <use Playwright or browser-inspection tooling against the running localhost app>

   Capture which `/info` requests return `429`, including the request body `type`, the route the app is on when the request fires, and whether the request belongs to the vault address `0x61b1cf5c2d7c4bf6d5db14f36651b2242e7cba0a`.

3. If the live repro still fails, patch one theory, rerun the same repro, and update this file before moving on to the next theory.

4. Run the repository gates after the final patch.

       npm test
       npm run test:websocket
       npm run check

5. Run the narrow browser regression or broaden it if the live repro exposes a missing case.

       npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "vault detail 429 retries stop after returning to trade"

## Validation and Acceptance

Acceptance requires both synthetic and real-browser proof.

The committed Playwright regression must pass. The real localhost repro must also stop showing repeated `429` responses when the user follows trade -> vaults -> `Only Shorts` -> trade. If any `429`s still appear after the final patch, this plan stays active and the next theory must be recorded before more code changes land.

The required validation commands remain:

    npm test
    npm run test:websocket
    npm run check

## Idempotence and Recovery

The investigation steps are safe to repeat. Browser repro runs may leave dev servers running; if a port is already occupied, reuse the existing server or stop it cleanly before retrying. If a theory is disproved, keep the log entry and revert only the incorrect patch rather than deleting the evidence that the theory failed.

## Artifacts and Notes

Current key evidence:

    npm test
    Ran 2829 tests containing 15133 assertions.
    0 failures, 0 errors.

    npm run test:websocket
    Ran 421 tests containing 2397 assertions.
    0 failures, 0 errors.

    npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "vault detail 429 retries stop after returning to trade"
    1 passed

Plan revision note: created this ExecPlan on 2026-03-26 to track the remaining real-browser localhost repro after the request-layer `:active?-fn` propagation fix and browser regression were added.
