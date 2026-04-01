(ns hyperopen.runtime.action-adapters.wallet
  (:require [clojure.string :as str]
            [nexus.registry :as nxr]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.platform :as platform]
            [hyperopen.staking.actions :as staking-actions]
            [hyperopen.trading-crypto-modules :as trading-crypto-modules]
            [hyperopen.wallet.actions :as wallet-actions]
            [hyperopen.wallet.agent-runtime :as agent-runtime]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.connection-runtime :as wallet-connection-runtime]))

(def connect-wallet-action wallet-actions/connect-wallet-action)

(defn disconnect-wallet-action
  [_state]
  (wallet-actions/disconnect-wallet-action nil))

(def should-auto-enable-agent-trading?
  wallet-connection-runtime/should-auto-enable-agent-trading?)

(defn handle-wallet-connected
  [store connected-address]
  (let [result (wallet-connection-runtime/handle-wallet-connected!
                {:store store
                 :connected-address connected-address
                 :should-auto-enable-agent-trading? should-auto-enable-agent-trading?
                 :dispatch! nxr/dispatch})
        route (get-in @store [:router :path])]
    (when (str/starts-with? (or route "") "/vaults")
      (nxr/dispatch store nil [[:actions/load-vault-route route]]))
    (when (staking-actions/staking-route? route)
      (nxr/dispatch store nil [[:actions/load-staking-route route]]))
    result))

(defn enable-agent-trading
  [_ store {:keys [storage-mode is-mainnet agent-name signature-chain-id]
            :or {storage-mode :local
                 is-mainnet true
                 agent-name nil
                 signature-chain-id nil}}]
  (letfn [(set-agent-load-error! [err]
            (swap! store update-in [:wallet :agent] merge
                   {:status :error
                    :error (agent-runtime/runtime-error-message err)
                    :agent-address nil
                    :last-approved-at nil
                    :nonce-cursor nil}))
          (enable-with-crypto! [crypto]
            (agent-runtime/enable-agent-trading!
             {:store store
              :options {:storage-mode storage-mode
                        :is-mainnet is-mainnet
                        :agent-name agent-name
                        :signature-chain-id signature-chain-id}
              :create-agent-credentials! (:create-agent-credentials! crypto)
              :now-ms-fn platform/now-ms
              :normalize-storage-mode agent-session/normalize-storage-mode
              :default-signature-chain-id-for-environment
              agent-session/default-signature-chain-id-for-environment
              :build-approve-agent-action agent-session/build-approve-agent-action
              :format-agent-name-with-valid-until agent-session/format-agent-name-with-valid-until
              :approve-agent! trading-api/approve-agent!
              :persist-agent-session-by-mode! agent-session/persist-agent-session-by-mode!
              :runtime-error-message agent-runtime/runtime-error-message
              :exchange-response-error agent-runtime/exchange-response-error}))]
    (if-let [crypto (trading-crypto-modules/resolved-trading-crypto)]
      (enable-with-crypto! crypto)
      (-> (trading-crypto-modules/load-trading-crypto-module!)
          (.then enable-with-crypto!)
          (.catch set-agent-load-error!)))))

(defn enable-agent-trading-action
  [state]
  (wallet-actions/enable-agent-trading-action
   state
   agent-session/normalize-storage-mode))

(defn set-agent-storage-mode-action
  [state storage-mode]
  (wallet-actions/set-agent-storage-mode-action
   state
   storage-mode
   agent-session/normalize-storage-mode))

(def copy-wallet-address-action wallet-actions/copy-wallet-address-action)
