# WS-First Gating Hotfix for `:n-a` User Streams

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, order/fill WS-first fallback suppression will engage in real runtime when user/account streams are subscribed and healthy-but-uncadenced (`:n-a` status), instead of requiring strict `:live` status. This should reduce repeated `/info` fallback fanout (`frontendOpenOrders`, `clearinghouseState`) for active accounts that already have websocket coverage.

You can verify this by running websocket tests that assert gating works for `:n-a` streams and by observing reduced fallback POST behavior in manual browser runs with ghost mode enabled.

## Progress

- [x] (2026-03-05 14:40Z) Created and claimed bug issue `hyperopen-sf6` from manual browser findings.
- [x] (2026-03-05 14:41Z) Audited current gating path in `/hyperopen/src/hyperopen/websocket/health_projection.cljs`, `/hyperopen/src/hyperopen/order/effects.cljs`, and `/hyperopen/src/hyperopen/websocket/user.cljs`.
- [x] (2026-03-05 14:44Z) Implemented `topic-stream-usable?` semantics to treat subscribed `:n-a` streams as WS-eligible for flow gating.
- [x] (2026-03-05 14:44Z) Wired order/fill refresh fanout gating to use stream usability instead of strict live-only checks.
- [x] (2026-03-05 14:44Z) Extended startup WS-first gating to use stream usability for delayed fallback and stage-B open-order refresh suppression.
- [x] (2026-03-05 14:45Z) Added regression tests in health projection, order effects, websocket user, and startup runtime suites.
- [x] (2026-03-05 14:45Z) Validated websocket suite and updated/closed issue `hyperopen-sf6`.
- [x] (2026-03-05 14:49Z) Updated startup bootstrap integration test fixture address normalization and re-ran required repository gates (`npm test`, `npm run test:websocket`, `npm run check`).

## Surprises & Discoveries

- Observation: In real browser runs, user streams (`openOrders`, `webData2`, `userFills`, `userFundings`) are often `status :n-a` despite being subscribed and receiving payloads.
  Evidence: Manual snapshot traces from 2026-03-05 browser inspection with ghost-mode active address.

- Observation: Current `topic-stream-live?` requires exact `:live` and therefore never considers these streams eligible for WS-first suppression.
  Evidence: `/hyperopen/src/hyperopen/websocket/health_projection.cljs` `stream-live?` + `topic-stream-live?` usage in order/user effects.

- Observation: Startup bootstrap used the same strict live-only predicate, so delayed backfill fetches for stream-backed topics still executed under `:n-a`.
  Evidence: `/hyperopen/src/hyperopen/startup/runtime.cljs` pre-fix `topic-live-for-address?` use in `schedule-stream-backed-startup-fallback!` and `stage-b-account-bootstrap!`.

## Decision Log

- Decision: Add a new helper (`topic-stream-usable?`) rather than changing semantics of existing `topic-stream-live?`.
  Rationale: Preserve existing behavior contracts/tests for callers that truly require strict `:live`, while letting flow gating use broader but explicit eligibility semantics.
  Date/Author: 2026-03-05 / Codex

- Decision: Treat statuses `:live` and `:n-a` as usable only when transport is live and stream is subscribed.
  Rationale: `:n-a` represents subscribed streams without cadence-based stale threshold; this is common for account/order event streams.
  Date/Author: 2026-03-05 / Codex

## Outcomes & Retrospective

Implemented and validated:

- Added stream usability selector (`topic-stream-usable?`) that treats subscribed `:live` and `:n-a` streams as WS-eligible when transport is live.
- Migrated WS-first gating to stream usability in:
  - `/hyperopen/src/hyperopen/order/effects.cljs`
  - `/hyperopen/src/hyperopen/websocket/user.cljs`
  - `/hyperopen/src/hyperopen/startup/runtime.cljs`
- Added targeted regressions for `:n-a` coverage in:
  - `/hyperopen/test/hyperopen/websocket/health_projection_test.cljs`
  - `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`
  - `/hyperopen/test/hyperopen/websocket/user_test.cljs`
  - `/hyperopen/test/hyperopen/startup/runtime_test.cljs`
- Updated `/hyperopen/test/hyperopen/core_bootstrap/runtime_startup_test.cljs` to use a normalized 42-char address in `account-bootstrap-two-stage-and-guarded-test`, aligning with `effective-account-address` guard semantics.
- Validation runs:
  - `npx shadow-cljs compile ws-test && node out/ws-test.js` passed (`328` tests, `1816` assertions, `0` failures/errors).
  - `npm test` passed (`1906` tests, `9812` assertions, `0` failures/errors).
  - `npm run test:websocket` passed (`328` tests, `1816` assertions, `0` failures/errors).
  - `npm run check` passed (lint/docs/compile gates).

## Context and Orientation

The WS-first suppression logic for post-order and post-fill fallback REST fanout currently calls `health_projection/topic-stream-live?` in two places:

- `/hyperopen/src/hyperopen/order/effects.cljs`
- `/hyperopen/src/hyperopen/websocket/user.cljs`

`topic-stream-live?` is implemented in `/hyperopen/src/hyperopen/websocket/health_projection.cljs` and only returns true for streams with status `:live`, subscribed true, and live transport.

In this repository, health status `:n-a` means “not cadence-graded” for a subscribed stream that can still be authoritative for event-driven updates. For user/account streams this is normal and should still allow WS-first suppression.

## Plan of Work

Add a stream usability selector in health projection that uses the same selector/address matching logic as `topic-stream-live?` but accepts `:n-a` status in addition to `:live`. Then switch order/user gating call sites to this selector.

Update tests to cover `:n-a` positive gating and preserve negatives for disconnected transport, ambiguous selector-free matches, and delayed/idle streams.

## Concrete Steps

From `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/websocket/health_projection.cljs`.
2. Edit `/hyperopen/src/hyperopen/order/effects.cljs` and `/hyperopen/src/hyperopen/websocket/user.cljs`.
3. Edit:
   - `/hyperopen/test/hyperopen/websocket/health_projection_test.cljs`
   - `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`
   - `/hyperopen/test/hyperopen/websocket/user_test.cljs`
4. Run:
   - `npx shadow-cljs compile ws-test && node out/ws-test.js`

## Validation and Acceptance

Acceptance criteria:

1. `topic-stream-usable?` returns true for matching subscribed streams in `:live` or `:n-a` status when transport is live.
2. Order mutation and user-fill refresh paths skip fallback open-orders/default-clearinghouse calls when matching streams are usable (`:n-a` or `:live`).
3. Existing fallback still happens for disabled flag, disconnected transport, `:idle`, or `:delayed` stream states.
4. Websocket test suite passes.

## Idempotence and Recovery

Changes are additive and isolated to health selection + call-site gating. If regressions appear, callers can be switched back to `topic-stream-live?` while keeping the new helper for future controlled use.

## Artifacts and Notes

Manual finding baseline (before fix): ghost-mode live session showed heavy fallback `frontendOpenOrders` and `clearinghouseState` POSTs while corresponding streams were subscribed with `:n-a` status.

## Interfaces and Dependencies

Add public helper in `/hyperopen/src/hyperopen/websocket/health_projection.cljs`:

- `topic-stream-usable? [health topic selector] -> boolean`

No external dependencies added.

Revision note (2026-03-05): Created to address post-migration manual finding that strict `:live` gating undercounts usable account streams and keeps fallback fanout active.
