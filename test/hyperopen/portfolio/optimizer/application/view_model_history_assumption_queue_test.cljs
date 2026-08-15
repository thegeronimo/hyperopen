(ns hyperopen.portfolio.optimizer.application.view-model-history-assumption-queue-test
  "Queue projection boundary coverage for the History-assumptions section
  (designer restructure 2026-08-14, option 1c): the fixed rail, the one active
  question, the recommendation-first model panel, the single folded detail
  block, and the prev/skip/advance footer."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.view-model :as view-model]))

(def ^:private btc {:instrument-id "perp:BTC" :market-type :perp :coin "BTC"
                    :symbol "BTC-USDC" :name "Bitcoin"})
(def ^:private eth {:instrument-id "perp:ETH" :market-type :perp :coin "ETH"
                    :symbol "ETH-USDC" :name "Ethereum"})
(def ^:private new-perp {:instrument-id "perp:NEW" :market-type :perp :coin "NEW"})
(def ^:private other-perp {:instrument-id "perp:OTHER" :market-type :perp :coin "OTHER"})

(def ^:private percent-formatters
  {:percent-label (fn [value] (str (js/Math.round (* 100 value)) "%"))})

(defn- loaded
  [universe]
  {:status :succeeded :request-signature {:universe universe}})

(def ^:private proxy-entry
  {:behavior :proxy
   :expected-return 0.0
   :volatility 0.8
   :max-weight 0.05
   :proxy {:instrument-ids ["perp:BTC"]
           :relationship-strength :medium
           :prior-weights nil}})

(defn- queue
  ([state assumptions] (queue state assumptions [btc eth new-perp other-perp]))
  ([state assumptions universe]
   (view-model/history-assumption-queue-model
    state
    {:universe universe
     :objective {:kind :minimum-variance}
     :constraints {:max-asset-weight 0.5}
     :history-assumptions assumptions}
    {:request {:requested-universe universe
               :universe [btc eth]
               :objective {:kind :minimum-variance}
               :history {:eligible-instruments [btc eth]}}
     :blocking-warnings []}
    (loaded universe)
    percent-formatters)))

(deftest queue-defaults-to-the-first-unsettled-asset-test
  (let [model (queue {} {"perp:NEW" proxy-entry})]
    (is (true? (:applicable? model)))
    (is (= ["perp:NEW" "perp:OTHER"] (mapv :instrument-id (:pills model)))
        "The rail carries every asset in the workflow, always.")
    (is (= "perp:NEW" (get-in model [:active :instrument-id]))
        "With nothing requested the queue opens on the first unsettled asset.")
    (is (= [true false] (mapv :active? (:pills model))))
    (is (= "0 of 2 settled" (:settled-label model)))
    (is (= "2 left" (:left-label model)))))

(deftest queue-honours-the-requested-asset-and-falls-back-when-stale-test
  (let [requested (queue {:portfolio-ui {:optimizer {:history-assumption-active "perp:OTHER"}}}
                         {"perp:NEW" proxy-entry})
        stale (queue {:portfolio-ui {:optimizer {:history-assumption-active "perp:GONE"}}}
                     {"perp:NEW" proxy-entry})]
    (is (= "perp:OTHER" (get-in requested [:active :instrument-id])))
    (is (= "perp:NEW" (get-in stale [:active :instrument-id]))
        "A stale pointer needs no cleanup - the queue falls back on its own.")))

(deftest queue-settled-means-accepted-not-merely-engine-complete-test
  ;; An entry the user edited since accepting is engine-complete but no longer
  ;; acknowledged: it still owes an Accept, which is the whole point of the
  ;; queue, so it must not count as settled.
  (let [acknowledged {"perp:NEW" (assoc proxy-entry
                                        :metadata {:source :user :acknowledged? true})}
        complete (queue {} {"perp:NEW" proxy-entry})
        accepted (queue {} acknowledged)
        revisited (queue {:portfolio-ui {:optimizer {:history-assumption-active "perp:NEW"}}}
                         acknowledged)]
    (is (= :configured (get-in complete [:active :status]))
        "The entry is engine-backed either way.")
    (is (false? (get-in complete [:active :settled?])))
    (is (= "Accept assumption" (get-in complete [:active :model :cta])))
    (is (= "0 of 2 settled" (:settled-label complete)))
    (is (= "1 of 2 settled" (:settled-label accepted)))
    (is (= "perp:OTHER" (get-in accepted [:active :instrument-id]))
        "Once accepted, the queue moves on to the next unsettled asset.")
    (is (true? (get-in revisited [:active :settled?]))
        "Coming back to a settled asset is allowed - it just stops asking.")
    (is (= "NEW is modeled - change it?" (get-in revisited [:active :question])))
    (is (= "Keep this" (get-in revisited [:active :model :cta])))))

(deftest queue-model-panel-leads-with-the-final-basket-test
  (let [model (queue {} {"perp:NEW" proxy-entry})
        panel (get-in model [:active :model])]
    (is (= :current (:kind panel)))
    (is (= "Model so far" (:eyebrow panel)))
    (is (= "BTC 100% · medium similarity · 80% vol · 5% max" (:line panel))
        "The lead line is the basket the engine would use, never the prior.")
    (is (= [:actions/apply-portfolio-optimizer-history-assumption "perp:NEW"]
           (:cta-action panel)))
    (is (false? (:cta-disabled? panel)))))

(deftest queue-folds-the-whole-exposure-story-into-one-detail-block-test
  (let [model (queue {} {"perp:NEW" proxy-entry})
        rows (get-in model [:active :detail-rows])
        by-key (into {} (map (juxt :key :value)) rows)]
    (is (= ["Prior basket" "Regression" "Final basket" "Confidence" "Specific risk" "Window"]
           (mapv :key rows)))
    (is (= "Equal-weight fallback - BTC 100%" (get by-key "Prior basket")))
    (is (= "No return overlap with the proxies yet. Using the prior only."
           (get by-key "Regression")))
    (is (= "BTC 100% after confidence shrinkage (q 0%)" (get by-key "Final basket")))
    (is (= "No usable native returns" (get by-key "Window")))))

(deftest queue-footer-advance-carries-the-panel-action-then-moves-on-test
  (let [model (queue {} {"perp:NEW" proxy-entry})]
    (is (= {:instrument-id "perp:OTHER"
            :label "OTHER"
            :action [:actions/set-portfolio-optimizer-history-assumption-active "perp:OTHER"]}
           (:next model)))
    (is (= {:instrument-id "perp:OTHER"
            :label "OTHER"
            :action [:actions/set-portfolio-optimizer-history-assumption-active "perp:OTHER"]}
           (:prev model))
        "Two assets wrap, so prev and next are the same neighbour.")
    (is (= {:label "Accept & next"
            :disabled? false
            :actions [[:actions/apply-portfolio-optimizer-history-assumption "perp:NEW"]
                      [:actions/set-portfolio-optimizer-history-assumption-active "perp:OTHER"]]}
           (:advance model)))))

(deftest queue-advance-is-held-while-the-model-cannot-be-accepted-test
  ;; A mode-less asset has nothing the engine can back yet, so the footer holds
  ;; on the SAME verdict the panel's own CTA uses rather than a looser one.
  (let [model (queue {} {})
        advance (:advance model)]
    (is (= "perp:NEW" (get-in model [:active :instrument-id])))
    (is (true? (get-in model [:active :model :cta-disabled?])))
    (is (true? (:disabled? advance)))))

(deftest queue-single-asset-drops-the-neighbours-test
  (let [model (queue {} {"perp:NEW" proxy-entry} [btc eth new-perp])]
    (is (= 1 (:card-count model)))
    (is (nil? (:prev model)))
    (is (nil? (:next model)))
    (is (= "Accept" (get-in model [:advance :label])))
    (is (= [[:actions/apply-portfolio-optimizer-history-assumption "perp:NEW"]]
           (get-in model [:advance :actions])))))

(deftest queue-rail-pill-tones-rank-done-suggested-unset-test
  (let [model (queue {} {"perp:NEW" (assoc proxy-entry
                                           :metadata {:source :user :acknowledged? true})})]
    (is (= {"perp:NEW" "settled" "perp:OTHER" "unset"}
           (into {} (map (juxt :instrument-id :tone)) (:pills model))))
    (is (= ["--" "--"] (mapv :native-label (:pills model)))
        "An unknown day count withholds rather than claiming zero.")))
