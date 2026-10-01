(ns hyperopen.funding.effects.hyperevm-runtime
  "Composition root for HyperEVM Transfer submits: binds the application
   flows to the wallet (EIP-1193), the public HyperEVM RPC, the HyperCore
   info client and the clock.

   Reads (gas price, allowance, estimates, receipts) go to the public RPC,
   never through the wallet, so they read HyperEVM whatever chain the wallet
   is on. Only signing and sending go through the wallet."
  (:require [hyperopen.api.default :as api]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.funding.application.hyperevm-submit :as hyperevm-submit]
            [hyperopen.funding.domain.transfer-run :as transfer-run]
            [hyperopen.funding.infrastructure.wallet-rpc :as wallet-rpc]
            [hyperopen.hyperevm.domain.abi :as abi]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.transfer-state :as transfer-state]
            [hyperopen.hyperevm.domain.units :as units]
            [hyperopen.hyperevm.infrastructure.rpc :as rpc]
            [hyperopen.platform :as platform]
            [hyperopen.telemetry :as telemetry]
            [hyperopen.wallet.core :as wallet]))

(def wallet-chain-config
  "HyperEVM as the wallet sees it: HYPE as native currency for
   `wallet_addEthereumChain`, and a verified switch (the chain is re-read
   after `wallet_switchEthereumChain`)."
  (assoc chain/mainnet
         :network-label "HyperEVM"
         :verify-switch? true))

(defonce ^:private flow-counter
  (atom 0))

(defn next-flow-id!
  "A flow id unique within the page: the clock plus a counter."
  []
  (str "hyperevm-" (platform/now-ms) "-" (swap! flow-counter inc)))

(defn read-allowance!
  "ERC-20 `allowance(owner, spender)` on `token-address`, read through the
   RPC, as a decimal units string."
  [rpc-deps token-address owner spender]
  (if-let [data (abi/encode-allowance owner spender)]
    (-> (rpc/eth-call! rpc-deps {:to token-address :data data})
        (.then (fn [result]
                 (or (some-> (abi/decode-uint256 result) units/units-text)
                     (js/Promise.reject (js/Error. "Invalid allowance response."))))))
    (js/Promise.reject (js/Error. "Invalid allowance request."))))

(defn- record-in-flight!
  [store]
  (fn [owner flow-id changes]
    (swap! store
           (fn [state]
             (if (:hash changes)
               (transfer-state/record-in-flight-hash state owner flow-id changes)
               (transfer-state/merge-in-flight state owner flow-id changes))))))

(defn submit-deps
  "The collaborators `hyperevm-submit/submit-hyperevm-to-core!` needs, bound
   to the real wallet, RPC and clock. `opts` may replace `:wallet-provider-fn`,
   `:rpc-deps` (the RPC client's `:fetch-fn` and timers), `:receipt-opts`
   (`rpc/wait-for-receipt!`'s) and `:now-ms-fn`; tests use them to run the
   real wiring against a fake wallet and a fake RPC."
  ([store]
   (submit-deps store {}))
  ([store {:keys [wallet-provider-fn rpc-deps receipt-opts now-ms-fn]
           :or {wallet-provider-fn wallet/provider
                rpc-deps {}
                receipt-opts {}
                now-ms-fn platform/now-ms}}]
   {:wallet-provider-fn wallet-provider-fn
    :state-fn (fn [] @store)
    :chain-config wallet-chain-config
    :ensure-wallet-chain! wallet-rpc/ensure-wallet-chain!
    :request-chain-id! wallet-rpc/request-chain-id!
    :send-transaction! wallet-rpc/send-transaction!
    :gas-price! (fn [] (rpc/gas-price! rpc-deps))
    :estimate-gas! (fn [tx] (rpc/estimate-gas! rpc-deps tx))
    :read-allowance! (fn [token-address owner spender]
                       (read-allowance! rpc-deps token-address owner spender))
    :wait-for-receipt! (fn [tx-hash] (rpc/wait-for-receipt! rpc-deps tx-hash receipt-opts))
    :record-in-flight! (record-in-flight! store)
    :now-ms-fn now-ms-fn}))

(defn submit-hyperevm-to-core-tx!
  "`(submit store owner action opts)`: the HyperEVM -> Core submitter bound
   to the real wallet and RPC."
  [store owner action opts]
  (hyperevm-submit/submit-hyperevm-to-core! (submit-deps store) owner action opts))

(defn refresh-spot-clearinghouse!
  "Re-read `owner`'s Spot balances after a HyperEVM move. `[:spot
   :clearinghouse-state]` holds the effective account's balances, so the
   reply is applied only while `owner` is still that account: a read that
   lands after the user selected a subaccount or started spectating is
   dropped. (The order-mutation refresh this replaces compared against the
   connected wallet, which stays the owner in both cases.)"
  ([store owner opts]
   (refresh-spot-clearinghouse! store owner opts api/request-spot-clearinghouse-state!))
  ([store owner opts request-spot-clearinghouse-state!]
   (let [apply-for-owner! (fn [f]
                            (swap! store (fn [state]
                                           (if (transfer-run/spot-shows-owner? state owner)
                                             (f state)
                                             state))))]
     (if-not (transfer-run/spot-shows-owner? @store owner)
       (js/Promise.resolve nil)
       (-> (request-spot-clearinghouse-state! owner opts)
           (.then (fn [data]
                    (apply-for-owner! #(api-projections/apply-spot-balances-success % data))))
           (.catch (fn [err]
                     (apply-for-owner! #(api-projections/apply-spot-balances-error % err))
                     (telemetry/log! "Error refreshing spot balances after a HyperEVM transfer:" err))))))))

(defn log!
  "The transfer effects' logger."
  [& args]
  (apply telemetry/log! args))

(defn get-transaction-receipt!
  [tx-hash]
  (rpc/get-transaction-receipt! {} tx-hash))
