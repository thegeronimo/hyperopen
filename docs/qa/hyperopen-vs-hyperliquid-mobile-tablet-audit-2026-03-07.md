# HyperOpen vs Hyperliquid Mobile/Tablet Audit (2026-03-07)

## Scope

This audit compares HyperOpen and Hyperliquid on smaller viewports for:

- `/trade`
- `/portfolio`
- `/vaults`

Viewport presets used:

- Phone: `390x844`
- Tablet: `1024x1366`

Evidence sources:

- Browser-inspection compare artifacts:
  - Trade phone: `/hyperopen/tmp/browser-inspection/compare-2026-03-07T23-16-09-271Z-a720af0d/`
  - Trade tablet: `/hyperopen/tmp/browser-inspection/compare-2026-03-07T23-17-36-956Z-77497916/`
  - Portfolio phone: `/hyperopen/tmp/browser-inspection/compare-2026-03-07T23-16-40-028Z-87df6814/`
  - Portfolio tablet: `/hyperopen/tmp/browser-inspection/compare-2026-03-07T23-17-56-603Z-296d9ac9/`
  - Vaults phone: `/hyperopen/tmp/browser-inspection/compare-2026-03-07T23-19-25-879Z-03816426/`
  - Vaults tablet: `/hyperopen/tmp/browser-inspection/compare-2026-03-07T23-19-25-906Z-5c4a7e12/`
- HyperOpen layout/source review:
  - `/hyperopen/src/hyperopen/views/header_view.cljs`
  - `/hyperopen/src/hyperopen/views/trade_view.cljs`
  - `/hyperopen/src/hyperopen/views/portfolio_view.cljs`
  - `/hyperopen/src/hyperopen/views/vaults/list_view.cljs`
  - `/hyperopen/src/hyperopen/views/app_view.cljs`

Note: the first vaults compare pass was contaminated by concurrent session reuse and is intentionally excluded. The artifact directories listed above are the corrected runs.

## Executive Summary

HyperOpen and Hyperliquid are closest on `/vaults`, moderately different on `/portfolio`, and furthest apart on `/trade`.

The general pattern is:

- Hyperliquid compresses more aggressively on smaller viewports. It uses denser tabbed surfaces, abbreviates action labels earlier, and reaches multi-column tablet layouts sooner.
- HyperOpen preserves more of its desktop shell and exposes more raw surface area on smaller screens. That gives users more information and controls at once, but also creates longer scroll depth and more visual competition.
- The biggest structural gap is `/portfolio` on tablet. Hyperliquid already uses a multi-column summary row at `1024px`, while HyperOpen stays single-column until `xl`.

## Trade Page

### Phone (`390x844`)

Visual differences:

- Hyperliquid’s phone trade screen is chart-first. The capture shows `Chart`, `Order Book`, and `Trades` tabs near the top, with account tabs (`Balances`, `Positions`, `Open Orders`, `TWAP`, `Trade History`) still visible in the same phone viewport.
- HyperOpen’s phone trade experience is less compressed. The page keeps the full app header shell and the trade screen remains a long vertical stack rather than a tabbed compact surface.
- HyperOpen also retains more top-shell chrome on phone. Below `md`, `/hyperopen/src/hyperopen/views/header_view.cljs` hides the main nav but still keeps brand, spectate trigger, wallet control, utility icons, and a hamburger button. Hyperliquid’s phone capture is much closer to content-first.

Functional differences:

- Hyperliquid exposes switching between chart, orderbook, trades, and account-history views directly in the phone layout.
- HyperOpen exposes the full ticket controls immediately (`Market/Limit/Pro`, `Buy / Long`, `Sell / Short`, `Price`, `Size`, `Reduce Only`, `TIF`, `Take Profit / Stop Loss`, `Place Order`), but does not compress trade surfaces into phone-specific tabs. The page composition in `/hyperopen/src/hyperopen/views/trade_view.cljs` remains chart -> orderbook -> order entry -> account tables in one column below `lg`.
- This means HyperOpen phone prioritizes always-available ticket inputs, while Hyperliquid phone prioritizes compressed market-state navigation.

### Tablet (`1024x1366`)

Visual differences:

- Hyperliquid keeps a desktop-like top row on tablet. The evidence shows chart content on the left, order-entry controls visible in a right rail, and account-table content directly below the chart without first collapsing into a single-column stack.
- HyperOpen’s `lg` layout is a hybrid, not a full desktop carry-over. At `1024px`, `/hyperopen/src/hyperopen/views/trade_view.cljs` renders:
  - chart in the left column
  - orderbook in a fixed `280px` right column
  - order ticket as a full-width row underneath
  - account tables as another full-width row underneath
- The practical result is that HyperOpen’s ticket drops below the chart on tablet, while Hyperliquid keeps ticket controls visible alongside the chart.

Functional differences:

- Hyperliquid keeps order entry reachable without scrolling past the chart on tablet.
- HyperOpen requires more vertical travel before the full ticket and account tabs are available, because its tablet layout still stacks the order-entry and account areas below the chart/orderbook row.

## Portfolio Page

### Phone (`390x844`)

Visual differences:

- Hyperliquid keeps the first KPI pair side by side even on phone. In the phone capture, `View Volume` and `View Fee Schedule` share the same vertical band, which matches a two-card row.
- HyperOpen stacks those same metric cards vertically on phone. `/hyperopen/src/hyperopen/views/portfolio_view.cljs` defines the KPI block as `grid-cols-1` until `md`, so `14 Day Volume` and `Fees` become a vertical stack.
- Hyperliquid abbreviates action labels early (`PM`, `Perp Spot`, `EVM Core`) and fits the action row into three wrapped lines. HyperOpen keeps longer labels (`Perps ↔ Spot`, `EVM ↔ Core`, `Portfolio Margin`) and spills into an extra wrapped line on phone.
- HyperOpen also keeps more persistent shell chrome at the top because of the branded/wallet header behavior noted above.

Functional differences:

- Hyperliquid exposes the reference account-tab set on phone, including `Interest` and `Deposits and Withdrawals`.
- HyperOpen diverges by adding extra analytics surfaces that Hyperliquid does not expose in the same way:
  - `Returns` tab in the chart card
  - benchmark search
  - `Performance Metrics` account tab
- HyperOpen is also missing exact tab parity with Hyperliquid’s `Interest` and `Deposits and Withdrawals` surfaces in the visible portfolio tab set.

### Tablet (`1024x1366`)

Visual differences:

- Hyperliquid promotes the summary region into a true multi-column layout by tablet width. The capture shows `14 Day Volume`, the account summary card (`Perps + Spot + Vaults`, `30D`), and the `Account Value / PNL` chart sharing the upper portion of the page side by side.
- HyperOpen does not. `/hyperopen/src/hyperopen/views/portfolio_view.cljs` keeps the summary grid at `grid-cols-1` until `xl`, so at `1024px` the metric cards, summary card, and chart still stack vertically.
- Hyperliquid also fits almost the entire actions row onto one horizontal band at tablet width, while HyperOpen still wraps earlier.

Functional differences:

- Hyperliquid keeps the portfolio table closer to the top of the page because the summary surfaces are already arranged horizontally by tablet width.
- HyperOpen’s tablet users have to scroll through the full stacked KPI card block, then the summary card, then the chart before reaching the account-table region.
- HyperOpen’s richer analytics are still present on tablet (`Returns`, benchmark search, `Performance Metrics`), but that additional functionality comes with more vertical depth than Hyperliquid’s tablet composition.

Additional data/content difference:

- HyperOpen’s summary surface explicitly includes `Vault Equity` and `Staking Account`, which are not surfaced the same way in the captured Hyperliquid portfolio summary.

## Vaults Page

### Phone (`390x844`)

Visual differences:

- This is the closest of the three pages structurally. Both products lead with:
  - vault TVL hero
  - vault search
  - filter/range controls
  - `Protocol Vaults`
  - `User Vaults`
- Hyperliquid’s phone surface is denser and more minimal. HyperOpen adds heavier page chrome, a branded hero treatment, and a disabled `Establish Connection` CTA at the top.
- HyperOpen’s phone rows are clearly card-based. The local capture shows rounded cards with a 2-column metric grid (`APR`, `TVL`, `Your Deposit`, `Age`).
- Hyperliquid’s phone capture is closer to a dense responsive list/table idiom, with column labels and row data staying visually closer to the desktop table model.

Functional differences:

- HyperOpen exposes labeled dropdown shells for `Filter` and `Range`. Hyperliquid’s controls read more like compact inline chips.
- HyperOpen adds the non-functional `Establish Connection` button; Hyperliquid does not use that CTA on the captured page.
- Because HyperOpen swaps to card rows on phone, it loses some of the table-like continuity that Hyperliquid keeps on its mobile vault list.

### Tablet (`1024x1366`)

Visual differences:

- Both products converge back to two wide table sections on tablet, with the same essential columns:
  - `Vault`
  - `Leader`
  - `APR`
  - `TVL`
  - `Your Deposit`
  - `Age`
  - `Snapshot`
- HyperOpen still adds more branded treatment at the page level:
  - stronger hero background
  - prominent `Vaults` heading
  - disabled `Establish Connection` CTA
  - explicit dropdown-styled control shells
- Hyperliquid’s tablet page is visually flatter and denser.

Functional differences:

- HyperOpen’s headers are interactive sort controls in the table surface, and the `User Vaults` section includes local pagination/page-size controls.
- Hyperliquid keeps the same broad information architecture, but HyperOpen’s table tooling is more overtly interactive and more obviously “app-shell styled.”

## Cross-Page Findings

1. HyperOpen is carrying more desktop structure into smaller viewports than Hyperliquid.
   On `/trade`, this creates the most friction because it turns phone and tablet layouts into longer stacked flows rather than compressed trading workspaces.

2. HyperOpen’s tablet breakpoint strategy is especially late on `/portfolio`.
   Hyperliquid is already multi-column at `1024px`; HyperOpen waits until `xl`, so the page reads much taller and less information-dense on tablets.

3. HyperOpen’s smaller-view shell is heavier.
   The persistent brand/wallet/spectate/utility header takes more vertical space than Hyperliquid’s smaller-view shell, especially on phone.

4. `/vaults` is closest to reference parity.
   The main remaining differences there are presentation style, CTA/chrome, and mobile card-vs-dense-list treatment, not core information architecture.

## Most Important Follow-Ups

If the goal is stronger small-viewport parity with Hyperliquid, the highest-leverage changes are:

1. Rework `/portfolio` so tablet width (`1024px`) uses a multi-column summary layout instead of waiting for `xl`.
2. Decide whether `/trade` on phone/tablet should stay “all surfaces live” or move toward Hyperliquid’s tabbed compression model.
3. Simplify smaller-view shell chrome, especially on phone, so content starts higher on the screen.
4. Decide whether the extra HyperOpen analytics on `/portfolio` (`Returns`, benchmarks, `Performance Metrics`) are intentionally additive or should be separated from the parity path.
