(ns hyperopen.websocket.user-runtime.fills
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.domain.market.instrument :as instrument]
            [hyperopen.order.feedback-runtime :as order-feedback-runtime]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.utils.formatting :as fmt]))

(def ^:private fill-size-format-options
  {:minimumFractionDigits 0
   :maximumFractionDigits 8})

(def ^:private fill-burst-window-ms 10000)
(def ^:private notable-notional-threshold-usd 10000)
(def ^:private high-slippage-threshold-pct 0.25)

(defn- clear-order-feedback-toast-timeout!
  ([]
   (clear-order-feedback-toast-timeout! nil))
  ([toast-id]
   (order-feedback-runtime/clear-order-feedback-toast-timeout-in-runtime!
    runtime-state/runtime
    platform/clear-timeout!
    toast-id)))

(defn- schedule-order-feedback-toast-clear!
  [store toast-id]
  (order-feedback-runtime/schedule-order-feedback-toast-clear!
   {:store store
    :runtime runtime-state/runtime
    :toast-id toast-id
    :clear-order-feedback-toast! order-feedback-runtime/clear-order-feedback-toast!
    :clear-order-feedback-toast-timeout! clear-order-feedback-toast-timeout!
    :order-feedback-toast-duration-ms runtime-state/order-feedback-toast-duration-ms
    :set-timeout-fn platform/set-timeout!}))

(defn- fill-identity
  [fill]
  (when (map? fill)
    (or (some-> (or (:tid fill)
                    (:fill-id fill)
                    (:fillId fill)
                    (:id fill))
                (vector :id))
        (let [time-token (or (:time fill)
                             (:timestamp fill)
                             (:ts fill)
                             (:t fill))
              coin-token (or (:coin fill)
                             (:symbol fill)
                             (:asset fill))
              side-token (or (:side fill)
                             (:dir fill))
              size-token (or (:sz fill)
                             (:size fill))
              price-token (or (:px fill)
                              (:price fill)
                              (:fillPx fill)
                              (:avgPx fill))]
          (when (or (some? time-token)
                    (some? coin-token)
                    (some? side-token)
                    (some? size-token)
                    (some? price-token))
            [:fallback time-token coin-token side-token size-token price-token])))))

(defn novel-fills
  [existing incoming]
  (let [known (->> (or existing [])
                   (keep fill-identity)
                   set)]
    (second
     (reduce (fn [[seen acc] fill]
               (if-let [identity (fill-identity fill)]
                 (if (contains? seen identity)
                   [seen acc]
                   [(conj seen identity) (conj acc fill)])
                 [seen (conj acc fill)]))
             [known []]
             (or incoming [])))))

(defn- fill-toast-message
  ([rows]
   (fill-toast-message rows nil))
  ([rows market-by-key]
   (let [coin* (some-> (or (:coin (first rows))
                           (:symbol (first rows))
                           (:asset (first rows)))
                       str
                       str/trim)
         market (markets/resolve-market-by-coin (or market-by-key {}) coin*)
         display-coin (some-> (instrument/resolve-base-symbol coin* market nil)
                              str
                              str/trim
                              str/upper-case)]
     (if (seq display-coin)
       (str "Order filled: " display-coin ".")
       "Order filled."))))

(defn- parse-finite-number
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseFloat (str/trim value))
              :else js/NaN)]
    (when (and (number? num)
               (js/isFinite num))
      num)))

(defn- parse-fill-time-ms
  [value]
  (parse-finite-number value))

(defn- normalize-order-type
  [row]
  (let [order-type (some-> (or (:orderType row)
                               (:order-type row)
                               (:type row)
                               (:tif row))
                           str
                           str/trim)]
    (if (seq order-type)
      (str/lower-case order-type)
      "limit")))

(defn- parse-slippage-pct
  [row]
  (some-> (or (:slippagePct row)
              (:slippage-pct row)
              (:slippage row))
          parse-finite-number))

(defn- normalize-fill-side
  [row]
  (let [side* (some-> (:side row) str str/trim str/upper-case)
        direction* (some-> (or (:dir row) (:direction row))
                           str
                           str/lower-case)]
    (cond
      (#{"B" "BUY" "LONG"} side*) :buy
      (#{"A" "S" "SELL" "SHORT"} side*) :sell
      (and (seq direction*)
           (or (str/includes? direction* "sell")
               (str/includes? direction* "open short")
               (str/includes? direction* "close long")))
      :sell
      (and (seq direction*)
           (or (str/includes? direction* "buy")
               (str/includes? direction* "open long")
               (str/includes? direction* "close short")))
      :buy
      :else nil)))

(defn- format-fill-size
  [size]
  (or (fmt/format-intl-number size fill-size-format-options)
      (fmt/safe-to-fixed size 4)))

(defn- format-fill-price
  [price]
  (or (fmt/format-currency-with-digits price 0 5)
      (str "$" (fmt/safe-to-fixed price 2))))

(defn- fill-coin-token
  [row]
  (some-> (or (:coin row)
              (:symbol row)
              (:asset row))
          str
          str/trim))

(defn- fill-display-coin
  [row market-by-key]
  (let [coin* (fill-coin-token row)
        market (markets/resolve-market-by-coin (or market-by-key {}) coin*)]
    (some-> (instrument/resolve-base-symbol coin* market nil)
            str
            str/trim
            str/upper-case)))

(defn- fill-size
  [row]
  (some-> (or (:sz row)
              (:size row)
              (:filledSz row)
              (:filled row))
          parse-finite-number
          js/Math.abs))

(defn- fill-price
  [row]
  (some-> (or (:px row)
              (:price row)
              (:fillPx row)
              (:avgPx row))
          parse-finite-number))

(defn- fill-timestamp
  [row]
  (parse-fill-time-ms (or (:time row)
                          (:timestamp row)
                          (:ts row)
                          (:t row))))

(defn- explicit-fill-id
  [row]
  (or (:tid row)
      (:fill-id row)
      (:fillId row)
      (:id row)))

(defn- generated-fill-id
  [{:keys [coin side size price ts]}]
  (when (or (some? ts)
            (seq coin)
            (some? side)
            (some? size)
            (some? price))
    (str coin "-" (name (or side :unknown)) "-"
         (or ts "na") "-" (or size "na") "-" (or price "na"))))

(defn- fill-row-id
  [row normalized-fields]
  (or (explicit-fill-id row)
      (generated-fill-id normalized-fields)))

(defn- normalized-fill-row-valid?
  [{:keys [coin display-coin side size price]}]
  (and (seq coin)
       (seq display-coin)
       (some? side)
       (number? size)
       (pos? size)
       (number? price)))

(defn- normalized-fill-row-map
  [{:keys [coin display-coin id side size price order-type ts slippage-pct]}]
  {:coin coin
   :display-coin display-coin
   :id (str id)
   :side side
   :size size
   :qty size
   :symbol display-coin
   :price price
   :orderType order-type
   :ts (or ts 0)
   :slippagePct slippage-pct})

(defn- normalized-fill-row
  ([row]
   (normalized-fill-row row nil))
  ([row market-by-key]
   (let [coin-token (fill-coin-token row)
         coin* (some-> coin-token
                       str/upper-case)
         display-coin* (or (fill-display-coin row market-by-key)
                           coin*)
         side* (normalize-fill-side row)
         size* (fill-size row)
         price* (fill-price row)
         ts* (fill-timestamp row)
         order-type* (normalize-order-type row)
         slippage-pct* (parse-slippage-pct row)
         normalized-fields {:coin coin*
                            :display-coin display-coin*
                            :side side*
                            :size size*
                            :price price*
                            :ts ts*
                            :order-type order-type*
                            :slippage-pct slippage-pct*}
         normalized-fields* (assoc normalized-fields
                                   :id (fill-row-id row normalized-fields))]
     (when (normalized-fill-row-valid? normalized-fields*)
       (normalized-fill-row-map normalized-fields*)))))

(defn- summarize-fill-group
  [fills]
  (let [total-size (reduce + 0 (map :qty fills))
        {:keys [weighted-notional weighted-size]}
        (reduce (fn [acc {:keys [qty price]}]
                  (if (number? price)
                    (-> acc
                        (update :weighted-notional + (* qty price))
                        (update :weighted-size + qty))
                    acc))
                {:weighted-notional 0
                 :weighted-size 0}
                fills)
        average-price (when (pos? weighted-size)
                        (/ weighted-notional weighted-size))
        {:keys [coin symbol side]} (first fills)
        action-label (if (= :buy side) "Bought" "Sold")
        headline (str action-label " "
                      (format-fill-size total-size)
                      " "
                      (or symbol coin))]
    {:headline headline
     :subline (when (number? average-price)
                (str "At average price of " (format-fill-price average-price)))}))

(defn- time-windowed?
  [fills]
  (let [times (->> fills
                   (map :ts)
                   (filter number?)
                   (remove zero?)
                   seq)]
    (or (nil? times)
        (<= (- (apply max times) (apply min times))
            fill-burst-window-ms))))

(defn- same-side?
  [fills]
  (let [side (:side (first fills))]
    (every? #(= side (:side %)) fills)))

(defn- notable-fill?
  [fill]
  (let [notional (when (and (number? (:qty fill))
                            (number? (:price fill)))
                   (* (:qty fill) (:price fill)))
        slippage (when (number? (:slippagePct fill))
                   (js/Math.abs (:slippagePct fill)))]
    (or (>= (or notional 0) notable-notional-threshold-usd)
        (= "market" (some-> (:orderType fill) str str/lower-case))
        (>= (or slippage 0) high-slippage-threshold-pct))))

(defn- fill-toast-variant
  [fills]
  (let [count* (count fills)]
    (cond
      (= 1 count*) (if (notable-fill? (first fills)) :detailed :pill)
      (and (<= 2 count* 3)
           (time-windowed? fills)) :stack
      (and (>= count* 4)
           (time-windowed? fills)
           (same-side? fills)) :consolidated
      (and (> count* 1)
           (time-windowed? fills)) :stack
      :else :split)))

(defn- latest-first
  [fills]
  (->> fills
       (sort-by :ts >)
       vec))

(defn- summarize-trade-toast
  [fills variant]
  (case variant
    (:pill :detailed)
    (summarize-fill-group fills)

    :consolidated
    (let [{:keys [headline subline]} (summarize-fill-group fills)]
      {:headline (str (count fills) " fills · " headline)
       :subline subline})

    :stack
    {:headline (str (count fills) " fills")
     :subline (->> fills
                   (map :symbol)
                   distinct
                   (str/join ", "))}

    {:headline "Order filled."}))

(defn- trade-toast-payload
  [fills]
  (let [fills* (latest-first fills)
        variant (fill-toast-variant fills*)
        {:keys [headline subline]} (summarize-trade-toast fills* variant)]
    (cond-> {:toast-surface :trade-confirmation
             :variant variant
             :fills fills*
             :headline headline
             :message headline}
      (seq subline) (assoc :subline subline))))

(defn fill-toast-payloads
  ([rows]
   (fill-toast-payloads rows nil))
  ([rows market-by-key]
   (let [parsed-rows (->> (or rows [])
                          (keep #(normalized-fill-row % market-by-key))
                          vec)]
     (if (seq parsed-rows)
       (let [variant (fill-toast-variant parsed-rows)]
         (if (= :split variant)
           (mapv #(trade-toast-payload [%]) parsed-rows)
           [(trade-toast-payload parsed-rows)]))
       [{:message (fill-toast-message rows market-by-key)}]))))

(defn- trade-confirmation-toast?
  [toast]
  (and (map? toast)
       (= :trade-confirmation (:toast-surface toast))
       (seq (:fills toast))))

(defn- active-toast-entries
  [state]
  (let [toasts (->> (or (get-in state [:ui :toasts]) [])
                    (filter map?)
                    vec)
        legacy-toast (get-in state [:ui :toast])]
    (if (seq toasts)
      toasts
      (if (map? legacy-toast)
        [legacy-toast]
        []))))

(defn- merge-candidate?
  [incoming-fills toast]
  (and (trade-confirmation-toast? toast)
       (time-windowed? (into (vec (:fills toast)) incoming-fills))))

(defn- remove-trade-toast-candidates!
  [store candidate-ids]
  (doseq [toast-id candidate-ids]
    (clear-order-feedback-toast-timeout! toast-id))
  (when (seq candidate-ids)
    (swap! store
           (fn [state]
             (let [candidate-set (set candidate-ids)
                   remaining (->> (active-toast-entries state)
                                  (remove #(contains? candidate-set (:id %)))
                                  vec)
                   latest-toast (some-> (peek remaining)
                                        (dissoc :id))]
               (-> state
                   (assoc-in [:ui :toasts] remaining)
                   (assoc-in [:ui :toast] latest-toast)))))))

(defn- merged-trade-payload
  [store payload]
  (let [incoming-fills (vec (:fills payload))
        candidates (->> (active-toast-entries @store)
                        (filter #(merge-candidate? incoming-fills %))
                        vec)
        candidate-ids (vec (keep :id candidates))
        expanded-candidate (first (filter :expanded? candidates))
        merged-fills (into (vec (mapcat :fills candidates)) incoming-fills)]
    (remove-trade-toast-candidates! store candidate-ids)
    (if (seq candidates)
      (cond-> (trade-toast-payload merged-fills)
        expanded-candidate (assoc :id (:id expanded-candidate)
                                  :expanded? true
                                  :auto-timeout? false))
      payload)))

(defn show-user-fill-toast!
  [store rows]
  (when (trading-settings/fill-alerts-enabled? @store)
    (let [market-by-key (get-in @store [:asset-selector :market-by-key] {})]
      (doseq [payload (fill-toast-payloads rows market-by-key)]
        (order-feedback-runtime/show-order-feedback-toast!
         store
         :success
         (if (= :trade-confirmation (:toast-surface payload))
           (merged-trade-payload store payload)
           payload)
         schedule-order-feedback-toast-clear!)))))
