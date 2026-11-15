import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
);
const palette = require(
  path.join(repoRoot, "src", "styles", "themes", "palette.js"),
);

const dryRun = process.argv.includes("--dry-run");

const tokenByHex = new Map(
  Object.entries(palette.themes[palette.defaultTheme].colors).map(
    ([token, hex]) => [hex.replace("#", "").toLowerCase(), `ho-${token}`],
  ),
);

const colorPrefixes =
  "bg|text|border(?:-[trblxy])?|divide|outline|ring(?:-offset)?|fill|stroke|from|via|to|placeholder|caret|accent|decoration";

function migrateSource(source) {
  let migrated = source;
  let count = 0;
  for (const [hex, token] of tokenByHex) {
    const pattern = new RegExp(
      `\\b(${colorPrefixes})-\\[#${hex}\\]`,
      "gi",
    );
    migrated = migrated.replace(pattern, (_match, prefix) => {
      count += 1;
      return `${prefix}-${token}`;
    });
  }
  return { migrated, count };
}

function* cljsFiles(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      yield* cljsFiles(fullPath);
    } else if (entry.name.endsWith(".cljs")) {
      yield fullPath;
    }
  }
}

const viewsRoot = path.join(repoRoot, "src", "hyperopen", "views");
let totalReplacements = 0;
let changedFiles = 0;
for (const file of cljsFiles(viewsRoot)) {
  const source = fs.readFileSync(file, "utf8");
  const { migrated, count } = migrateSource(source);
  if (count > 0) {
    totalReplacements += count;
    changedFiles += 1;
    if (!dryRun) {
      fs.writeFileSync(file, migrated);
    }
    console.log(`${count}\t${path.relative(repoRoot, file)}`);
  }
}
console.log(
  `${dryRun ? "[dry-run] " : ""}${totalReplacements} replacements in ${changedFiles} files`,
);
