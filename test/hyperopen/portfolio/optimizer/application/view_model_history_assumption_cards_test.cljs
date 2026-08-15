(ns hyperopen.portfolio.optimizer.application.view-model-history-assumption-cards-test
  "History-assumption card + rail view-model boundary coverage (split from
  view-model-setup-boundary-test when the suite outgrew it, 2026-07-05)."
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model :as view-model]))

(def ^:private btc-instrument
  {:instrument-id "perp:BTC"
   :market-type :perp
   :coin "BTC"
   :symbol "BTC-USDC"
   :name "Bitcoin"})

(def ^:private eth-instrument
  {:instrument-id "perp:ETH"
   :market-type :perp
   :coin "ETH"
   :symbol "ETH-USDC"
   :name "Ethereum"})

(def ^:private new-perp-instrument
  {:instrument-id "perp:NEW"
   :market-type :perp
   :coin "NEW"})

;; History assumption cards are load-aware: an asset is only carded once its history
;; has actually been fetched (a succeeded load that requested it), so a fully-historied
;; asset never flashes a card while history is still loading.
(def ^:private loaded-state-with-new
  {:status :succeeded
   :request-signature {:universe [btc-instrument new-perp-instrument]}})

(deftest history-assumption-cards-projects-render-ready-cards-test
  (let [draft {:universe [btc-instrument new-perp-instrument]
               :objective {:kind :minimum-variance}
               :history-assumptions
               {"perp:NEW" {:behavior :conservative
                            :expected-return nil
                            :volatility nil
                            :max-weight 0.03
                            :correlation-floor 0.75}}}
        readiness {:request {:requested-universe [btc-instrument new-perp-instrument]
                             :universe [btc-instrument]
                             :objective {:kind :minimum-variance}}
                   :blocking-warnings [{:code :history-assumption-incomplete
                                        :instrument-id "perp:NEW"
                                        :missing :volatility
                                        :message "NEW needs a modeled annual volatility."}]}
        model (view-model/history-assumption-cards
               {} draft readiness loaded-state-with-new
               {:percent-label (fn [value] (str (js/Math.round (* 100 value)) "%"))})
        card (first (:cards model))]
    (is (true? (:applicable? model)))
    (is (= 1 (count (:cards model))))
    (is (= "perp:NEW" (:instrument-id card)))
    (is (= :conservative (:mode card)))
    (is (= :incomplete (:status card)))
    (is (= "Needs assumptions" (:status-label card)))
    (is (= [:proxy :conservative]
           (mapv :value (:mode-options card)))
        "Both behaviors are offered; proxy leads.")
    (is (= "3%" (get-in card [:max-weight :percent-label])))
    (is (nil? (get-in card [:volatility :value])))
    (is (= ["NEW needs a modeled annual volatility."] (:errors card)))
    (is (= {:auto? false
            :source-label "Edited"
            :summary "-- vol · 3% max"
            :attention? true}
           (:risk-guardrails card))
        "A missing guardrail value reads as attention, never as auto-set.")
    (is (false? (:engine-applied? card)))
    (is (= :actions/set-portfolio-optimizer-history-assumption-mode
           (get-in card [:actions :set-mode])))
    (is (= :actions/clear-portfolio-optimizer-history-assumption
           (get-in card [:actions :clear])))))

(deftest history-assumption-cards-project-proxy-mode-test
  (let [eth {:instrument-id "perp:ETH" :market-type :perp :coin "ETH"}
        universe [btc-instrument eth new-perp-instrument]
        draft {:universe universe
               :objective {:kind :minimum-variance}
               :constraints {:max-asset-weight 0.5}
               :history-assumptions
               {"perp:NEW" {:behavior :proxy
                            :expected-return 0.0
                            :volatility 0.8
                            :max-weight 0.05
                            :proxy {:instrument-ids ["perp:BTC" "perp:ETH"]
                                    :relationship-strength :high
                                    :prior-weights nil}}}}
        readiness {:request {:requested-universe universe
                             :universe [btc-instrument eth]
                             :objective {:kind :minimum-variance}
                             :history {:eligible-instruments [btc-instrument eth]
                                       :history-window {:return-observations 399}}}
                   :blocking-warnings []}
        model (view-model/history-assumption-cards
               {} draft readiness
               {:status :succeeded
                :request-signature {:universe universe}}
               {:percent-label (fn [value] (str (js/Math.round (* 100 value)) "%"))})
        card (first (:cards model))]
    (is (= "perp:NEW" (:instrument-id card)))
    (is (= :proxy (:mode card)))
    (is (= :configured (:status card)) "Complete proxy entry reads Configured.")
    (is (true? (:engine-applied? card)))
    (is (false? (:configured? card)) "Not acknowledged yet - Apply is still offered.")
    (is (= ["perp:BTC" "perp:ETH"] (:selected-proxy-ids card)))
    (is (= ["perp:BTC" "perp:ETH"]
           (mapv :instrument-id (:selected-proxies card)))
        "Selected proxies surface as chips.")
    (is (= "" (:proxy-search-query card)) "Search starts empty.")
    (is (= [] (:proxy-search-results card))
        "No catalog results until the user types a query.")
    (is (= {:auto? true
            :source-label "Auto-set"
            :summary "80% vol · 5% max"
            :attention? false}
           (:risk-guardrails card))
        "Seed values read as auto-set guardrails.")
    (is (= :actions/set-portfolio-optimizer-history-assumption-proxy-search
           (get-in card [:actions :set-proxy-search])))
    (is (= :high (:relationship-strength card)))
    (is (= [50.0 50.0] (mapv :weight-percent (:prior-basket card)))
        "With no explicit prior, the prior basket is equal weight over the selected proxies.")
    (is (= :equal (:prior-source card))
        "The equal fallback is labeled as such - never presented as model output.")
    (is (= "Equal-weight fallback" (:prior-source-label card)))
    (is (= :skipped (get-in card [:regression-estimate :status]))
        "No overlap series on the readiness request -> the regression preview reports itself skipped.")
    (is (= "No return overlap with the proxies yet. Using the prior only."
           (get-in card [:regression-estimate :message])))
    (is (= [50.0 50.0]
           (mapv :weight-percent (get-in card [:final-basket :rows])))
        "q = 0 -> the final modeled basket IS the prior.")
    (is (zero? (get-in card [:final-basket :confidence-q])))
    (is (= "0%" (get-in card [:final-basket :confidence-label])))
    (is (= "Low" (-> (:diagnostics card) first :value))
        "No native returns -> regression confidence previews Low.")
    (is (some #(= "R² used for confidence, not weights" (:detail %))
              (:diagnostics card)))
    (is (= 399 (:covariance-observations card))
        "The shared aligned window rides on the card.")
    (is (= {:key :history-window
            :label "Covariance window"
            :value "399 days of returns"
            :detail "Extended via proxies · no native overlap (prior weights only)"}
           (last (:diagnostics card)))
        "The window cell reports the proxy-extended shared window, never the asset's own overlap.")
    (is (= "Final model: BTC 50% / ETH 50% + specific risk + 5% cap"
           (:final-model-line card))
        "The summary strip names the final basket, not a generic formula.")
    (is (= :actions/set-portfolio-optimizer-history-assumption-proxy-asset
           (get-in card [:actions :toggle-proxy-asset])))
    (is (= :actions/apply-portfolio-optimizer-history-assumption
           (get-in card [:actions :apply])))))

(deftest history-assumption-cards-guardrails-read-edited-off-seed-test
  ;; Editing either guardrail away from its behavior seed (vol 0.8, proxy cap
  ;; 0.05) flips the drawer's tag from Auto-set to Edited.
  (let [universe [btc-instrument new-perp-instrument]
        draft {:universe universe
               :objective {:kind :minimum-variance}
               :history-assumptions
               {"perp:NEW" {:behavior :proxy
                            :expected-return 0.0
                            :volatility 0.6
                            :max-weight 0.05
                            :proxy {:instrument-ids ["perp:BTC"]
                                    :relationship-strength :medium
                                    :prior-weights nil}}}}
        readiness {:request {:requested-universe universe
                             :universe [btc-instrument]
                             :objective {:kind :minimum-variance}
                             :history {:eligible-instruments [btc-instrument]}}
                   :blocking-warnings []}
        model (view-model/history-assumption-cards
               {} draft readiness loaded-state-with-new
               {:percent-label (fn [value] (str (js/Math.round (* 100 value)) "%"))})]
    (is (= {:auto? false
            :source-label "Edited"
            :summary "60% vol · 5% max"
            :attention? false}
           (:risk-guardrails (first (:cards model)))))))

(defn- blended-overlap-series
  "Target = 70% ETH + 30% BTC + small deterministic noise, matching the shape
  the request builder attaches to the readiness entry."
  [n]
  (let [eth (mapv (fn [i] (* 0.02 (js/Math.sin (* 0.9 i)))) (range n))
        btc (mapv (fn [i] (* 0.015 (js/Math.cos (* 0.7 i)))) (range n))
        y (mapv (fn [e b i] (+ (* 0.7 e) (* 0.3 b)
                               (* 0.004 (js/Math.sin (* 3.7 i)))))
                eth btc (range n))]
    {:x-returns y
     :proxy-returns-by-id {"perp:ETH" eth "perp:BTC" btc}
     :observations n}))

(deftest history-assumption-cards-preview-the-model-adjusted-basket-test
  ;; When the readiness request carries the overlap series, the card runs the
  ;; SAME domain pipeline the engine will run and previews the regression
  ;; estimate and the confidence-shrunk final basket - no more equal prior
  ;; presented as the model's output.
  (let [universe [btc-instrument eth-instrument
                  {:instrument-id "perp:NEW" :market-type :perp :coin "NEW"}]
        draft {:universe universe
               :objective {:kind :minimum-variance}
               :constraints {:max-asset-weight 0.5}
               :history-assumptions
               {"perp:NEW" {:behavior :proxy
                            :expected-return 0.0
                            :volatility 0.8
                            :max-weight 0.05
                            :proxy {:instrument-ids ["perp:BTC" "perp:ETH"]
                                    :relationship-strength :medium
                                    :prior-weights nil}}}}
        readiness {:request {:requested-universe universe
                             :universe [btc-instrument eth-instrument]
                             :objective {:kind :minimum-variance}
                             :history {:eligible-instruments [btc-instrument eth-instrument]
                                       :history-window {:return-observations 900}}
                             :history-assumptions
                             {"perp:NEW" {:behavior :proxy
                                          :volatility 0.8
                                          :max-weight 0.05
                                          :proxy-instrument-ids ["perp:BTC" "perp:ETH"]
                                          :proxy-prior-weights nil
                                          :relationship-strength :medium
                                          :regression-series (blended-overlap-series 248)}}}
                   :blocking-warnings []}
        model (view-model/history-assumption-cards
               {} draft readiness
               {:status :succeeded
                :request-signature {:universe universe}}
               {:percent-label (fn [value] (str (js/Math.round (* 100 value)) "%"))})
        card (first (:cards model))
        final-by-id (into {}
                          (map (juxt :instrument-id :weight))
                          (get-in card [:final-basket :rows]))]
    (is (= [50.0 50.0] (mapv :weight-percent (:prior-basket card)))
        "The prior stays the honest equal fallback...")
    (is (= :estimated (get-in card [:regression-estimate :status])))
    (is (str/includes? (get-in card [:regression-estimate :summary]) "248 observations"))
    (is (> (get final-by-id "perp:ETH") 0.53)
        "...while the final basket tilts toward the proxy the asset tracks.")
    (is (> (get final-by-id "perp:ETH") (get final-by-id "perp:BTC")))
    (is (> (get-in card [:final-basket :confidence-q]) 0.4))
    (is (not= (mapv :weight-percent (:prior-basket card))
              (mapv :weight-percent (get-in card [:final-basket :rows]))))
    (is (str/starts-with? (:final-model-line card) "Final model: BTC ")
        "The summary strip names the final basket weights.")
    (is (not (str/includes? (:final-model-line card) "BTC 50%"))
        "The strip carries the adjusted split, not the prior.")))

(deftest history-assumption-rail-model-summarizes-configured-assets-test
  (let [universe [btc-instrument new-perp-instrument]
        draft {:universe universe
               :objective {:kind :minimum-variance}
               :history-assumptions
               {"perp:NEW" {:behavior :proxy
                            :expected-return 0.0
                            :volatility 0.8
                            :max-weight 0.05
                            :proxy {:instrument-ids ["perp:BTC"]
                                    :relationship-strength :medium
                                    :prior-weights nil}}}}
        readiness {:request {:requested-universe universe
                             :universe [btc-instrument]
                             :objective {:kind :minimum-variance}
                             :history {:eligible-instruments [btc-instrument]
                                       :history-window {:return-observations 500}}}
                   :blocking-warnings []}
        model (view-model/history-assumption-rail-model
               {} draft readiness
               {:status :succeeded
                :request-signature {:universe universe}}
               {:percent-label (fn [value] (str (js/Math.round (* 100 value)) "%"))})
        row (first (:rows model))]
    (is (true? (:applicable? model)))
    (is (true? (:all-configured? model)))
    (is (= 1 (:configured-count model)))
    ;; The aggregate ready banner and the results-disclosure note are gone
    ;; (2026-07-10 comprehension pass): "ready" belongs to the run verdict +
    ;; Data health, and the disclosure fact is the folded :proxy-history-used
    ;; data note.
    (is (not (contains? model :ready-message)))
    (is (not (contains? model :disclosure-note)))
    (is (= "perp:NEW" (:instrument-id row)))
    (is (true? (:configured? row)))
    (is (string? (:summary row))
        "Each rail row carries the one-line summary its disclosure header shows.")
    (is (not-any? #(= "Status" (first %)) (:summary-pairs row))
        "No textual Status pair — the row's status chip already carries it.")
    (is (some #(= ["Proxy assets" "BTC"] %) (:summary-pairs row)))
    (is (some #(= ["Final modeled basket" "BTC 100%"] %) (:summary-pairs row))
        "The rail reports the confidence-shrunk basket that drives covariance.")
    (is (some #(= ["Relationship strength" "Medium"] %) (:summary-pairs row)))
    (is (some #(= ["Modeled volatility" "80%"] %) (:summary-pairs row)))
    (is (some #(= ["Max allocation cap" "5%"] %) (:summary-pairs row)))
    (is (some #(= ["History used" "500 days of returns · via proxies"] %)
              (:summary-pairs row))
        "The rail reports the proxy-extended covariance window, not the asset's own overlap.")
    (is (some #(= ["Calibration overlap" "No usable native returns"] %)
              (:summary-pairs row)))))

(deftest history-assumption-cards-hidden-while-history-still-loading-test
  ;; Regression: before history loads, readiness reports every asset as :missing
  ;; (nothing aligned yet). A fully-historied asset (e.g. DOGE) must NOT be carded
  ;; until its history has actually been fetched.
  (let [draft {:universe [btc-instrument new-perp-instrument]
               :objective {:kind :minimum-variance}
               :history-assumptions {}}
        readiness {:request {:requested-universe [btc-instrument new-perp-instrument]
                             :universe []
                             :objective {:kind :minimum-variance}}
                   :blocking-warnings []}
        loading (view-model/history-assumption-cards
                 {} draft readiness {:status :loading} {})
        idle (view-model/history-assumption-cards
              {} draft readiness {:status :idle} {})]
    (is (false? (:applicable? loading)))
    (is (empty? (:cards loading)))
    (is (false? (:applicable? idle)))
    (is (empty? (:cards idle)))))

(deftest history-assumption-cards-read-observations-from-aligned-history-test
  ;; Production regression (live-session finding, 2026-07-05): real sessions load
  ;; optimizer history through the api-v2 backend, so the state-side
  ;; :candle-history-by-coin map is EMPTY and the old state-only observation
  ;; count returned nil for every asset - thin-history detection never fired.
  ;; The count must come from the aligned history's raw per-instrument series
  ;; carried on the readiness request.
  (let [soph {:instrument-id "perp:SOPH" :market-type :perp :coin "SOPH"}
        universe [btc-instrument soph]
        readiness {:request {:requested-universe universe
                             :universe universe
                             :objective {:kind :minimum-variance}
                             :history {:eligible-instruments universe
                                       :raw-price-series-by-instrument
                                       {"perp:BTC" (vec (repeat 1079 {:close 1}))
                                        "perp:SOPH" (vec (repeat 180 {:close 1}))}}}
                   :blocking-warnings []}
        model (view-model/history-assumption-cards
               {} ;; NO state-side candles - the api-v2 production shape.
               {:universe universe
                :objective {:kind :minimum-variance}
                :history-assumptions {}}
               readiness
               {:status :succeeded
                :request-signature {:universe universe}}
               {})
        card (first (:cards model))]
    (is (= ["perp:SOPH"] (mapv :instrument-id (:cards model)))
        "The ~6-month asset is carded from the aligned history's row count alone.")
    (is (= 180 (:observations card)))
    (is (= [{:instrument-id "perp:BTC"
             :label "BTC"
             :observations 1079
             :option-label "BTC (1079 days)"}]
           (:addable-assets model))
        "The full-history asset remains manually addable (with its aligned day count in the option label); the carded one is not listed twice.")))

(deftest history-assumption-cards-flag-short-but-present-history-test
  ;; An asset with thin-but-present history (e.g. SKR's ~75 daily candles) clears the
  ;; engine's tiny minimum (status :sufficient) but is below the user-facing threshold,
  ;; so it should still be offered a card; an asset with ample history should not.
  (let [skr {:instrument-id "perp:SKR" :market-type :perp :coin "SKR"}
        draft {:universe [btc-instrument skr]
               :objective {:kind :minimum-variance}
               :history-assumptions {}}
        readiness {:request {:requested-universe [btc-instrument skr]
                             :universe [btc-instrument skr]
                             :objective {:kind :minimum-variance}}
                   :blocking-warnings []}
        state {:portfolio
               {:optimizer
                {:history-data
                 ;; SKR has only ~75 daily candles; BTC has the full ~1-year fetch cap.
                 {:candle-history-by-coin
                  {"SKR" (vec (repeat 75 {:time 1 :close "1"}))
                   "BTC" (vec (repeat 365 {:time 1 :close "1"}))}}}}}
        load-state {:status :succeeded
                    :request-signature {:universe [btc-instrument skr]}}
        model (view-model/history-assumption-cards state draft readiness load-state {})
        carded-ids (set (map :instrument-id (:cards model)))]
    (is (contains? carded-ids "perp:SKR")
        "Short-but-present history is flagged for assumptions.")
    (is (not (contains? carded-ids "perp:BTC"))
        "An asset with ample history is not flagged.")))

(deftest history-assumption-cards-addable-assets-sort-by-day-count-test
  ;; The manual entry dropdown ranks by native return-day count ASCENDING so the
  ;; assets limiting the shared covariance window sit at the top - the user
  ;; proxies out the most limiting asset first instead of guessing. Unknown
  ;; counts (history still loading) sink to the bottom with a bare label.
  (let [sol {:instrument-id "perp:SOL" :market-type :perp :coin "SOL"}
        pending {:instrument-id "perp:PEND" :market-type :perp :coin "PEND"}
        universe [btc-instrument sol pending]
        readiness {:request {:requested-universe universe
                             :universe universe
                             :objective {:kind :minimum-variance}
                             :history {:eligible-instruments universe
                                       :raw-price-series-by-instrument
                                       {"perp:BTC" (vec (repeat 1079 {:close 1}))
                                        "perp:SOL" (vec (repeat 403 {:close 1}))}}}
                   :blocking-warnings []}
        model (view-model/history-assumption-cards
               {}
               {:universe universe
                :objective {:kind :minimum-variance}
                :history-assumptions {}}
               readiness
               {:status :succeeded
                :request-signature {:universe universe}}
               {})]
    (is (= [{:instrument-id "perp:SOL"
             :label "SOL"
             :observations 403
             :option-label "SOL (403 days)"}
            {:instrument-id "perp:BTC"
             :label "BTC"
             :observations 1079
             :option-label "BTC (1079 days)"}
            {:instrument-id "perp:PEND"
             :label "PEND"
             :observations nil
             :option-label "PEND"}]
           (:addable-assets model))
        "Fewest days first; unknown counts last with no claimed count.")))

(deftest history-assumption-cards-separate-engine-backing-from-acceptance-test
  ;; Two different questions, two different keys. `:engine-applied?` /
  ;; `:status :configured` answer "can the engine back this?"; `:configured?`
  ;; additionally requires the user's yes. The queue's "settled" is the latter,
  ;; so conflating them would mark an entry the user edited since accepting as
  ;; done and stop asking about it.
  (let [conservative {:behavior :conservative
                      :expected-return 0.0
                      :volatility 0.8
                      :max-weight 0.03
                      :correlation-floor 0.75}
        acknowledged (assoc conservative
                            :metadata {:source :user :acknowledged? true})
        draft {:universe [btc-instrument new-perp-instrument]
               :objective {:kind :minimum-variance}
               :history-assumptions {"perp:NEW" acknowledged}}
        readiness {:request {:requested-universe [btc-instrument new-perp-instrument]
                             :universe [btc-instrument]
                             :objective {:kind :minimum-variance}}
                   :blocking-warnings []}
        card-for (fn [assumptions]
                   (first (:cards (view-model/history-assumption-cards
                                   {}
                                   (assoc draft :history-assumptions assumptions)
                                   readiness loaded-state-with-new {}))))]
    (let [card (card-for {"perp:NEW" acknowledged})]
      (is (= :configured (:status card)))
      (is (true? (:engine-applied? card)))
      (is (true? (:acknowledged? card)))
      (is (true? (:configured? card)) "Complete AND accepted."))
    (let [card (card-for {"perp:NEW" conservative})]
      (is (= :configured (:status card)))
      (is (true? (:engine-applied? card)) "The engine can back it either way.")
      (is (false? (:acknowledged? card)))
      (is (false? (:configured? card)) "...but the user never said yes."))
    (let [card (card-for {})]
      (is (false? (:engine-applied? card)))
      (is (false? (:configured? card))))))

(deftest history-assumption-cards-unconfigured-sort-above-configured-test
  ;; A card that still needs assumptions floats above a configured one even when
  ;; the configured asset comes first in universe order, so the asset the user
  ;; must act on is never buried beneath a finished one. Within each group the
  ;; universe order is preserved (stable sort), so the ordering stays predictable.
  (let [complete-entry {:behavior :conservative
                        :expected-return 0.0
                        :volatility 0.8
                        :max-weight 0.03
                        :correlation-floor 0.75}
        incomplete-entry {:behavior :conservative
                          :expected-return nil
                          :volatility nil
                          :max-weight 0.03
                          :correlation-floor 0.75}
        inst (fn [id] {:instrument-id id :market-type :perp :coin id})
        ;; Universe interleaves configured (A, C) and unconfigured (B, D).
        universe [(inst "perp:ACFG") (inst "perp:BNEED")
                  (inst "perp:CCFG") (inst "perp:DNEED")]
        draft {:universe universe
               :objective {:kind :minimum-variance}
               :history-assumptions {"perp:ACFG" complete-entry
                                     "perp:BNEED" incomplete-entry
                                     "perp:CCFG" complete-entry
                                     "perp:DNEED" incomplete-entry}}
        readiness {:request {:requested-universe universe
                             :universe universe
                             :objective {:kind :minimum-variance}}
                   :blocking-warnings []}
        cards (:cards (view-model/history-assumption-cards
                       {} draft readiness
                       {:status :succeeded :request-signature {:universe universe}}
                       {}))]
    (is (= ["perp:BNEED" "perp:DNEED" "perp:ACFG" "perp:CCFG"]
           (mapv :instrument-id cards))
        "Unconfigured cards float to the top; within each group universe order is preserved.")
    (is (= [:incomplete :incomplete :configured :configured]
           (mapv :status cards)))))

