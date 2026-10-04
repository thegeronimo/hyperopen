# Nightly UI QA Report - 2026-10-01

## Summary

- Overall state: `product-regression`
- Failure classification: `product-regression`
- Novelty: `NEW`
- Run id: `nightly-ui-qa-2026-10-02T02-00-53-949Z-3d3fbc99`
- Scenario bundle state: `product-regression`
- Design review state: `PASS`
- Branch: `main`
- Artifacts: `/Users/barry/projects/hyperopen/tmp/browser-inspection/nightly-ui-qa-2026-10-02T02-00-53-949Z-3d3fbc99`
- Previous nightly: `/Users/barry/projects/hyperopen/tmp/browser-inspection/nightly-ui-qa-2026-09-18T02-01-14-991Z-432e55d0`
- Comparison source: `durable-baseline` (resolved before run-directory retention).
- Report path: `/Users/barry/projects/hyperopen/docs/qa/nightly-ui-report-2026-10-01.md`

## Scenario counts

- pass: 21
- product-regression: 2
- automation-gap: 0
- manual-exception: 0

## Route coverage

- `/portfolio` / `desktop`: attempted 3/3, pass 3, failed 0
- `/portfolio` / `mobile`: attempted 3/3, pass 3, failed 0
- `/trade` / `desktop`: attempted 3/3, pass 3, failed 0
- `/trade` / `mobile`: attempted 3/3, pass 3, failed 0
- `/vaults` / `desktop`: attempted 1/1, pass 1, failed 0
- `/vaults` / `mobile`: attempted 1/1, pass 1, failed 0

## Coverage contract gaps

- None.

## Inspected spectate addresses

- `0x162cc7c861ebd0c06b3d72319201150482518185`
- `0x2ba553d9f990a3b66b03b2dc0d030dfc1c061036`
- `0x4096d3377ae5ade578daae8188804740c8b1da3e`

## New critical/high product regressions

- `asset-selection-eth` / `desktop` / `high`: Step 6 expectation failed: result.desktopPresent expected true, got false
- `trade-funding-tooltip-layering` / `desktop` / `high`: wait_for_eval timed out for trade-funding-tooltip-layering/desktop/wait_for_eval: result.triggerPresent expected true, got false

## New automation gaps

- None.

## Persistent automation gaps

- None.

## Manual exceptions

- None.

## Design Review

- State: `PASS`
- Run id: `design-review-2026-10-02T02-09-54-094Z-905a8b8e`
- Artifacts: `/Users/barry/projects/hyperopen/tmp/browser-inspection/design-review-2026-10-02T02-09-54-094Z-905a8b8e`
- visual-evidence-captured: PASS (0 issue(s))
- native-control: PASS (0 issue(s))
- styling-consistency: PASS (0 issue(s))
- interaction: PASS (0 issue(s))
- layout-regression: PASS (0 issue(s))
- jank-perf: PASS (0 issue(s))

## Filed bd issues

- `hyperopen-2xi5`: Nightly UI regression: asset-selection-eth (desktop) (P2 bug)
- `hyperopen-o803`: Nightly UI regression: trade-funding-tooltip-layering (desktop) (P2 bug)
- Recovery note: the wrapper's initial deduplication lookup failed because the externally managed Dolt server was stopped. The server was started, all issue statuses were checked for matching nightly fingerprints, and the two missing issues were then created with scenario evidence paths.
