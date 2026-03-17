import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const DEFAULT_SOURCE_ROOT = path.resolve("resources/public");
export const DEFAULT_OUTPUT_ROOT = path.resolve("out/release-public");
export const APP_INDEX_PATH = "index.html";
export const APP_CSS_PATH = path.join("css", "main.css");
export const APP_CSS_HREF = "/css/main.css";
const JS_DIR = "js";
const FONTS_DIR = "fonts";
const OPTIONAL_ROOT_FILES = ["sw.js", "favicon.ico"];
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

export function rewriteAppIndexHtml(indexHtml, { cssHref, mainScriptHref }) {
  const stylesheetPattern =
    /<link\b[^>]*href=["']\/css\/main\.css["'][^>]*>/;
  const bootstrapScriptPattern =
    /<script>\s*\(function \(\) \{[\s\S]*?fetch\(manifestUrl, \{ cache: "no-cache" \}\)[\s\S]*?<\/script>/;

  if (!stylesheetPattern.test(indexHtml)) {
    throw new Error("Expected app index.html to contain the default stylesheet link.");
  }

  if (!bootstrapScriptPattern.test(indexHtml)) {
    throw new Error("Expected app index.html to contain the dynamic main script bootstrap.");
  }

  return indexHtml
    .replace(
      stylesheetPattern,
      `<link rel="stylesheet" href="${cssHref}" />`
    )
    .replace(
      bootstrapScriptPattern,
      `<script defer src="${mainScriptHref}"></script>`
    );
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

export async function generateReleaseArtifacts({
  sourceRoot = DEFAULT_SOURCE_ROOT,
  outputRoot = DEFAULT_OUTPUT_ROOT,
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
  const rewrittenIndexHtml = rewriteAppIndexHtml(sourceIndexHtml, {
    cssHref: `/css/${cssFileName}`,
    mainScriptHref: `/js/${mainModule["output-name"]}`,
  });
  await fs.writeFile(path.join(outputRoot, APP_INDEX_PATH), rewrittenIndexHtml);

  const fontsSourcePath = path.join(sourceRoot, FONTS_DIR);
  if (await pathExists(fontsSourcePath)) {
    await fs.cp(fontsSourcePath, path.join(outputRoot, FONTS_DIR), { recursive: true });
  }

  for (const relativePath of OPTIONAL_ROOT_FILES) {
    await copyFileIntoRoot(sourceRoot, outputRoot, relativePath);
  }

  const releaseJavaScriptFiles = collectReleaseJavaScriptFiles({ manifest, moduleLoader });
  for (const fileName of releaseJavaScriptFiles) {
    const relativePath = path.join(JS_DIR, fileName);
    const copied = await copyFileIntoRoot(sourceRoot, outputRoot, relativePath);
    if (!copied) {
      throw new Error(`Expected release asset to exist: ${relativePath}`);
    }
  }

  const outputIndexPath = path.join(outputRoot, APP_INDEX_PATH);

  return {
    outputRoot,
    cssFileName,
    cssHref: `/css/${cssFileName}`,
    mainScriptHref: `/js/${mainModule["output-name"]}`,
    outputIndexPath,
    releaseJavaScriptFiles,
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
