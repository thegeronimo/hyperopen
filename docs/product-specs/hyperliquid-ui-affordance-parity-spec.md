---
owner: product
status: supporting
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# HyperOpen Trade UI Affordance Parity Spec (Hyperliquid Reference)

## 1) Objective and Scope

### Objective
Produce a decision-complete implementation spec that defines the UI/UX changes needed to make HyperOpen trade screen affordances match what Hyperliquid users are used to, with strict visual parity as the target.

### In Scope
- Trade screen only, including:
  - Header/navigation
  - Market context strip
  - Chart toolbar and chart container
  - Orderbook/trades panel
  - Order form
  - Bottom account tables
  - Right account equity panel
  - Footer/status row
- Color, typography, spacing, margin/padding, layout, border/radius geometry, and interaction states.
- Mapping every change to concrete implementation locations in current code.

### Out of Scope
- Backend/API changes.
- ClojureScript state shape changes for styling parity.
- Non-trade routes.

### Public Interface / Type Changes
- No backend/API/data-contract changes are required for this deliverable.
- This spec proposes styling interface updates only:
  - New/updated theme tokens in `/hyperopen/tailwind.config.js`.
  - Reusable UI utility/component classes in `/hyperopen/src/styles/main.css`.
  - No changes to core store shape in `/hyperopen/src/hyperopen/core.cljs`.

## 2) Reference Baseline

### Visual References Used
- HyperOpen current trade UI screenshot (provided by user).
- Hyperliquid trade UI screenshot (provided by user).

### Code Baseline (Current Implementation)
- Global token/theme baseline:
  - `/hyperopen/tailwind.config.js:6`
  - `/hyperopen/src/styles/main.css:29`
- Trade layout baseline:
  - `/hyperopen/src/hyperopen/views/trade_view.cljs:16`
  - `/hyperopen/src/hyperopen/views/app_view.cljs:9`

### Baseline Interpretation Rules
- Target is screenshot parity (not live runtime behavior from app.hyperliquid.xyz).
- Preserve HyperOpen brand name/logo content while aligning visual affordances.
- Prioritize parity in desktop layout first; mobile can follow after desktop lock.

## 3) Global Visual System Delta (Tokens)

### 3.1 Current Token System (Observed)
- Current palette centers on:
  - `trading-bg #0b0e11`, `trading-surface #161a1e`, `trading-border #30363d`
  - `trading-green #00d4aa`, `trading-red #ff6b6b`
  - `/hyperopen/tailwind.config.js:8`
- Body uses mono globally:
  - `/hyperopen/src/styles/main.css:20`
- Many components bypass semantic tokens and use raw `gray-*`/`blue-*` classes:
  - `/hyperopen/src/hyperopen/views/trading_chart/core.cljs:21`
  - `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs:263`
  - `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs:8`

### 3.2 Target Token Model (Strict Hyperliquid-Like Affordance)

| Token | Current | Target | Purpose |
|---|---:|---:|---|
| `trading-bg` | `#0b0e11` | `#06121c` | App background |
| `trading-surface` | `#161a1e` | `#0b1824` | Primary panel surface |
| `trading-surface-2` | n/a | `#10202f` | Secondary surface / strips |
| `trading-surface-3` | n/a | `#132638` | Hover/active container surface |
| `trading-border` | `#30363d` | `#213445` | Default border |
| `trading-border-strong` | n/a | `#2b4257` | Higher-contrast divider |
| `trading-text` | `#ffffff` | `#dce8f2` | Primary text |
| `trading-text-secondary` | `#8b949e` | `#8fa5b8` | Secondary text |
| `trading-text-muted` | n/a | `#6f8598` | Tertiary/meta text |
| `trading-accent` | implicit via `primary` | `#56e7d0` | Active tab/link/selection |
| `trading-accent-strong` | n/a | `#26dcc0` | Primary CTA fill |
| `trading-green` | `#00d4aa` | `#22c997` | Bid/profit semantics |
| `trading-red` | `#ff6b6b` | `#e35f78` | Ask/loss semantics |
| `trading-banner-danger` | n/a | `#9b1431` | Jurisdiction/warning strip |

### 3.3 Typography Delta

| Role | Current | Target |
|---|---|---|
| Global body | `font-mono` (`main.css`) | Sans for UI shell; mono only for numeric/market data |
| Nav/tab labels | mixed 14-16 | 13-14px, semibold, tighter hierarchy |
| Metric labels | 11px-12px | 11px uppercase/secondary with consistent tracking |
| Metric values | 13px+ mixed | 13-14px semibold for compact strips; 12-13 tabular in tables/orderbook |

Implementation notes:
- Keep `JetBrains Mono` for numbers and orderbook values.
- Introduce `font-sans` default for shell controls and labels.
- Current global mono source: `/hyperopen/src/styles/main.css:20`.

### 3.4 Spacing and Density Delta

| Primitive | Current Pattern | Target Pattern |
|---|---|---|
| Top bars | `py-3`, `py-2` mixed | Standardized 40-48px block heights |
| Inputs/selects | `px-3 py-2` | 36-38px control height with tighter inner spacing |
| Orderbook rows | `h-6` | Keep 24px row height but improve contrast hierarchy |
| Table rows | `py-3 px-4` | 28-32px visual row rhythm for compact scanability |

### 3.5 Radius and Geometry Delta

| Element Class | Current | Target |
|---|---|---|
| Major panels | mostly `rounded-none` | Keep square panel shells (parity) |
| Controls/buttons | many `rounded-lg` | Reduce to 4-6px radius for consistency |
| Status pills | mixed rounded | Keep pill radius for status badges only |

### 3.6 Border and State Delta
- Border contrast should be lower and cooler than current `base-300` in many areas.
- Active states should rely on cyan underline/fill, not blue fills.
- Focus states need explicit visible rings on keyboard navigation.

### 3.7 Proposed Token and Utility Update Locations
- `/hyperopen/tailwind.config.js`:
  - Update existing `trading-*` values.
  - Add `trading-surface-2`, `trading-surface-3`, `trading-border-strong`, `trading-text-muted`, `trading-accent`, `trading-accent-strong`.
  - Remap DaisyUI `primary/success/error/base-*` to target parity values.
- `/hyperopen/src/styles/main.css`:
  - Add utility classes for:
    - `ui-panel`, `ui-strip`, `ui-tab`, `ui-tab-active`
    - `ui-input`, `ui-select`, `ui-btn`, `ui-btn-primary`
    - `ui-focus` (`focus-visible` ring)
    - `ui-text-metric`, `ui-text-number`

## 4) Component-by-Component Gap Matrix

### 4.1 Header / Navigation Affordances

| Gap ID | Current State (Evidence) | Target Parity State | Concrete Delta | Implementation Location | Priority |
|---|---|---|---|---|---|
| H-1 | Branded wordmark is oversized script (`text-3xl font-splash`) | Compact, cleaner shell hierarchy; nav should dominate scan path | Reduce logo block visual weight; move nav prominence to active tab affordance | `/hyperopen/src/hyperopen/views/header_view.cljs:10` | P1 |
| H-2 | Nav uses loose spacing `space-x-8`; active is cyan text only | Hyperliquid-like compact nav with tighter rhythm and stronger active-state distinction | Change nav spacing to compact rhythm (20-24px), active item with stronger emphasis and consistent underline/marker behavior | `/hyperopen/src/hyperopen/views/header_view.cljs:13` | P0 |
| H-3 | Right controls mix DaisyUI and custom rounded-lg buttons | Unified compact controls with consistent heights, radius, and border contrast | Standardize to 32-36px heights, 4-6px radius, semantic tokenized borders/fills | `/hyperopen/src/hyperopen/views/header_view.cljs:63` | P0 |
| H-4 | Utility icon buttons are large (`w-10 h-10`) and rounded-lg | Smaller dense utility controls matching trade-terminal shell | Reduce to 32px square tokens, tighter horizontal spacing | `/hyperopen/src/hyperopen/views/header_view.cljs:82` | P1 |

### 4.2 Top Market Context Strip

| Gap ID | Current State (Evidence) | Target Parity State | Concrete Delta | Implementation Location | Priority |
|---|---|---|---|---|---|
| M-1 | Metric strip uses 7-col grid with inconsistent hierarchy and dashed underlines | Hyperliquid-like compact information strip with stronger label/value hierarchy | Normalize label/value scale; remove dashed underlines; standardize strip height and separators | `/hyperopen/src/hyperopen/views/active_asset_view.cljs:121` | P0 |
| M-2 | Pair selector row has hover/background but not parity density | Pair block should read as a high-priority market anchor with compact badge geometry | Tighten selector spacing and badge style; align symbol + leverage/market tags style | `/hyperopen/src/hyperopen/views/active_asset_view.cljs:55` | P1 |
| M-3 | No explicit warning strip under nav | Hyperliquid screenshot shows persistent red jurisdiction strip | Add optional warning strip component (feature-flagged) with `trading-banner-danger` | New component + mount near header in `/hyperopen/src/hyperopen/views/app_view.cljs:10` | P2 |

### 4.3 Chart Toolbar and Chart Container

| Gap ID | Current State (Evidence) | Target Parity State | Concrete Delta | Implementation Location | Priority |
|---|---|---|---|---|---|
| C-1 | Toolbar active controls are blue (`bg-blue-600`) with emoji indicator | Hyperliquid-like cyan accent controls and icon-consistent toolbar language | Replace blue accents with `trading-accent`; remove emoji icon; standardize iconography + density | `/hyperopen/src/hyperopen/views/trading_chart/core.cljs:27` | P0 |
| C-2 | Toolbar and chart use `rgb(30, 41, 55)` (too bright/gray relative to target) | Darker navy chart field integrated with shell | Shift chart background/grid/border colors to target tokens | `/hyperopen/src/hyperopen/views/trading_chart/core.cljs:22`, `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs:8` | P0 |
| C-3 | Dropdown menus use mixed gray/blue theme classes | Unified tokenized dropdown surfaces and active states | Replace raw `gray-*` and `blue-*` with semantic classes in chart dropdowns | `/hyperopen/src/hyperopen/views/trading_chart/timeframe_dropdown.cljs:12`, `/hyperopen/src/hyperopen/views/trading_chart/chart_type_dropdown.cljs:73`, `/hyperopen/src/hyperopen/views/trading_chart/indicators_dropdown.cljs:9` | P1 |
| C-4 | Chart legend font stack differs from shell and can appear disconnected | Cohesive legend typography with terminal shell | Keep tabular/mono numbers, align text color and shell hierarchy | `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs:207` | P2 |

### 4.4 Orderbook / Trades Panel

| Gap ID | Current State (Evidence) | Target Parity State | Concrete Delta | Implementation Location | Priority |
|---|---|---|---|---|---|
| O-1 | Tab strip and headers use raw gray palette with cyan border only on active tab | Hyperliquid-like compact top strip with consistent cyan-accent language | Convert tab + header palette to semantic tokens and consistent border weights | `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs:267` | P0 |
| O-2 | Mixed raw classes (`bg-gray-900`, `border-gray-700`) produce inconsistent contrast | Smooth depth contrast between ask/bid rows, headers, and spread center line | Replace gray classes with surface/border token utilities | `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs:263` | P0 |
| O-3 | Dropdown controls are close to parity but use generic rounded and spacing | Crisper compact dropdowns with consistent 6px radius and focus state | Standardize control height, radius, focus ring, hover contrast | `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs:206` | P1 |
| O-4 | Depth bars use fixed alpha and easing not matched to terminal feel | Subtle depth heatmap behind rows with clearer text layering | Tune bar opacity and animation timing to avoid visual jitter | `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs:327` | P2 |

### 4.5 Order Form and Controls

| Gap ID | Current State (Evidence) | Target Parity State | Concrete Delta | Implementation Location | Priority |
|---|---|---|---|---|---|
| F-1 | Form uses generic stacked sections; missing top mode strip (Cross/Leverage/Classic) | Hyperliquid-like control stack with mode context above order type | Add compact top row for margin/leverage/mode affordance | `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs:43` | P0 |
| F-2 | Buy/Sell are small `btn-xs` buttons | Prominent two-state segmented control (`Buy/Long` and `Sell/Short`) | Replace `btn-xs` pair with full-width segmented tabs | `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs:55` | P0 |
| F-3 | Inputs/selects are generic and rounded-lg | Denser, parity-style controls with stronger border and 4-6px radius | Standardize input/select classes, focus, placeholder styling | `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs:8`, `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs:66` | P0 |
| F-4 | Missing size slider / percentage quick-select affordance | Hyperliquid users expect a visual sizing control | Add percent slider control and preset chips | `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` | P1 |
| F-5 | Submit CTA uses generic DaisyUI primary button | Distinct high-emphasis cyan CTA with parity geometry | Update CTA fill, height, typography, and disabled visuals | `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs:149` | P0 |
| F-6 | Missing fee/slippage/notional summary band near CTA | Hyperliquid flow exposes key pre-submit context | Add compact summary rows above CTA | `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs` | P1 |

### 4.6 Bottom Tabbed Account Tables

| Gap ID | Current State (Evidence) | Target Parity State | Concrete Delta | Implementation Location | Priority |
|---|---|---|---|---|---|
| T-1 | Tab nav has larger padding and mixed active backgrounds | Compact terminal tab bar with cyan active indicator and muted inactive tabs | Reduce tab height/density; add consistent active indicator style | `/hyperopen/src/hyperopen/views/account_info_view.cljs:25` | P0 |
| T-2 | Table headers/rows use larger vertical spacing than target | Tighter dense data rows for high-information throughput | Reduce row padding and align all numeric columns as tabular nums | `/hyperopen/src/hyperopen/views/account_info_view.cljs:374`, `/hyperopen/src/hyperopen/views/account_info_view.cljs:485`, `/hyperopen/src/hyperopen/views/account_info_view.cljs:651` | P0 |
| T-3 | Action buttons in-table are DaisyUI ghost buttons, visually heavy | Hyperliquid-like minimal text/action affordances | Convert row actions to subtle text-link style controls | `/hyperopen/src/hyperopen/views/account_info_view.cljs:387`, `/hyperopen/src/hyperopen/views/account_info_view.cljs:643` | P1 |
| T-4 | Open-orders grid headers and body use different density than other tables | Uniform table rhythm across all tabs | Normalize header/body heights and column label tone | `/hyperopen/src/hyperopen/views/account_info_view.cljs:613` | P1 |

### 4.7 Right Account Equity Panel

| Gap ID | Current State (Evidence) | Target Parity State | Concrete Delta | Implementation Location | Priority |
|---|---|---|---|---|---|
| E-1 | Panel is close structurally but typography contrast hierarchy is soft | Hyperliquid-like stronger metric hierarchy and tighter sections | Increase primary metric prominence, compress spacing, sharpen label contrast | `/hyperopen/src/hyperopen/views/account_equity_view.cljs:126` | P0 |
| E-2 | Tooltip affordance uses dashed-underlined labels and large tooltip footprint | Compact tooltip icon/anchor and narrower contextual copy | Replace dashed underline with icon-trigger and reduce tooltip width | `/hyperopen/src/hyperopen/views/account_equity_view.cljs:68` | P2 |
| E-3 | Token usage mixes semantic and hard-coded grays | Full semantic token usage for parity consistency | Replace `gray-*` hardcodes with semantic text/surface tokens | `/hyperopen/src/hyperopen/views/account_equity_view.cljs:59` | P1 |

### 4.8 Footer / Status Row

| Gap ID | Current State (Evidence) | Target Parity State | Concrete Delta | Implementation Location | Priority |
|---|---|---|---|---|---|
| FT-1 | Footer spacing is slightly taller and link hierarchy stronger than target | Slim terminal footer row with subtle utility links | Reduce row height and text prominence; maintain readable contrast | `/hyperopen/src/hyperopen/views/footer_view.cljs:25` | P1 |
| FT-2 | Retry button appears for non-connected states and can overpower status row | Minimal status affordance with optional understated recovery action | Style retry as secondary text action, reduce visual weight | `/hyperopen/src/hyperopen/views/footer_view.cljs:36` | P2 |

### 4.9 Cross-Cutting Primitives (Mandatory)

| Primitive | Current State | Target State | Delta | Location | Priority |
|---|---|---|---|---|---|
| Color palette/semantic roles | Mixed semantic + raw gray/blue classes | Fully semantic palette with cyan-accent terminal shell | Tokenize all view-level classes; remove raw gray/blue from trade surface | `/hyperopen/tailwind.config.js`, `/hyperopen/src/styles/main.css`, multiple views | P0 |
| Typography roles | Global mono baseline | Sans UI shell + mono numeric data | Introduce explicit typography role classes | `/hyperopen/src/styles/main.css:20` | P0 |
| Spacing scale | Inconsistent per-component spacing | Unified compact spacing system | Standardize to 4/6/8/12/16 rhythm | `/hyperopen/src/styles/main.css` + all trade view files | P0 |
| Radius/geometry | Mixed `rounded-lg` and square | Square panels, 4-6px controls | Normalize control radius and panel edges | `/hyperopen/src/hyperopen/views/*` | P1 |
| Border opacity/contrast | Often too bright/heavy (`base-300`) | Cooler, lower-contrast terminal borders | Retune border tokens and divider classes | `/hyperopen/tailwind.config.js`, `/hyperopen/src/styles/main.css` | P0 |
| Hover/active/focus | Mixed DaisyUI defaults and custom behavior | Consistent cyan active states and visible keyboard focus | Add `focus-visible` ring, standard active/hover recipes | `/hyperopen/src/styles/main.css` | P0 |

## 5) Interaction and UX Affordance Gaps

1. Active-state language is inconsistent:
   - Current: cyan text in some places, blue fill in others, DaisyUI defaults elsewhere.
   - Required: one accent system (`trading-accent`) for active tabs, selected controls, and key links.

2. Focus visibility is not standardized:
   - Current: browser/default and DaisyUI mixed focus treatment.
   - Required: consistent `focus-visible` ring (2px accent ring + subtle offset) for keyboard navigation.

3. Terminal-density scanning is diluted by oversized controls:
   - Current: several control groups and icon buttons are larger than parity target.
   - Required: normalize control height and row rhythm to match Hyperliquid familiarity.

4. Pre-submit confidence affordances in order form are incomplete:
   - Current: missing compact notional/fee/liquidation context block near submit.
   - Required: add summary band and align with Hyperliquid-style pre-submit information pattern.

5. Motion timing is inconsistent:
   - Current: dropdown transition durations vary (50ms, 80ms, defaults).
   - Required: standardize to 100-140ms ease-out for open and 80-120ms for close.

6. Numeric readability is inconsistent across tables/orderbook:
   - Current: some columns are not explicitly tabular.
   - Required: enforce tabular numerals for all price/size/value columns.

## 6) Priority and Rollout Order

### P0 (Blockers for Familiarity Parity)
1. Introduce unified token layer and semantic utilities.
2. Remove blue-accent drift in chart/orderbook controls; switch to cyan accent language.
3. Normalize order form geometry and CTA emphasis.
4. Tighten tab/table density in bottom account panels.
5. Normalize border contrast and focus-visible behavior.

### P1 (High-Value Refinement)
1. Header control density and nav rhythm refinements.
2. Right account-equity typography/spacing polish.
3. Orderbook dropdown/control density refinements.
4. Add missing order-form affordances (size slider, summary rows).

### P2 (Optional / Contextual)
1. Jurisdiction warning strip support.
2. Tooltip trigger and footprint refinements.
3. Footer retry-action visual de-emphasis.

### Recommended Implementation Sequence (Engineering)
1. Token and utility groundwork:
   - `/hyperopen/tailwind.config.js`
   - `/hyperopen/src/styles/main.css`
2. Shell and top strips:
   - `/hyperopen/src/hyperopen/views/header_view.cljs`
   - `/hyperopen/src/hyperopen/views/active_asset_view.cljs`
3. Trading core panels:
   - `/hyperopen/src/hyperopen/views/trading_chart/core.cljs`
   - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs`
   - `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`
   - `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`
4. Bottom and right data surfaces:
   - `/hyperopen/src/hyperopen/views/account_info_view.cljs`
   - `/hyperopen/src/hyperopen/views/account_equity_view.cljs`
   - `/hyperopen/src/hyperopen/views/footer_view.cljs`

## 7) Acceptance Checklist

### 1. Visual parity snapshot checklist (desktop full-width)
- [ ] Header, market strip, chart, orderbook, form, bottom tables, and right panel visually align with Hyperliquid affordance density and hierarchy.
- [ ] No major raw `gray-*`/`blue-*` classes remain on trade-screen shell controls (semantic token classes used instead).

### 2. Header affordance check
- [ ] Active nav state is immediately recognizable without relying on bright color fill alone.
- [ ] Wallet/action buttons share one geometry system (height, radius, border).
- [ ] Utility icons are compact and visually balanced.

### 3. Form affordance check
- [ ] Buy/Sell segmented control is prominent and parity-like.
- [ ] Order type + input stack reads as one compact control surface.
- [ ] Submit CTA has clear primary emphasis and clear disabled/hover states.

### 4. Orderbook readability check
- [ ] Row rhythm supports rapid vertical scanning.
- [ ] Bid/ask semantics are clear and not over-saturated.
- [ ] Spread row is visually centered and legible.

### 5. Tables density check
- [ ] Tab bar is compact with clear active indicator.
- [ ] Headers and rows use consistent spacing/contrast.
- [ ] Numeric columns use tabular numerals and align correctly.

### 6. Consistency check across surfaces
- [ ] Same token roles are used across chart/orderbook/form/tables.
- [ ] Focus, hover, and active states follow one interaction language.

### 7. Accessibility sanity check
- [ ] Label/body text remains readable at trade-screen density.
- [ ] Keyboard focus is visible on all interactive controls.
- [ ] Contrast is sufficient for secondary labels and border dividers.

## 8) Risk Notes and Non-Goals

### Risks
1. Over-indexing on screenshot parity can conflict with existing DaisyUI defaults.
2. Mixed legacy classes and semantic tokens can produce temporary visual inconsistency during migration.
3. Compact density updates may expose overflow edge cases in low-width desktop windows.

### Mitigation
1. Enforce a semantic utility layer first, then migrate component-by-component.
2. Add a temporary lint/check rule to flag raw `gray-*` and `blue-*` classes in trade views.
3. Validate at common desktop widths before mobile-specific tuning.

### Non-Goals
1. No changes to exchange logic, websocket payloads, or order placement behavior.
2. No redesign of non-trade routes.
3. No re-architecture of ClojureScript state management.

---

## Appendix: Key Current-State Evidence Index

- Theme tokens and DaisyUI mapping:
  - `/hyperopen/tailwind.config.js:6`
- Base styles and utility layer:
  - `/hyperopen/src/styles/main.css:14`
- Layout composition:
  - `/hyperopen/src/hyperopen/views/trade_view.cljs:16`
- Header:
  - `/hyperopen/src/hyperopen/views/header_view.cljs:5`
- Market strip:
  - `/hyperopen/src/hyperopen/views/active_asset_view.cljs:121`
- Chart:
  - `/hyperopen/src/hyperopen/views/trading_chart/core.cljs:21`
  - `/hyperopen/src/hyperopen/views/trading_chart/utils/chart_interop.cljs:8`
- Orderbook:
  - `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs:263`
- Order form:
  - `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs:43`
- Account tables:
  - `/hyperopen/src/hyperopen/views/account_info_view.cljs:25`
- Account equity:
  - `/hyperopen/src/hyperopen/views/account_equity_view.cljs:126`
- Footer:
  - `/hyperopen/src/hyperopen/views/footer_view.cljs:25`
