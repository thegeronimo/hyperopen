import { expect, test } from "@playwright/test";
import {
  debugCall,
  dispatch,
  expectOracle,
  visitRoute,
  waitForIdle
} from "../support/hyperopen.mjs";

test("asset selector opens and selects ETH @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/toggle-asset-dropdown", ":asset-selector"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "asset-selector", {
    visibleDropdown: "asset-selector",
    desktopPresent: true
  });

  await dispatch(page, [":actions/select-asset", "ETH"]);
  await waitForIdle(page, { quietMs: 300, timeoutMs: 7_000, pollMs: 50 });
  await expectOracle(page, "asset-selector", {
    visibleDropdown: null,
    activeAsset: "ETH"
  });
  await expectOracle(
    page,
    "effect-order",
    {
      actionId: ":actions/select-asset",
      covered: true,
      projectionBeforeHeavy: true,
      phaseOrderValid: true,
      duplicateHeavyEffectIds: []
    },
    { args: { actionId: ":actions/select-asset" } }
  );
});

test("funding modal deposit flow selects USDC @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/open-funding-deposit-modal", null]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expectOracle(page, "funding-modal", {
    open: true,
    title: "Deposit",
    contentKind: ":deposit/select"
  });

  await dispatch(page, [":actions/select-funding-deposit-asset", "usdc"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 3_000, pollMs: 50 });
  await expectOracle(page, "funding-modal", {
    open: true,
    title: "Deposit USDC",
    contentKind: ":deposit/amount",
    selectedDepositAssetKey: "usdc"
  });
});

test("wallet connect and enable trading stays deterministic @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  await debugCall(page, "installWalletSimulator", {
    accounts: ["0x1111111111111111111111111111111111111111"],
    requestAccounts: ["0x1111111111111111111111111111111111111111"],
    chainId: "0xa4b1"
  });
  await debugCall(page, "setWalletConnectedHandlerMode", "suppress");
  await debugCall(page, "installExchangeSimulator", {
    approveAgent: { responses: [{ status: "ok" }] }
  });

  await dispatch(page, [":actions/connect-wallet"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", {
    connected: true,
    address: "0x1111111111111111111111111111111111111111",
    agentStatus: "not-ready"
  });

  await dispatch(page, [":actions/enable-agent-trading"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 5_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", {
    connected: true,
    agentStatus: "ready",
    agentError: null
  });
  await expectOracle(
    page,
    "effect-order",
    {
      actionId: ":actions/enable-agent-trading",
      projectionBeforeHeavy: true,
      heavyEffectCount: 1
    },
    { args: { actionId: ":actions/enable-agent-trading" } }
  );
});

test("order submit and cancel gating uses simulator-backed assertions @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  await debugCall(page, "installWalletSimulator", {
    accounts: ["0x1111111111111111111111111111111111111111"],
    requestAccounts: ["0x1111111111111111111111111111111111111111"],
    chainId: "0xa4b1"
  });
  await debugCall(page, "setWalletConnectedHandlerMode", "suppress");

  await dispatch(page, [":actions/connect-wallet"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", {
    connected: true,
    address: "0x1111111111111111111111111111111111111111",
    agentStatus: "not-ready"
  });

  await dispatch(page, [":actions/select-order-entry-mode", ":limit"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-input-mode", ":base"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/update-order-form", [":price"], "100"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-display", "1"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "order-form", {
    sizeDisplay: "1",
    submitDisabled: false,
    submitReason: null
  });

  await dispatch(page, [":actions/submit-order"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", {
    agentError: "Enable trading before submitting orders."
  });
  await expectOracle(page, "agent-trading-recovery", {
    open: true,
    message: "Enable trading before submitting orders."
  });
  await expectOracle(page, "order-form", {
    runtimeError: null
  });
  await expectOracle(
    page,
    "effect-order",
    {
      actionId: ":actions/submit-order",
      covered: true,
      heavyEffectCount: 0,
      projectionBeforeHeavy: true,
      phaseOrderValid: true
    },
    { args: { actionId: ":actions/submit-order" } }
  );

  await dispatch(page, [":actions/cancel-order", { coin: "BTC", oid: 101 }]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "order-form", {
    cancelError: "Enable trading before cancelling orders."
  });
  await expectOracle(
    page,
    "effect-order",
    {
      actionId: ":actions/cancel-order",
      covered: true,
      heavyEffectCount: 0,
      projectionBeforeHeavy: true,
      phaseOrderValid: true
    },
    { args: { actionId: ":actions/cancel-order" } }
  );
});

test("order submit confirmation renders in-app instead of opening a browser dialog @regression", async ({
  page
}) => {
  await visitRoute(page, "/trade");

  let browserDialogSeen = false;
  page.on("dialog", async (dialog) => {
    browserDialogSeen = true;
    await dialog.dismiss();
  });

  await debugCall(page, "installWalletSimulator", {
    accounts: ["0x1111111111111111111111111111111111111111"],
    requestAccounts: ["0x1111111111111111111111111111111111111111"],
    chainId: "0xa4b1"
  });
  await debugCall(page, "setWalletConnectedHandlerMode", "suppress");
  await debugCall(page, "installExchangeSimulator", {
    approveAgent: { responses: [{ status: "ok" }] }
  });

  await dispatch(page, [":actions/connect-wallet"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });
  await dispatch(page, [":actions/enable-agent-trading"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 5_000, pollMs: 50 });
  await expectOracle(page, "wallet-status", {
    connected: true,
    agentStatus: "ready",
    agentError: null
  });

  await dispatch(page, [":actions/select-order-entry-mode", ":limit"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-input-mode", ":base"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/update-order-form", [":price"], "100"]);
  await waitForIdle(page, { quietMs: 100, timeoutMs: 2_000, pollMs: 50 });
  await dispatch(page, [":actions/set-order-size-display", "1"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await dispatch(page, [":actions/submit-order"]);
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await expect(page.locator('[data-role="order-submit-confirmation-dialog"]')).toBeVisible();
  await expect(page.locator('[data-role="order-submit-confirmation-title"]')).toHaveText(
    "Submit Order?"
  );
  await expect(browserDialogSeen).toBe(false);
  await expectOracle(
    page,
    "effect-order",
    {
      actionId: ":actions/submit-order",
      covered: true,
      heavyEffectCount: 0,
      projectionBeforeHeavy: true,
      phaseOrderValid: true
    },
    { args: { actionId: ":actions/submit-order" } }
  );

  await page.locator('[data-role="order-submit-confirmation-cancel"]').click();
  await expect(page.locator('[data-role="order-submit-confirmation-dialog"]')).toHaveCount(0);
});
