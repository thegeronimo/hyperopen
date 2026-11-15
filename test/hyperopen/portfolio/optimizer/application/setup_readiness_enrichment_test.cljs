(ns hyperopen.portfolio.optimizer.application.setup-readiness-enrichment-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]))

(defn- deep-merge
  [& maps]
  (apply merge-with
         (fn [left right]
           (if (and (map? left)
                    (map? right))
             (deep-merge left right)
             right))
         maps))

(defn- optimizer-state
  [overrides]
  (deep-merge
   {:portfolio
    {:optimizer
     {:draft
      {:objective {:kind :minimum-variance}
       :return-model {:kind :historical-mean}
       :risk-model {:kind :diagonal-shrink}
       :constraints {:long-only? true}}
      :runtime {:as-of-ms 2500
                :stale-after-ms 5000
                :funding-periods-per-year 1095}}}}
   overrides))

(deftest build-readiness-enriches-undecorated-draft-from-discovery-test
  (let [readiness (setup-readiness/build-readiness
                   (optimizer-state
                    {:portfolio
                     {:optimizer
                      {:draft {:universe [{:instrument-id "perp:BTC"
                                           :market-type :perp
                                           :coin "BTC"
                                           :name "Bitcoin"}
                                          {:instrument-id "perp:BAD"
                                           :market-type :perp
                                           :coin "BAD"
                                           :name "Bad Perp"}]}
                       :history-discovery
                       {:backend-id-by-local-id {"perp:BTC" "hl:perp:BTC"
                                                 "perp:BAD" "hl:perp:BAD"}
                        :instruments-by-backend-id
                        {"hl:perp:BTC" {:instrument-kind :hl-perp}
                         "hl:perp:BAD" {:instrument-kind :hl-perp}}}
                       :history-data
                       {:api-v2-history
                        {:status :partial
                         :warnings [{:code :proxy-validation-failed
                                     :instrument-id "hl:perp:BAD"}]
                         :common-calendar [1000 2000]
                         :return-calendar [2000]
                         :aligned-returns-by-instrument
                         {"perp:BTC" {:returns [0.1]}
                          "perp:BAD" {:returns [0.1]}}
                         :series-by-instrument
                         {"perp:BTC" {:local-instrument-id "perp:BTC"
                                      :instrument-id "hl:perp:BTC"
                                      :lineage-kind :native
                                      :points [{:time-ms 1000
                                                :close 100
                                                :return nil}
                                               {:time-ms 2000
                                                :close 110
                                                :return 0.1}]
                                      :funding {:status :available
                                                :annualized-carry 0}}
                          "perp:BAD" {:local-instrument-id "perp:BAD"
                                      :instrument-id "hl:perp:BAD"
                                      :lineage-kind :stitched-native-proxy
                                      :points [{:time-ms 1000
                                                :close 50
                                                :return nil}
                                               {:time-ms 2000
                                                :close 55
                                                :return 0.1}]
                                      :funding {:status :available
                                                :annualized-carry 0}}}}}}}}))
        request (:request readiness)]
    (is (= ["hl:perp:BTC" "hl:perp:BAD"]
           (mapv :optimizer-history/instrument-id (:requested-universe request)))
        "Requested-universe entries are decorated from discovery at read time.")
    (is (= ["perp:BTC"]
           (mapv :instrument-id (:universe request)))
        "The backend-keyed hard warning excludes its asset from alignment.")
    (is (= :blocked (:status readiness)))
    (let [blocking (group-by :code (:blocking-warnings readiness))
          proxy-warning (first (get blocking :proxy-validation-failed))]
      (is (= "perp:BAD" (:instrument-id proxy-warning))
          "The warning's backend id is canonicalized to the draft instrument id.")
      (is (= "Bad Perp: proxy history failed backend validation."
             (:message proxy-warning))
          "The readiness panel labels the warning with the asset, not the raw backend id."))))
