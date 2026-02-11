# Agent Wallet Manual QA Matrix

## Purpose

Validate agent-wallet order signing end-to-end before rollout.

This matrix complements automated tests by covering wallet-provider behavior, extension prompts, and hardware-wallet timing.

## Test Environments

- Browser: Chrome stable
- Wallet providers:
- MetaMask software account
- MetaMask + hardware wallet account
- Networks:
- Arbitrum mainnet (`0xa4b1`) for production-like checks
- App persistence modes:
- `Device` (localStorage)
- `Session` (sessionStorage)

## Execution Notes

- Use a clean browser profile at least once for first-time setup checks.
- Record pass/fail and evidence for each case:
- screenshot of UI state
- request/response snippet for relevant `/exchange` calls
- wallet prompt behavior (shown/not shown)
- For hardware-wallet flows, allow up to 5 minutes for signing before declaring timeout failure.

## Matrix

| ID | Scenario | Steps | Expected |
|---|---|---|---|
| AW-01 | Connect wallet | Click `Connect Wallet` and approve in provider | Header shows connected address; no order signing prompt yet |
| AW-02 | Enable trading happy path | Open wallet menu, click `Enable Trading`, sign approve message | Status transitions `Awaiting signature...` -> `Trading enabled`; exactly one `approveAgent` `/exchange` call succeeds |
| AW-03 | No per-order wallet prompt | With status `Trading enabled`, submit limit order | Order request is signed in-browser by agent; no MetaMask signing popup |
| AW-04 | Cancel order path | Cancel an open order | Cancel request succeeds without MetaMask signing popup |
| AW-05 | Missing API wallet recovery | Force backend response `User or API Wallet ... does not exist` | UI shows actionable re-enable message; agent state invalidates and requires `Enable Trading` |
| AW-06 | Vault-not-registered response passthrough | Force backend response `Vault not registered: ...` | Error is surfaced to order form; session is not silently wiped |
| AW-07 | Nonce retry | Force first submit response `nonce too low`, second `ok` | One automatic retry occurs; second request uses higher nonce |
| AW-08 | Account switch invalidation | Connect account A, enable trading, switch to account B | Account A session is not reused; account B requires explicit enablement |
| AW-09 | Disconnect invalidation | Enable trading, disconnect wallet, reconnect | Agent session resets; user must enable trading again |
| AW-10 | Persistence mode switch reset | Toggle `Persist trading key` between `Device` and `Session` | Trading setup resets immediately with clear message requiring re-enable |
| AW-11 | Device persistence restore | In `Device` mode, enable trading, hard refresh | Mode and session restore predictably; no forced re-approval unless backend rejects agent |
| AW-12 | Session persistence scope | In `Session` mode, enable trading, close tab/window, reopen app | Session data is cleared and re-enable is required |
| AW-13 | Chain mismatch guidance | Trigger chain mismatch in wallet | User-facing error is clear; no partial ready state |
| AW-14 | Hardware wallet latency | Enable trading via hardware wallet with delayed user interaction | Flow remains pending long enough for user to complete signing; no premature failure |

## Exit Criteria

- All matrix cases pass in at least one clean run.
- `AW-02`, `AW-03`, `AW-04`, `AW-05`, `AW-08`, and `AW-10` must pass on every release candidate.
- Any failure in recovery scenarios (`AW-05`/`AW-06`/`AW-07`) blocks rollout.
