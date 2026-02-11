# Agent Trading Rollout Runbook

## Scope

Rollout and support guidance for agent-wallet signing (`approveAgent` + local signing for order/cancel).

## Preconditions

- CI and local gates pass:
- `npm run check`
- `npm test`
- `npm run test:websocket`
- `npx shadow-cljs compile app`
- `npx shadow-cljs compile test`
- Manual QA matrix completed:
- `/hyperopen/docs/qa/agent-wallet-manual-matrix.md`

## Rollout Steps

1. Deploy the release candidate to staging.
2. Execute critical matrix subset (`AW-02`, `AW-03`, `AW-04`, `AW-05`, `AW-08`, `AW-10`).
3. Verify no diagnostics/log output contains sensitive key material.
4. Promote to production.
5. Monitor first trading session cohort for signing and order-path failures.

## Behavior Guarantees To Re-Verify In PR Notes

- `Enable Trading` action order:
- Set `:wallet :agent :status` to `:approving` and clear prior error before network side effects.
- Emit exactly one `:effects/enable-agent-trading` per user click.
- Order submit/cancel action gating:
- If agent is not `:ready`, emit only local error projection and no API side effect.
- If agent is `:ready`, emit exactly one API effect (`:effects/api-submit-order` or `:effects/api-cancel-order`).
- Invalid API wallet recovery:
- `User or API Wallet ... does not exist` invalidates current agent session and requires re-enable.

## Known Error Mapping

| Error text | Meaning | User guidance | Operator notes |
|---|---|---|---|
| `User or API Wallet ... does not exist.` | Agent wallet not recognized by Hyperliquid | Click `Enable Trading` again | Expected after pruning/revocation or stale local key |
| `Vault not registered: ...` | Request references a vault address that is not registered | Recheck account/vault configuration and retry | Do not auto-wipe agent session for this response |
| `nonce too low` | Signer nonce behind server view | Retry submit/cancel | One automatic retry should occur; investigate if repeated |
| `Provided chainId ... must match active chainId ...` | Wallet/provider network mismatch | Switch wallet network and retry | Ensure chain-id handling remains deterministic |
| `Connect your wallet before enabling trading.` | No connected EOA for approval | Connect wallet first | Indicates flow ordering issue if seen while connected |

## Monitoring and Signals

- Track rate of `Trading setup failed` transitions.
- Track order submit/cancel error ratios immediately post rollout.
- Track frequency of `Enable Trading again` prompts.
- Watch for spikes in chain mismatch and nonce retry errors.

## Rollback

1. Stop rollout and revert to previous known-good release.
2. Confirm order entry still blocks without ready agent state.
3. Preserve captured logs/traces and failing payload examples for follow-up patch.
4. Add regression tests for the discovered failure mode before re-attempting rollout.
