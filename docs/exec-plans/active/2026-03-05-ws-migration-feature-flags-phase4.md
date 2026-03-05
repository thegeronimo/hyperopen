# WS Migration Feature Flags and Canary Guardrails

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, websocket-first migration behavior will be controlled by explicit rollout flags for three flow families: post-order/post-fill account refresh, startup account bootstrap, and candle migration paths. Operators can keep current defaults, disable a flow quickly if websocket health degrades, and run staged canary rollouts without reverting code.

You can verify this by running websocket and core bootstrap tests that assert each flow honors flag toggles and that health-degrade guardrails automatically force REST fallback behavior.

## Progress

- [x] (2026-03-05 03:25Z) Claimed `hyperopen-nhv.6` in `bd` and reviewed ready-work context.
- [x] (2026-03-05 03:27Z) Audited current WS-first seams in `/hyperopen/src/hyperopen/order/effects.cljs`, `/hyperopen/src/hyperopen/websocket/user.cljs`, `/hyperopen/src/hyperopen/startup/runtime.cljs`, `/hyperopen/src/hyperopen/chart/actions.cljs`, and `/hyperopen/src/hyperopen/websocket/subscriptions_runtime.cljs`.
- [x] (2026-03-05 03:28Z) Authored this ExecPlan before implementation.
- [x] (2026-03-05 03:34Z) Implemented centralized migration flag policy module in `/hyperopen/src/hyperopen/websocket/migration_flags.cljs` with config defaults, optional state overrides, and flow-specific health guardrails.
- [x] (2026-03-05 03:37Z) Wired flags into order/fill and startup bootstrap WS-first seams (`/hyperopen/src/hyperopen/order/effects.cljs`, `/hyperopen/src/hyperopen/websocket/user.cljs`, `/hyperopen/src/hyperopen/startup/runtime.cljs`).
- [x] (2026-03-05 03:39Z) Wired candle migration canary decisions into timeframe select and active-asset subscribe paths (`/hyperopen/src/hyperopen/chart/actions.cljs`, `/hyperopen/src/hyperopen/websocket/subscriptions_runtime.cljs`).
- [x] (2026-03-05 03:43Z) Added regression coverage in `/hyperopen/test/hyperopen/websocket/migration_flags_test.cljs`, `/hyperopen/test/hyperopen/core_bootstrap/chart_menu_and_storage_test.cljs`, `/hyperopen/test/hyperopen/websocket/subscriptions_runtime_test.cljs`, `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`, `/hyperopen/test/hyperopen/websocket/user_test.cljs`, and `/hyperopen/test/hyperopen/startup/runtime_test.cljs`.
- [x] (2026-03-05 03:44Z) Added staged canary/rollback runbook at `/hyperopen/docs/runbooks/ws-migration-rollout.md`.
- [x] (2026-03-05 03:46Z) Validation complete: websocket suite passed (`312 tests, 1761 assertions, 0 failures`); `npm run check` still blocked by missing `@noble/secp256k1`.
- [ ] Update `bd` notes with implementation evidence.

## Surprises & Discoveries

- Observation: There is no existing general feature-flag framework in runtime state; behavior toggles are mostly hard-coded or config-derived constants.
  Evidence: Search across `/hyperopen/src/hyperopen/**/*.cljs` found no runtime `:feature-flags` wiring for websocket migration seams.

- Observation: Candle REST fetches are triggered from two user journeys (`select-chart-timeframe`, `subscribe-active-asset!`) and currently run unconditionally.
  Evidence: `/hyperopen/src/hyperopen/chart/actions.cljs` emits `[:effects/fetch-candle-snapshot ...]` directly; `/hyperopen/src/hyperopen/websocket/subscriptions_runtime.cljs` always calls `fetch-candle-snapshot!` on subscribe.

- Observation: Existing WS-first order/startup logic already falls back when topic stream liveness checks fail, but there is no explicit global kill switch for canary rollback.
  Evidence: `/hyperopen/src/hyperopen/order/effects.cljs` and `/hyperopen/src/hyperopen/startup/runtime.cljs` gate by topic liveness only.

- Observation: `select-chart-timeframe` is a state-first action handler, so candle migration canary decisions can be made purely from current state without effect-layer coupling.
  Evidence: `/hyperopen/src/hyperopen/chart/actions.cljs` already receives `state` and returns an effect vector.

## Decision Log

- Decision: Introduce a dedicated policy module (`/hyperopen/src/hyperopen/websocket/migration_flags.cljs`) to centralize default flags, state overrides, and automatic health-degrade fallback guardrails.
  Rationale: Keeping rollout decisions in one pure module avoids scattered conditionals and makes tests deterministic.
  Date/Author: 2026-03-05 / Codex

- Decision: Use config defaults that preserve current behavior (`order/startup ws-first enabled`, `candle migration disabled`) and allow optional runtime override via `[:websocket :migration-flags]`.
  Rationale: This provides immediate kill-switch capability without forcing behavior regression in existing environments.
  Date/Author: 2026-03-05 / Codex

- Decision: Keep candle canary behavior bounded by cache-aware REST backfill (`skip only when cached rows exist`), not full REST elimination.
  Rationale: Full candle subscription migration is a separate issue; this canary flag should not risk blank chart startup/timeframe paths.
  Date/Author: 2026-03-05 / Codex

## Outcomes & Retrospective

`hyperopen-nhv.6` rollout-control slice is implemented.

Delivered:

- New migration flag policy helper (`/hyperopen/src/hyperopen/websocket/migration_flags.cljs`) with:
  - config defaults from `:ws-migration`,
  - optional runtime override via `[:websocket :migration-flags]`,
  - flow-specific health guardrails that force fallback on degradation.
- Order/fill and startup WS-first logic now requires feature enablement from the helper, allowing fast rollback.
- Candle migration flag path added:
  - timeframe selection and active-asset subscribe can skip redundant REST candle fetches when candle cache exists,
  - still performs bounded REST backfill when cache is missing or guardrails force fallback.
- Added rollout runbook with staged canary instructions and rollback toggles.

Validation:

- `npx shadow-cljs compile ws-test && node out/ws-test.js` passed (`312 tests containing 1761 assertions. 0 failures, 0 errors.`)
- `npm run check` remains blocked by missing dependency `@noble/secp256k1` in this environment.

## Context and Orientation

Recent migration slices already changed runtime behavior:

- Post-order/post-fill refresh fanout is WS-first when user streams are live.
- Startup bootstrap now uses delayed fallback fetches for stream-covered data.

This issue adds rollout controls around those changes and prepares candle migration toggles before full candle-subscription migration.

Key modules:

- `/hyperopen/src/hyperopen/order/effects.cljs` (post-order refresh fanout)
- `/hyperopen/src/hyperopen/websocket/user.cljs` (post-fill/ledger refresh fanout)
- `/hyperopen/src/hyperopen/startup/runtime.cljs` (startup account bootstrap)
- `/hyperopen/src/hyperopen/chart/actions.cljs` and `/hyperopen/src/hyperopen/websocket/subscriptions_runtime.cljs` (candle snapshot fetch triggers)

## Plan of Work

### Milestone 1: Central migration flag policy and guardrails

Add a pure helper module to resolve effective migration flags from config defaults plus optional state overrides. Include health-degrade guardrails that force REST fallback by flow when transport or relevant websocket groups are unhealthy.

### Milestone 2: Apply flags to runtime flows

Wire order/fill WS-first gating and startup WS-first bootstrap to require corresponding flag enablement in addition to topic liveness.

Wire candle fetch decisions through a new helper so candle migration canary mode can suppress redundant REST fetches when local candle cache is already present, while still allowing bounded backfill when cache is missing or websocket health is degraded.

### Milestone 3: Tests and rollout docs

Update existing tests and add new coverage for:

- explicit flag-off behavior forcing REST fallback,
- health-degrade guardrail behavior,
- candle migration toggle behavior on timeframe selection and active-asset subscribe.

Add a runbook document describing canary rollout order and fallback toggles.

## Concrete Steps

From `/hyperopen`:

1. Add `/hyperopen/src/hyperopen/websocket/migration_flags.cljs`.
2. Edit flow modules listed above to consume the helper.
3. Update tests:
   - `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`
   - `/hyperopen/test/hyperopen/websocket/user_test.cljs`
   - `/hyperopen/test/hyperopen/startup/runtime_test.cljs`
   - `/hyperopen/test/hyperopen/core_bootstrap/chart_menu_and_storage_test.cljs`
   - `/hyperopen/test/hyperopen/websocket/subscriptions_runtime_test.cljs`
   - add new `/hyperopen/test/hyperopen/websocket/migration_flags_test.cljs`
4. Add rollout doc under `/hyperopen/docs/runbooks/`.
5. Run:
   - `npx shadow-cljs compile ws-test && node out/ws-test.js`
   - `npm run check` (best effort; record blocker if unchanged)

## Validation and Acceptance

Acceptance criteria:

1. WS-first order/fill and startup behavior is controlled by explicit flags and can be turned off without code reversion.
2. Health-degrade guardrails force fallback behavior for affected flows.
3. Candle migration path has an explicit flag and bounded REST backfill behavior.
4. Regression tests cover flag on/off and fallback semantics.
5. Rollout runbook documents staged canary and rollback toggles.

## Idempotence and Recovery

All changes are additive and guard-driven. If regressions occur, disable the affected flow via migration flags to force REST fallback while keeping the code deployed.

## Artifacts and Notes

Command evidence:

- `npx shadow-cljs compile ws-test && node out/ws-test.js`
  - Output: `Ran 312 tests containing 1761 assertions. 0 failures, 0 errors.`
- `npm run check`
  - Blocker: `The required JS dependency "@noble/secp256k1" is not available` during `shadow-cljs compile app`.

## Interfaces and Dependencies

No public API signatures are removed. New helper APIs are internal and pure.

Revision note (2026-03-05): Updated after implementing migration flags + guardrails, wiring flow toggles, adding tests/runbook, and recording validation outputs/blockers.
