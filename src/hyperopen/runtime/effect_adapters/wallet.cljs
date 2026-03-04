(ns hyperopen.runtime.effect-adapters.wallet
  (:require [hyperopen.platform :as platform]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.wallet.agent-runtime :as agent-runtime]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.connection-runtime :as wallet-connection-runtime]
            [hyperopen.wallet.copy-feedback-runtime :as wallet-copy-runtime]
            [hyperopen.wallet.core :as wallet]))

(defn connect-wallet
  [_ store]
  (wallet-connection-runtime/connect-wallet!
   {:store store
    :log-fn telemetry/log!
    :request-connection! wallet/request-connection!}))

(defn set-agent-storage-mode
  [_ store storage-mode]
  (agent-runtime/set-agent-storage-mode!
   {:store store
    :storage-mode storage-mode
    :normalize-storage-mode agent-session/normalize-storage-mode
    :clear-agent-session-by-mode! agent-session/clear-agent-session-by-mode!
    :persist-storage-mode-preference! agent-session/persist-storage-mode-preference!
    :default-agent-state agent-session/default-agent-state
    :agent-storage-mode-reset-message runtime-state/agent-storage-mode-reset-message}))

(defn- set-wallet-copy-feedback!
  [store kind message]
  (wallet-copy-runtime/set-wallet-copy-feedback! store kind message))

(defn- clear-wallet-copy-feedback!
  [store]
  (wallet-copy-runtime/clear-wallet-copy-feedback! store))

(defn- clear-wallet-copy-feedback-timeout!
  [runtime]
  (wallet-copy-runtime/clear-wallet-copy-feedback-timeout-in-runtime!
   runtime
   platform/clear-timeout!))

(defn- schedule-wallet-copy-feedback-clear!
  [runtime store]
  (wallet-copy-runtime/schedule-wallet-copy-feedback-clear!
   {:store store
    :runtime runtime
    :clear-wallet-copy-feedback! clear-wallet-copy-feedback!
    :clear-wallet-copy-feedback-timeout! #(clear-wallet-copy-feedback-timeout! runtime)
    :wallet-copy-feedback-duration-ms runtime-state/wallet-copy-feedback-duration-ms
    :set-timeout-fn platform/set-timeout!}))

(defn copy-wallet-address
  ([_ store address]
   (copy-wallet-address runtime-state/runtime nil store address))
  ([runtime _ store address]
   (wallet-copy-runtime/copy-wallet-address!
    {:store store
     :address address
     :set-wallet-copy-feedback! set-wallet-copy-feedback!
     :clear-wallet-copy-feedback! clear-wallet-copy-feedback!
     :clear-wallet-copy-feedback-timeout! #(clear-wallet-copy-feedback-timeout! runtime)
     :schedule-wallet-copy-feedback-clear! #(schedule-wallet-copy-feedback-clear! runtime %)
     :log-fn telemetry/log!})))

(defn make-copy-wallet-address
  [runtime]
  (fn [ctx store address]
    (copy-wallet-address runtime ctx store address)))

(defn disconnect-wallet
  [runtime store {:keys [clear-order-feedback-toast-timeout!
                         clear-order-feedback-toast!]}]
  (wallet-connection-runtime/disconnect-wallet!
   {:store store
    :log-fn telemetry/log!
    :clear-wallet-copy-feedback-timeout! #(clear-wallet-copy-feedback-timeout! runtime)
    :clear-order-feedback-toast-timeout! #(clear-order-feedback-toast-timeout! runtime)
    :clear-order-feedback-toast! clear-order-feedback-toast!
    :set-disconnected! wallet/set-disconnected!}))
