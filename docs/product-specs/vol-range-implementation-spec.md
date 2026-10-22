---
document_id: HO-SPEC-014
version: "2.1"
status: draft
owner: product-and-trading-engineering
last_reviewed: 2026-10-19
review_cycle_days: 90
source_of_truth: false
target_release: Q4-2026
repository: thegeronimo/hyperopen
repository_commit: b4174f85fcd90610ddbb7ac8a015fdd899e62df7
---

# HyperOpen Vol Range Product Implementation Specification

## 1 Purpose and implementation baseline

Build **inventory-aware, volatility-scaled spot limit ladders** in HyperOpen. A user specifies the inventory they want to accumulate or distribute, an explicit spending cap, and inventory limits. HyperOpen converts those inputs into a reviewable set of fixed, post-only orders, manages their individual outcomes, and explains their subsequent performance.

The feature is a mode within **Pro → Scale → Volatility range**. Active ladders belong in the trading dock’s **Vol ladders** tab. Historical analysis belongs in a **Vol Range** tab in Portfolio. These surfaces share one order plan and one reconciled lifecycle.

This specification replaces the mathematical, execution, and placement assumptions in the original HO-SPEC-014. “Must” identifies an acceptance requirement. A proposed file or API is explicitly identified as new; existing integration points are pinned to the repository commit above. Product decisions below are the implementation baseline. Protocol capability tests remain release gates.

**Reading order for the implementer:** use Section 2 as the code map, Sections 5–10 as the calculation and execution contract, Sections 4 and 11 as the screen contract, Section 12 for reporting, and Sections 13–15 for fixtures, tests, and pull-request sequencing.

### 1.1 Product decisions

| Area | Required decision |
| --- | --- |
| Initial execution scope | Long, fully funded, USDC-quoted spot in explicitly supported standard account modes. |
| Entry point | Preserve `:type :scale`; add Price range / Volatility range within the existing Scale section. |
| Volatility | Daily Parkinson estimate from exactly 14 completed UTC daily candles. |
| Inventory adjustment | Optional **Inventory tilt**, off by default; strength 0.45 when enabled. |
| Inventory controls | Current spot inventory, protected core minimum, target, and maximum are separate values. Target is a preference; minimum and maximum constrain order admission. |
| Sizing | Requested base quantity on each enabled side. Buys additionally require a maximum USDC debit. Skew distributes base quantity. |
| Execution | One order action containing all admitted legs. Each leg has an independent outcome. |
| Order behavior | Fixed prices and sizes; post-only is mandatory. No automatic repricing, replenishment, or taker fallback. |
| Replacement | Cancel the selected revision, confirm terminal outcomes, refresh account state, then review and place a new revision. |
| Active management | Trade-owned **Vol ladders** dock tab, using the existing account-panel extension mechanism. |
| Reporting | Portfolio-owned Vol Range tab. Net tracked-asset P&L and contribution versus holding are the primary measures. |
| Operating model | Client-side calculation, persistence, and monitoring while the application is running. Resting orders remain at the exchange when the browser closes. |

### 1.2 Release boundaries

**Release A0 (preview) ships first, behind the preview capability.** It includes eligibility, data loading, exact planning, the full ticket, chart overlays, and the review screen with every canonical leg, but no signing, journal, or placement. Its review footer states **Placement isn’t available yet** in place of the commit action. A0 depends only on the precision, contracts, and planner work (Section 15.1, PRs 1–3); it validates the model and the screens with real users while the execution foundation is built. A0 must not construct a signable action or reserve a nonce.

**Release A includes the complete execution lifecycle**, not merely the calculator: eligibility, data loading, exact planning, ticket, review, chart overlays, durable intent records, nonce coordination, partial and unknown outcomes, active-grid dock, cancellation, replacement, and reload recovery.

**Release B adds analytical reporting:** cross-asset health, campaign accounting, coverage-aware P&L, a holding comparison, execution exports, and descriptive cycle statistics. Capture the necessary manifests and fills during Release A so this can be added without fabricating earlier history.

**Later extensions require their own capability gates:** perpetuals, short inventory, unified or portfolio-margin buying power, non-USDC quotes, quote-budget-only sizing, automatic funding or weekend adjustments, and the Spectate volatility lens. They are not implicit consequences of a generic market flag.

No new top-level route is required. Keep the Portfolio optimizer’s existing route family and implementation independent.

### 1.3 User-facing meaning

Describe the feature as a way to **place orders at chosen volatility distances while controlling inventory**. A volatility distance is a historical scale, not a probability of reversal. Do not label the feature an optimal market maker, a variance-risk-premium strategy, or an adverse-selection shield. Do not promise maker rebates, filled orders, or expected returns.

Use these core labels:

- **Daily volatility: 6.00% · 14-day lookback**
- **Inventory tilt**
- **Protected core**
- **Maximum USDC debit**
- **Quoted buy VWAP / Quoted sell VWAP**
- **Orders fill independently. Prices stay fixed until you update them.**

Show the difference between buy and sell VWAPs only when both sides are enabled, collapsed by default behind a disclosure. Label it **Quoted VWAP gap**, and explain in the same disclosure that it assumes matched quantities and is not expected profit. Never show it as a headline figure, in the dock, or in the review summary row.

### 1.4 Terminology

Every user-visible surface calls a deployed set of Vol Range orders a **ladder**: the **Vol ladders** dock tab, **Cancel this ladder**, **Ladder contribution versus hold**. HyperOpen never re-places, replenishes, or recycles orders after fills, and “grid” suggests a grid bot that does. The side cards are **Accumulate buys** and **Distribute sells**.

Internal identifiers keep the engineering term *grid* (`:grids`, grid IDs, the grid lifecycle, “logical grid” membership) so that the execution contracts in Sections 7–10 stay stable. Do not leak the internal term into labels, toasts, live-region announcements, or exports’ human-readable columns.

### 1.5 Changes in revision 2.1

| Area | Change |
| --- | --- |
| Inventory tilt | Normalized by the distance from target to the relevant bound (Section 5.4), so full strength is reached at protected core or maximum. Under 2.0 the shift was negligible (0.024σ in the reference fixture). |
| Post-only crossing | Planner offers explicit **Anchor to market price** and **Start buys/sells at Xσ** remediations (Section 6.3). |
| Required inputs | One-click **Set to N (all buys filled)** for maximum and **Use plan total** for the USDC cap; inline guidance when protected core leaves nothing to sell (Section 4). The values remain explicit user choices. |
| Naming | User-facing “grid” becomes “ladder”; Recycle sells becomes Distribute sells (Section 1.4). |
| Release plan | New Release A0 preview milestone (Section 1.2, Section 15.1). |
| Dock | Release A shows filled quantity and fill VWAP per side, explicitly not P&L (Section 11.2). |
| Drift flag | Anchor drift threshold raised from 0.25 to 0.50 deployed daily-volatility units. |
| Rung limits | 1–12 on each side (was buys 1–15, sells 1–8). |
| Quoted VWAP gap | Collapsed by default and shown only with both sides enabled (Section 1.3). |

## 2 Where this fits in the current code

The repository already separates pure decisions, application orchestration, external-data normalization, and effect interpreters. Extend those boundaries. Do not introduce the original draft’s parallel state tree, direct feature-level HTTP calls, or a second signing implementation. See the pinned [architecture][R02] and [trading boundary][R03].

### 2.1 Existing integration map

Paths in the following table are relative to `src/hyperopen/`.

| Existing source | Required implementation work |
| --- | --- |
| `trading/order_form_state.cljs` | Extend defaults and `normalize-scale-form` together. It currently reconstructs only start, end, count, and skew and would discard a new mode or nested draft. |
| `state/trading.cljs` | Preserve the canonical `:order-form`, `:order-form-ui`, and `:order-form-runtime` roots. Extend normalization, persistence, shared submit policy, and request construction for the nested mode. |
| `trading/order_form_transitions.cljs`, `trading/order_form_context_sync.cljs` | Add typed input paths and context invalidation. Prevent generic single-side size synchronization from overwriting the two Vol Range quantities. |
| `trading/order_form_ownership.cljs`, `state/trading/order_form_key_policy.cljs` | Extend ownership contracts while preserving valid active domain fields. Keep durable-export filtering separate. Grid lifecycle and history do not belong in the generic order-form transition return map. |
| `trading/order_form_application.cljs` | Expose a stable prepared-plan projection for the ticket, chart, and review. |
| `views/trade/order_form_type_extensions.cljs`, `views/trade/order_form_view.cljs` | Add the nested mode selector. Replace the global side/size controls with two side cards when this mode is active. |
| `views/trade/order_form_vm.cljs`, `schema/order_form_contracts.cljs` | Extend the VM and exact-key contracts together. Additional VM keys are not accepted automatically. |
| `schema/order_form_command_catalog.cljs`, `schema/runtime_registration/trade.cljs` | Register new commands and actions through the existing catalog. Do not duplicate the command-to-action map. |
| `runtime/effect_order_contract.cljs` | Register critical interactions so visible pending/review state precedes network effects and each interaction emits one logical operation. |
| `views/trade_view.cljs` | Extend the narrow, memoized ticket, chart, and dock state slices. A new root subtree alone will not repaint these panels. |
| `domain/trading/core.cljs`, `domain/trading/market.cljs` | Reuse ladder concepts and market identity. Extend the shared precision seam for exact, side-aware rounding. Do not use raw-price Scale totals as the final Vol Range budget. |
| `domain/trading/fees.cljs`, `state/trading/fee_context.cljs` | Reuse effective fee context with explicit unit conversion and provenance. Do not use the generic display fallback for hard cash admission. |
| `api/gateway/orders/commands.cljs` | Add a prepared two-sided leg path before ordinary Scale’s single-side assumptions. Map the final plan to one wire action without recalculating it. |
| `order/effects.cljs`, `api/trading.cljs` | Retain account targeting, mutation permission, agent readiness, and the public trading facade. Add a strict result contract and grid lifecycle around them. |
| `api/trading/agent_actions.cljs`, `api/trading/http.cljs`, `utils/hl_signing.cljs` | Reuse signing and session handling. Add shared nonce reservation before send and disable automatic ambiguous retries for grid operations. |
| `api/endpoints/market.cljs` | Reuse `request-candle-snapshot!`, spot metadata/context loading, and L2 reads through the request policy. |
| `api/endpoints/orders.cljs`, `api/endpoints/account/portfolio.cljs` | Reuse raw order/fill/balance/fee reads; add order-status lookup and an awaitable account snapshot barrier. |
| `websocket/user_runtime/handlers.cljs` | Reconcile raw open-order and fill events before UI pruning and the retained display-fill limit. |
| `account/surface_policy.cljs`, `account/surface_service.cljs` | Preserve centralized stream coverage and REST fallback ownership. |
| `platform/indexed_db.cljs` | Add feature stores and a transaction-completion write/update primitive for durable intents and nonce reservations. |

The actual state, command, execution, and persistence seams are supported by the pinned sources [R04]–[R14]. The implementer must recheck the current branch before editing; preserve the contracts below if paths move.

### 2.2 Foundation changes that must precede live execution

1. **Exact monetary arithmetic.** Existing size helpers multiply JavaScript numbers and floor them. For example, binary arithmetic can turn `0.29 × 100` into a value below 29. Do not treat existing helpers as proof of safe lot accounting.
2. **Strict per-leg outcomes.** Current `submit-outcome` can treat an unfamiliar non-error status, or outer success with an empty vector, as success. A grid needs recognized states, cardinality checks, OIDs, and uncertainty.
3. **Shared nonce reservation.** The current agent path persists its cursor after the response. Overlapping producers can reserve the same value. Coordinate all users of the signer, including manual and optimizer actions.
4. **Committed intent storage.** Current IndexedDB request success is not a transaction-completion acknowledgment. A write that later aborts must not permit an order send.
5. **Authoritative cancellation evidence.** Existing Open Orders UI can hide pending cancels optimistically. Its absence is not a cancellation barrier.
6. **Raw account capabilities.** Existing account-mode normalization groups portfolio margin with unified accounts and defaults unknown values to classic. Admission must inspect an explicit, fresh raw mode.

These are scoped integration requirements, not authorization to refactor unrelated trading behavior. Preserve existing public APIs or add compatible overloads with regression coverage.

### 2.3 Proposed feature ownership

The following files are **new proposed locations**, not existing modules:

| Proposed module | Responsibility |
| --- | --- |
| `vol_range/contracts.cljs` | Versioned scope, draft, plan, operation, leg, coverage, and persisted-record contracts. |
| `vol_range/domain/volatility.cljs` | Completed-day validation and Parkinson calculation. |
| `vol_range/domain/grid.cljs` | Anchor/tilt geometry, base-size weights, and integer-lot allocation. |
| `vol_range/domain/admission.cljs` | The single source of inventory, cash, fee, price, and capability invariants. |
| `vol_range/application/planner.cljs` | Pure orchestration from normalized snapshots and a draft to a prepared plan or typed errors. |
| `vol_range/application/lifecycle.cljs` | Pure operation and per-leg transitions, replay, and reconciliation decisions. |
| `vol_range/application/projections.cljs` | Stable ticket, chart, dock, and report view models. |
| `vol_range/actions.cljs` | Nexus actions and transition orchestration. |
| `vol_range/infrastructure/reads.cljs` | Awaitable, scoped snapshot and reconciliation reads through existing API adapters. |
| `vol_range/infrastructure/persistence.cljs` | Versioned records, migrations, committed writes, checkpoints, and pruning through shared IndexedDB. |
| `runtime/effect_adapters/vol_range.cljs` | Interpret feature effects through trading, data, storage, clock, and entropy collaborators. |
| `domain/trading/precision.cljs` | Shared exact decimal/lot and legal-price helpers if extracting them from current trading modules. |
| `api/trading/action_coordinator.cljs` | Shared signer nonce reservation and account mutation coordination. |

Views consume projections and emit commands. They do not generate prices, match fills, reserve cash, call IndexedDB, or decide whether an unknown order can be retried. Keep new namespaces and functions within repository size limits. Add an ADR for the journal, precision, and coordination boundaries.

## 3 Supported markets and account capabilities

### 3.1 Initial admission policy

A Release A plan is executable only if all of these facts are explicit:

- The selected instrument resolves to a spot pair whose quote token is the canonical USDC token for the selected network.
- Base and quote token IDs, pair index, wire asset ID, size decimals, and price precision are known.
- The account is a supported standard spot account. The initial raw-mode allowlist is `default`, `disabled`, and `dexAbstraction`; each must have a verified non-borrowing spot-balance fixture before its flag is enabled.
- `unifiedAccount`, `portfolioMargin`, missing modes, and unknown future values are blocked in this release.
- The account can mutate through the existing owner/subaccount permission policy, the agent is ready, and the target account is unambiguous.
- Required balances, orders, fee bounds, and market data satisfy freshness and completeness requirements.
- No unresolved placement or cancellation for the scope, and no conflicting recovered Vol Range orders, remain.

The selected account’s actual spot balances are the inventory source. Do not include staked, vault, perp, or externally bridged holdings in available spot inventory.

A connected owner may act for an explicitly supported subaccount through the existing account-context routing. Spectate and inspected-wallet surfaces remain read-only. Do not change account mode, transfer funds, enable borrowing, or update leverage as a hidden pre-action.

### 3.2 Identity contract

Every draft binding, snapshot, operation, persisted record, and async completion carries this resolved identity:

~~~clojure
{:network :hyperliquid-mainnet
 :owner-address "<normalized signing owner>"
 :account-address "<normalized account holding orders and balances>"
 :vault-address nil
 :market-type :spot
 :spot-pair-index 107
 :asset-id 10107
 :coin "@107"
 :base-token-id "<resolved token identity>"
 :quote-token-id "<resolved USDC identity>"
 :symbol "HYPE"
 :quote-symbol "USDC"
 :sz-decimals 2
 :metadata-version "<observed metadata revision>"}
~~~

Numbers and symbols above illustrate the contract; resolve all live identities from metadata. Spot wire IDs use the pair index, not the base-token index. Token symbols alone are insufficient identity. [P01]

Use `[network account-address spot-pair-index]` as the active-grid scope. Also enforce one active grid affecting a given base token in that account; multiple quote pairs must not bypass inventory accounting.

The existing exchange transport is mainnet-oriented. Carry network identity through endpoint selection and signing, and test their agreement. A testnet signing option must never be sent to a mainnet endpoint. Release A’s public execution capability is mainnet; any testnet adapter must be explicitly configured and verified.

### 3.3 Existing commitments

Worst-case exposure includes known live orders and durable local pending intents that can affect the base token. Ordinary limit orders are not exempt because they lack the feature prefix.

Account availability uses confirmed `total − hold`. Existing holds are already deducted from free balances; do not deduct them twice. Separately include their remaining quantities in the all-buy/all-sell inventory scenarios.

Transfer a pending reservation into venue-held or settled accounting only when a coherent account observation establishes that the corresponding hold or executed debit is already included. Order presence alone does not establish this. For a terminal order, release only the definitively rejected or canceled unfilled portion; retain conservative accounting for filled quantities and fees until the reconciled balance reflects settlement. Match by scope and CLOID/OID so each economic commitment is counted once.

If other spot pairs use the base token as a quote currency, or conditional/automated orders have exposure that the adapter cannot bound, block the affected plan with a specific remediation. Do not infer that “no visible limit orders” means no commitments.

The one-grid rule is enforced within the coordinated application and against observed exchange state. Browser-only storage cannot provide a globally atomic lock across independently submitting devices. Recheck before placement; discover and surface conflicts without claiming stronger exclusivity.

## 4 Ticket and review behavior

### 4.1 Entry and defaults

Keep ordinary Scale unchanged when its mode is Price range. A legacy Scale draft without a mode normalizes to Price range.

When Volatility range is selected, show:

1. Account and complete spot-market identity.
2. Daily volatility, anchor, and data freshness.
3. Inventory controls.
4. **Accumulate buys** and **Distribute sells** cards.
5. Final costs and one-side-fill inventory outcomes.
6. Review action.

Defaults for a new account/market draft:

| Control | Default and validation |
| --- | --- |
| Anchor | 24h VWAP when available. If unavailable, display that state and let the user explicitly select another source. |
| Volatility | Auto; manual override blank. |
| Inventory tilt | Off; advanced strength 0.45, allowed 0–1. |
| Protected core | Initialize to current confirmed spot inventory, then require review if the user lowers it. |
| Target | Initialize to current inventory; editable. |
| Maximum inventory | Blank until explicitly set; must be positive. Offer a one-click **Set to N (all buys filled)** where N = current inventory + existing buy exposure + enabled buy quantity + conservative base-credit bound, rounded up to the base lot. The button only fills the draft. |
| Buy card | Enabled; 4 rungs; distances 0.75–2.25; base-size skew 1.00×; quantity and USDC cap blank. Once a valid buy plan exists, offer **Use plan total** for the cap (Section 4.3). |
| Sell card | Disabled; 3 rungs; distances 1.25–2.25; base-size skew 1.00×; quantity blank. |
| Bands | Visible while the mode is focused. |
| Execution | Post-only, fixed and non-editable. Spot reduce-only is not shown. |

Rung counts are integers from 1 to 12 on each side. Twenty-four legs fit in one action of batch IP weight 1 and keep the review table readable; there is no product reason for asymmetric limits. Distances are positive magnitudes, from 0.10 to 4.00, with start no greater than end; for multiple rungs they must generate distinct legal prices. Skew is the farthest/nearest base-size ratio, from 1.00 to 3.50. These are product limits, not exchange limits.

Manual daily volatility accepts 0.10%–50.00%. Auto results are not silently clipped to that range. A valid zero estimate is displayed as zero, but cannot produce an executable collapsed ladder; offer a manual override.

Use numeric entry alongside sliders. Parse user text through the repository’s locale-aware input boundary, preserving a validated decimal representation for exact arithmetic. Empty or intermediate text is a draft state, not numeric zero.

### 4.2 Inventory controls

Show **Current spot inventory** read-only, including units held in open orders, and **Available to sell** separately. Explain:

- **Protected core:** the minimum the new orders should leave after all sells execute.
- **Target:** the inventory around which optional tilt is centered.
- **Maximum:** the ceiling after all buys execute.

Require `0 ≤ core ≤ target ≤ maximum`. Account-specific quantities and limits must not carry over to another wallet, subaccount, or market.

When the Sell card is enabled and sellable excess above protected core is zero (the default, because protected core starts at current inventory), show **Nothing to sell above protected core. Lower protected core to distribute inventory.** next to the quantity, and focus-link it to the protected-core field. Do not lower protected core automatically.

Sell quantity shortcuts may select 25%, 50%, or 100% of **currently sellable excess above protected core**, after existing commitments and fee bounds. They update the visible quantity and generate a new plan. Do not label these as percentages of total holdings.

Changing a control updates only the draft. It never modifies live orders.

### 4.3 Side cards

The Buy card contains quantity in base units, maximum USDC debit, near/far volatility distance, rung count, and skew. Display exact total quantity, gross order value, fee reserve by token, maximum debit, and buy VWAP.

The Sell card contains quantity, sellable-excess shortcuts, distances, count, and skew. Display base committed, any additional base fee reserve, gross proceeds, estimated net proceeds, and sell VWAP. Sale proceeds are not available to finance the staged bids.

At least one side must be enabled with a positive quantity. Disabled-side drafts may be preserved, but do not participate in admission or the action.

**Use plan total** sets the cap to the current plan’s maximum buy debit, rounded up to the cent. It is a draft edit: the cap does not follow later plan changes, and a later plan above the cap fails admission as usual.

Base quantity is authoritative in Release A. A USDC cap does not mean “spend the entire cap.” Quote-budget-only allocation is a later, explicitly different mode.

### 4.4 Review

The main ticket action is **Review N orders**. Review shows the exact canonical prices and sizes that will be signed, with:

- Account, spot pair, post-only behavior, plan timestamp, and data sources.
- Anchor, daily volatility, tilt, and the effective shifted anchor.
- Every leg’s Buy/Sell label, price, base quantity, notional, and actual volatility distance after price rounding.
- Total buys, total sells, USDC debit cap, fee reserves, and available-balance headroom.
- Current inventory, protected core, target, maximum, and worst-case inventory after each side fills first.
- Any existing orders included in those bounds.
- The fixed-order and independent-fill explanation.

The commit action is **Place N post-only orders**. It references the frozen preview ID. Enter while editing a field must not place orders. Show pending state immediately and prevent double submission.

Preparing review injects the plan/revision IDs and CLOIDs, binds the exact builder configuration, and freezes the canonical action. Reuse those identities at placement; do not generate a different action after review.

Review expires after 60 seconds as a product policy. Agent unlocking returns through fresh admission checks. It must not replay a stale submit automatically.

If revalidation changes the quantities, prices, scope, fees, inventory outcomes, or required user commitment, return to a refreshed review. Fresh BBO movement alone does not require changing the frozen prices if all safety checks still pass.

The anchor and volatility are model inputs frozen for review. Do not silently re-anchor to each new quote. If their source window, allowed age, or metadata becomes invalid, invalidate the review; otherwise validate the frozen prices against the current book and account.

## 5 Volatility, anchors, and inventory tilt

### 5.1 Candle boundary

Use the last 14 completed UTC calendar days for the selected spot coin. At 2026-10-19 14:00 UTC, the window is October 5–18 inclusive. Today’s partial candle is always excluded from the volatility sample, including after noon.

Fetch enough daily bars through the existing candle endpoint to obtain those exact dates. Request at least 16 bars, sort by open time, normalize source numeric strings, and validate market and interval identity.

Each required candle must have finite positive high/low values, high at least low, and the expected UTC boundaries. Identical duplicate records may be deduplicated; conflicting duplicates invalidate the sample and trigger a refetch. A missing or invalid required day is not replaced with an older day.

The external-data adapter accepts the documented numeric representations and produces one typed internal form. JavaScript string comparison and ClojureScript `double` are not numeric-string parsing.

Cache completed daily data by network, coin, interval, and window end. Use a 15-minute fetch TTL, request coalescing, and forced invalidation at a UTC day rollover. At rollover, new auto plans wait for the newly completed candle; retain the previous display only with an explicit stale/refreshing label.

If fewer than 14 valid days exist, show **14 completed daily candles required**. Manual volatility may enable planning if other required sources are valid; it does not silently repair the historical sample.

### 5.2 Daily Parkinson estimate

For exactly 14 valid days:

~~~text
v_raw = sqrt(sum(log(high_d / low_d)^2) / (14 × 4 × log(2)))
~~~

This is a daily volatility estimate; do not annualize it or label it “14-day volatility.” The 14 days are the lookback. The estimator follows the high-low method described by Parkinson. [P08]

Logarithm and square-root calculation may use binary floating point. Canonicalize the result to a decimal fraction with 12 places, round half up, and store both the estimator version and canonical value. Downstream order geometry uses that frozen decimal value.

Manual input is in percent and converts exactly once: `6.00 → 0.060000000000`. Name the stored field `:daily-fraction`; do not mix percent and fraction fields.

Do not average NATR with Parkinson in this release. If ATR is added later, it requires a separately named range measure, a specified smoothing seed and prior close, and a new model version. Do not add funding, open-interest, or weekend multipliers to this model.

### 5.3 Anchor sources

| Mode | Contract |
| --- | --- |
| 24h VWAP | Use same-market, same-window quote notional volume divided by base volume only when both are finite, positive, and explicitly available. For spot, use the resolved spot context. Never substitute perp volume or infer true VWAP from candle OHLC. |
| UTC open | Use the current UTC daily candle’s opening price. Using its open as an anchor does not admit its partial high/low range into volatility. |
| Market price | Use the fresh spot BBO midpoint. Label it Market price, not a perp mark price. |
| Custom price | An explicitly entered positive USDC/base price. Label it Custom anchor; do not imply a verified fundamental valuation. |

No source falls back invisibly. The UI shows the chosen source and timestamp. A custom anchor changes order geometry, not the measured volatility.

### 5.4 Tilt and rungs

Let `A` be the anchor, `q` current total spot inventory, `q_core` the protected core, `q_target` the target, `q_max` the positive maximum, and `kappa` the tilt strength:

~~~text
inventory_delta = 0                                              when q = q_target
inventory_delta = clamp((q − q_target) / (q_max − q_target), 0, +1)  when q > q_target
inventory_delta = clamp((q − q_target) / (q_target − q_core), −1, 0) when q < q_target
                  (a zero denominator yields +1 or −1 respectively)
r = A                                      when tilt is off
r = A × (1 − kappa × inventory_delta × v)   when tilt is on

u_i = 0                  when K = 1
u_i = i / (K − 1)        otherwise
z_i = z_start + u_i × (z_end − z_start)

raw_buy_price_i  = r × (1 − z_i × v)
raw_sell_price_i = r × (1 + z_i × v)
~~~

Overweight inventory shifts the order anchor down; underweight shifts it up. The delta reaches ±1, and the shift reaches `kappa × v` of the anchor, exactly at maximum or protected core. Normalizing by `q_max` alone (revision 2.0) left the shift below about 0.1 daily-volatility units for realistic limits, so the control did nothing visible. Show the effective shift in σ units next to the tilt control when tilt is enabled. This is an inventory-tilt heuristic with arithmetic volatility spacing. It is not the full Avellaneda–Stoikov optimal-quoting model. [P09]

Reject nonpositive or nonfinite anchors, shifted anchors, or rung prices. Do not emit a price of zero. Use positive distances internally; apply side signs only in formulas and presentation.

### 5.5 Bands and distance labels

Keep two concepts distinct:

- **Market reference bands:** `A × (1 ± v)` and `A × (1 ± 2v)`.
- **Your order zones:** the generated ranges around `r`, with the shifted anchor labeled.

For market excursion, use `(current_mid / A − 1) / v`. For each rounded order, show `(final_price / r − 1) / v`. Missing or zero volatility makes the distance unavailable.

Use descriptive excursion text such as **−0.85σ from anchor**. Do not assign an unqualified “buy zone” or reversal probability.

## 6 Exact sizes, prices, fees, and admission

### 6.1 Base lot allocation

Represent input quantities, prices, caps, balances, fee quantities, and notionals as validated decimal strings or exact decimal/integer values. Serialize them as clean decimal strings at the API boundary. A tested shared decimal implementation is required; an arbitrary epsilon added before `floor` is not sufficient.

For farthest/nearest base-size skew `k`:

~~~text
w_i = 1 + (k − 1) × u_i
lot = 10^(−szDecimals)
N = requested_base_quantity / lot
x_i = N × w_i / sum(w)
~~~

The requested total must be an exact multiple of the base lot. Otherwise return an input error with an explicit suggested valid quantity.

Allocate `floor(x_i)` lots, then distribute the remaining lots to the largest fractional remainders, breaking ties by ascending rung index. This preserves the requested total exactly. Store the actual allocated quantities; rounding can slightly change the ideal weight ratio.

Never drop a zero-sized rung, spend leftover cash, shrink a requested quantity, or silently change the count. A failed allocation returns typed errors and a non-executable preview.

### 6.2 Legal price quantization

Use the exchange’s legal price set, including its integer-price exception:

- Up to five significant figures for non-integer prices.
- At most `8 − szDecimals` decimal places for spot; the shared helper also supports `6 − szDecimals` for perps.
- Integer prices remain legal subject to the exchange’s other constraints. [P02]

Define quantization by result:

~~~text
buy_price  = greatest legal positive price ≤ raw_buy_price
sell_price = least legal positive price ≥ raw_sell_price
~~~

Handle changes of magnitude and precision at boundaries; recompute legality after rounding. Remove trailing zeros and exponent notation from wire strings. Revalidate every final price and quantity.

Preserve existing callers’ public interfaces when improving shared helpers. Add explicit side-aware behavior for Vol Range rather than changing all sell-price semantics silently.

### 6.3 Post-only and distance checks

Validate the final order vector against a fresh, non-crossed BBO:

~~~text
each buy price  < best ask
each sell price > best bid
maximum staged buy price < minimum staged sell price, when both sides exist
~~~

No “one tick below best bid” clamp is required. Do not replace the model with a percentage-of-BBO clamp. An order that would immediately match must be revised or removed through a new user-visible plan.

Crossing is expected whenever the market has moved more than the nearest distance away from a lagging anchor such as 24h VWAP. The planner must return these typed remediations with the error, each evaluated under the entire planner and offered only when it produces a `:ready` plan:

- **Anchor to market price:** switch the anchor source to Market price.
- **Start buys at Xσ** / **Start sells at Xσ:** the smallest start distance on the crossing side, in 0.05 increments and not above that side’s end distance, for which the plan becomes admissible with all other inputs unchanged.

Name the crossing legs and the touch they cross (for example **Buy at 76.40 is above the best ask 75.91**). A remediation changes only the draft and returns through a fresh plan and review. Never drop the crossing rungs silently.

ALO still may reject if the market moves before processing. Preserve that per-leg rejection; never retry it automatically as a taker or shift it to the touch. [P01]

For Release A, additionally require every final price to lie within **50% of the current spot midpoint**, inclusive. This is a configurable HyperOpen product guard, not a claim about a universal venue distance rule. Apply it after tilt and quantization.

If two same-side rungs become the same legal price, block the plan and offer **Reduce rung count** or **Widen range**. Do not merge or stack them invisibly.

### 6.4 Per-leg notional

For the USDC spot scope, the documented minimum is 10 units of the quote token. Use an initial product admission floor of **10.50 USDC per leg** and keep the venue minimum in a capability table. [P03]

Check `final_price × final_size` for every leg, not only the first or smallest-size leg. Validate exchange order-count/rate capacity before sending.

An optional **Use fewer rungs** remediation evaluates lower counts under the entire planner, offers the greatest valid count below the current value, and changes the draft only when clicked. If no count works, ask the user to increase quantity or change the range.

### 6.5 Fee contract

Obtain a fresh fee quote for the actual account and market. Existing `fees/quote-fees` returns **display percentages**: `0.040` means 0.040%, or a fraction of `0.0004`. Make the conversion explicit at the adapter boundary. [R10]

The normalized fee capability must provide conservative per-token debit and base-credit bounds for the submitted order. Include the applicable builder configuration once. Unknown settlement currency, token precision, or reserve rules block executable admission until supported by contract fixtures.

Use positive fee debit bounds for funding. Never count expected rebates or unfilled sales as funding. Include potential base-token rebates in the upper inventory bound.

For actual fills, subtract the signed fee from its reported fee token. Negative fees are credits. `builderFee` is already included in `fee`; do not subtract it twice. [P04]

A live fee quote is an estimate for the verified policy at admission. The UI must distinguish gross order value, fee reserves, and actual settled fees. It must not imply that a quoted rebate or future fee schedule is guaranteed.

### 6.6 Hard constraints

Let existing remaining buy exposure be `B_existing`, existing remaining sell exposure `S_existing`, and proposed totals `B_new` and `S_new`. Include relevant pending local intents without double-counting confirmed orders.

~~~text
all_buy_inventory =
    current_inventory + B_existing + B_new + conservative_base_credit_bound

all_sell_inventory =
    current_inventory − S_existing − S_new − conservative_base_debit_bound

maximum_buy_debit =
    sum(final_buy_price × final_buy_size) + additional_USDC_fee_debit_bound
~~~

The cash, fee, free-balance, precision, notional, and commitment checks apply to every proposal. For an account currently inside its inventory bounds, both inventory inequalities also apply:

~~~text
all_buy_inventory ≤ maximum_inventory
all_sell_inventory ≥ protected_core
maximum_buy_debit ≤ user_USDC_cap
maximum_buy_debit ≤ confirmed_available_USDC − unmatched_local_USDC_reservations
new_sell_quantity + additional_base_fee_bound
    ≤ confirmed_available_base − unmatched_local_base_reservations
~~~

Also reserve any supported fee in another token independently. Do not offset buys and sells in these checks.

If the account starts above its maximum, allow a sell-only corrective plan that does not increase the existing violation and does not breach protected core. If it starts below protected core, allow a buy-only corrective plan that does not worsen the deficit and does not breach the maximum. Show the starting breach explicitly.

Corrective proposals receive only that directional inventory exception. They receive no exemption from the USDC cap, available USDC/base, fee reserves, existing commitments, or any other admission check.

Pre-existing conflicting commitments must be surfaced. The planner cannot make them safe by assuming they will cancel or that opposite-side orders will fill first.

These bounds govern admission under the verified state and known commitments. Subsequent external activity can change the account; it invalidates later previews and appears as a limit or attribution issue during reconciliation.

### 6.7 Planner result contract

One pure planner owns the calculation and invariant checks:

~~~clojure
(prepare-plan {:plan-id injected-plan-id
               :scope scope
               :draft draft
               :market market-snapshot
               :account account-snapshot
               :fees fee-capability
               :pending-intents pending-intents
               :clock now-ms
               :model-version 1
               :precision-version 1})
;; =>
{:status :ready                 ; :invalid or :unavailable otherwise
 :plan-id "<injected identity>"
 :scope scope
 :input-fingerprint "<stable digest>"
 :model {...}
 :legs [{:leg-id "buy-0"
         :side :buy
         :rung-index 0
         :price "76.4"
         :size "7"
         :notional "534.8"
         :actual-sigma "-0.75"}]
 :totals {...}
 :inventory-outcomes {...}
 :fee-reserves {...}
 :source-versions {...}
 :errors []
 :warnings []}
~~~

This is a contract sketch, not drop-in implementation code. Inject IDs, time, snapshots, and entropy; pure domain functions must not read browser state or generate randomness.

Invalid/unavailable results may contain diagnostic draft geometry, but cannot contain an admissible exchange action. The ticket, chart ghosts, review, and wire builder consume the same canonical legs.

## 7 State, data, and persistence contracts

### 7.1 Canonical form state

Extend the existing Scale map; keep its ordinary fields for compatibility:

~~~clojure
{:order-form
 {:type :scale
  :scale
  {:mode :volatility-range       ; legacy/missing => :price-range
   :start "" :end "" :count 5 :skew "1.00"
   :vol-range
   {:anchor {:mode :vwap-24h :custom-price ""}
    :volatility {:mode :auto :manual-daily-pct ""}
    :inventory {:protected-core ""
                :target ""
                :maximum ""
                :tilt-enabled? false
                :tilt-strength "0.45"}
    :buy {:enabled? true
          :quantity ""
          :max-usdc-debit ""
          :count 4
          :start-sigma "0.75"
          :end-sigma "2.25"
          :skew "1.00"}
    :sell {:enabled? false
           :quantity ""
           :count 3
           :start-sigma "1.25"
           :end-sigma "2.25"
           :skew "1.00"}}}}
 :order-form-ui
 {:vol-range {:show-bands? true
              :advanced-open? false
              :review-open? false}}}
~~~

The sample shows only new/relevant fields. Retain all other canonical order-form fields and exact-key contracts.

Put feature runtime, operation, and report state under a new top-level `:vol-range` root, with scope-keyed maps. Do not put live grid state into `:order-form-ui` or the generic form transition’s three-root write map.

Suggested ownership:

~~~clojure
{:vol-range
 {:by-scope {scope-key {:draft-binding {...}
                       :prepared-plan nil
                       :frozen-review nil
                       :active-grid-id nil
                       :operation-id nil
                       :readiness {...}}}
  :grids {}
  :operations {}
  :daily-windows {}
  :campaigns {}
  :projections {:ticket nil :chart nil :dock nil :report nil}}}
~~~

Avoid maintaining duplicate authoritative balances or open-order lists. Persist feature attribution and lifecycle evidence; consume account truth through existing source owners.

### 7.2 Draft persistence and identity changes

Global order-form persistence may retain the selected Scale mode and bounded UI preferences. It must strip account/market-specific Vol Range quantities and limits before writing the generic unscoped form.

Preserve the complete active Vol Range draft through `normalize-order-form`, `persist-order-form`, ownership enforcement, and ordinary `save-many` application-state writes. Despite its name, the current `persist-order-form` function also participates in normal in-memory transitions. Implement removal of scoped values in a separate proposed browser-persistence export projection. Do not add valid Vol Range quantities or limits to the deprecated canonical-key stripping policy.

Persist those scoped inputs in IndexedDB, keyed by network, account, and pair. On account or market change, invalidate the frozen review immediately, bind the new scope, and restore only its own draft. If none exists, initialize safe defaults.

An async completion can update the record for its original scope, but cannot overwrite the currently selected market’s preview. Every completion includes `:scope`, `:request-id` or `:operation-id`, and generation/revision.

### 7.3 Snapshot admission

Initial freshness policies are centralized and versioned:

| Source | Placement requirement |
| --- | --- |
| BBO | Authoritative book observation within 2 seconds; valid positive bid/ask; live transport or freshly completed REST read. |
| Balances and raw open orders | Awaited, complete observations within 5 seconds, reconciled against the same local account-event boundary. |
| Account abstraction | Explicit value for this account, refreshed during final preflight. |
| Fee context | Correct address/market; loaded within 5 minutes; no fallback-only quote. |
| Auto daily volatility | Exact current completed-day window; successful fetch within 15 minutes. |
| VWAP context | Correct market/window; observation within 30 seconds; both volumes valid. |
| UTC open | Correct current UTC day and market. |
| Metadata | Complete current network catalog, supported listing, and unchanged identity/precision revision. |
| Frozen review | No older than 60 seconds and unchanged material inputs. |

Freshness is evidence of a current observation, not merely the time of the last price change. An unchanged market can remain valid through a fresh book snapshot.

Use an awaitable read coordinator that returns the raw snapshots and their provenance. Buffer/reconcile relevant account events during reads. If concurrent fills, pending intents, or response ordering prevent a coherent account boundary, retry within a bounded preflight operation or return **Account changed. Review updated quantities.**

A fire-and-forget UI refresh, two recently received but inconsistent snapshots, or an optimistically filtered table is insufficient.

After a refresh fails, show unavailable/stale values and preserve the previous display with its timestamp. Never replace them with fake zero balances or zero volatility.

### 7.4 Durable records

Use the shared IndexedDB boundary, not raw browser storage calls from feature code. Add versioned stores or keyed records for:

- Scoped drafts and limits.
- Immutable grid revisions and their admitted plans.
- Send/cancel attempts and request identities.
- CLOID/OID mappings and per-leg quantities.
- Relevant fills and durable deduplication keys.
- Coverage intervals, campaign baselines, and accounting checkpoints.

Each record has a schema version, scope, record ID, and `:saved-at-ms`. Store monetary values as strings; do not serialize BigInt or implementation-specific decimal objects directly.

Persist intent before send using a primitive that resolves on **transaction completion** and rejects on abort/error. Request-level `onsuccess` is insufficient. Test quota failure, blocked migration, unavailable storage, and late transaction abort.

New placement and replacement are blocked if the required journal cannot be committed. Identified-order cancellation must have a tested path through shared trading/signing coordination that does not require a successful grid-journal commit; cancel journaling is best effort in this failure mode.

Keep that availability boundary explicit: moving shared nonce reservation into the same failed store and then routing cancel through it does not satisfy the requirement. The implementer must prove a cancellation path retaining valid signer/nonce coordination, network identity, and mutation permission during grid-storage failure. It must not create a placement bypass. If the signer itself needs connection/unlocking or safe coordination cannot be established, show that recovery state; never bypass the signer checks.

Never store private keys, signatures, unlocked session material, or authentication secrets in the grid manifest or export. Reuse the existing agent lockbox/session boundary.

### 7.5 CLOIDs

Use the feature-family prefix `0x484f5652` followed by a **96-bit random per-order suffix**, generated with browser cryptographic entropy at the effect boundary. The result is exactly 128 bits: `0x` plus 32 hexadecimal characters. [P01]

Persist the mapping from CLOID to grid, revision, side, and rung before submission. A pure function may validate and concatenate injected suffixes. Do not derive uniqueness from a millisecond timestamp or rung index alone.

Allocate new CLOIDs for a newly reviewed revision or retry of a definitively rejected leg. Do not rely on CLOID reuse as an undocumented retry/idempotency guarantee.

A logical grid retains explicit membership across its placement and repair attempts. Each repair references the superseded logical leg and records new CLOIDs while preserving surviving earlier orders. Historical failed attempts remain auditable; the current logical-leg status reflects a successful repair.

The prefix identifies a feature family, not ownership, an original target, or a safe cancellation scope. All matching additionally requires network, account, and market identity.

### 7.6 Retention and recovery

Retain active/unresolved intents and all evidence needed to reconcile them. Do not prune them to satisfy a history cap.

Retain at least 90 days of terminal attempts and campaign fill detail. Pruning older campaign data requires a committed checkpoint containing cumulative cash, net base, open matching lots, and coverage state. Keep immutable baselines and attribution breaks. Do not label a 30-day window complete if its required evidence was removed.

On reload, restore the journal before permitting a new grid. Reconcile every possibly sent operation. On a device with no manifest, detect live prefixed orders for the exact account/market and display **Recovered Vol Range orders — original settings unavailable**. Permit explicit cancellation; block a second local deployment until the conflict is resolved.

Do not infer historical performance from a recovered prefix alone.

## 8 Placement and per-order lifecycle

### 8.1 Operation sequence

The shared lifecycle is a pure transition function:

~~~clojure
(step state message)
;; => {:state next-state :effects [...]}
~~~

The following is the intended placement sequence:

~~~mermaid
flowchart TD
  D["Editable draft"] --> R["Frozen review"]
  R --> P["Fresh admission"]
  P --> J["Commit intent and nonce"]
  J --> S["Sign and send"]
  S --> E["Interpret exchange evidence"]
  E --> L["Confirmed live or terminal legs"]
  E --> U["Unresolved outcome"]
  U --> C["Reconcile original IDs"]
  C --> E
~~~

The transition must:

1. Verify the submitted preview ID, scope, feature capability, mutation permission, and agent readiness.
2. Acquire the account admission coordination boundary; read current pending reservations and authoritative state.
3. Revalidate without modifying the approved order vector.
4. Commit the immutable intent, CLOIDs, and pending reservations.
5. Reserve a unique nonce through the shared signer coordinator.
6. Sign the exact action through the existing trading facade.
7. Commit send-attempt metadata before invoking the network call.
8. Immediately before HTTP invocation, recheck scope/permission, action identity, source freshness, and material account/fee/metadata/pending-reservation changes. Revalidate the exact fixed vector; never amend it in place. If admission fails, do not send, record proven pre-send failure where possible, and return to refresh/review.
9. Interpret the response strictly and reconcile per-leg evidence.
10. Release or convert pending reservations only when coherent hold/settlement evidence justifies it.

Revalidation must identify the operation's own reservation as the same proposed commitment, not an additional competing order. Storage/signing delays can exceed the 2-second book or 5-second account freshness policy; a 30-second action deadline is not a substitute for this final guard.

A crash after recording a possible send but before receiving a response produces uncertainty, even if the HTTP call may not have started. Favor reconstruction over duplicate exposure.

### 8.2 Nonce and cross-tab coordination

Coordinate nonce allocation by **network + signer address**. Two subaccounts using the same agent share that nonce authority.

Reserve a strictly increasing nonce before signing; do not wait for an exchange response to advance the cursor. Use a cross-tab-safe shared transaction/coordination mechanism, and ensure all action producers use it. A grid-only mutex does not solve concurrent manual-order submission.

Coordinate account admission separately by **network + target account** so two grids cannot allocate the same free USDC before either hold appears. Persist pending reservations before releasing that critical section.

All in-app placement producers sharing those funds must observe outstanding admission reservations or wait for their reconciliation. A manual or optimizer placement must not ignore a grid intent simply because its venue hold has not arrived yet. Cancellation does not receive a placement reservation requirement.

Expose typed “busy” or “reconciliation required” results rather than launching overlapping mutations. Never allow an expired lease or another tab’s completion to clear the wrong operation.

Set `:max-nonce-retries 0` for grid operations. A future automatic retry must be limited to an exact, authoritative whole-action rejection that proves no order was accepted. A timeout or an error string containing “nonce” does not prove that. The venue nonce model and shared API-wallet behavior are documented separately. [P05]

### 8.3 Wire mapping

The gateway maps the frozen canonical legs into the existing action/signing path:

~~~clojure
{:type "order"
 :orders [{:a resolved-spot-asset-id
           :b true
           :p "76.4"
           :s "7"
           :r false
           :t {:limit {:tif "Alo"}}
           :c "<persisted CLOID>"}
          ;; Remaining buys, then sells; deterministic rung order.
          ]
 :grouping "na"}
~~~

Use the application’s existing builder-fee configuration if applicable, and include it in preview and request identity. Preserve signing-sensitive key ordering through the established gateway/signer; do not rebuild the payload from UI maps.

All signed field values must match the reviewed canonical action. No empty action is valid. Do not invoke the old one-sided Scale submission twice.

For each attempt, set an action deadline of 30 seconds from final admission, using the existing supported `expiresAfter` path. Revalidate if signing/unlocking consumes that deadline. Request expiration prevents a late request from being accepted; it does not expire resting orders. [P01]

Record the exact action digest, scope, nonce, deadline, attempted-at time, and delivery phase. A local digest identifies the request; it is not a guarantee of venue idempotency.

### 8.4 Transport result and leg result are separate

~~~clojure
{:attempt-id "<id>"
 :operation-id "<id>"
 :scope scope
 :delivery :not-sent            ; :response or :unknown
 :nonce 0
 :expires-after-ms 0
 :action-digest "<digest>"
 :response-category nil
 :leg-results []}
~~~

`:not-sent` is allowed only when the code proves the failure preceded network invocation. Abort, timeout, fetch rejection, or unreadable data after invocation is `:unknown`.

Normalize results at one API boundary:

| Evidence | Required interpretation |
| --- | --- |
| Valid expected-length order vector with recognized resting entry | Record that leg’s OID and resting state. |
| Recognized filled entry | Record accepted/fill evidence and reconcile detailed fills; do not count the same fill again when the stream arrives. |
| Mixed resting/filled/error entries | Preserve each outcome. The grid is partially deployed when requested legs were rejected. |
| Recognized whole-action rejection | All legs are rejected with the shared reason. |
| Empty vector, wrong vector length, unexpected response type, or unknown entry shape | Preserve raw diagnostics and mark affected mapping unresolved. Do not count unknown entries as success. With ambiguous index alignment, resolve by original IDs. |
| Timeout or unreadable response after send | Keep all unresolved legs in Checking status and reconcile. |

One action is not an all-or-nothing fill or placement guarantee. The documented response format includes per-order statuses. [P01]

### 8.5 Per-leg model

Represent lifecycle and quantities independently:

~~~clojure
{:cloid "<id>"
 :oid "<lossless order identity when known>"
 :side :sell
 :rung-index 1
 :price "88.4"
 :original-base "10"
 :filled-base "3"
 :remaining-base "0"
 :canceled-base "7"
 :placement-state :accepted     ; :pending / :rejected / :unknown
 :order-state :terminal         ; :resting / :partially-filled / :terminal
 :terminal-reason :canceled
 :cancel-state :confirmed
 :last-evidence-at-ms 0}
~~~

A partially filled and then canceled order is neither “fully filled” nor “unfilled.” Retain fills, fees, remaining quantity, and terminal reason.

Grid status is a projection of its legs and operation:

- **Submitting:** a committed attempt is in flight.
- **Live:** known resting legs exist and no requested leg has an unresolved outcome.
- **Partially deployed:** some requested legs were rejected while other legs were accepted.
- **Checking status:** at least one possible send/cancel outcome is unresolved.
- **Canceling:** cancellation is in flight for identified legs.
- **Closed:** every leg is terminal with no unresolved attempt.
- **Not placed:** a definitive rejection or proven pre-send failure left no accepted legs.

Display counts as well as the aggregate label. Unknown status takes precedence over a reassuring aggregate label.

### 8.6 Unknown outcomes and retry

On uncertainty, query the original CLOIDs/OIDs using current raw orders, order-status lookup, and fills. Do not allocate a new grid while an old request might still become live.

A missing order in one snapshot, a timeout, an absent OID, or a generic missing-order cancellation response is not proof of non-placement.

Reconciliation may establish resting, filled, partially filled, canceled, rejected, or still unknown. “Not observed after expiration” may release an attempt only if the protocol adapter can prove the action can no longer be accepted and complete order/fill evidence covers its processing window. If the available API cannot establish that, keep the result unresolved. A fixed sleep does not establish safety.

Offer **Refresh status** and show known active orders. After definitive rejection, **Review rejected orders** creates a repair plan only for selected rejected quantities within the same logical grid, accounting for all surviving legs. It must not replay the original batch. Persist the repair attempt/revision and superseded-leg relationship; do not create an unrelated second grid.

## 9 Live data and reconciliation

The current app already has open-order and user-fill subscriptions, but it does not already wire the separate orderUpdates stream. Reuse current raw ingress plus bounded status reads for Release A. Adding orderUpdates is optional and must follow the existing WebSocket registry/reducer/effect contracts. [R13]

### 9.1 Required event envelope

Normalize incoming evidence before it reaches the lifecycle:

~~~clojure
{:kind :orders-snapshot         ; :fills / :order-status / :balance-snapshot / ...
 :scope scope
 :source :rest                 ; :websocket
 :request-id "<id>"
 :received-at-ms 0
 :observed-at-ms 0
 :snapshot? true
 :complete? true
 :account-event-revision "<local reconciliation boundary>"
 :rows [...]}
~~~

Do not invent a venue sequence number where none exists. Preserve source timestamps and use the application’s own event revision to detect races. Unknown completeness is false.

Consume raw orders before cancel-guard filtering. Never union stale REST orders with fresh WebSocket orders and treat the union as a current complete book.

### 9.2 Fill identity and retention

Use a normalized fill key containing network, account, market/coin, event time, and trade ID, plus order/side where needed to preserve distinct account fills. Validate the exact source identity contract in fixtures. Never deduplicate solely on a display timestamp, price, or `tid` across all assets.

Ingest snapshots and live increments idempotently. Preserve original order identity and execution attributes, including `crossed`, fee, fee token, and builder fee.

The existing display fill list retains only a bounded recent set. Feed the grid ledger from raw rows before that truncation. The display list is not the strategy ledger.

REST fill/history endpoints have retention and response limits. Add bounded time-window backfill, overlap deduplication, pagination/truncation detection, and a “no progress” guard. Never infer 30-day completeness merely because the oldest returned fill is recent or the ending balance matches. [P06]

### 9.3 Reconnect and suspended browser

Mark monitoring stale on disconnect or suspension. On resume, reconcile pending operations, current raw orders, balances, and missed fills before new placement.

Do not run a separate feature WebSocket or timer registry. Delegate subscription truth and fallback policy to the existing account-surface/runtime owners.

Opening a tab triggers coalesced reads; rendering does not. Dispose view-specific projections, overlays, observers, and timers on view unmount. Raw order/fill ingestion and durable reconciliation for active or unresolved grids belong to the account runtime and continue while that account session is observed, regardless of the selected route or dock tab.

On actual account-scope teardown or transport loss, mark monitoring and coverage stale. Reconcile before subsequent placement when the scope resumes. Resting orders remain fixed at the exchange.

### 9.4 Rate and capacity policy

Use the existing request scheduler and capacity model. Exchange batch IP weight is `1 + floor(n / 40)`; address-based usage counts the individual orders. Do not describe a 40-order batch as one weight unit or treat batching as removing all rate costs. [P07]

Prioritize reconciliation and explicit cancellation over background analytics. Poll with bounded backoff and jitter through the shared scheduler; avoid per-rung polling loops. Do not refresh every market’s daily candles on every account tick.

## 10 Cancel and replace workflow

### 10.1 Cancel this ladder

The action names the grid, account, market, and number of known live orders. Freeze the complete explicit nonterminal membership of that logical grid, including surviving orders from earlier repair attempts. Cancel each identified order once. Do not derive membership only from the latest revision or the family prefix. Prefer known OIDs; use CLOIDs when appropriate through the existing cancel gateway.

Record cancellation intent, send, and interpret each response. A cancellation acknowledgment updates the leg’s cancellation evidence, then reconcile fills and current state. A missing-order response may mean it already filled or was canceled elsewhere.

Do not use account-wide scheduled cancellation for a per-grid action. Do not filter all orders by prefix and cancel across unrelated markets or revisions.

### 10.2 Replace

The required user flow is:

1. **Preview update:** show old versus proposed anchor, volatility, tilt, prices, quantities, fees, and inventory bounds. Explain that cancellation and new placement are separate.
2. **Cancel current grid to update:** freeze the exact effective grid membership and identified cancellable legs across its revisions and repair attempts.
3. **Confirm cancellation:** reconcile all old legs, including fills occurring during cancellation. Unknown or surviving old legs block the new placement.
4. **Refresh:** obtain coherent balances, orders, fee context, and market data.
5. **Review replacement:** recalculate from the refreshed inventory and intended remaining quantities.
6. **Place replacement:** the user commits the new canonical revision through the normal placement lifecycle.

Use the intermediate message **Old ladder canceled. Review the updated quantities before placing the replacement.**

Preserve old revisions and all their fills. By default, the replacement proposes only the remaining unfilled quantity of the uniquely identified canceled working membership, adjusted for fills during cancellation. Do not sum duplicate historic revision copies or replenish already filled quantity. A user may explicitly change the new quantity before review.

If there were rejected legs in the old revision, show them separately. Include them in a replacement only through explicit selection; do not silently revive them.

If the user leaves after cancellation, the old grid remains canceled and no new orders are placed. If the replacement is partially rejected, surviving new legs stay visible and active.

Do not use immediate cancel-then-submit or an optimizer helper that automatically moves rejected ALO orders toward the book. Batch modification can be added later only after its precise failure/retention semantics are covered by contract tests.

## 11 Chart, dock, and interaction requirements

### 11.1 Chart integration

Add a proposed `views/trading_chart/utils/chart_interop/vol_range_overlays.cljs` with paired synchronization and cleanup functions. Thread its model through the existing chart VM, canvas context, animation-frame decoration pass, theme refresh, and unmount path.

Render:

- Subtle unadjusted reference bands at ±1 and ±2 daily-volatility units around the selected anchor.
- An anchor line and, when tilt is enabled, a separately labeled shifted order anchor.
- Exact staged rung prices, with restrained line-width/opacity encoding of base size.
- Labels distinguishing **Draft**, **Reviewed**, and **Deployed snapshot**.

Ghost orders have no live OID and no cancel action. Once exchange-confirmed overlays take over, remove corresponding ghosts to avoid duplicate lines. Viewing an old grid shows its stored parameters; current model conditions are a separate comparison.

Editing parameters must update decorations without remounting the chart, changing its timeframe/type, resetting the viewport, or disturbing indicators.

The ticker pill uses the same reference model and timestamps. For spot, do not add perp-only open-interest or funding labels.

### 11.2 Trading dock

Add `:vol-ladders` (label **Vol ladders**) as a trade-owned extra tab through `views/account_info/tab_registry.cljs` and `account-info-panel` options. Extend route-tab normalization and lazy renderer/module handling. Preserve the desktop dock’s outer dimensions.

Show one row per active/recovered grid with:

| Field | Meaning |
| --- | --- |
| Market and account | Full resolved identity, with human-readable labels. |
| Inventory / target | Current confirmed units and soft target. |
| Live / filled / rejected / checking | Explicit counts and remaining quantities. |
| Filled | Per side: filled base quantity and fill VWAP, plus fees by token. Release A data; label it as execution detail, not P&L. |
| Model at deployment | Anchor, daily volatility, tilt state, and creation time. |
| Current comparison | Changed model or inventory limits, with current observation time. |
| Actions | View orders, Refresh status, Review rejected orders, Preview update, Cancel this ladder. |

A detail panel shows each leg and typed rejection reason. When one of seven orders rejects, state **1 order was not placed. 6 other orders remain active.**

For operational attention, flag:

- Any unresolved outcome immediately.
- A stale required data source.
- A current inventory-limit breach or changed account capability.
- Anchor drift of at least 0.50 of the deployed daily-volatility unit. (At 6% daily volatility, 0.25σ is a 1.5% move and would flag continuously.) Keep the threshold in the versioned product-policy table.
- A relative daily-volatility change of at least 20%.

The drift thresholds are product heuristics for review. They never trigger repricing or recommend a buy/sell direction automatically.

### 11.3 Portfolio placement

Add a Portfolio-owned `:vol-range` tab through `views/portfolio/account_tabs.cljs::account-info-options`. Extend the Portfolio tab allowlist and relevant cached-section invalidation.

Use a proposed `views/portfolio/vol_range_report.cljs` view and a pure report projection. Preserve existing Portfolio route/query conventions; a dedicated route is not required.

### 11.4 Theme, responsiveness, and accessibility

Use current HyperOpen semantic tokens and theme sources. Buy and sell direction use `--ho-buy` and `--ho-sell`; warning states use `--ho-warn`. The mockups’ palette is illustrative. Do not hand-edit generated theme CSS.

The current chart token accessor is `hyperopen.views.trading-chart.utils.theme-colors/token`. Add overlay cleanup and re-theme behavior through that seam.

Keep the desktop right rail and stable chart/book/dock geometry. Stack side cards in a narrow rail; use progressive disclosure for estimator and tilt details. On small screens, use the existing responsive ticket/sheet pattern with visible costs and commit action.

Meet the repository’s WCAG 2.2 AA requirements: keyboard access, visible focus, semantic labels, non-color status cues, minimum target sizes, and no critical hover-only information. Announce submission/partial/unknown outcomes through an appropriate live region without announcing every market tick.

Use the existing Replicant conventions: class collections rather than space-separated class strings, and keyword style keys. Route all user numeric parsing through the locale-aware boundary.

## 12 Portfolio accounting and performance

### 12.1 Tracking campaign

Create one tracking campaign per network, account, base token, and supported USDC pair. It spans grid replacements and subsequent revisions; replacing orders does not reset performance.

At the first admitted placement, capture:

- The complete current spot base inventory `q0`, including held units.
- A fresh same-market valuation price `P0`, its source, and timestamp.
- A reconciled event boundary and the initial cumulative campaign cash flow `C0 = 0`.
- The campaign and accounting-model versions.

Record the starting market value as a reporting baseline, not a historical acquisition or tax cost basis. If historical cost is unknown, show **Cost basis unavailable**.

Do not allocate the wallet’s USDC balance to each campaign. A spending cap is not segregated capital. This avoids double-counting shared cash when several assets are tracked.

### 12.2 Fill ledger

Attribute fills through persisted order identity, not through maker status alone. Normalize side and monetary fields at the API boundary.

For gross filled quantity `x` at price `p`:

| Fill | Base change before fee | USDC cash change before fee |
| --- | ---: | ---: |
| Buy | +x | −p × x |
| Sell | −x | +p × x |

Subtract the signed fee from its actual token. A negative fee credits that token. Maintain cumulative net base `q_t` and cumulative USDC cash flow `C_t`. In this release an unsupported third-token fee causes an attribution break for dollar reporting; retain its actual token amount.

Capture all same-base account activity needed to detect contamination, including trades outside the feature and non-trading ledger changes. The known tagged fills remain useful even if headline attribution becomes incomplete.

### 12.3 Required headline equations

For a complete campaign interval:

~~~text
Net tracked asset P&L = C_t + q_t × P_t − q0 × P0
Hold P&L             = q0 × (P_t − P0)
Ladder contribution
  versus hold        = C_t + (q_t − q0) × P_t

Net tracked asset P&L = Hold P&L + Ladder contribution versus hold
~~~

This includes the mark-to-market effect of inventory that remains held. It also reveals the opportunity cost of selling inventory before further price increases.

These are **dollar contributions**, not account equity, capital efficiency, or percentage returns. The initial release must not manufacture a portfolio equity chart or a return denominator from a grid budget.

For a selected half-open subwindow `[a,b)`, use its starting reconstructed inventory and incremental cash flows:

~~~text
Net P&L[a,b) = (C_b − C_a) + q_b × P_b − q_a × P_a
Hold[a,b)    = q_a × (P_b − P_a)
Contribution[a,b) = (C_b − C_a) + (q_b − q_a) × P_b
~~~

The benchmark label is **Hold inventory at the selected window start**. Explain that this is a descriptive holding comparison, not a claim that every external wallet action would have remained feasible.

### 12.4 Marks and reporting windows

Offer **Since tracking began**, **7D**, and **30D**. Fixed-length reports end at the latest completed UTC hourly boundary and use same-market completed hourly spot closes for their boundary and chart marks. Show the exact start, end, and valuation timestamp.

Use a half-open event convention consistently: `q_a/C_a` reflect events strictly before `a`; `q_b/C_b` reflect events strictly before `b`; the selected fills and fees satisfy `a ≤ event_time < b`. `P_a` and `P_b` are the closes of the completed candles immediately preceding those boundaries. Apply this convention to chart checkpoints, FIFO matching, coverage, and exports.

The campaign’s inception point uses its captured fresh spot midpoint. Subsequent hourly marks use the completed-close series. A Since tracking began report becomes available once a later completed hourly mark exists; until then display the baseline and known execution activity.

At inception, use the explicitly reconciled event boundary to distinguish events already reflected in the starting balance from later events. A timestamp alone cannot order multiple events in the same millisecond. If that boundary cannot be established, report collection remains incomplete rather than assuming an opening balance.

A fixed window requires a reconstructed inventory checkpoint and complete events at its start. Do not extend the selected window backward before tracking began or fill a missing mark with zero. Preserve the valuation method and source timestamps in exports.

The P&L chart shows cumulative dollar P&L and its holding comparison at these same checkpoints. It does not label the vertical axis portfolio equity. Missing marks create gaps; incomplete intervals cannot produce an unqualified headline.

### 12.5 Matched fills and remaining inventory

Provide a secondary **Gross spread on matched fills** diagnostic. For the selected window, start empty buy/sell matching queues, sort confirmed fills deterministically by event time and stable identity, and match each fill against the oldest opposite-side unmatched fill. Match base quantity FIFO; split partial matches.

The queues contain gross, pre-fee filled quantities. Base-token fees change the account ledger separately; do not reduce matching quantities by fees and then subtract those fees again in the reconciliation below.

For each matched quantity:

~~~text
gross_matched_spread = matched_quantity × (sell_price − buy_price)
~~~

The matching may be buy-then-sell or sell-then-buy. Count matched base quantity and matched fill pairs explicitly; do not imply an exchange-defined completed “cycle.”

Mark unmatched gross buys at `quantity × (P_end − buy_price)`. Mark unmatched gross sells at `quantity × (sell_price − P_end)`; label these **Sold inventory not reaccumulated**, not an actual short position.

For base/USDC fees, a reconciliation view may show:

~~~text
Ladder contribution =
    gross matched spread
  + unmatched trade contribution
  − USDC fees
  − net base-token fees × end mark
~~~

Negative fees reverse the sign. Label the last two terms **Fee and rebate impact at the report mark**. Separately show actual paid/received token quantities; fee value at execution time may differ from its current marked impact.

Do not add matched spread to the headline again. Historical acquisition basis and “basis reduction” are not inferred from this matching convention.

### 12.6 Coverage and attribution breaks

Coverage states are `:complete`, `:partial`, `:gap`, `:attribution-break`, and `:unavailable`, each with a time interval and typed reason.

An untracked same-base trade, transfer, airdrop/dust change, unsupported fee token, unexplained balance change, or unprovable history gap breaks campaign attribution.

For a window crossing a break:

- Suppress Net tracked asset P&L, Hold P&L, and contribution-versus-hold headlines for the affected campaign.
- Continue showing independently known orders, fills, volumes, fees by token, and previous complete intervals.
- Explain the exact missing interval or external activity.
- Allow the user to start a new campaign from a freshly reconciled baseline.

Do not reset the baseline automatically or stitch a broken campaign into a continuous success history.

Ending-balance reconciliation is necessary but insufficient. An omitted buy and sell can leave base inventory unchanged while changing cash P&L. Completeness requires event/backfill coverage, not only matching balances.

### 12.7 Cross-asset aggregation

Sum dollar P&L across distinct base assets only when the account/network, USDC unit, reporting interval, and coverage agree. Every fill and fee belongs to exactly one campaign.

For **All tracked markets**, all included campaigns must cover the common selected interval. If one is incomplete, suppress the aggregate dollar headline. An explicitly chosen covered subset may be shown with that subset named.

No campaign contributes an allocated copy of the account’s USDC balance, so shared cash is not counted repeatedly.

### 12.8 Export and optional analytical extensions

Export normalized fills, order/revision mappings, signed fees by token, campaign baselines, source timestamps, coverage breaks, and the model versions required to reproduce the displayed calculations. Use the existing download infrastructure; exports contain no signing material.

After core reporting is accepted, optional fill markouts may measure signed price movement after 1 minute, 5 minutes, and 1 hour. Use the first valid same-market midpoint observation at or after the target time within a 5-second tolerance, and report sample coverage. Missing observations stay missing. Do not substitute a daily candle or claim all fills have measured adverse selection.

The later Spectate lens may show an inspected wallet’s observable resting orders against common market bands. It cannot infer that wallet’s full inventory intent, hedges, target, or protected core. Keep mutations unavailable in that view.

## 13 Deterministic acceptance fixtures

The mockups are layout references. These fixtures, typed contracts, and final canonical strings govern implementation behavior.

### 13.1 Volatility and tilt

| Fixture | Expected result |
| --- | --- |
| Fourteen valid days, each high 103 and low 97 | Raw daily Parkinson estimate approximately `0.03604448820648275`; canonical 12-place fraction `0.036044488206`. |
| Current time 2026-10-19 14:00 UTC | Use October 5–18; exclude October 19. |
| October 12 missing, October 4 available | Auto unavailable; do not substitute October 4. |
| One high/low string invalid | Auto unavailable; do not count a skipped day in the denominator. |
| Change custom anchor from 80 to 60 | Identical volatility estimate. |
| A=80, v=0.06, q=150, target=160, maximum=190, tilt off | Shifted anchor exactly 80. |
| Same inputs with protected core=120, tilt on, strength=0.45 | `inventory_delta = −10 / 40 = −0.25`; shifted anchor exactly `80.54`; order prices then follow legal quantization. |
| q=190, target=160, maximum=190, tilt on, strength=0.45, v=0.06, A=80 | `inventory_delta = +1`; shifted anchor exactly `77.84`. |
| q=170, target=maximum=170 | Zero denominator above target; delta is 0 because q equals target. With q=171 and maximum=170 (above-maximum corrective case), delta is +1. |
| Seven-order inputs, BBO 75.89 / 75.91 | Buy at 76.4 crosses. Offer **Anchor to market price** and **Start buys at 0.90σ** (76.4 → 75.68 at 0.90; 0.85 gives 75.92, which still crosses). |
| q=190, maximum=200, requested buys=50 | Reject buys for maximum-inventory breach. Proposed asks cannot offset the breach. |

### 13.2 Seven-order builder fixture

Use a **synthetic supported USDC spot market** with size decimals 2 and legal prices matching the values below. Use its resolved test asset ID; do not hardcode HYPE’s live metadata.

Inputs:

~~~text
Anchor:                       80
Daily volatility fraction:     0.06
Inventory tilt:                off
Current base inventory:        150
Protected core:                120
Target:                        160
Maximum:                       190
Available base:                150
Available USDC:                3000
Existing commitments:          none
Buy quantity:                  40
Maximum USDC debit:            2900
Buy rungs:                     4
Buy distances:                 0.75 to 2.25
Buy skew:                      1.857142857143
Sell quantity:                 30
Sell rungs:                    3
Sell distances:                1.25 to 2.25
Sell skew:                     1
BBO:                           79.99 / 80.01
~~~

The exact rational skew 13/7 corresponds to weights 7:9:11:13. The finite input above produces those same two-decimal-lot allocations under largest remainders. Do not label this fixture 2.25× skew.

| Side | Canonical price | Canonical size | Gross USDC notional |
| --- | ---: | ---: | ---: |
| Buy | 76.4 | 7 | 534.8 |
| Buy | 74 | 9 | 666 |
| Buy | 71.6 | 11 | 787.6 |
| Buy | 69.2 | 13 | 899.6 |
| Sell | 86 | 10 | 860 |
| Sell | 88.4 | 10 | 884 |
| Sell | 90.8 | 10 | 908 |

Expected totals:

- Buys: **40 base; 2,888 USDC gross; VWAP 72.20**.
- Sells: **30 base; 2,652 USDC gross; VWAP 88.40**.
- Gross inventory outcomes: **190 if all buys fill first; 120 if all sells fill first; 160 if all orders fill**.
- Quoted VWAP gap: **16.20 USDC per matched base unit**, about 22.4377% of buy VWAP. This is not a return on the full grid.

With a **synthetic** fee profile charging an additional four basis points in USDC on buys, the buy fee bound is 1.1552, maximum debit is 2,889.1552, and cap headroom is 10.8448. This fixture does not assert the live HYPE fee-settlement currency.

With a base-token fee/rebate profile, calculate and display the appropriate net outcomes instead; the gross 120/190 endpoints alone are not sufficient admission evidence.

### 13.3 Precision and allocation

| Input | Expected behavior |
| --- | --- |
| Base input 0.29, size decimals 2 | Exactly 29 lots; do not reduce it to 0.28. |
| Q=1, lot=0.01, three equal weights | Sizes 0.34, 0.33, 0.33; tie goes to the nearest rung. |
| Q=0.02, lot=0.01, three rungs | Invalid because a zero-sized leg results; do not emit two orders silently. |
| Spot raw price 0.0001234, size decimals 1 | Both directions keep 0.0001234. |
| Perp shared-helper raw price 0.0001234, size decimals 0 | Buy 0.000123; sell 0.000124. This tests the shared formatter, not perp feature eligibility. |
| Raw price 79.254, permitted decimals at least 3 | Keep 79.254. |
| Raw price 79.2546, permitted decimals at least 3 | Buy 79.254; sell 79.255. |
| Raw price 81129.4 | Buy 81129; sell 81130. |
| Integer price 123456 | Remains a legal integer under the significant-figure exception. |
| Raw price 9999.96, adequate decimal cap | Buy 9999.9; sell 10000; validate after the magnitude change. |
| Rungs 0.11 units at 100 and at 50 | First notional 11 passes; second 5.5 fails. Check both. |
| Rounding produces duplicate same-side prices | Invalid with explicit lower-count/wider-range remediation. |

### 13.4 Partial deployment and replacement

Starting from the seven-order fixture:

1. Return a definitive post-only rejection for the 7-unit buy at 76.4.
2. Return recognized resting outcomes for the remaining six orders.
3. Expect **6 live / 1 rejected**, with 33 live buy units and 30 live sell units.
4. Gross current live outcomes are **183 if buys fill first / 120 if sells fill first**.
5. Reviewing the rejected leg must not resend the six accepted orders.
6. Successfully repair that one logical leg using a newly reviewed safe price and new CLOID. Expect seven current live members and a historical rejection, not a permanently Partially deployed grid.
7. Cancel this ladder and verify that all seven current orders, including the six earlier survivors, are targeted exactly once.

For replacement, let 3 units of an old 10-unit sell fill while cancellation is pending. Confirm the remaining 7 units canceled. Expect current inventory reduced by the 3 units and actual base fees, preserve the fill, and rebuild from the remaining intended quantities. No replacement order may use the old pre-fill inventory snapshot.

Also test order visibility arriving before its balance hold, and a fill arriving before the balance debit. Neither event alone may release the pending funding reservation. Delay signing beyond the freshness windows, or inject a material fill after initial admission, and verify that the pre-HTTP guard prevents stale placement.

### 13.5 Reporting

| Scenario | Expected result |
| --- | --- |
| Start q0=150, P0=80; buy 7 at 76.4; mark 70; no fees | Net tracked P&L −1,544.8; Hold P&L −1,500; ladder contribution −44.8. |
| Start q0=150, P0=80; sell 30 for total 2,652; mark 100; no fees | Net tracked P&L +2,652; Hold P&L +3,000; ladder contribution −348. A profitable account can still lag holding. |
| Buy 10 at 80 with fee 0.004 base; mark 80 | Net base +9.996, cash −800, contribution −0.32. |
| A fill has fee 0.4 USDC and builderFee 0.1 already included | Total fee debit 0.4, not 0.5. |
| Buy 10 at 80 with fee 0.1 base; sell 9.9 at 90; end mark 100 | Gross matched spread 99; unmatched gross buy contribution 2; base-fee impact −10; net ladder contribution 91, equal to 891−800 with zero net base change. |
| Fill event timestamp equals the report end b | Excluded from [a,b) and included in the next interval; boundary inventory and FIFO use the same convention. |
| External same-base transfer during the selected window | Attribution break; affected dollar headlines unavailable. |
| Feed gap with a possibly missing buy/sell round trip, ending base unchanged | Coverage remains incomplete. Balance equality does not prove complete P&L. |
| Two disjoint assets share account USDC | Sum their transaction cash-flow contributions once; never insert the whole USDC balance into both campaigns. |

## 14 Acceptance test matrix

### 14.1 Required domain and boundary coverage

| ID | Acceptance contract |
| --- | --- |
| VR-01 | Legacy ordinary Scale behaves identically. Vol Range fields survive normalization → ownership → save-many and scoped restore; a separate unscoped durable-export projection excludes scoped values. |
| VR-02 | Numeric-string candles, completed-day selection, duplicate conflict, zero estimate, missing data, and manual percent conversion follow Section 5. |
| VR-03 | Geometry and lot allocation reproduce the fixtures, including K=1, finite-value rejection, and exact requested totals. |
| VR-04 | Shared precision respects legal prices, integer exception, directional rounding, and decimal-lot edge cases. |
| VR-05 | Every final rung passes minimum-notional, distinct-price, BBO, self-cross, and product-distance checks. |
| VR-06 | Cash and inventory admission count commitments once; order visibility or early fill events cannot release reservations before hold/settlement accounting. No prospective netting or fee double-counting. |
| VR-07 | Above-maximum sell-only and below-core buy-only corrective plans work without worsening the pre-existing violation. |
| VR-08 | Unsupported raw account modes, unknown token identity, stale fee fallbacks, missing balance provenance, and wrong-network endpoints are blocked at submit policy, not just UI. |
| VR-09 | Reviewed and signed actions match. Delayed signing, unlocking, identity/precision changes, and intervening fills trigger the final pre-HTTP admission guard. |
| VR-10 | Multiple action producers and tabs sharing an agent reserve distinct nonces before send; account cash reservations cannot race. |
| VR-11 | Transaction abort after request success prevents placement. Exercise actual storage failure through the shared coordinator; identified cancellation remains possible without a grid-journal commit or signer bypass. |
| VR-12 | Mixed statuses, empty/wrong-length vectors, unfamiliar entries, and whole-action rejection normalize correctly. |
| VR-13 | Timeout/reload after possible send recovers original IDs and never retries the full grid blindly. |
| VR-14 | A fill before the HTTP acknowledgment is counted once after stream reconciliation. Duplicate snapshots are idempotent. |
| VR-15 | Optimistic cancel filtering cannot prove terminality. Cancel/fill races and partial cancel failure block unsafe replacement. |
| VR-16 | Repair retains all surviving logical-grid members; Cancel/Replace targets each once. Replacement uses refreshed remaining quantities; abandonment after cancel never places new orders. |
| VR-17 | Missing local manifests recover visible orders without inventing settings or performance; conflicts block a second local grid. |
| VR-18 | Account/market/network changes cannot retarget an operation or project its completion into another scope. |
| VR-19 | Route changes preserve account-runtime ingestion. Reconnect, backfill caps, pagination stalls, and display-list truncation preserve coverage and uncertainty. |
| VR-20 | Reporting equations, fee-token effects, unmatched trades, selected-window baselines, and cross-asset sums reconcile exactly. |
| VR-21 | External activity and incomplete evidence suppress affected headlines while preserving known execution data. |
| VR-22 | Error remediation changes only the draft and requires fresh review; no hidden clamp, resize, rung drop, or taker fallback occurs. |
| VR-23 | Crossing remediations, **Set to N (all buys filled)**, and **Use plan total** each produce exactly the documented draft values, are offered only when they yield a ready plan, and never modify live orders. |
| VR-24 | Release A0 preview cannot construct a signable action, reserve a nonce, or write an intent, regardless of flag combination. |

### 14.2 Browser and visual coverage

Use deterministic exchange/wallet simulators and the repository’s guarded Playwright support. Add a proposed focused `tools/playwright/test/vol-range.spec.mjs`.

Verify:

- Price range / Volatility range switching and independent side-card controls.
- Seven-order fixture totals, clear fees, protected core, maximum, and commit labels.
- Disabled/unavailable, loading, validation, live, partial, checking, canceling, recovered, and closed states.
- Scope changes during review/unlock/send and persistent read-only behavior in Spectate.
- Double click, Enter-key behavior, reload recovery, rejected-leg review, and cancel-before-replacement barriers.
- The Vol ladders tab repainting when its lazy module arrives without a new WebSocket event.
- Chart edits without remount, viewport reset, stale ghost duplication, or leaked overlays after unmount.
- Portfolio filters and report revisions updating during existing hover/cache behavior.
- Keyboard flow, focus return, screen-reader status, errors, and visible review details.
- Stable desktop shell and usable layouts at 375, 768, 1280, and 1440 CSS pixels.
- All supported themes, using the repository’s visual, native-control, styling, interaction, layout, and jank/performance review passes.

Add stable roles such as `vol-range-mode`, `vol-range-review`, `vol-range-submit`, and `vol-ladder-status` where consistent with existing test conventions. Do not make tests depend only on incidental CSS or the current asset’s live price.

### 14.3 Nearby existing test seams

Extend or use as patterns:

- `test/hyperopen/state/trading/order_form_state_test.cljs`
- `test/hyperopen/trading/order_form_transitions_update_test.cljs`
- `test/hyperopen/state/trading/order_form_ownership_formal_conformance_test.cljs`
- `test/hyperopen/state/trading/identity_and_submit_policy_test.cljs`
- `test/hyperopen/state/trading/validation_and_scale_test.cljs`
- `test/hyperopen/state/trading/scale_pm_summary_test.cljs`
- `test/hyperopen/views/trade/order_form_vm_test.cljs`
- `test/hyperopen/views/trade/order_form_view/scale_preview_test.cljs`
- `test/hyperopen/views/trade_view/lazy_tab_module_repaint_test.cljs`
- Existing chart runtime/overlay, account navigation, order effects, signing, storage, and Portfolio contract suites.

When new test namespaces or formal contract surfaces are added, use the existing generators and conformance workflow. Do not hand-edit generated outputs as the source of truth.

## 15 Implementation sequence and release gates

### 15.1 Suggested pull requests

| PR | Scope | Exit condition |
| --- | --- | --- |
| 1 | Add the product spec and ExecPlan; define versioned contracts, capability matrix, and invariant ownership; implement shared exact precision. | Numeric fixtures and existing price/size compatibility coverage pass. |
| 2 | Implement normalized daily data, pure volatility/geometry/allocation/admission, remediations, and prepared-plan projection. | All deterministic planner, remediation, and cash/inventory fixtures pass; no direct I/O in domain decisions. |
| 3 | **Release A0:** Scale ticket mode, chart overlays, and read-only review behind the preview flag; contracts, commands, narrow projections, and theme behavior. | Focused browser suite for draft/review states passes; VR-24 passes; ordinary Scale remains compatible. |
| 4 | Add committed journal writes, shared nonce/account coordination, strict result normalization, and scoped raw read barriers. | Storage, concurrency, uncertain-delivery, account-targeting, and cancellation-boundary tests pass with no UI feature enabled. |
| 5 | Implement grid lifecycle, persisted mapping, fill ingestion, reconciliation, cancellation, and replacement through existing trading facade. | Simulator scenarios prove partial/unknown outcomes, reload recovery, and cancel/fill race behavior. |
| 6 | Add the commit action, Vol ladders dock (including per-side fill summary), lazy loading, and live/partial/checking/recovered states. | Full focused browser suite and required UI review pass. |
| 7 | Enable supported spot capabilities behind the execution flag after protocol fixtures and end-to-end gates. | Release A checklist below is complete. |
| 8 | Add campaign/report calculations, coverage, Portfolio tab, and exports. | Accounting fixtures and incomplete-history UX pass; Release B enabled independently. |

PRs 2–3 may proceed in parallel with PR 4. PRs 1 and 4 are deliberately visible foundation work. Do not hide changes to common signing, rounding, or persistence inside a large UI patch.

The original two-sprint estimate is not an acceptance requirement. Estimate delivery after the foundation changes and protocol fixtures are understood.

### 15.2 Feature flags

Use separate feature capabilities for preview (Release A0), execution, and reporting. The execution flag can disable new placement while preserving live-order visibility, reconciliation, and cancellation.

Gate market/account combinations explicitly. A new account-mode or exchange schema value defaults to unsupported. Reporting does not imply execution permission, and Spectate does not inherit mutation capability.

### 15.3 Release A checklist

- [ ] Correct spot/token/account identity and raw account-mode fixtures.
- [ ] Valid current volatility and anchor source behavior.
- [ ] Exact canonical vector, all-leg validation, fee-token reserve rules, and one-side-fill bounds.
- [ ] Single reviewed action, mandatory ALO, and no hidden leverage/transfer/taker behavior.
- [ ] Durable intent before send, unique signer nonces, and scoped pending reservations.
- [ ] Strict partial/unknown results and reload/reconnect reconciliation.
- [ ] Cancel/fill race protection and reviewed remaining-quantity replacement.
- [ ] Discovered-order recovery and multi-tab/account isolation.
- [ ] Responsive, accessible ticket/dock/chart with truthful lifecycle and freshness.
- [ ] Required repository gates and targeted browser coverage pass.
- [ ] Rollout flag preserves cancellation when new placement is disabled.

Release B additionally requires complete campaign boundaries, reproducible ledger/mark methods, honest coverage, and no fabricated equity or cost basis.

### 15.4 Validation commands

Run the repository’s required commands in the implementer’s checkout. These are validation instructions, not results of this specification handoff.

~~~bash
npm run setup:worktree
npm run test:runner:generate
npx playwright test tools/playwright/test/vol-range.spec.mjs
npm run gates
~~~

`npm run gates` aggregates `npm run check`, `npm test`, and `npm run test:websocket`. Run the focused browser suite first once it exists, then the smallest relevant existing regressions and required smoke tests.

For UI/style work, use the canonical browser-QA and theme workflows:

~~~bash
npm run test:playwright:smoke
npm run test:styles
npm run lint:theme-colors
npm run qa:design-ui -- --targets trade-route --manage-local-app
npm run browser:cleanup
~~~

Verify the current registered Portfolio design-review target before adding it to a command. Regenerate theme CSS only if its palette source changed.

Every implementation PR must state the invariant owner, changed public seams, commands actually run, outcomes, and remaining release gates. Follow the current repository AGENTS, ExecPlan, and role-ownership requirements.

## 16 Operational diagnostics and performance

Use the existing telemetry boundary for typed events: preview unavailable/invalid, review invalidated, admitted operation, send result category, per-leg rejection category, unknown duration, reconciliation result, cancel completion, and coverage break.

Correlate with local operation/plan IDs and model versions. Do not log keys, signatures, raw signed bodies, full wallet inventories, or sensitive draft quantities to remote telemetry by default.

Useful release measures are placement rejection rate by reason, unresolved-outcome duration, cancellation completion time, preview-to-wire mismatches, duplicate-intent incidents, and report-coverage availability. Trading profit is not an execution-correctness metric.

Keep calculator work proportional to at most 24 rungs plus the scoped commitments. Recompute daily volatility on sample/version changes, not each price tick. Memoize stable projections and use the chart’s existing animation-frame decoration pass. Profile on the repository’s mobile and desktop QA fixtures; parameter editing must remain responsive and must not increase unrelated route bundle work.

## 17 Handoff and source references

### 17.1 Repository placement

Suggested destination in the implementer’s checkout:

~~~text
docs/product-specs/vol-range-implementation-spec.md
~~~

Add it to the product-spec index. At implementation start create an ExecPlan under the current active-plan directory, linked to this spec and its tracking issue/PR. Preserve the required Progress, Surprises and Discoveries, Decision Log, and Outcomes sections. Do not mark implementation work complete merely because the product specification is complete.

Existing paths below were inspected at the pinned commit. Proposed modules in this document are implementation choices, not claims that those files already exist.

### 17.2 Existing repository references

| Reference | Source and relevance |
| --- | --- |
| R01 | [AGENTS and required implementation gates][R01] |
| R02 | [Architecture and persistence boundaries][R02] |
| R03 | [Trading ownership boundary][R03] |
| R04 | [Order-form defaults and normalization][R04] |
| R05 | [Trading state and shared submit policy][R05] |
| R06 | [Trading domain Scale allocation and totals][R06] |
| R07 | [Market identity, availability, and price/size formatting][R07] |
| R08 | [Exchange request gateway][R08] |
| R09 | [Order mutation effects and result handling][R09] |
| R10 | [Effective fee model][R10] |
| R11 | [Agent action signing and nonce orchestration][R11] |
| R12 | [Shared IndexedDB implementation][R12] |
| R13 | [Raw account WebSocket handlers][R13] and [subscriptions][R13S] |
| R14 | [Account abstraction and account read endpoints][R14] |
| R15 | [Trade view memoized state slices][R15] |
| R16 | [Account-panel tab extension mechanism][R16] |
| R17 | [Portfolio-owned extra tabs][R17] |
| R18 | [Chart runtime decoration and cleanup][R18] |
| R19 | [Theme palette source][R19] |
| R20 | [Browser storage policy][R20], [reliability][R20R], and [planning contract][R20P] |

### 17.3 Protocol and model references

Official protocol documentation was checked on 2026-10-19. Revalidate changed contracts against the current venue and capture fixtures before enabling execution.

| Reference | Source and relevance |
| --- | --- |
| P01 | [Hyperliquid exchange endpoint][P01]: order/cancel fields, ALO, CLOIDs, expiration, and per-order responses. |
| P02 | [Tick and lot size][P02]: significant figures, decimal limits, and integer exception. |
| P03 | [Error responses][P03]: order minimums and typed failure categories. |
| P04 | [WebSocket subscriptions and fill schema][P04]: account streams, fee token, rebates, builder-fee inclusion, and execution attributes. |
| P05 | [Nonces and API wallets][P05]: signer-level nonce behavior and API-wallet scope. |
| P06 | [Info endpoint][P06]: orders, status, candles, fills, and history limits. |
| P07 | [Rate and user limits][P07]: batch weighting and account constraints. |
| P08 | [Parkinson high-low volatility paper][P08]: the estimator used by the model. |
| P09 | [Avellaneda and Stoikov][P09]: background inventory-quoting framework; not a claim that this feature implements the complete model. |

The economic thesis remains a user decision. The implementer’s responsibility is to make the chosen orders, exposure, execution state, and measured results exact and inspectable.

[R01]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/AGENTS.md
[R02]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/ARCHITECTURE.md
[R03]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/trading/BOUNDARY.md
[R04]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/trading/order_form_state.cljs
[R05]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/state/trading.cljs
[R06]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/domain/trading/core.cljs
[R07]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/domain/trading/market.cljs
[R08]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/api/gateway/orders/commands.cljs
[R09]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/order/effects.cljs
[R10]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/domain/trading/fees.cljs
[R11]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/api/trading/agent_actions.cljs
[R12]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/platform/indexed_db.cljs
[R13]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/websocket/user_runtime/handlers.cljs
[R13S]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/websocket/user_runtime/subscriptions.cljs
[R14]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/api/endpoints/account/portfolio.cljs
[R15]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/views/trade_view.cljs
[R16]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/views/account_info/tab_registry.cljs
[R17]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/views/portfolio/account_tabs.cljs
[R18]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/hyperopen/views/trading_chart/runtime.cljs
[R19]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/src/styles/themes/palette.js
[R20]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/docs/BROWSER_STORAGE.md
[R20R]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/docs/RELIABILITY.md
[R20P]: https://github.com/thegeronimo/hyperopen/blob/b4174f85fcd90610ddbb7ac8a015fdd899e62df7/docs/PLANS.md
[P01]: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/exchange-endpoint
[P02]: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/tick-and-lot-size
[P03]: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/error-responses
[P04]: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/websocket/subscriptions
[P05]: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/nonces-and-api-wallets
[P06]: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint
[P07]: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/rate-limits-and-user-limits
[P08]: https://www.cmegroup.com/trading/fx/files/michael_parkinson.pdf
[P09]: https://math.nyu.edu/inmemoriam/avellaneda/HighFrequencyTrading.pdf
