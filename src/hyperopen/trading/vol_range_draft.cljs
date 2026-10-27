(ns hyperopen.trading.vol-range-draft
  "Canonical Vol Range draft shape and normalization (HO-SPEC-014 v2.1 sections
  7.1-7.2, VR-01).

  The draft lives at `[:order-form :scale :vol-range]` next to the legacy Scale
  fields. `normalize-scale-form` preserves `:mode` and `:vol-range` only when
  they are present, so a legacy Scale form normalizes exactly as before and a
  missing or unknown mode reads as `:price-range` through `scale-mode`.

  Numeric draft fields are kept as strings: empty or intermediate text is a
  draft state, never numeric zero. Nothing here parses user text; the
  locale-aware boundary is `hyperopen.vol-range.actions`."
  (:require [clojure.string :as str]
            [hyperopen.config :as config]
            [hyperopen.domain.trading.precision :as precision]))

(def scale-modes #{:price-range :volatility-range})

(def anchor-modes #{:vwap-24h :utc-open :market-price :custom})

(def volatility-modes #{:auto :manual})

(def amount-units
  "How a side's size (or the inventory limits) is entered: base quantity, or
  USDC that the planner converts."
  #{:base :quote})

(defn default-vol-range-draft
  "Spec 7.1 defaults for a new account/market draft."
  []
  {:anchor {:mode :vwap-24h :custom-price ""}
   :volatility {:mode :auto :manual-daily-pct ""}
   :inventory {:limit-unit :base
               :protected-core ""
               :target ""
               :maximum ""
               :protected-core-quote ""
               :target-quote ""
               :maximum-quote ""
               :tilt-enabled? false
               :tilt-strength "0.45"}
   :buy {:enabled? true
         :amount-unit :base
         :quantity ""
         :quote-amount ""
         :max-usdc-debit ""
         :count 4
         :start-sigma "0.75"
         :end-sigma "2.25"
         :skew "1.00"}
   :sell {:enabled? false
          :amount-unit :base
          :quantity ""
          :quote-amount ""
          :count 3
          :start-sigma "1.25"
          :end-sigma "2.25"
          :skew "1.00"}})

;; ---------------------------------------------------------------------------
;; Field coercion
;; ---------------------------------------------------------------------------

(defn- enum-value
  "Keyword or string -> keyword when a member of `allowed`, else `default`."
  [value allowed default]
  (let [candidate (cond
                    (keyword? value) value
                    (string? value) (keyword (str/lower-case (str/trim value)))
                    :else nil)]
    (if (contains? allowed candidate) candidate default)))

(defn normalize-scale-mode
  "Normalize a raw scale mode to a member of `scale-modes`; unknown, missing or
  legacy values read as :price-range."
  [mode]
  (enum-value mode scale-modes :price-range))

(defn scale-mode
  "Effective Scale mode of an order form: :volatility-range only when the
  preview capability is on AND the nested mode normalizes to it, else
  :price-range. With the preview off a persisted :volatility-range reads as
  :price-range, so ordinary Scale is untouched."
  [form]
  (if (and (config/vol-range-preview-enabled?)
           (= :volatility-range (normalize-scale-mode (get-in form [:scale :mode]))))
    :volatility-range
    :price-range))

(defn- numeric-string
  "Strings stay as typed; finite numbers become their canonical decimal
  string; anything else falls back to `default`."
  [value default]
  (cond
    (string? value) value
    (number? value) (if-let [d (precision/number->decimal value)]
                      (precision/decimal->string d)
                      default)
    :else default))

(defn- count-value [value default]
  (cond
    (and (number? value) (js/Number.isFinite value)) value
    (string? value) value
    :else default))

(defn- normalize-buy [raw defaults]
  (let [raw (if (map? raw) raw {})]
    {:enabled? (if (contains? raw :enabled?) (boolean (:enabled? raw)) (:enabled? defaults))
     :amount-unit (enum-value (:amount-unit raw) amount-units (:amount-unit defaults))
     :quantity (numeric-string (:quantity raw) (:quantity defaults))
     :quote-amount (numeric-string (:quote-amount raw) (:quote-amount defaults))
     :max-usdc-debit (numeric-string (:max-usdc-debit raw) (:max-usdc-debit defaults))
     :count (count-value (:count raw) (:count defaults))
     :start-sigma (numeric-string (:start-sigma raw) (:start-sigma defaults))
     :end-sigma (numeric-string (:end-sigma raw) (:end-sigma defaults))
     :skew (numeric-string (:skew raw) (:skew defaults))}))

(defn- normalize-sell [raw defaults]
  (let [raw (if (map? raw) raw {})]
    {:enabled? (if (contains? raw :enabled?) (boolean (:enabled? raw)) (:enabled? defaults))
     :amount-unit (enum-value (:amount-unit raw) amount-units (:amount-unit defaults))
     :quantity (numeric-string (:quantity raw) (:quantity defaults))
     :quote-amount (numeric-string (:quote-amount raw) (:quote-amount defaults))
     :count (count-value (:count raw) (:count defaults))
     :start-sigma (numeric-string (:start-sigma raw) (:start-sigma defaults))
     :end-sigma (numeric-string (:end-sigma raw) (:end-sigma defaults))
     :skew (numeric-string (:skew raw) (:skew defaults))}))

(defn normalize-vol-range-draft
  "Merge a raw draft over the defaults, coerce enum strings to keywords (unknown
  enums fall back to defaults), coerce booleans, keep numeric fields as strings
  and drop unknown keys."
  [draft]
  (let [raw (if (map? draft) draft {})
        defaults (default-vol-range-draft)
        anchor (if (map? (:anchor raw)) (:anchor raw) {})
        vol (if (map? (:volatility raw)) (:volatility raw) {})
        inv (if (map? (:inventory raw)) (:inventory raw) {})
        inv-defaults (:inventory defaults)]
    {:anchor {:mode (enum-value (:mode anchor) anchor-modes (get-in defaults [:anchor :mode]))
              :custom-price (numeric-string (:custom-price anchor) "")}
     :volatility {:mode (enum-value (:mode vol) volatility-modes (get-in defaults [:volatility :mode]))
                  :manual-daily-pct (numeric-string (:manual-daily-pct vol) "")}
     :inventory {:limit-unit (enum-value (:limit-unit inv) amount-units (:limit-unit inv-defaults))
                 :protected-core (numeric-string (:protected-core inv) "")
                 :target (numeric-string (:target inv) "")
                 :maximum (numeric-string (:maximum inv) "")
                 :protected-core-quote (numeric-string (:protected-core-quote inv) "")
                 :target-quote (numeric-string (:target-quote inv) "")
                 :maximum-quote (numeric-string (:maximum-quote inv) "")
                 :tilt-enabled? (boolean (:tilt-enabled? inv))
                 :tilt-strength (numeric-string (:tilt-strength inv) (:tilt-strength inv-defaults))}
     :buy (normalize-buy (:buy raw) (:buy defaults))
     :sell (normalize-sell (:sell raw) (:sell defaults))}))

;; ---------------------------------------------------------------------------
;; Scoped values and export projection
;; ---------------------------------------------------------------------------

(def scoped-draft-paths
  "Account- and market-specific quantities and limits. They never carry across
  wallets, subaccounts or markets and never reach the unscoped durable export."
  [[:inventory :protected-core]
   [:inventory :target]
   [:inventory :maximum]
   [:inventory :protected-core-quote]
   [:inventory :target-quote]
   [:inventory :maximum-quote]
   [:buy :quantity]
   [:buy :quote-amount]
   [:buy :max-usdc-debit]
   [:sell :quantity]
   [:sell :quote-amount]
   [:anchor :custom-price]])

(def localized-numeric-draft-paths
  "Draft paths whose raw text passes through the locale-aware input boundary."
  (into (set scoped-draft-paths)
        (concat [[:volatility :manual-daily-pct]
                 [:inventory :tilt-strength]]
                (for [side [:buy :sell]
                      field [:count :start-sigma :end-sigma :skew]]
                  [side field]))))

(defn scoped-values
  "Map of scoped path -> value for a draft."
  [draft]
  (into {} (map (fn [path] [path (get-in draft path "")])) scoped-draft-paths))

(defn blank-scoped-values
  "Draft with every scoped path set to an empty string."
  [draft]
  (reduce (fn [acc path] (assoc-in acc path "")) draft scoped-draft-paths))

(defn unscoped-export-projection
  "Separate durable-export projection (spec 7.2): the order form with every
  scoped Vol Range path blanked. The selected mode and unscoped preferences are
  kept. Forms without a nested draft are returned unchanged."
  [order-form]
  (if (map? (get-in order-form [:scale :vol-range]))
    (update-in order-form [:scale :vol-range] blank-scoped-values)
    order-form))
