(ns hyperopen.schema.contracts.effect-args-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.schema.contracts :as contracts]))

(deftest assert-effect-args-accepts-fetch-candle-snapshot-interval-only-test
  (is (= [:interval :1m]
         (contracts/assert-effect-args!
          :effects/fetch-candle-snapshot
          [:interval :1m]
          {:phase :test}))))

(deftest assert-effect-args-accepts-sync-active-candle-subscription-interval-test
  (is (= [:interval :1m]
         (contracts/assert-effect-args!
          :effects/sync-active-candle-subscription
          [:interval :1m]
          {:phase :test}))))

(deftest assert-effect-args-accepts-shareable-route-query-replacement-without-args-test
  (is (= []
         (contracts/assert-effect-args!
          :effects/replace-shareable-route-query
          []
          {:phase :test}))))

(deftest assert-effect-args-accepts-fetch-candle-snapshot-interval-and-bars-test
  (is (= [:interval :1m :bars 330]
         (contracts/assert-effect-args!
          :effects/fetch-candle-snapshot
          [:interval :1m :bars 330]
          {:phase :test}))))

(deftest assert-effect-args-accepts-fetch-candle-snapshot-coin-interval-and-bars-test
  (is (= [:coin "SPY" :interval :1h :bars 800]
         (contracts/assert-effect-args!
          :effects/fetch-candle-snapshot
          [:coin "SPY" :interval :1h :bars 800]
          {:phase :test}))))

(deftest assert-effect-args-accepts-fetch-candle-snapshot-detail-route-vault-address-test
  (is (= [:coin "SPY"
          :interval :1h
          :bars 800
          :detail-route-vault-address "0x1234567890abcdef1234567890abcdef12345678"]
         (contracts/assert-effect-args!
          :effects/fetch-candle-snapshot
          [:coin "SPY"
           :interval :1h
           :bars 800
           :detail-route-vault-address "0x1234567890abcdef1234567890abcdef12345678"]
          {:phase :test}))))

(deftest assert-effect-args-rejects-fetch-candle-snapshot-with-odd-kv-arity-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/fetch-candle-snapshot
        [:interval]
        {:phase :test}))))

(deftest assert-effect-args-rejects-fetch-candle-snapshot-with-unknown-key-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/fetch-candle-snapshot
        [:foo 1]
        {:phase :test}))))

(deftest assert-effect-args-rejects-fetch-candle-snapshot-with-blank-coin-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/fetch-candle-snapshot
        [:coin "   " :interval :1h]
        {:phase :test}))))

(deftest assert-effect-args-rejects-funding-history-request-id-when-not-non-negative-integer-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/api-fetch-user-funding-history
        ["abc"]
        {:phase :test}))))

(deftest assert-effect-args-rejects-order-history-request-id-when-negative-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/api-fetch-historical-orders
        [-1]
        {:phase :test}))))

(deftest assert-effect-args-accepts-confirm-api-submit-order-variant-test
  (is (= [{:variant :open-order
           :message "Submit?"
           :request {:action {:type "order"}}
           :path-values [[[:order-form-runtime :error] nil]]}]
         (contracts/assert-effect-args!
          :effects/confirm-api-submit-order
          [{:variant :open-order
            :message "Submit?"
            :request {:action {:type "order"}}
            :path-values [[[:order-form-runtime :error] nil]]}]
          {:phase :test}))))

(deftest assert-effect-args-accepts-portfolio-optimizer-execution-plan-test
  (let [plan {:scenario-id "scn_01"
              :status :ready
              :summary {:ready-count 1}
              :rows [{:instrument-id "perp:BTC"
                      :status :ready}]}]
	    (is (= [plan]
	           (contracts/assert-effect-args!
	            :effects/execute-portfolio-optimizer-plan
	            [plan]
	            {:phase :test})))))

(deftest assert-effect-args-accepts-portfolio-optimizer-tracking-refresh-test
  (is (= []
         (contracts/assert-effect-args!
          :effects/refresh-portfolio-optimizer-tracking
          []
          {:phase :test}))))

(deftest assert-effect-args-accepts-portfolio-optimizer-manual-tracking-enable-test
  (is (= []
         (contracts/assert-effect-args!
          :effects/enable-portfolio-optimizer-manual-tracking
          []
          {:phase :test}))))

(deftest assert-effect-args-validates-unlock-agent-trading-continuations-test
  (is (= []
         (contracts/assert-effect-args!
          :effects/unlock-agent-trading
          []
          {:phase :test})))
  (is (= [{:after-success-actions [[:actions/start-spectate-mode "0x123"]
                                   [:actions/stop-spectate-mode]]}]
         (contracts/assert-effect-args!
          :effects/unlock-agent-trading
          [{:after-success-actions [[:actions/start-spectate-mode "0x123"]
                                    [:actions/stop-spectate-mode]]}]
          {:phase :test})))
  (is (= [{:after-success-actions []}]
         (contracts/assert-effect-args!
          :effects/unlock-agent-trading
          [{:after-success-actions []}]
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/unlock-agent-trading
        [{}]
        {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/unlock-agent-trading
        [{:after-success-actions []
          :unexpected true}]
        {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/unlock-agent-trading
        [{:after-success-actions :actions/stop-spectate-mode}]
        {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/unlock-agent-trading
        [{:after-success-actions '()}]
        {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/unlock-agent-trading
        [{:after-success-actions [[:effects/unlock-agent-trading]]}]
        {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/unlock-agent-trading
        [{:after-success-actions [["actions/stop-spectate-mode"]]}]
        {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/unlock-agent-trading
        [{:after-success-actions []} {:after-success-actions []}]
        {:phase :test}))))

(deftest assert-effect-args-rejects-export-funding-history-csv-when-not-vector-of-maps-test
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/export-funding-history-csv
        ["not-a-vector"]
        {:phase :test}))))

(deftest assert-effect-args-validates-funding-predictability-sync-coin-test
  (is (= ["BTC"]
         (contracts/assert-effect-args!
          :effects/sync-active-asset-funding-predictability
          ["BTC"]
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/sync-active-asset-funding-predictability
        [""]
        {:phase :test}))))

(deftest assert-effect-args-validates-portfolio-optimizer-run-test
  (is (= [{:scenario-id "scenario-1"}
          {:scenario-id "scenario-1" :revision 1}]
         (contracts/assert-effect-args!
          :effects/run-portfolio-optimizer
          [{:scenario-id "scenario-1"}
           {:scenario-id "scenario-1" :revision 1}]
          {:phase :test})))
  (is (= [{:scenario-id "scenario-1"}
          {:scenario-id "scenario-1" :revision 1}
          {:computed-at-ms 123}]
         (contracts/assert-effect-args!
          :effects/run-portfolio-optimizer
          [{:scenario-id "scenario-1"}
           {:scenario-id "scenario-1" :revision 1}
           {:computed-at-ms 123}]
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/run-portfolio-optimizer
        [{:scenario-id "scenario-1"}]
        {:phase :test}))))

(deftest assert-effect-args-validates-portfolio-optimizer-pipeline-test
  (is (= []
         (contracts/assert-effect-args!
          :effects/run-portfolio-optimizer-pipeline
          []
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/run-portfolio-optimizer-pipeline
        [{:unexpected true}]
        {:phase :test}))))

(deftest assert-effect-args-validates-portfolio-optimizer-history-load-test
  (is (= []
         (contracts/assert-effect-args!
          :effects/load-portfolio-optimizer-history
          []
          {:phase :test})))
  (is (= [{:bars 180}]
         (contracts/assert-effect-args!
          :effects/load-portfolio-optimizer-history
          [{:bars 180}]
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
       :effects/load-portfolio-optimizer-history
        ["bad"]
        {:phase :test}))))

(deftest assert-effect-args-validates-portfolio-optimizer-scenario-loads-test
  (is (= []
         (contracts/assert-effect-args!
          :effects/load-portfolio-optimizer-scenario-index
          []
          {:phase :test})))
  (is (= [{:source :route}]
         (contracts/assert-effect-args!
          :effects/load-portfolio-optimizer-scenario-index
          [{:source :route}]
          {:phase :test})))
  (is (= ["scn_01"]
         (contracts/assert-effect-args!
          :effects/load-portfolio-optimizer-scenario
          ["scn_01"]
          {:phase :test})))
  (is (= ["scn_01" {:source :route}]
         (contracts/assert-effect-args!
          :effects/load-portfolio-optimizer-scenario
          ["scn_01" {:source :route}]
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/load-portfolio-optimizer-scenario
        []
        {:phase :test}))))

(deftest assert-effect-args-validates-portfolio-optimizer-scenario-mutations-test
  (is (= ["scn_01"]
         (contracts/assert-effect-args!
          :effects/archive-portfolio-optimizer-scenario
          ["scn_01"]
          {:phase :test})))
  (is (= ["scn_01" {:source :board}]
         (contracts/assert-effect-args!
          :effects/archive-portfolio-optimizer-scenario
          ["scn_01" {:source :board}]
          {:phase :test})))
  (is (= ["scn_01"]
         (contracts/assert-effect-args!
          :effects/duplicate-portfolio-optimizer-scenario
          ["scn_01"]
          {:phase :test})))
  (is (= ["scn_01" {:source :board}]
         (contracts/assert-effect-args!
          :effects/duplicate-portfolio-optimizer-scenario
          ["scn_01" {:source :board}]
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/archive-portfolio-optimizer-scenario
        []
        {:phase :test}))))

(deftest assert-effect-args-validates-portfolio-optimizer-scenario-save-test
  (is (= []
         (contracts/assert-effect-args!
          :effects/save-portfolio-optimizer-scenario
          []
          {:phase :test})))
  (is (= [{:scenario-id "scn_01"}]
         (contracts/assert-effect-args!
          :effects/save-portfolio-optimizer-scenario
          [{:scenario-id "scn_01"}]
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"effect request"
       (contracts/assert-effect-args!
        :effects/save-portfolio-optimizer-scenario
        ["bad"]
        {:phase :test}))))
