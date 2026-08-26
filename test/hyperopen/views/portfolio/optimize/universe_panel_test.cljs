(ns hyperopen.views.portfolio.optimize.universe-panel-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.views.portfolio-view :as portfolio-view]))

(defn- node-children
  [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- find-first-node
  [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- rendered-text
  "The view's text as the DOM would concatenate it. The candidate symbol is
  rendered as a lead span plus a quote pill (plus highlight segments), so the
  full symbol exists as text content but no longer as one string node."
  [node]
  (str/join (collect-strings node)))

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- node-by-role
  [node role]
  (find-first-node node #(= role (get-in % [1 :data-role]))))

(defn- click-actions
  [node]
  (get-in node [1 :on :click]))

(defn- input-actions
  [node]
  (get-in node [1 :on :input]))

(defn- keydown-actions
  [node]
  (get-in node [1 :on :keydown]))

(deftest portfolio-optimizer-universe-panel-does-not-render-quick-add-buttons-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer {:draft {:universe []
                                                     :constraints {:long-only? false}}}}
                    :asset-selector
                    {:markets [{:key "perp:TON"
                                :market-type :perp
                                :coin "TON"
                                :symbol "TON-USDC"}
                               {:key "perp:NEAR"
                                :market-type :perp
                                :coin "NEAR"
                                :symbol "NEAR-USDC"}
                               {:key "perp:AVAX"
                                :market-type :perp
                                :coin "AVAX"
                                :symbol "AVAX-USDC"}]}})
        strings (set (collect-strings view-node))]
    (is (contains? strings "Manual Add"))
    (is (not (contains? strings "quick add")))
    (is (not (contains? strings "+ TON")))
    (is (not (contains? strings "+ NEAR")))
    (is (not (contains? strings "+ AVAX")))
    (is (nil? (node-by-role view-node
                            "portfolio-optimizer-universe-quick-add-perp:TON")))
    (is (nil? (node-by-role view-node
                            "portfolio-optimizer-universe-quick-add-perp:NEAR")))
    (is (nil? (node-by-role view-node
                            "portfolio-optimizer-universe-quick-add-perp:AVAX")))))

(deftest portfolio-optimizer-workspace-supports-manual-universe-builder-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio-ui {:optimizer {:universe-search-query "eth"}}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :constraints {:long-only? false}}}}
                    :asset-selector
                    {:markets [{:key "perp:BTC"
                                :market-type :perp
                                :coin "BTC"
                                :symbol "BTC-USDC"}
                               {:key "perp:ETH"
                                :market-type :perp
                                :coin "ETH"
                                :symbol "ETH-USDC"
                                :dex "hl"}
                               {:key "spot:PURR/USDC"
                                :market-type :spot
                                :coin "PURR/USDC"
                                :symbol "PURR/USDC"}]
                     :market-by-key {"perp:BTC" {:key "perp:BTC"
                                                 :market-type :perp
                                                 :coin "BTC"
                                                 :symbol "BTC-USDC"}
                                     "perp:ETH" {:key "perp:ETH"
                                                 :market-type :perp
                                                 :coin "ETH"
                                                 :symbol "ETH-USDC"
                                                 :dex "hl"}
                                     "spot:PURR/USDC" {:key "spot:PURR/USDC"
                                                       :market-type :spot
                                                       :coin "PURR/USDC"
                                                       :symbol "PURR/USDC"}}}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node
                              "portfolio-optimizer-universe-selected-header")))
    ;; The flat candidate table header was replaced by per-type sticky group
    ;; headers, so a full result list stays readable while it scrolls.
    (is (some? (node-by-role view-node
                              "portfolio-optimizer-universe-candidate-group-header-perp")))
    (is (= "eth"
           (get-in (node-by-role view-node
                                 "portfolio-optimizer-universe-search-input")
                   [1 :value])))
    (is (= [[:actions/set-portfolio-optimizer-universe-search-query
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-universe-search-input"))))
    (is (= [[:actions/handle-portfolio-optimizer-universe-search-keydown
             [:event/key]
             ["perp:ETH"]]]
           (keydown-actions
            (node-by-role view-node
                          "portfolio-optimizer-universe-search-input"))))
    (is (= [[:actions/add-portfolio-optimizer-universe-instrument "perp:ETH"]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-universe-add-perp:ETH"))))
    (is (= [[:actions/remove-portfolio-optimizer-universe-instrument "perp:BTC"]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-universe-remove-perp:BTC"))))
    (is (nil? (node-by-role view-node
                            "portfolio-optimizer-universe-add-perp:BTC")))
    (is (contains? strings "Manual Add"))
    (is (contains? strings "Asset"))
    (is (contains? strings "Perps"))
    ;; The dedicated History column header was dropped: all-clear rows left it
    ;; empty, so the by-exception chip now renders inline on the row instead.
    (is (not (contains? strings "History")))
    (is (not (contains? strings "Liquidity")))
    (is (not (contains? strings "medium")))
    ;; Symbols are never truncated now: the quote token is its own pill and the
    ;; separator stays on the lead, so the row still reads as the whole symbol.
    (is (str/includes? (rendered-text view-node) "ETH-USDC"))
    (is (contains? strings "History starts loading after assets are included."))))

(deftest portfolio-optimizer-search-results-use-active-keyboard-row-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio-ui {:optimizer {:universe-search-query "hype"
                                               :universe-search-active-index 1}}
                    :portfolio {:optimizer {:draft {:universe []
                                                     :constraints {:long-only? false}}}}
                    :asset-selector
                    {:markets [{:key "spot:@107"
                                :market-type :spot
                                :coin "@107"
                                :symbol "HYPE/USDC"
                                :base "HYPE"
                                :quote "USDC"}
                               {:key "spot:@232"
                                :market-type :spot
                                :coin "@232"
                                :symbol "HYPE/USDH"
                                :base "HYPE"
                                :quote "USDH"}]}})
        input (node-by-role view-node "portfolio-optimizer-universe-search-input")
        first-row (node-by-role view-node "portfolio-optimizer-universe-candidate-row-spot:@107")
        second-row (node-by-role view-node "portfolio-optimizer-universe-candidate-row-spot:@232")]
    (is (= "portfolio-optimizer-universe-candidate-1"
           (get-in input [1 :aria-activedescendant])))
    (is (= [[:actions/handle-portfolio-optimizer-universe-search-keydown
             [:event/key]
             ["spot:@107" "spot:@232"]]]
           (keydown-actions input)))
    (is (= nil (get-in first-row [1 :data-active])))
    (is (= "true" (get-in second-row [1 :data-active])))
    (is (= "true" (get-in second-row [1 :aria-selected])))))

(deftest portfolio-optimizer-selected-universe-prefers-symbol-for-raw-spot-and-hip3-assets-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "spot:@107"
                                                     :market-type :spot
                                                     :coin "@107"
                                                     :symbol "UBTC/USDC"
                                                     :base "UBTC"
                                                     :quote "USDC"}
                                                    {:instrument-id "perp:xyz:@221"
                                                     :market-type :perp
                                                     :coin "@221"
                                                     :dex "xyz"
                                                     :hip3? true
                                                     :symbol "GOLD-USDC"
                                                     :base "GOLD"
                                                     :quote "USDC"}]
                                         :constraints {:long-only? false}}}}})
        strings (set (collect-strings view-node))]
    (is (contains? strings "UBTC/USDC"))
    (is (contains? strings "UBTC"))
    (is (contains? strings "GOLD-USDC"))
    (is (contains? strings "GOLD"))
    (is (not (contains? strings "@107")))
    (is (not (contains? strings "@221")))))

(deftest portfolio-optimizer-search-results-prefer-symbols-for-raw-spot-assets-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio-ui {:optimizer {:universe-search-query "hype"}}
                    :portfolio {:optimizer {:draft {:universe []
                                                     :constraints {:long-only? false}}}}
                    :asset-selector
                    {:markets [{:key "perp:HYPE"
                                :market-type :perp
                                :coin "HYPE"
                                :symbol "HYPE-USDC"
                                :name "Hyperliquid"}
                               {:key "spot:@107"
                                :market-type :spot
                                :coin "@107"
                                :symbol "HYPE/USDC"
                                :base "HYPE"
                                :quote "USDC"}
                               {:key "spot:@232"
                                :market-type :spot
                                :coin "@232"
                                :symbol "HYPE/USDH"
                                :base "HYPE"
                                :quote "USDH"}]
                     :market-by-key {"spot:@107" {:key "spot:@107"
                                                  :market-type :spot
                                                  :coin "@107"
                                                  :symbol "HYPE/USDC"
                                                  :base "HYPE"
                                                  :quote "USDC"}}}})
        strings (set (collect-strings view-node))]
    (is (str/includes? (rendered-text view-node) "HYPE-USDC"))
    (is (str/includes? (rendered-text view-node) "HYPE/USDC"))
    (is (str/includes? (rendered-text view-node) "HYPE/USDH"))
    ;; The matched substring is wrapped for highlighting, so "Hyperliquid"
    ;; renders as "Hype" + "rliquid" for this query.
    (is (str/includes? (rendered-text view-node) "Hyperliquid"))
    (is (not (contains? strings "@107")))
    (is (not (contains? strings "@232")))))

(deftest portfolio-optimizer-workspace-blocks-run-when-retained-history-misses-assets-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}
                                                    {:instrument-id "perp:ETH"
                                                     :market-type :perp
                                                     :coin "ETH"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true}}
                                 :history-data {:candle-history-by-coin
                                                {"BTC" [{:time 1000 :close "100"}
                                                        {:time 2000 :close "110"}]}
                                                :funding-history-by-coin {}}
                                 :runtime {:as-of-ms 2500
                                           :stale-after-ms 5000}}}})
        run-button (node-by-role view-node "portfolio-optimizer-run-draft")
        strings (set (collect-strings view-node))]
    (is (= false (get-in run-button [1 :disabled])))
    (is (contains? strings "History is incomplete for this universe. Run Optimization retries anything still missing."))
    (is (contains? strings "missing-candle-history"))))

(deftest portfolio-optimizer-universe-empty-holdings-states-why-test
  ;; The account snapshot arrived with nothing importable: the empty state says
  ;; so instead of repeating the "holdings load automatically" promise — and so
  ;; "Load my holdings" is never a silent no-op.
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :webdata2 {:clearinghouseState
                               {:marginSummary {:accountValue "1000"}
                                :assetPositions []}}
                    :portfolio {:optimizer {:draft nil}}})
        note (node-by-role view-node "portfolio-optimizer-universe-holdings-empty")]
    (is (some? note))
    (is (some #(= "No open positions to import for this account." %)
              (collect-strings note)))))

(deftest portfolio-optimizer-universe-empty-before-snapshot-keeps-promise-copy-test
  ;; Before the account snapshot arrives the auto-load promise is still true, so
  ;; the "nothing to import" note must NOT render.
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer {:draft nil}}})]
    (is (nil? (node-by-role view-node
                            "portfolio-optimizer-universe-holdings-empty")))))
