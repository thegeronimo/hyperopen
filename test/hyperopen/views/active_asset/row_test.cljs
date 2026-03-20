(ns hyperopen.views.active-asset.row-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.state.trading :as trading]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.active-asset.row :as row]
            [hyperopen.views.active-asset.test-support :as support]))

(deftest active-asset-row-symbol-fallback-test
  (let [ctx-data {:coin "SOL"
                  :mark 87.0
                  :oracle 86.9
                  :change24h 1.2
                  :change24hPct 1.4
                  :volume24h 1000
                  :openInterest 100
                  :fundingRate 0.001}
        market {:market-type :perp}
        view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})
        strings (set (support/collect-strings view-node))]
    (is (contains? strings "SOL"))))

(deftest active-asset-row-uses-app-shell-left-gutter-test
  (let [ctx-data {:coin "SOL"
                  :mark 87.0
                  :oracle 86.9
                  :change24h 1.2
                  :change24hPct 1.4
                  :volume24h 1000
                  :openInterest 100
                  :fundingRate 0.001}
        market {:coin "SOL"
                :symbol "SOL"
                :base "SOL"
                :market-type :perp}
        view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})]
    (is (support/contains-class? view-node "app-shell-gutter-left"))))

(deftest active-asset-row-applies-numeric-utility-to-live-values-test
  (let [ctx-data {:coin "SOL"
                  :mark 87.0
                  :markRaw "87.0"
                  :oracle 86.9
                  :oracleRaw "86.9"
                  :change24h 1.2
                  :change24hPct 1.4
                  :volume24h 1000
                  :openInterest 100
                  :fundingRate 0.001}
        market {:coin "SOL"
                :symbol "SOL"
                :base "SOL"
                :market-type :perp}
        view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})]
    (is (support/contains-class? view-node "num"))))

(deftest active-asset-row-prioritizes-symbol-column-during-resize-test
  (let [ctx-data {:coin "SOL-USD"
                  :mark 87.0
                  :markRaw "87.0"
                  :oracle 86.9
                  :oracleRaw "86.9"
                  :change24h 1.2
                  :change24hPct 1.4
                  :volume24h 1000
                  :openInterest 100
                  :fundingRate 0.001}
        market {:coin "SOL-USD"
                :symbol "SOL-USD"
                :base "SOL"
                :market-type :perp}
        view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})]
    (is (support/contains-class? view-node "md:grid-cols-[minmax(max-content,1.4fr)_minmax(0,0.9fr)_minmax(0,0.9fr)_minmax(0,1.1fr)_minmax(0,1.1fr)_minmax(0,1.2fr)_minmax(0,1.6fr)]"))))

(deftest active-asset-row-renders-dex-and-leverage-chips-test
  (let [ctx-data {:coin "XYZ100-USDC"
                  :mark 87.0
                  :oracle 86.9
                  :change24h 1.2
                  :change24hPct 1.4
                  :volume24h 1000
                  :openInterest 100
                  :fundingRate 0.001}
        market {:coin "XYZ100-USDC"
                :symbol "XYZ100-USDC"
                :base "XYZ100"
                :dex "xyz"
                :maxLeverage 25
                :market-type :perp}
        view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})
        strings (set (support/collect-strings view-node))]
    (is (contains? strings "xyz"))
    (is (contains? strings "25x"))
    (is (support/contains-class? view-node "bg-emerald-500/20"))
    (is (not (support/contains-class? view-node "bg-primary")))))

(deftest active-asset-row-renders-coin-namespace-chip-when-dex-missing-test
  (let [ctx-data {:coin "xyz:XYZ100-USDC"
                  :mark 87.0
                  :oracle 86.9
                  :change24h 1.2
                  :change24hPct 1.4
                  :volume24h 1000
                  :openInterest 100
                  :fundingRate 0.001}
        market {:coin "xyz:XYZ100-USDC"
                :symbol "XYZ100-USDC"
                :base "XYZ100"
                :maxLeverage 25
                :market-type :perp}
        view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})
        strings (set (support/collect-strings view-node))]
    (is (contains? strings "xyz"))))

(deftest active-asset-row-render-visible-branch-skips-hidden-renderer-test
  (let [render-visible-branch @#'row/render-visible-branch
        mobile-calls (atom 0)
        desktop-calls (atom 0)]
    (support/with-viewport-width
      430
      (fn []
        (let [branch-node (render-visible-branch
                           (fn []
                             (swap! mobile-calls inc)
                             [:div {:data-role "mobile-branch"} "mobile"])
                           (fn []
                             (swap! desktop-calls inc)
                             [:div {:data-role "desktop-branch"} "desktop"]))]
          (is (= 1 @mobile-calls))
          (is (= 0 @desktop-calls))
          (is (some? (support/find-node-by-role branch-node "mobile-branch")))
          (is (nil? (support/find-node-by-role branch-node "desktop-branch"))))))
    (reset! mobile-calls 0)
    (reset! desktop-calls 0)
    (support/with-viewport-width
      1280
      (fn []
        (let [branch-node (render-visible-branch
                           (fn []
                             (swap! mobile-calls inc)
                             [:div {:data-role "mobile-branch"} "mobile"])
                           (fn []
                             (swap! desktop-calls inc)
                             [:div {:data-role "desktop-branch"} "desktop"]))]
          (is (= 0 @mobile-calls))
          (is (= 1 @desktop-calls))
          (is (some? (support/find-node-by-role branch-node "desktop-branch")))
          (is (nil? (support/find-node-by-role branch-node "mobile-branch"))))))))

(deftest select-asset-row-renders-mobile-empty-state-only-on-mobile-viewport-test
  (support/with-viewport-width
    430
    (fn []
      (let [view-node (row/select-asset-row {:visible-dropdown nil})
            strings (set (support/collect-strings view-node))]
        (is (contains? strings "Select Asset"))
        (is (contains? strings "Select a market to view price, liquidity, and funding details."))
        (is (not (support/contains-class? view-node row/active-asset-grid-template)))
        (is (not (contains? strings "Funding / Countdown")))))))

(deftest select-asset-row-renders-desktop-empty-state-only-on-desktop-viewport-test
  (support/with-viewport-width
    1280
    (fn []
      (let [view-node (row/select-asset-row {:visible-dropdown nil})
            strings (set (support/collect-strings view-node))]
        (is (contains? strings "Select Asset"))
        (is (support/contains-class? view-node row/active-asset-grid-template))
        (is (contains? strings "Funding / Countdown"))
        (is (contains? strings "24h Volume"))
        (is (not (contains? strings "Select a market to view price, liquidity, and funding details.")))))))

(deftest mobile-active-asset-row-collapses-details-by-default-test
  (support/with-viewport-width
    430
    (fn []
      (let [ctx-data {:coin "SOL"
                      :mark 87.0
                      :markRaw "87.0"
                      :oracle 86.9
                      :oracleRaw "86.9"
                      :change24h 1.2
                      :change24hPct 1.4
                      :volume24h 1000
                      :openInterest 100
                      :fundingRate 0.001}
            market {:coin "SOL"
                    :symbol "SOL"
                    :base "SOL"
                    :market-type :perp}
            view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})
            strings (set (support/collect-strings view-node))
            toggle-button (support/find-node-by-role view-node "trade-mobile-asset-details-toggle")
            details-panel (support/find-node-by-role view-node "trade-mobile-asset-details-panel")]
        (is (contains? strings "SOL"))
        (is (some? toggle-button))
        (is (= [[:actions/toggle-trade-mobile-asset-details]]
               (get-in toggle-button [1 :on :click])))
        (is (not (support/contains-class? view-node row/active-asset-grid-template)))
        (is (not (contains? strings "24h Volume")))
        (is (nil? details-panel))))))

(deftest mobile-active-asset-row-renders-disclosure-panel-when-open-test
  (support/with-viewport-width
    430
    (fn []
      (let [ctx-data {:coin "SOL"
                      :mark 87.0
                      :markRaw "87.0"
                      :oracle 86.9
                      :oracleRaw "86.9"
                      :change24h 1.2
                      :change24hPct 1.4
                      :volume24h 1000
                      :openInterest 100
                      :fundingRate 0.001}
            market {:coin "SOL"
                    :symbol "SOL"
                    :base "SOL"
                    :market-type :perp}
            view-node (row/active-asset-row ctx-data
                                            market
                                            {:visible-dropdown nil}
                                            {:asset-selector {:missing-icons #{}}
                                             :trade-ui {:mobile-asset-details-open? true}})
            details-panel (support/find-node-by-role view-node "trade-mobile-asset-details-panel")
            strings (set (support/collect-strings details-panel))]
        (is (some? details-panel))
        (is (contains? strings "Mark / Oracle"))
        (is (contains? strings "24h Volume"))
        (is (contains? strings "Open Interest"))
        (is (contains? strings "Funding / Countdown"))
        (is (support/contains-class? details-panel "border-t"))
        (is (not (support/contains-class? details-panel "rounded-xl")))
        (is (support/contains-class? details-panel "cursor-help"))
        (is (support/contains-class? details-panel "decoration-dashed"))))))

(deftest mobile-active-asset-row-renders-open-funding-tooltip-content-when-visible-test
  (support/with-viewport-width
    430
    (fn []
      (let [ctx-data {:coin "xyz:GOLD"
                      :mark 5000.0
                      :oracle 4998.0
                      :change24h 5.0
                      :change24hPct 0.5
                      :volume24h 2000000
                      :openInterest 200
                      :fundingRate 0.0056}
            market {:coin "xyz:GOLD"
                    :symbol "GOLD-USDC"
                    :base "GOLD"
                    :market-type :perp}
            full-state (-> {:active-asset "xyz:GOLD"
                            :asset-selector {:missing-icons #{}}
                            :trade-ui {:mobile-asset-details-open? true}}
                           (support/with-visible-funding-tooltip "xyz:GOLD"))]
        (with-redefs [trading/position-for-active-asset
                      (fn [_] nil)
                      fmt/format-funding-countdown
                      (fn [] "00:10:00")]
          (let [view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
                details-panel (support/find-node-by-role view-node "trade-mobile-asset-details-panel")
                strings (set (support/collect-strings details-panel))]
            (is (contains? strings "Hypothetical Position"))
            (is (contains? strings "Projections"))))))))

(deftest active-asset-row-renders-24h-change-without-funding-rate-test
  (let [ctx-data {:coin "SOL"
                  :mark 87.0
                  :markRaw "87.0"
                  :oracle 86.9
                  :oracleRaw "86.9"
                  :change24h 1.2
                  :change24hPct 1.4
                  :volume24h 1000
                  :openInterest 100}
        market {:coin "SOL"
                :symbol "SOL"
                :base "SOL"
                :market-type :perp}
        view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})
        loading-count (count (filter #(= "Loading..." %) (support/collect-strings view-node)))]
    (is (= 1 loading-count))
    (is (contains? (set (support/collect-strings view-node)) "24h Change"))))
