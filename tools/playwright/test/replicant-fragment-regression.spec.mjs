// Replicant fragment guard, browser-side (2026-08-26).
//
// This repo's Replicant has no fragment tag. `[:<> a b]` is treated as an
// element whose tag name is the literal string "<>", so the render reaches
// document.createElement("<>"), throws, and takes the surrounding subtree with
// it. The cljs suite cannot catch this: view tests assert on hiccup DATA and
// never run the renderer, so a fragment passes every unit test and fails only
// in a browser. `npm run lint:hiccup` now fails on the construct statically;
// this spec is the runtime half, and it is the only check here that would
// notice if Replicant's fragment behavior itself changed.
//
// The footer's text links are the case that motivated it. They are configured
// data and the only producer ships an empty vector, so the branch never renders
// in the running app -- loading a route proves nothing about it. The workbench
// scene mounts the populated branch through the real renderer instead.
import { expect, test } from "@playwright/test";

const SCENE =
  "/ui-workbench.html?id=hyperopen.workbench.scenes.shell.shell-scenes" +
  "/footer-utility-links-populated";

const ROOT_ROLE = "footer-utility-links";

function role(name) {
  return `[data-role='${name}']`;
}

// Observed failure mode, with the fragment restored and recompiled: the
// workbench boots its sidebar but never creates the scene iframe at all, and
// nothing reaches the console -- the throw is swallowed on the way up. So the
// load-bearing assertion is that the scene RENDERS; this error collector is a
// cheap second net for the case where a throw does surface.
function collectRenderErrors(page) {
  const errors = [];
  const record = entry => {
    if (
      entry.includes("createElement") ||
      entry.includes("InvalidCharacterError") ||
      entry.includes("'<>'") ||
      entry.includes("replicant")
    ) {
      errors.push(entry);
    }
  };
  page.on("pageerror", error => record(`pageerror: ${error.message}`));
  page.on("console", message => {
    if (message.type() === "error") {
      record(`console: ${message.text()}`);
    }
  });
  return errors;
}

// The workbench is served by the shadow-cljs watch server, which serves a blank
// page whenever the output on disk was produced by a different shadow instance
// (a standalone `compile` in the same worktree is enough to trigger it). That
// clears once the watch rebuilds, so give the boot a couple of reloads before
// treating a missing scene as a real failure.
async function openScene(page) {
  await page.goto(SCENE);

  for (let attempt = 0; attempt < 3; attempt += 1) {
    const frame = page.frameLocator("iframe").first();
    try {
      await expect(frame.locator(role(ROOT_ROLE))).toBeVisible({
        timeout: 20_000
      });
      return frame;
    } catch (error) {
      if (attempt === 2) {
        throw error;
      }
      await page.reload();
    }
  }

  throw new Error("workbench scene never booted");
}

test.describe("replicant fragment regression", () => {
  test("populated footer links render without a createElement throw", async ({
    page
  }) => {
    const errors = collectRenderErrors(page);
    const frame = await openScene(page);

    // Positive proof the branch actually rendered. Without links the whole
    // conditional is skipped, so assert the links AND the divider that shares
    // the branch with them -- neither can appear unless the branch ran.
    const textLinks = frame.locator(role("footer-text-links"));
    await expect(textLinks).toBeVisible();
    await expect(textLinks).toContainText("Docs");
    await expect(textLinks).toContainText("Status");
    await expect(frame.locator(role("footer-links-divider"))).toBeAttached();

    // The rest of the row must survive alongside the spliced children: a
    // fragment throw would have taken these siblings down with it.
    await expect(frame.locator(role("footer-social-links"))).toBeVisible();

    expect(errors, errors.join("\n")).toEqual([]);
  });

  test("the spliced children are real siblings in the flex row", async ({
    page
  }) => {
    // The fix splices into the parent rather than wrapping the pair in a
    // [:span], because a wrapper would collapse the gap-4 rhythm between the
    // links, the divider and the build badge. Assert the DOM shape that
    // encodes that decision, so a "fix" that wraps instead fails here.
    const frame = await openScene(page);

    const childRoles = await frame
      .locator(role(ROOT_ROLE))
      .evaluate(node =>
        Array.from(node.children).map(child => child.getAttribute("data-role"))
      );

    expect(childRoles).toEqual([
      "footer-text-links",
      "footer-links-divider",
      "footer-build-id-shell",
      "footer-social-links"
    ]);
  });
});
