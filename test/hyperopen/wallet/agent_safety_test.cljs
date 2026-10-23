(ns hyperopen.wallet.agent-safety-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.wallet.agent-safety :as agent-safety]))

(defn- ready-store []
  ;; Strict is opt-in (the default is Off); these tests exercise an armed switch
  (atom {:wallet {:connected? true
                  :address "0xowner"
                  :agent {:status :ready
                          :agent-address "0xagent"}}
         :trading-settings {:open-order-safety-mode :strict}}))

(defn- ready-store-with-mode
  [mode]
  (atom (assoc-in @(ready-store)
                  [:trading-settings :open-order-safety-mode]
                  mode)))

(deftest install-agent-safety-classifies-volume-gate-and-stops-refresh-test
  (async done
    (let [store (ready-store)
          runtime (atom {:timeouts {:agent-schedule-cancel-refresh nil}})
          schedule-calls (atom [])
          timer-calls (atom [])
          original-schedule-cancel! trading-api/schedule-cancel!]
      (set! trading-api/schedule-cancel!
            (fn [_store address cancel-at-ms]
              (swap! schedule-calls conj [address cancel-at-ms])
              (js/Promise.resolve
               {:status "err"
                :response "Cannot set scheduled cancel time until enough volume traded. Required: $1000000. Traded: $890168.23."})))
      (agent-safety/install-agent-safety-watch!
       {:store store
        :runtime runtime
        :ahead-ms 60000
        :refresh-ms 30000
        :now-ms-fn (constantly 1000)
        :set-timeout-fn (fn [callback delay-ms]
                          (swap! timer-calls conj [callback delay-ms])
                          :timer-id)})
      (js/setTimeout
       (fn []
         (try
           (is (= [["0xowner" 61000]] @schedule-calls))
           (is (= [] @timer-calls))
           (is (= {:status :unavailable
                   :reason :volume-gate
                   :required "$1,000,000"
                   :traded "$890,168.23"
                   :message "Safety auto-cancel unavailable until $1,000,000 traded. Current volume: $890,168.23."}
                  (get-in @store [:wallet :agent :safety])))
           (finally
             (set! trading-api/schedule-cancel! original-schedule-cancel!)
             (agent-safety/stop-agent-safety! {:runtime runtime
                                               :clear-timeout-fn (fn [_])})
             (done))))
       0))))

(deftest install-agent-safety-routes-selected-subaccount-schedule-cancel-test
  (async done
    (let [owner-address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          subaccount-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
          store (atom {:wallet {:connected? true
                                :address owner-address
                                :agent {:status :ready
                                        :agent-address "0xagent"}}
                       :account-context {:subaccounts
                                         {:selected-address subaccount-address
                                          :rows [{:sub-account-user subaccount-address
                                                  :master owner-address
                                                  :name "Desk A"}]}}
                       :trading-settings {:open-order-safety-mode :strict}})
          runtime (atom {:timeouts {:agent-schedule-cancel-refresh nil}})
          schedule-calls (atom [])
          timer-calls (atom [])
          original-schedule-cancel! trading-api/schedule-cancel!]
      (set! trading-api/schedule-cancel!
            (fn [_store address cancel-at-ms & [options]]
              (swap! schedule-calls conj [address cancel-at-ms options])
              (js/Promise.resolve {:status "ok"})))
      (agent-safety/install-agent-safety-watch!
       {:store store
        :runtime runtime
        :ahead-ms 60000
        :refresh-ms 30000
        :now-ms-fn (constantly 1000)
        :set-timeout-fn (fn [callback delay-ms]
                          (swap! timer-calls conj [callback delay-ms])
                          :timer-id)})
      (js/setTimeout
       (fn []
         (try
           (is (= [[owner-address 61000 {:vault-address subaccount-address}]]
                  @schedule-calls))
           (is (= 1 (count @timer-calls)))
           (is (= 30000 (second (first @timer-calls))))
           (finally
             (set! trading-api/schedule-cancel! original-schedule-cancel!)
             (agent-safety/stop-agent-safety! {:runtime runtime
                                               :clear-timeout-fn (fn [_])})
             (done))))
       0))))

(deftest install-agent-safety-uses-extended-open-order-safety-mode-test
  (async done
    (let [store (ready-store-with-mode :extended)
          runtime (atom {:timeouts {:agent-schedule-cancel-refresh nil}})
          schedule-calls (atom [])
          timer-calls (atom [])
          original-schedule-cancel! trading-api/schedule-cancel!]
      (set! trading-api/schedule-cancel!
            (fn [_store address cancel-at-ms & [_options]]
              (swap! schedule-calls conj [address cancel-at-ms])
              (js/Promise.resolve {:status "ok"})))
      (agent-safety/install-agent-safety-watch!
       {:store store
        :runtime runtime
        :ahead-ms 60000
        :refresh-ms 30000
        :now-ms-fn (constantly 1000)
        :set-timeout-fn (fn [callback delay-ms]
                          (swap! timer-calls conj [callback delay-ms])
                          :timer-id)})
      (js/setTimeout
       (fn []
         (try
           (is (= [["0xowner" 14401000]] @schedule-calls))
           (is (= 1 (count @timer-calls)))
           (is (= 900000 (second (first @timer-calls))))
           (finally
             (set! trading-api/schedule-cancel! original-schedule-cancel!)
             (agent-safety/stop-agent-safety! {:runtime runtime
                                               :clear-timeout-fn (fn [_])})
             (done))))
       0))))

(deftest install-agent-safety-clears-schedule-cancel-when-open-order-safety-is-off-test
  (async done
    (let [store (ready-store-with-mode :off)
          runtime (atom {:timeouts {:agent-schedule-cancel-refresh nil}})
          schedule-calls (atom [])
          timer-calls (atom [])
          original-schedule-cancel! trading-api/schedule-cancel!]
      (set! trading-api/schedule-cancel!
            (fn [_store address cancel-at-ms & [_options]]
              (swap! schedule-calls conj [address cancel-at-ms])
              (js/Promise.resolve {:status "ok"})))
      (agent-safety/install-agent-safety-watch!
       {:store store
        :runtime runtime
        :ahead-ms 60000
        :refresh-ms 30000
        :now-ms-fn (constantly 1000)
        :set-timeout-fn (fn [callback delay-ms]
                          (swap! timer-calls conj [callback delay-ms])
                          :timer-id)})
      (js/setTimeout
       (fn []
         (try
           (is (= [["0xowner" nil]] @schedule-calls))
           (is (= [] @timer-calls))
           (finally
             (set! trading-api/schedule-cancel! original-schedule-cancel!)
             (agent-safety/stop-agent-safety! {:runtime runtime
                                               :clear-timeout-fn (fn [_])})
             (done))))
       0))))

(deftest install-agent-safety-reacts-to-open-order-safety-mode-change-test
  (async done
    (let [store (ready-store)
          runtime (atom {:timeouts {:agent-schedule-cancel-refresh nil}})
          schedule-calls (atom [])
          timer-calls (atom [])
          original-schedule-cancel! trading-api/schedule-cancel!]
      (set! trading-api/schedule-cancel!
            (fn [_store address cancel-at-ms & [_options]]
              (swap! schedule-calls conj [address cancel-at-ms])
              (js/Promise.resolve {:status "ok"})))
      (agent-safety/install-agent-safety-watch!
       {:store store
        :runtime runtime
        :ahead-ms 60000
        :refresh-ms 30000
        :now-ms-fn (constantly 1000)
        :set-timeout-fn (fn [callback delay-ms]
                          (swap! timer-calls conj [callback delay-ms])
                          :timer-id)})
      (js/setTimeout
       (fn []
         (try
           (swap! store assoc-in [:trading-settings :open-order-safety-mode] :off)
           (js/setTimeout
            (fn []
              (try
                (is (= [["0xowner" 61000]
                        ["0xowner" nil]]
                       @schedule-calls))
                (finally
                  (set! trading-api/schedule-cancel! original-schedule-cancel!)
                  (agent-safety/stop-agent-safety! {:runtime runtime
                                                    :clear-timeout-fn (fn [_])})
                  (done))))
            0)
         (catch :default err
           (set! trading-api/schedule-cancel! original-schedule-cancel!)
           (agent-safety/stop-agent-safety! {:runtime runtime
                                             :clear-timeout-fn (fn [_])})
           (is false (str "Unexpected error: " err))
           (done))))
       0))))

(deftest install-agent-safety-arms-nothing-by-default-test
  ;; no saved choice: Off, so the switch only clears any scheduled cancel left over
  (async done
    (let [store (atom (dissoc @(ready-store) :trading-settings))
          runtime (atom {:timeouts {:agent-schedule-cancel-refresh nil}})
          schedule-calls (atom [])
          original-schedule-cancel! trading-api/schedule-cancel!]
      (set! trading-api/schedule-cancel!
            (fn [_store address cancel-at-ms]
              (swap! schedule-calls conj [address cancel-at-ms])
              (js/Promise.resolve {:status "ok"})))
      (agent-safety/install-agent-safety-watch!
       {:store store
        :runtime runtime
        :now-ms-fn (constantly 1000)
        :set-timeout-fn (fn [_ _] (is false "no renewal timer while Off") :timer)
        :clear-timeout-fn (fn [_] nil)})
      (js/setTimeout
       (fn []
         (set! trading-api/schedule-cancel! original-schedule-cancel!)
         (agent-safety/stop-agent-safety! {:runtime runtime :clear-timeout-fn (fn [_] nil)})
         (remove-watch store :hyperopen.wallet.agent-safety/agent-safety)
         (is (= [["0xowner" nil]] @schedule-calls))
         (done))
       0))))
