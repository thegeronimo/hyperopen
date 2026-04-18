import crypto from "node:crypto";
import { execFileSync } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  RELEASE_APP_SHELL_PLACEHOLDER,
  RELEASE_ROUTE_METADATA_SCRIPT_PATH,
  ROBOTS_FILE_PATH,
  SITE_METADATA_FILE_PATH,
  SITEMAP_FILE_PATH,
  RELEASE_SEO_PLACEHOLDER,
  buildRouteLoadingShellMarkup,
  buildReleaseMetadataSyncScript,
  buildReleaseSeoHeadMarkup,
  buildRobotsTxt,
  buildSiteMetadata,
  buildSitemapXml,
  normalizeCanonicalOrigin,
  normalizePublicPath,
  publicPathToRelativePath
} from "./site_metadata.mjs";
import {
  SECURITY_HEADERS_FILE_PATH,
  buildReleaseHeadersFile,
} from "./security_headers.mjs";

export const DEFAULT_SOURCE_ROOT = path.resolve("resources/public");
export const DEFAULT_OUTPUT_ROOT = path.resolve("out/release-public");
export const APP_INDEX_PATH = "index.html";
export const APP_CSS_PATH = path.join("css", "main.css");
export const APP_CSS_HREF = "/css/main.css";
const JS_DIR = "js";
const FONTS_DIR = "fonts";
const REQUIRED_JS_METADATA_FILES = ["module-loader.json"];
const REQUIRED_WORKER_FILES = ["portfolio_worker.js", "vault_detail_worker.js"];

export function hashContent(content) {
  return crypto
    .createHash("sha256")
    .update(content)
    .digest("hex")
    .slice(0, 16)
    .toUpperCase();
}

export function fingerprintFileName(fileName, fingerprint) {
  const extension = path.extname(fileName);
  const baseName = path.basename(fileName, extension);
  return `${baseName}.${fingerprint}${extension}`;
}

export function rewriteAppIndexHtml(indexHtml, {
  cssHref,
  releaseMetadataScriptHref,
  mainScriptHref,
  title,
  description,
  releaseSeoHeadMarkup,
  releaseAppShellMarkup
}) {
  const stylesheetPattern =
    /<link\b[^>]*href=["']\/css\/main\.css["'][^>]*>/;
  const descriptionPattern =
    /<meta\b(?=[^>]*\bname=["']description["'])[^>]*>/i;
  const titlePattern = /<title>[\s\S]*?<\/title>/i;
  const bootstrapScriptPattern =
    /<script>\s*\(function \(\) \{(?:(?!<\/script>)[\s\S])*?const manifestUrl = ["']\/js\/manifest\.json["'];(?:(?!<\/script>)[\s\S])*?const defaultMainScriptUrl = ["']\/js\/main\.js["'];(?:(?!<\/script>)[\s\S])*?fetch\(manifestUrl, \{ cache: "no-cache" \}\)(?:(?!<\/script>)[\s\S])*?<\/script>/;

  if (!stylesheetPattern.test(indexHtml)) {
    throw new Error("Expected app index.html to contain the default stylesheet link.");
  }

  if (!bootstrapScriptPattern.test(indexHtml)) {
    throw new Error("Expected app index.html to contain the dynamic main script bootstrap.");
  }

  if (!descriptionPattern.test(indexHtml)) {
    throw new Error("Expected app index.html to contain a description meta tag.");
  }

  if (!titlePattern.test(indexHtml)) {
    throw new Error("Expected app index.html to contain a <title> element.");
  }

  if (!indexHtml.includes(RELEASE_SEO_PLACEHOLDER)) {
    throw new Error(
      `Expected app index.html to contain the release SEO placeholder: ${RELEASE_SEO_PLACEHOLDER}`
    );
  }

  if (!indexHtml.includes(RELEASE_APP_SHELL_PLACEHOLDER)) {
    throw new Error(
      `Expected app index.html to contain the release app shell placeholder: ${RELEASE_APP_SHELL_PLACEHOLDER}`
    );
  }

  return indexHtml
    .replace(
      descriptionPattern,
      `<meta name="description" content="${String(description)
        .replace(/&/g, "&amp;")
        .replace(/"/g, "&quot;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")}" />`
    )
    .replace(
      titlePattern,
      `<title>${String(title)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")}</title>`
    )
    .replace(
      stylesheetPattern,
      `<link rel="stylesheet" href="${cssHref}" />`
    )
    .replace(RELEASE_SEO_PLACEHOLDER, releaseSeoHeadMarkup)
    .replace(RELEASE_APP_SHELL_PLACEHOLDER, releaseAppShellMarkup)
    .replace(
      bootstrapScriptPattern,
      [
        `<script defer src="${releaseMetadataScriptHref}"></script>`,
        `<script defer src="${mainScriptHref}"></script>`,
      ].join("\n")
    );
}

export function routePathToOutputHtmlPath(routePath) {
  const normalizedPath = normalizePublicPath(routePath);
  if (normalizedPath === "/") {
    return APP_INDEX_PATH;
  }

  return path.join(publicPathToRelativePath(normalizedPath), APP_INDEX_PATH);
}

async function prepareOutputRoot(outputRoot) {
  await fs.rm(outputRoot, { recursive: true, force: true });
  await fs.mkdir(outputRoot, { recursive: true });
}

async function pathExists(targetPath) {
  try {
    await fs.access(targetPath);
    return true;
  } catch (_error) {
    return false;
  }
}

async function copyFileIntoRoot(sourceRoot, outputRoot, relativePath) {
  const sourcePath = path.join(sourceRoot, relativePath);
  if (!(await pathExists(sourcePath))) {
    return false;
  }

  const destinationPath = path.join(outputRoot, relativePath);
  await fs.mkdir(path.dirname(destinationPath), { recursive: true });
  await fs.copyFile(sourcePath, destinationPath);
  return true;
}

export function normalizeModuleUriToRelativeJsPath(uri) {
  if (typeof uri !== "string" || uri.length === 0) {
    return null;
  }

  const [pathname] = uri.split(/[?#]/, 1);
  if (typeof pathname !== "string" || pathname.length === 0) {
    return null;
  }

  const normalized = pathname.replace(/^\.?\//, "");
  if (!normalized.startsWith(`${JS_DIR}/`)) {
    return null;
  }

  return normalized.slice(`${JS_DIR}/`.length);
}

export function collectReleaseJavaScriptFiles({ manifest, moduleLoader }) {
  const jsFiles = new Set([...REQUIRED_JS_METADATA_FILES, ...REQUIRED_WORKER_FILES]);

  for (const moduleInfo of manifest) {
    if (moduleInfo && typeof moduleInfo["output-name"] === "string") {
      jsFiles.add(moduleInfo["output-name"]);
    }
  }

  const moduleUris = moduleLoader?.["module-uris"] ?? {};
  for (const uris of Object.values(moduleUris)) {
    if (!Array.isArray(uris)) {
      continue;
    }

    for (const uri of uris) {
      const relativeJsPath = normalizeModuleUriToRelativeJsPath(uri);
      if (relativeJsPath) {
        jsFiles.add(relativeJsPath);
      }
    }
  }

  return [...jsFiles].sort();
}

function isFingerprintedReleaseJavaScriptFile(fileName) {
  if (typeof fileName !== "string" || !fileName.endsWith(".js")) {
    return false;
  }

  return path.posix.basename(fileName).split(".").length >= 3;
}

const SHADOW_LOADER_EVAL_RUNTIME_PATTERN =
  /var\s+([A-Za-z_$][A-Za-z0-9_$]*)=new\s+([A-Za-z_$][A-Za-z0-9_$]*);\1\.pk=!0;/;
const SHADOW_LOADER_SCRIPT_TAG_RUNTIME_PATTERN =
  /var\s+([A-Za-z_$][A-Za-z0-9_$]*)=new\s+([A-Za-z_$][A-Za-z0-9_$]*);\1\.tk=!0;/;

export function rewriteMainModuleLoaderRuntime(mainModuleSource) {
  if (typeof mainModuleSource !== "string") {
    throw new Error("Expected the main module source to be a string.");
  }

  if (SHADOW_LOADER_SCRIPT_TAG_RUNTIME_PATTERN.test(mainModuleSource)) {
    return mainModuleSource;
  }

  const rewrittenSource = mainModuleSource.replace(
    SHADOW_LOADER_EVAL_RUNTIME_PATTERN,
    (_match, loaderVar, loaderCtor) => `var ${loaderVar}=new ${loaderCtor};${loaderVar}.tk=!0;`
  );

  if (rewrittenSource === mainModuleSource) {
    throw new Error(
      "Expected the main module to contain the shadow-cljs eval-based loader runtime."
    );
  }

  return rewrittenSource;
}

function nonEmptyEnv(name) {
  const value = process.env[name];
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : null;
}

function firstNonEmpty(...values) {
  for (const value of values) {
    if (typeof value === "string" && value.trim().length > 0) {
      return value.trim();
    }
  }

  return null;
}

function readGit(args) {
  try {
    return execFileSync("git", args, {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
  } catch (_error) {
    return null;
  }
}

function normalizeDeployedAt(value) {
  const rawValue = typeof value === "string" && value.trim().length > 0 ? value.trim() : null;
  const date = rawValue ? new Date(rawValue) : new Date();
  return Number.isFinite(date.getTime()) ? date.toISOString() : new Date().toISOString();
}

function normalizeBuildEnv(value, branch) {
  const explicit = typeof value === "string" ? value.trim().toLowerCase() : "";
  if (["prod", "staging", "dev"].includes(explicit)) {
    return explicit;
  }

  if (nonEmptyEnv("CF_PAGES")) {
    return branch === "main" || branch === "master" ? "prod" : "staging";
  }

  return "dev";
}

export function resolveReleaseBuildInfo() {
  const sha = firstNonEmpty(
    nonEmptyEnv("HYPEROPEN_BUILD_ID"),
    nonEmptyEnv("HYPEROPEN_GIT_SHA"),
    nonEmptyEnv("VITE_GIT_SHA"),
    nonEmptyEnv("GIT_COMMIT_SHA"),
    nonEmptyEnv("GITHUB_SHA"),
    nonEmptyEnv("CF_PAGES_COMMIT_SHA"),
    readGit(["rev-parse", "HEAD"])
  );

  if (!sha) {
    return null;
  }

  const branch = firstNonEmpty(
    nonEmptyEnv("HYPEROPEN_BUILD_BRANCH"),
    nonEmptyEnv("GIT_BRANCH"),
    nonEmptyEnv("GITHUB_REF_NAME"),
    nonEmptyEnv("CF_PAGES_BRANCH"),
    readGit(["rev-parse", "--abbrev-ref", "HEAD"]),
    "unknown"
  );
  const message = firstNonEmpty(
    nonEmptyEnv("HYPEROPEN_BUILD_MESSAGE"),
    nonEmptyEnv("GIT_COMMIT_MESSAGE"),
    readGit(["log", "-1", "--pretty=%s"]),
    "Build metadata unavailable"
  );
  const env = normalizeBuildEnv(nonEmptyEnv("HYPEROPEN_BUILD_ENV"), branch);

  return {
    sha,
    short: sha.slice(0, 7),
    branch,
    message,
    deployedAt: normalizeDeployedAt(
      firstNonEmpty(nonEmptyEnv("HYPEROPEN_DEPLOYED_AT"), nonEmptyEnv("HYPEROPEN_BUILD_DEPLOYED_AT"))
    ),
    env,
    region: firstNonEmpty(nonEmptyEnv("HYPEROPEN_BUILD_REGION"), "global"),
  };
}

export function resolveReleaseBuildId() {
  const envBuildId =
    typeof process.env.HYPEROPEN_BUILD_ID === "string"
      ? process.env.HYPEROPEN_BUILD_ID.trim()
      : "";
  if (envBuildId) {
    return envBuildId;
  }

  return firstNonEmpty(nonEmptyEnv("CF_PAGES_COMMIT_SHA"), readGit(["rev-parse", "HEAD"]));
}

export async function generateReleaseArtifacts({
  sourceRoot = DEFAULT_SOURCE_ROOT,
  outputRoot = DEFAULT_OUTPUT_ROOT,
  canonicalOrigin = process.env.HYPEROPEN_SITE_ORIGIN,
} = {}) {
  const manifestPath = path.join(sourceRoot, JS_DIR, "manifest.json");
  const moduleLoaderPath = path.join(sourceRoot, JS_DIR, "module-loader.json");
  const manifest = JSON.parse(await fs.readFile(manifestPath, "utf8"));
  const moduleLoader = JSON.parse(await fs.readFile(moduleLoaderPath, "utf8"));
  const mainModule = manifest.find((moduleInfo) => moduleInfo?.["module-id"] === "main");

  if (!mainModule || typeof mainModule["output-name"] !== "string") {
    throw new Error("Expected manifest.json to contain a main module output.");
  }

  await prepareOutputRoot(outputRoot);

  const sourceCssPath = path.join(sourceRoot, APP_CSS_PATH);
  const cssContent = await fs.readFile(sourceCssPath);
  const cssFingerprint = hashContent(cssContent);
  const cssFileName = fingerprintFileName(path.basename(APP_CSS_PATH), cssFingerprint);
  const outputFingerprintPath = path.join(outputRoot, "css", cssFileName);

  await fs.mkdir(path.dirname(outputFingerprintPath), { recursive: true });
  await fs.writeFile(outputFingerprintPath, cssContent);

  const sourceIndexHtml = await fs.readFile(path.join(sourceRoot, APP_INDEX_PATH), "utf8");
  const siteMetadata = buildSiteMetadata({
    canonicalOrigin: normalizeCanonicalOrigin(canonicalOrigin),
    indexHtml: sourceIndexHtml,
    buildId: resolveReleaseBuildId(),
    buildInfo: resolveReleaseBuildInfo(),
  });
  const releaseMetadataScriptSource = buildReleaseMetadataSyncScript(siteMetadata);
  const releaseMetadataScriptOutputPath = path.join(outputRoot, RELEASE_ROUTE_METADATA_SCRIPT_PATH);
  const releaseMetadataScriptHref = `/${RELEASE_ROUTE_METADATA_SCRIPT_PATH}`;

  await fs.mkdir(path.dirname(releaseMetadataScriptOutputPath), { recursive: true });
  await fs.writeFile(releaseMetadataScriptOutputPath, releaseMetadataScriptSource);

  const generatedRouteHtmlFiles = [];
  for (const route of siteMetadata.routes) {
    const rewrittenRouteHtml = rewriteAppIndexHtml(sourceIndexHtml, {
      cssHref: `/css/${cssFileName}`,
      releaseMetadataScriptHref,
      mainScriptHref: `/js/${mainModule["output-name"]}`,
      title: route.title,
      description: route.description,
      releaseSeoHeadMarkup: buildReleaseSeoHeadMarkup(siteMetadata, route),
      releaseAppShellMarkup: buildRouteLoadingShellMarkup(route),
    });
    const routeOutputRelativePath = routePathToOutputHtmlPath(route.path);
    const routeOutputPath = path.join(outputRoot, routeOutputRelativePath);
    await fs.mkdir(path.dirname(routeOutputPath), { recursive: true });
    await fs.writeFile(routeOutputPath, rewrittenRouteHtml);
    generatedRouteHtmlFiles.push(routeOutputRelativePath);
  }

  await fs.writeFile(
    path.join(outputRoot, SITE_METADATA_FILE_PATH),
    `${JSON.stringify(siteMetadata, null, 2)}\n`
  );
  await fs.writeFile(path.join(outputRoot, ROBOTS_FILE_PATH), `${buildRobotsTxt(siteMetadata)}\n`);
  await fs.writeFile(path.join(outputRoot, SITEMAP_FILE_PATH), `${buildSitemapXml(siteMetadata)}\n`);

  const fontsSourcePath = path.join(sourceRoot, FONTS_DIR);
  if (await pathExists(fontsSourcePath)) {
    await fs.cp(fontsSourcePath, path.join(outputRoot, FONTS_DIR), { recursive: true });
  }

  const copiedRootAssetPaths = [];
  for (const publicPath of siteMetadata.rootAssetPaths) {
    const relativePath = publicPathToRelativePath(publicPath);
    const copied = await copyFileIntoRoot(sourceRoot, outputRoot, relativePath);
    if (!copied) {
      throw new Error(`Expected release root asset to exist: ${relativePath}`);
    }
    copiedRootAssetPaths.push(relativePath);
  }

  const releaseJavaScriptFiles = collectReleaseJavaScriptFiles({ manifest, moduleLoader });
  for (const fileName of releaseJavaScriptFiles) {
    const relativePath = path.join(JS_DIR, fileName);
    const sourcePath = path.join(sourceRoot, relativePath);
    if (!(await pathExists(sourcePath))) {
      throw new Error(`Expected release asset to exist: ${relativePath}`);
    }

    const destinationPath = path.join(outputRoot, relativePath);
    await fs.mkdir(path.dirname(destinationPath), { recursive: true });

    if (fileName === mainModule["output-name"]) {
      const rewrittenMainModule = rewriteMainModuleLoaderRuntime(
        await fs.readFile(sourcePath, "utf8")
      );
      await fs.writeFile(destinationPath, rewrittenMainModule);
      continue;
    }

    await fs.copyFile(sourcePath, destinationPath);
  }

  const immutableAssetPaths = [
    `/css/${cssFileName}`,
    ...releaseJavaScriptFiles
      .filter((fileName) => isFingerprintedReleaseJavaScriptFile(fileName))
      .map((fileName) => `/${path.posix.join(JS_DIR, fileName)}`),
  ].sort();

  await fs.writeFile(
    path.join(outputRoot, SECURITY_HEADERS_FILE_PATH),
    buildReleaseHeadersFile({ immutableAssetPaths })
  );

  const outputIndexPath = path.join(outputRoot, APP_INDEX_PATH);

  return {
    outputRoot,
    cssFileName,
    cssHref: `/css/${cssFileName}`,
    immutableAssetPaths,
    mainScriptHref: `/js/${mainModule["output-name"]}`,
    outputIndexPath,
    generatedRouteHtmlFiles: generatedRouteHtmlFiles.sort(),
    copiedRootAssetPaths: copiedRootAssetPaths.sort(),
    releaseMetadataScriptHref,
    securityHeadersPath: path.join(outputRoot, SECURITY_HEADERS_FILE_PATH),
    releaseJavaScriptFiles,
    siteMetadata,
  };
}

async function main() {
  const result = await generateReleaseArtifacts();
  process.stdout.write(
    `Generated release artifacts in ${result.outputRoot} with ${result.cssFileName}\n`
  );
}

const entryScriptPath = process.argv[1]
  ? path.resolve(process.argv[1])
  : null;
const currentFilePath = fileURLToPath(import.meta.url);

if (entryScriptPath && currentFilePath === entryScriptPath) {
  main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
  });
}
