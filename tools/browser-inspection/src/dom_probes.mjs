function jsString(value) {
  return JSON.stringify(value);
}

function identityScriptParts() {
  return `
    const shortText = (value) => {
      const text = String(value || "").replace(/\\s+/g, " ").trim();
      return text.length > 160 ? text.slice(0, 160) : text;
    };

    const describeNode = (el) => ({
      tag: el.tagName.toLowerCase(),
      id: el.id || null,
      className: typeof el.className === "string" ? el.className : "",
      dataRole: el.getAttribute("data-role"),
      parityId: el.getAttribute("data-parity-id"),
      text: shortText(el.innerText || el.textContent || "")
    });

    const rectFor = (el) => {
      const rect = el.getBoundingClientRect();
      return {
        left: rect.left,
        top: rect.top,
        right: rect.right,
        bottom: rect.bottom,
        width: rect.width,
        height: rect.height
      };
    };

    const safeQuery = (selector) => {
      try {
        return [...document.querySelectorAll(selector)];
      } catch (_error) {
        return [];
      }
    };
  `;
}

function evaluateExpression(service, sessionId, expression, options = {}) {
  return service
    .evaluate({
      sessionId,
      expression,
      allowUnsafeEval: options.allowUnsafeEval,
      timeoutMs: options.timeoutMs
    })
    .then((result) => result.result);
}

export function computedStyleExpression({ selectors = [], props = [], maxMatches = 10 } = {}) {
  return `(() => {
    ${identityScriptParts()}
    const selectors = ${jsString(selectors)};
    const props = ${jsString(props)};
    const maxMatches = ${Number(maxMatches) || 10};
    return {
      viewport: {
        width: window.innerWidth,
        height: window.innerHeight
      },
      selectors: selectors.map((selector) => {
        const matches = safeQuery(selector);
        return {
          selector,
          count: matches.length,
          matches: matches.slice(0, maxMatches).map((el) => {
            const style = getComputedStyle(el);
            const styles = {};
            for (const prop of props) {
              styles[prop] = style[prop] ?? null;
            }
            return {
              ...describeNode(el),
              rect: rectFor(el),
              visible: rectFor(el).width > 0 && rectFor(el).height > 0,
              scrollWidth: el.scrollWidth,
              clientWidth: el.clientWidth,
              scrollHeight: el.scrollHeight,
              clientHeight: el.clientHeight,
              styles
            };
          })
        };
      })
    };
  })();`;
}

export function nativeControlsExpression({ allowlist = [] } = {}) {
  return `(() => {
    ${identityScriptParts()}
    const allowlist = ${jsString(allowlist)};
    const matchesAllowlist = (el) =>
      allowlist.some((selector) => {
        try {
          return el.matches(selector);
        } catch (_error) {
          return false;
        }
      });

    const controls = [...document.querySelectorAll("input, select, textarea, button")]
      .map((el) => {
        const type = (el.getAttribute("type") || "").toLowerCase();
        const descriptor = el.tagName.toLowerCase() === "input"
          ? \`input[\${type || "text"}]\`
          : el.tagName.toLowerCase();
        const specialNative = el.tagName.toLowerCase() === "select" ||
          ["date", "time", "color", "file", "number"].includes(type);
        return {
          ...describeNode(el),
          descriptor,
          inputType: type || null,
          disabled: Boolean(el.disabled),
          matchedAllowlist: matchesAllowlist(el),
          specialNative,
          rect: rectFor(el),
          visible: rectFor(el).width > 0 && rectFor(el).height > 0
        };
      });

    return {
      count: controls.length,
      controls,
      unexpectedSpecialNative: controls.filter(
        (entry) => entry.visible && entry.specialNative && !entry.matchedAllowlist
      )
    };
  })();`;
}

export function boundingBoxesExpression({ selectors = [] } = {}) {
  return `(() => {
    ${identityScriptParts()}
    const selectors = ${jsString(selectors)};
    return {
      viewport: {
        width: window.innerWidth,
        height: window.innerHeight
      },
      selectors: selectors.map((selector) => ({
        selector,
        matches: safeQuery(selector).map((el) => ({
          ...describeNode(el),
          rect: rectFor(el)
        }))
      }))
    };
  })();`;
}

export function focusWalkExpression({ selectors = [], limit = 20 } = {}) {
  return `(() => {
    ${identityScriptParts()}
    const selectors = ${jsString(selectors)};
    const limit = ${Number(limit) || 20};
    const roots = selectors.length === 0
      ? [document.body]
      : selectors.flatMap((selector) => safeQuery(selector));
    const isVisibleForFocusWalk = (node) => {
      if (!node || typeof getComputedStyle !== "function") {
        return false;
      }
      if ("disabled" in node && node.disabled) {
        return false;
      }
      const rect = rectFor(node);
      if (rect.width <= 0 || rect.height <= 0) {
        return false;
      }
      const style = getComputedStyle(node);
      if (!style || style.display === "none" || style.visibility === "hidden") {
        return false;
      }
      const opacity = parseFloat(style.opacity || "1");
      if (Number.isFinite(opacity) && opacity <= 0) {
        return false;
      }
      if (style.pointerEvents === "none") {
        return false;
      }
      if (typeof node.closest === "function") {
        const hiddenAncestor = node.closest("[hidden],[aria-hidden='true']");
        if (hiddenAncestor) {
          return false;
        }
      }
      return true;
    };

    const seen = new Set();
    const focusables = [];
    for (const root of roots) {
      if (!root) {
        continue;
      }
      const nodes = [...root.querySelectorAll("a[href], button, input, select, textarea, [tabindex]:not([tabindex='-1'])")];
      for (const node of nodes) {
        if (seen.has(node)) {
          continue;
        }
        seen.add(node);
        if (!isVisibleForFocusWalk(node)) {
          continue;
        }
        focusables.push(node);
      }
    }

    const previous = document.activeElement;
    const steps = [];
    for (const node of focusables.slice(0, limit)) {
      try {
        node.focus({ preventScroll: true });
      } catch (_error) {
        continue;
      }
      const style = getComputedStyle(node);
      const outlineWidth = parseFloat(style.outlineWidth || "0") || 0;
      const outlineVisible = outlineWidth > 0 && style.outlineStyle !== "none";
      const boxShadowVisible = style.boxShadow && style.boxShadow !== "none";
      const hasVisibleFocusIndicator = outlineVisible || boxShadowVisible;
      steps.push({
        ...describeNode(node),
        rect: rectFor(node),
        focusVisible: (() => {
          try {
            return node.matches(":focus-visible");
          } catch (_error) {
            return false;
          }
        })(),
        hasVisibleFocusIndicator,
        styles: {
          outlineWidth: style.outlineWidth,
          outlineStyle: style.outlineStyle,
          outlineColor: style.outlineColor,
          boxShadow: style.boxShadow,
          borderColor: style.borderColor
        }
      });
    }
    if (previous && typeof previous.focus === "function") {
      try {
        previous.focus({ preventScroll: true });
      } catch (_error) {
      }
    }

    return {
      count: focusables.length,
      steps,
      invisibleFocusSelectors: steps
        .filter((entry) => !entry.hasVisibleFocusIndicator)
        .map((entry) => entry.parityId || entry.dataRole || entry.id || entry.tag)
    };
  })();`;
}

export function layoutAuditExpression({ selectors = [], maxMatches = 20 } = {}) {
  return `(() => {
    ${identityScriptParts()}
    const selectors = ${jsString(selectors)};
    const maxMatches = ${Number(maxMatches) || 20};
    const viewportWidth = window.innerWidth;
    const viewportHeight = window.innerHeight;
    const docWidth = Math.max(
      document.documentElement ? document.documentElement.scrollWidth : 0,
      document.body ? document.body.scrollWidth : 0
    );
    const hasScrollableOverflowAncestor = (el, axis) => {
      const styleProp = axis === "x" ? "overflowX" : "overflowY";
      let current = el ? el.parentElement : null;
      while (current) {
        const style = getComputedStyle(current);
        const overflowValue = style?.[styleProp];
        const canScroll = axis === "x"
          ? current.scrollWidth > current.clientWidth + 1
          : current.scrollHeight > current.clientHeight + 1;
        if (canScroll && ["auto", "scroll", "overlay"].includes(overflowValue)) {
          return true;
        }
        current = current.parentElement;
      }
      return false;
    };
    const fixedSticky = [...document.querySelectorAll("body *")]
      .filter((el) => {
        const rect = rectFor(el);
        if (rect.width <= 0 || rect.height <= 0) {
          return false;
        }
        const position = getComputedStyle(el).position;
        return position === "fixed" || position === "sticky";
      })
      .slice(0, maxMatches)
      .map((el) => ({
        ...describeNode(el),
        rect: rectFor(el),
        position: getComputedStyle(el).position,
        zIndex: getComputedStyle(el).zIndex
      }));

    const overflowIssues = selectors.flatMap((selector) =>
      safeQuery(selector).slice(0, maxMatches).flatMap((el) => {
        const rect = rectFor(el);
        const style = getComputedStyle(el);
        const issues = [];
        const horizontallyOutOfViewport = rect.left < -1 || rect.right > viewportWidth + 1;
        if (horizontallyOutOfViewport && !hasScrollableOverflowAncestor(el, "x")) {
          issues.push("out-of-viewport");
        }
        if (el.scrollWidth > el.clientWidth + 1 && !["hidden", "clip"].includes(style.overflowX)) {
          issues.push("horizontal-overflow");
        }
        if (el.scrollHeight > el.clientHeight + 1 && !["hidden", "clip"].includes(style.overflowY)) {
          issues.push("vertical-overflow");
        }
        return issues.length === 0
          ? []
          : [{
              ...describeNode(el),
              selector,
              issues,
              rect,
              scrollWidth: el.scrollWidth,
              clientWidth: el.clientWidth,
              scrollHeight: el.scrollHeight,
              clientHeight: el.clientHeight,
              overflowX: style.overflowX,
              overflowY: style.overflowY
            }];
      })
    );

    return {
      viewport: {
        width: viewportWidth,
        height: viewportHeight
      },
      documentHorizontalOverflowPx: Math.max(0, docWidth - viewportWidth),
      fixedSticky,
      overflowIssues
    };
  })();`;
}

export function interactionTraceExpression({
  selectors = [],
  focusLimit = 4,
  scrollFractions = [0, 0.5, 0],
  delayMs = 90,
  settleDelayMs = 0,
  dispatchActions = []
} = {}) {
  return `(async () => {
    ${identityScriptParts()}
    const selectors = ${jsString(selectors)};
    const focusLimit = ${Number(focusLimit) || 4};
    const scrollFractions = ${jsString(scrollFractions)};
    const delayMs = ${Number(delayMs) || 90};
    const settleDelayMs = ${Math.max(0, Number(settleDelayMs) || 0)};
    const dispatchActions = ${jsString(dispatchActions)};
    const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
    const metrics = {
      performanceObserverSupported: typeof PerformanceObserver === "function",
      layoutShiftValue: 0,
      layoutShiftEntries: 0,
      longTaskCount: 0,
      maxLongTaskMs: 0,
      focusCount: 0,
      scrolledFractions: [],
      dispatchedActionCount: 0
    };

    if (globalThis.HYPEROPEN_DEBUG?.waitForIdle) {
      try {
        await globalThis.HYPEROPEN_DEBUG.waitForIdle({ quietMs: 160, timeoutMs: 4000, pollMs: 30 });
      } catch (_error) {
      }
    }
    if (settleDelayMs > 0) {
      await sleep(settleDelayMs);
    }

    const observers = [];
    if (metrics.performanceObserverSupported) {
      try {
        const ls = new PerformanceObserver((list) => {
          for (const entry of list.getEntries()) {
            if (!entry.hadRecentInput) {
              metrics.layoutShiftValue += entry.value || 0;
              metrics.layoutShiftEntries += 1;
            }
          }
        });
        ls.observe({ type: "layout-shift" });
        observers.push(ls);
      } catch (_error) {
      }
      try {
        const lt = new PerformanceObserver((list) => {
          for (const entry of list.getEntries()) {
            metrics.longTaskCount += 1;
            metrics.maxLongTaskMs = Math.max(metrics.maxLongTaskMs, entry.duration || 0);
          }
        });
        lt.observe({ type: "longtask" });
        observers.push(lt);
      } catch (_error) {
      }
    }

    if (dispatchActions.length > 0 && globalThis.HYPEROPEN_DEBUG?.dispatchMany) {
      try {
        await globalThis.HYPEROPEN_DEBUG.dispatchMany(dispatchActions);
        metrics.dispatchedActionCount = dispatchActions.length;
        if (globalThis.HYPEROPEN_DEBUG.waitForIdle) {
          await globalThis.HYPEROPEN_DEBUG.waitForIdle({ quietMs: 120, timeoutMs: 4000, pollMs: 30 });
        }
      } catch (_error) {
      }
    }

    const roots = selectors.length === 0
      ? [document.body]
      : selectors.flatMap((selector) => safeQuery(selector));
    const seen = new Set();
    const focusables = [];
    for (const root of roots) {
      if (!root) {
        continue;
      }
      for (const node of root.querySelectorAll("a[href], button, input, select, textarea, [tabindex]:not([tabindex='-1'])")) {
        if (seen.has(node)) {
          continue;
        }
        seen.add(node);
        const rect = rectFor(node);
        if (rect.width > 0 && rect.height > 0) {
          focusables.push(node);
        }
      }
    }
    for (const node of focusables.slice(0, focusLimit)) {
      try {
        node.focus({ preventScroll: true });
        metrics.focusCount += 1;
      } catch (_error) {
      }
      await sleep(delayMs);
    }

    const maxScroll = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
    for (const fraction of scrollFractions) {
      const top = Math.max(0, Math.min(maxScroll, maxScroll * Number(fraction || 0)));
      window.scrollTo({ top, behavior: "auto" });
      metrics.scrolledFractions.push(Number(fraction || 0));
      await sleep(delayMs);
    }

    for (const observer of observers) {
      try {
        observer.disconnect();
      } catch (_error) {
      }
    }

    return metrics;
  })();`;
}

export async function getComputedStyles(service, sessionId, options = {}) {
  return evaluateExpression(service, sessionId, computedStyleExpression(options), {
    timeoutMs: options.timeoutMs || 20000
  });
}

export async function listNativeControls(service, sessionId, options = {}) {
  return evaluateExpression(service, sessionId, nativeControlsExpression(options), {
    timeoutMs: options.timeoutMs || 15000
  });
}

export async function getBoundingBoxes(service, sessionId, options = {}) {
  return evaluateExpression(service, sessionId, boundingBoxesExpression(options), {
    timeoutMs: options.timeoutMs || 15000
  });
}

export async function runFocusWalk(service, sessionId, options = {}) {
  return evaluateExpression(service, sessionId, focusWalkExpression(options), {
    timeoutMs: options.timeoutMs || 20000
  });
}

export async function runLayoutAudit(service, sessionId, options = {}) {
  return evaluateExpression(service, sessionId, layoutAuditExpression(options), {
    timeoutMs: options.timeoutMs || 20000
  });
}

export async function traceInteraction(service, sessionId, options = {}) {
  return evaluateExpression(service, sessionId, interactionTraceExpression(options), {
    timeoutMs: options.timeoutMs || 25000
  });
}
