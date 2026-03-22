export function renderSummaryMarkdown(summary) {
  const passLines = summary.passes
    .map((entry) => {
      const suffix = entry.blockedReason ? ` - ${entry.blockedReason}` : "";
      return `- ${entry.pass}: ${entry.status} (${entry.issueCount} issue(s))${suffix}`;
    })
    .join("\n");

  const issueLines = summary.issues.length
    ? summary.issues
        .map(
          (issue) =>
            `- [${issue.severity}] ${issue.pass} / ${issue.route} / ${issue.viewport}: ${issue.observedBehavior}`
        )
        .join("\n")
    : "- None.";

  const blindSpotLines = summary.residualBlindSpots.length
    ? summary.residualBlindSpots.map((entry) => `- ${entry}`).join("\n")
    : "- None.";

  const viewportLines = summary.inspectedViewports
    .map((entry) => `\`${entry.name} ${entry.width}x${entry.height}\``)
    .join(", ");

  return `# Design Review - ${summary.runId}

## Summary

- Run status: \`${summary.runStatus}\`
- Review outcome: \`${summary.reviewOutcome}\`
- Targets: ${summary.targets.map((target) => `\`${target.id}\``).join(", ")}
- Viewports: ${viewportLines}
- Started: \`${summary.startedAt}\`
- Ended: \`${summary.endedAt}\`

## Passes

${passLines}

## Issues

${issueLines}

## Residual Blind Spots

${blindSpotLines}
`;
}
