(ns hyperopen.websocket.user-runtime.fills
  (:require [clojure.string :as str]
            [hyperopen.order.feedback-runtime :as order-feedback-runtime]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.state :as runtime-state]
            [hyperopen.trading-settings :as trading-settings]
            [hyperopen.utils.formatting :as fmt]))

(def ^:private fill-size-format-options
  {:minimumFractionDigits 0
   :maximumFractionDigits 8})

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
  [rows]
  (let [coin* (some-> (or (:coin (first rows))
                          (:symbol (first rows))
                          (:asset (first rows)))
                      str
                      str/trim)]
    (if (seq coin*)
      (str "Order filled: " coin* ".")
      "Order filled.")))

(defn- parse-finite-number
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseFloat (str/trim value))
              :else js/NaN)]
    (when (and (number? num)
               (js/isFinite num))
      num)))

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

(defn- normalized-fill-row
  [row]
  (let [coin* (some-> (or (:coin row)
                          (:symbol row)
                          (:asset row))
                      str
                      str/trim
                      str/upper-case)
        side* (normalize-fill-side row)
        size* (some-> (or (:sz row)
                          (:size row)
                          (:filledSz row)
                          (:filled row))
                      parse-finite-number
                      js/Math.abs)
        price* (some-> (or (:px row)
                           (:price row)
                           (:fillPx row)
                           (:avgPx row))
                       parse-finite-number)]
    (when (and (seq coin*)
               (some? side*)
               (number? size*)
               (pos? size*))
      {:coin coin*
       :side side*
       :size size*
       :price price*})))

(defn- add-fill-group
  [acc fill]
  (let [group-key [(:coin fill) (:side fill)]]
    (if (contains? (:groups acc) group-key)
      (update-in acc [:groups group-key] conj fill)
      (-> acc
          (update :group-order conj group-key)
          (assoc-in [:groups group-key] [fill])))))

(defn- summarize-fill-group
  [fills]
  (let [total-size (reduce + 0 (map :size fills))
        {:keys [weighted-notional weighted-size]}
        (reduce (fn [acc {:keys [size price]}]
                  (if (number? price)
                    (-> acc
                        (update :weighted-notional + (* size price))
                        (update :weighted-size + size))
                    acc))
                {:weighted-notional 0
                 :weighted-size 0}
                fills)
        average-price (when (pos? weighted-size)
                        (/ weighted-notional weighted-size))
        {:keys [coin side]} (first fills)
        action-label (if (= :buy side) "Bought" "Sold")
        headline (str action-label " " (format-fill-size total-size) " " coin)]
    {:headline headline
     :subline (when (number? average-price)
                (str "At average price of " (format-fill-price average-price)))}))

(defn fill-toast-payloads
  [rows]
  (let [parsed-rows (->> (or rows [])
                         (keep normalized-fill-row)
                         vec)
        {:keys [group-order groups]}
        (reduce add-fill-group
                {:group-order []
                 :groups {}}
                parsed-rows)]
    (if (seq group-order)
      (mapv (fn [group-key]
              (let [{:keys [headline subline]} (summarize-fill-group (get groups group-key))]
                (cond-> {:headline headline
                         :message headline}
                  (seq subline) (assoc :subline subline))))
            group-order)
      [{:message (fill-toast-message rows)}])))

(defn show-user-fill-toast!
  [store rows]
  (when (trading-settings/fill-alerts-enabled? @store)
    (doseq [payload (fill-toast-payloads rows)]
      (order-feedback-runtime/show-order-feedback-toast!
       store
       :success
       payload
       schedule-order-feedback-toast-clear!))))
