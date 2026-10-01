(ns hyperopen.funding.application.submit-effects
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.application.hyperevm-run-state :as hyperevm-run-state]
            [hyperopen.funding.application.hyperevm-transfer-effects :as hyperevm-transfer-effects]
            [hyperopen.funding.domain.transfer-invariants :as transfer-invariants]))

(defn- spectate-mode-submit-error
  [store]
  (account-context/mutations-blocked-message @store))

(defn api-submit-funding-send!
  [{:keys [store
           request
           dispatch!
           submit-send-asset!
           exchange-response-error
           runtime-error-message
           show-toast!
           default-funding-modal-state
           set-funding-submit-error!
           close-funding-modal!
           refresh-after-funding-submit!]}]
  (let [spectate-mode-message (spectate-mode-submit-error store)
        address (get-in @store [:wallet :address])
        action (:action request)
        ;; Send takes any destination, including a HyperEVM system address.
        ;; The wrong token there (PURR to HYPE's 0x2222…) is lost for good.
        invariant-error (transfer-invariants/check-request @store request)]
    (cond
      (seq spectate-mode-message)
      (set-funding-submit-error! store show-toast! spectate-mode-message)

      (nil? address)
      (set-funding-submit-error! store
                                 show-toast!
                                 "Connect your wallet before sending tokens.")

      (seq invariant-error)
      (set-funding-submit-error! store show-toast! invariant-error)

      :else
      (-> (submit-send-asset! store address action)
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                     (do
                       (close-funding-modal! store default-funding-modal-state)
                       (show-toast! store :success "Send submitted.")
                       (refresh-after-funding-submit! store dispatch! address)
                       resp)
                     (let [error-text (str/trim (str (exchange-response-error resp)))
                           message (str "Send failed: "
                                        (if (seq error-text) error-text "Unknown exchange error"))]
                       (set-funding-submit-error! store show-toast! message)
                       resp))))
          (.catch (fn [err]
                    (let [error-text (str/trim (str (runtime-error-message err)))
                          message (str "Send failed: "
                                       (if (seq error-text) error-text "Unknown runtime error"))]
                      (set-funding-submit-error! store show-toast! message))))))))

(def ^:private gas-topup-path
  [:funding-ui :modal :transfer-gas-topup])

(defn- claim-gas-topup
  "`state` with the fix the action just marked `{:status :submitting}`
   stamped with `topup-id`, so only this top-up's outcome lands on it. A fix
   that is not freshly submitting (closed modal, another top-up's) is left
   alone."
  [state topup-id]
  (let [topup (get-in state gas-topup-path)]
    (if (and (true? (get-in state [:funding-ui :modal :open?]))
             (= :submitting (:status topup))
             (nil? (:id topup)))
      (assoc-in state (conj gas-topup-path :id) topup-id)
      state)))

(defn- claim-gas-topup!
  "Claim the fix for this top-up; returns its id, or nil when there was
   nothing to claim (its outcome then only toasts)."
  [store topup-id]
  (let [[_ after] (swap-vals! store claim-gas-topup topup-id)]
    (when (= topup-id (get-in after (conj gas-topup-path :id)))
      topup-id)))

(defn- set-gas-topup-status!
  "Record the gas top-up's outcome on the fix only, never on the draft, and
   only while the open modal still shows this top-up (`topup-id`) submitting:
   a closed or reopened modal, even one with a newer top-up running, is left
   alone."
  [store topup-id status]
  (swap! store (fn [state]
                 (let [topup (get-in state gas-topup-path)]
                   (if (and (some? topup-id)
                            (true? (get-in state [:funding-ui :modal :open?]))
                            (= :submitting (:status topup))
                            (= topup-id (:id topup)))
                     (assoc-in state gas-topup-path (assoc status :id topup-id))
                     state)))))

(defn- gas-topup-failed!
  [store topup-id show-toast! message]
  (set-gas-topup-status! store topup-id {:status :failed :error message})
  (show-toast! store :error message))

(defn- submit-gas-topup!
  "The one-click gas fix (`:purpose :gas-topup`): a HYPE Spot -> HyperEVM
   `sendAsset` that leaves the user's HyperEVM -> Core draft untouched. Its
   status lives at `[:funding-ui :modal :transfer-gas-topup]`: `:sent` on
   success (the modal stays open), `{:status :failed :error ..}` on any
   refusal or failure, so the fix is never stuck submitting and the draft's
   `:error` and `:submitting?` are never written. The effect first stamps
   the fix with this top-up's id (`claim-gas-topup!`) and writes only while
   the fix still carries it. A sent top-up starts the HyperEVM fast poll,
   so the gas block clears as soon as the HYPE lands."
  [{:keys [store
           request
           dispatch!
           submit-send-asset!
           exchange-response-error
           runtime-error-message
           show-toast!
           refresh-after-funding-submit!] :as deps}]
  (let [topup-id (claim-gas-topup! store (hyperevm-run-state/next-flow-id deps))
        spectate-mode-message (spectate-mode-submit-error store)
        address (get-in @store [:wallet :address])
        refresh-address (or (account-context/effective-account-address @store) address)
        action (:action request)
        invariant-error (transfer-invariants/check-request @store request)]
    (cond
      (seq spectate-mode-message)
      (gas-topup-failed! store topup-id show-toast! spectate-mode-message)

      (nil? address)
      (gas-topup-failed! store topup-id show-toast! "Connect your wallet before transferring funds.")

      (seq invariant-error)
      (gas-topup-failed! store topup-id show-toast! invariant-error)

      (not= "sendAsset" (:type action))
      (gas-topup-failed! store topup-id show-toast! "The gas top-up must be a Spot to HyperEVM send.")

      :else
      (-> (submit-send-asset! store address action)
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                     (do
                       (set-gas-topup-status! store topup-id {:status :sent})
                       (show-toast! store :success
                                    (str "Sent " (:amount action) " HYPE to HyperEVM for gas."))
                       (refresh-after-funding-submit! store dispatch! refresh-address)
                       (hyperevm-run-state/refresh-hyperevm-after-transfer! deps)
                       resp)
                     (let [error-text (str/trim (str (exchange-response-error resp)))]
                       (gas-topup-failed! store topup-id show-toast!
                                          (str "Gas top-up failed: "
                                               (if (seq error-text) error-text "Unknown exchange error")))
                       resp))))
          (.catch (fn [err]
                    (let [error-text (str/trim (str (runtime-error-message err)))]
                      (gas-topup-failed! store topup-id show-toast!
                                         (str "Gas top-up failed: "
                                              (if (seq error-text)
                                                error-text
                                                "Unknown runtime error"))))))))))

(defn- submit-transfer-request!
  [{:keys [store
           request
           dispatch!
           submit-usd-class-transfer!
           submit-send-asset!
           exchange-response-error
           runtime-error-message
           show-toast!
           default-funding-modal-state
           set-funding-submit-error!
           close-funding-modal!
           refresh-after-funding-submit!]}]
  (let [spectate-mode-message (spectate-mode-submit-error store)
        ;; The connected owner wallet signs, even when the funds source from a
        ;; selected subaccount (Hyperliquid subaccount actions are owner-signed).
        address (get-in @store [:wallet :address])
        ;; Balances that change belong to the active/effective account (which may be
        ;; the selected subaccount), so refresh that rather than only the owner wallet.
        refresh-address (or (account-context/effective-account-address @store) address)
        action (:action request)
        ;; Named-DEX transfers are `sendAsset` and must be signed with the
        ;; sendAsset signer; default spot <-> perps stays `usdClassTransfer`.
        ;; Any other type is refused, never signed as a usdClassTransfer (the
        ;; client-only `hyperEvmToCore` never reaches here, see
        ;; `api-submit-funding-transfer!`).
        submit-transfer! (case (:type action)
                           "sendAsset" submit-send-asset!
                           "usdClassTransfer" submit-usd-class-transfer!
                           nil)
        ;; A request that would send a token to the wrong HyperEVM bridge
        ;; address loses it; refuse before anything is signed.
        invariant-error (transfer-invariants/check-request @store request)]
    (cond
      (seq spectate-mode-message)
      (set-funding-submit-error! store show-toast! spectate-mode-message)

      (nil? address)
      (set-funding-submit-error! store
                                 show-toast!
                                 "Connect your wallet before transferring funds.")

      (seq invariant-error)
      (set-funding-submit-error! store show-toast! invariant-error)

      (nil? submit-transfer!)
      (set-funding-submit-error! store
                                 show-toast!
                                 "This transfer type isn't supported.")

      :else
      (-> (submit-transfer! store address action)
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                     (do
                       (close-funding-modal! store default-funding-modal-state)
                       (show-toast! store :success "Transfer submitted.")
                       (refresh-after-funding-submit! store dispatch! refresh-address)
                       resp)
                     (let [error-text (str/trim (str (exchange-response-error resp)))
                           message (str "Transfer failed: "
                                        (if (seq error-text) error-text "Unknown exchange error"))]
                       (set-funding-submit-error! store show-toast! message)
                       resp))))
          (.catch (fn [err]
                    (let [error-text (str/trim (str (runtime-error-message err)))
                          message (str "Transfer failed: "
                                       (if (seq error-text) error-text "Unknown runtime error"))]
                      (set-funding-submit-error! store show-toast! message))))))))

(defn api-submit-funding-transfer!
  "Submit a Transfer request, by what it moves:

   - the gas top-up (`:purpose :gas-topup`) keeps its own status and leaves
     the draft alone;
   - `hyperEvmToCore` sends HyperEVM transactions through the wallet;
   - a `sendAsset` routed `:core->evm` keeps the modal open on its run;
   - everything else (Perps <-> Spot, named-DEX sends) closes the modal on
     success, exactly as before HyperEVM."
  [{:keys [request] :as deps}]
  (if (= :gas-topup (:purpose request))
    (submit-gas-topup! deps)
    (case (get-in request [:action :type])
      "hyperEvmToCore" (hyperevm-transfer-effects/submit-evm-to-core! deps)
      "sendAsset" (if (= :core->evm (:route request))
                    (hyperevm-transfer-effects/submit-core-to-evm! deps)
                    (submit-transfer-request! deps))
      (submit-transfer-request! deps))))

(defn api-submit-funding-withdraw!
  [{:keys [store
           request
           dispatch!
           submit-withdraw3!
           submit-send-asset!
           submit-hyperunit-send-asset-withdraw-request-fn
           request-hyperunit-operations!
           request-hyperunit-withdrawal-queue!
           set-timeout-fn
           now-ms-fn
           exchange-response-error
           runtime-error-message
           show-toast!
           default-funding-modal-state
           set-funding-submit-error!
           close-funding-modal!
           refresh-after-funding-submit!
           resolve-hyperunit-base-urls
           awaiting-withdraw-lifecycle
           start-hyperunit-withdraw-lifecycle-polling!]}]
  (let [spectate-mode-message (spectate-mode-submit-error store)
        address (get-in @store [:wallet :address])
        action (:action request)
        submit-withdraw! (case (:type action)
                           "withdraw3" submit-withdraw3!
                           "hyperunitSendAssetWithdraw"
                           (fn [store* owner-address action*]
                             (submit-hyperunit-send-asset-withdraw-request-fn
                              store*
                              owner-address
                              action*
                              submit-send-asset!))
                           (fn [_store _address _action]
                             (js/Promise.resolve {:status "err"
                                                  :error "Withdrawal action type is not supported."})))]
    (if (seq spectate-mode-message)
      (set-funding-submit-error! store show-toast! spectate-mode-message)
      (if (nil? address)
      (set-funding-submit-error! store
                                 show-toast!
                                 "Connect your wallet before withdrawing.")
      (-> (submit-withdraw! store address action)
          (.then (fn [resp]
                   (if (= "ok" (:status resp))
                     (if (true? (:keep-modal-open? resp))
                       (let [asset-key (some-> (:asset resp) str str/lower-case keyword)
                             base-urls (resolve-hyperunit-base-urls store)
                             base-url (first base-urls)
                             now-ms (if (fn? now-ms-fn)
                                      (now-ms-fn)
                                      (js/Date.now))]
                         (swap! store (fn [state]
                                        (-> state
                                            (assoc-in [:funding-ui :modal :submitting?] false)
                                            (assoc-in [:funding-ui :modal :error] nil)
                                            (assoc-in [:funding-ui :modal :withdraw-generated-address] (:protocol-address resp))
                                            (assoc-in [:funding-ui :modal :hyperunit-lifecycle]
                                                      (awaiting-withdraw-lifecycle asset-key now-ms)))))
                         (start-hyperunit-withdraw-lifecycle-polling!
                          {:store store
                           :wallet-address address
                           :asset-key asset-key
                           :protocol-address (:protocol-address resp)
                           :destination-address (:destination resp)
                           :base-url base-url
                           :base-urls base-urls
                           :request-hyperunit-operations! request-hyperunit-operations!
                           :request-hyperunit-withdrawal-queue! request-hyperunit-withdrawal-queue!
                           :set-timeout-fn set-timeout-fn
                           :now-ms-fn now-ms-fn
                           :runtime-error-message runtime-error-message
                           :on-terminal-lifecycle! (fn [_lifecycle]
                                                    (refresh-after-funding-submit! store
                                                                                   dispatch!
                                                                                   address))})
                         (show-toast! store :success "Withdrawal submitted.")
                         resp)
                       (do
                         (close-funding-modal! store default-funding-modal-state)
                         (show-toast! store :success "Withdrawal submitted.")
                         (refresh-after-funding-submit! store dispatch! address)
                         resp))
                     (let [error-text (str/trim (str (or (:error resp)
                                                        (exchange-response-error resp))))
                           message (str "Withdrawal failed: "
                                        (if (seq error-text) error-text "Unknown exchange error"))]
                       (set-funding-submit-error! store show-toast! message)
                       resp))))
          (.catch (fn [err]
                    (let [error-text (str/trim (str (runtime-error-message err)))
                          message (str "Withdrawal failed: "
                                       (if (seq error-text) error-text "Unknown runtime error"))]
                      (set-funding-submit-error! store show-toast! message)))))))))

(defn api-submit-funding-deposit!
  [{:keys [store
           request
           dispatch!
           submit-usdc-bridge2-deposit!
           submit-usdt-lifi-deposit!
           submit-usdh-across-deposit!
           submit-hyperunit-address-request!
           request-hyperunit-operations!
           set-timeout-fn
           now-ms-fn
           runtime-error-message
           show-toast!
           default-funding-modal-state
           set-funding-submit-error!
           close-funding-modal!
           refresh-after-funding-submit!
           resolve-hyperunit-base-urls
           awaiting-deposit-lifecycle
           start-hyperunit-deposit-lifecycle-polling!]}]
  (let [spectate-mode-message (spectate-mode-submit-error store)
        address (get-in @store [:wallet :address])
        action (:action request)
        submit-deposit! (case (:type action)
                          "bridge2Deposit" submit-usdc-bridge2-deposit!
                          "lifiUsdtToUsdcBridge2Deposit" submit-usdt-lifi-deposit!
                          "acrossUsdcToUsdhDeposit" submit-usdh-across-deposit!
                          "hyperunitGenerateDepositAddress" submit-hyperunit-address-request!
                          (fn [_store _address _action]
                            (js/Promise.resolve {:status "err"
                                                 :error "Deposit action type is not supported."})))]
    (if (seq spectate-mode-message)
      (set-funding-submit-error! store show-toast! spectate-mode-message)
      (if (nil? address)
      (set-funding-submit-error! store
                                 show-toast!
                                 "Connect your wallet before depositing.")
      (let [submit-result (try
                            (submit-deposit! store address action)
                            (catch :default err
                              (js/Promise.reject err)))
            submit-promise (if (fn? (some-> submit-result .-then))
                             submit-result
                             (js/Promise.resolve submit-result))]
        (-> submit-promise
            (.then (fn [resp]
                     (if (= "ok" (:status resp))
                       (if (true? (:keep-modal-open? resp))
                         (let [asset-key (some-> (:asset resp) str str/lower-case keyword)
                               base-urls (resolve-hyperunit-base-urls store)
                               base-url (first base-urls)
                               now-ms (if (fn? now-ms-fn)
                                        (now-ms-fn)
                                        (js/Date.now))]
                           (swap! store (fn [state]
                                          (-> state
                                              (assoc-in [:funding-ui :modal :submitting?] false)
                                              (assoc-in [:funding-ui :modal :error] nil)
                                              (assoc-in [:funding-ui :modal :deposit-generated-address] (:deposit-address resp))
                                              (assoc-in [:funding-ui :modal :deposit-generated-signatures] (:deposit-signatures resp))
                                              (assoc-in [:funding-ui :modal :deposit-generated-asset-key] asset-key)
                                              (assoc-in [:funding-ui :modal :hyperunit-lifecycle]
                                                        (awaiting-deposit-lifecycle asset-key now-ms)))))
                           (start-hyperunit-deposit-lifecycle-polling!
                            {:store store
                             :wallet-address address
                             :asset-key asset-key
                             :protocol-address (:deposit-address resp)
                             :base-url base-url
                             :base-urls base-urls
                             :request-hyperunit-operations! request-hyperunit-operations!
                             :set-timeout-fn set-timeout-fn
                             :now-ms-fn now-ms-fn
                             :on-terminal-lifecycle! (fn [_lifecycle]
                                                      (refresh-after-funding-submit! store
                                                                                     dispatch!
                                                                                     address))})
                           (show-toast! store
                                        :success
                                        (if (true? (:reused-address? resp))
                                          "Using existing deposit address."
                                          "Deposit address generated."))
                           resp)
                         (let [network (or (:network resp) "Arbitrum")]
                           (close-funding-modal! store default-funding-modal-state)
                           (show-toast! store :success (str "Deposit submitted on " network "."))
                           (refresh-after-funding-submit! store dispatch! address)
                           resp))
                       (let [error-text (str/trim (str (or (:error resp)
                                                          (runtime-error-message resp))))
                             message (str "Deposit failed: "
                                          (if (seq error-text) error-text "Unknown runtime error"))]
                         (set-funding-submit-error! store show-toast! message)
                         resp))))
            (.catch (fn [err]
                      (let [error-text (str/trim (str (runtime-error-message err)))
                            message (str "Deposit failed: "
                                         (if (seq error-text) error-text "Unknown runtime error"))]
                        (set-funding-submit-error! store show-toast! message))))))))))
