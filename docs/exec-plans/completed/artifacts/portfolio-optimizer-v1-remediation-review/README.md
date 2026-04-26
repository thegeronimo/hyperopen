# Portfolio Optimizer V1 Remediation Review Artifacts

Generated from branch `codex/portfolio-optimizer-v1-remediation` for Phase 9 of the remediation ExecPlan.

## Where To Look First

1. `screenshots/workspace-full-page.png` shows Scenario A with non-zero NAV, signed target weights, visible tracking, and executable perp rows.
2. `screenshots/target-allocation-table.png` shows non-zero signed current-vs-target allocation output.
3. `screenshots/rebalance-preview.png` shows Scenario B with ready perp rows and a blocked spot row carrying `spot-submit-unsupported`.
4. `screenshots/black-litterman-views-editor.png` shows Scenario C with absolute and relative BL views plus prior source/weights.
5. `screenshots/infeasible-constraints.png` shows Scenario D's infeasible constraint explanation and highlighted controls.

## Scenario Payloads

- `scenario-data/scenario-a-perp-executable.json`: positive capital, non-zero target notionals, non-zero quantities, non-zero fees/slippage, all-perp executable preview.
- `scenario-data/scenario-b-mixed-spot-perp.json`: mixed spot/perp preview with perp rows ready and spot blocked.
- `scenario-data/scenario-c-black-litterman.json`: BL return-model scenario with authored absolute and relative views.
- `scenario-data/scenario-d-infeasible-constraints.json`: infeasible target-return/max-weight case with binding-control metadata.

These artifacts intentionally exclude built assets, dependency directories, browser caches, and Playwright reports.
