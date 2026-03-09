(ns hyperopen.websocket.user-runtime.refresh-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.account.surface-service :as account-surface-service]
            [hyperopen.api.default :as api]
            [hyperopen.api.service :as api-service]
            [hyperopen.api.promise-effects :as promise-effects]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.websocket.user-runtime.subscriptions :as subscriptions-runtime]
            [hyperopen.websocket.user-runtime.refresh :as refresh-runtime]))

(defn- make-store
  [address]
  (atom {:wallet {:address address}}))

(deftest schedule-account-surface-refresh-after-fill-uses-runtime-timeout-storage-test
  (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        store (make-store address)
        runtime (atom (runtime-state/default-runtime-state))
        scheduled (atom nil)
        cleared (atom [])
        refresh-calls (atom [])]
    (swap! runtime assoc-in [:timeouts :user-account-surface-refresh] :old-timeout)
    (with-redefs [runtime-state/runtime runtime
                  platform/set-timeout! (fn [callback ms]
                                          (reset! scheduled [callback ms])
                                          :new-timeout)
                  platform/clear-timeout! (fn [timeout-id]
                                            (swap! cleared conj timeout-id))
                  refresh-runtime/refresh-account-surfaces-after-user-fill!
                  (fn [store* address*]
                    (swap! refresh-calls conj [store* address*]))]
      (refresh-runtime/schedule-account-surface-refresh-after-fill! store)
      (is (= [:old-timeout] @cleared))
      (is (= :new-timeout
             (get-in @runtime [:timeouts :user-account-surface-refresh])))
      (is (= 250 (second @scheduled)))
      ((first @scheduled))
      (is (nil? (get-in @runtime [:timeouts :user-account-surface-refresh])))
      (is (= [[store address]] @refresh-calls)))))

(deftest scheduled-account-surface-refresh-skips-stale-address-test
  (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        other-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        store (make-store address)
        runtime (atom (runtime-state/default-runtime-state))
        scheduled-callback (atom nil)
        refresh-calls (atom [])]
    (with-redefs [runtime-state/runtime runtime
                  platform/set-timeout! (fn [callback _ms]
                                          (reset! scheduled-callback callback)
                                          :timeout-id)
                  platform/clear-timeout! (fn [_] nil)
                  refresh-runtime/refresh-account-surfaces-after-user-fill!
                  (fn [store* address*]
                    (swap! refresh-calls conj [store* address*]))]
      (refresh-runtime/schedule-account-surface-refresh-after-fill! store)
      (swap! store assoc-in [:wallet :address] other-address)
      (@scheduled-callback)
      (is (empty? @refresh-calls)))))

(deftest apply-success-and-error-wrappers-only-mutate-active-address-test
  (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        stale-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        store (atom {:wallet {:address address}
                     :results []
                     :errors []})
        apply-success
        (refresh-runtime/apply-success-and-return-when-address-active
         store
         address
         (fn [state path payload]
           (update-in state path conj payload))
         [:results])
        apply-stale-success
        (refresh-runtime/apply-success-and-return-when-address-active
         store
         stale-address
         (fn [state path payload]
           (update-in state path conj payload))
         [:results])]
    (is (= :payload (apply-success :payload)))
    (is (= [:payload] (:results @store)))
    (is (= :stale (apply-stale-success :stale)))
    (is (= [:payload] (:results @store)))
    (with-redefs [promise-effects/reject-error (fn [err]
                                                 {:rejected err})]
      (let [apply-error
            (refresh-runtime/apply-error-and-reject-when-address-active
             store
             address
             (fn [state path err]
               (update-in state path conj err))
             [:errors])
            apply-stale-error
            (refresh-runtime/apply-error-and-reject-when-address-active
             store
             stale-address
             (fn [state path err]
               (update-in state path conj err))
             [:errors])]
        (is (= {:rejected :boom}
               (apply-error :boom)))
        (is (= [:boom] (:errors @store)))
        (is (= {:rejected :stale-boom}
               (apply-stale-error :stale-boom)))
        (is (= [:boom] (:errors @store)))))))

(deftest refresh-snapshot-helpers-apply-successes-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          store (atom {:wallet {:address address}})
          calls (atom [])
          refresh-open-orders! @#'refresh-runtime/refresh-open-orders-snapshot!
          refresh-default! @#'refresh-runtime/refresh-default-clearinghouse-snapshot!
          refresh-spot! @#'refresh-runtime/refresh-spot-clearinghouse-snapshot!
          refresh-perp! @#'refresh-runtime/refresh-perp-dex-clearinghouse-snapshot!
          service (api-service/make-service
                   {:info-client-instance
                    {:request-info! (fn [body opts]
                                      (swap! calls conj [(get body "type") body opts])
                                      (case (get body "type")
                                        "frontendOpenOrders" (js/Promise.resolve [{:oid 1}])
                                        "spotClearinghouseState" (js/Promise.resolve {:balances [1]})
                                        "clearinghouseState" (js/Promise.resolve {:dex (get body "dex")
                                                                                  :ok true})
                                        (js/Promise.resolve nil)))
                     :get-request-stats (fn [] {})
                     :reset! (fn [] nil)}
                    :log-fn (fn [& _] nil)})]
      (api/install-api-service! service)
      (let [finish! (fn []
                      (api/reset-api-service!)
                      (done))]
        (-> (refresh-open-orders! store address "vault" {:priority :low})
            (.then (fn [payload]
                     (is (= [{:oid 1}] payload))
                     (is (= ["frontendOpenOrders"
                             {"type" "frontendOpenOrders"
                              "user" address
                              "dex" "vault"}
                             {:priority :low}]
                            (first @calls)))
                     (is (= [{:oid 1}]
                            (get-in @store [:orders :open-orders-snapshot-by-dex "vault"])))
                     (refresh-open-orders! store address "" nil)))
            (.then (fn [_]
                     (is (= ["frontendOpenOrders"
                             {"type" "frontendOpenOrders"
                              "user" address}
                             {:priority :high}]
                            (second @calls)))
                     (is (= [{:oid 1}]
                            (get-in @store [:orders :open-orders-snapshot])))
                     (refresh-default! store address {:priority :high})))
            (.then (fn [payload]
                     (is (= {:dex nil
                             :ok true}
                            payload))
                     (is (= {:dex nil
                             :ok true}
                            (get-in @store [:webdata2 :clearinghouseState])))
                     (refresh-spot! store address {:priority :medium})))
            (.then (fn [payload]
                     (is (= {:balances [1]} payload))
                     (is (= {:balances [1]}
                            (get-in @store [:spot :clearinghouse-state])))
                     (refresh-perp! store address "vault" {:priority :low})))
            (.then (fn [payload]
                     (is (= {:dex "vault"
                             :ok true}
                            payload))
                     (is (= {:dex "vault"
                             :ok true}
                            (get-in @store [:perp-dex-clearinghouse "vault"])))
                     (finish!)))
            (.catch (fn [err]
                      (api/reset-api-service!)
                      (is false (str "Unexpected error: " err))
                      (done))))))))

(deftest refresh-snapshot-helpers-apply-errors-and-log-default-failures-test
  (async done
    (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          store (atom {:wallet {:address address}
                       :errors []})
          rejected (atom [])
          refresh-open-orders! @#'refresh-runtime/refresh-open-orders-snapshot!
          refresh-default! @#'refresh-runtime/refresh-default-clearinghouse-snapshot!
          refresh-spot! @#'refresh-runtime/refresh-spot-clearinghouse-snapshot!
          refresh-perp! @#'refresh-runtime/refresh-perp-dex-clearinghouse-snapshot!
          service (api-service/make-service
                   {:info-client-instance
                    {:request-info! (fn [body _opts]
                                      (case (get body "type")
                                        "frontendOpenOrders" (js/Promise.reject {:message "open failed"
                                                                                 :category :protocol})
                                        "spotClearinghouseState" (js/Promise.reject {:message "spot failed"
                                                                                     :category :unexpected})
                                        "clearinghouseState" (js/Promise.reject {:message (if (get body "dex")
                                                                                            "perp failed"
                                                                                            "default failed")
                                                                                 :category (if (get body "dex")
                                                                                             :validation
                                                                                             :transport)})
                                        (js/Promise.reject {:message "unexpected"
                                                            :category :unexpected})))
                     :get-request-stats (fn [] {})
                     :reset! (fn [] nil)}
                    :log-fn (fn [& _] nil)})]
      (api/install-api-service! service)
      (-> (refresh-open-orders! store address "vault" {:priority :high})
          (.then (fn [_]
                   (is false "Expected refresh-open-orders! to reject")))
          (.catch (fn [err]
                    (swap! rejected conj err)
                    (is (= {:message "open failed"
                            :category :protocol}
                           err))
                    (refresh-default! store address {:priority :low})))
          (.then (fn [result]
                   (is (map? result))
                   (refresh-spot! store address {:priority :low})))
          (.then (fn [_]
                   (is false "Expected refresh-spot! to reject")))
          (.catch (fn [err]
                    (swap! rejected conj err)
                    (is (= {:message "spot failed"
                            :category :unexpected}
                           err))
                    (refresh-perp! store address "vault" {:priority :low})))
          (.then (fn [_]
                   (is false "Expected refresh-perp! to reject")))
          (.catch (fn [err]
                    (swap! rejected conj err)
                    (is (= {:message "perp failed"
                            :category :validation}
                           err))
                    (is (= "open failed"
                           (get-in @store [:orders :open-error])))
                    (is (= :protocol
                           (get-in @store [:orders :open-error-category])))
                    (is (= "spot failed"
                           (get-in @store [:spot :error])))
                    (is (= :unexpected
                           (get-in @store [:spot :error-category])))
                    (is (= "perp failed"
                           (get-in @store [:perp-dex-clearinghouse-error])))
                    (is (= :validation
                           (get-in @store [:perp-dex-clearinghouse-error-category])))
                    (is (= [{:message "open failed"
                             :category :protocol}
                            {:message "spot failed"
                             :category :unexpected}
                            {:message "perp failed"
                             :category :validation}]
                           @rejected))
                    (api/reset-api-service!)
                    (done)))))))

(deftest refresh-account-surface-delegation-and-nil-schedule-paths-test
  (let [address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        store (make-store address)
        captured-deps (atom nil)
        runtime (atom (runtime-state/default-runtime-state))
        cleared (atom [])
        scheduled (atom [])]
    (with-redefs [account-surface-service/refresh-after-user-fill!
                  (fn [deps]
                    (reset! captured-deps deps)
                    :delegated)
                  runtime-state/runtime runtime
                  platform/clear-timeout! (fn [timeout-id]
                                            (swap! cleared conj timeout-id))
                  platform/set-timeout! (fn [callback ms]
                                          (swap! scheduled conj [callback ms])
                                          :timeout-id)]
      (is (= :delegated
             (refresh-runtime/refresh-account-surfaces-after-user-fill! store address)))
      (is (= store (:store @captured-deps)))
      (is (= address (:address @captured-deps)))
      (is (fn? (:ensure-perp-dexs! @captured-deps)))
      (is (= subscriptions-runtime/sync-perp-dex-clearinghouse-subscriptions!
             (:sync-perp-dex-clearinghouse-subscriptions! @captured-deps)))
      (is (fn? (:refresh-open-orders! @captured-deps)))
      (is (fn? (:refresh-default-clearinghouse! @captured-deps)))
      (is (fn? (:refresh-spot-clearinghouse! @captured-deps)))
      (is (fn? (:refresh-perp-dex-clearinghouse! @captured-deps)))
      (refresh-runtime/clear-account-surface-refresh-timeout!)
      (is (empty? @cleared))
      (with-redefs [platform/set-timeout! (fn [_ _]
                                            (throw (js/Error. "should not schedule")))
                    account-surface-service/refresh-after-user-fill!
                    (fn [_]
                      (throw (js/Error. "should not refresh")))]
        (refresh-runtime/schedule-account-surface-refresh-after-fill!
         (atom {:wallet {:address nil}}))
        (refresh-runtime/schedule-account-surface-refresh-after-fill!
         (atom {:wallet {:address "not-an-address"}})))
      (is (empty? @scheduled)))))
