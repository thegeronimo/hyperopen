(ns hyperopen.portfolio.optimizer.universe-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]
            [hyperopen.portfolio.optimizer.defaults :as optimizer-defaults]))

(def ^:private custom-universe-source-path-value
  ;; Hand-editing the universe flips the recorded source to a custom set.
  [[:portfolio :optimizer :draft :metadata :universe-source] {:kind :custom}])

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

(deftest set-draft-universe-from-current-holdings-test
  (let [btc-instrument {:instrument-id "perp:BTC"
                        :market-type :perp
                        :coin "BTC"
                        :shortable? true
                        :position-side :long}
        purr-instrument {:instrument-id "spot:PURR"
                         :market-type :spot
                         :coin "PURR"
                         :shortable? false
                         :position-side :long
                         :symbol "PURR/USDC"
                         :base "PURR"
                         :quote "USDC"}
        state {:webdata2 {:clearinghouseState
                          {:marginSummary {:accountValue "1000"}
                           :assetPositions
                           [{:position {:coin "BTC"
                                        :szi "0.5"
                                        :positionValue "500"
                                        :leverage {:type "cross"
                                                   :value "5"}}}]}}
               :spot {:balances [{:coin "PURR"
                                  :total "10"}]}
               :asset-selector {:market-by-key
                                {"spot:PURR" {:key "spot:PURR"
                                              :market-type :spot
                                              :coin "PURR/USDC"
                                              :symbol "PURR/USDC"
                                              :base "PURR"
                                              :quote "USDC"
                                              :mark "2"}}}}]
    (let [effects (actions/set-portfolio-optimizer-universe-from-current state)
          values (effect-values-by-path effects)]
      ;; An untouched (nil) draft is materialized as a full default draft first,
      ;; so the persisted/autosaved draft is always spec-complete.
      (is (= (optimizer-defaults/default-draft)
             (get values [:portfolio :optimizer :draft])))
      (is (= [btc-instrument]
             (get values [:portfolio :optimizer :draft :universe])))
      ;; Loading from holdings records the source and accounts for what was
      ;; omitted (spot assets are excluded while :include-spot? is off).
      (is (= {:kind :holdings
              :omitted [{:instrument-id "spot:PURR"
                         :label "PURR/USDC"
                         :reason :spot-excluded}]}
             (get values [:portfolio :optimizer :draft :metadata :universe-source])))
      ;; Materializing the default also seeds the holdings-derived constraint
      ;; bands (the button path now matches the route preseed path).
      (is (number? (:gross-min (get values [:portfolio :optimizer :draft :constraints]))))
      (is (= (queued-prefetch-state [btc-instrument])
             (get values [:portfolio :optimizer :history-prefetch])))
      (is (true? (get values [:portfolio :optimizer :draft :metadata :dirty?])))
      (is (= selection-prefetch-effect (second effects))))
    (let [include-spot-effects
          (actions/set-portfolio-optimizer-universe-from-current
           (assoc-in state
                     [:portfolio :optimizer :draft :constraints :include-spot?]
                     true))
          include-spot-values (effect-values-by-path include-spot-effects)]
      (is (= [btc-instrument purr-instrument]
             (get include-spot-values [:portfolio :optimizer :draft :universe])))
      (is (= {:kind :holdings :omitted []}
             (get include-spot-values
                  [:portfolio :optimizer :draft :metadata :universe-source])))
      (is (= (queued-prefetch-state [btc-instrument purr-instrument])
             (get include-spot-values [:portfolio :optimizer :history-prefetch])))
      (is (= selection-prefetch-effect
             (second include-spot-effects))))))

(deftest set-draft-universe-from-current-holdings-ignores-empty-snapshot-test
  (is (= []
         (actions/set-portfolio-optimizer-universe-from-current {}))))

(deftest add-draft-universe-instrument-from-asset-selector-market-test
  (let [btc-instrument {:instrument-id "perp:BTC"
                        :market-type :perp
                        :coin "BTC"
                        :shortable? true}
        eth-instrument {:instrument-id "perp:ETH"
                        :market-type :perp
                        :coin "ETH"
                        :shortable? true
                        :position-side :long
                        :dex "hl"
                        :symbol "ETH-USDC"
                        :base "ETH"
                        :quote "USDC"}
        purr-instrument {:instrument-id "spot:PURR/USDC"
                         :market-type :spot
                         :coin "PURR/USDC"
                         :shortable? false
                         :position-side :long
                         :symbol "PURR/USDC"
                         :base "PURR"
                         :quote "USDC"}
        state {:portfolio {:optimizer {:draft {:universe [btc-instrument]}}}
               :asset-selector {:market-by-key
                                {"perp:ETH" {:key "perp:ETH"
                                             :market-type :perp
                                             :coin "ETH"
                                             :symbol "ETH-USDC"
                                             :base "ETH"
                                             :quote "USDC"
                                             :dex "hl"
                                             :maxLeverage 50}
                                 "spot:PURR/USDC" {:key "spot:PURR/USDC"
                                                   :market-type :spot
                                                   :coin "PURR/USDC"
                                                   :symbol "PURR/USDC"
                                                   :base "PURR"
                                                   :quote "USDC"}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [btc-instrument eth-instrument]]
              custom-universe-source-path-value
              [[:portfolio-ui :optimizer :universe-search-query]
               ""]
              [[:portfolio-ui :optimizer :universe-search-active-index]
               0]
              [[:portfolio-ui :optimizer :universe-search-type-filter]
               :all]
              [[:portfolio-ui :optimizer :universe-search-quote-filter]
               :all]
              [[:portfolio :optimizer :history-prefetch]
               (queued-prefetch-state [eth-instrument])]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]
            selection-prefetch-effect]
           (actions/add-portfolio-optimizer-universe-instrument
            state
            "perp:ETH")))
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [btc-instrument purr-instrument]]
              custom-universe-source-path-value
              [[:portfolio-ui :optimizer :universe-search-query]
               ""]
              [[:portfolio-ui :optimizer :universe-search-active-index]
               0]
              [[:portfolio-ui :optimizer :universe-search-type-filter]
               :all]
              [[:portfolio-ui :optimizer :universe-search-quote-filter]
               :all]
              [[:portfolio :optimizer :history-prefetch]
               (queued-prefetch-state [purr-instrument])]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]
            selection-prefetch-effect]
           (actions/add-portfolio-optimizer-universe-instrument
            state
            "spot:PURR/USDC")))))

(deftest set-draft-add-asset-open-updates-results-selector-ui-state-test
  (is (= [[:effects/save-many
           [[[:portfolio-ui :optimizer :draft-add-asset-open?] true]
            [[:portfolio-ui :optimizer :universe-search-query] ""]
            [[:portfolio-ui :optimizer :universe-search-active-index] 0]
            [[:portfolio-ui :optimizer :universe-search-type-filter] :all]
            [[:portfolio-ui :optimizer :universe-search-quote-filter] :all]]]]
         (actions/set-portfolio-optimizer-draft-add-asset-open
          {:portfolio-ui {:optimizer {:universe-search-query "eth"
                                      :universe-search-active-index 2}}}
          true)))
  (is (= [[:effects/save-many
           [[[:portfolio-ui :optimizer :draft-add-asset-open?] false]
            [[:portfolio-ui :optimizer :universe-search-query] ""]
            [[:portfolio-ui :optimizer :universe-search-active-index] 0]
            [[:portfolio-ui :optimizer :universe-search-type-filter] :all]
            [[:portfolio-ui :optimizer :universe-search-quote-filter] :all]]]]
         (actions/set-portfolio-optimizer-draft-add-asset-open
          {:portfolio-ui {:optimizer {:universe-search-query "eth"
                                      :universe-search-active-index 2}}}
          false))))

(deftest add-draft-universe-instrument-and-run-closes-selector-before-recompute-test
  (let [btc-instrument {:instrument-id "perp:BTC"
                        :market-type :perp
                        :coin "BTC"
                        :shortable? true}
        eth-instrument {:instrument-id "perp:ETH"
                        :market-type :perp
                        :coin "ETH"
                        :shortable? true
                        :position-side :long
                        :symbol "ETH-USDC"
                        :base "ETH"
                        :quote "USDC"}
        state {:portfolio {:optimizer {:draft {:universe [btc-instrument]}}}
               :portfolio-ui {:optimizer {:draft-add-asset-open? true
                                          :universe-search-query "eth"
                                          :universe-search-active-index 0}}
               :asset-selector {:market-by-key
                                {"perp:ETH" {:key "perp:ETH"
                                             :market-type :perp
                                             :coin "ETH"
                                             :symbol "ETH-USDC"
                                             :base "ETH"
                                             :quote "USDC"}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [btc-instrument eth-instrument]]
              custom-universe-source-path-value
              [[:portfolio-ui :optimizer :universe-search-query]
               ""]
              [[:portfolio-ui :optimizer :universe-search-active-index]
               0]
              [[:portfolio-ui :optimizer :universe-search-type-filter]
               :all]
              [[:portfolio-ui :optimizer :universe-search-quote-filter]
               :all]
              [[:portfolio-ui :optimizer :draft-add-asset-open?]
               false]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]
            [:effects/run-portfolio-optimizer-pipeline]]
           (actions/add-portfolio-optimizer-universe-instrument-and-run
            state
            "perp:ETH")))))

(deftest add-draft-universe-instrument-and-run-runs-with-zero-black-litterman-views-test
  ;; Zero authored views no longer gates the run: the posterior equals the
  ;; baseline, so adding an asset to a views-aware draft runs immediately.
  (let [btc-instrument {:instrument-id "perp:BTC"
                        :market-type :perp
                        :coin "BTC"
                        :shortable? true}
        eth-instrument {:instrument-id "perp:ETH"
                        :market-type :perp
                        :coin "ETH"
                        :shortable? true
                        :position-side :long}
        state {:portfolio {:optimizer {:draft {:universe [btc-instrument]
                                               :return-model {:kind :black-litterman
                                                              :views []}
                                               :risk-model {:kind :sample-covariance}
                                               :constraints {:long-only? true}}}}
               :portfolio-ui {:optimizer {:draft-add-asset-open? true}}
               :asset-selector {:market-by-key
                                {"perp:ETH" {:key "perp:ETH"
                                             :market-type :perp
                                             :coin "ETH"}}}}
        effects (actions/add-portfolio-optimizer-universe-instrument-and-run
                 state
                 "perp:ETH")
        values (effect-values-by-path effects)]
    (is (some #(= :effects/run-portfolio-optimizer-pipeline (first %))
              effects)
        "The run is no longer gated on having at least one view.")
    (is (= [btc-instrument eth-instrument]
           (get values [:portfolio :optimizer :draft :universe])))
    (is (= false
           (get values [:portfolio-ui :optimizer :draft-add-asset-open?])))))


(deftest toggle-draft-universe-instrument-exclusion-and-run-keeps-row-in-universe-test
  (let [btc-instrument {:instrument-id "perp:BTC"
                        :market-type :perp
                        :coin "BTC"
                        :shortable? true}
        eth-instrument {:instrument-id "perp:ETH"
                        :market-type :perp
                        :coin "ETH"
                        :shortable? true}
        state {:portfolio {:optimizer {:draft {:universe [btc-instrument
                                                           eth-instrument]
                                               :constraints {:blocklist []}}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :constraints :blocklist]
               ["perp:ETH"]]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]
            [:effects/run-portfolio-optimizer-pipeline]]
           (actions/toggle-portfolio-optimizer-universe-instrument-exclusion-and-run
            state
            "perp:ETH")))
    (is (= [btc-instrument eth-instrument]
           (get-in state [:portfolio :optimizer :draft :universe])))))

(deftest toggle-draft-universe-instrument-exclusion-and-run-reincludes-blocklisted-row-test
  (let [btc-instrument {:instrument-id "perp:BTC"
                        :market-type :perp
                        :coin "BTC"
                        :shortable? true}
        eth-instrument {:instrument-id "perp:ETH"
                        :market-type :perp
                        :coin "ETH"
                        :shortable? true}
        state {:portfolio {:optimizer {:draft {:universe [btc-instrument
                                                           eth-instrument]
                                               :constraints {:blocklist ["perp:ETH"]}}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :constraints :blocklist]
               []]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]
            [:effects/run-portfolio-optimizer-pipeline]]
           (actions/toggle-portfolio-optimizer-universe-instrument-exclusion-and-run
            state
            "perp:ETH")))))

(deftest set-draft-universe-instrument-side-updates-row-and-marks-dirty-test
  (let [btc-instrument {:instrument-id "perp:BTC"
                        :market-type :perp
                        :coin "BTC"
                        :shortable? true
                        :position-side :long}
        eth-instrument {:instrument-id "perp:ETH"
                        :market-type :perp
                        :coin "ETH"
                        :shortable? true
                        :position-side :long}
        state {:portfolio {:optimizer {:draft {:universe [btc-instrument
                                                           eth-instrument]}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [(assoc btc-instrument :position-side :short)
                eth-instrument]]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]]
           (actions/set-portfolio-optimizer-universe-instrument-side
            state
            "perp:BTC"
            :short)))))

(deftest set-draft-universe-instrument-side-keeps-non-shortable-row-long-test
  (let [spot-instrument {:instrument-id "spot:PURR"
                         :market-type :spot
                         :coin "PURR"
                         :shortable? false
                         :position-side :long}
        state {:portfolio {:optimizer {:draft {:universe [spot-instrument]}}}}]
    (is (= []
           (actions/set-portfolio-optimizer-universe-instrument-side
            state
            "spot:PURR"
            :short)))))

(deftest set-draft-universe-instrument-side-and-run-reruns-after-side-change-test
  (let [btc-instrument {:instrument-id "perp:BTC"
                        :market-type :perp
                        :coin "BTC"
                        :shortable? true
                        :position-side :long}
        state {:portfolio {:optimizer {:draft {:universe [btc-instrument]
                                               :return-model {:kind :historical-mean}
                                               :risk-model {:kind :sample-covariance}
                                               :constraints {:long-only? false}}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [(assoc btc-instrument :position-side :short)]]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]
            [:effects/run-portfolio-optimizer-pipeline]]
           (actions/set-portfolio-optimizer-universe-instrument-side-and-run
            state
            "perp:BTC"
            :short)))))

(deftest add-draft-universe-instrument-preserves-history-discovery-backend-id-test
  (let [eth-instrument {:instrument-id "perp:ETH"
                        :market-type :perp
                        :coin "ETH"
                        :shortable? true
                        :position-side :long
                        :optimizer-history/instrument-id "hl:perp:ETH"
                        :optimizer-history/display-symbol "ETH"
                        :optimizer-history/instrument-kind :hl-perp
                        :optimizer-history/history-status :available
                        :optimizer-history/quality-status :passed}
        state {:portfolio {:optimizer
                           {:draft {:universe []}
                            :history-discovery
                            {:backend-id-by-local-id {"perp:ETH" "hl:perp:ETH"}
                             :instruments-by-backend-id
                             {"hl:perp:ETH"
                              {:instrument-id "hl:perp:ETH"
                               :display-symbol "ETH"
                               :instrument-kind :hl-perp
                               :history {:status :available
                                         :quality-status :passed}}}}}}
               :asset-selector {:market-by-key
                                {"perp:ETH" {:key "perp:ETH"
                                             :market-type :perp
                                             :coin "ETH"}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [eth-instrument]]
              custom-universe-source-path-value
              [[:portfolio-ui :optimizer :universe-search-query]
               ""]
              [[:portfolio-ui :optimizer :universe-search-active-index]
               0]
              [[:portfolio-ui :optimizer :universe-search-type-filter]
               :all]
              [[:portfolio-ui :optimizer :universe-search-quote-filter]
               :all]
              [[:portfolio :optimizer :history-prefetch]
               (queued-prefetch-state [eth-instrument])]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]
            selection-prefetch-effect]
           (actions/add-portfolio-optimizer-universe-instrument
            state
            "perp:ETH")))))

(deftest add-draft-universe-instrument-from-vault-row-test
  (let [vault-address "0x1111111111111111111111111111111111111111"
        vault-instrument {:instrument-id (str "vault:" vault-address)
                          :market-type :vault
                          :coin (str "vault:" vault-address)
                          :vault-address vault-address
                          :shortable? false
                          :position-side :long
                          :name "Alpha Yield"
                          :symbol "Alpha Yield"
                          :tvl 500}
        state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"
                                                            :market-type :perp
                                                            :coin "BTC"
                                                            :shortable? true}]}}}
               :vaults {:merged-index-rows [{:name "Alpha Yield"
                                             :vault-address "0x1111111111111111111111111111111111111111"
                                             :relationship {:type :normal}
                                             :tvl 500}]}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [{:instrument-id "perp:BTC"
                 :market-type :perp
                 :coin "BTC"
                 :shortable? true}
                vault-instrument]]
              custom-universe-source-path-value
              [[:portfolio-ui :optimizer :universe-search-query]
               ""]
              [[:portfolio-ui :optimizer :universe-search-active-index]
               0]
              [[:portfolio-ui :optimizer :universe-search-type-filter]
               :all]
              [[:portfolio-ui :optimizer :universe-search-quote-filter]
               :all]
              [[:portfolio :optimizer :history-prefetch]
               (queued-prefetch-state [vault-instrument])]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]
            selection-prefetch-effect]
           (actions/add-portfolio-optimizer-universe-instrument
            state
            (str "vault:" vault-address))))))

(deftest add-draft-universe-instrument-skips-prefetch-when-history-is-loaded-test
  (let [eth-instrument {:instrument-id "perp:ETH"
                        :market-type :perp
                        :coin "ETH"
                        :shortable? true
                        :position-side :long}
        state {:portfolio {:optimizer
                           {:draft {:universe []}
                            :history-data {:candle-history-by-coin
                                           {"ETH" [{:time 1000 :close "100"}
                                                   {:time 2000 :close "101"}]}
                                           :funding-history-by-coin
                                           {"ETH" [{:time-ms 1000
                                                   :funding-rate-raw 0}]}}}}
               :asset-selector {:market-by-key
                                {"perp:ETH" {:key "perp:ETH"
                                             :market-type :perp
                                             :coin "ETH"}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [eth-instrument]]
              custom-universe-source-path-value
              [[:portfolio-ui :optimizer :universe-search-query]
               ""]
              [[:portfolio-ui :optimizer :universe-search-active-index]
               0]
              [[:portfolio-ui :optimizer :universe-search-type-filter]
               :all]
              [[:portfolio-ui :optimizer :universe-search-quote-filter]
               :all]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]]
           (actions/add-portfolio-optimizer-universe-instrument
            state
            "perp:ETH")))))

(deftest add-draft-universe-instrument-rejects-missing-or-duplicate-market-test
  (let [state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"
                                                            :market-type :perp
                                                            :coin "BTC"}]}}}
               :asset-selector {:market-by-key {"perp:BTC" {:key "perp:BTC"
                                                            :market-type :perp
                                                            :coin "BTC"}}}}]
    (is (= []
           (actions/add-portfolio-optimizer-universe-instrument
            state
            "perp:BTC")))
    (is (= []
           (actions/add-portfolio-optimizer-universe-instrument
            state
            "perp:ETH")))
    (is (= []
           (actions/add-portfolio-optimizer-universe-instrument
            state
            " ")))))

(deftest remove-draft-universe-instrument-cleans-dependent-constraints-test
  (let [state {:portfolio
               {:optimizer
                {:draft
                 {:universe [{:instrument-id "perp:BTC"
                              :market-type :perp
                              :coin "BTC"}
                             {:instrument-id "perp:ETH"
                              :market-type :perp
                              :coin "ETH"}]
                  :constraints {:allowlist ["perp:BTC" "perp:ETH"]
                                :blocklist ["perp:ETH"]
                                :held-locks ["perp:ETH"]
                                :asset-overrides {"perp:ETH" {:max-weight 0.2}
                                                  "perp:BTC" {:max-weight 0.5}}
                                :perp-leverage {"perp:ETH" {:max-weight 0.4}}}
                  :history-assumptions {"perp:ETH" {:behavior :conservative
                                                    :expected-return nil
                                                    :volatility 0.9
                                                    :max-weight 0.03
                                                    :correlation-floor 0.75}
                                        "perp:BTC" {:behavior :conservative
                                                    :expected-return nil
                                                    :volatility 0.8
                                                    :max-weight 0.03
                                                    :correlation-floor 0.75}}}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [{:instrument-id "perp:BTC"
                 :market-type :perp
                 :coin "BTC"}]]
              [[:portfolio :optimizer :draft :constraints :allowlist]
               ["perp:BTC"]]
              [[:portfolio :optimizer :draft :constraints :blocklist]
               []]
              [[:portfolio :optimizer :draft :constraints :held-locks]
               []]
              [[:portfolio :optimizer :draft :constraints :asset-overrides]
               {"perp:BTC" {:max-weight 0.5}}]
              [[:portfolio :optimizer :draft :constraints :perp-leverage]
               {}]
              [[:portfolio :optimizer :draft :history-assumptions]
               {"perp:BTC" {:behavior :conservative
                            :expected-return nil
                            :volatility 0.8
                            :max-weight 0.03
                            :correlation-floor 0.75}}]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]]
           (actions/remove-portfolio-optimizer-universe-instrument
            state
            "perp:ETH")))
    (is (= []
           (actions/remove-portfolio-optimizer-universe-instrument
            state
            "perp:SOL")))))

(deftest clear-universe-empties-selection-and-per-asset-residue-test
  ;; The "start from scratch" escape hatch: one action empties the universe and
  ;; every per-asset constraint/assumption remnant, and records the custom source
  ;; so the holdings preseed will not refill the cleared draft.
  (let [state {:portfolio
               {:optimizer
                {:draft
                 {:universe [{:instrument-id "perp:BTC"
                              :market-type :perp
                              :coin "BTC"}]
                  :constraints {:allowlist ["perp:BTC"]
                                :blocklist ["perp:BTC"]
                                :held-locks ["perp:BTC"]
                                :asset-overrides {"perp:BTC" {:max-weight 0.5}}
                                :perp-leverage {"perp:BTC" {:max-weight 0.4}}}
                  :history-assumptions {"perp:BTC" {:behavior :conservative}}}}}}
        effects (actions/clear-portfolio-optimizer-universe state)
        path-values (into {} (second (first effects)))]
    (is (= :effects/save-many (ffirst effects)))
    (is (= [] (get path-values [:portfolio :optimizer :draft :universe])))
    (is (= {:kind :custom}
           (get path-values [:portfolio :optimizer :draft :metadata :universe-source])))
    (is (= [] (get path-values [:portfolio :optimizer :draft :constraints :allowlist])))
    (is (= [] (get path-values [:portfolio :optimizer :draft :constraints :blocklist])))
    (is (= [] (get path-values [:portfolio :optimizer :draft :constraints :held-locks])))
    (is (= {} (get path-values [:portfolio :optimizer :draft :constraints :asset-overrides])))
    (is (= {} (get path-values [:portfolio :optimizer :draft :constraints :perp-leverage])))
    (is (= {} (get path-values [:portfolio :optimizer :draft :history-assumptions])))
    (is (true? (get path-values [:portfolio :optimizer :draft :metadata :dirty?]))))
  ;; Clearing an already-empty universe is a no-op.
  (is (= [] (actions/clear-portfolio-optimizer-universe
             {:portfolio {:optimizer {:draft {:universe []}}}}))))
