import { REQUIRED_VIEWPORTS } from "../src/design_review_contracts.mjs";
import { DESIGN_REVIEW_PASS_NAMES } from "../src/design_review/pass_registry.mjs";

const REQUIRED_PASSES = DESIGN_REVIEW_PASS_NAMES;
const REQUIRED_VIEWPORT_SPECS = Object.entries(REQUIRED_VIEWPORTS).map(([name, viewport]) => ({
  name,
  width: viewport.width,
  height: viewport.height
}));

function passForCategories(passName, categories, index) {
  const failedByCategory = {
    "native-control": ["native-control-leak"],
    "styling-consistency": ["token-drift", "spacing-mismatch", "radius-mismatch"],
    interaction: ["focus-regression", "hover-regression"],
    "layout-regression": ["layout-overflow", "z-index-overlap"],
    "jank-perf": ["flicker-jank"]
  };
  const status = (failedByCategory[passName] || []).some((category) => categories.includes(category))
    ? "FAIL"
    : "PASS";
  return {
    pass: passName,
    status,
    issueCount: (failedByCategory[passName] || []).filter((category) => categories.includes(category))
      .length,
    evidencePaths: [`/hyperopen/tmp/browser-inspection/design-review-evals/${index}/${passName}.json`]
  };
}

function issueForCategory(caseId, category, route, selector, index) {
  const passByCategory = {
    "native-control-leak": "native-control",
    "token-drift": "styling-consistency",
    "spacing-mismatch": "styling-consistency",
    "radius-mismatch": "styling-consistency",
    "focus-regression": "interaction",
    "hover-regression": "interaction",
    "layout-overflow": "layout-regression",
    "z-index-overlap": "layout-regression",
    "flicker-jank": "jank-perf"
  };
  return {
    id: `${caseId}:${category}`,
    fingerprint: `${caseId}:${category}:fingerprint`,
    targetId: `${route.slice(1)}-route`,
    severity: ["focus-regression", "layout-overflow", "native-control-leak"].includes(category)
      ? "high"
      : "medium",
    pass: passByCategory[category],
    route,
    viewport: "review-375",
    selector,
    reproSteps: [`Open ${route}.`, `Inspect ${selector}.`],
    artifactPath: `/hyperopen/tmp/browser-inspection/design-review-evals/${index}/${category}.png`,
    evidenceRefs: [
      {
        kind: "artifact",
        path: `/hyperopen/tmp/browser-inspection/design-review-evals/${index}/${category}.png`
      }
    ],
    observedBehavior: `${category} was detected during design review.`,
    expectedBehavior: "The reviewed UI should conform to the design-system browser QA contract.",
    category,
    ruleCode: category
  };
}

function makeCase(index, { id, sourcePath, route, selector, categories }) {
  const issues = categories.map((category) => issueForCategory(id, category, route, selector, index));
  return {
    id,
    sourcePath,
    expectedCategories: categories,
    report: {
      runId: `design-review-eval-${index}`,
      runDir: `/hyperopen/tmp/browser-inspection/design-review-evals/${index}`,
      runStatus: "completed",
      reviewOutcome: "FAIL",
      state: "FAIL",
      startedAt: "2026-03-16T00:00:00.000Z",
      endedAt: "2026-03-16T00:01:00.000Z",
      inspectedViewports: REQUIRED_VIEWPORT_SPECS,
      targets: [{ id: `${route.slice(1)}-route`, route }],
      passes: REQUIRED_PASSES.map((passName) => passForCategories(passName, categories, index)),
      issues,
      blindSpots: [
        {
          pass: "interaction",
          targetId: `${route.slice(1)}-route`,
          route,
          viewport: "review-375",
          reasonCode: "state-coverage-gap",
          message:
            "Hover, active, disabled, and loading states still require route-specific actions when not present by default.",
          evidenceRefs: []
        }
      ],
      residualBlindSpots: [
        `${route}: hover, active, disabled, and loading states still require targeted route actions when not present by default.`
      ]
    }
  };
}

const caseSpecs = [
  {
    id: "trade-short-height-overflow",
    sourcePath: "/hyperopen/docs/qa/desktop-trade-short-height-scroll-qa-2026-03-14.md",
    route: "/trade",
    selector: "[data-parity-id='trade-root']",
    categories: ["layout-overflow"]
  },
  {
    id: "funding-popover-layering",
    sourcePath: "/hyperopen/docs/qa/funding-modal-popover-fallback-2026-03-07.md",
    route: "/trade",
    selector: "[data-role='funding-modal']",
    categories: ["z-index-overlap"]
  },
  {
    id: "funding-seams-spacing",
    sourcePath: "/hyperopen/docs/qa/funding-modal-view-seams-browser-qa-2026-03-12.md",
    route: "/trade",
    selector: "[data-role='funding-modal']",
    categories: ["spacing-mismatch"]
  },
  {
    id: "funding-workflow-focus",
    sourcePath: "/hyperopen/docs/qa/funding-modal-workflow-slices-2026-03-07.md",
    route: "/trade",
    selector: "[data-role='funding-modal-close']",
    categories: ["focus-regression"]
  },
  {
    id: "mobile-tablet-token-drift",
    sourcePath: "/hyperopen/docs/qa/hyperopen-vs-hyperliquid-mobile-tablet-audit-2026-03-07.md",
    route: "/trade",
    selector: "[data-parity-id='order-form']",
    categories: ["token-drift"]
  },
  {
    id: "iphone-native-control",
    sourcePath: "/hyperopen/docs/qa/iphone-trade-parity-wave-qa-2026-03-08.md",
    route: "/trade",
    selector: "select",
    categories: ["native-control-leak"]
  },
  {
    id: "mobile-account-overflow",
    sourcePath: "/hyperopen/docs/qa/mobile-account-surface-parity-qa-2026-03-09.md",
    route: "/portfolio",
    selector: "[data-parity-id='portfolio-root']",
    categories: ["layout-overflow"]
  },
  {
    id: "mobile-account-card-spacing",
    sourcePath: "/hyperopen/docs/qa/mobile-account-tab-expandable-cards-qa-2026-03-08.md",
    route: "/portfolio",
    selector: "[data-role='portfolio-account-table']",
    categories: ["spacing-mismatch"]
  },
  {
    id: "mobile-account-hover-state",
    sourcePath: "/hyperopen/docs/qa/mobile-account-tabs-positions-follow-up-qa-2026-03-08.md",
    route: "/portfolio",
    selector: "[data-role='portfolio-account-table'] button",
    categories: ["hover-regression"]
  },
  {
    id: "balances-send-focus",
    sourcePath: "/hyperopen/docs/qa/mobile-balances-send-modal-qa-2026-03-08.md",
    route: "/portfolio",
    selector: "[data-role='balances-send-submit']",
    categories: ["focus-regression"]
  },
  {
    id: "position-overlay-layering",
    sourcePath: "/hyperopen/docs/qa/mobile-position-overlay-parity-qa-2026-03-08.md",
    route: "/trade",
    selector: "[data-role='position-tpsl-mobile-sheet-layer']",
    categories: ["z-index-overlap"]
  },
  {
    id: "positions-card-radius",
    sourcePath: "/hyperopen/docs/qa/mobile-positions-card-visual-parity-qa-2026-03-08.md",
    route: "/trade",
    selector: "[data-role='position-card']",
    categories: ["radius-mismatch"]
  },
  {
    id: "small-viewport-overflow-spacing",
    sourcePath: "/hyperopen/docs/qa/small-viewport-follow-up-wave-qa-2026-03-08.md",
    route: "/trade",
    selector: "[data-parity-id='trade-root']",
    categories: ["layout-overflow", "spacing-mismatch"]
  },
  {
    id: "small-viewport-token-radius",
    sourcePath: "/hyperopen/docs/qa/small-viewport-hyperliquid-parity-implementation-qa-2026-03-08.md",
    route: "/trade",
    selector: "[data-parity-id='order-form']",
    categories: ["token-drift", "radius-mismatch"]
  },
  {
    id: "overlay-drag-jank",
    sourcePath: "/hyperopen/docs/qa/position-overlay-live-drag-and-text-node-validation-2026-03-06.md",
    route: "/trade",
    selector: "[data-role='position-overlay']",
    categories: ["flicker-jank"]
  },
  {
    id: "account-surface-bootstrap-overflow",
    sourcePath: "/hyperopen/docs/qa/account-surface-bootstrap-refresh-validation-2026-03-07.md",
    route: "/portfolio",
    selector: "[data-parity-id='portfolio-root']",
    categories: ["layout-overflow"]
  },
  {
    id: "info-post-token-drift",
    sourcePath: "/hyperopen/docs/qa/info-post-hotspot-baseline-2026-03-05.md",
    route: "/trade",
    selector: "[data-role='info-post']",
    categories: ["token-drift"]
  },
  {
    id: "cadence-sensitivity-jank",
    sourcePath: "/hyperopen/docs/qa/performance-metrics-irregular-cadence-sensitivity-2026-02-27.md",
    route: "/vaults",
    selector: "[data-role='vault-detail-chart-plot-area']",
    categories: ["flicker-jank"]
  },
  {
    id: "cadence-validation-jank",
    sourcePath: "/hyperopen/docs/qa/performance-metrics-irregular-cadence-validation-2026-02-27.md",
    route: "/vaults",
    selector: "[data-role='vault-detail-chart-plot-area']",
    categories: ["flicker-jank"]
  },
  {
    id: "quantstats-token-drift",
    sourcePath: "/hyperopen/docs/qa/performance-metrics-quantstats-parity-report-2026-02-26.md",
    route: "/vaults",
    selector: "[data-role='vault-detail-performance-metrics-card']",
    categories: ["token-drift"]
  },
  {
    id: "quantstats-radius",
    sourcePath: "/hyperopen/docs/qa/performance-metrics-quantstats-tests-coverage-2026-02-26.md",
    route: "/vaults",
    selector: "[data-role='vault-detail-performance-metrics-card']",
    categories: ["radius-mismatch"]
  },
  {
    id: "user-funding-focus",
    sourcePath: "/hyperopen/docs/qa/user-funding-rate-limit-scheduling-validation-2026-03-05.md",
    route: "/trade",
    selector: "[data-role='funding-submit']",
    categories: ["focus-regression"]
  },
  {
    id: "spectate-hover",
    sourcePath: "/hyperopen/docs/qa/user-funding-spot-clearinghouse-spectate-mode-sampling-2026-03-05.md",
    route: "/trade",
    selector: "[data-role='spectate-mode-open-button']",
    categories: ["hover-regression"]
  },
  {
    id: "websocket-overflow",
    sourcePath: "/hyperopen/docs/qa/websocket-user-runtime-adapter-validation-2026-03-07.md",
    route: "/trade",
    selector: "[data-parity-id='account-equity']",
    categories: ["layout-overflow"]
  },
  {
    id: "ws-migration-native-control",
    sourcePath: "/hyperopen/docs/qa/ws-migration-impact-validation-2026-03-05.md",
    route: "/api-wallets",
    selector: "input[type='file']",
    categories: ["native-control-leak"]
  },
  {
    id: "funding-seams-hover",
    sourcePath: "/hyperopen/docs/qa/funding-modal-view-seams-browser-qa-2026-03-12.md",
    route: "/trade",
    selector: "[data-role='funding-modal-tab']",
    categories: ["hover-regression"]
  },
  {
    id: "mobile-account-overflow-focus",
    sourcePath: "/hyperopen/docs/qa/mobile-account-surface-parity-qa-2026-03-09.md",
    route: "/portfolio",
    selector: "[data-role='portfolio-account-table']",
    categories: ["layout-overflow", "focus-regression"]
  },
  {
    id: "vault-detail-token-jank",
    sourcePath: "/hyperopen/docs/qa/performance-metrics-quantstats-parity-report-2026-02-26.md",
    route: "/vaults",
    selector: "[data-role='vault-detail-chart-plot-area']",
    categories: ["token-drift", "flicker-jank"]
  },
  {
    id: "trade-spacing-radius",
    sourcePath: "/hyperopen/docs/qa/desktop-trade-short-height-scroll-qa-2026-03-14.md",
    route: "/trade",
    selector: "[data-parity-id='order-form']",
    categories: ["spacing-mismatch", "radius-mismatch"]
  },
  {
    id: "api-wallets-focus-overflow",
    sourcePath: "/hyperopen/docs/qa/mobile-balances-send-modal-qa-2026-03-08.md",
    route: "/api-wallets",
    selector: "[data-role='api-wallets-table']",
    categories: ["focus-regression", "layout-overflow"]
  }
];

export const browserQaEvalCorpus = {
  cases: caseSpecs.map((entry, index) => makeCase(index + 1, entry))
};
