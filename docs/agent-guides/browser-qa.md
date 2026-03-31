---
owner: platform
status: canonical
last_reviewed: 2026-03-16
review_cycle_days: 90
source_of_truth: true
---

# Design-System Browser QA Contract

## Purpose and Scope

Use this guide for any UI-facing change under `/hyperopen/src/hyperopen/views/**`, `/hyperopen/src/styles/**`, or any user interaction flow. This is a design-system conformance review, not generic browser QA.

Use this guide with:
- `/hyperopen/docs/FRONTEND.md`
- `/hyperopen/docs/agent-guides/ui-foundations.md`
- `/hyperopen/docs/agent-guides/trading-ui-policy.md`
- `/hyperopen/docs/runbooks/browser-live-inspection.md`

## Required Review Widths

Every design review must inspect exactly these widths:
- `375`
- `768`
- `1280`
- `1440`

The checked-in design-review viewports are `review-375`, `review-768`, `review-1280`, and `review-1440`.

## Required Passes

Before calling a UI change done, mark every pass `PASS`, `FAIL`, or `BLOCKED`.

1. Visual pass
- Compare changed surfaces against governed product patterns and design references.
- Flag spacing, typography, border radius, color, icon size, alignment, clipping, and overflow issues.

2. Native-control pass
- Enumerate all visible form controls on the reviewed route.
- Flag unexpected special native elements such as `select`, `input[type=date]`, `input[type=time]`, `input[type=color]`, `input[type=file]`, and visible browser-default number steppers.
- Treat native controls as failures unless they are explicitly allowlisted in the design-review routing config.

3. Styling-consistency pass
- Inspect computed styles for the reviewed selectors.
- Compare against approved design-system scales and component tokens.
- Flag one-off values and drift in typography, spacing, border width, radius, overflow, or stacking.

4. Interaction pass
- Check hover, focus, active, disabled, loading, open/close, keyboard navigation, and resize behavior.
- Check empty, error, and success states when they are reachable.

5. Layout-regression pass
- Check wrapping, clipping, horizontal overflow, sticky/fixed positioning, scrollbars, and z-index overlap.
- Check both mobile and desktop review widths.

6. Jank/perf pass
- Repeat open/close, resize, focus, and scroll interactions.
- Look for layout shifts, flicker, dropped frames, delayed paints, and unstable measurements.

## High-Risk Trade Route Checks

When the changed surface touches the desktop `/trade` shell, treat the chart row, order book, and lower account tables as one geometry contract and verify all of the following in browser QA:

- Inspect `1280` and `1440` widths with DOM or bounding-box evidence, not screenshots alone.
- Switch the seven standard account tabs:
  - `Balances`
  - `Positions`
  - `Open Orders`
  - `TWAP`
  - `Trade History`
  - `Funding History`
  - `Order History`
- Mark the review `FAIL` if the outer account-panel width or height changes when those tabs change.
- Mark the review `FAIL` if the chart bottom edge or order-book bottom edge stops being flush with the top edge of the lower account panel.
- Mark the review `FAIL` if the lower account panel collapses toward content height as viewport height grows instead of preserving the governed desktop shell proportion.
- When a parity reference exists, record the measured chart, order-book, and account-panel rects in the artifact or report.

## Reference Precedence

When judging visual or styling correctness, use this precedence order:
1. Explicit user-attached screenshots, design refs, or task-local targets.
2. Governed product specs and canonical docs under `/hyperopen/docs/**`.
3. Relevant workbench scenes under `/hyperopen/portfolio/hyperopen/workbench/scenes/**`.
4. Accepted prior QA artifacts under `/hyperopen/docs/qa/**` and `/hyperopen/tmp/browser-inspection/**`.

If no usable reference exists for the visual or styling comparison, mark the affected pass `BLOCKED`. Do not infer design intent from vibes.

## Prompt Contract

Use this structure when an agent performs browser QA:

```text
Review the changed UI as a design-system QA specialist.

<completeness_contract>
- Treat the task as incomplete until all required QA passes are covered.
- Keep an internal checklist of passes, routes, widths, and reachable states.
- Do not finalize with any unaccounted-for pass, viewport, or changed surface.
</completeness_contract>

<tool_persistence_rules>
- Use browser, DOM, style, and trace tools whenever they materially improve correctness.
- Do not stop early if another screenshot, DOM query, style inspection, or interaction trace could reveal issues.
- Retry with a different strategy if a result is partial, missing, or suspiciously narrow.
</tool_persistence_rules>

<verification_loop>
Before finalizing:
- Check correctness: did you inspect every required pass?
- Check grounding: is every claim backed by screenshot, DOM, style, or trace evidence?
- Check formatting: did you report severity, route, viewport, selector, repro, and expected vs observed?
</verification_loop>

<dig_deeper_nudge>
- Do not stop at the first plausible answer.
- Look for second-order issues around responsive breakpoints, hover/focus states, and native widgets.
</dig_deeper_nudge>
```

## Reporting Contract

Every final browser-QA result must return:
1. Summary verdict
2. `PASS` / `FAIL` / `BLOCKED` for each pass
3. Issues with evidence
4. Residual blind spots
5. Explicit browser-session cleanup confirmation when Browser MCP or browser-inspection sessions were used

Every issue record must include:
- severity
- pass
- route
- viewport
- selector or anchor
- repro steps
- artifact path
- observed behavior
- expected behavior
- confidence

Overall state rules:
- `FAIL` if any pass fails
- `BLOCKED` if none fail but any pass is blocked
- `PASS` otherwise

Do not say `looks good` unless every required pass is explicitly accounted for and the final state is `PASS`.

## Blocked vs Manual Exception

Use `BLOCKED` when:
- design references are missing
- required tooling is unavailable
- browser/CDP probes cannot complete
- a route cannot be inspected for automation reasons

Use `manual-exception` only for:
- real wallet extension UI
- hardware-wallet prompts or transport
- browser permission prompts outside the app document
- third-party provider UI outside Hyperopen ownership

Missing references or incomplete tooling are not manual exceptions.

## Command Surface

- `npm run qa:design-ui`
- `npm run qa:pr-ui`
- `npm run qa:nightly-ui -- --allow-non-main`
- `npm run test:browser-qa-evals`
- `npm run browser:cleanup`

Artifact bundles are written under `/hyperopen/tmp/browser-inspection/design-review-*/`.
