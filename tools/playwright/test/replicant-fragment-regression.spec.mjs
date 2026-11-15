import { expect, test } from "../support/guarded_test.mjs";

const SCENE =
  "/ui-workbench.html?id=hyperopen.workbench.scenes.shell.shell-scenes" +
  "/footer-utility-links-populated";

const ROOT_ROLE = "footer-utility-links";

function role(name) {
  return `[data-role='${name}']`;
}

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

    const textLinks = frame.locator(role("footer-text-links"));
    await expect(textLinks).toBeVisible();
    await expect(textLinks).toContainText("Docs");
    await expect(textLinks).toContainText("Status");
    await expect(frame.locator(role("footer-links-divider"))).toBeAttached();

    await expect(frame.locator(role("footer-social-links"))).toBeVisible();

    expect(errors, errors.join("\n")).toEqual([]);
  });

  test("the spliced children are real siblings in the flex row", async ({
    page
  }) => {
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
