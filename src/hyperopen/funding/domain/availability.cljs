(ns hyperopen.funding.domain.availability
  (:require [clojure.string :as str]
            [hyperopen.funding.domain.amounts :as amounts]
            [hyperopen.funding.domain.assets :as assets-domain]))

(defn usdc-coin?
  [coin]
  (and (string? coin)
       (str/starts-with? (str/upper-case (str/trim coin)) "USDC")))

(defn normalize-coin-token
  [value]
  (some-> value str str/trim str/upper-case))

(defn direct-balance-row-available
  [row]
  (or (amounts/parse-num (:available row))
      (amounts/parse-num (:availableBalance row))
      (amounts/parse-num (:free row))))

(defn derived-balance-row-available
  [row]
  (let [total (or (amounts/parse-num (:total row))
                  (amounts/parse-num (:totalBalance row)))
        hold (amounts/parse-num (:hold row))]
    (when (amounts/finite-number? total)
      (if (amounts/finite-number? hold)
        (- total hold)
        total))))

(defn balance-row-available
  [row]
  (when (map? row)
    (let [available (or (direct-balance-row-available row)
                        (derived-balance-row-available row))]
      (when (amounts/finite-number? available)
        (max 0 available)))))

(defn spot-usdc-available
  [state]
  (some (fn [row]
          (when (usdc-coin? (:coin row))
            (balance-row-available row)))
        (get-in state [:spot :clearinghouse-state :balances])))

(defn unified-account-mode?
  [state]
  (= :unified (get-in state [:account :mode])))

(defn unified-spot-usdc-available
  [state]
  (when (unified-account-mode? state)
    (spot-usdc-available state)))

(defn spot-asset-available
  [state symbol]
  (let [target (normalize-coin-token symbol)]
    (some (fn [row]
            (when (= target
                     (normalize-coin-token (:coin row)))
              (balance-row-available row)))
          (get-in state [:spot :clearinghouse-state :balances]))))

(defn summary-derived-withdrawable
  [summary]
  (let [account-value (amounts/parse-num (:accountValue summary))
        margin-used (amounts/parse-num (:totalMarginUsed summary))]
    (when (and (amounts/finite-number? account-value)
               (amounts/finite-number? margin-used))
      (max 0 (- account-value margin-used)))))

(defn summary-candidates
  [clearinghouse-state]
  (remove nil?
          [(:marginSummary clearinghouse-state)
           (:crossMarginSummary clearinghouse-state)
           (get-in clearinghouse-state [:clearinghouseState :marginSummary])
           (get-in clearinghouse-state [:clearinghouseState :crossMarginSummary])]))

(defn clearinghouse-withdrawable
  [clearinghouse-state]
  (let [root-candidates (some amounts/parse-num
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
      (amounts/finite-number? root-candidates)
      (max 0 root-candidates)

      :else
      (some summary-derived-withdrawable (summary-candidates clearinghouse-state)))))

(defn clearinghouse-state-candidates
  [state]
  (->> [(get-in state [:webdata2 :clearinghouseState])
        (get-in state [:webdata2 :clearinghouseState :clearinghouseState])]
       (filter map?)
       distinct
       vec))

(defn perps-withdrawable
  [state]
  (max 0 (or (some clearinghouse-withdrawable
                   (clearinghouse-state-candidates state))
             0)))

(defn withdrawable-usdc
  [state]
  (max 0 (or (unified-spot-usdc-available state)
             (perps-withdrawable state))))

(defn withdraw-available-amount
  [state asset]
  (cond
    (nil? asset)
    0

    (= :usdc (:key asset))
    (withdrawable-usdc state)

    :else
    (or (spot-asset-available state (:symbol asset))
        0)))

(defn withdraw-available-list-display
  [value]
  (if (pos? (or value 0))
    (amounts/amount->text value)
    "-"))

(defn withdraw-assets
  [state]
  (mapv (fn [asset]
          (let [available-amount (withdraw-available-amount state asset)]
            (assoc asset
                   :available-amount available-amount
                   :available-display (withdraw-available-list-display available-amount)
                   :available-detail-display (amounts/amount->text available-amount))))
        (assets-domain/withdraw-assets state)))

(defn withdraw-assets-filtered
  [state modal]
  (let [search-term (-> (or (:withdraw-search-input modal) "")
                        str
                        str/trim
                        str/lower-case)
        assets (withdraw-assets state)]
    (if-not (seq search-term)
      assets
      (filterv (fn [{:keys [symbol name network]}]
                 (let [symbol* (str/lower-case (or symbol ""))
                       name* (str/lower-case (or name ""))
                       network* (str/lower-case (or network ""))]
                   (or (str/includes? symbol* search-term)
                       (str/includes? name* search-term)
                       (str/includes? network* search-term))))
               assets))))

(defn withdraw-asset
  [state modal]
  (let [selected-key (or (assets-domain/normalize-withdraw-asset-key
                          (:withdraw-selected-asset-key modal))
                         assets-domain/withdraw-default-asset-key)
        assets (withdraw-assets state)]
    (or (some (fn [asset]
                (when (= selected-key (:key asset))
                  asset))
              assets)
        (first assets))))

(defn transfer-dex-name
  "Normalize the perps-side dex carried on the transfer modal. An empty string,
  nil, or \"spot\" all mean the default perps clearinghouse; any other non-blank
  string names a HIP-3 perps DEX (e.g. \"xyz\")."
  [value]
  (let [dex (some-> value str str/trim)]
    (when (and (seq dex)
               (not= "spot" (str/lower-case dex)))
      dex)))

(defn named-dex-withdrawable
  [state dex]
  (max 0 (or (clearinghouse-withdrawable (get-in state [:perp-dex-clearinghouse dex]))
             0)))

(defn pooled-perps-collateral?
  "True when the account holds perps USDC as a single pooled balance shared across
  DEXs rather than siloed per HIP-3 DEX. For pooled accounts the exchange rejects a
  per-DEX `sendAsset` (named `sourceDex`/`destinationDex`) with \"Unified account
  only supports sending assets through spot\": their perps<->spot moves must use the
  *default* perps DEX (`sourceDex`/`destinationDex` in {\"\", \"spot\"}). Verified
  against the live ledger of the reporting unified account `0x999e9a39…`, whose 2026
  perps<->spot transfers are all `sendAsset` with `sourceDex:\"\"`/`destinationDex:\"spot\"`
  (and the reverse) — never a named dex, and no `usdClassTransfer` since the unified
  migration.

  Covers two account shapes the rest of the app otherwise treats differently:
  - unified / portfolio-margin accounts (`:account :mode` is `:unified`), and
  - the deprecated DEX Abstraction mode (raw `userAbstraction` = `dexAbstraction`),
    which `normalize-user-abstraction-mode` folds into `:classic` even though its
    USDC defaults to the (pooled) perps balance.

  Genuine Standard/manual accounts (`default`/`disabled`/nil/unknown) return false
  and keep the per-DEX `sendAsset` path, where their collateral really is siloed."
  [state]
  (or (= :unified (get-in state [:account :mode]))
      (= "dexabstraction"
         (some-> (get-in state [:account :abstraction-raw])
                 str
                 str/trim
                 str/lower-case))))

(defn transfer-max-amount
  [state {:keys [to-perp? transfer-dex]}]
  (if (true? to-perp?)
    (or (spot-usdc-available state) 0)
    (if-let [dex (transfer-dex-name transfer-dex)]
      (if (pooled-perps-collateral? state)
        (perps-withdrawable state)
        (named-dex-withdrawable state dex))
      (perps-withdrawable state))))

(defn withdraw-max-amount
  [state selected-asset]
  (withdraw-available-amount state selected-asset))
