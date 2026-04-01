(ns hyperopen.trading-crypto-modules
  (:require [goog.object :as gobj]
            [shadow.loader :as loader]))

(def ^:private trading-crypto-module-name
  "trading_crypto")

(def ^:private exported-function-paths
  {:create-agent-credentials! ["hyperopen" "trading_crypto" "module" "createAgentCredentials"]
   :private-key->agent-address ["hyperopen" "trading_crypto" "module" "privateKeyToAgentAddress"]
   :sign-l1-action-with-private-key! ["hyperopen" "trading_crypto" "module" "signL1ActionWithPrivateKey"]
   :sign-approve-agent-action! ["hyperopen" "trading_crypto" "module" "signApproveAgentAction"]
   :sign-usd-class-transfer-action! ["hyperopen" "trading_crypto" "module" "signUsdClassTransferAction"]
   :sign-send-asset-action! ["hyperopen" "trading_crypto" "module" "signSendAssetAction"]
   :sign-c-deposit-action! ["hyperopen" "trading_crypto" "module" "signCDepositAction"]
   :sign-c-withdraw-action! ["hyperopen" "trading_crypto" "module" "signCWithdrawAction"]
   :sign-token-delegate-action! ["hyperopen" "trading_crypto" "module" "signTokenDelegateAction"]
   :sign-withdraw3-action! ["hyperopen" "trading_crypto" "module" "signWithdraw3Action"]})

(defonce ^:private resolved-trading-crypto* (atom nil))
(defonce ^:private inflight-trading-crypto-load* (atom nil))

(defn- resolve-exported-function
  [path-segments]
  (let [root (or (some-> js/goog .-global)
                 js/globalThis)]
    (reduce (fn [acc segment]
              (when acc
                (gobj/get acc segment)))
            root
            path-segments)))

(defn- trading-crypto-ready?
  [resolved]
  (and (map? resolved)
       (every? fn? (vals resolved))))

(defn- resolve-exported-trading-crypto
  []
  (reduce-kv (fn [resolved key path-segments]
               (assoc resolved key (resolve-exported-function path-segments)))
             {}
             exported-function-paths))

(defn resolved-trading-crypto
  []
  (let [cached @resolved-trading-crypto*]
    (cond
      (trading-crypto-ready? cached)
      cached

      (some? cached)
      (do
        (reset! resolved-trading-crypto* nil)
        nil)

      :else
      (let [resolved (resolve-exported-trading-crypto)]
        (when (trading-crypto-ready? resolved)
          (reset! resolved-trading-crypto* resolved)
          resolved)))))

(defn load-trading-crypto-module!
  []
  (if-let [existing (resolved-trading-crypto)]
    (js/Promise.resolve existing)
    (if-let [existing-load @inflight-trading-crypto-load*]
      existing-load
      (let [resolve-loaded-trading-crypto!
            (fn []
              (let [resolved (resolve-exported-trading-crypto)]
                (when-not (trading-crypto-ready? resolved)
                  (throw (js/Error.
                          "Loaded trading crypto module without exported helpers.")))
                (reset! resolved-trading-crypto* resolved)
                resolved))]
        (try
          (let [load-promise
                (-> (if (loader/loaded? trading-crypto-module-name)
                      (js/Promise.resolve nil)
                      (loader/load trading-crypto-module-name))
                    (.then (fn [_]
                             (resolve-loaded-trading-crypto!)))
                    (.finally (fn []
                                (reset! inflight-trading-crypto-load* nil))))]
            (reset! inflight-trading-crypto-load* load-promise)
            load-promise)
          (catch :default err
            (js/Promise.reject err)))))))
