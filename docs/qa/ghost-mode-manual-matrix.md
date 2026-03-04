# Ghost Mode Manual QA Matrix

## Purpose

Validate Ghost Mode account spectating end-to-end before rollout.

This matrix focuses on identity routing, read-only mutation guardrails, and explicit stop controls.

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

- Run with one funded owner wallet and at least one public spectated address with account history.
- Record pass/fail and evidence for each case:
- screenshot of UI state
- request/response snippet for blocked mutation attempts
- keyboard shortcut behavior for Cmd/Ctrl+X

## Matrix

| ID | Scenario | Steps | Expected |
|---|---|---|---|
| GM-01 | Enter Ghost Mode from header | Open wallet controls, click `Ghost Mode`, submit valid address | Header/app banner show active spectating state and target address |
| GM-02 | Enter Ghost Mode from watchlist | Add address to watchlist, click spectate from list | Effective account surfaces switch to selected watchlist address |
| GM-03 | App-level stop control | While spectating, click banner `Stop Ghost Mode` | Spectating stops and account surfaces return to owner address |
| GM-04 | Ticket-level stop control | While spectating on trade screen, use order ticket `Stop Ghost Mode` | Spectating stops without leaving trade view |
| GM-05 | Keyboard stop shortcut | While spectating, press Cmd/Ctrl+X outside inputs | Ghost Mode stops immediately; shortcut does not require modal focus |
| GM-06 | Keyboard collision guard | While spectating, focus a text input and press Cmd/Ctrl+X | Native cut/input behavior is preserved; Ghost Mode remains active |
| GM-07 | Order submit blocked | While spectating, attempt to place limit/market order | Submit is blocked with `Stop Ghost Mode` guidance; no submit mutation sent |
| GM-08 | Order cancel blocked | While spectating with open orders, attempt cancel | Cancel is blocked with `Stop Ghost Mode` guidance; no cancel mutation sent |
| GM-09 | Position TP/SL blocked | While spectating, attempt TP/SL submit from positions panel | Submit is blocked with `Stop Ghost Mode` guidance |
| GM-10 | Position margin update blocked | While spectating, attempt isolated margin update | Submit is blocked with `Stop Ghost Mode` guidance |
| GM-11 | Funding transfer/deposit/withdraw blocked | While spectating, attempt each funding submit path | Mutation is blocked with `Stop Ghost Mode` guidance |
| GM-12 | Vault transfer blocked | While spectating, attempt vault deposit/withdraw submit | Mutation is blocked with `Stop Ghost Mode` guidance |
| GM-13 | Watchlist persistence restore | Add/remove watchlist addresses, refresh app | Watchlist and last-search restore correctly |
| GM-14 | Effective-address read routing | While spectating, validate account info/history/portfolio/vault data | Read surfaces consistently track spectated address only |

## Exit Criteria

- All matrix cases pass in at least one clean run.
- `GM-03`, `GM-04`, `GM-05`, `GM-07`, `GM-08`, `GM-11`, and `GM-12` must pass on every release candidate.
- Any failure in mutation guardrail scenarios (`GM-07` through `GM-12`) blocks rollout.

## Related Runbook

- `/hyperopen/docs/runbooks/ghost-mode-rollout.md`
