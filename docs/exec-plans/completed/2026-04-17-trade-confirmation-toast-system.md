# Implement Trade Confirmation Toast System

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. This document follows `/hyperopen/docs/PLANS.md` and `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Hyperopen currently shows order and fill feedback through a generic single toast shape. After this change, confirmed trade fills will use the supplied handoff system: a compact pill for ordinary single fills, a detailed pill for notable fills, a short stack for small bursts, a consolidated summary for larger same-side bursts, and an expandable blotter card for grouped multi-fill activity. A user can see the change when live `userFills` websocket events arrive, and a contributor can inspect the five variants in the Portfolio UI workbench without changing the Portfolio or Trade page layouts.

The live tracker for this work is `bd` issue `hyperopen-pmyo`.

## Progress

- [x] (2026-04-17 19:12Z) Created and claimed `bd` issue `hyperopen-pmyo`; read the handoff HTML/CSS, current `notifications-view`, `order.feedback-runtime`, `websocket.user-runtime.fills`, workbench scenes, and UI/browser QA contracts.
- [x] (2026-04-17 19:18Z) Added RED coverage for fill state-machine classification, structured payloads, notification rendering, metadata preservation, and websocket incremental merge behavior.
- [x] (2026-04-17 19:31Z) Ported the five handoff components into `hyperopen.views.trade-confirmation-toasts` with `cljs.spec` prop contracts and preserved class hooks.
- [x] (2026-04-17 19:36Z) Wired `userFills` into structured trade-confirmation payloads, active-toast burst merging, expand/collapse actions, and metadata-preserving feedback runtime storage.
- [x] (2026-04-17 19:40Z) Added Portfolio workbench scenes for `PillToast`, `DetailedToast`, `ToastStack`, `ConsolidatedToast`, and `BlotterCard`.
- [x] (2026-04-17 19:58Z) Ran required checks plus workbench/browser validation across the four required widths.

## Surprises & Discoveries

- Observation: The supplied handoff files are readable by direct file path, but listing `/Users/barry/Downloads/handoff` was denied by macOS privacy controls.
  Evidence: `stat` succeeded for both files; `ls -la /Users/barry/Downloads/handoff` returned `Operation not permitted`.
- Observation: The existing toast runtime already supports multiple entries in `:ui :toasts` with per-toast dismiss IDs and timeouts.
  Evidence: `/hyperopen/src/hyperopen/order/feedback_runtime.cljs` stores bounded toasts and `/hyperopen/src/hyperopen/websocket/user_runtime/fills.cljs` already calls `show-order-feedback-toast!` for fill events.
- Observation: The feedback runtime originally normalized map payloads down to only `:message`, `:headline`, and `:subline`, which stripped the new `:toast-surface`, `:variant`, and `:fills` fields before rendering and prevented cross-event merge detection.
  Evidence: The first full `npm test` run after implementation showed missing variants in `:ui :toasts`; preserving the original map while normalizing text fixed both rendering and merge behavior.
- Observation: The hiccup linter cannot parse auto-resolved keywords in view files because its Edamame parser is not configured with `:auto-resolve`.
  Evidence: `npm run check` initially failed in `lint:hiccup` on `::id` inside `trade_confirmation_toasts.cljs`; explicit namespaced spec keywords fixed the gate.
- Observation: Browser verification through the Portfolio workbench is slow when each scene reloads the iframe, but it completed deterministically with a Node Playwright runner.
  Evidence: `output/playwright/toast-components/*.png` contains screenshots for all five scenes at 375, 768, 1280, and 1440 px, and the runner reported visible non-empty bounds with no horizontal overflow.

## Decision Log

- Decision: Preserve `:ui :toasts` and the existing timeout/dismiss adapter instead of adding a second toast store.
  Rationale: The current runtime already handles auto-timeout and manual dismiss. Adding a parallel store would duplicate state and risk inconsistent cleanup.
  Date/Author: 2026-04-17 / Codex.
- Decision: Treat the handoff HTML/CSS as the visual source of truth and port its class hooks directly into `notifications-view` and `src/styles/main.css`.
  Rationale: The task explicitly requires pixel matching and preserving names/class hooks. ClojureScript/Hiccup can render those hooks without React runtime changes.
  Date/Author: 2026-04-17 / Codex.
- Decision: Use the existing feedback toast store for trade toasts, but preserve structured payload metadata through `order.feedback-runtime`.
  Rationale: This keeps auto-timeout and manual dismiss semantics intact while giving the notification renderer enough data to choose the five trade-confirmation surfaces.
  Date/Author: 2026-04-17 / Codex.
- Decision: Render mixed 4+ bursts as a stack with overflow rather than a consolidated pill.
  Rationale: The explicit auto-merge rule only assigns `ConsolidatedToast` to 4+ fills with the same side; mixed-side groups need an expandable surface that does not imply one net-side summary.
  Date/Author: 2026-04-17 / Codex.

## Outcomes & Retrospective

Implemented.

The production toast host now dispatches `:trade-confirmation` payloads to five Hiccup component functions: `PillToast`, `DetailedToast`, `ToastStack`, `ConsolidatedToast`, and `BlotterCard`. The websocket fill runtime emits the required fill prop shape `{id, side, symbol, price, qty, orderType, ts}`, classifies one-fill notable cases, 2-3 fill bursts, and 4+ same-side bursts, and merges active trade toasts that remain inside the ten-second window. Stack and consolidated toasts expand through `:actions/expand-order-feedback-toast`; the blotter collapses through `:actions/collapse-order-feedback-toast`.

The browser-QA accounting for `/ui-workbench.html?id=hyperopen.workbench.scenes.shell.shell-scenes/<toast-scene>` is:

- Visual pass: PASS at 375, 768, 1280, and 1440 px; screenshots captured under `output/playwright/toast-components/`.
- Native-control pass: PASS; visible controls are real `button` elements for dismiss, expand, and collapse.
- Styling-consistency pass: PASS; computed styles retained Inter/JetBrains Mono stack, `o-toast-in` on animated surfaces, and `o-pulse` on the blotter pulse.
- Interaction pass: PASS for reachable close/expand/collapse hooks and keyboard-reachable buttons; workbench reducers cover expand/collapse state.
- Layout-regression pass: PASS; automated DOM checks found visible non-empty bounds and no horizontal overflow at all required widths.
- Jank/perf pass: PASS for repeated scene load/resize checks; no unstable bounds or delayed paints were observed during the automated matrix.

No Browser MCP or browser-inspection session remained open; `npm run browser:cleanup` returned an empty stopped-session list.

## Context and Orientation

The existing global notification surface is `/hyperopen/src/hyperopen/views/notifications_view.cljs`. It renders entries from `[:ui :toasts]` or the legacy `[:ui :toast]`, and its container is fixed near the bottom right. The generic toast runtime is `/hyperopen/src/hyperopen/order/feedback_runtime.cljs`; it normalizes message text, assigns IDs, keeps the most recent toast in the legacy location, and schedules clear timers through runtime adapters.

Trade-fill websocket events are handled under `/hyperopen/src/hyperopen/websocket/user_runtime/fills.cljs`. That namespace deduplicates incoming rows, normalizes raw Hyperliquid fill fields such as `:coin`, `:side`, `:sz`, `:px`, `:time`, and currently emits generic success toast payloads. The actual `userFills` handler calls this fill runtime from `/hyperopen/src/hyperopen/websocket/user_runtime/handlers.cljs` and websocket tests already cover incremental fills.

The Portfolio UI workbench lives under `/hyperopen/portfolio/hyperopen/workbench/scenes/**`. Existing shell notification scenes are in `/hyperopen/portfolio/hyperopen/workbench/scenes/shell/shell_scenes.cljs`; they can host the new five toast demos because these are app-frame notifications rather than route layout changes.

The handoff components to preserve are named `PillToast`, `DetailedToast`, `ToastStack`, `ConsolidatedToast`, and `BlotterCard`. In ClojureScript they should be exposed as similarly named Hiccup functions or aliases while preserving CSS hooks such as `.o-toast`, `.o-toast.sell`, `.o-stack`, `.o-more`, `.o-consol`, `.o-blotter`, `.o-blotter-*`, and `.o-bg-*`.

## Plan of Work

First, extend tests around `hyperopen.websocket.user-runtime.fills` so one incoming fill produces a structured `:trade-fill` toast payload, notable single fills produce a `:trade-fill-detailed` variant, two or three fills in the same ten-second burst produce a stack payload, four or more same-side fills produce a consolidated payload, and expanding sets a blotter view. The tests should prove the shape includes `{id, side, symbol, price, qty, orderType, ts}` for each fill.

Second, extend `hyperopen.views.notifications-view` tests so the rendered Hiccup uses the new class hooks, keeps `role="status"` and `aria-live="polite"` on the container, and uses keyboard-reachable `button` elements for dismiss, expand, and collapse controls.

Third, implement the ClojureScript/Hiccup component functions in `notifications_view.cljs`, with small pure helpers for formatting, totals, grouping, and variant selection. Generic non-fill toasts must keep working.

Fourth, port the CSS from `/Users/barry/Downloads/handoff/toast-components.css` into `/hyperopen/src/styles/main.css`, replacing the old `.global-toast-*` card styling for this surface while preserving bottom-right host positioning and reduced-motion handling. The host z-index must remain above page content and below modal surfaces.

Fifth, add five Portfolio workbench scenes under the existing shell collection that render each card with static sample data. These scenes should match the handoff demo without adding route chrome or modifying Trade/Portfolio layouts.

Finally, run targeted tests first, then required repository gates: `npm run check`, `npm test`, and `npm run test:websocket`. Because this is UI-facing, also run the smallest relevant workbench/browser validation and account for the browser QA passes and widths from `/hyperopen/docs/agent-guides/browser-qa.md`.

## Concrete Steps

Run commands from `/Users/barry/.codex/worktrees/7b23/hyperopen`.

The RED phase should use targeted CLJS tests first:

    npm test -- --focus hyperopen.websocket.user-runtime.fills-test
    npm test -- --focus hyperopen.views.app-view-test

If the runner does not support `--focus`, use the nearest project-supported targeted command discovered during implementation, then run the full gates.

## Validation and Acceptance

Acceptance means all of the following are true:

1. A single ordinary fill from `userFills` renders as `PillToast` with buy green and sell red styling.
2. A single notable fill renders as `DetailedToast` with average price, notional, and slippage/detail strip.
3. Two or three fills in a ten-second window render as `ToastStack`.
4. Four or more fills in a ten-second window with the same side render as `ConsolidatedToast`.
5. Clicking expand on stack or consolidated toasts renders `BlotterCard`; clicking collapse returns to the compact grouped toast.
6. Existing auto-timeout and manual dismiss behavior still clear the correct toast entries.
7. The toast region has `role="status"` and `aria-live="polite"`, and close/expand/collapse controls are buttons.
8. Portfolio workbench includes five scenes corresponding to the handoff components.
9. `npm run check`, `npm test`, and `npm run test:websocket` pass, or any failure is recorded here with a concrete blocker.

## Idempotence and Recovery

The changes are additive to the existing toast pipeline. If a test or browser pass fails, restore the previous state by removing the new structured toast metadata and component branches while leaving `order.feedback-runtime` untouched. The workbench scenes are isolated and can be removed without affecting production routes. Do not run `git pull --rebase` or `git push` for this plan unless the user explicitly requests remote sync.

## Artifacts and Notes

The handoff reference files are:

- `/Users/barry/Downloads/handoff/Toast Components Demo.html`
- `/Users/barry/Downloads/handoff/toast-components.css`

The relevant implementation files are expected to include:

- `/hyperopen/src/hyperopen/views/notifications_view.cljs`
- `/hyperopen/src/hyperopen/websocket/user_runtime/fills.cljs`
- `/hyperopen/src/styles/main.css`
- `/hyperopen/portfolio/hyperopen/workbench/scenes/shell/shell_scenes.cljs`
- `/hyperopen/test/hyperopen/websocket/user_runtime/fills_test.cljs`
- `/hyperopen/test/hyperopen/views/notifications_view_trade_confirmation_test.cljs`

## Interfaces and Dependencies

No new React dependency is needed. The app uses ClojureScript and Hiccup through Replicant. The component functions should accept maps shaped like the handoff props:

    {:id string-or-number
     :side :buy-or-sell
     :symbol string
     :price number
     :qty number
     :orderType string
     :ts number}

The typed props contract should be expressed with pure predicates or `cljs.spec.alpha` so tests can validate malformed rows without needing browser rendering.
