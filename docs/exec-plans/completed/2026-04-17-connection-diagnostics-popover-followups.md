# Connection Diagnostics Popover Follow-Ups

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. This document follows `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

The compact Connection Diagnostics popover should behave like a nearby footer affordance, not like a remote drawer. After this change, clicking the Online connection trigger on the left side of the footer opens the popover directly above that trigger, the trigger only shows millisecond freshness when the trader has enabled freshness labels, and the developer details area stays compact enough to resemble the supplied mock states. Full redacted support data remains available through Copy diagnostics, but the visible developer preview only shows a small summary.

This work is tracked by `bd` issue `hyperopen-ijyo`.

## Progress

- [x] (2026-04-18 02:30Z) Captured the three reported defects in `bd` issue `hyperopen-ijyo` and this ExecPlan: left trigger anchoring, freshness-gated trigger latency, and oversized developer details.
- [x] (2026-04-18 02:37Z) Added failing CLJS and Playwright coverage for the reported behavior; `npm test` failed on the intended anchoring, latency, and developer-preview assertions before production changes.
- [x] (2026-04-18 02:38Z) Implemented the minimal production changes in footer rendering, diagnostics view-model, and CSS.
- [x] (2026-04-18 02:41Z) Ran focused validation, the required repo gates, governed design review, and browser cleanup.
- [x] (2026-04-18 02:43Z) Moved this ExecPlan to completed and closed `hyperopen-ijyo` after acceptance passed.

## Surprises & Discoveries

- `npx playwright test tools/playwright/test/connection-diagnostics.spec.mjs --grep "connection diagnostics" --workers=1` was initially blocked because an orphaned local `shadow-cljs watch app portfolio-worker vault-detail-worker` process held port 8080. Stopping that repo-local watch process allowed Playwright to own the configured web server.

## Decision Log

- Decision: Treat the screenshots and written report as the approved design target rather than asking for another design approval loop.
  Rationale: The user gave explicit visual examples, concrete corrections, and asked to implement the corrections now.
  Date/Author: 2026-04-18 / Codex.

## Outcomes & Retrospective

Complete. The popover now renders from the left footer connection slot and `.hx-pop-layer`/`.hx-pop` are left-aligned so the card opens above the Online trigger. The trigger latency is gated by `:websocket-ui :show-surface-freshness-cues?`, so the millisecond label is hidden until freshness labels are enabled. Developer details now show a compact visible preview: three timeline rows and five streams under a single `Streams (N)` heading, while Copy diagnostics continues to use the full redacted payload path.

Validation passed:

- `npm run css:build`
- `npx shadow-cljs --force-spawn compile app`
- `npx playwright test tools/playwright/test/connection-diagnostics.spec.mjs --grep "connection diagnostics" --workers=1`
- `npm test`
- `npm run check`
- `npm run test:websocket`
- `npm run qa:design-ui -- --targets trade-route --manage-local-app`
- `npm run browser:cleanup`

## Context and Orientation

The footer surface lives in `src/hyperopen/views/footer_view.cljs`. It renders the left-side connection trigger through `src/hyperopen/views/footer/connection_meter.cljs` and renders the popover through `src/hyperopen/views/footer/diagnostics_drawer.cljs`. The trader-facing diagnostics data is prepared in `src/hyperopen/websocket/diagnostics/view_model.cljs`; the trigger meter values come from `src/hyperopen/websocket/diagnostics/policy.cljs`. Styling for the redesign is in `src/styles/main.css` under `.hx-*` selectors.

The prior implementation renders the popover after the entire footer row and positions `.hx-pop-layer` with `right: 0`, so the popover anchors near the right side of the footer even when the trigger is on the left. The trigger renders latency whenever the meter has `:latency-label`, regardless of `:websocket-ui :show-surface-freshness-cues?`. The developer details body renders all timeline rows and all stream rows from the view model, which can make the popover much taller than the compact mock states.

## Plan of Work

First, update tests so they fail for the defects. Footer view tests should assert that an open diagnostics layer is rendered next to the connection trigger, that latency is hidden until `show-surface-freshness-cues?` is true, and that the developer preview limits the number of rendered recent events and streams. The Playwright smoke should assert that the popover appears near the left-side Online trigger at desktop width.

Second, move the diagnostics render call into the same left footer container as the trigger so CSS can anchor the popover above the trigger. Adjust `.hx-pop-layer`, `.hx-pop`, and `.hx-pop::after` so the 380px card is left-aligned with the trigger and still points down at it. The existing fixed transparent backdrop still handles outside click.

Third, gate the trigger latency by adding a show-freshness boolean to the connection-meter model and only rendering `.hx-trigger-latency` when it is enabled. This does not change the health scoring or websocket runtime.

Fourth, make developer details a compact preview by limiting recent events and streams in the visible view-model. The Copy diagnostics payload remains unchanged and still contains full redacted support data, including fields intentionally removed from visible UI.

## Concrete Steps

Run commands from `/Users/barry/.codex/worktrees/a16b/hyperopen`.

Write failing tests first:

    npm test -- --grep diagnostics

This project test runner does not support a narrow grep flag, so if that command is not supported use the normal targeted CLJS compile/test command already used by `npm test`, then run the full `npm test` after implementation.

After implementation, run:

    npm run css:build
    npx shadow-cljs --force-spawn compile app
    npx playwright test tools/playwright/test/connection-diagnostics.spec.mjs --grep "connection diagnostics" --workers=1
    npm run check
    npm test
    npm run test:websocket
    npm run qa:design-ui -- --targets trade-route --manage-local-app
    npm run browser:cleanup

## Validation and Acceptance

Acceptance is met when the footer Online trigger opens the diagnostics card over the left trigger area, not the right footer links; the trigger does not show `· Nms` while Show freshness labels is off; enabling Show freshness labels restores the inline latency; and the developer details section stays a compact preview by showing only a small number of recent events and streams while Copy diagnostics continues to include the full redacted JSON payload.

Tests must cover the three regressions before production changes. Browser evidence must include the focused Playwright diagnostics smoke. The required repo gates are `npm run check`, `npm test`, and `npm run test:websocket`.

## Idempotence and Recovery

The edits are local source, style, test, and plan changes. Re-running the validation commands is safe. If browser inspection leaves sessions behind, run `npm run browser:cleanup`. If a test exposes that live websocket health overwrites seeded degraded browser state, keep degraded-state assertions in deterministic CLJS view tests and use Playwright for layout and interaction assertions.

## Artifacts and Notes

The user supplied screenshots showing the desired compact card: the popover should sit above the Online trigger and the developer details section should present only a small preview. The full support payload belongs in Copy diagnostics rather than visible UI.
