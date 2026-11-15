(ns hyperopen.views.portfolio.optimize.execution-exit-affordance-test
  "The Execution tab's sell-excluded-holdings affordance: a skipped
  excluded-from-optimization row offers \"Sell instead\" (staging an explicit
  sell-to-zero), several offer a bulk control, and an exit-marked sell row carries the
  exit chip + a revert-to-hold control in its editor. Pre-run only."
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

(def ^:private current-run-signature
  {:scenario-id "scn_01" :input-signature "sig-current"})

(def ^:private solved-result
  (fixtures/sample-solved-result
   {:instrument-ids ["perp:BTC"]
    :current-weights [0.1]
    :target-weights [0.2]
    :target-weights-by-instrument {"perp:BTC" 0.2}
    :current-weights-by-instrument {"perp:BTC" 0.1}}))

(defn- scenario-view
  [optimizer]
  (portfolio-view/portfolio-view
   {:router {:path "/portfolio/optimize/scn_01"}
    :portfolio-ui {:optimizer {:results-tab :execution}}
    :portfolio {:optimizer
                (merge {:active-scenario {:loaded-id "scn_01" :status :computed}
                        :draft {:id "scn_01"}
                        :run-state {:status :succeeded
                                    :request-signature current-run-signature}
                        :last-successful-run {:result solved-result
                                              :request-signature current-run-signature}}
                       optimizer)}}))

(def ^:private ready-btc-row
  {:row-id "perp:BTC" :instrument-id "perp:BTC" :instrument-type :perp
   :status :ready :side :buy :quantity 0.25 :order-type :market
   :delta-notional-usd 1000
   :cost {:source :snapshot :slippage-bps 5.0 :estimated-slippage-usd 5}})

(defn- held-out-row
  [instrument-id]
  {:row-id instrument-id :instrument-id instrument-id :instrument-type :perp
   :status :skipped :reason :excluded-from-optimization :side :none
   :quantity nil :delta-notional-usd 0})

(def ^:private plan-with-held-out-rows
  {:status :ready :execution-disabled? false
   :summary {:ready-count 1 :blocked-count 0 :skipped-count 2
             :gross-ready-notional-usd 1000
             :margin {:after-utilization 0.42 :after-gross-leverage 1.85
                      :before-gross-leverage 1.79 :free-margin-usd 8600
                      :capital-usd 10000 :warning :none}}
   :rows [ready-btc-row
          (held-out-row "perp:ENS")
          (held-out-row "perp:WLD")]})

(deftest skipped-held-out-rows-offer-sell-instead-test
  (let [view-node (scenario-view
                   {:execution {:status :idle :history []}
                    :execution-modal {:open? true :phase :staged
                                      :exit-instrument-ids #{}
                                      :plan plan-with-held-out-rows}})
        skipped (node-by-role view-node "portfolio-optimizer-execution-skipped")
        sell-ens (node-by-role view-node "portfolio-optimizer-execution-exit-perp-ENS")
        sell-all (node-by-role view-node "portfolio-optimizer-execution-exit-all")]
    (is (some? skipped))
    (is (str/includes? (node-text skipped) "2 can be closed instead"))
    (is (some? sell-ens))
    (is (= [[:actions/set-portfolio-optimizer-execution-exit ["perp:ENS"] true]]
           (click-actions sell-ens)))
    (is (some? sell-all))
    (is (= [[:actions/set-portfolio-optimizer-execution-exit
             ["perp:ENS" "perp:WLD"] true]]
           (click-actions sell-all)))))

(deftest auto-exit-setting-strip-renders-and-toggles-test
  (let [held-view (scenario-view
                   {:execution {:status :idle :history []}
                    :execution-modal {:open? true :phase :staged
                                      :exit-instrument-ids #{}
                                      :plan plan-with-held-out-rows}})
        strip (node-by-role held-view "portfolio-optimizer-execution-auto-exit")
        checkbox (find-first-node strip #(= "checkbox" (get-in % [1 :type])))]
    (is (some? strip))
    (is (str/includes? (node-text strip) "Close perp positions removed from the allocation"))
    (is (true? (get-in checkbox [1 :checked])))
    (is (= [[:actions/set-portfolio-optimizer-execution-auto-exit false]]
           (get-in checkbox [1 :on :change])))
    (let [off-view (portfolio-view/portfolio-view
                    {:router {:path "/portfolio/optimize/scn_01"}
                     :trading-settings {:optimizer-auto-exit-excluded? false}
                     :portfolio-ui {:optimizer {:results-tab :execution}}
                     :portfolio {:optimizer
                                 {:active-scenario {:loaded-id "scn_01" :status :computed}
                                  :draft {:id "scn_01"}
                                  :run-state {:status :succeeded
                                              :request-signature current-run-signature}
                                  :last-successful-run {:result solved-result
                                                        :request-signature current-run-signature}
                                  :execution {:status :idle :history []}
                                  :execution-modal {:open? true :phase :staged
                                                    :exit-instrument-ids #{}
                                                    :plan plan-with-held-out-rows}}}})
          off-checkbox (find-first-node
                        (node-by-role off-view "portfolio-optimizer-execution-auto-exit")
                        #(= "checkbox" (get-in % [1 :type])))]
      (is (some? off-view))
      (is (false? (get-in off-checkbox [1 :checked])))
      (is (= [[:actions/set-portfolio-optimizer-execution-auto-exit true]]
             (get-in off-checkbox [1 :on :change]))))))

(deftest exit-marked-row-shows-chip-and-revert-test
  (let [exit-row {:row-id "perp:ENS" :instrument-id "perp:ENS" :instrument-type :perp
                  :status :ready :side :sell :quantity 10 :order-type :market
                  :delta-notional-usd -200
                  :cost {:source :snapshot :slippage-bps 5.0 :estimated-slippage-usd 1}}
        plan (assoc plan-with-held-out-rows
                    :rows [ready-btc-row exit-row (held-out-row "perp:WLD")])
        view-node (scenario-view
                   {:execution {:status :idle :history []}
                    :execution-modal {:open? true :phase :staged
                                      :exit-instrument-ids #{"perp:ENS"}
                                      :open-row "perp:ENS"
                                      :plan plan}})
        ens-row (node-by-role view-node
                              "portfolio-optimizer-execution-order-row-perp-ENS")
        revert (node-by-role view-node "portfolio-optimizer-execution-exit-revert")]
    (is (some? ens-row))
    (is (str/includes? (node-text ens-row) "exit"))
    (is (some? revert))
    (is (= [[:actions/set-portfolio-optimizer-execution-exit ["perp:ENS"] false]]
           (click-actions revert)))))

(deftest exit-affordance-absent-after-terminal-run-test
  (let [view-node (scenario-view
                   {:execution {:status :executed :history []}
                    :execution-modal {:open? true :phase :staged
                                      :exit-instrument-ids #{}
                                      :plan plan-with-held-out-rows}})]
    (is (nil? (node-by-role view-node
                            "portfolio-optimizer-execution-exit-perp-ENS")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-execution-exit-all")))))
