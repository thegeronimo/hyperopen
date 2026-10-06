(ns hyperopen.vaults.equity-effects-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.projections.vaults :as vault-projections]
            [hyperopen.vaults.effects :as effects]))

(def ^:private vault-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private wallet-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(deftest api-fetch-user-vault-equities-allows-skip-route-override-test
  (async done
    (let [request-calls (atom [])
          store (atom {:router {:path "/trade"}
                       :wallet {:address wallet-address}})]
      (-> (effects/api-fetch-user-vault-equities!
           {:store store
            :address wallet-address
            :request-user-vault-equities! (fn [address opts]
                                            (swap! request-calls conj [address opts])
                                            (js/Promise.resolve [{:vault-address vault-address
                                                                  :equity-usd 42}]))
            :begin-user-vault-equities-load (fn [state requested-address]
                                              (assoc state :equities-loading? requested-address))
            :apply-user-vault-equities-success (fn [state requested-address rows]
                                                 (assoc state :equities [requested-address rows]))
            :apply-user-vault-equities-error (fn [state requested-address err]
                                               (assoc state :equities-error [requested-address err]))
            :opts {:skip-route-gate? true
                   :priority :high}})
          (.then (fn [rows]
                   (is (= [{:vault-address vault-address
                            :equity-usd 42}]
                          rows))
                   (is (= [[wallet-address {:priority :high}]]
                          @request-calls))
                   (is (= wallet-address (:equities-loading? @store)))
                   (is (= [wallet-address rows] (:equities @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected vault equities error")
                    (done)))))))

(deftest api-fetch-user-vault-equities-does-not-apply-an-old-account-response-after-a-switch-test
  (async done
    (let [account-b "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
          resolve-request! (atom nil)
          account-b-rows [{:vault-address "0xvault-b" :equity 7 :equity-raw "7"}]
          store (atom {:router {:path "/trade"}
                       :wallet {:address wallet-address}
                       :scoped-equities [account-b account-b-rows]})]
      (-> (effects/api-fetch-user-vault-equities!
           {:store store
            :address wallet-address
            :request-user-vault-equities! (fn [_address _opts]
                                            (js/Promise.
                                             (fn [resolve _reject]
                                               (reset! resolve-request! resolve))))
            :begin-user-vault-equities-load (fn [state requested-address]
                                              (assoc state :loading-for requested-address))
            :apply-user-vault-equities-success (fn [state requested-address rows]
                                                 (assoc state :scoped-equities [requested-address rows]))
            :apply-user-vault-equities-error (fn [state requested-address err]
                                               (assoc state :equities-error [requested-address err]))
            :opts {:skip-route-gate? true
                   :priority :high}})
          (.then (fn [rows]
                   (is (= [{:vault-address "0xvault-a" :equity 99 :equity-raw "99"}]
                          rows))
                   (is (= [account-b account-b-rows] (:scoped-equities @store)))
                   (is (nil? (:equities-error @store)))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected stale vault equity response error")
                    (done))))
      ;; The old route request may resolve after a newer account snapshot. It
      ;; must still return its response to its caller but cannot write it into
      ;; B's scoped projection.
      (swap! store assoc
             :wallet {:address account-b}
             :scoped-equities [account-b account-b-rows])
      (@resolve-request! [{:vault-address "0xvault-a" :equity 99 :equity-raw "99"}]))))

(deftest api-submit-vault-transfer-refreshes-vault-equity-for-the-current-account-after-navigation-test
  (async done
    (let [request {:vault-address vault-address
                   :action {:type "vaultTransfer"
                            :vaultAddress vault-address
                            :isDeposit true
                            :usd 1000000}}
          refresh-calls (atom [])
          store (atom {:wallet {:address wallet-address :agent {:status :ready}}
                       :router {:path "/trade"}})]
      (-> (effects/api-submit-vault-transfer!
           {:store store
            :request request
            :submit-vault-transfer! (fn [& _] (js/Promise.resolve {:status "ok"}))
            :fetch-user-vault-equities! (fn [& args]
                                          (swap! refresh-calls conj args)
                                          (js/Promise.resolve nil))
            :dispatch! (fn [& _] nil)})
          (.then (fn [_]
                   ;; A transfer can resolve after the route leaves its vault
                   ;; detail page. The Account Equity value on /trade must not
                   ;; wait for the minute poller to see the new deposit.
                   (is (= [[store wallet-address {:priority :high
                                                   :force-refresh? true}]]
                          @refresh-calls))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected vault transfer refresh error")
                    (done)))))))

(deftest api-submit-vault-transfer-does-not-refresh-vault-equity-after-an-account-switch-test
  (async done
    (let [account-b "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
          resolve-submit! (atom nil)
          refresh-calls (atom [])
          store (atom {:wallet {:address wallet-address :agent {:status :ready}}
                       :router {:path "/trade"}})]
      (-> (effects/api-submit-vault-transfer!
           {:store store
            :request {:vault-address vault-address
                      :action {:type "vaultTransfer"
                               :vaultAddress vault-address
                               :isDeposit true
                               :usd 1000000}}
            :submit-vault-transfer! (fn [& _]
                                      (js/Promise.
                                       (fn [resolve _reject]
                                         (reset! resolve-submit! resolve))))
            :fetch-user-vault-equities! (fn [& args]
                                          (swap! refresh-calls conj args)
                                          (js/Promise.resolve nil))
            :dispatch! (fn [& _] nil)})
          (.then (fn [_]
                   (is (empty? @refresh-calls))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected switched-account transfer error")
                    (done))))
      (swap! store assoc :wallet {:address account-b :agent {:status :ready}})
      (@resolve-submit! {:status "ok"}))))

(deftest api-fetch-user-vault-equities-keeps-the-latest-same-account-response-and-error-test
  (async done
    (let [requests (atom [])
          rows-100 [{:vault-address "0xvault-a" :equity 100 :equity-raw "100"}]
          rows-200 [{:vault-address "0xvault-a" :equity 200 :equity-raw "200"}]
          store (atom {:router {:path "/trade"}
                       :wallet {:address wallet-address}
                       :vaults {:user-equities []
                                :user-equities-for-address nil
                                :loading {:user-equities? false}
                                :errors {:user-equities nil}}})
          request! (fn [address opts]
                     (js/Promise.
                      (fn [resolve reject]
                        (swap! requests conj {:address address
                                              :opts opts
                                              :resolve resolve
                                              :reject reject}))))
          deps {:store store
                :address wallet-address
                :request-user-vault-equities! request!
                :begin-user-vault-equities-load vault-projections/begin-user-vault-equities-load
                :apply-user-vault-equities-success vault-projections/apply-user-vault-equities-success
                :apply-user-vault-equities-error vault-projections/apply-user-vault-equities-error
                :opts {:skip-route-gate? true
                       :priority :high
                       :force-refresh? true}}
          first-request (effects/api-fetch-user-vault-equities! deps)
          second-request (effects/api-fetch-user-vault-equities! deps)
          latest-request (effects/api-fetch-user-vault-equities! deps)]
      ;; Every forced refresh has to reach the request boundary instead of
      ;; reusing a 5-second cached or single-flight response.
      (is (= 3 (count @requests)))
      (is (every? #(= {:priority :high :force-refresh? true} (:opts %))
                  @requests))
      (-> (js/Promise.all #js [(.catch first-request (fn [_] :old-success-consumed))
                               (.catch second-request (fn [_] :old-error-consumed))
                               latest-request])
          (.then (fn [_]
                   (is (= rows-200 (get-in @store [:vaults :user-equities])))
                   (is (= wallet-address
                          (get-in @store [:vaults :user-equities-for-address])))
                   (is (nil? (get-in @store [:vaults :errors :user-equities])))
                   (is (nil? (get-in @store [:vaults :user-equities-error-for-address])))
                   (done)))
          (.catch (fn [err]
                    (js/console.error err)
                    (is false "Unexpected same-account vault request error")
                    (done))))
      ((:resolve (nth @requests 2)) rows-200)
      ((:resolve (nth @requests 0)) rows-100)
      ((:reject (nth @requests 1)) (js/Error. "old request failed")))))
