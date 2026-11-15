(ns hyperopen.views.portfolio.optimize.inputs-tab-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.inputs-tab :as inputs-tab]))

(defn- node-children
  [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- node-text
  [node]
  (apply str (collect-strings node)))

(deftest inputs-tab-renders-vault-names-in-universe-audit-test
  (let [vault-address "0x5555555555555555555555555555555555555555"
        vault-id (str "vault:" vault-address)
        view-node (inputs-tab/inputs-tab
                   {:portfolio {:optimizer
                                {:active-scenario {:loaded-id "scn_inputs"
                                                   :status :saved}
                                 :draft {:id "scn_inputs"
                                         :universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}
                                                    {:instrument-id vault-id
                                                     :market-type :vault
                                                     :coin vault-id
                                                     :vault-address vault-address
                                                     :name "Alpha Yield"}]
                                         :objective {:kind :target-volatility}
                                         :return-model {:kind :black-litterman
                                                        :views []}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true}
                                         :execution-assumptions {}}}}})
        text (node-text view-node)]
    (is (str/includes? text "Alpha Yield"))
    (is (not (str/includes? text vault-id)))
    (is (not (str/includes? text vault-address)))))

(deftest inputs-tab-renders-history-assumptions-summary-test
  (let [view-node (inputs-tab/inputs-tab
                   {:portfolio {:optimizer
                                {:active-scenario {:loaded-id "scn_assumptions"
                                                   :status :saved}
                                 :draft {:id "scn_assumptions"
                                         :universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}
                                                    {:instrument-id "perp:NEW"
                                                     :market-type :perp
                                                     :coin "NEW"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true}
                                         :execution-assumptions {}
                                         :history-assumptions
                                         {"perp:NEW" {:behavior :conservative
                                                      :expected-return 0.25
                                                      :volatility 0.9
                                                      :max-weight 0.03
                                                      :correlation-floor 0.75}}}}}})
        text (node-text view-node)]
    (is (str/includes? text "History Assumptions Used"))
    (is (str/includes? text "NEW"))
    (is (str/includes? text "Conservative"))))

(deftest inputs-tab-discloses-proxy-model-from-run-diagnostics-test
  (let [draft {:id "scn_proxy"
               :universe [{:instrument-id "perp:BTC" :market-type :perp :coin "BTC"}
                          {:instrument-id "perp:ETH" :market-type :perp :coin "ETH"}
                          {:instrument-id "perp:NEW" :market-type :perp :coin "NEW"}]
               :objective {:kind :minimum-variance}
               :return-model {:kind :historical-mean}
               :risk-model {:kind :diagonal-shrink}
               :constraints {:long-only? true}
               :execution-assumptions {}
               :history-assumptions
               {"perp:NEW" {:behavior :proxy
                            :expected-return 0.0
                            :volatility 0.8
                            :max-weight 0.05
                            :proxy {:instrument-ids ["perp:ETH" "perp:BTC"]
                                    :relationship-strength :medium
                                    :prior-weights nil}}}}
        run-result {:status :solved
                    :risk-estimation
                    {:history-assumptions
                     {"perp:NEW" {:behavior :proxy
                                  :proxy-instrument-ids ["perp:ETH" "perp:BTC"]
                                  :prior-source :equal
                                  :prior-weights {"perp:ETH" 0.5 "perp:BTC" 0.5}
                                  :regression-status :estimated
                                  :regression-beta {"perp:ETH" 0.68 "perp:BTC" 0.32}
                                  :final-beta {"perp:ETH" 0.6 "perp:BTC" 0.4}
                                  :r2 0.41
                                  :sample-count 248
                                  :confidence-q 0.54
                                  :effective-modeled-volatility 0.8}}}}
        view-node (inputs-tab/inputs-tab
                   {:portfolio {:optimizer
                                {:active-scenario {:loaded-id "scn_proxy"
                                                   :status :saved}
                                 :draft draft
                                 :last-successful-run {:result run-result}}}})
        text (node-text view-node)]
    (is (str/includes? text "Prior: ETH 50% / BTC 50%"))
    (is (str/includes? text "equal-weight fallback"))
    (is (str/includes? text "Regression estimate: ETH 68% / BTC 32%"))
    (is (str/includes? text "248 observations"))
    (is (str/includes? text "Final modeled basket: ETH 60% / BTC 40%"))
    (is (str/includes? text "confidence q 54%"))
    (is (str/includes? text "Effective modeled volatility: 80%"))
    (is (str/includes? text "R² is never used as a direct weight"))))

(deftest inputs-tab-omits-proxy-model-when-run-diagnostics-mismatch-test
  (let [draft {:id "scn_proxy_stale"
               :universe [{:instrument-id "perp:BTC" :market-type :perp :coin "BTC"}
                          {:instrument-id "perp:NEW" :market-type :perp :coin "NEW"}]
               :objective {:kind :minimum-variance}
               :return-model {:kind :historical-mean}
               :risk-model {:kind :diagonal-shrink}
               :constraints {:long-only? true}
               :execution-assumptions {}
               :history-assumptions
               {"perp:NEW" {:behavior :proxy
                            :expected-return 0.0
                            :volatility 0.8
                            :max-weight 0.05
                            :proxy {:instrument-ids ["perp:BTC"]
                                    :relationship-strength :medium
                                    :prior-weights nil}}}}
        run-result {:status :solved
                    :risk-estimation
                    {:history-assumptions
                     {"perp:NEW" {:behavior :proxy
                                  :proxy-instrument-ids ["perp:ETH" "perp:BTC"]
                                  :prior-source :equal
                                  :prior-weights {"perp:ETH" 0.5 "perp:BTC" 0.5}
                                  :regression-status :estimated
                                  :final-beta {"perp:ETH" 0.6 "perp:BTC" 0.4}
                                  :confidence-q 0.54}}}}
        view-node (inputs-tab/inputs-tab
                   {:portfolio {:optimizer
                                {:active-scenario {:loaded-id "scn_proxy_stale"
                                                   :status :saved}
                                 :draft draft
                                 :last-successful-run {:result run-result}}}})
        text (node-text view-node)]
    (is (str/includes? text "History Assumptions Used"))
    (is (not (str/includes? text "Final modeled basket:"))
        "The stale run basket (built on a different proxy set) is not shown.")))
