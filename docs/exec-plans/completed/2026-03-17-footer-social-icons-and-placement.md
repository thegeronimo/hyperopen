# Refine Footer Social Icon Placement and SVG Treatment

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Linked live work: `hyperopen-gypk` ("Refine footer social icons and link placement").

## Purpose / Big Picture

After this change, the desktop footer will present one clear hierarchy: the live websocket connection meter remains alone on the left, while utility items stay on the right. The Telegram and GitHub marks will no longer appear as mixed placeholder treatments beside the status control. Instead, they will render as consistent inline filled SVG utility marks that visually match the slim footer-row target.

Someone reviewing the footer after this work should see a quieter status area, a unified right-side utility cluster, and no image-based Telegram logo or stroked Lucide GitHub mark in the desktop footer. The new behavior can be verified by rendering the footer scene, running the footer regression tests, and confirming the required repository gates still pass.

## Progress

- [x] (2026-03-18 00:31Z) Reviewed `/hyperopen/AGENTS.md`, `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/WORK_TRACKING.md`, `/hyperopen/docs/FRONTEND.md`, and the footer-specific UI guidance to confirm planning, issue-tracking, and validation requirements.
- [x] (2026-03-18 00:31Z) Created and claimed `hyperopen-gypk` in `bd` for this footer utility-row refinement.
- [x] (2026-03-18 00:34Z) Audited `/hyperopen/src/hyperopen/views/footer_view.cljs`, `/hyperopen/src/hyperopen/views/footer/links.cljs`, `/hyperopen/src/hyperopen/websocket/diagnostics/view_model.cljs`, `/hyperopen/test/hyperopen/views/footer_view_test.cljs`, and the existing Telegram/GitHub assets to pin the current behavior.
- [x] (2026-03-18 00:36Z) Updated `/hyperopen/src/hyperopen/views/footer_view.cljs` so the connection meter is the sole left-cluster desktop affordance and the right-side footer utility row owns the social marks.
- [x] (2026-03-18 00:36Z) Replaced the Telegram `img` and Lucide GitHub outline in `/hyperopen/src/hyperopen/views/footer/links.cljs` with one inline filled-SVG renderer, a compact utility-row divider, and stable `data-role` hooks.
- [x] (2026-03-18 00:36Z) Updated `/hyperopen/test/hyperopen/views/footer_view_test.cljs` to assert the new right-side utility cluster structure, inline SVG `fill="currentColor"` rendering, and removal of the legacy Telegram asset image.
- [x] (2026-03-18 00:37Z) Installed local JavaScript dependencies with `npm ci` because this worktree started without `node_modules/`, which blocked the design-review toolchain.
- [x] (2026-03-18 00:40Z) Ran `npm run qa:design-ui -- --changed-files src/hyperopen/views/footer/links.cljs,src/hyperopen/views/footer_view.cljs --manage-local-app` and recorded artifact bundle `tmp/browser-inspection/design-review-2026-03-18T00-38-26-738Z-9a45179b/`; the overall run failed due unrelated `/trade` and `/vaults` route issues, and follow-up `bd` issues `hyperopen-p40o` and `hyperopen-y9wx` were filed.
- [x] (2026-03-18 00:43Z) Passed `npm test`, `npm run test:websocket`, and `npm run check`; during `check`, moved stale completed plan `/hyperopen/docs/exec-plans/active/2026-03-17-codex-agent-consolidation.md` to `/hyperopen/docs/exec-plans/completed/` to clear a pre-existing docs-lint blocker.
- [x] (2026-03-18 00:46Z) Tightened the Telegram SVG viewBox in `/hyperopen/src/hyperopen/views/footer/links.cljs` after user review flagged that the mark still looked optically smaller than GitHub, then reran `npm test`.

## Surprises & Discoveries

- Observation: the current desktop footer already has a project-level parity note saying the row is slightly too tall and the utility links read too prominently.
  Evidence: `/hyperopen/docs/product-specs/hyperliquid-ui-affordance-parity-spec.md` section `4.8 Footer / Status Row` records `FT-1`.

- Observation: the Telegram and GitHub marks are not part of the footer view model at all. They are hardcoded presentation placeholders in `/hyperopen/src/hyperopen/views/footer/links.cljs`.
  Evidence: `render-social-placeholders` in `/hyperopen/src/hyperopen/views/footer/links.cljs` renders a Telegram `img` and a Lucide GitHub SVG separately from `:footer-links`.

- Observation: the checked-in Telegram asset is a large gradient circle logo, which is visually heavier than the slim utility-row target when used as an `img`.
  Evidence: `/hyperopen/resources/public/telegram_logo.svg` contains a 1000x1000 gradient circle plus a white plane mark.

- Observation: extracting only the Telegram plane path but keeping the original 1000x1000 logo viewBox leaves enough empty canvas that the glyph reads visibly smaller than GitHub at the same `16px` CSS size.
  Evidence: the plane path bounds are approximately `545x453`, while the initial footer SVG used `viewBox "0 0 1000 1000"` and user review immediately flagged the Telegram mark as undersized.

- Observation: this worktree began without `node_modules/`, so both browser QA and the repository validation gates required a local dependency install before they could run.
  Evidence: `test -d node_modules && echo present || echo missing` returned `missing`, and `npm ci` then installed `329` packages successfully.

- Observation: the browser design-review command did run successfully after dependencies were installed, but its overall state was `FAIL` because it surfaced route-level `/trade` overflow and `/vaults` focus/jank findings outside the footer diff.
  Evidence: `tmp/browser-inspection/design-review-2026-03-18T00-38-26-738Z-9a45179b/summary.md` reports trade-route layout/jank issues and vaults-route interaction/layout/jank issues, while the footer-specific visual and styling passes remained `PASS`.

- Observation: the required `npm run check` gate was blocked by a stale pre-existing active ExecPlan unrelated to this footer task.
  Evidence: `npm run check` initially failed `lint:docs` with `active-exec-plan-no-open-bd-issue` and `active-exec-plan-no-unchecked-progress` for `/hyperopen/docs/exec-plans/active/2026-03-17-codex-agent-consolidation.md`.

## Decision Log

- Decision: keep `:footer-links` in `/hyperopen/src/hyperopen/websocket/diagnostics/view_model.cljs` as the text-link data model for now and keep the social marks presentation-owned by `/hyperopen/src/hyperopen/views/footer/links.cljs`.
  Rationale: the user requested a narrow footer styling and placement refinement, and the repository does not currently contain canonical outbound destinations for all footer utility items. Keeping the social marks presentation-owned avoids widening the diagnostics schema for a purely visual/layout change.
  Date/Author: 2026-03-17 / Codex

- Decision: replace both existing social treatments with inline filled SVGs that inherit `currentColor`.
  Rationale: the current footer mixes a raw `img` for Telegram and a stroked Lucide icon for GitHub. Inline filled SVGs make both glyphs share the same optical weight, sizing, and color behavior without relying on rasterized or multi-color assets.
  Date/Author: 2026-03-17 / Codex

- Decision: move the social marks into the right-side utility cluster and leave the connection meter as the sole left-cluster affordance.
  Rationale: the footer should communicate one clear hierarchy: live runtime status on the left and secondary utility/community items on the right.
  Date/Author: 2026-03-17 / Codex

- Decision: derive the Telegram mark from the existing checked-in Telegram asset’s plane path and derive the GitHub mark from the user-provided GitHub SVG path data, instead of introducing a new asset file.
  Rationale: the user explicitly supplied the GitHub mark, the repository already contained usable Telegram path data, and embedding both glyphs directly in `/hyperopen/src/hyperopen/views/footer/links.cljs` kept the change narrow and self-contained.
  Date/Author: 2026-03-17 / Codex

- Decision: fix the Telegram optical-size mismatch by tightening its SVG viewBox instead of giving it a larger CSS class than GitHub.
  Rationale: both utility marks should stay on the same nominal CSS size; the mismatch came from extra whitespace in the Telegram canvas, not from the wrapper dimensions.
  Date/Author: 2026-03-18 / Codex

## Outcomes & Retrospective

The footer refinement completed as planned. The desktop footer now leaves the websocket meter alone on the left, and the right-side utility row owns both the text links and the Telegram/GitHub marks. The Telegram `img` asset and Lucide GitHub outline are gone from the footer render tree; both marks now render as inline filled SVGs that inherit `currentColor` and share one compact treatment.

After initial implementation, user review caught one remaining polish issue: the Telegram plane still looked smaller than GitHub. Tightening the Telegram viewBox to the actual glyph bounds resolved that without introducing one-off icon size classes, and `npm test` still passed after the adjustment.

The required code gates all passed after installing dependencies and clearing one unrelated docs-lint blocker from a stale active ExecPlan. The browser design-review run completed and produced a durable artifact bundle, but the overall state remained `FAIL` because the tool surfaced unrelated `/trade` overflow and `/vaults` focus/layout/jank findings. Those residual items were filed as `hyperopen-p40o` and `hyperopen-y9wx` so the footer task could close without losing the QA evidence.

Overall complexity was reduced slightly. Before this change, the footer mixed two different icon systems and split the desktop utility affordances across both left and right clusters. After the change, the desktop footer has a simpler visual hierarchy and `/hyperopen/src/hyperopen/views/footer/links.cljs` owns one consistent social-mark rendering path instead of juggling an external `img` plus a Lucide transform helper.

## Context and Orientation

The desktop footer entrypoint is `/hyperopen/src/hyperopen/views/footer_view.cljs`. It builds a footer view model, renders the mobile footer navigation on small screens, and renders the desktop footer row on `lg` and up. The desktop row currently contains two visual clusters. The left cluster holds the websocket connection meter and a second hardcoded social-placeholder row. The right cluster renders text utility links from the footer view model.

The left-side social placeholder row lives in `/hyperopen/src/hyperopen/views/footer/links.cljs`. That file currently uses two unrelated rendering paths: Telegram is rendered as `/hyperopen/resources/public/telegram_logo.svg` inside an `img`, while GitHub is rendered from the Lucide icon package as a stroked outline icon. Both are `span` placeholders and not part of the text-link model returned by `/hyperopen/src/hyperopen/websocket/diagnostics/view_model.cljs`.

The regression surface for this footer is `/hyperopen/test/hyperopen/views/footer_view_test.cljs`. Those tests already assert footer utility-link typography and the older Telegram asset-based behavior, so this change must update the tests to prove the new utility-cluster structure and inline SVG rendering.

## Plan of Work

First, update `/hyperopen/src/hyperopen/views/footer_view.cljs` so the desktop left cluster only renders `connection-meter/render`. Remove the extra social-placeholder call from that cluster so the status area becomes visually singular.

Second, refactor `/hyperopen/src/hyperopen/views/footer/links.cljs` into one utility-row renderer. Keep the existing text links, but wrap them in a parent utility cluster that also renders a small divider and a compact social-mark group. Replace the current Telegram `img` and Lucide GitHub icon with inline filled SVGs that use `currentColor`, fixed view boxes, and shared utility-row classes. Use `data-role` attributes on the utility cluster and on both social marks so the render contract is easy to test.

Third, update `/hyperopen/test/hyperopen/views/footer_view_test.cljs` to remove the assertion that the Telegram footer mark is an `img` sourced from `/telegram_logo.svg`. Replace it with assertions that the right-side utility cluster contains both social marks, that each social SVG uses `fill="currentColor"` and `stroke="none"`, and that the old Telegram asset image is absent from the footer tree.

Finally, run the browser design-review command and the repository-required validation gates. If the review surfaces unrelated route-level issues, record the exact artifact bundle, file follow-up `bd` issues, and keep the footer change scoped.

## Concrete Steps

Work from `/hyperopen`.

1. Create this active ExecPlan and link it to `hyperopen-gypk`.
2. Edit `/hyperopen/src/hyperopen/views/footer_view.cljs` and `/hyperopen/src/hyperopen/views/footer/links.cljs` to move the social marks into the utility cluster and switch them to inline filled SVGs.
3. Edit `/hyperopen/test/hyperopen/views/footer_view_test.cljs` to pin the new structure and rendering contract.
4. Run:
   - `npm ci`
   - `npm run qa:design-ui -- --changed-files src/hyperopen/views/footer/links.cljs,src/hyperopen/views/footer_view.cljs --manage-local-app`
   - `npm test`
   - `npm run test:websocket`
   - `npm run check`
5. Update this plan’s living sections, move it to `/hyperopen/docs/exec-plans/completed/`, and close `hyperopen-gypk` when all validations pass.

## Validation and Acceptance

Acceptance is complete only when all of the following are true:

1. The desktop footer left cluster renders the websocket connection meter without the old adjacent social-placeholder row.
2. The desktop footer right cluster renders the existing text utility links plus Telegram and GitHub as one utility row.
3. No footer Telegram `img` sourced from `/telegram_logo.svg` remains in the render tree.
4. Both footer social marks render as inline SVGs that use `fill="currentColor"` and `stroke="none"`.
5. The design-review command accounts for the required passes and widths and any unrelated findings are captured with artifact paths and follow-up `bd` issues.
6. `npm test`, `npm run test:websocket`, and `npm run check` pass.

## Idempotence and Recovery

This change is safe to re-run because it is limited to footer presentation, tests, and this ExecPlan. If the new utility-row layout proves too dense or too loose during review, adjust spacing classes in `/hyperopen/src/hyperopen/views/footer/links.cljs` and rerun the footer tests plus the design-review command. The prior behavior can be restored by reintroducing the old `render-social-placeholders` call in `/hyperopen/src/hyperopen/views/footer_view.cljs` and restoring the previous icon helper implementation in `/hyperopen/src/hyperopen/views/footer/links.cljs`.

## Artifacts and Notes

Important pre-change evidence:

    bd create "Refine footer social icons and link placement" ... --json
    {"id":"hyperopen-gypk", ...}

    bd update hyperopen-gypk --claim --json
    [{"id":"hyperopen-gypk","status":"in_progress",...}]

    sed -n '1,80p' src/hyperopen/views/footer/links.cljs
    ;; shows Telegram rendered as /telegram_logo.svg in an <img>
    ;; and GitHub rendered through lucide/dist/esm/icons/github.js

Important completion evidence:

    npm ci
    added 329 packages, and audited 330 packages in 2s

    npm test
    Ran 2482 tests containing 13025 assertions.
    0 failures, 0 errors.

    npm test
    Ran 2482 tests containing 13025 assertions.
    0 failures, 0 errors.

    npm run test:websocket
    Ran 393 tests containing 2249 assertions.
    0 failures, 0 errors.

    npm run check
    [:test] Build completed. (920 files, 4 compiled, 0 warnings, 3.94s)

    npm run qa:design-ui -- --changed-files src/hyperopen/views/footer/links.cljs,src/hyperopen/views/footer_view.cljs --manage-local-app
    runId: design-review-2026-03-18T00-38-26-738Z-9a45179b
    state: FAIL
    follow-ups filed: hyperopen-p40o, hyperopen-y9wx

## Interfaces and Dependencies

At the end of this work, `/hyperopen/src/hyperopen/views/footer/links.cljs` must continue exporting:

    (defn render [links] ...)

The `links` argument remains the existing vector of text-link maps from `/hyperopen/src/hyperopen/websocket/diagnostics/view_model.cljs`. The social marks remain a presentation concern in `/hyperopen/src/hyperopen/views/footer/links.cljs`, but the renderer must also expose stable `data-role` attributes for:

    footer-utility-links
    footer-social-links
    footer-social-telegram
    footer-social-github

Plan revision note: 2026-03-18 00:34Z - Initial plan created for `hyperopen-gypk` after auditing the footer render structure, current social-mark implementations, and required validation workflow.
Plan revision note: 2026-03-18 00:43Z - Updated progress, discoveries, and retrospective after implementing the footer refactor, running browser QA plus repository gates, and filing follow-up issues for unrelated route-level design-review findings.
Plan revision note: 2026-03-18 00:46Z - Updated the completed plan after tightening the Telegram SVG viewBox to correct an optical-size mismatch spotted during user review, then reran `npm test`.
