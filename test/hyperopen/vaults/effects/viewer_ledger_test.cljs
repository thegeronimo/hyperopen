(ns hyperopen.vaults.effects.viewer-ledger-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.vaults.effects :as effects]))

(def ^:private vault-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private wallet-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(defn- details-deps
  [store details-payload ledger-overrides]
  (merge {:store store
          :vault-address vault-address
          :user-address wallet-address
          :request-vault-details! (fn [_vault-address _opts]
                                    (js/Promise.resolve details-payload))
          :begin-vault-details-load (fn [state _] state)
          :apply-vault-details-success (fn [state _ _ _] state)
          :apply-vault-details-error (fn [state _ err] (assoc state :detail-error err))
          :request-user-non-funding-ledger-updates! (fn [_ _ _ _] (js/Promise.resolve []))
          :begin-vault-viewer-ledger-load (fn [state vault viewer]
                                            (assoc state :ledger-begin [vault viewer]))
          :apply-vault-viewer-ledger-success (fn [state vault viewer rows]
                                               (assoc state :ledger [vault viewer rows]))
          :apply-vault-viewer-ledger-error (fn [state _ _ err]
                                             (assoc state :ledger-error (.-message err)))}
         ledger-overrides))

(deftest api-fetch-vault-details-fetches-viewer-ledger-from-vault-entry-time-test
  (async done
    (let [ledger-calls (atom [])
          store (atom {:router {:path (str "/vaults/" vault-address)}})
          payload {:follower-state {:vault-entry-time-ms 1774416974173}}]
      (-> (effects/api-fetch-vault-details!
           (details-deps store payload
                         {:request-user-non-funding-ledger-updates!
                          (fn [address start-ms end-ms opts]
                            (swap! ledger-calls conj [address start-ms end-ms opts])
                            (js/Promise.resolve [{:time 1}]))}))
          (.then (fn [result]
                   (let [[address start-ms end-ms opts] (first @ledger-calls)]
                     (is (= payload result))
                     (is (= wallet-address address))
                     (is (= 1774416974173 start-ms))
                     (is (nil? end-ms))
                     (is (fn? (:active?-fn opts)))
                     (is (nil? (:user opts))))
                   (is (= [vault-address wallet-address] (:ledger-begin @store)))
                   (is (= [vault-address wallet-address [{:time 1}]] (:ledger @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected vault details error")
                    (done)))))))

(deftest api-fetch-vault-details-viewer-ledger-failure-does-not-fail-details-test
  (async done
    (let [store (atom {:router {:path (str "/vaults/" vault-address)}})]
      (-> (effects/api-fetch-vault-details!
           (details-deps store
                         {:follower-state {:vault-entry-time-ms 5}}
                         {:request-user-non-funding-ledger-updates!
                          (fn [_ _ _ _] (js/Promise.reject (js/Error. "boom")))}))
          (.then (fn [_payload]
                   (is (= "boom" (:ledger-error @store)))
                   (is (nil? (:detail-error @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Viewer ledger failure must not reject details")
                    (done)))))))

(deftest api-fetch-vault-details-skips-viewer-ledger-without-follower-state-or-user-test
  (async done
    (let [calls (atom 0)
          count-call (fn [_ _ _ _]
                       (swap! calls inc)
                       (js/Promise.resolve []))
          store (atom {:router {:path (str "/vaults/" vault-address)}})]
      (-> (effects/api-fetch-vault-details!
           (details-deps store {:name "No follower"}
                         {:request-user-non-funding-ledger-updates! count-call}))
          (.then (fn [_]
                   (effects/api-fetch-vault-details!
                    (details-deps store {:follower-state {:vault-entry-time-ms 5}}
                                  {:user-address nil
                                   :request-user-non-funding-ledger-updates! count-call}))))
          (.then (fn [_]
                   (is (= 0 @calls))
                   (is (nil? (:ledger-begin @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected error")
                    (done)))))))
