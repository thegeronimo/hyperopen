# Scale Order Panel Hyperliquid Parity

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document must be maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Active `bd` issue: `hyperopen-cu2`.

## Purpose / Big Picture

Users currently lose important scale-order summary information on shorter desktop viewports because the Hyperopen order rail spends too much vertical space above the estimates block and splits the scale preview into a separate section above the submit button. After this change, the order rail will match Hyperliquid more closely: the submit call-to-action will be denser, the scale preview rows (`Start`, `End`) will live in the same footer summary block as `Order Value`, `Margin Required`, and `Fees`, and the rail will stop reserving unnecessary vertical slack that pushes those values downward.

A user will be able to open the trade rail on a shorter desktop viewport, switch to `Scale`, and still read the start/end summary and cost rows without them being cut off or pushed below the fold as aggressively as before.

## Progress

- [x] (2026-03-11 13:11Z) Read repository guardrails for architecture, frontend runtime, UI foundations, trading UI policy, work tracking, and ExecPlan requirements.
- [x] (2026-03-11 13:16Z) Created and claimed `bd` issue `hyperopen-cu2` for this parity bug.
- [x] (2026-03-11 13:18Z) Installed local Node dependencies with `npm ci` so browser-inspection tooling could run.
- [x] (2026-03-11 13:19Z) Inspected Hyperliquid live desktop trade rail DOM/CSS via browser-inspection and compared it against the provided connected scale-ticket screenshot.
- [x] (2026-03-11 13:24Z) Audited Hyperopen order-form implementation and identified the main divergence points in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`, `/hyperopen/src/hyperopen/views/trade/order_form_type_extensions.cljs`, and `/hyperopen/src/hyperopen/views/trade/order_form_component_primitives.cljs`.
- [x] (2026-03-11 13:34Z) Implemented the order-form layout changes: removed forced rail minimum heights, removed the spacer above the footer region, moved scale preview rows into the footer summary block, and reduced submit CTA height.
- [x] (2026-03-11 13:35Z) Added view tests covering scale-preview footer ordering, compact submit CTA geometry, and removal of the legacy forced min-height classes.
- [x] (2026-03-11 13:37Z) Ran required validation gates successfully: `npm run check`, `npm test`, and `npm run test:websocket`.
- [x] (2026-03-11 13:37Z) Finalized outcomes and moved this ExecPlan to `/hyperopen/docs/exec-plans/completed/`.

## Surprises & Discoveries

- Observation: Hyperliquid’s live unauthenticated desktop rail still exposes the key density choices needed for this task even without the exact connected scale state from the user screenshot.
  Evidence: browser-inspection on 2026-03-11 showed the right rail content container at `266x655`, with the upper content stack using `display:flex`, `flex-direction:column`, `gap:8px`, and `padding:0 10px 10px`; the CTA button measured `246x33`.

- Observation: Hyperopen currently guarantees extra vertical slack in the rail before the footer summary.
  Evidence: `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` sets panel `min-h-[500px] lg:min-h-[560px] xl:min-h-[640px]` and also inserts `[:div {:class [\"flex-1\"]}]` immediately above the scale preview / error / submit / footer block.

- Observation: The scale summary rows are already computed separately from the editable scale inputs, so regrouping them with the footer metrics is a pure view-layout change.
  Evidence: `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs` exposes `:scale-preview-lines`, and `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` currently renders those lines as a standalone block above submit.

- Observation: The lowest-risk parity fix did not require changing the editable scale input section at all; only the bottom composition changed.
  Evidence: final code changes are confined to `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` and `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs`.

## Decision Log

- Decision: Treat the user-provided Hyperliquid screenshot as the source for connected scale-state grouping, and use live Hyperliquid DOM/CSS inspection for density and geometry measurements.
  Rationale: Anonymous live inspection does not expose the exact connected state shown in the screenshot, but it does expose the production panel structure, CTA sizing, and footer spacing that matter for parity.
  Date/Author: 2026-03-11 / Codex

- Decision: Fix the layout by editing the existing order-form view and shared primitives instead of introducing new wrappers or route-specific variants.
  Rationale: The mismatch is caused by spacing, grouping, and CTA sizing in the current rail composition, not by missing data or a separate product flow.
  Date/Author: 2026-03-11 / Codex

- Decision: Move `Start` and `End` into the footer metrics block rather than trying to preserve a dedicated pre-submit scale preview section.
  Rationale: This matches the user’s screenshot and removes one entire vertical section from the rail.
  Date/Author: 2026-03-11 / Codex

- Decision: Keep the scale-editing inputs (`Start price`, `End price`, `Total Orders`, `Size Skew`) in the existing order-type extension section and change only the preview/summary composition.
  Rationale: The user asked for parity on layout density and summary grouping, not a redesign of the editable controls. This minimizes regression risk.
  Date/Author: 2026-03-11 / Codex

## Outcomes & Retrospective

Completed the intended parity pass for the order rail:

- Removed the legacy `min-h-*` rail sizing and the explicit spacer that forced the bottom summary region downward.
- Moved the scale preview rows into the footer summary block so `Start`, `End`, `Order Value`, `Margin Required`, and `Fees` now read as one compact cluster below submit.
- Reduced the submit CTA height from `40px` to `33px`, aligning it much more closely with Hyperliquid’s live CTA geometry.
- Added tests that lock the new footer ordering and prevent the old forced-min-height classes from returning.

Validation results:

- `npm run check` passed.
- `npm test` passed (2253 tests, 11767 assertions).
- `npm run test:websocket` passed (372 tests, 2128 assertions).

This change reduced overall complexity. The rail now relies on normal content flow instead of a combination of fixed minimum heights, an artificial flex spacer, and a separate scale-summary section. That makes the layout easier to reason about and closer to the reference behavior.

## Context and Orientation

The trading order rail is rendered in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`. That file composes the full panel from top controls (margin mode, leverage, style mode), entry mode tabs (`Market`, `Limit`, `Pro`), side toggle, balances, price/size fields, order-type-specific sections, submit CTA, and footer metrics.

The editable order-type-specific sections are defined in `/hyperopen/src/hyperopen/views/trade/order_form_type_extensions.cljs`. For `:scale`, that section currently renders only the four editable fields: `Start price`, `End price`, `Total Orders`, and `Size Skew`.

Shared primitives live in `/hyperopen/src/hyperopen/views/trade/order_form_component_primitives.cljs`. The submit CTA sizing and metric-row visual density are both anchored there and in the main order-form view.

The current scale-summary data is already available in the view model as `:scale-preview-lines`, built in `/hyperopen/src/hyperopen/trading/order_form_application.cljs` and surfaced through `/hyperopen/src/hyperopen/views/trade/order_form_vm.cljs`. No reducer or domain change is needed for this task.

The user-visible problem is specifically about vertical density on the right rail. The two local causes visible in code are:

1. The order panel reserves large minimum heights (`500px`, `560px`, `640px`) even when the content itself is shorter.
2. A `flex-1` spacer is inserted before the submit and summary region, which forces all of the bottom content farther down.

Hyperliquid’s live rail does not behave that way. Its content area is a simple column with `8px` gaps and no reserved `min-height`, its CTA is `33px` tall, and its estimate rows live together in one footer block below the CTA.

## Plan of Work

First, change the order-form composition in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` so the rail no longer reserves extra empty height. Remove the fixed minimum-height classes from the outer panel, remove the explicit `flex-1` spacer, and keep the panel as a normal flex column that grows naturally from its content.

Second, regroup the scale summary. Instead of rendering `Start` and `End` as a standalone block above the submit button, extend the footer metrics helper so it can optionally prepend `Start` and `End` inside the same bottom summary cluster that already renders `Order Value`, `Margin Required`, `Slippage`, and `Fees`.

Third, reduce CTA height and tighten the footer density to better match Hyperliquid. The current submit button is `h-10` (`40px`), while Hyperliquid’s live CTA is `33px`. Shrink the local CTA height and, if needed, slightly tighten the surrounding spacing so the summary rows remain readable without clipping.

Fourth, add or update focused tests around order-form rendering so the new grouping is enforced: scale summary rows should render in the footer block, not above submit, and the CTA class/height should reflect the denser geometry.

## Concrete Steps

From `/Users/barry/.codex/worktrees/a2f9/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`:
   - remove fixed `min-h-*` classes from the outer panel.
   - remove the explicit `flex-1` spacer above the submit/footer region.
   - extend `footer-metrics` to accept scale preview data and render `Start` / `End` in the footer block.
   - move the scale preview call site from above submit into the footer helper arguments.
   - reduce submit CTA height and any directly adjacent spacing needed for parity.

2. If helper changes are clearer there, adjust `/hyperopen/src/hyperopen/views/trade/order_form_component_primitives.cljs` for any shared metric-row spacing or compact class reuse.

3. Update order-form view tests in `/hyperopen/test/hyperopen/views/trade/order_form_view_test.cljs` (and nearby order-form tests if a better fit emerges) so scale preview grouping and CTA density are asserted.

4. Run required validation:
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance is met when all of the following are true:

- On the trade rail, the scale order summary rows `Start` and `End` render inside the same bottom summary component as `Order Value`, `Margin Required`, and `Fees`.
- There is no dedicated `Start` / `End` block above the submit CTA anymore.
- The submit CTA is visibly shorter than before and closer to Hyperliquid’s compact rail CTA.
- The order form no longer reserves the previous large minimum height or spacer-driven empty space that pushed the footer block down.
- Required repository validation gates pass.

## Idempotence and Recovery

These edits are source-level view changes and are safe to reapply. If regrouping `Start` / `End` causes an unexpected regression, the safe rollback is to keep the CTA height reduction and spacer removal while temporarily restoring the previous scale-preview call site above submit. That isolates the grouping change from the density fix.

## Artifacts and Notes

Parity evidence gathered on 2026-03-11:

- Hyperliquid live desktop right-rail wrapper measured `266x655` with no reserved `min-height`.
- Hyperliquid live content stack used `gap: 8px` and `padding: 0 10px 10px`.
- Hyperliquid live CTA measured `246x33`, with `8px` border radius and `12px` font size.
- Hyperliquid live footer block started immediately below the CTA and rendered estimate rows in a single grouped section.

Local divergence gathered from source:

- `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` currently uses `gap-2.5 sm:gap-3`, `min-h-[500px] lg:min-h-[560px] xl:min-h-[640px]`, and a `flex-1` spacer before the bottom stack.
- `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` renders `Start` and `End` above submit when `show-scale-preview?` is true.
- `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` renders the submit CTA with `h-10` (`40px`).

## Interfaces and Dependencies

No public runtime or domain interfaces change. This is a view-layer parity fix in the order ticket.

The existing data contract remains:

- `:order-form-vm/scale-preview-lines` must continue exposing `:start` and `:end`.
- `footer-metrics` in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` will gain an internal optional argument for scale preview data, but no cross-namespace API needs to change.

Revision note (2026-03-11): Initial plan created after live Hyperliquid DOM/CSS inspection, local code audit, and before implementation.
Revision note (2026-03-11): Updated after implementation with completed progress, validation evidence, and final outcomes before archival.
