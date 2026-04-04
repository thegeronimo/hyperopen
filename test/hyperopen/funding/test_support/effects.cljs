(ns hyperopen.funding.test-support.effects
  (:require [clojure.string :as str]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.application.modal-state :as modal-state]
            [hyperopen.funding.application.hyperunit-query :as hyperunit-query]
            [hyperopen.funding.application.lifecycle-guards :as lifecycle-guards]
            [hyperopen.funding.application.lifecycle-polling :as lifecycle-polling]
            [hyperopen.funding.domain.lifecycle-operations :as lifecycle-ops]
            [hyperopen.funding.domain.policy :as funding-policy]))

(defn seed-modal
  [mode]
  {:open? true
   :mode mode
   :submitting? true
   :error nil
   :send-token nil
   :send-symbol nil
   :send-prefix-label nil
   :send-max-amount nil
   :send-max-display nil
   :send-max-input ""
   :amount-input "10"
   :destination-input "0x1234567890abcdef1234567890abcdef12345678"
   :withdraw-selected-asset-key :usdc
   :withdraw-generated-address nil
   :hyperunit-lifecycle (funding-actions/default-hyperunit-lifecycle-state)
   :hyperunit-fee-estimate (funding-actions/default-hyperunit-fee-estimate-state)
   :hyperunit-withdrawal-queue (funding-actions/default-hyperunit-withdrawal-queue-state)})

(defn default-funding-modal-state
  []
  {:open? false
   :mode nil
   :submitting? false
   :error nil
   :focus-return-data-role nil
   :focus-return-token 0})

(defn base-store
  [mode]
  {:wallet {:address "0xabc"}
   :funding-ui {:modal (seed-modal mode)}})

(defn capture-toast!
  [toasts]
  (fn [_store kind message]
    (swap! toasts conj [kind message])
    nil))

(defn capture-dispatch!
  [dispatches]
  (fn [store* _ event]
    (swap! dispatches conj [store* event])
    nil))

(defn fallback-exchange-response-error
  [resp]
  (or (some-> (:error resp) str str/trim not-empty)
      (some-> (:message resp) str str/trim not-empty)
      (some-> (:response resp) str str/trim not-empty)
      "Unknown exchange error"))

(defn fallback-runtime-error-message
  [err]
  (or (some-> err .-message)
      (str err)))

(defn set-funding-submit-error!
  [store show-toast! error-text]
  (swap! store
         (fn [state]
           (-> state
               (assoc-in [:funding-ui :modal :submitting?] false)
               (assoc-in [:funding-ui :modal :error] error-text))))
  (show-toast! store :error error-text))

(defn close-funding-modal!
  [store default-funding-modal-state]
  (swap! store update-in [:funding-ui :modal]
         (fn [modal]
           (modal-state/closed-funding-modal-state default-funding-modal-state modal))))

(defn refresh-after-funding-submit!
  [store dispatch! address]
  (when (and (fn? dispatch!)
             (string? address))
    (dispatch! store nil [[:actions/load-user-data address]])))

(defn resolve-hyperunit-base-urls
  [store]
  (let [chain-id (some-> @store :wallet :chain-id str str/trim str/lower-case)]
    (if (contains? #{"0x66eee" "421614"} chain-id)
      ["https://api.hyperunit-testnet.xyz"]
      ["https://api.hyperunit.xyz"])))

(defn awaiting-deposit-lifecycle
  [asset-key now-ms]
  (lifecycle-ops/awaiting-deposit-lifecycle
   funding-actions/normalize-hyperunit-lifecycle
   asset-key
   now-ms))

(defn awaiting-withdraw-lifecycle
  [asset-key now-ms]
  (lifecycle-ops/awaiting-withdraw-lifecycle
   funding-actions/normalize-hyperunit-lifecycle
   asset-key
   now-ms))

(defn fetch-hyperunit-withdrawal-queue!
  [opts]
  (hyperunit-query/fetch-hyperunit-withdrawal-queue!
   {:modal-active-for-withdraw-queue? lifecycle-guards/modal-active-for-withdraw-queue?
    :normalize-hyperunit-withdrawal-queue funding-actions/normalize-hyperunit-withdrawal-queue
    :resolve-hyperunit-base-urls resolve-hyperunit-base-urls
    :non-blank-text funding-policy/non-blank-text
    :fallback-runtime-error-message fallback-runtime-error-message}
   opts))

(defn start-hyperunit-lifecycle-polling!
  [opts]
  (let [tokens* (atom {})]
    (lifecycle-polling/start-hyperunit-lifecycle-polling!
     (merge {:lifecycle-poll-key-fn lifecycle-guards/lifecycle-poll-key
             :install-lifecycle-poll-token! (fn [poll-key token]
                                              (lifecycle-guards/install-lifecycle-poll-token! tokens* poll-key token))
             :clear-lifecycle-poll-token! (fn [poll-key token]
                                            (lifecycle-guards/clear-lifecycle-poll-token! tokens* poll-key token))
             :lifecycle-poll-token-active? (fn [poll-key token]
                                             (lifecycle-guards/lifecycle-poll-token-active? tokens* poll-key token))
             :modal-active-for-lifecycle? lifecycle-guards/modal-active-for-lifecycle?
             :normalize-hyperunit-lifecycle funding-actions/normalize-hyperunit-lifecycle
             :select-operation lifecycle-ops/select-operation
             :operation->lifecycle (fn [operation direction asset-key now-ms]
                                     (lifecycle-ops/operation->lifecycle
                                      funding-actions/normalize-hyperunit-lifecycle
                                      operation
                                      direction
                                      asset-key
                                      now-ms))
             :awaiting-lifecycle (fn [direction asset-key now-ms]
                                   (lifecycle-ops/awaiting-lifecycle
                                    funding-actions/normalize-hyperunit-lifecycle
                                    direction
                                    asset-key
                                    now-ms))
             :lifecycle-next-delay-ms (fn [now-ms lifecycle]
                                        (lifecycle-ops/lifecycle-next-delay-ms
                                         {:default-delay-ms 3000
                                          :min-delay-ms 1000
                                          :max-delay-ms 60000}
                                         now-ms
                                         lifecycle))
             :hyperunit-lifecycle-terminal? funding-actions/hyperunit-lifecycle-terminal?
             :fetch-hyperunit-withdrawal-queue! fetch-hyperunit-withdrawal-queue!
             :non-blank-text funding-policy/non-blank-text
             :default-poll-delay-ms 3000
             :runtime-error-message fallback-runtime-error-message}
            opts))))

(defn start-hyperunit-deposit-lifecycle-polling!
  [opts]
  (start-hyperunit-lifecycle-polling!
   (assoc opts :direction :deposit)))

(defn start-hyperunit-withdraw-lifecycle-polling!
  [opts]
  (start-hyperunit-lifecycle-polling!
   (assoc opts :direction :withdraw)))

(defn base-submit-effect-deps
  []
  {:submit-send-asset! (fn [_store _address _action]
                         (js/Promise.resolve {:status "ok"}))
   :exchange-response-error fallback-exchange-response-error
   :runtime-error-message fallback-runtime-error-message
   :default-funding-modal-state default-funding-modal-state
   :set-funding-submit-error! set-funding-submit-error!
   :close-funding-modal! close-funding-modal!
   :refresh-after-funding-submit! refresh-after-funding-submit!
   :resolve-hyperunit-base-urls resolve-hyperunit-base-urls
   :awaiting-deposit-lifecycle awaiting-deposit-lifecycle
   :awaiting-withdraw-lifecycle awaiting-withdraw-lifecycle
   :start-hyperunit-deposit-lifecycle-polling! start-hyperunit-deposit-lifecycle-polling!
   :start-hyperunit-withdraw-lifecycle-polling! start-hyperunit-withdraw-lifecycle-polling!})
