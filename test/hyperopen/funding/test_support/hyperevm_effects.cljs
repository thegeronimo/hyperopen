(ns hyperopen.funding.test-support.hyperevm-effects
  "A harness for the HyperEVM Transfer submit effects: requests built by the
   real preview, a store, and captured toasts, dispatches, signatures, Spot
   re-reads and timers."
  (:require [hyperopen.funding.domain.evm-transfer-preview :as evm-preview]
            [hyperopen.funding.test-support.effects :as effects-support]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.hyperevm.domain.tokens :as tokens]
            [hyperopen.hyperevm.domain.transfer-state :as transfer-state]))

(def hash-1
  "0x1111111111111111111111111111111111111111111111111111111111111111")

(def purr-to-evm
  {:transfer-from :spot :transfer-to :hyperevm :transfer-asset support/purr-index})

(def hype-to-spot
  {:transfer-from :hyperevm :transfer-to :spot :transfer-asset support/hype-index})

(defn token
  [state index]
  (tokens/token-by-index (get-in state [:spot :meta]) index))

(defn core->evm
  [state index amount]
  (evm-preview/core->evm-request state {:from :spot :to :hyperevm} (token state index) amount))

(defn evm->core
  [state index amount]
  (evm-preview/evm->core-request state (token state index) amount))

(defn store-for
  [modal-overrides]
  (atom (support/state (merge {:amount-input "100" :submitting? true} modal-overrides))))

(defn harness
  "Submit-effect deps over `store` and `request` with every collaborator
   captured; `overrides` replace any of them."
  [store request overrides]
  (let [toasts (atom [])
        dispatches (atom [])
        signed (atom [])
        spot (atom [])
        timers (atom [])
        logs (atom [])]
    {:toasts toasts :dispatches dispatches :signed signed :spot spot :timers timers :logs logs
     :deps (merge (effects-support/base-submit-effect-deps)
                  {:store store
                   :request request
                   :dispatch! (fn [_ _ actions] (swap! dispatches into actions))
                   :submit-send-asset! (fn [_ owner action]
                                         (swap! signed conj [:send-asset owner action])
                                         (js/Promise.resolve {:status "ok"}))
                   :submit-usd-class-transfer! (fn [_ owner action]
                                                 (swap! signed conj [:usd-class owner action])
                                                 (js/Promise.resolve {:status "ok"}))
                   :refresh-spot-clearinghouse! (fn [_ address opts] (swap! spot conj [address opts]))
                   :next-flow-id! (constantly "flow-1")
                   :now-ms-fn (constantly support/now-ms)
                   :set-timeout-fn (fn [f ms] (swap! timers conj [ms f]) (count @timers))
                   :log-fn (fn [& args] (swap! logs conj (vec args)))
                   :show-toast! (effects-support/capture-toast! toasts)}
                  overrides)}))

(defn fire!
  "Run every captured timer scheduled `ms` after it was set."
  [timers ms]
  (doseq [[at f] @timers :when (= at ms)] (f)))

(defn run-of
  [store]
  (get-in @store [:funding-ui :modal :transfer-evm]))

(defn stub-submitter
  "A submitter that checks the in-flight entry exists before any wallet
   call, reports `events`, and resolves with `result`."
  [seen events result]
  (fn [store owner action {:keys [flow-id on-step!]}]
    (swap! seen conj {:entry (transfer-state/in-flight-entry @store owner)
                      :action action
                      :flow-id flow-id})
    (doseq [[step event] events] (on-step! step event))
    (js/Promise.resolve result)))

(defn spectating
  "`state` spectating another address: Spot now shows that account."
  [state]
  (assoc state :account-context {:spectate-mode {:active? true :address support/subaccount}}))
