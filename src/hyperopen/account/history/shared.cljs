(ns hyperopen.account.history.shared
  (:require [clojure.string :as str]
            [hyperopen.utils.parse :as parse-utils]))

(def ^:private order-history-page-size-options
  #{25 50 100})

(def default-order-history-page-size
  50)

(def ^:private account-info-coin-search-tabs
  #{:balances :positions :open-orders :trade-history :order-history})

(def ^:private account-info-route-tabs
  #{:balances
    :positions
    :outcomes
    :open-orders
    :twap
    :trade-history
    :funding-history
    :order-history})

(defn- normalize-keyword-like
  [value]
  (let [text (cond
               (keyword? value) (name value)
               (string? value) (str/trim value)
               :else nil)]
    (when (seq text)
      (-> text
          (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
          str/lower-case
          (str/replace #"[_\s]+" "-")
          keyword))))

(defn normalize-order-history-page-size
  ([value]
   (normalize-order-history-page-size value nil))
  ([value locale]
   (let [candidate (parse-utils/parse-localized-int-value value locale)]
     (if (contains? order-history-page-size-options candidate)
       candidate
       default-order-history-page-size))))

(defn normalize-order-history-page
  ([value]
   (normalize-order-history-page value nil nil))
  ([value max-page]
   (normalize-order-history-page value max-page nil))
  ([value max-page locale]
   (let [candidate (max 1 (or (parse-utils/parse-localized-int-value value locale) 1))
         max-page* (when (some? max-page)
                     (max 1 (or (parse-utils/parse-localized-int-value max-page locale) 1)))]
     (if max-page*
       (min candidate max-page*)
       candidate))))

(defn normalize-account-info-tab
  [tab]
  (let [tab* (cond
               (keyword? tab) tab
               (string? tab) (keyword (-> tab
                                          str/trim
                                          str/lower-case))
               :else :balances)]
    (if (contains? account-info-coin-search-tabs tab*)
      tab*
      :balances)))

(defn normalize-account-info-route-tab
  [value]
  (let [token (normalize-keyword-like value)
        normalized (case token
                     :balance :balances
                     :balances :balances
                     :position :positions
                     :positions :positions
                     :outcome :outcomes
                     :outcomes :outcomes
                     :openorder :open-orders
                     :openorders :open-orders
                     :open-orders :open-orders
                     :twap :twap
                     :trade :trade-history
                     :trades :trade-history
                     :fills :trade-history
                     :tradehistory :trade-history
                     :trade-history :trade-history
                     :accountactivity :funding-history
                     :account-activity :funding-history
                     :activity :funding-history
                     :fundinghistory :funding-history
                     :funding-history :funding-history
                     :order :order-history
                     :orders :order-history
                     :orderhistory :order-history
                     :order-history :order-history
                     token)]
    (when (contains? account-info-route-tabs normalized)
      normalized)))

(defn normalize-coin-search-value
  [value]
  (if (string? value)
    value
    (str (or value ""))))
