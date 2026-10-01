# Move tokens between HyperCore and HyperEVM from one Transfer surface

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept current as work proceeds. Maintain it according to `/hyperopen/.agents/PLANS.md`.

## Purpose / Big Picture

Hyperliquid runs two ledgers for the same wallet. HyperCore is the exchange ledger that holds perps collateral and spot balances. HyperEVM is an Ethereum-compatible chain, id 999, where the same wallet address holds ERC-20 tokens and native HYPE. Users move tokens between the two all the time, for example to use HYPE in a HyperEVM lending app and then bring it back to trade. Hyperopen can't do this today. It only labels such movements in account history after they happen somewhere else, so users have to leave the app, and they often lose track of funds that land on HyperEVM.

After this change, a hyperopen user can:

1. **Transfer from one modal.** The existing funding modal's Transfer mode, today "Perps <-> Spot" for USDC only, becomes a single Transfer form. It has From and To choices over three places (Perps, Spot, HyperEVM) and an asset picker for any token linked between the two ledgers. It shows balances on both sides before and after the move, locks the destination to the user's own address, and states the fee and arrival time.
2. **Move HyperCore to HyperEVM.** This is one signature and needs no network switch.
3. **Move HyperEVM to HyperCore.** The wallet switches to the HyperEVM network, then the modal shows step-by-step progress: switch network, then approve, then deposit for USDC; switch network, then send for other tokens. If the wallet has no HYPE for gas on HyperEVM, the modal explains that and offers a one-click fix that sends 0.05 HYPE from Spot to HyperEVM without losing the draft.
4. **See HyperEVM balances.** They appear as real rows in the Balances table on desktop and mobile, with an EVM chip, an All / HyperCore / HyperEVM filter, and per-row actions ("To Spot", "To HyperEVM", "To Perps").
5. **See where funds are on Portfolio.** A new "Where your funds are" strip shows total value (including HyperEVM), a Perps card, a Spot card, and a HyperEVM card with gas status. Buttons between the cards open Transfer preset to that pair. The Perps ↔ Spot header button is renamed "Transfer".
6. **See HyperEVM on the Trade page.** The account panel's "Perps <-> Spot" button becomes "Transfer", and Account Equity gains a "HyperEVM (not margin)" line with a Move link.

To see it working: open `/portfolio` with a connected wallet that holds HYPE and a linked token. The strip shows the HyperEVM card. Choose "To HyperEVM" on the HYPE Spot row, enter an amount, and submit. The success panel appears, and within seconds a HYPE row with the EVM chip shows the new balance. The deterministic proof is the Playwright spec `/hyperopen/tools/playwright/test/funding-transfer-hyperevm.spec.mjs`. It drives the whole flow against a simulated wallet, a simulated exchange, and a mocked HyperEVM RPC, and asserts the exact signed `sendAsset` payload and the exact `eth_sendTransaction` calldata.

This is correctness and feature work. It adds one polling read (HyperEVM balances) that is bounded by the public RPC's rate limit, and it makes no other performance claims.

## Context References

Public refs:

- Direct user request, 2026-09-30: "turn this into an exec plan and implement it". This followed an approved design canvas that combines three options (A, B and C):
  - A: a dedicated EVM ↔ Core entry point in the style of Hyperliquid's own app;
  - B: one unified Transfer modal with Perps, Spot and HyperEVM as places;
  - C: HyperEVM balances shown as first-class rows.

  The user asked for "the complete interface … that would give the best possible user experience".

Repo artifacts:

- `/hyperopen/src/hyperopen/funding/BOUNDARY.md`: layering rules for the funding context. Domain code is pure, application code gets its collaborators injected, and RPC/browser code lives only in infrastructure.
- `/hyperopen/src/hyperopen/domain/account_ledger/derive.cljs` already labels HyperEVM bridge movements in history. This plan extends it.
- `/hyperopen/docs/exec-plans/completed/2026-08-21-account-tab-lazy-module-repaint.md`: the precedent for the memoized trade-view state-slice trap this plan must avoid.
- `/hyperopen/docs/BROWSER_TESTING.md` and `/hyperopen/docs/agent-guides/browser-qa.md`: browser QA widths and commands.

Local scratch refs (non-authoritative):

- None.

## Scope and Non-Goals

In scope:

- Core→EVM for every linked spot token and HYPE, from Spot.
- ~~Core→EVM for USDC from Perps on standard (non-pooled) accounts, via `sendAsset` with `sourceDex ""`.~~ Withdrawn in Milestone 9: no live ledger shows HyperCore bridging a `sendAsset` that leaves the Perps dex for a system address, so Perps → HyperEVM goes Perps → Spot → HyperEVM until one small live transfer verifies it (see "Milestone 9 review fixes" in the Decision Log).
- EVM→Core to Spot for every linked token, native HYPE, and USDC (through Circle's deposit contract).
- EVM balance reads for any address the app displays (so spectate mode shows them read-only).
- The Balances table, Portfolio strip, Trade account panel, header renames, add-to-wallet, the gas top-up, ledger labeling of HYPE and spotSend legs, and the release CSP entry.

Non-goals:

- HyperEVM testnet (chain 998). The app's HyperCore API URLs are hard-coded to mainnet. Routing EVM transactions to testnet while Core reads mainnet would strand funds, so testnet constants exist for fixtures only.
- EVM→Perps as one step. Circle's `destinationDex 0` settles asynchronously through CoreWriter, which is Hyperliquid's EVM-to-Core action bridge, and cannot revert. Users go EVM→Spot, then Spot→Perps.
- Named HIP-3 perp DEXs as HyperEVM endpoints.
- Transfers to other people. The existing Send mode covers that.
- Moving tokens for a selected subaccount. A subaccount has no private key, so funds credited to its HyperEVM address would be unreachable.
- Switching the wallet back to Arbitrum after an EVM→Core transfer. Hyperliquid signing works on chain 999, and an extra prompt is harmful.

## Progress

- [x] (2026-09-30 14:10Z) Mapped all touched subsystems with seven parallel read-only agents and verified protocol constants live against mainnet and testnet. Findings are captured in this plan.
- [x] (2026-09-30 14:27Z) Milestone 0: gate hygiene and size-cap extractions (no behavior change). All 11 expiring exceptions were renewed, and four extractions landed: funding action args, the transfer VM contract, `open-funding-transfer-modal`, and the open-modal test expectation. Review follow-ups added a literal pin of every modal default in `modal_state_test.cljs` and a committed transfer-contract test, `/hyperopen/test/hyperopen/schema/funding_modal_transfer_contracts_test.cljs`. Nothing is committed yet.
- [x] (2026-09-30 15:05Z) Milestone 1: HyperEVM pure domain (chain, units, tokens, abi, fees), the JSON-RPC read client, and the CSP entry. It was built in an isolated worktree, reviewed, fixed, and copied into this worktree. The combined tree passes `npm test`: 6461 tests, 0 failures. `read-balances!` round-tripped against the live RPC.
- [x] (2026-09-30 15:20Z) Pre-implementation critique by three adversarial reviewers. It raised 1 blocker and about 12 major findings, all absorbed into the "Plan revision note" and the rewritten Milestones 2–9. The milestones are reordered so that the modal domain lands before the Balances table.
- [x] (2026-09-30 15:27Z) Milestone 2: HyperEVM state, poller, bridge health, gating, ledger labels. `npm test` passes: 6522 tests, 0 failures. The bridge-health read was verified live twice: once through the RPC client alone and once through the whole runtime in the dev build (one RPC request each).
- [x] (2026-09-30 15:56Z) Milestone 2 review fixes: 3 majors and 13 minors addressed (see "Milestone 2 review fixes" in the Decision Log). `npm test` passes: 6534 tests, 0 failures.
- [x] (2026-09-30 16:39Z) Milestone 3: Transfer modal domain, commands, view-model (routes, precision/MAX, previews with every blocked code, invariants, gas-topup action and formal sync). `npm test` passes: 6605 tests, 0 failures, and `npm run gates` passed 34/34. `bb tools/formal.clj sync` and `verify --surface effect-order-contract` are green, the app build compiles with 0 warnings, and the 8 funding/transfer Playwright cases in trade- and portfolio-regressions pass against this worktree's build. See "Milestone 3 as built" in the Decision Log.
- [x] (2026-09-30 17:15Z) Milestone 3 review fixes: 3 majors and 11 minors addressed (see "Milestone 3 review fixes" in the Decision Log). The HyperCore bridge balance of an open HyperEVM → Core draft is now read again by edits, MAX, percent, submit and the balance poller; the retry-capability save uses a keyword path; the gas top-up owns its failure path; the invariant covers the review's six gaps and now guards Send too. `npm test` and `npm run gates` results are in Outcomes.
- [x] (2026-09-30 17:53Z) Milestone 4: Submit paths. HyperEVM → Core sends chain-pinned wallet transactions with in-flight tracking and flow ids; Core → HyperEVM keeps the modal open on an arriving run; a move left pending is settled in the background; add-to-wallet is registered. `npm test` passes: 6692 tests, 0 failures; `npm run check` and `npm run test:websocket` (585 tests) pass. `bb tools/formal.clj verify --surface effect-order-contract` is green (no policy changed). See "Milestone 4 as built" in the Decision Log.
- [x] (2026-09-30 18:28Z) Milestone 4 review fixes: 1 major and 17 minors addressed (see "Milestone 4 review fixes" in the Decision Log). Spot re-reads during arrival land only while the owner's Spot is shown, nothing after an accepted `sendAsset` can fail its run, a send answered without a hash stays unresolved, and the tests cover the submitter's remaining failure branches. Gate results are in Outcomes.
- [x] (2026-09-30 19:07Z) Milestone 5: Transfer modal views. The form (segmented From/To with visible disabled reasons, a native-radio asset list, amount with MAX, percents and USD, before/after cards, the locked destination and route rows, the blocked card with its fix, the verification notice, a sticky submit) and the run views (progress, pending, failed with "Back to edit"/"Try again", success arriving -> arrived) render from the Milestone 3 view-model. The shell routes the four run kinds, caps the panel's height, restores focus to the new openers, and now layers above the app's fixed footer; the Perps <-> Spot buttons read "Transfer". `npm test` passes: 6728 tests, 0 failures; the app build compiles with 0 warnings; the 8 funding Playwright cases pass against this worktree's build. See "Milestone 5 as built" in the Decision Log.
- [x] (2026-09-30 19:49Z) Milestone 5 review fixes: 7 majors and 11 minors addressed, two of each duplicates (see "Milestone 5 review fixes" in the Decision Log). A failure whose outcome is unknown no longer offers a one-click "Try again"; the Transfer form shows its own message above the sticky submit (never under a run view); re-choosing the pressed place changes nothing; an unknown source never reads as empty; hidden slots hold no focusable control; "Back to edit" focuses the amount. Gate results are in Outcomes.
- [x] (2026-09-30 20:53Z) Milestone 6: Balances table. HyperEVM rows (EVM chip, gas-reserve note, hyperevmscan contract link) join the Balances tab after the unstaking annotation and never the shared row memo; every row carries move targets (the legacy Perps <-> Spot move kept byte-identical, plus To HyperEVM / To Spot with visible disabled reasons); an All / HyperCore / HyperEVM filter sits in the tab header with HyperEVM empty states and a note; /trade reads a projected HyperEVM slice that repaints only when a row would change. `npm test` passes: 6786 tests, 0 failures; `npm run gates` passed 34/34. See "Milestone 6 as built" in the Decision Log.
- [x] (2026-09-30 21:32Z) Milestone 6 review fixes: 2 majors and 11 minors addressed, two of them duplicates (see "Milestone 6 review fixes" in the Decision Log). On `/trade` at 1280 px a row's moves now end at 928 px inside the 960 px panel (they wrap onto two lines where the Transfer column is at its 104 px minimum); a disabled move's reason is visible text (a hover/focus tooltip on desktop, a line under a mobile card's actions); the note also shows an account-level block and a pending or failed first read under All. Gate results are in Outcomes.
- [x] (2026-09-30 22:08Z) Milestone 7: Portfolio strip and the Trade account-panel HyperEVM line. `/portfolio` shows "Where your funds are" (total value = Total Equity + HyperEVM, "—" while HyperEVM is unknown; Perps, Spot and HyperEVM cards, or one "Trading account" card for unified accounts; connectors preset to their pair, disabled with visible reasons), and the `/trade` Account Equity panel has the "HyperEVM · Not margin" line with its Move link, desktop and mobile, from a model precomputed on the full state. `npm test` passes: 6818 tests, 0 failures; the app build compiles with 0 warnings; `npm run gates` passed 34/34 (7573 tests) before the last change (the HyperEVM card's "N unpriced" note, one new test), after which `npm test`, the four lints and the app build were rerun green; a mutation check that let a loading total fall back to Total Equity, disabled Spot <-> HyperEVM only for read-only views, gave read-only views a Move link and dropped the model from the desktop panel's opts failed 20 assertions across 4 tests. See "Milestone 7 as built" in the Decision Log.
- [x] (2026-09-30 22:50Z) Milestone 7 review fixes: 2 majors and 9 minors addressed; two minors overlap the majors (the label, and HyperCore data treated as zero) (see "Milestone 7 review fixes" in the Decision Log). The total card names its HyperCore part "Total Equity $X" (the summary card's own label) and says what sits in vaults and Earn, so the cards reconcile with the total; the strip shows "Loading…"/"—" until the shown account's HyperCore snapshot has loaded; a HyperEVM read with a chunk never answered is partial, never a total; a retry of a failed first read keeps saying "unavailable"; loading is no longer in the warning tone; unpriced HyperEVM holdings read "Unpriced" and keep the /trade line and its Move link; sub-cent dust hides the line; the disabled connector's reason shows at every width, and its tooltip sits under its button, stays open under the pointer and closes on Escape (the Balances move tooltip closes on Escape too). `npm test` passes: 6835 tests, 0 failures; `npm run gates` passed 34/34 (7591 tests).
- [x] (2026-10-01 00:05Z) Milestone 8 items 1-4: the wallet simulator signs, sends, switches (4902 add-then-switch, 4200 refusal), watches assets and logs every request; `hyperevm_fixtures.mjs` mocks the HyperEVM RPC and the HyperCore reads with production-shaped data; `visitRoute` routes the RPC with an empty default and a guard (corrected by the review fixes below: that guard could not fail, and specs that skip `visitRoute` still reached the public RPC); `funding-transfer-hyperevm.spec.mjs` covers (a)-(o) plus acceptance 5, 8, 9, 11 and 16 in 22 cases asserting the exact `sendAsset` payloads, wallet log and calldata, and passes 22/22 (44/44 over two repeats). `npm test` passes: 6843 tests, 0 failures; `npm run test:playwright-support` 15/15. The broadened rerun (102 cases) passed 94, skipped 2 and failed 6: five fail identically on a clean HEAD build (no log was kept; the review fixes below re-ran them and saved one), and the sixth (the Portfolio volume-history popover's distance check) was a Milestone 7 layout effect, now measured as a gap. See "Milestone 8 as built" in the Decision Log.
- [x] (2026-10-01 01:18Z) Milestone 8 review fixes: 6 majors and 15 minors addressed, three of them duplicates (see "Milestone 8 review fixes" in the Decision Log). Every spec now runs under one HyperEVM RPC guard (`guarded_test.mjs`, enforced by `spec_imports.test.mjs`) that mocks the RPC for every context a test opens and fails on any request no mock answered, independently of routes; the mock rejects batches over 20 and reverts a deposit estimate without the allowance, as live; the wallet simulator rejects unknown methods and can leave HyperEVM after a send; the exchange simulator's per-type queues match and can hold a response. The HyperEVM spec has 26 cases (new: acceptance 7, the pending timeout, keyboard HyperEVM -> Spot, /trade (n)) and passes 26/26, 52/52 over two repeats; `npm run gates` passed 34/34 (7631 tests). The broadened rerun (140 cases) passed 129, skipped 2 and failed 9: six fail on a clean HEAD build too (log kept), two were transient and passed 2/2 on rerun, and one was a timing bug in a new test, fixed and passing 2/2. See Outcomes.
- [x] (2026-10-01 02:16Z) Milestone 8 item 5: browser QA at 375, 768, 1280 and 1440 (with the `/trade` high-risk checks from `browser-qa.md`), recorded PASS, FAIL or BLOCKED in Artifacts and Notes. Scripted Playwright screenshots and DOM, style and contrast probes ran against this worktree's `compile app` build with the wallet and exchange simulators and the mocked HyperEVM RPC / HyperCore info, and against a clean HEAD build for the `/trade` geometry and tab-strip comparisons. Three FAILs were fixed: the Balances header hid the selected tab at 768 px, the run views' route cards measured 3.64:1 text contrast, and MAX and the percent chips used the browser's default focus outline. Every surface x width is now PASS or N/A, with five minor findings recorded as remaining risks. `npm test` passes: 6853 tests, 0 failures. See "Milestone 8 item 5 as built" in the Decision Log.
- [x] (2026-10-01 03:30Z) Milestone 9: full gates, adversarial review, docs, retrospective. The final three-lens review's 27 findings (4 majors, 23 minors) are addressed, 3 minors in part (see "Milestone 9 review fixes" in the Decision Log); `npm run gates` passed 34/34 (7668 tests), the app build compiles with 0 warnings, the HyperEVM spec passes 54/54 over two repeats, the funding and balances subsets of the broadened specs pass (one transient failure passed 2/2 on rerun), `/hyperopen/docs/BROWSER_TESTING.md` lists the HyperEVM spec, and Outcomes holds the retrospective for the whole feature. Nothing is committed.
- [ ] User acceptance and merge: the user reviews the remaining risks in Outcomes (real-wallet check of the EIP-1559 fields and `chainId`, the withdrawn Perps -> HyperEVM route, the renewed exception deadlines), commits, merges, and moves this plan to `completed/`.

## Surprises & Discoveries

- Observation: HYPE is not linked in mainnet `spotMeta`: its `evmContract` is `null`, while testnet reports `{address 0x000…0, evm_extra_wei_decimals 10}`. A filter like "tokens with an evmContract" silently drops HYPE.
  Evidence: `curl -X POST https://api.hyperliquid.xyz/info -d '{"type":"spotMeta"}'` on 2026-09-30. Mainnet has 503 tokens, 172 of them linked, and HYPE index 150 has `evmContract: null`.

- Observation: USDC's `spotMeta` `evmContract.address` (`0x6b9e773128f453f5c2c60935ee2de2cbc5390a24`) is Circle's CoreDepositWallet proxy, not the USDC ERC-20. `balanceOf`, `decimals`, `name` and `symbol` all revert on it. The ERC-20 is native USDC at `0xb88339CB7199b77E23DB6E890353E22632Ba630f` (6 decimals), which is also what `CoreDepositWallet.token()` returns.
  Evidence: `eth_call` against `https://rpc.hyperliquid.xyz/evm` on 2026-09-30.

- Observation: a token's position in the `spotMeta` token array is not its index. They diverge from position 458 on mainnet (FUNT is index 478), so system addresses must be derived from `:index`.
  Evidence: live `spotMeta`.

- Observation: HYPE's system address is `0x2222222222222222222222222222222222222222`, not the generic `0x20…0096`. Sending a non-HYPE asset to `0x2222…` loses it.
  Evidence: Hyperliquid HyperCore↔HyperEVM transfer docs, and 948.6M HYPE pre-minted at `0x2222…`.

- Observation: the public RPC `https://rpc.hyperliquid.xyz/evm` works for browser reads:
  - CORS responses are `Access-Control-Allow-Origin: *`.
  - JSON-RPC batches are capped at 20 entries (error -32010).
  - It allows 100 requests per minute per IP.
  - It serves only the latest block.
  - Multicall3 (`0xcA11bde05977b3631167028862bE2a173976CA11`) is deployed there. One `aggregate3` call read native HYPE plus all 172 linked-token balances in 0.65 s (38.8 KB calldata, 27.7 KB response).
  Evidence: curl OPTIONS/POST and `cast` on 2026-09-30.

- Observation: the Core→EVM fee for non-HYPE tokens is 200000 × the next small-block base fee, rounded up to 8 decimals and charged in spot HYPE. HYPE Core→EVM showed a fee of 0.
  Evidence: two live ledger fees, 0.00002075 and 0.00002307 HYPE, match the base fees of blocks 47294902 and 47294947.

- Observation: `evm_extra_wei_decimals` ranges from -2 to 13 on mainnet (USDC, UBTC, USDT0, USDH and XAUT0 are -2; PURR is 13). On-chain `decimals()` disagrees with weiDecimals+extra for HOPE (index 122, returns 0) and reverts for JOFF (index 296).
  Evidence: a Multicall3 `decimals()` sweep on 2026-09-30.

- Observation: since commit `64eccaa60`, user-signed Hyperliquid actions sign with the wallet's active chain id. After an EVM→Core transfer leaves the wallet on 0x3e7, later `sendAsset`/`usdClassTransfer` signatures carry `signatureChainId` 0x3e7 with `hyperliquidChain` "Mainnet". Hyperliquid accepts this, and wallets such as Rabby require it.
  Evidence: `/hyperopen/src/hyperopen/api/trading/user_actions.cljs` lines 37-79.

- Observation: five namespace-size exceptions expire on 2026-09-30, including `/hyperopen/src/hyperopen/schema/contracts/action_args.cljs`, which this feature must touch. So do all six namespace-boundary exceptions. `dev/check_namespace_sizes.clj` fails when `retire-by` is before today, so `npm run check` goes red on 2026-10-01 regardless of this feature.
  Evidence: `grep 2026-09-30 dev/namespace_size_exceptions.edn dev/namespace_boundary_exceptions.edn`.

- Observation: cljs.spec decides at definition time whether a keyword spec is an alias. `(s/def :a :b)` stores an alias only if `:b` is already registered. Otherwise it wraps `:b` as a predicate, so `:a` then checks `(:b value)`. That fails every normal map, and no error is raised. A spec namespace can therefore alias only specs that are registered before it loads. It cannot alias a spec defined in a namespace that requires it.
  Evidence: `cljs/spec/alpha.cljs` `def-impl` (ClojureScript 1.12.42 jar, lines 306-316) keeps the keyword only when `(get @registry-ref spec)` is non-nil and otherwise calls `spec-impl`. In Milestone 0, `:funding-modal-vm.transfer/actions` aliases `:funding-modal-vm/actions`, which the parent contract defines, so the alias had to stay in the parent.

- Observation: some linked tokens cannot be moved safely, because their bridge side is empty or their contract is broken. On mainnet, SIX (idx 6) and NAV (idx 1022) have 0 at their EVM system address while Core supply is outstanding. FUNT (idx 478) is 0 on both sides. JOFF's `balanceOf` reverts, and HOPE's `decimals()` returns 0 where spotMeta implies 5.
  Evidence: the pre-implementation fund-safety critic ran one Multicall3 sweep of `balanceOf(systemAddress)` and `decimals()` over all 171 non-USDC linked tokens, plus `spotClearinghouseState` of their Core system addresses, on 2026-09-30.

- Observation: the public HyperEVM RPC rate-limits with HTTP 200 and a single JSON-RPC error object `{"id":null,"error":{"code":-32005,"message":"rate limited"}}`, even in reply to a batch.
  Evidence: a live batch request on 2026-09-30 by the critic.

- Observation: `0x2222…2222` holds about 1371 native USDC, 186 PURR and 0.0031 UBTC on HyperEVM, all permanently stuck. This confirms that non-HYPE assets sent there are lost.
  Evidence: the Milestone 1 live `read-balances!` round-trip.

- Observation: bridge health for every linked token fits in the balance poll's single HTTP request. The first poll of a session carried native HYPE, the gas price, 173 owner `balanceOf` calls, and 342 health calls (171 `balanceOf(systemAddress)` plus 171 `decimals()`) as one 7-entry JSON-RPC batch, and answered in 1.14 s. Live results:
  - SIX, NAV and FUNT have 0 at their HyperEVM system address, so their Core→EVM capacity is "0";
  - HOPE (`decimals()` mismatch) and JOFF (both calls revert) are the only unhealthy tokens;
  - PURR's system address holds about 508.6M PURR and UBTC's about 21.0M UBTC on HyperEVM.
  Mainnet has 173 linked tokens once HYPE (native, `evmContract null`) is counted with the 172 that have an `evmContract`.
  Evidence: a temporary `hyperevm-live-probe` namespace compiled with `--config-merge` onto the `:test` build and run under Node on 2026-09-30T15:15Z (1 RPC request), then deleted. Values are pinned in `/hyperopen/test/hyperopen/hyperevm/test_support/bridge_fixtures.cljs`.

- Observation: the whole Milestone 2 runtime works end to end in the dev build. Spectating `0x2222…2222` on `/trade` made the poller issue exactly one RPC request (1.31 s by Performance API timing). That request stored 32 non-zero linked-token balances, including 1371.11 USDC and 186.08 PURR, plus health for 171 tokens with SIX at "0". `:actions/refresh-hyperevm-bridge-capacity` for SIX and HYPE stored their Core system balances, "293.0535384" and "51277657.7969295308". No console errors or state-validation failures appeared.
  Evidence: the Browser pane against `node tools/playwright/static_server.mjs` serving this worktree's `compile app` output, 2026-09-30T15:24Z. The poller was switched off with `HYPEROPEN_DEBUG.setHyperevmPollerEnabled(false)` right after the first read, and the server was stopped afterwards.

- Observation: HyperCore reports more fractional digits than `weiDecimals` on system-address balances. PURR has weiDecimals 5, yet its system address held "91403084.7764399946". `userRole` answers `{"role":"missing"}` for an address with no HyperCore account and `{"role":"user"}` otherwise. HyperCore system addresses hold real spot balances; SIX's `0x2000…0006` held 293.05 SIX on Core while 0 SIX sat on the EVM side.
  Evidence: `spotClearinghouseState` and `userRole` info requests on 2026-09-30T15:08Z.

- Observation: the Milestone 2 review found that a receipt wait could pause HyperEVM polling for good. The pause had no time bound and covered every address, including one that had never been read. A dropped transaction, or an account switch during a wait, would then leave balances unknown for the rest of the session.
  Evidence: `waiting-receipt?` ignored `:submitted-at-ms`, and `refresh-plan` applied it to all addresses. Fixed and pinned in `receipt-wait-pause-is-bounded-and-spares-unread-addresses-test`.

- Observation: the Node unit-test runtime bootstraps the real app. `/hyperopen/test/hyperopen/core_bootstrap/test_support/fixtures.cljs` calls `app-bootstrap/bootstrap-runtime!`, so an unconditionally installed poller would run a real 4 s interval during `npm test` and could reach the public RPC.
  Evidence: `runtime-bootstrap-fixture` in that file.

- Observation: the legacy transfer submit effect signed every action whose `:type` was not `"sendAsset"` as a `usdClassTransfer`. Once Milestone 3 made the `hyperEvmToCore` pseudo action reachable from `:actions/submit-funding-transfer`, dispatching it before Milestone 4 would have signed a Perps -> Spot `usdClassTransfer` of the same amount.
  Evidence: `submit-transfer!` in `/hyperopen/src/hyperopen/funding/application/submit_effects.cljs` was `(if (= "sendAsset" (:type action)) submit-send-asset! submit-usd-class-transfer!)`. It is now a `case` that refuses unknown types; pinned by `/hyperopen/test/hyperopen/funding/application/submit_effects_transfer_guard_test.cljs`.

- Observation: the view-model runs on every render, and the first draft priced every linked token (about 173) through `resolve-market-by-coin`, which can scan the whole market list, before checking whether the source held any of it.
  Evidence: `asset-option` in `transfer_route.cljs`; it now prices only held tokens.

- Observation: an action cannot emit `[:actions/...]` (the effect-id schema rejects it), so "dispatch `:actions/refresh-hyperevm-bridge-capacity`" on EVM -> Core selection is done by inlining what that action returns. `open-funding-transfer-modal`, `set-funding-transfer-location`, `swap-funding-transfer-locations` and `select-funding-transfer-asset` append its `:effects/fetch-hyperevm-core-bridge-balance` and `:effects/fetch-hyperevm-core-account-status` effects, through an injected `:refresh-hyperevm-bridge-capacity` command dep.
  Evidence: the same constraint is documented in `/hyperopen/src/hyperopen/margin_rec/actions.cljs` (`apply-margin-rec-batch`).

- Observation: an `:effects/save` path must be all keywords. `::common/state-path` is `keyword-path?`, and the runtime checks every emitted effect only when `goog.DEBUG` is true (dev and the Playwright `compile app` build). Unit tests that call actions through the facade skip that check, so a string segment (the wallet provider id in `[:hyperevm :wallet-capabilities "io.rabby"]`) passed `npm test` and would have thrown in dev.
  Evidence: `hyperopen.runtime.validation/wrap-action-handler` calls `assert-emitted-effects!` only when `validation-enabled?`. `/hyperopen/test/hyperopen/funding/application/transfer_capacity_refresh_test.cljs` now runs every Transfer action's effects through `hyperopen.schema.contracts/assert-emitted-effects!`.

- Observation: the HyperCore bridge balance of a HyperEVM → Core draft was read only on open, route change or asset change. After 60 s the preview treated it as unknown ("Checking the bridge balance…") while nothing was checking, so HYPE HyperEVM → Spot dead-ended a minute after opening. A failed `userRole` read likewise left "Checking your HyperCore account…" up for good.
  Evidence: `refresh-hyperevm-bridge-capacity` had one emitter (`capacity-effects` in `transfer_commands.cljs`), and the balance poller reads only the HyperEVM RPC.

- Observation: `token-price-usd` resolved the market before checking the balance row or the stable fallback. For USDC, no market key matches, so `resolve-market-by-coin` scanned the whole catalogue, and the Transfer step priced USDC on every funding-modal render in every mode.
  Evidence: `hyperopen.domain.token-pricing/token-price-usd` bound `market` eagerly. The account-equity resolver test counted those calls; it now pins that USDC is never looked up.

- Observation: a HyperEVM → Core flow can double-send before any hash exists. The wallet prompt can stay open while the user closes the modal, reopens it and submits again, so two `eth_sendTransaction` prompts are live at once. An in-flight entry written only once a hash returns (the plan's step 7) cannot block the second one.
  Evidence: `stale-flow-writes-are-ignored-after-close-and-reopen-test` in `/hyperopen/test/hyperopen/funding/application/hyperevm_submit_test.cljs` defers the first send, reopens the modal and submits again; the second move is refused only because the entry is written when the flow starts.

- Observation: the plan's acceptance wallet log for HyperEVM → Core HYPE (`eth_chainId`, switch, `eth_chainId`, `eth_sendTransaction`) has room for only one chain read between the switch and the send. The verified switch's re-read therefore has to be the pre-send check for the first transaction, with no await in between. Every later transaction (the USDC deposit) re-reads `eth_chainId` right before its send.
  Evidence: `native-hype-switches-verifies-and-sends-one-pinned-transaction-test` pins that exact log.

- Observation: a `sendAsset` Core → HyperEVM request that was signed asynchronously broke `a-valid-core-to-evm-send-still-signs-as-send-asset-test`, which reads the signer's calls synchronously. The legacy path calls the signer synchronously, so the Core → HyperEVM and HyperEVM → Core effects do too (inside a `try` that turns a throw into a rejection).
  Evidence: `/hyperopen/test/hyperopen/funding/application/submit_effects_transfer_guard_test.cljs`.

- Observation: the Spot re-read after an order mutation (`hyperopen.order.effects.spot-refresh`) guards on the connected wallet address, not on the account the app shows. Milestone 4 reused it for HyperEVM arrival, so a re-read issued for the owner (up to 40 s after a move, or minutes later when a pending move settled in the background) wrote the owner's Spot into a selected subaccount's or a spectated address's `[:spot :clearinghouse-state]`.
  Evidence: `active-wallet-address?` compares with `[:wallet :address]`, which stays the owner in both modes; the websocket refresh path guards on `account-context/effective-account-address` instead (`websocket/user_runtime/common.cljs` `requested-address-active?`). Pinned by `spot-re-reads-stop-once-another-account-is-shown-test`.

- Observation: in HyperCore -> HyperEVM the `.catch` after the success branch meant any throw in the follow-ups (a dispatched action handler asserting its effects in a validation build, a refresh) rewrote a `:succeeded` run as "Transfer failed", offering to send funds that had already moved.
  Evidence: `core-to-evm-follow-up-errors-never-fail-a-sent-move-test` throws from `dispatch!` and the Spot refresh after an ok response.

- Observation: the app's fixed footer (`z-[170]`) painted over the funding modal (`z-[80]`). On a 375x812 viewport its mobile nav covered the bottom 49 px of every funding sheet, so a sheet's submit button sat half under it; on /trade the chart legend painted over an anchored popover's title. This predates the feature (the withdraw sheet shows it too). The 2026-08-21 PnL share-card plan recorded the same latent layering problem for the funding modal and left it.
  Evidence: a headless Playwright probe against this worktree's `compile app` build: with the real withdraw sheet open at 375x812, `document.elementFromPoint` 10 px above the sheet's bottom returned the footer; the Transfer run and form views probed the same way before the fix.

- Observation: Nexus expands every action of one dispatch against the same state snapshot before running any effect, so a "Try again" wired as `[[:actions/reset-funding-transfer-evm] [:actions/submit-funding-transfer]]` would submit against the still-failed run and be refused.
  Evidence: `nexus.core/dispatch` calls `expand-actions` with `(:state ctx)` once for the whole action vector (nexus 2025.07.1, `nexus/core.cljc`).

- Observation: the dialog's Tab trap counted a link inside a `display:none` slot. `visible-node?` read only the node's own computed `display`, and a descendant of a hidden element reports its own value (`inline` for a link), so the verified-token explorer link (rendered inside the hidden verification notice) became the dialog's last focusable element and Tab from the real last control left the modal.
  Evidence: `focusable-nodes` in `/hyperopen/src/hyperopen/views/ui/dialog_focus.cljs`; pinned by `no-focusable-control-hides-in-a-hidden-slot-test` and `dialog-focus-trap-skips-nodes-under-a-hidden-ancestor-test`.

- Observation: Replicant reuses a node when the old and new element share a tag and key (`replicant.core/reusable?`, 2025.06.21), and hook memory is stored per node (`r/remember`/`r/recall`), not per hook function. Every Transfer view's root is an unkeyed `:div` in the same slot, so a hook on the root sees the view it showed last, which is how "Back to edit" knows to focus the amount.
  Evidence: `replicant/core.cljc` `reusable?` and `call-hook` in the 2025.06.21 jar.

- Observation: a sticky element's inset is measured from the scroll container's padding edge inward, so the footer's `-bottom-4`/`-mb-4`/`pb-4` cancelled exactly 1rem of panel padding. The mobile sheet pads `max(env(safe-area-inset-bottom), 1rem)`, so on a phone with a home indicator the stuck footer floated about 18 px above the sheet's edge.
  Evidence: `mobile-sheet-style` in `/hyperopen/src/hyperopen/views/ui/funding_modal_positioning.cljs`; the M5 review.

- Observation: the Balances coin cell shows the label from `:selection-coin`, not the row's `:coin`. The Perps and Spot USDC rows are built as "USDC (Perps)" and "USDC (Spot)" but both render "USDC", and a HyperEVM USDC row would render "USDC" too. The place chips are therefore what tells the three apart; a first draft that skipped chips on labels naming a place left all three rows reading "USDC".
  Evidence: `balance-coin-display` calls `shared/resolve-coin-display (or selection-coin coin)`; a headless screenshot of `/portfolio` at 1280x800 against this worktree's build showed two unchipped "USDC" rows.

- Observation: the typography test pins the Balances row wrapper to the literal call `(desktop-grid-template-class read-only?)`, so the grid template cannot take a second argument. Widening the Coin track while place chips show is done with a CSS variable inside the arbitrary track instead (`minmax(var(--balances-coin-min,84px),0.78fr)`), set on the tab's root with an inline `:style`.
  Evidence: `balances-tab-uses-12px-typography-for-toggle-and-rows-test` in `/hyperopen/test/hyperopen/views/typography_scale_test.cljs`; Tailwind emits `grid-template-columns:minmax(var(--balances-coin-min,84px),.78fr) …` in `resources/public/css/main.css`.

- Observation: a HyperEVM entry that is still loading its first read compared unequal to no entry, so the /trade account panel repainted when the poller merely started its first request. Every reader treats the two the same, so the projected slice leaves such an entry out.
  Evidence: `account-panel-repaints-only-when-hyperevm-balances-change-test` failed (2 renders instead of 1) before the fix.

- Observation: on `/trade` at 1280x800 the account panel is 960 px wide, below the Balances table's minimum track sum (1,134 px before this milestone, 1,178 px with the wider Transfer track, 1,214 px while place chips show), so the table already scrolled sideways there and the Repay and Contract columns were off-screen before this change.
  Evidence: the Milestone 6 headless probe of this worktree's `compile app` build measured `[data-parity-id="account-tables"]` at 960 px.

- Observation: Replicant never writes an attribute whose value is `false` and removes one that becomes falsy (`set-attributes` filters on the value; `update-attr` calls `remove-attribute`). A boolean `:aria-pressed` therefore left the two unpressed filter options with no `aria-pressed` at all, so assistive tech read them as plain buttons; a hiccup-level test asserting `[false false true]` passed anyway. ARIA state attributes must be the strings "true"/"false".
  Evidence: `replicant/core.cljc` 2025.06.21, and the Milestone 6 review; the Milestone 5 Transfer form already passed strings (`funding_modal/transfer.cljs`).

- Observation: the HyperCore Spot USDC row shows 8 decimals, not 2. Spot rows format at the token's `weiDecimals` (USDC's is 8, "2,105.40000000 USDC"); only the Perps row (`:amount-decimals nil`) formats as currency. The HyperEVM USDC row at its on-chain 6 decimals matched neither.
  Evidence: `token-decimals` in `/hyperopen/src/hyperopen/views/account_info/projections/balances.cljs`; `desktop_test.cljs` pins "201.38936500 USDC".

- Observation: after the review fixes, a headless probe of this worktree's build measured the Spot USDC row's "To HyperEVM" at 850-928 px on `/trade` at 1280x800 (panel 960 px) with place chips showing, and on `/portfolio` at 1440 px both moves still sit on one line (the Transfer track grows past its minimum there). At `/trade` 1440 (panel 1120 px, still narrower than the table's minimums) and `/portfolio` 1280 the two USDC moves wrap onto a second line (row height 52 px).
  Evidence: `scratchpad/qa6fix/probe.mjs` against `compile app` served on port 8093, 2026-09-30T21:20Z.

- Observation: a headless probe that seeds app state once, right after changing identity (connecting the wallet, selecting a subaccount), loses the seed: the account lifecycle resets the account surfaces asynchronously, which clears `[:hyperevm :balances :by-address]`, Spot and webdata2, so the strip read "Loading…" and $0.00. Seeding a second time after a short pause sticks. Milestone 8's Playwright fixtures should seed (or route) after the identity settles.
  Evidence: the Milestone 7 probe (`scratchpad/qa7/probe.mjs`, `compile app` served on port 8094), first run versus the run that seeds twice.

- Observation: on `/trade` the equity metrics and the HyperEVM line already price against the same catalogue. `account-equity-view-state` merges `asset-selector-market-lookup-state`, so the metrics call `memoized-balance-rows` with the real `[:asset-selector :market-by-key]`, exactly the arguments `hyperevm-funds/core-balance-rows` passes, and the two share the single-slot memo (no thrash). Every active-asset ctx tick makes a new catalogue object and so rebuilds the balance rows once for the metrics; that predates this feature. What the line added was re-pricing its own rows (`hyperevm-rows`) on each such tick, for every account whose HyperEVM read had landed, holders or not.
  Evidence: `/hyperopen/test/hyperopen/views/trade_view/hyperevm_funds_cost_test.cljs` counts `build-balance-rows` calls with and without the line export (equal: `[1 0 1 1 1 1]` over a first render, a repeat render, two perp ticks, a webdata2 tick and a held token's market tick) and `hyperevm-rows` calls (`[1 0 0 0 1 1]` for a holder, all zero for an empty wallet).

- Observation: a HyperEVM read can land with whole aggregate3 chunks unread, and the entry kept those next to per-call failures in one `:unread-token-indexes` list, so no reader could tell "this read is incomplete" (a failed chunk, fixed by the next poll) from JOFF's `balanceOf`, which reverts on every read. A token answered as zero earlier also becomes unknown when its chunk is lost later (zeros are not stored), so "partial" has to mean "never answered by any read", or every transient chunk failure would blank the total.
  Evidence: `collect-balances` in `/hyperopen/src/hyperopen/hyperevm/infrastructure/rpc.cljs` and `apply-success` in `/hyperopen/src/hyperopen/hyperevm/domain/balances.cljs`; pinned by `a-chunk-never-answered-leaves-the-read-partial-test`.

- Observation: the debug exchange simulator never matches a per-action-type response queue for signed actions. `installExchangeSimulator` keywordizes its config (`{:signedActions {:sendAsset …}}`), but `post-signed-action!` looks it up with the action's string type (`[:signedActions "sendAsset"]`), so only the `default` queue answers; a spec that configures only `sendAsset` falls through to the real exchange. (Info queues work: their path uses `(keyword type)`.)
  Evidence: the first run of case (b) with `signedActions {sendAsset {responses [ok]}}` plus a `default` refusal got the refusal ("Transfer failed: Unexpected signed action in this spec."); `simulated-fetch-response` in `/hyperopen/src/hyperopen/api/trading/debug_exchange_simulator.cljs` and its caller in `/hyperopen/src/hyperopen/api/trading/http.cljs`. `subaccounts-regressions.spec.mjs` configures `sendAsset`/`subAccountTransfer` queues the same way. Left as it was at first (outside this plan; the HyperEVM spec queues every answer on `default`), then fixed in the Milestone 8 review fixes: the lookup is by keyword now.

- Observation: under a burst of test runs the live info API answers 429 to `metaAndAssetCtxs`, `perpDexs` and `spotMetaAndAssetCtxs`. The asset selector's full load then never finishes, so `[:spot :meta]` is never stored even though `spotMeta` itself answered 200, and without it the HyperEVM refresh plan (which needs linked tokens) never reads anything: no RPC request at all, balances unknown for good.
  Evidence: a failing repeat of cases (a), (l) and (m) (1 in 6 to 1 in 3 runs): `[:spot :meta]` nil after 15 s, the RPC route saw no request, and the page's info log read `metaAndAssetCtxs:429, perpDexs:429, spotMeta:200, spotMetaAndAssetCtxs:429, …` with the asset selector at `:full`, `loading? true`. With the catalog routed from fixtures, 44/44 runs passed.

- Observation: the live websocket's user `clearinghouseState` stream overwrites a fixture account's REST clearinghouse state with zeros (the address has no HyperCore account), so Perps read $0.00 a moment after the fixture's 5,000 USDC landed.
  Evidence: the Milestone 8 probe: `[:webdata2 :clearinghouseState :marginSummary :accountValue]` "0.0" with the REST route answering "5000.0", and `{"method":"subscribe","subscription":{"type":"clearinghouseState","user":"0x1234…","dex":""}}` in the page's websocket frames; dropping only the balance-stream subscriptions (`clearinghouseState`, `webData2`, `spotState`) kept "5000.0".

- Observation: `visitRoute` keeps the query string only for `/trade`; any other route is reached by dispatching `:actions/navigate` with the bare path, so `/portfolio?spectate=…` through `visitRoute` loses spectate. Spectate cases therefore start from `/trade?spectate=…`. One existing case (`portfolio volume history follows the spectated account user fees`) opens `/portfolio?spectate=…` with `page.goto` and never calls `visitRoute`, so it needed its own `routeHyperEvmRpc` (since the review fixes, the shared guard's context-level mock answers it instead).
  Evidence: `splitRoute`/`visitRoute` in `/hyperopen/tools/playwright/support/hyperopen.mjs`; `portfolio-regressions.spec.mjs` around line 2990.

- Observation: with an account shown, the Milestone 7 "Where your funds are" strip (441 px tall on a 375x812 phone) pushes the Portfolio volume-history trigger to y=745. The shared anchored popover never flips above its anchor; it clamps its top to fit the viewport (`viewport - estimated height - margin`), so the 500 px popover opens at y=296 and covers the trigger. `portfolio volume history opens near the metric card trigger` measured top-to-top distance (449 px, limit 160) and failed on this branch only; it passes on a clean HEAD build, where the trigger sits at y=288 and the popover at 284.
  Evidence: a probe of both builds at 375x812 with `[:wallet :address]` seeded (trigger 745 / popover 296-796 on this branch; trigger 288 / popover 284 on HEAD with no strip); `anchored-popover-layout-style` in `/hyperopen/src/hyperopen/views/ui/anchored_popover.cljs`.

- Observation: the Milestone 8 "unrouted request" guard could never fail in a real browser, and two specs still reached the public HyperEVM RPC. `routeHyperEvmRpc` installed its `page.on("request")` observer and its `page.route` together with the same host predicate, so every observed request was also handled, and pages that never got the route were not observed at all. `chart-custom-range.spec.mjs` (`visitSharedUrl`, a bare `page.goto` followed by seeding spectate mode) and `shareable-view-url.spec.mjs` (`openFreshPageAt`, a new context on `/portfolio?spectate=0x162c…` and a trader route) showed a real address without `visitRoute`, so the poller sent live `eth_getBalance`/`aggregate3` reads.
  Evidence: the Milestone 8 review; `routeHyperEvmRpc` and `visitRoute` as they stood. Under the context-level guard both specs now pass with every RPC request answered by the mock (`scratchpad/m8fix-leak-specs.log`, 18 of 19 passed before the volume-history fix; then 3/3 volume-history cases).

- Observation: live shapes the mock had not pinned. A 21-entry JSON-RPC batch gets HTTP 200 and `{"jsonrpc":"2.0","id":null,"error":{"code":-32010,"message":"The batch request was too large","data":"Exceeded max limit of 20"}}`. `eth_estimateGas` of `deposit(1000000000, 4294967295)` on the CoreDepositWallet without the allowance answers code 3, `execution reverted: ERC20: transfer amount exceeds allowance`, with the revert data. `subAccounts` of an address with none answers JSON `null`.
  Evidence: one read-only request each to https://rpc.hyperliquid.xyz/evm and https://api.hyperliquid.xyz/info at 2026-10-01T00:28:43Z (`scratchpad/m8fix-live-shapes.txt`); the fixture constants `BATCH_TOO_LARGE_BODY` and `ALLOWANCE_REVERT` were compared byte for byte against those answers.

- Observation: the HyperEVM spec still sent eight user reads to the live info API for its account-less fixture addresses: `portfolio`, `userFees`, `delegatorSummary`, `userNonFundingLedgerUpdates`, `userFunding`, `userFills`, `frontendOpenOrders` and `historicalOrders` (the first four in every case). They are now answered from their live answers for the fixture owner, captured once.
  Evidence: `routeHyperCoreInfo(page, info, { strict: true })` recorded them in the first run of the reworked spec (`scratchpad/m8fix-spec-run1.log`, 25 of 26 cases); the answers are in `/hyperopen/tools/playwright/support/account_less_user_reads.json` (captured 2026-10-01T00:41:09Z).

- Observation: on a 375 px phone the Balances toolbar keeps the coin search and the All/Core/EVM filter on one row and wraps "Hide Small Balances" onto a second. The comment on `balances-header-actions` ("the location filter drops under the search on a phone") describes narrower widths, not 375 px.
  Evidence: the reworked case (a) measured the filter's top (435) above the search's bottom (465) at 375x812; the failure screenshot shows both on one row.

- Observation: on a clean HEAD build the Portfolio volume-history popover also covers its trigger on a 375 px phone. The anchored popover fits neither left nor right of the anchor there, so it becomes a full-width overlay whose top follows the trigger's; covering the trigger is the existing phone behaviour, not a Milestone 7 regression. What Milestone 7 changed is how far the popover is clamped up when the trigger sits low.
  Evidence: a probe of the HEAD baseline on port 8096 at 375x812: trigger (25, 288)-(103, 312), popover (12, 284)-(363, 784), `elementFromPoint` at the trigger's centre inside the popover.

- Observation: the app scrolls inside a container, not the window, so `window.scrollBy` in a test moves nothing. Helpers that place an element on screen must scroll its nearest scrolling ancestor.
  Evidence: the first run of the restored volume-history anchoring check read the trigger at y=745 after `window.scrollBy(0, 545)`.

- Observation: of the six cases that failed in Milestone 8's broadened run, five fail identically on a clean HEAD build, including the optimizer "From holdings seeds current exposure constraints" case (`Expected: "0.95"`, `Received: "1"` on HEAD too). The icon probe and trading-settings toggles, which passed in Milestone 8's run, also fail on HEAD, so they are environmental (they depend on the dev server) rather than branch effects.
  Evidence: HEAD's specs run against the `git archive HEAD` build served on port 8096, 2026-10-01T00:24Z: 7 failed, 1 passed (the volume-history case, as expected on HEAD). Log: `scratchpad/m8fix-baseline-run.log`.

- Observation: the Balances header's location filter costs the account tab strip 218 px wherever the tab header is one row (768 px and up). The Balances actions shell measured 529 px against 311 px on HEAD. At `/portfolio` 768 the strip was left 205 px wide and the selected "Balances" tab sat outside it (only "Performance Metrics" and part of "Monte Carlo" showed). On `/trade` the strip goes from 649 to 431 px at 1280 and from 809 to 591 px at 1440 while Balances is selected, so Funding History and Order History move behind the strip's hidden-scrollbar sideways scroll.
  Evidence: the QA compare probe (`scratchpad/qa/m8qa-compare-{branch,head}.jsonl`, screenshots `compare-tabstrip-*`), this worktree's build on port 8093 against the `git archive HEAD` build on port 8096, 2026-10-01T01:40Z.

- Observation: at 375 px a toast (z 280, above the funding sheet at 262) sits over the sheet's lower content for its 3.5 s lifetime. In the failed view it covered "Try again" for 3.95 s, and in the pending and success views it covered the explorer link and "View in Account Activity". HEAD's `set-funding-submit-error!` already toasts over an open funding modal, so the layering is pre-existing; the HyperEVM run views make it visible because the modal stays open after the move.
  Evidence: `scratchpad/qa/m8qa-toast.jsonl` (`coveredBy "global-toast-headline"`, `clearedAfterMs 3954`), `transfer-failed-375.png` and `transfer-failed-375-after-toast.png`.

- Observation: the funds strip appears only once an account is shown, so connecting a wallet on `/portfolio` shifts everything below it down by the strip's height (116 px at 1280, 441 px at 375). In the scripted connect flow the load-time layout shift was 0.114 at 1280x800 (HEAD 0.039) and 0.589 at 375x812, almost all of it the one shift at connect. Idle polls and every interaction (five modal open/close cycles, filter toggles) shifted nothing, and the strip's rect was identical across 9 s of polls.
  Evidence: `scratchpad/qa/m8qa-jank-branch-run1.jsonl` and `m8qa-jank-head.jsonl` (PerformanceObserver `layout-shift`, buffered).

- Observation: Replicant writes attribute names exactly as given, so `:tab-index -1` on the run views' headings put an unknown `tab-index` attribute in the DOM. The headings were never focusable, `focus-when-shown` was a no-op in every browser, and after a submit focus fell to the dialog's first control, the × Close button, where a repeated Enter closed the modal mid-transfer. The hiccup-level unit test passed because it read back the key the view used. The dialog panels' `:tab-index 0` (pre-existing) made their focus fallback a no-op the same way, and about twenty other views outside this feature spell it the same way.
  Evidence: `set-attribute` in `replicant/dom.cljs` (2025.06.21 jar) calls `(.setAttribute el attr v)` with no alias; the Milestone 8 QA record (`scratchpad/qa/m8qa-modal.jsonl`) shows `activeElement` `funding-modal-close` in the progress view at every width; the Milestone 9 Playwright checks (`toBeFocused` on the progress, failed, pending and success headings) pass only with `:tabindex`.

- Observation: Playwright treats `aria-disabled="true"` as disabled: `toBeEnabled()` fails and `click()` waits for it to become enabled until the test times out. A control that is deliberately focusable but inert (the busy gas fix) is asserted with `el.disabled === false` and clicked with `{ force: true }`.
  Evidence: the first Milestone 9 run of cases (e): `expect(locator).toBeEnabled() failed` on an `aria-disabled` button, and `locator.click: Test timeout of 45000ms exceeded … element is not enabled` (`scratchpad/m9/pw-spec-run1.log`).

- Observation: on a short Balances table (a subaccount with two rows) a disabled move's downward tooltip extends past the rows viewport's bottom and is clipped there, so a pointer moved to the tooltip's centre lands outside it and the hover ends. The part next to the action is reachable; the clipped part is the remaining risk recorded since Milestone 6.
  Evidence: the first Milestone 9 run of case (f): opacity went 0.97 → 0.03 → 0 as the pointer reached the tooltip's measured centre; targeting the text just past the gap passes, with `document.elementFromPoint` inside the tooltip.

- Observation: the release `main` module already exceeded its advisory gzip budget before this feature. A release compile of a clean `git archive HEAD` export measures `main` at 676,855 gzip bytes against the 640,000 budget in `/hyperopen/tools/release-assets/bundle-budget.json`; this branch measures 719,156 (+42,301, +6.2%), from 33 namespaces newly reachable from `hyperopen.core` (the HyperEVM domain, RPC client, poller, and the funding Transfer domain, commands and view-model). `funding_modal` grows by 9,668 gzip bytes and `account_surfaces` by 7,148, both lazy. `hyperopen.domain.account-ledger.derive` sits in the lazy `portfolio_route` module on HEAD and again on this branch after Milestone 9 removed the pre-signing invariant's require of it.
  Evidence: `npx shadow-cljs --force-spawn release app` in both trees, each module gzipped at level 9 as `check_bundle_budget.mjs` does (`scratchpad/m9/release-{head,branch}-sizes.json`), and the release `manifest.json` `sources` lists, 2026-10-01T03:27Z.

- Observation: between 768 and 1023 px the account tab header is 97 px tall while Balances is selected and 49 px on every other tab, on `/portfolio` and `/trade`, so switching to or from Balances moves the table content 48 px. From 1024 px up the header is one 49 px row on every tab; with the short filter labels the Balances actions take 459 px (529 px before), leaving the `/trade` tab strip 501 px at 1280 (Milestone 8: 431; HEAD: 649) and 661 px at 1440 (Milestone 8: 591; HEAD: 809). From 1536 px (2xl) the long labels return.
  Evidence: `scratchpad/m9/header_probe.mjs` against this worktree's `compile app` build on port 8093, no account shown, 2026-10-01T03:26Z (`scratchpad/m9/header-probe-branch.jsonl`).

## Decision Log

- Decision: support HyperEVM mainnet (chain 999) only.
  Rationale: HyperCore info and exchange URLs are hard-coded to mainnet (`/hyperopen/src/hyperopen/api/trading/http.cljs`, `/hyperopen/src/hyperopen/config.cljs`), and `signing-context-for-chain-id` maps every chain except 421614 to "Mainnet". Supporting 998 would mis-sign and strand funds.
  Date/Author: 2026-09-30 / Claude.

- Decision: HyperEVM funds count toward the Portfolio strip's "Total value" only. They never count toward trading equity, margin ratios, the Portfolio summary card's Total Equity, or the Monte Carlo start equity.
  Rationale: HyperEVM funds cannot margin a position. The shared memoized balance rows feed `derive-account-equity-metrics` (spot equity, portfolio value, unified leverage), so EVM rows must never enter `build-balance-rows` or `memoized-balance-rows`.
  Date/Author: 2026-09-30 / Claude (default chosen because the user did not answer the open question; recorded for later override).

- Decision: EVM moves are master-account only. When a subaccount is selected, EVM options stay visible but disabled, with the reason "HyperEVM transfers are available for the master account only". Spectate mode and trader routes are read-only: balances are visible and all move actions are disabled.
  Rationale: subaccounts have no private key. This matches Hyperliquid's own app, which disables its EVM button for non-master accounts.
  Date/Author: 2026-09-30 / Claude.

- Decision: when the wallet cannot switch to chain 999, the EVM→Core attempt fails with a plain explanation in the progress panel. The app caches that capability per wallet provider in `[:hyperevm :wallet-capabilities]`, and later EVM→Core routes show disabled with the same explanation until the provider changes.
  Rationale: EIP-1193 has no way to probe this capability up front. Codes 4200 and -32601, or a chain that doesn't change after a switch, are the reliable signals.
  Date/Author: 2026-09-30 / Claude.

- Decision: route every direction through the existing `:actions/submit-funding-transfer` → `:effects/api-submit-funding-transfer`.
  - Core→EVM is an ordinary `sendAsset` request.
  - EVM→Core uses a client-only pseudo action type, `"hyperEvmToCore"`, which is never posted to Hyperliquid. `submit_effects.cljs` branches on it, following the precedent of `bridge2Deposit` and `hyperunitSendAssetWithdraw` in the deposit and withdraw submits.
  Rationale: this avoids new effect-order policies and Lean formal sync for the main flow, and keeps the legacy Perps↔Spot path byte-identical.
  Date/Author: 2026-09-30 / Claude.

- Decision: the gas fix is a dedicated covered action, `:actions/submit-funding-transfer-gas-topup`. It submits a 0.05 HYPE Spot→HyperEVM `sendAsset` directly (HYPE Core→EVM has no fee) while the user's EVM→Core draft stays intact.
  Rationale: it is one click and the draft survives, which is the best UX. It needs one effect-order policy entry plus the Lean corpus line and `bb tools/formal.clj sync --surface effect-order-contract`. `lean` and `lake` are installed at `~/.elan/bin` with toolchain v4.28.0. If Lean sync proves impossible in this environment, fall back to a projection-only preset and record that here.
  Date/Author: 2026-09-30 / Claude.

- Decision: From and To are segmented button groups (Perps / Spot / HyperEVM) with a swap button, not dropdowns. The asset picker is an always-visible radio-group list of the eligible assets held on the source side, sorted by value.
  Rationale: this needs no open/close state (so no nil-hole or `<details>` reset traps under Replicant), passes the design-review native-control rule, and needs fewer actions.
  Date/Author: 2026-09-30 / Claude.

- Decision: there is no separate compact "row quick-move" popover. A row's move action opens the same Transfer modal anchored to the clicked button: a 448 px popover on desktop and a bottom sheet at ≤640 px. It is preset to that row's asset and direction.
  Rationale: the anchored funding modal already behaves as a popover. A second popover system would duplicate anchoring, focus return and mobile-sheet logic. This is a deliberate small deviation from the canvas.
  Date/Author: 2026-09-30 / Claude.

- Decision: keep every existing opener data-role (`portfolio-action-perps-spot`, `funding-action-transfer`) and change only visible labels to "Transfer". Do not add a header Send button or any `portfolio-action-evm-core` role.
  Rationale: four test surfaces and one browser-inspection scenario assert these roles and absences (`header_test.cljs`, `portfolio_view_test.cljs`, `portfolio-regressions.spec.mjs:1450-1466`, `tools/browser-inspection/scenarios/portfolio-interaction-states.json:77`), and the header must keep exactly 5 buttons.
  Date/Author: 2026-09-30 / Claude.

- Decision: the Balances table keeps its 9 columns. The EVM chip sits in the Coin cell. The 7th column keeps the header "Transfer" and holds one or more short move actions ("To Perps", "To Spot", "To HyperEVM").
  Rationale: this preserves header and row-geometry tests and the typography gate's regex pins. HyperCore rows also get a SPOT or PERPS chip only when the location filter is "All" and the account has EVM rows, so the location stays unambiguous without cluttering accounts that never use HyperEVM.
  Date/Author: 2026-09-30 / Claude.

- Decision: EVM transactions send explicit `gas`, `maxFeePerGas` and `maxPriorityFeePerGas` ("0x0"), never `gasPrice`.
  - `gas` = RPC `eth_estimateGas` × 1.3, falling back to a per-kind table.
  - `maxFeePerGas` = 2 × `eth_gasPrice`, with a 0.2 gwei floor.
  Rationale: Hyperliquid's FAQ acknowledges MetaMask failing on HyperEVM with "type 0x2 but included a gasPrice". Explicit EIP-1559 fields avoid the wallet synthesising `gasPrice`. The priority fee is burned on HyperEVM and `eth_maxPriorityFeePerGas` returns 0. This remains an unverified mitigation; a manual wallet check is listed under remaining risks.
  Date/Author: 2026-09-30 / Claude.

- Decision: in Milestone 0, renew every size and boundary exception that expires 2026-09-30 to 2026-12-31, with an explicit reason: "renewed 2026-09-30 so gates stay green through the HyperEVM transfer branch; the retirement work is still owed". Also split the funding action-argument specs out of `action_args.cljs`.
  Rationale: this is required for any gate to pass from 2026-10-01. It is surfaced to the user in the final report because it changes debt deadlines.
  Date/Author: 2026-09-30 / Claude.

- Decision: in Milestone 0, move all 8 funding modal action-arg specs and their 29 map entries into `funding_action_args.cljs`. The plan named only `action_args.cljs:69-73`, which holds just `funding-transfer-open-args`. The 8 are `funding-modal`, `funding-modal-field`, `submit-funding-repay`, `set-hyperunit-lifecycle`, `set-hyperunit-lifecycle-error`, `funding-send-open`, `funding-modal-open` and `funding-transfer-open`. `action-args-spec-by-id` is now `(merge core-action-args-spec-by-id funding-action-args/funding-action-args-spec-by-id)`. The funding-history specs stay in `action_args.cljs`.
  Rationale: the moved map entries refer to all 8 specs, so moving only one would split the funding modal contract across two files. The spec keywords now live under `hyperopen.schema.contracts.funding-action-args/`, and no other namespace referred to the old names. The two maps share no action id, so `merge` shadows nothing; together they hold the same 566 ids as before.
  Date/Author: 2026-09-30 / Claude.

- Decision: in Milestone 0, `:funding-modal-vm.transfer/actions` stays in `/hyperopen/src/hyperopen/schema/funding_modal_contracts.cljs`, just after the send submap specs. Every other transfer VM spec moved to `funding_modal_transfer_contracts.cljs`, which the parent requires.
  Rationale: the child loads first, and cljs.spec resolves aliases at definition time (see Surprises & Discoveries). An alias to `:funding-modal-vm/actions` defined in the child would silently become a keyword predicate and reject every transfer VM. The child cannot require the parent, because that would be a cycle. Milestone 4 must follow the same rule. Any new transfer spec that aliases a parent-defined spec, such as `:funding-modal-vm/actions` or `:funding-modal-vm/destination`, must be registered in the parent after its target. Alternatively, the shared targets can move to a base namespace that the child requires. `/hyperopen/test/hyperopen/schema/funding_modal_transfer_contracts_test.cljs` checks that the alias resolves.
  Date/Author: 2026-09-30 / Claude.

- Decision: `expected-open-modal` in `actions_test.cljs` now merges over `default-funding-modal-state`, and a new literal test in `/hyperopen/test/hyperopen/funding/application/modal_state_test.cljs` pins every default value.
  Rationale: the merge stops new default keys from breaking each open-modal expectation. Without a literal pin, though, a wrong default would pass silently. Milestone 4 must add its five new nil defaults (`:transfer-from`, `:transfer-to`, `:transfer-asset`, `:transfer-evm`, `:transfer-gas-topup`) to that literal on purpose.
  Date/Author: 2026-09-30 / Claude.

- Decision: fold the pre-implementation critique into the plan and reorder the milestones: state, then modal domain, then submit, then views, then Balances, then Portfolio/Trade, then Playwright.
  Rationale: Balances row move actions pass `{:from :to :asset}` context. The open command ignores that context until the modal domain lands, so shipping Balances first would open a USDC Spot→Perps form from a PURR row.
  Date/Author: 2026-09-30 / Claude.

- Decision: bridge solvency and token health are hard gates, not notices.
  - Core→EVM is capped by `balanceOf(systemAddress)` on EVM. USDC and HYPE are unlimited.
  - EVM→Core is capped by the Core system address's spot balance, fetched on demand.
  - Tokens whose `decimals()` or `balanceOf` misbehave are unmovable.
  Rationale: Hyperliquid runs no checks. A move of SIX or NAV to HyperEVM would debit Core and never credit EVM.
  Date/Author: 2026-09-30 / Claude.

- Decision: after a HyperEVM transaction hash exists, the flow never returns to a submittable form.
  - Hashes are recorded at `[:hyperevm :in-flight <owner>]`, outside the modal.
  - Receipt waits retry transient errors and end in a "pending" view after a timeout.
  - New EVM→Core submits are blocked while one is unresolved.
  - Every progress write carries a flow id, and stale flows cannot write into a reopened modal.
  Rationale: a double send moves funds twice.
  Date/Author: 2026-09-30 / Claude.

- Decision: every HyperEVM `eth_sendTransaction` carries `chainId "0x3e7"`, and the flow re-reads `eth_chainId` immediately before each send.
  Rationale: a chain change between approve and deposit would otherwise send the other chain's native coin to `0x2222…`.
  Date/Author: 2026-09-30 / Claude.

- Decision: the post-switch chain verification in `ensure-wallet-chain!` is opt-in (`:verify-switch?`, set only on the HyperEVM config).
  Rationale: deposit flows and their exact-call-sequence test must stay unchanged.
  Date/Author: 2026-09-30 / Claude.

- Decision: the price resolver moves to a non-view `hyperopen.domain.token-pricing`, and `views/account_equity/pricing.cljs` delegates to it.
  Rationale: the funding view-model needs USD estimates and asset ordering, but may not import views.
  Date/Author: 2026-09-30 / Claude.

- Decision: EVM routes use route precision (`min(weiDecimals, evmDecimals)`) and floor in both directions. `transfer-max-amount*` returns a floored string for EVM routes and a number for the legacy route.
  Rationale: today's USDC formatter rounds half-up with `toFixed(6)`, which would make MAX exceed the balance.
  Date/Author: 2026-09-30 / Claude.

- Decision: design elements restored after the critique:
  - percent chips and a USD estimate;
  - submit labels that include the amount, and an "already on 0x3e7" variant;
  - "Waiting for wallet (step n of m)";
  - the close-mid-flight reassurance;
  - arriving and arrived states;
  - "View in Deposits & Transfers".
  The only deliberate deviation left is the absence of a separate row quick-move popover, recorded above.
  Rationale: fidelity to the approved canvas.
  Date/Author: 2026-09-30 / Claude.

- Decision (Milestone 1 as built): these are the real signatures and behaviors that later milestones code against.
  - `rpc/wait-for-receipt! [deps tx-hash opts]`:
    - `deps` holds the per-request `:fetch-fn`, `:timeout-ms` and `:set-timeout-fn`.
    - `opts` holds `:poll-ms` (1000), `:timeout-ms` (180000, whole wait), `:schedule-poll-fn` and `:now-ms-fn`. A nil value means the default.
    - A failed poll is retried until the deadline.
  - `rpc/read-balances! [deps {:owner :tokens :chain}]` sends one JSON-RPC batch: `eth_getBalance`, then `eth_gasPrice`, then one `aggregate3` of `balanceOf` calls per 150 tokens.
    - A failed or malformed chunk adds its indexes to `:unread-token-indexes`; they are unknown, never zero.
    - Only an unreadable native balance or gas price, or a whole-request failure, rejects.
  - The token catalog adds `:evm->core-recipient`: `0x2222…` for `:native`, the system address for `:erc20`, and nil for `:usdc-cdw`, since USDC must go through `deposit`.
  - `linked-tokens` drops USDC when spotMeta's CoreDepositWallet differs from the chain config.
  - `fees/evm-gas-status` returns nil when the balance is unknown.
  - Extra public helpers:
    - `rpc/gas-price!` and `rpc/eth-call!`;
    - `abi/quantity-hex`, `abi/parse-quantity` and `abi/selectors`;
    - `units/compare-amounts`;
    - `fees/evm-tx-cost-wei`;
    - `chain/explorer-tx-url` and `chain/explorer-token-url`.
  - Shared real-payload fixtures live in `/hyperopen/test/hyperopen/hyperevm/test_support/fixtures.cljs`.
  Rationale: the Milestone 1 review found fund-safety and robustness gaps, and these changes close them: native balance and gas price no longer depend on third-party contracts, and USDC's direction is explicit.
  Date/Author: 2026-09-30 / Claude.

- Decision (Milestone 2 as built): these are the real shapes and signatures that later milestones code against.
  - State at `[:hyperevm]` is `(hyperopen.hyperevm.domain.balances/default-state)`. It holds the plan's keys plus two additions: `:core-account {}` (owner → `:active|:missing`) and `:backoff {:strikes 0 :until-ms nil}`.
  - A `:by-address` entry also carries `:token-indexes`, the set of indexes the read covered, so a token added to spotMeta since the read is unknown (nil) rather than "0".
  - Rate-limit backoff lives in app-db, not in the poller, so the pure `refresh-plan` applies it to fingerprint dispatches, ticks, and forced refreshes alike (30 s, 60 s, then 120 s). It is settled once per poll (see the review-fix entry below). A forced refresh bypasses freshness, the surface check, the loading guard and the receipt-wait pause, but never the backoff.
  - `rpc/read-balances!` accepts `:extra-calls` (`[{:key :target :call-data}]`). The calls ride the same JSON-RPC batch as allowFailure `aggregate3` chunks, and their outcomes come back as `:extra-results {key {:success? :return-data}}` and `:extra-unread [key]`. Bridge health rides only the FIRST address's batch, so a poll reads it once.
  - `hyperopen.hyperevm.domain.bridge` provides:
    - `health-calls`, `pending-decimals-indexes`, `health-result` and `apply-health`;
    - `token-health-status` (`:ok|:bad|:unknown`) and `movable?` (true only for `:ok`, so unknown health is not movable). Milestone 3 uses `token-health-status` to say "Checking…" for unknown health instead of "unmovable";
    - `core->evm-capacity` and `evm->core-capacity`, each a Core-precision string, `:unlimited`, or nil for unknown;
    - `core-capacity-fetch-needed?`, `apply-core-system-balance` and `apply-core-system-balance-error`.
  - `hyperopen.hyperevm.domain.balances` also exposes `fast-polling?`, `waiting-receipt?`, `backing-off?`, `apply-rate-limit` and `apply-core-account-role`. `evm-gas-status` takes `[state address]` or `[state address kinds]` (default `[:native]`).
  - Runtime ids:
    - actions `:actions/refresh-hyperevm-balances [{:now-ms :force? :fast-poll-ms}]` and `:actions/refresh-hyperevm-bridge-capacity [idx]`;
    - effects `:effects/fetch-hyperevm-balances [{:addresses :requested-at-ms}]`, `:effects/fetch-hyperevm-core-bridge-balance [idx address]`, and the sibling `:effects/fetch-hyperevm-core-account-status [owner]` (`userRole`).
    None of them is effect-order covered. The effect handlers sit under `:api` and point at the same `hyperopen.runtime.effect-adapters.hyperevm` functions in both `runtime/collaborators.cljs` and `app/effects.cljs`.
  - `userRole` goes through the rate-limited info client as `hyperopen.api.default/request-user-role!`, following the existing five-layer pattern with a 60 s TTL. The Core-balance effect refuses any address other than the catalog's system address for that index. A forged capacity would otherwise let a move past the real cap.
  - The poller is one 4 s interval. On each tick it dispatches an unforced refresh while fast-polling, and otherwise at most every 30 s while a surface is active and the document is visible. `app/bootstrap.cljs` installs it only when `js/document` exists, which keeps the Node test runtime off the public RPC. Re-installing on dev reload replaces the interval instead of stacking a second one. The kill switch is `HYPEROPEN_DEBUG.setHyperevmPollerEnabled(bool)`, and `qaReset` turns the poller back on.
  - `reset-account-surface-state` clears `[:hyperevm :balances :by-address]` and keeps `:in-flight`. The account lifecycle invariant now also requires `:by-address` to be empty when there is no effective account.
  - Ledger: a Core→EVM `send` is labelled from the venue its `sourceDex` names, so USDC from perps reads "Perps → HyperEVM". A `send` FROM a system address is also labelled, as HyperEVM → destination venue.
  - Two caps were raised, each with an appended reason: `action_args.cljs` 742 → 744 for the require and merge lines, and `telemetry/console_preload.cljs` 550 → 551 for one debug-api key.
  Rationale: each item either closes a fund-safety gap (the address check, backoff enforced in pure code, unknown ≠ zero) or keeps tests and gates deterministic.
  Date/Author: 2026-09-30 / Claude.

- Decision (Milestone 2 review fixes): these contracts replace the matching parts of "Milestone 2 as built". Milestones 3 and 4 code against them.
  - **Receipt-wait pause.**
    - `balances/waiting-receipt? [state now-ms]` counts only in-flight entries that have `:waiting-receipt? true` and a numeric `:submitted-at-ms` less than `receipt-wait-pause-ms` (180000, equal to `rpc/default-receipt-timeout-ms`) old. An entry without `:submitted-at-ms` pauses nothing.
    - While the pause holds, an unforced refresh skips only addresses that already hold a read. An address never read is always planned, so an account switch during a wait still gets its first read.
    - Milestone 4 must write `:submitted-at-ms` with each in-flight entry. When the foreground wait ends in "pending", it should set `:waiting-receipt? false` and keep the entry unresolved for the `:in-flight` submit guard.
  - **Forcing and the loading guard.**
    - `:force?` is reserved for explicit post-transfer refreshes. The poller never forces, fast-poll ticks included.
    - A running fast poll counts as an active surface and drops freshness to 0. It still honours the receipt-wait pause and the loading guard.
    - The loading guard skips an address whose read is `:loading` and was requested less than `read-timeout-ms` (10000, equal to `rpc/default-timeout-ms`) ago, so a slow reply is never dropped as overtaken. Only `:force?` supersedes it.
  - **Backoff per poll.**
    - `apply-success` no longer resets the backoff. The effect collects each address's outcome (`:ok`, `:rate-limited` or `:error`) and calls `balances/apply-poll-backoff` once per poll.
    - Any rate limit adds exactly one strike. A poll with no rate limit and at least one success resets the backoff.
  - **Bridge-health ordering.**
    - `bridge/apply-health [state result requested-at-ms]` applies a result only when its request is newer than `[:hyperevm :bridge :health-requested-at-ms]`, which is now in the default state.
    - The effect applies health by request time, whether or not the balance reply is still current.
  - **Unknown is never zero.**
    - `read-balances!` reports each owner `balanceOf` that failed, or answered with no word, in `:failed-token-indexes`.
    - `apply-success` folds those into the entry's `:unread-token-indexes`. They keep their previous value, and `token-amount-text` returns nil for them. JOFF's always-reverting `balanceOf` therefore reads as unknown, and Milestone 6 skips it.
    - A failed `decimals()` call is no longer cached as `false`, so it is re-checked on the next poll. Only a definite answer is cached, so HOPE's mismatch stays `:bad`.
  - **Activation re-reads.**
    - Only `:active` is final. `:actions/refresh-hyperevm-bridge-capacity` re-reads a `:missing` (or unknown) status on every call.
    - `fetch-core-account-status!` passes `:force-refresh? true` whenever the stored status is `:missing`, so the 60 s `:user-role` info cache cannot return a stale "missing".
    - Milestone 3 must dispatch this action on every EVM→Core route or asset selection, including a modal opened preset to one. Milestone 4 must dispatch it after any completed Core-bound transfer, because a USDC EVM→Core move activates the account.
  - **EVM→Core capacity freshness.**
    - `bridge/evm->core-capacity` returns nil (unknown) whenever the latest fetch recorded an `:error`, even though the last amount stays in state.
    - A 3-arity `[state token now-ms]` also returns nil once the amount is `core-capacity-max-age-ms` (60000) old. Milestone 3's preview and MAX must call the 3-arity with the clock.
  - **Account resets.** `watch-fingerprint` now includes whether each address has an entry. A reset that clears `:by-address` therefore triggers the debounced re-read, instead of waiting up to 30 s.
  - **Tests pin the determinism seams:**
    - app bootstrap installs the poller only when `js/document` exists;
    - `HYPEROPEN_DEBUG.setHyperevmPollerEnabled` toggles the poller;
    - the ledger's `hype-system-address` equals `chain/mainnet`'s, and HYPE's catalog system address is recognised by the ledger.
  - **Not done.** Splitting a gas-starved health chunk to isolate the offending token (the review marked it optional). A persistently griefing linked token still leaves the `decimals()` checks later in its chunk unknown, so those tokens stay unmovable (fail-safe) until it stops.
  Rationale: each change closes a liveness or fund-safety gap that the Milestone 2 review confirmed:
  - a permanent polling pause;
  - a stale "missing" activation;
  - failed calls read as 0;
  - sticky unhealthy tokens;
  - double backoff strikes;
  - stale capacity passing as current.
  Date/Author: 2026-09-30 / Claude.

- Decision (Milestone 3 as built): these are the real shapes and signatures that Milestones 4-7 code against.
  - **Modal keys.** `:transfer-from`/`:transfer-to` are `:perps|:spot|:hyperevm` or nil (both nil = the legacy route derived from `:to-perp?`); `:transfer-asset` is a spot token index; `:transfer-evm` and `:transfer-gas-topup` are maps written by effects. `normalize-modal-state` coerces strings to keywords and digit strings to indexes.
  - **Domain namespaces** (all pure, under `/hyperopen/src/hyperopen/funding/domain/`):
    - `transfer_route.cljs`: `transfer-route [modal]` -> `{:from :to :valid? :legacy?}`, `route-kind`, `evm-route?`, `with-location`, `swapped`, `location-options [state route]` -> `{:from [..] :to [..]}`, `asset-options [state route]`, `eligible-asset-index`, `route-token`, `empty-assets-message`. The plan's `location-options [state route asset]` did not need the asset.
    - `transfer_balances.cljs`: exact decimal-string balances per place (`location-available-text`), `add-text`/`sub-text`/`min-text`/`percent-of-text`, and `display-amount`.
    - `evm_transfer_amounts.cljs`: `route-precision`, `gas-kinds`, `native-gas-reserve`, `source-available`, `capacity`, `max-amount-text [state route token now-ms]`, `validate-amount`.
    - `evm_transfer_preview.cljs`: `evm-transfer-preview [state modal]`/`[state modal now-ms]` -> `{:ok? :display-message :request :blocked :notice}`, with `:blocked` = `{:code :title :message :fix? :explorer-url}`; `blocked-reason`, `core->evm-request`, `evm->core-request`, `gas-topup-request [state]`, `core-fee-hype`, `approx-hype`.
    - `transfer_invariants.cljs`: `check-request [state request]`. System addresses are recognised with `hyperopen.domain.account-ledger.derive/token-system-address?`, so a user wallet that merely starts with `0x20` is never mistaken for one.
    - `transfer_dispatch.cljs`: `transfer-preview*`, `transfer-max-amount*`, `preview*`, each with an optional trailing `now-ms`. `policy.cljs` points at them and also exposes `gas-topup-request`.
  - **Blocked order.** Route, then identity (read-only, subaccount, no owner), then (EVM -> Core only) no provider, in flight, no chain switch, then spotMeta, then asset: unmovable health, unknown HyperEVM entry, empty bridge side, missing gas (EVM -> Core) or Core fee (Core -> EVM), missing Core account (non-USDC), and last the "Checking…" states (unknown health, unknown or stale capacity, unknown activation). Definitive blocks win over checking ones where their data is known. A Perps source with a non-USDC token, a pooled (unified or DEX-abstraction) Perps -> HyperEVM route, and HyperEVM -> Perps all block as `:invalid-route` with their own message.
  - **Clock.** The domain never reads the clock. `modal_actions.cljs` (the composition seam) binds `transfer-preview`, `transfer-max-amount` and `preview` to `platform/now-ms`, so the Transfer commands and view-model always age the HyperCore bridge capacity (the 3-arity `evm->core-capacity`).
  - **Commands** (`transfer_commands.cljs`): `open-funding-transfer-modal` (legacy context saves exactly the old map plus the five nil keys; a `{:from :to :asset}` preset keeps its asset even before its balance loads), `set-funding-transfer-location`, `swap-funding-transfer-locations`, `select-funding-transfer-asset`, `set-funding-transfer-direction` (moved out of `modal_commands.cljs`), `set-funding-amount-to-max` and `submit-funding-transfer` (wrappers that delegate to `modal_commands` for the legacy route), `set-funding-transfer-amount-percent`, `submit-funding-transfer-gas-topup`, `reset-funding-transfer-evm`, `retry-funding-transfer-capability`, `add-funding-transfer-token-to-wallet`. Every route-changing command is refused while `:transfer-evm :phase` is `:running` or `:pending`; submit is refused while `:submitting?` or while any `:transfer-evm` exists.
  - **Gas top-up.** Covered by the effect-order policy (identical to `:actions/submit-funding-transfer`), in the Lean `policyCorpus`, and synced with `bb tools/formal.clj sync --surface effect-order-contract`; the Lean fallback was not needed. It emits `[:effects/save [:funding-ui :modal :transfer-gas-topup] {:status :submitting}]` then the submit effect with `{:purpose :gas-topup}`; a failed precondition saves `{:status :failed :error ..}` on the fix only. The review fixes made the submit effect branch on `:purpose` (see "Milestone 3 review fixes"), so the top-up no longer takes the legacy close-and-toast path.
  - **Add to wallet** emits `[:effects/wallet-watch-asset {:chain-id "0x3e7" :address :symbol :decimals}]` (`:address` nil for HYPE). Milestone 4 must register that effect with this argument shape; no view dispatches the action before Milestone 5.
  - **Wallet capability cache.** `hyperopen.hyperevm.domain.transfer-state` owns the paths: `[:hyperevm :wallet-capabilities <provider-key>] {:chain-switch :unsupported}` with `provider-key` = `[:wallet :selected-provider-id]` or `"default"`, plus `in-flight-entry`, `in-flight-tx-url` and `wallet-chain-id`. Milestone 4 writes both, never through a string-keyed `:effects/save` path (see "Milestone 3 review fixes").
  - **Submit effect guard.** Plan item 6 wired now: `api-submit-funding-transfer!` refuses a request that fails `check-request`, and refuses any action type other than `sendAsset`/`usdClassTransfer` ("Transfers from HyperEVM aren't available yet.") instead of signing it as a `usdClassTransfer`. Milestone 4 replaces that refusal with the `hyperEvmToCore` branch.
  - **View-model.** `modal_vm/transfer.cljs` (`with-transfer-context`, after amounts and before presentation) and `modal_vm/transfer_details.cljs` (summary rows, blocked card with fix, run progress). The exact-keys contract in `funding_modal_transfer_contracts.cljs` is the plan's shape plus four additive keys the design needs: `:amount :notice` (precision or gas-reserve note), `:asset :empty-message`, `:balances <side> :delta` ("−250.00"/"+250.00") and `:blocked :title`/`:explorer-url` (the gas card title, the in-flight explorer link). No spec aliases a parent spec. Injected VM deps: `:hyperevm-linked-tokens`, `:hyperevm-entry`, `:hyperevm-moves-blocked-message`, `:token-price-usd`, `:wallet-chain-id`; the plan's `:hyperevm-bridge` was not needed (the domain reads bridge state directly) and was left out.
  - **Unified accounts.** Perps is not a new choice for a unified account (reason "Unified accounts share one balance for Perps and Spot. Use Spot."), but a Perps route that is already selected (every legacy opener derives Spot -> Perps) stays enabled and submits exactly as before, because the legacy preview must stay byte-identical (`named_dex_transfer_preview_test.cljs` exercises unified accounts through it).
  - **Title rename landed here.** Presentation now titles the modal "Transfer", so the two Playwright cases that read that title through the funding-modal oracle (`portfolio-regressions.spec.mjs:3056`, `trade-regressions.spec.mjs:3274`) and `funding_modal_test.cljs:521` were updated in this milestone; the button-label renames stay in Milestone 5.
  - **Pricing.** `hyperopen.domain.token-pricing` holds the resolver; `views/account_equity/pricing.cljs` re-exports `normalized-token-name`, `balance-rows-by-token` and `token-price-usd` from it, and `market-token-price-usd [state token]` serves the funding view-model.
  - **Caps.** `effect_order_contract.cljs` 743 -> 750 and `modal_commands.cljs` 516 -> 505, each with an appended reason. Every new namespace is under 500 lines.
  Rationale: each choice keeps the legacy Perps <-> Spot path byte-identical, keeps the domain pure and deterministic, and closes a fund-safety gap before any HyperEVM view exists.
  Date/Author: 2026-09-30 / Claude.

- Decision (Milestone 3 review fixes): these replace the matching parts of "Milestone 3 as built". Milestones 4-8 code against them.
  - **HyperCore reads stay current.** `bridge/core-capacity-refresh-due? [state token now-ms]` is true when the system balance was never read, failed, or is `core-capacity-refresh-ms` (45 s, under the 60 s max age) old, and no read was sent in the last `core-capacity-retry-ms` (10 s). `fetch-core-bridge-balance!` stamps `:requested-at-ms` before it asks, and a failure keeps the stamp, so the retry gap applies. `transfer-commands/capacity-refresh-due? [state modal now-ms]` adds an unknown activation. Command deps gain `:now-ms` (bound to `platform/now-ms` in `modal_actions.cljs`).
    - `enter-funding-transfer-amount` (now routed through `transfer_commands.cljs`), MAX, percent and submit append the refresh effects when due. For submit they come after the covered effects and are `:other` to the effect-order policy.
    - MAX on an unknown max keeps the typed amount instead of writing "".
    - The HyperEVM balance poller takes `:capacity-refresh-index-fn` (`funding.actions/transfer-capacity-refresh-index`, wired in `app/bootstrap.cljs`) and on each visible, enabled tick dispatches `[:actions/refresh-hyperevm-bridge-capacity idx]` for the open draft's token, at most once per 10 s. An idle HYPE HyperEVM → Spot form therefore never reaches the 60 s limit, and a `:missing` activation is re-read with it.
    - A failed read shows "The bridge balance couldn't be read. Retrying…" instead of "Checking…".
  - **`:effects/save` paths are keywords only.** `retry-funding-transfer-capability` saves the whole `transfer-state/wallet-capabilities-path` map (`capabilities-without-chain-switch`). Milestone 4 must write `[:hyperevm :in-flight <owner>]` and the capability cache through store swaps in its effects, or save whole keyword-pathed maps, never a string- or address-keyed `:effects/save` path.
  - **Legacy direction toggle.** `set-funding-transfer-direction` clears the places, asset, amount, run and top-up only when a route was chosen (`:transfer-from`/`:transfer-to` set). On the legacy route it saves exactly the pre-feature `{:to-perp? :error nil}` update, so the typed amount survives, both on a click of the selected direction and on a real flip. The review suggested also clearing on a real flip. We kept the flip byte-identical to today's form instead, because Milestone 5 replaces that form and the legacy path's contract is "unchanged".
  - **Gas top-up owns its outcome now.** `api-submit-funding-transfer!` dispatches on `(:purpose request)`. A `:gas-topup` request never closes the modal or writes the draft's `:error`/`:submitting?`. Success writes `{:status :sent}` and toasts "Sent 0.05 HYPE to HyperEVM for gas.". Spectate, no wallet, an invariant refusal, a non-`sendAsset` type, an exchange error or a runtime error write `{:status :failed :error msg}` and toast it. Each write lands only while the modal is open and the fix still shows `:submitting`. Milestone 4 still adds the fast poll after a sent top-up.
  - **Invariant.** `check-request` now also requires, for a `sendAsset` to a system address: `:fromSubAccount ""`, `:destinationDex "spot"`, and at most `:core-precision` significant decimals. A request with `:route :core->evm` must target a system address. `hyperEvmToCore` requires `destinationDex` 4294967295 and the Core precision. Milestone 4's transaction builders should read these values from the checked action. `api-submit-funding-send!` runs the same check, so Send can no longer sign PURR to 0x2222… or HYPE to a token system address.
  - **Pooled accounts.** `location-options` gives Perps (From, when To is HyperEVM) and HyperEVM (To, when From is Perps) the reason "Perps and Spot share one USDC balance on this account. Move USDC to HyperEVM from Spot." whenever `availability/pooled-perps-collateral?`, so DEX-abstraction accounts see why, and the preview's `:invalid-route` message is the same text.
  - **View-model cost.** `with-transfer-context` builds the full submodel only in Transfer mode; other modes get `idle-transfer-vm`, the contract's shape with nothing computed. `token-price-usd` resolves a market only when no balance row prices the token, and returns 1 for USDC without a lookup.
  - **Known gap kept for Milestone 4.** `:actions/add-funding-transfer-token-to-wallet` emits `:effects/wallet-watch-asset`, which has no registration, handler or arg spec yet. Emission validation lets an unknown effect id through (`::common/any-args`), so a dispatch today reaches no handler. Milestone 4 must register it with a spec for `{:chain-id :address :symbol :decimals}`.
  Rationale: the review confirmed each gap. The capacity fix removes a dead end on the main HYPE flow. The keyword-path fix makes "Try again" work in dev and Playwright builds. The top-up fix keeps the fix retryable. The invariant and Send checks close fund-loss paths before anything is signed.
  Date/Author: 2026-09-30 / Claude.

- Decision (Milestone 4 as built): these are the real shapes and signatures that Milestones 5-8 code against.
  - **Where things live.**
    - `hyperopen.hyperevm.domain.txs` (domain): `native-to-core-tx`, `erc20-to-core-tx`, `usdc-approve-tx`, `usdc-core-deposit-tx`, `transfer-plan [from action]` (a `case` on `:kind` with no default; the USDC approve is `:optional?`), `priced-tx`, `with-fees`, `estimate-request`, `allowance-covers?`. Every tx carries `:chainId "0x3e7"`; `native-to-core-tx` refuses any recipient but `0x2222…`.
    - `hyperopen.hyperevm.domain.fees` gained `max-fee-per-gas-wei` (2 × max(gasPrice, 0.2 gwei)) and `gas-limit [estimate-text kind]` (estimate × 1.3 rounded up, else the fallback table).
    - `hyperopen.funding.domain.transfer-run` (pure): the run model at `[:funding-ui :modal :transfer-evm]` (`start-run`, `apply-step-event`, `succeeded`, `failed`, `pending`, `approval-only`, `arrived`, `slow`), the step lists and copy (`messages`), and `arrival-plan`/`arrived?`.
    - `hyperopen.funding.application.hyperevm-submit/submit-hyperevm-to-core! [deps owner action {:flow-id :on-step!}]`. `on-step!` is `(on-step! step-id event)` with events `:active :confirming :done :skipped :failed`. It resolves `{:status "ok"|"pending"|"err" …}` and never rejects.
    - `hyperopen.funding.application.hyperevm-run-state`: flow-id guarded modal writes (`update-run!`), refresh helpers, `cache-chain-switch-unsupported!`, `track-arrival!`.
    - `hyperopen.funding.application.hyperevm-transfer-effects`: `submit-core-to-evm!`, `submit-evm-to-core!`, `resolve-in-flight-receipt!`, `wallet-watch-asset!`.
    - `hyperopen.funding.effects.hyperevm-runtime`: `wallet-chain-config` (mainnet + `:verify-switch? true`), `submit-deps [store opts]`, `submit-hyperevm-to-core-tx!`, `read-allowance!`, `next-flow-id!`, `refresh-spot-clearinghouse!`, `get-transaction-receipt!`. `funding.effects/api-submit-funding-transfer!` gets them through `:or` defaults, and the transfer adapter now passes `platform/set-timeout!` and `platform/now-ms`.
  - **Flow order (HyperEVM → Core).** Gas price → (USDC) allowance → estimate the first transaction → switch with verification → send → receipt → (USDC) estimate the deposit → `eth_chainId` → send → receipt. All reads go through the public RPC. The verified switch's re-read is the chain check for the first send (see Surprises). An estimate that fails with a JSON-RPC error blocks with "HyperEVM would reject this transfer: …"; transport, rate-limit and timeout failures fall back to the gas table.
  - **In-flight entries** (deviation from plan step 7, see Surprises). `[:hyperevm :in-flight <owner>]` is written when the flow starts, before the wallet is asked for anything: `{:flow-id :status :running :hashes [] :waiting-receipt? false :started-at-ms :kind :asset :token-index :amount :arrival}`. Each hash is added (`:waiting-receipt? true`, `:submitted-at-ms`, `:step`) as soon as the wallet returns it. The entry is cleared on ok and on err (nothing is then unresolved), and becomes `{:status :pending :waiting-receipt? false}` on a receipt timeout. Helpers in `transfer-state`: `start-in-flight`, `record-in-flight-hash`, `merge-in-flight`, `clear-in-flight`, `pending-in-flight` (every change is flow-id guarded). A wallet that never answers its prompt therefore keeps the entry until a reload; we accept that fail-safe block.
  - **No plain failure after a hash.** The submitter tracks unresolved hashes. A receipt timeout, and any unexpected error while a hash is outstanding, end as `pending`; the effect also turns a submitter rejection into `pending` when the entry holds a hash. A wallet that returns no valid hash ends as an error ("Check your wallet activity before trying again"), since there is nothing to track.
  - **Background resolution.** `:actions/check-hyperevm-in-flight` (no args, uncovered) emits `[:effects/fetch-hyperevm-in-flight-receipt owner tx-hash]` for each `:pending` entry. The balance poller dispatches it at most every 8 s while one exists, the page is visible and the RPC is not backing off. On a receipt, the effect clears the entry, finishes the modal's `:pending` run if it still shows that flow (succeeded, reverted, or `approval-only` when the pending hash was the USDC approve), toasts, refreshes HyperEVM and the activation, and tracks arrival from the entry's stored `:arrival` plan.
  - **Effect outcomes.** Core → HyperEVM success keeps the modal open (`:succeeded`, `:arrival :arriving`), refreshes user data, Spot and HyperEVM (forced, fast poll 60 s), and tracks arrival on the HyperEVM entry. HyperEVM → Core success clears the entry, refreshes HyperEVM and the activation (`:actions/refresh-hyperevm-bridge-capacity`), and tracks arrival on Spot with Spot re-reads at 0, 2, 5, 10, 20 and 40 s. Arrival flips to `:arrived` when the destination grows by the amount (by amount − 1 for a first USDC move into an unactivated account); after 60 s it is `:slow`, and it still flips if the credit shows within 5 minutes. An unknown starting balance never reads as arrived. Failures mark the failing step `:failed`; `:chain-switch-unsupported` caches the provider's capability. A sent gas top-up starts the forced fast poll.
  - **Branching.** `api-submit-funding-transfer!` runs the gas top-up for `:purpose :gas-topup`; otherwise it is a `case` on the action type: `hyperEvmToCore` goes to the HyperEVM submitter, a `sendAsset` with `:route :core->evm` to `submit-core-to-evm!`, and everything else to the legacy path, whose unknown-type refusal now reads "This transfer type isn't supported.". Both EVM paths refuse up front on `hyperevm-moves-blocked-message`, an owner that changed since the preview, and the pre-signing invariant.
  - **`wallet_rpc`.** `wallet-add-chain-params` uses `:native-currency` (Ether default). Nested 4902 adds the chain. 4200, -32601 and "not supported" reject with `{:kind :chain-switch-unsupported}` and keep the wallet's message and `code`, so deposit error text is unchanged. `:verify-switch?` re-reads the chain after a switch. Also new: `request-chain-id!`, `send-transaction!` (nil `:data` is omitted; `:chainId`, `:gas` and the EIP-1559 fields pass through), `watch-asset!`, and an opts arity for `wait-for-transaction-receipt!`. `erc20_rpc` encoders delegate to `abi` and throw where `abi` returns nil, so a deposit can never send without calldata.
  - **New runtime ids** (none effect-order covered, so no Lean change): action `:actions/check-hyperevm-in-flight`; effects `:effects/fetch-hyperevm-in-flight-receipt [owner tx-hash]` and `:effects/wallet-watch-asset [{:chain-id "0x3e7" :address|nil :symbol :decimals}]` (exact keys). Both effect handlers sit under `:api` in `runtime/collaborators.cljs` and `app/effects.cljs`, pointing at `runtime/effect_adapters/hyperevm.cljs`.
  Rationale: each rule closes a double-send or wrong-chain path before Milestone 5 renders the run, and the in-flight entry written at flow start is stricter than the plan's hash-time write.
  Date/Author: 2026-09-30 / Claude.

- Decision (Milestone 4 review fixes): these replace the matching parts of "Milestone 4 as built". Milestones 5-8 code against them.
  - **Spot re-reads follow the shown account.** `transfer-run/spot-shows-owner? [state owner]` is true only while `account-context/effective-account-address` is the owner. `run-state/refresh-spot!` skips the read otherwise, and `hyperevm-runtime/refresh-spot-clearinghouse! [store owner opts]` (a 4-arity takes the info request fn) applies the reply only if that still holds when it lands. It no longer goes through `order.effects.spot-refresh`, whose wallet-address guard is left as it is for order mutations.
  - **Arrival.** Spot arrival is judged on the row's `:total` (holds included, `transfer-balances/spot-total-text`), so an order placed while the move arrives no longer keeps it "slow", and only while Spot shows the owner; otherwise `:before` is nil and nothing reads as arrived. The arrival watch serves the modal's run only: it ends as soon as the modal stops showing that run (closed, Done, Back to edit, a new draft), the delayed Spot re-reads with it; the first Spot re-read still happens. We did not re-snapshot `:before` when a pending move settles in the background: by then HyperCore may already show the credit, and a snapshot that includes it would leave the run "slow" for good, which is worse than the rare early "Arrived" the review describes.
  - **Nothing after an accepted move can fail it.** `submit-core-to-evm!` handles the `sendAsset` outcome with `.then(on-ok, on-error)`; the follow-ups (toast, user data, HyperEVM and Spot refreshes, arrival) each run through `run-state/follow-up!`, which logs an error through the injected `:log-fn` (`telemetry/log!` via `hyperevm-runtime/log!`) and swallows it. HyperEVM -> Core success and background settlement use the same helper. `transfer-run/failed` is also a no-op on a `:succeeded` run.
  - **Core -> HyperEVM runtime errors** (the POST threw, so the `sendAsset` may still have been applied) read user data and HyperEVM again at once (forced, 60 s fast poll), so a retry is checked against the bridge capacity as it is now. The message reads "Transfer failed: <reason>. Check your HyperEVM balance before trying again." An exchange error response still refreshes nothing: nothing moved. We did not also hold "Try again" until the refreshed capacity lands; the forced read answers in about a second, well before a user can retry.
  - **Failed steps.** `fail-steps` marks only the failing step; a read that fails before the wallet is asked (`:step nil`: gas price, allowance, no provider) marks none, and every other step still `:active` goes back to `:pending`, so "Switch wallet to HyperEVM" never shows failed or active in a failed run it did not cause.
  - **A send answered without a hash stays unresolved.** A `:no-hash` failure keeps the owner's in-flight entry as `{:status :unconfirmed :waiting-receipt? false}` (nothing to poll, `pending-in-flight` skips it), so no further HyperEVM -> Core move starts until a reload; the run ends `:failed` with "The wallet didn't return a transaction hash, so the transfer may still have been sent. Check your wallet activity; reload the page before starting another." The preview, the submit command and the effect all block with `transfer-state/in-flight-blocked-message`, which gives `unconfirmed-message` for this entry. Milestone 5 renders this failed run without "Try again" if it wants to; the block holds either way.
  - **Estimates.** `priced!` blocks only when the estimate says the transaction would fail: JSON-RPC code 3, or a message containing "revert" or "insufficient funds". Every other RPC error (-32603 internal error, -32000 header not found or timed out), rate limits and transport failures fall back to the gas table.
  - **A switch that lands elsewhere.** `wallet-rpc/ensure-wallet-chain!` with `:verify-switch?` now rejects `{:kind :chain-switch-unsupported}` only when the re-read chain is the one before the switch (or unreadable), and `{:kind :chain-changed :chain-id}` when the wallet is on some third chain. The submitter maps the latter to "Your wallet moved to another network instead of HyperEVM (chain 999), so nothing was sent…" and nothing is cached against the wallet.
  - **Background receipt reads slow down.** `transfer-state/in-flight-check-interval-ms [state now-ms base-ms]` gives the poller's own 8 s while the youngest pending move's transaction is at most 5 minutes old, 30 s up to 30 minutes, then 120 s. A replaced (wallet "speed up"/"cancel") or dropped transaction never gets a receipt, so its entry still blocks HyperEVM -> Core moves until a reload. Detecting replacement needs the transaction's nonce, which the wallet assigns and which the public RPC (latest block only) may not report for a pending transaction; a sound check is left for later (see Outcomes).
  - **Gas top-up ids.** The submit effect stamps the fix the action just marked `{:status :submitting}` with an id (`hyperevm-run-state/next-flow-id`) and writes an outcome only while the fix still carries that id, so a top-up started before a close and reopen never lands on the next one's fix. The stored map is `{:status :id}` or `{:status :failed :error :id}`; the view-model reads `:status` and `:error` only.
  - **Background approve revert.** A pending USDC approve that reverts fails the approve step with "The USDC approval reverted on HyperEVM." and toasts "Your USDC approval for <amount> USDC reverted on HyperEVM, so nothing was sent."
  - **Docs.** The in-flight entry's `:kind` is the action's kind string ("native", "erc20", "usdcCoreDeposit"), as the `transfer-state` docstring now says; both Milestone 4 runtime ids are in "Interfaces and Dependencies".
  - **Tests.** New namespaces `hyperevm_submit_failures_test.cljs` (gas price, allowance and pre-deposit chain reads failing, a deposit estimate reverting after the approve, an approve receipt timeout, node errors on the estimate, a send answered without a hash, a switch that landed elsewhere) and `hyperevm_transfer_outcomes_test.cljs` (Spot re-reads while spectating or on a subaccount, the arrival watch ending with its run and at its deadline, holds, follow-up errors, runtime errors), plus shared harnesses in `test_support/hyperevm_effects.cljs` and `test_support/hyperevm_wallet.cljs` (`submit!`, `request`).
  Rationale: the review confirmed each gap. The Spot guard stops the owner's balances from showing as another account's; the rest keep a move that went through (or may have) from ever returning to a form that would send it again, and keep failures legible.
  Date/Author: 2026-09-30 / Claude.

- Decision (Milestone 5 as built): these are the real view shapes that Milestones 6-8 code and test against.
  - **Where things live.** `/hyperopen/src/hyperopen/views/funding_modal/transfer.cljs` (the form, `render-content`), `transfer_evm.cljs` (`progress-content`, `pending-content`, `failed-content`, `success-content`, `retry-action`, `close-note`) and `transfer_parts.cljs` (icons, buttons, balance cards, the summary `dl`, the static route header, the sticky action footer, `focus-when-shown`). All three sit in the lazy `funding_modal` module and hold no raw colors.
  - **Location chip created here, not in Milestone 6.** `/hyperopen/src/hyperopen/views/ui/location_chip.cljs` `(location-chip :perps|:spot|:hyperevm)` renders "Perps"/"Spot"/"EVM" (uppercase by class) with the plan's token tones and `data-role "location-chip-<location>"`, nil for anything else. The modal needed it first; Milestone 6 reuses it for the Balances rows.
  - **Form layout.** From and To are side by side at `sm` and up and stacked below it (three segmented buttons do not fit two 147 px columns on a 375 px sheet). Under each group: the place chip and that side's balance. Disabled places keep focus (`aria-disabled`, no click handler) and point `aria-describedby` at one visible reason line per distinct reason, under both groups. The asset list is a `fieldset` of native radios (`name "funding-transfer-asset"`), `max-h-32` with its own scroll, hidden by class on Perps <-> Spot. While the preview is blocked, the before/after cards and the summary are hidden by class so the blocked card and its fix stay near the top, as in the TransferNeedsGas artboard. The form has no Cancel button (the dialog's close button and Escape remain), and the submit sits in a sticky footer (`data-role "funding-transfer-actions"`) that stays in view while the panel scrolls.
  - **Data-roles** (for Milestone 8): `funding-transfer-{from,to}-group`, `funding-transfer-{from,to}-<place>`, `funding-transfer-{from,to}-balance`, `funding-transfer-place-reasons`, `funding-transfer-swap`, `funding-transfer-asset-list`, `funding-transfer-asset-option-<index>`, `funding-transfer-asset-empty`, `funding-transfer-amount-input` (input id `funding-transfer-amount-input-field`, unchanged), `funding-transfer-amount-symbol`, `funding-transfer-max`, `funding-transfer-percent-<n>`, `funding-transfer-usd-estimate`, `funding-transfer-available`, `funding-transfer-amount-notice`, `funding-transfer-blocked-region` (always present, `role status`, `aria-live polite`), `funding-transfer-blocked` (`data-blocked-code`), `funding-transfer-gas-fix` or `funding-transfer-capability-retry` (`data-fix-status`), `funding-transfer-fix-reason`, `funding-transfer-fix-status`, `funding-transfer-verification-notice`, `funding-transfer-token-explorer`, `funding-transfer-details`, `funding-transfer-balances`, `funding-transfer-balance-{from,to}`, `funding-transfer-summary`, `funding-transfer-submit`; run views `funding-transfer-{progress,pending,failed,success}`, `funding-transfer-<view>-heading`, `funding-transfer-route`, `funding-transfer-steps`, `funding-transfer-step-<id>` (`data-step-status`), `funding-transfer-close-note`, `funding-transfer-error`, `funding-transfer-back-to-edit`, `funding-transfer-try-again`, `funding-transfer-explorer-link`, `funding-transfer-arrival`, `funding-transfer-result-{from,to}`, `funding-transfer-add-to-wallet-box`, `funding-transfer-add-to-wallet`, `funding-transfer-view-history`, `funding-transfer-done`.
  - **Run views.** Progress and pending offer no way back to the form: progress has only the disabled "Waiting for wallet (step n of m)" button and "If you close this, the transfer keeps going in your wallet."; pending only closes. Each run view's heading has `tabindex -1` and `focus-when-shown`, which focuses it (after a 0 ms timeout, so after the dialog's own microtask focus) whenever it starts showing a different view. The step list is a polite live region and each step speaks its state ("— In progress"). Success reads "Sent 100 PURR to HyperEVM" while arriving, "100 PURR is on HyperEVM" plus "Arrived in 3 seconds at your wallet 0x…" once arrived, and "Taking longer than usual — check Deposits & Transfers." when slow; its cards show each side's current balance. "View in Deposits & Transfers" dispatches `[[:actions/close-funding-modal] [:actions/set-portfolio-account-info-tab :deposits-withdrawals] [:actions/navigate "/portfolio"]]` (the portfolio's Account Activity tab). Add to wallet is a hidden slot unless the destination is HyperEVM.
  - **"Try again" resubmits (deviation from Milestone 3).** `transfer-commands/submit-funding-transfer` now accepts a `:failed` run: when the draft still submits, it first emits `[:effects/save [:funding-ui :modal :transfer-evm] nil]` so the submit effect can start a new run (its `start-run!` only starts on a modal with no run). Running, pending and succeeded runs still refuse. A failed run never leaves a transaction unresolved (a hashless send keeps its `:unconfirmed` in-flight entry, which still blocks). The failed view picks the action with `retry-action`: the submit when nothing blocks the draft, `:actions/retry-funding-transfer-capability` when the block is `:no-chain-switch`, and no "Try again" otherwise (an in-flight or unconfirmed entry, missing gas, …), where "Back to edit" shows why. A single retry action was not added because it would have needed its own effect-order policy and Lean corpus line; see Surprises for why two chained actions cannot do it.
  - **Modal shell.** `render-content` routes `:transfer/progress|pending|failed|success`. Every desktop panel gets `max-h-[calc(100vh-24px)] overflow-y-auto`, and an anchored popover's style adds `max-height: calc(100vh - <top> - 12px)`. `funding-modal-positioning` places a Transfer popover as if it were 700 px tall (other modes keep 560), so one opened from a low row sits high enough for its fix and submit to show at 1280x800 (probe: fix 602-642 px, sticky footer from 713 px). The `:transfer` focus-restore selector adds `portfolio-funds-connector-perps-spot`, `portfolio-funds-connector-spot-evm`, `account-equity-hyperevm-move` and the prefix form `[data-role^="balances-move-"]`.
  - **Modal layer above the footer (deviation, shell-wide).** Both funding modal layers moved from `z-[80]`/`z-[81]` to `z-[262]`/`z-[263]`: above the fixed footer (170) and the trade panes that paint up to 260, below toasts (280). Without it the mobile nav covered the sheet's submit (see Surprises). Deposit, Withdraw and Send move with it; their Playwright cases pass.
  - **Renames.** `funding_actions.cljs` and the Portfolio header (label and mobile label) read "Transfer"; data-roles are unchanged. `account_equity_view_test.cljs` and `header_test.cljs` pin the new label.
  Rationale: the views stay dumb over the Milestone 3 view-model, every conditional part is an always-present slot, and a move that may be on chain never returns to a submittable form. The two deviations close real failures: a "Try again" that could not submit, and a mobile sheet whose submit sat under the app's nav.
  Date/Author: 2026-09-30 / Claude.

- Decision (Milestone 5 review fixes): these replace the matching parts of "Milestone 5 as built". Milestones 6-8 code against them.
  - **"Try again" only when nothing may have moved (corrects "Try again resubmits").** The earlier claim that a failed run never leaves a transaction unresolved was wrong for HyperCore -> HyperEVM: when the `sendAsset` POST throws, the exchange may still have applied it. `transfer-run/failed` now takes `{:maybe-sent? bool}` and every run carries `:maybe-sent?` (false from `start-run`). `core-to-evm-errored!` and the hashless HyperEVM -> Core send (`:no-hash`) set it. The view-model computes `[:transfer :evm :retry-action]` (`transfer-details/retry-action`): nil unless the run failed; nil when `:maybe-sent?`; `[:actions/retry-funding-transfer-capability]` for a `:no-chain-switch` block; nil for any other block; `[:actions/submit-funding-transfer]` only when the draft's preview is ok. The failed view reads it (the view-level `retry-action` is gone), and `submit-funding-transfer` also refuses a `:maybe-sent?` failed run, so the only way on is "Back to edit", whose form shows the balances the errored path already re-read.
  - **The Transfer form owns its message.** Presentation never shows the shell's `funding-status` line in Transfer mode; it computes `:transfer-message` (the last submit error, else the preview's message when nothing blocks), which the transfer VM carries as `:message` (nil while a run shows). The form renders it inside the sticky footer, right above the submit (`funding-transfer-message` in an always-present polite live region `funding-transfer-message-region`), and the amount input (`aria-invalid`) and submit point `aria-describedby` at it. A success view therefore never shows the draft checked against the balances the move itself changed.
  - **Re-choosing the pressed place is a no-op.** `set-funding-transfer-location` returns `[]` when `with-location` gives the current `{:from :to}`, so a click on the pressed option keeps the typed amount and a submitting or sent gas top-up (acceptance item 8).
  - **An unknown source never reads as empty.** `transfer-balances/location-known? [state location owner]` (Perps once a clearinghouse state exists, Spot once its rows exist, HyperEVM once a read landed). The asset list's empty copy needs a known source, and with no asset chosen the preview's `blocked-reason` now surfaces the source's own block (`source-block`: "Checking HyperEVM balances…", "HyperEVM balances are unavailable right now.", "Checking your Spot balances…", "Checking your Perps balance…"). The review suggested also putting the checking copy in the asset list; we show it once, in the blocked line, to avoid saying it twice. The Perps <-> Spot route's balances read nil (shown "--" / blank) until their place is known, instead of "0.00 USDC".
  - **Checking blocks stay quiet.** The blocked VM gains `:checking?` (true for `:hyperevm-unavailable` and `:meta-missing`). Such a block renders as a small secondary line (`data-checking "true"`), and the before/after cards and summary stay visible, so the form no longer jumps while a read lands.
  - **Live regions carry text only.** The blocked card's outer slots are always present; the live region is `funding-transfer-blocked-status` (title and message only), while the explorer link and the fix sit outside it; the fix's status line (`funding-transfer-fix-status`) is its own polite region. The step list is no longer live; the progress view has an sr-only polite line (`funding-transfer-step-announcement`, "Step 2 of 3: Approve 1,000 USDC. Confirming on HyperEVM…") with the active step alone.
  - **Focus.** The verification notice renders its explorer link only with the notice, the hidden "Add to wallet" button is disabled, and `dialog-focus/visible-node?` also asks `checkVisibility()` (where the browser has it), so no control under a hidden ancestor counts for the Tab trap. Every Transfer view root carries `transfer-parts/view-root-hook`; when the form replaces a run view ("Back to edit", or the network "Try again") it focuses the amount input after a 0 ms timeout, after the dialog's own microtask focus.
  - **Sticky footer and scroll padding.** Panels publish their bottom padding as `--funding-panel-pad-bottom` (the mobile sheet sets it to its safe-area expression; desktop falls back to 1rem), and the footer's `bottom`, `margin-bottom` and `padding-bottom` are inline styles over that variable. Transfer panels also get `scroll-pb-28`, so a control reached with Tab scrolls clear of the footer.
  - **Copy.** "View in Deposits & Transfers" is now "View in Account Activity" (the Portfolio tab's real name), the slow line reads "check Account Activity", and the button also selects the Account Transfers sub-tab (`[:actions/set-portfolio-account-activity-sub-tab :account-transfers]`), where the ledger lists both HyperEVM legs. Step labels and the submit label group their amounts like the heading ("Approve 1,000 USDC", "Move 1,000 PURR to HyperEVM", `transfer-balances/grouped-amount`). While the active step is confirming on chain (`transfer-run/confirming-detail`), the submit label reads "Confirming on HyperEVM (step n of m)" instead of "Waiting for wallet".
  - **Arrival time.** `transfer-run/succeeded` takes `now-ms` and stamps `[:result :sent-at-ms]`; "Arrived in N seconds" counts from it (falling back to `:started-at-ms`), so it measures the bridge, not the time spent signing.
  - **Contract.** The transfer VM gained `:message`, the blocked card `:checking?`, and the run `:maybe-sent?` and `:retry-action` (all exact-keys in `funding_modal_transfer_contracts.cljs`).
  - **Browser check.** A headless Chromium probe of this worktree's `compile app` build (the Milestone 5 QA harness, state rendered with `replicant.dom/render`) confirmed at 1280x800 (popover from a low anchor) and 375x812 (sheet): an over-max draft's message is inside the scrolling panel's view and above the submit, and no `funding-status` renders; with a verified token and the submit disabled, no link sits under a hidden ancestor and Tab from the last rendered control stays in the dialog; replacing a failed run with the form focuses `funding-transfer-amount-input-field` (Replicant did reuse the root node); the stuck footer's bottom meets the sheet's inner edge (gap 0), the sheet publishes the padding variable, and its scroll padding is 112 px.
  - **Tests.** New `/hyperopen/test/hyperopen/views/funding_modal/transfer_form_states_test.cljs` (a submit in flight locks every control; the `:no-chain-switch` card and its retry; the in-flight card's link; an unmovable asset's reason and `aria-describedby`; the form message; no stale error under success; checking states; no focusable control in a hidden slot across nine states; the footer's padding variable) and `/hyperopen/test/hyperopen/views/ui/location_chip_test.cljs`, plus additions to the command, run, effect, VM, contract, run-view and dialog-focus tests. A mutation check that reverted four of the fixes (the notice link, the shell feedback guard, the pressed-place no-op, the `:maybe-sent?` retry guard) failed 15 assertions.
  Rationale: each change closes a finding the Milestone 5 review confirmed; the two most serious were a one-click resend of a move that may have gone through and a validation message hidden under the sticky footer.
  Date/Author: 2026-09-30 / Claude.

- Decision (Milestone 6 as built): these are the real shapes Milestones 7-8 code and test against.
  - **Where things live.**
    - `/hyperopen/src/hyperopen/views/account_info/projections/balances_hyperevm.cljs`: `hyperevm-rows [state core-rows]`, `hyperevm-status [state]` (`:ready|:loading|:unavailable`; `:ready` with no account shown, since nothing will be read), `hyperevm-row-count [state]`, `row-location [row]` (`:hyperevm`, `:perps` for `perps-usdc*` keys, else `:spot`), `filter-rows-by-location`, `normalize-location-filter`, `hyperevm-row?`.
    - `/hyperopen/src/hyperopen/views/account_info/projections/balances_moves.cljs`: `with-move-targets [rows state]`.
    - `/hyperopen/src/hyperopen/hyperevm/panel_slice.cljs`: `balances-panel-slice [state]`, merged into `trade_view.cljs` `account-info-view-state`. It is a non-view namespace so the main bundle's trade view does not pull the account-info projections out of the lazy `account_surfaces` module.
    - Views: `/hyperopen/src/hyperopen/views/account_info/tabs/balances/moves.cljs` (desktop Transfer cell, mobile footer nodes) and `location_filter.cljs` (the filter control, `chip-location`, the HyperEVM empty-state copy and the note). The chip is the Milestone 5 `/hyperopen/src/hyperopen/views/ui/location_chip.cljs`.
  - **Rows.** A HyperEVM row is `{:key "hyperevm-<idx>" :location :hyperevm :selection-coin :coin :token idx :total-balance :available-balance :total-text :available-text :usdc-value :pnl-value nil :pnl-pct nil :amount-decimals <core precision> :contract-id nil :evm-native? :evm-contract :evm-contract-url :market-coin? :gas-reserve-text?}`. Native HYPE comes first, then by token index; only indexes the read returned a balance for are looked at. Native HYPE's available amount holds back `fees/native-max-reserve-hype` (the Transfer modal's MAX reserve) and names it ("0.001 kept for gas"). USD values come from `hyperopen.domain.token-pricing` with the HyperCore rows as the price rows; an unpriced HyperEVM token shows "--", not $0.00.
  - **"Unknown or in error" means no read yet (interpretation).** Rows exist once the shown account has any successful read. A later refresh that is loading or failed keeps the last values (stale-while-revalidate, as the Milestone 2 contract and the Transfer preview already do); only a first read that is loading or failed gives no rows and `:loading`/`:unavailable`. Emptying the table on every rate-limited poll would make rows flicker.
  - **Move targets.** Every row of the Balances tab carries `:move-targets`, each `{:row-key :from :to :label :aria-label :legacy? :context :disabled? :reason}`:
    - A legacy target exists exactly where the old Transfer button was enabled (a HyperCore USDC row that is not a unified account's pooled row). It reads "To Perps" or "To Spot" and dispatches the row's unchanged `balance-row-transfer-action` (opener nil).
    - HyperEVM targets: Spot rows of linked tokens with a positive total get "To HyperEVM"; the default-dex Perps USDC row gets "To HyperEVM" unless Perps and Spot are pooled (`availability/pooled-perps-collateral?`); HyperEVM rows get "To Spot". Each opens `[:actions/open-funding-transfer-modal :event.currentTarget/bounds <data-role> {:from :to :asset idx}]`.
    - A token with bad bridge health gets no HyperEVM target; one whose health is still unknown gets a disabled target ("Checking this token's HyperEVM link…"), so the column does not jump when the first poll lands. The disabled reason, in order: `hyperevm-moves-blocked-message`, unknown health, an empty HyperEVM bridge side (Core -> EVM capacity "0", the preview's own copy).
  - **Data-roles.** Desktop `balances-move-<row-key>-<to>`; mobile `balances-move-mobile-<row-key>-<to>` (deviation: the plan named one role per target, but the desktop row and an expanded mobile card render at once, so each gets its own; both match the Milestone 5 focus-restore prefix `balances-move-`). Move containers are `balances-moves-<key>` and `balances-moves-mobile-<key>`. A disabled target is a focusable `aria-disabled` button with the reason as `title` and in an sr-only `<data-role>-reason` node it points at with `aria-describedby`. Also `balance-row-gas-reserve-note`, `balance-row-evm-contract`, `balances-location-filter` (+ `-all|-hypercore|-hyperevm`, `aria-pressed`), `balances-hyperevm-note(-mobile)`, `balances-hyperevm-moves-blocked(-mobile)`.
  - **Chips.** A HyperEVM row always shows the EVM chip. HyperCore rows show PERPS or SPOT only while the filter is All and the account has HyperEVM rows, except a unified account's pooled USDC row. On phones the chip wraps under the coin name.
  - **Filter and header.** `:actions/set-balances-location-filter [:all|:hypercore|:hyperevm]` lives in `hyperopen.hyperevm.actions`, registered in the HyperEVM registration file with its arg spec in `hyperevm_action_args.cljs` and its handler under `:hyperevm` (uncovered, session state like Hide Small Balances, not persisted). The control sits between the search and Hide Small Balances; the actions shell wraps below `md` and stays one row from `md` up. Filter order is location, then Hide Small Balances, then search, then sort. The tab badge counts HyperEVM rows whatever the filter, as it ignores Hide Small Balances. Sorting puts a HyperCore row before a HyperEVM row of the same coin on ties.
  - **Read-only.** Spectate and trader routes keep today's table without the Send/Transfer/Repay columns, so there are no move controls to disable; the note under the table shows the read-only reason (deviation from "every move control disabled with a visible reason": nothing is rendered to disable). A selected subaccount keeps its Perps <-> Spot moves and sees every HyperEVM target disabled with the master-only reason.
  - **Note and empty states.** "HyperEVM balances are read on chain 999." (plus "Only tokens linked between HyperCore and HyperEVM are shown and can be moved." from 640 px) shows under the table (above the cards below 1024 px) while the account has HyperEVM rows or the filter is HyperEVM, with the move-block reason when there is one. Filtered to HyperEVM with nothing to show: "Checking HyperEVM balances…", "HyperEVM balances are unavailable right now." or "No HyperEVM balances.".
  - **Slice.** Deviation from the plan's `select-keys [:native-wei :token-units :status]`: the domain readers also need the read's coverage, so the slice keeps `:token-indexes` and `:unread-token-indexes` (as sets), collapses `:status` to `:ready` once read, drops an entry still loading its first read, keeps the gas price only when it lifts the native reserve above its floor, keeps bridge health, and keeps only the HyperEVM system balances whose Core -> EVM capacity is zero (memoized on identity). `/hyperopen/test/hyperopen/views/account_info/vm_hyperevm_test.cljs` checks the view-model is identical on the full state and the slice in five states.
  - **Layout.** The Transfer track widened from `minmax(104px,0.56fr)` to `minmax(148px,0.8fr)` so "To Perps  To HyperEVM" fits on one line at 1280; the Coin track's minimum is `--balances-coin-min`, 84 px, set to 116 px while place chips show (see Surprises).
  - **Deviations from the artboards.** No "Where" column (the earlier Decision Log entry keeps 9 columns; the chip sits in the Coin cell). A HyperEVM USDC row offers only "To Spot" (the plan: HyperEVM -> Perps is two steps). Mobile move actions sit in the expanded card's footer, beside Send, not on the collapsed card as in MobileSheet: the collapsed card is itself the expand button and cannot hold buttons. Send keeps its existing white styling; the move actions use the artboard's accent color.
  - **Legacy rendering kept.** A row without `:move-targets` (a caller other than the Balances-tab view-model, and the pre-existing row tests) still renders the single "Transfer"/"Unified" control.
  Rationale: the rows and targets reuse the Transfer modal's own readers and copy, so the table never offers a move the modal would refuse for a reason the table could know; the slice keeps the trade panel from repainting on every poll.
  Date/Author: 2026-09-30 / Claude.

- Decision (Milestone 6 review fixes): these replace the matching parts of "Milestone 6 as built". Milestones 7-8 code against them.
  - **Layout (replaces "Layout").** The Transfer track is back to a 104 px minimum, `minmax(104px,0.8fr)`, and a row's moves wrap onto a second line where the column is that narrow, as the Main artboard's `flex-wrap` does. The 148 px minimum had pushed the second move past the 960 px `/trade` panel at 1280 px (850-998 px), and the rows' sideways scroll does not move the header. The Coin track keeps its 116 px chip-time minimum. `/hyperopen/test/hyperopen/views/account_info/tabs/balances/balances_geometry_test.cljs` pins the budget: every track up to Transfer, at its minimum with chips, plus gaps and padding, ends within 960 px.
  - **Disabled reasons are visible text (replaces the `title` + sr-only node).** On desktop a disabled move's reason is a tooltip (`role "tooltip"`, `data-role`/`id` `<data-role>-reason`, the `aria-describedby` target) that shows on hover and while the action has keyboard focus (`group-hover`/`group-focus-within`), anchored at the action's right edge and opening leftward so it never widens the rows viewport; it opens downward on the first two rows (`:move-reason-position`) and upward below them. The native `title` is gone (it doubled the tooltip). On a mobile card the reason is a visible `text-xs` line under the card's actions (`balances-moves-mobile-reasons-<key>`, always present, hidden by class when nothing is disabled), and it is the line the action's `aria-describedby` names.
  - **The note shows whenever it has something to say (extends "Note and empty states").** Besides HyperEVM rows or the HyperEVM filter, the note shows (a) whenever an account-level block (subaccount, no wallet) disables a rendered HyperEVM move, so a subaccount with nothing on HyperEVM sees the master-only reason, and (b) under All while the shown account's first HyperEVM read is pending or failed, with "Checking HyperEVM balances…" or "HyperEVM balances are unavailable right now." (`balances-hyperevm-status`, `-mobile`). Its body text is `text-ho-text-muted` (5.4:1 or better in every theme; `text-ho-text-dim` was 2.8:1 in the institutional theme).
  - **Unknown health while the first read fails.** A target whose token health is unknown says "HyperEVM balances are unavailable right now." instead of "Checking this token's HyperEVM link…" while `hyperevm-status` is `:unavailable` (health rides the same read, so nothing is being checked).
  - **Hide Small Balances.** An unpriced HyperEVM row (`:usdc-value nil`) is unknown, not small, and stays. Filtered to HyperEVM, the empty state names its cause from the final rows: the read's status, "All HyperEVM balances are hidden by Hide Small Balances.", or "No HyperEVM balances match your search.".
  - **HyperEVM rows show no HyperCore actions.** Their Send and Repay cells are empty on desktop (was a muted "Send"/"Repay"), the mobile card's Send slot is hidden by class, and a HyperEVM row with no move (a broken bridge link) has an empty Transfer cell.
  - **Amount precision (replaces `:amount-decimals <core precision>`).** HyperEVM rows format at the token's `weiDecimals`, the same rule as its HyperCore Spot row, so the HyperEVM USDC row reads like the Spot USDC row (8 decimals) rather than at its 6 on-chain decimals. For every token whose HyperEVM decimals are at least its `weiDecimals` this is unchanged. The Transfer modal keeps its route precision.
  - **Accessible names.** A legacy move on a named-DEX Perps row names its DEX ("Move xyz USDC from Perps to Spot").
  - **Filter semantics.** `aria-pressed` is the string "true"/"false" (see Surprises).
  - **Coin sort.** The Coin sort groups every USDC row as "USDC", then lists HyperCore before HyperEVM (whatever the direction), then the HyperCore labels in the sort's direction as before; other columns keep their tie-breaks.
  - **PERPS chip tone (plan deviation).** `text-ho-text/80` on `bg-ho-surface-raised` (9.0:1 default theme) instead of the plan's `text-trading-text-secondary` (4.38:1, below AA for 12 px text). The chip is shared with the Transfer modal.
  - **Slice contract.** `hyperopen.hyperevm.panel-slice` now documents which readers agree on the slice and which do not (`evm-gas-status`, positive capacities, `evm->core-capacity`, in-flight and activation). Milestone 7's Trade HyperEVM line and Portfolio strip must derive from the full state or their own projection.
  - **Read-only contract (plan amended).** Acceptance 10 and Milestone 8 (g) now describe the as-built spectate behavior: HyperEVM rows visible, no move column, and the read-only reason in the note wherever the note shows.
  - **Tests.** New `/hyperopen/test/hyperopen/views/account_info/tabs/balances/hyperevm_reasons_test.cljs` (a subaccount with no HyperEVM rows sees the master-only reason as visible text; the pending/failed status line under All and not under HyperCore; a mobile card's reason line is its action's `aria-describedby` target; HyperEVM rows have no Send/Repay; Hide Small Balances keeps unpriced HyperEVM rows; the HyperEVM filter's hidden-by-filter and no-match messages) and `balances_geometry_test.cljs`, plus the tooltip, `aria-pressed`, USDC Coin sort, unavailable-reason and named-DEX label cases in the existing tests.
  Rationale: each change closes a finding the review confirmed. The two majors were a move clipped by the `/trade` panel at a required QA width and disabled reasons that a touch or keyboard user could not see.
  Date/Author: 2026-09-30 / Claude.

- Decision (Milestone 7 as built): these are the real shapes Milestone 8 codes and tests against.
  - **One HyperEVM sum for both surfaces.** `/hyperopen/src/hyperopen/views/account_info/projections/hyperevm_funds.cljs` `hyperevm-funds [state]` -> `{:status :ready|:loading|:unavailable|:none :address :usd :token-count :unpriced-count :symbols :native-hype-text :gas-status}` sums the Balances tab's own HyperEVM rows (`balances-hyperevm/hyperevm-rows`, priced against the shared HyperCore row memo, `core-balance-rows`), so the table, the strip and the line agree on every token's value. It reads the full state (never the lossy `/trade` Balances slice, which drops the gas price the gas status needs) and is memoized on the identity of the shown address's entry, spotMeta, the market catalogue and the HyperCore rows, because `/trade` asks on every render. A held token with no price counts in `:token-count` and adds nothing to `:usd` (as an unpriced HyperCore token adds nothing to spot equity); `:usd` is nil unless `:ready`.
  - **Portfolio strip.** `/hyperopen/src/hyperopen/views/portfolio/funds_locations.cljs`: `funds-locations-model [state summary]` (summary = the portfolio VM's `:summary`, so `views/portfolio/vm.cljs` is untouched) and `funds-locations-strip [model]`, inserted in `portfolio_view.cljs` after the background-status region and before the summary grid. With no account shown the section is an empty node hidden by class. Total value = `:total-equity` + HyperEVM USD; while HyperEVM is `:loading` or `:unavailable` the total reads "—" with "HyperEVM balances loading" / "HyperEVM balances unavailable", and the HyperEVM card "Loading…" / "—". The Total Equity sub-line uses the artboard's copy, "Trading equity $X" (the summary's Total Equity, HyperCore only; it includes vault and Earn balances like the summary card does). Cards: PERPS "Trading margin" (Perps account equity, "USDC collateral · N positions"), SPOT "HyperCore" (Spot account equity, "N tokens · A, B, C +K" by USD value), EVM "HyperEVM wallet" + "chain 999" (count, ", N unpriced" when a held token has no price, and gas: "gas: 12.50 HYPE" with a buy-tone dot, "Low gas: …" or "No HYPE for gas" in warn tone, no warning for an empty wallet). A unified account gets one PERPS+SPOT "Trading account" card (Spot account equity, "Perps and Spot share one balance") and only the Spot <-> HyperEVM connector.
  - **Connectors.** `portfolio-funds-connector-perps-spot` opens `{:from :perps :to :spot}` and `portfolio-funds-connector-spot-evm` opens `{:from :spot :to :hyperevm}` (the modal picks the first eligible asset by value), each with `focus-return/data-role-return-focus-props`. Perps <-> Spot is disabled only by `mutations-blocked-message` (read-only views; a subaccount keeps it, as the header Transfer does); Spot <-> HyperEVM by `hyperevm-moves-blocked-message`. A disabled connector keeps focus (`aria-disabled`) and points `aria-describedby` at one visible line per distinct reason under the strip (`portfolio-funds-reason-<n>`, inside `portfolio-funds-reasons`, `lg:hidden`); from `lg` up a tooltip (`<data-role>-tooltip`, `aria-hidden`, named group `group/connector`, shown on hover and keyboard focus) repeats it, and the hidden line is still the description.
  - **Layout.** Below `md` the cards stack and each connector slot has zero height, so the round button sits on the seam between the two cards it joins (icon turned vertical); at `md` the total spans the first row and the three cards share the second (`md:grid-cols-[minmax(0,1fr)_44px_…]`); from `lg` it is the artboard's single row (`240px` total at `lg`, `300px` at `xl`). "chain 999" is dropped between `md` and `lg`, where it wrapped. Values use `text-2xl`/`text-xl` (the artboard's 28/22 px are not on the scale).
  - **Trade line.** `/hyperopen/src/hyperopen/views/account_equity/hyperevm_line.cljs`: `hyperevm-line-model [state]` -> `{:visible? :usd :move-action :blocked-reason :focus-token}` and `hyperevm-line [model]`. The model is a constant hidden map unless the shown account is `:ready` with `:usd` > 0; `:move-action` is nil on read-only views (no link rendered) and `:blocked-reason` disables it otherwise; `:focus-token` is set only while the Move link opened the modal, so no other opener's focus return changes the model. Deviation: `/trade`'s main bundle cannot require the account-info projections (lazy `account_surfaces` module), so the model is a new account-surfaces export (`:hyperevm-line-model` in `hyperopen.surface-modules`, `account_surfaces_module/hyperevm_line_model`) that `trade-view-panel-context` calls on the full state, the way it already gets `:account-equity-metrics`, frozen with the other panels while the asset selector scrolls. `shell/render-order-entry-panel-shell` passes `{:hyperevm-line model}` instead of `{}` and `mobile-account-surface` takes it as a third argument; `panels/account-equity-view` reads `:hyperevm-line` from opts and renders the always-present slot as the last row of the classic Spot/Perps group and after the unified metrics (outside Account Value). The disabled Move link's reason is a visible `text-xs` line under the row (`account-equity-hyperevm-move-reason`, its `aria-describedby` target), not a tooltip, because the same panel renders on phones.
  - **Data-roles** (for Milestone 8): `portfolio-funds-strip`, `portfolio-funds-grid`, `portfolio-funds-total`, `portfolio-funds-total-value`, `portfolio-funds-total-status`, `portfolio-funds-card-{perps,spot,trading,evm}` (+ `-value`, `-detail`), `portfolio-funds-evm-gas-status` (`data-gas-tone` ok|warn), `portfolio-funds-connector-{perps-spot,spot-evm}` (+ `-slot`, `-tooltip`), `portfolio-funds-reasons`, `portfolio-funds-reason-<n>`; `account-equity-hyperevm-line`, `account-equity-hyperevm-value`, `account-equity-hyperevm-move`, `account-equity-hyperevm-move-reason` (data-roles present only while shown; the slots always are).
  - **Repaint.** `/hyperopen/test/hyperopen/views/trade_view/hyperevm_line_repaint_test.cljs` counts equity-panel renders on `/trade` (repaint on the first read and on a balance change, none for a first read in flight, a refresh, or a poll with a new gas price) and checks the mobile account surface receives the model; the strip's model is pinned equal across such a poll in `funds_locations_test.cljs`.
  Rationale: one sum keeps the three surfaces consistent and trading equity untouched; the lazy-module export keeps the account-info projections out of the main bundle; and every conditional part is an always-present slot with visible reasons.
  Date/Author: 2026-09-30 / Claude.

- Decision (Milestone 7 review fixes): these replace the matching parts of "Milestone 7 as built". Milestone 8 codes against them.
  - **Total card copy (replaces "Trading equity $X"; plan deviation from Main.dc.html).** The sub-line is "Total Equity $X", the summary card's own label for the same figure. The artboard's "Trading equity" named a figure (Perps + Spot) that is not the summary's Total Equity once vaults or Earn hold anything, and a unified account's summary already shows "Trading Equity" for Spot account equity, so one page showed two amounts under one label. A second line, `portfolio-funds-total-includes` (always present, hidden by class when empty), reads "Includes $Y in vaults and Earn" / "in vaults" / "in Earn" when either is at least a cent (a unified account's Total Equity leaves Earn out, so only vaults count there). Perps + Spot (or Trading account) + HyperEVM + that note add up to Total value.
  - **HyperCore readiness (extends "Unknown is never zero").** `funds-locations.model/hypercore-status [state]` is `:ready` once `[:webdata2 :clearinghouseState]` and `[:spot :clearinghouse-state]` are both maps, `:unavailable` when `[:spot :error]` is set with no Spot state, else `:loading`. Until `:ready` the Perps, Spot and Trading account cards read "Loading…" ("Checking HyperCore balances…") or "—", and the total "—". The total's status line is, in order: "HyperEVM balances unavailable", "Some HyperEVM balances unavailable", "HyperCore balances unavailable" (warn tone), then "Balances loading", "HyperCore balances loading", "HyperEVM balances loading" (muted). The model exposes `[:total :tone]` (`:muted`/`:warn`) and the status node carries `data-tone`; only a failure takes `text-ho-warn`.
  - **Partial reads.** An entry now also holds `:never-read-token-indexes` (a set): tokens of the read's unread chunks that no read of the entry ever answered. `hyperopen.hyperevm.domain.balances/partial-read? [state address]` is true while it is non-empty; per-call failures (JOFF) never count. `hyperevm-funds` reports such a read as `:status :partial` with `:usd` nil, so the total reads "—" ("Some HyperEVM balances unavailable"), the HyperEVM card "—" and the /trade line hides. `:unread-token-indexes` is unchanged (the union), so every existing reader is too.
  - **Failed first read while retrying.** `hyperopen.hyperevm.domain.balances/first-read-failed? [entry]` is true for an entry never read whose last read failed, including while `apply-loading` has it `:loading` again (the failure's `:error` is kept). `balances-hyperevm/hyperevm-status`, the panel slice's projected status and the Transfer preview's source block use it, so the strip, the Balances note and the modal keep saying "unavailable" through retries instead of flipping to "loading" on every poll, and the `/trade` slice compares equal across them.
  - **Unpriced and dust (trade line model gains `:value-text`).** `hyperevm-line-model` -> `{:visible? :usd :value-text :move-action :blocked-reason :focus-token}`. It is visible when `:ready` and either the value is at least $0.005 (rounds to a cent) or a held token has no price; `:value-text` is the currency or "Unpriced". Sub-cent dust alone hides the line. The strip's HyperEVM card reads "Unpriced" under the same rule.
  - **Connector tooltip (replaces its part of "Connectors").** The reason line under the strip shows at every width (`lg:hidden` dropped; a tap on a touch screen at desktop width never hovers). From `lg` up the tooltip is still there for pointer and keyboard users, now inside an inline `relative` `group/connector` wrapper around the button, so `top-full` is the button's bottom edge (the grid slot stretches to the card row); it is `invisible` when closed (no `pointer-events-none`), its 6 px gap is its own top padding so the pointer can move onto it, and Escape dismisses it. The new `hyperopen.views.ui.dismissible-tooltip` gives the group `group-attrs` (keydown/mouseenter/mouseleave/focusin/focusout handlers that set or clear `data-tooltip-dismissed` on the group, with a document keydown listener only while hovered) and the tooltip hides with `group-data-[tooltip-dismissed=true]/connector:!invisible`. The Balances move tooltip (`tabs/balances/moves.cljs`) uses the same helper with the unnamed-group class `dismissible-tooltip/hidden-when-dismissed-class`.
  - **Memo (replaces "memoized on the identity of … the market catalogue and the HyperCore rows").** `hyperevm-funds` reuses its result while the shown read (address, entry and spotMeta objects) is the same and either nothing positive is held (the HyperCore rows are then never read) or the HyperCore inputs (`:webdata2`, `:spot`, `:account`, `:perp-dex-clearinghouse`) are the same objects and the held tokens' price inputs compare equal: every spot market, plus `perp:<NAME>` for each held token (the key `resolve-market-by-coin` tries first). The active perp's ticks therefore re-price nothing.
  - **Namespaces.** The model moved to `/hyperopen/src/hyperopen/views/portfolio/funds_locations/model.cljs` (`hyperopen.views.portfolio.funds-locations.model`); `funds-locations/funds-locations-model`, `perps-spot-data-role` and `spot-evm-data-role` stay as re-exports, so callers are unchanged. `balances-hyperevm/held-token-names [state]` is new.
  - **Not changed, with reason.** Finding 9's premise that the metrics price against `{}` on `/trade` is wrong (see Surprises); the fix keeps its recommendations (skip the HyperCore rows when nothing is held, key on held-token prices) because they remove the line's own per-tick re-pricing.
  Rationale: each change closes a finding the review raised; the two majors were a label that meant two different figures on one page and a HyperCore-unloaded window that showed zeros and a HyperEVM-only total.
  Date/Author: 2026-09-30 / Claude.

- Decision (Milestone 8 as built, items 1-4): these are the real test seams Milestone 8's browser QA and Milestone 9 build on.
  - **Wallet simulator.** The EVM methods live in a new pure namespace, `/hyperopen/src/hyperopen/telemetry/console_preload/wallet_evm.cljs` (`hyperopen.telemetry.console-preload.wallet-evm`); `simulators.cljs` (380 -> 415 lines) logs every request first and routes `eth_sendTransaction`, `wallet_addEthereumChain` and `wallet_watchAsset` to it. Config (camelCase or kebab): `switchChainErrorCode` (4902 = every chain but the start chain is unknown until `wallet_addEthereumChain` adds it, which does not switch; any other code, e.g. 4200, rejects every switch), `sendTransactionErrors` (one entry per send, in order: nil sends, a code/string/`{code message}` rejects with an `Error` carrying `code`), `txHashes` (handed out first), `watchAssetResult` (default true). Hashes are deterministic: `simulated-tx-hash n` = `0x` + 56 `e` + `n` in 8 hex digits, the same string `hyperevm_fixtures.mjs` `simulatedTxHash` builds (both tests pin `…00000001` and `…0000001a`). `eth_chainId` follows switches (it already did). `HYPEROPEN_DEBUG.walletSimulatorSnapshot()` returns `{installed config listenerCounts requests transactions addedChains watchedAssets}`: `requests` is every EIP-1193 request as `{method params}`, and each transaction carries its params plus `hash` and `walletChainId`. The debug key cost `console_preload.cljs` one line (cap 551 -> 552, reason appended). Tests: `/hyperopen/test/hyperopen/telemetry/console_preload/simulators_evm_test.cljs` (8 tests).
  - **Fixtures and routes.** `/hyperopen/tools/playwright/support/hyperevm_fixtures.mjs` (no Playwright import; node-tested in `hyperevm_fixtures.test.mjs`, 12 tests):
    - `routeHyperEvmRpc(page, fixture)` routes every request to the host `rpc.hyperliquid.xyz` (a URL predicate, so any path) through `handleHyperEvmRpc`, which answers `eth_getBalance`, `eth_gasPrice`, `eth_chainId`, `eth_estimateGas` (the live estimates per call kind: 22.8k native, 29.7k ERC-20, 56.2k approve, 63k deposit; `estimateErrors` queue), `eth_call` (Multicall3 `aggregate3` decoded call by call: owner and system-address `balanceOf`, `decimals()`, `getEthBalance`; direct `allowance`, `balanceOf`, `decimals`), `eth_getTransactionReceipt` (a live-shaped success receipt for any hash, or a per-hash queue of `"rate-limited"`, `"pending"` or receipt overrides, with an `onReceipt(hash, receipt)` hook), batches, and `rateLimitNext` (the live single `-32005` object, batch or not). Unknown methods answer -32601 and are recorded. Calling it again on the same page swaps the fixture; the controller exposes `fixture`, `update(fn)`, `entries()`, `unknown()` and `unrouted()` (requests the page made to the host that the route never handled, from `page.on("request")`).
    - `emptyHyperEvmFixture()` holds nothing, with the live bridge state (system units, `decimals()`, JOFF reverting) and the probe gas price; `hyperEvmFixture({owner holdings overrides})` and `setHolding` take decimal token amounts.
    - `routeHyperCoreInfo(page, fixture)` answers `spotClearinghouseState` (the live HYPE and PURR system-address states by default), `clearinghouseState` (base dex only), `userRole` (default `missing`), `userAbstraction` (default `"default"`), `subAccounts` (when set) and the market catalog (`spotMeta`, `spotMetaAndAssetCtxs`, `metaAndAssetCtxs`, `perpDexs` `[null]`, `outcomeMeta`), and falls back for everything else. The catalog is routed because live 429s otherwise leave `[:spot :meta]` unset (see Surprises); it also makes USD prices deterministic (the live marks of PURR/USDC, @5, @81, @107 and @142 on 2026-09-30T23:34Z).
    - `routeHyperCoreAccountStreams(page)` keeps the live websocket but drops page -> server `subscribe`/`unsubscribe` messages for a user's `clearinghouseState`, `webData2` and `spotState` streams.
  - **visitRoute.** `visitRoute` installs `routeHyperEvmRpc` with `emptyHyperEvmFixture()` before its first navigation unless the page already has one (a spec's own fixture wins), and `options.hyperEvmFixture` swaps it. `expectNoUnroutedHyperEvmRequests(page)` fails on any unrouted request or unknown method; the new spec runs it after every case. The guard case connects a wallet with only the default fixture and asserts the poller's reads were all routed.
  - **Spec.** `/hyperopen/tools/playwright/test/funding-transfer-hyperevm.spec.mjs` (`@regression`, 22 cases): the guard; (a)-(o) as listed in Milestone 8 (with (d) split into approve-then-deposit and allowance-covers, (e) plus a "Spot HYPE below 0.05" case, (n) at both sizes); and three acceptance cases the list does not name: unknown HyperEVM data (every read rate-limited: strip "—", the modal's quiet "unavailable" line, no gas fix; acceptance 9), an unactivated HyperCore account (PURR blocked, USDC's activation-fee row; acceptance 11) and a keyboard-only Spot -> HyperEVM move with focus returning to the opener (acceptance 16). HOPE (its live `decimals()` mismatch) sits in (a) with no move target (acceptance 5). Assertions are exact where the acceptance criteria name values: the whole `sendAsset` action minus the signing fields (and `signatureChainId` 0xa4b1, `hyperliquidChain` Mainnet, one exchange call), the whole HYPE wallet log (`eth_chainId`, switch `[{chainId 0x3e7}]`, `eth_chainId`, `eth_sendTransaction` with `value 0x8ac7230489e80000`, `gas` = live estimate x 1.3, `maxFeePerGas` 0x17d78400, `maxPriorityFeePerGas` 0x0), both USDC transactions field for field (approve calldata to 0xb883…630f, deposit calldata to 0x6b9e…0a24, each `chainId 0x3e7`), the deposit estimate ordered after the approve's receipt, the 4902 `wallet_addEthereumChain` params, and the gas top-up's `sendAsset`.
  - **Exchange simulator use.** Every case queues its answers on `signedActions.default` followed by eight refusals, since per-type queues never match (see Surprises) and an exhausted queue falls through to the real exchange.
  - **Existing specs.** The two title renames were already made in Milestones 3/5 (`portfolio-regressions.spec.mjs:3056`, `trade-regressions.spec.mjs:3274`); no other spec asserts the old labels. Two `portfolio-regressions.spec.mjs` changes: the direct-`goto` spectate case calls `routeHyperEvmRpc`, and `portfolio volume history opens near the metric card trigger` now checks the gap between the popover and its trigger (0 when they overlap; still under 160 px) instead of top-to-top distance, since the funds strip moved the trigger to the bottom of a 375 px viewport where the popover clamps up over it (see Surprises). A popover pinned to the top margin would still fail it (gap 233 px). This is a deviation from "update the label specs": the alternative, flipping the shared anchored popover above its anchor, changes every funding popover and is left for review.
  - **Mutation check.** A build that dropped `:chainId` from every HyperEVM transaction, removed the gas top-up's submitting/sent refusal, and treated every `decimals()` answer as matching failed 6 of the 22 cases ((a) HOPE gained a move, (c), both (d), (i), and (e), whose double click sent two top-ups and read "sent").
  Rationale: the wallet, the exchange and HyperEVM are all simulated with production-shaped data, so every acceptance value is asserted exactly and no case depends on the live RPC; the HyperCore catalog and balance streams are fixed because live rate limits and zero streams otherwise make the cases flaky.
  Date/Author: 2026-09-30 / Claude.

- Decision (Milestone 8 review fixes): these replace the matching parts of "Milestone 8 as built". The review raised 21 findings, numbered here in its order: 6 majors (1, 2, 3, 4, 14, 15) and 15 minors; 15 duplicates 1, 16 duplicates 10, and 19 duplicates 4. Findings 20 (baseline evidence) and 21 (full gates) are answered in Outcomes.
  - **One guard for every spec (majors 1, 2 and 15).** Every spec now imports `test`/`expect` from `/hyperopen/tools/playwright/support/guarded_test.mjs`; `tools/playwright/support/spec_imports.test.mjs` (in `npm run test:playwright-support`) fails a spec that imports `@playwright/test` itself. Its automatic `hyperEvmRpcGuard` fixture (`/hyperopen/tools/playwright/support/hyperevm_rpc_guard.mjs`) routes the RPC host at the context level from an empty fixture, for the test's context and for every context opened through `browser.newContext()`/`browser.newPage()` while the test runs (the browser method is wrapped and restored). It observes requests with `context.on("request")`, independently of any route, and at teardown fails the test for each request that no mock handler answered (page- and context-level handlers record what they answer in one registry, `wasHyperEvmRequestMocked`), for each method a mock could not answer, and for each batch over 20 entries. The page-local observer and `expectNoUnroutedHyperEvmRequests` are gone; `visitRoute` still installs the page-level mock so specs get a controller. The guard case proves three things in a real browser: visitRoute's mock answers a connected wallet; a new context loaded with `page.goto` and no `visitRoute` (the `shareable-view-url` path) is answered by the context mock; and a request that a later page handler lets past the mock (aborted, so nothing goes live) is reported although the page has the mock. The case then calls `acknowledge()`, which only that self-test uses.
  - **USDC ordering (major 3).** The mock's deposit `eth_estimateGas` now reverts with the live allowance error until the owner's CoreDepositWallet allowance covers the amount, and `approveOnReceipt` grants it when the approve's receipt is answered. Case (d) queues two pending reads for the approve, asserts exactly three approve receipt reads, one deposit estimate after the third (the one that returned the receipt), and the whole six-entry wallet log. Case (h) grants the allowance the same way. `fixture.onReceipt` became a `receiptHooks` list.
  - **Volume history (findings 4 and 19).** The anchoring case again asserts top-to-top distance under 160 px, with the trigger scrolled into the upper half first. A new case, `portfolio volume history opened from a low trigger is clamped fully into view at 375 px`, places the trigger as low as the mobile nav allows and asserts the popover is wholly on screen and clamped against the bottom margin (bottom within 40 px of it), so a popover pinned to the top fails. Deviation from finding 19: no assertion that the trigger stays uncovered, because the HEAD build covers it too at 375 px (see Surprises); flipping the shared anchored popover above its anchor remains a design call, and browser QA should record it.
  - **Double-send window (minor 5).** Case (k) queues rate-limited, pending, pending and checks after each read that the progress view shows, no form or amount field exists and the submit is disabled. A new (k) case installs Playwright's clock, keeps the receipt pending, jumps 3 min 5 s past the foreground wait, and asserts the pending view, its explorer link (`https://hyperevmscan.io/tx/<hash>`), the in-flight entry, and that a second HyperEVM -> Spot draft is blocked with "A HyperEVM transfer is still confirming…" while the wallet saw one send.
  - **Gas fix (minor 6).** The debug exchange simulator holds a response for its `delayMs` (the key is not part of the payload). Case (e) holds the refusal 1.5 s, asserts `submitting` after the double click, dispatches `:actions/submit-funding-transfer-gas-topup` while it is submitting and again once it is sent, and still counts one and then two `sendAsset` calls.
  - **Wallet simulator (minors 7 and 8).** Unknown methods reject with 4200 and are listed in `walletSimulatorSnapshot().unknownRequests`; the HyperEVM spec's afterEach asserts the list is empty. `chainAfterSends` (`{n: chainId}`) moves the wallet right after its nth successful send, and an emitted `chainChanged` now changes what `eth_chainId` answers. A new case (p) covers acceptance 7: with `chainAfterSends {1: 0xa4b1}` the approve is sent, the deposit is not, the run fails with the "left HyperEVM" message, and the in-flight entry is cleared. A unit test sends a `chainId 0x3e7` transaction from a wallet on 0xa4b1 and expects `walletChainId` 0xa4b1.
  - **Mock fidelity (minor 9).** A batch over 20 entries gets the live -32010 object and runs nothing. A rate-limited receipt read answers its whole batch before any other entry runs, so no queue moves and no receipt hook fires.
  - **Layout (minors 10 and 16).** Case (n) scrolls the lowest opener as low as a pointer reaches it (24 px above the viewport bottom, or above a fixed layer) and asserts its bottom is at least 0.75 x the viewport height, that it is hit-testable, and that the click did not scroll it. It then checks that `document.elementFromPoint` at the centres of the fix and the submit lands on them, plus a trial click on the fix. It now runs on `/trade` 1280x800 too. Case (o) hit-tests the sheet's submit.
  - **HyperCore reads (minor 11).** `routeHyperCoreInfo` takes `{ strict }`, which records every user-carrying read it lets through. `subAccounts` answers the live `null` (deviation: the review suggested `[]`; live answers `null` for an address with none). Eight user reads the spec still sent live are answered from their captured live answers for an account-less address. The spec's afterEach fails on any user read that reached the live API. The market websocket stays live: it carries no account data once the balance subscriptions are dropped, and a local stub would have to fake mids, books and candles (left out).
  - **Exchange simulator lookup (minor 12).** `post-signed-action!` looks per-type queues up by keyword (`(keyword (str (:type action)))`, as `post-info!` does), and the `scheduleCancel` default matches by `name`, so a keyword path still gets it. `internal_seams_test.cljs` pins it: a keywordized `sendAsset` queue answers with no fetch. `subaccounts-regressions.spec.mjs`'s `sendAsset`/`subAccountTransfer` queues now answer instead of falling through to the live exchange.
  - **Arrival (minor 13).** Case (b) raises PURR to 50, waits for two more balance reads and the stored 50, asserts still arriving, then 100 and arrived.
  - **Reason tooltips (major 14).** Cases (a) and (f) assert opacity 0 before focus and 1 after (`toBeVisible` ignores opacity), and (a) asserts Escape hides the tooltip without moving focus.
  - **Keyboard (minor 17).** The Spot -> HyperEVM case reaches its opener by Tab, changes the To place with Space (to Perps and back) and the asset with the arrow keys. A new HyperEVM -> Spot case is keyboard-only: the wallet rejects the first send, "Back to edit" is pressed by key (focus lands on the kept amount), the second submit succeeds and arrives, Done returns focus to the opener. The From group is not driven by key; the To group uses the same control.
  - **375 px toolbar (minor 18).** Case (a) at 375 px asserts that Hide Small Balances wraps under the search row with the search, filter and checkbox on screen, an EVM chip in the HYPE HyperEVM card, and that the HyperEVM filter leaves only HyperEVM cards. Deviation: the review's "filter top below the search's bottom" is not how the toolbar wraps at 375 px (see Surprises).
  - **Mutation check.** A build that dropped `group-focus-within:opacity-100` from the move reasons, the pre-send chain check (`send-pinned!`) and the gas top-up's submitting/sent refusal failed exactly the four targeted cases: (a) and (f) on opacity, (p) with no failed view, and (e) with a second top-up "sent".
  Rationale: each assertion now fails on the defect it names, and the guard catches a leak wherever it comes from because it watches requests rather than routes.
  Date/Author: 2026-10-01 / Claude.

- Decision (Milestone 8 item 5 as built): browser QA results and the three fixes it made. Milestone 9 reviews against these.
  - **How it ran.** A throwaway spec, `tools/playwright/test/tmp-m8-browser-qa.spec.mjs`, deleted afterwards (copy at `scratchpad/qa/m8qa-spec-copy.mjs`), drove this worktree's `compile app` build on port 8093 through `guarded_test.mjs`. It used the wallet and exchange simulators, `hyperEvmFixture` (HYPE 12.5, USDC 1240 and UBTC 0.05 on HyperEVM; USDC 2,105.40, HYPE 412.08, PURR 9,800 and SIX 12.5 on Spot; 8,420.18 USDC Perps) and strict `routeHyperCoreInfo`. Each width got a fresh context; pages were quiesced (fetch frozen, poller off) before closing, so the RPC guard saw no aborted request. The `/trade` geometry, account tab strip, layout-shift and volume-popover probes also ran against the `git archive HEAD` build on port 8096. Screenshots are `scratchpad/qa/<surface>-<state>-<width>.png`; measurements are the `m8qa-*.jsonl` files beside them.
  - **Fix 1: the Balances tab header stacks below lg.** `tab_actions.cljs` `header-classes` swaps `md:flex-row`/`md:items-stretch`/`md:border-b-0` for their `lg:` forms on the header shell and the strip viewport, only while Balances is selected. Between 768 and 1023 px the search, location filter and Hide Small Balances take their own row under a full-width tab strip, as on phones. Effect: `/portfolio` 768 shows 7 tabs with the selected one in view (HEAD 3), and `/trade` 768 shows all 8 (HEAD 5). From lg up nothing changed (see the remaining risks). `tab_actions.cljs` is 498/500 lines.
  - **Fix 2: the run views' route cards keep full opacity.** `transfer_parts.cljs` `route-card` dropped `opacity-75`, which brought the cards' 12 px balance text to 3.64:1 and the EVM chip to 4.04:1. This deviates from the `TransferEvmToCoreUsdc` artboard's `opacity: .75`; the cards are still static, with no choices. The decorative arrow (`aria-hidden`, 4.32:1) is exempt.
  - **Fix 3: MAX and the percent chips use the design-system focus ring.** They had no focus classes and showed Chromium's default `auto 1px` blue outline. They now use `focus:outline-none focus-visible:ring-2 focus-visible:ring-ho-accent/50`, like the place buttons, swap and fix.
  - **Tests.** `/hyperopen/test/hyperopen/views/funding_modal/transfer_qa_fixes_test.cljs` pins all three: no `opacity-*` on route cards, the ring classes on MAX and the 25/50/75 chips, and `lg:` stacking on the Balances header only (Positions keeps `md:flex-row`).
  - **Not changed, with reasons.**
    - The tab-strip squeeze from lg up. The artboard puts the filter in the tab header; the strip already scrolls sideways on HEAD at 1280; the selected tab stays visible. Compact labels ("All / Core / EVM", the plan's below-640 copy) or a filter row inside the tab would change plan-decided copy or layout, so that is left for review.
    - `/trade` 1440: with place chips showing, the Contract column's explorer icons sit 18 px past the 1120 px panel. The rows' sideways scroll reaches them, and the 116 px chip-time Coin minimum is a Milestone 6 decision pinned by `balances_geometry_test.cljs`.
    - The toast over the 375 px sheet (pre-existing layering; it clears in about 4 s and has a close button).
    - The strip's insertion shift on connect: an account-only section cannot reserve space for visitors who have no account.
    - The desktop Balances moves' focus style: underline plus the brighter accent, no ring. It is visible in every theme (screenshots `portfolio-balances-disabled-reason-{1280,1440}.png`) and matches the text-link moves.
  Rationale: each fix closes a FAIL the QA measured, with the smallest class-level change; the rest are trade-offs or pre-existing behaviour, recorded rather than redesigned in a QA pass.
  Date/Author: 2026-10-01 / Claude.

- Decision (Milestone 9 review fixes): the final three-lens review (fund safety, regressions and contracts, UX and accessibility) raised 27 findings: 4 majors and 23 minors. Every major is fixed; 20 minors are fixed in full and 3 in part (the deviations below). These replace the matching parts of earlier entries.
  - **The HyperEVM bridge cap is current or unknown (fund-safety major).** `bridge/apply-health` stamps `[:hyperevm :bridge :evm-system-read-at-ms idx]` with the poll's request time for each system balance it actually read (a chunk that went unread stamps nothing). `bridge/core->evm-capacity [state token now-ms]` returns nil once the reading is `evm-capacity-max-age-ms` (60 s) old, and also while it predates the user's last Core -> EVM send of that token by less than `core->evm-settle-ms` (5 s): `submit-core-to-evm!` stamps `[:hyperevm :bridge :core->evm-sent-at-ms idx]` (`bridge/mark-core->evm-sent`, HYPE and USDC exempt) before the POST and again when HyperCore answers, through `run-state/follow-up!`. `evm-amounts/capacity` passes the clock for both directions, so a frozen reading (failed, rate-limited or paused polls, a lost chunk) reads "Checking the bridge balance…", and a second move from a reopened modal, while the first POST is in flight or right after it, waits for a read that shows the first one's draw (the forced fast poll after a send delivers one within about 8 s). The review's other option, subtracting unreflected sends, was not taken: whether a reading reflects a send cannot be known from the reading. The 2-arity (no clock) still serves the Balances table's and the panel slice's "bridge empty" checks.
  - **A confirmed receipt never becomes a retryable failure (minor).** `hyperevm-submit/confirm!` records the in-flight change inside a `try` and only then drops the hash from `unresolved`, so a store watcher that throws there (a validation build) cannot turn a confirmed transaction into `{:status "err" :kind :unexpected}` with "Try again".
  - **Perps -> HyperEVM withdrawn (minor; scope change).** Nothing showed HyperCore bridging a `sendAsset` with `sourceDex ""`, and verifying it needs a live transfer of the user's own funds, which only the user can make. `transfer-route/supported-pair?` now refuses Perps -> HyperEVM like HyperEVM -> Perps: the To HyperEVM option is disabled with "Move USDC to Spot first, then Spot → HyperEVM." (pooled accounts keep their own reason), choosing Perps as the source of a HyperEVM move turns the destination into Spot, the Perps USDC row has no "To HyperEVM", `core->evm-request` always sends `sourceDex "spot"`, and the pre-signing invariant refuses any other `sourceDex` for a system address (Send included). Re-enabling it needs one small live Perps -> HyperEVM USDC transfer with its ledger shape and the HyperEVM credit recorded in Surprises.
  - **The HyperCore cap's age starts when HyperCore answers (minor).** `fetch-core-bridge-balance!` passes `:force-refresh? true`, so the info client's 15 s cache cannot hand back a reply the 60 s age then counts from its arrival.
  - **Wallet errors are read from any JS object (minor).** `wallet-rpc/error-field` and the nested `data.originalError` read use `goog/typeOf` "object" instead of cljs `object?`/`instance? js/Error`, so a provider that rejects with a class instance (or an object from another realm) keeps the deposit flows' 4902 add-chain fallback and the 4200/-32601/"not supported" classification, as HEAD did. Pinned with a class-instance rejection for the deposit config and the HyperEVM config.
  - **Main bundle (minor, in part).** The pre-signing invariant uses the new `tokens/system-address?` (pinned equal to the ledger's `token-system-address?` on fourteen addresses), so `hyperopen.domain.account-ledger.derive` is back in the lazy `portfolio_route` module. The release `main` growth is measured and recorded (Surprises: +42,301 gzip bytes, 676,855 -> 719,156; the advisory 640,000 budget was already exceeded on HEAD). Not done: moving the HyperEVM runtime or the Transfer domain out of `main` (the poller and the funding actions are registered at startup; a lazy split is its own project), or keeping `views.ui.location-chip` (about 30 lines, shared by two lazy modules) out of `main`.
  - **The foreground receipt wait backs off while rate-limited (minor).** `rpc/wait-for-receipt!` doubles its gap after each rate-limited poll (2 s, 4 s, then 8 s at most) within the same 180 s deadline and returns to 1 s once the RPC answers. Not done: feeding the app-wide backoff from the wait (optional in the review). `rpc.cljs` is now 500/500 lines.
  - **Balances tab header (minor, in part, with the tab-strip minor).** The location filter shows its short labels ("All / Core / EVM") from lg to 2xl, beside the tab strip, and the long labels from 640 px until lg (its own row) and from 2xl; each option's accessible name is always the long label, which contains the short one. Measured: the Balances actions are 459 px instead of 529 px from lg up, so `/trade` keeps 70 px more tab strip at 1280 and 1440 (Surprises). Not done: stacking the header for every tab between md and lg (it would change every tab's geometry on `/trade`, one of the high-risk QA checks) or reserving a second row for all tabs; the 48 px shift when switching to or from Balances between 768 and 1023 px is recorded instead. Also kept: the filter renders for every account, because rendering it only once HyperEVM rows exist would shift the header when the first read lands and hide the HyperEVM filter's "No HyperEVM balances." answer.
  - **Run headings take focus (UX major).** The run views' headings use `:tabindex "-1"` and the dialog panels `:tabindex "-1"` (the panel stays out of the Tab order, as it effectively was, and the dialog's focus fallback now works). `/hyperopen/test/hyperopen/views/funding_modal/tabindex_attribute_test.cljs` fails on a hyphenated tabindex key anywhere in the funding modal's view sources; the other views that spell it so predate this feature and are left for a separate fix. The HyperEVM spec asserts `toBeFocused` on the progress, pending, failed and success headings.
  - **The gas fix keeps focus (UX major).** The fix is never natively disabled: busy (`:submitting`, `:sent`) or with a reason it is `aria-disabled` (busy also `aria-busy`), has no click handler and keeps its focus ring. When the blocked card's fix goes away while it held focus (the gas landed, or the network retry re-enabled the form), `transfer-parts/fix-focus-hook` on the always-present blocked region moves focus, a tick after the dialog's own microtask focus, to the submit when it is enabled, else the amount input, never to Close. A keyboard Playwright case presses the fix, presses Enter again, lands the gas and asserts focus on the submit, never on `funding-modal-close`.
  - **In-flight and pending copy say how to get out (UX major).** `transfer-state/in-flight-blocked-message` follows the entry: no hash yet -> "Your last HyperEVM transfer is waiting for your wallet. Finish or reject it there, or reload the page to start over."; a hash in the foreground wait -> the old "still confirming" copy; `:pending` -> "Your last HyperEVM transfer hasn't confirmed yet. If your wallet shows it finished, was replaced or was cancelled, reload the page to start another."; `:unconfirmed` unchanged. The pending view and its message no longer say "before trying again" or "it will finish on its own". Not done: a confirmed "Stop waiting" action (optional in the review; a reload clears the entry, which is not persisted).
  - **Partial reads are unknown (minor).** `transfer-balances/location-known? :hyperevm` is false while `balances/partial-read?`; the preview's source block then says "Some HyperEVM balances couldn't be read. Retrying…" instead of the asset list's empty copy; `balances-hyperevm/hyperevm-status` returns `:partial`, which the Balances note and the HyperEVM filter's empty state say in the same words; the `/trade` panel slice keeps `:never-read-token-indexes` so it agrees.
  - **Success cards (minor).** While arriving or slow the cards show the move ("Sent −100 PURR", "Arriving +100 PURR"); once arrived, "Spot now" / "HyperEVM now" with the live balances.
  - **Blank amount (minor).** The Transfer form's message leaves out the legacy preview's "Enter a valid amount." while the field is blank and no submit error is shown (the preview itself is unchanged), so a freshly opened Perps <-> Spot form shows no red error and no `aria-invalid`.
  - **Failed steps say why (minor).** `transfer-run/failure-detail`: "Rejected in wallet", "Reverted on HyperEVM", "May have been sent — check your wallet" (`:maybe-sent?`), else "Didn't finish".
  - **Known-empty bridges in the asset list (minor).** An option toward HyperEVM whose bridge reads "0" is disabled with the Balances table's reason; toward HyperCore it stays selectable (its reading is fetched only for the selected token, so a disabled one could never be read again) but is flagged `:empty-bridge?`. `eligible-asset-index` keeps neither as the current asset and picks the first usable option before them. A HyperCore reading not yet fetched is unknown, so the first open can still land on an empty one.
  - **Available (minor).** A balance side gains `:available` (contract updated): `source-available`, so native HYPE leaving HyperEVM reads "Available 12.499 HYPE" like the Balances table and MAX; the cards keep the balance itself.
  - **Tooltip (minor, in part).** The Balances move tooltip is hoverable (no `pointer-events-none`; `invisible` when closed; the gap is its own padding), as the strip's connector tooltip is. Not done: choosing up or down from the anchor's position in the scrolled viewport; a tooltip on a short table can still be clipped at its far end (Surprises).
  - **Smaller UX fixes.** A disabled asset dims only its radio and symbol (its reason keeps full secondary-text contrast); "HyperCore Spot" names Spot-only places (the strip's SPOT card, the run views' route card) and "HyperCore" stays for the ledger; enabled mobile moves and mobile Send underline on keyboard focus; the network retry reads "Try switching again" (it re-enables the form; one dispatch cannot both clear the block and submit); run outcomes toast only once the modal no longer shows that run, with grouped amounts ("Sent 1,000 PURR to HyperEVM."); percent chips are named "25% of the maximum"; an unknown MAX is "Use the maximum"; the amount notice is in the input's `aria-describedby`; the gas card's live region announces only its title (the estimate changes with every gas-price poll); explorer links hide the arrow from screen readers and say "(opens in a new tab)"; a one-step run's submit reads "Waiting for wallet" without "(step 1 of 1)"; the activation block reads "Your HyperCore account isn't active yet. Move more than 1 USDC from HyperEVM to Spot first (1 USDC is the one-time activation fee), then move this token."; the swap icon turns vertical while From and To stack.
  - **Tests.** New namespaces `/hyperopen/test/hyperopen/hyperevm/domain/bridge_freshness_test.cljs`, `/hyperopen/test/hyperopen/funding/domain/transfer_review_fixes_test.cljs`, `/hyperopen/test/hyperopen/funding/application/hyperevm_review_fixes_test.cljs`, `/hyperopen/test/hyperopen/views/funding_modal/transfer_review_fixes_test.cljs`, `/hyperopen/test/hyperopen/views/funding_modal/tabindex_attribute_test.cljs` and `/hyperopen/test/hyperopen/views/account_info/tabs/balances/review_fixes_test.cljs`, plus additions to the wallet_rpc, rpc, tokens, transfer-state, submit and follow-up tests. Unit tests that call Transfer actions through the facade with a Core -> HyperEVM draft now pin the composition seam's clock (`platform/now-ms`) to the fixture's, as the HyperEVM -> Core ones already did. The HyperEVM spec gained the keyboard gas-fix case and heading-focus, hover, busy-fix and copy assertions (27 cases).
  Rationale: each change closes a finding the review confirmed. The three fund-safety items close a stale-cap and a double-send path and stop signing an unverified bridge shape; the three UX majors stop focus from landing on Close mid-transfer and give a stuck move a way out.
  Date/Author: 2026-10-01 / Claude.

## Outcomes & Retrospective

Milestone 0 (2026-09-30) changed no behavior. All 112 `retire-by` dates in the two exception files are now 2026-12-31: 106 size entries and 6 boundary entries. Only the 11 that expired on 2026-09-30 got the renewal note in their reasons. Four excepted namespaces shrank. Each cap was lowered to the new line count, so none of them has spare room. A later milestone that grows one must raise its cap and give a reason:

- `action_args.cljs` 786 → 742/742;
- `funding_modal_contracts.cljs` 799 → 776/776;
- `modal_commands.cljs` 559 → 516/516;
- `actions_test.cljs` 1316 → 1290/1290.

New code in later milestones therefore goes into the new namespaces: `funding_action_args.cljs` (58 lines), `funding_modal_transfer_contracts.cljs` (40 lines) and `transfer_commands.cljs` (48 lines). Two review follow-ups added tests only: the literal defaults pin and the transfer-contract rejection test. `npm run gates` passed 34/34 both before and after them. Overall complexity rose slightly, by three small namespaces, and each one gives later milestones a clear owner. The renewals push debt deadlines out by three months and still have to be paid.

Milestone 2 (2026-09-30) added the HyperEVM read side: state defaults, a poller, bridge health in the same RPC batch, on-demand HyperCore bridge and activation reads, the identity gate, and ledger labels for every bridge leg. It shows nothing in the UI yet. The poller reads live whenever a wallet or spectated address is on `/trade` or `/portfolio`. Until Milestone 8 routes the RPC in `visitRoute`, existing Playwright specs that connect an address may therefore reach `rpc.hyperliquid.xyz`. Specs that need determinism before then can call `HYPEROPEN_DEBUG.setHyperevmPollerEnabled(false)`. `npm test` grew from 6461 to 6522 tests, and `npm run gates` passed 34/34. The main risk is new: background reads now reach an outside host.

Milestone 3 (2026-09-30) added the whole Transfer domain, its commands and its view-model, but no new view: the modal still renders the legacy form, now titled "Transfer". Every HyperEVM route has a preview with a specific blocked reason, MAX floored to the route precision and capped by the bridge, a pre-signing invariant that the submit effect enforces, and a covered one-click gas top-up with a regenerated formal corpus. `npm test` grew from 6534 to 6605 tests with 0 failures. The main new risk is that a gas top-up or a Core -> EVM move dispatched before Milestone 4 still closes the modal on success like a legacy transfer; nothing in the UI dispatches them yet.

The Milestone 3 review fixes (2026-09-30) changed no view. A HyperEVM → Core draft now keeps its HyperCore bridge balance and activation current: edits, MAX, percent and submit read them again when due, and the balance poller re-reads an idle draft's balance once it is 45 s old (at most once per 10 s). "Try again" saves a keyword path, the gas top-up records its own success and every failure, and the pre-signing invariant covers subaccount sources, the destination dex, Core precision and the Perps deposit dex, and now also guards Send. Outside Transfer mode the view-model computes no Transfer options or prices. `npm test` grew from 6605 to 6626 tests with 0 failures, `npm run test:websocket` ran 585 tests with 0 failures, and `npm run gates` passed 34/34. Remaining Milestone 4 risks:
- `:effects/wallet-watch-asset` is emitted but not registered, so "Add to wallet" does nothing until Milestone 4 registers it.
- A successful Core → EVM move still takes the legacy path: it closes the modal and toasts "Transfer submitted.".
- A sent gas top-up waits for the next 30 s balance poll, because the fast poll arrives with Milestone 4.

Milestone 4 (2026-09-30) added the submit paths without any new view. HyperEVM → Core now sends chain-pinned wallet transactions through the real `wallet_rpc` and the public RPC. The owner's in-flight entry exists before the wallet is asked for anything, a hash is never followed by a plain failure, and every modal write is dropped once the modal stops showing its flow. Core → HyperEVM keeps the modal open on an arriving run, a move left pending is settled by the balance poller, and "Add to wallet" is registered. `npm test` grew from 6626 to 6692 tests with 0 failures. A mutation check confirmed the new tests catch a removed flow-id guard and a removed pre-send chain check. The main remaining risks:
- The explicit EIP-1559 fields and the `chainId` param are still untested against real wallets (Milestone 8's manual check).
- A wallet that never answers its prompt blocks HyperEVM → Core moves until a reload.
- The receipt wait can use up to 60 RPC requests a minute, which is within the 100-per-minute limit for one tab but not across several tabs on one IP.

The Milestone 4 review fixes (2026-09-30) changed no view. Spot re-reads after a move now land only while the owner's Spot is shown, so spectating or selecting a subaccount mid-arrival no longer shows the owner's balances as that account's. Nothing after an accepted `sendAsset` can fail its run, a Core -> HyperEVM runtime error refreshes before a retry, a send answered without a hash stays unresolved, and background receipt reads slow down as a move ages. `npm test` grew from 6692 to 6712 tests with 0 failures, `npm run test:websocket` ran 585 tests with 0 failures, and `npm run gates` passed 34/34. A mutation check that removed both Spot guards failed 6 assertions across the new outcome and runtime tests. Remaining risks:
- A pending transaction that the user replaces or cancels in the wallet, or that is dropped, never gets a receipt: HyperEVM -> Core moves stay blocked until a reload (reads slow to every 2 minutes). A nonce-based check, or a manual "I've checked my wallet" dismiss in Milestone 5, would release it.
- A send the wallet answers without a hash, and a wallet that never answers its prompt, also block HyperEVM -> Core moves until a reload.
- Spot arrival is still judged against the balance when the move was submitted; for a move settled in the background minutes later, an unrelated credit meanwhile can read as "Arrived" early.

Milestone 5 (2026-09-30) put the Transfer on screen. The form and the four run views render from the Milestone 3 view-model with no raw colors, every conditional part is a class-hidden slot, and a run that may be on chain offers only Close. "Try again" now resubmits a failed run's draft, and the funding modal layers above the app's fixed footer, which had covered a mobile sheet's submit. A headless check of every state at 1280x800 and 375x812 against this worktree's build found no horizontal overflow; the gas fix and the sticky submit stay in view when the popover opens from a low anchor at 1280x800 (full browser QA at 375/768/1280/1440 stays with Milestone 8). `npm test` grew from 6712 to 6728 tests with 0 failures, the 8 funding Playwright cases pass against this worktree's build, and `npm run gates` passed 34/34 (7484 tests). Remaining risks:
- The z-index move changes every funding mode's stacking. Deposit, Withdraw and Send pass their Playwright cases but were not otherwise re-checked by eye.
- On a 375x812 sheet opened on a gas-blocked HyperEVM -> Spot draft, the gas fix sits about 5 px under the sticky submit until the sheet scrolls, because From and To stack on phones.
- The mobile sheet follows the MobileSheet artboard's order but not its large centered amount; the form keeps one amount layout at every width.

The Milestone 5 review fixes (2026-09-30) closed 7 majors and 11 minors (two of each were duplicates). A failure whose outcome is unknown now offers only "Back to edit", the form's message sits above its sticky submit and is announced, a finished move never shows a stale draft error, clicking the pressed place keeps the draft and a sent gas top-up, an unknown source says "Checking…" instead of "No linked tokens", and Tab can no longer leave the dialog through a hidden link. `npm test` grew from 6728 to 6749 tests (38143 assertions) with 0 failures, `npm run test:websocket` ran 585 tests with 0 failures, `npm run gates` passed 34/34 (7505 tests; the app build compiles with 0 warnings), and the 8 funding Playwright cases in trade- and portfolio-regressions pass against this worktree's build. A mutation check that reverted four fixes failed 15 assertions, and a headless probe confirmed the message placement, the Tab trap, focus on "Back to edit" and the footer offset in a real browser. Remaining risks:
- `checkVisibility()` needs a recent browser (Chrome 105, Firefox 106, Safari 17.4); older ones fall back to the node's own style, which the view-level fixes (no focusable control in a hidden slot) already cover.
- Focus return after "Back to edit" relies on Replicant reusing the view root node; if a future change keys or re-tags a view root, focus falls back to the dialog's close button (as before), not somewhere wrong.
- `scroll-pb-28` is a fixed 112 px; a footer taller than that (a two-line message on a narrow sheet plus the close note) could still cover the bottom of a focused control.

Milestone 6 (2026-09-30) put HyperEVM balances in the Balances table on `/trade` and `/portfolio`, desktop and mobile. The table keeps its 9 columns: the EVM (and, beside HyperEVM rows, PERPS/SPOT) chip sits in the Coin cell, the Transfer column holds the row's moves, and a filter narrows the rows. Trading equity is provably untouched: `/hyperopen/test/hyperopen/views/account_info/hyperevm_equity_invariance_test.cljs` gives an account a HyperEVM wallet worth over $1.2M and asserts the equity metrics, the shared balance-row memo and the Portfolio summary's Total, Spot and Perps equity are identical with and without it. `npm test` grew from 6749 to 6786 tests (38330 assertions) with 0 failures, and `npm run gates` passed 34/34 (7542 tests, the app build with 0 warnings). A mutation check that leaked HyperEVM rows into the equity metrics, put the raw `:hyperevm` subtree in the /trade slice, dropped the Send guard for HyperEVM rows and let a bad-health token keep its target failed 12 assertions across six tests. A headless probe of this worktree's build at 1440, 1280, 768 and 375 px on `/portfolio` and `/trade` (state seeded in the store, network blocked) found no horizontal page overflow; the header wraps under the search at 375 px and stays one 48 px row from 768 px up. The 11 Playwright cases matching balances, read-only or funding in mobile-, portfolio- and trade-regressions pass against this build. A broader run of trade-regressions, mobile-regressions and account-equity passed 41, skipped 2 and failed 7: one (mobile positions list) passed on rerun, and the other six (icon probe, three vault-route cases, asset-selector rapid scroll, trading-settings toggles) fail identically against a `git archive HEAD` build served the same way, so they predate this feature and depend on the dev server. Remaining risks:
- On `/trade` at 1280 px the account panel (960 px) is narrower than the Balances table's minimum width, so the Transfer column scrolls into view sideways; this was already true of the Repay and Contract columns, and the wider Transfer track (148 px) and chip-time Coin track (116 px) add 80 px to the scroll.
- Mobile move actions are one tap deeper than the MobileSheet artboard (inside the expanded card).
- Full browser QA at every width, the `/trade` tab-switch geometry checks and Playwright coverage of the new controls stay with Milestone 8.

The Milestone 6 review fixes (2026-09-30) closed 2 majors and 11 minors (two minors duplicated others). A row's moves stay inside the `/trade` panel at 1280 px, every disabled move shows its reason as text on touch and keyboard, a subaccount or a pending or failed first read is explained under All, unpriced HyperEVM tokens survive Hide Small Balances, HyperEVM rows lose their muted HyperCore placeholders, and the filter keeps its toggle state in the DOM. `npm test` grew from 6786 to 6794 tests (38369 assertions) with 0 failures, and the 11 balances, read-only and funding Playwright cases in mobile-, portfolio- and trade-regressions pass against this worktree's build. `npm run test:websocket` ran 585 tests with 0 failures, and `npm run gates` passed 34/34 (7550 tests; the app build compiles with 0 warnings). A headless probe confirmed the `/trade` 1280 geometry, the focused tooltip and the mobile reason line in a real browser. A mutation check that restored the 148 px Transfer minimum and the boolean `aria-pressed`, dropped the account-block note trigger and the unavailable reason failed 6 assertions across 5 tests. Remaining risks:
- Where the Transfer column sits at its minimum (`/trade` at 1280 and 1440, `/portfolio` at 1280) the two USDC rows are two lines tall (52 px), so the `/trade` panel shows about one row fewer.
- Under All, the note appears for about a second on every load while the first HyperEVM read is pending, then hides for an account with nothing on HyperEVM; below 1024 px the note sits above the cards, so they shift by one line when it hides.
- A disabled move's desktop tooltip lives inside the rows viewport's scroll box; with the table scrolled so a disabled move sits on the viewport's top or bottom edge, part of the tooltip can be clipped (the reason stays in `aria-describedby`).

Milestone 7 (2026-09-30) put the Portfolio "Where your funds are" strip and the `/trade` "HyperEVM · Not margin" line on screen. Both read one HyperEVM sum built from the Balances rows, so they agree with the table, and neither touches trading equity: Total Equity, the equity metrics and Account Value read the same with and without HyperEVM (pinned in `hyperevm_line_test.cljs`, on top of Milestone 6's invariance test). Unknown HyperEVM data shows "—" or "Loading…", never a HyperCore-only total or $0.00. `npm test` grew from 6794 to 6818 tests (38506 assertions) with 0 failures; lint:namespace-sizes, lint:namespace-boundaries, lint:hiccup and lint:theme-colors pass; the app build compiles with 0 warnings; `npm run gates` passed 34/34 (7573 tests) before the last change (the HyperEVM card's "N unpriced" note, one new test), after which `npm test`, the four lints and the app build were rerun green; a mutation check that let a loading total fall back to Total Equity, disabled Spot <-> HyperEVM only for read-only views, gave read-only views a Move link and dropped the model from the desktop panel's opts failed 20 assertions across 4 tests. A headless probe of this worktree's build (state seeded in the store, network blocked) at 1440, 1280, 768 and 375 px found no horizontal overflow on `/portfolio`; the connector opened Transfer Spot -> HyperEVM with both places pressed; the focused disabled connector showed its tooltip at 1280/1440 and the reason line below `lg`; on `/trade` (1440, 1280, 375 mobile account surface) the line shows, hides while loading and shows the master-only reason for a subaccount, and Move opened HyperEVM -> Spot and returned focus on Escape. Remaining risks:
- An account holding only unpriced HyperEVM tokens shows no `/trade` line (its USD value is not above zero); the strip's HyperEVM card then reads $0.00 with "N tokens, N unpriced".
- The strip is cached with the rest of the page while the chart is hovered, so a HyperEVM read that lands mid-hover shows when the hover ends.
- Below `md` the connector buttons overlap the cards' seams by 8 px; a future card with content centred on its top or bottom edge would sit under them.
- Playwright coverage of the strip and the Move link (cases l and m) and full browser QA stay with Milestone 8.

The Milestone 7 review fixes (2026-09-30) closed 2 majors and 9 minors (two minors overlapped the majors: the label, and HyperCore data treated as zero). The strip's total now names its HyperCore part "Total Equity" like the summary card and notes vault and Earn balances so its cards add up, it shows no figure while the HyperCore snapshot or a HyperEVM chunk is unknown, retries of a failed read no longer flicker, unpriced holdings read "Unpriced" instead of $0.00, dust no longer shows a $0.00 line, and the disabled connector's reason is visible at every width with a tooltip that is anchored, hoverable and dismissible. `npm test` grew from 6818 to 6835 tests (38625 assertions) with 0 failures, `npm run test:websocket` ran 585 tests with 0 failures, and `npm run gates` passed 34/34 (7591 tests; the app build compiles with 0 warnings); lint:hiccup, lint:theme-colors, lint:namespace-sizes, lint:namespace-boundaries, lint:test and lint:docs pass; Tailwind emits every new variant (`group-data-[tooltip-dismissed=true]/connector:!invisible` and the rest). A mutation check that reverted eight fixes (the retry status, HyperCore readiness, the "Total Equity" label, "Unpriced", the dust floor, the price-keyed memo, partial reads and the dismissal class) failed 36 assertions across 17 tests. A headless probe of this worktree's `compile app` build (state seeded twice, network blocked) at 1440, 1280, 768 and 375 px found no horizontal overflow; the strip read "Total Equity $30,799.31" (equal to the summary card), "Includes $500.00 in vaults" for a unified account (Trading account $22,379.13 = the summary's Trading Equity), "—" with "HyperCore balances loading" and "Loading…" cards with the HyperCore snapshot cleared, and "HyperEVM balances unavailable" in the warn tone through a retry; at 1280/1440 the tooltip's top equals the button's bottom, it stayed visible with the pointer on it, Escape hid it on hover and on focus, and a closed tooltip let the pointer through; `/trade` at 1280 and 375 still shows, hides and disables the line as before. Remaining risks:
- The HyperCore readiness check reads the base-dex clearinghouse state and the Spot balances only; an account whose vault or Earn figures load later still shows a known total without them for that moment, as the summary card does.
- The price-keyed memo relies on the HyperCore Spot rows pricing only from spot markets (and a held token's `perp:<NAME>` fallback); a future pricing source outside those would need adding to `price-key`.
- The Escape handlers are DOM-level (an attribute on the group), not app state; a Replicant re-render that replaces the group node clears a dismissal early, which only shows the tooltip again.

Milestone 8, items 1-4 (2026-10-01), put the whole Transfer under deterministic browser coverage. The wallet simulator now signs, sends, switches and adds chains like a wallet, with a request log; the HyperEVM RPC is mocked through `visitRoute` (corrected by the review fixes: specs that skipped `visitRoute` still reached the public RPC, and the page-local guard could not fail; the shared guard below replaces it), and the HyperCore catalog and balance streams are fixed because live 429s and zero streams made the first runs flaky. `/hyperopen/tools/playwright/test/funding-transfer-hyperevm.spec.mjs` passes 22/22 against this worktree's `compile app` build (44/44 over two repeats) and asserts exact values: the PURR `sendAsset` (destination `0x2000…0001`, `token "PURR:0xc1fb593aeffbeb02f85e0308e9956a90"`, `amount "100"`, one exchange call), the HYPE wallet log (`eth_chainId`, switch to 0x3e7, `eth_chainId`, `eth_sendTransaction` with `value 0x8ac7230489e80000`, `gas 0x73c8`, `maxFeePerGas 0x17d78400`, `maxPriorityFeePerGas 0x0`, `chainId 0x3e7`), both USDC calldatas with the deposit estimated after the approve's receipt, the 4902 add-chain params, and the 0.05 HYPE top-up. `npm test` grew from 6835 to 6843 tests (38651 assertions) with 0 failures; `npm run test:playwright-support` passes 15/15; lint:namespace-sizes, lint:namespace-boundaries, lint:hiccup, lint:theme-colors, lint:test and lint:docs pass. The broadened rerun of trade-, portfolio-, mobile-, subaccounts- and staking-regressions and account-equity-* (102 cases) passed 94, skipped 2 and failed 6. Five of them (the optimizer "From holdings" constraints, three vault-route cases, asset-selector rapid scroll) fail identically on a clean HEAD build served the same way. The sixth, the volume-history popover's distance check, is a Milestone 7 layout effect; its check now measures the popover-to-trigger gap and passes. The icon probe and trading-settings toggles, which failed in Milestone 6's run, passed here. Remaining risks:
- Browser QA at 375, 768, 1280 and 1440 (Milestone 8 item 5) is still owed.
- The spec still reaches the live HyperCore API for market data it does not fix (candles, mids, the websocket), so a heavily rate-limited IP can still slow it. (Corrected by the review fixes: it also sent eight user reads live, `subAccounts` included; they are now answered from fixtures and the spec fails on any user read that goes live.)
- The debug exchange simulator's per-type signed-action queues never match (see Surprises), so `subaccounts-regressions.spec.mjs`'s `sendAsset`/`subAccountTransfer` queues may fall through to the real exchange; fixing `post-signed-action!`'s lookup is outside this plan. (Fixed by the review fixes.)
- The volume-history popover now covers its trigger on a 375 px phone with an account shown; whether the shared anchored popover should flip above its anchor is a design call for review.

The Milestone 8 review fixes (2026-10-01) closed 6 majors and 15 minors (three duplicates). The page-local guard, which could never fail, is replaced by one that every spec runs under: all 39 specs import `test` from `/hyperopen/tools/playwright/support/guarded_test.mjs` (`spec_imports.test.mjs` fails one that does not), and its automatic fixture mocks the HyperEVM RPC at the context level for the test's context and every context the test opens, then fails the test on any request to the host that no mock answered, any unknown method and any batch over 20. In a real browser the guard case shows a fresh context reached only by `page.goto` being answered by the context mock, and a request let past the mock by a later page handler being reported although the page has the mock. `chart-custom-range` and `shareable-view-url`, which reached the public RPC before, pass with every request mocked, and the guard never fired in the broadened run. Results:
- `/hyperopen/tools/playwright/test/funding-transfer-hyperevm.spec.mjs`: 26 cases, 26/26, and 52/52 over two repeats (`scratchpad/m8fix-spec-run3.log`).
- `npm run test:playwright-support`: 26/26. `npm test`: 6850 tests, 38669 assertions, 0 failures. `npm run test:websocket`: 587 tests, 0 failures.
- `npm run gates`: 34/34 PASS, 7631 tests, 42157 assertions; the app and the other builds compile with 0 warnings (`scratchpad/m8fix-gates.log`). This answers finding 21: Milestone 8's own record listed no `npm run check`/`gates`/`test:websocket` run.
- A mutation build (no focus reveal on move reasons, no pre-send chain check, no gas top-up busy refusal) failed exactly the four targeted cases.
- Broadened rerun (trade-, portfolio-, mobile-, subaccounts- and staking-regressions, account-equity-*, chart-custom-range, shareable-view-url and the new spec; `scratchpad/m8fix-broad.log`): 140 cases, 129 passed, 2 skipped (env-gated live specs), 9 failed.
  - Six fail on a clean HEAD build too (`scratchpad/m8fix-baseline-run.log`, finding 20): the optimizer "From holdings" constraints case, three vault-route cases, asset-selector rapid scroll and the trading-settings toggles.
  - "mobile positions list clears the fixed bottom nav" (seen as transient in Milestone 6 too) and "asset selector outcome rows use full-width question copy" failed once and passed 2/2 on rerun (`scratchpad/m8fix-recheck-branch.log`).
  - The new low-anchor volume-history case measured before the popover's rows loaded; it now waits for them and passes 2/2.
  - `subaccounts-regressions` passes 8/8 with its per-type exchange queues now answering.
Remaining risks:
- Browser QA at 375, 768, 1280 and 1440 (Milestone 8 item 5) is still owed. It should record the phone volume-history popover, which covers its trigger as it does on HEAD (the clamp is now pinned by a test; flipping above the anchor is a design call).
- The HyperEVM spec's market data (mids, books, candles, the websocket) is still live; only account reads and the catalog are fixed, so a rate-limited IP can still slow it.
- The guard sees requests from browser contexts only; a request a spec makes from Node (`request` fixture or `fetch`) is outside it, and none does today.
- Rejecting unknown wallet methods with 4200 applies to every spec that installs the wallet simulator. The broadened specs that install it (mobile, staking, subaccounts and trade regressions) pass, but a flow that starts reading through the wallet will now fail where it read nil.

Milestone 8 item 5 (2026-10-01) ran the governed browser QA at 375, 768, 1280 and 1440 on `/portfolio`, `/trade` and seven Transfer modal states, against this worktree's build with simulated wallet, exchange and HyperEVM. The matrix is in Artifacts and Notes. It found three FAILs and fixed each with a class-level change:
- the Balances tab header hid the selected tab at 768 px; it now stacks under the strip below lg;
- the run views' route cards measured 3.64:1 text contrast; they lost their 75% opacity;
- MAX and the percent chips showed the browser's default focus outline; they now use the `ho-accent` ring.

`/trade` geometry is identical to a clean HEAD build on all seven account tabs at 1280x800, 1440x900 and 1440x1200, with the chart and order book flush (0 px) and a lower-panel share that grows with height. Results after the fixes:
- New test namespace `transfer_qa_fixes_test.cljs`. `npm test`: 6853 tests, 38693 assertions, 0 failures.
- `npm run gates`: 34/34 PASS, 7634 tests, 42181 assertions (`scratchpad/qa/m8qa-gates.log`).
- `funding-transfer-hyperevm.spec.mjs` plus `account-tab-lazy-module.spec.mjs`: 29/29.
- Mobile-, portfolio- and trade-regressions (81 cases): 75 passed and 6 failed.
  - Five are the known HEAD failures (optimizer "From holdings", three vault routes, asset-selector rapid scroll).
  - The sixth, "outcome market tooltip uses adaptive readable width", passed 2/2 on rerun and passes on HEAD (`m8qa-broad.log`, `m8qa-outcome-{branch,head}.log`).

Remaining risks (for Milestone 9 review and user acceptance):
- From lg up, the Balances header's filter (529 px of actions against 311 px on HEAD) narrows the account tab strip by 218 px while Balances is selected. `/trade` shows 5 tabs at 1280 (HEAD 7) and 6 at 1440 (HEAD 8); `/portfolio` shows 7 at 1280 (HEAD 9) and 8 at 1440 (HEAD 11). The rest are behind the strip's hidden-scrollbar sideways scroll, and the selected tab stays visible. Compact filter labels or a filter row inside the tab would recover it; that is a design call.
- `/trade` 1440 with place chips: the Contract column's explorer icons sit 18 px past the panel and need the rows' sideways scroll (HEAD fits).
- On a 375 px sheet, a toast covers the run views' lower links (and "Try again" when failed) for about 4 s (pre-existing toast-over-modal layering).
- The funds strip appears when an account is shown and pushes the page down once (116 px desktop, 441 px phone). No shift after that.
- The phone volume-history popover covers its trigger, as on HEAD.
- Not yet verified with real wallets: the explicit EIP-1559 fields and `chainId` (unchanged from Milestone 4).

The Milestone 2 review fixes (2026-09-30) changed no UI. The receipt-wait pause is now bounded and never blocks a first read, a `:missing` activation is re-read, failed calls read as unknown rather than zero, and backoff is counted once per poll. `npm test` grew to 6534 tests with 0 failures. `rpc.cljs` is now 497/500 lines, so the next RPC helper must go in a sibling namespace.

Milestone 9 (2026-10-01) fixed the final review's 27 findings (4 majors, 23 minors; 3 minors in part, see "Milestone 9 review fixes" in the Decision Log). The HyperEVM bridge cap on HyperCore -> HyperEVM moves is now trusted only while it is under a minute old and newer than the user's own last send of that token; a confirmed receipt can no longer surface as a retryable failure; Perps -> HyperEVM goes through Spot until its bridge shape is verified live; run headings and the busy gas fix keep keyboard focus off the Close button; a stuck move's copy says how to get out. Results:
- `npm test`: 6887 tests, 38873 assertions, 0 failures (6853 before). `npm run test:websocket`: 587 tests, 0 failures.
- `npm run gates`: 34/34 PASS, 7668 tests, 42361 assertions (`scratchpad/m9/gates.log`). The app, portfolio and worker builds compile with 0 warnings; the `:test` build keeps its 2 pre-existing warnings (a `range` redefinition and an undeclared var in two HEAD test namespaces), and Milestone 9 removed a third, an inference warning in the new `simulators_evm_test.cljs`.
- `/hyperopen/tools/playwright/test/funding-transfer-hyperevm.spec.mjs`: 27 cases, 54/54 over two repeats against this worktree's `compile app` build on port 8093 (`scratchpad/m9/pw-spec-run2.log`). The first run failed three cases on test mechanics (Playwright treats `aria-disabled` as not enabled; a pointer target in the clipped part of a tooltip, see Surprises); after those assertions were corrected they passed 4/4, then 54/54.
- The funding, balances, transfer, read-only, spectate and subaccount cases of trade-, portfolio-, mobile-, subaccounts- and staking-regressions, account-equity-* and account-tab-lazy-module (34 cases, the live venue-parity spec excluded): 33 passed; `account-equity-classic-named-dex` "reports the aggregate account rather than the empty base dex" failed once on a $0.70 account-value difference and passed 2/2 on rerun (`scratchpad/m9/pw-broad-subset.log`, `pw-named-dex-rerun.log`). None of the cases known to fail on a clean HEAD build is in this subset.
- Release `main` gzip: 719,156 bytes against 676,855 for a clean HEAD build (+42,301); see Surprises.

### Retrospective for the whole feature (Milestones 0-9)

What shipped, behind no flag:
- **One Transfer surface.** The funding modal's Transfer mode moves funds between Perps, Spot and HyperEVM: segmented From/To places with visible disabled reasons, a native-radio asset list of every linked token the source holds (most valuable first), MAX and 25/50/75% floored to each route's precision and capped by the bridge, a USD estimate, before/after cards, the locked destination, fee, arrival and network rows, and a sticky submit whose label carries the amount. The legacy Perps <-> Spot request is byte-identical.
- **HyperCore -> HyperEVM** for every linked Spot token and HYPE: one `sendAsset` to the token's system address, the modal staying open on "Arriving…" until the HyperEVM balance shows the amount, then "Arrived in N seconds", plus "Add to wallet".
- **HyperEVM -> HyperCore Spot** for native HYPE (to 0x2222…), linked ERC-20s (to their system address) and USDC (approve, then Circle's CoreDepositWallet `deposit`), as chain-pinned wallet transactions with explicit EIP-1559 fees, step-by-step progress, failed, pending and success views, and a one-click 0.05 HYPE gas top-up that keeps the draft.
- **Fund safety.** Bridge solvency and token health are hard gates (SIX, NAV and FUNT's empty sides, HOPE's `decimals()` mismatch, JOFF's reverting `balanceOf`), with capacity that must be current in both directions; a pre-signing invariant on every Transfer and Send request (own system address, master account, Spot in and out, Core precision, catalog kind, recipient, units, chain 999); an in-flight entry written before the wallet is asked anything, so no second HyperEVM -> Core move can start while one may be live; a flow id on every modal write; no plain failure after a hash; background settlement of pending moves.
- **Balances.** HyperEVM rows with an EVM chip, gas-reserve note and contract link; per-row moves with visible disabled reasons; an All / HyperCore / HyperEVM filter; empty states and a note that never claims "nothing" for an unread or partial read. Trading equity, margin and the Portfolio summary are provably unchanged by HyperEVM balances.
- **Portfolio and Trade.** The "Where your funds are" strip (total = Total Equity + HyperEVM, "—" while either is unknown) with preset connectors, and the `/trade` "HyperEVM · Not margin" line with its Move link; the Perps <-> Spot openers read "Transfer".
- **Reads.** One HyperEVM RPC batch per shown address per poll (balances, gas price and bridge health together), 30 s while a surface is up, 4 s for a minute after a move, rate-limit backoff 30/60/120 s, and a debug kill switch.
- **Test seams.** A wallet simulator that signs, sends, switches and logs; production-shaped HyperEVM RPC and HyperCore fixtures; and a guard every Playwright spec runs under that fails a test on any request to the public HyperEVM RPC that no mock answered.

Deviations from the approved design and the original plan:
- Perps -> HyperEVM was built (Milestones 3-6) and withdrawn in Milestone 9 (unverified bridge shape); USDC goes Perps -> Spot -> HyperEVM.
- No separate row quick-move popover: a row's move opens the same Transfer modal, anchored (Decision Log).
- The Balances table keeps 9 columns (chips in the Coin cell, no "Where" column); mobile moves sit in the expanded card.
- The strip's total sub-line reads "Total Equity", not the artboard's "Trading equity" (Milestone 7 review).
- The run views' route cards lost the artboard's 75% opacity for contrast; the funding modal layers moved above the app's fixed footer (all funding modes).
- "Try again" on a failure whose outcome is unknown is not offered; the network retry is "Try switching again".
- Toasts carry a run's outcome only once the modal stops showing it.
- The HyperEVM -> Core in-flight entry is written at flow start, not when the first hash returns (stricter than planned).

Remaining risks and owed work (for user acceptance):
- **Real wallets are unverified.** Every HyperEVM transaction sends explicit `gas`, `maxFeePerGas` and `maxPriorityFeePerGas "0x0"` and a `chainId` param, and the switch is verified by re-reading the chain. None of this has been tried with a real MetaMask, Rabby or mobile wallet; the simulator only proves the requests. A wallet that rejects the explicit fields or the `chainId` param would fail every HyperEVM -> Core move (safely, before anything is sent). A manual check with small amounts is owed before release.
- **Perps -> HyperEVM** needs one small live transfer (ledger shape and HyperEVM credit recorded) before it can be re-enabled.
- **In-flight state is not persisted.** `[:hyperevm :in-flight]` lives in app-db only: a reload clears it, so after a reload the app no longer knows a HyperEVM -> Core move was pending (the in-flight copy tells users when a reload is the way out: a wallet prompt left unanswered, or a transaction the wallet shows finished, replaced or cancelled). A replaced, dropped or never-answered transaction blocks further HyperEVM -> Core moves until a reload.
- **Exception deadlines were renewed.** Milestone 0 moved the 11 size and boundary exceptions that expired on 2026-09-30 (5 in `/hyperopen/dev/namespace_size_exceptions.edn`, 6 in `/hyperopen/dev/namespace_boundary_exceptions.edn`) to 2026-12-31, each with the note "Renewed 2026-09-30 so gates stay green through the HyperEVM transfer branch; the retirement work is still owed." All 106 size and 6 boundary entries now retire on 2026-12-31; `npm run check` fails on 2027-01-01 unless that work is done or the dates move again.
- **Bundle.** Release `main` grows by 42,301 gzip bytes (6.2%); the advisory budget was already exceeded on HEAD. Moving the HyperEVM runtime and Transfer domain to a lazy module is a separate project.
- **Layout.** Between 768 and 1023 px the account tab header is 48 px taller while Balances is selected than on any other tab; from lg up the Balances actions still take 459 px (HEAD 311), so the `/trade` tab strip is 148 px narrower than HEAD while Balances is selected. A disabled move's tooltip on a short table can be clipped at its far end. The funds strip pushes the page down once when an account first shows (116 px desktop, 441 px phone). `/trade` 1440 with place chips puts the Contract icons 18 px past the panel.
- **Reads and limits.** The public RPC allows 100 requests a minute per IP; several tabs, or a receipt wait plus fast polls, can still hit it (backoff and the receipt wait's own backoff limit the damage, and unknown data blocks rather than guesses). A HyperCore -> HyperEVM draft opened while a receipt wait pauses polling shows "Checking the bridge balance…" for up to three minutes. The first open of a HyperEVM -> Spot preset can land on an asset whose HyperCore bridge is empty (that reading is fetched on selection).
- **Repo-wide.** About twenty views outside the funding modal still spell `:tab-index`, so their elements are not focusable as intended; only the funding modal is guarded by a test.
- **Process.** Nothing is committed; the plan stays in `active/` until the user accepts and merges.

## Context and Orientation

Hyperopen is a ClojureScript single-page app. It is built with shadow-cljs and renders with Replicant, a hiccup renderer: views are vectors like `[:div {:class ["a" "b"]} child]`. Actions dispatch through Nexus. Every user interaction is a vector such as `[:actions/open-funding-transfer-modal anchor data-role context]` handled by a pure function that returns a vector of effects, such as `[[:effects/save path value] [:effects/api-submit-funding-transfer request]]`. Effects are the only place side effects happen. The app state is one atom called "app-db" (`hyperopen.system/store`).

Some terms used below:

- **HyperCore**: Hyperliquid's exchange ledger. Its spot balances arrive by REST in `[:spot :clearinghouse-state :balances]` as rows `{:coin "HYPE" :token 150 :total "412.08" :hold "0"}`. `:token` is an integer token index.
- **spotMeta**: the Hyperliquid info response describing spot tokens. It is stored verbatim at `[:spot :meta]`. Each entry of `(:tokens spot-meta)` looks like `{:name "PURR" :index 1 :tokenId "0xc1fb593aeffbeb02f85e0308e9956a90" :szDecimals 0 :weiDecimals 5 :evmContract {:address "0x9b49…b44e" :evm_extra_wei_decimals 13}}`. The info client keywordizes JSON and keeps the snake_case key `:evm_extra_wei_decimals`.
- **Linked token**: a spot token whose `:evmContract` is non-nil, plus HYPE, which is native on HyperEVM even though mainnet `spotMeta` reports `evmContract null`.
- **System address**: the HyperEVM address that bridges a token.
  - For index `i` it is `"0x20"` followed by `i` in lowercase hex, left-padded with zeros to 38 hex digits. Index 1 gives `0x2000000000000000000000000000000000000001`, and index 0 (USDC) gives `0x2000000000000000000000000000000000000000`.
  - HYPE's is `0x2222222222222222222222222222222222222222`.
  - Sending a HyperCore token to its system address with the exchange action `sendAsset` credits it on HyperEVM at the same address in the next EVM block.
  - Sending an ERC-20 (or native HYPE) to the system address on HyperEVM credits the sender's HyperCore spot balance.
- **Wire token id**: the string Hyperliquid's `sendAsset` needs in `:token`, formed as `NAME:tokenId`, e.g. `HYPE:0x0d01dc56dcaaca66ad901c959b4011ec`. Bare symbols and bare indexes are rejected live. `/hyperopen/src/hyperopen/funding/domain/spot_tokens.cljs` owns this join (`resolve-with`, `usdc-wire-token-id`).
- **EVM decimals**: an amount of `a` tokens on HyperCore equals `a × 10^(weiDecimals + evm_extra_wei_decimals)` base units on HyperEVM. Native HYPE is special-cased at 18 EVM decimals with 8 Core weiDecimals. When extra > 0, an EVM→Core amount that is not a multiple of `10^extra` base units loses the remainder, so amounts are floored to Core precision, `min(weiDecimals, evmDecimals)` decimal places.
- **CoreDepositWallet**: Circle's contract that bridges native USDC from HyperEVM to HyperCore. EVM→Core for USDC is:
  1. `approve(CoreDepositWallet, units)` on native USDC;
  2. `CoreDepositWallet.deposit(units, destinationDex)` with `destinationDex = 4294967295` for spot.
  A plain USDC transfer to `0x2000…0000` is not credited, and tokens sent directly to the CoreDepositWallet are lost.
- **Effect-order contract**: `/hyperopen/src/hyperopen/runtime/effect_order_contract.cljs` declares, for selected "covered" actions, which heavy effects they may emit and in what order. It is mirrored in Lean at `/hyperopen/spec/lean/Hyperopen/Formal/EffectOrderContract.lean`, and its committed test vectors `/hyperopen/test/hyperopen/formal/effect_order_contract_vectors.cljs` are generated by `bb tools/formal.clj sync --surface effect-order-contract`. Actions not listed in `effect-order-policy-required-action-ids` of their registration file are uncovered and need none of this.
- **Memoized trade-view slices**: `/hyperopen/src/hyperopen/views/trade_view.cljs` renders panels from `select-keys` subsets of app-db compared with `=`. A new top-level app-db key that a panel reads must be added to that panel's key vector, or the panel never repaints when the key changes. See the 2026-08-21 ExecPlan referenced above.

Files this feature touches today, and their state:

- **Funding modal state and commands.** The funding modal is one map at `[:funding-ui :modal]`. Its defaults live in `/hyperopen/src/hyperopen/funding/application/modal_state.cljs` (`default-funding-modal-state`, `normalize-modal-state`). The transfer keys are `:to-perp?` (default true), `:transfer-dex`, `:transfer-destination-address` and `:transfer-from-subaccount`. Commands live in `/hyperopen/src/hyperopen/funding/application/modal_commands.cljs` (505 lines, cap 505 since Milestone 3): the legacy `enter-funding-transfer-amount` (326), `set-funding-amount-to-max` (376) and `submit-funding-transfer` (423). Every Transfer-mode command lives in `/hyperopen/src/hyperopen/funding/application/transfer_commands.cljs` (441 lines at Milestone 9, no exception): open, places, asset, direction, amount entry, MAX, percent, submit, gas top-up, run controls, and the HyperCore read refresh (`capacity-refresh-due?`, `capacity-refresh-index`). It wraps the legacy commands for the Perps <-> Spot route. New transfer commands go there.
- **Composition seam and facade.** `/hyperopen/src/hyperopen/funding/application/modal_actions.cljs` injects domain functions into commands (`command-deps`, 143-177) and into the view-model (98-133). Its `open-funding-transfer-modal` wrapper calls `transfer-commands/open-funding-transfer-modal`. `/hyperopen/src/hyperopen/funding/actions.cljs` is the public facade.
- **Domain.** `/hyperopen/src/hyperopen/funding/domain/policy.cljs` is a facade over domain functions. Repointing its `transfer-preview`, `transfer-max-amount` and `preview` defs (lines 25, 35, 39) re-routes both commands and the view-model with no edit to `modal_commands.cljs`. `/hyperopen/src/hyperopen/funding/domain/preview.cljs` builds today's `usdClassTransfer`/`sendAsset` request. It must not require any new route-dispatch namespace, because that would be a cycle.
- **View-model.** `/hyperopen/src/hyperopen/funding/application/modal_vm.cljs` is a pipeline of steps under `modal_vm/`. `modal_vm/transfer.cljs` (`with-transfer-context`) builds `:transfer-vm` in Transfer mode and an idle shape otherwise, with `modal_vm/transfer_details.cljs` for summary rows, the blocked card and the run. `presentation.cljs:88` titles the modal "Transfer", and `models.cljs:55` (`transfer-model`) adds the submit actions.
- **VM contract.** `/hyperopen/src/hyperopen/schema/funding_modal_contracts.cljs` (776 lines, cap 776) is an exact-keys spec for the VM. Since Milestone 0, the `:transfer` submap specs live in `/hyperopen/src/hyperopen/schema/funding_modal_transfer_contracts.cljs` (40 lines, no exception), which the parent requires. The one exception is the `:funding-modal-vm.transfer/actions` alias, which stays in the parent at line 616 (see the Decision Log). Since Milestone 3 `:transfer` must have exactly 15 keys: `:to-perp? :route :from-options :to-options :swap-action :asset :balances :destination :summary :usd-estimate :percent-actions :blocked :evm :amount :actions` (`required-transfer-keys` in the child, 268 lines at Milestone 9; a balance side also carries `:available` since then). The whole VM is asserted in `/hyperopen/test/hyperopen/funding/application/modal_vm_test.cljs` and `/hyperopen/test/hyperopen/schema/contracts_test.cljs`. The transfer submap's rejections are asserted in `/hyperopen/test/hyperopen/schema/funding_modal_transfer_contracts_test.cljs`.
- **Submit.** `/hyperopen/src/hyperopen/funding/application/submit_effects.cljs` `api-submit-funding-transfer!` branches by purpose and action type: the gas top-up, `hyperEvmToCore` and `:core->evm` `sendAsset` go to `/hyperopen/src/hyperopen/funding/application/hyperevm_transfer_effects.cljs` (Milestone 4); the legacy path picks a signer by `(:type action)`, closes the modal on success, toasts "Transfer submitted." and refreshes the effective account. `/hyperopen/src/hyperopen/funding/effects.cljs` wraps it with `:or` defaults, including the HyperEVM collaborators from `/hyperopen/src/hyperopen/funding/effects/hyperevm_runtime.cljs`. `/hyperopen/src/hyperopen/runtime/effect_adapters/funding.cljs` injects dispatch, toasts, timers and error formatters.
- **Wallet transactions.** `/hyperopen/src/hyperopen/funding/infrastructure/wallet_rpc.cljs` handles EIP-1193 chain switch/add (`ensure-wallet-chain!`), receipts and send. Its `wallet-add-chain-params` hard-codes the ETH native currency, its receipt messages say "Deposit", and `send-and-confirm-evm-transaction!` always sends `:data`. `/hyperopen/src/hyperopen/funding/infrastructure/erc20_rpc.cljs` hand-encodes `transfer`, `approve`, `balanceOf` and `allowance`. Its readers go through the wallet provider and so read whatever chain the wallet is on. `/hyperopen/src/hyperopen/funding/application/deposit_submit.cljs` is the template for approve-then-send orchestration.
- **Balances table.**
  - `/hyperopen/src/hyperopen/views/account_info/projections/balances.cljs` (499 lines, no exception) builds HyperCore rows. Those rows are memoized in `/hyperopen/src/hyperopen/views/account_info/derived_cache.cljs` and shared with the equity metrics.
  - `/hyperopen/src/hyperopen/views/account_info/vm.cljs:417` applies `balances-staking/with-unstaking-hype`. That function's `hype-row?` matches any row whose coin is HYPE, so EVM rows must be appended after it.
  - `/hyperopen/src/hyperopen/views/account_info/tabs/balances.cljs` filters, sorts and renders. `tabs/balances/{shared,desktop,mobile}.cljs` hold row actions. `send-enabled?` and `transfer-enabled?` in `shared.cljs` must reject EVM rows.
  - `/hyperopen/src/hyperopen/views/account_info/tab_actions.cljs` (498 lines since Milestone 8 item 5, no exception) holds the header toolbar.
- **Portfolio.**
  - `/hyperopen/src/hyperopen/views/portfolio/header.cljs:7-31` has the "Perps ↔ Spot" action.
  - `/hyperopen/src/hyperopen/views/portfolio_view.cljs` `build-portfolio-view-sections` assembles the page.
  - `/hyperopen/src/hyperopen/views/portfolio/vm.cljs` is at its 535-line cap; do not add to it.
- **Trade page.**
  - `/hyperopen/src/hyperopen/views/account_equity/funding_actions.cljs:58` has the "Perps <-> Spot" label.
  - `/hyperopen/src/hyperopen/views/account_equity/panels.cljs` renders the equity panels. `unified-isolated-notional-note` (55-71) shows the always-present hidden-slot idiom.
  - The trade view slices at `/hyperopen/src/hyperopen/views/trade_view.cljs:31-56`.
- **Identity.** `/hyperopen/src/hyperopen/account/context.cljs` is the canonical owner of identity (`owner-address`, `effective-account-address`, `spectate-mode-active?`, `exchange-vault-address`, `inspected-account-read-only?`, `mutations-blocked-message`). The Account boundary forbids keying read-only behavior off `[:wallet :address]` directly.
- **Runtime registration.** New actions and effects are registered in `/hyperopen/src/hyperopen/schema/runtime_registration/<feature>.cljs`, concatenated in `/hyperopen/src/hyperopen/schema/runtime_registration_catalog.cljs`. Argument specs live in `/hyperopen/src/hyperopen/schema/contracts/action_args.cljs` (744 lines, cap 744 since Milestone 2) and `effect_args.cljs`. The funding modal action specs live in `/hyperopen/src/hyperopen/schema/contracts/funding_action_args.cljs` (58 lines, no exception) as `funding-action-args-spec-by-id`, which `action-args-spec-by-id` merges in. Action handlers are wired through `/hyperopen/src/hyperopen/runtime/collaborators/order.cljs` (funding actions) and `/hyperopen/src/hyperopen/app/actions.cljs`. Effect handlers go through `/hyperopen/src/hyperopen/runtime/collaborators.cljs` (`:api` sub-map) and `/hyperopen/src/hyperopen/app/effects.cljs` overrides. A handler key under two different sub-map paths throws "Duplicate runtime handler key".
- **Test simulators.** `/hyperopen/src/hyperopen/telemetry/console_preload/simulators.cljs` is the debug wallet simulator that Playwright uses. It handles only accounts, chainId, typed-data signing and switch-chain; every other method resolves `null`.
- **CSP.** `/hyperopen/tools/release-assets/security_headers.mjs:38-50` (`DOCUMENT_CONNECT_SRC`) is the release Content Security Policy. It does not allow `https://rpc.hyperliquid.xyz`. The dev server sends no CSP, so a missing entry only fails in production.

Size caps that bite (from `/hyperopen/dev/namespace_size_exceptions.edn`, 500-line default; sizes refreshed at the end of Milestone 9, 2026-10-01; every `retire-by` is 2026-12-31):

- At their cap:
  - `modal_commands.cljs` 505/505 (Milestone 3)
  - `funding_modal_contracts.cljs` 776/776
  - `action_args.cljs` 744/744 (Milestone 2)
  - `effect_order_contract.cljs` 750/750 (Milestone 3)
  - `effect_adapters.cljs` 648/650
  - `telemetry/console_preload.cljs` 552/552 (Milestones 2 and 8)
  - `views/portfolio/vm.cljs` 535/535
  - `projections/balances.cljs` 499/500
- Near their cap:
  - `app_defaults.cljs` 505/515 (Milestone 2)
  - `hyperevm/infrastructure/rpc.cljs` 500/500 (Milestone 9's receipt backoff, no exception). New RPC helpers go in a sibling namespace, for example `hyperopen.hyperevm.infrastructure.rpc-tx`.
  - `tab_actions.cljs` 498/500 (Milestone 8 item 5)
- Test files:
  - `test/hyperopen/funding/actions_test.cljs` 1290/1290
  - `test/hyperopen/views/funding_modal_test.cljs` 556/558
  - `test/hyperopen/views/account_equity_view_test.cljs` 619/620
  - `test/hyperopen/views/account_info/vm_test.cljs` 558/580

New code and tests go into new namespaces. Shrinking an excepted file to 500 lines or fewer requires deleting its exception entry, or the checker fails on a stale exception.

Other gates that bite:

- `npm test` fails any `text-[10|11|13|14|15px]` under `views/**`; use `text-xs`, which is pinned to 12px.
- `npm run lint:hiccup` requires `:class` as a vector of single-token strings, keyword `:style` keys, and no `[:<>` fragments. Replicant has none, and they render as nothing.
- `npm run lint:theme-colors` gives every new file a baseline of zero raw `#hex`/`rgb()` literals, including in comments. Use the `ho-*` token utilities:
  - SPOT chip: `bg-ho-accent-soft text-ho-accent-bright`
  - PERPS chip: `bg-ho-surface-raised text-ho-text/80` (the Milestone 6 review raised it from `text-trading-text-secondary`, 4.38:1, for AA contrast)
  - EVM chip: `bg-ho-info/15 text-ho-info`
  - warnings: `bg-ho-warn/10 border-ho-warn/40 text-ho-warn`
  - primary: `bg-ho-accent text-ho-bg-deep`
- Conditional children must be always-present slots hidden with a class, never `nil` holes next to keyed siblings. Nil holes have frozen or misaligned renders in this app.

## Plan of Work

The work proceeds in ten milestones. Each milestone ends with its narrow tests green and `npm test` passing. Milestones 1 and 2 are pure additions. Milestone 0 changes no behavior.

### Milestone 0: gate hygiene and size-cap extractions

1. In `/hyperopen/dev/namespace_size_exceptions.edn` and `/hyperopen/dev/namespace_boundary_exceptions.edn`, change each `:retire-by "2026-09-30"` to `"2026-12-31"` and append to its `:reason`: " Renewed 2026-09-30 so gates stay green through the HyperEVM transfer branch; the retirement work is still owed."
2. Split funding action-arg specs out of `action_args.cljs`. Create `/hyperopen/src/hyperopen/schema/contracts/funding_action_args.cljs` holding the funding specs (today at `action_args.cljs:69-73` and the funding entries at roughly 639-667) and a map `funding-action-args-spec-by-id`. Merge it into `action-args-spec-by-id` with `merge`. Set the exception's `:max-lines` to the new line count and keep the renewed retire-by.
3. Move the transfer submap specs (`required-transfer-amount-keys`, `required-transfer-keys`, and `:funding-modal-vm/transfer` with its sub-specs) out of `funding_modal_contracts.cljs` into `/hyperopen/src/hyperopen/schema/funding_modal_transfer_contracts.cljs`, which `funding_modal_contracts.cljs` requires. Lower that exception's `:max-lines` to the new count.
4. Move `open-funding-transfer-modal` out of `modal_commands.cljs` into a new `/hyperopen/src/hyperopen/funding/application/transfer_commands.cljs`, and rewire `modal_actions.cljs` to call it. Lower the `modal_commands` cap accordingly.
5. In `/hyperopen/test/hyperopen/funding/actions_test.cljs`, replace the literal `expected-open-modal` map (lines 17-52) with `(merge (funding-actions/default-funding-modal-state) {...})` so that new default keys stop breaking it, and lower its cap.
6. Run `npm test` and `npm run lint:namespace-sizes`. Behavior is unchanged.

### Milestone 1: HyperEVM pure domain, read client, CSP

Create a new non-view bounded context under `/hyperopen/src/hyperopen/hyperevm/`. It is non-view because the funding view-model, which may not import views, needs it.

1. `hyperopen.hyperevm.domain.chain` (`chain.cljs`) holds constants. `mainnet` is:

        {:chain-id "0x3e7" :chain-id-decimal 999 :chain-name "HyperEVM"
         :native-currency {:name "HYPE" :symbol "HYPE" :decimals 18}
         :rpc-url "https://rpc.hyperliquid.xyz/evm"
         :explorer-url "https://hyperevmscan.io"
         :multicall3-address "0xca11bde05977b3631167028862be2a173976ca11"
         :hype-system-address "0x2222222222222222222222222222222222222222"
         :usdc-token-address "0xb88339cb7199b77e23db6e890353e22632ba630f"
         :usdc-core-deposit-wallet "0x6b9e773128f453f5c2c60935ee2de2cbc5390a24"
         :core->evm-system-gas 200000
         :core-deposit-destination-dex {:perps 0 :spot 4294967295}}

   Also define a `testnet` map (0x3e6, `https://rpc.hyperliquid-testnet.xyz/evm`, USDC `0x2b3370ee501b4a559b57d449569354196457d8ab`, CoreDepositWallet `0x0b80659a4076e9e93c7dbe0f10675a16a3e5c206`) that is used only by fixtures. Chain ids must be lowercase hex, because `ensure-wallet-chain!` compares against normalized lowercase.

2. `hyperopen.hyperevm.domain.units` holds BigInt amount math. Amounts are always decimal strings outside this namespace.
   - `parse-units [amount-text decimals] -> js/BigInt|nil` floors extra fractional digits.
   - `format-units [bigint decimals] -> string` trims trailing zeros.
   - `units-text` converts BigInt to a decimal string.
   - `floor-to-decimals [amount-text decimals]`.
   Never store BigInt in app-db or in action/effect arguments: telemetry `JSON.stringify` throws on BigInt, and wallets reject it.

3. `hyperopen.hyperevm.domain.tokens` holds the linked-token catalog.
   - `linked-tokens [spot-meta]` returns, keyed and sorted by `:index`, maps of the form

         {:index :name :token-id :wire-id :wei-decimals :evm-decimals :core-precision :kind :erc20-address :spender :system-address :verified?}

   - `:kind` is one of:
     - `:native`, for HYPE, found by `:name "HYPE"`, never by a hard-coded index, with evm-decimals 18;
     - `:usdc-cdw`, for USDC at index 0. Its `:erc20-address` is the chain's native USDC, its `:spender` is the `evmContract.address` (the CoreDepositWallet), and its evm-decimals are 6;
     - `:erc20`, for everything else.
   - `:system-address` uses the rule above, with HYPE → `0x2222…`. `:wire-id` is `(str name ":" tokenId)`. `:verified?` is true for HYPE, PURR and USDC.
   - Exclude tokens whose computed evm-decimals are negative or above 36.
   - Memoize `linked-tokens` on `identical?` spot-meta.
   - Also expose `token-by-index`, `token-by-name`, and `system-address [index]`.

4. `hyperopen.hyperevm.domain.abi` holds hand-rolled ABI encoding with js/BigInt uint256. Selectors:
   - `balanceOf` 0x70a08231
   - `transfer` 0xa9059cbb
   - `approve` 0x095ea7b3
   - `allowance` 0xdd62ed3e
   - `deposit(uint256,uint32)` 0x2b2dfd2c
   - `getEthBalance(address)` 0x4d2301cc
   - `aggregate3((address,bool,bytes)[])` 0x82ad56cb

   Provide `encode-transfer`, `encode-approve`, `encode-core-deposit`, `encode-balance-of`, `encode-get-eth-balance`, `encode-aggregate3 [[{:target :allow-failure? :call-data}]]`, `decode-aggregate3 [hex] -> [{:success? :return-data}]` and `decode-uint256 [hex] -> BigInt`. Validate addresses (`0x` plus 40 hex) before encoding and return nil on invalid input, never throwing.

   Golden vectors for tests:
   - `deposit(1000000, 4294967295)` = `0x2b2dfd2c` + `00…0f4240` (64 hex) + `00…ffffffff` (64 hex).
   - `balanceOf(0x2222…2222)` = `0x70a08231` + 24 zeros + `2222…2222`.

5. `hyperopen.hyperevm.domain.fees`:
   - `core->evm-fee-hype [gas-price-wei-text token]` = ceil-to-8-decimals(200000 × gasPrice / 1e18). It is "0" for HYPE.
   - `evm-gas-limit [kind]` falls back to a table: native 30000, erc20 60000, usdc approve 70000, usdc deposit 90000.
   - `evm-tx-cost-hype [gas-price-text kinds]` = Σ limit × 2 × max(gasPrice, 0.2 gwei).
   - `native-max-reserve-hype` = max(0.001, cost).
   - `evm-gas-status [evm-hype-text needed-text]` returns `:ok`, `:low` or `:none`.

6. `hyperopen.hyperevm.infrastructure.rpc` is a page-side JSON-RPC client over an injected `fetch-fn` (default `js/fetch`) with an AbortController timeout of 10 s.
   - `rpc-request!` and `rpc-batch!` (batch at most 20).
   - `read-balances! [{:owner :tokens :chain}]`:
     - sends ONE batch of two requests: an `eth_call` to Multicall3 `aggregate3`, with `getEthBalance(owner)` first and then `balanceOf(owner)` on each `:erc20-address` with `allowFailure true`, plus `eth_gasPrice`;
     - chunks tokens at 150 per aggregate call;
     - returns `{:native-wei "…" :token-units {index "…"} :gas-price-wei "…"}` as decimal strings and omits zero balances.
   - `estimate-gas!`, `get-transaction-receipt!` and `wait-for-receipt! [hash {:poll-ms 1000 :timeout-ms 180000 :set-timeout-fn :now-ms-fn}]`.
   - Errors reject with `ex-info` carrying `:code` and `:http-status`.
   - Surface HTTP 429 through `hyperopen.api.info-client` `set-on-rate-limit!` if convenient, otherwise as an error category.

7. CSP: add `"https://rpc.hyperliquid.xyz"` to `DOCUMENT_CONNECT_SRC` in `/hyperopen/tools/release-assets/security_headers.mjs`. Add the test "release CSP permits the HyperEVM RPC origin" next to the price-history test (around line 109) in `/hyperopen/tools/release-assets/generate_release_artifacts.test.mjs`.

8. Tests go in new namespaces:
   - `/hyperopen/test/hyperopen/hyperevm/domain/{chain,units,tokens,abi,fees}_test.cljs`. Fixtures are copied from REAL mainnet `spotMeta` rows with a dated provenance comment: HYPE with `evmContract nil`, USDC with the CoreDepositWallet, PURR +13, UBTC -2, and FUNT with index ≠ position.
   - `/hyperopen/test/hyperopen/hyperevm/infrastructure/rpc_test.cljs`, with a stubbed fetch.
   - Cross-check `system-address` against `hyperopen.domain.account-ledger.derive/token-system-address?`.

### Plan revision note (2026-09-30, after the pre-implementation critique)

Three adversarial reviewers critiqued the plan before Milestone 2: fund safety and protocol, architecture and contracts, and UX and tests. They found one blocker and about twelve major gaps. The milestones below were rewritten to absorb them. The main changes:

- **Bridge solvency is a hard gate.** A system address that cannot pay out now blocks the move.
- **In-flight transactions are tracked outside the modal.** This prevents a double send.
- **Every `eth_sendTransaction` is chain-pinned.**
- **Core account activation is checked** before EVM→Core.
- **Amounts and MAX follow each route's precision** instead of the USDC formatters.
- **Failure, pending, unknown and arrival states are specified.**
- **Accessibility is specified.** Disabled reasons are visible text, and progress is announced.
- **Milestones are reordered.** The modal domain now lands before the Balances table, whose row actions depend on it.

The original Milestones 3–5 are now Milestones 3–6 in this order: modal domain, then submit, then views, then Balances. The Decision Log records each change.

### Milestone 2: HyperEVM state, poller, bridge health, gating, ledger labels

1. **App-db defaults.** In `/hyperopen/src/hyperopen/state/app_defaults.cljs` (502/515), add:

        :hyperevm {:balances {:by-address {}}
                   :bridge {:evm-system-units {} :core-system-balances {} :token-health {}}
                   :in-flight {}
                   :fast-poll-until-ms nil
                   :wallet-capabilities {}}

   Also add `[:account-info :balances-location-filter] :all`.

   `:by-address` entries are keyed by lowercase address and look like:

        {:status :loading|:ready|:error :stale? bool :requested-at-ms :loaded-at-ms
         :native-wei "…" :token-units {idx "…"} :unread-token-indexes [..]
         :gas-price-wei "…" :error "…" :error-kind kw}

   Values are always decimal strings. `apply-loading` and `apply-error` are stale-while-revalidate: they keep the last good `:native-wei`, `:token-units` and `:gas-price-wei`, and set `:stale? true` on error. `apply-success` treats indexes in `:unread-token-indexes` as unknown and keeps their previous value; it never zeroes them.

   Clear `:balances :by-address` in `/hyperopen/src/hyperopen/startup/runtime.cljs` `reset-account-surface-state`. Never clear `:in-flight`, which must survive account switches.

2. **Bridge health, the Milestone 1 read extended.** This fixes the critique's blocker. Hyperliquid runs no supply or ERC-20 checks. Live, SIX (idx 6) and NAV (idx 1022) have a zero EVM system balance with Core supply outstanding, and FUNT (idx 478) is zero on both sides. JOFF's `balanceOf` reverts, and HOPE's `decimals()` disagrees with spotMeta.
   - Extend `hyperopen.hyperevm.infrastructure.rpc/read-balances!`, or add a sibling `read-bridge-health!`, so the same aggregate batch also reads `balanceOf(systemAddress)` for every linked `:erc20` token. Store it at `[:hyperevm :bridge :evm-system-units idx]`.
   - On a token's first sighting in the session, also call `decimals()` and store `[:hyperevm :bridge :token-health idx] {:decimals-ok? bool :balance-of-ok? bool}`.
   - Native HYPE is always healthy.
   - USDC (`:usdc-cdw`) is served by Circle's contract. Treat it as healthy with unlimited EVM-side payout. Its Core→EVM credit comes from the CoreDepositWallet, not a pre-minted balance.
   - Add a pure `hyperopen.hyperevm.domain.bridge` with:
     - `core->evm-capacity [state token]`: the EVM system units scaled to Core units, or `:unlimited` for HYPE and USDC;
     - `evm->core-capacity [state token]`: from `:core-system-balances`, fetched on demand as below;
     - `movable? [state token]`: false when token health is bad (JOFF, HOPE) or when the token is unknown.
   - EVM→Core Core-side capacity is fetched on demand with `hyperopen.api` `spotClearinghouseState` for `user = systemAddress`, only when an EVM→Core route with an `:erc20` token is selected. HYPE uses `0x2222…`. Store it at `[:hyperevm :bridge :core-system-balances idx] {:amount "…" :loaded-at-ms}`.
   - Add the action `:actions/refresh-hyperevm-bridge-capacity [idx]` and the effect `:effects/fetch-hyperevm-core-bridge-balance [idx address]` through the existing info client, which already rate-limits. The action is uncovered by the effect-order policy.

3. **Pure state functions.** `hyperopen.hyperevm.domain.balances` provides:
   - `entry`, `token-amount-text` and `native-hype-text`, all returning Core-precision strings, with nil meaning unknown;
   - `evm-gas-status`, which returns `:ok|:low|:none|nil`, where nil means unknown;
   - `display-address` (effective account address), `owner-address` and `balance-addresses`;
   - `surface-active?`: true on `/trade*`, on `/portfolio*` except `/portfolio/optimize*`, or while the funding modal is open;
   - `watch-fingerprint`;
   - `refresh-plan [state now-ms {:force?}]`. The freshness skip is 10 s normally and 0 s while `fast-poll-until-ms` is in the future. Polling also pauses while an `[:hyperevm :in-flight]` entry has `:waiting-receipt? true`, to save RPC budget for the receipt wait. Forced post-transfer calls bypass this pause. As amended by the review fixes (Decision Log), the pause lasts at most 180 s from `:submitted-at-ms` and never skips an address that has not been read yet, fast-poll ticks are unforced, and a read still loading is never overtaken by an unforced refresh.
   - the `apply-*` projections.

4. **RPC error model.** Harden rpc.cljs for real failure shapes seen live today:
   - A rate limit arrives as HTTP 200 with a single JSON-RPC error object `{"id":null,"error":{"code":-32005,…}}`, even for a batch.
   - Map a non-array batch reply and code -32005 to `{:kind :rate-limited}`. The poller backs off exponentially (30 s → 60 s → 120 s, reset on success).
   - Add tests for both shapes.

5. **Actions and effects.**
   - `hyperopen.hyperevm.actions/refresh-hyperevm-balances [state opts]` emits `[:effects/fetch-hyperevm-balances plan]`, plus `[:effects/save [:hyperevm :fast-poll-until-ms] …]` when `:fast-poll-ms` is given. The caller supplies `:now-ms` in opts.
   - `hyperopen.hyperevm.effects` implements the fetch.
   - Add `/hyperopen/src/hyperopen/runtime/effect_adapters/hyperevm.cljs`.
   - Handlers are wired through a new `/hyperopen/src/hyperopen/runtime/collaborators/hyperevm.cljs`, whose `action-deps` merge under `:hyperevm` in `runtime-action-deps`, and through the `:api` effect path in `/hyperopen/src/hyperopen/runtime/collaborators.cljs` plus `/hyperopen/src/hyperopen/app/effects.cljs`, following the margin-rec precedent.
   - Add `/hyperopen/src/hyperopen/schema/runtime_registration/hyperevm.cljs` to the catalog, and an arg-spec file `/hyperopen/src/hyperopen/schema/contracts/hyperevm_action_args.cljs` merged into `action-args-spec-by-id` the same way as `funding_action_args`.
   - Effect args go in `effect_args.cljs`.
   - Add identity assertions in `collaborators_test`.
   - None of these actions is effect-order covered.

6. **Poller.** Add `hyperopen.hyperevm.infrastructure.balance-poller/install-hyperevm-balance-poller!`, modeled on `/hyperopen/src/hyperopen/margin_rec/watcher.cljs` and `/hyperopen/src/hyperopen/portfolio/optimizer/infrastructure/working_order_refresh.cljs`.
   - Watching the fingerprint triggers a dispatch, debounced 250 ms.
   - An interval ticks every 30 s while `surface-active?` and the document is visible, and every 4 s with force while fast-polling.
   - Rate-limit backoff as in step 4.
   - Timers are injectable.
   - Install it via `app/bootstrap.cljs` `watchers-deps` and `runtime/bootstrap.cljs` `install-runtime-watchers!`.
   - Provide a debug kill switch, `HYPEROPEN_DEBUG`-reachable through the existing simulators namespace (for example `hyperevm-poller-enabled?` in a `defonce` atom), so Playwright specs can disable it deterministically.

7. **Identity gate.** In `/hyperopen/src/hyperopen/account/context.cljs`, add `hyperevm-moves-blocked-message [state]`:
   - when read-only, return the existing `mutations-blocked-message`;
   - when a subaccount is selected, return "HyperEVM transfers are available for the master account only.";
   - when no wallet is connected, return "Connect your wallet to move funds.";
   - otherwise return nil.

   Also add `core-account-activation-status [state]`. It reads `[:hyperevm :core-account <owner>]` (`:active|:missing|nil`), which is filled by a one-shot `userRole` info request on the first EVM→Core selection. That request goes through `:actions/refresh-hyperevm-bridge-capacity`, or a sibling action and effect in the same registration file.

8. **Ledger labels.** In `/hyperopen/src/hyperopen/domain/account_ledger/derive.cljs`:
   - accept `0x2222…2222` in `token-system-address?`;
   - add `retype` rules for a `spotTransfer` to a system address and for a `send` to or from `0x2222…` or a token system address.

   Test with the live ledger shapes in Artifacts and Notes.

9. **Tests.** Add new namespaces under `/hyperopen/test/hyperopen/hyperevm/` covering balances, bridge, actions, effects and the poller. Also add `/hyperopen/test/hyperopen/account/hyperevm_gate_test.cljs` and extend the ledger derive tests.

### Milestone 3: Transfer modal domain, commands, view-model

1. **Modal state.** Add these defaults to `modal_state.cljs`, and pin each one in the literal of `/hyperopen/test/hyperopen/funding/application/modal_state_test.cljs` and in `/hyperopen/test/hyperopen/core_public_actions_test.cljs` (convert that literal to a merge over the defaults, as Milestone 0 did for actions_test):
   - `:transfer-from nil`
   - `:transfer-to nil`
   - `:transfer-asset nil`
   - `:transfer-evm nil`
   - `:transfer-gas-topup nil`

   Normalize keyword strings in `normalize-modal-state`. Any legacy open must save a map equal to today's plus these nil keys; compare with `dissoc` of the new keys.

2. **Route logic.** Create `/hyperopen/src/hyperopen/funding/domain/transfer_route.cljs`.
   - `transfer-route [modal]` returns `{:from :to :valid? bool}`:
     - when both locations are nil, derive the legacy route from `:to-perp?`;
     - when both are set, valid and distinct, use them;
     - otherwise return `:valid? false`. The preview then blocks with `:invalid-route` instead of falling back.
   - `route-kind` returns one of `:core-internal`, `:core->evm`, `:evm->core`.
   - `location-options [state route asset]` returns labels, a visible disabled reason, and pooled/unified handling. For unified or portfolio-margin accounts, Perps and Spot are one pooled balance, so the Perps option is disabled with "Unified accounts share one balance for Perps and Spot. Use Spot." Standard accounts keep Perps.
     - Perps→HyperEVM is allowed for USDC on standard accounts only, via `sendAsset sourceDex ""`. (Withdrawn in Milestone 9: it is now an unsupported pair, like HyperEVM→Perps, with the reason "Move USDC to Spot first, then Spot → HyperEVM.")
     - HyperEVM→Perps is disabled with "Move to Spot first, then Spot → Perps."
     - HyperEVM is disabled with `hyperevm-moves-blocked-message` when that is non-nil.
   - `asset-options [state route]` lists linked, `movable?` tokens with a balance on the source side, sorted by source balance value where a price is available and then by name. Pricing comes from a non-view resolver (item 3).
   - Unmovable tokens that are held are listed disabled, with the reason "This token's HyperEVM link can't be verified, so it can't be moved here."

3. **Pricing extraction.** Move the pure body of `token-price-usd` and its helpers from `/hyperopen/src/hyperopen/views/account_equity/pricing.cljs` into a new non-view `/hyperopen/src/hyperopen/domain/token_pricing.cljs`, and have the view namespace delegate to it. The funding view-model then gets USD estimates through an injected dependency, without breaking namespace boundaries.

4. **Precision and amounts.** Every route carries a precision: `:core-precision = min(weiDecimals, evmDecimals)`, in both directions. For example, USDC Core→EVM floors to 6 decimals and UBTC floors to 8.
   - `transfer-max-amount*` returns a number for `:core-internal`, so the legacy path is byte-identical. For EVM routes it returns a floored decimal STRING at the route precision. That value is:
     - the source balance;
     - minus the native gas reserve, when the source is HyperEVM HYPE;
     - capped by bridge capacity;
     - for Core sources, using available = total − hold.
   - Move `set-funding-amount-to-max` into `transfer_commands.cljs`, or wrap it, so EVM routes fill that string verbatim. Legacy routes keep `format-usdc-input`.
   - The view-model overrides `:max-display`, `:max-input` and `:symbol` for EVM routes.
   - Add percent chips (25/50/75%) that fill floored fractions of the max.

5. **EVM previews.** Create `/hyperopen/src/hyperopen/funding/domain/evm_transfer_preview.cljs`. It returns `{:ok? :display-message :request :blocked}`, where `:blocked` is `{:code :message :fix?}` and `:code` is one of:

        :read-only :subaccount :no-owner :meta-missing :hyperevm-unavailable :unmovable-token
        :bridge-empty :no-evm-gas :no-core-fee :no-chain-switch :no-provider :core-account-missing
        :in-flight :invalid-route

   - Gas and availability blocks are evaluated as soon as the route and asset are chosen, independent of the amount, to match the TransferNeedsGas design.
   - Unknown EVM data (entry nil, `:loading` with no prior value, or `:error` with no prior value) yields `:hyperevm-unavailable` with the message "Checking HyperEVM balances…" or "HyperEVM balances are unavailable right now." It never yields `:no-evm-gas`.
   - `:core-account-missing`:
     - for a non-USDC EVM→Core move, block with "Your HyperCore account isn't activated yet. Deposit at least 1 USDC to HyperCore first. The first transfer in pays a 1 USDC activation fee.";
     - for USDC, allow amounts greater than 1 and add a summary row "Activation fee: 1 USDC (first transfer only)".
   - `:in-flight`: an unresolved in-flight EVM→Core transaction for this owner blocks a new one with "A HyperEVM transfer is still confirming. Wait for it to finish before starting another." It links to the explorer.

   Request shapes are as in the original plan. Core→EVM is `sendAsset` to the token's `:system-address`. The client-only keys `:route` and `:evm` stay at the top level.

   EVM→Core uses the pseudo action `{:type "hyperEvmToCore" :kind "native"|"erc20"|"usdcCoreDeposit" :tokenIndex :symbol :tokenAddress :spender :recipient :amount :units :destinationDex 4294967295 :chainId "0x3e7"}`. Its `:recipient` comes from the catalog's `:evm->core-recipient`, which is nil for USDC, whose `:kind` is `usdcCoreDeposit`.

   The validation order is:
   1. route validity;
   2. blocked checks;
   3. blank amount → `{:ok? false}` with no message;
   4. not a finite number;
   5. ≤ 0;
   6. more decimals than the route precision (floor and show a notice);
   7. more than the max;
   8. more than bridge capacity.

6. **Pre-signing invariant.** Create `/hyperopen/src/hyperopen/funding/domain/transfer_invariants.cljs` with `check-request [state request] -> nil | error-string`:
   - For a `sendAsset` whose destination starts with `0x20` or equals `0x2222…`, the destination must equal the system address of the token parsed from the wire id, and `0x2222…` is allowed only for the HYPE wire id.
   - For `hyperEvmToCore`, the kind must match the catalog kind for `:tokenIndex`. USDC must be `usdcCoreDeposit`. `:recipient` must equal the catalog's `:evm->core-recipient`. `:units` must equal `parse-units(:amount, evm-decimals)`.

   The submit effect calls it and refuses on a mismatch.

7. **Route-aware dispatch.** Create `/hyperopen/src/hyperopen/funding/domain/transfer_dispatch.cljs` with `transfer-preview*`, `transfer-max-amount*` and `preview*`. `:core-internal` delegates to today's functions unchanged. Repoint the three defs in `policy.cljs` at these. `preview.cljs` must not require this namespace.

8. **Commands.** In `transfer_commands.cljs`, make `mutation-guard-effects` public in modal_commands, or duplicate it, and use `hyperevm-moves-blocked-message` for EVM routes.
   - `open-funding-transfer-modal` also reads context `{:from :to :asset}`.
   - `set-funding-transfer-location [state side loc]`: auto-swap when from equals to; sync `:to-perp?` for a Perps/Spot pair; clear the amount, error, `:transfer-evm` and `:transfer-gas-topup`; re-select the first asset option when the current asset is not eligible on the new source, with the view announcing the change.
   - `swap-funding-transfer-locations`.
   - `select-funding-transfer-asset [state idx]`: clears the amount.
   - `set-funding-transfer-direction`: clears from/to, the asset and the amount.
   - `submit-funding-transfer-gas-topup`: refuses while `:transfer-gas-topup :status` is `:submitting` or `:sent`, or while Spot HYPE available < 0.05. It builds the 0.05 HYPE Spot→HyperEVM `sendAsset` with `:purpose :gas-topup`, and emits `[[:effects/save [:funding-ui :modal :transfer-gas-topup] {:status :submitting}] [:effects/api-submit-funding-transfer request]]`.
   - `reset-funding-transfer-evm`: clears `:transfer-evm` and `:submitting?` and keeps the asset, amount and route. It backs "Back to edit".
   - `retry-funding-transfer-capability`: clears the cached unsupported-switch flag for the current provider.
   - `add-funding-transfer-token-to-wallet`.
   - `submit-funding-transfer` itself refuses when `:submitting?` is already true, or when an in-flight EVM→Core entry exists for an EVM→Core route.

   Register the new actions, their arg specs (in the funding args file), handlers (`runtime/collaborators/order.cljs`) and the facade:
   - `set-funding-transfer-location`
   - `swap-funding-transfer-locations`
   - `select-funding-transfer-asset`
   - `set-funding-transfer-amount-percent`
   - `submit-funding-transfer-gas-topup` (covered: add it to the funding registration's `effect-order-policy-required-action-ids`, give it a policy identical to `:actions/submit-funding-transfer` in `effect_order_contract.cljs` and raise its cap with a reason, add the Lean `policyCorpus` line with heavy ids in sorted order, then run `bb tools/formal.clj sync --surface effect-order-contract` and `verify`)
   - `reset-funding-transfer-evm`
   - `retry-funding-transfer-capability`
   - `add-funding-transfer-token-to-wallet`

9. **View-model.** Create `/hyperopen/src/hyperopen/funding/application/modal_vm/transfer.cljs` (`with-transfer-context`) and insert it before presentation. Add its new injected deps to `modal_actions.cljs` and to `test_support` `base-deps`:
   - `:hyperevm-linked-tokens`
   - `:hyperevm-entry`
   - `:hyperevm-bridge`
   - `:hyperevm-moves-blocked-message`
   - `:token-price-usd`
   - `:wallet-chain-id`

   The transfer VM is exact-keys and is defined in `funding_modal_transfer_contracts.cljs`. Any alias to a parent spec must be registered in the parent, per the Milestone 0 note.

        {:to-perp? bool
         :route {:from kw :to kw :kind kw :valid? bool}
         :from-options [{:id :label :selected? :disabled? :reason :action :data-role}]
         :to-options […]
         :swap-action vec
         :asset {:visible? :options [{:index :symbol :balance-display :selected? :disabled? :reason :action}] :selected {:symbol :notice :explorer-url}}
         :balances {:from {:label :before :after} :to {:label :before :after}}
         :destination {:display str}
         :summary [{:label :value :tone}]
         :usd-estimate str|nil
         :percent-actions [{:label :action}]
         :blocked {:code :message :fix {:label :action :status :status-message :disabled? :reason}}|nil
         :evm {:phase :running|:pending|:failed|:succeeded :flow-id :steps [{:id :label :status :detail}] :step-index :step-count :tx-url :error :arrival :idle|:arriving|:arrived|:slow :result {…} :add-to-wallet? bool}|nil
         :amount {…existing keys…}
         :actions {…existing keys…}}

   Presentation:
   - The `:transfer` title becomes "Transfer".
   - The content kind is one of `:transfer/form`, `:transfer/progress`, `:transfer/pending`, `:transfer/failed` or `:transfer/success`.
   - `status-message` is suppressed when `:blocked` is set.
   - The submit label carries the amount, for example "Move 250 HYPE to HyperEVM". While the wallet chain is not 0x3e7 it reads "Switch network & move 10 HYPE to Spot"; when the chain is already 0x3e7, "Move 10 HYPE to Spot". While running it reads "Waiting for wallet (step 2 of 3)".

10. **Tests.** Add new test namespaces for route, preview (every blocked code, including unknown data never reporting no-gas, MAX floors such as 0.12345678 HYPE → "0.12345678" and USDC 12.34567891 → "12.345678" Core→EVM, and bridge caps), invariants, dispatch, commands and the view-model. Extend `funding_modal_transfer_contracts_test.cljs` and `modal_vm_test.cljs`. `/hyperopen/test/hyperopen/funding/domain/named_dex_transfer_preview_test.cljs` must pass unchanged.

### Milestone 4: Submit paths

1. **Generalize `wallet_rpc.cljs` without changing deposits.**
   - `wallet-add-chain-params` uses `(:native-currency chain-config)` with an ETH default.
   - The post-switch `eth_chainId` verification is opt-in via `(:verify-switch? chain-config)`, which is true only on the HyperEVM config. Deposit flows and the existing exact-call-sequence test are untouched.
   - Detect a nested 4902 (`err.data.originalError.code`).
   - Classify 4200, -32601 and "not supported" as `{:kind :chain-switch-unsupported}`.
   - `wait-for-transaction-receipt!` gains an opts arity with deposit-worded defaults.
   - `send-and-confirm-evm-transaction!` omits nil `:data` and passes `:chainId`, `:gas`, `:maxFeePerGas` and `:maxPriorityFeePerGas` through.
   - Add `watch-asset!`, whose params are an object `{type "ERC20" options {address symbol decimals}}`.

2. **Transaction builders are domain code.** Put them in `/hyperopen/src/hyperopen/hyperevm/domain/txs.cljs`, not infrastructure, because they encode business rules.
   - `native-to-core-tx`, `erc20-to-core-tx`, `usdc-approve-tx` (exact amount) and `usdc-core-deposit-tx` (`destinationDex` 4294967295).
   - Every builder includes `:chainId "0x3e7"`.
   - Each branches on `:kind` with a `case` that has no default.
   - They use `hyperopen.hyperevm.domain.abi` only. Have `erc20_rpc.cljs` encoders delegate to `abi` so there is one encoder; its existing tests must stay green.

3. **EVM→Core orchestration.** Implement it in `/hyperopen/src/hyperopen/funding/application/hyperevm_submit.cljs` as `submit-hyperevm-to-core! [deps owner action {:flow-id :on-step!}]`:
   1. Validate the provider and owner, then run `transfer_invariants/check-request`.
   2. `ensure-wallet-chain!` with the HyperEVM config (`:switch-network`).
   3. Read the gas price through the RPC client.
   4. USDC only: read the allowance through the RPC client. If it is insufficient, send `approve` (`:approve`), recording its hash, and wait for the receipt.
   5. Estimate gas for the final transaction only after any approve. Fall back to the fee table only on transport or rate-limit errors. An execution revert blocks with the revert reason.
   6. Re-read `eth_chainId` right before EACH `eth_sendTransaction` and abort with the switch-network error unless it is `0x3e7`.
   7. Send (`:send` or `:deposit`). As soon as a hash is returned, write `[:hyperevm :in-flight <owner>] {:flow-id :hashes [..] :waiting-receipt? true :submitted-at-ms :kind :asset :amount}` through an injected `record-in-flight!`.
   8. Wait for the receipt through `rpc/wait-for-receipt!` (`:confirming`). Transient errors keep polling until the 180 s deadline.
   9. On timeout, return `{:status "pending" :txHash h :explorer-url u}`, and set the in-flight entry's `:waiting-receipt?` to false so balance polling resumes (the entry stays unresolved, so it still blocks new EVM→Core submits). The effect shows `:transfer/pending` ("Submitted — confirmation pending", with the explorer link and no Retry). The in-flight entry keeps being polled in the background: add a `:hyperevm` in-flight resolver to the poller that calls `get-transaction-receipt!` on each tick until the transaction resolves, then clears the entry.
   10. On a receipt with status 1, clear `:waiting-receipt?` and return ok. On a revert, return err with "Transaction reverted on HyperEVM."

   Map errors to plain messages per step, as in the original plan. A wallet rejection before any hash was returned is recoverable ("Transfer rejected in wallet."). On `:chain-switch-unsupported`, cache the provider capability.

4. **Effect branching.** In `submit_effects.cljs`, dispatch the submitter by a `case` on `(:type action)`: `"hyperEvmToCore"`, `"sendAsset"`, or the default `usdClassTransfer`. Every EVM write into `[:funding-ui :modal …]` must match the modal's current `:transfer-evm :flow-id`; stale flows write only to `[:hyperevm :in-flight]` and toasts. The outcomes by route and purpose are:

   - **Gas top-up success:** `[:funding-ui :modal :transfer-gas-topup] {:status :sent}`. The draft is untouched. Toast "Sent 0.05 HYPE to HyperEVM for gas." Start the fast poll. The view shows "Sent 0.05 HYPE. Waiting for it to arrive…" until the EVM gas status is `:ok`.
   - **Gas top-up failure:** `{:status :failed :error msg}`. The draft's `:error` and `:submitting?` are untouched.
   - **Core→EVM success:** `:transfer-evm {:phase :succeeded :arrival :arriving …}`, with the modal kept open.
   - **EVM→Core success:** `:phase :succeeded :arrival :arriving`.
   - **Arrival:** it flips to `:arrived` when the destination balance moves by the expected delta, read from the EVM entry for Core→EVM and from the spot clearinghouse for EVM→Core. Retry the spot refresh within the fast-poll window. After 60 s it becomes `:slow`, showing "Taking longer than usual — check Deposits & Transfers."
   - **EVM failure:** `:transfer-evm {:phase :failed :error msg}`, and the failing step is marked `:failed`. This applies to both the non-ok branch and `.catch`. It leaves the "Back to edit" and "Try again" actions.
   - **Pending:** as in step 3.
   - **Legacy route:** unchanged. Close the modal, toast "Transfer submitted.", then refresh.

   After every EVM route and gas top-up, dispatch `[:actions/refresh-hyperevm-balances {:force? true :fast-poll-ms 60000 :now-ms …}]` and refresh spot through an injected `refresh-spot-clearinghouse-snapshot!` from `/hyperopen/src/hyperopen/order/effects/spot_refresh.cljs`. Add a defensive master-only guard for EVM routes.

   Closing the modal mid-flight is allowed. The in-flight entry and a completion toast carry the result, and the progress view shows the design copy "If you close this, the transfer keeps going in your wallet."

5. **Wiring.** Put the new submit collaborators in a new `/hyperopen/src/hyperopen/funding/effects/hyperevm_runtime.cljs`, passed through the `:or` defaults in `funding/effects.cljs`. Add the platform timers to the transfer adapter.

6. **Add to wallet.** Add the effect `:effects/wallet-watch-asset`. It first ensures chain 0x3e7 (the UI copy says the wallet will switch), then calls `watch-asset!` with `{address symbol decimals: evm-decimals}`. The address is native USDC for USDC. For HYPE it only ensures the chain, which adds the network. It is offered only when the destination was HyperEVM. It gets a registration row, an effect-args spec, and an `:api` handler; it is not covered by the effect-order policy.

7. **Tests.**
   - New `hyperevm/domain/txs_test.cljs` and `funding/application/hyperevm_submit_test.cljs`, covering: step order; allowance skip; a rejection at each step; unsupported switch; a chain change between approve and deposit and between switch and native send (assert no further `eth_sendTransaction`); estimate-after-approve; an RPC error during the receipt wait then success; timeout → pending; stale flow-id writes ignored after close and reopen.
   - New `submit_effects` cases in a new `/hyperopen/test/hyperopen/funding/application/submit_effects_hyperevm_test.cljs`.
   - Additions to `wallet_rpc_test.cljs`, with the existing assertions untouched.
   - A signing regression in `internal_seams_test.cljs` (0x3e7 → Mainnet).
   - Formal verify green.

### Milestone 5: Transfer modal views

1. **The form.** Rewrite `/hyperopen/src/hyperopen/views/funding_modal/transfer.cljs`:
   - **From and To.** `role="group"` segmented buttons with `aria-pressed`. A disabled option uses `aria-disabled="true"`, stays focusable, and has `aria-describedby` pointing at a visible reason line under the group. The swap button has `aria-label` "Swap direction".
   - **Asset list.** A `<fieldset>` with a `<legend>` "Asset", using native radio inputs styled as rows. It has a max height with internal scroll, and is always present but hidden with a class when no EVM side is chosen. Empty source copy: "No linked tokens on HyperEVM yet." or "No linked tokens on Spot."
   - **Amount.** The shared amount field, with the existing data-role and input id, then the percent chips and the "≈ $…" estimate.
   - **Balances and summary.** Before/after cards; summary rows for destination (with a lock icon), fee, arrival, network and activation fee.
   - **Blocked card.** `role="status" aria-live="polite"`, warn tokens, and the fix button `data-role "funding-transfer-gas-fix"` with its disabled and status text.
   - **Verification notice.** Includes the explorer link.
   - **Action row.**

2. **The run views.** Create `/hyperopen/src/hyperopen/views/funding_modal/transfer_evm.cljs`:
   - **Progress:** a step list with `role="status" aria-live="polite"`, focusing a heading when progress starts, the "Waiting for wallet (step n of m)" label, and the close-mid-flight copy.
   - **Pending:** the transaction link.
   - **Failed:** the error, "Back to edit" and "Try again".
   - **Success:** arriving → arrived, new balances, "Add to wallet" (EVM destination only), "View in Deposits & Transfers" (navigates to portfolio history), the explorer link, and Done.

3. **Modal shell.** In `/hyperopen/src/hyperopen/views/funding_modal.cljs`:
   - route the content kinds;
   - give the desktop panel `max-height: calc(100vh - 24px)` with internal `overflow-y-auto` (a Tailwind arbitrary value class, no raw color);
   - add a prefix form to `combined-restore-selector` (`[data-role^="balances-move-"]`) plus the exact new roles (`portfolio-funds-connector-perps-spot`, `portfolio-funds-connector-spot-evm`, `account-equity-hyperevm-move`);
   - update `/hyperopen/test/hyperopen/views/funding_modal_accessibility_test.cljs`.

4. **Renames.** As in the original plan:
   - `funding_actions.cljs:58` becomes "Transfer".
   - `header.cljs` gets label and mobile-label "Transfer".
   - Update `funding_modal_test.cljs:521`, `account_equity_view_test.cljs:315`, `trade-regressions.spec.mjs:3274` and `portfolio-regressions.spec.mjs:3056`.

5. **Tests.** New `/hyperopen/test/hyperopen/views/funding_modal/transfer_view_test.cljs` and `transfer_evm_view_test.cljs`. They assert the visible reasons, `aria-describedby`, `aria-live`, radio semantics, the blocked-card fix states and every run phase.

### Milestone 6: Balances table

1. **EVM row projection.** Create `/hyperopen/src/hyperopen/views/account_info/projections/balances_hyperevm.cljs`.
   - `hyperevm-rows [state core-rows]` produces rows as in the original plan, with `:location :hyperevm`.
   - USD values come from the extracted `hyperopen.domain.token-pricing`.
   - The native HYPE available amount subtracts the gas reserve, with a note.
   - Unread or unknown tokens are skipped.
   - When the display entry is unknown or in error, emit no rows. Instead, expose `:hyperevm-status :loading|:unavailable|:ready` for the filter empty states.

2. **Move targets.** `with-move-targets [rows state]` annotates every row, EVM rows included; apply it AFTER appending the EVM rows. Targets:
   - Core spot rows of linked, movable tokens: To HyperEVM.
   - Spot USDC: To Perps (legacy action, byte-identical, only when not `:transfer-disabled?`) and To HyperEVM.
   - Perps USDC: To Spot (legacy). It also gets To HyperEVM on standard accounts. (Withdrawn in Milestone 9: Perps USDC keeps only To Spot.)
   - EVM rows: To Spot.
   - The unified USDC row keeps its `:transfer-disabled?` behavior for Perps/Spot and still offers To HyperEVM.

   Each target has a unique `data-role` `balances-move-<row-key>-<to>` and an `aria-label` such as "Move HYPE from HyperEVM to Spot". Its action is `[:actions/open-funding-transfer-modal :event.currentTarget/bounds <data-role> {:from :to :asset}]`. A disabled target carries a visible reason: the `title` plus an `aria-describedby` hidden text node.

3. **Merge point.** In `vm.cljs:417`, compose `(-> rows (with-unstaking-hype state) (into (hyperevm-rows state rows)) (with-move-targets state))`. Update `balance-tab-count`. Expose the filter, `:has-hyperevm-rows?` and `:hyperevm-status`.

4. **Views.**
   - Guard `send-enabled?` and `transfer-enabled?` on `:location`.
   - Create the location chip (`/hyperopen/src/hyperopen/views/ui/location_chip.cljs`).
   - Add `moves.cljs` for the desktop 7th cell and the mobile footer.
   - Link EVM contract cells to hyperevmscan.
   - Create `location_filter.cljs` with the labels "All / HyperCore / HyperEVM" at ≥ 640 px and "All / Core / EVM" below. It wraps under the search at 375 px with no horizontal overflow.
   - Empty states when filtered to HyperEVM: "Checking HyperEVM balances…", "HyperEVM balances are unavailable right now.", or "No HyperEVM balances."
   - Add the action `:actions/set-balances-location-filter`.

5. **Slices.** Do NOT put the raw `:hyperevm` subtree into `account-info-view-base-state-keys`. Merge a projected, ready-only slice instead, the display entry's `select-keys [:native-wei :token-units :status]` plus bridge health, following the `asset-selector-market-lookup-state` pattern in `trade_view.cljs`, so polls with unchanged balances do not re-render the panel. Add a repaint test.

6. **Tests.** New namespaces as in the original plan, plus tests for the move-target data-roles and aria-labels and for the empty states.

### Milestone 7: Portfolio strip and the Trade account-panel line

1. **Portfolio strip.** As in the original plan, with these additions:
   - For unified accounts, one "Trading account" card and a single Spot↔HyperEVM connector.
   - When EVM data is unknown, the total shows "—" with the sub-line "HyperEVM balances loading". It never shows a partial total labeled as total value.
   - Connector data-roles are exact: `portfolio-funds-connector-perps-spot` and `portfolio-funds-connector-spot-evm`.
   - Disabled reasons are visible text under the strip on small screens, and a tooltip plus `aria-describedby` on desktop.

2. **Trade account-panel line.** Precompute a `hyperevm-line-model` (`{:usd :visible? :move-action :blocked-reason}`) in `trade-view-panel-context`. Thread it through `/hyperopen/src/hyperopen/views/trade_view/shell.cljs` `render-order-entry-panel-shell` (replacing the hard-coded `{}` opts) and through `mobile-account-surface`. Render it in the classic and unified panels. It is always present, and hidden when the value is nil or ≤ 0. The Move link is hidden when read-only and disabled with a reason for a subaccount.

3. **Tests.** Add tests for both, including spectate and subaccount, and assert that the equity metrics are unchanged by EVM balances.

4. **Do not reuse the Balances slice.** `hyperopen.hyperevm.panel-slice/balances-panel-slice` is lossy (it drops the gas price at normal prices, positive bridge capacities, `:core-system-balances`, `:in-flight` and `:core-account`), so `balances/evm-gas-status` and the capacity readers give wrong answers on it. Derive the Trade HyperEVM line and the Portfolio strip from the full state or from their own projection, with their own repaint test.

### Milestone 8: Simulator, Playwright, browser QA

1. **Wallet simulator.** Extend `simulators.cljs` with:
   - `eth_sendTransaction`, returning deterministic hashes and recording chainId, to, value and data;
   - `eth_chainId`, which must reflect switches;
   - `wallet_switchEthereumChain` with a configurable `switchChainErrorCode` (4902, 4200);
   - `wallet_addEthereumChain` and `wallet_watchAsset`;
   - a request log.

   Receipts, gas and balances come ONLY from the mocked HyperEVM RPC keyed by the simulator's deterministic hashes, never from the wallet. Tests go in `/hyperopen/test/hyperopen/telemetry/console_preload/simulators_evm_test.cljs`.

2. **Fixtures and shared route.** Add `/hyperopen/tools/playwright/support/hyperevm_fixtures.mjs`, with production-shaped spotMeta rows and a JSON-RPC handler for `eth_getBalance`, `eth_gasPrice`, `eth_estimateGas`, `eth_call` (aggregate3 of balanceOf/decimals), `eth_getTransactionReceipt`, rate-limit injection and batches. Add its node test.
   - Add `routeHyperEvmRpc(page, fixture)` and call it from `visitRoute` with an empty-balance default, so every existing spec is deterministic.
   - Add a guard test that fails if any request reaches `rpc.hyperliquid.xyz` unrouted.

3. **Spec.** Add `/hyperopen/tools/playwright/test/funding-transfer-hyperevm.spec.mjs` (`@regression`). Cases:
   - (a) EVM rows, chips, and the filter at desktop and 375 px (no horizontal overflow); on `/trade` at 1280x800 the right edge of `balances-move-spot-0-hyperevm` is at or before the right edge of `[data-parity-id="account-tables"]`; a disabled move's reason is visible when the action is focused (desktop) and under the expanded card (375 px);
   - (b) Core→EVM PURR: the exact `sendAsset`, the arriving → arrived state, and Add to wallet (wallet log: switch 0x3e7, then `wallet_watchAsset` decimals 18);
   - (c) EVM→Core HYPE: the wallet log is `eth_chainId` → switch → `eth_chainId` → `eth_sendTransaction` {chainId 0x3e7, to 0x2222…};
   - (d) USDC approve then deposit calldata;
   - (e) gas-blocked: a double-click on the fix sends one top-up, the draft is kept, and an exchange error re-enables the fix;
   - (f) subaccount: visible reason text;
   - (g) spectate: HyperEVM rows visible, no Send/Transfer/Repay columns (so no move controls), and the read-only reason in the note under the table (see "Milestone 6 as built", Read-only);
   - (h) wallet rejects the deposit step → failed view → "Back to edit" keeps the draft;
   - (i) 4902 → add-chain with HYPE nativeCurrency → switch;
   - (j) 4200 → unsupported explanation and "Try again";
   - (k) the receipt RPC returns a rate-limit error once, then success;
   - (l) portfolio strip connectors;
   - (m) the /trade Move link opens the HyperEVM→Spot preset;
   - (n) the modal's submit and fix buttons are inside the viewport at 1280×800 and 1440×900 when opened from the lowest Balances row;
   - (o) the 375 px mobile sheet.

4. **Existing specs.** Update the label specs and rerun the broader set that connects owners or spectates on `/trade` and `/portfolio`: trade-regressions, portfolio-regressions, mobile-regressions, account-equity-*, subaccounts-regressions and staking-regressions.

5. **Browser QA.** At 375, 768, 1280 and 1440, including the `/trade` high-risk checks from `browser-qa.md` (switch all account tabs; panel geometry unchanged). Record PASS, FAIL or BLOCKED.

### Milestone 9: gates, review, docs

- Run `npm run gates` and the app compile.
- Run an adversarial review through three lenses: fund safety, contracts and regressions, and UX/accessibility. Fix what the review confirms.
- Update `/hyperopen/docs/BROWSER_TESTING.md` coverage.
- Write the retrospective. Move the plan to completed after acceptance.

## Concrete Steps

Run everything from the worktree root, `/hyperopen` below.

    npm run setup:worktree          # symlinks node_modules; required before any gate
    npm test                        # full cljs unit suite (regenerates test/test_runner_generated.cljs)
    npm run lint:namespace-sizes
    npm run lint:hiccup
    npm run lint:theme-colors
    bb tools/formal.clj sync --surface effect-order-contract     # after the Lean corpus edit (Milestone 4)
    bb tools/formal.clj verify --surface effect-order-contract
    npm run test:release-assets     # CSP test
    npm run gates                   # check + test + test:websocket matrix

Playwright against this worktree's own build, because the main checkout may hold port 8080:

    npm run css:build && npx shadow-cljs --force-spawn compile app
    PLAYWRIGHT_STATIC_ROOT=resources/public PLAYWRIGHT_WEB_PORT=8091 node tools/playwright/static_server.mjs &
    PLAYWRIGHT_BASE_URL=http://127.0.0.1:8091 PLAYWRIGHT_REUSE_EXISTING_SERVER=true npx playwright test funding-transfer-hyperevm --workers=1
    PLAYWRIGHT_BASE_URL=http://127.0.0.1:8091 PLAYWRIGHT_REUSE_EXISTING_SERVER=true npx playwright test trade-regressions portfolio-regressions --grep "funding" --workers=1
    npm run browser:cleanup

Expected after Milestone 9: `npm run gates` prints PASS for every row, and the new spec reports all cases passed.

## Validation and Acceptance

Acceptance is behavior. Every item below is exercised by a unit test, by the Playwright spec `/hyperopen/tools/playwright/test/funding-transfer-hyperevm.spec.mjs`, or by both.

1. **Legacy byte-identity.** With no HyperEVM involvement, Perps↔Spot builds exactly the same request as before. `named_dex_transfer_preview_test.cljs` and `submit-funding-transfer-validates-and-emits-api-effect-test` pass unchanged.
2. **Core→EVM.**
   - Setup: 9,800 PURR on Spot, ≥ 0.001 spot HYPE, and a funded PURR system address.
   - Action: choose From Spot, To HyperEVM, asset PURR, amount 100, then "Move 100 PURR to HyperEVM".
   - It posts exactly one signed action, `{:type "sendAsset" :destination "0x2000000000000000000000000000000000000001" :sourceDex "spot" :destinationDex "spot" :token "PURR:0xc1fb593aeffbeb02f85e0308e9956a90" :amount "100" :fromSubAccount ""}`.
   - The modal shows "Arriving…", then "Arrived" once the mocked EVM balance rises by 100.
3. **EVM→Core HYPE.**
   - Setup: 12.5 HYPE on HyperEVM; move 10 HYPE to Spot.
   - The wallet log is, in order: `eth_chainId`, `wallet_switchEthereumChain {chainId "0x3e7"}`, `eth_chainId`, `eth_sendTransaction {chainId "0x3e7" to "0x2222…2222" value "0x8ac7230489e80000" gas … maxFeePerGas … maxPriorityFeePerGas "0x0"}`.
   - Progress is announced, and success follows.
4. **EVM→Core USDC.**
   - Moving 1,000 USDC issues `approve(0x6b9e…0a24, 1000000000)` on `0xb883…630f`, then `deposit(1000000000, 4294967295)` on `0x6b9e…0a24`. Each carries chainId 0x3e7.
   - The approve is skipped when the allowance suffices.
   - The deposit gas estimate runs only after the approve receipt.
5. **Bridge safety.**
   - A token whose EVM system balance is 0 shows "To HyperEVM" disabled, with a visible reason.
   - A token with a `decimals()` mismatch appears in no move target.
   - MAX never exceeds the bridge capacity.
6. **Double-send protection.**
   - After a transaction hash is returned, a receipt-poll rate limit or a timeout never returns the user to an editable form with Submit enabled. The modal shows pending, with the explorer link.
   - A new EVM→Core submit is blocked with "A HyperEVM transfer is still confirming…" until the in-flight transaction resolves.
   - Closing and reopening mid-flight does not leak old progress into the new draft.
7. **Chain pinning.** If the wallet's chain changes after the switch (for example between approve and deposit), no further `eth_sendTransaction` is issued, and the run fails with the switch-network message.
8. **Gas top-up.**
   - Setup: 0 HYPE on HyperEVM and ≥ 0.05 HYPE available on Spot.
   - Choosing From HyperEVM shows "You need HYPE on HyperEVM to pay gas" before any amount is typed, along with the fix button.
   - One click posts one `sendAsset` of HYPE 0.05 to `0x2222…`. Further clicks are ignored while it is submitting or sent. The draft's asset and amount are unchanged.
   - An exchange error shows inline and re-enables the fix.
   - With Spot HYPE < 0.05, the fix is disabled with a reason.
9. **Unknown data.** While HyperEVM balances are unknown (loading, or a rate-limit error before any read), the modal shows "Checking HyperEVM balances…" or the unavailable text. It never shows the gas-fix button. The Portfolio strip total shows "—".
10. **Identity.**
    - With a subaccount selected, the HyperEVM options show the visible reason "HyperEVM transfers are available for the master account only."
    - In spectate mode, EVM rows are visible, the table has no move controls (the read-only table has no Send/Transfer/Repay columns), and the note under the table shows the read-only reason (amended by the Milestone 6 review; see "Milestone 6 as built", Read-only).
11. **Activation.** A HyperCore account with `userRole` "missing" cannot start a non-USDC EVM→Core move, and sees the activation explanation. USDC shows the 1 USDC activation-fee row.
12. **Failure recovery.** When the wallet rejects the deposit step, the modal shows the failed view. "Back to edit" restores the form with the same asset and amount.
13. **Wallet support.**
    - A wallet answering 4902 gets `wallet_addEthereumChain` with nativeCurrency HYPE/18, then a switch.
    - A wallet answering 4200 sees the unsupported explanation and "Try again".
14. **Balances and metrics.** The Balances table shows EVM rows with the EVM chip, and the location filter narrows rows. At 375 px the toolbar wraps with no horizontal page overflow. Trading equity figures are identical with and without EVM balances.
15. **Portfolio strip.** The strip shows total value = total equity + EVM USD, and each connector opens Transfer preset to its pair. The /trade HyperEVM line's Move opens the HyperEVM→Spot preset.
16. **Layout and keyboard.** At 1280×800 and 1440×900, the modal's submit and fix controls are inside the viewport when opened from the lowest Balances row. The whole flow is completable by keyboard.
17. **Gates.** `npm run gates` passes. The new spec and the broadened existing specs pass. Browser QA PASS is recorded at 375, 768, 1280 and 1440 for `/portfolio`, `/trade` and the modal states.

## Idempotence and Recovery

All steps are additive or re-runnable:

- `npm run test:runner:generate` rewrites the generated runner deterministically.
- The formal sync regenerates vectors from Lean and can be re-run.
- If the Lean toolchain fails, revert the gas-top-up coverage by removing its policy entry, Lean line and required-id entry, switch the gas fix to a projection-only preset, and record that in the Decision Log.
- If a milestone leaves gates red, the previous milestone's commit is the rollback point. Commit at every milestone.
- EVM transactions in the app cannot be retried automatically. The UI tells users to check their wallet activity before retrying after a timeout, because a double-send moves funds twice.

## Artifacts and Notes

Live ledger shapes (`userNonFundingLedgerUpdates`) for the derive tests:

    Core→EVM sendAsset: {:type "send" :destination "0x20…" :sourceDex "spot" :destinationDex "spot" :token "USDC" :nativeTokenFee "0.00002"}
    HYPE Core→EVM:      {:type "send" :destination "0x2222222222222222222222222222222222222222" :nativeTokenFee "0.0"}
    spotSend Core→EVM:  {:type "spotTransfer" :destination "0x20…"}
    EVM→Core:           {:type "spotTransfer" :user "<system address>" :destination "<me>"}

Mainnet constants, verified 2026-09-30. Each line gives index / tokenId / weiDecimals / evmContract / extra:

    HYPE 150 / 0x0d01dc56dcaaca66ad901c959b4011ec / 8 / null (native, 18 dec) / — / system 0x2222…2222
    USDC 0 / 0x6d1e7cde53ba9467b783cb7c530ce054 / 8 / 0x6b9e773128f453f5c2c60935ee2de2cbc5390a24 (CoreDepositWallet) / -2 / ERC20 0xb88339cb7199b77e23db6e890353e22632ba630f
    PURR 1 / 0xc1fb593aeffbeb02f85e0308e9956a90 / 5 / 0x9b498c3c8a0b8cd8ba1d9851d40d186f1872b44e / 13
    KHYPE 121 / 0xbc8a22f25703a03101630ce6b09f4baa / 8 / 0xfd739d4e423301ce9385c1fb8850539d657c296d / 10
    UBTC 197 / 0x8f254b963e8468305d409b33aa137c67 / 10 / 0x9fdbda0a5e284c32744d2f17ee5c74b284993463 / -2
    UETH 221 / 0xe1edd30daaf5caac3fe63569e24748da / 9 / 0xbe6727b535545c67d5caa73dea54865b92cf7907 / 9

Milestone 8 review-fix evidence lives in this session's scratchpad (`/private/tmp/claude-501/-Users-barry-projects-hyperopen--claude-worktrees-compassionate-archimedes-9ad5d9/5e3abd89-fab8-473f-bfab-fa3363304b9c/scratchpad`, written `scratchpad/` elsewhere in this plan; local and non-authoritative):

- `m8fix-baseline-run.log`: HEAD's specs against the `git archive HEAD` build on port 8096, for the cases that failed in Milestone 8's broadened run (7 failed, 1 passed).
- `m8fix-live-shapes.txt`: the live 21-entry batch error, the deposit-estimate allowance revert and `subAccounts` for an address with none.
- `m8fix-live-user-reads.txt`: the eight live user reads for the account-less fixture owner, the source of `tools/playwright/support/account_less_user_reads.json`.
- `m8fix-spec-run3.log`: the HyperEVM spec run twice over; `m8fix-broad.log`: the broadened rerun; `m8fix-gates.log`: `npm run gates`.

### Milestone 8 item 5: browser QA matrix (2026-10-01)

Evidence lives in `scratchpad/qa/` (local and non-authoritative): screenshots `<surface>-<state>-<width>.png`, measurements `m8qa-*.jsonl`, run logs `m8qa-run-*.log`. "Fixed" marks a FAIL found by this pass and fixed in it (see "Milestone 8 item 5 as built"); every result is after the fixes. Contrast was measured on every visible text node against its composited background (WCAG AA 4.5:1, or 3:1 for large text). "No overflow" means `scrollWidth <= innerWidth` for both `html` and `body`.

| Surface | 375 | 768 | 1280 | 1440 |
|---|---|---|---|---|
| `/portfolio` funds strip | PASS: cards stack, connectors on the seams, total $55,883.01 = Total Equity $49,336.13 + HyperEVM $6,546.89, no overflow | PASS: total row then 3 cards, no overflow | PASS: one row, matches Main | PASS: one row, matches Main |
| `/portfolio` header Transfer | PASS: label "Transfer", hit-testable, sheet submit in view, focus returns on Escape | PASS | PASS | PASS |
| `/portfolio` Balances (HyperEVM rows, filter, moves) | PASS: toolbar wraps, filter All/Core/EVM, mobile reason line visible | PASS (fixed: the selected tab was hidden, strip 205 px; now 734 px with 7 tabs in view) | PASS: moves inside the table, disabled reason on focus; strip 701 px against HEAD's 919 | PASS: as 1280, strip 861 px against HEAD's 1079 |
| `/trade` account panel (Transfer, HyperEVM line) | PASS: mobile Account surface, line and Move link, focus returns | PASS | PASS: matches TradeFundingPanel | PASS |
| `/trade` Balances tab | PASS | PASS (fixed: strip now 768 px with 8 tabs, HEAD 457 px) | PASS: moves end at 928 px or less in the 960 px panel; strip 431 px against HEAD's 649 | PASS: Contract icons 18 px past the panel edge (sideways scroll); strip 591 px against HEAD's 809 |
| `/trade` high-risk geometry (7 tabs) | N/A (mobile layout; tab rect constant) | N/A (mobile layout; tab rect constant) | PASS: identical to HEAD on all 7 tabs, flush 0/0, lower share 0.3556 | PASS: identical to HEAD at 1440x900 and 1440x1200, flush 0/0, share 0.3659 and 0.3385 |
| Modal form, Core to HyperEVM | PASS: sheet, sticky submit in view | PASS | PASS: matches TransferCoreToEvm | PASS |
| Modal HyperEVM to Core USDC progress | PASS (fixed: route text 3.64:1, now 5.1:1 or better) | PASS (fixed) | PASS (fixed): matches TransferEvmToCoreUsdc | PASS (fixed) |
| Modal gas-blocked | PASS: fix at 702-742, submit at 749, both hit-testable | PASS | PASS: matches TransferNeedsGas | PASS |
| Modal failed | PASS: Try again under the error toast for 3.95 s, then hit-testable | PASS | PASS | PASS |
| Modal pending | PASS: explorer link under the toast until it clears | PASS | PASS | PASS |
| Modal success, arriving then arrived | PASS: matches TransferSuccess (toast as above) | PASS | PASS | PASS |
| Mobile sheet | PASS: full width, top radius 22 px, sticky footer clear of the nav | N/A (popover above 640 px) | N/A | N/A |
| Focus rings (modal, moves, filter, strip) | PASS (fixed: MAX and percent chips used the browser's default outline) | PASS | PASS | PASS |
| Institutional theme | n/a | n/a | PASS: strip, Balances, reason, form, gas card, trade panel; contrast 5.29:1 or better | n/a |

Passes per `browser-qa.md`:
- Visual: PASS, with the fidelity gaps below.
- Native controls: PASS. No new `select`, date, time, color, file, number or range input. The asset list uses native radios (a plan decision) and Hide Small Balances a checkbox; the order-entry range slider on `/trade` predates this feature.
- Styling consistency: PASS after fix 3. New colors are `ho-*` tokens only (`lint:theme-colors` and `lint:hiccup` pass).
- Interaction: PASS. Focus returns to every opener; the disabled reasons show on focus and hover and under mobile cards; Escape closes the modal and the tooltips.
- Layout regression: PASS after fix 1, with the lg-and-up tab-strip squeeze recorded as a remaining risk.
- Jank: PASS. Zero shift on idle polls and interactions; modal open 43-106 ms. The one-time shift when the strip appears on connect is recorded as a remaining risk.

Overall: PASS. Browser sessions were cleaned up (`npm run browser:cleanup` stopped nothing left open; both static servers were killed).

Fidelity gaps against `scratchpad/evm-design/project` (all but the last are plan decisions or minor):
- Main: no "Where" column (chips sit in the Coin cell); no header Send button; the total sub-line reads "Total Equity" rather than "Trading equity".
- TradeFundingPanel: the Transfer button has no swap icon.
- MobileSheet: places are stacked segmented groups, not two chip buttons; there is no 40 px centred amount or "After" block; moves sit inside the expanded card.
- TransferCoreToEvm: segmented From/To and an asset radio list instead of dropdown cards; "No switch needed" is in the neutral tone, not green.
- TransferEvmToCoreUsdc: the route cards are no longer dimmed (fix 2).
- TransferSuccess: no "View on explorer" for Core to HyperEVM, since there is no HyperEVM transaction hash.
- BalancesQuickMove: not built (plan decision).
- Pre-existing and outside this feature: the app nav's logo overlaps the nav links at 768 px (and at 1280 in the institutional theme). The nav views are unchanged on this branch.
- Phone volume-history popover: it covers its trigger on both builds (branch: trigger y=745, popover 296-796, fully in view; HEAD: trigger y=288, popover 284-784).

### Milestone 9 evidence (2026-10-01)

In `scratchpad/m9/` (local and non-authoritative):

- `test-run1.log`, `test-run2.log`: `npm test` before and after the test updates (30 failures, then 6887 tests, 0 failures).
- `gates.log`: `npm run gates`, 34/34 PASS.
- `compile-app.log`: `npm run css:build && npx shadow-cljs --force-spawn compile app`, 0 warnings.
- `pw-spec-run1.log`, `pw-spec-ef.log`, `pw-spec-run2.log`: the HyperEVM spec (24/27, then the 4 (e)/(f) cases, then 54/54 over two repeats), against this worktree's build served by `tools/playwright/static_server.mjs` on port 8093.
- `pw-broad-subset.log`, `pw-named-dex-rerun.log`: the funding and balances subsets of the broadened specs (33/34, the one failure then 2/2).
- `header_probe.mjs`, `header-probe-branch.jsonl`: the account tab header's height per tab at 768-1536 px and the filter's width.
- `main_gzip.mjs`, `release-head-sizes.json`, `release-branch-sizes.json`: release gzip sizes per module, this branch against a clean `git archive HEAD` export.

Browser QA addendum to the Milestone 8 matrix: the run views' headings now take focus (Playwright `toBeFocused` on progress, pending, failed and success), the busy gas fix keeps focus and hands it to the submit when the gas lands, and the disabled-move tooltip stays open under the pointer. Not covered by the matrix and recorded instead: between 768 and 1023 px the account tab header is 97 px with Balances selected and 49 px on every other tab, so a tab switch to or from Balances moves the table 48 px (the matrix's 768 `/trade` geometry row read "N/A (mobile layout; tab rect constant)").

## Interfaces and Dependencies

No new npm dependencies. ABI encoding is hand-rolled over `js/BigInt`. Keccak is not needed, because every selector is a constant listed above.

The following functions must exist at the end:

    hyperopen.hyperevm.domain.tokens/linked-tokens [spot-meta] -> [token-map]
    hyperopen.hyperevm.domain.tokens/system-address [index] -> "0x…"
    hyperopen.hyperevm.domain.units/parse-units [text decimals] -> js/BigInt|nil
    hyperopen.hyperevm.domain.abi/encode-core-deposit [units dex] -> "0x…"
    hyperopen.hyperevm.domain.fees/core->evm-fee-hype [gas-price-text token] -> "0.00002075"
    hyperopen.hyperevm.infrastructure.rpc/read-balances! [deps {:owner :tokens :chain}] -> Promise<{:native-wei :token-units :gas-price-wei}>
    hyperopen.hyperevm.domain.balances/refresh-plan [state now-ms opts] -> plan|nil
    hyperopen.account.context/hyperevm-moves-blocked-message [state] -> string|nil
    hyperopen.funding.domain.transfer-route/transfer-route [modal] -> {:from :to}
    hyperopen.funding.domain.evm-transfer-preview/evm-transfer-preview [state modal] -> {:ok? :request :blocked :display-message}
    hyperopen.funding.application.hyperevm-submit/submit-hyperevm-to-core! [deps owner action] -> Promise<{:status …}>
    hyperopen.views.account-info.projections.balances-hyperevm/hyperevm-rows [state core-rows] -> [row]
    hyperopen.views.account-info.projections.balances-moves/with-move-targets [rows state] -> [row]
    hyperopen.hyperevm.panel-slice/balances-panel-slice [state] -> {:hyperevm …}
    hyperopen.views.account-info.projections.hyperevm-funds/hyperevm-funds [state] -> {:status :ready|:loading|:unavailable|:partial|:none :usd :token-count :gas-status …}
    hyperopen.views.portfolio.funds-locations/funds-locations-model [state summary] -> model   ; re-export of funds-locations.model
    hyperopen.views.portfolio.funds-locations.model/hypercore-status [state] -> :ready|:loading|:unavailable
    hyperopen.views.account-equity.hyperevm-line/hyperevm-line-model [state] -> {:visible? :usd :value-text :move-action :blocked-reason :focus-token}
    hyperopen.hyperevm.domain.balances/first-read-failed? [entry] -> boolean
    hyperopen.hyperevm.domain.balances/partial-read? [state address] -> boolean
    hyperopen.views.ui.dismissible-tooltip/group-attrs [] -> {:on {…}}
    hyperopen.hyperevm.domain.bridge/core->evm-capacity [state token now-ms] -> "…"|:unlimited|nil   ; nil once the reading is 60 s old or predates a send (Milestone 9)
    hyperopen.hyperevm.domain.bridge/mark-core->evm-sent [state token now-ms] -> state
    hyperopen.hyperevm.domain.tokens/system-address? [address] -> boolean
    hyperopen.hyperevm.domain.transfer-state/in-flight-blocked-message [entry] -> string|nil       ; by entry state (Milestone 9)
    hyperopen.funding.domain.transfer-run/failure-detail [error maybe-sent?] -> string
    hyperopen.views.funding-modal.transfer-parts/fix-focus-hook [fix?] -> on-render hook

New runtime ids:

    Actions: refresh-hyperevm-balances, refresh-hyperevm-bridge-capacity (Milestone 2), set-balances-location-filter, set-funding-transfer-location, swap-funding-transfer-locations, select-funding-transfer-asset, set-funding-transfer-amount-percent, submit-funding-transfer-gas-topup (effect-order covered), reset-funding-transfer-evm, retry-funding-transfer-capability, add-funding-transfer-token-to-wallet, check-hyperevm-in-flight (Milestone 4).
    Effects: fetch-hyperevm-balances, fetch-hyperevm-core-bridge-balance, fetch-hyperevm-core-account-status (all Milestone 2), wallet-watch-asset, fetch-hyperevm-in-flight-receipt (Milestone 4).
