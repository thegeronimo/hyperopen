# Passkey Unlock Before Open Order Cancel

This plan tracks `hyperopen-z9uh`.

## Goal

After a page refresh restores a remembered passkey trading session as `:locked`, cancelling an open order must prompt the user to unlock with passkey and then run the original cancel request. A locked passkey session must not make cancel appear inert.

## Findings

- Order submit already handles locked sessions by dispatching `:effects/unlock-agent-trading` with an after-success continuation.
- Open-order cancel currently treats `:locked` as a terminal readiness error in `hyperopen.order.actions/emit-cancel-effects`.
- The lower-level `hyperopen.order.effects/api-cancel-order` has the same locked-session terminal error path, so direct API effect invocations also do not unlock.
- Refreshing a remembered passkey session is expected to restore `[:wallet :agent :status]` to `:locked`, so this explains why the passkey prompt is missing specifically after refresh.
- A second fresh-reload failure can happen before passkey unlock: named-dex open orders can arrive as a display/base coin like `SILVER` plus `:dex "xyz"`. Cancel request construction only resolved direct coin keys, so it could miss the canonical `xyz:SILVER` market asset id and return nil.
- `[:orders :cancel-error]` was not rendered by the Open Orders tab, and the account-info VM did not pass that error into the tab state. This made nil-request failures look like an inert click.

## Implementation

- Add focused failing tests for locked open-order cancel at the action layer.
- Add focused failing tests for locked API cancel effect fallback.
- Add a cancel continuation action that runs only after unlock succeeds and replays the already-built cancel request.
- Register and schema the new action.
- Keep non-passkey/non-ready cancel behavior unchanged.
- Resolve base-coin plus dex cancel rows through the namespaced market first, preserving the HIP-3 rule that named-dex cancels require canonical `:asset-id` rather than local `:idx`.
- Render Open Orders cancel failures as an assertive inline banner and wire `[:orders :cancel-error]` through the account-info VM.
- Add a Playwright regression for locked remembered passkey cancel with named-dex market metadata.

## Validation

- Run focused order action and order effect tests first.
- Run `npm run check`, `npm test`, and `npm run test:websocket` before completion.
- Run the targeted Playwright regression for the locked passkey named-dex cancel path.

## Progress

- [x] Root cause isolated.
- [x] Failing regression tests added.
- [x] Implementation complete.
- [x] Focused validation passed.
- [x] Required repository gates passed.
