# Break Account Activity into sub-tabs, and make the rows underneath them true

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. It must be maintained in accordance with `/hyperopen/.agents/PLANS.md` (the full ExecPlan writing contract) and the public planning entry point `/hyperopen/docs/PLANS.md`.

## Purpose / Big Picture

Our portfolio page has an **Account Activity** tab. It renders one flat, unsorted, unpaginated list of every non-funding ledger event, with seven columns. The competitor the user pointed at renders the same underlying data behind **nine sub-tabs** — All, Account Transfers, Deposits and Withdrawals, Spot Transfers, Internal Transfers, Earn, Vaults, Staking, Auctions — across **ten columns**, sorted, and paginated fifty rows at a time. A user who wants to answer "how much have I moved between spot and perps this month" can do that there in one click; with our table they cannot do it at all.

Scoping the competitor's implementation turned up something more serious than a missing tab strip. Our row normalizer, `src/hyperopen/domain/account_ledger.cljs`, cannot support sub-tabs *and* is wrong about the rows it already shows:

- It throws away the machine-readable ledger `type`, keeping only an English label. Three distinct types collapse to the single word "Send", so no partition into sub-tabs is recoverable from what it emits.
- It decides whether an amount is a credit or a debit from a **hardcoded per-type table** rather than from the payload. `internalTransfer`, `spotTransfer` and `subAccountTransfer` are unconditionally negative — so **money arriving in your account renders as money leaving it**. The two fields that settle the question, `delta.user` and `delta.destination`, are dropped, and the function never even receives the account address it would need to compare them against.
- Source and Destination are a two-branch stub. Only `deposit` and `withdraw` get real endpoints; every transfer, vault move, class transfer and genesis row renders the uninformative "Trading Account → Trading Account". The counterparty address in the payload is never read.
- `accountClassTransfer` carries its spot↔perp direction in `delta.toPerp`. Nothing in the ledger path reads `toPerp`, and the type is in neither sign table, so those rows show an unsigned magnitude between two identical endpoints.
- Rows with no scalar amount key are silently dropped. `vaultWithdraw` (whose value lives in `netWithdrawnUsd`) and `liquidation` carry none of the probed keys, so the labels "Vault Withdrawal" and "Liquidation" exist in the code and can never be reached by real data.
- `spotTransfer` ships both `amount` (token units) and `usdcValue`. We read `amount` and discard `usdcValue`, which is exactly the number a "USD Value" column wants.
- The fee is labelled "USDC" for every row regardless of the row's actual asset.

So this plan is not "add a tab strip". It is: port the competitor's sub-tab partition faithfully, and rebuild the row derivation underneath it so each sub-tab is populated from the payload rather than from a guess.

After this plan, the Account Activity tab has the nine sub-tabs above with the competitor's exact labels and ordering; the ten columns Time / Status / Asset / Action / From / To / Destination / Account Change / USD Value / Fee, with the same per-sub-tab column hiding; sortable headers defaulting to time-descending; and fifty-row pagination. Incoming transfers render green and outgoing red because the sign is computed by comparing the payload's `user`/`destination` against the account being viewed. Spot↔perp transfers name their two ends. Vault withdrawals and liquidations appear at all.

You can see it working by opening the portfolio page on an account with transfer history and clicking through the sub-tab strip; the automated proof is a domain test suite that pins every type's sub-tab membership, sign, endpoints and USD value against payloads taken from the live API shape, plus a rendering test per sub-tab.

## Context References

Public refs:
- Direct user request, 2026-08-22: "on trade.xyz They have an account activity tab, which lets you see transfers, deposits, and other activity like that… We have such a tab on our portfolio page, but we don't have the sub tabs that break it down like they do. What I want you to do is scope out the exact functionality and how they implement this feature, and I want you to then get us to feature parity. So create an execution plan that outlines this and then proceed to implement it." Two screenshots of the reference tab were supplied.

Repo artifacts:
- `docs/exec-plans/completed/2026-08-21-account-tab-lazy-module-repaint.md` — the account-tab module split and the memo-slice trap it documents. The Account Activity tab is portfolio-only and ships in `:portfolio_route`, so that trap does not apply here; recorded so the next reader does not re-derive it.
- `AGENTS.md` — required gates, write authority, browser-testing routing.
- `docs/agent-guides/trading-ui-policy.md` — the rule this plan leans on hardest: *MUST NOT display fake zeros/placeholders as real data*, and *MUST include + or - signs for deltas and avoid colour-only gain/loss encoding*.

Local scratch refs (non-authoritative):
- Reverse-engineering notes and the 240 downloaded reference chunks under the session scratchpad. Every load-bearing claim from them is restated inline in this plan, so the plan stands alone once the scratchpad is gone.

## Reference Implementation — What It Actually Does

Established by reading the reference application's shipped JavaScript, then re-extracted independently by a second pass. Recorded here because the mapping is the entire specification and is not guessable from the tab names.

**The host.** `trade.xyz` is a marketing page; every app route on it 404s and its webpack runtime has an empty chunk map. The application is `app.trade.xyz` (Next.js + Turbopack). The three chunks that matter are `2nntit_fxgo47.js` (the tab enum), `0acbkn1o6osx1.js` (the predicate table, the store, the column derivations) and `0pckuj00l0437.js` (the table, sorting, pagination).

**The predicate is a static table, not a function per tab.** `LEDGER_UPDATE_TYPES_CONFIG` maps each Hyperliquid ledger delta `type` to `{label, displayedInTabs}`, and the runtime shows a row iff the active filter is `All` or is listed in that row's `displayedInTabs`:

| Sub-tab (id == label) | Delta types routed there |
|---|---|
| All | every type that survives ingest |
| Account Transfers | `send`, `accountClassTransfer`, `activateDexAbstraction` |
| Deposits and Withdrawals | `deposit`, `withdraw` |
| Spot Transfers | `spotTransfer` |
| Internal Transfers | `internalTransfer` |
| Earn | *(nothing — see below)* |
| Vaults | `vaultCreate`, `vaultDeposit`, `vaultDistribution`, `vaultWithdraw`, `vaultLeaderCommission` |
| Staking | `cStakingTransfer` |
| Auctions | `deployGasAuction` |

**Four re-typing rules run before the lookup**, rewriting a row's effective type so it lands on a different sub-tab than its raw type implies:
1. `send` whose `delta.user` is the native USDC contract becomes a `deposit` (Arbitrum → Hyperliquid).
2. `send` whose `delta.user` equals `delta.destination` (a self-send) becomes `accountClassTransfer`.
3. `spotTransfer` whose sender is a HyperEVM token system address becomes `accountClassTransfer`, HyperEVM → Spot.
4. `send` whose `delta.destination` is a token system address becomes `accountClassTransfer`, Spot → HyperEVM, with a negated amount.

**From / To / Destination are three independent fields**, produced together. From and To are *venue* tokens (`hyperliquid`, `hyperliquid_perps`, `spot`, `vault`, `staking`, `gas_auction`, `arbitrum`, `hyper_evm`) rendered through a display map; in unified-account mode the perp and spot venues collapse to the literal string "Trading Account", which is why every row in the user's screenshot reads "Trading Account → Trading Account". Destination is a raw counterparty **address**, truncated and click-to-copy, populated only for `send`, `internalTransfer`, the vault types and the re-typed Arbitrum deposit.

**Sign** comes from the payload: `spotTransfer`, `send` and `internalTransfer` are `amount × (user is the destination ? +1 : −1)`; `deposit` is `+usdc`; `withdraw` is `−usdc`; `accountClassTransfer` takes `delta.usdc` as already signed by the API; `cStakingTransfer` is `amount × (isDeposit ? −1 : +1)`; vault deposits are negative, distributions positive, and `vaultWithdraw` uses `netWithdrawnUsd`.

**USD Value** is `|usdcValue|` formatted `$0,0.00`, or `--`. `spotTransfer`/`send` take it from the payload's own `usdcValue`; the USDC-denominated types use `|accountChange|`; `cStakingTransfer` and `deployGasAuction` deliberately return nothing and show `--`.

**Fee** is populated only for `spotTransfer` (core collateral), `withdraw` (core collateral) and `send` (in `delta.feeToken`); everything else is zero and renders `--`.

**Columns hide per sub-tab.** Action is hidden on Spot Transfers and Account Transfers; Destination is hidden on Spot Transfers. So Spot Transfers renders 8 columns, Account Transfers 9, everything else 10.

**Sorting and pagination.** All ten columns sortable, client-side, default time-descending; clicking a new column forces descending. Page size 50, 1-indexed, footer hidden when the row count fits one page.

### Where This Plan Deliberately Departs From The Reference

The reference's implementation loses data, and we are not going to copy that.

- **It silently discards four labelled types.** `subAccountTransfer`, `liquidation`, `spotGenesis` and `rewardsClaim` have labels defined but no `displayedInTabs`, and the ingest mapper drops any such row before it reaches the store. They appear on no sub-tab, **not even All**. In the ledgers sampled during scoping, `subAccountTransfer` alone was 945 of 5,146 rows.
- **Five more types are not in its enum at all** and cannot be parsed: `borrowLend` (44 live rows in the same sample), `welcomeBonus`, `gossipPriorityGasAuction`, `accountActivationGas`, and the HIP-3 liquidator types.
- Together that is roughly **20% of real ledger rows invisible** in a surface whose entire purpose is to account for movements of the user's money.
- **The Earn sub-tab is dead.** `AccountActivityTabs.EARN` is rendered but is never a `displayedInTabs` target anywhere in the bundle, so it is permanently empty.
- **Page index is not reset on sub-tab change**, so switching from a three-page sub-tab to a one-page one while on page 3 leaves an empty table with the footer hidden.

We match the reference's structure, labels, order, columns and predicates, and depart on exactly these points: every type is routed to a sub-tab and appears in All; Earn is wired to `borrowLend` and `rewardsClaim`; the page resets to 1 when the sub-tab changes. This is recorded as a decision, not an accident.

## Acceptance Criteria

- [ ] The Account Activity tab renders a sub-tab strip with exactly `All | Account Transfers | Deposits and Withdrawals | Spot Transfers | Internal Transfers | Earn | Vaults | Staking | Auctions`, in that order, with All selected by default.
- [ ] Every ledger delta type our API can return is routed to at least one sub-tab and appears under All. No type is dropped.
- [ ] Columns are Time / Status / Asset / Action / From / To / Destination / Account Change / USD Value / Fee, with Action hidden on Spot Transfers and Account Transfers, and Destination hidden on Spot Transfers.
- [ ] An **incoming** `internalTransfer`, `spotTransfer`, `subAccountTransfer` or `send` (the viewed account is the payload's `destination`) renders a **positive, green** Account Change; an outgoing one renders negative and red. Both carry an explicit sign, not colour alone.
- [ ] `accountClassTransfer` names its two ends from `delta.toPerp` (Spot → Perps or Perps → Spot) instead of "Trading Account → Trading Account".
- [ ] `vaultWithdraw` and `liquidation` rows render instead of being dropped for want of a probed amount key.
- [ ] USD Value reads `usdcValue` where the payload supplies it, falls back to `|accountChange|` for USDC-denominated types, and renders `--` (never `$0.00`) where no honest value exists.
- [ ] Fee renders in the row's own fee asset, not a hardcoded "USDC".
- [ ] Headers sort, defaulting to time-descending; pagination defaults to 50 rows and resets to page 1 when the sub-tab changes.
- [ ] `npm run gates` is 34/34 PASS with no new namespace-size, theme-colour, typography, hiccup or boundary exceptions added.

## Milestones

**M0 — Domain model, proven red first.** New pure namespace `src/hyperopen/domain/account_activity.cljs`: the sub-tab catalog, the ported type→sub-tab config table, the normalizer for a sub-tab id, and per-sub-tab column visibility. Its test is written first and must fail before the namespace exists.

**M1 — Rebuild row derivation.** Rewrite `src/hyperopen/domain/account_ledger.cljs` to take the viewed account address and emit the richer row: `:type-key`, `:from-label`, `:to-label`, `:destination-address`, payload-derived `:signed-amount`, `:usd-value`, `:fee` + `:fee-asset`. Fixes the sign, endpoint, `toPerp`, `usdcValue`, fee-asset and dropped-row defects listed above. The domain stays pure — the address arrives as an argument, never by importing `account.context`.

**M2 — Actions and state.** Sub-tab selection, sort, and the six pagination actions, under `[:portfolio-ui :account-activity]`. Each needs its arg-contract entry, its registration row and its collaborator binding; all emit only `:effects/save-many`, so no effect-order-contract or Lean formal sync is involved.

**M3 — View.** New `src/hyperopen/views/portfolio/account_activity.cljs` rendering the strip, the ten-column grid with per-sub-tab hiding, sortable headers, and the house pagination control as the table footer. `account_tabs.cljs` delegates to it.

**M4 — USD valuation.** Wire non-USDC assets through `hyperopen.views.account-equity.pricing/token-price-usd`, which returns `nil` rather than `0` when nothing resolves — the nil discipline this column needs. This happens in the view layer, because a `/domain/` namespace may not import `hyperopen.views.*` and the boundary checker allows no exception for it.

**M5 — Tests and gates.** Domain suite, view suite, and the split of `portfolio_view_test.cljs`, which sits at 497 lines against a hard 500-line limit and cannot absorb a single new assertion.

## Progress

- [x] (2026-08-22) Reference implementation reverse-engineered from the shipped bundle and independently re-verified; the predicate table, re-typing rules, column derivations, sorting and pagination behaviour are recorded above.
- [x] (2026-08-22) Our own surfaces mapped: tab framework, pagination component, action registration checklist, and the seven defects in `domain/account_ledger.cljs`.
- [x] (2026-08-22) Baseline confirmed green before any change: `npm run gates` 34/34 PASS, 6721 tests, 36377 assertions.
- [x] (2026-08-22) M0 — `src/hyperopen/domain/account_activity.cljs` plus its suite. Proven red first: the test namespace failed to compile against a missing namespace before the model existed.
- [x] (2026-08-22) M1 — `domain/account_ledger.cljs` rewritten over a new `domain/account_ledger/derive.cljs`. Rows now carry `:type-key`, venue endpoints, a payload-derived sign, `:usd-value` and a real `:fee-asset`; a row is no longer dropped for want of a probed amount.
- [x] (2026-08-22) M2 — eight actions under `[:portfolio-ui :account-activity]`, each with its arg contract, registration row and collaborator binding. No effect-order-contract or Lean change was needed, as predicted.
- [x] (2026-08-22) M3 — `views/portfolio/account_activity.cljs` + `.../account_activity/model.cljs`; `account_tabs.cljs` delegates to it and the old flat table is gone.
- [x] (2026-08-22) M4 — non-USDC rows valued through `account-equity.pricing/token-price-usd`, preserving its nil-not-zero discipline and flagging estimates in the cell tooltip.
- [x] (2026-08-22) M5 — four new suites; the two Account Activity tests moved out of `portfolio_view_test.cljs` (497 —> 420 lines). `npm run gates` 34/34 PASS, 6753 tests / 36554 assertions, up from 6721 / 36377.
- [x] (2026-08-22) Browser QA — the Playwright regression spec was rewritten for the new contract and passes at all four review viewports, including sub-tab filtering, per-sub-tab column hiding, and an incoming-versus-outgoing spot transfer.
- [ ] Owner review of the two deliberate departures recorded in the Decision Log (routing every type somewhere visible, and wiring Earn) before this plan moves to `completed/`.

## Surprises & Discoveries

- Observation: The site the user named does not host the feature. `trade.xyz` is a marketing page — every app route 404s and its webpack runtime ships an empty chunk-id map (`r.u=e=>{}`), so there are no lazy chunks to search. The application is `app.trade.xyz`, found by subdomain probe; it is the only `*.trade.xyz` that resolves.
  Evidence: `404` for `/trade`, `/perps`, `/portfolio`, `/account`, `/dashboard`, `/trade/BTC`; `200` for `https://app.trade.xyz/` and its `/portfolio`, `/earn`, `/trade` routes. None of trade.xyz's 10 chunks contain any sub-tab label.

- Observation: The sub-tab predicate is not a set of per-tab filter functions, which is what the tab names invite you to assume. It is one static lookup table keyed by ledger type, with a list of tabs per entry. Getting this wrong is not a small error: an early pass in scoping *inferred* the mapping from the tab names and the Hyperliquid API semantics, and was wrong on six of nine rows — most consequentially placing `send` under Spot Transfers when the shipped table puts it under Account Transfers.
  Evidence: `LEDGER_UPDATE_TYPES_CONFIG` in `0acbkn1o6osx1.js`, extracted verbatim twice by independent passes. The user's own screenshot is the corroboration: its rows read Action "Send", From/To "Trading Account", with a `0x` Destination — a raw, un-retyped `send`, which the table routes to Account Transfers.

- Observation: The reference hides about a fifth of the data it purports to account for, and does it in two different ways at two different stages. Four types are labelled but have no `displayedInTabs`, and are dropped at ingest; five more are absent from its type enum entirely and are unparseable. The user-visible effect is that a sub-account transfer — the third most common event in the sampled ledgers — cannot be found anywhere in the UI, including the tab called "All".
  Evidence: The ingest guard `if(!_[e.delta.type]||!(null==(r=_[e.delta.type].displayedInTabs)?void 0:r.length))return null;`. Sampled live ledgers: 945 `subAccountTransfer`, 44 `borrowLend`, 33 `spotGenesis`, 9 `rewardsClaim`, 3 `liquidation` out of 5,146 rows.

- Observation: The "Earn" sub-tab in the screenshot is permanently empty by construction, not merely empty for that account. `AccountActivityTabs.EARN` has zero occurrences as a `displayedInTabs` target across all 240 chunks, and the component is mounted without an `excludeTabs` prop so it is not hidden either. Real earn activity lives under a *sibling top-level* tab, "Interest History".
  Evidence: Zero-hit grep for `EARN` as a routing target; `borrowLend` is not a member of the app's 18-type enum and is served by a separate store.

- Observation: Our sign bug is not latent — it is guaranteed to fire on any account that has ever *received* a transfer. `normalize-ledger-row` does not take the account address as a parameter at all, so the comparison that decides direction is not merely unimplemented, it is unrepresentable in the current signature.
  Evidence: `src/hyperopen/domain/account_ledger.cljs` — `negative-change-type?` hardcodes `internal-transfer`, `spot-transfer` and `sub-account-transfer` as negative; `normalize-ledger-row` is a one-argument function over the row.

- Observation: Two of our tables' labels are unreachable by real data. `vaultWithdraw` carries its value in `netWithdrawnUsd` and `liquidation` in `accountValue`/`liquidatedPositions`; our `amount-value` probes only `usdc`/`amount`/`value`/`qty`/`sz`, and a row whose amount does not resolve is rejected wholesale by the `finite-number?` guard.
  Evidence: `amount-value` and the `(when (and type-token time-ms (finite-number? signed) ...))` guard in `normalize-ledger-row`.

- Observation: The obvious place to add assertions is full. `test/hyperopen/views/portfolio_view_test.cljs` is 497 lines against a hard 500-line namespace-size threshold with no exception entry, so it cannot take a single new `deftest` without failing `npm run check`. This constrains M5 to a split rather than an extension.
  Evidence: `wc -l` = 497; `dev/check_namespace_sizes.clj` `default-threshold` = 500; no matching entry in `dev/namespace_size_exceptions.edn`.

- Observation: The live websocket mirror of this ledger is capped at 200 rows with no deduplication, which sets a hard ceiling on what pagination can page through from that source.
  Evidence: `upsert-seq` in `src/hyperopen/websocket/user_runtime/handlers.cljs` — `(vec (take 200 (concat incoming current)))`.

- Observation: Every ledger fixture in the repository is hand-written, and the websocket ones do not match the wire shape — they set `:delta` to a **string**, so `ledger-delta` falls back to the row itself, the row has no `:type`, and `normalize-ledger-row` drops it. Those fixtures cannot catch a regression in the websocket→display path, which is precisely the path this plan changes.
  Evidence: `test/hyperopen/websocket/user_test.cljs` — `{:time 1000 :coin "USDC" :delta "5.0"}`.

- Observation: The reference's `isTokenSystemAddress` is a *shape* test, not an ordering test, and getting that wrong silently corrupts the sub-tab partition. A first implementation compared the address against the `0x20...` prefix with `compare`, which matches roughly every wallet address sorting above it — so ordinary spot transfers were re-typed into Account Transfers. The real predicate is `0x20`, then 34 zeros, then a four-hex token index.
  Evidence: the shipped helper, `e=>{let t=e.toLowerCase();return t===p.toLowerCase()||!!t.startsWith("0x20")&&42===t.length&&/^0+$/.test(t.slice(4,38))}`, paired with its minting function `` e=>`0x20${e.toString(16).padStart(38,"0")}` ``. The bug surfaced as a test failure, not in review.

- Observation: `NATIVE_USDC_CONTRACT_ADDRESS` is not a literal in the reference bundle — it is read from chain config at runtime — so the fourth re-typing rule cannot be ported by copying a constant. It is moot regardless: all three rules that reference `send` are carried for completeness, because `send` is a multi-dex delta Hyperliquid's own API does not emit.
  Evidence: the bundle exports it as a computed binding, `"NATIVE_USDC_CONTRACT_ADDRESS",0,E`, where `E` derives from `o.address.toLowerCase()`.

- Observation: `src/hyperopen/state/app_defaults.cljs` required `hyperopen.account-tab-modules` twice. Adding one line to it pushed the namespace to 501 against the 500-line limit; deleting the duplicate require brought it back to exactly 500, so the size gate paid for a real (if harmless) defect rather than being silenced with an exception entry.
  Evidence: lines 5 and 7 of the `ns` form were identical.

- Observation: A worktree cannot be browser-tested on the default port without testing the wrong code. A `shadow-cljs watch` from the *main* checkout was holding `127.0.0.1:8080`, which is the Playwright base URL, so the suite would have exercised the main checkout's bundle against this worktree's spec. Running the worktree's own build on `:8090` is the fix — and the static server needs an SPA fallback (`--proxy`), or `/portfolio` 404s and the app never boots, which presents as an opaque 45-second timeout inside `visitRoute`.
  Evidence: `lsof -nP -iTCP:8080` showed a `shadow.cljs.devtools.cli --npm watch` JVM; `PLAYWRIGHT_BASE_URL=http://127.0.0.1:8090` with `npx http-server resources/public -p 8090 -c-1 --proxy http://127.0.0.1:8090?` passes.

## Decision Log

- Decision: Port the reference's sub-tab structure, labels, ordering, columns and predicate table exactly — and do **not** port its data loss. Every type we can receive is routed to a sub-tab and appears under All; `subAccountTransfer` goes to Account Transfers, and `borrowLend`/`rewardsClaim` make Earn a live tab rather than a dead one.
  Rationale: The user asked for feature parity with a surface whose job is to account for movements of their money. Faithfully reproducing a bug that hides a fifth of those movements — including the third most common event type — would deliver the appearance of parity and a worse product. `docs/agent-guides/trading-ui-policy.md` forbids presenting incomplete data as complete. The departure is cheap and reversible: the routing lives in one table, so bug-for-bug fidelity is a data edit, not a rewrite.
  Date/Author: 2026-08-22, Claude (via direct user request).

- Decision: Fix the sign, endpoint and dropped-row defects as part of this plan rather than deferring them.
  Rationale: They are not adjacent bugs, they are load-bearing. Sub-tabs that partition by type are only as trustworthy as the type, and a Spot Transfers tab whose every incoming row shows a debit is worse than no tab. `accountClassTransfer` — the type that populates Account Transfers — currently renders unsigned between two identical endpoints, which would make the flagship new sub-tab the least informative one.
  Date/Author: 2026-08-22, Claude.

- Decision: Reuse the house pagination control (`history-pagination-controls`) instead of reproducing the reference's "Showing 1 to 50 of 60 results" footer with Previous/Next.
  Rationale: Trade History, Funding History and Order History all sit in the same panel and already use the house control; matching a competitor's footer copy inside our own panel would make Account Activity the odd one out, against the policy rule to keep terminology and controls consistent. The control is strictly more capable (page size, jump-to-page) and costs six actions we need anyway.
  Date/Author: 2026-08-22, Claude.

- Decision: The viewed account address is threaded into `normalize-ledger-rows` as an argument rather than read from app state inside the domain namespace.
  Rationale: `dev/check_namespace_boundaries.clj` hard-fails any `/domain/` namespace importing `hyperopen.views.*` and explicitly refuses to let domain namespaces use the exception registry for it. Passing the address in keeps the derivation pure and directly testable against payload fixtures, which is what the acceptance criteria assert.
  Date/Author: 2026-08-22, Claude.

- Decision: New code lives under `src/hyperopen/views/portfolio/**`, not `src/hyperopen/views/account_info/**`.
  Rationale: `account_info/**` compiles into `:account_surfaces`, a dependency of every account-tab chunk *and* of the trade route, so a portfolio-only table placed there would ship to the trade page for nothing. `views/portfolio/**` is already inside the route-lazy `:portfolio_route` module, so this needs no `shadow-cljs.edn` change and adds no bytes to any other route.
  Date/Author: 2026-08-22, Claude.

- Decision: Scope stays on the portfolio page; the tab is not added to the trade page in this plan.
  Rationale: The user's stated gap was the missing sub-tabs on a tab we already have, not a missing tab. The trade panel is height-constrained with eight tabs already, so adding a ninth is a separate product change with its own layout consequences. The sub-tab work is placed in a namespace the trade route could mount unchanged if that is wanted later.
  Date/Author: 2026-08-22, Claude.

## Validation and Acceptance

Required gates, run as `npm run gates` (single PASS/FAIL matrix, no short-circuit on first failure):
- `npm run check`, `npm test`, `npm run test:websocket`.

Specific gates this plan is most likely to trip, and why:
- `lint:namespace-sizes` — `portfolio_view_test.cljs` is 3 lines from the limit; `account_tabs.cljs` has ~337 lines of headroom but the new view must not consume it.
- `lint:namespace-boundaries` — the domain namespace must not import views.
- `lint:theme-colors` — `account_tabs.cljs` has no baseline entry, so one raw `#hex` fails it.
- `lint:hiccup` — `:class` must be a vector of single classes; `:style` map keys must be keywords.
- `typography-scale-test` — `text-[10|11|13|14|15px]` is banned under `views/**`; use `text-xs` / `text-sm`.
- `lint:docs` — this plan must keep an unchecked progress item while work remains.

Browser QA: the smallest relevant Playwright command first (`tools/playwright/test/portfolio-regressions.spec.mjs` already stubs `userNonFundingLedgerUpdates` and asserts the tab at four viewports), broadened only after it passes. Any Browser MCP session opened for exploration is explicitly stopped before concluding.

Evidence to record here on completion: the gate matrix, the red-then-green transitions for M0/M1, and confirmation that no new size/theme/boundary exception entries were added.

## Outcomes & Retrospective

All acceptance criteria are met and every gate passes. Final `npm run gates`: **34/34 PASS, 6,753 tests, 36,554 assertions** (baseline before this work: 34/34, 6,721 tests, 36,377 assertions). No new namespace-size, theme-colour, typography, hiccup or boundary exception was added; the one exception edit raises the pre-existing `action_args.cljs` cap from 735 to 745 for the eight new action contracts, following the convention already used in that entry.

Files added:
- `src/hyperopen/domain/account_activity.cljs` — sub-tab catalog, ported predicate table, column visibility.
- `src/hyperopen/domain/account_ledger/derive.cljs` — per-type endpoints, sign, USD value, fee.
- `src/hyperopen/portfolio/account_activity_actions.cljs` — the eight actions.
- `src/hyperopen/views/portfolio/account_activity.cljs` and `.../account_activity/model.cljs` — strip, table, sorting, pagination, USD enrichment.
- Four test namespaces covering the domain model, the derivation, the actions and the rendering.

Evidence the tests bite rather than merely pass: reverting the one-line direction fix in `derive.cljs` (`direction (if (incoming? delta viewer-address) 1 -1)` back to a constant `-1`) turns **7 assertions red across three namespaces** — five in the derivation suite, one in the merge test, one in the rendering suite — and nothing else in the 6,753-test suite moves.

What this plan did not do, and why:
- The Account Activity tab was not added to the trade page. The stated gap was missing sub-tabs on a tab we already have; the trade panel already carries eight tabs in a height-constrained shell. The view namespace is mountable there unchanged if that is wanted.
- The reference's fourth re-typing rule is not ported (its constant is runtime-derived, and the delta type it keys on is one Hyperliquid does not emit).
- Sub-tab selection is not URL-persisted. The reference keeps it in local storage and deliberately excludes it from its query-param sync; our vault-detail precedent does persist its equivalent, so this is a live candidate for follow-up rather than a settled question.
- The `[:orders :ledger]` websocket mirror is still capped at 200 rows with no dedupe. That cap now bounds what pagination can reach from the live source; it predates this work and is left as recorded tech debt.
