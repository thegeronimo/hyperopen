import fs from "node:fs/promises";
import path from "node:path";
import { ensureDir, writeJsonFile } from "../util.mjs";

function viewportDir(runRef, targetId, viewportName) {
  return path.join(runRef.runDir, targetId, viewportName);
}

export function createDesignReviewArtifactStore() {
  return {
    async ensureViewportDir(runRef, targetId, viewportName) {
      const dir = viewportDir(runRef, targetId, viewportName);
      await ensureDir(dir);
      return dir;
    },

    async writeReviewSpec(runRef, reviewSpec) {
      const reviewSpecPath = path.join(runRef.runDir, "review-spec.json");
      await writeJsonFile(reviewSpecPath, reviewSpec);
      return reviewSpecPath;
    },

    async writeProbe(runRef, { targetId, viewportName, name, value }) {
      const filePath = path.join(viewportDir(runRef, targetId, viewportName), "probes", `${name}.json`);
      await writeJsonFile(filePath, value);
      return filePath;
    },

    async writePassDetails(runRef, { passName, details }) {
      const passesDir = path.join(runRef.runDir, "passes");
      await ensureDir(passesDir);
      const filePath = path.join(passesDir, `${passName}.json`);
      await writeJsonFile(filePath, details);
      return filePath;
    },

    async writeSummary(runRef, summary, renderSummaryMarkdown) {
      const summaryPath = path.join(runRef.runDir, "summary.json");
      const summaryMarkdownPath = path.join(runRef.runDir, "summary.md");
      await writeJsonFile(summaryPath, summary);
      await fs.writeFile(summaryMarkdownPath, renderSummaryMarkdown(summary));
      return {
        summaryPath,
        summaryMarkdownPath
      };
    }
  };
}
