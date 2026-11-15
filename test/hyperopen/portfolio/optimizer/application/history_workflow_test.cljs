(ns hyperopen.portfolio.optimizer.application.history-workflow-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.history-workflow :as workflow]))

(def btc-instrument
  {:instrument-id "perp:BTC"
   :market-type :perp
   :coin "BTC"})

(def eth-instrument
  {:instrument-id "perp:ETH"
   :market-type :perp
   :coin "ETH"})

(def hype-instrument
  {:instrument-id "perp:HYPE"
   :market-type :perp
   :coin "HYPE"})

(def xyz-gold-instrument
  {:instrument-id "perp:xyz:GOLD"
   :market-type :perp
   :coin "xyz:GOLD"
   :base "GOLD"
   :dex "xyz"
   :hip3? true})

(def queued-status
  {:status :queued
   :started-at-ms nil
   :completed-at-ms nil
   :error nil
   :warnings []})

(defn- prefetch-state
  []
  {:portfolio {:optimizer
               {:draft {:universe [btc-instrument eth-instrument]}
                :history-data {:candle-history-by-coin
                               {"SOL" [{:time 1000 :close "20"}]}
                               :funding-history-by-coin
                               {"SOL" [{:time-ms 1000 :funding-rate-raw 0}]}}
                :history-prefetch {:queue [btc-instrument eth-instrument]
                                   :active-instrument-id nil
                                   :by-instrument-id {"perp:BTC" queued-status
                                                      "perp:ETH" queued-status}}
                :runtime {:as-of-ms 3000
                          :stale-after-ms 60000}}}})

(deftest begin-selection-prefetch-starts-first-queued-instrument-test
  (let [result (workflow/begin-selection-prefetch
                {:state (prefetch-state)
                 :opts {:source :selection-prefetch
                        :queue? true
                        :merge? true}
                 :now-ms 1000})]
    (is (= "perp:BTC"
           (get-in result [:state :portfolio :optimizer :history-prefetch :active-instrument-id])))
    (is (= :loading
           (get-in result
                   [:state
                    :portfolio
                    :optimizer
                    :history-prefetch
                    :by-instrument-id
                    "perp:BTC"
                    :status])))
    (is (= [{:command/type :optimizer.workflow/request-history-bundle
             :source :selection-prefetch
             :instrument-id "perp:BTC"
             :instrument-ids ["perp:BTC" "perp:ETH"]
             :request-signature (get-in result
                                        [:state
                                         :portfolio
                                         :optimizer
                                         :history-load-state
                                         :request-signature])
             :request (get-in result [:commands 0 :request])}]
           (:commands result)))
    (is (= ["perp:BTC" "perp:ETH"]
           (mapv :instrument-id (get-in result [:commands 0 :request :universe]))))))

(deftest begin-selection-prefetch-batches-queued-instruments-test
  (let [result (workflow/begin-selection-prefetch
                {:state (prefetch-state)
                 :opts {:source :selection-prefetch
                        :queue? true
                        :merge? true}
                 :now-ms 1000})]
    (is (= ["perp:BTC" "perp:ETH"]
           (mapv :instrument-id (get-in result [:commands 0 :request :universe]))))
    (is (= {:status :loading
            :started-at-ms 1000
            :completed-at-ms nil
            :error nil
            :warnings []}
           (get-in result
                   [:state
                    :portfolio
                    :optimizer
                    :history-prefetch
                    :by-instrument-id
                    "perp:BTC"])))
    (is (= :loading
           (get-in result
                   [:state
                    :portfolio
                    :optimizer
                    :history-prefetch
                    :by-instrument-id
                    "perp:ETH"
                    :status])))))

(deftest begin-selection-prefetch-enriches-queued-instrument-from-discovery-test
  (let [state (assoc-in
               (prefetch-state)
               [:portfolio :optimizer :history-discovery]
               {:status :partial
                :backend-id-by-local-id {"perp:BTC" "hl:perp:BTC"}
                :instruments-by-backend-id
                {"hl:perp:BTC" {:instrument-id "hl:perp:BTC"
                                :display-symbol "BTC"
                                :instrument-kind :hl-perp
                                :history {:status :available
                                          :quality-status :passed}}}})
        result (workflow/begin-selection-prefetch
                {:state state
                 :opts {:source :selection-prefetch
                        :queue? true
                        :merge? true}
                 :now-ms 1000})
        request-instrument (get-in result [:commands 0 :request :universe 0])]
    (is (= "perp:BTC" (:instrument-id request-instrument)))
    (is (= "hl:perp:BTC"
           (:optimizer-history/instrument-id request-instrument)))
    (is (= :hl-perp
           (:optimizer-history/instrument-kind request-instrument)))))

(deftest begin-selection-prefetch-enriches-hip3-instrument-from-discovery-alias-test
  (let [state (assoc-in
               (prefetch-state)
               [:portfolio :optimizer]
               {:draft {:universe [xyz-gold-instrument]}
                :history-prefetch {:queue [xyz-gold-instrument]
                                   :active-instrument-id nil
                                   :by-instrument-id {"perp:xyz:GOLD" queued-status}}
                :runtime {:as-of-ms 3000
                          :stale-after-ms 60000}
                :history-discovery
                {:status :partial
                 :backend-id-by-local-id {"hip3:xyz:GOLD" "hl:hip3:xyz:GOLD"}
                 :instruments-by-backend-id
                 {"hl:hip3:xyz:GOLD"
                  {:instrument-id "hl:hip3:xyz:GOLD"
                   :display-symbol "xyz:GOLD"
                   :instrument-kind :hl-hip3
                   :history {:status :stale
                             :quality-status :passed}}}}})
        result (workflow/begin-selection-prefetch
                {:state state
                 :opts {:source :selection-prefetch
                        :queue? true
                        :merge? true}
                 :now-ms 1000})
        request-instrument (get-in result [:commands 0 :request :universe 0])]
    (is (= "perp:xyz:GOLD" (:instrument-id request-instrument)))
    (is (= "hl:hip3:xyz:GOLD"
           (:optimizer-history/instrument-id request-instrument)))
    (is (= :hl-hip3
           (:optimizer-history/instrument-kind request-instrument)))))

(deftest begin-history-load-carries-current-portfolio-universe-separately-test
  (let [state (-> (prefetch-state)
                  (assoc-in [:webdata2 :clearinghouseState]
                            {:marginSummary {:accountValue "1000"}
                             :assetPositions [{:position {:coin "HYPE"
                                                          :szi "1"
                                                          :positionValue "250"}}]})
                  (assoc-in [:asset-selector :market-by-key]
                            {"perp:HYPE" {:key "perp:HYPE"
                                          :market-type :perp
                                          :coin "HYPE"
                                          :symbol "HYPE"}})
                  (assoc-in [:portfolio :optimizer :history-discovery]
                            {:status :partial
                             :backend-id-by-local-id {"perp:HYPE" "hl:perp:HYPE"}
                             :instruments-by-backend-id
                             {"hl:perp:HYPE" {:instrument-id "hl:perp:HYPE"
                                             :display-symbol "HYPE"
                                             :instrument-kind :hl-perp
                                             :history {:status :available
                                                       :quality-status :passed}}}}))
        result (workflow/begin-history-load
                {:state state
                 :opts {}
                 :now-ms 1000})
        request (get-in result [:commands 0 :request])]
    (is (= ["perp:BTC" "perp:ETH"]
           (mapv :instrument-id (:universe request))))
    (is (= ["perp:HYPE"]
           (mapv :instrument-id (:current-portfolio-universe request))))
    (is (= ["hl:perp:HYPE"]
           (mapv :optimizer-history/instrument-id
                 (:current-portfolio-universe request))))))

(deftest complete-selection-prefetch-failure-continues-with-next-queued-instrument-test
  (let [started (workflow/begin-selection-prefetch
                 {:state (prefetch-state)
                  :opts {:source :selection-prefetch
                         :queue? true
                         :merge? true}
                  :now-ms 1000})
        signature (get-in started
                          [:state
                           :portfolio
                           :optimizer
                           :history-load-state
                           :request-signature])
        result (workflow/complete-selection-prefetch
                {:state (:state started)
                 :instrument-id "perp:BTC"
                 :request-signature signature
                 :completed-at-ms 1100
                 :error {:message "history boom"}
                 :opts {:source :selection-prefetch
                        :queue? true
                        :merge? true}})]
    (is (= :failed
           (get-in result
                   [:state
                    :portfolio
                    :optimizer
                    :history-prefetch
                    :by-instrument-id
                    "perp:BTC"
                    :status])))
    (is (= "perp:ETH"
           (get-in result [:state :portfolio :optimizer :history-prefetch :active-instrument-id])))
    (is (= ["perp:ETH"]
           (mapv :instrument-id
                 (get-in result [:state :portfolio :optimizer :history-prefetch :queue]))))
    (is (= :optimizer.workflow/request-history-bundle
           (get-in result [:commands 0 :command/type])))
    (is (= ["perp:ETH"]
           (mapv :instrument-id (get-in result [:commands 0 :request :universe]))))
    (is (= {"SOL" [{:time 1000 :close "20"}]}
           (get-in result
                   [:state :portfolio :optimizer :history-data :candle-history-by-coin])))))

(deftest complete-selection-prefetch-success-merges-api-v2-history-by-local-id-test
  (let [started (workflow/begin-selection-prefetch
                 {:state (prefetch-state)
                  :opts {:source :selection-prefetch
                         :queue? true
                         :merge? true}
                  :now-ms 1000})
        signature (get-in started
                          [:state
                           :portfolio
                           :optimizer
                           :history-load-state
                           :request-signature])
        result (workflow/complete-selection-prefetch
                {:state (:state started)
                 :instrument-id "perp:BTC"
                 :request-signature signature
                 :completed-at-ms 1100
                 :bundle {:api-v2-history
                          {:status :ok
                           :series-by-instrument
                           {"perp:BTC" {:local-instrument-id "perp:BTC"
                                        :instrument-id "hl:perp:BTC"
                                        :lineage-kind :native
                                        :points [{:time-ms 1000
                                                  :close 100
                                                  :return nil}
                                                 {:time-ms 2000
                                                  :close 110
                                                  :return 0.1}]}}
                           :warnings []}
                          :warnings []}
                 :opts {:source :selection-prefetch
                        :queue? true
                        :merge? true}})]
    (is (= "hl:perp:BTC"
           (get-in result
                   [:state
                    :portfolio
                    :optimizer
                    :history-data
                    :api-v2-history
                    :series-by-instrument
                    "perp:BTC"
                    :instrument-id])))
    (is (= "perp:ETH"
           (get-in result [:state :portfolio :optimizer :history-prefetch :active-instrument-id])))))

(deftest complete-selection-prefetch-success-finishes-batched-queued-instruments-test
  (let [started (workflow/begin-selection-prefetch
                 {:state (prefetch-state)
                  :opts {:source :selection-prefetch
                         :queue? true
                         :merge? true}
                  :now-ms 1000})
        signature (get-in started
                          [:state
                           :portfolio
                           :optimizer
                           :history-load-state
                           :request-signature])
        result (workflow/complete-selection-prefetch
                {:state (:state started)
                 :instrument-id "perp:BTC"
                 :instrument-ids ["perp:BTC" "perp:ETH"]
                 :request-signature signature
                 :completed-at-ms 1100
                 :bundle {:api-v2-history
                          {:status :ok
                           :series-by-instrument
                           {"perp:BTC" {:instrument-id "hl:perp:BTC"}
                            "perp:ETH" {:instrument-id "hl:perp:ETH"}}
                           :warnings []}
                          :warnings []}
                 :opts {:source :selection-prefetch
                        :queue? true
                        :merge? true}})]
    (is (= []
           (get-in result [:state :portfolio :optimizer :history-prefetch :queue])))
    (is (nil?
         (get-in result [:state :portfolio :optimizer :history-prefetch :active-instrument-id])))
    (is (= {:status :succeeded
            :started-at-ms 1000
            :completed-at-ms 1100
            :error nil
            :warnings []}
           (get-in result
                   [:state
                    :portfolio
                    :optimizer
                    :history-prefetch
                    :by-instrument-id
                    "perp:ETH"])))
    (is (= []
           (:commands result)))))

(def sol-reference-instrument
  {:instrument-id "perp:SOL"
   :market-type :perp
   :coin "SOL"
   :optimizer-history/instrument-id "hl:perp:SOL"})

(defn- reference-proxy-prefetch-state
  "A queued reference-only proxy (perp:SOL) outside the draft universe, with the
  api-v2 cache already populated — the state right after picking a catalog proxy."
  []
  (-> (prefetch-state)
      (assoc-in [:portfolio :optimizer :draft :proxy-reference-instruments]
                [sol-reference-instrument])
      (assoc-in [:portfolio :optimizer :history-data :api-v2-history]
                {:status :partial
                 :series-by-instrument {"perp:BTC" {:instrument-id "hl:perp:BTC"}}
                 :aligned-returns-by-instrument {}})
      (assoc-in [:portfolio :optimizer :history-prefetch :queue]
                [sol-reference-instrument])
      (assoc-in [:portfolio :optimizer :history-prefetch :by-instrument-id]
                {"perp:SOL" queued-status})))

(deftest begin-selection-prefetch-includes-reference-proxy-instruments-test
  (let [result (workflow/begin-selection-prefetch
                {:state (reference-proxy-prefetch-state)
                 :opts {:source :selection-prefetch :queue? true :merge? true}
                 :now-ms 2000})]
    (is (= ["perp:SOL"]
           (mapv :instrument-id
                 (get-in result [:commands 0 :request :universe]))))))

(deftest history-request-skips-references-already-in-universe-test
  (let [state (assoc-in (prefetch-state)
                        [:portfolio :optimizer :draft :proxy-reference-instruments]
                        [btc-instrument sol-reference-instrument])
        request (workflow/history-request state {})]
    (is (= ["perp:BTC" "perp:ETH" "perp:SOL"]
           (mapv :instrument-id (:universe request)))
        "The in-universe reference is not fetched twice; the true reference rides along.")))

(deftest complete-selection-prefetch-merges-reference-proxy-series-test
  (let [started (workflow/begin-selection-prefetch
                 {:state (reference-proxy-prefetch-state)
                  :opts {:source :selection-prefetch :queue? true :merge? true}
                  :now-ms 2000})
        signature (get-in started
                          [:state :portfolio :optimizer :history-load-state
                           :request-signature])
        result (workflow/complete-selection-prefetch
                {:state (:state started)
                 :instrument-id "perp:SOL"
                 :request-signature signature
                 :completed-at-ms 2100
                 :bundle {:api-v2-history
                          {:status :ok
                           :series-by-instrument
                           {"perp:SOL" {:local-instrument-id "perp:SOL"
                                        :instrument-id "hl:perp:SOL"
                                        :lineage-kind :native
                                        :points [{:time-ms 2000
                                                  :close 22
                                                  :return 0.1}]}}
                           :warnings []}
                          :warnings []}
                 :opts {:source :selection-prefetch :queue? true :merge? true}})]
    (is (= "hl:perp:SOL"
           (get-in result
                   [:state :portfolio :optimizer :history-data :api-v2-history
                    :series-by-instrument "perp:SOL" :instrument-id]))
        "The reference proxy's fetched series merges into history data.")
    (is (= :succeeded
           (get-in result
                   [:state :portfolio :optimizer :history-prefetch
                    :by-instrument-id "perp:SOL" :status]))
        "Its prefetch status survives the cleanup-to-universe pass.")))

(defn- cached-prefetch-state
  "Api-v2 cache populated for perp:BTC, perp:ETH queued. `btc-series` shapes
  whether BTC counts as aligned-only (empty points) or point-backed."
  [btc-series]
  (-> (prefetch-state)
      (assoc-in [:portfolio
                 :optimizer
                 :history-data
                 :api-v2-history]
                {:status :partial
                 :series-by-instrument {"perp:BTC" btc-series}
                 :aligned-returns-by-instrument
                 {"perp:BTC" {:instrument-id "hl:perp:BTC"
                              :returns [0.01]}}})
      (assoc-in [:portfolio
                 :optimizer
                 :history-prefetch
                 :queue]
                [eth-instrument])
      (assoc-in [:portfolio
                 :optimizer
                 :history-prefetch
                 :by-instrument-id]
                {"perp:BTC" {:status :succeeded}
                 "perp:ETH" queued-status})))

(deftest begin-selection-prefetch-requests-current-universe-after-api-v2-cache-test
  (let [result (workflow/begin-selection-prefetch
                {:state (cached-prefetch-state {:instrument-id "hl:perp:BTC"})
                 :opts {:source :selection-prefetch
                        :queue? true
                        :merge? true}
                 :now-ms 2000})]
    (is (= ["perp:BTC" "perp:ETH"]
           (mapv :instrument-id
                 (get-in result [:commands 0 :request :universe]))))))

(deftest begin-selection-prefetch-fetches-only-queued-delta-after-api-v2-cache-test
  (let [result (workflow/begin-selection-prefetch
                {:state (cached-prefetch-state
                         {:instrument-id "hl:perp:BTC"
                          :lineage-kind :native
                          :points [{:time-ms 1000 :close 100 :return nil}
                                   {:time-ms 2000 :close 101 :return 0.01}]})
                 :opts {:source :selection-prefetch
                        :queue? true
                        :merge? true}
                 :now-ms 2000})]
    (is (= ["perp:ETH"]
           (mapv :instrument-id
                 (get-in result [:commands 0 :request :universe]))))
    (is (= ["perp:ETH"]
           (get-in result [:commands 0 :instrument-ids])))))
