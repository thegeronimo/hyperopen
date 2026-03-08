# Small-Viewport Hyperliquid Parity Implementation QA (2026-03-08)

## Scope

Compared local HyperOpen against Hyperliquid on:

- `/trade`
- `/portfolio`
- `/vaults`

Viewports used:

- Phone: `390x844`
- Tablet: `1024x1366`

Local compare target during QA:

- `http://localhost:8081`

`8081` was used because an existing local shadow server already occupied `8080` during the QA run.

Validation gates completed before QA:

- `npm run check`
- `npm test`
- `npm run test:websocket`

Evidence files:

- Current compare summary: `/hyperopen/tmp/browser-inspection/mobile-tablet-layout-summaries-2026-03-08.json`
- Current trade phone compare: `/hyperopen/tmp/browser-inspection/compare-2026-03-08T01-16-28-846Z-5258dda8/`
- Current trade tablet compare: `/hyperopen/tmp/browser-inspection/compare-2026-03-08T01-16-49-205Z-f54a8397/`
- Current portfolio phone compare: `/hyperopen/tmp/browser-inspection/compare-2026-03-08T01-17-11-118Z-e124a862/`
- Current portfolio tablet compare: `/hyperopen/tmp/browser-inspection/compare-2026-03-08T01-17-42-114Z-c3c471dc/`
- Current vaults phone compare: `/hyperopen/tmp/browser-inspection/compare-2026-03-08T01-18-13-653Z-00def0fd/`
- Current vaults tablet compare: `/hyperopen/tmp/browser-inspection/compare-2026-03-08T01-18-36-290Z-76d8a724/`
- Prior audit baseline: `/hyperopen/docs/qa/hyperopen-vs-hyperliquid-mobile-tablet-audit-2026-03-07.md`

## Result

The gap narrowed significantly overall.

The clearest wins are `/trade` and `/portfolio`:

- `/trade` phone visual diff ratio dropped from `0.2388` to `0.1283`.
- `/trade` tablet dropped from `0.1263` (`high`) to `0.0743` (`medium`).
- `/portfolio` phone dropped from `0.1689` (`high`) to `0.0940` (`medium`).
- `/portfolio` tablet dropped from `0.0828` (`medium`) to `0.0325` (`low`).

`/vaults` is improved qualitatively, but the March 7 vault baselines are not geometry-compatible with the new QA run, so those ratio deltas should not be treated as apples-to-apples.

## Compare Summary

| Route | Viewport | Baseline | Current | Delta | Verdict |
| --- | --- | --- | --- | --- | --- |
| `/trade` | phone | `0.2388` (`high`) | `0.1283` (`high`) | `-0.1106` | materially narrower |
| `/trade` | tablet | `0.1263` (`high`) | `0.0743` (`medium`) | `-0.0520` | materially narrower |
| `/portfolio` | phone | `0.1689` (`high`) | `0.0940` (`medium`) | `-0.0749` | materially narrower |
| `/portfolio` | tablet | `0.0828` (`medium`) | `0.0325` (`low`) | `-0.0503` | materially narrower |
| `/vaults` | phone | `0.0320` (`low`)* | `0.1166` (`medium`) | not reliable* | current run still shows remaining styling gap |
| `/vaults` | tablet | `0.8004` (`high`)* | `0.0430` (`medium`) | not reliable* | current run is acceptable, but baseline mismatch invalidates direct delta |

\* Vault baseline caveat:

- March 7 vault phone screenshots were `3072x4098`, while the current phone run is `1170x2532`.
- March 7 vault tablet screenshots were `780x1688`, while the current tablet run is `2048x2732`.
- Because the screenshot geometry differs, the vault ratio deltas are not directly comparable across runs.

## Browser QA Notes

### `/trade`

- Phone now matches the intended chart-first direction much better. The local capture exposes explicit smaller-view surface tabs (`Chart`, `Order Book`, `Ticket`, `Account`) instead of one uninterrupted chart -> orderbook -> ticket -> account stack.
- Tablet is materially closer to Hyperliquid than before. The order ticket now stays in a right rail at `1024px`, which removes the biggest structural mismatch from the March 7 audit.
- The remaining gap is mostly chrome and product identity, not the old layout failure. Hyperliquid still has different top-shell structure and data density, so trade phone remains `high` even after the large improvement.

### `/portfolio`

- Phone is visibly denser. KPI cards now stay side by side, action labels are shorter, and the account section starts from a more parity-aligned default tab.
- Tablet is the strongest parity result from this wave. The upper summary region now composes in multiple columns at `1024px`, which was the largest portfolio mismatch in the original audit.
- HyperOpen still carries additive analytics (`Returns`, benchmarks, `Performance Metrics`), but they no longer dominate the first paint the way they did before.

### `/vaults`

- Smaller-view hero chrome is reduced, the disabled top CTA is gone below `xl`, and the control/card density is tighter.
- The current phone and tablet runs are still stylistically different from Hyperliquid. HyperOpen remains more branded and card-like while Hyperliquid stays flatter and denser.
- Because the March 7 vault captures were taken with different screenshot dimensions, the current vault results should be judged from the new artifacts themselves rather than from direct ratio deltas.

## Conclusion

This implementation wave achieved the main goal: the browser compare artifacts show a materially narrower gap on the two pages that mattered most, `/trade` and `/portfolio`, and removed the largest structural mismatches called out in the audit. `/vaults` is improved, but it still has a visible style gap and should be treated as follow-up polish rather than parity-complete.
