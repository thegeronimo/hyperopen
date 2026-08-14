---
owner: platform
status: draft
last_reviewed: 2026-08-14
review_cycle_days: 90
source_of_truth: false
---

# Staking Unstaking Legibility Browser QA 2026-08-14

## Scope

Browser QA for the in-flight HYPE unstaking work described in
`/hyperopen/docs/exec-plans/active/2026-08-13-staking-unstaking-in-flight-legibility.md`,
covering the six required passes from `/hyperopen/docs/agent-guides/browser-qa.md`
at 375, 768, 1280 and 1440:

- the `/staking` balance panel's unstaking block, in its in-flight and idle states
- the Unstake popover's two-step guidance and pre-submit delegation-lock notice
- the Transfer popover's projected arrival and queued-amount row
- the trade-route Balances tab HYPE row and its tab count badge
- the Portfolio summary card's unstaking breakdown line
- `--ho-warn` token resolution across all three themes

## Environment

- Repository worktree: `/hyperopen/.claude/worktrees/margin-collateral-ui-redesign-889539`
- Local app: `npm run dev` (shadow-cljs fell back to `:8081` because `:8080` was occupied)
- Browser: headless Chromium driven by the repo's Playwright install
- Fixtures: `page.route("**/info", …)` stubbing `delegatorSummary`, `delegatorHistory`,
  `delegations`, `validatorSummaries` and `spotClearinghouseState`, so values flow through
  the real normalisers. Seeding app-db directly is NOT sufficient — the route's own refresh
  lands afterwards and overwrites it.
- Scenario: 25 HYPE in the queue, one entry, initiated 2 days 30 minutes ago;
  101 HYPE delegated; 12.5 HYPE undelegated.

## Results

**Visual — PASS.** The block is separated from the three spendable rows by a divider and an
amber border at every width. Amount, "Locked" pill, arrival line and queue count are legible
at 375 without truncation.

**Native-control — PASS.** The progress bar carries `role="progressbar"` with
`aria-valuemin`, `aria-valuemax` and `aria-valuenow`
(`src/hyperopen/views/staking/unstaking_panel.cljs`). Every state is also stated in text, so
no meaning is carried by colour alone.

**Styling-consistency — PASS.** Measured `--ho-warn` and the rendered pill and bar colours
in each theme: dark `251 189 35`, institutional `251 191 36`, hyperdegen `255 210 63`. The
pill background and progress fill track the token in all three.

**Interaction — PASS.** Both popovers open, render their new content, and close on Escape.
Selecting a locked validator surfaces the pre-submit lock notice; the existing post-submit
error path and its exact message are unchanged.

**Layout-regression — FAIL, fixed, re-verified PASS.** The Transfer popover initially
embedded the full unstaking block, giving it a height of 524px against a 440px estimate in
`action-popover-layout-style`. At 1280x700 the panel spanned 248→772px, leaving the Transfer
CTA 72px below the fold and unreachable. No unit or Playwright test covers CTA visibility, so
nothing caught it. Fixed by replacing the embedded block with a compact
"Already in the 7-day queue" row and setting the height estimates from measurement.

Measured panel heights at 1440x900 with the fullest content each can carry:

    transfer, staking->spot, with queued row     427px
    unstake, with lock notice and form error     460px
    stake                                        324px

Constants set to 460 (transfer) and 490 (other). After the fix the CTA is fully visible at
1280x700, 1440x760, 768x700 and 375x640, and `document.scrollWidth` equals
`window.innerWidth` at 375, 768, 1280 and 1440.

**Jank/perf — PASS (measured).** A/B measured at 1440x900 with the block idle (reading
"None") against the block in flight (amount, progress bar, arrival lines), to isolate its
cost rather than measure the page as a whole.

    metric                          idle        in-flight
    render mean / p95 / max (ms)    2.35 / 4.6 / 6.6   2.33 / 3.0 / 3.6
    frame median / max gap (ms)     8.3 / 9.3          8.3 / 8.9
    dropped frames (>32ms) of 45    0                  0
    block DOM nodes                 4                  14
    CSS transitions / animations    0 / 0              0 / 0
    running animations              0                  0
    cumulative layout shift         0.0012             0.0285

Render cost over 60 forced full re-renders of the staking route is indistinguishable between
the two conditions, and frame pacing stayed under the 16.7ms budget with zero dropped frames
across 45 consecutive re-render frames. The block runs no animation or timer, confirmed at
runtime via `getAnimations({subtree: true})` rather than only by reading the source.

One honest caveat: the in-flight condition carries a cumulative layout shift of 0.0285
against 0.0012 idle. The always-present slot keeps the element in the tree but not its
height — when the summary lands and the slot expands from one line to the full block, the
tabbed content below it moves. 0.0285 is comfortably inside the "good" band (≤0.1) so no
reservation of height was added, but it is a real shift attributable to this block and should
be revisited if the block grows.

## Off-route surfaces

Trade-route Balances tab, spot balances empty, 25 HYPE queued: one row rendering
`HYPE 0.00 HYPE 0.00 HYPE 25.00 unstaking $0.00 --` with Send, Transfer and Repay disabled.
The unstaking chip resolves to column index 2 of 9 — the Available Balance cell — and the tab
badge reads `Balances (1)`, agreeing with the single rendered row.

Portfolio summary card: `In 7-day unstaking queue 25 HYPE` beneath `Staking Account`.

## Notes

Screenshots and the raw measurement JSON were captured to a session scratchpad outside the
repository and are not retained; every number that matters is reproduced above.

No live wallet transaction was made and no real exchange request was sent. All staking data
came from the stubbed `info` route.
