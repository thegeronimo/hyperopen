import fs from "node:fs/promises";
import path from "node:path";

import { resolveReleaseBuildId, resolveReleaseBuildInfo } from "./generate_release_artifacts.mjs";

const DEFAULT_OUTPUT_PATH = path.resolve("resources/public/build-id.txt");
const DEFAULT_BUILD_INFO_OUTPUT_PATH = path.resolve("resources/public/build-info.json");

export async function writeBuildIdFile({
  outputPath = DEFAULT_OUTPUT_PATH,
  buildId = resolveReleaseBuildId(),
} = {}) {
  const normalizedBuildId =
    typeof buildId === "string" && buildId.trim().length > 0 ? buildId.trim() : "";
  await fs.mkdir(path.dirname(outputPath), { recursive: true });
  await fs.writeFile(outputPath, `${normalizedBuildId}\n`, "utf8");
  return { outputPath, buildId: normalizedBuildId };
}

export async function writeBuildInfoFile({
  outputPath = DEFAULT_BUILD_INFO_OUTPUT_PATH,
  buildInfo = resolveReleaseBuildInfo(),
} = {}) {
  await fs.mkdir(path.dirname(outputPath), { recursive: true });
  await fs.writeFile(outputPath, `${JSON.stringify(buildInfo, null, 2)}\n`, "utf8");
  return { outputPath, buildInfo };
}

export async function writeBuildMetadataFiles({
  buildIdOutputPath = DEFAULT_OUTPUT_PATH,
  buildInfoOutputPath = DEFAULT_BUILD_INFO_OUTPUT_PATH,
  buildInfo = resolveReleaseBuildInfo(),
  buildId = buildInfo?.sha || resolveReleaseBuildId(),
} = {}) {
  const buildIdResult = await writeBuildIdFile({
    outputPath: buildIdOutputPath,
    buildId,
  });
  const buildInfoResult = await writeBuildInfoFile({
    outputPath: buildInfoOutputPath,
    buildInfo,
  });
  return {
    buildId: buildIdResult.buildId,
    buildIdOutputPath: buildIdResult.outputPath,
    buildInfo: buildInfoResult.buildInfo,
    buildInfoOutputPath: buildInfoResult.outputPath,
  };
}

const invokedFromCli =
  process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname);

if (invokedFromCli) {
  await writeBuildMetadataFiles();
}
