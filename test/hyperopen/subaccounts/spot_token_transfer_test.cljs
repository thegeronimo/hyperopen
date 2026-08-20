(ns hyperopen.subaccounts.spot-token-transfer-test
  "Regressions for sending a non-USDC spot token between a master account and a
   sub-account.

   Two defects are covered. The first is that a unified (portfolio-margin)
   master was funnelled into a USDC-only path and could not send any other spot
   token at all. The second is that the token identifier reaching the exchange
   was derived from a `spotClearinghouseState` balance row's own `:token`
   field, which is a numeric token index rather than the `NAME:0x<hash>` wire
   token id Hyperliquid requires."
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.subaccounts.actions :as actions]
            [hyperopen.subaccounts.effects :as effects]))

(def ^:private owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private subaccount-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(def ^:private hype-wire-token
  "HYPE:0x0d01dc56dcaaca66ad901c959b4011ec")

(def ^:private usdc-wire-token
  "USDC:0x6d1e7cde53ba9467b783cb7c530ce054")

(def ^:private production-spot-meta
  "Production-shaped `spotMeta` `:tokens`. Hyperliquid returns `:name`,
   `:index`, and `:tokenId` separately and never a prejoined `NAME:0x...`
   string."
  {:tokens [{:name "USDC" :index 0 :tokenId "0x6d1e7cde53ba9467b783cb7c530ce054"
             :szDecimals 8 :weiDecimals 8}
            {:name "HYPE" :index 150 :tokenId "0x0d01dc56dcaaca66ad901c959b4011ec"
             :szDecimals 2 :weiDecimals 8}]})

(defn- store-with
  [{:keys [unified? spot-meta]}]
  (atom (cond-> {:router {:path "/subAccounts"}
                 :wallet {:address owner-address}
                 :spot {:meta spot-meta}
                 :account-context
                 {:subaccounts {:rows [{:name "Desk"
                                        :master owner-address
                                        :sub-account-user subaccount-address}]
                                :selected-address subaccount-address
                                :transferring-address subaccount-address}}}
          unified? (assoc :account {:mode :unified}))))

(defn- hype-request
  [is-deposit?]
  {:sub-account-user subaccount-address
   :is-deposit is-deposit?
   :account-kind :spot
   :token hype-wire-token
   :token-symbol "HYPE"
   :amount "1.5"
   :amount-display "1.5"
   :amount-units "150000000"
   :amount-decimals 8})

(defn- deps
  [store request captures]
  {:store store
   :request request
   :transfer-sub-account! (fn [& args]
                            (swap! (:l1 captures) conj args)
                            (js/Promise.resolve {:status "err" :response "unexpected L1 transfer"}))
   :transfer-sub-account-spot! (fn [& args]
                                 (swap! (:spot captures) conj args)
                                 (js/Promise.resolve {:status "ok" :response {:type "default"}}))
   :submit-send-asset! (fn [store* owner action]
                         (swap! (:send-asset captures) conj [store* owner action])
                         (js/Promise.resolve {:status "ok" :response {:type "default"}}))
   :load-subaccounts! (fn [_opts] (js/Promise.resolve :reloaded))
   :dispatch! (fn [_store _ctx _effects] nil)
   :runtime-error-message (fn [err] (str err))})

(defn- captures
  []
  {:l1 (atom []) :spot (atom []) :send-asset (atom [])})

(deftest unified-master-deposits-a-non-usdc-spot-token-through-send-asset-test
  (async done
    (let [store (store-with {:unified? true :spot-meta production-spot-meta})
          caps (captures)]
      (-> (effects/transfer-subaccount! (deps store (hype-request true) caps))
          (.then (fn [_result]
                   (testing "unified masters never touch the classic primitives"
                     (is (= [] @(:l1 caps)))
                     (is (= [] @(:spot caps))))
                   (is (= [[store
                            owner-address
                            {:type "sendAsset"
                             :destination subaccount-address
                             :sourceDex "spot"
                             :destinationDex "spot"
                             :token hype-wire-token
                             :amount "1.5"
                             :fromSubAccount ""}]]
                          @(:send-asset caps)))
                   (is (nil? (get-in @store [:account-context :subaccounts :error])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected unified HYPE deposit error: " err))
                    (done)))))))

(deftest unified-master-withdraws-a-non-usdc-spot-token-from-the-subaccount-test
  (async done
    (let [store (store-with {:unified? true :spot-meta production-spot-meta})
          caps (captures)]
      (-> (effects/transfer-subaccount! (deps store (hype-request false) caps))
          (.then (fn [_result]
                   (is (= [[store
                            owner-address
                            {:type "sendAsset"
                             :destination owner-address
                             :sourceDex "spot"
                             :destinationDex "spot"
                             :token hype-wire-token
                             :amount "1.5"
                             :fromSubAccount subaccount-address}]]
                          @(:send-asset caps)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected unified HYPE withdraw error: " err))
                    (done)))))))

(deftest unified-master-still-defaults-to-usdc-when-no-token-was-chosen-test
  (async done
    (let [store (store-with {:unified? true :spot-meta nil})
          caps (captures)]
      (-> (effects/transfer-subaccount!
           (deps store
                 {:sub-account-user subaccount-address
                  :is-deposit true
                  :usd 1230000
                  :amount "1.23"}
                 caps))
          (.then (fn [_result]
                   (is (= usdc-wire-token
                          (:token (last (first @(:send-asset caps))))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected unified USDC default error: " err))
                    (done)))))))

(deftest unresolvable-spot-token-fails-instead-of-signing-a-bare-symbol-test
  (async done
    (let [store (store-with {:unified? true :spot-meta nil})
          caps (captures)]
      (-> (effects/transfer-subaccount!
           (deps store (assoc (hype-request true) :token "HYPE") caps))
          (.then (fn [_result]
                   (testing "a bare symbol is rejected by the exchange, so never sign one"
                     (is (= [] @(:send-asset caps)))
                     (is (= [] @(:spot caps)))
                     (is (= [] @(:l1 caps))))
                   (is (= "Spot asset details are unavailable. Refresh balances before transferring."
                          (get-in @store [:account-context :subaccounts :error])))
                   (is (nil? (get-in @store [:account-context :subaccounts :transferring-address])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected unresolved token error: " err))
                    (done)))))))

(deftest classic-spot-transfer-sends-a-wire-token-id-not-a-balance-index-test
  (async done
    (let [store (store-with {:unified? false :spot-meta production-spot-meta})
          caps (captures)]
      (-> (effects/transfer-subaccount!
           (deps store (assoc (hype-request true) :token "150") caps))
          (.then (fn [_result]
                   (testing "a spot balance row's :token is an index; the wire needs NAME:0xhash"
                     (is (= [] @(:send-asset caps)))
                     (is (= 1 (count @(:spot caps))))
                     (let [[_store _owner _address _is-deposit? token amount]
                           (first @(:spot caps))]
                       (is (= hype-wire-token token))
                       (is (= "1.5" amount))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected classic spot transfer error: " err))
                    (done)))))))

;; --- Action layer -----------------------------------------------------------
;;
;; A unified master used to have its selected token silently rewritten to a
;; USDC perps transfer. That rewrite was pinned as expected behavior by
;; `hyperopen.subaccounts.actions-test`, which is what made "no option to send
;; spot HYPE" a bug protected by a green test.

(def ^:private action-state
  {:wallet {:address owner-address}
   :account {:mode :unified}
   :spot {:meta production-spot-meta}
   :account-context
   {:subaccounts {:rows [{:name "Desk"
                          :master owner-address
                          :sub-account-user subaccount-address}]
                  :transfer-amount "1.5"
                  :transfer-direction :deposit
                  :transfer-account :spot
                  :transfer-token hype-wire-token}}})

(defn- transfer-request
  [state]
  (second (last (actions/submit-transfer-subaccount state subaccount-address))))

(deftest unified-master-keeps-the-selected-spot-token-test
  (is (= {:sub-account-user subaccount-address
          :is-deposit true
          :amount "1.5"
          :amount-display "1.5"
          :amount-units "150000000"
          :amount-decimals 8
          :account-kind :spot
          :token hype-wire-token
          :token-symbol "HYPE"}
         (transfer-request action-state)))
  (testing "amount precision comes from the token's own weiDecimals, not USDC's six"
    (is (= 8 (:amount-decimals (transfer-request action-state))))))

(deftest unified-master-defaulting-to-usdc-still-resolves-to-the-spot-path-test
  ;; Precision is metadata-driven on the spot path, so USDC uses its own
  ;; weiDecimals (8) rather than the six-decimal constant the perps path hard
  ;; codes. This matches what the classic spot path already did for USDC.
  (is (= {:sub-account-user subaccount-address
          :is-deposit true
          :amount "1.5"
          :amount-display "1.5"
          :amount-units "150000000"
          :amount-decimals 8
          :account-kind :spot
          :token "USDC"
          :token-symbol "USDC"}
         (transfer-request
          (-> action-state
              (assoc-in [:account-context :subaccounts :transfer-account] :trading)
              (assoc-in [:account-context :subaccounts :transfer-token] "USDC"))))))

(deftest classic-master-perps-transfer-stays-usdc-only-test
  (testing "perps collateral genuinely is USDC, so the classic trading path is unchanged"
    (is (= {:sub-account-user subaccount-address
            :is-deposit true
            :usd 1500000
            :amount "1.5"
            :amount-display "1.5"
            :amount-units "1500000"
            :amount-decimals 6
            :account-kind :trading
            :token "USDC"}
           (transfer-request
            (-> action-state
                (dissoc :account)
                (assoc-in [:account-context :subaccounts :transfer-account] :trading)
                (assoc-in [:account-context :subaccounts :transfer-token] "USDC")))))))
