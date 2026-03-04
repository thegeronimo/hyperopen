# Ghost Mode Rollout Runbook

## Scope

Rollout and support guidance for Ghost Mode account spectating and read-only mutation guardrails.

## Preconditions

- CI and local gates pass:
- `npm run check`
- `npm test`
- `npm run test:websocket`
- `npx shadow-cljs compile app`
- `npx shadow-cljs compile test`
- Manual QA matrix completed:
- `/hyperopen/docs/qa/ghost-mode-manual-matrix.md`

## Rollout Steps

1. Deploy the release candidate to staging.
2. Execute critical matrix subset (`GM-03`, `GM-04`, `GM-05`, `GM-07`, `GM-08`, `GM-11`, `GM-12`).
3. Confirm effective-address reads switch between owner and spectated addresses without stale crossover.
4. Confirm all mutation attempts while spectating are blocked with `Stop Ghost Mode` guidance.
5. Promote to production.
6. Monitor first-session cohort for ghost start/stop usage and blocked-mutation errors.

## Behavior Guarantees To Re-Verify In PR Notes

- Owner/signer identity remains in `:wallet :address`; Ghost Mode only changes effective read identity.
- Read-side startup/websocket/API routing follows `effective-account-address` under Ghost Mode.
- Mutation guardrails apply in both action and effect boundaries:
- order submit/cancel
- position TP/SL and margin update
- funding transfer/withdraw/deposit submits
- vault transfer submit
- Order ticket includes explicit `Stop Ghost Mode` affordance.
- Cmd/Ctrl+X stops Ghost Mode outside editable inputs without hijacking input cut behavior.

## Known Error Mapping

| Error text | Meaning | User guidance | Operator notes |
|---|---|---|---|
| `Ghost Mode is read-only. Stop Ghost Mode to place trades or move funds.` | Spectating mode is active and mutation path was attempted | Click `Stop Ghost Mode` from banner, order ticket, modal, or Cmd/Ctrl+X | Expected behavior; treat as guardrail confirmation, not outage |
| `Enter a valid 0x-prefixed EVM address.` | Ghost Mode input failed address validation | Enter a valid `0x` address | No backend dependency |

## Monitoring and Signals

- Track rate of Ghost Mode start/stop actions.
- Track blocked mutation error rates while Ghost Mode is active.
- Track ratio of blocked mutations that are followed by successful stop + mutation flows.
- Watch for stale read-address symptoms (read surfaces not matching active spectated address).

## Rollback

1. Stop rollout and revert to previous known-good release.
2. Validate owner-address read routing and mutation paths recover to baseline.
3. Preserve logs and payload evidence for any stale routing or guardrail regressions.
4. Add regression coverage for discovered failure mode before re-attempting rollout.

## Related QA Matrix

- `/hyperopen/docs/qa/ghost-mode-manual-matrix.md`
