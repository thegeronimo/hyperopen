(ns hyperopen.views.account-info.projections.coins
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.outcomes.names :as outcome-names]
            [hyperopen.views.account-info.projections.parse :as parse]))

(def non-blank-text parse/non-blank-text)

(defn title-case-label [value]
  (let [text (non-blank-text value)]
    (if (seq text)
      (->> (str/split (str/lower-case text) #"[_\s-]+")
           (remove str/blank?)
           (map str/capitalize)
           (str/join " "))
      "-")))

(defn parse-coin-namespace [coin]
  (let [coin* (non-blank-text coin)]
    (when coin*
      (if (str/includes? coin* ":")
        (let [[prefix suffix] (str/split coin* #":" 2)]
          {:prefix (non-blank-text prefix)
           :base (non-blank-text suffix)})
        {:prefix nil
         :base coin*}))))

(defn symbol-base-label [symbol]
  (let [symbol* (non-blank-text symbol)]
    (when symbol*
      (let [parts (cond
                    (str/includes? symbol* "/") (str/split symbol* #"/" 2)
                    (str/includes? symbol* "-") (str/split symbol* #"-" 2)
                    :else [symbol*])]
        (non-blank-text (first parts))))))

(defn outcome-coin-display
  "Event · answer · side for an outcome side coin, never the id. A coin
   whose market has not loaded reads \"Outcome\" until it does."
  [coin market-by-key]
  (let [described (outcome-names/resolve-coin market-by-key coin)]
    {:base-label (or (:label described) "Outcome")
     :prefix-label nil
     :outcome? true
     :outcome-side-label (:side-label described)
     :outcome-event (:event described)}))

(defn- resolve-coin-display*
  [coin market-by-key resolve-market-by-coin]
  (let [coin* (non-blank-text coin)
        parsed (parse-coin-namespace coin*)
        market (resolve-market-by-coin (or market-by-key {}) coin*)
        market-base (or (non-blank-text (:display-name market))
                        (non-blank-text (:base market))
                        (symbol-base-label (:symbol market))
                        (some-> (:coin market) parse-coin-namespace :base))
        base-label (or market-base (:base parsed) coin* "-")
        prefix-label (:prefix parsed)]
    {:base-label base-label
     :prefix-label prefix-label}))

(defn resolve-coin-display
  ([coin market-by-key]
   (resolve-coin-display coin market-by-key markets/resolve-market-by-coin))
  ([coin market-by-key resolve-market-by-coin]
   (if (outcome-names/outcome-coin? (non-blank-text coin))
     (outcome-coin-display (non-blank-text coin) market-by-key)
     (resolve-coin-display* coin market-by-key resolve-market-by-coin))))

(defn normalized-coin-token [coin]
  (some-> coin non-blank-text str/upper-case))

(defn base-coin-token [coin]
  (some-> coin parse-coin-namespace :base normalized-coin-token))
