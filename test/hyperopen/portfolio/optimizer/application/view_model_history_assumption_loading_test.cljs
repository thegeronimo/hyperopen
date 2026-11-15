(ns hyperopen.portfolio.optimizer.application.view-model-history-assumption-loading-test
  "Loading-visibility coverage for the proxy workflow cards (own namespace:
  view-model-history-assumption-cards-test sits at its size cap, 2026-07-07).

  While proxy history is still fetching, the card must say so instead of
  passing mid-flight \"no data\" verdicts off as final — and once the load
  settles, an unusable proxy must never keep claiming \"loading\"."
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.view-model :as view-model]))

(def ^:private btc-instrument
  {:instrument-id "perp:BTC" :market-type :perp :coin "BTC"})

(def ^:private eth-instrument
  {:instrument-id "perp:ETH" :market-type :perp :coin "ETH"})

(def ^:private new-perp-instrument
  {:instrument-id "perp:NEW" :market-type :perp :coin "NEW"})

(def ^:private loading-visibility-draft
  {:universe [btc-instrument eth-instrument new-perp-instrument]
   :objective {:kind :minimum-variance}
   :constraints {:max-asset-weight 0.5}
   :history-assumptions
   {"perp:NEW" {:behavior :proxy
                :expected-return 0.0
                :volatility 0.8
                :max-weight 0.05
                :proxy {:instrument-ids ["perp:BTC" "perp:ETH"]
                        :relationship-strength :high
                        :prior-weights nil}}}})

(defn- diagnostic-cell
  [card key*]
  (some #(when (= key* (:key %)) %) (:diagnostics card)))

(deftest history-assumption-cards-loading-visibility-test
  (let [universe (:universe loading-visibility-draft)
        readiness {:request {:requested-universe universe
                             :universe []
                             :objective {:kind :minimum-variance}}
                   :blocking-warnings []}
        model (view-model/history-assumption-cards
               {:optimizer {:history-prefetch
                            {:queue ["perp:ETH"]
                             :active-instrument-id "perp:BTC"
                             :by-instrument-id {"perp:BTC" {:status :loading}
                                                "perp:ETH" {:status :queued}}}}}
               loading-visibility-draft readiness {:status :loading} {})
        card (first (:cards model))]
    (is (= 1 (:history-loading-count model))
        "The section-level banner count sees the in-flight card.")
    (is (true? (:history-loading? card)))
    (is (every? :loading? (:selected-proxies card))
        "Chips for proxies whose fetch is in flight read loading.")
    (is (= :skipped (get-in card [:regression-estimate :status])))
    (is (true? (get-in card [:regression-estimate :loading?])))
    (is (str/includes? (get-in card [:regression-estimate :message])
                       "Waiting for proxy history to load")
        "A skip while fetching is not presented as a verdict.")
    (is (= "Loading history…" (:value (diagnostic-cell card :history-window)))
        "The covariance-window cell never claims 'No usable native returns' mid-flight.")))

(deftest history-assumption-cards-loading-settles-to-honest-verdicts-test
  (let [universe (:universe loading-visibility-draft)
        settled-load {:status :succeeded
                      :request-signature {:universe universe}}
        usable-readiness {:request {:requested-universe universe
                                    :universe [btc-instrument eth-instrument]
                                    :objective {:kind :minimum-variance}
                                    :history {:eligible-instruments
                                              [btc-instrument eth-instrument]}}
                          :blocking-warnings []}
        unusable-readiness {:request {:requested-universe universe
                                      :universe []
                                      :objective {:kind :minimum-variance}
                                      :history {:eligible-instruments []}}
                            :blocking-warnings []}
        usable (view-model/history-assumption-cards
                {} loading-visibility-draft usable-readiness settled-load {})
        unusable (view-model/history-assumption-cards
                  {} loading-visibility-draft unusable-readiness settled-load {})
        usable-card (first (:cards usable))
        unusable-card (first (:cards unusable))]
    (is (zero? (:history-loading-count usable)))
    (is (false? (:history-loading? usable-card)))
    (is (not-any? :loading? (:selected-proxies usable-card)))
    (is (str/includes? (get-in usable-card [:regression-estimate :message])
                       "No return overlap")
        "After a settled load the honest skip verdict returns.")
    (is (= "No usable native returns"
           (:value (diagnostic-cell usable-card :history-window))))
    (is (false? (:history-loading? unusable-card))
        "A proxy still unusable after a settled load is a problem, not 'loading'.")
    (is (not-any? :loading? (:selected-proxies unusable-card)))))

(deftest history-assumption-rail-loading-visibility-test
  (let [universe (:universe loading-visibility-draft)
        readiness {:request {:requested-universe universe
                             :universe []
                             :objective {:kind :minimum-variance}}
                   :blocking-warnings []}
        loading (view-model/history-assumption-rail-model
                 {} loading-visibility-draft readiness {:status :loading} {})
        row (first (:rows loading))
        settled (view-model/history-assumption-rail-model
                 {} loading-visibility-draft
                 {:request {:requested-universe universe
                            :universe [btc-instrument eth-instrument]
                            :objective {:kind :minimum-variance}
                            :history {:eligible-instruments
                                      [btc-instrument eth-instrument]}}
                  :blocking-warnings []}
                 {:status :succeeded :request-signature {:universe universe}} {})
        settled-row (first (:rows settled))]
    (is (true? (:any-loading? loading)))
    (is (true? (:history-loading? row)))
    (is (false? (:all-configured? loading))
        "Mid-flight completeness (nil usable set) must not read Ready to run.")
    (is (nil? (:ready-message loading)))
    (is (not-any? #(= "Status" (first %)) (:summary-pairs row))
        "The Status pair is gone — the chip carries the loading verdict.")
    (is (some #(= ["History used" "Loading history…"] %) (:summary-pairs row)))
    (is (some #(= ["Calibration overlap" "Loading history…"] %) (:summary-pairs row)))
    (is (false? (:any-loading? settled)))
    (is (false? (:history-loading? settled-row)))
    (is (true? (:all-configured? settled))
        "Once the load settles, a complete entry reads Ready again.")
    (is (some #(= ["Calibration overlap" "No usable native returns"] %)
              (:summary-pairs settled-row))
        "Settled rows return to the honest no-native-returns label.")))
