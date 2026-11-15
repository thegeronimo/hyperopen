(ns hyperopen.portfolio.optimizer.universe-from-holdings-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]))

(def ^:private selection-prefetch-effect
  [:effects/load-portfolio-optimizer-history
   {:source :selection-prefetch
    :queue? true
    :merge? true}])

(defn- queued-prefetch-state
  [instruments]
  {:queue (vec instruments)
   :active-instrument-id nil
   :by-instrument-id
   (into {}
         (map (fn [instrument]
                [(:instrument-id instrument)
                 {:status :queued
                  :started-at-ms nil
                  :completed-at-ms nil
                  :error nil
                  :warnings []}]))
         instruments)})

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

(defn- perp-position-row
  [coin notional-usdc]
  {:position {:coin coin
              :szi "1"
              :positionValue (str notional-usdc)
              :leverage {:type "cross"
                         :value "3"}}})

(defn- discovery-state
  [coins missing-coins]
  {:backend-id-by-local-id
   (into {}
         (map (fn [coin]
                [(str "perp:" coin) (str "hl:perp:" coin)]))
         coins)
   :instruments-by-backend-id
   (into {}
         (map (fn [coin]
                (let [missing? (contains? missing-coins coin)]
                  [(str "hl:perp:" coin)
                   {:instrument-id (str "hl:perp:" coin)
                    :display-symbol coin
                    :instrument-kind :hl-perp
                    :history {:status (if missing? :missing :available)
                              :quality-status (if missing? :failed :passed)}}])))
         coins)})

(defn- holdings-state-with-spot
  [include-spot?]
  {:webdata2 {:clearinghouseState
              {:marginSummary {:accountValue "1000"}
               :assetPositions [(perp-position-row "BTC" 300)]}}
   :spot {:clearinghouse-state
          {:balances [{:coin "PURR"
                       :total "10"
                       :hold "0"}]}}
   :asset-selector {:market-by-key {"perp:BTC" {:key "perp:BTC"
                                                :market-type :perp
                                                :coin "BTC"
                                                :symbol "BTC"}
                                    "spot:PURR/USDC" {:key "spot:PURR/USDC"
                                                      :market-type :spot
                                                      :coin "PURR/USDC"
                                                      :symbol "PURR/USDC"
                                                      :base "PURR"
                                                      :quote "USDC"
                                                      :mark "5"}}}
   :portfolio {:optimizer {:draft {:constraints {:include-spot? include-spot?}}}}})

(deftest set-draft-universe-from-current-holdings-skips-known-unusable-history-without-truncating-test
  (let [coins (mapv #(str "COIN" %) (range 30))
        missing-coins #{"COIN29" "COIN28"}
        state {:webdata2 {:clearinghouseState
                          {:marginSummary {:accountValue "100000"}
                           :assetPositions
                           (mapv (fn [idx coin]
                                   (perp-position-row coin (inc idx)))
                                 (range)
                                 coins)}}
               :portfolio {:optimizer
                           {:history-discovery
                            (discovery-state coins missing-coins)}}}
        effects (actions/set-portfolio-optimizer-universe-from-current state)
        path-values (effect-values-by-path effects)
        universe (get path-values [:portfolio :optimizer :draft :universe])
        prefetch-state (get path-values [:portfolio :optimizer :history-prefetch])
        selected-ids (mapv :instrument-id universe)
        expected-ids (mapv #(str "perp:COIN" %)
                           (range 27 -1 -1))]
    (is (= 28 (count universe)))
    (is (= expected-ids selected-ids))
    (is (not-any? missing-coins (mapv :coin universe)))
    (is (every? #(= :available (:optimizer-history/history-status %))
                universe))
    (is (= (queued-prefetch-state universe)
           prefetch-state))
    (is (= selection-prefetch-effect
           (second effects)))))

(deftest set-draft-universe-from-current-holdings-respects-include-spot-constraint-test
  (let [spot-excluded-effects
        (actions/set-portfolio-optimizer-universe-from-current
         (holdings-state-with-spot false))
        spot-excluded-values (effect-values-by-path spot-excluded-effects)
        spot-excluded-universe
        (get spot-excluded-values [:portfolio :optimizer :draft :universe])
        spot-included-effects
        (actions/set-portfolio-optimizer-universe-from-current
         (holdings-state-with-spot true))
        spot-included-values (effect-values-by-path spot-included-effects)
        spot-included-universe
        (get spot-included-values [:portfolio :optimizer :draft :universe])]
    (is (= ["perp:BTC"]
           (mapv :instrument-id spot-excluded-universe)))
    (is (= ["perp:BTC" "spot:PURR/USDC"]
           (mapv :instrument-id spot-included-universe)))))

(deftest auto-preseed-seeds-universe-on-fresh-optimize-new-draft-with-holdings-test
  (let [state (assoc-in (holdings-state-with-spot true)
                        [:portfolio :optimizer :draft] nil)
        effects (actions/auto-preseed-portfolio-optimizer-universe-from-current
                 state "/portfolio/optimize/new")
        path-values (effect-values-by-path effects)
        universe (get path-values [:portfolio :optimizer :draft :universe])]
    (is (seq universe))
    (is (some #{"perp:BTC"} (map :instrument-id universe)))))

(deftest auto-preseed-never-clobbers-a-touched-draft-test
  (let [state (assoc-in (holdings-state-with-spot true)
                        [:portfolio :optimizer :draft]
                        {:universe [{:instrument-id "perp:ETH"}]
                         :constraints {:include-spot? true}})]
    (is (= [] (actions/auto-preseed-portfolio-optimizer-universe-from-current
               state "/portfolio/optimize/new")))))

(deftest auto-preseed-only-fires-on-the-workspace-route-test
  (let [state (assoc-in (holdings-state-with-spot true)
                        [:portfolio :optimizer :draft] nil)]
    (is (seq (actions/auto-preseed-portfolio-optimizer-universe-from-current
              state "/portfolio/optimize")))
    (is (= [] (actions/auto-preseed-portfolio-optimizer-universe-from-current
               state "/portfolio/optimize/some-scenario-id")))))

(deftest auto-preseed-no-ops-when-holdings-not-loaded-test
  (let [state {:portfolio {:optimizer {:draft nil}}}]
    (is (= [] (actions/auto-preseed-portfolio-optimizer-universe-from-current
               state "/portfolio/optimize/new")))))
