(ns hyperopen.views.portfolio.optimize.refinement-status-card-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio.optimize.refinement-status-card :as refinement-card]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [click-actions collect-strings node-attr node-by-role]]))

(def ^:private depth-options
  [{:key :quick :points 56 :label "Quick" :hint "Light pass · ~a few seconds" :selected? false}
   {:key :thorough :points 72 :label "Thorough" :hint "Balanced · ~10–20s est." :selected? true}
   {:key :maximum :points 80 :label "Maximum" :hint "Densest · ~20–40s est." :selected? false}])

(def ^:private draft-refinement
  {:solved? true
   :can-refine? true
   :in-flight? false
   :depth :thorough
   :depth-options depth-options
   :assessment {:tier :draft
                :point-count 40
                :frontier-quality :medium
                :selection-stability :provisional
                :exact-selection? false
                :next-step :refine-optimization
                :stop-reason :draft-budget-reached}
   :runtime-ms 7350
   :progress {:overall-percent nil :active-step nil}})

(deftest refinement-card-demotes-options-behind-closed-disclosure-test
  (let [card (refinement-card/refinement-status-card draft-refinement)
        status (node-by-role card "portfolio-optimizer-refinement-status")
        disclosure (node-by-role card "portfolio-optimizer-refinement-disclosure")
        toggle (node-by-role card "portfolio-optimizer-refinement-disclosure-toggle")
        options (node-by-role card "portfolio-optimizer-refinement-options")
        strings (set (collect-strings card))]
    (is (some? status))
    (is (some? (node-by-role status "portfolio-optimizer-refinement-quality"))
        "Quality tag stays visible in the compact (closed) state.")
    (is (some? (node-by-role status "portfolio-optimizer-refinement-stability"))
        "Stability tag stays visible in the compact (closed) state.")
    (is (= :details (first disclosure)))
    (is (nil? (node-attr disclosure :open))
        "The disclosure is closed by default — no open attribute.")
    (is (= :summary (first toggle)))
    (is (contains? strings "Refinement options"))
    (is (some? (node-by-role disclosure "portfolio-optimizer-refinement-options"))
        "Depth options render inside the disclosure, not in the card body.")
    (is (some? (node-by-role disclosure "portfolio-optimizer-refine-now")))
    (is (nil? (node-by-role status "portfolio-optimizer-refinement-options"))
        "No second copy of the options outside the disclosure.")
    (is (some? options))))

(deftest refinement-card-disclosure-keeps-depth-and-refine-dispatches-test
  (let [card (refinement-card/refinement-status-card draft-refinement)
        quick-tile (node-by-role card "portfolio-optimizer-refinement-depth-quick")
        refine-now (node-by-role card "portfolio-optimizer-refine-now")]
    (is (= [[:actions/set-portfolio-optimizer-refinement-depth :quick]]
           (click-actions quick-tile)))
    (is (= [[:actions/refine-portfolio-optimizer]]
           (click-actions refine-now)))
    (is (contains? (set (collect-strings refine-now))
                   "Refine now · Thorough · 72 points"))))

(deftest refinement-card-moves-selection-explanation-into-disclosure-test
  (let [card (refinement-card/refinement-status-card draft-refinement)
        disclosure (node-by-role card "portfolio-optimizer-refinement-disclosure")
        status (node-by-role card "portfolio-optimizer-refinement-status")
        explanation "Fast draft based on the current frontier sample. Refine for higher confidence — the selection is sampled from the frontier and may shift when refined."]
    (is (contains? (set (collect-strings disclosure)) explanation))
    (is (not (contains? (set (collect-strings status)) explanation)))))

(deftest refinement-card-in-flight-view-is-unchanged-test
  (let [card (refinement-card/refinement-status-card
              (assoc draft-refinement
                     :in-flight? true
                     :progress {:overall-percent 42 :active-step :solve-frontier}))
        running (node-by-role card "portfolio-optimizer-refinement-running")
        stop (node-by-role card "portfolio-optimizer-refinement-stop")]
    (is (some? running))
    (is (nil? (node-by-role card "portfolio-optimizer-refinement-disclosure")))
    (is (contains? (set (collect-strings running)) "Refining optimization"))
    (is (= [[:actions/stop-portfolio-optimizer-refinement]]
           (click-actions stop)))))

(deftest refinement-card-outcome-stays-outside-disclosure-test
  (let [card (refinement-card/refinement-status-card
              (assoc draft-refinement
                     :outcome {:depth :thorough
                               :change {:sharpe-delta 0.02
                                        :return-delta 0.004
                                        :vol-delta -0.003
                                        :weight-l1-delta 0.11
                                        :material? false}
                               :exact-selection? false
                               :material? false}))
        outcome (node-by-role card "portfolio-optimizer-refinement-outcome")
        disclosure (node-by-role card "portfolio-optimizer-refinement-disclosure")]
    (is (some? outcome))
    (is (nil? (node-by-role disclosure "portfolio-optimizer-refinement-outcome")))
    (is (contains? (set (collect-strings outcome)) "Selected portfolio stayed stable"))))
