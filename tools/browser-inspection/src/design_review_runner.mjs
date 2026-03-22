import { loadDesignReviewConfig, resolveDesignReviewSelection } from "./design_review_loader.mjs";
import { assertDesignReviewSummary } from "./design_review_contracts.mjs";
import {
  aggregatePassStatus,
  aggregateSummaryState,
  compactEvidenceRefs,
  makeEvidenceRef,
  makeIssue,
  makePassResult,
  makeSummary,
  makeTargetResult,
  makeTargetViewportResult,
  makeViewportSpec,
  PASS_STATUS,
  RUN_STATUS
} from "./design_review/models.mjs";
import {
  DESIGN_REVIEW_PASS_NAMES,
  makeUnavailablePassResult,
  PROBE_ARTIFACT_NAMES,
  requiredProbeNames,
  resolvePassRegistry
} from "./design_review/pass_registry.mjs";
import { createDesignReviewRuntime } from "./design_review/runtime.mjs";

function detailEntryForPass(result, target, viewportName) {
  return {
    ...result,
    targetId: target.id,
    route: target.route,
    viewport: viewportName,
    issueCount: result.issueCount ?? result.issues?.length ?? 0,
    evidencePaths: result.evidencePaths || result.evidenceRefs?.map((entry) => entry.path) || []
  };
}

function passEvidenceRefs(passDefinition, captureEvidenceRefs, probePaths) {
  return compactEvidenceRefs([
    ...captureEvidenceRefs,
    ...(passDefinition.requires || []).map((probeName) =>
      makeEvidenceRef(probeName, probePaths[probeName] || null)
    )
  ]);
}

async function executeProbeSuite({
  runtime,
  runRef,
  sessionId,
  target,
  viewportName,
  designConfig,
  passRegistry
}) {
  const probeResults = {};
  const probePaths = {};

  for (const probeName of requiredProbeNames(passRegistry)) {
    const gateway = runtime.probeGateway[probeName];
    if (typeof gateway !== "function") {
      probeResults[probeName] = {
        ok: false,
        status: PASS_STATUS.TOOLING_GAP,
        reason: `No probe gateway was configured for ${probeName}.`
      };
      continue;
    }

    const result = await gateway(sessionId, {
      target,
      viewportName,
      designConfig
    });
    probeResults[probeName] = result;

    if (result.ok) {
      probePaths[probeName] = await runtime.artifactStore.writeProbe(runRef, {
        targetId: target.id,
        viewportName,
        name: PROBE_ARTIFACT_NAMES[probeName] || probeName,
        value: result.value
      });
    }
  }

  return {
    probeResults,
    probePaths
  };
}

function aggregatePassEntries(passRegistry, passEntries) {
  return passRegistry.map((passDefinition) => {
    const matches = passEntries.filter((entry) => entry.pass === passDefinition.name);
    const status = aggregatePassStatus(matches);
    const issueCount = matches.reduce((sum, entry) => sum + (entry.issueCount || 0), 0);
    const blockedReason =
      status !== PASS_STATUS.FAIL
        ? matches.find((entry) => entry.status === status && entry.blockedReason)?.blockedReason
        : undefined;

    return {
      pass: passDefinition.name,
      status,
      issueCount,
      blockedReason,
      evidencePaths: [...new Set(matches.flatMap((entry) => entry.evidencePaths || []))]
    };
  });
}

async function executeViewportReview({
  runtime,
  runRef,
  passRegistry,
  designConfig,
  sessionId,
  target,
  viewportName,
  viewport
}) {
  await runtime.artifactStore.ensureViewportDir(runRef, target.id, viewportName);

  const capture = await runtime.captureGateway.captureViewport(runRef, sessionId, {
    target,
    viewportName,
    viewport,
    designConfig
  });

  if (!capture.ok) {
    const passEntries = passRegistry.map((passDefinition) =>
      detailEntryForPass(
        capture.status === PASS_STATUS.FAIL
          ? makePassResult({
              passName: passDefinition.name,
              status: PASS_STATUS.FAIL,
              summary: capture.reason,
              statusReason: capture.reason
            })
          : makeUnavailablePassResult({
              passName: passDefinition.name,
              target,
              viewportName,
              status: capture.status,
              reason: capture.reason,
              reasonCode: "capture-tooling-gap"
            }),
        target,
        viewportName
      )
    );

    const issues =
      capture.status === PASS_STATUS.FAIL
        ? [
            makeIssue({
              passName: "visual-evidence-captured",
              target,
              viewportName,
              severity: "high",
              selector: target.selectors[0] || target.route,
              evidenceRefs: [makeEvidenceRef("run", runRef.runDir)],
              artifactPath: runRef.runDir,
              observed: `The route failed to capture: ${capture.reason}`,
              expected: "The reviewed route should render and capture successfully.",
              category: "capture-failure",
              ruleCode: "capture-failure",
              reproSteps: [`Open ${target.route} at ${viewportName}.`]
            })
          ]
        : [];

    return {
      targetViewport: makeTargetViewportResult({
        viewportName,
        viewport,
        passes: passEntries
      }),
      passEntries,
      issues,
      blindSpots: passEntries.flatMap((entry) => entry.blindSpots || [])
    };
  }

  const { probeResults, probePaths } = await executeProbeSuite({
    runtime,
    runRef,
    sessionId,
    target,
    viewportName,
    designConfig,
    passRegistry
  });

  const probeValues = Object.fromEntries(
    Object.entries(probeResults)
      .filter(([, result]) => result.ok)
      .map(([probeName, result]) => [probeName, result.value])
  );

  const passEntries = [];
  const issues = [];
  const blindSpots = [];

  for (const passDefinition of passRegistry) {
    const evidenceRefs = passEvidenceRefs(passDefinition, capture.evidenceRefs, probePaths);
    const missingProbe = (passDefinition.requires || [])
      .map((probeName) => [probeName, probeResults[probeName]])
      .find(([, result]) => !result?.ok);

    const result = missingProbe
      ? makeUnavailablePassResult({
          passName: passDefinition.name,
          target,
          viewportName,
          status: missingProbe[1]?.status || PASS_STATUS.TOOLING_GAP,
          reason: missingProbe[1]?.reason || `${missingProbe[0]} probe failed.`,
          reasonCode: `${missingProbe[0]}-probe-failure`,
          evidenceRefs
        })
      : passDefinition.grade({
          ctx: {
            target,
            viewportName
          },
          probes: probeValues,
          artifacts: {
            snapshotPath: capture.snapshotPath,
            screenshotPath: capture.screenshotPath,
            probePaths,
            evidenceRefs
          },
          policy: designConfig
        });

    const detailEntry = detailEntryForPass(result, target, viewportName);
    passEntries.push(detailEntry);
    issues.push(...(detailEntry.issues || []));
    blindSpots.push(...(detailEntry.blindSpots || []));
  }

  return {
    targetViewport: makeTargetViewportResult({
      viewportName,
      viewport,
      snapshotPath: capture.snapshotPath,
      screenshotPath: capture.screenshotPath,
      probePaths,
      passes: passEntries
    }),
    passEntries,
    issues,
    blindSpots
  };
}

export async function runDesignReview(service, options = {}) {
  const designConfig = await loadDesignReviewConfig(options.designConfigPath);
  const passRegistry = resolvePassRegistry(designConfig);
  const selection = await resolveDesignReviewSelection({
    changedFiles: options.changedFiles || [],
    routingPath: options.routingPath,
    targetIds: options.targetIds || []
  });
  const viewports = Object.entries(designConfig.viewports).filter(
    ([name]) => !options.viewports || options.viewports.length === 0 || options.viewports.includes(name)
  );

  if (viewports.length === 0) {
    throw new Error("No design-review viewports were selected.");
  }

  if (options.dryRun) {
    return {
      dryRun: true,
      passes: DESIGN_REVIEW_PASS_NAMES,
      viewports: viewports.map(([name, viewport]) => makeViewportSpec(name, viewport)),
      selection
    };
  }

  const runtime = options.runtime || createDesignReviewRuntime(service, options.runtimeOverrides || {});
  const startedAt = runtime.now();
  const runRef = await runtime.runRepository.createRun("design-review", {
    requestedAt: startedAt,
    changedFiles: selection.changedFiles,
    targetIds: selection.targets.map((target) => target.id)
  });

  let sessionId = options.sessionId;
  let tempSession = null;

  try {
    if (!sessionId) {
      tempSession = await runtime.sessionGateway.startSession({
        headless: options.headless,
        manageLocalApp: options.manageLocalApp ?? true,
        localAppUrl: options.localUrl,
        attachPort: options.attachPort || null,
        attachHost: options.attachHost || null,
        targetId: options.targetId || null,
        readOnly: true
      });
      sessionId = tempSession.id;
    }

    const inspectedViewports = viewports.map(([name, viewport]) => makeViewportSpec(name, viewport));
    const reviewSpec = {
      createdAt: runtime.now(),
      changedFiles: selection.changedFiles,
      matchedRuleIds: selection.matchedRuleIds,
      passes: passRegistry.map((entry) => entry.name),
      targets: selection.targets,
      viewports: inspectedViewports
    };
    const reviewSpecPath = await runtime.artifactStore.writeReviewSpec(runRef, reviewSpec);

    const passEntries = [];
    const issues = [];
    const blindSpots = [];
    const targetResults = [];

    for (const target of selection.targets) {
      const targetResult = makeTargetResult(target);

      for (const [viewportName, viewport] of viewports) {
        const viewportReview = await executeViewportReview({
          runtime,
          runRef,
          passRegistry,
          designConfig,
          sessionId,
          target,
          viewportName,
          viewport
        });

        targetResult.viewports.push(viewportReview.targetViewport);
        passEntries.push(...viewportReview.passEntries);
        issues.push(...viewportReview.issues);
        blindSpots.push(...viewportReview.blindSpots);
      }

      targetResults.push(targetResult);
    }

    const aggregatedPasses = aggregatePassEntries(passRegistry, passEntries);
    const summary = makeSummary({
      runRef,
      startedAt,
      endedAt: runtime.now(),
      reviewSpecPath,
      inspectedViewports,
      targets: targetResults.map((entry) => ({
        id: entry.id,
        route: entry.route
      })),
      targetResults,
      passes: aggregatedPasses,
      issues,
      blindSpots
    });

    assertDesignReviewSummary(summary);

    for (const passEntry of aggregatedPasses) {
      const details = passEntries.filter((entry) => entry.pass === passEntry.pass);
      await runtime.artifactStore.writePassDetails(runRef, {
        passName: passEntry.pass,
        details: {
          ...passEntry,
          details
        }
      });
    }

    const { summaryPath, summaryMarkdownPath } = await runtime.artifactStore.writeSummary(
      runRef,
      summary,
      runtime.summaryRenderer
    );

    await runtime.runRepository.finalizeRun(runRef, {
      runStatus: RUN_STATUS.COMPLETED,
      reviewOutcome: summary.reviewOutcome,
      state: summary.state,
      summaryPath,
      summaryMarkdownPath
    });

    return summary;
  } catch (error) {
    await runtime.runRepository
      .finalizeRun(runRef, {
        runStatus: RUN_STATUS.FAILED,
        errorMessage: error?.message || String(error)
      })
      .catch(() => null);
    throw error;
  } finally {
    if (tempSession) {
      await runtime.sessionGateway.stopSession(tempSession.id).catch(() => null);
    }
  }
}

export { aggregateSummaryState, DESIGN_REVIEW_PASS_NAMES };
