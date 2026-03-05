# Spot Clearinghouse Fanout Hotfix for Ghost-Mode WS Cadence

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, repeated websocket-driven account refreshes during active ghost-mode sessions will no longer generate high-frequency network POST load for `spotClearinghouseState`. The user-visible outcome is fewer `/info` calls and lower rate-limit pressure while account streams remain live under event-driven (`:n-a`) status.

You can verify this by activating ghost mode with a busy address and observing that spot clearinghouse refresh activity is bounded by cache/TTL behavior instead of calling network on every refresh cadence.

## Progress

- [x] (2026-03-05 15:12Z) Claimed bug `hyperopen-918` and traced primary call path to `refresh_account_surfaces_after_user_fill!` -> `request-spot-clearinghouse-state!`.
- [x] (2026-03-05 15:13Z) Confirmed `request-spot-clearinghouse-state!` currently lacks request-policy TTL/dedupe defaults in `/hyperopen/src/hyperopen/api/endpoints/account.cljs`.
- [x] (2026-03-05 15:18Z) Added central request policy defaults and dedupe key handling for spot clearinghouse requests.
- [x] (2026-03-05 15:20Z) Added/updated endpoint tests for default policy behavior and explicit override semantics.
- [x] (2026-03-05 15:25Z) Ran required validation gates (`npm run test:websocket`, `npm test`, `npm run check`) and all passed.
- [x] (2026-03-05 15:27Z) Executed post-fix manual browser QA (ghost mode address `0x162cc7c861ebd0c06b3d72319201150482518185`) and captured post-reset request stats plus websocket stream health.
- [x] (2026-03-05 15:28Z) Updated issue notes and closed `hyperopen-918` with validation evidence.

## Surprises & Discoveries

- Observation: The repeated `spotClearinghouseState` activity is driven by websocket user refresh scheduling and not by `openOrders`/`webData2` fallback paths.
  Evidence: Captured stacks show `refresh_account_surfaces_after_user_fill_BANG_` -> `refresh_spot_clearinghouse_snapshot_BANG_` call chain.

- Observation: `request-spot-clearinghouse-state!` merges only priority/opts and does not apply `request-policy/apply-info-request-policy` unlike several other non-subscribable endpoints.
  Evidence: `/hyperopen/src/hyperopen/api/endpoints/account.cljs`.

- Observation: In a controlled post-fix window after `reset_request_runtime!`, ghost-mode account streams remained active (`webData2`, `openOrders`, `userFills` with status `:n-a`) while spot clearinghouse requests stayed bounded.
  Evidence: browser inspection session `sess-1772724050699-5b1f90` showed over ~49s window: `spotClearinghouseState` started `3` with `0` rate-limited while websocket `userFills` increased by `15` messages and `openOrders` by `9` messages.

## Decision Log

- Decision: Fix at endpoint policy boundary first (TTL + dedupe defaults) rather than introducing additional websocket-side timers.
  Rationale: Endpoint policy gives uniform protection across all call sites and leverages existing info-client cache/single-flight controls without expanding runtime timer complexity.
  Date/Author: 2026-03-05 / Codex

## Outcomes & Retrospective

Hotfix implemented and validated.

Outcome summary:
- `:spot-clearinghouse-state` now has central TTL defaults in `/hyperopen/src/hyperopen/api/request_policy.cljs` (`5000ms`).
- `request-spot-clearinghouse-state!` now applies request policy and stable per-address dedupe key in `/hyperopen/src/hyperopen/api/endpoints/account.cljs`.
- Endpoint tests prove default dedupe+TTL behavior and explicit caller override preservation in `/hyperopen/test/hyperopen/api/endpoints/account_test.cljs`.
- Required gates passed locally (`npm run test:websocket`, `npm test`, `npm run check`).
- Manual QA on branch-local app with active ghost-mode address shows bounded spot request cadence under live account websocket activity.

## Context and Orientation

The websocket user domain in `/hyperopen/src/hyperopen/websocket/user.cljs` schedules account-surface refreshes after user fills and non-funding ledger updates. This flow always refreshes spot clearinghouse state using `api/request-spot-clearinghouse-state!`.

The API endpoint implementation for this call lives in `/hyperopen/src/hyperopen/api/endpoints/account.cljs`. Request-level cache and dedupe behavior is centralized in `/hyperopen/src/hyperopen/api/request_policy.cljs` and enforced by the info client in `/hyperopen/src/hyperopen/api/info_client.cljs` when options include `:cache-ttl-ms` and `:dedupe-key`.

Because spot clearinghouse requests currently omit policy defaults, repeated refresh triggers can produce repeated network activity.

## Plan of Work

Update `request-policy` defaults to include a spot clearinghouse TTL entry, then refactor `request-spot-clearinghouse-state!` to apply policy defaults and per-address dedupe keys while preserving caller overrides.

Add endpoint-level tests proving:
- default dedupe key is derived from normalized address,
- default TTL is present,
- explicit `:dedupe-key`/`:cache-ttl-ms` override still works.

Then run repository validation gates and perform manual ghost-mode browser inspection QA focused on `/info` network cadence for `spotClearinghouseState`.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/api/request_policy.cljs`.
2. Edit `/hyperopen/src/hyperopen/api/endpoints/account.cljs`.
3. Edit `/hyperopen/test/hyperopen/api/endpoints/account_test.cljs`.
4. Run:
   - `npm run test:websocket`
   - `npm test`
   - `npm run check`
5. Run browser inspection manual QA on branch-local build and capture `/info` by-type counts.

## Validation and Acceptance

Acceptance criteria:

1. Spot clearinghouse endpoint requests include stable dedupe key and TTL defaults unless explicitly overridden.
2. Automated tests cover the policy behavior.
3. Required validation gates pass.
4. Manual ghost-mode QA confirms spot clearinghouse `/info` network request cadence is materially reduced/bounded versus prior behavior.

## Idempotence and Recovery

Changes are additive and safe to re-run. If policy defaults are too aggressive, callers can temporarily force refresh with `:force-refresh? true` while preserving the central dedupe/TTL seam.

## Artifacts and Notes

Pending post-fix QA artifact summary.

## Interfaces and Dependencies

No new external dependencies.

Existing interfaces updated:

- `/hyperopen/src/hyperopen/api/request_policy.cljs`
  - `default-info-request-ttl-ms` adds `:spot-clearinghouse-state`.
- `/hyperopen/src/hyperopen/api/endpoints/account.cljs`
  - `request-spot-clearinghouse-state!` applies `request-policy/apply-info-request-policy` with per-address dedupe.

Revision note (2026-03-05): Created for `hyperopen-918` after manual QA identified residual high-cadence `spotClearinghouseState` traffic during ghost-mode websocket operation.
