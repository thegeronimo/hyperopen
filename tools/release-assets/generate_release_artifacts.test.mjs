import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  collectReleaseJavaScriptFiles,
  fingerprintFileName,
  generateReleaseArtifacts,
  hashContent,
  normalizeModuleUriToRelativeJsPath,
  rewriteAppIndexHtml,
} from "./generate_release_artifacts.mjs";

test("rewriteAppIndexHtml replaces the default stylesheet href", () => {
  const source = `<!DOCTYPE html>
<html>
  <head>
    <link rel="stylesheet" href="/css/main.css" />
  </head>
  <body>
    <script>
      (function () {
        const manifestUrl = "/js/manifest.json";
        const defaultMainScriptUrl = "/js/main.js";

        fetch(manifestUrl, { cache: "no-cache" })
          .then((response) => (response.ok ? response.json() : null))
          .then(() => {
            document.body.dataset.loaded = defaultMainScriptUrl;
          });
      })();
    </script>
  </body>
</html>`;

  const rewritten = rewriteAppIndexHtml(source, {
    cssHref: "/css/main.ABC123.css",
    mainScriptHref: "/js/main.HASH.js",
  });

  assert.match(rewritten, /href="\/css\/main\.ABC123\.css"/);
  assert.doesNotMatch(rewritten, /href="\/css\/main\.css"/);
  assert.match(rewritten, /<script defer src="\/js\/main\.HASH\.js"><\/script>/);
  assert.doesNotMatch(rewritten, /manifestUrl/);
  assert.doesNotMatch(rewritten, /defaultMainScriptUrl/);
});

test("fingerprint helpers preserve the base filename and use uppercase hashes", () => {
  const fingerprint = hashContent(Buffer.from("abc"));
  assert.equal(fingerprint, fingerprint.toUpperCase());
  assert.equal(fingerprintFileName("main.css", fingerprint), `main.${fingerprint}.css`);
});

test("collectReleaseJavaScriptFiles keeps only explicit release assets", () => {
  const files = collectReleaseJavaScriptFiles({
    manifest: [
      { "module-id": "main", "output-name": "main.HASH.js" },
      { "module-id": "trade_chart", "output-name": "trade_chart.HASH.js" },
    ],
    moduleLoader: {
      "module-uris": {
        main: [],
        trade_chart: ["/js/trade_chart.HASH.js"],
      },
    },
  });

  assert.deepEqual(files, [
    "main.HASH.js",
    "module-loader.json",
    "portfolio_worker.js",
    "trade_chart.HASH.js",
    "vault_detail_worker.js",
  ]);
});

test("normalizeModuleUriToRelativeJsPath preserves nested js paths and strips query strings", () => {
  assert.equal(
    normalizeModuleUriToRelativeJsPath("/js/chunks/trade_chart.HASH.js?v=1"),
    "chunks/trade_chart.HASH.js"
  );
  assert.equal(normalizeModuleUriToRelativeJsPath("/assets/trade_chart.HASH.js"), null);
});

test("generateReleaseArtifacts assembles a deterministic release root", async () => {
  const tempRoot = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-release-assets-"));
  const sourceRoot = path.join(tempRoot, "source");
  const outputRoot = path.join(tempRoot, "output");

  await fs.mkdir(path.join(sourceRoot, "css"), { recursive: true });
  await fs.mkdir(path.join(sourceRoot, "js"), { recursive: true });
  await fs.mkdir(path.join(sourceRoot, "fonts"), { recursive: true });
  await fs.mkdir(path.join(sourceRoot, "js", "cljs-runtime"), { recursive: true });
  await fs.writeFile(
    path.join(sourceRoot, "index.html"),
    `<!DOCTYPE html>
<html>
  <head>
    <link rel="stylesheet" href="/css/main.css" />
  </head>
  <body>
    <script>
      (function () {
        const manifestUrl = "/js/manifest.json";
        const defaultMainScriptUrl = "/js/main.js";

        fetch(manifestUrl, { cache: "no-cache" })
          .then((response) => (response.ok ? response.json() : null))
          .then(() => {
            document.body.dataset.loaded = defaultMainScriptUrl;
          });
      })();
    </script>
  </body>
</html>`
  );
  await fs.writeFile(path.join(sourceRoot, "css", "main.css"), "body { color: white; }\n");
  await fs.writeFile(
    path.join(sourceRoot, "js", "manifest.json"),
    JSON.stringify([
      { "module-id": "main", "output-name": "main.HASH.js" },
      { "module-id": "trade_chart", "output-name": "trade_chart.CHUNK.js" },
    ])
  );
  await fs.writeFile(
    path.join(sourceRoot, "js", "module-loader.json"),
    JSON.stringify({
      "module-uris": {
        main: [],
        trade_chart: ["/js/trade_chart.CHUNK.js"],
      },
    })
  );
  await fs.writeFile(path.join(sourceRoot, "js", "main.HASH.js"), "console.log('main');\n");
  await fs.writeFile(path.join(sourceRoot, "js", "trade_chart.CHUNK.js"), "console.log('chunk');\n");
  await fs.writeFile(path.join(sourceRoot, "js", "portfolio_worker.js"), "worker();\n");
  await fs.writeFile(path.join(sourceRoot, "js", "vault_detail_worker.js"), "worker();\n");
  await fs.writeFile(path.join(sourceRoot, "js", "main.js"), "stale();\n");
  await fs.writeFile(path.join(sourceRoot, "js", "portfolio.js"), "stale();\n");
  await fs.writeFile(path.join(sourceRoot, "js", "cljs-runtime", "stale.map"), "stale");
  await fs.writeFile(path.join(sourceRoot, "fonts", "InterVariable.woff2"), "font");
  await fs.writeFile(path.join(sourceRoot, "sw.js"), "self.addEventListener('fetch', () => {});");
  await fs.writeFile(path.join(sourceRoot, "ui-workbench.html"), "<html></html>");

  const result = await generateReleaseArtifacts({ sourceRoot, outputRoot });

  const generatedIndex = await fs.readFile(path.join(outputRoot, "index.html"), "utf8");
  const generatedCss = await fs.readFile(path.join(outputRoot, "css", result.cssFileName), "utf8");
  const copiedMain = await fs.readFile(path.join(outputRoot, "js", "main.HASH.js"), "utf8");
  const copiedFont = await fs.readFile(path.join(outputRoot, "fonts", "InterVariable.woff2"), "utf8");

  assert.match(generatedIndex, new RegExp(`href="${result.cssHref.replace(".", "\\.")}"`));
  assert.match(generatedIndex, /<script defer src="\/js\/main\.HASH\.js"><\/script>/);
  assert.doesNotMatch(generatedIndex, /manifestUrl/);
  assert.equal(generatedCss, "body { color: white; }\n");
  assert.equal(copiedMain, "console.log('main');\n");
  assert.equal(copiedFont, "font");
  await assert.rejects(fs.access(path.join(outputRoot, "css", "main.css")));
  await assert.rejects(fs.access(path.join(outputRoot, "js", "manifest.json")));
  await assert.rejects(fs.access(path.join(outputRoot, "js", "main.js")));
  await assert.rejects(fs.access(path.join(outputRoot, "js", "portfolio.js")));
  await assert.rejects(fs.access(path.join(outputRoot, "js", "cljs-runtime", "stale.map")));
  await assert.rejects(fs.access(path.join(outputRoot, "ui-workbench.html")));
});

test("generateReleaseArtifacts fails closed when a required release asset is missing", async () => {
  const tempRoot = await fs.mkdtemp(path.join(os.tmpdir(), "hyperopen-release-assets-missing-"));
  const sourceRoot = path.join(tempRoot, "source");
  const outputRoot = path.join(tempRoot, "output");

  await fs.mkdir(path.join(sourceRoot, "css"), { recursive: true });
  await fs.mkdir(path.join(sourceRoot, "js"), { recursive: true });
  await fs.writeFile(
    path.join(sourceRoot, "index.html"),
    `<!DOCTYPE html>
<html>
  <head>
    <link rel="stylesheet" href="/css/main.css" />
  </head>
  <body>
    <script>
      (function () {
        const manifestUrl = "/js/manifest.json";
        const defaultMainScriptUrl = "/js/main.js";

        fetch(manifestUrl, { cache: "no-cache" })
          .then((response) => (response.ok ? response.json() : null))
          .then(() => {
            document.body.dataset.loaded = defaultMainScriptUrl;
          });
      })();
    </script>
  </body>
</html>`
  );
  await fs.writeFile(path.join(sourceRoot, "css", "main.css"), "body { color: white; }\n");
  await fs.writeFile(
    path.join(sourceRoot, "js", "manifest.json"),
    JSON.stringify([{ "module-id": "main", "output-name": "main.HASH.js" }])
  );
  await fs.writeFile(
    path.join(sourceRoot, "js", "module-loader.json"),
    JSON.stringify({ "module-uris": { main: [] } })
  );
  await fs.writeFile(path.join(sourceRoot, "js", "main.HASH.js"), "console.log('main');\n");

  await assert.rejects(
    generateReleaseArtifacts({ sourceRoot, outputRoot }),
    /Expected release asset to exist: js\/portfolio_worker\.js/
  );
});

test("rewriteAppIndexHtml works against the real tracked app entry", async () => {
  const projectRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..", "..");
  const realIndexPath = path.join(projectRoot, "resources", "public", "index.html");
  const realIndexHtml = await fs.readFile(realIndexPath, "utf8");

  const rewritten = rewriteAppIndexHtml(realIndexHtml, {
    cssHref: "/css/main.TEST.css",
    mainScriptHref: "/js/main.TEST.js",
  });

  assert.match(rewritten, /href="\/css\/main\.TEST\.css"/);
  assert.match(rewritten, /<script defer src="\/js\/main\.TEST\.js"><\/script>/);
  assert.doesNotMatch(rewritten, /manifestUrl/);
});
