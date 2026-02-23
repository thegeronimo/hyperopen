(ns hyperopen.state.trading.fee-context
  (:require [clojure.string :as str]))

(def ^:private special-quote-adjustment-quotes
  #{"HORSE"
    "USDH"
    "USDL"
    "USDZZ"})

(defn- normalize-market-type
  [value]
  (cond
    (keyword? value) value
    (string? value) (let [normalized (some-> value str/trim str/lower-case)]
                      (when (seq normalized)
                        (keyword normalized)))
    :else nil))

(defn- normalize-dex
  [market]
  (let [dex (some-> (:dex market) str str/trim)]
    (when (seq dex)
      dex)))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- parse-number
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseFloat value)
              :else js/NaN)]
    (when (finite-number? num)
      num)))

(defn- growth-mode-enabled?
  [value]
  (or (= true value)
      (= :enabled value)
      (= "enabled" (some-> value str str/trim str/lower-case))))

(defn- special-quote-fee-adjustment?
  [market]
  (let [quote (some-> (:quote market) str str/trim str/upper-case)]
    (contains? special-quote-adjustment-quotes quote)))

(defn select-fee-context
  "Extract fee-relevant order-summary inputs for the active market."
  [state]
  (let [active-market (or (:active-market state) {})
        dex (normalize-dex active-market)
        growth-mode? (if (contains? active-market :growth-mode?)
                       (boolean (:growth-mode? active-market))
                       (growth-mode-enabled? (:growthMode active-market)))
        user-fees (get-in state [:portfolio :user-fees])]
    {:market-type (normalize-market-type (:market-type active-market))
     :stable-pair? (boolean (:stable-pair? active-market))
     :growth-mode? (boolean growth-mode?)
     :dex dex
     :deployer-fee-scale (parse-number (get-in state [:perp-dex-fee-config-by-name dex :deployer-fee-scale]))
     :special-quote-fee-adjustment?
     (if (contains? active-market :special-quote-fee-adjustment?)
       (boolean (:special-quote-fee-adjustment? active-market))
       (special-quote-fee-adjustment? active-market))
     :user-fees (when (map? user-fees)
                  user-fees)}))
