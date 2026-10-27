(ns hyperopen.api.trading.subaccount-management-signing-test
  (:require [cljs.test :refer-macros [async deftest is]]
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

(defn- assert-public-management-action-omits-vault!
  [done {:keys [invoke! expected-action]}]
  (let [store (support/ready-agent-store 1700000040000)
        mixed-case-subaccount "0xABCDEF1234567890ABCDEF1234567890ABCDEF12"
        fixed-expires-after 1700000045000
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
             :nonce-cursor 1700000040000}))
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
               (is (nil? (:vault-address sign-opts-map)))
               (is (= fixed-expires-after (:expires-after sign-opts-map)))
               (is (true? (:is-mainnet sign-opts-map)))
               (is (= expected-action (:action payload)))
               (is (false? (contains? payload :vaultAddress)))
               (is (= fixed-expires-after (:expiresAfter payload))))
             (done)))
          (.catch (async-support/unexpected-error done))
          (.finally restore!))
      (catch :default err
        (restore!)
        (is false (str "Unexpected synchronous error: " err))
        (done)))))

(deftest create-sub-account-public-facade-signs-master-scoped-action-without-vault-test
  (async done
    (let [action {:type "createSubAccount"
                  :name "Desk"}]
      (assert-public-management-action-omits-vault!
       done
       {:expected-action action
        :invoke! (fn [store owner-address vault-address expires-after]
                   (trading/create-sub-account! store
                                                owner-address
                                                "Desk"
                                                {:vault-address vault-address
                                                 :expires-after expires-after
                                                 :is-mainnet true
                                                 :max-nonce-retries 0}))}))))

(deftest modify-sub-account-public-facade-signs-master-scoped-action-without-vault-test
  (async done
    (let [subaccount "0xabcdef1234567890abcdef1234567890abcdef12"
          action {:type "subAccountModify"
                  :subAccountUser subaccount
                  :name "Ops"}]
      (assert-public-management-action-omits-vault!
       done
       {:expected-action action
        :invoke! (fn [store owner-address vault-address expires-after]
                   (trading/modify-sub-account! store
                                                owner-address
                                                subaccount
                                                "Ops"
                                                {:vault-address vault-address
                                                 :expires-after expires-after
                                                 :is-mainnet true
                                                 :max-nonce-retries 0}))}))))

(deftest transfer-sub-account-public-facade-signs-master-scoped-action-with-raw-usd-test
  (async done
    (let [subaccount "0xabcdef1234567890abcdef1234567890abcdef12"
          action {:type "subAccountTransfer"
                  :subAccountUser subaccount
                  :isDeposit true
                  :usd 1230000}]
      (assert-public-management-action-omits-vault!
       done
       {:expected-action action
        :invoke! (fn [store owner-address vault-address expires-after]
                   (trading/transfer-sub-account! store
                                                  owner-address
                                                  subaccount
                                                  true
                                                  1230000
                                                  {:vault-address vault-address
                                                   :expires-after expires-after
                                                   :is-mainnet true
                                                   :max-nonce-retries 0}))}))))

(deftest transfer-sub-account-spot-public-facade-signs-master-scoped-action-test
  (async done
    (let [subaccount "0xabcdef1234567890abcdef1234567890abcdef12"
          action {:type "subAccountSpotTransfer"
                  :subAccountUser subaccount
                  :isDeposit false
                  :token "USDH:0xabc"
                  :amount "4.63"}]
      (assert-public-management-action-omits-vault!
       done
       {:expected-action action
        :invoke! (fn [store owner-address vault-address expires-after]
                   (trading/transfer-sub-account-spot! store
                                                       owner-address
                                                       subaccount
                                                       false
                                                       "USDH:0xabc"
                                                       "4.63"
                                                       {:vault-address vault-address
                                                        :expires-after expires-after
                                                        :is-mainnet true
                                                        :max-nonce-retries 0}))}))))
