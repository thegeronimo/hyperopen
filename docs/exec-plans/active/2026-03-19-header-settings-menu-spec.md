# Specify A Safe, Scoped Header Settings Surface

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is governed by `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`. The tracked work item for this plan is `hyperopen-15vq`, and that `bd` issue must remain the lifecycle source of truth while this plan tracks the implementation story.

## Purpose / Big Picture

After this change, a user on Hyperopen should be able to click the existing header gear icon on the trade route and open a real `Trading Settings` surface instead of a dead icon. The surface should expose only settings that Hyperopen can honor truthfully, with conservative defaults, clear risk copy, and no fake parity checkboxes.

Phase 1 is already shipped. This refresh narrows the next implementation increment to phase 1.5: `Animate order book` and `Show fill markers on chart`. The plan should continue to defer transaction-delay protection, verbose diagnostics, and order confirmation toggles to a later follow-up until the underlying product flows are ready.

## Progress

- [x] (2026-03-19 13:02Z) Created and claimed `bd` issue `hyperopen-15vq` for the header settings-menu spec work.
- [x] (2026-03-19 13:06Z) Reviewed `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/browser-qa.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, `/hyperopen/docs/agent-guides/trading-ui-policy.md`, and `/hyperopen/docs/BROWSER_STORAGE.md`.
- [x] (2026-03-19 13:13Z) Inspected the current header gear trigger, header state, wallet storage-mode flow, fill-toast runtime, orderbook settings, chart marker surfaces, and startup restore paths.
- [x] (2026-03-19 13:19Z) Reviewed official Hyperliquid documentation for account abstraction modes and transaction delay protection to separate true platform semantics from local UI preferences.
- [x] (2026-03-19 13:27Z) Collected multi-agent findings for spec structure, UI behavior, and codebase feasibility and resolved a phased scope for Hyperopen.
- [x] (2026-03-19 14:09Z) Updated the ExecPlan to include an explicit implementation agent workflow and to require `ui_designer` before `worker`.
- [x] (2026-03-19 15:30Z) Re-scoped the active plan for phase 1.5 so implementation can add order-book animation and fill markers without widening into unrelated security or confirmation flows.
- [x] (2026-03-19 16:01Z) Collected `acceptance_test_writer` and `edge_case_test_writer` proposals aligned on startup restore, order-book transition gating, chart marker gating, and persistence invariants.
- [x] (2026-03-19 16:01Z) Collected `ui_designer` copy and grouping guidance for a compact `Display` section with scoped chart-marker wording.
- [ ] Materialize the approved RED-phase tests and begin phase-1.5 execution.

## Surprises & Discoveries

- Observation: Hyperopen already renders a settings gear in the header, but there is no settings-panel state or action flow behind it.
  Evidence: `/hyperopen/src/hyperopen/views/header_view.cljs` defines `settings-icon` and renders `header-settings-button`, while `/hyperopen/src/hyperopen/state/app_defaults.cljs` gives `:header-ui` only `:mobile-menu-open?`.

- Observation: the current gear is unavailable between the `md` and `lg` breakpoints, so the settings entry point disappears at the required `768` review width.
  Evidence: `/hyperopen/src/hyperopen/views/header_view.cljs` wraps the utility icon group in classes equivalent to `visible below md`, `hidden from md`, and `visible again at lg`.

- Observation: Hyperopen does not currently implement browser or operating-system background fill notifications. It only emits in-app order feedback toasts while the app is open.
  Evidence: `/hyperopen/src/hyperopen/websocket/user_runtime/fills.cljs` funnels fill events into `show-order-feedback-toast!`, and no browser `Notification` API or audio pipeline exists in the repository.

- Observation: the safest immediate security-adjacent setting is not an account-mode toggle. It is the existing agent trading persistence mode, which already supports `localStorage` versus `sessionStorage` behavior and already forces a re-enable step when changed.
  Evidence: `/hyperopen/src/hyperopen/wallet/actions.cljs`, `/hyperopen/src/hyperopen/wallet/agent_session.cljs`, `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs`, and `/hyperopen/src/hyperopen/startup/restore.cljs` already implement storage-mode persistence, restore, and reset behavior.

- Observation: Hyperopen does not have a general pre-submit confirmation gate for new orders or close-position actions yet, so “skip confirmation” cannot honestly ship as a day-one checkbox.
  Evidence: `/hyperopen/src/hyperopen/order/actions.cljs` contains leverage and cancel-visible confirmation paths, but there is no generic confirm step in the normal order submit or close-position submit flow.

- Observation: Hyperopen already has low-level signed-action support for `expiresAfter`, which makes transaction delay protection a bounded future feature rather than a full greenfield subsystem.
  Evidence: `/hyperopen/src/hyperopen/api/trading.cljs` and `/hyperopen/src/hyperopen/utils/hl_signing.cljs` already accept `expires-after`, even though no current UI uses it.

- Observation: the order-book panel already owns the current depth-bar transitions, so an `Animate order book` preference can be a local UI gate rather than a new rendering path.
  Evidence: `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs` already applies transition classes to the bid and ask depth bars.

- Observation: the chart stack already has marker plumbing, but generic fill markers are not yet exposed as a user preference.
  Evidence: `/hyperopen/src/hyperopen/views/trading_chart/core.cljs` merges indicator markers with position-overlay markers, while `/hyperopen/src/hyperopen/views/trading_chart/utils/position_overlay_model.cljs` currently derives entry markers from fills for the active position rather than rendering all buy/sell fills as a toggleable setting.

- Observation: Hyperliquid’s “Disable Unified Account Mode” and “Disable HIP-3 Dex Abstraction” are not ordinary local UI preferences. They map to account abstraction modes with real collateral, clearinghouse, and rate-limit consequences.
  Evidence: the official Hyperliquid account-abstraction documentation describes Standard, Unified, Portfolio Margin, and DEX abstraction as account modes, notes that DEX abstraction is being discontinued, notes that Unified and Portfolio Margin are limited to 50k user actions per day, and notes that builder-code addresses must remain in Standard mode to accrue builder fees.

- Observation: Hyperliquid’s transaction delay protection has concrete failure semantics and cannot be treated like a cosmetic checkbox.
  Evidence: the official Hyperliquid support article for `Action already expired` says the protection rejects actions not accepted by L1 within 15 seconds and warns that disabling it can cause delayed or duplicate order placement after reconnection or congestion clears.

## Decision Log

- Decision: treat the screenshot as a product reference, not as a one-for-one backlog.
  Rationale: the user asked for a product spec that fits Hyperopen. Shipping inert parity rows would reduce trust, especially on security-sensitive controls.
  Date/Author: 2026-03-19 / Codex

- Decision: phase 1 shipped only two real controls: `Remember trading session on this device` and `Show fill alerts in app`.
  Rationale: those are the only settings in the screenshot that mapped cleanly to existing Hyperopen behavior without inventing unsupported account semantics or new subsystems.
  Date/Author: 2026-03-19 / Codex

- Decision: present the settings surface as a small anchored dialog on desktop and a bottom sheet on narrow screens, not as a literal ARIA menu.
  Rationale: the content is a mixed preference panel with helper copy, switches, and risk cues. Dialog semantics fit that interaction better than `menuitem` semantics.
  Date/Author: 2026-03-19 / Codex

- Decision: use positive, user-facing labels instead of the screenshot’s double negatives.
  Rationale: labels such as `Remember trading session on this device` and `Show fill alerts in app` are easier to understand, easier to QA, and less error-prone than `Disable ...` style text.
  Date/Author: 2026-03-19 / Codex

- Decision: interpret the screenshot’s background fill notification row as a future parity direction, but scope Hyperopen phase 1 to in-app fill toasts only.
  Rationale: Hyperopen does not have browser background notifications today. Renaming the control avoids promising behavior that does not exist.
  Date/Author: 2026-03-19 / Codex

- Decision: narrow phase 1.5 to `Animate order book` and `Show fill markers on chart`.
  Rationale: both settings map to existing local UI seams, can be implemented without inventing unsupported trading semantics, and keep the next increment small enough to validate cleanly.
  Date/Author: 2026-03-19 / Codex

- Decision: continue deferring transaction delay protection, verbose diagnostics, and order confirmation toggles to a later follow-up.
  Rationale: those controls either require a fuller submit-flow redesign or carry risk semantics that should not be implied by a lightweight settings row.
  Date/Author: 2026-03-19 / Codex

- Decision: keep the settings panel short and truthful rather than expanding it with parity rows for unsupported features.
  Rationale: the surface should behave like a product control panel, not a screenshot replica, and the new phase should stay focused on the two adjacent settings we can honestly honor.
  Date/Author: 2026-03-19 / Codex

- Decision: keep new phase-1 preferences in a small versioned local preference record, but keep trading-session persistence authoritative in the existing wallet storage-mode path.
  Rationale: `localStorage` is appropriate for bounded startup preferences under `/hyperopen/docs/BROWSER_STORAGE.md`, while duplicating `:local` versus `:session` state in two places would create drift.
  Date/Author: 2026-03-19 / Codex

- Decision: make the implementation agent sequence explicit in this plan and include `ui_designer` before the main `worker`.
  Rationale: this feature is both UI-facing and trust-sensitive. Freezing the intended agent workflow in the plan reduces ambiguity before implementation starts and ensures the surface design is reviewed before production edits land.
  Date/Author: 2026-03-19 / Codex

## Outcomes & Retrospective

This planning pass refreshed the original phase-1 spec into a narrower phase-1.5 implementation story. The resulting surface still behaves like a trustworthy Hyperopen control panel, but the next increment is now constrained to two adjacent runtime-backed preferences: order-book animation and chart fill markers.

The plan keeps the broader safety line intact. It continues to separate local UI toggles from account-level trading semantics, and it now makes the remaining work explicit for transaction delay protection, verbose diagnostics, and confirmation toggles as later follow-up items rather than implied next-step scope. No production code has been written for phase 1.5 yet; the remaining work is implementation and governed UI validation.

## Context and Orientation

The current header surface lives in `/hyperopen/src/hyperopen/views/header_view.cljs`. That file already defines the gear icon and renders `header-settings-button`, but the button is inert. The only header UI state today is mobile-menu state in `/hyperopen/src/hyperopen/state/app_defaults.cljs` and `/hyperopen/src/hyperopen/header/actions.cljs`.

In this repository, a “tiny startup preference” means a small user interface value that is safe to read synchronously at startup, such as chart type, orderbook view mode, page size, or locale. `/hyperopen/docs/BROWSER_STORAGE.md` says those values belong in `localStorage`. A “session-only trading connection” means the generated Hyperliquid agent credentials should survive only the current browser session, which the repository already supports with `sessionStorage`. A “device-persistent trading connection” means those credentials are allowed to survive browser restarts on the same device, which the repository already supports with `localStorage`.

The current trading persistence implementation is spread across `/hyperopen/src/hyperopen/wallet/actions.cljs`, `/hyperopen/src/hyperopen/wallet/agent_session.cljs`, `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs`, `/hyperopen/src/hyperopen/app/startup.cljs`, and `/hyperopen/src/hyperopen/startup/restore.cljs`. Changing storage mode already clears the current agent state and surfaces the message “Trading persistence updated. Enable Trading again.” That makes trading persistence the strongest existing security-adjacent candidate for a first settings release.

The current fill-alert implementation is in-app only. `/hyperopen/src/hyperopen/websocket/user_runtime/fills.cljs` detects novel fills and pushes toast payloads into `/hyperopen/src/hyperopen/order/feedback_runtime.cljs`. There is no true background notification path, no browser permission request flow, and no sound playback pipeline in the current codebase. That matters because the screenshot language suggests background notifications, but Hyperopen can honestly implement only in-app fill alerts in phase 1.

The current orderbook already animates depth bars in `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`, and the current chart already has marker and overlay plumbing in `/hyperopen/src/hyperopen/views/trading_chart/**`. Those are good adjacent surfaces for later phases, but they are not required to deliver the first trustworthy settings release.

The Hyperliquid-specific account controls in the screenshot should not be treated as simple checkboxes. Hyperliquid’s official docs describe Standard, Unified Account, Portfolio Margin, and DEX abstraction as account abstraction modes that change how balances collateralize trading. The same docs say DEX abstraction is being discontinued, builder-code addresses must use Standard mode to accrue builder fees, and Unified/Portfolio Margin have a 50k-user-action-per-day limit. Those semantics belong in a later signed-action workflow, not in a phase-1 local preference panel.

Similarly, Hyperliquid’s official support docs say transaction delay protection enforces a 15-second acceptance window and warn that disabling it can cause delayed or duplicate orders after reconnection or congestion. Hyperopen should eventually expose that capability with strong copy, but only after the product explicitly accepts the risk and the implementation binds it to the existing `expiresAfter` plumbing.

## Proposed Product Scope

This product spec deliberately translates the screenshot into a smaller Hyperopen-specific surface. The phase-1 goal was not visual parity, and phase 1.5 keeps that same principle by only adding settings Hyperopen can truthfully honor today.

### Surface Design

On desktop and larger tablet widths, the gear should open a right-aligned `Trading Settings` popover anchored to the trigger. The popover should be approximately `320` to `360` pixels wide, use the existing dropdown panel visual language, and stay short enough that it does not feel like a second navigation menu. On narrow mobile widths, the same trigger should open a bottom sheet with the same content order, a clear title, a close affordance, and scrollable body content when needed.

The gear trigger itself should be available at all required review widths: `375`, `768`, `1280`, and `1440`. The current `md:hidden lg:flex` treatment creates a product gap at `768`, so the implementation must remove that breakpoint hole.

Every settings row in this surface should have three parts: a plain-English label, a short supporting sentence when risk or scope needs clarification, and a right-side control or status cue. Suitable cues are `This device`, `In app`, or `Requires re-enable`. Avoid color-only meaning. Avoid long disabled lists. If a future version needs to hint that more controls are coming, use one muted note at the bottom instead of fake switches.

Phase-1 rows should save immediately only when the effect is a local UI preference. Settings that invalidate trading state must show a short confirmation step first. The open or close UI state should update before any side effects fire, in line with `/hyperopen/docs/FRONTEND.md`.

### V1

Phase 1 is the shipped baseline for this surface.

- `Remember trading session on this device`
  This is the phase-1 security-adjacent preference. It maps directly to the existing wallet agent storage mode. `OFF` means session-only trading persistence. `ON` means device-persistent trading persistence. For fresh installs, default to `OFF`. For users with an existing stored choice, preserve the existing stored value instead of silently migrating them. Toggling either direction should open a short confirm step that says the setting applies only to this device and that changing it will require `Enable Trading` again.

- `Show fill alerts in app`
  This replaces the misleading screenshot language about background notifications. It controls only the existing in-app fill toast path. Default it to `ON` so existing behavior remains stable unless the user opts out. Supporting copy should make clear that browser or operating-system background notifications are not part of phase 1.

Phase 1 should not show account-mode rows, sound rows, chart rows, or warning rows.

### V1.5

This bounded phase adds only the two settings that the codebase can support without inventing new trading semantics.

- `Animate order book`, default `ON`, implemented as a local UI preference that gates the current depth-bar transitions in the order-book panel.
- `Show fill markers on chart`, default `OFF`, implemented as a local preference that gates generic buy/sell fill markers on the active trade chart for the active asset only.

The new rows should live under a compact `Display` section rather than expanding the panel with parity filler. Use one short helper sentence per row and small scope badges rather than extra explanatory blocks.

- `Animate order book`
  Use helper copy equivalent to: `Smooths bid and ask depth-bar changes when the order book updates. Turning it off keeps the same data, just without motion.`
  Use badges equivalent to `Order Book` and `Motion`.
- `Show fill markers on chart`
  Use helper copy equivalent to: `Shows fill markers for the active asset on the price chart. This does not add account-wide markers or markers for other assets.`
  Use badges equivalent to `Chart` and `Active Asset`.

### Later

Later work may include transaction delay protection, verbose error details, order confirmation toggles, true browser background notifications with a permission flow, fill sounds, a product-wide sensitive-value or PNL hiding mode, a centralized warning taxonomy, and explicit account-mode management. Those are real features, not cosmetic checkboxes.

When Hyperopen eventually addresses account abstraction modes, the product should not ship separate checkbox rows named `Disable Unified Account Mode` and `Disable HIP-3 Dex Abstraction`. It should instead ship a separate `Account Mode` management flow that explains the current mode, the impact of switching, whether a signature is required, and any platform constraints such as builder-fee eligibility, collateral behavior, and action-rate limits.

`Hold to Close All Positions` belongs in a later phase only after the underlying close-all behavior itself exists. Today the UI has a stub path, so a preference around that behavior would be premature.

### Explicit Non-Goals

This plan does not cover layout customization, `Return to Default Layout`, spot dusting preferences, order-book click-size behavior, transaction-delay protection, verbose error details, order confirmation toggles, or any long disabled list built only to imitate the screenshot. This plan also does not claim to solve the repository’s browser-secret-storage risk beyond exposing the existing persistence choice more clearly. Stronger key-protection work remains separate.

## Plan of Work

First, add a bounded trading-preference record for local settings that truly belong to Hyperopen. The recommended shape is a new top-level subtree such as `:trading-settings`, restored during startup through a dedicated helper and persisted in one versioned `localStorage` record such as `hyperopen:trading-settings:v1`. Phase 1.5 needs only `:animate-orderbook?` and `:show-fill-markers?`. Do not duplicate unrelated trading-session persistence here.

Next, update `/hyperopen/src/hyperopen/views/header_view.cljs` so the existing gear continues to open the settings surface at all four governed review widths. The trigger must expose `aria-haspopup="dialog"` and `aria-expanded`, and the settings entry point must stay available at all review widths.

After that, wire the two phase-1.5 preferences into the real runtime surfaces. `Animate order book` should gate the current depth-bar transitions in `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`. `Show fill markers on chart` should gate the generic fill-marker path in the trade-chart stack without interfering with fill ingestion or position-overlay rendering.

Finally, define follow-up issues before implementation expands beyond phase 1.5. The next obvious splits are transaction delay protection, verbose error details, and order confirmation preferences. Those belong in linked `bd` tickets once implementation begins, not as implied backlog hidden inside the panel.

## Implementation Workflow / Agents

Implementation should use the repository’s exact agent names and should follow the `feature-flow` and `ui-flow` contracts in a single parent-orchestrated run.

Start with `acceptance_test_writer` and `edge_case_test_writer` in parallel. Their scope is phase-1.5 only: the settings shell, the desktop and mobile presentation rules, the order-book animation toggle, the chart fill-marker toggle, startup restore behavior, and persistence ordering. After both proposals return, freeze one approved contract before any RED-phase test materialization begins.

Next run `tdd_test_writer` to materialize only the approved failing tests for the phase-1.5 contract. Verify the RED phase with the narrowest relevant commands before implementation starts.

Then run `ui_designer` before the main implementation pass. The UI-design task is to lock the final shape of the anchored desktop popover, the narrow-screen bottom sheet, the row grouping, the trust cues, the positive labels, and the copy for the two phase-1.5 toggles. Any material UI decision it makes should be reflected back into this plan before code edits begin.

After the UI direction is frozen, run `worker` as the only role allowed to edit `/hyperopen/src/**`. The `worker` owns the production implementation plus any approved test-surface edits required by the frozen contract.

After implementation, run `reviewer` for correctness, regression, and security review. Because the change is UI-facing, also run `ui_visual_validator` and `browser_debugger` before signoff. `browser_debugger` must record `PASS`, `FAIL`, or `BLOCKED` for all six governed browser-QA passes at `375`, `768`, `1280`, and `1440`.

## Concrete Steps

Work from `/hyperopen`.

1. Keep this plan current while phase-1.5 implementation proceeds. Every stop point must update `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective`.

2. Extend trading-settings state in:

    `/hyperopen/src/hyperopen/state/app_defaults.cljs`
    `/hyperopen/src/hyperopen/trading_settings.cljs`

   Add the phase-1.5 preference keys and default state.

3. Register new header settings actions in:

    `/hyperopen/src/hyperopen/header/actions.cljs`
    `/hyperopen/src/hyperopen/app/actions.cljs`
    `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
    `/hyperopen/src/hyperopen/core/public_actions.cljs`
    `/hyperopen/src/hyperopen/schema/contracts.cljs`
    `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`

   Keep the interaction flow pure and deterministic. Close the settings surface before firing heavy effects.

4. Update the header trigger and panel in:

    `/hyperopen/src/hyperopen/views/header_view.cljs`

   Make the gear visible at `375`, `768`, `1280`, and `1440`. Implement the desktop anchored popover and the narrow-screen bottom sheet using dialog semantics.

5. Add startup-restored trading settings in:

    `/hyperopen/src/hyperopen/app/startup.cljs`
    `/hyperopen/src/hyperopen/startup/restore.cljs`

   Use one versioned `localStorage` record for the phase-1.5 preferences. Follow `/hyperopen/docs/BROWSER_STORAGE.md`.

6. Wire the phase-1.5 runtime behavior in:

    `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`
    `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`
    `/hyperopen/src/hyperopen/views/trading_chart/utils/position_overlay_model.cljs`
    `/hyperopen/src/hyperopen/websocket/user_runtime/fills.cljs`
    `/hyperopen/src/hyperopen/trading_settings.cljs`

   `Animate order book` must only gate the current order-book depth-bar transitions. `Show fill markers on chart` must only gate the chart marker path for buy/sell fills on the active asset.

7. Add or update tests in:

    `/hyperopen/test/hyperopen/views/header_view_test.cljs`
    `/hyperopen/test/hyperopen/header/actions_test.cljs`
    `/hyperopen/test/hyperopen/startup/restore_test.cljs`
    `/hyperopen/test/hyperopen/app/startup_test.cljs`
    `/hyperopen/test/hyperopen/views/l2_orderbook_view_test.cljs`
    `/hyperopen/test/hyperopen/views/trading_chart/core_test.cljs`
    `/hyperopen/test/hyperopen/views/trading_chart/utils/position_overlay_model_test.cljs`
    `/hyperopen/test/hyperopen/websocket/user_runtime/fills_test.cljs`
    `/hyperopen/test/hyperopen/trading_settings_test.cljs`

   Phase-1.5 tests should cover header open or close behavior, breakpoint entry-point presence where feasible, preference restore, order-book transition gating, chart marker gating, marker asset scoping and deduping invariants, and persistence ordering.

8. Run validation commands:

    cd /hyperopen
    npm run check
    npm test
    npm run test:websocket

9. Run governed browser QA on the trade route at `375`, `768`, `1280`, and `1440`, and report `PASS`, `FAIL`, or `BLOCKED` for all six required passes from `/hyperopen/docs/agent-guides/browser-qa.md`.

## Validation and Acceptance

For this spec-only ticket, the required validation is:

    cd /hyperopen
    npm run check

For future phase-1.5 implementation, acceptance is behavior, not just code edits.

On `/trade`, a user must be able to open `Trading Settings` from the header gear at `375`, `768`, `1280`, and `1440`. The surface must open as an anchored dialog on larger widths and as a bottom sheet on narrow widths. Focus must move into the surface on open, `Escape` and outside click must dismiss it, and focus must return to the trigger after dismissal.

For phase 1.5 specifically, the settings panel must expose `Animate order book` and `Show fill markers on chart` with the documented defaults, and those controls must persist and restore reliably across reloads.

The already-shipped `Remember trading session on this device` row must continue to mirror the real current wallet storage mode. Toggling it must not silently mutate storage. It must first show a short confirm step that explains the device scope and the need to re-enable trading. After confirmation, Hyperopen must reuse the existing storage-mode switch path and existing reset message rather than re-implementing that logic.

The already-shipped `Show fill alerts in app` row must continue to suppress new in-app fill toasts when `OFF` and preserve the current fill-toast behavior when `ON`. Fill ingestion, order refresh, and account-surface refresh behavior must remain unchanged.

For phase 1.5 specifically, `Animate order book` must default to `ON`, only gate motion classes on the current depth bars, and leave order data, row geometry, and freshness cues unchanged. `Show fill markers on chart` must default to `OFF`, render markers only for fills that match the active asset when enabled, and suppress those markers entirely when disabled.

Phase 1.5 acceptance also requires omission of unsupported high-stakes controls. The panel must not add active controls for transaction delay protection, order-confirmation skipping, account abstraction mode changes, or sound notifications in this release.

UI signoff must explicitly account for:

- `visual`
- `native-control`
- `styling-consistency`
- `interaction`
- `layout-regression`
- `jank/perf`

If any pass cannot be completed because the live app or design reference is unavailable, record it as `BLOCKED` with evidence.

## Idempotence and Recovery

This spec is safe to re-read and revise. The planned phase-1.5 code changes are ordinary tracked-file edits and startup preference writes. Re-running the restore logic should remain idempotent because the local preference record is small, versioned, and deterministic. If a later phase expands the preference schema, increment the record version explicitly and keep fallback behavior temporary.

If implementation accidentally drifts into unsupported parity work, stop and split that work into a new `bd` issue rather than letting the phase-1.5 panel accumulate inert switches. If a storage-mode change path risks invalidating a live trading session unexpectedly, prefer preserving the current wallet state and surface a clear retry path rather than force-resetting silently.

## Artifacts and Notes

Structured handoff for this spec lives at:

- `/hyperopen/tmp/multi-agent/hyperopen-15vq/spec.json`

The key external product facts already embedded into this plan are:

- Hyperliquid account abstraction controls are account-mode choices with collateral and rate-limit consequences, not ordinary local preferences.
- Hyperliquid transaction delay protection uses a 15-second acceptance window and disabling it carries delayed-order and duplicate-order risk.

The key internal implementation facts already embedded into this plan are:

- Hyperopen already has a persistent-versus-session trading storage mode.
- Hyperopen already has in-app fill toasts but not background notifications.
- Hyperopen already has orderbook animation and chart marker plumbing that later phases can reuse.

## Interfaces and Dependencies

Phase-1.5 implementation should end with these stable state shapes and ownership boundaries:

In `/hyperopen/src/hyperopen/state/app_defaults.cljs`, `:header-ui` should keep its open/close shell state:

    {:mobile-menu-open? false
     :settings-open? false
     :settings-confirmation nil}

In startup-restored application state, add a bounded preference subtree such as:

    {:trading-settings
     {:animate-orderbook? true
      :show-fill-markers? false}}

Do not add trading-session persistence or unrelated risk controls to that subtree. The authoritative interface for storage-mode persistence remains the existing wallet agent storage mode under:

    [:wallet :agent :storage-mode]

No new library is required for phase 1.5. The implementation should stay within the existing Hyperopen view, header action, startup, orderbook, trading-chart, websocket, and test modules named throughout this plan.

Revision note: created on 2026-03-19 to turn the user-supplied Hyperliquid-style screenshot into a truthful Hyperopen product spec, informed by multi-agent repo analysis and official Hyperliquid documentation for account modes and transaction delay protection.

Revision note: updated on 2026-03-19 to add an explicit `Implementation Workflow / Agents` section, including `ui_designer` before `worker`, so the implementation handoff is unambiguous.

Revision note: updated on 2026-03-19 to freeze phase 1.5 to `Animate order book` and `Show fill markers on chart`, incorporating acceptance, edge-case, and UI-copy proposals before RED-phase test materialization.
