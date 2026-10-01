(ns hyperopen.funding.application.modal-vm.transfer
  "The Transfer view-model step: From/To places, the asset list, amounts,
   before/after balances, summary rows, the blocked card and the HyperEVM
   run. It runs after the amounts step and before presentation, and leaves
   `:transfer-vm` (everything but the submit actions, which presentation
   decides) plus a few keys presentation reads.

   The Perps <-> Spot route keeps the legacy USDC amounts and submit label, so
   its form and request are unchanged.

   The view-model runs on every render in every mode, so outside Transfer
   mode this step only fills the contract's shape (`idle-transfer-vm`) and
   computes no options, balances or prices."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.application.modal-vm.transfer-details :as details]
            [hyperopen.funding.domain.amounts :as amounts]
            [hyperopen.funding.domain.evm-transfer-amounts :as evm-amounts]
            [hyperopen.funding.domain.transfer-balances :as transfer-balances]
            [hyperopen.funding.domain.transfer-route :as transfer-route]
            [hyperopen.funding.domain.transfer-run :as transfer-run]
            [hyperopen.hyperevm.domain.chain :as chain]
            [hyperopen.hyperevm.domain.units :as units]
            [hyperopen.utils.formatting :as fmt]))

(def ^:private percent-steps [25 50 75])

(def ^:private legacy-symbol "USDC")

(def ^:private legacy-amount-decimals 6)

(defn- short-address
  [address]
  (when (and (string? address) (> (count address) 10))
    (str (subs address 0 5) "…" (subs address (- (count address) 4)))))

(defn- data-role
  [side location]
  (str "funding-transfer-" (name side) "-" (name location)))

(defn- location-options-model
  [state route]
  (let [options (transfer-route/location-options state route)]
    (into {}
          (map (fn [[side side-options]]
                 [side (mapv (fn [{:keys [id] :as option}]
                               (assoc option
                                      :action [:actions/set-funding-transfer-location side id]
                                      :data-role (data-role side id)))
                             side-options)]))
          options)))

(defn- token-for
  [{:keys [hyperevm-linked-tokens]} state modal]
  (when-let [index (transfer-route/normalize-asset-index (:transfer-asset modal))]
    (some #(when (= index (:index %)) %) (hyperevm-linked-tokens state))))

(defn- verification-notice
  [token]
  (when (and token (not (:verified? token)) (:erc20-address token))
    (str (:name token) " is linked to contract " (short-address (:erc20-address token))
         " on HyperEVM. Hyperliquid doesn't check linked contracts, so verify it"
         " before moving large amounts.")))

(defn- asset-model
  "The asset list. Its empty copy (\"No linked tokens on HyperEVM yet.\") is
   shown only once the source's balances were read: while they load, or
   after a failed first read, the blocked line says so instead."
  [state route token owner]
  (let [visible? (boolean (and (:from route) (transfer-route/evm-route? route)))
        options (if visible? (transfer-route/asset-options state route) [])
        source-known? (and visible?
                           (seq (transfer-route/linked-tokens state))
                           (transfer-balances/location-known? state (:from route) owner))]
    {:visible? visible?
     :options (mapv (fn [{:keys [index symbol available disabled? reason]}]
                      {:index index
                       :symbol symbol
                       :balance-display (or (transfer-balances/display-amount available) "--")
                       :selected? (= index (:index token))
                       :disabled? (boolean disabled?)
                       :reason reason
                       :action [:actions/select-funding-transfer-asset index]})
                    options)
     :selected (when (and visible? token)
                 {:symbol (:name token)
                  :notice (verification-notice token)
                  :explorer-url (some-> (:erc20-address token) chain/explorer-token-url)})
     :empty-message (when (and source-known? (empty? options))
                      (transfer-route/empty-assets-message route))}))

(defn- typed-amount
  "The amount the move would carry: the preview's floored amount when it is
   valid, else what was typed floored to `precision`, else nil."
  [modal preview-result precision]
  (or (get-in preview-result [:request :evm :amount])
      (when (number? precision)
        (let [input (amounts/normalize-amount-input (:amount-input modal))
              floored (units/floor-to-decimals input precision)]
          (when (transfer-balances/positive-text? floored) floored)))))

(defn- side-model
  ([label before after delta symbol]
   (side-model label before after delta symbol before))
  ([label before after delta symbol available]
   (let [show (fn [text] (some-> (transfer-balances/display-amount text) (str " " symbol)))]
     {:label label
      :before (show before)
      :after (show after)
      :delta delta
      :available (show available)})))

(defn- balances-model
  "Before/after balances of both places. The Perps <-> Spot route shows the
   legacy USDC max of each place, but only once that place's balances were
   read: before that the legacy max reads 0, which would show a false
   \"0.00 USDC\"."
  [{:keys [transfer-max-amount]} state modal route token owner amount symbol]
  (let [legacy? (not (transfer-route/evm-route? route))
        available (fn [location]
                    (if legacy?
                      (when (transfer-balances/location-known? state location owner)
                        (transfer-balances/number->text
                         (transfer-max-amount state (assoc modal
                                                           :to-perp? (= :spot location)
                                                           :transfer-from nil
                                                           :transfer-to nil))))
                      (when token
                        (transfer-balances/location-available-text state location token owner))))
        from-before (when (:from route) (available (:from route)))
        to-before (when (:to route) (available (:to route)))
        ;; "Available" means what MAX and the validation allow: native HYPE
        ;; leaving HyperEVM keeps its gas reserve, as the Balances table says.
        from-available (if (and (not legacy?) token (:from route))
                         (evm-amounts/source-available state route token)
                         from-before)
        shown (some-> amount transfer-balances/display-amount)]
    {:from (side-model (get transfer-route/location-labels (:from route) "")
                       from-before
                       (when amount (transfer-balances/sub-text from-before amount))
                       (when shown (str "−" shown))
                       symbol
                       from-available)
     :to (side-model (get transfer-route/location-labels (:to route) "")
                     to-before
                     (when amount (transfer-balances/add-text to-before amount))
                     (when shown (str "+" shown))
                     symbol)}))

(defn- destination-model
  [state modal route owner]
  (let [address (if (transfer-route/evm-route? route)
                  owner
                  (or (amounts/normalize-evm-address (:transfer-destination-address modal))
                      owner))
        subaccount? (and address owner (not= address owner))]
    {:display (str (if subaccount? "Subaccount" "Your wallet")
                   (when-let [short (short-address address)] (str " " short)))}))

(defn- usd-estimate
  [{:keys [token-price-usd]} state symbol amount]
  (let [price (when amount (token-price-usd state symbol))
        value (when (number? price) (* price (js/parseFloat amount)))]
    (when (and (number? value) (js/isFinite value))
      (str "≈ " (fmt/format-currency value)))))

(defn- amount-model
  [ctx route token max-amount preview-result]
  (if (transfer-route/evm-route? route)
    {:value (:amount-input ctx)
     :max-display (or (transfer-balances/display-amount max-amount) "--")
     :max-input (if (string? max-amount) max-amount "")
     :symbol (or (:name token) "")
     :notice (or (:notice preview-result)
                 (when (and (= :native (:kind token)) (= :hyperevm (:from route)))
                   (str "MAX keeps "
                        (evm-amounts/native-gas-reserve (:state ctx) (get-in ctx [:transfer-owner]))
                        " HYPE on HyperEVM for gas.")))}
    {:value (:amount-input ctx)
     :max-display (:transfer-max-display ctx)
     :max-input (:transfer-max-input ctx)
     :symbol legacy-symbol
     :notice nil}))

(defn- submit-label
  [{:keys [wallet-chain-id]} state route token amount blocked evm submitting?]
  (let [move (fn [prefix destination]
               (str/join " " (remove str/blank? [prefix (transfer-balances/grouped-amount amount)
                                                 (:name token) "to" destination])))]
    (cond
      (= :running (:phase evm))
      (let [active (get (:steps evm) (dec (:step-index evm)))
            confirming? (and (= :active (:status active))
                             (= transfer-run/confirming-detail (:detail active)))]
        (str (if confirming? "Confirming on HyperEVM" "Waiting for wallet")
             ;; A one-step move (HyperCore -> HyperEVM) has no steps to count.
             (when (> (:step-count evm) 1)
               (str " (step " (max 1 (:step-index evm)) " of " (:step-count evm) ")"))))

      submitting? "Submitting..."
      (not (transfer-route/evm-route? route)) "Transfer"
      (= :no-evm-gas (:code blocked)) "Add gas to continue"

      (= :evm->core (transfer-route/route-kind route))
      (if (= (:chain-id chain/mainnet) (wallet-chain-id state))
        (move "Move" "Spot")
        (move "Switch network & move" "Spot"))

      :else (move "Move" (get transfer-route/location-labels (:to route) "HyperEVM")))))

(defn- with-evm-max-overrides
  "Routes touching HyperEVM show their own MAX and symbol, not the USDC
   formatting the amounts step applied."
  [ctx amount]
  (merge ctx {:max-display (:max-display amount)
              :max-input (:max-input amount)
              :max-symbol (:symbol amount)
              :transfer-max-display (:max-display amount)
              :transfer-max-input (:max-input amount)}))

(defn- idle-transfer-vm
  "The `:transfer` submodel outside Transfer mode, where nothing renders it:
   the contract's shape with nothing computed."
  [{:keys [modal amount-input transfer-max-display transfer-max-input]}]
  (let [route (transfer-route/transfer-route modal)
        empty-side (side-model "" nil nil nil "")]
    {:to-perp? (true? (:to-perp? modal))
     :route (assoc (select-keys route [:from :to :valid?])
                   :kind (transfer-route/route-kind route))
     :from-options []
     :to-options []
     :swap-action [:actions/swap-funding-transfer-locations]
     :asset {:visible? false :options [] :selected nil :empty-message nil}
     :balances {:from empty-side :to empty-side}
     :destination {:display ""}
     :summary []
     :usd-estimate nil
     :percent-actions []
     :blocked nil
     :evm nil
     :amount {:value (or amount-input "")
              :max-display (or transfer-max-display "")
              :max-input (or transfer-max-input "")
              :symbol legacy-symbol
              :notice nil}}))

(defn- transfer-context
  [{:keys [state modal preview-result submitting?] :as ctx}
   {:keys [transfer-max-amount] :as deps}]
  (let [route (transfer-route/transfer-route modal)
        evm-route? (transfer-route/evm-route? route)
        owner (account-context/owner-address state)
        token (when evm-route? (token-for deps state modal))
        precision (if evm-route? (:core-precision token) legacy-amount-decimals)
        amount (typed-amount modal preview-result precision)
        symbol (if evm-route? (or (:name token) "") legacy-symbol)
        max-amount (when evm-route? (transfer-max-amount state modal))
        blocked (details/blocked-model deps state modal (:blocked preview-result))
        evm (when evm-route?
              (details/with-retry-action (details/evm-model modal route)
                                         blocked
                                         (:ok? preview-result)))
        activation-fee? (and (= :evm->core (transfer-route/route-kind route))
                             (= :usdc-cdw (:kind token))
                             (= :missing (get-in state [:hyperevm :core-account owner])))
        ctx* (assoc ctx :transfer-owner owner)
        amount-vm (amount-model ctx* route token max-amount preview-result)
        {:keys [from to]} (location-options-model state route)]
    (cond-> (assoc ctx*
                   :transfer-route route
                   :transfer-blocked blocked
                   :transfer-evm evm
                   :transfer-content-kind (details/content-kind evm)
                   :transfer-submit-label (submit-label deps state route token amount
                                                        blocked evm submitting?)
                   :transfer-vm
                   {:to-perp? (true? (:to-perp? modal))
                    :route (assoc (select-keys route [:from :to :valid?])
                                  :kind (transfer-route/route-kind route))
                    :from-options from
                    :to-options to
                    :swap-action [:actions/swap-funding-transfer-locations]
                    :asset (asset-model state route token owner)
                    :balances (balances-model deps state modal route token owner amount symbol)
                    :destination (destination-model state modal route owner)
                    :summary (details/summary-rows deps state route token owner activation-fee?)
                    :usd-estimate (usd-estimate deps state symbol amount)
                    :percent-actions (mapv (fn [pct]
                                             {:label (str pct "%")
                                              :action [:actions/set-funding-transfer-amount-percent pct]})
                                           percent-steps)
                    :blocked blocked
                    :evm evm
                    :amount amount-vm})
      evm-route? (with-evm-max-overrides amount-vm))))

(defn with-transfer-context
  "The Transfer step. Only Transfer mode computes the full submodel; every
   other mode gets `idle-transfer-vm`."
  [{:keys [mode] :as ctx} deps]
  (if (= :transfer mode)
    (transfer-context ctx deps)
    (assoc ctx :transfer-vm (idle-transfer-vm ctx))))
