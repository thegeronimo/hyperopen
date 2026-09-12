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
    (is (contains? strings "SOL"))
    (is (nil? (support/find-node-by-role view-node "outcome-market-tooltip")))))

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
        view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}})
        statistics-scroll (support/find-node-by-role view-node "active-asset-statistics-scroll")
        statistic-cell (support/find-node-by-role view-node "active-asset-stat-cell")]
    (is (support/contains-class? view-node "md:grid-cols-[minmax(max-content,1.4fr)_minmax(max-content,0.9fr)_minmax(max-content,0.9fr)_minmax(max-content,1.1fr)_minmax(max-content,1.1fr)_minmax(max-content,1.2fr)_minmax(max-content,1.6fr)]"))
    (is (= "Market statistics" (get-in statistics-scroll [1 :aria-label])))
    (is (= 0 (get-in statistics-scroll [1 :tabindex])))
    (is (some? statistic-cell))))

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

(deftest desktop-active-asset-row-renders-outcome-header-and-details-test
  (support/with-viewport-width
    1280
    (fn []
      (let [market {:coin "#0"
                    :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                    :title "BTC above 78213 on May 3 at 2:00 AM?"
                    :market-type :outcome
                    :underlying "BTC"
                    :quote "USDH"
                    :target-price 78213
                    :mark 0.57841
                    :markRaw "0.57841"
                    :change24h 0.0268
                    :change24hPct 4.87
                    :volume24h 180211.68
                    :openInterest 537233
                    :expiry-ms 1777788000000
                    :outcome-details "If BTC settles above 78213, YES pays $1."}
            view-node (row/active-asset-row {} market {:visible-dropdown nil} {:asset-selector {:missing-icons #{}}
                                                                                :now-ms 1777751495000})
            strings (set (support/collect-strings view-node))
            tooltip (support/find-node-by-role view-node "outcome-market-tooltip")
            scroll-container (support/find-node-by-role view-node "outcome-tooltip-scroll-container")
            summary-scroll (support/find-node-by-role view-node "outcome-tooltip-summary-scroll")
            settlement-label (support/find-node-by-role view-node "outcome-tooltip-settlement-label")]
        (is (contains? strings "Countdown"))
        (is (contains? strings "10h 8m 25s"))
        (is (contains? strings "% Chance"))
        (is (contains? strings "Price (Yes)"))
        (is (contains? strings "58%"))
        (is (contains? strings "$537,233"))
        (is (contains? strings "Two sided-open interest: the sum of Yes and No shares on this contract"))
        (is (not (contains? strings "Details")))
        (is (some? tooltip))
        (is (some? summary-scroll))
        (is (contains? strings "Outcome Details"))
        (is (contains? strings "This market resolves to YES or NO based on the following settlement condition at the specified time."))
        (is (contains? strings "Settlement Condition"))
        (is (contains? strings "BTC mark price is above 78,213"))
        (is (contains? strings "on May 03, 2026 02:00 AM UTC"))
        (is (contains? strings "Payout Rule"))
        (is (contains? strings "YES"))
        (is (contains? strings "$1.00"))
        (is (contains? strings "NO"))
        (is (contains? strings "$0.00"))
        (is (contains? strings "each"))
        (is (contains? strings "Payouts are in USDH."))
        (is (not (contains? strings "All payouts are in USDC.")))
        (is (not (contains? strings "Learn more")))
        (is (support/contains-class? view-node "group/outcome-name"))
        (is (support/contains-class? view-node "group/outcome-open-interest"))
        (is (support/contains-class? tooltip "left-0"))
        (is (= {:width "min(44rem, calc(100vw - 2rem))"
                :max-width "calc(100vw - 2rem)"}
               (get-in tooltip [1 :style])))
        (is (= {:max-height "min(40rem, calc(100vh - 5rem))"}
               (get-in scroll-container [1 :style])))
        (is (support/contains-class? scroll-container "overflow-y-auto"))
        (is (not (support/contains-class? summary-scroll "overflow-y-auto")))
        (is (support/contains-class? view-node "shadow-[0_0_24px_rgba(45,212,191,0.10),0_18px_70px_rgba(0,0,0,0.55)]"))
        (is (support/contains-class? settlement-label "whitespace-normal"))
        (is (support/contains-class? settlement-label "break-words"))
        (is (not (support/contains-class? settlement-label "whitespace-nowrap")))
        (is (support/contains-class? view-node "items-center"))
        (is (support/contains-class? view-node "group-hover/outcome-name:border-[#2dd4bf]/35"))
        (is (not (support/contains-class? tooltip "right-0")))
        (is (not (support/contains-class? view-node "w-[min(50rem,calc(100vw-3rem))]")))
        (is (support/contains-class? view-node "group-hover/outcome-name:opacity-100"))
        (is (support/contains-class? view-node "group-hover/outcome-open-interest:opacity-100"))
        (is (support/contains-class? view-node "group-focus-within/outcome-name:opacity-100"))
        (is (support/contains-class? view-node "pointer-events-none"))
        (is (not (contains? strings "Funding / Countdown")))))))

(defn- outcome-option-handlers-fixture []
  {:on-select-outcome-option (fn [outcome-id]
                               [[:actions/close-outcome-option-dropdown]
                                [:actions/update-order-form [:outcome-option-id] outcome-id]])
   :on-toggle-option-dropdown [[:actions/toggle-outcome-option-dropdown]]
   :on-option-dropdown-keydown [[:actions/handle-outcome-option-dropdown-keydown [:event/key]]]
   :on-change-option-query [[:actions/set-outcome-option-query [:event.target/value]]]})

(defn- world-cup-row-vm
  [option-ui]
  {:is-outcome true
   :icon-market {:coin "#1890"
                 :symbol "2026 World Cup Champion"
                 :market-type :outcome}
   :dropdown-visible? false
   :missing-icons #{}
   :loaded-icons #{}
   :outcome-options [{:outcome-id 173
                      :label "Argentina"
                      :mark 0.14
                      :volume24h 2461
                      :openInterest 35592}
                     {:outcome-id 189
                      :label "France"
                      :mark 0.18
                      :volume24h 5776
                      :openInterest 41448}
                     {:outcome-id 212
                      :label "Spain"
                      :mark 0.17
                      :volume24h 1791
                      :openInterest 27056}]
   :outcome-option-id 189
   :outcome-option-ui option-ui
   :outcome-chance-label "18%"
   :countdown-text "10h 8m"
   :mark 0.17514
   :markRaw "0.17514"
   :change24h 0.01413
   :change24hPct 8.78
   :volume24h 5776
   :open-interest-usd 41448})

(deftest desktop-outcome-row-renders-option-selector-next-to-market-selector-test
  (support/with-viewport-width
    1280
    (fn []
      (let [view-node (row/active-asset-row-from-vm
                       (world-cup-row-vm {:open? false :query ""})
                       {:outcome-handlers (outcome-option-handlers-fixture)})
            selector (support/find-node-by-role view-node "market-strip-outcome-option-selector")
            hover-region (support/find-node-by-role view-node "outcome-market-name-hover-region")
            trigger (support/find-node-by-role view-node "outcome-option-select-trigger")
            menu (support/find-node-by-role view-node "outcome-option-select-menu")
            strings (set (support/collect-strings view-node))
            hover-region-strings (set (support/collect-strings hover-region))]
        (is (some? selector))
        (is (some? hover-region))
        (is (some? trigger))
        (is (= [[:actions/toggle-outcome-option-dropdown]]
               (get-in trigger [1 :on :click])))
        (is (contains? strings "2026 World Cup Champion"))
        (is (contains? strings "France"))
        (is (not (contains? hover-region-strings "France")))
        (is (not (support/contains-class? selector "group/outcome-name")))
        (is (not (contains? strings "Spain")))
        (is (nil? menu))
        (is (support/contains-class? selector "max-w-[18rem]"))))))

(deftest desktop-outcome-row-renders-searchable-option-menu-from-market-strip-test
  (support/with-viewport-width
    1280
    (fn []
      (let [view-node (row/active-asset-row-from-vm
                       (world-cup-row-vm {:open? true :query "sp"})
                       {:outcome-handlers (outcome-option-handlers-fixture)})
            selector (support/find-node-by-role view-node "market-strip-outcome-option-selector")
            menu (support/find-node-by-role view-node "outcome-option-select-menu")
            header-row (support/find-node-by-role view-node "outcome-option-select-header-row")
            first-option-row (support/find-node-by-role view-node "outcome-option-select-row")
            option-grid-template "minmax(0, 1.35fr) 4.5rem 5rem 5.5rem 5.75rem"
            search-input (support/find-node
                          #(and (vector? %)
                                (= :input (first %)))
                          view-node)
            strings (set (support/collect-strings selector))]
        (is (some? menu))
        (is (= "sp" (get-in search-input [1 :value])))
        (is (= [[:actions/set-outcome-option-query [:event.target/value]]]
               (get-in search-input [1 :on :input])))
        (is (support/contains-class? menu "w-[min(42rem,calc(100vw-2rem))]"))
        (is (support/contains-class? menu "bg-ho-bg-deep"))
        (is (support/contains-class? menu "z-[280]"))
        (is (= {:width "min(42rem, calc(100vw - 2rem))"
                :max-width "calc(100vw - 2rem)"}
               (get-in menu [1 :style])))
        (is (= {:z-index 260}
               (get-in selector [1 :style])))
        (is (= option-grid-template (get-in header-row [1 :style :grid-template-columns])))
        (is (= option-grid-template (get-in first-option-row [1 :style :grid-template-columns])))
        (is (contains? strings "Live Outcomes"))
        (is (contains? strings "% Chance"))
        (is (contains? strings "Price"))
        (is (contains? strings "Volume"))
        (is (contains? strings "Open Int"))
        (is (contains? strings "Spain"))
        (is (not (contains? strings "Argentina")))))))

(deftest mobile-outcome-row-renders-option-selector-with-market-selector-test
  (support/with-viewport-width
    430
    (fn []
      (let [view-node (row/active-asset-row-from-vm
                       (world-cup-row-vm {:open? false :query ""})
                       {:outcome-handlers (outcome-option-handlers-fixture)})
            selector (support/find-node-by-role view-node "market-strip-outcome-option-selector")
            strings (set (support/collect-strings view-node))]
        (is (some? selector))
        (is (contains? strings "2026 World Cup Champion"))
        (is (contains? strings "France"))
        (is (not (contains? strings "Countdown")))))))

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
        (with-redefs [trading/position-for-market
                      (fn [_ _ _] nil)
                      fmt/format-funding-countdown
                      (fn [] "00:10:00")]
          (let [pin-id (support/funding-tooltip-pin-id "xyz:GOLD")
                view-node (row/active-asset-row ctx-data market {:visible-dropdown nil} full-state)
                details-panel (support/find-node-by-role view-node "trade-mobile-asset-details-panel")
                layer-node (support/find-node-by-role view-node "active-asset-funding-mobile-sheet-layer")
                backdrop-node (support/find-node-by-role view-node "active-asset-funding-mobile-sheet-backdrop")
                sheet-node (support/find-node-by-role view-node "active-asset-funding-mobile-sheet")
                sheet-strings (set (support/collect-strings sheet-node))]
            (is (some? details-panel))
            (is (some? layer-node))
            (is (some? backdrop-node))
            (is (some? sheet-node))
            (is (= [[:actions/set-funding-tooltip-pinned pin-id false]
                    [:actions/set-funding-tooltip-visible pin-id false]]
                   (get-in backdrop-node [1 :on :click])))
            (is (= "dialog" (get-in sheet-node [1 :role])))
            (is (= true (get-in sheet-node [1 :aria-modal])))
            (is (contains? sheet-strings "Hypothetical Position"))
            (is (contains? sheet-strings "Projections"))))))))

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
