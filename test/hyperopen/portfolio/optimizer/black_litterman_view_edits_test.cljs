(ns hyperopen.portfolio.optimizer.black-litterman-view-edits-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.black-litterman-actions :as actions]))

(def ^:private views-path
  [:portfolio :optimizer :draft :return-model :views])

(def ^:private dirty-path
  [:portfolio :optimizer :draft :metadata :dirty?])

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

(defn- deep-merge
  [& maps]
  (apply merge-with
         (fn [left right]
           (if (and (map? left)
                    (map? right))
             (deep-merge left right)
             right))
         maps))

(defn- effect-values-by-path
  [effects]
  (reduce (fn [acc effect]
            (case (first effect)
              :effects/save
              (assoc acc (second effect) (nth effect 2))

              :effects/save-many
              (reduce (fn [acc [path value]]
                        (assoc acc path value))
                      acc
                      (second effect))

              acc))
          {}
          (or effects [])))

(defn- sample-view
  [id overrides]
  (merge {:id id
          :kind :absolute
          :instrument-id "perp:BTC"
          :return 0.1
          :confidence 0.5
          :horizon :1y
          :weights {"perp:BTC" 1}
          :notes ""}
         overrides))

(defn- relative-view
  [id overrides]
  (merge {:id id
          :kind :relative
          :instrument-id "perp:ETH"
          :comparator-instrument-id "perp:SOL"
          :direction :outperform
          :return 0.05
          :confidence-level :medium
          :confidence 0.5
          :confidence-variance 0.5
          :horizon :6m
          :weights {"perp:ETH" 1
                    "perp:SOL" -1}
          :notes ""}
         overrides))

(defn- base-state
  [& overrides]
  (apply deep-merge
         {:portfolio
          {:optimizer
           {:draft
            {:return-model
             {:kind :black-litterman
              :views []}
             :universe [{:instrument-id "perp:BTC"
                         :market-type :perp
                         :coin "BTC"
                         :symbol "BTC-USDC"}
                        {:instrument-id "perp:ETH"
                         :market-type :perp
                         :coin "ETH"
                         :symbol "ETH-USDC"}
                        {:instrument-id "perp:SOL"
                         :market-type :perp
                         :coin "SOL"
                         :symbol "SOL-USDC"}
                        {:instrument-id "perp:HYPE"
                         :market-type :perp
                         :coin "HYPE"
                         :symbol "HYPE-USDC"}]
             :metadata {:dirty? false}}}}}
         overrides))

(deftest black-litterman-direct-view-edits-switch-kind-and-update-numeric-fields-test
  (let [existing-view (sample-view "view-1"
                                   {:instrument-id "perp:BTC"
                                    :return 0.14
                                    :confidence-level :high
                                    :confidence 0.75
                                    :confidence-variance 0.25
                                    :horizon :1y})
        state (base-state
               {:portfolio
                {:optimizer
                 {:draft
                  {:return-model
                   {:views [existing-view]}}}}})
        kind-values (effect-values-by-path
                     (actions/set-portfolio-optimizer-black-litterman-view-parameter
                      state
                      "view-1"
                      :kind
                      "relative"))
        [kind-view] (get kind-values views-path)
        return-values (effect-values-by-path
                       (actions/set-portfolio-optimizer-black-litterman-view-parameter
                        state
                        "view-1"
                        :return
                        "0.18"))
        [return-view] (get return-values views-path)
        confidence-values (effect-values-by-path
                           (actions/set-portfolio-optimizer-black-litterman-view-parameter
                            state
                            "view-1"
                            :confidence
                            "0.8"))
        [confidence-view] (get confidence-values views-path)]
    (is (= :relative (:kind kind-view)))
    (is (= "perp:BTC" (:instrument-id kind-view)))
    (is (= "perp:ETH" (:comparator-instrument-id kind-view)))
    (is (= 0.14 (:return kind-view)))
    (is (= :high (:confidence-level kind-view)))
    (is (= 0.75 (:confidence kind-view)))
    (is (= 0.25 (:confidence-variance kind-view)))
    (is (= :1y (:horizon kind-view)))
    (is (= {"perp:BTC" 1
            "perp:ETH" -1}
           (:weights kind-view)))
    (is (= true (get kind-values dirty-path)))
    (is (= 0.18 (:return return-view)))
    (is (= true (get return-values dirty-path)))
    (is (= 0.8 (:confidence confidence-view)))
    (is (near? 0.2 (:confidence-variance confidence-view)))
    (is (= true (get confidence-values dirty-path)))))

(deftest black-litterman-direct-view-edits-rebuild-absolute-and-relative-instrument-weights-test
  (let [absolute-state (base-state
                        {:portfolio
                         {:optimizer
                          {:draft
                           {:return-model
                            {:views [(sample-view "absolute-1"
                                                  {:instrument-id "perp:BTC"
                                                   :weights {"perp:BTC" 1}})]}}}}})
        absolute-values (effect-values-by-path
                         (actions/set-portfolio-optimizer-black-litterman-view-parameter
                          absolute-state
                          "absolute-1"
                          :instrument-id
                          "perp:SOL"))
        [absolute-view] (get absolute-values views-path)
        relative-state (base-state
                        {:portfolio
                         {:optimizer
                          {:draft
                           {:return-model
                            {:views [(relative-view "relative-1" {})]}}}}})
        primary-values (effect-values-by-path
                        (actions/set-portfolio-optimizer-black-litterman-view-parameter
                         relative-state
                         "relative-1"
                         :instrument-id
                         "perp:HYPE"))
        [primary-view] (get primary-values views-path)
        comparator-values (effect-values-by-path
                           (actions/set-portfolio-optimizer-black-litterman-view-parameter
                            relative-state
                            "relative-1"
                            :comparator-instrument-id
                            "perp:BTC"))
        [comparator-view] (get comparator-values views-path)]
    (is (= "perp:SOL" (:instrument-id absolute-view)))
    (is (= {"perp:SOL" 1}
           (:weights absolute-view)))
    (is (= true (get absolute-values dirty-path)))
    (is (= "perp:HYPE" (:instrument-id primary-view)))
    (is (= "perp:SOL" (:comparator-instrument-id primary-view)))
    (is (= {"perp:HYPE" 1
            "perp:SOL" -1}
           (:weights primary-view)))
    (is (= true (get primary-values dirty-path)))
    (is (= "perp:ETH" (:instrument-id comparator-view)))
    (is (= "perp:BTC" (:comparator-instrument-id comparator-view)))
    (is (= {"perp:ETH" 1
            "perp:BTC" -1}
           (:weights comparator-view)))
    (is (= true (get comparator-values dirty-path)))))

(deftest black-litterman-direct-view-edits-reject-duplicate-relative-instruments-test
  (let [state (base-state
               {:portfolio
                {:optimizer
                 {:draft
                  {:return-model
                   {:views [(relative-view "relative-1" {})]}}}}})
        primary-effects (actions/set-portfolio-optimizer-black-litterman-view-parameter
                         state
                         "relative-1"
                         :instrument-id
                         "perp:SOL")
        comparator-effects (actions/set-portfolio-optimizer-black-litterman-view-parameter
                            state
                            "relative-1"
                            :comparator-instrument-id
                            "perp:ETH")
        primary-values (effect-values-by-path primary-effects)
        comparator-values (effect-values-by-path comparator-effects)]
    (is (= [] primary-effects))
    (is (not (contains? primary-values views-path)))
    (is (= [] comparator-effects))
    (is (not (contains? comparator-values views-path)))))
