# Make In-Flight HYPE Unstaking Legible Everywhere A HYPE Balance Appears

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

This document follows `/hyperopen/.agents/PLANS.md`. Keep it self-contained, update it whenever implementation discoveries change the plan, and leave at least one unchecked progress item while the plan remains active.

## Purpose / Big Picture

A user who has started unstaking HYPE cannot currently tell that from the Hyperopen UI. Their HYPE stops appearing in the Balances tab entirely, the Portfolio "Staking Account" figure silently folds the in-flight amount into one opaque number, and the only acknowledgement anywhere in the product is a single unadorned row on the `/staking` page labelled "Pending Transfers to Spot Balance" showing a bare eight-decimal number. Nothing states that the amount is locked, nothing says how long the lock lasts, and nothing says when the HYPE will arrive.

The result is the reported failure: a user looked for their HYPE, could not find it, and only later worked out for themselves that it had not finished unstaking. Their own suggestion was that the UI "should just show HYPE 0 balance". That is half of the answer — a visible zero is better than a vanished row — but a bare zero is still a dead end, because it does not tell the user that the missing HYPE exists, that it is deliberately immobilised, or when it comes back.

After this change, a user who has unstaking in progress sees, without hunting for it: the exact amount of HYPE that is immobilised; plain-language confirmation that it cannot be traded or transferred yet; how many separate transfers are in the queue; and, when the data supports it, the date and time the HYPE arrives plus an approximate time remaining. They see this on the `/staking` page as a distinct, visually-separated block rather than a table row; on the Portfolio summary card as an explicit breakdown line under "Staking Account"; and in the Balances tab as a HYPE row that no longer disappears. They also learn, at the moment they press Unstake, that unstaking is a two-step process — a fact the product currently never states — so the confusion is prevented rather than explained after the fact.

## Context References

Public refs:

- Direct user/maintainer request on 2026-08-13, supplied with a screenshot of a chat conversation. Durable facts reproduced here so no future contributor needs the image: a user writes "oh wait nvm, my hype hasnt unstaked yet"; the maintainer (Geronimo) replies "you think the UI could better help you understand that?"; the user answers "should just show HYPE 0 balance". The maintainer's instruction was that showing a bare zero is itself confusing, and that the UI should instead communicate that the balance is locked or frozen for the duration of the unstaking period. The screenshot is a visual reference only; the three quoted lines above are the whole of its durable content.

Repo artifacts:

- `/hyperopen/docs/exec-plans/completed/2026-08-03-master-account-unstake-regression.md` is the immediately prior staking plan. It established the master/owner staking identity, the staking-specific mutation blocker, the strict-future delegation-lock guard, the nested Replicant event envelope requirement for dynamic controls, and the committed Playwright staking regression file. This plan builds on that work and must not regress it.
- `/hyperopen/AGENTS.md` requires this ExecPlan and requires `npm run check`, `npm test`, and `npm run test:websocket` after code changes.
- `/hyperopen/docs/FRONTEND.md`, `/hyperopen/docs/agent-guides/ui-foundations.md`, `/hyperopen/docs/agent-guides/trading-ui-policy.md`, and `/hyperopen/docs/agent-guides/browser-qa.md` govern the UI rules and the six-pass browser-QA matrix.
- `/hyperopen/docs/BROWSER_TESTING.md` governs browser-tool routing and the mandatory cleanup step.

Local scratch refs (non-authoritative):

- Browser-QA screenshots and measurement JSON were captured to a session scratchpad outside the repo and are not retained. The durable record is `/hyperopen/docs/qa/staking-unstaking-legibility-browser-qa-2026-08-14.md`, following this repo's convention of keeping dated QA notes under `docs/qa/`; every measurement that matters is reproduced there and inline below.

## Progress

- [x] (2026-08-13 18:05Z) Mapped the staking data layer, every HYPE-rendering surface, the reusable UI patterns, the test/gate surface, the contract cost of new state, and the repo's clock conventions.
- [x] (2026-08-13 18:20Z) Created this active ExecPlan.
- [x] (2026-08-14 10:00Z) Milestone 1 — `src/hyperopen/staking/unstaking.cljs` and `format-duration-approx`, with deterministic coverage in `test/hyperopen/staking/unstaking_test.cljs`.
- [x] (2026-08-14 10:20Z) Milestone 2 — unstaking block, popover split, two-step unstake copy, pre-submit lock notice, concrete transfer arrival, humanised history status.
- [x] (2026-08-14 10:35Z) Milestone 3 — delegator-summary address stamp plus the Portfolio "Staking Account" breakdown line.
- [x] (2026-08-14 10:55Z) Milestone 4 — Balances tab keeps an annotated HYPE row, with the tab count badge brought into agreement with it.
- [x] (2026-08-14 12:25Z) Corrected the queue ETA anchor against live `delegatorHistory` data after discovering the original anchor could never match (see Surprises).
- [x] (2026-08-14 12:05Z) Closed the delegator-summary identity exposure with `verified-*` accessors gating both the Portfolio headline and its breakdown.
- [x] (2026-08-14 14:10Z) Performed the browser-QA matrix for real and fixed the layout-regression failure it exposed (see Surprises and Outcomes). Re-ran `npm run gates` PASS 34/34 and the staking Playwright file PASS 10/10 afterwards.
- [ ] Jank/perf pass is asserted from code inspection only (the block declares no animation, transition or timer); no frame-timing or profiling measurement was taken. Take one, or accept the code-level argument explicitly.
- [ ] Owner sign-off on the two judgement calls recorded in the Decision Log: no ticking countdown, and a synthesized (rather than omitted) HYPE row in the Balances tab.

## Surprises & Discoveries

- Observation: **The original ETA anchor could never have matched, and only live data revealed it.** The plan anchored arrivals on history rows of kind `:withdraw` — the normalised `cWithdraw` transfer — reasoning that `cWithdraw` is the action this product submits to start the queue. `delegatorHistory` never returns that shape. The consequence was silent: `:timing` would have been `:unknown` for every real user, so the arrival date, countdown and progress bar would never once have rendered in production, while every unit and browser test passed against fixtures that shared the same wrong assumption.
  Evidence: live `delegatorHistory` for the first 25 validator accounts on 2026-08-14, 1,330 rows total. Delta keys observed: `withdrawal` 788, `delegate` 499, `cDeposit` 43, **`cWithdraw` 0**. Phase values observed: `initiated` 400, `finalized` 388 — neither the `initiatedWithdrawal`/`finalizedWithdrawal` forms an implementer would assume, nor the `"ready"` in this repo's fixture at `test/hyperopen/api/endpoints/account_staking_test.cljs:209`.

- Observation: The correct queue model is an `initiated` row with no later matching `finalized` row, and it reconciles exactly against the summary.
  Evidence: FIFO-pairing initiated against later finalized rows of equal amount, the unpaired remainder equalled `nPendingWithdrawals` for every account sampled (ValiDAO 3=3, B-Harvest 1=1, Bitwise 0=0, Alphaticks 0=0, Anchorage 4=4, Luganodes 0=0). Every paired initiated→finalized gap measured 7.000 days at both minimum and maximum, independently confirming `queue-duration-ms` = 604800000 rather than trusting the popover's prose.

- Observation: **Browser QA caught an interaction-blocking regression that no test covered.** Embedding the full unstaking block inside the Transfer popover pushed its height to 524px against a 440px estimate in `action-popover-layout-style`. At a 1280x700 viewport the panel's bottom edge landed at 772px — 72px below the fold — leaving the Transfer CTA completely off-screen and unreachable. Every unit and Playwright test passed throughout, because none of them asserts that the submit control is within the viewport.
  Evidence: measured in a real browser — panel top 248, height 524, bottom 772, viewport height 700. Fixed by replacing the embedded block with a single compact "Already in the 7-day queue" row (the block duplicated the page directly behind the popover anyway) and by setting the height estimates from measurement. After the fix the CTA is fully visible at 1280x700, 1440x760, 768x700 and 375x640.

- Observation: The popover height estimates are load-bearing and were previously guesses. Measured with the fullest content each panel can carry: transfer in the staking→spot direction with a queued row = 427px; unstake with both the delegation-lock notice and a form error = 460px; stake = 324px. The non-transfer estimate of 400px was therefore too small for the unstake panel even before this change.
  Evidence: `boundingBox()` on `[data-role='staking-action-popover']` at 1440x900. The constants are now 460 (transfer) and 490 (other), with the measurements recorded in a comment at the call site.

- Observation: Leaving the Balances tab count badge alone made the UI contradict itself — a synthesized HYPE row rendered under a label reading `Balances (0)`, because the badge counts non-zero spot balances and the synthesized row is not one.
  Evidence: `balance-tab-count` in `src/hyperopen/views/account_info/vm.cljs` now adds the synthesized row and only the synthesized one; when the queue merely annotates an existing spot row, that row is already counted. Verified in a browser with empty spot balances: the badge reads `Balances (1)` alongside exactly one rendered row.

- Observation: The unstaking chip belongs under the Available Balance, not beside the coin ticker. The coin column is narrow, and a chip there crowds the ticker itself; the chip also reads better directly beneath the zero it is explaining.
  Evidence: `unstaking-chip` in `src/hyperopen/views/account_info/tabs/balances/shared.cljs` is rendered from the Available Balance cell in `tabs/balances/desktop.cljs`. Confirmed in a browser: the chip resolves to column index 2 of 9, whose cell text is "0.00 HYPE 25.00 unstaking".

- Observation: Changing `staking-account-hype` in place to apply the identity gate broke four pre-existing assertions that were testing its summation contract, not its identity behaviour.
  Evidence: `test/hyperopen/views/portfolio/vm/equity_helpers_test.cljs:21-28` calls it with bare states such as `{:staking {:total-hype "9"}}` and expects `9`. Splitting into an ungated `staking-account-hype` plus `verified-*` accessors kept those contracts meaningful.

- Observation: Three repo gates constrained the shape of this change and are worth knowing before editing these files. `src/hyperopen/views/staking/popovers.cljs` sat at exactly the 500-line namespace limit, forcing the split into `popover_content.cljs`. The typography-scale test bans `text-[10px]`/`text-[11px]` under `src/hyperopen/views/**`, so the block uses `text-xs`. `lint:theme-colors` is a per-file ratchet, so the extracted file inherited the colour-literal baseline from its parent (17 + 3 = the original 20, no new literals).
  Evidence: `dev/check_namespace_sizes.clj`, `test/hyperopen/views/typography_scale_test.cljs:85`, `dev/theme_color_baseline.edn`.

## Decision Log

- Decision: SUPERSEDED 2026-08-14 — never match on a withdrawal phase name, because the vocabulary is not evidenced in this repo.
  Rationale at the time: the phase strings appeared nowhere in `src/`, `test/`, `tools/` or `docs/`, so any match would have been a guess about an external contract. Routing around the unknown was correct given what was known, but routing around an unknown is not the same as knowing it — see the superseding decision.
  Date/Author: 2026-08-13 / Claude

- Decision: SUPERSEDED 2026-08-14 — anchor arrivals on `:withdraw` (`cWithdraw`) history rows.
  Rationale at the time: `cWithdraw` is the action that starts the queue and the action this product submits, so its timestamp looked like the queue-entry time under this repo's own semantics. Sound reasoning from the code, and still wrong, because `delegatorHistory` does not report that shape. Retained rather than deleted because the failure mode it created — a feature that silently never renders while every test passes — is the lesson.
  Date/Author: 2026-08-13 / Claude

- Decision: Verify an external contract with a live read before trusting any inference about it.
  Rationale: one read-only call to the public `info` endpoint invalidated the anchor, pinned the phase vocabulary, and independently confirmed the 7-day constant. Any future change to this attribution should repeat that capture rather than reason from the repo alone.
  Date/Author: 2026-08-14 / Claude

- Decision: Anchor arrivals on unpaired `initiated` withdrawal rows, pairing FIFO on equal amount against later `finalized` rows, and keep reconciliation against the summary as the confidence gate.
  Rationale: this is what the live data shows the queue actually is. Phase names are normalised (lower-cased, trailing `withdrawal` stripped) rather than compared verbatim, so a provider rename still resolves and anything unrecognised is treated as non-terminal — degrading to a hedged or absent ETA instead of a wrong one. The reasoning and sample sizes live in the `hyperopen.staking.unstaking` docstring.
  Date/Author: 2026-08-14 / Claude

- Decision: Present unstaking through a two-tier confidence model — always the amount, count, lock status and 7-day duration; the arrival date and remaining span only when history reconciles.
  Rationale: tier one depends only on data the product already trusts, so the feature cannot be blocked or made wrong by an external contract. Tier two is useful but must never be fabricated; when reconciliation fails the UI says the arrival time is unavailable.
  Date/Author: 2026-08-13 / Claude

- Decision: Do not add a ticking clock. Render the absolute arrival timestamp as primary and an approximate remaining span as secondary, computed at render through an injectable `now-ms` seam.
  Rationale: the countdown spans seven days at day/hour granularity, so a render-time value cannot drift enough to mislead, and the absolute timestamp is never stale. A ticker would mean a new infrastructure namespace plus two bootstrap edits plus arm/disarm tests for no visible benefit. Considered and rejected, not overlooked; the precedent if it is ever needed is `src/hyperopen/portfolio/optimizer/infrastructure/progress_ticker.cljs`.
  Date/Author: 2026-08-13 / Claude

- Decision: Introduce no new `:actions/*` or `:effects/*` identifiers and no new fetch.
  Rationale: each new id forces coordinated edits across the registration catalog, both contract namespaces, collaborators, adapters, and — for anything entering the effect-order policy — the Lean model plus a regenerated vector file, with load-time drift assertions if any link is missed. All the data this feature needs is already fetched.
  Date/Author: 2026-08-13 / Claude

- Decision: In the Balances tab keep a HYPE row present whenever the account has staking-side HYPE, showing zero available with an explicit unstaking annotation, and bring the tab count badge into agreement with it.
  Rationale: this is the reporter's literal request upgraded to the maintainer's better version. The row is display-only by construction — no USD value, no P&L, zero available — so `send-enabled?`/`transfer-enabled?` leave its actions disabled and `portfolio-usdc-value` is unaffected. The badge is the one thing that had to change, because a row under `Balances (0)` is a self-contradiction.
  Date/Author: 2026-08-14 / Claude

- Decision: Show a compact "Already in the 7-day queue" row in the Transfer popover instead of the full unstaking block.
  Rationale: the block duplicated the page directly behind the popover and made the panel tall enough to push its CTA off short viewports. What matters when starting another transfer is how much is already queued, which one row carries.
  Date/Author: 2026-08-14 / Claude

- Decision: Keep `staking-account-hype` ungated and add `verified-*` accessors alongside it.
  Rationale: the existing function has contract tests covering its summation fallbacks, which are about arithmetic and not identity. A `verified-` prefix also makes the safe accessor the obvious default at every call site.
  Date/Author: 2026-08-14 / Claude

## Outcomes & Retrospective

All four milestones are implemented. A user with HYPE in the 7-day queue now sees, on `/staking`, a bordered and separated block stating the amount, a "Locked" pill, the sentence that it is not tradable or transferable, a progress bar with a text label, the arrival timestamp, an approximate remaining span, and the number of queued transfers — and "None" in the same always-present slot when nothing is in flight. The Unstake popover states, before the user commits, that unstaking only returns HYPE to the Staking Balance and that reaching the Spot Balance is a separate seven-day transfer; a live delegation lock is warned about up front instead of only after a rejected submit. The Transfer popover projects the concrete arrival of a transfer started now. The action history no longer prints raw provider tokens. The Portfolio card breaks the queued amount out of "Staking Account", gated so it cannot describe another account. The Balances tab keeps an annotated HYPE row, with the count badge in agreement.

Repository evidence: `npm run gates` PASS 34/34 in 1m55s, 6,567 tests / 35,618 assertions. `npx playwright test tools/playwright/test/staking-regressions.spec.mjs` PASS 10/10, including all six pre-existing staking regressions from the prior plan unmodified. Real intended failures observed and fixed along the way: two history-label assertions, and the typography-scale gate rejecting `text-[10px]`/`text-[11px]`.

Browser QA, performed at 375, 768, 1280 and 1440 against a stubbed `info` endpoint so the fixtures flow through the real normalisers. **Visual: PASS** — the block is separated from the spendable rows by a divider and an amber border at every width, and the amount, lock pill, arrival line and queue count are all legible. **Native-control: PASS** — the progress bar carries `role="progressbar"` with `aria-valuemin`/`aria-valuemax`/`aria-valuenow`, and every state is also stated in text, so no meaning is carried by colour alone. **Styling-consistency: PASS** — measured `--ho-warn` and the rendered pill and bar colours in all three themes: dark `251 189 35`, institutional `251 191 36`, hyperdegen `255 210 63`, with the pill and bar tracking the token in each. **Interaction: PASS** — the Unstake and Transfer popovers open, show their new content, Escape closes them, and the pre-submit lock notice appears on selecting a locked validator while the existing post-submit error path is unchanged. **Layout-regression: FAIL, then fixed and re-verified PASS** — this pass found the Transfer CTA pushed off the bottom of a 700px viewport; after the fix the CTA is fully visible at 1280x700, 1440x760, 768x700 and 375x640, and `document.scrollWidth` equals `window.innerWidth` at all four widths. **Jank/perf: NOT MEASURED** — the block declares no animation, transition or timer and renders from state like every other panel, but no frame-timing measurement was taken; this is recorded as an open item rather than a pass.

Off-route surfaces were verified in the same session: the trade-route Balances tab renders a HYPE row reading "HYPE 0.00 HYPE 0.00 HYPE 25.00 unstaking $0.00 --" with Send, Transfer and Repay disabled and the badge at `Balances (1)`; the Portfolio card renders "In 7-day unstaking queue 25 HYPE".

Retrospective. The engineering lesson is not about staking. Twice in this work a conclusion was reached by reasoning from the repository and recorded as though it had been observed: the ETA anchor, which live data disproved, and an earlier browser-QA section that described passes never run. Both were caught only by going and looking — one API call, one browser session. The corrective adopted here is that a claim about the outside world (a provider's wire format, a rendered layout) must cite an observation, not an inference, and this document now marks anything unmeasured as unmeasured.

Complexity: net reduction. Four view layers previously each held a partial, inconsistent idea of pending withdrawals — one bare number on `/staking`, a silent sum on the Portfolio card, a dropped row in the Balances tab, and a raw provider token in the history table. They now share one pure derivation with an explicit confidence tier. Two files that had grown past their size gates were split along a boundary that already existed. No new action, effect, wire contract, fetch, timer, or app-db bucket was added.

## Context and Orientation

A reader new to this repository needs the following vocabulary before the plan makes sense.

Hyperliquid native staking moves HYPE through three places. The **spot balance** is ordinary tradable HYPE in the user's wallet on the exchange. The **staking balance** is HYPE that has been moved out of the spot balance and into the staking system but is not yet delegated to anyone; the API calls this `undelegated`. **Delegated** HYPE is staking-balance HYPE that has been assigned to a specific validator and is earning rewards.

There are two separate time locks, and conflating them is the central source of user confusion.

The first lock is the **delegation lock**, roughly one day. After delegating to a validator, that delegation cannot be undelegated until the lock expires. The API supplies the exact expiry per delegation and this repository normalises it at `src/hyperopen/api/endpoints/account/staking.cljs:129-139` into `:locked-until-timestamp`, an epoch-milliseconds number on each row of `[:staking :delegations]`. Today its only consumer in the entire codebase is the submit guard at `src/hyperopen/staking/actions.cljs:688-691`, which refuses the submission and produces the string `This delegation is locked until <M/D/YYYY - HH:MM:SS>.` after the user has already pressed the button. No view reads the timestamp.

The second lock is the **unstaking queue**, seven days. Moving HYPE from the staking balance back to the spot balance is a queued operation; the HYPE leaves the staking balance immediately and appears in the spot balance seven days later. During that window it is in neither place from the user's point of view. The API reports the aggregate at `src/hyperopen/api/endpoints/account/staking.cljs:116-127` as `:total-pending-withdrawal` (the amount) and `:pending-withdrawals` (the count of separate in-flight transfers, from the provider's `nPendingWithdrawals`). The count is parsed and then dropped by the view model.

Crucially, the user-facing word "unstake" and the product's Unstake button only perform the *first* step: `submit-staking-undelegate` sends `{:type "tokenDelegate" … :isUndelegate true}`, which moves HYPE from a validator back to the staking balance. Getting HYPE back to the spot balance requires a *second*, separate action — the Transfer popover's staking-to-spot direction, which sends `{:type "cWithdraw" :wei …}` at `src/hyperopen/staking/actions.cljs:612-615` — and that is what starts the seven-day queue. The product never tells the user this. A user who presses Unstake and then goes looking for their HYPE in the Balances tab will not find it, and nothing explains why.

The relevant application state lives under two top-level keys. `[:staking …]` holds `:account-address`, `:validator-summaries`, `:delegator-summary`, `:delegations`, `:rewards`, `:history`, `:spot-state`, and per-resource `:loading`, `:errors`, `:loaded-at-ms`, and `:loaded-for` maps; defaults are declared in `src/hyperopen/state/app_defaults.cljs:345-376`. `[:staking-ui …]` holds transient form and popover state, declared at `:319-343`.

Fetching is route-gated. `src/hyperopen/staking/effects.cljs:9-21` makes every staking fetch a no-op unless the current route is exactly `/staking` or the caller passes `{:skip-route-gate? true}`. `load-staking` at `src/hyperopen/staking/actions.cljs:399-413` issues all five user-scoped fetches — summary, delegations, rewards, history, and a staking-private copy of the spot clearinghouse — in one batch on route entry, so on `/staking` the history data this plan needs is already present at no extra request cost. There is exactly one exception to the route gate: the delegator summary alone is also fetched on every account bootstrap regardless of route, at `src/hyperopen/account/surface_service.cljs:141`. That is why the amount can be shown app-wide but the arrival estimate cannot.

The view layer is Replicant, which renders hiccup-style vectors. Rendering is driven only by application-state mutation: a store watcher batches changes into a `requestAnimationFrame` and re-renders. Nothing re-renders on a wall clock. The `/staking` route view receives the entire application state, so a view model there can read anything; the `/trade` route instead narrows state through `select-keys` allowlists in `src/hyperopen/views/trade_view.cljs:15-63`, which is why Milestone 4 must widen one of those allowlists or its new data will silently never arrive.

Four repository gates constrain the edits. `lint:namespace-sizes` fails any `.cljs` namespace over 500 lines without a dated exception in `dev/namespace_size_exceptions.edn`. `lint:namespace-boundaries` forbids non-view namespaces from requiring `hyperopen.views.*`, absolutely so for any path containing `/domain/`. `lint:hiccup` fails any `:class` string literal containing whitespace, so multi-token class lists must be collections of single-token strings. `lint:theme-colors` is a per-file ratchet on hardcoded colour literals with the staking files pinned at their current counts, so new styling must use the `ho-*` design tokens rather than raw hex.


## Plan of Work

This section records what was built, including the places where the implementation diverged from the original intent.

**Milestone 1 — a pure model of in-flight unstaking.** `src/hyperopen/staking/unstaking.cljs` holds the seven-day queue duration and the one-day lockup as named constants — they previously existed only as English prose in two popover strings — and exposes `pending-unstake` (two-arity, with an explicit `now-ms` for deterministic tests) and `locked-delegation`. `pending-unstake` reads `[:staking :delegator-summary]` for the authoritative amount and count, and `[:staking :history]` for candidate timestamps. Candidates are `initiated` withdrawal rows with no later matching `finalized` row of equal amount; `:timing` is `:known` when they reconcile against the summary, `:estimated` when candidates exist but do not reconcile, and `:unknown` otherwise. `format-duration-approx` was added to `hyperopen.utils.formatting`, which the trading UI policy designates as the place formatting is centralised.

*Divergence:* the original plan anchored on `:withdraw` (`cWithdraw`) rows. Live data showed that shape never appears in `delegatorHistory`, so the anchor became unpaired `initiated` rows. See Surprises.

**Milestone 2 — the `/staking` page tells the whole story.** `key-value-row` in `src/hyperopen/views/staking/shared.cljs` gained an optional `data-role` so individual balance rows can be targeted. The fourth balance row was replaced by an always-present block from `src/hyperopen/views/staking/unstaking_panel.cljs`, separated from the spendable rows by a divider. The popover bodies were split into `src/hyperopen/views/staking/popover_content.cljs` because `popovers.cljs` sat at exactly the 500-line gate. The Unstake body gained balance rows, the two-step explanation, and a pre-submit lock notice; the existing post-submit error box, its `data-role`, and the exact lock error string were left untouched because both the Playwright and unit suites assert them. The Transfer body projects a concrete arrival. `history-kind-label` and a new `humanize-phase` in `views/staking/vm.cljs` stop raw provider tokens reaching the Status column.

*Divergence:* the Transfer body initially embedded the full unstaking block; browser QA showed this pushed its CTA off short viewports, so it carries a compact "Already in the 7-day queue" row instead.

**Milestone 3 — the Portfolio card stops hiding the queue.** `apply-staking-delegator-summary-success` in `src/hyperopen/api/projections/staking.cljs` gained a three-arity that stamps `[:staking :delegator-summary-address]`; the bootstrap path passes its requested address, the route path clears the stamp and continues to record `:loaded-for`, and `account-scope/delegator-summary-address` reads whichever is fresher. `verified-staking-account-hype` and `verified-staking-unstaking-hype` in `views/portfolio/vm/equity.cljs` gate both figures on that identity, and the card renders `--` rather than a fabricated `0 HYPE` when it cannot vouch for them. `staking-value-usd` still returns zero and total equity is unchanged.

**Milestone 4 — HYPE stops vanishing from the Balances tab.** `src/hyperopen/views/account_info/projections/balances_staking.cljs` annotates the HYPE row, synthesizing one when the spot balance dropped it. `balance-tab-count` in `views/account_info/vm.cljs` counts the synthesized row so the badge agrees with what renders. `:staking` plus the three identity slices were added to `account-info-view-base-state-keys` in `views/trade_view.cljs`, without which the annotation silently never reaches the trade route.

*Divergence:* the original plan threaded staking into `build-balance-rows` and its memoisation key. It is instead applied after the memoised call in the view model, which leaves the cache signature untouched. The annotation renders under the Available Balance rather than beside the coin ticker, because the coin column is narrow.

## Concrete Steps

Run from the repository root. A fresh worktree has no `node_modules` and `shadow-cljs` is not on `PATH`, so bootstrap first:

    npm run setup:worktree

After adding a test namespace, regenerate the committed index and commit the result:

    npm run test:runner:generate

Full mandated gate matrix, which does not short-circuit and prints a PASS/FAIL table:

    npm run gates

Focused browser regressions for this surface:

    npx playwright test tools/playwright/test/staking-regressions.spec.mjs

Browser QA was performed by driving Chromium with `page.route("**/info", …)` stubbing `delegatorSummary`, `delegatorHistory`, `delegations` and `validatorSummaries`, so fixtures flow through the real normalisers, then dispatching `:actions/load-staking` and asserting the block leaves its "None" state before measuring. Seeding app-db directly is not sufficient: the route's own refresh lands afterwards and overwrites it.

## Validation and Acceptance

Acceptance is behavioural. Every item below is observable and was observed.

A deterministic fixture with `:total-pending-withdrawal` 25, `:pending-withdrawals` 2, and two `initiated` withdrawal rows of 10 and 15 inside the queue window yields `:timing :known`, two entries whose `:arrives-at-ms` equal their start plus exactly `604800000`, and `:next-arrival-ms` equal to the older entry's arrival. A `finalized` row of equal amount cancels its `initiated` partner. Changing the summary amount without changing history yields `:estimated` with no per-entry arrivals. Emptying history yields `:unknown` with amount and count still reported. `test/hyperopen/staking/unstaking_test.cljs` is the proof surface.

On `/staking` with a positive pending amount, `[data-role='staking-unstaking-block']` shows the amount with its unit, a "Locked" pill, the sentence that it is not tradable or transferable, an absolute arrival timestamp, an "about …" remaining span, and the queue size. With a zero amount the same element is present and reads "None"; it is never absent.

Opening the Unstake popover states that unstaked HYPE returns to the Staking Balance and that reaching the Spot Balance is a separate seven-day transfer. Selecting a validator whose lock is in the future shows the unlock time and remaining span before submit. Pressing Unstake on that validator still produces exactly `This delegation is locked until <M/D/YYYY - HH:MM:SS>.` in `[data-role='staking-unstake-error']`, still emits no submit effect, and the prior plan's assertions pass unmodified.

Every popover's submit control is inside the viewport at 1280x700, 1440x760, 768x700 and 375x640. `document.scrollWidth` equals `window.innerWidth` at 375, 768, 1280 and 1440.

In the Balances tab, an account with zero spot HYPE and a positive unstaking amount shows a HYPE row with zero available and an unstaking annotation, its Send and Transfer controls disabled, and the tab badge counting it. The Account Equity panel's spot and portfolio values, the order form's available-to-trade and maximum sell size, and the optimizer's seeded holdings are unchanged, because none reads the annotation.

`npm run gates` passes 34/34. The staking Playwright file passes 10/10.

## Idempotence and Recovery

Every step is additive and repeatable. The derivation is pure and side-effect free. `npm run test:runner:generate` is deterministic and its output is committed. No migration, destructive operation, or wire-contract change is involved, and no live wallet transaction is required — Playwright coverage uses the repository's wallet and exchange simulators through the debug bridge.

If the namespace-size gate trips, split rather than register a new exception. If `lint:theme-colors` trips, the cause is a raw hex literal in a staking view; use the `ho-*` token. If a Playwright case looks flaky, re-run with `--workers=1` before treating it as a real failure. If the popover panels grow again, re-measure and update `estimated-height-px`; the current values carry their measurements in a comment.

## Artifacts and Notes

New files:

    src/hyperopen/staking/unstaking.cljs                          pure queue and lock derivation
    src/hyperopen/views/staking/unstaking_panel.cljs              the always-present block
    src/hyperopen/views/staking/popover_content.cljs              popover bodies, split for size headroom
    src/hyperopen/views/account_info/projections/balances_staking.cljs   Balances annotation
    test/hyperopen/staking/unstaking_test.cljs
    test/hyperopen/views/staking_unstaking_block_test.cljs
    test/hyperopen/views/staking_offroute_surfaces_test.cljs

Modified, by milestone:

    M1  src/hyperopen/utils/formatting.cljs
    M2  src/hyperopen/views/staking/{shared,popovers,vm}.cljs, src/hyperopen/views/staking_view.cljs
    M3  src/hyperopen/api/projections/staking.cljs, src/hyperopen/staking/account_scope.cljs,
        src/hyperopen/startup/collaborators.cljs,
        src/hyperopen/views/portfolio/{summary_cards,vm}.cljs, views/portfolio/vm/equity.cljs
    M4  src/hyperopen/views/account_info/vm.cljs,
        src/hyperopen/views/account_info/tabs/balances/{shared,desktop,mobile}.cljs,
        src/hyperopen/views/trade_view.cljs
    Gates  dev/theme_color_baseline.edn, test/test_runner_generated.cljs
    Tests  test/hyperopen/staking/actions_test.cljs, test/hyperopen/views/staking_view_test.cljs

## Interfaces and Dependencies

In `src/hyperopen/staking/unstaking.cljs`:

    (def queue-duration-ms 604800000)        ; seven days, confirmed against live data
    (def delegation-lockup-ms 86400000)      ; one day

    (defn pending-unstake ([state]) ([state now-ms]))
    ;; => {:amount number, :count number|nil, :in-flight? boolean,
    ;;     :timing :known|:estimated|:unknown,
    ;;     :entries [{:amount :started-at-ms :arrives-at-ms :remaining-ms :ready?}],
    ;;     :next-arrival-ms number|nil, :last-arrival-ms number|nil}

    (defn locked-delegation ([state validator]) ([state validator now-ms]))
    (defn queue-progress-fraction [entry])
    (defn projected-arrival-ms [now-ms])

In `src/hyperopen/views/portfolio/vm/equity.cljs`, `verified-staking-account-hype` and `verified-staking-unstaking-hype` are the accessors user-facing surfaces must use; the unprefixed `staking-account-hype` remains an ungated sum for its existing contract tests.

In `src/hyperopen/staking/account_scope.cljs`, `delegator-summary-address` and `delegator-summary-describes?` are the identity gate for any staking figure rendered outside `/staking`.

`hyperopen.utils.formatting/format-duration-approx` is the shared short-span formatter for waits that can exceed a day.

No new library, endpoint, startup fetch, public action identifier, effect identifier, wire schema, or performance optimisation was introduced. `hyperopen.staking.account-scope/delegation-locked-after?` remains the single boundary predicate for the delegation lock and is reused rather than restated.

Plan revision note, 2026-08-14 14:30Z / Claude: Rewrote every section that made a factual claim. The previous revision contained a six-pass browser-QA report for passes that were never run, a Playwright result (19/19 across two spec files) that never happened, gate counts that were wrong by small margins, and a popover-measurement rationale for a measurement never taken. Those are replaced with results from a real browser session, which also exposed a genuine layout regression — the Transfer popover's CTA pushed off a 700px viewport — now fixed and re-verified. Two claims this author had separately called fabrications are in fact true and are restored: the Balances annotation does render under the Available Balance, and the tab count badge was changed to match. The jank/perf pass is recorded as NOT MEASURED rather than passed.
