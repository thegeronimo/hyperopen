(ns hyperopen.asset-selector.market-info
  "The \"About this market\" model for a HIP-3 market: a plain-language
  headline, the rest of the deployer's description, a fact grid, and a unit
  warning when the price is not what a trader would assume. Pure: built from
  `annotations/symbol-metadata` plus the catalog market."
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.annotations :as annotations]
            [hyperopen.asset-selector.perp-annotations :as perp-annotations]))

(def ^:private headline-max-length 140)

(def ^:private listing-pattern
  ;; "(Nasdaq: SPCX)", "(KRX: 005380)", "(SSE STAR: 688825)", "(Cboe BZX: DRAM)"
  #"\(([A-Z][A-Za-z]+(?:\s+[A-Z][A-Za-z]+)?:\s*[A-Z0-9][A-Z0-9.]*)\)")

(def ^:private unit-patterns
  ;; "references 1 share of", "the USD price of 1 barrel of", "1 troy ounce"
  [#"(?i)\b(?:references|tracks|represents)\s+(?:the\s+(?:USD\s+)?(?:spot\s+)?(?:price|value)\s+of\s+)?((?:one|1|\d+(?:\.\d+)?)\s+(?:registered\s+)?(?:Class\s+[A-Z]\s+)?(?:ordinary\s+|common\s+)?(?:American\s+depositary\s+shares?|ADSs?|[AH]-shares?|shares?|troy\s+ounces?|barrels?|bushels?|pounds?|metric\s+tons?|MMBtu|U\.S\.\s+gallons?))\b"
   ;; "ARM references 1 Arm Holdings plc ADS" -> "ADS"
   #"\breferences\s+(?:one|1)\s+.+?\s+((?:Class\s+[A-Z]\s+)?(?:ADS|ordinary\s+share|common\s+share|[AH]-share))\b"])

(defn listing-from-description
  [description]
  (when (string? description)
    (some-> (re-find listing-pattern description) second str/trim)))

(defn contract-unit-from-description
  [description]
  (when (string? description)
    (some (fn [pattern]
            (when-let [[_ unit] (re-find pattern description)]
              (let [unit* (-> unit
                              (str/replace #"(?i)^one\s+" "1 ")
                              (str/replace #"\s+" " ")
                              str/trim)]
                (if (re-find #"^\d" unit*)
                  unit*
                  (str "1 " unit*)))))
          unit-patterns)))

(defn- unit-warning
  [{:keys [category description]}]
  (let [text (str/lower-case (or description ""))]
    (cond
      (and (= :preipo category) (str/includes? text "valuation"))
      "Price is a company valuation, not a share price"

      (and (= :rates category) (str/includes? text "yield"))
      "Price is a yield in percent, not a bond price"

      :else nil)))

(defn- leverage-label
  [max-leverage]
  (when (and (number? max-leverage) (pos? max-leverage))
    (str (if (== max-leverage (js/Math.floor max-leverage))
           (js/Math.floor max-leverage)
           max-leverage)
         "x")))

(defn- margin-label
  [market]
  (cond
    (true? (:only-isolated? market)) "Isolated only"
    (map? market) "Cross or isolated"
    :else nil))

(defn- fact
  [label value]
  (when (and (string? value) (seq value))
    {:label label :value value}))

(defn market-info-model
  "Nil unless `symbol-meta` describes a HIP-3 market."
  [symbol-meta market]
  (when (map? symbol-meta)
    (let [{:keys [coin dex deployer description issuer display-name keywords
                  category category-label loading?]} symbol-meta
          unit (contract-unit-from-description description)
          ;; Built from parsed parts when possible: issuer names such as
          ;; "Corp." would otherwise end the first sentence early.
          parsed-headline (when (and issuer unit) (str unit " of " issuer))
          headline (or parsed-headline
                       (annotations/description-first-clause description headline-max-length)
                       (when (seq display-name)
                         display-name))
          deployer-label (if (and (seq deployer) (not= deployer dex))
                           (str deployer " (" dex ")")
                           (or deployer dex))]
      {:coin coin
       :symbol (or (perp-annotations/display-symbol market) coin)
       :status (cond
                 loading? :loading
                 (seq description) :described
                 :else :undescribed)
       :headline headline
       :details (if parsed-headline
                  description
                  (annotations/description-remainder description))
       :description description
       :category-label category-label
       :warning (unit-warning {:category category :description description})
       :deployer (or deployer dex)
       :keywords (vec keywords)
       :facts (into []
                    (keep identity)
                    [(fact "Underlying" issuer)
                     (fact "Listing" (listing-from-description description))
                     (fact "Contract size" unit)
                     (fact "Category" category-label)
                     (fact "Deployer" deployer-label)
                     (fact "Max leverage" (leverage-label (:maxLeverage market)))
                     (fact "Margin" (margin-label market))])})))

(defn open-panel-model
  "The panel model when the trade UI has it open, else nil. The panel shares
  the space under the market strip with the selector, which hides it."
  [state coin market]
  (when (true? (get-in state [:trade-ui :market-info-open?]))
    (market-info-model (annotations/header-metadata state coin) market)))
