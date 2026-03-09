---
owner: platform
status: canonical
last_reviewed: 2026-03-09
review_cycle_days: 90
source_of_truth: true
---

# Agent-First UI QA Manual Exceptions

Use this runbook only when the scenario runner classifies a failure as `manual-exception` or when the workflow depends on hardware or third-party UI that the debug simulator lane does not own.

## Supported Manual Exceptions

- Real wallet extension approval surfaces that cannot be simulated without bypassing vendor UI.
- Hardware-wallet signing latency, confirmation prompts, or transport failures.
- Browser-level permission prompts outside the app document.
- Third-party provider behavior that depends on real extension state, stored accounts, or external secure elements.

## What Still Runs First

- `npm run qa:pr-ui` for the fixed critical bundle.
- `npm run qa:nightly-ui -- --allow-non-main` for the broad matrix when validating locally.
- Scenario-level evidence from `/hyperopen/tmp/browser-inspection/**` before any human retest.

## Manual Exception Procedure

1. Record the failing scenario id, viewport, and run directory from the scenario summary.
2. Reproduce only the unsupported step with a real extension or hardware wallet.
3. Capture the exact browser state with screenshots plus the relevant scenario JSON and markdown artifacts.
4. If the product behavior is wrong, file or update a `bd` issue linked to the nightly or PR run evidence.
5. If the product behavior is correct but unsupported by automation, keep the result as `manual-exception` and note the unsupported dependency.

## Non-Exceptions

- Anchor-sensitive popovers and mobile sheets: use explicit debug dispatch with anchor bounds, not manual clicks.
- Funding modal, mobile account surface, and position overlay regressions: use the checked-in scenarios first.
- Wallet connect, enable trading, submit-order gating, and cancel-order gating: use the simulator lane first.
