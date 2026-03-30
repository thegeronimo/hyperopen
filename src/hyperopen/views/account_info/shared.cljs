(ns hyperopen.views.account-info.shared
  (:require ["lucide/dist/esm/icons/external-link.js" :default lucide-external-link-node]
            [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.account-info.projections :as projections]))

(def ^:private fallback-decimals
  2)

(def ^:private max-decimals
  8)

(defn parse-num [value]
  (fmt/safe-number value))

(defn parse-optional-num [value]
  (projections/parse-optional-num value))

(defn parse-optional-int [value]
  (projections/parse-optional-int value))

(defn title-case-label [value]
  (projections/title-case-label value))

(defn non-blank-text [value]
  (projections/non-blank-text value))

(defn parse-coin-namespace [coin]
  (projections/parse-coin-namespace coin))

(defn normalize-coin-search-query [value]
  (-> (or value "")
      str
      str/trim
      str/lower-case))

(defn compile-coin-search-query [value]
  (normalize-coin-search-query value))

(defn coin-search-query-blank? [compiled-query]
  (str/blank? (or compiled-query "")))

(defn normalized-coin-search-candidates [candidates]
  (->> (or candidates [])
       (map normalize-coin-search-query)
       (remove str/blank?)
       vec))

(defn- ordered-subsequence?
  [needle haystack]
  (let [needle* (or needle "")
        haystack* (or haystack "")
        needle-len (.-length needle*)
        haystack-len (.-length haystack*)]
    (loop [needle-idx 0
           haystack-idx 0]
      (cond
        (>= needle-idx needle-len) true
        (>= haystack-idx haystack-len) false
        (= (.charCodeAt needle* needle-idx)
           (.charCodeAt haystack* haystack-idx))
        (recur (inc needle-idx) (inc haystack-idx))
        :else
        (recur needle-idx (inc haystack-idx))))))

(defn- normalized-coin-matches-compiled-query?
  [normalized-coin compiled-query]
  (let [query (or compiled-query "")
        coin* (or normalized-coin "")]
    (or (coin-search-query-blank? query)
        (str/includes? coin* query)
        (ordered-subsequence? query coin*))))

(defn normalized-coin-candidates-match?
  [normalized-candidates compiled-query]
  (let [query (or compiled-query "")]
    (or (coin-search-query-blank? query)
        (boolean
         (some #(normalized-coin-matches-compiled-query? % query)
               (or normalized-candidates []))))))

(defn coin-matches-search?
  [coin search-query]
  (let [query (compile-coin-search-query search-query)
        coin* (normalize-coin-search-query coin)]
    (normalized-coin-matches-compiled-query? coin* query)))

(defn resolve-coin-display [coin market-by-key]
  (projections/resolve-coin-display coin market-by-key))

(def ^:private coin-select-control-classes
  ["inline-flex"
   "min-h-6"
   "min-w-0"
   "max-w-full"
   "items-center"
   "rounded"
   "focus:outline-none"
   "focus-visible:outline-none"
   "focus-visible:ring-2"
   "focus-visible:ring-trading-green/70"
   "focus-visible:ring-offset-1"
   "focus-visible:ring-offset-base-100"])

(defn coin-select-control
  ([coin content]
   (coin-select-control coin content {}))
  ([coin content {:keys [extra-classes style click-actions attrs]
                  :or {extra-classes []
                       attrs {}}}]
   (let [coin* (non-blank-text coin)
         classes (into coin-select-control-classes extra-classes)
         base-attrs (cond-> {:class classes}
                      style (assoc :style style))]
     (if (seq coin*)
       [:button (merge base-attrs
                       attrs
                       {:type "button"
                        :on {:click (or click-actions
                                        [[:actions/select-asset coin*]])}})
        content]
       [:span (merge base-attrs attrs)
        content]))))

(defn- lucide-node->hiccup [node]
  (let [tag-name (aget node 0)
        attrs (js->clj (aget node 1) :keywordize-keys true)]
    [(keyword tag-name) attrs]))

(defn external-link-icon
  ([] (external-link-icon ["h-3.5" "w-3.5" "shrink-0"]))
  ([class-names]
   (external-link-icon class-names {:stroke-width 2}))
  ([class-names {:keys [stroke-width]
                 :or {stroke-width 2}}]
   (into [:svg {:class class-names
                :viewBox "0 0 24 24"
                :fill "none"
                :stroke "currentColor"
                :stroke-width stroke-width
                :stroke-linecap "round"
                :stroke-linejoin "round"
                :aria-hidden true}]
         (map lucide-node->hiccup
              (array-seq lucide-external-link-node)))))

(defn format-currency [value]
  (fmt/format-fixed-number value fallback-decimals))

(defn format-trade-price [value]
  (if (or (nil? value) (= value "N/A"))
    "0.00"
    (let [num-val (js/parseFloat value)]
      (if (js/isNaN num-val)
        "0.00"
        (or (fmt/format-trade-price num-val value) "0.00")))))

(defn format-amount [value decimals]
  (let [safe-decimals (-> (or decimals fallback-decimals)
                          (max 0)
                          (min max-decimals))]
    (fmt/format-fixed-number value safe-decimals)))

(defn format-balance-amount [value decimals]
  (if decimals
    (format-amount value decimals)
    (format-currency value)))

(defn format-open-orders-time [ms]
  (fmt/format-local-date-time ms))

(defn format-funding-history-time [time-ms]
  (fmt/format-local-date-time time-ms))

(defn pnl-tone-class [pnl-value]
  (let [pnl-num (parse-num pnl-value)]
    (cond
      (pos? pnl-num) "text-success"
      (neg? pnl-num) "text-error"
      :else "text-trading-text")))

(defn format-pnl-text [pnl-value pnl-pct]
  (when (and (some? pnl-value) (some? pnl-pct))
    (let [pnl-num (parse-num pnl-value)
          pct-num (parse-num pnl-pct)]
      (str (if (pos? pnl-num) "+" "")
           "$" (format-currency pnl-num)
           " (" (if (pos? pct-num) "+" "") (.toFixed pct-num 2) "%)"))))

(defn format-pnl [pnl-value pnl-pct]
  (if-let [pnl-text (format-pnl-text pnl-value pnl-pct)]
    [:span {:class [(pnl-tone-class pnl-value) "num"]}
     pnl-text]
    [:span.text-trading-text "--"]))

(def position-chip-classes
  ["px-3"
   "py-[1px]"
   "text-xs"
   "leading-none"
   "font-medium"
   "rounded-lg"
   "border"
   "bg-[#242924]"
   "text-emerald-300"
   "border-[#273035]"])

(def position-short-chip-classes
  ["px-3"
   "py-[1px]"
   "text-xs"
   "leading-none"
   "font-medium"
   "rounded-lg"
   "border"
   "bg-[#242924]"
   "text-red-300"
   "border-[#273035]"])

(defn position-chip-classes-for-side [side]
  (case side
    :short position-short-chip-classes
    position-chip-classes))

(def position-coin-cell-style
  {:background "linear-gradient(90deg, rgb(31, 166, 125) 0px, rgb(31, 166, 125) 4px, rgb(11, 50, 38) 4px, transparent 100%) transparent"
   :padding-left "12px"})

(def position-short-coin-cell-style
  {:background "transparent linear-gradient(90deg, rgb(237, 112, 136) 0px, rgb(237, 112, 136) 4px, rgba(52, 36, 46, 1) 0%, transparent 100%)"
   :padding-left "12px"})

(defn position-coin-cell-style-for-side [side]
  (case side
    :short position-short-coin-cell-style
    position-coin-cell-style))

(defn position-side-tone-class [side]
  (case side
    :short "text-red-300"
    :long "text-emerald-300"
    "text-trading-text"))

(defn position-side-size-class [side]
  (case side
    :short "text-error"
    :long "text-success"
    "text-trading-text"))

(def positions-grid-template-class
  "grid-cols-[minmax(180px,2.15fr)_minmax(142px,1.34fr)_minmax(94px,0.9fr)_minmax(94px,0.9fr)_minmax(94px,0.9fr)_minmax(114px,1.06fr)_minmax(88px,0.82fr)_minmax(124px,1.08fr)_minmax(80px,0.78fr)_minmax(94px,0.8fr)_minmax(146px,1.06fr)]")

(def positions-grid-min-width-class
  "min-w-[1335px]")
