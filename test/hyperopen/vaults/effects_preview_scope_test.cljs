(ns hyperopen.vaults.effects-preview-scope-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.vaults.effects :as effects]))

(def vault-address
  "0x1234567890abcdef1234567890abcdef12345678")

(deftest api-fetch-vault-index-skips-startup-preview-persistence-on-portfolio-route-test
  (async done
    (let [preview-persist-calls (atom [])
          rows [{:vault-address "0x1"
                 :name "Vault 1"}]
          store (atom {:router {:path "/portfolio"}
                       :vaults {:index-rows []}})]
      (-> (effects/api-fetch-vault-index!
           {:store store
            :request-vault-index-response! (fn [_opts]
                                             (js/Promise.resolve {:status :ok
                                                                  :rows rows}))
            :begin-vault-index-load (fn [state]
                                      (assoc state :index-loading? true))
            :apply-vault-index-success (fn [state response]
                                         (assoc-in state [:vaults :index-rows] (:rows response)))
            :apply-vault-index-error (fn [state err]
                                       (assoc state :index-error err))
            :persist-vault-startup-preview-record! (fn [preview-state]
                                                    (swap! preview-persist-calls conj preview-state)
                                                    true)
            :persist-vault-index-cache-record! (fn [_rows _metadata]
                                                (js/Promise.resolve true))})
          (.then (fn [_response]
                   (is (= rows (get-in @store [:vaults :index-rows])))
                   (is (= [] @preview-persist-calls))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected portfolio-route vault index error")
                    (done)))))))

(deftest api-fetch-vault-index-skips-startup-preview-persistence-on-vault-detail-route-test
  (async done
    (let [preview-persist-calls (atom [])
          rows [{:vault-address "0x1"
                 :name "Vault 1"}]
          store (atom {:router {:path (str "/vaults/" vault-address)}
                       :vaults {:index-rows []}})]
      (-> (effects/api-fetch-vault-index!
           {:store store
            :request-vault-index-response! (fn [_opts]
                                             (js/Promise.resolve {:status :ok
                                                                  :rows rows}))
            :begin-vault-index-load (fn [state]
                                      (assoc state :index-loading? true))
            :apply-vault-index-success (fn [state response]
                                         (assoc-in state [:vaults :index-rows] (:rows response)))
            :apply-vault-index-error (fn [state err]
                                       (assoc state :index-error err))
            :persist-vault-startup-preview-record! (fn [preview-state]
                                                    (swap! preview-persist-calls conj preview-state)
                                                    true)
            :persist-vault-index-cache-record! (fn [_rows _metadata]
                                                (js/Promise.resolve true))})
          (.then (fn [_response]
                   (is (= rows (get-in @store [:vaults :index-rows])))
                   (is (= [] @preview-persist-calls))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected vault-detail vault index error")
                    (done)))))))
