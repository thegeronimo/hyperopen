# Order Form TP/SL Input Parity Regression Fix

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

After this change, the order form `Take Profit / Stop Loss` rows no longer overlap labels and values in compact widths, TP/SL values remain readable, and Gain/Loss no longer drifts from `1.00` to `0.99` due floating-point artifacts. A user can verify this by enabling TP/SL in the trade form, typing `1` in Gain and Loss, and observing stable values and non-overlapping TP/SL inputs.

## Progress

- [x] (2026-02-25 01:00Z) Captured live Hyperliquid trade UI with browser inspection (`/tmp/browser-inspection/inspect-2026-02-25T00-59-52-278Z-944d6f8e`) and verified runtime surface structure.
- [x] (2026-02-25 01:05Z) Started interactive browser session and navigated to live trade page to inspect live DOM/state behavior (`sessionId: sess-1771981248386-5c11ac`).
- [x] (2026-02-25 01:10Z) Retrieved and de-minified current live bundle (`main.7a1533d2.js`) and identified TP/SL row component and conversion logic (`module 32368`, function export `y`).
- [x] (2026-02-25 01:20Z) Implemented dedicated compact TP/SL input primitive with inline label/value layout parity pattern and migrated TP/SL rows to that primitive.
- [x] (2026-02-25 01:23Z) Patched TP/SL offset rounding to guard against floating precision regression (`0.999999... -> 1.00` behavior).
- [x] (2026-02-25 01:27Z) Updated tests for TP/SL row structure and policy rounding behavior.
- [x] (2026-02-25 01:36Z) Ran required gates (`npm run check`, `npm test`, `npm run test:websocket`) and recorded outcomes (all passing when executed sequentially).
- [x] (2026-02-25 01:19Z) Performed parity correction pass for TP/SL row geometry: removed nested native input chrome, aligned compact input metrics to Hyperliquid (`39px` container, `31px` inner input, `12px` font, `5/12/5/10` padding), and removed forced label truncation.
- [x] (2026-02-25 01:20Z) Verified local runtime computed styles through browser inspection eval (`http://localhost:8080/trade`) for `TP Price`, `Gain`, `SL Price`, and `Loss` fields; all matched targeted metrics and confirmed `borderTopWidth: 0px` on the inner input.
- [x] (2026-02-25 01:23Z) Added compact short-label fallback for populated TP/SL price rows (`TP`/`SL`) to increase visible numeric width in narrow sidebar cells while preserving full `aria-label` accessibility strings.
- [x] (2026-02-25 01:25Z) Re-ran required gates after short-label pass (`npm run check`, `npm test`, `npm run test:websocket`), with websocket suite passing on immediate rerun after a transient first-run failure.
- [x] (2026-02-25 01:34Z) Reduced TP/SL trigger price precision generated from Gain/Loss conversion from 8 decimals to 5 decimals to align with Hyperliquid compact readability behavior; added policy tests for 5-decimal output.
- [x] (2026-02-25 01:38Z) Re-ran required gates for the precision update (`npm run check`, `npm test`, `npm run test:websocket`), all passing in final run.
- [x] (2026-02-25 01:43Z) Applied user-provided Hyperliquid computed style evidence for Gain input regular sizing (`height: 33px`) and updated compact TP/SL row height from `39px` to `33px`.
- [x] (2026-02-25 01:47Z) Re-ran required gates after regular-size update (`npm run check`, `npm test`, `npm run test:websocket`), all passing.
- [x] (2026-02-25 01:50Z) Used browser inspection on live Hyperliquid with user-provided class anchors (`sc-dIfARi jXCepL`) and confirmed shared input shell metrics (`height 33px`, `padding 5px 12px 5px 10px`, `gap 6px`, `border 1px rgb(39,48,53)`, `border-radius 8px`).
- [x] (2026-02-25 01:54Z) Applied additional parity refinements from Hyperliquid component shape: TP/SL row grid gaps to `10px` and enabled-input cursor to `default`; reran required gates successfully.

## Surprises & Discoveries

- Observation: Live Hyperliquid TP/SL controls are not visible in anonymous session state on first render, so direct DOM inspection of rendered TP/SL rows is incomplete without state transitions.
  Evidence: Interactive DOM scan on `https://app.hyperliquid.xyz/trade` found `TIF` and `Price`, but no rendered `tp/sl` controls before authenticated/expanded state.

- Observation: The exact TP/SL row and conversion implementation is still observable from the live production bundle, including styling and value conversion semantics.
  Evidence: `/tmp/hyperliquid-live-main.semicolons.js` module `32368` exposes input layout and conversion:
  - Grid layout for TP/SL paired rows (`grid-template-columns: 1fr 1fr` under `keepHalfProportion`/mobile condition).
  - Offset conversion function floors to 2 decimals via `Math.floor(100*t)/100`.
  - Input composition uses inline left label and right input/accessory components, not an absolute overlaid label.

- Observation: Our regression came from using absolute-positioned labels in half-width fields with aggressive left/right padding constraints.
  Evidence: Local `row-input` used `order-row-input-label` overlay + fixed `pl-*`/`pr-*` classes, causing overlap and clipping under narrow widths.

- Observation: Running `check` and websocket tests in parallel produced a transient websocket failure that did not reproduce when run sequentially.
  Evidence: `hyperopen.websocket.application.runtime-engine-test` failed under parallel gate execution, then passed in a clean sequential `npm run test:websocket`.

- Observation: `npm run test:websocket` can still fail transiently in `runtime_engine_test` even in isolated runs, then pass immediately on rerun without source changes.
  Evidence: First websocket run during this pass failed with missing recorded effects in `start-engine-records-runtime-messages-and-effects-test`; second consecutive websocket run passed (147 tests, 643 assertions, 0 failures).

- Observation: The “box inside input” regression was caused by missing border reset on the nested `<input>` in the compact TP/SL primitive, which surfaced user-agent text input chrome inside the bordered container.
  Evidence: Prior class list for compact inner input did not include `border-0`/`p-0`/fixed input height; runtime screenshot showed nested boxed value area.

- Observation: Hyperliquid order-form Gain/Loss input in the user-provided devtools dump uses the regular-height variant (`height: 33px`, `padding: 5px 12px 5px 10px`) rather than medium-height (`39px`).
  Evidence: User-captured computed styles include `height: 33px`, `border-radius: 8px`, `padding: 5px 12px 5px 10px`.

- Observation: In anonymous live Hyperliquid state, TP/SL controls are not always present in DOM, but the shared input shell class (`sc-dIfARi jXCepL`) remains inspectable on adjacent controls and matches the same geometry used by TP/SL input primitives in bundle definitions.
  Evidence: Browser inspection eval located `sc-dIfARi jXCepL` nodes with `height: 33px`, `padding: 5px 12px 5px 10px`, `gap: 6px`; bundle module `32368` confirms TP/SL row grid `gap: 10px`.

## Decision Log

- Decision: Keep baseline `row-input` behavior for non-TP/SL rows and add a dedicated compact TP/SL primitive.
  Rationale: Main price/size rows already have styling contract tests and were not regressed; TP/SL needs a different layout strategy in narrow paired cells.
  Date/Author: 2026-02-25 / Codex

- Decision: Use inline flex label + input structure for TP/SL rows to match Hyperliquid’s composition pattern.
  Rationale: This removes label/value overlap risk and mirrors live bundle behavior more closely than absolute overlays.
  Date/Author: 2026-02-25 / Codex

- Decision: Add epsilon-adjusted floor rounding in TP/SL policy.
  Rationale: Preserve existing floor-to-2-decimals semantics while eliminating common float artifact drift (`0.999999...` becoming `0.99`).
  Date/Author: 2026-02-25 / Codex

- Decision: Match Hyperliquid compact input geometry exactly and use non-placeholder inline labels.
  Rationale: The fixed dimensions and border reset remove nested input chrome and improve visible numeric width; placeholders are unnecessary when inline labels are always shown.
  Date/Author: 2026-02-25 / Codex

- Decision: Format TP/SL trigger prices from offset input to 5 decimals instead of 8.
  Rationale: Long trigger strings were clipping in compact width and diverged from Hyperliquid's effective readability; 5 decimals preserves useful precision while improving fit.
  Date/Author: 2026-02-25 / Codex

- Decision: Use regular compact row height (`33px`) for TP/SL inputs in the order form.
  Rationale: Aligns with direct Hyperliquid computed style evidence for Gain/Loss fields and improves compact-density parity.
  Date/Author: 2026-02-25 / Codex

- Decision: Match Hyperliquid row-level spacing (`gap: 10px`) and default cursor behavior for enabled compact TP/SL inputs.
  Rationale: Improves visual/interaction parity at row composition level and aligns with observed style system behavior (`cursor: default` for enabled input shell).
  Date/Author: 2026-02-25 / Codex

## Outcomes & Retrospective

The TP/SL UI now follows a compact inline layout that avoids overlapping text in constrained widths and better matches Hyperliquid’s live component structure, including regular-height `33px` density for order-form rows and five-decimal trigger precision for compact readability. Offset rounding remains floor-based but no longer regresses near integer boundaries due binary floating representation. Required repo gates pass after implementation when run sequentially. Remaining parity work beyond this plan is optional fine-tuning of typography spacing against exact pixel-level snapshots.

## Context and Orientation

The trade form renders in `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`. TP/SL rows are assembled in `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs` and shared input primitives are in `/hyperopen/src/hyperopen/views/trade/order_form_component_primitives.cljs`. Gain/Loss conversion math is centralized in `/hyperopen/src/hyperopen/trading/order_form_tpsl_policy.cljs`. Tests for section rendering and primitive contracts live under `/hyperopen/test/hyperopen/views/trade/`, and TP/SL policy tests are in `/hyperopen/test/hyperopen/trading/order_form_tpsl_policy_test.cljs`.

In this repository, “TP/SL parity” for this task means matching two concrete behaviors from Hyperliquid: inline compact row composition for price + gain/loss fields and stable gain/loss conversion display behavior under user input.

## Plan of Work

First, keep the existing generalized `row-input` primitive intact for all existing order form rows. Add a new `compact-row-input` primitive specialized for compact paired inputs, using a flex container with inline label, flex-growing input, and optional right accessory. Then switch TP/SL rows (`TP Price`, `Gain`, `SL Price`, `Loss`) in `order_form_component_sections.cljs` to this primitive.

Second, patch `order_form_tpsl_policy.cljs` rounding so floor semantics remain but include a tiny epsilon before flooring to prevent binary precision regressions near whole-cent boundaries.

Finally, update tests to assert the new TP/SL compact input shape and add a precision-focused policy test. Then run required validation gates.

## Concrete Steps

From `/Users//projects/hyperopen`:

1. Capture live Hyperliquid evidence.
   - `node tools/browser-inspection/src/cli.mjs inspect --url https://app.hyperliquid.xyz/trade --target hyperliquid --viewports desktop`
   - `node tools/browser-inspection/src/cli.mjs session start`
   - `node tools/browser-inspection/src/cli.mjs navigate --session-id <id> --url https://app.hyperliquid.xyz/trade --viewport desktop`
   - `node tools/browser-inspection/src/cli.mjs eval --session-id <id> --expression "..."`

2. Pull live bundle and extract TP/SL component behavior.
   - `curl -L 'https://app.hyperliquid.xyz/static/js/main.7a1533d2.js' -o tmp/hyperliquid-live-main.js`
   - `perl -0pe 's/;/;\n/g' tmp/hyperliquid-live-main.js > tmp/hyperliquid-live-main.semicolons.js`
   - `rg -n "ntlPctAttributes|keepHalfProportion|tp\.price|sl\.price|gain|loss" tmp/hyperliquid-live-main.semicolons.js`

3. Implement compact TP/SL input layout and rounding patch.
   - Edit `/hyperopen/src/hyperopen/views/trade/order_form_component_primitives.cljs`
   - Edit `/hyperopen/src/hyperopen/views/trade/order_form_component_sections.cljs`
   - Edit `/hyperopen/src/hyperopen/trading/order_form_tpsl_policy.cljs`

4. Update tests.
   - Edit `/hyperopen/test/hyperopen/views/trade/order_form_component_primitives_test.cljs`
   - Edit `/hyperopen/test/hyperopen/views/trade/order_form_component_sections_test.cljs`
   - Edit `/hyperopen/test/hyperopen/trading/order_form_tpsl_policy_test.cljs`

5. Run validation gates.
   - `npm run check`
   - `npm test`
   - `npm run test:websocket`

## Validation and Acceptance

Acceptance criteria:

- In TP/SL mode, each TP/SL field shows readable label and value without overlap in compact order form width.
- Gain/Loss inputs do not regress from `1` to `0.99` when values are mathematically equal up to floating-point noise.
- Existing order form rows outside TP/SL retain their styling contracts.
- Required gates pass with zero test failures.

## Idempotence and Recovery

All edits are source-only and safe to reapply. If any TP/SL rendering regression appears, rollback can be scoped to the three touched source files and corresponding tests. Browser-inspection and downloaded bundle artifacts are non-destructive diagnostics under `/tmp`.

## Artifacts and Notes

- Live browser inspection run:
  - `/hyperopen/tmp/browser-inspection/inspect-2026-02-25T00-59-52-278Z-944d6f8e/manifest.json`
  - `/hyperopen/tmp/browser-inspection/inspect-2026-02-25T00-59-52-278Z-944d6f8e/hyperliquid/desktop/screenshot.png`
- Live bundle used for parity inspection:
  - `/hyperopen/tmp/hyperliquid-live-main.js`
  - `/hyperopen/tmp/hyperliquid-live-main.semicolons.js`
- Local runtime verification session:
  - Browser inspection session `sess-1771982297627-a885b1` on `http://localhost:8080/trade`
  - Computed TP/SL metrics captured via `eval`:
    - input: `height 31px`, `line-height 31px`, `font-size 12px`, `border-top-width 0px`
    - container: `height 39px`, `padding 5px 12px 5px 10px`, `gap 6px`, `border-top-width 1px`
- Key parity anchor in bundle:
  - Module `32368` export `y` (TP/SL row input and gain/loss conversion semantics).

## Interfaces and Dependencies

No new dependencies are introduced.

New/updated internal interfaces:

- `hyperopen.views.trade.order-form-component-primitives/compact-row-input`
  - Purpose: compact inline label/value input for paired TP/SL rows.
- `hyperopen.trading.order-form-tpsl-policy/round-floor-2` behavior
  - Purpose: retain floor-to-2-decimals semantics while resisting float precision drift.
- `hyperopen.trading.order-form-tpsl-policy/trigger-price-decimals`
  - Purpose: centralize TP/SL trigger precision used when deriving trigger from Gain/Loss offset input.

Plan revision note: 2026-02-25 01:27Z - Created plan after live Hyperliquid evidence collection and implementation of TP/SL layout + rounding fixes for parity regression.
Plan revision note: 2026-02-25 01:36Z - Updated progress/outcomes with completed validation gates and documented transient parallel-run websocket failure with sequential recovery.
