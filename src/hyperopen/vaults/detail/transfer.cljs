(ns hyperopen.vaults.detail.transfer
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.vaults.adapters.webdata :as webdata-adapter]
            [hyperopen.vaults.domain.identity :as vault-identity]
            [hyperopen.vaults.domain.transfer-policy :as vault-transfer-policy]))

(def ^:private ms-per-day
  (* 24 60 60 1000))

(defn- optional-number
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      value)

    (string? value)
    (let [trimmed (str/trim value)]
      (when (seq trimmed)
        (let [parsed (js/Number trimmed)]
          (when (js/isFinite parsed)
            parsed))))

    :else nil))

(defn- optional-int
  [value]
  (some-> value optional-number js/Math.round))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- usdc-coin?
  [coin]
  (let [token (some-> coin str str/trim str/upper-case)]
    (and (seq token)
         (str/starts-with? token "USDC"))))

(defn- balance-row-available
  [row]
  (when (map? row)
    (let [available-direct (or (optional-number (:available row))
                               (optional-number (:availableBalance row))
                               (optional-number (:free row)))
          total (or (optional-number (:total row))
                    (optional-number (:totalBalance row)))
          hold (optional-number (:hold row))
          available-derived (cond
                              (number? total)
                              (if (number? hold)
                                (- total hold)
                                total)

                              :else nil)
          available (or available-direct available-derived)]
      (when (number? available)
        (max 0 available)))))

(defn- usdc-available-from-balance-rows
  [rows]
  (some (fn [row]
          (when (usdc-coin? (or (:coin row)
                                (:token row)))
            (balance-row-available row)))
        (if (sequential? rows) rows [])))

(defn- webdata-usdc-available
  [webdata]
  (let [balance-available (some (fn [row]
                                  (when (usdc-coin? (:coin row))
                                    (when-let [available (optional-number (:available row))]
                                      (max 0 available))))
                                (webdata-adapter/balances webdata))
        clearinghouse-state (or (:clearinghouseState webdata)
                                (get-in webdata [:data :clearinghouseState])
                                {})
        withdrawable-direct (some optional-number
                                 [(:withdrawable clearinghouse-state)
                                  (:withdrawableUsd clearinghouse-state)
                                  (:withdrawableUSDC clearinghouse-state)
                                  (:availableToWithdraw clearinghouse-state)
                                  (:availableToWithdrawUsd clearinghouse-state)
                                  (:availableToWithdrawUSDC clearinghouse-state)])
        margin-summary (or (:marginSummary clearinghouse-state)
                           (:crossMarginSummary clearinghouse-state)
                           {})
        account-value (optional-number (:accountValue margin-summary))
        total-margin-used (optional-number (:totalMarginUsed margin-summary))
        withdrawable-derived (when (and (number? account-value)
                                        (number? total-margin-used))
                               (- account-value total-margin-used))
        withdrawable (or withdrawable-direct
                        withdrawable-derived)]
    (or balance-available
        (when (number? withdrawable)
          (max 0 withdrawable)))))

(defn- floor-to-decimals
  [value decimals]
  (let [n (or (optional-number value) 0)
        factor (js/Math.pow 10 decimals)]
    (/ (js/Math.floor (* (max 0 n) factor)) factor)))

(defn- format-usdc-display
  [value]
  (let [n (max 0 (or (optional-number value) 0))]
    (fmt/format-fixed-number n 2)))

(defn- format-usdc-input
  [value]
  (let [fixed (.toFixed (max 0 (or (optional-number value) 0)) 2)]
    (or (some-> fixed
                (str/replace #"(\.\d*?[1-9])0+$" "$1")
                (str/replace #"\.0+$" "")
                non-blank-text)
        "0")))

(defn- default-deposit-lockup-days
  [vault-name]
  (if (= "hyperliquidity provider (hlp)"
         (some-> vault-name str str/trim str/lower-case))
    4
    1))

(defn- follower-lockup-days
  [details]
  (let [entry-ms (optional-int (get-in details [:follower-state :vault-entry-time-ms]))
        lockup-ms (optional-int (get-in details [:follower-state :lockup-until-ms]))]
    (when (and (number? entry-ms)
               (number? lockup-ms)
               (> lockup-ms entry-ms))
      (let [days (js/Math.round (/ (- lockup-ms entry-ms) ms-per-day))]
        (when (pos? days)
          days)))))

(defn- vault-deposit-lockup-days
  [details vault-name]
  (or (follower-lockup-days details)
      (default-deposit-lockup-days vault-name)))

(defn- vault-transfer-deposit-max-usdc
  [state wallet-webdata vault-webdata]
  (let [spot-available (usdc-available-from-balance-rows
                        (get-in state [:spot :clearinghouse-state :balances]))
        wallet-webdata-available (webdata-usdc-available wallet-webdata)
        vault-webdata-available (webdata-usdc-available vault-webdata)
        available (or spot-available
                      wallet-webdata-available
                      vault-webdata-available
                      0)]
    (floor-to-decimals available 2)))

(defn read-model
  [state {:keys [vault-address vault-name details webdata]}]
  (let [vault-name* (or (non-blank-text vault-name)
                        "Vault")
        wallet-webdata (if (map? (:webdata2 state))
                         (:webdata2 state)
                         {})
        deposit-max-usdc (vault-transfer-deposit-max-usdc state wallet-webdata webdata)
        deposit-lockup-days (vault-deposit-lockup-days details vault-name*)
        raw-vault-transfer-modal (get-in state [:vaults-ui :vault-transfer-modal])
        vault-transfer-modal* (merge (vault-transfer-policy/default-vault-transfer-modal-state)
                                     (if (map? raw-vault-transfer-modal)
                                       raw-vault-transfer-modal
                                       {}))
        vault-transfer-vault-address (or (vault-identity/normalize-vault-address
                                          (:vault-address vault-transfer-modal*))
                                         vault-address)
        vault-transfer-mode (vault-transfer-policy/normalize-vault-transfer-mode
                             (:mode vault-transfer-modal*))
        vault-transfer-open? (and (true? (:open? vault-transfer-modal*))
                                  (= vault-transfer-vault-address vault-address))
        vault-transfer-preview (vault-transfer-policy/vault-transfer-preview
                                state
                                (assoc vault-transfer-modal*
                                       :vault-address vault-transfer-vault-address
                                       :mode vault-transfer-mode))
        vault-transfer-submitting? (true? (:submitting? vault-transfer-modal*))
        vault-transfer-submit-disabled? (or vault-transfer-submitting?
                                            (not (:ok? vault-transfer-preview)))
        vault-transfer-confirm-label (if vault-transfer-submitting?
                                      (if (= vault-transfer-mode :deposit)
                                        "Depositing..."
                                        "Withdrawing...")
                                      (if (= vault-transfer-mode :deposit)
                                        "Deposit"
                                        "Withdraw"))
        deposit-allowed? (vault-transfer-policy/vault-transfer-deposit-allowed? state vault-address)]
    {:can-open-deposit? deposit-allowed?
     :can-open-withdraw? true
     :open? vault-transfer-open?
     :mode vault-transfer-mode
     :vault-address vault-transfer-vault-address
     :deposit-max-usdc deposit-max-usdc
     :deposit-max-display (format-usdc-display deposit-max-usdc)
     :deposit-max-input (format-usdc-input deposit-max-usdc)
     :deposit-lockup-days deposit-lockup-days
     :deposit-lockup-copy (str "Deposit funds to "
                               vault-name*
                               ". The deposit lock-up period is "
                               deposit-lockup-days
                               " "
                               (if (= 1 deposit-lockup-days)
                                 "day."
                                 "days."))
     :amount-input (:amount-input vault-transfer-modal*)
     :withdraw-all? (true? (:withdraw-all? vault-transfer-modal*))
     :submitting? vault-transfer-submitting?
     :error (:error vault-transfer-modal*)
     :preview-ok? (:ok? vault-transfer-preview)
     :preview-message (:display-message vault-transfer-preview)
     :title (if (= vault-transfer-mode :deposit)
              "Deposit"
              "Withdraw")
     :confirm-label vault-transfer-confirm-label
     :submit-disabled? vault-transfer-submit-disabled?}))
