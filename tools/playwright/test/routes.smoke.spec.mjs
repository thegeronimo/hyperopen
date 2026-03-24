import { test } from "@playwright/test";
import { expectOracle, mobileViewport, visitRoute } from "../support/hyperopen.mjs";

const routeCases = [
  { name: "trade", route: "/trade", parityId: "trade-root" },
  { name: "portfolio", route: "/portfolio", parityId: "portfolio-root" },
  { name: "leaderboard", route: "/leaderboard", parityId: "leaderboard-root" },
  { name: "vaults", route: "/vaults", parityId: "vaults-root" }
];

test.describe("main route smoke @smoke", () => {
  for (const routeCase of routeCases) {
    test(`${routeCase.name} desktop root renders @smoke`, async ({ page }) => {
      await visitRoute(page, routeCase.route);
      await expectOracle(
        page,
        "parity-element",
        { present: true },
        { args: { parityId: routeCase.parityId } }
      );
    });
  }
});

test.describe("main route smoke mobile @smoke", () => {
  test.use(mobileViewport);

  for (const routeCase of routeCases) {
    test(`${routeCase.name} mobile root renders @smoke`, async ({ page }) => {
      await visitRoute(page, routeCase.route);
      await expectOracle(
        page,
        "parity-element",
        { present: true },
        { args: { parityId: routeCase.parityId } }
      );
    });
  }
});
