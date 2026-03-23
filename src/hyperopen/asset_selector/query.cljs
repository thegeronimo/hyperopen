(ns hyperopen.asset-selector.query
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.list-metrics :as list-metrics]
            [hyperopen.utils.formatting :as fmt]))

(def ^:private asset-list-default-render-limit
  list-metrics/default-render-limit)

(def ^:private asset-list-row-height-px
  list-metrics/row-height-px)

(def ^:private asset-list-viewport-height-px
  list-metrics/viewport-height-px)

(def ^:private asset-list-overscan-rows
  12)

(defn- parse-cache-order [value]
  (let [parsed (cond
                 (number? value) value
                 (string? value) (js/parseInt value 10)
                 :else js/NaN)]
    (when (and (number? parsed)
               (not (js/isNaN parsed)))
      (js/Math.floor parsed))))

(defn- safe-sort-number [value]
  (let [n (fmt/safe-number value)]
    (if (js/isNaN n) 0 n)))

(defn- sort-token [value]
  (str/lower-case (or (some-> value str str/trim) "")))

(defn- market-primary-sort-rank [sort-key asset]
  (case sort-key
    :name (sort-token (:symbol asset))
    :price (safe-sort-number (:mark asset))
    :volume (safe-sort-number (:volume24h asset))
    :change (safe-sort-number (:change24hPct asset))
    :openInterest (safe-sort-number (:openInterest asset))
    :funding (safe-sort-number (:fundingRate asset))
    (safe-sort-number (:volume24h asset))))

(defn- market-fallback-sort-rank [asset]
  [(or (parse-cache-order (:cache-order asset))
       js/Number.MAX_SAFE_INTEGER)
   (sort-token (:symbol asset))
   (sort-token (:coin asset))
   (sort-token (:key asset))])

(defn- compare-markets [sort-key sort-direction a b]
  (let [primary-cmp (compare (market-primary-sort-rank sort-key a)
                             (market-primary-sort-rank sort-key b))
        directional-primary (if (= :desc sort-direction)
                              (- primary-cmp)
                              primary-cmp)]
    (if (zero? directional-primary)
      (compare (market-fallback-sort-rank a)
               (market-fallback-sort-rank b))
      directional-primary)))

(defn matches-search?
  [asset search-term strict?]
  (let [query (str/lower-case (or search-term ""))
        symbol (str/lower-case (or (:symbol asset) ""))
        coin (str/lower-case (or (:coin asset) ""))
        base (str/lower-case (or (:base asset) ""))]
    (if strict?
      (or (str/starts-with? symbol query)
          (str/starts-with? coin query)
          (str/starts-with? base query))
      (or (str/includes? symbol query)
          (str/includes? coin query)
          (str/includes? base query)))))

(defn- hip3-tab-eligible?
  [asset strict?]
  (if strict?
    (if (contains? asset :hip3-eligible?)
      (true? (:hip3-eligible? asset))
      true)
    true))

(defn- perps-tab-eligible?
  [asset strict?]
  (if strict?
    (or (not (:hip3? asset))
        (hip3-tab-eligible? asset strict?))
    true))

(defn tab-match? [asset active-tab strict?]
  (case active-tab
    :all true
    :perps (and (= :perp (:market-type asset))
                (perps-tab-eligible? asset strict?))
    :spot (= :spot (:market-type asset))
    :crypto (and (= :perp (:market-type asset)) (= :crypto (:category asset)))
    :tradfi (and (= :perp (:market-type asset)) (= :tradfi (:category asset)))
    :hip3 (and (= :perp (:market-type asset))
               (:hip3? asset)
               (hip3-tab-eligible? asset strict?))
    true))

(defn filter-and-sort-assets
  [assets search-term sort-key sort-direction favorites favorites-only? strict? active-tab]
  (let [filtered-assets (->> assets
                             (filter #(tab-match? % active-tab strict?))
                             (filter (fn [asset]
                                       (if favorites-only?
                                         (contains? favorites (:key asset))
                                         true)))
                             (filter (fn [asset]
                                       (if (and search-term (not (str/blank? search-term)))
                                         (matches-search? asset search-term strict?)
                                         true))))
        comparator (fn [a b]
                     (neg? (compare-markets sort-key sort-direction a b)))]
    (vec (sort comparator filtered-assets))))

(defn normalize-render-limit
  [render-limit total]
  (let [candidate (parse-cache-order render-limit)
        default-limit (min total asset-list-default-render-limit)]
    (if (and (number? candidate)
             (not (js/isNaN candidate)))
      (-> candidate
          (max 1)
          (min total))
      default-limit)))

(defn normalize-scroll-top [scroll-top]
  (max 0 (or (parse-cache-order scroll-top) 0)))

(defn virtual-window
  ([limit scroll-top]
   (virtual-window limit scroll-top asset-list-overscan-rows))
  ([limit scroll-top overscan-rows]
   (let [overscan-rows* (let [candidate (parse-cache-order overscan-rows)]
                          (if (number? candidate)
                            (max 0 candidate)
                            asset-list-overscan-rows))
         rows-in-view (-> (/ asset-list-viewport-height-px asset-list-row-height-px)
                          js/Math.ceil
                          int)
         window-size (+ rows-in-view (* 2 overscan-rows*))
         first-visible-row (-> (/ scroll-top asset-list-row-height-px)
                               js/Math.floor
                               int)
         start-index (-> first-visible-row
                         (- overscan-rows*)
                         (max 0)
                         (min limit))
         end-index (-> (+ start-index window-size)
                       (min limit)
                       (max start-index))
         top-spacer-px (* start-index asset-list-row-height-px)
         bottom-spacer-px (* (- limit end-index) asset-list-row-height-px)]
     {:start-index start-index
      :end-index end-index
      :top-spacer-px top-spacer-px
      :bottom-spacer-px bottom-spacer-px})))

(defn selector-visible-market-coins
  [state]
  (let [selector-state (:asset-selector state)]
    (if-not (= :asset-selector (:visible-dropdown selector-state))
      #{}
      (let [markets (get selector-state :markets [])
            processed-assets (filter-and-sort-assets
                              markets
                              (:search-term selector-state "")
                              (:sort-by selector-state :volume)
                              (:sort-direction selector-state :desc)
                              (:favorites selector-state #{})
                              (:favorites-only? selector-state false)
                              (:strict? selector-state false)
                              (:active-tab selector-state :all))
            total (count processed-assets)]
        (if (zero? total)
          #{}
          (let [limit (normalize-render-limit (:render-limit selector-state) total)
                scroll-top (normalize-scroll-top (:scroll-top selector-state))
                {:keys [start-index end-index]} (virtual-window limit scroll-top)]
            (->> (subvec processed-assets start-index end-index)
                 (keep :coin)
                 set)))))))
