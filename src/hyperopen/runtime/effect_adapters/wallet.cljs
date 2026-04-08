(ns hyperopen.runtime.effect-adapters.wallet
  (:require [hyperopen.account.spectate-mode-links :as spectate-mode-links]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.wallet.agent-lockbox :as agent-lockbox]
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
    :normalize-local-protection-mode agent-session/normalize-local-protection-mode
    :clear-persisted-agent-session!
    (fn [wallet-address mode local-protection-mode]
      (agent-session/clear-persisted-agent-session! wallet-address mode local-protection-mode)
      (when (= :passkey (agent-session/normalize-local-protection-mode local-protection-mode))
        (agent-lockbox/delete-locked-session! wallet-address)))
    :clear-unlocked-session! agent-lockbox/clear-unlocked-session!
    :persist-storage-mode-preference! agent-session/persist-storage-mode-preference!
    :default-agent-state agent-session/default-agent-state
    :agent-storage-mode-reset-message runtime-state/agent-storage-mode-reset-message}))

(defn set-agent-local-protection-mode
  [_ store local-protection-mode]
  (agent-runtime/set-agent-local-protection-mode!
   {:store store
    :local-protection-mode local-protection-mode
    :normalize-local-protection-mode agent-session/normalize-local-protection-mode
    :normalize-storage-mode agent-session/normalize-storage-mode
    :clear-persisted-agent-session!
    (fn [wallet-address mode local-protection-mode*]
      (agent-session/clear-persisted-agent-session! wallet-address mode local-protection-mode*)
      (when (= :passkey (agent-session/normalize-local-protection-mode local-protection-mode*))
        (agent-lockbox/delete-locked-session! wallet-address)))
    :clear-agent-session-by-mode! agent-session/clear-agent-session-by-mode!
    :load-agent-session-by-mode agent-session/load-agent-session-by-mode
    :load-unlocked-session agent-lockbox/load-unlocked-session
    :clear-unlocked-session! agent-lockbox/clear-unlocked-session!
    :cache-unlocked-session! agent-lockbox/cache-unlocked-session!
    :create-locked-session! agent-lockbox/create-locked-session!
    :delete-locked-session! agent-lockbox/delete-locked-session!
    :persist-agent-session-by-mode! agent-session/persist-agent-session-by-mode!
    :persist-passkey-session-metadata! agent-session/persist-passkey-session-metadata!
    :clear-passkey-session-metadata! agent-session/clear-passkey-session-metadata!
    :persist-local-protection-mode-preference!
    agent-session/persist-local-protection-mode-preference!
    :default-agent-state agent-session/default-agent-state
    :agent-protection-mode-reset-message runtime-state/agent-protection-mode-reset-message
    :persist-session-error "Unable to persist agent credentials."
    :missing-session-error "Trading session data is unavailable. Enable Trading again."
    :unlock-required-error "Unlock trading before turning off passkey protection."}))

(defn unlock-agent-trading
  [_ store]
  (agent-runtime/unlock-agent-trading!
   {:store store
    :normalize-storage-mode agent-session/normalize-storage-mode
    :normalize-local-protection-mode agent-session/normalize-local-protection-mode
    :load-passkey-session-metadata agent-session/load-passkey-session-metadata
    :unlock-locked-session! agent-lockbox/unlock-locked-session!
    :runtime-error-message agent-runtime/runtime-error-message}))

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

(defn copy-spectate-link
  ([_ store path address]
   (copy-spectate-link runtime-state/runtime nil store path address))
  ([runtime _ store path address]
   (wallet-copy-runtime/copy-spectate-link!
    {:store store
     :url (spectate-mode-links/spectate-url path address)
     :set-wallet-copy-feedback! set-wallet-copy-feedback!
     :clear-wallet-copy-feedback! clear-wallet-copy-feedback!
     :clear-wallet-copy-feedback-timeout! #(clear-wallet-copy-feedback-timeout! runtime)
     :schedule-wallet-copy-feedback-clear! #(schedule-wallet-copy-feedback-clear! runtime %)
     :log-fn telemetry/log!})))

(defn make-copy-spectate-link
  [runtime]
  (fn [ctx store path address]
    (copy-spectate-link runtime ctx store path address)))

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
