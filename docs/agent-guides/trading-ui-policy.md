# Hyperopen Trading UI Policy for Agents

## Purpose and Scope
- This guide applies to trading UI work in `/hyperopen/src/hyperopen/views/**` and `/hyperopen/src/styles/**`.
- Use this guide with `/hyperopen/docs/FRONTEND.md` and `/hyperopen/docs/agent-guides/ui-foundations.md`.
- If guidance conflicts, `/hyperopen/docs/FRONTEND.md` wins.

## Prime Directive (MUST)
- MUST prevent expensive mistakes (wrong account, instrument, side, size, or order type).
- MUST communicate runtime truth (live vs delayed, pending vs confirmed, success vs failure).
- MUST stay stable under live updates (no flicker, unexpected reordering, or control jumps).
- MUST remain fully operable with keyboard and accessible semantics.

## Non-Negotiables (MUST)
- MUST prioritize clarity over cleverness for labels, copy, and controls.
- MUST prioritize error prevention over post-submit error messaging.
- MUST keep critical state explicit: account, selected instrument, exposure, data freshness, and order lifecycle.
- MUST keep terminology, number formatting, status labels, and shortcuts consistent.

## Trading Interaction Rules (MUST)
- MUST display full instrument identity where ambiguity is possible: symbol plus venue/type and derivative specifics when relevant.
- MUST keep trade side explicit in text (for example `Buy`, `Sell`, `Sell Short`) and not color-only.
- MUST model orders as state transitions, not one-off events.
- MUST render order lifecycle states consistently with status label, timestamps, and human-readable failure reason.
- MUST NOT present `Working` or `Filled` before backend confirmation; optimistic updates MUST be labeled pending.
- MUST show risk context near submit: estimated cost/credit, fee note, buying power or margin impact, post-trade estimate, and market snapshot timestamp.
- MUST block placement until validation passes and show inline field-level remediation.

## Data and Time Display Rules (MUST)
- MUST use uniform money/price/quantity formatting with explicit currency symbols where applicable.
- MUST include `+` or `-` signs for deltas and avoid color-only gain/loss encoding.
- MUST show timezone context and data freshness when staleness is possible.
- MUST show market session context (open/closed/pre/post) where execution behavior changes.
- MUST keep Trade History value columns `Price`, `Size`, `Trade Value`, `Fee`, and `Closed PNL` left-aligned in both header and row cells so values start directly under the header labels.
- MUST NOT apply right-alignment utilities (for example `text-right` or `num-right`) to those Trade History value columns.

## Instrument Rendering Rules (MUST)
- MUST render namespaced instrument identifiers as base symbol text plus a separate venue/type chip in table/list cells (for example `xyz:NVDA` renders as `NVDA` with an `xyz` chip).
- MUST NOT render raw namespaced identifiers (for example `xyz:NVDA`) as the primary visible coin label when a parsed/base symbol is available.
- MUST render size/quantity text using the base symbol only (for example `0.500 NVDA`), without namespace/type prefixes in the size suffix.

## Order Ticket and Confirmation Rules
- MUST support this ticket sequence: instrument, side, quantity, order type, conditional price fields, time-in-force, estimate context, and submit path.
- SHOULD include a review step before submit for high-impact actions.
- MUST require explicit confirmation for high-risk actions such as large notionals, close-all/cancel-all, and account-switch with draft orders.
- MUST represent cancel and replace as explicit in-flight states and prevent conflicting actions while requests are in-flight.

## Real-Time and Performance Rules (MUST)
- MUST decouple ingestion rate from render rate and throttle render updates for high-frequency streams.
- MUST keep layout and table geometry stable during live updates.
- MUST virtualize long frequently-updating lists.
- MUST show persistent connection health and data freshness status.
- MUST freeze and label stale values as stale; MUST NOT display fake zeros/placeholders as real data.
- MUST use skeletons for structured loading and avoid full-screen blocking for non-critical loads.

## Error, Resilience, and Trust Rules (MUST)
- MUST classify and present errors consistently: validation, submission/network, rejection, authorization, and system status.
- MUST explain what happened, why it happened, and what the user can do next.
- MUST prevent accidental double submit and support idempotent submission behavior.
- MUST disable primary submit while in-flight and re-enable with clear recovery state if it fails.
- MUST keep cancellation paths clear and no harder than submission paths.
- MUST keep dangerous hotkeys opt-in and clearly visible if supported.

## Architecture and Observability Rules (MUST)
- MUST keep server state (orders, positions, quotes) separate from local UI state (drafts, open panels, expanded rows).
- MUST keep websocket logic out of render components.
- MUST centralize formatting utilities for price, quantity, and timestamp rendering.
- MUST parse user-entered numeric text through `/hyperopen/src/hyperopen/utils/parse.cljs` locale-aware helpers at action/transition boundaries.
- MUST NOT parse user-entered decimals directly with `js/parseFloat` or hand-rolled comma/period replacement in UI flow code.
- MUST instrument order lifecycle and feed-health events for diagnostics.

## Default Decisions (SHOULD)
- SHOULD use a right-rail order ticket on desktop and a drawer on smaller screens.
- SHOULD favor stable layout over dynamic reflow.
- SHOULD prefer semantic HTML controls over custom div-based controls.
- SHOULD prefer tokenized styling over one-off ad hoc classes.

## Minimal Gold Standard Order Flow (Reference)
1. User selects instrument.
2. Market data loads and freshness is visible.
3. User configures ticket fields with inline validation.
4. Review summarizes account, instrument, side, size, type, estimates, and timestamp.
5. Submit enters `Submitting...` with a local client identifier.
6. Backend confirmation transitions to `Accepted` and then `Working`.
7. Partial and full fills update lifecycle state.
8. Reject/cancel/replace outcomes include clear reason and next step.
9. Activity history preserves audit-ready status transitions.

## Implementation Anchors (Current Code)
- `/hyperopen/src/hyperopen/views/trade/order_form_view.cljs`
- `/hyperopen/src/hyperopen/views/account_info_view.cljs`
- `/hyperopen/src/hyperopen/views/l2_orderbook_view.cljs`
- `/hyperopen/src/hyperopen/views/active_asset_view.cljs`
- `/hyperopen/src/styles/main.css`
