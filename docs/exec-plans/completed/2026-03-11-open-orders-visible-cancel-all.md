# Open Orders Visible-Scope Cancel All

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md`.

Completed `bd` issue: `hyperopen-ybl`.

## Purpose / Big Picture

After this change, the `Cancel All` header in the Open Orders table becomes a real control that cancels only the orders the user can currently see in that table. If the user narrows the table with the direction filter or the coin search, the cancel action targets only those visible rows and leaves hidden orders untouched.

A user can verify the result by filtering Open Orders down to a subset, clicking the `Cancel All` header control, confirming the destructive action, and observing that only the visible orders disappear and are sent through the cancel pipeline.

## Progress

- [x] (2026-03-11 14:17Z) Audited the existing Open Orders view, cancel action flow, runtime contracts, and validation docs.
- [x] (2026-03-11 14:18Z) Created and claimed `bd` issue `hyperopen-ybl` for this work.
- [x] (2026-03-11 14:19Z) Verified from Hyperliquid documentation that cancel requests accept a `cancels` array and are supported as batched cancels in one exchange request.
- [x] (2026-03-11 14:20Z) Created this active ExecPlan.
- [x] (2026-03-11 14:27Z) Implemented header-triggered visible-scope cancel confirmation, batched cancel request building, and runtime wiring.
- [x] (2026-03-11 14:30Z) Added regression coverage for visible-row targeting, batched cancel request construction, confirmation dispatch, and partial batch cancel handling.
- [x] (2026-03-11 14:34Z) Installed local Node dependencies with `npm ci`, ran `npm run check`, `npm test`, and `npm run test:websocket`, and prepared this plan for move to completed.

## Surprises & Discoveries

- Observation: The Open Orders table already computes the exact visible row set after direction filtering, coin search, and sorting in one place.
  Evidence: `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs` `memoized-sorted-open-orders` returns the same ordered row set the table renders.

- Observation: The current cancel request shape already uses the exchange `cancel` action with a `cancels` vector, so the data model is already close to batch support.
  Evidence: `/hyperopen/src/hyperopen/api/trading.cljs` `build-cancel-order-request` returns `{:action {:type "cancel" :cancels [...]}}`.

- Observation: Repository trading UI policy requires explicit confirmation for cancel-all flows, so an immediate one-click destructive header action would violate local UI rules.
  Evidence: `/hyperopen/docs/agent-guides/trading-ui-policy.md` states that high-risk actions such as close-all and cancel-all must require explicit confirmation.

- Observation: The existing cancel effect treated any top-level exchange `ok` response as full success, which is unsafe for batched cancels because per-entry statuses can still contain errors.
  Evidence: `/hyperopen/src/hyperopen/order/effects.cljs` previously pruned every targeted order whenever `(:status resp)` equaled `"ok"`.

- Observation: The workspace initially lacked `node_modules`, so the exact `npm test` script could not resolve `shadow-cljs` and runtime JS dependencies until local packages were installed.
  Evidence: Pre-install validation failed with `Cannot find module '@noble/secp256k1'`; `npm ci` resolved the environment and the required gates passed afterward.

## Decision Log

- Decision: Implement visible-row canceling as one batched Hyperliquid cancel request rather than N separate cancel requests.
  Rationale: Hyperliquid’s exchange cancel action accepts a `cancels` array, and the official rate-limit docs describe batched cancels explicitly. One request keeps the UI deterministic, reduces request fan-out, and aligns with the exchange interface we already use.
  Date/Author: 2026-03-11 / Codex

- Decision: Require an explicit confirmation step before issuing the visible-scope cancel-all request.
  Rationale: Local trading UI policy makes cancel-all a high-risk action that must not fire immediately. A confirmation effect preserves the requested header interaction while keeping the behavior compliant.
  Date/Author: 2026-03-11 / Codex

- Decision: Treat the request builder as all-or-nothing for the visible order set.
  Rationale: The user intent is “cancel everything currently shown.” If any visible row cannot be converted into a valid cancel target, silently dropping it would create misleading partial behavior. Failing fast is safer and easier to reason about.
  Date/Author: 2026-03-11 / Codex

- Decision: Parse cancel batch outcomes per status entry and prune only successful cancel targets.
  Rationale: A batched cancel can succeed for some orders and fail for others. Preserving failed rows while removing successful ones matches the exchange truth and prevents false “all canceled” UI state.
  Date/Author: 2026-03-11 / Codex

## Outcomes & Retrospective

Implementation completed successfully. The Open Orders `Cancel All` header now dispatches a confirmation step and, after confirmation, cancels only the currently visible rows with one batched Hyperliquid `cancel` action. Hidden rows outside the active filter/search remain untouched.

The cancel pipeline now understands partial batch responses. Successful cancels are pruned immediately, failed cancels are restored to view, and the toast/error state explains that the batch only partially succeeded when appropriate.

Validation outcomes:

- `npm run check`: pass
- `npm test`: pass (`Ran 2264 tests containing 11805 assertions. 0 failures, 0 errors.`)
- `npm run test:websocket`: pass (`Ran 372 tests containing 2128 assertions. 0 failures, 0 errors.`)

Retrospective: Overall complexity increased slightly because the change adds one confirmation effect and one batched-request helper, but it reduced behavioral risk by centralizing bulk cancel behavior inside the existing order-cancel pipeline instead of spawning many ad hoc requests from the view layer.

## Context and Orientation

Open Orders UI rendering lives in `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs`. That file normalizes and sorts the rows, then renders the table header and row-level `Cancel` buttons. The table already has a `Cancel All` header label, but it is currently just static text.

Order-cancel request construction lives in `/hyperopen/src/hyperopen/api/trading.cljs`. Today the public helper `build-cancel-order-request` converts one order row into the exchange `cancel` action shape.

Cancel action orchestration lives in `/hyperopen/src/hyperopen/order/actions.cljs`. That module validates trading preconditions, marks targeted order ids as pending cancel in local state, and emits `:effects/api-cancel-order`.

The asynchronous cancel effect lives in `/hyperopen/src/hyperopen/order/effects.cljs`. It sends the signed exchange request, prunes optimistically hidden orders on success, restores pending state on failure, refreshes account surfaces, and shows the order feedback toast.

Runtime action/effect wiring lives in:

- `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs`
- `/hyperopen/src/hyperopen/app/effects.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters.cljs`
- `/hyperopen/src/hyperopen/runtime/effect_adapters/order.cljs`

Regression coverage already exists near the touched seams:

- Open Orders view tests: `/hyperopen/test/hyperopen/views/account_info/tabs/open_orders_test.cljs`
- Order action tests: `/hyperopen/test/hyperopen/core_bootstrap/order_entry_actions_test.cljs`
- Order effect tests: `/hyperopen/test/hyperopen/core_bootstrap/order_effects_test.cljs`
- Trading API cancel request tests: `/hyperopen/test/hyperopen/api/trading/cancel_request_test.cljs`
- Account-history state/action tests: `/hyperopen/test/hyperopen/account/history/actions_test.cljs`

In Hyperliquid’s exchange API, a cancel request is one signed action with type `cancel` and a `cancels` array. Each entry describes one target order. The API can therefore cancel multiple orders in one request, but the response still reports per-entry statuses, so Hyperopen must handle batch success and batch failure carefully.

## Plan of Work

First, update `/hyperopen/src/hyperopen/api/trading.cljs` so it can build a batched cancel request from a sequence of visible order rows. Keep the existing single-order helper intact for compatibility, but add a sequence-oriented helper that either returns a complete batched request or `nil` if any visible row cannot be converted safely.

Next, extend `/hyperopen/src/hyperopen/order/actions.cljs` with a new visible-open-orders cancel action that reuses the existing pending-cancel projection flow and emits the existing `:effects/api-cancel-order` effect with the new batched request. Add a separate confirmation-trigger action that emits a dedicated confirmation effect instead of calling the browser confirm dialog from the action layer.

Then, add the confirmation effect in the order effect-adapter path and wire the new action/effect ids through runtime collaborators, contracts, catalog registration, public action aliases, and the effect-order contract. The confirmation message should explicitly tell the user that only the currently visible Open Orders rows will be canceled.

After that, update `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs` so the `Cancel All` header renders as a keyboard-accessible button that dispatches the confirmation-trigger action with the exact rendered row set. Preserve current row-level cancel behavior.

Finally, make `/hyperopen/src/hyperopen/order/effects.cljs` batch-aware when handling the exchange response. A top-level `ok` response is not sufficient for a batch; the code must inspect per-cancel statuses so it only prunes successfully canceled orders, restores failed ones, and shows a precise success or partial-failure message.

## Concrete Steps

Run from `/hyperopen`:

1. Edit `/hyperopen/src/hyperopen/api/trading.cljs` to add a batched cancel request builder and test it in `/hyperopen/test/hyperopen/api/trading/cancel_request_test.cljs`.
2. Edit `/hyperopen/src/hyperopen/order/actions.cljs` and the runtime registration/contract files so visible-order confirmation and batched cancel are first-class actions/effects.
3. Edit `/hyperopen/src/hyperopen/order/effects.cljs` so batched cancel responses handle per-entry statuses correctly.
4. Edit `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs` and its tests so the `Cancel All` header is an actionable button tied to the rendered row set.
5. Run:

   npm run check
   npm test
   npm run test:websocket

Expected validation outcomes at completion:

- `npm run check` passes with no doc or contract drift.
- `npm test` passes with new regressions covering visible-scope cancel-all and batch cancel semantics.
- `npm run test:websocket` remains green because the work does not alter websocket behavior.

Observed completion transcript highlights:

- `npm run check`: completed with docs/lint checks green and all required Shadow builds compiled successfully.
- `npm test`: `Ran 2264 tests containing 11805 assertions. 0 failures, 0 errors.`
- `npm run test:websocket`: `Ran 372 tests containing 2128 assertions. 0 failures, 0 errors.`

## Validation and Acceptance

Acceptance is complete only when all of the following are true:

- The Open Orders table shows a clickable `Cancel All` header control whenever rows are present.
- Clicking that control asks for explicit confirmation before any cancel request is sent.
- Confirming the action with a filtered or searched table cancels only the rows currently rendered in that table.
- The cancel request reaches Hyperliquid as one batched `cancel` action containing one `cancels` entry per visible order.
- If the exchange partially rejects a batch, Hyperopen keeps failed orders visible again and surfaces a failure message instead of pretending the entire batch succeeded.
- `npm run check`, `npm test`, and `npm run test:websocket` all pass.

## Idempotence and Recovery

The edits are additive and safe to re-run. Repeated confirmation clicks should only emit a new cancel request when the user confirms again.

If a batch-response handling change causes regressions, recover by reverting the new batch parsing logic in `/hyperopen/src/hyperopen/order/effects.cljs` and re-running the test suite. If the new header action causes UI regressions, revert the header button wiring in `/hyperopen/src/hyperopen/views/account_info/tabs/open_orders.cljs` while leaving the lower-level batch request helper in place.

No migrations, remote sync, or destructive repository operations are required.

## Artifacts and Notes

Important external implementation note captured from official Hyperliquid documentation:

- The exchange `cancel` action accepts a `cancels` array, so multiple open orders can be canceled in one signed request.
- Hyperliquid’s rate-limit documentation explicitly calls out batched cancels, which confirms the intended request shape is one request with N cancels, not N isolated requests.

## Interfaces and Dependencies

Existing interfaces that must remain compatible:

- `hyperopen.api.trading/build-cancel-order-request`
- `hyperopen.order.actions/cancel-order`
- `hyperopen.order.effects/api-cancel-order`

New interfaces expected after implementation:

- `hyperopen.api.trading/build-cancel-orders-request`
- `:actions/confirm-cancel-open-orders`
- `:actions/cancel-open-orders`
- `:effects/confirm-cancel-open-orders`

The new order action must continue to reuse `:effects/api-cancel-order` for the actual network operation so all order-cancel side effects stay centralized in one effect pipeline.

Plan revision note: 2026-03-11 14:20Z - Initial plan created after auditing the current cancel flow, creating `bd` issue `hyperopen-ybl`, and confirming Hyperliquid batched cancel support.
Plan revision note: 2026-03-11 14:34Z - Updated progress, discoveries, decisions, and validation outcomes after implementation completion; moved the plan from active to completed.
