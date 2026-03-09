import { assertSnapshotPayload } from "./contracts.mjs";
import { redactHeaders, redactValue } from "./redaction.mjs";
import { sleep } from "./util.mjs";

function consoleArgsToStrings(args = []) {
  return args.map((arg) => {
    if (arg.value !== undefined) {
      return String(arg.value);
    }
    if (arg.description) {
      return String(arg.description);
    }
    if (arg.preview?.properties) {
      const parts = arg.preview.properties.map((p) => `${p.name}:${p.value}`);
      return `{${parts.join(",")}}`;
    }
    return arg.type || "<unknown>";
  });
}

function nowMs() {
  return Date.now();
}

async function waitForNetworkIdle(inflight, quietMs, maxWaitMs) {
  const start = nowMs();
  let quietStart = nowMs();
  while (nowMs() - start < maxWaitMs) {
    if (inflight.size === 0) {
      if (nowMs() - quietStart >= quietMs) {
        return;
      }
    } else {
      quietStart = nowMs();
    }
    await sleep(50);
  }
}

function semanticScript(maskSelectors, styleKeys, maxNodes) {
  return `(() => {
    const selectors = ${JSON.stringify(maskSelectors)};
    const styleKeys = ${JSON.stringify(styleKeys)};
    const maxNodes = ${Number(maxNodes) || 2500};
    const body = document.body;
    if (!body) {
      return { nodes: [], maskRects: [] };
    }

    const isMasked = (el) => selectors.some((selector) => {
      try {
        return el.matches(selector) || Boolean(el.closest(selector));
      } catch (_err) {
        return false;
      }
    });

    const getPath = (el) => {
      if (!el || !el.parentElement) return el?.tagName?.toLowerCase?.() || "unknown";
      const parts = [];
      let current = el;
      while (current && parts.length < 8) {
        const tag = current.tagName ? current.tagName.toLowerCase() : "unknown";
        const parent = current.parentElement;
        if (!parent) {
          parts.push(tag);
          break;
        }
        const siblings = [...parent.children].filter((node) => node.tagName === current.tagName);
        const index = siblings.indexOf(current) + 1;
        parts.push(tag + ':nth-of-type(' + index + ')');
        current = parent;
      }
      return parts.reverse().join(" > ");
    };

    const textOf = (el) => {
      const raw = (el.innerText || el.textContent || "").replace(/\s+/g, " ").trim();
      return raw.length > 180 ? raw.slice(0, 180) : raw;
    };

    const nodes = [];
    const walker = document.createTreeWalker(body, NodeFilter.SHOW_ELEMENT);
    while (walker.nextNode() && nodes.length < maxNodes) {
      const el = walker.currentNode;
      const style = getComputedStyle(el);
      const styleSubset = {};
      for (const key of styleKeys) {
        styleSubset[key] = style[key] || null;
      }

      nodes.push({
        tag: el.tagName.toLowerCase(),
        id: el.id || null,
        className: el.className || "",
        dataRole: el.getAttribute("data-role"),
        parityId: el.getAttribute("data-parity-id"),
        path: getPath(el),
        text: textOf(el),
        masked: isMasked(el),
        style: styleSubset
      });
    }

    const maskRects = [];
    for (const selector of selectors) {
      let list = [];
      try {
        list = [...document.querySelectorAll(selector)];
      } catch (_err) {
        list = [];
      }
      for (const node of list) {
        const rect = node.getBoundingClientRect();
        if (rect.width > 0 && rect.height > 0) {
          maskRects.push({ x: rect.x, y: rect.y, width: rect.width, height: rect.height, selector });
        }
      }
    }

    return {
      nodes,
      maskRects,
      viewport: {
        width: window.innerWidth,
        height: window.innerHeight
      }
    };
  })();`;
}

async function setViewport(client, cdpSessionId, viewport) {
  await client.send(
    "Emulation.setDeviceMetricsOverride",
    {
      width: viewport.width,
      height: viewport.height,
      mobile: Boolean(viewport.mobile),
      deviceScaleFactor: viewport.deviceScaleFactor || 1
    },
    cdpSessionId
  );
}

export async function captureSnapshot(sessionManager, sessionId, options = {}) {
  const config = sessionManager.config;
  const session = await sessionManager.getSession(sessionId);
  const viewport = options.viewport;
  const captureConfig = config.capture;
  const attached = await sessionManager.ensureAttachedTarget(sessionId);

  const network = [];
  const inflight = new Set();
  const consoleEvents = [];

  const unsubscribe = [];
  const pushConsole = (entry) => {
    if (consoleEvents.length >= captureConfig.consoleEventLimit) {
      return;
    }
    consoleEvents.push(entry);
  };

  const pushNetwork = (entry) => {
    if (network.length >= captureConfig.networkEventLimit) {
      return;
    }
    network.push(entry);
  };

  unsubscribe.push(
    attached.client.on("Runtime.consoleAPICalled", (msg) => {
      if (msg.sessionId !== attached.cdpSessionId) {
        return;
      }
      pushConsole(
        redactValue({
          type: msg.params.type,
          timestamp: msg.params.timestamp,
          args: consoleArgsToStrings(msg.params.args)
        })
      );
    })
  );

  unsubscribe.push(
    attached.client.on("Runtime.exceptionThrown", (msg) => {
      if (msg.sessionId !== attached.cdpSessionId) {
        return;
      }
      pushConsole(
        redactValue({
          type: "exception",
          timestamp: msg.params.timestamp,
          details: msg.params.exceptionDetails?.text || "Exception"
        })
      );
    })
  );

  unsubscribe.push(
    attached.client.on("Network.requestWillBeSent", (msg) => {
      if (msg.sessionId !== attached.cdpSessionId) {
        return;
      }
      inflight.add(msg.params.requestId);
      pushNetwork({
        phase: "request",
        requestId: msg.params.requestId,
        timestamp: msg.params.timestamp,
        url: msg.params.request.url,
        method: msg.params.request.method,
        headers: redactHeaders(msg.params.request.headers || {})
      });
    })
  );

  unsubscribe.push(
    attached.client.on("Network.responseReceived", (msg) => {
      if (msg.sessionId !== attached.cdpSessionId) {
        return;
      }
      pushNetwork({
        phase: "response",
        requestId: msg.params.requestId,
        timestamp: msg.params.timestamp,
        status: msg.params.response.status,
        mimeType: msg.params.response.mimeType,
        url: msg.params.response.url,
        headers: redactHeaders(msg.params.response.headers || {})
      });
    })
  );

  unsubscribe.push(
    attached.client.on("Network.loadingFinished", (msg) => {
      if (msg.sessionId !== attached.cdpSessionId) {
        return;
      }
      inflight.delete(msg.params.requestId);
      pushNetwork({
        phase: "finished",
        requestId: msg.params.requestId,
        timestamp: msg.params.timestamp,
        encodedDataLength: msg.params.encodedDataLength
      });
    })
  );

  unsubscribe.push(
    attached.client.on("Network.loadingFailed", (msg) => {
      if (msg.sessionId !== attached.cdpSessionId) {
        return;
      }
      inflight.delete(msg.params.requestId);
      pushNetwork({
        phase: "failed",
        requestId: msg.params.requestId,
        timestamp: msg.params.timestamp,
        errorText: msg.params.errorText,
        canceled: msg.params.canceled
      });
    })
  );

  try {
    await attached.client.send("Page.enable", {}, attached.cdpSessionId);
    await attached.client.send("Runtime.enable", {}, attached.cdpSessionId);
    await attached.client.send("Network.enable", {}, attached.cdpSessionId);

    if (viewport) {
      await setViewport(attached.client, attached.cdpSessionId, viewport);
    }

    const shouldNavigate = options.navigate !== false;
    if (shouldNavigate) {
      const loadEventPromise = attached.client.waitForEvent("Page.loadEventFired", {
        sessionId: attached.cdpSessionId,
        timeoutMs: options.navigationTimeoutMs || captureConfig.navigationTimeoutMs
      });

      await attached.client.send("Page.navigate", { url: options.url }, attached.cdpSessionId);
      await loadEventPromise;
    }

    await waitForNetworkIdle(
      inflight,
      options.networkIdleQuietMs || captureConfig.networkIdleQuietMs,
      options.networkIdleMaxWaitMs || captureConfig.networkIdleMaxWaitMs
    );

    const pageMetaResult = await attached.client.send(
      "Runtime.evaluate",
      {
        expression: "({ title: document.title, url: location.href, readyState: document.readyState })",
        returnByValue: true
      },
      attached.cdpSessionId
    );

    const semanticResult = await attached.client.send(
      "Runtime.evaluate",
      {
        expression: semanticScript(
          options.maskSelectors || config.masking.selectors,
          config.masking.computedStyleKeys,
          config.masking.maxSemanticNodes
        ),
        returnByValue: true
      },
      attached.cdpSessionId,
      30000
    );

    const screenshot = await attached.client.send(
      "Page.captureScreenshot",
      {
        format: "png",
        fromSurface: true,
        captureBeyondViewport: true
      },
      attached.cdpSessionId,
      30000
    );

    let hyperopenDebugSnapshot = null;
    try {
      const debugSnapshotResult = await attached.client.send(
        "Runtime.evaluate",
        {
          expression: `(() => {
            const api = globalThis.HYPEROPEN_DEBUG;
            if (!api) {
              return null;
            }
            if (typeof api.qaSnapshot === "function") {
              return api.qaSnapshot();
            }
            if (typeof api.snapshot === "function") {
              return api.snapshot();
            }
            return null;
          })()`,
          returnByValue: true,
          awaitPromise: true
        },
        attached.cdpSessionId,
        10000
      );
      hyperopenDebugSnapshot = redactValue(debugSnapshotResult?.result?.value ?? null);
    } catch (_err) {
      hyperopenDebugSnapshot = null;
    }

    const payload = {
      sessionId: session.id,
      targetLabel: options.targetLabel || "target",
      viewport: options.viewportName || "desktop",
      capturedAt: new Date().toISOString(),
      url: options.url || pageMetaResult?.result?.value?.url || null,
      page: redactValue(pageMetaResult?.result?.value || {}),
      semantic: redactValue(semanticResult?.result?.value || { nodes: [], maskRects: [] }),
      console: redactValue(consoleEvents),
      network: redactValue(network),
      hyperopenDebugSnapshot,
      screenshot: {
        format: "png",
        dataBase64: screenshot.data
      }
    };

    assertSnapshotPayload(payload);
    return payload;
  } finally {
    for (const stop of unsubscribe) {
      try {
        stop();
      } catch (_err) {
        // ignore
      }
    }
    await attached.cleanup();
  }
}
