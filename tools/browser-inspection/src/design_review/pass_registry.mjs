import {
  makeBlindSpot,
  makeIssue,
  makePassResult,
  PASS_STATUS
} from "./models.mjs";

function parseComparablePx(value) {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value !== "string") {
    return null;
  }
  const trimmed = value.trim().toLowerCase();
  if (trimmed === "0") {
    return 0;
  }
  if (!trimmed.endsWith("px")) {
    return null;
  }
  const number = Number.parseFloat(trimmed.slice(0, -2));
  return Number.isFinite(number) ? number : null;
}

function hasAllowedPxValue(value, allowed = []) {
  const parsed = parseComparablePx(value);
  if (parsed === null) {
    return null;
  }
  return allowed.some((candidate) => Math.abs(parsed - candidate) < 0.26);
}

function referencesForTarget(target) {
  return [...(target.referenceDocs || []), ...(target.workbenchScenes || [])];
}

function missingPolicyBlindSpot(passName, target, viewportName, policyKey, evidenceRefs) {
  return makeBlindSpot({
    passName,
    target,
    viewportName,
    reasonCode: "missing-approved-scale",
    message: `design review policy is missing approved values for ${policyKey}.`,
    evidenceRefs
  });
}

function unsupportedUnitBlindSpot(passName, target, viewportName, selector, prop, value, evidenceRefs) {
  return makeBlindSpot({
    passName,
    target,
    viewportName,
    reasonCode: "unsupported-style-unit",
    message: `${selector} ${prop} uses unsupported computed value ${value}.`,
    evidenceRefs
  });
}

export const PROBE_ARTIFACT_NAMES = Object.freeze({
  computedStyles: "computed-styles",
  nativeControls: "native-controls",
  focusWalk: "focus-walk",
  layoutAudit: "layout-audit",
  interactionTrace: "interaction-trace"
});

export function makeUnavailablePassResult({
  passName,
  target,
  viewportName,
  status = PASS_STATUS.TOOLING_GAP,
  reason,
  reasonCode = "probe-failure",
  evidenceRefs = []
}) {
  const blindSpots =
    status === PASS_STATUS.FAIL
      ? []
      : [
          makeBlindSpot({
            passName,
            target,
            viewportName,
            reasonCode,
            message: reason,
            evidenceRefs
          })
        ];

  return makePassResult({
    passName,
    status,
    summary: reason,
    statusReason: reason,
    issues: [],
    blindSpots,
    evidenceRefs
  });
}

function gradeVisualEvidenceCaptured({ ctx, artifacts }) {
  const references = referencesForTarget(ctx.target);
  if (references.length === 0) {
    return makePassResult({
      passName: "visual-evidence-captured",
      status: PASS_STATUS.CONFIG_GAP,
      summary: "No governed or route-level design references were configured for this target.",
      statusReason: "No governed or route-level design references were configured for this target.",
      blindSpots: [
        makeBlindSpot({
          passName: "visual-evidence-captured",
          target: ctx.target,
          viewportName: ctx.viewportName,
          reasonCode: "missing-design-reference",
          message: "visual evidence was captured, but no design reference was resolved for comparison.",
          evidenceRefs: artifacts.evidenceRefs
        })
      ],
      evidenceRefs: artifacts.evidenceRefs
    });
  }

  return makePassResult({
    passName: "visual-evidence-captured",
    status: PASS_STATUS.PASS,
    summary: "Design references were resolved and screenshots were captured for later visual review.",
    evidenceRefs: artifacts.evidenceRefs
  });
}

function gradeNativeControls({ ctx, probes, artifacts }) {
  const unexpected = probes.nativeControls?.unexpectedSpecialNative || [];
  const issues = unexpected.map((entry) =>
    makeIssue({
      passName: "native-control",
      target: ctx.target,
      viewportName: ctx.viewportName,
      severity: "medium",
      selector:
        entry.parityId ||
        entry.dataRole ||
        entry.id ||
        `${entry.tag}${entry.inputType ? `[type=${entry.inputType}]` : ""}`,
      evidenceRefs: artifacts.evidenceRefs,
      observed: `Unexpected native control ${entry.descriptor} is visible.`,
      expected: "Special native controls must be explicitly allowlisted before shipping.",
      category: "native-control-leak",
      ruleCode: "unexpected-native-control",
      reproSteps: [
        `Open ${ctx.target.route} at ${ctx.viewportName}.`,
        `Inspect the visible ${entry.descriptor} control.`
      ]
    })
  );

  return makePassResult({
    passName: "native-control",
    status: issues.length > 0 ? PASS_STATUS.FAIL : PASS_STATUS.PASS,
    summary:
      issues.length > 0
        ? `Found ${issues.length} unexpected special native control(s).`
        : "No unexpected special native controls were detected.",
    issues,
    evidenceRefs: artifacts.evidenceRefs
  });
}

function gradeStyleConsistency({ ctx, probes, artifacts, policy }) {
  const allowed = policy.approvedValues || {};
  const styleGroups = [
    {
      props: ["fontSize"],
      allowed: allowed.fontSizePx || [],
      policyKey: "fontSizePx",
      category: "token-drift",
      severity: "low",
      expected: "Font sizes should stay on approved design-system values."
    },
    {
      props: ["lineHeight"],
      allowed: allowed.lineHeightPx || [],
      policyKey: "lineHeightPx",
      category: "token-drift",
      severity: "low",
      expected: "Line heights should stay on approved design-system values."
    },
    {
      props: ["letterSpacing"],
      allowed: allowed.letterSpacingPx || [],
      policyKey: "letterSpacingPx",
      category: "token-drift",
      severity: "low",
      expected: "Letter spacing should stay on approved design-system values."
    },
    {
      props: [
        "paddingTop",
        "paddingRight",
        "paddingBottom",
        "paddingLeft",
        "marginTop",
        "marginRight",
        "marginBottom",
        "marginLeft",
        "gap",
        "rowGap",
        "columnGap"
      ],
      allowed: allowed.spacingPx || [],
      policyKey: "spacingPx",
      category: "spacing-mismatch",
      severity: "medium",
      expected: "Spacing values should stay on approved rhythm values."
    },
    {
      props: ["borderRadius"],
      allowed: allowed.radiusPx || [],
      policyKey: "radiusPx",
      category: "radius-mismatch",
      severity: "low",
      expected: "Border radii should stay on approved geometry values."
    },
    {
      props: ["borderTopWidth", "borderRightWidth", "borderBottomWidth", "borderLeftWidth"],
      allowed: allowed.borderWidthPx || [],
      policyKey: "borderWidthPx",
      category: "token-drift",
      severity: "low",
      expected: "Border widths should stay on approved design-system values."
    }
  ];

  const missingPolicies = styleGroups.filter((group) => group.allowed.length === 0);
  if (missingPolicies.length > 0) {
    return makePassResult({
      passName: "styling-consistency",
      status: PASS_STATUS.CONFIG_GAP,
      summary: `Design review policy is missing ${missingPolicies.length} approved style scale(s).`,
      statusReason: `Design review policy is missing ${missingPolicies.map((group) => group.policyKey).join(", ")}.`,
      blindSpots: missingPolicies.map((group) =>
        missingPolicyBlindSpot(
          "styling-consistency",
          ctx.target,
          ctx.viewportName,
          group.policyKey,
          artifacts.evidenceRefs
        )
      ),
      evidenceRefs: artifacts.evidenceRefs
    });
  }

  const issues = [];
  const blindSpots = [];

  for (const selectorResult of probes.computedStyles?.selectors || []) {
    for (const match of selectorResult.matches || []) {
      for (const group of styleGroups) {
        for (const prop of group.props) {
          const value = match.styles?.[prop];
          const allowedValue = hasAllowedPxValue(value, group.allowed);
          if (allowedValue === null) {
            if (typeof value === "string" && value.trim() !== "") {
              blindSpots.push(
                unsupportedUnitBlindSpot(
                  "styling-consistency",
                  ctx.target,
                  ctx.viewportName,
                  selectorResult.selector,
                  prop,
                  value,
                  artifacts.evidenceRefs
                )
              );
            }
            continue;
          }
          if (!allowedValue) {
            issues.push(
              makeIssue({
                passName: "styling-consistency",
                target: ctx.target,
                viewportName: ctx.viewportName,
                severity: group.severity,
                selector: selectorResult.selector,
                evidenceRefs: artifacts.evidenceRefs,
                observed: `${prop} resolved to ${value}.`,
                expected: group.expected,
                category: group.category,
                ruleCode: `${group.category}:${prop}`,
                reproSteps: [
                  `Open ${ctx.target.route} at ${ctx.viewportName}.`,
                  `Inspect ${selectorResult.selector} computed ${prop}.`
                ]
              })
            );
          }
        }
      }
    }
  }

  const status =
    issues.length > 0
      ? PASS_STATUS.FAIL
      : blindSpots.length > 0
        ? PASS_STATUS.TOOLING_GAP
        : PASS_STATUS.PASS;

  const summary =
    issues.length > 0
      ? `Found ${issues.length} out-of-scale computed style values.`
      : blindSpots.length > 0
        ? `Computed style evaluation encountered ${blindSpots.length} unsupported value(s).`
        : "Computed style values stayed within the configured design-system scales.";

  return makePassResult({
    passName: "styling-consistency",
    status,
    summary,
    statusReason:
      blindSpots.length > 0 && issues.length === 0
        ? "Computed style evaluation encountered unsupported value units."
        : undefined,
    issues,
    blindSpots,
    evidenceRefs: artifacts.evidenceRefs
  });
}

function gradeInteraction({ ctx, probes, artifacts }) {
  const issues = [];
  if ((probes.focusWalk?.count || 0) === 0) {
    return makePassResult({
      passName: "interaction",
      status: PASS_STATUS.NOT_APPLICABLE,
      summary: "No focusable controls were found for keyboard traversal.",
      blindSpots: [
        makeBlindSpot({
          passName: "interaction",
          target: ctx.target,
          viewportName: ctx.viewportName,
          reasonCode: "no-focusable-controls",
          message: "hover, active, disabled, and loading states were not reachable because the page exposed no focusable controls.",
          evidenceRefs: artifacts.evidenceRefs
        })
      ],
      evidenceRefs: artifacts.evidenceRefs
    });
  }

  for (const missing of probes.focusWalk?.steps?.filter((entry) => !entry.hasVisibleFocusIndicator) || []) {
    issues.push(
      makeIssue({
        passName: "interaction",
        target: ctx.target,
        viewportName: ctx.viewportName,
        severity: "high",
        selector: missing.parityId || missing.dataRole || missing.id || missing.tag,
        evidenceRefs: artifacts.evidenceRefs,
        observed: "Focused element did not expose a visible focus indicator.",
        expected: "Keyboard-focusable controls must expose a visible focus indicator.",
        category: "focus-regression",
        ruleCode: "focus-indicator-visible",
        reproSteps: [
          `Open ${ctx.target.route} at ${ctx.viewportName}.`,
          "Move keyboard focus onto the affected control."
        ]
      })
    );
  }

  const blindSpots = [
    makeBlindSpot({
      passName: "interaction",
      target: ctx.target,
      viewportName: ctx.viewportName,
      reasonCode: "state-sampling-limited",
      message: "hover, active, disabled, and loading states still require targeted route actions when not present by default.",
      evidenceRefs: artifacts.evidenceRefs
    })
  ];

  if (!probes.interactionTrace?.performanceObserverSupported) {
    blindSpots.push(
      makeBlindSpot({
        passName: "interaction",
        target: ctx.target,
        viewportName: ctx.viewportName,
        reasonCode: "performance-observer-unavailable",
        message: "performance observers were unavailable for interaction tracing.",
        evidenceRefs: artifacts.evidenceRefs
      })
    );
  }

  return makePassResult({
    passName: "interaction",
    status: issues.length > 0 ? PASS_STATUS.FAIL : PASS_STATUS.PASS,
    summary:
      issues.length > 0
        ? `Found ${issues.length} focus-indicator regression(s) during keyboard traversal.`
        : "Keyboard traversal completed with visible focus indicators on sampled controls.",
    issues,
    blindSpots,
    evidenceRefs: artifacts.evidenceRefs
  });
}

function gradeLayout({ ctx, probes, artifacts }) {
  const issues = [];
  if ((probes.layoutAudit?.documentHorizontalOverflowPx || 0) > 1) {
    issues.push(
      makeIssue({
        passName: "layout-regression",
        target: ctx.target,
        viewportName: ctx.viewportName,
        severity: "high",
        selector: ctx.target.selectors[0] || ctx.target.route,
        evidenceRefs: artifacts.evidenceRefs,
        observed: `Document overflowed horizontally by ${probes.layoutAudit.documentHorizontalOverflowPx}px.`,
        expected: "The route should not introduce horizontal viewport overflow.",
        category: "layout-overflow",
        ruleCode: "document-horizontal-overflow",
        reproSteps: [
          `Open ${ctx.target.route} at ${ctx.viewportName}.`,
          "Observe the page width and horizontal scrolling behavior."
        ]
      })
    );
  }

  for (const entry of probes.layoutAudit?.overflowIssues || []) {
    const isOverflow = entry.issues.includes("out-of-viewport");
    issues.push(
      makeIssue({
        passName: "layout-regression",
        target: ctx.target,
        viewportName: ctx.viewportName,
        severity: isOverflow ? "high" : "medium",
        selector: entry.selector,
        evidenceRefs: artifacts.evidenceRefs,
        observed: `Layout issue(s): ${entry.issues.join(", ")}.`,
        expected: "Reviewed surfaces should not clip or overflow the viewport unexpectedly.",
        category: isOverflow ? "layout-overflow" : "z-index-overlap",
        ruleCode: isOverflow ? "selector-out-of-viewport" : "selector-overlap",
        reproSteps: [
          `Open ${ctx.target.route} at ${ctx.viewportName}.`,
          `Inspect ${entry.selector}.`
        ]
      })
    );
  }

  return makePassResult({
    passName: "layout-regression",
    status: issues.length > 0 ? PASS_STATUS.FAIL : PASS_STATUS.PASS,
    summary:
      issues.length > 0
        ? `Found ${issues.length} overflow or viewport clipping issue(s).`
        : "No horizontal overflow or selector-level clipping was detected.",
    issues,
    evidenceRefs: artifacts.evidenceRefs
  });
}

function gradeJankPerf({ ctx, probes, artifacts, policy }) {
  if (!probes.interactionTrace?.performanceObserverSupported) {
    return makePassResult({
      passName: "jank-perf",
      status: PASS_STATUS.ENVIRONMENT_LIMITATION,
      summary: "Performance observers were unavailable in this browser session.",
      statusReason: "Performance observers were unavailable in this browser session.",
      blindSpots: [
        makeBlindSpot({
          passName: "jank-perf",
          target: ctx.target,
          viewportName: ctx.viewportName,
          reasonCode: "performance-observer-unavailable",
          message: "layout-shift and long-task metrics were unavailable.",
          evidenceRefs: artifacts.evidenceRefs
        })
      ],
      evidenceRefs: artifacts.evidenceRefs
    });
  }

  const thresholds = policy.interactionTrace || {};
  const issues = [];

  if ((probes.interactionTrace.layoutShiftValue || 0) >= (thresholds.layoutShiftFailThreshold || 0.1)) {
    issues.push(
      makeIssue({
        passName: "jank-perf",
        target: ctx.target,
        viewportName: ctx.viewportName,
        severity: "high",
        selector: ctx.target.selectors[0] || ctx.target.route,
        evidenceRefs: artifacts.evidenceRefs,
        observed: `Layout shift accumulated ${probes.interactionTrace.layoutShiftValue.toFixed(3)} during the sampled interaction trace.`,
        expected: "Repeated interaction traces should not introduce visible layout shifts.",
        category: "flicker-jank",
        ruleCode: "layout-shift-threshold",
        reproSteps: [
          `Open ${ctx.target.route} at ${ctx.viewportName}.`,
          "Repeat focus and scroll interactions several times."
        ]
      })
    );
  }

  if ((probes.interactionTrace.maxLongTaskMs || 0) >= (thresholds.longTaskFailThresholdMs || 120)) {
    issues.push(
      makeIssue({
        passName: "jank-perf",
        target: ctx.target,
        viewportName: ctx.viewportName,
        severity: "medium",
        selector: ctx.target.selectors[0] || ctx.target.route,
        evidenceRefs: artifacts.evidenceRefs,
        observed: `Interaction trace recorded a long task of ${probes.interactionTrace.maxLongTaskMs.toFixed(1)}ms.`,
        expected: "Repeated interaction traces should avoid long blocking tasks.",
        category: "flicker-jank",
        ruleCode: "long-task-threshold",
        reproSteps: [
          `Open ${ctx.target.route} at ${ctx.viewportName}.`,
          "Repeat focus and scroll interactions several times."
        ]
      })
    );
  }

  return makePassResult({
    passName: "jank-perf",
    status: issues.length > 0 ? PASS_STATUS.FAIL : PASS_STATUS.PASS,
    summary:
      issues.length > 0
        ? `Interaction tracing detected ${issues.length} jank or long-task issue(s).`
        : "Interaction tracing did not detect excessive layout shifts or long tasks.",
    issues,
    evidenceRefs: artifacts.evidenceRefs
  });
}

export const PASS_REGISTRY = Object.freeze([
  {
    name: "visual-evidence-captured",
    order: 10,
    requires: [],
    grade: gradeVisualEvidenceCaptured
  },
  {
    name: "native-control",
    order: 20,
    requires: ["nativeControls"],
    grade: gradeNativeControls
  },
  {
    name: "styling-consistency",
    order: 30,
    requires: ["computedStyles"],
    grade: gradeStyleConsistency
  },
  {
    name: "interaction",
    order: 40,
    requires: ["focusWalk", "interactionTrace"],
    grade: gradeInteraction
  },
  {
    name: "layout-regression",
    order: 50,
    requires: ["layoutAudit"],
    grade: gradeLayout
  },
  {
    name: "jank-perf",
    order: 60,
    requires: ["interactionTrace"],
    grade: gradeJankPerf
  }
]);

export const DESIGN_REVIEW_PASS_NAMES = Object.freeze(
  PASS_REGISTRY.map((entry) => entry.name)
);

export function resolvePassRegistry(designConfig) {
  const configured = Array.isArray(designConfig?.passes) ? designConfig.passes : [];
  if (JSON.stringify(configured) !== JSON.stringify(DESIGN_REVIEW_PASS_NAMES)) {
    throw new Error(
      `Design review pass config drifted from the registry. Expected ${JSON.stringify(DESIGN_REVIEW_PASS_NAMES)}, received ${JSON.stringify(configured)}.`
    );
  }
  return PASS_REGISTRY;
}

export const configuredPassRegistry = resolvePassRegistry;

export function requiredProbeNames(passRegistry = PASS_REGISTRY) {
  return [...new Set(passRegistry.flatMap((entry) => entry.requires || []))];
}

export function evaluatePass({
  definition,
  ctx,
  probes,
  artifacts,
  policy
}) {
  for (const probeName of definition.requires || []) {
    const probeResult = probes[probeName];
    if (!probeResult?.ok) {
      return makeUnavailablePassResult({
        passName: definition.name,
        target: ctx.target,
        viewportName: ctx.viewportName,
        reason: probeResult?.message || `${probeName} probe failed.`,
        evidenceRefs: artifacts.evidenceRefs
      });
    }
  }

  const probeValues = Object.fromEntries(
    Object.entries(probes).map(([name, result]) => [name, result?.value ?? null])
  );

  return definition.grade({
    ctx,
    probes: probeValues,
    artifacts,
    policy
  });
}
