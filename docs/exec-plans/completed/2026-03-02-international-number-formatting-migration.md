# International Number Formatting Migration

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md` and must be maintained in accordance with that file.

## Purpose / Big Picture

Hyperopen currently formats many numbers with hardcoded `"en-US"` and local view-level helpers, which makes output inconsistent across screens and blocks locale-correct rendering for international users. After this migration, number and currency formatting will come from one canonical formatter surface that can respect a user locale preference while preserving current output defaults until each UI surface is migrated.

A user can verify the behavior by setting a locale preference, loading key views (`/trade`, `/portfolio`, account history tabs, and vault screens), and observing consistent separators/currency display rules on all migrated surfaces without regression in value precision.

## Progress

- [x] (2026-03-02 15:55Z) Audited repository formatter usage and identified hardcoded `"en-US"` and local formatting duplication hotspots.
- [x] (2026-03-02 15:55Z) Reviewed planning contract requirements in `/hyperopen/.agents/PLANS.md` and established this active ExecPlan.
- [x] (2026-03-02 16:08Z) Implemented Milestone 1 foundation: locale preference ownership plus centralized formatter registry and compatibility wrappers in `/hyperopen/src/hyperopen/utils/formatting.cljs` and `/hyperopen/src/hyperopen/i18n/locale.cljs`.
- [x] (2026-03-02 16:08Z) Implemented Milestone 1 first-wave migration of direct `"en-US"` callsites in account history, order form summary, orderbook, and vault transfer formatting.
- [x] (2026-03-02 16:08Z) Added Milestone 1 tests for locale preference resolution and formatter behavior compatibility (`test/hyperopen/i18n/locale_test.cljs`, `test/hyperopen/state/app_defaults_test.cljs`, `test/hyperopen/utils/formatting_test.cljs`).
- [x] (2026-03-02 16:08Z) Ran Milestone 1 validation commands and required gates (`npm test`, `npm run check`, `npm run test:websocket`) successfully.
- [x] (2026-03-02 16:08Z) Implemented Milestone 2 migration of remaining hardcoded `"en-US"` formatters in portfolio/vault chart tooltip and custom chart formatter modules.
- [x] (2026-03-02 16:36Z) Implemented Milestone 3 deduplication of percent/ratio/integer helpers into shared formatter utilities and migrated portfolio/vault detail callsites.
- [x] (2026-03-02 16:36Z) Implemented Milestone 4 locale-aware input parsing boundaries for vault transfer input paths plus targeted parsing tests.
- [x] (2026-03-02 16:36Z) Ran required validation gates after Milestones 3-4 (`npm test`, `npm run check`, `npm run test:websocket`) with zero failures.
- [x] (2026-03-02 16:37Z) Moved plan to `/hyperopen/docs/exec-plans/completed/2026-03-02-international-number-formatting-migration.md`.

## Surprises & Discoveries

- Observation: The repository already has a central formatter namespace (`/hyperopen/src/hyperopen/utils/formatting.cljs`), but many high-traffic views bypass it and directly call `.toLocaleString` with hardcoded `"en-US"`.
  Evidence: At plan start, `rg -n '"en-US"' src/hyperopen --hidden` returned 27 matches in 12 files.

- Observation: Policy documentation already requires centralized formatting, so this migration aligns implementation with existing project guidance rather than introducing a new policy.
  Evidence: `/hyperopen/docs/agent-guides/trading-ui-policy.md` states “MUST centralize formatting utilities for price, quantity, and timestamp rendering.”

- Observation: `npm test` initially failed in this worktree because dependencies were not installed and `shadow-cljs` was missing from PATH.
  Evidence: Initial failure `sh: shadow-cljs: command not found`; resolved after `npm install`.

- Observation: Portfolio and vault tooltip date tests encoded a fixed `year month day` string order; locale-native date formatting required test assertions to validate tokens instead of exact order.
  Evidence: Updated assertion in `test/hyperopen/views/portfolio_view_test.cljs` from exact `"2026 Feb 26"` to token-presence matching.

- Observation: Initial locale-grouping validation rejected valid grouped `en-US` inputs like `"1,234.56"` because regex-based symbol splitting was too brittle.
  Evidence: Failing assertions in `test/hyperopen/utils/parse_test.cljs`; resolved by moving grouping validation to string index/split checks and keeping malformed grouping rejection (`"1,2,3"`).

## Decision Log

- Decision: Keep default locale behavior `en-US` unless a valid explicit locale preference is stored, rather than switching defaults to browser locale in Milestone 1.
  Rationale: This preserves current deterministic output and test expectations while adding international support as an explicit capability; browser-locale default can be introduced later behind clear acceptance criteria.
  Date/Author: 2026-03-02 / Codex

- Decision: Implement migration in additive milestones without breaking existing formatter function signatures.
  Rationale: Existing views and tests depend heavily on current formatter APIs; compatibility-first changes reduce regression risk and allow staged adoption.
  Date/Author: 2026-03-02 / Codex

- Decision: Normalize chart tooltip date rendering to locale-native `Intl.DateTimeFormat` output rather than forcing a custom `year month day` part order.
  Rationale: Locale-native order is more correct for international users and aligns with migration goals; tests were updated to assert semantic presence instead of fixed token order.
  Date/Author: 2026-03-02 / Codex

- Decision: Compute signed percent prefix from rounded value, not raw input, to avoid rendering `-0.00%` when display precision rounds to zero.
  Rationale: UI display semantics should reflect rendered precision; users should not see negative zero artifacts.
  Date/Author: 2026-03-02 / Codex

- Decision: Enforce locale grouping validity with explicit integer/fraction partition checks before normalization.
  Rationale: Accepting any grouped text risks silently coercing malformed user input; explicit validation preserves predictable parsing while allowing locale-correct grouped strings.
  Date/Author: 2026-03-02 / Codex

## Outcomes & Retrospective

Milestones 1-4 are complete and validated:

- Locale preference foundation now exists in app state (`[:ui :locale]`) and startup restore flow.
- Shared formatter utilities now own locale-aware number/date formatting via memoized `Intl` registries.
- First-wave and second-wave hardcoded locale callsites were migrated; `rg -n '"en-US"' src/hyperopen test` now reports only the intentional fallback constant in `/hyperopen/src/hyperopen/i18n/locale.cljs`.
- Repeated cross-view percent/ratio/integer helper logic now lives in `/hyperopen/src/hyperopen/utils/formatting.cljs` and is consumed by portfolio/vault detail modules.
- Safe locale-aware input normalization/parsing now exists in `/hyperopen/src/hyperopen/utils/parse.cljs`, with vault transfer preview parsing reading locale from `[:ui :locale]`.
- Added targeted regression tests for new formatter helpers and localized parser behavior:
  - `/hyperopen/test/hyperopen/utils/formatting_test.cljs`
  - `/hyperopen/test/hyperopen/utils/parse_test.cljs`
  - `/hyperopen/test/hyperopen/vaults/actions_test.cljs`
- Validation passed:
  - `npm test`
  - `npm run check`
  - `npm run test:websocket`

## Context and Orientation

The canonical formatter utility today is `/hyperopen/src/hyperopen/utils/formatting.cljs`. It currently hardcodes `"en-US"` in several functions and exposes helpers used across views. In parallel, multiple view modules contain local formatting helpers with direct `.toLocaleString`/`.toFixed` usage. The highest-risk duplicated surfaces are:

- Account history tabs and CSV export:
  - `/hyperopen/src/hyperopen/views/account_info/tabs/funding_history.cljs`
  - `/hyperopen/src/hyperopen/views/account_info/tabs/trade_history.cljs`
  - `/hyperopen/src/hyperopen/views/account_info/tabs/order_history.cljs`
  - `/hyperopen/src/hyperopen/views/account_info/tabs/positions.cljs`
  - `/hyperopen/src/hyperopen/account/history/effects.cljs`
- Order form summary display:
  - `/hyperopen/src/hyperopen/views/trade/order_form_summary_display.cljs`
- Orderbook formatting:
  - `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`
- Vault transfer display formatting:
  - `/hyperopen/src/hyperopen/vaults/detail/transfer.cljs`

App-state defaults are defined in `/hyperopen/src/hyperopen/state/app_defaults.cljs`, and startup preference restoration is orchestrated by `/hyperopen/src/hyperopen/startup/init.cljs` and `/hyperopen/src/hyperopen/app/startup.cljs`.

In this plan, “locale preference ownership” means a single app-state location (`[:ui :locale]`) and one storage key for persisted preference. “Formatter registry” means a memoized factory of `Intl.NumberFormat` instances keyed by locale and options, so all number formatting uses one code path.

## Plan of Work

### Milestone 1: Foundation and First-Wave Migrations

Milestone 1 adds the minimum architecture to make locale-aware formatting possible without destabilizing existing behavior. First, add a small locale utility namespace that validates locale tokens, loads a persisted locale preference, and exposes canonical fallback resolution. Then extend app defaults and startup restore flow so locale preference is part of deterministic UI state. Next, refactor `/hyperopen/src/hyperopen/utils/formatting.cljs` to use memoized `Intl.NumberFormat` helpers instead of hardcoded `"en-US"`, while preserving existing public formatter function signatures and output defaults.

After the formatter boundary is in place, migrate direct hardcoded `"en-US"` callsites in account history, order form summary, orderbook, and vault transfer modules to shared formatter helpers. This milestone ends when those modules no longer own locale constants, tests still pass, and outputs remain unchanged under the default locale.

### Milestone 2: Remaining Hardcoded Locale Surfaces

Migrate remaining direct `"en-US"` usage in portfolio/vault chart tooltip formatters and chart interop formatting modules. Remove local `Intl.NumberFormat` construction where equivalent shared helpers exist or add narrowly scoped helpers in `/hyperopen/src/hyperopen/utils/formatting.cljs` when not yet covered.

### Milestone 3: Cross-View Numeric Formatting Dedup

Deduplicate repeated percent/ratio/integer display helper logic across portfolio and vault detail views into shared formatter helpers. Keep display contracts stable (`"--"` fallback semantics, sign behavior, decimal precision), but move logic ownership out of per-view duplicated functions.

### Milestone 4: Locale-Aware Input Parsing (Safe Boundaries)

Introduce locale-aware parsing only for user-entered numeric inputs where parsing currently assumes dot-decimal and where behavior can be validated without touching signing or protocol serialization contracts. Keep protocol payload formatting deterministic and unchanged where exchange requirements already exist.

## Concrete Steps

From `/hyperopen`:

1. Implement Milestone 1 locale utility + app-state/startup wiring.
2. Refactor `/hyperopen/src/hyperopen/utils/formatting.cljs` to use memoized locale-aware `Intl.NumberFormat` wrappers.
3. Migrate first-wave direct locale callsites listed in Milestone 1.
4. Add/adjust tests in:
   - `/hyperopen/test/hyperopen/state/app_defaults_test.cljs`
   - new locale utility tests
   - `/hyperopen/test/hyperopen/utils/formatting_test.cljs`
   - any affected view tests.
5. Run:
   - `npm test`
   - `npm run check`
   - `npm run test:websocket`
6. Update this plan sections with concrete evidence and timestamps.

## Validation and Acceptance

Milestone 1 acceptance criteria:

1. No first-wave migrated file contains hardcoded `"en-US"` numeric formatting.
2. Formatter APIs used by existing callsites remain backward-compatible.
3. Default output remains compatible with existing tests and snapshots.
4. Locale preference is represented in app state and restored deterministically from persisted storage.

Overall plan acceptance criteria:

1. Number/currency formatting behavior is centralized through shared helpers.
2. Locale preference can drive formatting behavior across migrated UI surfaces.
3. Required repo validation gates pass (`npm run check`, `npm test`, `npm run test:websocket`).

## Idempotence and Recovery

All changes are source-only and additive. Steps are safe to re-run. If a migration step causes formatting regressions, revert only the affected callsite to shared formatter wrappers while keeping locale utility and registry foundation intact. If tests fail due output drift, recover by preserving old precision/sign fallback semantics and adding adapter wrappers rather than changing view contracts abruptly.

## Artifacts and Notes

Key discovery command output before Milestone 1 implementation:

    rg -n '"en-US"' src/hyperopen --hidden

Expected during migration:

- Match count decreases as callsites move to shared formatters.
- Existing formatting tests continue to pass with default locale behavior.

## Interfaces and Dependencies

Milestone 1 introduces these interfaces:

- Locale preference utility namespace under `/hyperopen/src/hyperopen/i18n/` with normalization and resolution helpers.
- Shared formatter registry behavior in `/hyperopen/src/hyperopen/utils/formatting.cljs` using memoized `Intl.NumberFormat` constructors keyed by locale/options.

No new external npm dependencies are required. Existing `Intl` runtime support in browser/Node is used.

Plan revision note: 2026-03-02 15:55Z - Initial plan authored after repository formatting audit; Milestone 1 implementation pending.
Plan revision note: 2026-03-02 16:36Z - Milestones 3-4 completed and validated; plan ready to move to completed.
