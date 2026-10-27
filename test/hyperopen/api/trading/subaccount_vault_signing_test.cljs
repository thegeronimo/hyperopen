(ns hyperopen.api.trading.subaccount-vault-signing-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.trading :as trading]
            [hyperopen.api.trading.test-support :as support]
            [hyperopen.test-support.api-stubs :as api-stubs]
            [hyperopen.test-support.async :as async-support]
            [hyperopen.utils.hl-signing :as signing]
            [hyperopen.wallet.agent-session :as agent-session]))

(defn- captured-sign-options
  [sign-call]
  (let [opts (nth sign-call 3)]
    (cond
      (map? opts) opts
      (and (= 1 (count opts))
           (map? (first opts))) (first opts)
      :else (apply hash-map opts))))

(defn- assert-public-agent-action-vault-propagation!
  [done {:keys [invoke! expected-action]}]
  (let [store (support/ready-agent-store 1700000030000)
        mixed-case-subaccount "0xABCDEF1234567890ABCDEF1234567890ABCDEF12"
        normalized-subaccount (str/lower-case mixed-case-subaccount)
        fixed-expires-after 1700000035000
        load-calls (atom [])
        sign-calls (atom [])
        fetch-calls (atom [])
        persist-calls (atom [])
        original-load agent-session/load-agent-session-by-mode
        original-persist agent-session/persist-agent-session-by-mode!
        original-sign signing/sign-l1-action-with-private-key!
        restore-fetch! (support/install-fetch-stub!
                        (fn [url opts]
                          (swap! fetch-calls conj [url opts])
                          (js/Promise.resolve
                           (support/json-response {:status "ok"}))))
        restore! (fn []
                   (set! agent-session/load-agent-session-by-mode original-load)
                   (set! agent-session/persist-agent-session-by-mode! original-persist)
                   (set! signing/sign-l1-action-with-private-key! original-sign)
                   (restore-fetch!))]
    (set! agent-session/load-agent-session-by-mode
          (fn [wallet-address storage-mode]
            (swap! load-calls conj [wallet-address storage-mode])
            {:agent-address "0x8fd379246834eac74b8419ffda202cf8051f7a03"
             :private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
             :nonce-cursor 1700000030000}))
    (set! agent-session/persist-agent-session-by-mode!
          (fn [wallet-address storage-mode session]
            (swap! persist-calls conj [wallet-address storage-mode session])
            true))
    (set! signing/sign-l1-action-with-private-key! (api-stubs/signing-stub sign-calls))
    (try
      (-> (invoke! store
                   support/owner-address
                   mixed-case-subaccount
                   fixed-expires-after)
          (.then
           (fn [resp]
             (is (= "ok" (:status resp)))
             (is (= [[support/owner-address :session]]
                    @load-calls))
             (is (= 1 (count @sign-calls)))
             (is (= 1 (count @fetch-calls)))
             (is (= 1 (count @persist-calls)))
             (let [[_ signed-action _ _] (first @sign-calls)
                   sign-opts-map (captured-sign-options (first @sign-calls))
                   [_ fetch-opts] (first @fetch-calls)
                   payload (support/fetch-body->map fetch-opts)]
               (is (= expected-action signed-action))
               (is (= normalized-subaccount (:vault-address sign-opts-map)))
               (is (= fixed-expires-after (:expires-after sign-opts-map)))
               (is (true? (:is-mainnet sign-opts-map)))
               (is (= expected-action (:action payload)))
               (is (= normalized-subaccount (:vaultAddress payload)))
               (is (= fixed-expires-after (:expiresAfter payload))))
             (done)))
          (.catch (async-support/unexpected-error done))
          (.finally restore!))
      (catch :default err
        (restore!)
        (is false (str "Unexpected synchronous error: " err))
        (done)))))

(deftest submit-order-public-facade-propagates-vault-options-test
  (async done
    (let [action {:type "order"
                  :orders []
                  :grouping "na"}]
      (assert-public-agent-action-vault-propagation!
       done
       {:expected-action action
        :invoke! (fn [store owner-address vault-address expires-after]
                   (trading/submit-order! store
                                          owner-address
                                          action
                                          {:vault-address vault-address
                                           :expires-after expires-after
                                           :is-mainnet true
                                           :max-nonce-retries 0}))}))))

(deftest cancel-order-public-facade-propagates-vault-options-test
  (async done
    (let [action {:type "cancel"
                  :cancels [{:a 0
                             :o 22}]}]
      (assert-public-agent-action-vault-propagation!
       done
       {:expected-action action
        :invoke! (fn [store owner-address vault-address expires-after]
                   (trading/cancel-order! store
                                          owner-address
                                          action
                                          {:vault-address vault-address
                                           :expires-after expires-after
                                           :is-mainnet true
                                           :max-nonce-retries 0}))}))))

(deftest schedule-cancel-public-facade-propagates-vault-options-test
  (async done
    (let [cancel-at-ms 1700000040000
          action {:type "scheduleCancel"
                  :time cancel-at-ms}]
      (assert-public-agent-action-vault-propagation!
       done
       {:expected-action action
        :invoke! (fn [store owner-address vault-address expires-after]
                   (trading/schedule-cancel! store
                                             owner-address
                                             cancel-at-ms
                                             {:vault-address vault-address
                                              :expires-after expires-after
                                              :is-mainnet true
                                              :max-nonce-retries 0}))}))))

(deftest clear-schedule-cancel-public-facade-omits-time-and-propagates-vault-options-test
  (async done
    (let [action {:type "scheduleCancel"}]
      (assert-public-agent-action-vault-propagation!
       done
       {:expected-action action
        :invoke! (fn [store owner-address vault-address expires-after]
                   (trading/schedule-cancel! store
                                             owner-address
                                             nil
                                             {:vault-address vault-address
                                              :expires-after expires-after
                                              :is-mainnet true
                                              :max-nonce-retries 0}))}))))
