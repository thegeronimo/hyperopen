(ns hyperopen.portfolio.optimizer.black-litterman-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.black-litterman-actions :as actions]))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

(defn- saved-path-values
  [effects]
  (second (first effects)))

(defn- first-saved-value
  [effects]
  (second (first (saved-path-values effects))))

(deftest black-litterman-view-actions-author-draft-views-test
  (let [state {:portfolio {:optimizer {:draft {:return-model {:kind :black-litterman
                                                              :views []}
                                               :universe [{:instrument-id "perp:BTC"
                                                           :market-type :perp
                                                           :coin "BTC"}
                                                          {:instrument-id "perp:ETH"
                                                           :market-type :perp
                                                           :coin "ETH"}]}}}}
        absolute-effects (actions/add-portfolio-optimizer-black-litterman-view
                          state
                          :absolute)
        absolute-view (first (first-saved-value absolute-effects))
        relative-effects (actions/add-portfolio-optimizer-black-litterman-view
                          state
                          :relative)
        relative-view (first (first-saved-value relative-effects))]
    (is (= :absolute (:kind absolute-view)))
    (is (= "perp:BTC" (:instrument-id absolute-view)))
    (is (= {"perp:BTC" 1} (:weights absolute-view)))
    (is (= :relative (:kind relative-view)))
    (is (= {"perp:BTC" 1
            "perp:ETH" -1}
           (:weights relative-view)))
    (is (= [[:portfolio :optimizer :draft :metadata :dirty?] true]
           (second (saved-path-values relative-effects))))))

(deftest black-litterman-view-actions-edit-and-remove-views-test
  (let [state {:portfolio {:optimizer {:draft {:return-model
                                               {:kind :black-litterman
                                                :views [{:id "view-1"
                                                         :kind :absolute
                                                         :instrument-id "perp:BTC"
                                                         :return 0.1
                                                         :confidence 0.5
                                                         :confidence-variance 0.5
                                                         :weights {"perp:BTC" 1}}
                                                        {:id "view-2"
                                                         :kind :relative
                                                         :long-instrument-id "perp:BTC"
                                                         :short-instrument-id "perp:ETH"
                                                         :return 0.03
                                                         :confidence 0.5
                                                         :confidence-variance 0.5
                                                         :weights {"perp:BTC" 1
                                                                   "perp:ETH" -1}}]}
                                               :universe [{:instrument-id "perp:BTC"
                                                           :market-type :perp}
                                                          {:instrument-id "perp:ETH"
                                                           :market-type :perp}
                                                          {:instrument-id "perp:SOL"
                                                           :market-type :perp}]}}}}
        confidence-effects (actions/set-portfolio-optimizer-black-litterman-view-parameter
                            state
                            "view-1"
                            :confidence
                            "0.8")
        confidence-view (first (first-saved-value confidence-effects))
        instrument-effects (actions/set-portfolio-optimizer-black-litterman-view-parameter
                            state
                            "view-1"
                            :instrument-id
                            "perp:SOL")
        instrument-view (first (first-saved-value instrument-effects))
        remove-effects (actions/remove-portfolio-optimizer-black-litterman-view
                        state
                        "view-1")]
    (is (= 0.8 (:confidence confidence-view)))
    (is (near? 0.2 (:confidence-variance confidence-view)))
    (is (= {"perp:SOL" 1} (:weights instrument-view)))
    (is (= ["view-2"] (mapv :id (first-saved-value remove-effects))))
    (is (= []
           (actions/set-portfolio-optimizer-black-litterman-view-parameter
            state
            "view-2"
            :short-instrument-id
            "perp:BTC")))
    (is (= []
           (actions/add-portfolio-optimizer-black-litterman-view
            {:portfolio {:optimizer {:draft {:return-model {:kind :historical-mean}}}}}
            :absolute)))))
