import vm from "node:vm";
import test from "node:test";
import assert from "node:assert/strict";
import {
  computedStyleExpression,
  focusWalkExpression,
  interactionTraceExpression,
  layoutAuditExpression,
  nativeControlsExpression
} from "../src/dom_probes.mjs";

function createElement(context, options = {}) {
  const attributes = { ...(options.attributes || {}) };
  const rect = options.rect || {
    left: 0,
    top: 0,
    right: 120,
    bottom: 40,
    width: 120,
    height: 40
  };
  const element = {
    tagName: String(options.tagName || "div").toUpperCase(),
    id: options.id || "",
    className: options.className || "",
    innerText: options.innerText || "",
    textContent: options.textContent || options.innerText || "",
    disabled: Boolean(options.disabled),
    scrollWidth: options.scrollWidth ?? rect.width,
    clientWidth: options.clientWidth ?? rect.width,
    scrollHeight: options.scrollHeight ?? rect.height,
    clientHeight: options.clientHeight ?? rect.height,
    parentElement: null,
    children: [],
    getAttribute(name) {
      return Object.hasOwn(attributes, name) ? attributes[name] : null;
    },
    getBoundingClientRect() {
      return rect;
    },
    matches(selector) {
      if (selector === ":focus-visible") {
        return context.document.activeElement === element;
      }
      if (selector === "input, select, textarea, button") {
        return ["INPUT", "SELECT", "TEXTAREA", "BUTTON"].includes(element.tagName);
      }
      const attrMatch = selector.match(/^\[([^=]+)=['"]([^'"]+)['"]\]$/);
      if (attrMatch) {
        return element.getAttribute(attrMatch[1]) === attrMatch[2];
      }
      if (selector.startsWith(".")) {
        return element.className.split(/\s+/).includes(selector.slice(1));
      }
      return element.tagName.toLowerCase() === selector.toLowerCase();
    },
    closest(selector) {
      let current = element;
      while (current) {
        if (typeof current.matches === "function" && current.matches(selector)) {
          return current;
        }
        current = current.parentElement;
      }
      return null;
    },
    querySelectorAll(selector) {
      return (options.queryMap?.[selector] || []).slice();
    },
    focus() {
      context.document.activeElement = element;
    }
  };
  return element;
}

function buildContext() {
  const context = {
    setTimeout,
    clearTimeout,
    console
  };
  context.globalThis = context;
  context.window = {
    innerWidth: 1280,
    innerHeight: 900,
    scrollTo(_options) {}
  };
  context.document = {
    activeElement: null,
    body: null,
    documentElement: { scrollHeight: 1800 },
    querySelectorAll() {
      return [];
    }
  };
  context.getComputedStyle = (element) => ({
    display: "block",
    position: "static",
    visibility: "visible",
    opacity: "1",
    pointerEvents: "auto",
    outlineWidth: "0px",
    outlineStyle: "none",
    outlineColor: "rgba(0, 0, 0, 0)",
    boxShadow: "none",
    borderColor: "rgb(0, 0, 0)",
    overflowX: "visible",
    overflowY: "visible",
    ...element.__style
  });
  return context;
}

test("nativeControlsExpression flags unexpected special native controls", async () => {
  const context = buildContext();
  const select = createElement(context, { tagName: "select" });
  const allowlistedFile = createElement(context, {
    tagName: "input",
    attributes: {
      type: "file",
      "data-role": "allowed-file"
    }
  });
  context.document.querySelectorAll = (selector) =>
    selector === "input, select, textarea, button" ? [select, allowlistedFile] : [];

  const result = await vm.runInNewContext(
    nativeControlsExpression({ allowlist: ["[data-role='allowed-file']"] }),
    context
  );

  assert.equal(result.unexpectedSpecialNative.length, 1);
  assert.equal(result.unexpectedSpecialNative[0].descriptor, "select");
});

test("computedStyleExpression returns requested style props for selector matches", async () => {
  const context = buildContext();
  const card = createElement(context, {
    tagName: "div",
    className: "card",
    innerText: "Card"
  });
  card.__style = {
    fontFamily: "IBM Plex Sans",
    fontSize: "14px",
    borderRadius: "8px",
    gap: "16px"
  };
  context.document.querySelectorAll = (selector) => (selector === ".card" ? [card] : []);

  const result = await vm.runInNewContext(
    computedStyleExpression({
      selectors: [".card"],
      props: ["fontFamily", "fontSize", "borderRadius", "gap"]
    }),
    context
  );

  assert.equal(result.selectors[0].count, 1);
  assert.equal(result.selectors[0].matches[0].styles.fontSize, "14px");
  assert.equal(result.selectors[0].matches[0].styles.borderRadius, "8px");
  assert.equal(result.selectors[0].matches[0].styles.gap, "16px");
});

test("focusWalkExpression reports controls without visible focus indicators", async () => {
  const context = buildContext();
  const root = createElement(context, { tagName: "div", className: "scope" });
  const plainButton = createElement(context, {
    tagName: "button",
    id: "plain-button"
  });
  plainButton.__style = {
    outlineWidth: "0px",
    outlineStyle: "none",
    boxShadow: "none"
  };
  const focusedButton = createElement(context, {
    tagName: "button",
    attributes: { "data-role": "visible-focus-button" }
  });
  focusedButton.__style = {
    outlineWidth: "2px",
    outlineStyle: "solid",
    outlineColor: "rgb(255, 0, 0)"
  };
  root.querySelectorAll = (selector) =>
    selector === "a[href], button, input, select, textarea, [tabindex]:not([tabindex='-1'])"
      ? [plainButton, focusedButton]
      : [];
  plainButton.parentElement = root;
  focusedButton.parentElement = root;
  context.document.body = root;
  context.document.activeElement = root;
  context.document.querySelectorAll = (selector) => (selector === ".scope" ? [root] : []);

  const result = await vm.runInNewContext(
    focusWalkExpression({ selectors: [".scope"], limit: 5 }),
    context
  );

  assert.equal(result.count, 2);
  assert.deepEqual(Array.from(result.invisibleFocusSelectors), ["plain-button"]);
});

test("focusWalkExpression skips hidden and disabled controls", async () => {
  const context = buildContext();
  const root = createElement(context, { tagName: "div", className: "scope" });
  const hiddenButton = createElement(context, {
    tagName: "button",
    id: "hidden-button"
  });
  hiddenButton.__style = {
    visibility: "hidden",
    opacity: "0",
    pointerEvents: "none"
  };
  const disabledButton = createElement(context, {
    tagName: "button",
    id: "disabled-button",
    disabled: true
  });
  const visibleButton = createElement(context, {
    tagName: "button",
    id: "visible-button"
  });
  visibleButton.__style = {
    outlineWidth: "2px",
    outlineStyle: "solid",
    outlineColor: "rgb(255, 0, 0)"
  };
  root.querySelectorAll = (selector) =>
    selector === "a[href], button, input, select, textarea, [tabindex]:not([tabindex='-1'])"
      ? [hiddenButton, disabledButton, visibleButton]
      : [];
  hiddenButton.parentElement = root;
  disabledButton.parentElement = root;
  visibleButton.parentElement = root;
  context.document.body = root;
  context.document.activeElement = root;
  context.document.querySelectorAll = (selector) => (selector === ".scope" ? [root] : []);

  const result = await vm.runInNewContext(
    focusWalkExpression({ selectors: [".scope"], limit: 5 }),
    context
  );

  assert.equal(result.count, 1);
  assert.equal(result.steps[0].id, "visible-button");
  assert.deepEqual(Array.from(result.invisibleFocusSelectors), []);
});

test("layoutAuditExpression ignores out-of-viewport descendants inside horizontal scrollers", async () => {
  const context = buildContext();
  context.window.innerWidth = 768;
  const body = createElement(context, {
    tagName: "body",
    rect: {
      left: 0,
      top: 0,
      right: 768,
      bottom: 900,
      width: 768,
      height: 900
    },
    scrollWidth: 768,
    clientWidth: 768
  });
  const scroller = createElement(context, {
    tagName: "div",
    className: "scroller",
    rect: {
      left: 24,
      top: 120,
      right: 744,
      bottom: 420,
      width: 720,
      height: 300
    },
    scrollWidth: 920,
    clientWidth: 720
  });
  scroller.__style = {
    overflowX: "auto"
  };
  const row = createElement(context, {
    tagName: "tr",
    attributes: { "data-role": "vault-row" },
    rect: {
      left: 24,
      top: 180,
      right: 804,
      bottom: 232,
      width: 780,
      height: 52
    },
    scrollWidth: 780,
    clientWidth: 780
  });
  scroller.parentElement = body;
  row.parentElement = scroller;
  context.document.body = body;
  context.document.documentElement.scrollWidth = 768;
  context.document.querySelectorAll = (selector) => {
    if (selector === "[data-role='vault-row']") {
      return [row];
    }
    if (selector === "body *") {
      return [body, scroller, row];
    }
    return [];
  };

  const result = await vm.runInNewContext(
    layoutAuditExpression({ selectors: ["[data-role='vault-row']"] }),
    context
  );

  assert.equal(result.documentHorizontalOverflowPx, 0);
  assert.deepEqual(Array.from(result.overflowIssues), []);
});

test("interactionTraceExpression records layout shift and long-task metrics", async () => {
  const context = buildContext();
  const root = createElement(context, { tagName: "div", className: "scope" });
  const button = createElement(context, { tagName: "button", id: "trace-button" });
  button.parentElement = root;
  root.querySelectorAll = (selector) =>
    selector === "a[href], button, input, select, textarea, [tabindex]:not([tabindex='-1'])"
      ? [button]
      : [];
  context.document.body = root;
  context.document.activeElement = root;
  context.document.querySelectorAll = (selector) => (selector === ".scope" ? [root] : []);
  context.HYPEROPEN_DEBUG = {
    async dispatchMany(actions) {
      return { count: actions.length };
    },
    async waitForIdle() {
      return { settled: true };
    }
  };
  context.PerformanceObserver = class PerformanceObserver {
    constructor(callback) {
      this.callback = callback;
    }

    observe(options) {
      if (options.type === "layout-shift") {
        this.callback({
          getEntries() {
            return [{ value: 0.12, hadRecentInput: false }];
          }
        });
      }
      if (options.type === "longtask") {
        this.callback({
          getEntries() {
            return [{ duration: 145 }];
          }
        });
      }
    }

    disconnect() {}
  };

  const result = await vm.runInNewContext(
    interactionTraceExpression({
      selectors: [".scope"],
      focusLimit: 1,
      scrollFractions: [0, 0.5, 0],
      delayMs: 1,
      dispatchActions: [["actions/example"]]
    }),
    context
  );

  assert.equal(result.performanceObserverSupported, true);
  assert.equal(result.layoutShiftValue, 0.12);
  assert.equal(result.maxLongTaskMs, 145);
  assert.equal(result.focusCount, 1);
  assert.equal(result.dispatchedActionCount, 1);
  assert.deepEqual(Array.from(result.scrolledFractions), [0, 0.5, 0]);
});

test("interactionTraceExpression supports an additional post-idle settle delay", async () => {
  const delays = [];
  const context = buildContext();
  context.setTimeout = (callback, ms) => {
    delays.push(ms);
    callback();
    return delays.length;
  };

  const root = createElement(context, { tagName: "div", className: "scope" });
  const button = createElement(context, { tagName: "button", id: "trace-button" });
  button.parentElement = root;
  root.querySelectorAll = (selector) =>
    selector === "a[href], button, input, select, textarea, [tabindex]:not([tabindex='-1'])"
      ? [button]
      : [];
  context.document.body = root;
  context.document.activeElement = root;
  context.document.querySelectorAll = (selector) => (selector === ".scope" ? [root] : []);
  context.HYPEROPEN_DEBUG = {
    async waitForIdle() {
      return { settled: true };
    }
  };

  await vm.runInNewContext(
    interactionTraceExpression({
      selectors: [".scope"],
      focusLimit: 1,
      scrollFractions: [0],
      delayMs: 7,
      settleDelayMs: 25
    }),
    context
  );

  assert.deepEqual(delays, [25, 7, 7]);
});
