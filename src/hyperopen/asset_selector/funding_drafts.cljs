(ns hyperopen.asset-selector.funding-drafts
  (:require [clojure.string :as str]
            [hyperopen.active-asset.funding-policy :as funding-policy]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.utils.parse :as parse-utils]))

(def ^:private funding-hypothetical-default-value
  1000)

(defn- parse-finite-number
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (let [text (str/trim value)]
                                (if (seq text)
                                  (js/Number text)
                                  js/NaN))
              :else js/NaN)]
    (when (and (number? num)
               (js/isFinite num))
      num)))

(defn- normalize-decimal-input
  [value]
  (-> (str (or value ""))
      (str/replace #"\$" "")
      str/trim))

(defn- parse-decimal-input
  ([value]
   (parse-decimal-input value nil))
  ([value locale]
   (parse-utils/parse-localized-currency-decimal
    (normalize-decimal-input value)
    locale)))

(defn- normalize-coin-key
  [coin]
  (let [text (some-> coin str str/trim)]
    (when (seq text)
      (str/upper-case text))))

(defn- active-related-market
  [state]
  (let [active-asset (:active-asset state)
        active-market (:active-market state)
        market-by-key (get-in state [:asset-selector :market-by-key] {})]
    (cond
      (markets/market-matches-coin? active-market active-asset)
      active-market

      (seq active-asset)
      (markets/resolve-or-infer-market-by-coin market-by-key active-asset)

      :else
      nil)))

(defn- active-related-coins
  [state]
  (markets/coin-aliases (:active-asset state)
                        (active-related-market state)))

(defn- active-tooltip-id->coin
  [state]
  (into {}
        (map (fn [coin]
               [(funding-policy/funding-tooltip-pin-id coin) coin]))
        (active-related-coins state)))

(defn- format-fixed
  [value digits]
  (if (and (number? value)
           (js/isFinite value))
    (.toFixed value digits)
    ""))

(defn- default-hypothetical-size-input
  [mark]
  (let [mark* (parse-finite-number mark)]
    (when (and (number? mark*)
               (pos? mark*))
      (format-fixed (/ funding-hypothetical-default-value mark*) 4))))

(defn- default-hypothetical-entry
  [mark]
  {:size-input (or (default-hypothetical-size-input mark) "")
   :value-input (format-fixed funding-hypothetical-default-value 2)})

(defn- hypothetical-entry
  [state coin mark]
  (let [stored (get-in state [:funding-ui :hypothetical-position-by-coin coin])]
    (merge (default-hypothetical-entry mark)
           (if (map? stored) stored {}))))

(defn- normalized-hypothetical-entry
  [entry]
  (let [entry* (if (map? entry) entry {})]
    (cond-> {}
      (contains? entry* :size-input)
      (assoc :size-input (normalize-decimal-input (:size-input entry*)))

      (contains? entry* :value-input)
      (assoc :value-input (normalize-decimal-input (:value-input entry*))))))

(defn- cleared-active-funding-drafts
  [state next-visible-id next-pinned-id]
  (let [active-coins (keep normalize-coin-key (active-related-coins state))
        active-tooltip-ids (set (keys (active-tooltip-id->coin state)))
        by-coin (or (get-in state [:funding-ui :hypothetical-position-by-coin]) {})]
    (when (and (seq active-coins)
               (some #(contains? by-coin %) active-coins)
               (not (contains? active-tooltip-ids next-visible-id))
               (not (contains? active-tooltip-ids next-pinned-id)))
      (apply dissoc by-coin active-coins))))

(defn set-funding-tooltip-visible
  [state tooltip-id visible?]
  (let [tooltip-id* (some-> tooltip-id str str/trim)
        current-visible-id (get-in state [:funding-ui :tooltip :visible-id])
        current-pinned-id (get-in state [:funding-ui :tooltip :pinned-id])
        tooltip-id->coin (active-tooltip-id->coin state)
        next-visible-id (cond
                          (and (true? visible?) (seq tooltip-id*)) tooltip-id*
                          (= current-visible-id tooltip-id*) nil
                          :else current-visible-id)
        next-by-coin (cleared-active-funding-drafts state
                                                    next-visible-id
                                                    current-pinned-id)
        sync-coin (get tooltip-id->coin next-visible-id)]
    (if (and (= current-visible-id next-visible-id)
             (nil? next-by-coin))
      []
      (cond-> [(if next-by-coin
                 [:effects/save-many [[[:funding-ui :tooltip :visible-id] next-visible-id]
                                      [[:funding-ui :hypothetical-position-by-coin] next-by-coin]]]
                 [:effects/save [:funding-ui :tooltip :visible-id] next-visible-id])]
        (and (true? visible?)
             (seq sync-coin))
        (conj [:effects/sync-active-asset-funding-predictability sync-coin])))))

(defn set-funding-tooltip-pinned
  [state tooltip-id pinned?]
  (let [tooltip-id* (some-> tooltip-id str str/trim)
        current-pinned-id (get-in state [:funding-ui :tooltip :pinned-id])
        current-visible-id (get-in state [:funding-ui :tooltip :visible-id])
        next-pinned-id (cond
                         (and (true? pinned?) (seq tooltip-id*)) tooltip-id*
                         (= current-pinned-id tooltip-id*) nil
                         :else current-pinned-id)
        next-by-coin (cleared-active-funding-drafts state
                                                    current-visible-id
                                                    next-pinned-id)]
    (if (and (= current-pinned-id next-pinned-id)
             (nil? next-by-coin))
      []
      [(if next-by-coin
         [:effects/save-many [[[:funding-ui :tooltip :pinned-id] next-pinned-id]
                              [[:funding-ui :hypothetical-position-by-coin] next-by-coin]]]
         [:effects/save [:funding-ui :tooltip :pinned-id] next-pinned-id])])))

(defn enter-funding-hypothetical-position
  [state coin mark entry]
  (if-let [coin* (normalize-coin-key coin)]
    (let [by-coin (or (get-in state [:funding-ui :hypothetical-position-by-coin]) {})
          next-entry (merge (default-hypothetical-entry mark)
                            (normalized-hypothetical-entry entry))
          next-by-coin (assoc by-coin coin* next-entry)]
      [[:effects/save [:funding-ui :hypothetical-position-by-coin] next-by-coin]])
    []))

(defn reset-funding-hypothetical-position
  [state coin]
  (if-let [coin* (normalize-coin-key coin)]
    (let [by-coin (or (get-in state [:funding-ui :hypothetical-position-by-coin]) {})]
      (if (contains? by-coin coin*)
        [[:effects/save [:funding-ui :hypothetical-position-by-coin]
          (dissoc by-coin coin*)]]
        []))
    []))

(defn set-funding-hypothetical-size
  [state coin mark size-input]
  (if-let [coin* (normalize-coin-key coin)]
    (let [locale (get-in state [:ui :locale])
          size-input* (normalize-decimal-input size-input)
          mark* (parse-finite-number mark)
          size* (parse-decimal-input size-input* locale)
          next-value (when (and (number? mark*)
                                (pos? mark*)
                                (number? size*))
                       (* (js/Math.abs size*) mark*))
          by-coin (or (get-in state [:funding-ui :hypothetical-position-by-coin]) {})
          next-entry (cond-> (hypothetical-entry state coin* mark)
                       true (assoc :size-input size-input*)
                       (number? next-value) (assoc :value-input (format-fixed next-value 2)))
          next-by-coin (assoc by-coin coin* next-entry)]
      [[:effects/save [:funding-ui :hypothetical-position-by-coin] next-by-coin]])
    []))

(defn set-funding-hypothetical-value
  [state coin mark value-input]
  (if-let [coin* (normalize-coin-key coin)]
    (let [locale (get-in state [:ui :locale])
          value-input* (normalize-decimal-input value-input)
          mark* (parse-finite-number mark)
          value* (parse-decimal-input value-input* locale)
          sign (if (or (str/starts-with? value-input* "-")
                       (str/starts-with? value-input* "−"))
                 -1
                 1)
          value-magnitude* (when (number? value*)
                             (js/Math.abs value*))
          next-size (when (and (number? mark*)
                               (pos? mark*)
                               (number? value-magnitude*))
                      (* sign (/ value-magnitude* mark*)))
          by-coin (or (get-in state [:funding-ui :hypothetical-position-by-coin]) {})
          next-entry (cond-> (hypothetical-entry state coin* mark)
                       true (assoc :value-input value-input*)
                       (number? next-size) (assoc :size-input (format-fixed next-size 4)))
          next-by-coin (assoc by-coin coin* next-entry)]
      [[:effects/save [:funding-ui :hypothetical-position-by-coin] next-by-coin]])
    []))
