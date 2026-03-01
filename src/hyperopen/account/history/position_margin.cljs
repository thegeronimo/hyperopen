(ns hyperopen.account.history.position-margin
  (:require [clojure.string :as str]
            [hyperopen.account.history.position-identity :as position-identity]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.domain.trading :as trading-domain]))

(def ^:private anchor-keys
  [:left :right :top :bottom :width :height :viewport-width :viewport-height])

(def ^:private ntli-scale
  1000000)

(def ^:private min-prefill-margin-amount
  0.000001)

(defn- parse-num
  [value]
  (trading-domain/parse-num value))

(defn- clamp
  [value min-value max-value]
  (-> value
      (max min-value)
      (min max-value)))

(defn- normalize-display-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- normalize-mode
  [value]
  (let [candidate (cond
                    (keyword? value) value
                    (string? value) (keyword (str/lower-case (str/trim value)))
                    :else nil)]
    (if (= candidate :remove)
      :remove
      :add)))

(defn- normalize-prefill-source
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (keyword (str/lower-case (str/trim value)))
                :else nil)]
    (when (= token :chart-liquidation-drag)
      :chart-liquidation-drag)))

(defn- normalize-anchor
  [anchor]
  (when (map? anchor)
    (let [normalized (reduce (fn [acc k]
                               (if-let [num (parse-num (get anchor k))]
                                 (assoc acc k num)
                                 acc))
                             {}
                             anchor-keys)]
      (when (seq normalized)
        normalized))))

(defn- amount->text
  [value]
  (if (and (number? value)
           (not (js/isNaN value))
           (js/isFinite value))
    (trading-domain/number->clean-string (max 0 value) 6)
    "0"))

(defn- percent->text
  [value]
  (if (and (number? value)
           (not (js/isNaN value))
           (js/isFinite value))
    (trading-domain/number->clean-string (clamp value 0 100) 2)
    "0"))

(defn- parse-input-number
  [value]
  (let [text (str/trim (str (or value "")))]
    (when (seq text)
      (parse-num text))))

(defn- normalize-non-negative-input
  [value fallback]
  (let [parsed (parse-input-number value)]
    (if (and (number? parsed)
             (not (js/isNaN parsed))
             (js/isFinite parsed)
             (>= parsed 0))
      parsed
      fallback)))

(defn- position-side
  [szi]
  (let [size-num (parse-num szi)]
    (cond
      (and (number? size-num) (neg? size-num)) :short
      (and (number? size-num) (pos? size-num)) :long
      :else :flat)))

(defn- absolute-position-size
  [szi]
  (let [size-num (parse-num szi)]
    (if (and (number? size-num)
             (js/isFinite size-num))
      (js/Math.abs size-num)
      0)))

(defn- extract-leverage
  [position]
  (or (parse-num (get-in position [:leverage :value]))
      (parse-num (:leverage position))
      0))

(defn- removable-margin
  [position margin-used]
  (let [position-value (parse-num (:positionValue position))
        leverage (extract-leverage position)
        min-required (cond
                       (and (number? position-value)
                            (pos? position-value)
                            (number? leverage)
                            (pos? leverage))
                       (max (* 0.1 position-value)
                            (/ position-value leverage))

                       (and (number? position-value)
                            (pos? position-value))
                       (* 0.1 position-value)

                       :else
                       0)]
    (max 0 (- (max 0 margin-used) min-required))))

(defn- clearinghouse-state-for-dex
  [state dex]
  (let [dex* (normalize-display-text dex)]
    (if (seq dex*)
      (get-in state [:perp-dex-clearinghouse dex*])
      (get-in state [:webdata2 :clearinghouseState]))))

(defn- unified-account-mode?
  [state]
  (= :unified (get-in state [:account :mode])))

(defn- usdc-coin?
  [coin]
  (and (string? coin)
       (str/starts-with? coin "USDC")))

(defn- unified-spot-usdc-available
  [state]
  (when (unified-account-mode? state)
    (some (fn [balance]
            (when (usdc-coin? (:coin balance))
              (let [available-direct (some parse-num
                                           [(:available balance)
                                            (:availableBalance balance)
                                            (:available-balance balance)])
                    total-num (parse-num (:total balance))
                    hold-num (or (parse-num (:hold balance)) 0)
                    available-derived (when (finite-number? total-num)
                                        (- total-num hold-num))
                    available (if (finite-number? available-direct)
                                available-direct
                                available-derived)]
                (when (finite-number? available)
                  (max 0 available)))))
          (get-in state [:spot :clearinghouse-state :balances]))))

(defn- summary-candidates
  [clearinghouse-state]
  (remove nil?
          [(:marginSummary clearinghouse-state)
           (:crossMarginSummary clearinghouse-state)
           (get-in clearinghouse-state [:clearinghouseState :marginSummary])
           (get-in clearinghouse-state [:clearinghouseState :crossMarginSummary])]))

(defn- summary-derived-withdrawable
  [summary]
  (let [account-value (parse-num (:accountValue summary))
        margin-used (parse-num (:totalMarginUsed summary))]
    (when (and (finite-number? account-value)
               (finite-number? margin-used))
      (max 0 (- account-value margin-used)))))

(defn- clearinghouse-withdrawable
  [clearinghouse-state]
  (let [root-candidates (some parse-num
                              [(:withdrawable clearinghouse-state)
                               (:withdrawableUsd clearinghouse-state)
                               (:withdrawableUSDC clearinghouse-state)
                               (:availableToWithdraw clearinghouse-state)
                               (:availableToWithdrawUsd clearinghouse-state)
                               (:availableToWithdrawUSDC clearinghouse-state)
                               (get-in clearinghouse-state [:clearinghouseState :withdrawable])
                               (get-in clearinghouse-state [:clearinghouseState :withdrawableUsd])
                               (get-in clearinghouse-state [:clearinghouseState :withdrawableUSDC])
                               (get-in clearinghouse-state [:clearinghouseState :availableToWithdraw])
                               (get-in clearinghouse-state [:clearinghouseState :availableToWithdrawUsd])
                               (get-in clearinghouse-state [:clearinghouseState :availableToWithdrawUSDC])])]
    (cond
      (finite-number? root-candidates)
      (max 0 root-candidates)

      :else
      (some summary-derived-withdrawable (summary-candidates clearinghouse-state)))))

(defn- clearinghouse-state-candidates
  [state dex]
  (let [primary (clearinghouse-state-for-dex state dex)
        default-state (get-in state [:webdata2 :clearinghouseState])]
    (->> [primary default-state]
         (filter map?)
         distinct
         vec)))

(defn- available-to-add
  [state position-data]
  (let [position (or (:position position-data) {})
        dex (or (:dex position-data)
                (:dex position))
        unified-available (unified-spot-usdc-available state)
        clearinghouse-available (some clearinghouse-withdrawable
                                   (clearinghouse-state-candidates state dex))]
    (max 0 (or unified-available
               clearinghouse-available
               0))))

(defn default-modal-state
  []
  {:open? false
   :submitting? false
   :position-key nil
   :anchor nil
   :coin nil
   :dex nil
   :position-side :flat
   :position-size 0
   :position-value 0
   :margin-used 0
   :available-to-add 0
   :max-removable 0
   :mode :add
   :amount-input "0"
  :amount-percent-input "0"
   :prefill-source nil
   :prefill-liquidation-target-price nil
   :prefill-liquidation-current-price nil
   :error nil})

(defn open?
  [modal]
  (boolean (:open? modal)))

(defn mode
  [modal]
  (normalize-mode (:mode modal)))

(defn- max-amount
  [modal]
  (let [max-value (if (= :remove (mode modal))
                    (:max-removable modal)
                    (:available-to-add modal))]
    (if (and (number? max-value)
             (js/isFinite max-value))
      (max 0 max-value)
      0)))

(defn- active-amount
  [modal]
  (let [parsed (normalize-non-negative-input (:amount-input modal) 0)]
    (if (and (number? parsed)
             (js/isFinite parsed))
      parsed
      0)))

(defn- sync-percent-from-amount
  [modal amount]
  (let [max-value (max-amount modal)
        percent (if (pos? max-value)
                  (* 100 (/ amount max-value))
                  0)]
    (assoc modal
           :amount-input (amount->text amount)
           :amount-percent-input (percent->text percent)
           :error nil)))

(defn- sync-amount-from-percent
  [modal percent]
  (let [max-value (max-amount modal)
        amount (* max-value (/ percent 100))]
    (assoc modal
           :amount-input (amount->text amount)
           :amount-percent-input (percent->text percent)
           :error nil)))

(defn set-modal-field
  [modal path value]
  (let [path* (if (vector? path) path [path])]
    (cond
      (= path* [:amount-input])
      (let [amount (normalize-non-negative-input value (active-amount modal))]
        (sync-percent-from-amount modal amount))

      (= path* [:amount-percent-input])
      (let [percent (clamp (normalize-non-negative-input value 0) 0 100)]
        (sync-amount-from-percent modal percent))

      (= path* [:mode])
      (let [next-modal (assoc modal :mode (normalize-mode value) :error nil)]
        (sync-percent-from-amount next-modal (active-amount next-modal)))

      :else
      modal)))

(defn set-amount-percent
  [modal percent]
  (let [percent* (clamp (normalize-non-negative-input percent 0) 0 100)]
    (sync-amount-from-percent modal percent*)))

(defn set-amount-to-max
  [modal]
  (let [max-value (max-amount modal)]
    (assoc modal
           :amount-input (amount->text max-value)
           :amount-percent-input (if (pos? max-value) "100" "0")
           :error nil)))

(defn validate-modal
  [modal]
  (let [active? (open? modal)
        current-mode (mode modal)
        amount (active-amount modal)
        max-value (max-amount modal)
        side (:position-side modal)]
    (cond
      (not active?)
      {:is-ok false
       :display-message "Select an amount"}

      (not (contains? #{:long :short} side))
      {:is-ok false
       :display-message "Position side unavailable."}

      (<= max-value 0)
      {:is-ok false
       :display-message (if (= current-mode :remove)
                          "No removable margin available."
                          "No available margin to add.")}

      (<= amount 0)
      {:is-ok false
       :display-message "Select an amount"}

      (> amount max-value)
      {:is-ok false
       :display-message (if (= current-mode :remove)
                          "Amount exceeds removable margin."
                          "Amount exceeds available margin.")}

      :else
      {:is-ok true
       :display-message (if (= current-mode :remove)
                          "Remove"
                          "Add")})))

(defn- candidate-market?
  [market coin dex]
  (let [coin* (normalize-display-text coin)
        dex* (normalize-display-text dex)
        market-coin* (normalize-display-text (:coin market))
        market-dex* (normalize-display-text (:dex market))]
    (and (= :perp (:market-type market))
         (= coin* market-coin*)
         (= (or dex* "")
            (or market-dex* "")))))

(defn- resolve-market-by-coin-and-dex
  [market-by-key coin dex]
  (let [markets* (vals (or market-by-key {}))
        exact (some #(when (candidate-market? % coin dex) %) markets*)
        fallback (markets/resolve-market-by-coin market-by-key coin)]
    (or exact fallback)))

(defn- parse-int-value
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(defn- resolve-market-asset-id
  [market]
  (or (some parse-int-value
            [(:asset-id market)
             (:assetId market)])
      (let [idx (parse-int-value (:idx market))
            dex (normalize-display-text (:dex market))]
        (when (and (number? idx)
                   (or (nil? dex) (= "" dex)))
          idx))))

(defn- amount->ntli
  [amount mode*]
  (let [magnitude (js/Math.round (* (max 0 amount) ntli-scale))
        signed (if (= mode* :remove)
                 (- magnitude)
                 magnitude)]
    (when (not (zero? signed))
      signed)))

(defn prepare-submit
  [state modal]
  (let [validation (validate-modal modal)
        market-by-key (get-in state [:asset-selector :market-by-key] {})
        market (resolve-market-by-coin-and-dex market-by-key
                                               (:coin modal)
                                               (:dex modal))
        asset-id (resolve-market-asset-id market)
        mode* (mode modal)
        amount (active-amount modal)
        ntli (amount->ntli amount mode*)
        is-buy (= :long (:position-side modal))]
    (cond
      (not (:is-ok validation))
      {:ok? false
       :display-message (:display-message validation)}

      (not (number? asset-id))
      {:ok? false
       :display-message "Select an asset and ensure market data is loaded."}

      (nil? ntli)
      {:ok? false
       :display-message "Select an amount"}

      :else
      {:ok? true
       :display-message (:display-message validation)
       :request {:action {:type "updateIsolatedMargin"
                          :asset asset-id
                          :isBuy is-buy
                          :ntli ntli}}})))

(defn- sanitize-prefill-liquidation-price
  [value]
  (let [num (parse-num value)]
    (when (and (finite-number? num)
               (pos? num))
      num)))

(defn- apply-prefill
  [modal position-data]
  (if-not (map? position-data)
    modal
    (let [mode* (normalize-mode (:prefill-margin-mode position-data))
          amount (parse-num (:prefill-margin-amount position-data))
          source (normalize-prefill-source (:prefill-source position-data))
          modal* (assoc modal
                        :prefill-source source
                        :prefill-liquidation-target-price (sanitize-prefill-liquidation-price
                                                           (:prefill-liquidation-target-price position-data))
                        :prefill-liquidation-current-price (sanitize-prefill-liquidation-price
                                                            (:prefill-liquidation-current-price position-data)))]
      (if (and (finite-number? amount)
               (>= amount min-prefill-margin-amount))
        (let [modal-with-mode (set-modal-field modal* [:mode] mode*)
              max-value (max-amount modal-with-mode)
              clamped-amount (clamp amount 0 max-value)]
          (set-modal-field modal-with-mode [:amount-input] (amount->text clamped-amount)))
        modal*))))

(defn from-position-row
  ([state position-data]
   (from-position-row state position-data nil))
  ([state position-data anchor]
   (let [position (or (:position position-data) {})
         side (position-side (:szi position))
         margin-used (max 0 (or (parse-num (:marginUsed position)) 0))
         available (available-to-add state position-data)
         removable (removable-margin position margin-used)]
     (-> (assoc (default-modal-state)
                :open? true
                :position-key (position-identity/position-unique-key position-data)
                :anchor (normalize-anchor anchor)
                :coin (:coin position)
                :dex (normalize-display-text (:dex position-data))
                :position-side side
                :position-size (absolute-position-size (:szi position))
                :position-value (max 0 (or (parse-num (:positionValue position)) 0))
                :margin-used margin-used
                :available-to-add available
                :max-removable removable
                :mode :add
                :amount-input "0"
                :amount-percent-input "0"
                :submitting? false
                :error nil)
         (apply-prefill position-data)))))
