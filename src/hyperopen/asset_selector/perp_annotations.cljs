(ns hyperopen.asset-selector.perp-annotations
  "Hyperliquid curates per-market annotations for HIP-3 perps
   (`{\"type\" \"perpConciseAnnotations\"}`): a human display name
   (`xyz:HO` -> DIESEL), a category, and search keywords. The coin code stays
   the market identity; annotations only drive what the UI shows and searches."
  (:require [clojure.string :as str]))

(defn- non-blank-text
  [value]
  (when (string? value)
    (let [text (str/trim value)]
      (when (seq text) text))))

(defn- normalize-keywords
  [keywords]
  (when (sequential? keywords)
    (not-empty
     (into []
           (comp (keep non-blank-text)
                 (map str/lower-case)
                 (distinct))
           keywords))))

(defn- normalize-annotation
  [annotation]
  (when (map? annotation)
    (let [display-name (non-blank-text (or (get annotation :displayName)
                                           (get annotation "displayName")))
          category (some-> (non-blank-text (or (get annotation :category)
                                               (get annotation "category")))
                           str/lower-case)
          keywords (normalize-keywords (or (get annotation :keywords)
                                           (get annotation "keywords")))]
      (when (or display-name category keywords)
        (cond-> {}
          display-name (assoc :display-name display-name)
          category (assoc :category category)
          keywords (assoc :keywords keywords))))))

(defn normalize-perp-annotations
  "Turn the `[[coin annotation] ...]` payload into `{coin annotation}`.
   Malformed rows are dropped; anything else yields an empty map."
  [payload]
  (if (sequential? payload)
    (reduce (fn [acc row]
              (if (and (sequential? row) (= 2 (count row)))
                (let [coin (non-blank-text (first row))
                      annotation (normalize-annotation (second row))]
                  (if (and coin annotation)
                    (assoc acc coin annotation)
                    acc))
                acc))
            {}
            payload)
    {}))

(defn- annotated-category
  "Only promote to :crypto. Our two perp buckets are :crypto and :tradfi, and
   HIP-3 markets already default to :tradfi."
  [market annotation]
  (if (and (:hip3? market)
           (= "crypto" (:category annotation)))
    :crypto
    (:category market)))

(defn apply-perp-annotation
  [market annotation]
  (if (and (= :perp (:market-type market))
           (map? annotation))
    (let [{:keys [display-name keywords]} annotation
          quote (or (:quote market) "USDC")]
      (cond-> (assoc market :category (annotated-category market annotation))
        display-name (assoc :display-name display-name
                            :display-symbol (str display-name "-" quote))
        keywords (assoc :search-keywords keywords)))
    market))

(defn apply-perp-annotations
  "Enrich every perp market in a market-state map
   (`:markets`, `:market-by-key`, `:active-market`) with its annotation."
  [market-state annotations-by-coin]
  (if (and (map? market-state) (seq annotations-by-coin))
    (let [enrich (fn [market]
                   (apply-perp-annotation market
                                          (get annotations-by-coin (:coin market))))
          markets (mapv enrich (:markets market-state))]
      (cond-> (assoc market-state
                     :markets markets
                     :market-by-key (into {}
                                          (map (fn [m] [(:key m) m]))
                                          markets))
        (map? (:active-market market-state))
        (update :active-market enrich)))
    market-state))

(defn display-symbol
  "The label to show for a market: Hyperliquid's display name when it has one,
   otherwise the coin-derived symbol."
  [market]
  (or (non-blank-text (:display-symbol market))
      (non-blank-text (:symbol market))))
