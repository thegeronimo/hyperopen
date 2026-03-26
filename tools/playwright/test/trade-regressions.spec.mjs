import { expect, test } from "@playwright/test";
import {
  debugCall,
  dispatch,
  expectOracle,
  oracle,
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

test("vault detail 429 retries stop after returning to trade @regression", async ({ page }) => {
  const vaultAddress = "0x61b1cf5c2d7c4bf6d5db14f36651b2242e7cba0a";
  let vaultDetailsRequests = 0;
  let vaultWebDataRequests = 0;

  await page.route("https://api.hyperliquid.xyz/info", async route => {
    const payload = JSON.parse(route.request().postData() || "{}");
    const requestType = payload?.type;
    const requestVaultAddress = String(
      payload?.vaultAddress || payload?.user || ""
    ).toLowerCase();

    if (
      requestType === "vaultDetails" &&
      requestVaultAddress === vaultAddress
    ) {
      vaultDetailsRequests += 1;
      await route.fulfill({
        status: 429,
        contentType: "application/json",
        body: JSON.stringify({ error: "rate-limited" })
      });
      return;
    }

    if (
      requestType === "webData2" &&
      requestVaultAddress === vaultAddress
    ) {
      vaultWebDataRequests += 1;
      await route.fulfill({
        status: 429,
        contentType: "application/json",
        body: JSON.stringify({ error: "rate-limited" })
      });
      return;
    }

    await route.continue();
  });

  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/navigate", "/vaults", { "replace?": true }]);
  await waitForIdle(page);
  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "vaults-root" } }
  );

  await dispatch(page, [
    ":actions/navigate",
    `/vaults/${vaultAddress}`,
    { "replace?": true }
  ]);

  await expect
    .poll(() => vaultDetailsRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);
  await expect
    .poll(() => vaultWebDataRequests, { timeout: 10_000 })
    .toBeGreaterThanOrEqual(1);

  await dispatch(page, [":actions/navigate", "/trade", { "replace?": true }]);
  await waitForIdle(page);
  await expectOracle(
    page,
    "parity-element",
    { present: true },
    { args: { parityId: "trade-root" } }
  );

  const detailsRequestsAfterLeave = vaultDetailsRequests;
  const webDataRequestsAfterLeave = vaultWebDataRequests;

  await page.waitForTimeout(1200);

  await expect(vaultDetailsRequests).toBe(detailsRequestsAfterLeave);
  await expect(vaultWebDataRequests).toBe(webDataRequestsAfterLeave);
});

test("asset selector favorite toggle keeps dropdown open @regression", async ({ page }) => {
  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/toggle-asset-dropdown", ":asset-selector"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expectOracle(page, "asset-selector", {
    visibleDropdown: "asset-selector",
    desktopPresent: true
  });

  const selectorState = await oracle(page, "asset-selector");
  const favoriteButton = page.locator('[data-role="asset-selector-row"] [data-role="asset-selector-favorite-button"]').first();

  await expect(favoriteButton).toHaveAttribute("aria-pressed", "false");
  await favoriteButton.click();
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });

  await expect(favoriteButton).toHaveAttribute("aria-pressed", "true");
  await expectOracle(page, "asset-selector", {
    visibleDropdown: "asset-selector",
    activeAsset: selectorState.activeAsset
  });
});

test("asset selector rapid scroll keeps rows visible @regression", async ({ page }) => {
  const nestedRenderWarnings = [];
  const pageErrors = [];
  page.on("console", (message) => {
    const text = message.text();
    if (
      text.includes("Triggered a render while rendering") ||
      text.includes("replicant.dom/render was called while working on a previous render")
    ) {
      nestedRenderWarnings.push(text);
    }
  });
  page.on("pageerror", (error) => {
    pageErrors.push(String(error));
  });

  await visitRoute(page, "/trade");

  await dispatch(page, [":actions/toggle-asset-dropdown", ":asset-selector"]);
  await waitForIdle(page, { quietMs: 150, timeoutMs: 4_000, pollMs: 50 });
  await expect(page.locator('[data-role="asset-selector-scroll-container"]')).toBeVisible();
  await expect(page.locator('[data-role="asset-selector-row"]').first()).toBeVisible();

  const coverageSamples = await page.evaluate(async () => {
    const container = document.querySelector('[data-role="asset-selector-scroll-container"]');
    if (!container) {
      throw new Error("asset selector scroll container not found");
    }

    const rowCoverage = () => {
      const containerRect = container.getBoundingClientRect();
      const intervals = Array.from(document.querySelectorAll('[data-role="asset-selector-row"]'))
        .map((row) => row.getBoundingClientRect())
        .map((rect) => [Math.max(containerRect.top, rect.top), Math.min(containerRect.bottom, rect.bottom)])
        .filter(([top, bottom]) => bottom > top)
        .sort((a, b) => a[0] - b[0]);

      let covered = 0;
      let cursor = containerRect.top;
      for (const [top, bottom] of intervals) {
        const start = Math.max(top, cursor);
        if (bottom > start) {
          covered += bottom - start;
          cursor = bottom;
        }
      }

      return {
        covered,
        height: containerRect.height,
        blank: Math.max(0, containerRect.height - covered)
      };
    };

    const maxScrollTop = Math.max(0, container.scrollHeight - container.clientHeight);
    const targets = [0.15, 0.3, 0.45, 0.6, 0.75, 0.9]
      .map((fraction) => Math.max(0, Math.min(maxScrollTop, Math.floor(maxScrollTop * fraction))))
      .filter((target, index, allTargets) => index === 0 || target !== allTargets[index - 1]);

    const sampleTarget = async (target) => {
      container.scrollTop = target;
      const immediateCoverage = rowCoverage();
      await new Promise((resolve) => requestAnimationFrame(() => resolve()));
      const nextFrameCoverage = rowCoverage();
      await new Promise((resolve) => requestAnimationFrame(() => resolve()));
      return {
        target,
        immediateCoverage,
        nextFrameCoverage,
        settledCoverage: rowCoverage()
      };
    };

    const samples = [];
    for (const target of targets) {
      samples.push(await sampleTarget(target));
    }

    return samples;
  });

  for (const sample of coverageSamples) {
    expect(sample.immediateCoverage.blank).toBeLessThanOrEqual(1);
    expect(sample.nextFrameCoverage.blank).toBeLessThanOrEqual(1);
    expect(sample.settledCoverage.blank).toBeLessThanOrEqual(1);
  }
  expect(nestedRenderWarnings).toEqual([]);
  expect(pageErrors).toEqual([]);
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

test("trading settings confirmation toggles respond to visible switch clicks @regression", async ({
  page
}) => {
  await visitRoute(page, "/trade");

  await page.locator('[data-role="header-settings-button"]').click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  const openToggleLabel = page
    .locator(
      '[data-role="trading-settings-panel"] [data-role="trading-settings-confirm-open-orders-row"] label'
    )
    .first();
  const openToggleInput = page
    .locator(
      '[data-role="trading-settings-panel"] [data-role="trading-settings-confirm-open-orders-row"] input[type="checkbox"]'
    )
    .first();
  const closeToggleLabel = page
    .locator(
      '[data-role="trading-settings-panel"] [data-role="trading-settings-confirm-close-position-row"] label'
    )
    .first();
  const closeToggleInput = page
    .locator(
      '[data-role="trading-settings-panel"] [data-role="trading-settings-confirm-close-position-row"] input[type="checkbox"]'
    )
    .first();

  await expect(openToggleInput).toBeChecked();
  await expect(closeToggleInput).toBeChecked();

  await openToggleLabel.click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await expect(openToggleInput).not.toBeChecked();
  await expect
    .poll(
      async () =>
        (await debugCall(page, "snapshot"))["app-state"]["trading-settings"]["confirm-open-orders?"],
      { timeout: 4_000 }
    )
    .toBe(false);

  await closeToggleLabel.click();
  await waitForIdle(page, { quietMs: 250, timeoutMs: 4_000, pollMs: 50 });

  await expect(closeToggleInput).not.toBeChecked();
  await expect
    .poll(
      async () =>
        (await debugCall(page, "snapshot"))["app-state"]["trading-settings"]["confirm-close-position?"],
      { timeout: 4_000 }
    )
    .toBe(false);

  await expect
    .poll(
      () =>
        page.evaluate(() =>
          JSON.parse(localStorage.getItem("hyperopen:trading-settings:v1") || "{}")
        ),
      { timeout: 4_000 }
    )
    .toMatchObject({
      "confirm-open-orders?": false,
      "confirm-close-position?": false
    });
});
