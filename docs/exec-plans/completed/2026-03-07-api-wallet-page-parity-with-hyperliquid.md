# API Wallet Page Parity with Hyperliquid

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document is maintained in accordance with `/hyperopen/.agents/PLANS.md` and `/hyperopen/docs/PLANS.md`.

## Purpose / Big Picture

After this change, Hyperopen will expose a dedicated API wallet management page that behaves like Hyperliquid’s `/API` surface instead of forcing users to manage trading agents from the wallet dropdown only. A connected user will be able to open `/API`, review the current default app-managed agent, authorize named API wallets, generate a fresh wallet address client-side, optionally set an expiry in days, and remove previously authorized wallets from the same page.

A user can verify this by navigating to `/API`, connecting a wallet, generating a new API wallet, authorizing it, seeing it appear in the table with a `Valid Until` value, and removing it again. They should also see the current default Hyperopen-managed trading agent represented as a special row when one exists.

## Progress

- [x] (2026-03-07 15:08Z) Reviewed planning and tracking requirements in `/hyperopen/.agents/PLANS.md`, `/hyperopen/docs/PLANS.md`, and `/hyperopen/docs/WORK_TRACKING.md`.
- [x] (2026-03-07 15:08Z) Reviewed architecture and UI constraints in `/hyperopen/ARCHITECTURE.md`, `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, and `/hyperopen/docs/agent-guides/trading-ui-policy.md`.
- [x] (2026-03-07 15:08Z) Audited current Hyperopen route rendering, header navigation, runtime action/effect registration, wallet agent runtime, and relevant API modules.
- [x] (2026-03-07 15:08Z) Reverse-inspected Hyperliquid’s live `/API` page with rendered DOM capture, network capture, and production bundle analysis.
- [x] (2026-03-07 15:08Z) Verified the live data and action seams Hyperliquid uses for this page: `info` `extraAgents`, existing account `webData2` agent fields, and `approveAgent` for both authorization and removal flows.
- [x] (2026-03-07 15:08Z) Authored this ExecPlan.
- [x] (2026-03-07 15:12Z) Filed follow-up feature issue `hyperopen-p0l` in `bd` for implementation tracking.
- [x] (2026-03-07 15:58Z) Implemented feature state, route parsing, and `More`-menu navigation entry for `/API`, including startup route loading and app-view ownership.
- [x] (2026-03-07 16:00Z) Added account/API wallet fetch plumbing for `extraAgents` and default-agent snapshot hydration via owner `webData2`.
- [x] (2026-03-07 16:01Z) Generalized the existing `approveAgent` runtime so the API page can authorize named wallets and remove wallets without duplicating signing logic.
- [x] (2026-03-07 16:02Z) Implemented the responsive API page view, confirmation modal, generated-key disclosure, and table sorting behavior.
- [x] (2026-03-07 16:03Z) Added and updated tests across endpoint, runtime, action/effect, schema, startup, and view layers.
- [x] (2026-03-07 16:18Z) Ran required validation gates:
  `npm run check`
  `npm test`
  `npm run test:websocket`
- [x] (2026-03-07 16:09Z) Completed in-browser QA against local `/API` on desktop and mobile, plus a Hyperliquid-vs-local parity capture for reference.
- [x] (2026-03-07 16:09Z) Closed `bd` feature `hyperopen-p0l` after implementation and QA.
- [x] (2026-03-07 16:10Z) Moved this ExecPlan to `/hyperopen/docs/exec-plans/completed/2026-03-07-api-wallet-page-parity-with-hyperliquid.md`.

## Surprises & Discoveries

- Observation: Hyperliquid’s API page is an account-management page, not a documentation page.
  Evidence: Rendered `/API` DOM shows a page title, explanatory copy, a wallet authorization form, and a sortable table; it does not embed docs prose or SDK examples.

- Observation: Hyperliquid accepts both `/API` and `/api` in practice, but the app’s route table is registered as lowercase `/api`.
  Evidence: Production route bundle registers `path:"/api"` while rendered navigation links and direct browser navigation use `/API`.

- Observation: Named API wallets come from a dedicated `info` request, not from the default account `webData2` payload.
  Evidence: Production bundle module `29159` fetches `{"type":"extraAgents","user":<address>}` and exposes the result as `extraAgents`.

- Observation: The default app-managed trading agent is not part of `extraAgents`; Hyperliquid injects it as a special row from live account data.
  Evidence: Production `/API` page code reads `webData.agentAddress` and `webData.agentValidUntil`, then appends a synthetic row labeled `app.hyperliquid.xyz`.

- Observation: Hyperliquid uses the same `approveAgent` typed-data path for authorization and removal.
  Evidence: Production bundle helper `bn(...)` always builds an action with `:type "approveAgent"`. Removal is represented by using the zero address sentinel (`0x0000000000000000000000000000000000000000`) and optionally preserving the agent name.

- Observation: Expiry is encoded in the agent name string, not in a separate request field.
  Evidence: Production bundle appends ` valid_until <epoch-ms>` to `agentName` before signing when the user enters a days-valid value.

- Observation: Generated keys for named API wallets are shown once in the confirmation modal and are not persisted as active trading credentials by Hyperliquid.
  Evidence: The live bundle only persists the default app agent path; the `/API` page modal merely displays the generated private key when the entered address matches the generated key.

- Observation: Current Hyperopen already has the hardest protocol work for this page: typed-data signing for `approveAgent`, owner-wallet signature submission, and local agent-session generation.
  Evidence: `/hyperopen/src/hyperopen/api/trading.cljs`, `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs`, `/hyperopen/src/hyperopen/wallet/agent_session.cljs`, and `/hyperopen/src/hyperopen/utils/hl_signing.cljs` already implement and test those pieces for `Enable Trading`.

## Decision Log

- Decision: Implement this feature under a dedicated `api-wallets` feature namespace instead of adding more page-specific logic directly to `wallet/*` or generic `api/*` view code.
  Rationale: The repository already separates route-scoped features like `funding_comparison` and `vaults`; using `api-wallets` keeps route state, page effects, and view logic cohesive and avoids confusion with the transport-layer `hyperopen.api.*` namespaces.
  Date/Author: 2026-03-07 / Codex

- Decision: Reuse the existing `approveAgent` signing and submission seams by extracting a generic approval helper, then keep `Enable Trading` as a wrapper over that helper.
  Rationale: Duplicating `approveAgent` request construction would create two subtly different signing paths for the same protocol action. The API page and wallet dropdown should share one lower-level approval implementation.
  Date/Author: 2026-03-07 / Codex

- Decision: Treat `extraAgents` as the canonical source for named rows and `:webdata2` plus a route-entry fallback fetch as the canonical source for the default app-managed row.
  Rationale: This matches observed Hyperliquid behavior and avoids inventing local-only state for server-owned API-wallet metadata.
  Date/Author: 2026-03-07 / Codex

- Decision: Accept both `/API` and `/api` for route matching, but navigate to `/API` from the UI.
  Rationale: This preserves Hyperliquid-visible URL parity while making route handling robust in Hyperopen’s simpler custom router.
  Date/Author: 2026-03-07 / Codex

- Decision: Do not persist generated private keys for named API wallets beyond the confirmation modal.
  Rationale: Hyperliquid only persists the default app trading agent. Storing extra generated keys would increase browser-held secret surface without being necessary for parity.
  Date/Author: 2026-03-07 / Codex

- Decision: Scope mutations to the connected owner wallet, not to spectated addresses.
  Rationale: API wallet approval is a signing-owner concern. Hyperopen’s spectate mode is read-only and should never mutate the viewed address.
  Date/Author: 2026-03-07 / Codex

## Outcomes & Retrospective

Implementation is complete. Hyperopen now has a dedicated `/API` page with Hyperliquid-style route handling, a real `More`-menu entry, owner-scoped loading of named API wallets via `extraAgents`, synthetic default-agent row hydration from `webData2`, shared `approveAgent` authorization/removal plumbing, responsive desktop/mobile layouts, generated-key disclosure for newly created named wallets, and regression coverage across route, endpoint, runtime, schema, startup, and view layers.

Required validation gates passed end to end:

- `npm run check`
- `npm test`
- `npm run test:websocket`

Browser QA also passed for the local `/API` route on desktop and mobile. The local page rendered the expected disconnected-state copy, form controls, disabled authorize CTA, available generate CTA, empty-state table/card, and `More -> API` navigation entry. A parity compare against Hyperliquid `/API` was captured as reference evidence; the remaining DOM/visual differences are expected shell/parity deltas rather than route breakage.

Retrospective:

- The protocol side was correctly de-risked during planning; the hardest parts were not exchange mutations, but page ownership, route wiring, and fitting the API-wallet flow cleanly into existing app/runtime boundaries.
- Tightening `normalize-wallet-address` to require a real `0x` address closed a real validation gap and surfaced stale short-address fixtures in existing agent-session tests. Fixing that contract at the shared helper was the correct move.
- The plan’s original open items are all complete, and this document has been moved to `completed`.

## Context and Orientation

Hyperopen currently has no dedicated API wallet page. Route rendering lives in `/hyperopen/src/hyperopen/views/app_view.cljs`, basic path normalization lives in `/hyperopen/src/hyperopen/router.cljs`, and navigation clicks flow through `/hyperopen/src/hyperopen/runtime/action_adapters.cljs`. The header already renders a visual `More` affordance in `/hyperopen/src/hyperopen/views/header_view.cljs`, but that affordance does not open a real menu, and there is no route branch for `/API`.

Current trading-agent behavior lives in these files:

- `/hyperopen/src/hyperopen/wallet/agent_session.cljs`
- `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs`
- `/hyperopen/src/hyperopen/wallet/actions.cljs`
- `/hyperopen/src/hyperopen/api/trading.cljs`
- `/hyperopen/src/hyperopen/utils/hl_signing.cljs`

Those modules already know how to:

- generate an agent private key,
- derive the agent address,
- build and sign `approveAgent`,
- submit the signed exchange payload, and
- persist the default Hyperopen trading agent for later order signing.

This page needs more than the current wallet-dropdown flow. It needs route-scoped state, fetches for existing authorized wallets, a table view, and authorization/removal UX for arbitrary named wallets. In this plan, “default app-managed agent” means the single agent Hyperopen uses today for `Enable Trading`. “Named API wallet” means any extra user-approved API wallet row shown in Hyperliquid’s table. “Zero address sentinel” means the Ethereum zero address `0x0000000000000000000000000000000000000000`, which Hyperliquid uses when removing a wallet via `approveAgent`.

The live Hyperliquid page behavior relevant to implementation is:

1. The page route is effectively case-insensitive and is commonly visited as `/API`.
2. The hero block contains only a title (`API`) and one short explanatory paragraph.
3. The main form has three desktop columns: name input, address input with `Generate`, and `Authorize API Wallet` button. On mobile the same controls stack into one column.
4. The table has four columns: `API Wallet Name`, `API Wallet Address`, `Valid Until`, and `Action`.
5. Table rows are the union of:
   - `extraAgents` fetched from `POST /info` with `{"type":"extraAgents","user":<owner>}`, and
   - one synthetic default row sourced from current account data (`agentAddress`, `agentValidUntil`) and labeled `app.hyperliquid.xyz`.
6. Confirming an authorization/removal opens a modal. Named authorization can include a days-valid field capped at `180`. A generated private key is shown only when the address in the form was derived from a newly generated key.
7. Removal uses the same `approveAgent` action with the zero address sentinel; removing the default row passes no agent name, while removing a named row preserves the name.

In Hyperopen, the easiest reusable account snapshot for the default row is `:webdata2`, which the websocket layer already stores wholesale in `/hyperopen/src/hyperopen/websocket/webdata2.cljs`. The current issue is that route entry does not guarantee those fields are fresh when a user opens the future API page directly, so the page should either consume the live `:webdata2` snapshot when it is present or trigger a route-entry fallback fetch for the active owner address.

## Plan of Work

### Milestone 1: Add a Dedicated `api-wallets` Feature Slice and Route Ownership

Create a route-scoped feature module so `/API` is not just another special case in `header_view.cljs`.

Add:

- `/hyperopen/src/hyperopen/api_wallets/actions.cljs`
- `/hyperopen/src/hyperopen/api_wallets/effects.cljs`

In `actions.cljs`, define a route parser that accepts both `/API` and `/api` and classifies anything else as `:other`. Add actions for route entry, form field updates, sort toggles, modal open/close, key generation, authorization submit, and removal submit.

Extend `/hyperopen/src/hyperopen/state/app_defaults.cljs` with two new branches:

- `:api-wallets-ui`
  This should hold small page-local controls only: form name, form address, generated private key (modal-only lifetime), modal state, sort state, loading flags, and user-entered days-valid value.
- `:api-wallets`
  This should hold fetched data and page errors only: `:extra-agents`, `:default-agent-row`, `:loading`, `:error`, and `:loaded-at-ms`.

Update route ownership in:

- `/hyperopen/src/hyperopen/views/app_view.cljs`
- `/hyperopen/src/hyperopen/runtime/action_adapters.cljs`
- `/hyperopen/src/hyperopen/startup/runtime.cljs`

The route flow should follow the repo’s UI ordering rule: save the new route and page-visible loading state first, then dispatch the heavier fetch effects. Add any new route-critical action IDs to `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs` so projection writes happen before network effects.

### Milestone 2: Add Fetch Plumbing for Existing API Wallet Rows

Implement the two data inputs the live page needs: named wallets and current default-agent metadata.

For named wallets, add `request-extra-agents!` to the existing account/info seam:

- `/hyperopen/src/hyperopen/api/endpoints/account.cljs`
- `/hyperopen/src/hyperopen/api/gateway/account.cljs`
- `/hyperopen/src/hyperopen/api/default.cljs`
- `/hyperopen/src/hyperopen/api/instance.cljs`

The request body is:

    {"type":"extraAgents","user":<owner-address>}

Normalize the response into a vector of maps with the exact keys the view model will use:

    {:name string
     :address lowercased-address
     :valid-until-ms number-or-nil}

For the default app-managed row, prefer existing `:webdata2` when it already holds `:agentAddress`, `:agentValidUntil`, and `:serverTime`. If route entry occurs before websocket state is hydrated, add one explicit owner-address fallback fetch. The cleanest place is a new user-specific `webData2` request in the account seam rather than overloading the public-market helper in `/hyperopen/src/hyperopen/api/endpoints/market.cljs`.

The new fallback request should be additive and small:

    {"type":"webData2","user":<owner-address>}

Add pure projections in `/hyperopen/src/hyperopen/api/projections.cljs` for:

- beginning the API-wallet page load,
- applying extra-agents success/error,
- applying default-agent snapshot success/error, and
- clearing stale page errors before a new submit or refresh.

The page loader effect in `/hyperopen/src/hyperopen/api_wallets/effects.cljs` should:

1. refuse to fetch when no owner wallet is connected,
2. use the connected owner wallet address, not the spectated address,
3. fetch `extraAgents`,
4. refresh the default-agent snapshot only when the current `:webdata2` snapshot is missing the required fields or belongs to another address.

### Milestone 3: Generalize `approveAgent` Runtime for API Page Actions

Do not write a second `approveAgent` implementation.

Extract a lower-level helper from the current default-agent enablement flow so both the wallet dropdown and the future API page can use it. The likely edit points are:

- `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs`
- `/hyperopen/src/hyperopen/wallet/agent_session.cljs`
- `/hyperopen/src/hyperopen/runtime/action_adapters.cljs`
- `/hyperopen/src/hyperopen/app/effects.cljs`
- `/hyperopen/src/hyperopen/schema/contracts.cljs`
- `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`

The extracted helper should accept:

- owner wallet address,
- agent address,
- optional agent name,
- optional generated private key,
- optional days-valid,
- current server time or fallback `Date.now`,
- a flag indicating whether this is the default app-managed agent and therefore should persist the local session.

Keep existing `enable-agent-trading!` as a wrapper that:

1. generates the default private key,
2. sets `persist-session? true`, and
3. leaves `agent-name` unset.

Add a new API-page-specific effect such as `:effects/approve-api-wallet` that:

1. reuses the shared helper,
2. never persists generated private keys for named API wallets,
3. refreshes the page rows after success, and
4. resets transient UI state when the mutation finishes.

Encode days-valid exactly the way Hyperliquid does. That means appending this suffix to the user-visible agent name before signing:

    " valid_until <server-time-ms-plus-days>"

Cap the days-valid field at `180`, allow it to be blank, and keep the blank case as “no expiry suffix”.

Model removal the same way Hyperliquid does:

- removing a named row submits `approveAgent` with the zero address sentinel and the original row name,
- removing the default app-managed row submits `approveAgent` with the zero address sentinel and no name.

### Milestone 4: Build the Page View, Modal, and Navigation Entry

Add render-only view code in:

- `/hyperopen/src/hyperopen/views/api_wallets_view.cljs`
- `/hyperopen/src/hyperopen/views/api_wallets/vm.cljs`

The view model should:

- merge named rows and the synthetic default row,
- derive sort order for the four table columns,
- derive empty/disconnected/loading states,
- detect whether the current form address matches a just-generated private key,
- format `valid-until-ms` to user-facing date text,
- surface whether the primary action should be disabled.

The render layer should match the live Hyperliquid information hierarchy:

1. page hero with title and one explanatory paragraph,
2. first card with form controls,
3. second card with the table.

Desktop layout should use the live three-column form grid (`1fr 1fr auto`), and mobile should collapse to one column. The table should remain readable on smaller screens; a horizontally scrollable wrapper is acceptable if it preserves header/row alignment.

Add a confirmation modal that supports both authorization and removal. The modal should:

- show `API Wallet Name`,
- show `API Wallet Address` except when removing the default row,
- show the days-valid input only for authorization, not removal,
- show the generated private key warning only when the entered address was generated in-page,
- change button label/title between `Authorize API Wallet` and `Remove API Wallet`.

Update `/hyperopen/src/hyperopen/views/header_view.cljs` so users can actually reach the page from the shell. The current `More` affordance is a visual stub. Replace it with a real dropdown or popover menu that contains at least the implemented overflow destinations needed for parity, including `API`. Keep this narrowly scoped: the purpose is a discoverable route entry, not a complete rebuild of Hyperliquid’s entire overflow menu system.

Update `/hyperopen/src/hyperopen/views/app_view.cljs` so the new page renders when the parsed route is an API-wallet route.

### Milestone 5: Add Regression Coverage and Run Validation

Add tests in the seams this feature touches.

At minimum, cover:

- endpoint/gateway/default/instance support for `extraAgents` and any new owner-specific `webData2` request,
- projection state transitions for page load and mutation success/error,
- action/effect route loading and UI-first ordering,
- shared `approveAgent` helper behavior for:
  - default enablement,
  - named authorization,
  - default-row removal,
  - named-row removal,
  - days-valid suffix generation,
  - “generated key shown once” modal state,
- header navigation and route activation,
- view model row merging and sort behavior,
- view rendering for disconnected, empty, populated, and loading states.

Likely files to create or extend:

- `/hyperopen/test/hyperopen/api/endpoints/account_test.cljs`
- `/hyperopen/test/hyperopen/api/gateway/account_test.cljs`
- `/hyperopen/test/hyperopen/api/default_test.cljs`
- `/hyperopen/test/hyperopen/api/instance_test.cljs`
- `/hyperopen/test/hyperopen/api_wallets/actions_test.cljs`
- `/hyperopen/test/hyperopen/api_wallets/effects_test.cljs`
- `/hyperopen/test/hyperopen/wallet/agent_runtime_test.cljs`
- `/hyperopen/test/hyperopen/runtime/action_adapters_test.cljs`
- `/hyperopen/test/hyperopen/startup/runtime_test.cljs`
- `/hyperopen/test/hyperopen/views/api_wallets/vm_test.cljs`
- `/hyperopen/test/hyperopen/views/api_wallets_view_test.cljs`
- `/hyperopen/test/hyperopen/views/header_view_test.cljs`
- `/hyperopen/test/hyperopen/schema/contracts_test.cljs`

When implementation is complete, run from `/hyperopen`:

    npm run check
    npm test
    npm run test:websocket

## Concrete Steps

From `/hyperopen`:

1. Add the new feature state branches in `/hyperopen/src/hyperopen/state/app_defaults.cljs`.
2. Add route parsing and feature actions in `/hyperopen/src/hyperopen/api_wallets/actions.cljs`.
3. Register the new action/effect IDs through:
   `/hyperopen/src/hyperopen/app/actions.cljs`
   `/hyperopen/src/hyperopen/app/effects.cljs`
   `/hyperopen/src/hyperopen/runtime/action_adapters.cljs`
   `/hyperopen/src/hyperopen/runtime/collaborators.cljs`
   `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`
   `/hyperopen/src/hyperopen/schema/contracts.cljs`
4. Add account/info endpoint support for `extraAgents` and any user-specific `webData2` fallback fetch.
5. Add projections and feature effects.
6. Extract the shared `approveAgent` helper and wire the new page mutation effect.
7. Build the view model, page view, and confirmation modal.
8. Update `header_view.cljs` and `app_view.cljs` for discoverable navigation and route rendering.
9. Add or update tests in all affected seams.
10. Run:

    npm run check
    npm test
    npm run test:websocket

Expected outcomes:

- `npm run check` finishes without contract, compile, or lint failures.
- `npm test` passes with new feature tests included.
- `npm run test:websocket` continues to pass, proving the new route/page does not violate websocket/runtime assumptions.

## Validation and Acceptance

Acceptance is behavior-based:

1. Entering `/API` or `/api` in the browser renders a dedicated API wallet page instead of falling back to the trade page.
2. The page hero matches the live information hierarchy: title, short explanation, authorization form card, then table card.
3. With no connected wallet, the page still renders but the authorize action is disabled and the table shows no mutable owner rows.
4. With a connected owner wallet and an existing default trading agent, the table includes a synthetic default row labeled `app.hyperopen.xyz` with address, valid-until, and remove action.
5. Clicking `Generate` fills the address input with the derived address of a freshly generated private key.
6. Authorizing a generated or pasted wallet opens a modal, optionally accepts days-valid up to `180`, submits `approveAgent`, and refreshes the table on success.
7. When the authorized address came from in-page generation, the modal shows the generated private key warning exactly once for that flow and the key is not persisted in local storage for later reuse.
8. Removing a named or default row also uses the modal flow and removes the row after a successful refresh.
9. Spectate mode does not let the user mutate the spectated address. The page mutates only the connected signing owner.
10. Required validation commands pass:

    npm run check
    npm test
    npm run test:websocket

## Idempotence and Recovery

All code changes in this plan are additive and safe to rerun. The page fetch effects are read-only and can be repeated. Authorization and removal use the same protocol action Hyperliquid uses today; retrying after a failed signature or failed network request is safe because the page will refresh server-owned rows after the next successful response.

If the shared `approveAgent` refactor breaks the existing wallet-dropdown `Enable Trading` flow, restore behavior by keeping the new lower-level helper but making `enable-agent-trading!` call it with the old arguments. Do not duplicate the old code path again.

If the fallback `webData2` fetch proves redundant because websocket state is always present on route entry, keep the helper but gate it behind a missing-field check so repeated visits do not add unnecessary load.

Do not persist generated private keys for named API wallets as a recovery shortcut. Recovery for those wallets is “generate again or paste the intended address again,” which matches the live Hyperliquid page’s security posture more closely than browser persistence would.

## Artifacts and Notes

Key reverse-inspection evidence captured during planning:

- Rendered live `/API` page structure shows:

    title: `API`
    hero copy: `API wallets (also known as agent wallets) can perform actions on behalf of your account without having withdrawal permissions. You must still use your account's public address for info requests.`
    form columns: `name input`, `address input + Generate`, `Authorize API Wallet`
    table columns: `API Wallet Name`, `API Wallet Address`, `Valid Until`, `Action`

- Network capture from live `/API` page confirms the extra-agents data source:

    POST https://api-ui.hyperliquid.xyz/info
    {"type":"extraAgents","user":"<connected owner address>"}

- Production bundle logic confirms the authorization/removal transport:

    action.type = "approveAgent"
    if removing default row:
      agentAddress = "0x0000000000000000000000000000000000000000"
      agentName = nil
    if removing named row:
      agentAddress = "0x0000000000000000000000000000000000000000"
      agentName = "<row-name>"
    if days-valid present:
      agentName = "<name> valid_until <serverTimePlusDaysMs>"

- Relevant live bundle files inspected:

    https://app.hyperliquid.xyz/static/js/main.be4d3bab.js
    https://app.hyperliquid.xyz/static/js/2690.7e3d169d.chunk.js

These details are embedded here so the implementer does not need to rediscover them from the live site before writing code.

## Interfaces and Dependencies

The implementation should end with the following new or changed interfaces.

New route feature interfaces:

- `hyperopen.api-wallets.actions/parse-api-wallet-route`
- `hyperopen.api-wallets.actions/api-wallet-route?`
- `hyperopen.api-wallets.actions/load-api-wallet-route`
- `hyperopen.api-wallets.actions/set-api-wallet-name`
- `hyperopen.api-wallets.actions/set-api-wallet-address`
- `hyperopen.api-wallets.actions/set-api-wallet-valid-days`
- `hyperopen.api-wallets.actions/generate-api-wallet`
- `hyperopen.api-wallets.actions/open-api-wallet-modal`
- `hyperopen.api-wallets.actions/close-api-wallet-modal`
- `hyperopen.api-wallets.actions/submit-api-wallet-authorization`
- `hyperopen.api-wallets.actions/submit-api-wallet-removal`
- `hyperopen.api-wallets.actions/set-api-wallet-sort`

New effect interfaces:

- `:effects/api-fetch-extra-agents`
- `:effects/api-fetch-api-wallet-default-snapshot`
- `:effects/approve-api-wallet`

New API account interfaces:

- `hyperopen.api.endpoints.account/request-extra-agents!`
- `hyperopen.api.gateway.account/request-extra-agents!`
- `hyperopen.api.default/request-extra-agents!`
- instance API map key `:request-extra-agents!`

If a user-specific `webData2` fallback request is added, expose it through the same endpoint/gateway/default/instance layers with a precise name such as `request-user-webdata2!`.

New view interfaces:

- `hyperopen.views.api-wallets.vm/api-wallets-vm`
- `hyperopen.views.api-wallets-view/api-wallets-view`

Refactored wallet/runtime interface:

- a new shared lower-level helper inside `/hyperopen/src/hyperopen/wallet/agent_runtime.cljs` that both the existing default agent enablement flow and the new page mutation effect call.

Revision note (2026-03-07 / Codex): Initial plan authored after live DOM, network, and production-bundle inspection of Hyperliquid’s `/API` page so implementation can proceed without repeating that research. Updated the progress section to record follow-up issue `hyperopen-p0l` for implementation tracking in `bd`.
Revision note (2026-03-07 / Codex): Updated progress, outcomes, and retrospective after implementation, validation (`npm run check`, `npm test`, `npm run test:websocket`), browser QA, and local `bd` issue closure; moved plan from `active` to `completed`.
