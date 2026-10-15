(ns hyperopen.asset-selector.annotations
  "HIP-3 market annotations published on-chain by each deployer.

  `perpConciseAnnotations` returns category, display name and keywords for
  every annotated market in one call. `perpAnnotation` returns the same plus
  a free-text description, one coin per call. Everything here is pure: payload
  normalization, the `:perp-annotations` app-db projection, and the subtitle
  the market header and selector rows show."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Text helpers

(defn- non-blank-text
  [value]
  (when (string? value)
    (let [text (str/trim value)]
      (when (seq text)
        text))))

(defn- coin-dex
  [coin]
  (when (string? coin)
    (let [idx (str/index-of coin ":")]
      (when (and idx (pos? idx))
        (subs coin 0 idx)))))

(defn- coin-base
  [coin]
  (when (string? coin)
    (if-let [idx (str/index-of coin ":")]
      (subs coin (inc idx))
      coin)))

(defn annotatable-coin?
  "Only HIP-3 perps (`dex:BASE`) carry deployer annotations."
  [coin]
  (some? (coin-dex coin)))

;; ---------------------------------------------------------------------------
;; Categories

(def category-order
  [:stocks :indices :commodities :preipo :fx :rates :crypto])

(def ^:private category-aliases
  {"stock" :stocks
   "stocks" :stocks
   "equity" :stocks
   "equities" :stocks
   "index" :indices
   "indices" :indices
   "commodity" :commodities
   "commodities" :commodities
   "preipo" :preipo
   "pre-ipo" :preipo
   "fx" :fx
   "forex" :fx
   "rate" :rates
   "rates" :rates
   "crypto" :crypto})

(def ^:private category-singular-labels
  {:stocks "Stock"
   :indices "Index"
   :commodities "Commodity"
   :preipo "Pre-IPO"
   :fx "FX"
   :rates "Rates"
   :crypto "Crypto"})

(def ^:private category-plural-labels
  {:stocks "Stocks"
   :indices "Indices"
   :commodities "Commodities"
   :preipo "Pre-IPO"
   :fx "FX"
   :rates "Rates"
   :crypto "Crypto"})

(defn normalize-category
  "Deployers spell categories inconsistently (`fx`/`FX`, `stock`/`stocks`)."
  [value]
  (when-let [text (some-> value non-blank-text str/lower-case)]
    (or (get category-aliases text)
        (keyword text))))

(defn category-label
  [category]
  (when (keyword? category)
    (or (get category-singular-labels category)
        (str/capitalize (name category)))))

(defn category-plural-label
  [category]
  (when (keyword? category)
    (or (get category-plural-labels category)
        (str/capitalize (name category)))))

;; ---------------------------------------------------------------------------
;; Payload normalization

(defn- normalize-keywords
  [value]
  (when (sequential? value)
    (->> value
         (keep non-blank-text)
         (map str/lower-case)
         distinct
         vec
         not-empty)))

(defn normalize-annotation
  "Normalize one annotation map from either endpoint. Returns nil for the
  JSON `null` the API sends when a deployer published nothing."
  [value]
  (when (map? value)
    (let [category (normalize-category (:category value))
          display-name (non-blank-text (:displayName value))
          keywords (normalize-keywords (:keywords value))
          description (non-blank-text (:description value))]
      (cond-> {}
        category (assoc :category category)
        display-name (assoc :display-name display-name)
        keywords (assoc :keywords keywords)
        description (assoc :description description)))))

(defn normalize-concise-payload
  "`[[coin {category displayName keywords}] ...]` -> `{coin annotation}`."
  [payload]
  (cond
    (map? payload)
    (reduce-kv (fn [acc coin value]
                 (let [coin* (non-blank-text (if (keyword? coin) (name coin) coin))
                       annotation (normalize-annotation value)]
                   (if (and coin* annotation)
                     (assoc acc coin* annotation)
                     acc)))
               {}
               payload)

    (sequential? payload)
    (reduce (fn [acc entry]
              (let [[coin value] (when (sequential? entry) entry)
                    coin* (non-blank-text coin)
                    annotation (normalize-annotation value)]
                (if (and coin* annotation)
                  (assoc acc coin* annotation)
                  acc)))
            {}
            payload)

    :else
    {}))

;; ---------------------------------------------------------------------------
;; App-db projection: [:perp-annotations]

(def default-state
  {:concise-status :idle
   :by-coin {}
   :detail-by-coin {}})

(defn- annotations-state
  [state]
  (merge default-state (:perp-annotations state)))

(defn concise-load-needed?
  [state]
  (contains? #{:idle :error} (:concise-status (annotations-state state))))

(defn begin-concise-load
  [state]
  (assoc state :perp-annotations
         (assoc (annotations-state state) :concise-status :loading)))

(defn apply-concise-success
  [state payload]
  (assoc state :perp-annotations
         (assoc (annotations-state state)
                :concise-status :loaded
                :by-coin (normalize-concise-payload payload))))

(defn apply-concise-error
  [state]
  (assoc state :perp-annotations
         (assoc (annotations-state state) :concise-status :error)))

(defn detail-load-needed?
  [state coin]
  (let [status (get-in (annotations-state state) [:detail-by-coin coin :status])]
    (or (nil? status)
        (= :error status))))

(defn begin-detail-load
  [state coin]
  (assoc state :perp-annotations
         (assoc-in (annotations-state state)
                   [:detail-by-coin coin]
                   {:status :loading})))

(defn apply-detail-success
  [state coin payload]
  (assoc state :perp-annotations
         (assoc-in (annotations-state state)
                   [:detail-by-coin coin]
                   {:status :loaded
                    :annotation (normalize-annotation payload)})))

(defn apply-detail-error
  [state coin]
  (assoc state :perp-annotations
         (assoc-in (annotations-state state)
                   [:detail-by-coin coin]
                   {:status :error})))

;; ---------------------------------------------------------------------------
;; Subtitle derivation

(def ^:private max-subtitle-length 56)

(defn- clip
  [text limit]
  (if (> (count text) limit)
    (let [cut (subs text 0 limit)
          space (str/last-index-of cut " ")
          cut* (if (and space (> space (quot limit 2)))
                 (subs cut 0 space)
                 cut)]
      (str (str/replace cut* #"[\s,;:(\-]+$" "") "…"))
    text))

(def ^:private issuer-patterns
  ;; Deployers phrase single-share contracts three ways. Periods and commas
  ;; are never terminators, so "Tesla, Inc." and "N.V." survive intact.
  [;; "SPCX references 1 share of Space Exploration Technologies Corp. Class A
   ;; common stock (Nasdaq: SPCX)" -> "Space Exploration Technologies Corp."
   #"(?i)\b(?:references|tracks|represents)\s+(?:one|\d+(?:\.\d+)?)\s+(?:registered\s+)?(?:Class\s+[A-Z]\s+)?(?:ordinary\s+|common\s+)?(?:American\s+depositary\s+shares?|ADSs?|[AH]-shares?|shares?|units?)\s+of\s+(?:the\s+)?(.+?)(?:\s+Class\s+[A-Z]\b|\s+(?:common|ordinary|preferred|American)\b|\s+(?:shares?|ADSs?)\b|\s+stock\b(?!\s+company)|\s*\(|;|\s+quoted\b)"
   ;; "ARM references 1 Arm Holdings plc ADS (Nasdaq: ARM)"
   ;; "NBIS references 1 Nebius Group N.V. Class A ordinary share"
   #"\breferences\s+(?:one|1)\s+(.+?)\s+(?:Class\s+[A-Z]\s+)?(?:ADSs?|ordinary\s+shares?|common\s+shares?|[AH]-shares?)\b"
   ;; "GDX tracks the value of one share of the VanEck Gold Miners ETF."
   #"(?i)\bvalue\s+of\s+(?:one|1)\s+share\s+of\s+(?:the\s+)?(.+?)(?:\.(?:\s|$)|,|;|\s*\()"])

(defn issuer-from-description
  "The company or fund behind a single-share contract, when the description
  names one."
  [description]
  (when-let [text (non-blank-text description)]
    (some (fn [pattern]
            (when-let [[_ issuer] (re-find pattern text)]
              (let [issuer* (non-blank-text issuer)]
                (when (and issuer*
                           (<= (count issuer*) 80))
                  issuer*))))
          issuer-patterns)))

(def ^:private description-lead-pattern
  ;; "WTIOIL references the USD price ..." -> "the USD price ..."
  ;; "A perpetual future contract tracking the price of Gold" -> "Gold"
  #"(?i)^(?:(?:[A-Z0-9][A-Za-z0-9&.$/-]*\s+)?(?:references|tracks|represents|provides|offers)\s+|a\s+perpetual\s+future(?:\s+contract)?\s+tracking\s+(?:the\s+price\s+of\s+)?)")

(def ^:private sentence-boundary
  ;; A period after a lowercase letter, digit or ")" ends a sentence, so
  ;; "U.S." and "N.V." do not. No-limit split: CLJS's limited split ignores
  ;; lookbehind.
  #"(?<=[a-z0-9)])\.\s+")

(defn description-first-clause
  ([description]
   (description-first-clause description max-subtitle-length))
  ([description limit]
   (when-let [text (non-blank-text description)]
     (let [sentence (first (str/split text sentence-boundary))
           clause (-> sentence
                      (str/replace description-lead-pattern "")
                      (str/replace #"(?i),?\s+quoted in [A-Z]{3}\.?$" "")
                      (str/replace #"\.$" "")
                      str/trim)]
       (when (seq clause)
         (clip (str (str/upper-case (subs clause 0 1)) (subs clause 1))
               limit))))))

(defn description-remainder
  "The description after its first sentence, or nil."
  [description]
  (when-let [text (non-blank-text description)]
    (some->> (str/split text sentence-boundary)
             rest
             seq
             (str/join ". ")
             non-blank-text)))

(defn- display-name-differs?
  [display-name base]
  (and (seq display-name)
       (not= (str/lower-case display-name)
             (str/lower-case (or base "")))))

(defn symbol-metadata
  "Everything the header and selector show about a HIP-3 market.

  Returns nil for coins that cannot carry annotations. `:subtitle` degrades
  from the parsed issuer, to the description's first clause, to the
  deployer's display name, to the deployer itself."
  [{:keys [coin concise detail-entry dex-full-name]}]
  (when (annotatable-coin? coin)
    (let [dex (coin-dex coin)
          base (coin-base coin)
          detail-status (:status detail-entry)
          detail (:annotation detail-entry)
          annotation (merge concise detail)
          description (:description annotation)
          category (:category annotation)
          display-name (:display-name annotation)
          issuer (issuer-from-description description)
          deployer (or (non-blank-text dex-full-name) dex)
          subtitle (or issuer
                       (description-first-clause description)
                       (when (display-name-differs? display-name base)
                         display-name))
          loading? (and (nil? subtitle)
                        (nil? category)
                        (contains? #{nil :loading} detail-status))]
      (cond-> {:coin coin
               :dex dex
               :deployer deployer
               :loading? loading?}
        subtitle (assoc :subtitle subtitle)
        issuer (assoc :issuer issuer)
        category (assoc :category category
                        :category-label (category-label category))
        description (assoc :description description)
        (:keywords annotation) (assoc :keywords (:keywords annotation))
        display-name (assoc :display-name display-name)
        (and (nil? subtitle) (not loading?))
        (assoc :fallback-label (str "Market by " deployer))))))

(defn row-descriptor
  "The short muted text a selector row shows after its chips, or nil."
  [{:keys [coin base] :as _market} concise detail-entry]
  (when (annotatable-coin? coin)
    (let [annotation (merge concise (:annotation detail-entry))]
      (or (issuer-from-description (:description annotation))
          (let [display-name (:display-name annotation)]
            (when (display-name-differs? display-name (or base (coin-base coin)))
              display-name))))))

(defn header-metadata
  "Header model for the active coin, read from app state."
  [state coin]
  (when (annotatable-coin? coin)
    (let [annotations (annotations-state state)]
      (symbol-metadata
       {:coin coin
        :concise (get-in annotations [:by-coin coin])
        :detail-entry (get-in annotations [:detail-by-coin coin])
        :dex-full-name (get-in state [:perp-dex-full-name-by-name (coin-dex coin)])}))))

(defn annotate-markets
  "Merge annotation fields the selector searches, filters and renders into
  catalog market rows. Markets without annotations are returned unchanged."
  [markets annotations]
  (let [{:keys [by-coin detail-by-coin]} (merge default-state annotations)]
    (if (and (empty? by-coin) (empty? detail-by-coin))
      markets
      (mapv (fn [market]
              (let [coin (:coin market)
                    concise (get by-coin coin)
                    detail-entry (get detail-by-coin coin)]
                (if (or concise detail-entry)
                  (let [annotation (merge concise (:annotation detail-entry))
                        descriptor (row-descriptor market concise detail-entry)]
                    (cond-> market
                      (:category annotation) (assoc :asset-class (:category annotation))
                      (:keywords annotation) (assoc :search-keywords (:keywords annotation))
                      (:display-name annotation) (assoc :display-name (:display-name annotation))
                      descriptor (assoc :descriptor descriptor)))
                  market)))
            markets))))
