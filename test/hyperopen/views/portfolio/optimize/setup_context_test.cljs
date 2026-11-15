(ns hyperopen.views.portfolio.optimize.setup-context-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.views.portfolio.optimize.setup-context :as setup-context]
            [hyperopen.views.portfolio.optimize.test-support :as ts]))

(def ^:private btc {:instrument-id "perp:BTC" :market-type :perp :coin "BTC"})

(defn- thin-history-instrument
  [n]
  {:instrument-id (str "perp:NEW" n) :market-type :perp :coin (str "NEW" n)})

(defn- proxy-entry
  []
  {:behavior :proxy
   :expected-return 0.0
   :volatility 0.8
   :max-weight 0.05
   :proxy {:instrument-ids ["perp:BTC"]
           :relationship-strength :medium
           :prior-weights nil}})

(def ^:private thin-instruments (mapv thin-history-instrument (range 1 9)))
(def ^:private universe (into [btc] thin-instruments))

(defn- context-rail-node
  []
  (setup-context/context-rail
   {:state {}
    :draft {:universe universe
            :objective {:kind :minimum-variance}
            :constraints {:max-asset-weight 0.5}
            :history-assumptions (into {}
                                       (map (fn [{:keys [instrument-id]}]
                                              [instrument-id (proxy-entry)]))
                                       thin-instruments)}
    :readiness {:request {:requested-universe universe
                          :universe [btc]
                          :objective {:kind :minimum-variance}
                          :history {:eligible-instruments [btc]}}
                :blocking-warnings []
                :warnings []}
    :snapshot {:snapshot-loaded? true}
    :preview-snapshot {:capital-ready? true :execution-ready? true :account {}}
    :run-state {:status :idle}
    :optimization-progress {:status :idle}
    :history-load-state {:status :succeeded :request-signature {:universe universe}}
    :last-successful-run {}
    :current-result? false
    :result-path nil}))

(def ^:private new-perp
  {:instrument-id "perp:NEW" :market-type :perp :coin "NEW"})

(def ^:private recommendation-discovery
  {:backend-id-by-local-id {"perp:NEW" "hl:perp:NEW"
                            "perp:BTC" "hl:perp:BTC"}
   :instruments-by-backend-id
   {"hl:perp:NEW" {:instrument-id "hl:perp:NEW"
                   :aliases {:hyperopen-market-key "perp:NEW"}
                   :history {:status :available}
                   :default-assumption {:approach "conservative"
                                        :members []
                                        :rationale "No defensible basket."}}
    "hl:perp:BTC" {:instrument-id "hl:perp:BTC"
                   :aliases {:hyperopen-market-key "perp:BTC"}
                   :display-symbol "BTC"
                   :history {:status :available}}}})

(defn- recommendation-rail-node
  []
  (let [universe* [btc new-perp]]
    (setup-context/context-rail
     {:state (assoc-in {} contracts/history-discovery-path recommendation-discovery)
      :draft {:universe universe*
              :objective {:kind :minimum-variance}
              :constraints {:max-asset-weight 0.5}
              :history-assumptions {}}
      :readiness {:request {:requested-universe universe*
                            :universe universe*
                            :objective {:kind :minimum-variance}
                            :history {:eligible-instruments universe*
                                      :raw-price-series-by-instrument
                                      {"perp:BTC" (vec (repeat 1079 {:close 1}))
                                       "perp:NEW" (vec (repeat 40 {:close 1}))}}}
                  :blocking-warnings []
                  :warnings []}
      :snapshot {:snapshot-loaded? true}
      :preview-snapshot {:capital-ready? true :execution-ready? true :account {}}
      :run-state {:status :idle}
      :optimization-progress {:status :idle}
      :history-load-state {:status :succeeded
                           :request-signature {:universe universe*}}
      :last-successful-run {}
      :current-result? false
      :result-path nil})))

(deftest history-assumptions-rail-offers-apply-all-recommended-test
  (let [node (recommendation-rail-node)
        button (ts/node-by-role
                node
                "portfolio-optimizer-history-assumptions-rail-apply-all-recommended")]
    (is (some? button))
    (is (= [[:actions/apply-portfolio-optimizer-recommended-history-assumptions]]
           (get-in button [1 :on :click])))
    (is (contains? (set (ts/collect-strings button)) "Apply all recommended (1)"))))

(deftest history-assumptions-rail-hides-apply-all-without-recommendations-test
  (is (nil? (ts/node-by-role
             (context-rail-node)
             "portfolio-optimizer-history-assumptions-rail-apply-all-recommended"))))

(deftest history-assumptions-rail-rows-are-height-capped-and-scrollable-test
  (let [node (context-rail-node)
        rows (ts/node-by-role node "portfolio-optimizer-history-assumptions-rail-rows")
        classes (set (get-in rows [1 :class]))
        strings (set (ts/collect-strings rows))]
    (is (some? (ts/node-by-role node "portfolio-optimizer-history-assumptions-rail")))
    (is (some? rows))
    (is (contains? classes "optimizer-history-assumptions-rows"))
    (is (contains? classes "overflow-y-auto"))
    (is (contains? classes "min-h-0"))
    (doseq [{:keys [coin]} thin-instruments]
      (is (contains? strings coin)
          (str coin " renders inside the capped rows wrapper.")))))
