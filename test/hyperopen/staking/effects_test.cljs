(ns hyperopen.staking.effects-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.staking.effects :as effects]))

(def ^:private wallet-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private spectate-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(def ^:private user-fetch-cases
  [{:invoke effects/api-fetch-staking-delegator-summary!
    :request-key :request-staking-delegator-summary!
    :begin-key :begin-staking-delegator-summary-load
    :success-key :apply-staking-delegator-summary-success
    :error-key :apply-staking-delegator-summary-error
    :result-path [:staking :delegator-summary]
    :loading-path [:staking :loading :delegator-summary]
    :payload {:undelegated 7}}
   {:invoke effects/api-fetch-staking-delegations!
    :request-key :request-staking-delegations!
    :begin-key :begin-staking-delegations-load
    :success-key :apply-staking-delegations-success
    :error-key :apply-staking-delegations-error
    :result-path [:staking :delegations]
    :loading-path [:staking :loading :delegations]
    :payload [{:validator wallet-address
               :amount 2}]}
   {:invoke effects/api-fetch-staking-rewards!
    :request-key :request-staking-delegator-rewards!
    :begin-key :begin-staking-rewards-load
    :success-key :apply-staking-rewards-success
    :error-key :apply-staking-rewards-error
    :result-path [:staking :rewards]
    :loading-path [:staking :loading :rewards]
    :payload [{:amount 1.5}]}
   {:invoke effects/api-fetch-staking-history!
    :request-key :request-staking-delegator-history!
    :begin-key :begin-staking-history-load
    :success-key :apply-staking-history-success
    :error-key :apply-staking-history-error
    :result-path [:staking :history]
    :loading-path [:staking :loading :history]
    :payload [{:event "delegate"}]}
   {:invoke effects/api-fetch-staking-spot-state!
    :request-key :request-spot-clearinghouse-state!
    :begin-key :begin-spot-balances-load
    :success-key :apply-spot-balances-success
    :error-key :apply-spot-balances-error
    :result-path [:spot :clearinghouse-state]
    :loading-path [:spot :loading :balances]
    :payload {:balances [{:coin "HYPE"
                          :available 3}]}}])
(deftest api-fetch-staking-validator-summaries-respects-route-gate-test
  (async done
    (let [store (atom {:router {:path "/trade"}})
          request-calls (atom 0)]
      (-> (effects/api-fetch-staking-validator-summaries!
           {:store store
            :request-staking-validator-summaries! (fn [_opts]
                                                    (swap! request-calls inc)
                                                    (js/Promise.resolve []))
            :begin-staking-validator-summaries-load identity
            :apply-staking-validator-summaries-success (fn [state _] state)
            :apply-staking-validator-summaries-error (fn [state _] state)})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @request-calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest api-fetch-staking-validator-summaries-applies-begin-and-success-projections-test
  (async done
    (let [store (atom {:router {:path "/staking"}})]
      (-> (effects/api-fetch-staking-validator-summaries!
           {:store store
            :request-staking-validator-summaries! (fn [_opts]
                                                    (js/Promise.resolve [{:validator "0xabc"}]))
            :begin-staking-validator-summaries-load (fn [state]
                                                      (assoc-in state [:staking :loading :validator-summaries] true))
            :apply-staking-validator-summaries-success (fn [state rows]
                                                         (-> state
                                                             (assoc-in [:staking :validator-summaries] rows)
                                                             (assoc-in [:staking :loading :validator-summaries] false)))
            :apply-staking-validator-summaries-error (fn [state _err]
                                                       (assoc-in state [:staking :loading :validator-summaries] false))})
          (.then (fn [_]
                   (is (= [{:validator "0xabc"}]
                          (get-in @store [:staking :validator-summaries])))
                   (is (= false
                          (get-in @store [:staking :loading :validator-summaries])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest api-fetch-staking-validator-summaries-strips-route-gate-opts-and-applies-error-projections-test
  (async done
    (let [store (atom {:router {:path "/trade"}})
          request-calls (atom [])]
      (-> (effects/api-fetch-staking-validator-summaries!
           {:store store
            :opts {:skip-route-gate? true
                   :priority :high}
            :request-staking-validator-summaries! (fn [opts]
                                                    (swap! request-calls conj opts)
                                                    (js/Promise.reject (js/Error. "summaries failed")))
            :begin-staking-validator-summaries-load (fn [state]
                                                      (assoc-in state [:staking :loading :validator-summaries] true))
            :apply-staking-validator-summaries-success (fn [state rows]
                                                         (assoc-in state [:staking :validator-summaries] rows))
            :apply-staking-validator-summaries-error (fn [state err]
                                                       (-> state
                                                           (assoc-in [:staking :loading :validator-summaries] false)
                                                           (assoc-in [:staking :errors :validator-summaries]
                                                                     (.-message err))))})
          (.then (fn [_]
                   (is false "Expected validator summaries fetch to reject")
                   (done)))
          (.catch (fn [err]
                    (is (= [{:priority :high}] @request-calls))
                    (is (= "summaries failed" (.-message err)))
                    (is (= false
                           (get-in @store [:staking :loading :validator-summaries])))
                    (is (= "summaries failed"
                           (get-in @store [:staking :errors :validator-summaries])))
                    (done)))))))

(deftest api-fetch-staking-user-scoped-loaders-forward-effective-address-and-strip-route-gate-flag-test
  (async done
    (-> (js/Promise.all
         (mapv (fn [{:keys [invoke
                            request-key
                            begin-key
                            success-key
                            error-key
                            result-path
                            loading-path
                            payload]}]
                 (let [store (atom {:router {:path "/trade"}
                                    :account-context {:spectate-mode {:active? true
                                                                      :address spectate-address}}})
                       request-calls (atom [])
                       deps {:store store
                             :opts {:skip-route-gate? true
                                    :priority :high}}]
                   (-> (invoke (assoc deps
                                      request-key (fn [address opts]
                                                    (swap! request-calls conj [address opts])
                                                    (js/Promise.resolve payload))
                                      begin-key (fn [state]
                                                  (assoc-in state loading-path true))
                                      success-key (fn [state result]
                                                    (-> state
                                                        (assoc-in result-path result)
                                                        (assoc-in loading-path false)))
                                      error-key (fn [state err]
                                                  (assoc-in state [:staking :errors :fetch]
                                                            (.-message err)))))
                       (.then (fn [result]
                                (is (= payload result))
                                (is (= [[spectate-address {:priority :high}]]
                                       @request-calls))
                                (is (= payload (get-in @store result-path)))
                                (is (= false (get-in @store loading-path))))))))
               user-fetch-cases))
        (.then (fn [_]
                 (done)))
        (.catch (fn [err]
                  (is false (str "Unexpected error: " err))
                  (done))))))

(deftest api-fetch-staking-user-scoped-loaders-short-circuit-when-route-or-address-is-missing-test
  (async done
    (let [inactive-route-promises
          (mapv (fn [{:keys [invoke
                             request-key
                             begin-key
                             success-key
                             error-key]}]
                  (let [store (atom {:router {:path "/trade"}})
                        request-calls (atom 0)]
                    (-> (invoke {:store store
                                 request-key (fn [_address _opts]
                                               (swap! request-calls inc)
                                               (js/Promise.resolve :should-not-run))
                                 begin-key identity
                                 success-key (fn [state _] state)
                                 error-key (fn [state _] state)})
                        (.then (fn [result]
                                 (is (nil? result))
                                 (is (= 0 @request-calls)))))))
                user-fetch-cases)
          missing-address-promises
          (mapv (fn [{:keys [invoke
                             request-key
                             begin-key
                             success-key
                             error-key]}]
                  (let [store (atom {:router {:path "/staking"}})
                        request-calls (atom 0)]
                    (-> (invoke {:store store
                                 request-key (fn [_address _opts]
                                               (swap! request-calls inc)
                                               (js/Promise.resolve :should-not-run))
                                 begin-key identity
                                 success-key (fn [state _] state)
                                 error-key (fn [state _] state)})
                        (.then (fn [result]
                                 (is (nil? result))
                                 (is (= 0 @request-calls)))))))
                user-fetch-cases)]
      (-> (js/Promise.all (into inactive-route-promises missing-address-promises))
          (.then (fn [_]
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest api-fetch-staking-user-scoped-loaders-apply-error-projections-on-rejection-test
  (async done
    (-> (js/Promise.all
         (mapv (fn [{:keys [invoke
                            request-key
                            begin-key
                            success-key
                            error-key
                            loading-path]}]
                 (let [store (atom {:router {:path "/staking"}})
                       request-calls (atom [])
                       rejection (js/Error. "fetch failed")]
                   (-> (invoke {:store store
                                :address wallet-address
                                request-key (fn [address opts]
                                              (swap! request-calls conj [address opts])
                                              (js/Promise.reject rejection))
                                begin-key (fn [state]
                                            (assoc-in state loading-path true))
                                success-key (fn [state _result]
                                              (assoc state :unexpected-success? true))
                                error-key (fn [state err]
                                            (-> state
                                                (assoc-in loading-path false)
                                                (assoc :fetch-error (.-message err))))})
                       (.then (fn [_]
                                (is false "Expected user-scoped fetch to reject")))
                       (.catch (fn [err]
                                 (is (= "fetch failed" (.-message err)))
                                 (is (= [[wallet-address {}]] @request-calls))
                                 (is (= false (get-in @store loading-path)))
                                 (is (= "fetch failed" (:fetch-error @store))))))))
               user-fetch-cases))
        (.then (fn [_]
                 (done)))
        (.catch (fn [err]
                  (is false (str "Unexpected error: " err))
                  (done))))))

(deftest submitting-key-normalizes-kind-aliases-and-defaults-test
  (let [submitting-key @#'hyperopen.staking.effects/submitting-key]
    (is (= :deposit? (submitting-key :deposit)))
    (is (= :withdraw? (submitting-key :withdraw?)))
    (is (= :delegate? (submitting-key :delegate)))
    (is (= :undelegate? (submitting-key :undelegate?)))
    (is (= :deposit? (submitting-key :unexpected-kind)))
    (is (= :deposit? (submitting-key nil)))))

(deftest api-submit-staking-deposit-success-clears-submitting-state-and-refreshes-test
  (async done
    (let [store (atom {:wallet {:address wallet-address}
                       :staking-ui {:submitting {:deposit? true}
                                    :deposit-amount "1.2"
                                    :form-error nil}})
          submit-calls (atom [])
          toasts (atom [])
          dispatches (atom [])]
      (-> (effects/api-submit-staking-deposit!
           {:store store
            :request {:kind :deposit
                      :action {:type "cDeposit"
                               :wei 120000000}}
            :submit-c-deposit! (fn [store* address action]
                                 (swap! submit-calls conj [store* address action])
                                 (js/Promise.resolve {:status "ok"}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))
            :dispatch! (fn [store* _ctx effects]
                         (swap! dispatches conj [store* effects]))})
          (.then (fn [resp]
                   (is (= {:status "ok"} resp))
                   (is (= false (get-in @store [:staking-ui :submitting :deposit?])))
                   (is (= "" (get-in @store [:staking-ui :deposit-amount])))
                   (is (nil? (get-in @store [:staking-ui :form-error])))
                   (is (= [[wallet-address
                            {:type "cDeposit" :wei 120000000}]]
                          (mapv (fn [[_store address action]]
                                  [address action])
                                @submit-calls)))
                   (is (= [[:success "Transfer to staking balance submitted."]]
                          @toasts))
                   (is (= [[[[:actions/load-staking]]]]
                          (mapv (fn [[_store effects]] [effects]) @dispatches)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest api-submit-staking-deposit-invalid-action-payload-sets-error-without-submitting-test
  (let [store (atom {:wallet {:address wallet-address}
                     :staking-ui {:submitting {:deposit? true}
                                  :form-error nil}})
        submit-calls (atom 0)
        toasts (atom [])]
    (effects/api-submit-staking-deposit!
     {:store store
      :request {:kind :deposit
                :action :invalid}
      :submit-c-deposit! (fn [_store _address _action]
                           (swap! submit-calls inc)
                           (js/Promise.resolve {:status "ok"}))
      :show-toast! (fn [_store kind message]
                     (swap! toasts conj [kind message]))})
    (is (= 0 @submit-calls))
    (is (= false (get-in @store [:staking-ui :submitting :deposit?])))
    (is (= "Invalid staking request payload."
           (get-in @store [:staking-ui :form-error])))
    (is (= [[:error "Invalid staking request payload."]]
           @toasts))))

(deftest api-submit-staking-withdraw-success-clears-input-without-refresh-handler-test
  (async done
    (let [store (atom {:wallet {:address wallet-address}
                       :staking-ui {:submitting {:withdraw? true}
                                    :withdraw-amount "0.75"
                                    :form-error "stale"}})
          submit-calls (atom [])
          toasts (atom [])]
      (-> (effects/api-submit-staking-withdraw!
           {:store store
            :request {:kind :withdraw
                      :action {:type "cWithdraw"
                               :wei 75000000}}
            :submit-c-withdraw! (fn [_store address action]
                                  (swap! submit-calls conj [address action])
                                  (js/Promise.resolve {:status "ok"}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= {:status "ok"} resp))
                   (is (= false (get-in @store [:staking-ui :submitting :withdraw?])))
                   (is (= "" (get-in @store [:staking-ui :withdraw-amount])))
                   (is (nil? (get-in @store [:staking-ui :form-error])))
                   (is (= [[wallet-address
                            {:type "cWithdraw" :wei 75000000}]]
                          @submit-calls))
                   (is (= [[:success "Transfer to spot balance submitted."]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest api-submit-staking-withdraw-covers-blocked-and-fallback-error-branches-test
  (let [blocked-store (atom {:wallet {:address wallet-address}
                             :account-context {:spectate-mode {:active? true
                                                               :address spectate-address}}
                             :staking-ui {:submitting {:withdraw? true}
                                          :form-error nil}})
        blocked-submit-calls (atom 0)
        blocked-toasts (atom [])]
    (effects/api-submit-staking-withdraw!
     {:store blocked-store
      :request {:kind :withdraw
                :action {:type "cWithdraw"
                         :wei 100000000}}
      :submit-c-withdraw! (fn [_store _address _action]
                            (swap! blocked-submit-calls inc)
                            (js/Promise.resolve {:status "ok"}))
      :show-toast! (fn [_store kind message]
                     (swap! blocked-toasts conj [kind message]))})
    (is (= 0 @blocked-submit-calls))
    (is (= false (get-in @blocked-store [:staking-ui :submitting :withdraw?])))
    (is (= account-context/spectate-mode-read-only-message
           (get-in @blocked-store [:staking-ui :form-error])))
    (is (= [[:error account-context/spectate-mode-read-only-message]]
           @blocked-toasts)))
  (async done
    (let [store (atom {:wallet {:address wallet-address}
                       :staking-ui {:submitting {:withdraw? true}
                                    :form-error nil}})
          toasts (atom [])]
      (-> (effects/api-submit-staking-withdraw!
           {:store store
            :request {:kind :withdraw
                      :action {:type "cWithdraw"
                               :wei 100000000}}
            :submit-c-withdraw! (fn [_store _address _action]
                                  (js/Promise.resolve {:status "error"}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= {:status "error"} resp))
                   (is (= false (get-in @store [:staking-ui :submitting :withdraw?])))
                   (is (= "Transfer to spot balance failed: Unknown exchange error"
                          (get-in @store [:staking-ui :form-error])))
                   (is (= [[:error "Transfer to spot balance failed: Unknown exchange error"]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest api-submit-staking-delegate-without-wallet-sets-error-without-submitting-test
  (let [store (atom {:wallet {}
                     :staking-ui {:submitting {:delegate? true}
                                  :form-error nil}})
        submit-calls (atom 0)
        toasts (atom [])]
    (effects/api-submit-staking-delegate!
     {:store store
      :request {:kind :delegate
                :action {:type "tokenDelegate"
                         :validator "0x1234567890abcdef1234567890abcdef12345678"
                         :wei 100000000
                         :isUndelegate false}}
      :submit-token-delegate! (fn [_store _address _action]
                                (swap! submit-calls inc)
                                (js/Promise.resolve {:status "ok"}))
      :show-toast! (fn [_store kind message]
                     (swap! toasts conj [kind message]))})
    (is (= 0 @submit-calls))
    (is (= false (get-in @store [:staking-ui :submitting :delegate?])))
    (is (= "Connect your wallet before submitting stake."
           (get-in @store [:staking-ui :form-error])))
    (is (= [[:error "Connect your wallet before submitting stake."]]
           @toasts))))

(deftest api-submit-staking-delegate-runtime-rejection-sets-form-error-and-toast-test
  (async done
    (let [store (atom {:wallet {:address wallet-address}
                       :staking-ui {:submitting {:delegate? true}
                                    :delegate-amount "1"
                                    :form-error nil}})
          toasts (atom [])]
      (-> (effects/api-submit-staking-delegate!
           {:store store
            :request {:kind :delegate
                      :action {:type "tokenDelegate"
                               :validator wallet-address
                               :wei 100000000
                               :isUndelegate false}}
            :submit-token-delegate! (fn [_store _address _action]
                                      (js/Promise.reject (js/Error. "rpc boom")))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message])
                           nil)})
          (.then (fn [resp]
                   (is (nil? resp))
                   (is (= false (get-in @store [:staking-ui :submitting :delegate?])))
                   (is (= "Stake failed: rpc boom"
                          (get-in @store [:staking-ui :form-error])))
                   (is (= [[:error "Stake failed: rpc boom"]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest api-submit-staking-undelegate-predicate-kind-updates-undelegate-submit-state-test
  (async done
    (let [store (atom {:wallet {:address wallet-address}
                       :staking-ui {:submitting {:undelegate? true}
                                    :form-error nil}})
          toasts (atom [])]
      (-> (effects/api-submit-staking-undelegate!
           {:store store
            :request {:kind :undelegate?
                      :action {:type "tokenDelegate"
                               :validator wallet-address
                               :wei 100000000
                               :isUndelegate true}}
            :submit-token-delegate! (fn [_store _address _action]
                                      (js/Promise.resolve {:status "error"
                                                           :message "validator busy"}))
            :show-toast! (fn [_store kind message]
                           (swap! toasts conj [kind message]))})
          (.then (fn [resp]
                   (is (= {:status "error" :message "validator busy"} resp))
                   (is (= false (get-in @store [:staking-ui :submitting :undelegate?])))
                   (is (= "Staking action failed: validator busy"
                          (get-in @store [:staking-ui :form-error])))
                   (is (= [[:error "Staking action failed: validator busy"]]
                          @toasts))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))
