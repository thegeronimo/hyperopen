(ns hyperopen.portfolio.optimizer.application.universe-duplicates
  "Same-underlying detection for the Universe panel.

  The catalog lists one stock several times — NVDA-USDC on one HIP-3 dex,
  NVDA-USDH on another, NVDA/USDC as spot — and in a long hand-built universe a
  trader can add a second listing without noticing the first. Optimizing both
  splits one bet across two near-perfectly correlated lines, which makes the
  weights unstable. This namespace answers \"is this the same asset as
  something already in the mix?\" and projects that answer for the search
  results, the add-all batch, the inline confirm and the universe list.

  The rule is deliberately strict: two markets are siblings only when their
  UNDERLYING symbols are equal after the venue prefix and quote token are
  dropped. Plain prefix overlap (NVDA vs NVDL) never matches — a warning that
  fires on look-alikes would be ignored. Vaults never match anything."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(def ^:private normalized-text coercion/non-blank-text)

(def ^:private matchable-market-types
  #{:perp :spot})

(defn- strip-venue-prefix
  "xyz:NVDA -> NVDA. HIP-3 coins carry their dex as a `dex:` prefix."
  [text]
  (if (str/includes? text ":")
    (last (str/split text #":"))
    text))

(defn- strip-quote
  "NVDA-USDC / NVDA/USDC -> NVDA."
  [text]
  (first (str/split text #"[-/]" 2)))

(defn- raw-asset-id?
  [text]
  (boolean (or (str/starts-with? text "@")
               (re-matches #"\d+" text))))

(defn underlying-key
  "The upper-case underlying symbol of a market or universe instrument, or nil
  when it has none worth comparing (vaults, raw `@123` spot ids with no base)."
  [market-or-instrument]
  (let [market-type (coercion/normalize-keyword-like
                     (:market-type market-or-instrument))]
    (when (contains? matchable-market-types market-type)
      (let [base (normalized-text (:base market-or-instrument))
            symbol (normalized-text (:symbol market-or-instrument))
            coin (normalized-text (:coin market-or-instrument))
            raw (or base
                    (some-> symbol strip-venue-prefix strip-quote)
                    (when (and coin (not (raw-asset-id? coin)))
                      (-> coin strip-venue-prefix strip-quote)))]
        (some-> raw strip-venue-prefix normalized-text str/upper-case)))))

(defn instrument-label
  "The label a trader recognises for a universe instrument: its symbol, falling
  back to its coin."
  [instrument]
  (or (normalized-text (:symbol instrument))
      (some-> (normalized-text (:coin instrument)) strip-venue-prefix)
      (:instrument-id instrument)))

(defn venue-label
  "The HIP-3 dex, or the market type when the listing has no dex."
  [market-or-instrument]
  (or (normalized-text (:dex market-or-instrument))
      (let [coin (normalized-text (:coin market-or-instrument))]
        (when (and coin (str/includes? coin ":"))
          (first (str/split coin #":"))))
      (some-> (:market-type market-or-instrument)
              coercion/normalize-keyword-like
              name)))

(defn venue-qualified-label
  "\"NVDA-USDC · xyz\" for a HIP-3 listing, so two listings that share a symbol
  on different dexes stay tellable apart; plain label otherwise."
  [market-or-instrument label]
  (if-let [dex (normalized-text (:dex market-or-instrument))]
    (str label " · " dex)
    label))

(defn universe-index
  "{underlying [instrument ...]} in universe order, matchable instruments only."
  [universe]
  (reduce (fn [acc instrument]
            (if-let [k (underlying-key instrument)]
              (update acc k (fnil conj []) instrument)
              acc))
          {}
          universe))

(defn existing-sibling
  "The first universe instrument that is the same underlying as `market` but a
  different listing, or nil."
  [index market]
  (when-let [k (underlying-key market)]
    (some (fn [instrument]
            (when (not= (:instrument-id instrument) (:key market))
              instrument))
          (get index k))))

(defn- acknowledged-set
  [state]
  (set (get-in state contracts/ui-universe-duplicate-acknowledged-path)))

(defn acknowledged?
  "True once the trader chose \"Add both\" for this underlying this session; the
  prompt is asked once per underlying, not on every add."
  [state underlying]
  (contains? (acknowledged-set state) underlying))

(defn prompt-market-key
  [state]
  (normalized-text (get-in state contracts/ui-universe-duplicate-prompt-path)))

(defn needs-confirm?
  "Should adding `market` stop and ask first?"
  [state universe market]
  (let [sibling (existing-sibling (universe-index universe) market)]
    (boolean
     (and sibling
          (not (acknowledged? state (underlying-key market)))))))

;; ---------------------------------------------------------------------------
;; Search projection

(defn- quote-label
  [market-or-instrument]
  (some-> (normalized-text (:quote market-or-instrument)) str/upper-case))

(defn- prompt-model
  [market existing]
  {:market-key (:key market)
   :existing-id (:instrument-id existing)
   :existing-label (venue-qualified-label existing (instrument-label existing))
   :existing-symbol (instrument-label existing)
   :adding-label (venue-qualified-label market
                                        (or (normalized-text (:symbol market))
                                            (normalized-text (:coin market))))
   :adding-symbol (or (normalized-text (:symbol market))
                      (normalized-text (:coin market)))
   :existing-venue (venue-label existing)
   :adding-venue (venue-label market)
   :existing-quote (quote-label existing)
   :adding-quote (quote-label market)})

(defn annotate-candidate-rows
  "Mark each candidate row that duplicates something already in the mix, and
  open the inline confirm on the row the trader is being asked about."
  [state universe rows]
  (let [index (universe-index universe)
        prompt-key (prompt-market-key state)]
    (mapv (fn [{:keys [market market-key] :as row}]
            (if-let [existing (existing-sibling index market)]
              (cond-> (assoc row
                             :duplicate-of-id (:instrument-id existing)
                             :duplicate-of-label (venue-qualified-label
                                                  existing
                                                  (instrument-label existing)))
                (= prompt-key market-key)
                (assoc :duplicate-prompt (prompt-model market existing)))
              row))
          rows)))

(defn search-summary
  "Banner + count data for the result list: which mix members the current
  results duplicate. Nil when nothing in the results duplicates the mix."
  [rows]
  (let [pairs (->> rows
                   (keep (fn [{:keys [duplicate-of-id duplicate-of-label market]}]
                           (when duplicate-of-id
                             {:existing-id duplicate-of-id
                              :existing-label duplicate-of-label
                              :underlying (underlying-key market)})))
                   (reduce (fn [acc pair]
                             (if (some #(= (:existing-id pair) (:existing-id %)) acc)
                               acc
                               (conj acc pair)))
                           []))]
    (when (seq pairs)
      {:existing pairs
       :existing-ids (into #{} (map :existing-id) pairs)
       :duplicate-row-count (count (filter :duplicate-of-id rows))})))

(defn addable-market-keys
  "The add-all batch with duplicates removed: rows that duplicate the mix are
  skipped, and among the rest only the first listing of each underlying is
  kept, so \"add all\" on `NVDA` cannot add four NVDAs."
  [rows]
  (:keys
   (reduce (fn [{:keys [seen] :as acc} {:keys [market market-key duplicate-of-id]}]
             (let [k (underlying-key market)]
               (cond
                 duplicate-of-id acc
                 (and k (contains? seen k)) acc
                 :else (cond-> (update acc :keys conj market-key)
                         k (update :seen conj k)))))
           {:seen #{} :keys []}
           rows)))

(defn skipped-suffix
  "Appended to the add-all label so the batch says what it left out."
  [skipped-count]
  (when (pos? skipped-count)
    (str " · skips " skipped-count
         (if (= 1 skipped-count) " duplicate" " duplicates"))))

(defn skip-same-underlying
  "Drop batch instruments that duplicate an underlying already in the universe
  or earlier in the batch — the add-all twin of the single-add confirm."
  [universe instruments]
  (:items
   (reduce (fn [{:keys [seen] :as acc} instrument]
             (let [k (underlying-key instrument)]
               (if (and k (contains? seen k))
                 acc
                 (cond-> (update acc :items conj instrument)
                   k (update :seen conj k)))))
           {:seen (set (keys (universe-index universe)))
            :items []}
           instruments)))

;; ---------------------------------------------------------------------------
;; Universe-list projection

(def duplicate-warning-code
  :duplicate-underlying)

(defn- position-side
  [instrument]
  (if (= :short (coercion/normalize-keyword-like (:position-side instrument)))
    :short
    :long))

(defn twin-groups
  "{underlying [instrument-id ...]} for every underlying held more than once."
  [universe]
  (into {}
        (keep (fn [[k instruments]]
                (when (< 1 (count instruments))
                  [k (mapv :instrument-id instruments)])))
        (universe-index universe)))

(defn group-selected-rows
  "Re-order the universe rows so every twin sits directly under the first
  listing of its underlying, and tag the rows of each group. Rows outside a
  group keep their order."
  [selected-rows twins]
  (if (empty? twins)
    selected-rows
    (let [id->k (into {}
                      (mapcat (fn [[k ids]] (map (fn [id] [id k]) ids)))
                      twins)
          rows-by-k (group-by #(id->k (:instrument-id %)) selected-rows)]
      (:rows
       (reduce (fn [{:keys [emitted] :as acc} row]
                 (let [k (id->k (:instrument-id row))]
                   (cond
                     (nil? k) (update acc :rows conj row)
                     (contains? emitted k) acc
                     :else
                     (let [group (get rows-by-k k)
                           n (count group)]
                       (-> acc
                           (update :emitted conj k)
                           (update :rows into
                                   (map-indexed
                                    (fn [i r]
                                      (assoc r
                                             :twin-underlying k
                                             :twin-count n
                                             :twin-first? (zero? i)
                                             :twin-last? (= i (dec n))))
                                    group)))))))
               {:emitted #{} :rows []}
               selected-rows)))))

(defn duplicate-warnings
  "One readiness caution per underlying held as more than one listing ON THE
  SAME SIDE. Long spot against short perp is a deliberate hedge, not an
  accident, so opposite sides stay quiet here (the list still groups them).
  Keyed to the SECOND listing, so the Data health asset list names the one most
  likely added by mistake."
  [universe]
  (into []
        (keep (fn [[k instruments]]
                (when (and (< 1 (count instruments))
                           (apply = (map position-side instruments)))
                  {:code duplicate-warning-code
                   :instrument-id (:instrument-id (second instruments))
                   :underlying k
                   :message (str k " is in the universe "
                                 (count instruments) " times ("
                                 (str/join ", " (map instrument-label instruments))
                                 ")")})))
        (sort-by key (universe-index universe))))
