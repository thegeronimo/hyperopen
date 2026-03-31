import fs from "node:fs/promises";
import path from "node:path";
import { chromium } from "@playwright/test";
import { debugCall, waitForDebugBridge, waitForIdle } from "../../playwright/support/hyperopen.mjs";

const ORIGIN = process.env.HYPEROPEN_ORIGIN || "http://localhost:8080";
const VIEWPORT = { width: 1440, height: 900 };
const PORTFOLIO_SPECTATE = "0x162cc7c861ebd0c06b3d72319201150482518185";
const VAULT_ADDRESS = process.env.HYPEROPEN_VAULT_ADDRESS || "0xdfc24b077bc1425ad1dea75bcb6f8158e10df303";
const POST_IDLE_SETTLE_MS = Number(process.env.HYPEROPEN_POST_IDLE_SETTLE_MS || "0");
const STEP_TIMEOUT_MS = Number(process.env.HYPEROPEN_STEP_TIMEOUT_MS || "15000");
const TRACE_TIMEOUT_MS = Number(process.env.HYPEROPEN_TRACE_TIMEOUT_MS || "45000");
const HOST_WAIT_TIMEOUT_MS = Number(process.env.HYPEROPEN_HOST_WAIT_TIMEOUT_MS || "10000");
const SAMPLE_SETTLE_TIMEOUT_MS = Number(process.env.HYPEROPEN_SAMPLE_SETTLE_TIMEOUT_MS || "500");

function log(message, details = undefined) {
  const prefix = `[mceo-hover-profile ${new Date().toISOString()}] ${message}`;
  if (details === undefined) {
    console.error(prefix);
    return;
  }
  console.error(`${prefix} ${JSON.stringify(details)}`);
}

async function withTimeout(label, timeoutMs, work) {
  let timeoutId;
  try {
    return await Promise.race([
      Promise.resolve().then(work),
      new Promise((_, reject) => {
        timeoutId = setTimeout(() => {
          reject(new Error(`${label} timed out after ${timeoutMs}ms`));
        }, timeoutMs);
      })
    ]);
  } finally {
    clearTimeout(timeoutId);
  }
}

async function runStep(label, work, options = {}) {
  const timeoutMs = options.timeoutMs ?? STEP_TIMEOUT_MS;
  const start = Date.now();
  log(`start ${label}`, { timeoutMs });
  try {
    const result = await withTimeout(label, timeoutMs, work);
    log(`done ${label}`, { elapsedMs: Date.now() - start });
    return result;
  } catch (error) {
    log(`fail ${label}`, { elapsedMs: Date.now() - start, error: error.message });
    throw error;
  }
}

async function readDebugSnapshot(page) {
  const snapshot = { pageUrl: page.url() };
  try {
    snapshot.documentReadyState = await page.evaluate(() => document.readyState);
  } catch {}
  try {
    snapshot.qaSnapshot = await debugCall(page, "qaSnapshot");
  } catch (error) {
    snapshot.qaSnapshotError = error.message;
  }
  try {
    snapshot.hostVisibility = await page.evaluate(() => ({
      portfolioHost: Boolean(document.querySelector("[data-role='portfolio-chart-d3-host']")),
      vaultHost: Boolean(document.querySelector("[data-role='vault-detail-chart-d3-host']"))
    }));
  } catch {}
  return snapshot;
}

async function writeFailureArtifact(artifactDir, id, page, error) {
  const safeId = id.replace(/[^a-z0-9-]+/gi, "_");
  const failurePath = path.join(artifactDir, `${safeId}-failure.json`);
  const screenshotPath = path.join(artifactDir, `${safeId}-failure.png`);
  const payload = {
    recordedAt: new Date().toISOString(),
    id,
    error: {
      message: error.message,
      stack: error.stack
    },
    snapshot: await readDebugSnapshot(page)
  };
  await fs.writeFile(failurePath, JSON.stringify(payload, null, 2));
  try {
    await page.screenshot({ path: screenshotPath, fullPage: true });
    payload.screenshotPath = screenshotPath;
    await fs.writeFile(failurePath, JSON.stringify(payload, null, 2));
  } catch (screenshotError) {
    payload.screenshotError = screenshotError.message;
    await fs.writeFile(failurePath, JSON.stringify(payload, null, 2));
  }
  log("wrote failure artifact", { failurePath });
}

async function openRoute(page, route, id) {
  const url = new URL(route, ORIGIN);
  await runStep(`${id}:goto`, () => page.goto(`${ORIGIN}/index.html${url.search}`));
  await runStep(`${id}:debug-bridge`, () => waitForDebugBridge(page));
  await runStep(`${id}:qa-reset`, () => debugCall(page, "qaReset"));
  if (url.pathname !== "/trade") {
    await runStep(`${id}:navigate`, () =>
      debugCall(page, "dispatch", [":actions/navigate", url.pathname, { "replace?": true }])
    );
  }
  await runStep(
    `${id}:wait-for-idle`,
    () => waitForIdle(page, { quietMs: 500, timeoutMs: 12000, pollMs: 50 }),
    { timeoutMs: 15000 }
  );
}

function summarize(samples) {
  const values = [...samples].sort((a, b) => a - b);
  const pick = (fraction) => {
    if (values.length === 0) {
      return null;
    }
    const index = Math.min(values.length - 1, Math.floor(values.length * fraction));
    return values[index];
  };
  return {
    count: values.length,
    medianMs: pick(0.5),
    p95Ms: pick(0.95),
    maxMs: values.length === 0 ? null : values[values.length - 1]
  };
}

async function traceHover(page, options) {
  const {
    id,
    route,
    hostSelector,
    hoverLineSelector,
    tooltipSelector,
    setupActions,
    artifactDir
  } = options;
  return withTimeout(`trace:${id}`, TRACE_TIMEOUT_MS, async () => {
    try {
      log("trace begin", { id, route, hostSelector });
      await openRoute(page, route, id);
      if (setupActions.length > 0) {
        await runStep(`${id}:dispatch-setup-actions`, () => debugCall(page, "dispatchMany", setupActions));
        await runStep(
          `${id}:wait-for-idle-after-setup`,
          () => waitForIdle(page, { quietMs: 500, timeoutMs: 12000, pollMs: 50 }),
          { timeoutMs: 15000 }
        );
      }
      if (POST_IDLE_SETTLE_MS > 0) {
        await runStep(`${id}:post-idle-settle`, () => page.waitForTimeout(POST_IDLE_SETTLE_MS), {
          timeoutMs: POST_IDLE_SETTLE_MS + 2000
        });
      }

      const host = page.locator(hostSelector);
      await runStep(`${id}:wait-for-host`, () => host.waitFor({ state: "visible", timeout: HOST_WAIT_TIMEOUT_MS }), {
        timeoutMs: HOST_WAIT_TIMEOUT_MS + 1000
      });
      const plot = await runStep(`${id}:host-bounding-box`, () => host.boundingBox());
      if (plot === null) {
        throw new Error(`Missing plot box for ${id}`);
      }
      log("resolved plot bounds", { id, plot });

      const warmX = plot.x + plot.width * 0.08;
      const warmY = plot.y + plot.height * 0.45;
      await runStep(`${id}:warm-pointer-move`, () => page.mouse.move(warmX, warmY));
      await runStep(`${id}:warm-pointer-settle`, () => page.waitForTimeout(250), { timeoutMs: 1000 });

      const probeHandle = await runStep(`${id}:install-probe`, () =>
        page.evaluateHandle(({ hoverLineSelector, tooltipSelector, sampleSettleTimeoutMs }) => {
          const line = document.querySelector(hoverLineSelector);
          const tooltip = document.querySelector(tooltipSelector);
          const state = {
            settleSamples: [],
            dispatchSamples: [],
            timeoutCount: 0,
            sampleSettleTimeoutMs,
            observerMetrics: {
              layoutShiftEntriesAll: 0,
              layoutShiftValueAll: 0,
              layoutShiftEntriesNoRecentInput: 0,
              layoutShiftValueNoRecentInput: 0,
              layoutShiftSources: [],
              longTaskCount: 0,
              maxLongTaskMs: 0,
              supported: typeof PerformanceObserver === "function"
            }
          };
          const observers = [];
          if (state.observerMetrics.supported) {
            try {
              const ls = new PerformanceObserver((list) => {
                for (const entry of list.getEntries()) {
                  state.observerMetrics.layoutShiftEntriesAll += 1;
                  state.observerMetrics.layoutShiftValueAll += entry.value || 0;
                  if (entry.hadRecentInput === true) {
                  } else {
                    state.observerMetrics.layoutShiftEntriesNoRecentInput += 1;
                    state.observerMetrics.layoutShiftValueNoRecentInput += entry.value || 0;
                  }
                  state.observerMetrics.layoutShiftSources.push({
                    value: entry.value || 0,
                    hadRecentInput: entry.hadRecentInput === true,
                    startTime: entry.startTime || 0,
                    sources: (entry.sources || []).map((source) => {
                      const node = source?.node;
                      return {
                        tag: node?.tagName || null,
                        role: node?.getAttribute?.("data-role") || null,
                        parityId: node?.getAttribute?.("data-parity-id") || null,
                        className: node?.className || null
                      };
                    })
                  });
                }
              });
              ls.observe({ type: "layout-shift" });
              observers.push(ls);
            } catch {}
            try {
              const lt = new PerformanceObserver((list) => {
                for (const entry of list.getEntries()) {
                  state.observerMetrics.longTaskCount += 1;
                  state.observerMetrics.maxLongTaskMs = Math.max(state.observerMetrics.maxLongTaskMs, entry.duration || 0);
                }
              });
              lt.observe({ type: "longtask" });
              observers.push(lt);
            } catch {}
          }

          return {
            line,
            tooltip,
            state,
            observers
          };
        }, { hoverLineSelector, tooltipSelector, sampleSettleTimeoutMs: SAMPLE_SETTLE_TIMEOUT_MS })
      );
      const probeMeta = await runStep(`${id}:validate-probe`, () =>
        probeHandle.evaluate((probe) => ({
          hasLine: Boolean(probe.line),
          hasTooltip: Boolean(probe.tooltip)
        }))
      );
      if (!probeMeta.hasLine || !probeMeta.hasTooltip) {
        throw new Error(
          `Missing hover probe nodes for ${id}: ${JSON.stringify(probeMeta)}`
        );
      }

      const sampleFractions = Array.from({ length: 24 }, (_value, index) => 0.08 + (0.84 * index) / 23);
      for (const [index, fraction] of sampleFractions.entries()) {
        const targetX = plot.x + plot.width * fraction;
        await runStep(`${id}:sample-${index + 1}-move`, () => page.mouse.move(targetX, warmY), { timeoutMs: 2000 });
        await runStep(
          `${id}:sample-${index + 1}-settle`,
          () =>
            probeHandle.evaluate(async (probe, targetXLocal) => {
              probe.state.dispatchSamples.push(Date.now());
              const start = performance.now();
              const hostRect = probe.line?.ownerSVGElement?.getBoundingClientRect?.() || { left: 0 };
              while (performance.now() - start < probe.state.sampleSettleTimeoutMs) {
                const xAttr = probe.line?.getAttribute?.("x1");
                const transform = probe.tooltip?.style?.transform || "";
                const x = xAttr ? Number(xAttr) + hostRect.left : null;
                const tooltipReady = transform.includes("translate3d(");
                if (x !== null && Math.abs(x - targetXLocal) < 1.5 && tooltipReady) {
                  probe.state.settleSamples.push(performance.now() - start);
                  return;
                }
                await new Promise((resolve) => requestAnimationFrame(resolve));
              }
              probe.state.timeoutCount += 1;
              probe.state.settleSamples.push(probe.state.sampleSettleTimeoutMs);
            }, targetX),
          { timeoutMs: SAMPLE_SETTLE_TIMEOUT_MS + 500 }
        );
      }

      const result = await runStep(`${id}:collect-results`, () =>
        probeHandle.evaluate((probe) => {
          for (const observer of probe.observers) {
            try {
              observer.disconnect();
            } catch {}
          }
          return {
            settleSamples: probe.state.settleSamples,
            dispatchSamples: probe.state.dispatchSamples,
            timeoutCount: probe.state.timeoutCount,
            observerMetrics: probe.state.observerMetrics
          };
        })
      );

      const summary = {
        id,
        route,
        plot,
        settle: summarize(result.settleSamples),
        samples: sampleFractions.length,
        timeoutCount: result.timeoutCount,
        observers: result.observerMetrics
      };
      log("trace complete", {
        id,
        settle: summary.settle,
        timeoutCount: summary.timeoutCount,
        maxLongTaskMs: summary.observers.maxLongTaskMs
      });
      return summary;
    } catch (error) {
      await writeFailureArtifact(artifactDir, id, page, error);
      throw error;
    }
  });
}

async function main() {
  const artifactDir = path.join(process.cwd(), "tmp", "browser-inspection", `mceo-hover-profile-${Date.now()}`);
  await fs.mkdir(artifactDir, { recursive: true });
  log("artifact dir ready", { artifactDir, origin: ORIGIN });
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: VIEWPORT });
  try {
    const results = [];
    results.push(await traceHover(page, {
      id: "portfolio-spectate-route",
      route: `/portfolio?spectate=${PORTFOLIO_SPECTATE}`,
      hostSelector: "[data-role='portfolio-chart-d3-host']",
      hoverLineSelector: "[data-role='portfolio-chart-hover-line']",
      tooltipSelector: "[data-role='portfolio-chart-hover-tooltip']",
      artifactDir,
      setupActions: [
        [":actions/select-portfolio-chart-tab", ":returns"],
        [":actions/select-portfolio-returns-benchmark", "BTC"]
      ]
    }));
    results.push(await traceHover(page, {
      id: "vault-detail-route",
      route: `/vaults/${VAULT_ADDRESS}`,
      hostSelector: "[data-role='vault-detail-chart-d3-host']",
      hoverLineSelector: "[data-role='vault-detail-chart-hover-line']",
      tooltipSelector: "[data-role='vault-detail-chart-hover-tooltip']",
      artifactDir,
      setupActions: [
        [":actions/set-vault-detail-chart-series", ":returns"],
        [":actions/select-vault-detail-returns-benchmark", "BTC"]
      ]
    }));

    const outputPath = path.join(artifactDir, "profile.json");
    await fs.writeFile(outputPath, JSON.stringify({
      recordedAt: new Date().toISOString(),
      origin: ORIGIN,
      postIdleSettleMs: POST_IDLE_SETTLE_MS,
      stepTimeoutMs: STEP_TIMEOUT_MS,
      traceTimeoutMs: TRACE_TIMEOUT_MS,
      hostWaitTimeoutMs: HOST_WAIT_TIMEOUT_MS,
      sampleSettleTimeoutMs: SAMPLE_SETTLE_TIMEOUT_MS,
      viewport: VIEWPORT,
      results
    }, null, 2));
    console.log(outputPath);
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
