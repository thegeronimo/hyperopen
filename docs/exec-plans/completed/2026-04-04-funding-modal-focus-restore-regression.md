# Restore funding modal focus to the opener after close

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, `/hyperopen/AGENTS.md`, `/hyperopen/docs/MULTI_AGENT.md`, and `/hyperopen/docs/BROWSER_TESTING.md`.

Tracked issue: `hyperopen-d2td` ("Fix funding modal focus restore after close").

## Purpose / Big Picture

After this change, a keyboard or assistive-technology user who opens the funding modal from `/trade` or `/portfolio` should land inside the dialog, dismiss it, and return to the exact opener they came from instead of losing focus to the page body. A mouse user will not see a visual difference, but a keyboard user will keep their place in the interaction flow after closing the modal through the close button, a backdrop click, or `Escape`.

The observable success case is simple: focus `Deposit`, `Perps <-> Spot` or `Withdraw`, open the funding modal, close it, and confirm the opener is focused again. Before this fix, live browser smoke showed that the modal opened and closed correctly but left `document.activeElement` on `BODY` across both `/trade` and `/portfolio`.

## Progress

- [x] (2026-04-04 12:12Z) Ran a headed live browser smoke across `/trade` and `/portfolio` for deposit, transfer/send, and withdraw openers with close-button, backdrop, and `Escape` dismissal.
- [x] (2026-04-04 12:18Z) Confirmed the regression shape: all tested openers moved focus into the dialog and all tested close paths dismissed the modal, but focus settled on `BODY` after close instead of the opener.
- [x] (2026-04-04 12:26Z) Re-read the shared focus helper, funding modal shell, and helper tests to locate the likely restore timing bug and any route-specific fallback selector gaps.
- [x] (2026-04-04 12:43Z) Created and claimed follow-up tracker `hyperopen-d2td` because the original funding modal bead `hyperopen-tyy8` is already closed.
- [x] (2026-04-04 12:44Z) Gathered sidecar analysis from a `spec_writer` and a read-only `reviewer` to tighten the scope, acceptance criteria, and likely root cause before implementation.
- [x] (2026-04-04 16:02Z) Confirmed the deeper root cause in real browser coverage: the funding modal close path was not receiving a usable `:replicant.life-cycle/unmount` callback, so fixing retry timing inside unmount alone could never clear the regression.
- [x] (2026-04-04 16:28Z) Moved remembered focus capture into a neutral UI runtime module, added a dedicated `:effects/restore-dialog-focus` close effect, and kept unmount restore as a helper-level fallback.
- [x] (2026-04-04 16:37Z) Widened funding modal restore selectors for `/portfolio` header and card funding actions, and kept the shared helper entry point stable for the modal shell.
- [x] (2026-04-04 16:45Z) Added helper-level regression coverage for the remembered-focus close path, close-action coverage for the new restore effect, and committed Playwright assertions for both `/trade` and `/portfolio`.
- [x] (2026-04-04 17:11Z) Incorporated additional sidecar reviewer findings: exact opener selectors were shadowing cross-surface fallback selectors, the retry guard could steal focus after explicit close, and the committed trade Playwright open path needed deterministic metadata coverage instead of a brittle synthetic click.
- [x] (2026-04-04 17:29Z) Added ordered exact-plus-fallback selector resolution, preserved legitimate focus moves outside the dialog surface, and tightened helper plus shell tests for transfer/send fallback and retry interleavings.
- [x] (2026-04-04 17:54Z) Re-ran the headed smoke matrix and found one remaining live defect: all backdrop closes still dropped focus because the sibling backdrop button was being treated as a legitimate outside focus target.
- [x] (2026-04-04 18:07Z) Fixed backdrop-close restore by treating the dialog surface container, not only the dialog node, as inside the close interaction, then added direct unit coverage for the backdrop-active case.
- [x] (2026-04-04 18:19Z) Re-ran focused tests, deterministic Playwright regressions for `/trade` and `/portfolio`, a headed 27-case smoke matrix across both routes and all three close methods, and the governed `trade-route` plus `portfolio-route` design review.

## Surprises & Discoveries

- Observation: the live browser smoke did not uncover multiple defects. It uncovered one systemic focus-restore regression that reproduced on every tested opener and close path.
  Evidence: `/trade` and `/portfolio` each failed for `Deposit`, `Perps <-> Spot` or `Send`, and `Withdraw`, with close via button, backdrop, and `Escape`, while open state and close state still passed.

- Observation: the current helper retries only when the restore target is not the active element immediately after a focus call. That means it can stop too early if the browser clears focus to `BODY` slightly later in the close sequence.
  Evidence: `/Users/barry/.codex/worktrees/dbbd/hyperopen/src/hyperopen/views/ui/dialog_focus.cljs`

- Observation: the funding modal currently passes restore selectors that only describe the trade-surface funding buttons. Those selectors cannot recover portfolio openers if the original opener node is replaced or unavailable.
  Evidence: `/Users/barry/.codex/worktrees/dbbd/hyperopen/src/hyperopen/views/funding_modal.cljs`

- Observation: the current helper tests are too synchronous to model the live browser failure. They prove immediate restore, but not delayed focus loss back to `BODY`.
  Evidence: `/Users/barry/.codex/worktrees/dbbd/hyperopen/test/hyperopen/views/ui/dialog_focus_test.cljs`

- Observation: the live browser did not populate any restore-attempt debug state from the dialog helper during close, even though mount-time focus behavior still worked.
  Evidence: targeted Playwright instrumentation showed focus returning to `BODY` while the helper's remembered-target restore code on the unmount path never ran.

- Observation: the reliable close hook already existed elsewhere in the product as an action-driven return-focus flow rather than a render-time unmount dependency.
  Evidence: `/Users/barry/.codex/worktrees/dbbd/hyperopen/src/hyperopen/views/header/settings.cljs` and `/Users/barry/.codex/worktrees/dbbd/hyperopen/src/hyperopen/header/actions.cljs`

- Observation: once the first close-effect fix landed, the fallback selectors in the funding modal were still weaker than intended because the helper collapsed "exact opener selector" and "equivalent-opener fallback selector" into one value.
  Evidence: `/Users/barry/.codex/worktrees/dbbd/hyperopen/src/hyperopen/views/ui/dialog_focus.cljs`, `/Users/barry/.codex/worktrees/dbbd/hyperopen/src/hyperopen/ui/dialog_focus_runtime.cljs`, and `/Users/barry/.codex/worktrees/dbbd/hyperopen/src/hyperopen/views/funding_modal.cljs`

- Observation: backdrop close is a distinct focus-restoration case. The backdrop is a sibling focusable control, not a child of the dialog node, so a dialog-only containment check incorrectly treats backdrop focus as "the user moved elsewhere" and aborts restore.
  Evidence: the headed 27-case smoke matrix initially failed all nine backdrop-dismiss cases while button and `Escape` dismissals passed.

- Observation: the committed Playwright tests can assert close-time focus restoration reliably, but the funding opener click path itself is not deterministic enough under Playwright automation on these surfaces to serve as the sole metadata-wiring proof.
  Evidence: both `/trade` and `/portfolio` real-click test attempts failed to open the modal under Playwright, while deterministic debug dispatch with live element bounds/data-role plus unit placeholder tests and live smoke remained stable.

## Decision Log

- Decision: treat this as a new tracked regression under `hyperopen-d2td` instead of reopening `hyperopen-tyy8`.
  Rationale: `hyperopen-tyy8` is already closed and accepted. The live smoke found a follow-up defect that needs its own active plan and tracker history.
  Date/Author: 2026-04-04 / Codex

- Decision: keep the fix centered in the shared dialog focus helper first, and only widen modal restore selectors where route-specific opener recovery actually needs it.
  Rationale: the regression reproduced across all tested funding surfaces, which points to shared restore timing rather than a single opener. Duplicating route-specific fixes would hide the systemic bug.
  Date/Author: 2026-04-04 / Codex

- Decision: stop relying on the modal shell's unmount callback for browser restoration and restore remembered focus from an explicit close effect instead.
  Rationale: browser instrumentation showed the close path was not executing a usable helper unmount callback, so the only reliable close-time boundary was the action/effect pipeline that already owns modal dismissal.
  Date/Author: 2026-04-04 / Codex

- Decision: extract remembered-focus capture and restore into `hyperopen.ui.dialog-focus-runtime` instead of importing a `views` namespace from runtime code.
  Rationale: the runtime and funding effect layers need access to the remembered restore target, but namespace-boundary policy forbids non-view namespaces from depending on `hyperopen.views.*`.
  Date/Author: 2026-04-04 / Codex

- Decision: remember both the exact opener selector and the funding-shell fallback selector instead of collapsing them into one value.
  Rationale: the exact opener selector should win when the same control still exists, but a different equivalent opener on the active route must remain available when the original node disappears.
  Date/Author: 2026-04-04 / Codex

- Decision: treat the dialog surface container, not only the dialog node, as "inside" for restore-settling decisions.
  Rationale: backdrop buttons are siblings of the dialog node during close. They are part of the same modal teardown interaction and must not suppress focus restoration to the opener.
  Date/Author: 2026-04-04 / Codex

- Decision: keep the committed Playwright regressions deterministic by opening the funding modal through `HYPEROPEN_DEBUG.dispatch` with real locator-derived bounds/data-role, and rely on unit coverage plus headed live smoke for the non-deterministic automation click gap.
  Rationale: the fix under test is close-time focus restoration. The deterministic open seam keeps CI coverage stable while the unit/runtime tests still pin placeholder registration and the live smoke verifies the full close matrix.
  Date/Author: 2026-04-04 / Codex

- Decision: keep scope narrow to focus restore and regression coverage.
  Rationale: the smoke confirmed that dialog open, trap, close-button behavior, outside-click close, `Escape`, and labeling are already working. Widening into copy, layout, or schema work would blur the acceptance criteria and risk re-opening unrelated surfaces.
  Date/Author: 2026-04-04 / Codex

## Outcomes & Retrospective

Implementation and validation are complete. The regression is no longer reproducible in the headed browser matrix that originally failed.

The most important retrospective correction is that the first theory was incomplete. The bug was not only "retry timing is too short"; it was "the real browser close path does not run the restore logic through the helper's unmount hook." The second correction came after the first implementation passed button and `Escape`: backdrop dismiss still failed because the backdrop button is a sibling focus target outside the dialog node. The final solution is reliable because it captures exact plus equivalent restore selectors on mount, restores from an explicit close effect, and treats the full dialog surface as part of the active teardown interaction.

Validated outcome:

- Focus returns to the opener on `/trade` for `Deposit`, `Perps <-> Spot`, and `Withdraw` after close via button, backdrop, and `Escape`.
- Focus returns to the opener on `/portfolio` for `Deposit`, `Send`, and `Withdraw`, plus the portfolio funding card actions, after close via button, backdrop, and `Escape`.
- The modal still moves focus inside on open and keeps the existing dialog labeling behavior intact.
- The governed design review passed for `trade-route` and `portfolio-route` at `375`, `768`, `1280`, and `1440`.

## Context and Orientation

The funding modal shell lives in `/Users/barry/.codex/worktrees/dbbd/hyperopen/src/hyperopen/views/funding_modal.cljs`. That file renders both desktop and mobile dialog surfaces and attaches the shared focus helper through `:replicant/on-render`. The completed fix now supplies mode-specific fallback selectors that cover the trade funding buttons plus the portfolio header and card funding actions.

The shared focus helper lives in `/Users/barry/.codex/worktrees/dbbd/hyperopen/src/hyperopen/views/ui/dialog_focus.cljs`. In this repository, a "Replicant on-render helper" is a function that runs DOM behavior at mount, update, and unmount time for a rendered node. This helper captures the previously focused element on mount, traps `Tab` inside the dialog while open, and tries to restore focus on unmount.

The original helper was correct in unit conditions but wrong in the live browser close sequence. It restored focus once, saw success immediately, and stopped retrying. The browser smoke showed that focus could later settle on `BODY`, and the finished runtime now keeps retrying until focus either returns to the opener or legitimately settles outside the dialog surface.

The helper tests are in `/Users/barry/.codex/worktrees/dbbd/hyperopen/test/hyperopen/views/ui/dialog_focus_test.cljs`. The funding accessibility shell tests are in `/Users/barry/.codex/worktrees/dbbd/hyperopen/test/hyperopen/views/funding_modal_accessibility_test.cljs`. The smallest committed browser regression coverage currently lives in `/Users/barry/.codex/worktrees/dbbd/hyperopen/tools/playwright/test/trade-regressions.spec.mjs`.

## Plan of Work

First, update `/Users/barry/.codex/worktrees/dbbd/hyperopen/src/hyperopen/views/ui/dialog_focus.cljs` so restore attempts survive the browser's late focus clearing. The helper should keep retrying across later tasks or frames even if one immediate focus call appears to succeed, and it should treat `BODY` as an unstable post-close landing point rather than a valid restore result. The implementation should remain generic so any dialog using the helper benefits, not only the funding modal.

Second, update `/Users/barry/.codex/worktrees/dbbd/hyperopen/src/hyperopen/views/funding_modal.cljs` so restore-selector fallbacks cover both `/trade` and `/portfolio` openers. The key requirement is that if the original opener element is gone or replaced, the helper can still find the best equivalent opener on the active route.

Third, tighten the tests. `/Users/barry/.codex/worktrees/dbbd/hyperopen/test/hyperopen/views/ui/dialog_focus_test.cljs` should add a case where restore initially succeeds, a later event resets `document.activeElement` to `BODY`, and the helper still converges back on the opener. It should also add a selector-fallback case for a portfolio opener. If needed, `/Users/barry/.codex/worktrees/dbbd/hyperopen/test/hyperopen/views/funding_modal_accessibility_test.cljs` can assert that the funding modal chooses route-compatible restore selectors, but the shared helper test remains the primary coverage.

Fourth, rerun the live smoke after the code and tests are in place. The acceptance bar is not only "no test failures"; it is "the live browser no longer lands on `BODY` after modal close." If headless Playwright can assert the focused opener reliably after the fix, keep the assertion. If not, keep the unit coverage strong and record the live smoke as the authoritative evidence.

## Concrete Steps

All commands should run from `/Users/barry/.codex/worktrees/dbbd/hyperopen`.

Planned validation sequence:

    npm run test:runner:generate
    npx shadow-cljs compile test
    node out/test.js --test=hyperopen.views.ui.dialog-focus-test,hyperopen.views.funding-modal-accessibility-test,hyperopen.views.funding-modal-test
    npx playwright test tools/playwright/test/trade-regressions.spec.mjs --grep "funding modal"
    <headed live smoke for /trade and /portfolio openers and close paths>
    npm run browser:cleanup
    npm run check
    npm test
    npm run test:websocket

Commands already run before implementation:

    headed live browser smoke across /trade and /portfolio, covering deposit, transfer/send, and withdraw openers with close-button, backdrop, and Escape dismissal
    read-only inspection of src/hyperopen/views/ui/dialog_focus.cljs
    read-only inspection of src/hyperopen/views/funding_modal.cljs
    read-only inspection of test/hyperopen/views/ui/dialog_focus_test.cljs
    read-only inspection of test/hyperopen/views/funding_modal_accessibility_test.cljs
    bd create "Fix funding modal focus restore after close" ... --json
    bd update hyperopen-d2td --claim --json

Expected implementation outcome:

- focused helper tests pass with delayed restore coverage
- funding modal tests pass
- the live browser smoke shows the opener focused again after close
- required repo gates stay green

## Validation and Acceptance

Acceptance is behavioral and route-specific.

On `/trade`, a user must be able to focus `Deposit`, `Perps <-> Spot`, or `Withdraw`, open the funding modal, close it with the close button, a click outside the dialog, or `Escape`, and find that the same opener is focused again.

On `/portfolio`, a user must be able to focus `Deposit`, `Send`, or `Withdraw`, open the funding modal, close it through the same three close paths, and find that the same opener is focused again.

The dialog must still focus inside the modal on open and keep `Tab` and `Shift+Tab` trapped while open. The change is incomplete if it fixes close restore by breaking open focus or tab trapping.

Run the focused tests and expect them to pass. Re-run the live browser smoke and confirm the post-close active element is the opener instead of `BODY`. Finish by running `npm run check`, `npm test`, and `npm run test:websocket` and expect all three to pass.

## Idempotence and Recovery

These edits are safe to rerun because they are additive adjustments to one shared helper, one funding modal shell, and their tests. If a test fails partway through, fix the code and rerun the same focused command before broadening to the full gates. Always end browser work with `npm run browser:cleanup` so no stale inspection sessions remain open. Stop the local Dolt server after the tracker work is complete if no further `bd` commands are needed.

## Artifacts and Notes

Important evidence captured so far:

    Smoke outcome before the fix:
      - modal opened from all tested triggers
      - close button, backdrop click, and Escape all closed the modal
      - focus moved into the dialog on open
      - after close, document.activeElement became BODY instead of the opener

    Read-only reviewer findings:
      - restore retries stop too early once a single focus call appears successful
      - funding modal restore selectors only describe trade openers
      - helper tests do not model delayed focus loss back to BODY

## Interfaces and Dependencies

The shared helper entry point remains `hyperopen.views.ui.dialog-focus/dialog-focus-on-render`. The implementation may add internal helpers, but the funding modal should continue consuming this one public helper surface.

The funding modal shell should continue choosing restore behavior by modal mode, but the fallback selector data must describe both trade and portfolio opener surfaces where needed.

Any new tests should continue to live under `/Users/barry/.codex/worktrees/dbbd/hyperopen/test/hyperopen/views/ui/` or `/Users/barry/.codex/worktrees/dbbd/hyperopen/test/hyperopen/views/` and should not introduce a second testing style for DOM behavior.

Revision note: 2026-04-04 12:44Z. Created this active ExecPlan after the headed browser smoke found a live focus-restore regression that the previous funding modal accessibility work did not catch, then linked it to new tracker `hyperopen-d2td`.
