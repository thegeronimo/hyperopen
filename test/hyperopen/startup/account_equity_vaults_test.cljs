(ns hyperopen.startup.account-equity-vaults-test
  "The Account Equity panel's vault NAV must be loaded for the effective
   account, and a response for an account that is no longer effective must not
   overwrite the newer account's scoped snapshot."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.account.surface-service :as surface-service]
            [hyperopen.startup.collaborators :as collaborators]))

(def ^:private account-a
  "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def ^:private account-b
  "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

(deftest account-surface-bootstrap-requests-user-vault-equities-for-the-effective-address-test
  (let [calls (atom [])
        store (atom {:wallet {:address account-a}})]
    (surface-service/bootstrap-account-surfaces!
     {:store store
      :address account-a
      :fetch-user-vault-equities! (fn [actual-store address opts]
                                    (swap! calls conj [actual-store address opts]))})
    (is (= [[store account-a {:priority :high}]] @calls))))

(deftest post-event-account-surface-refresh-requests-user-vault-equities-for-the-effective-address-test
  (let [calls (atom [])
        store (atom {:wallet {:address account-a}})]
    (surface-service/refresh-after-user-fill!
     {:store store
      :address account-a
      :fetch-user-vault-equities! (fn [actual-store address opts]
                                    (swap! calls conj [actual-store address opts]))})
    (is (= [[store account-a {:priority :high}]] @calls))))

(deftest unified-account-surfaces-do-not-start-or-fetch-classic-vault-equity-test
  (let [fetch-calls (atom [])
        poller-calls (atom [])
        store (atom {:wallet {:address account-a}
                     :account {:mode :unified}})]
    (surface-service/bootstrap-account-surfaces!
     {:store store
      :address account-a
      :fetch-user-vault-equities! (fn [& args] (swap! fetch-calls conj args))
      :start-user-vault-equity-poller! (fn [& args] (swap! poller-calls conj args))})
    (surface-service/refresh-after-user-fill!
     {:store store
      :address account-a
      :fetch-user-vault-equities! (fn [& args] (swap! fetch-calls conj args))})
    (is (empty? @fetch-calls))
    (is (empty? @poller-calls))))

(deftest startup-vault-equity-collaborator-uses-the-requested-effective-account-test
  (async done
    (let [request-calls (atom [])
          store (atom {:wallet {:address account-a}
                       :vaults {:user-equities []
                                :user-equities-for-address nil
                                :loading {:user-equities? false}
                                :errors {:user-equities nil}}})
          deps (collaborators/startup-base-deps
                {:api {:request-user-vault-equities!
                       (fn [address opts]
                         (swap! request-calls conj [address opts])
                         (js/Promise.resolve []))}})
          fetch! (:fetch-user-vault-equities! deps)]
      (is (fn? fetch!))
      (if (fn? fetch!)
        (-> (fetch! store account-a {:priority :high})
            (.then (fn [_]
                     (is (= [[account-a {:priority :high}]] @request-calls))
                     (is (= [] (get-in @store [:vaults :user-equities])))
                     (is (= account-a
                            (get-in @store [:vaults :user-equities-for-address])))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected vault equity request error: " err))
                      (done))))
        (done)))))

(deftest startup-vault-equity-collaborator-ignores-a-late-response-after-effective-account-switch-test
  (async done
    (let [resolve-request! (atom nil)
          account-b-rows [{:vault-address "0xvault-b" :equity 7 :equity-raw "7"}]
          store (atom {:wallet {:address account-a}
                       :vaults {:user-equities []
                                :user-equities-for-address nil
                                :loading {:user-equities? false}
                                :errors {:user-equities nil}}})
          deps (collaborators/startup-base-deps
                {:api {:request-user-vault-equities!
                       (fn [_address _opts]
                         (js/Promise.
                          (fn [resolve _reject]
                            (reset! resolve-request! resolve))))}})
          fetch! (:fetch-user-vault-equities! deps)]
      (is (fn? fetch!))
      (if (fn? fetch!)
        (do
          (-> (fetch! store account-a {:priority :high})
              (.then (fn [_]
                       (is (= account-b
                              (get-in @store [:vaults :user-equities-for-address])))
                       (is (= account-b-rows
                              (get-in @store [:vaults :user-equities])))
                       (done)))
              (.catch (fn [err]
                        (is false (str "Unexpected late vault equity request error: " err))
                        (done))))
          ;; Simulate the newer effective account's already-confirmed snapshot
          ;; before the old request finishes. A's callback may not replace it.
          (swap! store assoc
                 :wallet {:address account-b}
                 :vaults {:user-equities account-b-rows
                          :user-equities-for-address account-b
                          :loading {:user-equities? false}
                          :errors {:user-equities nil}})
          (@resolve-request! [{:vault-address "0xvault-a" :equity 99 :equity-raw "99"}]))
        (done)))))

(deftest startup-vault-equity-collaborator-does-not-let-a-late-stale-invocation-steal-the-current-account-request-token-test
  (async done
    (let [request-calls (atom [])
          rows-b [{:vault-address "0xvault-b" :equity 7 :equity-raw "7"}]
          store (atom {:wallet {:address account-b}
                       :vaults {:user-equities []
                                :user-equities-for-address nil
                                :loading {:user-equities? false}
                                :errors {:user-equities nil}}})
          deps (collaborators/startup-base-deps
                {:api {:request-user-vault-equities!
                       (fn [address opts]
                         (if (empty? @request-calls)
                           (js/Promise.
                            (fn [resolve _reject]
                              (swap! request-calls conj {:address address
                                                         :opts opts
                                                         :resolve resolve})))
                           ;; Existing buggy behavior reaches the API for the
                           ;; stale A call. Resolve it so the test does not
                           ;; hang before demonstrating that it stole B's token.
                           (do
                             (swap! request-calls conj {:address address
                                                        :opts opts})
                             (js/Promise.resolve
                              [{:vault-address "0xvault-a"
                                :equity 99
                                :equity-raw "99"}]))))}})
          fetch! (:fetch-user-vault-equities! deps)
          current-request (fetch! store account-b {:priority :high})]
      (is (= 1 (count @request-calls)))
      (let [current-request-id (get-in @store [:vaults :user-equities-request-id])]
        (-> (fetch! store account-a {:priority :high})
            (.then (fn [stale-result]
                     (is (nil? stale-result))
                     (is (= 1 (count @request-calls)))
                     (is (= current-request-id
                            (get-in @store [:vaults :user-equities-request-id])))
                     ((:resolve (first @request-calls)) rows-b)
                     current-request))
            (.then (fn [_]
                     (is (= rows-b (get-in @store [:vaults :user-equities])))
                     (is (= account-b
                            (get-in @store [:vaults :user-equities-for-address])))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected stale invocation error: " err))
                      (done))))))))

(deftest startup-vault-equity-collaborator-keeps-the-latest-forced-same-account-response-test
  (async done
    (let [requests (atom [])
          rows-100 [{:vault-address "0xvault-a" :equity 100 :equity-raw "100"}]
          rows-200 [{:vault-address "0xvault-a" :equity 200 :equity-raw "200"}]
          store (atom {:wallet {:address account-a}
                       :vaults {:user-equities []
                                :user-equities-for-address nil
                                :loading {:user-equities? false}
                                :errors {:user-equities nil}}})
          deps (collaborators/startup-base-deps
                {:api {:request-user-vault-equities!
                       (fn [address opts]
                         (js/Promise.
                          (fn [resolve reject]
                            (swap! requests conj {:address address
                                                  :opts opts
                                                  :resolve resolve
                                                  :reject reject}))))}})
          fetch! (:fetch-user-vault-equities! deps)
          force-opts {:priority :high :force-refresh? true}
          first-request (fetch! store account-a force-opts)
          second-request (fetch! store account-a force-opts)
          latest-request (fetch! store account-a force-opts)]
      (is (= 3 (count @requests)))
      (is (every? #(= force-opts (:opts %)) @requests))
      (-> (js/Promise.all #js [(.catch first-request (fn [_] :old-success-consumed))
                               (.catch second-request (fn [_] :old-error-consumed))
                               latest-request])
          (.then (fn [_]
                   (is (= rows-200 (get-in @store [:vaults :user-equities])))
                   (is (= account-a
                          (get-in @store [:vaults :user-equities-for-address])))
                   (is (nil? (get-in @store [:vaults :errors :user-equities])))
                   (is (nil? (get-in @store [:vaults :user-equities-error-for-address])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected same-account startup vault request error: " err))
                    (done))))
      ((:resolve (nth @requests 2)) rows-200)
      ((:resolve (nth @requests 0)) rows-100)
      ((:reject (nth @requests 1)) (js/Error. "old request failed")))))

(deftest startup-vault-equity-collaborator-marks-a-current-request-error-for-the-effective-account-test
  (async done
    (let [store (atom {:wallet {:address account-a}
                       :vaults {:user-equities []
                                :user-equities-for-address nil
                                :loading {:user-equities? false}
                                :errors {:user-equities nil}}})
          deps (collaborators/startup-base-deps
                {:api {:request-user-vault-equities!
                       (fn [_address _opts]
                         (js/Promise.reject (js/Error. "vault request failed")))}})
          fetch! (:fetch-user-vault-equities! deps)]
      (is (fn? fetch!))
      (if (fn? fetch!)
        (-> (fetch! store account-a {:priority :high})
            (.then (fn [_]
                     (is false "Expected vault equity request rejection")
                     (done)))
            (.catch (fn [_]
                      (is (= "Error: vault request failed"
                             (get-in @store [:vaults :errors :user-equities])))
                      (is (= account-a
                             (get-in @store [:vaults :user-equities-error-for-address])))
                      (done))))
        (done)))))
