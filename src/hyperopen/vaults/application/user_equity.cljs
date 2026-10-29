(ns hyperopen.vaults.application.user-equity
  "The shown account's total vault equity, read from the `userVaultEquities`
   rows at `[:vaults :user-equities]`.

   This is the only live source. Hyperliquid no longer sends webData2, so
   `[:webdata2 :totalVaultEquity]` is never written; reading it reported $0 in
   vaults for every account. Every account-value surface (the /trade Account
   Equity panel, the Portfolio summary, its funds strip and Monte Carlo)
   reads through here so they cannot disagree.

   Unknown is never zero: rows that belong to another account, a failed read,
   the first load, or a row that does not parse all leave the figure unknown."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]))

(defn- parse-finite-number
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

    :else
    nil))

(defn vault-equity-status
  "`{:status :ready :usd n}` once the effective account's rows are loaded and
   every row parses; otherwise `{:status s}` with `s` one of `:none` (no
   account shown), `:unavailable` (the read failed or returned rows that do
   not parse) or `:loading`. A background refresh keeps the last good rows,
   so it stays `:ready`."
  [state]
  (let [effective-address (account-context/effective-account-address state)
        rows (get-in state [:vaults :user-equities])
        source-address (get-in state [:vaults :user-equities-for-address])
        error-address (get-in state [:vaults :user-equities-error-for-address])]
    (cond
      (nil? effective-address)
      {:status :none}

      (= effective-address error-address)
      {:status :unavailable}

      (not= effective-address source-address)
      {:status :loading}

      (not (vector? rows))
      {:status :unavailable}

      :else
      (let [equities (mapv (fn [row]
                             (when (map? row)
                               (parse-finite-number (:equity-raw row))))
                           rows)]
        (if (every? some? equities)
          {:status :ready
           :usd (reduce + 0 equities)}
          {:status :unavailable})))))

(defn current-vault-equity
  "The effective account's total vault equity in USD, or nil when unknown."
  [state]
  (let [{:keys [status usd]} (vault-equity-status state)]
    (when (= :ready status)
      usd)))
