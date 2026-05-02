import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
);

const mainCssPath = path.join(repoRoot, "src", "styles", "main.css");
const expectedImports = [
  "./base.css",
  "./surfaces/trading.css",
  "./surfaces/optimizer.css",
  "./surfaces/app-shell.css",
  "./surfaces/account.css",
  "./surfaces/trading-controls.css",
  "./surfaces/chart.css",
  "./surfaces/trading-layout.css",
  "./surfaces/utilities.css",
  "./surfaces/vaults.css",
];

const importPattern = /^@import\s+"([^"]+)";$/;
const requiredTailwindDirectives = [
  "@tailwind base;",
  "@tailwind components;",
  "@tailwind utilities;",
];

function significantLines(text) {
  return text
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter(Boolean);
}

test("main.css remains a route/surface import manifest", () => {
  const mainCss = fs.readFileSync(mainCssPath, "utf8");
  const lines = significantLines(mainCss);

  assert.equal(
    lines.length,
    expectedImports.length,
    "main.css should contain only the expected import statements",
  );

  const actualImports = lines.map((line) => {
    const match = importPattern.exec(line);
    assert.ok(match, `expected import statement, received: ${line}`);
    return match[1];
  });

  assert.deepEqual(actualImports, expectedImports);

  for (const importPath of expectedImports) {
    const targetPath = path.resolve(path.dirname(mainCssPath), importPath);
    assert.ok(fs.existsSync(targetPath), `missing imported CSS file: ${importPath}`);
  }
});

test("base stylesheet keeps the Tailwind entry directives", () => {
  const baseCssPath = path.join(repoRoot, "src", "styles", "base.css");
  const baseLines = significantLines(fs.readFileSync(baseCssPath, "utf8"));
  const actualDirectives = baseLines.filter((line) => line.startsWith("@tailwind "));

  assert.deepEqual(actualDirectives, requiredTailwindDirectives);
});

test("split stylesheet compiles through Tailwind CLI", () => {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "hyperopen-css-split-"));
  const outputPath = path.join(tempDir, "main.css");
  const tailwindBin = path.join(
    repoRoot,
    "node_modules",
    ".bin",
    process.platform === "win32" ? "tailwindcss.cmd" : "tailwindcss",
  );

  try {
    execFileSync(tailwindBin, ["-i", mainCssPath, "-o", outputPath, "--minify"], {
      cwd: repoRoot,
      encoding: "utf8",
      stdio: "pipe",
    });

    const output = fs.readFileSync(outputPath, "utf8");
    assert.match(output, /\.flex\{display:flex\}/);
  } finally {
    fs.rmSync(tempDir, { recursive: true, force: true });
  }
});
