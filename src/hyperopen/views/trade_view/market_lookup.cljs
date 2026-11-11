(ns hyperopen.views.trade-view.market-lookup
  "The selector catalog as the account panels and the chart see it.

   Outcome sides carry their live best bid/ask inside the catalog (the list,
   header and ticket read the one chance rule from there), so every quote
   batch replaces `market-by-key`. The account tables, Account Equity and the
   chart read the catalog for names, marks, decimals and fill matching —
   never for a side's bid/ask — yet their memoized panels compared the
   catalog by value and re-rendered (rebuilding balance rows and outcome
   lookups) on every quote batch. `stable-market-by-key` hands them the same
   catalog value until something other than an outcome side's bid/ask
   changes.")

(defn- quote-free-sides
  [sides]
  (mapv #(dissoc % :bid :ask) sides))

(defn- quote-free
  "An outcome market without its sides' top of book."
  [market]
  (cond-> (update market :outcome-sides quote-free-sides)
    (seq (:question-options market))
    (update :question-options
            (fn [options] (mapv #(update % :sides quote-free-sides) options)))))

(defn- quote-only-change?
  [old-market new-market]
  (and (= :outcome (:market-type old-market))
       (= :outcome (:market-type new-market))
       (= (quote-free old-market) (quote-free new-market))))

(defn quotes-only-difference?
  "True when catalogs `a` and `b` hold the same keys and every entry is
   identical or an outcome market whose only difference is its sides'
   bid/ask. Pointer comparisons for untouched entries; a value comparison
   only for the few outcome markets a quote batch replaced."
  [a b]
  (and (map? a)
       (map? b)
       (= (count a) (count b))
       (reduce-kv (fn [_ market-key new-market]
                    (let [old-market (get a market-key ::missing)]
                      (if (or (identical? old-market new-market)
                              (and (map? old-market)
                                   (quote-only-change? old-market new-market)))
                        true
                        (reduced false))))
                  true
                  b)))

(defonce ^:private cache
  ;; {:source <last catalog seen> :stable <catalog handed out>}
  (atom nil))

(defn stable-market-by-key
  "`market-by-key`, or the catalog handed out last time when the two differ
   only in outcome sides' bid/ask (see the namespace doc)."
  [market-by-key]
  (let [{:keys [source stable]} @cache]
    (cond
      (identical? market-by-key source)
      stable

      (and (some? stable) (quotes-only-difference? source market-by-key))
      (do (reset! cache {:source market-by-key :stable stable})
          stable)

      :else
      (do (reset! cache {:source market-by-key :stable market-by-key})
          market-by-key))))

(defonce ^:private active-market-cache
  ;; {:source <last active market seen> :stable <active market handed out>}
  (atom nil))

(defn stable-active-market
  "`active-market`, or the one handed out last time when the two are the
   same outcome market but for its sides' bid/ask. The chart reads names,
   dex, type and decimals from it, never a side's quote, so a quote batch for
   the open event must not hand it a new object (header and ticket keep the
   live one)."
  [active-market]
  (let [{:keys [source stable]} @active-market-cache]
    (cond
      (identical? active-market source)
      stable

      (and (map? stable) (map? active-market) (quote-only-change? source active-market))
      (do (reset! active-market-cache {:source active-market :stable stable})
          stable)

      :else
      (do (reset! active-market-cache {:source active-market :stable active-market})
          active-market))))

(defn reset-cache!
  "Tests only."
  []
  (reset! cache nil)
  (reset! active-market-cache nil))
