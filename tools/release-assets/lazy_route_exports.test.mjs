import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";

const lazyRouteExports = [
  {
    sourcePath: "src/hyperopen/views/portfolio_view.cljs",
    exportSymbol: "hyperopen.views.portfolio_view.route_view",
  },
  {
    sourcePath: "src/hyperopen/views/leaderboard_view.cljs",
    exportSymbol: "hyperopen.views.leaderboard_view.route_view",
  },
  {
    sourcePath: "src/hyperopen/views/funding_comparison_view.cljs",
    exportSymbol: "hyperopen.views.funding_comparison_view.route_view",
  },
  {
    sourcePath: "src/hyperopen/views/referrals_view.cljs",
    exportSymbol: "hyperopen.views.referrals_view.route_view",
  },
  {
    sourcePath: "src/hyperopen/views/earn_view.cljs",
    exportSymbol: "hyperopen.views.earn_view.route_view",
  },
  {
    sourcePath: "src/hyperopen/views/staking_view.cljs",
    exportSymbol: "hyperopen.views.staking_view.route_view",
  },
  {
    sourcePath: "src/hyperopen/views/api_wallets_view.cljs",
    exportSymbol: "hyperopen.views.api_wallets_view.route_view",
  },
  {
    sourcePath: "src/hyperopen/views/subaccounts_view.cljs",
    exportSymbol: "hyperopen.views.subaccounts_view.route_view",
  },
  {
    sourcePath: "src/hyperopen/views/vaults/list_view.cljs",
    exportSymbol: "hyperopen.views.vaults.list_view.route_view",
  },
  {
    sourcePath: "src/hyperopen/views/vaults/detail_view.cljs",
    exportSymbol: "hyperopen.views.vaults.detail_view.route_view",
  },
];

test("lazy route views expose explicit advanced-build exports", () => {
  for (const { sourcePath, exportSymbol } of lazyRouteExports) {
    const source = fs.readFileSync(sourcePath, "utf8");

    assert.match(
      source,
      /\(defn\s+\^:export\s+route-view\b/,
      `${sourcePath} must mark route-view as ^:export`
    );
    assert.ok(
      source.includes(`(goog/exportSymbol "${exportSymbol}" route-view)`),
      `${sourcePath} must export ${exportSymbol}`
    );
  }
});
