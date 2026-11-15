(ns hyperopen.views.portfolio.optimize.execution-tab-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
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

(defn- node-text
  [node]
  (apply str (collect-strings node)))

(def solved-result
  (fixtures/sample-solved-result
   {:instrument-ids ["perp:BTC"]
    :current-weights [0.1]
    :target-weights [0.2]
    :target-weights-by-instrument {"perp:BTC" 0.2}
    :current-weights-by-instrument {"perp:BTC" 0.1}
    :expected-return 0.12
    :volatility 0.24
    :diagnostics {:gross-exposure 0.2
                  :net-exposure 0.2
                  :effective-n 1
                  :turnover 0.1}
    :rebalance-preview
    {:status :ready
     :capital-usd 10000
     :summary {:ready-count 1
               :blocked-count 0
               :gross-trade-notional-usd 1000}
     :rows [{:instrument-id "perp:BTC"
             :instrument-type :perp
             :status :ready
             :side :buy
             :quantity 0.25
             :delta-notional-usd 1000}]}}))

(def ^:private current-run-signature
  {:scenario-id "scn_01" :input-signature "sig-current"})

(defn- scenario-view
  "Renders the optimizer scenario surface at the given results-tab + optimizer state. The run is
  current by default (matching run-state/last-run signature); override :draft with a dirty
  metadata map to exercise the stale path."
  [results-tab optimizer]
  (portfolio-view/portfolio-view
   {:router {:path "/portfolio/optimize/scn_01"}
    :portfolio-ui {:optimizer {:results-tab results-tab}}
    :portfolio {:optimizer
                (merge {:active-scenario {:loaded-id "scn_01" :status :computed}
                        :draft {:id "scn_01"}
                        :run-state {:status :succeeded
                                    :request-signature current-run-signature}
                        :last-successful-run {:result solved-result
                                              :request-signature current-run-signature}}
                       optimizer)}}))

(def ^:private staged-plan
  {:status :partially-blocked
   :execution-disabled? false
   :summary {:ready-count 1 :blocked-count 1 :skipped-count 0
             :gross-ready-notional-usd 1000
             :estimated-fees-usd 10 :estimated-slippage-usd 5
             :margin {:after-utilization 0.42 :after-gross-leverage 1.85 :before-gross-leverage 1.79 :after-venue-leverage 1.4 :free-margin-usd 8600 :capital-usd 10000 :warning :none}}
   :rows [{:row-id "perp:BTC" :instrument-id "perp:BTC" :instrument-type :perp
           :status :ready :side :buy :quantity 0.25 :order-type :market
           :delta-notional-usd 1000
           :cost {:source :snapshot :slippage-bps 5.0 :estimated-slippage-usd 5}}
          {:row-id "spot:PURR" :instrument-id "spot:PURR" :instrument-type :spot
           :status :blocked :side :sell :reason :spot-submit-unsupported
           :delta-notional-usd -500}]})

(deftest execution-tab-staged-buys-sells-exclude-blocked-rows-test
  (let [plan {:status :partially-blocked :execution-disabled? false
              :summary {:ready-count 1 :blocked-count 1
                        :gross-ready-notional-usd 300
                        :margin {:after-utilization 0.42 :after-gross-leverage 1.85 :before-gross-leverage 1.79 :free-margin-usd 8600 :capital-usd 10000 :warning :none}}
              :rows [{:row-id "perp:BTC" :instrument-id "perp:BTC" :instrument-type :perp
                      :status :ready :side :buy :quantity 3 :delta-notional-usd 300
                      :cost {:source :snapshot :slippage-bps 0 :estimated-slippage-usd 0}}
                     {:row-id "spot:PURR" :instrument-id "spot:PURR" :instrument-type :spot
                      :status :blocked :side :sell :reason :spot-submit-unsupported
                      :delta-notional-usd -50000}]}
        view-node (scenario-view :execution
                                 {:execution {:status :idle :history []}
                                  :execution-modal {:open? true :phase :staged :plan plan}})
        buys (node-text (node-by-role view-node "portfolio-optimizer-execution-kpi-buys"))
        sells (node-text (node-by-role view-node "portfolio-optimizer-execution-kpi-sells"))]
    (is (str/includes? buys "300"))
    (is (not (str/includes? sells "50,000")))
    (is (str/includes? sells "$0"))))

(deftest execution-tab-staged-buys-sells-exclude-skipped-rows-test
  (let [plan {:status :ready :execution-disabled? false
              :summary {:ready-count 1 :blocked-count 0 :skipped-count 1
                        :gross-ready-notional-usd 300
                        :margin {:after-utilization 0.42 :after-gross-leverage 1.85 :before-gross-leverage 1.79 :free-margin-usd 8600 :capital-usd 10000 :warning :none}}
              :rows [{:row-id "perp:BTC" :instrument-id "perp:BTC" :instrument-type :perp
                      :status :ready :side :buy :quantity 3 :delta-notional-usd 300
                      :cost {:source :snapshot :slippage-bps 0 :estimated-slippage-usd 0}}
                     {:row-id "perp:ZEC" :instrument-id "perp:ZEC" :instrument-type :perp
                      :status :skipped :side :buy :reason :within-tolerance :tolerance 0.03
                      :quantity 48 :delta-notional-usd 22000}]}
        view-node (scenario-view :execution
                                 {:execution {:status :idle :history []}
                                  :execution-modal {:open? true :phase :staged :plan plan}})
        buys (node-text (node-by-role view-node "portfolio-optimizer-execution-kpi-buys"))]
    (is (str/includes? buys "300"))
    (is (not (str/includes? buys "22,")))))

(deftest execution-order-table-skipped-row-explains-why-test
  (let [plan (update staged-plan :rows conj
                     {:row-id "perp:ZEC" :instrument-id "perp:ZEC" :instrument-type :perp
                      :status :skipped :side :buy :reason :within-tolerance :tolerance 0.03
                      :quantity 48 :delta-notional-usd 22000})
        view-node (scenario-view :execution
                                 {:execution {:status :idle :history []}
                                  :execution-modal {:open? true :phase :staged :plan plan}})
        row (node-by-role view-node "portfolio-optimizer-execution-order-row-perp-ZEC")]
    (is (some? row))
    (is (str/includes? (node-text row)
                       "skipped · within 3 pp band — no trade needed"))))

(deftest execution-tab-staged-renders-plan-and-arm-action-test
  (let [view-node (scenario-view :execution
                                 {:execution {:status :idle :history []}
                                  :execution-modal {:open? true :phase :staged :plan staged-plan}})
        strings (set (collect-strings view-node))
        arm (node-by-role view-node "portfolio-optimizer-execution-arm")]
    (is (some? (node-by-role view-node "portfolio-optimizer-execution-tab")))
    (is (some? (node-by-role view-node "portfolio-optimizer-execution-order-row-perp-BTC")))
    (is (some? (node-by-role view-node "portfolio-optimizer-execution-order-row-spot-PURR")))
    (is (some? arm))
    (is (= [[:actions/set-portfolio-optimizer-execution-phase :armed]] (click-actions arm)))
    (is (some #(str/includes? % "snapshot") strings))
    (is (contains? strings "Gross leverage after"))
    (is (some #(str/includes? % "venue ≈1.40x") strings))
    (is (contains? strings "spot-submit-unsupported"))))

(deftest execution-tab-stale-recommendation-disables-arm-test
  (let [view-node (scenario-view :execution
                                 {:draft {:id "scn_01" :metadata {:dirty? true}}
                                  :execution {:status :idle :history []}
                                  :execution-modal {:open? true :phase :staged :plan staged-plan}})
        arm (node-by-role view-node "portfolio-optimizer-execution-arm")
        stale-notice (node-by-role view-node "portfolio-optimizer-execution-stale")]
    (is (some? arm))
    (is (true? (get-in arm [1 :disabled])))
    (is (nil? (click-actions arm)))
    (is (some? stale-notice))
    (is (str/includes? (node-text stale-notice) "re-run"))))

(deftest execution-tab-orders-largest-first-exact-notional-test
  (let [plan {:status :ready :execution-disabled? false
              :summary {:ready-count 2 :blocked-count 0
                        :gross-ready-notional-usd 2300
                        :margin {:after-utilization 0.3 :after-gross-leverage 1.85 :before-gross-leverage 1.79 :free-margin-usd 8600 :capital-usd 10000 :warning :none}}
              :rows [{:row-id "perp:SMALL" :instrument-id "perp:SMALL" :instrument-type :perp
                      :status :ready :side :buy :quantity 1 :order-type :market
                      :delta-notional-usd 300
                      :cost {:source :snapshot :slippage-bps 5 :estimated-slippage-usd 1}}
                     {:row-id "perp:BIG" :instrument-id "perp:BIG" :instrument-type :perp
                      :status :ready :side :sell :quantity 1 :order-type :market
                      :delta-notional-usd -2345
                      :cost {:source :snapshot :slippage-bps 5 :estimated-slippage-usd 1}}]}
        view-node (scenario-view :execution
                                 {:execution {:status :idle :history []}
                                  :execution-modal {:open? true :phase :staged :plan plan}})
        order-list (node-by-role view-node "portfolio-optimizer-execution-order-list")
        big-row (node-by-role view-node "portfolio-optimizer-execution-order-row-perp-BIG")
        strs (vec (collect-strings order-list))
        idx (fn [s] (first (keep-indexed (fn [i x] (when (= x s) i)) strs)))]
    (is (str/includes? (node-text big-row) "$2,345"))
    (is (not (str/includes? (node-text big-row) "$2.3k")))
    (is (< (idx "BIG") (idx "SMALL")))))

(deftest execution-tab-slip-is-type-aware-test
  (let [market-view (scenario-view :execution
                                   {:execution {:status :idle :history []}
                                    :execution-modal {:open? true :phase :staged :plan staged-plan}})
        market-row (node-by-role market-view "portfolio-optimizer-execution-order-row-perp-BTC")
        limit-view (scenario-view :execution
                                  {:execution {:status :idle :history []}
                                   :execution-modal {:open? true :phase :staged :plan staged-plan
                                                     :overrides {"perp:BTC" :limit}}})
        limit-row (node-by-role limit-view "portfolio-optimizer-execution-order-row-perp-BTC")]
    (is (str/includes? (node-text market-row) "bp"))
    (is (not (str/includes? (node-text market-row) "rests")))
    (is (str/includes? (node-text limit-row) "rests"))
    (is (not (str/includes? (node-text limit-row) "bp")))))

(deftest execution-tab-cost-kpis-react-to-order-type-test
  (let [plan (assoc-in staged-plan [:rows 0 :cost]
                       {:source :snapshot :slippage-bps 5.0
                        :estimated-slippage-usd 12.0
                        :estimated-fee-usd 6.0
                        :maker-fee-usd 2.0})
        view (fn [overrides]
               (scenario-view :execution
                              {:execution {:status :idle :history []}
                               :execution-modal (cond-> {:open? true :phase :staged :plan plan}
                                                  overrides (assoc :overrides overrides))}))
        kpi (fn [v role] (node-text (node-by-role v (str "portfolio-optimizer-execution-kpi-" role))))
        market (view nil)
        limit (view {"perp:BTC" :limit})]
    (is (str/includes? (kpi market "price-cost") "12"))
    (is (str/includes? (kpi market "fees") "6"))
    (is (str/includes? (kpi market "all-in") "18"))
    (is (str/includes? (kpi limit "price-cost") "$0"))
    (is (not (str/includes? (kpi limit "price-cost") "12")))
    (is (str/includes? (kpi limit "fees") "2"))
    (is (str/includes? (kpi limit "all-in") "2"))
    (is (not (str/includes? (kpi limit "all-in") "18")))))

(deftest execution-tab-twap-override-slices-the-price-cost-kpi-test
  (let [plan (-> staged-plan
                 (assoc-in [:rows 0 :delta-notional-usd] 100000)
                 (assoc-in [:rows 0 :cost]
                           {:source :snapshot :slippage-bps 102 :estimated-slippage-usd 1020
                            :spread-bps 2 :spread-usd 20 :impact-bps 100 :impact-usd 1000
                            :notional-usd 100000
                            :fee-bps 4 :estimated-fee-usd 40
                            :maker-fee-bps 1 :maker-fee-usd 10}))
        view (fn [overrides]
               (scenario-view :execution
                              {:execution {:status :idle :history []}
                               :execution-modal {:open? true :phase :staged :plan plan
                                                 :overrides overrides}}))
        kpi (fn [v role] (node-text (node-by-role v (str "portfolio-optimizer-execution-kpi-" role))))
        market (view {"perp:BTC" :market})
        twap (view {"perp:BTC" :twap})]
    (is (str/includes? (kpi market "price-cost") "1,020"))
    (is (str/includes? (kpi market "all-in") "1,060"))
    (is (str/includes? (kpi twap "price-cost") "190.73"))
    (is (not (str/includes? (kpi twap "price-cost") "1,020")))
    (is (str/includes? (kpi twap "all-in") "230.73"))))

(deftest execution-tab-depth-floor-estimates-render-lower-bound-marker-test
  (let [plan (assoc-in staged-plan [:rows 0 :cost]
                       {:source :depth-extrapolated :slippage-bps 999.1
                        :estimated-slippage-usd 99.91
                        :spread-bps 10 :spread-usd 1 :impact-bps 989.1 :impact-usd 98.91
                        :notional-usd 1000
                        :depth-status :insufficient-visible-depth
                        :estimate-floor? true :depth-overrun 409 :depth-coverage 0.0024
                        :fee-bps 4 :estimated-fee-usd 0.4
                        :maker-fee-bps 1 :maker-fee-usd 0.1})
        view-node (scenario-view :execution
                                 {:execution {:status :idle :history []}
                                  :execution-modal {:open? true :phase :staged :plan plan
                                                    :overrides {"perp:BTC" :market}}})
        price (node-text (node-by-role view-node "portfolio-optimizer-execution-kpi-price-cost"))
        all-in (node-text (node-by-role view-node "portfolio-optimizer-execution-kpi-all-in"))
        row (node-by-role view-node "portfolio-optimizer-execution-order-row-perp-BTC")]
    (is (str/includes? price "≥"))
    (is (str/includes? all-in "≥"))
    (is (str/includes? (node-text row) "≥"))))

(deftest execution-tab-health-rail-discloses-what-the-costs-stand-on-test
  (let [rail (fn [cost]
               (node-text
                (node-by-role
                 (scenario-view :execution
                                {:execution {:status :idle :history []}
                                 :execution-modal {:open? true :phase :staged
                                                   :plan (assoc-in staged-plan
                                                                   [:rows 0 :cost] cost)}})
                 "portfolio-optimizer-execution-health")))
        flat (rail {:source :fallback-bps :slippage-bps 25 :estimated-slippage-usd 2.5})
        live (rail {:source :snapshot :slippage-bps 5 :estimated-slippage-usd 5
                    :age-ms 4200})
        book (rail {:source :live-orderbook :slippage-bps 5 :estimated-slippage-usd 5})
        untrusted (rail {:source :untrusted-snapshot-fill :slippage-bps 25
                         :estimated-slippage-usd 2.5})]
    (is (str/includes? flat "Book data"))
    (is (str/includes? flat "flat estimate"))
    (is (str/includes? flat "no live book"))
    (is (str/includes? flat "TWAP cannot be sliced"))
    (is (str/includes? live "1 of 1 live"))
    (is (str/includes? live "book 4.2s old"))
    (is (not (str/includes? live "flat estimate")))
    (is (str/includes? book "1 of 1 live"))
    (is (str/includes? untrusted "flat estimate"))))

(deftest execution-tab-row-expansion-shows-cost-breakdown-test
  (let [plan (assoc-in staged-plan [:rows 0 :cost]
                       {:source :snapshot :slippage-bps 25 :estimated-slippage-usd 0.68
                        :spread-bps 18 :spread-usd 0.49 :impact-bps 7 :impact-usd 0.19
                        :fee-bps 4 :estimated-fee-usd 0.23 :maker-fee-bps 1.5 :maker-fee-usd 0.08})
        view-node (scenario-view :execution
                                 {:execution {:status :idle :history []}
                                  :execution-modal {:open? true :phase :staged :plan plan
                                                    :overrides {"perp:BTC" :market}
                                                    :open-row "perp:BTC"}})
        breakdown (node-by-role view-node "portfolio-optimizer-execution-cost-breakdown")
        text (node-text breakdown)]
    (is (some? breakdown))
    (is (str/includes? text "Spread crossing"))
    (is (str/includes? text "Book impact"))
    (is (str/includes? text "Price cost"))
    (is (str/includes? text "All-in"))
    (is (str/includes? text "0.91"))))

(deftest execution-tab-row-expansion-unsplittable-cost-shows-not-separable-note-test
  (let [plan (assoc-in staged-plan [:rows 0 :cost]
                       {:source :untrusted-snapshot-fill :slippage-bps 25
                        :estimated-slippage-usd 0.19
                        :fee-bps 4.5 :estimated-fee-usd 0.03
                        :maker-fee-bps 1.5 :maker-fee-usd 0.01})
        view-node (scenario-view :execution
                                 {:execution {:status :idle :history []}
                                  :execution-modal {:open? true :phase :staged :plan plan
                                                    :overrides {"perp:BTC" :market}
                                                    :open-row "perp:BTC"}})
        breakdown (node-by-role view-node "portfolio-optimizer-execution-cost-breakdown")
        text (node-text breakdown)]
    (is (some? breakdown))
    (is (not (str/includes? text "Spread crossing")))
    (is (str/includes? text "separable"))
    (is (str/includes? text "Price cost"))
    (is (str/includes? text "All-in"))))

(deftest execution-tab-armed-renders-enabled-confirm-test
  (let [view-node (scenario-view :execution
                                 {:execution {:status :idle :history []}
                                  :execution-modal {:open? true :phase :armed :plan staged-plan}})
        confirm (node-by-role view-node "portfolio-optimizer-execution-confirm")]
    (is (some? confirm))
    (is (= false (boolean (get-in confirm [1 :disabled]))))
    (is (= [[:actions/confirm-portfolio-optimizer-execution]] (click-actions confirm)))))

(deftest execution-tab-armed-restates-money-figures-test
  (let [plan (assoc staged-plan :summary {:ready-count 1 :blocked-count 1
                                          :gross-ready-notional-usd 1000
                                          :margin {:after-utilization 0.42 :after-gross-leverage 1.85 :before-gross-leverage 1.79 :free-margin-usd 8600 :capital-usd 10000 :warning :none}})
        view-node (scenario-view :execution
                                 {:execution {:status :idle :history []}
                                  :execution-modal {:open? true :phase :armed :plan plan}})
        figures (node-by-role view-node "portfolio-optimizer-execution-armed-figures")
        confirm (node-by-role view-node "portfolio-optimizer-execution-confirm")
        ftext (node-text figures)]
    (is (some? figures))
    (is (str/includes? ftext "buys"))
    (is (str/includes? ftext "sells"))
    (is (str/includes? ftext "gross"))
    (is (str/includes? ftext "leverage"))
    (is (str/includes? ftext "1.85x"))
    (is (str/includes? (str/join " " (get-in confirm [1 :class])) "optimizer-exec-commit"))))

(deftest execution-tab-running-abort-acknowledges-test
  (let [view-node (scenario-view :execution
                                 {:execution {:status :submitting :history [] :abort-requested? true}
                                  :execution-modal {:open? true :phase :armed :submitting? true
                                                    :plan staged-plan}})
        band (node-by-role view-node "portfolio-optimizer-execution-control-band")
        pause (node-by-role view-node "portfolio-optimizer-execution-pause")]
    (is (= "running" (get-in band [1 :data-phase])))
    (is (str/includes? (node-text band) "Stopping"))
    (is (= true (boolean (get-in pause [1 :disabled]))))
    (is (nil? (get-in pause [1 :on])))))

(deftest execution-tab-discard-requires-confirmation-test
  (let [view-node (scenario-view :execution
                                 {:execution {:status :idle :history []}
                                  :execution-modal {:open? true :phase :staged :plan staged-plan}})
        confirm-wrap (node-by-role view-node "portfolio-optimizer-execution-discard-confirm")
        discard (node-by-role view-node "portfolio-optimizer-execution-discard")]
    (is (some? confirm-wrap) "discard is wrapped in a confirm disclosure")
    (is (some? discard))
    (is (= [[:actions/discard-portfolio-optimizer-execution]] (click-actions discard)))))

(deftest execution-tab-running-hides-confirm-and-shows-progress-test
  (let [view-node (scenario-view :execution
                                 {:execution {:status :submitting :history []}
                                  :execution-modal {:open? true :phase :armed :submitting? true
                                                    :plan staged-plan}})
        band (node-by-role view-node "portfolio-optimizer-execution-control-band")]
    (is (= "running" (get-in band [1 :data-phase])))
    (is (nil? (node-by-role view-node "portfolio-optimizer-execution-confirm")))))

(deftest execution-tab-halted-renders-failed-latest-attempt-test
  (let [view-node (scenario-view :execution
                                 {:execution {:status :partially-executed
                                              :history [{:attempt-id "exec_1000"
                                                         :status :partially-executed
                                                         :rows [{:instrument-id "perp:BTC"
                                                                 :status :failed
                                                                 :side :buy
                                                                 :delta-notional-usd 1000
                                                                 :error {:message "Order must have minimum value of $10. asset=110023"}}]}]}
                                  :execution-modal {:open? true :phase :staged
                                                    :error "Execution halted before all rows submitted."
                                                    :plan staged-plan}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-execution-latest-attempt")))
    (is (= "halted" (get-in (node-by-role view-node "portfolio-optimizer-execution-control-band")
                            [1 :data-phase])))
    (is (contains? strings "Latest attempt"))
    (is (contains? strings "Order must have minimum value of $10. asset=110023"))
    (is (some? (node-by-role view-node "portfolio-optimizer-execution-resume")))))

(deftest execution-tab-partial-when-resting-orders-still-live-test
  (let [view-node (scenario-view :execution
                                 {:execution {:status :partially-executed
                                              :history [{:attempt-id "exec_3000"
                                                         :status :partially-executed
                                                         :rows [{:instrument-id "perp:BTC"
                                                                 :status :failed
                                                                 :side :buy
                                                                 :delta-notional-usd 1000
                                                                 :error {:message "Order must have minimum value of $10. asset=197"}}
                                                                {:instrument-id "perp:ETH"
                                                                 :status :resting
                                                                 :side :sell
                                                                 :delta-notional-usd -500}]}]}
                                  :execution-modal {:open? true :phase :staged
                                                    :plan staged-plan}})
        band (node-by-role view-node "portfolio-optimizer-execution-control-band")
        strings (set (collect-strings view-node))]
    (is (= "partial" (get-in band [1 :data-phase]))
        "live resting rows alongside a rejection drive the partial band, not halted")
    (is (not (contains? strings "Execution halted — 0 filled · 1 resting · 1 failed"))
        "no headline claims the run halted while orders are still working")
    (is (contains? strings "Execution partial — 0 filled · 1 resting · 1 failed"))
    (is (some? (node-by-role view-node "portfolio-optimizer-execution-resume"))
        "the recovery actions stay available while resting orders work")))

(deftest execution-tab-read-only-disables-arm-and-shows-message-test
  (let [message "Spectate Mode is read-only. Stop Spectate Mode to place trades or move funds."
        view-node (scenario-view :execution
                                 {:execution {:status :idle :history []}
                                  :execution-modal {:open? true :phase :staged
                                                    :plan (assoc staged-plan
                                                                 :execution-disabled? true
                                                                 :disabled-message message)}})
        strings (set (collect-strings view-node))
        arm (node-by-role view-node "portfolio-optimizer-execution-arm")]
    (is (some? (node-by-role view-node "portfolio-optimizer-execution-readonly")))
    (is (contains? strings message))
    (is (= true (boolean (get-in arm [1 :disabled]))))))

(deftest execution-tab-resolves-vault-labels-by-name-test
  (let [vault-address "0x6666666666666666666666666666666666666666"
        vault-id (str "vault:" vault-address)
        view-node (scenario-view :execution
                                 {:last-successful-run
                                  {:result (assoc solved-result
                                                  :labels-by-instrument {vault-id "Alpha Yield"})}
                                  :execution {:status :idle :history []}
                                  :execution-modal
                                  {:open? true :phase :staged
                                   :plan {:status :partially-blocked
                                          :summary {:ready-count 0 :blocked-count 1
                                                    :margin {:after-utilization 0.1 :after-gross-leverage 1.85 :before-gross-leverage 1.79 :free-margin-usd 8600 :capital-usd 10000 :warning :none}}
                                          :rows [{:row-id vault-id :instrument-id vault-id
                                                  :status :blocked :side :sell
                                                  :reason :vault-submit-unsupported
                                                  :delta-notional-usd -400}]}}})
        tab (node-by-role view-node "portfolio-optimizer-execution-tab")
        text (node-text tab)]
    (is (str/includes? text "Alpha Yield"))
    (is (not (str/includes? text vault-id)))
    (is (not (str/includes? text vault-address)))))

(deftest execution-tab-resting-renders-open-state-not-filled-test
  (let [view-node (scenario-view :execution
                                 {:execution {:status :resting
                                              :history [{:attempt-id "exec_2000"
                                                         :status :resting
                                                         :rows [{:instrument-id "perp:BTC"
                                                                 :status :resting
                                                                 :side :buy
                                                                 :delta-notional-usd 1000}]}]}
                                  :execution-modal {:open? true :phase :staged
                                                    :plan staged-plan}})
        strings (set (collect-strings view-node))
        band (node-by-role view-node "portfolio-optimizer-execution-control-band")
        orders-kpi (node-by-role view-node "portfolio-optimizer-execution-kpi-orders")]
    (is (= "resting" (get-in band [1 :data-phase]))
        "a resting run drives the resting control band, not done/halted")
    (is (contains? strings "open")
        "the resting order renders its state as \"open\"")
    (is (not (contains? strings "filled"))
        "no order-state cell claims \"filled\" when nothing filled")
    (is (str/includes? (node-text orders-kpi) "0 / 1")
        "Orders filled reports 0 of 1 — the resting order is not counted as filled")
    (is (str/includes? (node-text orders-kpi) "resting on book")
        "the resting order is surfaced in the orders KPI")))
