(ns hyperopen.funding.effects-api-wrappers-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.funding.application.hyperunit-query :as hyperunit-query]
            [hyperopen.funding.application.submit-effects :as submit-effects]
            [hyperopen.funding.effects :as effects]
            [hyperopen.funding.effects.common :as common]
            [hyperopen.funding.effects.hyperunit-runtime :as hyperunit-runtime]
            [hyperopen.funding.effects.transport-runtime :as transport-runtime]
            [hyperopen.funding.test-support.effects :as effects-support]))

(deftest funding-effect-public-api-wrappers-forward-overrides-to-application-layer-test
  (let [custom-estimate! (fn [& _] :estimate)
        custom-queue! (fn [& _] :queue)
        custom-transfer! (fn [& _] :transfer)
        custom-send! (fn [& _] :send)
        custom-withdraw! (fn [& _] :withdraw)
        custom-address-request! (fn [& _] :address-request)
        custom-usdc-deposit! (fn [& _] :usdc-deposit)
        custom-usdt-deposit! (fn [& _] :usdt-deposit)
        custom-usdh-deposit! (fn [& _] :usdh-deposit)
        custom-show-toast! (fn [& _] :toast)
        custom-dispatch! (fn [& _] :dispatch)
        custom-now-ms-fn (fn [] 4242)
        custom-runtime-error-message (fn [err] (str "runtime:" err))
        store (atom {:wallet {:chain-id "421614"}})
        seen (atom {})]
    (with-redefs [hyperunit-query/api-fetch-hyperunit-fee-estimate!
                  (fn [deps opts]
                    (swap! seen assoc :fee-estimate {:deps deps
                                                     :opts opts})
                    :fee-estimate-result)
                  hyperunit-query/fetch-hyperunit-withdrawal-queue!
                  (fn [deps opts]
                    (swap! seen assoc :withdrawal-queue {:deps deps
                                                         :opts opts})
                    :withdrawal-queue-result)
                  submit-effects/api-submit-funding-transfer!
                  (fn [deps]
                    (swap! seen assoc :transfer deps)
                    :transfer-result)
                  submit-effects/api-submit-funding-send!
                  (fn [deps]
                    (swap! seen assoc :send deps)
                    :send-result)
                  submit-effects/api-submit-funding-withdraw!
                  (fn [deps]
                    (swap! seen assoc :withdraw deps)
                    :withdraw-result)
                  submit-effects/api-submit-funding-deposit!
                  (fn [deps]
                    (swap! seen assoc :deposit deps)
                    :deposit-result)]
      (is (= :fee-estimate-result
             (effects/api-fetch-hyperunit-fee-estimate! {:store store
                                                         :request-hyperunit-estimate-fees! custom-estimate!
                                                         :now-ms-fn custom-now-ms-fn
                                                         :runtime-error-message custom-runtime-error-message})))
      (is (identical? custom-estimate!
                      (get-in @seen [:fee-estimate :opts :request-hyperunit-estimate-fees!])))
      (is (identical? custom-now-ms-fn
                      (get-in @seen [:fee-estimate :opts :now-ms-fn])))
      (is (identical? custom-runtime-error-message
                      (get-in @seen [:fee-estimate :opts :runtime-error-message])))
      (is (identical? hyperunit-runtime/prefetch-selected-hyperunit-deposit-address!
                      (get-in @seen [:fee-estimate :deps :prefetch-selected-hyperunit-deposit-address!])))

      (is (= :withdrawal-queue-result
             (effects/api-fetch-hyperunit-withdrawal-queue! {:store store
                                                             :request-hyperunit-withdrawal-queue! custom-queue!
                                                             :now-ms-fn custom-now-ms-fn
                                                             :runtime-error-message custom-runtime-error-message})))
      (is (= "https://api.hyperunit-testnet.xyz"
             (get-in @seen [:withdrawal-queue :opts :base-url])))
      (is (= ["https://api.hyperunit-testnet.xyz"]
             (get-in @seen [:withdrawal-queue :opts :base-urls])))
      (is (identical? custom-queue!
                      (get-in @seen [:withdrawal-queue :opts :request-hyperunit-withdrawal-queue!])))

      (is (= :transfer-result
             (effects/api-submit-funding-transfer! {:store store
                                                    :request {:action {:type "usdClassTransfer"}}
                                                    :dispatch! custom-dispatch!
                                                    :submit-usd-class-transfer! custom-transfer!
                                                    :runtime-error-message custom-runtime-error-message
                                                    :show-toast! custom-show-toast!
                                                    :default-funding-modal-state effects-support/default-funding-modal-state})))
      (is (identical? custom-transfer!
                      (:submit-usd-class-transfer! (:transfer @seen))))
      (is (identical? effects/set-funding-submit-error!
                      (:set-funding-submit-error! (:transfer @seen))))
      (is (identical? effects/close-funding-modal!
                      (:close-funding-modal! (:transfer @seen))))

      (is (= :send-result
             (effects/api-submit-funding-send! {:store store
                                                :request {:action {:type "sendAsset"}}
                                                :dispatch! custom-dispatch!
                                                :submit-send-asset! custom-send!
                                                :show-toast! custom-show-toast!
                                                :default-funding-modal-state effects-support/default-funding-modal-state})))
      (is (identical? custom-send!
                      (:submit-send-asset! (:send @seen))))
      (is (identical? effects/refresh-after-funding-submit!
                      (:refresh-after-funding-submit! (:send @seen))))

      (is (= :withdraw-result
             (effects/api-submit-funding-withdraw! {:store store
                                                    :request {:action {:type "withdraw3"}}
                                                    :dispatch! custom-dispatch!
                                                    :submit-withdraw3! custom-withdraw!
                                                    :submit-send-asset! custom-send!
                                                    :request-hyperunit-operations! :ops
                                                    :request-hyperunit-withdrawal-queue! :queue
                                                    :set-timeout-fn :timeout
                                                    :now-ms-fn custom-now-ms-fn
                                                    :runtime-error-message custom-runtime-error-message
                                                    :show-toast! custom-show-toast!
                                                    :default-funding-modal-state effects-support/default-funding-modal-state})))
      (is (= :ops
             (:request-hyperunit-operations! (:withdraw @seen))))
      (is (= :queue
             (:request-hyperunit-withdrawal-queue! (:withdraw @seen))))
      (is (identical? hyperunit-runtime/start-hyperunit-withdraw-lifecycle-polling!
                      (:start-hyperunit-withdraw-lifecycle-polling! (:withdraw @seen))))

      (is (= :deposit-result
             (effects/api-submit-funding-deposit! {:store store
                                                   :request {:action {:type "bridge2Deposit"}}
                                                   :dispatch! custom-dispatch!
                                                   :submit-usdc-bridge2-deposit! custom-usdc-deposit!
                                                   :submit-usdt-lifi-deposit! custom-usdt-deposit!
                                                   :submit-usdh-across-deposit! custom-usdh-deposit!
                                                   :submit-hyperunit-address-request! custom-address-request!
                                                   :request-hyperunit-operations! :ops
                                                   :set-timeout-fn :timeout
                                                   :now-ms-fn custom-now-ms-fn
                                                   :runtime-error-message custom-runtime-error-message
                                                   :show-toast! custom-show-toast!
                                                   :default-funding-modal-state effects-support/default-funding-modal-state})))
      (is (identical? custom-address-request!
                      (:submit-hyperunit-address-request! (:deposit @seen))))
      (is (identical? custom-usdc-deposit!
                      (:submit-usdc-bridge2-deposit! (:deposit @seen))))
      (is (identical? custom-usdt-deposit!
                      (:submit-usdt-lifi-deposit! (:deposit @seen))))
      (is (identical? custom-usdh-deposit!
                      (:submit-usdh-across-deposit! (:deposit @seen))))
      (is (identical? hyperunit-runtime/start-hyperunit-deposit-lifecycle-polling!
                      (:start-hyperunit-deposit-lifecycle-polling! (:deposit @seen)))))))

(deftest funding-effect-public-api-wrappers-use-internal-default-collaborators-test
  (let [store (atom {:wallet {:chain-id "0xa4b1"}})
        seen (atom {})]
    (with-redefs [hyperunit-query/api-fetch-hyperunit-fee-estimate!
                  (fn [deps opts]
                    (swap! seen assoc :fee-estimate {:deps deps
                                                     :opts opts})
                    :fee-estimate-result)
                  submit-effects/api-submit-funding-withdraw!
                  (fn [deps]
                    (swap! seen assoc :withdraw deps)
                    :withdraw-result)
                  submit-effects/api-submit-funding-deposit!
                  (fn [deps]
                    (swap! seen assoc :deposit deps)
                    :deposit-result)]
      (is (= :fee-estimate-result
             (effects/api-fetch-hyperunit-fee-estimate! {:store store})))
      (is (identical? hyperunit-runtime/request-hyperunit-estimate-fees!
                      (get-in @seen [:fee-estimate :opts :request-hyperunit-estimate-fees!])))
      (is (identical? common/fallback-runtime-error-message
                      (get-in @seen [:fee-estimate :opts :runtime-error-message])))

      (is (= :withdraw-result
             (effects/api-submit-funding-withdraw! {:store store
                                                    :request {:action {:type "withdraw3"}}
                                                    :dispatch! (fn [& _] nil)})))
      (is (identical? transport-runtime/submit-hyperunit-send-asset-withdraw-request!
                      (:submit-hyperunit-send-asset-withdraw-request-fn (:withdraw @seen))))
      (is (identical? common/fallback-exchange-response-error
                      (:exchange-response-error (:withdraw @seen))))

      (is (= :deposit-result
             (effects/api-submit-funding-deposit! {:store store
                                                   :request {:action {:type "bridge2Deposit"}}
                                                   :dispatch! (fn [& _] nil)})))
      (is (identical? transport-runtime/submit-usdc-bridge2-deposit-tx!
                      (:submit-usdc-bridge2-deposit! (:deposit @seen))))
      (is (identical? transport-runtime/submit-usdt-lifi-bridge2-deposit-tx!
                      (:submit-usdt-lifi-deposit! (:deposit @seen))))
      (is (identical? transport-runtime/submit-usdh-across-deposit-tx!
                      (:submit-usdh-across-deposit! (:deposit @seen))))
      (is (identical? transport-runtime/submit-hyperunit-address-deposit-request!
                      (:submit-hyperunit-address-request! (:deposit @seen)))))))
