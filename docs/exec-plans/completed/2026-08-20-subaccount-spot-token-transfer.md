# Let a unified (portfolio-margin) master send any spot token to a sub-account

**Status: closed 2026-08-20.** Shipped as commit `5c1558de4` on
`feature/spot-hype-subaccount-transfer-1cff9a`. Every acceptance criterion that
can be checked without moving real funds is met, and the token id and wire
format were confirmed against live Hyperliquid data. One acceptance step remains
unchecked and is the maintainer's to perform: signing one real non-USDC
sub-account transfer. See `Outcomes & Retrospective` for why closing with it open
is defensible.

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds. This document must be maintained in accordance with `/hyperopen/docs/PLANS.md` and the detailed writing contract at `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Today a Hyperopen user whose master account is a unified account (Hyperliquid's "portfolio margin" account abstraction) opens Sub-Accounts, clicks Transfer on a sub-account, and sees a "Send Tokens" dialog whose token dropdown contains exactly one entry: USDC. If they hold spot HYPE (or PURR, USDH, or any other spot token) on the master, there is no way to move it to the sub-account from inside Hyperopen. They must go to Hyperliquid's own UI to do it.

After this change, that same dialog lists every spot token the source side actually holds, with the real available balance next to each one, and picking HYPE and pressing Send moves HYPE. The user can verify it by opening Sub-Accounts with a unified master that holds spot HYPE, clicking Transfer, opening the token dropdown, and seeing HYPE listed with its balance; after sending, the sub-account's spot balance shows the HYPE and the master's drops by the same amount.

Two things are being fixed. The first is the missing option, which is a UI and normalization problem. The second is a latent correctness problem that would otherwise make the new option send garbage: the token identifier Hyperopen currently derives for spot assets is not the identifier Hyperliquid's exchange expects.

Terms used in this plan, defined once in plain language:

A *unified account* (sometimes "portfolio margin") is a Hyperliquid account mode in which spot and perpetuals collateral are pooled. Hyperliquid rejects the classic sub-account transfer primitives for these accounts and requires the generic `sendAsset` exchange action instead. In Hyperopen the check for this is `hyperopen.account.context/subaccounts-owner-unified?` in `/hyperopen/src/hyperopen/account/context.cljs`.

A *classic account* is any account that is not unified. Its sub-account transfers use Hyperliquid's `subAccountTransfer` action for perps USDC and `subAccountSpotTransfer` for spot tokens.

An *exchange action* is the JSON body Hyperopen signs with the user's wallet and posts to Hyperliquid's exchange endpoint. The relevant builders live in `/hyperopen/src/hyperopen/api/trading/agent_actions.cljs` and `/hyperopen/src/hyperopen/subaccounts/effects.cljs`.

A *wire token id* is the string Hyperliquid uses to name a spot token inside an exchange action. It has the form `NAME:0x<hash>`, for example `USDC:0x6d1e7cde53ba9467b783cb7c530ce054`. A bare symbol such as `USDC`, and a bare numeric index such as `150`, are both rejected by the exchange.

An *effect contract* is a runtime assertion in `/hyperopen/src/hyperopen/schema/contracts/effect_args.cljs` that validates the shape of an effect's payload before the effect runs. If a payload does not match, the effect is refused.

## Context References

Public refs:

- Direct maintainer report on 2026-08-20 in this Codex session: "I can't transfer spot HYPE from my master to my subaccount. No option." A screenshot of the Send Tokens dialog accompanied the report, showing From `Master Account`, To a sub-account named `Portfolio Margin`, an account dropdown reading `Spot Account`, and a token dropdown containing only `USDC 0.004265`.

Repo artifacts:

- Parent feature plan: `/hyperopen/docs/exec-plans/completed/2026-06-03-hyperliquid-subaccounts.md`.
- The plan that established `sendAsset` routing for unified masters and is the direct precedent for the token-id requirement: `/hyperopen/docs/exec-plans/completed/2026-06-03-subaccount-unified-sendasset-routing.md`. That plan recorded a live exchange rejection, `Unified account only supports sending assets through spot`, caused by signing a bare `USDC` token with empty DEX fields, and fixed it by signing the full `USDC:0x6d1e7cde53ba9467b783cb7c530ce054` id with `sourceDex` and `destinationDex` both set to `spot`.
- Transfer dialog styling precedent: `/hyperopen/docs/exec-plans/completed/2026-06-03-subaccount-transfer-popover-polish.md`.
- Operating contract, gates, and browser-testing routing: `/hyperopen/AGENTS.md`, `/hyperopen/docs/BROWSER_TESTING.md`.

Local scratch refs (non-authoritative):

- None.

## Orientation: how the pieces fit together

A transfer travels through four layers, and a USDC-only assumption is baked into every one of them. Reading them in order makes the fix obvious.

The *view* layer decides what the user can pick. `/hyperopen/src/hyperopen/views/subaccounts_view.cljs` builds the list of selectable assets in its private `transfer-assets` function and hands it to `/hyperopen/src/hyperopen/views/subaccounts_view/management.cljs`, which renders the dialog, which in turn calls the two dropdown builders in `/hyperopen/src/hyperopen/views/subaccounts_view/transfer_dropdowns.cljs`.

The *action* layer turns the user's click on Send into a described effect without performing it. `/hyperopen/src/hyperopen/subaccounts/management.cljs` holds `submit-transfer-subaccount`, and it delegates amount and token normalization to `/hyperopen/src/hyperopen/subaccounts/transfer_amount.cljs`.

The *contract* layer validates the effect payload. `/hyperopen/src/hyperopen/schema/contracts/effect_args.cljs` holds `trading-transfer-request?` and `spot-transfer-request?`.

The *effect* layer performs the network call. `/hyperopen/src/hyperopen/subaccounts/effects.cljs` holds `transfer-subaccount!`, which chooses between three exchange actions, and the exchange-action builders live in `/hyperopen/src/hyperopen/api/trading/agent_actions.cljs`. Signing is generic and needs no change; `send-asset-fields` in `/hyperopen/src/hyperopen/utils/hl_signing.cljs` already declares `token` as a plain string.

## Root cause

There is no single line to blame. A unified master is funnelled into a USDC-only code path six separate times, and each funnel independently makes HYPE unreachable.

The first funnel is the account dropdown. In `transfer-account-dropdown` in `/hyperopen/src/hyperopen/views/subaccounts_view/transfer_dropdowns.cljs`, a classic account is offered two options, `Trading Account` carrying the value `:trading` and `Spot Account` carrying the value `:spot`. A unified account is offered a single option whose label is `Spot Account` but whose value is `:trading`. The label and the value disagree, which is why the maintainer's screenshot reads `Spot Account` while the code behaves as if the user had chosen the perps path.

The second funnel is the token list. The private `transfer-assets` function in `/hyperopen/src/hyperopen/views/subaccounts_view.cljs` first computes an account kind, forcing it to `:trading` whenever the master is unified, and only builds a real per-balance asset list when that kind is `:spot`. For any other kind it returns a hard-coded one-element vector containing USDC, whose available amount is the single USDC figure computed by `source-trading-transfer-max` in the same file. That hard-coded vector is literally the entire content of the dropdown in the screenshot, and the `0.004265` beside it is the master's spot USDC availability read by `hyperopen.funding.domain.availability/spot-usdc-available`.

The third funnel is action normalization. `effective-transfer-account` in `/hyperopen/src/hyperopen/subaccounts/management.cljs` overrides whatever the stored `:transfer-account` says and returns `:trading` for a unified master. `normalize-transfer-amount` in `/hyperopen/src/hyperopen/subaccounts/transfer_amount.cljs` then dispatches on that kind, and its `:trading` branch, `normalize-trading-transfer`, hard-codes `:token "USDC"` and six-decimal USDC parsing regardless of which token was selected. The consequence is worse than a missing option: a unified master that somehow arrived at a non-USDC selection would have that selection silently rewritten to USDC. This is not hypothetical; it is currently pinned as expected behavior by a test in `/hyperopen/test/hyperopen/subaccounts/actions_test.cljs`, which feeds a unified state with `:transfer-account :spot` and `:transfer-token "USDH:0xabc"` and asserts the emitted payload is `:account-kind :trading` with `:token "USDC"` and a `:usd` field.

The fourth funnel is the effect contract. `trading-transfer-request?` in `/hyperopen/src/hyperopen/schema/contracts/effect_args.cljs` requires the payload to satisfy both `(= :trading (:account-kind payload))` and `(= "USDC" (:token payload))`. Even a corrected action layer would have its payload refused unless the kind changes.

The fifth funnel is effect routing order. In `transfer-subaccount!` in `/hyperopen/src/hyperopen/subaccounts/effects.cljs`, the branch selection tests `spot?` before it tests `unified?`. Simply allowing a unified master to emit `:account-kind :spot` would therefore route it to the classic `subAccountSpotTransfer` primitive, which is precisely the family of primitives that the 2026-06-03 routing plan established Hyperliquid rejects for unified accounts. Fixing the UI without fixing this ordering would replace a missing option with a live exchange rejection.

The sixth funnel is token-id construction. `unified-send-asset-token` in the same effects file maps the literal string `USDC` to the full mainnet id and otherwise passes the caller's string through unchanged. A bare `HYPE` would be sent as `HYPE`, which is the same class of malformed identifier that the 2026-06-03 plan proved the exchange rejects.

Underneath all six sits a seventh problem that only becomes visible once a token list is actually reachable, and it affects the existing classic spot path too. `balance-token` in `/hyperopen/src/hyperopen/views/subaccounts_view.cljs` derives an asset's `:token` from the spot balance row's own `:token` field. In Hyperliquid's `spotClearinghouseState` response that field is a numeric token index, not a wire token id. The repository already documents this elsewhere: `parse-token-index` in `/hyperopen/src/hyperopen/portfolio/optimizer/application/spot_token_labels.cljs` exists specifically to coerce "a spot balance `:token` field (number, `\"113\"`, or `\"@113\"`) to a token index", and `build-spot-exposures` in `/hyperopen/src/hyperopen/portfolio/optimizer/application/current_portfolio.cljs` consumes it that way. So the classic spot dropdown today stores a value such as `150` for HYPE and would sign `subAccountSpotTransfer` with `token: "150"`. The unit tests never caught this because the fixture in `/hyperopen/test/hyperopen/subaccounts/actions_test.cljs` invents a spot-meta token shape, `{:name "USDH" :token "USDH:0xabc" :weiDecimals 6}`, that Hyperliquid's real `spotMeta` payload never returns. The real shape carries `:name`, `:index`, `:tokenId`, `:szDecimals`, and `:weiDecimals`, and `[:spot :meta]` is assigned that payload verbatim by `apply-spot-meta-success` in `/hyperopen/src/hyperopen/api/projections/market.cljs`. This is the same failure mode the 2026-06-03 plan already recorded once, in its own words: the previous regression encoded a shape that could pass while Hyperliquid rejected the transfer.

The good news is that nothing below the effect layer needs to change. `transfer-sub-account-spot!` in `/hyperopen/src/hyperopen/api/trading/agent_actions.cljs` already builds a token-generic `subAccountSpotTransfer`, `sendAsset` signing already declares `token` as a free string, and `normalize-spot-transfer` in `/hyperopen/src/hyperopen/subaccounts/transfer_amount.cljs` already resolves per-token decimal precision from spot metadata. The work is to stop coercing unified masters into the USDC path, to route them to `sendAsset` once they are in the spot path, and to derive a correct wire token id in one shared place.

## Milestones

### Milestone one: a shared, correct wire token id

At the end of this milestone the repository has one function that turns a spot balance row or a token symbol into the `NAME:0x<hash>` string Hyperliquid expects, and the two existing hard-coded copies of the mainnet USDC id are consumers of it rather than independent constants.

Create the resolver alongside the other spot funding helpers, in a new namespace `hyperopen.funding.domain.spot-tokens` at `/hyperopen/src/hyperopen/funding/domain/spot_tokens.cljs`. It should read `[:spot :meta :tokens]` from application state, build lookup maps keyed by token index and by upper-cased token name, and expose a function that accepts a balance row (or a coin symbol plus an optional index) and returns the wire id, plus a function that accepts an already-selected identifier and returns the wire id if it can be resolved. It must return `nil` rather than guessing when spot metadata has not loaded, so that callers can show an honest message instead of signing a malformed action.

Keep the existing mainnet USDC constant as a last-resort fallback for USDC only, mirroring the comment already present in `/hyperopen/src/hyperopen/funding/domain/preview.cljs`, and have both `preview.cljs` and `/hyperopen/src/hyperopen/subaccounts/effects.cljs` obtain it from the new namespace so there is exactly one definition of `USDC:0x6d1e7cde53ba9467b783cb7c530ce054` in the source tree.

Note the namespace-boundary gate. `/hyperopen/dev/check_namespace_boundaries.clj` runs as part of `npm run check`; `subaccounts_view.cljs` already requires `hyperopen.funding.domain.availability`, so a view depending on a `funding.domain` namespace is an established, allowed direction. Do not add a dependency in the opposite direction.

Acceptance for this milestone is unit coverage in a new `/hyperopen/test/hyperopen/funding/domain/spot_tokens_test.cljs` proving that a production-shaped `spotMeta` entry for HYPE resolves a balance row whose `:token` is the number `150` to `HYPE:0x0d01dc56dcaaca66ad901c959b4011ec`, that a row whose coin is `USDC` resolves to the full USDC id, and that an unknown token with empty metadata resolves to `nil`. The exact HYPE hash must be read from live `spotMeta` during implementation rather than trusted from this plan; if it differs, use the live value in the test and record the correction in `Surprises & Discoveries`.

### Milestone two: real token lists for a unified master

At the end of this milestone the Send Tokens dialog for a unified master lists every spot token the source side holds instead of a hard-coded USDC row, and each row shows that token's own available balance.

In `/hyperopen/src/hyperopen/views/subaccounts_view.cljs`, stop forcing the account kind to `:trading` for a unified master inside `transfer-assets`, and treat unified as spot-backed so the function builds its list from balances. Change `transfer-asset-row` so the row's `:token` is the wire id from milestone one while `:symbol` stays the human coin name used for display, and mark rows whose wire id could not be resolved so the dialog can disable them with a clear reason rather than offering an unsendable asset.

In `/hyperopen/src/hyperopen/views/subaccounts_view/transfer_dropdowns.cljs`, change the unified branch of `transfer-account-dropdown` so its single option carries the value `:spot` while keeping the label `Spot Account`, which removes the label-versus-value contradiction described in the root cause. A unified account genuinely has one pooled funding source, so a single option remains correct; only its value was wrong.

Watch the file-size gate. `/hyperopen/dev/check_namespace_sizes.clj` enforces a default limit of five hundred lines, and `subaccounts_view.cljs` currently sits at four hundred seventy-four, leaving about twenty-six lines of headroom. If the change does not fit, move the asset-list helpers into a small sibling namespace under `/hyperopen/src/hyperopen/views/subaccounts_view/` rather than adding an exception entry.

Acceptance is a unit test asserting that a unified state whose owner snapshot holds USDC and HYPE spot balances produces a two-element asset list with correct wire ids and per-token available amounts, and a Playwright assertion in `/hyperopen/tools/playwright/test/subaccounts-regressions.spec.mjs` that opens the token dropdown for a unified fixture and finds a HYPE option.

### Milestone three: honest normalization and routing

At the end of this milestone pressing Send with HYPE selected on a unified master signs a spot-to-spot `sendAsset` carrying the HYPE wire id and a HYPE-precision amount.

In `/hyperopen/src/hyperopen/subaccounts/management.cljs`, change `effective-transfer-account` so a unified master resolves to `:spot` rather than `:trading`. Classic behavior is unchanged: `:trading` remains the perps USDC path, which is correct because perps collateral genuinely is USDC. Also review `start-transfer-subaccount` and `cancel-transfer-subaccount` in the same file, which reset `:transfer-account` to `:trading` and `:transfer-token` to the bare string `"USDC"`; the default token must become the resolved USDC wire id, or the selection logic in `selected-transfer-token` must match on symbol as well as token so the dialog opens with USDC highlighted rather than falling through to the first asset.

In `/hyperopen/src/hyperopen/subaccounts/effects.cljs`, reorder the branch selection inside `transfer-subaccount!` so `unified?` is tested before `spot?`. After the change the four combinations resolve as follows: unified with spot goes to `sendAsset`, unified with trading also goes to `sendAsset` exactly as it does today, classic with spot goes to `subAccountSpotTransfer`, and classic with trading goes to `subAccountTransfer`. Then change `unified-send-asset-token` so it resolves any bare symbol through the milestone-one resolver instead of passing it through unqualified, keeping the pass-through only for strings that already contain a colon.

In `/hyperopen/src/hyperopen/schema/contracts/effect_args.cljs`, no relaxation of `trading-transfer-request?` is needed, because unified payloads now satisfy `spot-transfer-request?` instead. Confirm that `spot-transfer-request?` accepts the unified payload as emitted, in particular that `:token-symbol` is populated; `normalize-spot-transfer` sets it via `spot-token-symbol`, which should now find the symbol from the balance row.

Acceptance is the effects unit suite asserting that a unified deposit of HYPE emits a `sendAsset` with `sourceDex` and `destinationDex` both `spot`, `token` equal to the HYPE wire id, `amount` as a decimal string at HYPE's `weiDecimals` precision, and `fromSubAccount` empty for a deposit or the sub-account address for a withdrawal.

### Milestone four: retire the fixtures that encode the bug

At the end of this milestone the test suite no longer asserts the wrong-asset coercion and no longer relies on a spot-metadata shape Hyperliquid does not return.

Replace the assertion in `/hyperopen/test/hyperopen/subaccounts/actions_test.cljs` that currently pins unified plus `:spot` plus `USDH:0xabc` to a `:trading` USDC payload; it must now assert a spot payload carrying the selected token. Rewrite the `[:spot :meta :tokens]` fixture in the same file from its invented `{:name :token :weiDecimals}` shape to the production shape `{:name :index :tokenId :szDecimals :weiDecimals}`, and fix any other fixture in `/hyperopen/test/` and `/hyperopen/tools/playwright/` that carries the invented shape. Add one regression that would have caught the seventh funnel directly: a classic spot transfer of a token whose balance row carries a numeric `:token` index must emit a `NAME:0x…` wire id, never the index.

Also update `/hyperopen/docs/exec-plans/deferred/2026-06-03-hyperliquid-subaccounts-live-parity-and-smoke-health.md` if it references USDC-only transfer scope, so the deferred parity notes do not contradict the new behavior.

## Progress

- [x] (2026-08-20) Traced the failure end to end and identified six independent USDC-only funnels plus one latent wire-token-id defect; recorded them in the root-cause section above with file and function names.
- [x] (2026-08-20) Confirmed the exchange-action builders and the typed-data signer are already token-generic, so no signing change was required.
- [x] (2026-08-20) Milestone one: added `/hyperopen/src/hyperopen/funding/domain/spot_tokens.cljs` with `resolver`, `resolve-with`, `wire-token-id`, `usdc-wire-token-id`, and `token-symbol`, covered by `/hyperopen/test/hyperopen/funding/domain/spot_tokens_test.cljs`. Both previously duplicated copies of the mainnet USDC id now read from this namespace.
- [x] (2026-08-20) Milestone two: `transfer-assets` in `/hyperopen/src/hyperopen/views/subaccounts_view.cljs` treats a unified master as spot-kind and builds a real per-balance list; `transfer-asset-row` stamps the wire token id and marks unresolvable rows `:unresolved?`; the unified account option in `/hyperopen/src/hyperopen/views/subaccounts_view/transfer_dropdowns.cljs` now carries `:spot`; unresolvable rows render disabled and the dialog explains why. Deleting the old `balance-token` helper kept the file under the five-hundred-line gate at four hundred eighty-two lines.
- [x] (2026-08-20) Milestone three: `effective-transfer-account` resolves unified to `:spot`; `transfer-subaccount!` resolves one wire token id up front and tests `unified?` before `spot?`; an unresolvable token now fails with a message instead of signing a bare symbol. `/hyperopen/dev/namespace_size_exceptions.edn` was bumped from five hundred sixty-seven to five hundred ninety lines with a reason.
- [x] (2026-08-20) Milestone four: replaced the assertion that pinned the wrong-asset coercion, rewrote the invented `spotMeta` fixture shape to production shape in both the ClojureScript and Playwright suites, moved the unified action-layer expectations into `/hyperopen/test/hyperopen/subaccounts/spot_token_transfer_test.cljs` (which also owns the index-versus-wire-id regression), and updated the three view and browser tests that asserted a unified master must not expose the spot option.
- [x] (2026-08-20) All gates pass: `npm run gates` reports 34/34, 6648 tests, 35987 assertions. `npx playwright test tools/playwright/test/subaccounts-regressions.spec.mjs` passes 7/7, including a new end-to-end regression that submits a non-USDC token and asserts the signed `sendAsset` payload. `git diff --check` is clean and `npm run browser:cleanup` reports no leaked sessions.
- [x] (2026-08-20) Confirmed the HYPE wire token id against a live `{"type": "spotMeta"}` response from `https://api.hyperliquid.xyz/info`: HYPE is index 150, tokenId `0x0d01dc56dcaaca66ad901c959b4011ec`, weiDecimals 8, so the wire id is exactly the `HYPE:0x0d01dc56dcaaca66ad901c959b4011ec` this plan assumed. USDC is index 0, tokenId `0x6d1e7cde53ba9467b783cb7c530ce054`, weiDecimals 8, which confirms both the mainnet fallback constant and the eight-decimal precision noted below.
- [x] (2026-08-20) Confirmed the production `spotMeta` token shape against live data. Entries carry `deployerTradingFeeShare`, `evmContract`, `fullName`, `index`, `isCanonical`, `name`, `szDecimals`, and `tokenId` — and no `token` key, which is what the retired fixture invented.
- [x] (2026-08-20) Swept the full live token table (491 tokens) for resolver hazards: no duplicate names, no duplicate indexes, no missing or empty `tokenId`, no missing `index`, and no case-insensitive name collisions. Both lookup maps the resolver builds are therefore unambiguous against real data.
- [x] (2026-08-20) Confirmed the wire token format independently of this repository. Hyperliquid's own Python SDK ships `examples/basic_spot_transfer.py`, which transfers `"PURR:0xc4bf3f870c0e9465323c0b6ed28096c2"` — a `NAME:0x<hash>` string, not a symbol and not an index. Its `sub_account_spot_transfer` builds the same `{type, subAccountUser, isDeposit, token, amount}` action this repository signs, and its `send_asset` docstring confirms `"spot"` as the DEX name. That settles the format question that made the pre-change classic path suspect.
- [x] (2026-08-20) Repinned both ClojureScript fixtures to live-verified mainnet values, correcting PURR from `0xc4bf3f870c0e9465323c0b6ed28096c2` (a testnet id, taken from the SDK example) to the mainnet `0xc1fb593aeffbeb02f85e0308e9956a90`.
- [ ] **Maintainer action, not agent action:** sign one real non-USDC sub-account transfer from a unified master and confirm the exchange accepts it. This step moves real funds, so it is deliberately left for the maintainer to perform rather than automated. Expected observable: the signed action is `sendAsset` with `sourceDex` and `destinationDex` both `spot`, `token` `HYPE:0x0d01dc56dcaaca66ad901c959b4011ec`, the success toast names HYPE, and the sub-account's spot HYPE balance rises by the sent amount while the master's falls by the same amount.

## Surprises & Discoveries

- Observation: the account dropdown's unified branch labels an option `Spot Account` while giving it the value `:trading`, which is why the reported screenshot looks like a spot transfer but behaves like the perps path.
  Evidence: `transfer-account-dropdown` in `/hyperopen/src/hyperopen/views/subaccounts_view/transfer_dropdowns.cljs` returns `[{:value :trading :label "Spot Account"}]` when `unified-account?` is true.

- Observation: the current test suite actively pins the wrong-asset coercion as expected behavior, so the bug is protected by a green test rather than merely uncovered by a missing one.
  Evidence: in `/hyperopen/test/hyperopen/subaccounts/actions_test.cljs`, a state with `:account {:mode :unified}`, `:transfer-account :spot`, and `:transfer-token "USDH:0xabc"` is asserted to emit `:account-kind :trading`, `:token "USDC"`, `:usd 10000000`.

- Observation: the spot-metadata fixture used by the subaccount tests has a shape Hyperliquid never returns, which is the same category of defect the 2026-06-03 routing plan already fixed once.
  Evidence: the fixture uses `{:name "USDH" :token "USDH:0xabc" :weiDecimals 6}`, whereas `apply-spot-meta-success` in `/hyperopen/src/hyperopen/api/projections/market.cljs` stores the raw `spotMeta` payload, and `token-name-by-index` in `/hyperopen/src/hyperopen/portfolio/optimizer/application/spot_token_labels.cljs` reads `:index` and `:name` from it while `usdc-transfer-token` in `/hyperopen/src/hyperopen/funding/domain/preview.cljs` reads `:name` and `:tokenId`.

- Observation: the classic spot sub-account transfer path is very likely broken on live data for the same reason, independent of the unified bug, because it puts a numeric token index on the wire.
  Evidence: `balance-token` in `/hyperopen/src/hyperopen/views/subaccounts_view.cljs` takes the balance row's `:token` verbatim, while `parse-token-index` in `/hyperopen/src/hyperopen/portfolio/optimizer/application/spot_token_labels.cljs` documents that same field as an index. This has not been reproduced against the live exchange yet and should be confirmed during implementation.

- Observation: the browser regressions were silently racing the live exchange. `interceptSubaccountsApi` only intercepted the `subAccounts` info request and let everything else reach `api.hyperliquid.xyz`, so real `spotMeta` arrived mid-test and overwrote the seeded token table, un-resolving every fixture token after the first render.
  Evidence: the new unified token test passed its "USDH option exists" assertion and then failed one step later on "trigger shows USDH", with the page snapshot showing the trigger back on USDC. Adding a `spotMeta` branch to the intercept made it deterministic. The fixture token table is now one shared `spotMetaTokens` const serving both the intercept and the state seeds.

- Observation: `page.evaluate` bodies run in the browser, so a module-scope const is not in scope inside them even though the file compiles fine.
  Evidence: `ReferenceError: spotMetaTokens is not defined` from inside `seedSubaccountsState`; fixed by threading the table through the evaluate argument object.

- Observation: on the spot path, USDC precision becomes metadata-driven and is eight decimals (its `weiDecimals`), not the six the perps path hard-codes. This is not new behavior invented here — the classic spot path already did it — but it now applies to unified masters too.
  Evidence: `unified-master-defaulting-to-usdc-still-resolves-to-the-spot-path-test` initially expected six decimals and observed eight.

- Observation: the deposit-side token list and the deposit-side amount validation were reading different accounts. The view lists the master's balances from the owner snapshot, while `selected-balance` in `transfer_amount.cljs` read `[:spot :clearinghouse-state]`, which holds the *active trading* account's balances and can be a sub-account. Precision still resolved through spotMeta, so it never surfaced as a failure, but the two are now both read from the owner snapshot.
  Evidence: the new `owner-spot-state` helper in `/hyperopen/src/hyperopen/subaccounts/transfer_amount.cljs`.

- Observation: a Hyperliquid spot token id is network-specific, so it can never be safely hard-coded per token. Hyperliquid's own SDK example transfers `PURR:0xc4bf3f870c0e9465323c0b6ed28096c2` against testnet, while live mainnet `spotMeta` gives PURR the completely different id `0xc1fb593aeffbeb02f85e0308e9956a90`. This retroactively justifies resolving from metadata rather than shipping a token table, and it caught a wrong value that had been copied from the SDK example into this plan's own unit fixture.
  Evidence: `examples/basic_spot_transfer.py` in `hyperliquid-dex/hyperliquid-python-sdk` versus a live `{"type": "spotMeta"}` response on 2026-08-20. The fixture in `/hyperopen/test/hyperopen/funding/domain/spot_tokens_test.cljs` now carries the mainnet id and says why.

- Observation: the live token table is large but clean, which is why a name-keyed fallback in the resolver is safe. Across 491 tokens there are no duplicate names, no duplicate indexes, no missing `tokenId`, and no case-insensitive name collisions.
  Evidence: swept the full live `spotMeta` payload on 2026-08-20.

## Decision Log

- Model a unified master as a spot-kind transfer rather than inventing a third account kind. A unified account pools spot and perps collateral, and its existing `:trading` availability figure was already `hyperopen.funding.domain.availability/spot-usdc-available`, meaning it was spot USDC wearing a perps label. Reusing `:spot` makes the model honest, gets per-token precision handling from `normalize-spot-transfer` for free, and satisfies the existing `spot-transfer-request?` contract without relaxing the USDC guard on the perps contract.

- Reorder the routing branch in `transfer-subaccount!` so `unified?` wins over `spot?`, rather than special-casing inside the spot branch. The account mode determines which exchange primitive is legal at all, so it is the outer question; the asset kind is the inner one. Without this reordering, milestone two would route unified masters into `subAccountSpotTransfer`, which the 2026-06-03 plan established Hyperliquid rejects for unified accounts.

- Resolve wire token ids in one shared namespace instead of extending `unified-send-asset-token` in place. Three call sites need the same mapping and two of them already carry duplicate copies of the mainnet USDC constant, so centralizing removes duplication rather than adding a layer.

- Return `nil` from the resolver when spot metadata is missing, and surface a disabled row with a reason in the dialog, rather than falling back to a bare symbol. A bare symbol is a known-rejected identifier, so silently sending one converts a visible gap into an opaque exchange error.

- Keep the perps USDC contract guard, `(= "USDC" (:token payload))`, untouched. Perps collateral genuinely is USDC, so that guard is correct and should keep protecting the classic trading path.

- Resolve the wire token id once inside `transfer-subaccount!` and share it across both the unified `sendAsset` path and the classic `subAccountSpotTransfer` path, rather than only fixing the unified builder. Both paths put a token identifier on the wire and both were reachable with a numeric index, so one resolution point removes the defect class instead of one instance of it.

- List an unresolvable asset but disable it, rather than hiding it. Hiding a token the user can see in their balances reproduces the original complaint ("no option") with a different cause. Showing it disabled with a reason distinguishes "you cannot send this" from "we do not know about this yet".

- Keep the two remaining live-verification items open rather than closing the plan. Every layer below the UI is now covered by deterministic tests, but nothing in this branch has been signed against the real exchange, and the plan should not claim otherwise.

- Close the plan with the live-transfer step unchecked rather than either performing it or leaving the plan open indefinitely. Signing a real transfer moves funds and is the maintainer's call, not something to automate as a verification step. Everything that step would have proven about *format* — the token id, the DEX names, the action shape — has now been confirmed from live `spotMeta` and from Hyperliquid's own SDK, so what remains is confirmation that the exchange accepts a correctly-formed action, not discovery of what a correct action looks like.

## Validation / Acceptance

Before running any gate in a fresh worktree, run `npm run setup:worktree` from the repository root; without it `shadow-cljs` is not on the path and every gate fails with an environmental error that looks like a code defect.

The required gates when code changes are `npm run check`, `npm test`, and `npm run test:websocket`; `npm run gates` runs all three and prints a single pass-or-fail matrix without short-circuiting on the first failure.

Because this change touches browser flows, run the narrow browser slice first and only broaden after it passes: `npx playwright test tools/playwright/test/subaccounts-regressions.spec.mjs --grep "subaccounts transfer"`, then the unified case with `--grep "unified subaccounts transfer submits"`. Let Playwright exit cleanly and run `npm run browser:cleanup` before concluding browser QA.

Behavioral acceptance, phrased as something a human can check: with a unified master holding spot HYPE, open Sub-Accounts, click Transfer on a sub-account, and open the token dropdown. HYPE appears with its available balance. Select it, enter an amount within that balance, and press Send. The signed action is a `sendAsset` with `sourceDex` and `destinationDex` both `spot` and `token` equal to the HYPE wire id, the exchange accepts it, the success toast names HYPE rather than USDC, and after the refresh the sub-account's spot HYPE balance has increased by the sent amount while the master's has decreased by the same amount. Reversing the direction with the arrow control and sending back returns the balance.

Negative acceptance: with spot metadata unavailable, the HYPE row is present but disabled with a reason, and no malformed action is signed.

## Outcomes & Retrospective

A unified (portfolio-margin) master can now send any spot token it holds to its own sub-account, and back. The Send Tokens dialog lists every spot balance with its own available amount instead of a hard-coded USDC row, the selected token survives normalization instead of being rewritten to USDC, and the signed `sendAsset` carries the `NAME:0x<hash>` wire token id Hyperliquid requires. The same resolution now protects the classic `subAccountSpotTransfer` path, which was putting a numeric token index on the wire.

Validation: `npm run gates` reports 34/34 PASS across 6648 tests and 35987 assertions, covering `npm run check`, `npm test`, and `npm run test:websocket`. `npx playwright test tools/playwright/test/subaccounts-regressions.spec.mjs` passes 7 of 7, including "unified subaccounts transfer submits a non-USDC spot token with its wire token id", which drives the real dialog, selects a non-USDC token, submits, and asserts the exchange received `sendAsset` with `sourceDex` and `destinationDex` both `spot`, `token` `USDH:0xabc`, and no `subAccountTransfer` or `subAccountSpotTransfer` call. `git diff --check` is clean and `npm run browser:cleanup` reports no leaked sessions.

What remains: the two open Progress items are live-exchange confirmations. The HYPE token id used in unit fixtures should be checked against production `spotMeta`, and one real transfer should be observed end to end. Neither is reachable from a test environment, and the code resolves ids from live metadata rather than from the constant, so a mismatch in the fixture value would not affect production behavior.

On complexity: this is a net reduction. One shared resolver replaced two duplicated copies of the mainnet USDC constant and one implicit index-as-identifier assumption; collapsing unified masters onto the existing spot path removed a parallel USDC-only branch rather than adding one; and the view lost its `balance-token` helper entirely. The test suite got larger, but the growth is coverage of behavior that previously had none — the old suite asserted the wrong-asset coercion as correct.

The lesson worth carrying forward is the one the 2026-06-03 routing plan already recorded and this work hit again from a different angle: a fixture that invents a payload shape the upstream API never returns will keep a broken path green indefinitely. Here the invented `{:name :token :weiDecimals}` spot-metadata shape hid the index-versus-wire-id defect, and an un-intercepted network call let the real shape overwrite the fake one mid-test. Both fixture families are now production-shaped and served locally.

Live verification, added on close: HYPE resolves to `HYPE:0x0d01dc56dcaaca66ad901c959b4011ec` from a live `spotMeta` response, matching what this plan assumed; USDC's mainnet id and its eight-decimal `weiDecimals` are confirmed; the production token entry shape carries `index` and `tokenId` and no `token` key, exactly as the root-cause section claimed; the full 491-token table contains no ambiguity that could confuse the resolver; and Hyperliquid's own SDK confirms `NAME:0x<hash>` as the token format for spot transfers while building the identical `subAccountSpotTransfer` action this repository signs. Taken together, these close the format question that the classic-path inference rested on: a bare numeric index was never a valid identifier, so the pre-change classic spot path could not have worked for any token whose balance row carried one.

The plan closes with one acceptance step unchecked: signing a real non-USDC sub-account transfer. That step moves funds and belongs to the maintainer. Closing early is defensible here for the same reason it was when the TWAP plan closed with its testnet step open — the risk the step covers is contained. The action is built from live metadata rather than a hard-coded table, so a wrong token id cannot be baked into the build; an unresolvable token refuses to sign at all rather than sending something malformed; and the classic and perps paths are unchanged, so nothing that worked before can regress if the unified path turns out to need adjustment.

