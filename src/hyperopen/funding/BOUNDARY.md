# Funding Boundary

## Owns

- Funding modal state, funding modal view-model preparation, lifecycle polling, Hyperunit deposit and withdraw orchestration, transfer and send submission flows, and funding-specific history or predictability helpers.
- Pure funding asset, lifecycle, and preview policy under `domain/`.
- Modal and submission orchestration under `application/`.
- RPC, route-client, wallet, and Hyperunit transport details under `infrastructure/`.

## Stable Public Seams

- `hyperopen.funding.actions`
  The stable facade for funding modal defaults, lifecycle helpers, and user-facing funding commands.
- `hyperopen.funding.effects`
  The stable effect seam for Hyperunit queries, fee estimation, deposit, transfer, and withdraw execution.
- `hyperopen.funding.application.modal-actions`
  The composition seam for funding modal commands and view-model shaping.
- `hyperopen.funding.history-cache`
  Funding-history cache behavior shared with startup and account surfaces.
- `hyperopen.funding.predictability`
  Funding predictability calculations consumed by funding UI and related projections.

## Dependency Rules

- Allowed:
  `funding.domain.*` stays pure and must not depend on RPC, browser, or runtime mutation code.
- Allowed:
  `funding.application.*` may depend on `funding.domain.*` plus injected collaborators.
- Allowed:
  `funding.infrastructure.*` may depend on wallet, route, or HTTP seams, but should not own business rules.
- Allowed:
  External callers should go through `hyperopen.funding.actions` and `hyperopen.funding.effects` unless they are extending an existing funding-owned internal seam.
- Forbidden:
  Do not move modal UI state back under `funding.domain.*`.
- Forbidden:
  Do not put browser or RPC logic into `funding.application.*`.
- Forbidden:
  Do not import `hyperopen.views.*` into non-view funding namespaces.

## Key Tests

- Key namespaces:
  `hyperopen.funding.actions-test`,
  `hyperopen.funding.effects.facade-test`,
  `hyperopen.funding.effects.common-test`,
  `hyperopen.funding.effects.hyperunit-runtime-test`,
  `hyperopen.funding.effects.transport-runtime-test`,
  `hyperopen.funding.application.modal-state-test`,
  `hyperopen.funding.application.modal-vm-test`,
  `hyperopen.funding.application.lifecycle-polling-test`,
  `hyperopen.funding.application.submit-effects-test`,
  `hyperopen.funding.application.submit-effects-transfer-guard-test`,
  `hyperopen.funding.application.submit-effects-gas-topup-test`,
  `hyperopen.funding.application.submit-effects-hyperevm-test`,
  `hyperopen.funding.application.hyperevm-submit-test`,
  `hyperopen.funding.application.hyperevm-submit-failures-test`,
  `hyperopen.funding.application.hyperevm-transfer-followup-test`,
  `hyperopen.funding.application.hyperevm-transfer-outcomes-test`,
  `hyperopen.funding.effects.hyperevm-runtime-test`,
  `hyperopen.funding.domain.transfer-run-test`,
  `hyperopen.funding.infrastructure.wallet-rpc-test`,
  `hyperopen.funding.application.transfer-commands-test`,
  `hyperopen.funding.application.transfer-capacity-refresh-test`,
  `hyperopen.funding.application.modal-vm.transfer-test`,
  `hyperopen.funding.domain.transfer-route-test`,
  `hyperopen.funding.domain.evm-transfer-amounts-test`,
  `hyperopen.funding.domain.evm-transfer-preview-test`,
  `hyperopen.funding.domain.transfer-invariants-test`,
  `hyperopen.funding.domain.transfer-dispatch-test`,
  `hyperopen.funding.infrastructure.hyperunit-client-test`,
  `hyperopen.funding.infrastructure.route-clients-test`,
  `hyperopen.funding.history-cache-test`
- The repo-wide final gates remain:
  `npm run check`, `npm test`, `npm run test:websocket`

## Where This Change Goes

- New modal default, normalization rule, or modal command:
  `hyperopen.funding.application.modal-state` or `hyperopen.funding.application.modal-commands`
  (transfer-mode commands: `hyperopen.funding.application.transfer-commands`)
- New funding preview, lifecycle rule, or asset policy:
  `hyperopen.funding.domain.*`
- New deposit, withdraw, or transfer orchestration:
  `hyperopen.funding.application.*`
  (HyperEVM -> Core wallet transactions: `hyperopen.funding.application.hyperevm-submit`;
  the effects and runs of Transfer routes that touch HyperEVM:
  `hyperopen.funding.application.hyperevm-transfer-effects` and `hyperevm-run-state`;
  their real collaborators: `hyperopen.funding.effects.hyperevm-runtime`.
  HyperEVM transactions are built in `hyperopen.hyperevm.domain.txs`, and every
  modal write from a HyperEVM run must go through `hyperevm-run-state/update-run!`,
  which drops writes from a flow the modal no longer shows.)
- New Hyperunit, wallet, or route transport integration:
  `hyperopen.funding.infrastructure.*`
- New stable action or effect entrypoint:
  wire it through `hyperopen.funding.actions` or `hyperopen.funding.effects` as a thin facade
