(ns hyperopen.portfolio.optimizer.application.view-model.universe-search
  "Projection for the Universe search results list.

  The panel used to render at most six candidates with no sign that more
  existed, and clipped every symbol to a fixed column so PUMP-USDC, PUMP/USDE
  and PUMP/USDH were the same row visually. This namespace owns the replacement:
  the type/quote facets, the type-grouped render order, the match highlighting,
  and the honest counts.

  It lives beside view-model.universe rather than inside it because that
  namespace is at its size cap."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]
            [hyperopen.portfolio.optimizer.coercion :as coercion]))

(def ^:private normalized-text coercion/non-blank-text)

;; Render order for the grouped list. Perps lead because a perp is what the
;; trader is usually reaching for and it used to sort below eight spot pairs and
;; fall under the six-row cut.
(def group-order
  [{:key :perp :label "Perps"}
   {:key :spot :label "Spot"}
   {:key :vault :label "Vaults"}])

;; What the Universe panel asks candidate-markets for.
;;
;; The defect being fixed is SILENT truncation, not truncation itself: a realistic
;; query ("pump" -> 13 markets) must show every match, and anything beyond the cut
;; must be visibly counted and reachable through the facets. This is deliberately
;; not unbounded — the rows are rendered, not virtualized, and a one- or two-
;; character query matches most of a ~9,500-row vault index, which re-renders on
;; every keystroke inside an already busy route. 25 covers realistic queries
;; whole (the motivating "pump" case is 13), and the footer states the remainder
;; rather than hiding it.
(def panel-candidate-limit
  25)

(def type-filter-values
  #{:all :perp :spot :vault})

(defn normalize-type-filter
  [value]
  (let [value* (coercion/normalize-keyword-like value)]
    (if (contains? type-filter-values value*)
      value*
      :all)))

(defn normalize-quote-filter
  "The quote facet is a token string (USDC / USDH / USDT0 / a HIP-3 dex's
  collateral) or :all. It is NOT a closed set — the live catalog carries at
  least four spot quotes and every HIP-3 dex contributes its own collateral
  token — so anything non-blank is accepted and simply matches nothing if it
  has gone stale."
  [value]
  ;; Keywords must be unwrapped with `name` BEFORE any text coercion: the shared
  ;; non-blank-text helper stringifies :all to \":all\", which would sail through
  ;; as a quote token of \":ALL\" and silently match nothing — an empty result
  ;; list on a query with hundreds of hits. The stored default IS the keyword.
  (let [text (if (keyword? value)
               (name value)
               (normalized-text value))]
    (cond
      (not (seq text)) :all
      (= "all" (str/lower-case text)) :all
      :else (str/upper-case text))))

(defn- market-quote-upper
  [market]
  (some-> (universe-candidates/market-quote market) str/upper-case))

(defn- matches-type?
  [type-filter market]
  (or (= :all type-filter)
      (= type-filter (:market-type market))))

(defn- matches-quote?
  [quote-filter market]
  (or (= :all quote-filter)
      (= quote-filter (market-quote-upper market))))

(defn highlight
  "Split `text` around the first case-insensitive occurrence of `query`.

  Returns {:pre :match :post}. A blank query, or a query that does not occur in
  this particular field, yields the whole string as :pre with an empty :match —
  the caller renders all three parts unconditionally, so a row still shows its
  text when only its OTHER field matched. (The candidate filter searches several
  fields, so a visible row need not contain the query in either rendered one.)"
  [text query]
  (let [text* (str (or text ""))
        query* (normalized-text query)
        idx (when query*
              (str/index-of (str/lower-case text*)
                            (str/lower-case query*)))]
    (if (nil? idx)
      {:pre text* :match "" :post ""}
      {:pre (subs text* 0 idx)
       :match (subs text* idx (+ idx (count query*)))
       :post (subs text* (+ idx (count query*)))})))

(defn- symbol-lead
  "The symbol up to and including its quote separator, so the quote token can be
  rendered as its own pill without the two halves losing the separator between
  them. Keeping the separator on this side means the row's textContent still
  concatenates to the full `ETH-USDC` / `PUMP/USDC` symbol."
  [label quote-label market-type]
  (let [separator (case market-type
                    :perp "-"
                    :spot "/"
                    nil)]
    (if (and quote-label
             separator
             (str/ends-with? (str label) (str separator quote-label)))
      (subs (str label)
            0
            (- (count (str label)) (count quote-label)))
      (str label))))

(defn- type-count
  [markets market-type]
  (count (filter #(= market-type (:market-type %)) markets)))

(defn type-chips
  "Type facet. Counts are taken over the QUERY match set, before the type filter
  is applied, so the chips keep saying what is available rather than collapsing
  to the current selection."
  [query-markets type-filter]
  (into [{:key :all
          :label "All"
          :count (count query-markets)
          :active? (= :all type-filter)}]
        (map (fn [{:keys [key label]}]
               {:key key
                :label label
                :count (type-count query-markets key)
                :active? (= key type-filter)}))
        group-order))

(defn quote-chips
  "Quote facet, derived from the data rather than hard-coded. The catalog
  carries USDC, USDH, USDT0 and USDE on spot alone, and every HIP-3 perp dex
  contributes its own collateral token, so a fixed any/USDC/USDE/USDH set would
  drop rows into no bucket. Vaults have no quote and are excluded from the
  counts entirely rather than being given a fabricated one."
  [query-markets type-filter quote-filter]
  (let [in-scope (filterv #(matches-type? type-filter %) query-markets)
        tokens (->> in-scope
                    (keep market-quote-upper)
                    frequencies)]
    (into [{:key :all
            :label "Any"
            :count (count in-scope)
            :active? (= :all quote-filter)}]
          (map (fn [[token n]]
                 {:key token
                  :label token
                  :count n
                  :active? (= token quote-filter)}))
          ;; Most-populated first, then alphabetical, so the chip row is stable
          ;; across keystrokes instead of reshuffling on every render.
          (sort-by (fn [[token n]] [(- n) token]) tokens))))

(defn filter-markets
  "Apply the facets and, when `group?`, re-order into the grouped render order.
  This vector is the single source of truth for what the user sees, which row
  the keyboard cursor is on, and which market-keys the keydown handler indexes
  into — those three MUST agree or ArrowDown+Enter adds a different asset than
  the one highlighted.

  Grouping is opt-in because the results-page \"Add to universe\" popover shares
  this projection and is asserted on the ranked (volume/TVL) order."
  [query-markets type-filter quote-filter group?]
  (let [matching (filterv #(and (matches-type? type-filter %)
                                (matches-quote? quote-filter %))
                          query-markets)]
    (if-not group?
      matching
      (let [by-type (group-by :market-type matching)]
        (into []
              (mapcat (fn [{:keys [key]}] (get by-type key)))
              group-order)))))

(defn groups
  "Group the rendered rows for the sticky group headers. Each row keeps the flat
  index it was given in render order."
  [rows]
  (let [by-type (group-by :market-type rows)]
    (into []
          (keep (fn [{:keys [key label]}]
                  (when-let [group-rows (seq (get by-type key))]
                    {:key key
                     :label label
                     :count (count group-rows)
                     :rows (vec group-rows)})))
          group-order)))

(defn row-search-fields
  "The per-row search presentation: the symbol split around its quote token, and
  the highlight segments for both rendered fields."
  [{:keys [market label name]} query]
  (let [quote-label (some-> (universe-candidates/market-quote market)
                            str/upper-case)
        lead (symbol-lead label quote-label (:market-type market))]
    {:symbol-segments (highlight lead query)
     :name-segments (highlight name query)
     :quote-label quote-label
     ;; The name line is context, and it earns its row only when it ADDS
     ;; something. Vault candidates set :name and :symbol to the same string, and
     ;; a market with no catalog name falls back to echoing its own base, so
     ;; "PUMP" would print under "PUMP/USDC". Suppress both.
     :duplicate-name? (let [name* (str/lower-case (str name))
                            base (str/lower-case
                                  (str/replace (str lead) #"[-/]$" ""))]
                        (or (= name* (str/lower-case (str label)))
                            (= name* base)))}))

(defn match-label
  [n]
  (str n (if (= 1 n) " hit" " hits")))

(defn footer-label
  [shown total]
  (if (= shown total)
    (str total (if (= 1 total) " match" " matches"))
    (str shown " of " total " matches shown")))

(defn group-count-label
  [n]
  (str n (if (= 1 n) " market" " markets")))

;; The design's footer offers "add all N matches". N is unbounded — a
;; two-character query matches hundreds of instruments and every one of them
;; joins the history request — so the batch is capped and the label always states
;; the number that will actually be added.
(def add-all-limit
  20)

(defn add-all-label
  [shown]
  (cond
    (zero? shown) nil
    (<= shown add-all-limit) (str "+ add all " shown)
    :else (str "+ add first " add-all-limit)))
