(ns hyperopen.staking.effects-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.staking.effects :as effects]))

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
    (let [store (atom {:wallet {:address "0x1234567890abcdef1234567890abcdef12345678"}
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
                   (is (= [["0x1234567890abcdef1234567890abcdef12345678"
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

(deftest api-submit-staking-undelegate-predicate-kind-updates-undelegate-submit-state-test
  (async done
    (let [store (atom {:wallet {:address "0x1234567890abcdef1234567890abcdef12345678"}
                       :staking-ui {:submitting {:undelegate? true}
                                    :form-error nil}})
          toasts (atom [])]
      (-> (effects/api-submit-staking-undelegate!
           {:store store
            :request {:kind :undelegate?
                      :action {:type "tokenDelegate"
                               :validator "0x1234567890abcdef1234567890abcdef12345678"
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
