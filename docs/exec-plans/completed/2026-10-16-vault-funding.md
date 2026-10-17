# Fund an optimizer vault allocation (batch withdraw + deposit)

This ExecPlan is a living document. Keep `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` up to date as work proceeds.

## Purpose / Big Picture

After an optimizer run that holds Hyperliquid vaults, the user can move money so their vault holdings match the target weights: enter an amount of available USDC, review a plan that withdraws from vaults above target (when unlocked) and deposits into vaults below target, confirm once, and watch each transfer complete, with retry for any that fail. Perp/spot runs are unchanged.

Design reference: canvas "Fund a vault allocation" (https://claude.ai/artifact/XHZYwhFBG8BSZJymFnmeso), artboards 1–4, approved by the owner 2026-10-16 ("The design looks good. Go ahead and build the vault funding flow.").

## Context References

Public refs:
- Owner requests in this session (2026-10-16): existing deposits count by default (uncheckable, then only new cash is split); a vault already above target gets no new cash and the user is told to consider withdrawing; when withdrawal is available, withdraw first and recycle the cash into the other vaults.

Repo artifacts (from the code map, verified):
- Signing: `hyperopen.api.trading/submit-vault-transfer!` [store owner-address action] — agent-signed L1 `vaultTransfer`, no wallet pop-up; resolves `{:status "ok"}` or `{:status "err" :response text}`; send sequentially (nonce cursor).
- Action shape (contract key order): `{:type "vaultTransfer" :vaultAddress a :isDeposit bool :usd <int micros>}`.
- Holdings: `[:vaults :user-equity-by-address addr] {:equity :locked-until-ms}`, loaded by `[:effects/api-fetch-user-vault-equities effective-address]` (lazy vaults module; route gate accepts /portfolio); trust only when `[:vaults :user-equities-for-address]` matches. Optimizer current weights never include vaults.
- Cash: `vaults.domain.transfer-policy/deposit-available-usdc` (perps withdrawable on classic, spot USDC on unified). Moving spot→perps (`usdClassTransfer`) is wallet-signed.
- Gates: `account.context/mutations-blocked-message`, `actions.execution/stale-recommendation?`, `[:wallet :agent :status]` (:locked → unlock with replay, :unlocking → hold, else enable-trading recovery).
- Do NOT reuse the order Execution tab (`execution-path` is replaced wholesale; vault rows are `:blocked :unsupported-market-type`) or the modal-coupled `:effects/api-submit-vault-transfer`.

## Plan

- Pure planner `application/vault_funding/plan.cljs` (written first, tested).
- Pure run transitions + view model `application/vault_funding/run.cljs`, `model.cljs`.
- Actions `actions/vault_funding.cljs`: one field setter and one command action (open, close, review, back, confirm, stop, retry, done).
- Effect `runtime/effect_adapters/portfolio_optimizer_vault_funding.cljs`: sequential promise chain, withdrawals before deposits, halt deposits when a withdrawal fails, abort flag, re-checks inside the effect, terminal catch, forced refresh of vault equities + user data afterwards.
- Views: `views/portfolio/optimize/vault_funding_sheet.cljs` (plan, review, progress screens) mounted next to the save modal; verdict CTA becomes "Fund this allocation" for all-vault runs; the hypothetical-performance card gains the button for mixed runs.
- State at a new contracts path `vault-funding-path`.

## Validation and Acceptance

- `npm run gates` passes.
- Unit: planner (worked design example, new-cash-only, zero-cash rebalance, closed vault spread/cash, locked excess, dust, errors), run transitions, actions (gates: spectate, subaccount, stale, agent states, holdings not loaded), effect (order, halt on withdrawal failure, abort, retry, terminal catch).
- Playwright: seeded vault run → open sheet → plan rows and totals → review → confirm with a stubbed exchange → progress done / one failure → retry.
- Acceptance: a perp/spot-only run shows no funding entry points.

## Progress

- [x] (2026-10-16) Code map (6 parallel readers): transfer signing, holdings, execution tab, registration, cash/transfer, UI entry.
- [x] (2026-10-16) Pure planner + tests.
- [x] (2026-10-16) Run transitions, view model, actions, effect, registration.
- [x] (2026-10-16) Sheet UI + entry points (verdict CTA "Fund this allocation" on all-vault runs; "Fund the vaults" on the hypothetical-performance card for mixed runs).
- [x] (2026-10-16) Adversarial review (5 dimensions, 3 skeptics per finding: 28 findings, 22 confirmed) and fixes; `npm run gates` 34/34; Playwright `optimizer-vault-funding.spec.mjs` (3) + tear-sheet spec green; live render of the design's worked example matches the canvas to the cent.
- [x] (2026-10-17) Owner follow-up (canvas board 5, approved): choose the split (Optimizer target or Equal weight), a per-vault "Locked until" column, Target beside After, "All withdrawable by" in the footer, and no review screen: the plan's button names the amounts and sends.
- [x] (2026-10-17) Owner review of the split and lock-up build: accepted, with the follow-ups below (leader-share deposit cap, a clear message for reasonless refusals, per-row Include/Skip, an off-by-default "Only fill vaults below their target"); merged to main in `b15fcf20f`.

## Surprises & Discoveries

- Review findings fixed: (1) spreading a closed vault's share used to send new cash into over-target vaults and could withdraw from and redeposit into the same vault; targets are now computed once per vault (a closed vault keeps at most what it holds, open vaults share the rest), so no vault is ever on both sides; (2) Retry could re-sign a run for another wallet or another optimizer result, and resend transfers whose outcome was unknown; runs now carry `:owner`/`:source`, a broken-off request is `:unknown` (never resent; "Plan again" re-plans from refreshed holdings), only exchange refusals are retryable, and every row is claimed atomically; (3) a decimal comma was read as a thousands mark — amounts now parse in the UI locale at USDC precision; (4) an unknown balance no longer lifts the spending cap; (5) Confirm now sends exactly the reviewed steps (changed plan → back to review); (6) the sheet sits above the app footer, traps focus and closes on Escape.
- A gate run's one-off `compile app` overwrites the running dev watch's module bundles with stale code; touch a source file afterwards so the watch rewrites `resources/public/js/portfolio_route.js`.

- Moving spot USDC to perps is a wallet-signed action, so the design's "step 0" cannot ride the agent-signed batch silently; the sheet links to the existing Transfer modal instead.
- The order Execution tab keeps no history across reloads either; funding runs are session state (the sheet says to keep the tab open).

## Decision Log

- (2026-10-17) After a partial run the remaining cash spread over every vault because the closed vault's share was spread (plus a bug: HLP, a protocol vault whose leader holds 0.2%, was flagged at the 5% floor; protocol vaults are now exempt). Owner chose: a per-row Include box (a skipped vault gets no deposit or withdrawal; its holding is its target) and an off-by-default "Only fill vaults below their target" checkbox (closed-mode :cash; replaces the closed-vault radio buttons).

- (2026-10-17) First live run (16 deposits): Cold Process refused "Deposit causes leader share to drop below 5%." (leader at 5.0001%, about $4.75 of room); FuturAI refused with Hyperliquid's reasonless "Vault transfer error" while its live state looked normal (deposits on, leader 12.5%, about $252k of room). Opening the sheet now reads each vault's live vaultDetails; deposits are capped at 98% of the leader-share room (leader equity x (20 - 1/leader fraction)), a vault with under $10 of room counts as closed (:leader-floor), and a reasonless refusal says so. The table's Target column became Current (share of today's holdings).

- (2026-10-17) Equal weight = the tear sheet's Equal weight: the same share for every vault the run gave a non-zero weight; 0% vaults stay at 0 (anything held there comes out). Every other rule is unchanged.
- (2026-10-17) Lock-up per vault: 4 days for HLP, 1 day otherwise (`transfer-policy/deposit-lockup-days`, shared with the vault page). A deposit restarts the lock on the whole position (Hyperliquid keeps one `lockupUntil` per depositor); an untouched locked position keeps its lock.
- (2026-10-17) No review screen. Sending from the plan is safe because the button names exactly what is sent; when trading must be unlocked first, the plan on screen is pinned and the replayed send goes out only if the plan is unchanged.

- A failed withdrawal halts the deposits (they may depend on its cash); a failed deposit does not stop the others. Retry re-sends only failed/halted steps.
- Sign as the owner wallet with no subaccount option (the existing vault flow does the same); funding is blocked while a subaccount is selected so holdings and signer agree.
- Vaults closed to deposits are detected from loaded vaultDetails (`:allow-deposits? false`, not leader) or the Liquidator name; unknown is treated as open and the exchange's answer is shown per step.
- No effect-order policy entry (the order execution confirm has none either), avoiding a Lean sync.

## Outcomes & Retrospective

Shipped and merged to main in `b15fcf20f` (2026-10-17). The sheet funds a vault allocation in one batch: withdrawals from over-target vaults first, then deposits, each its own agent-signed `vaultTransfer`, per-vault retryable. The user picks Optimizer target or Equal weight, sees each vault's lock-up and when everything is withdrawable, and sends from the plan. Deposits are capped by the leader's 5% share (a vault at the floor counts as closed; protocol vaults like HLP are exempt), and any vault can be skipped. Remaining risk: the send path has run live only on the owner's own deposits; one refusal (FuturAI Labs, "Vault transfer error") has no known cause, and the sheet now says Hyperliquid gave no reason and leaves it retryable.
