(ns hyperopen.funding.application.submit-effects-gas-topup-test
  "The gas top-up rides the Transfer submit effect but owns its own status:
   it never closes the modal or writes the draft's `:error`/`:submitting?`,
   and every failure leaves the fix retryable instead of stuck submitting."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.application.submit-effects :as effects]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-preview]
            [hyperopen.funding.test-support.effects :as effects-support]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.test-support.async :as async-support]))

(def ^:private draft
  {:transfer-from :hyperevm :transfer-to :spot :transfer-asset support/purr-index
   :amount-input "20" :submitting? false :error nil
   :transfer-gas-topup {:status :submitting}})

(defn- store
  []
  (atom (support/state draft)))

(defn- request
  []
  (:request (evm-preview/gas-topup-request (support/state))))

(defn- submit!
  [store* overrides]
  (let [toasts (atom [])
        signed (atom [])
        result (effects/api-submit-funding-transfer!
                (merge (effects-support/base-submit-effect-deps)
                       {:store store*
                        :request (request)
                        :submit-send-asset! (fn [_ _ action]
                                              (swap! signed conj action)
                                              (js/Promise.resolve {:status "ok"}))
                        :submit-usd-class-transfer! (fn [& _]
                                                      (js/Promise.reject (js/Error. "wrong signer")))
                        :show-toast! (effects-support/capture-toast! toasts)}
                       overrides))]
    {:result result :toasts toasts :signed signed}))

(defn- draft-untouched?
  [store*]
  (= (dissoc (get-in (support/state draft) [:funding-ui :modal]) :transfer-gas-topup)
     (dissoc (get-in @store* [:funding-ui :modal]) :transfer-gas-topup)))

(deftest a-sent-top-up-keeps-the-draft-open-test
  (async done
    (let [store* (store)
          {:keys [result toasts signed]} (submit! store* {})]
      (-> result
          (.then (fn [_]
                   (is (= "0.05" (:amount (first @signed))))
                   (is (= :sent (get-in @store* [:funding-ui :modal :transfer-gas-topup :status])))
                   (is (true? (get-in @store* [:funding-ui :modal :open?])) "the modal stays open")
                   (is (draft-untouched? store*))
                   (is (= [[:success "Sent 0.05 HYPE to HyperEVM for gas."]] @toasts))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest an-exchange-error-reenables-the-fix-and-spares-the-draft-test
  (async done
    (let [store* (store)
          {:keys [result]} (submit! store* {:submit-send-asset!
                                            (fn [& _]
                                              (js/Promise.resolve {:status "err"
                                                                   :response "Insufficient balance"}))
                                            :exchange-response-error (fn [resp] (:response resp))})]
      (-> result
          (.then (fn [_]
                   (is (= {:status :failed :error "Gas top-up failed: Insufficient balance"}
                          (select-keys (get-in @store* [:funding-ui :modal :transfer-gas-topup])
                                       [:status :error])))
                   (is (draft-untouched? store*) "the draft's :error and :submitting? are untouched")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest a-runtime-error-reenables-the-fix-test
  (async done
    (let [store* (store)
          {:keys [result]} (submit! store* {:submit-send-asset!
                                            (fn [& _] (js/Promise.reject (js/Error. "offline")))
                                            :runtime-error-message (fn [err] (.-message err))})]
      (-> result
          (.then (fn [_]
                   (is (= {:status :failed :error "Gas top-up failed: offline"}
                          (select-keys (get-in @store* [:funding-ui :modal :transfer-gas-topup])
                                       [:status :error])))
                   (is (draft-untouched? store*))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest refusals-fail-the-fix-without-signing-test
  (let [spectating (atom (assoc (support/state draft) :account-context
                                {:spectate-mode {:active? true :address support/subaccount}}))
        {:keys [signed]} (submit! spectating {})]
    (is (= [] @signed))
    (is (= :failed (get-in @spectating [:funding-ui :modal :transfer-gas-topup :status])))
    (is (nil? (get-in @spectating [:funding-ui :modal :error]))))
  (let [tampered (store)
        {:keys [signed]} (submit! tampered {:request (assoc-in (request) [:action :destination]
                                                               "0x2000000000000000000000000000000000000001")})]
    (is (= [] @signed))
    (is (= :failed (get-in @tampered [:funding-ui :modal :transfer-gas-topup :status])))))

(deftest a-reopened-modal-only-records-its-own-top-up-test
  ;; Click fix -> close -> reopen -> click fix: the first top-up's outcome
  ;; must not land on the second one's fix.
  (async done
    (let [store* (store)
          first-send (atom nil)
          second-send (atom nil)
          ids (atom 0)
          deps {:next-flow-id! #(str "topup-" (swap! ids inc))}
          {first-result :result} (submit! store* (assoc deps :submit-send-asset!
                                                        (fn [& _] (js/Promise. #(reset! first-send %)))))]
      (is (= {:status :submitting :id "topup-1"}
             (get-in @store* [:funding-ui :modal :transfer-gas-topup])))
      (swap! store* assoc-in [:funding-ui :modal] {:open? false :transfer-gas-topup nil})
      (swap! store* assoc-in [:funding-ui :modal] (get-in (support/state draft) [:funding-ui :modal]))
      (let [{second-result :result}
            (submit! store* (assoc deps
                                   :submit-send-asset! (fn [& _] (js/Promise. (fn [_ reject]
                                                                                (reset! second-send reject))))
                                   :runtime-error-message (fn [err] (.-message err))))]
        (is (= {:status :submitting :id "topup-2"}
               (get-in @store* [:funding-ui :modal :transfer-gas-topup])))
        (@first-send {:status "ok"})
        (-> first-result
            (.then (fn [_]
                     (is (= {:status :submitting :id "topup-2"}
                            (get-in @store* [:funding-ui :modal :transfer-gas-topup]))
                         "the first top-up's success is not recorded on the second one's fix")
                     (@second-send (js/Error. "offline"))
                     second-result))
            (.then (fn [_]
                     (is (= {:status :failed :error "Gas top-up failed: offline" :id "topup-2"}
                            (get-in @store* [:funding-ui :modal :transfer-gas-topup]))
                         "the second top-up's own failure still lands")
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest a-closed-or-reopened-modal-is-left-alone-test
  (async done
    (let [store* (store)
          {:keys [result]} (submit! store* {})]
      (swap! store* assoc-in [:funding-ui :modal] {:open? false :transfer-gas-topup nil})
      (-> result
          (.then (fn [_]
                   (is (nil? (get-in @store* [:funding-ui :modal :transfer-gas-topup])))
                   (done)))
          (.catch (async-support/unexpected-error done))))))
