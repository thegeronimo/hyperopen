(ns hyperopen.views.trading-chart.utils.chart-interop.visible-range-persistence
  (:require [hyperopen.platform.indexed-db :as indexed-db]
            [hyperopen.platform :as platform]
            [hyperopen.schema.chart-interop-contracts :as chart-contracts]))

(def ^:private visible-range-storage-key-prefix "chart-visible-time-range")
(def ^:private visible-range-storage-key-version "v2")
(def ^:private visible-range-write-debounce-ms 250)
(def ^:private default-recent-logical-bars 120)
(def ^:private default-recent-right-offset-bars 8)
(def ^:private single-candle-centered-logical-half-width 2)
(def ^:private logical-range-overhang-bars 24)
(def ^:private time-range-overhang-bars 24)

(defn- ->promise
  [result]
  (if (instance? js/Promise result)
    result
    (js/Promise.resolve result)))

(defn- normalize-visible-range-kind
  [kind]
  (cond
    (= kind :time) :time
    (= kind :logical) :logical
    (= kind "time") :time
    (= kind "logical") :logical
    :else nil))

(defn- parse-range-number
  [value]
  (cond
    (number? value) value
    (string? value) (let [parsed (js/parseFloat value)]
                      (when-not (js/isNaN parsed) parsed))
    :else nil))

(defn- parse-saved-at-ms
  [value]
  (let [parsed (parse-range-number value)]
    (if (some? parsed)
      (js/Math.floor parsed)
      0)))

(defn- normalize-visible-range
  [range-data]
  (let [kind (normalize-visible-range-kind (:kind range-data))
        from (parse-range-number (:from range-data))
        to (parse-range-number (:to range-data))]
    (when (and kind
               (some? from)
               (some? to)
               (<= from to))
      {:kind kind
       :from from
       :to to})))

(defn- normalize-timeframe-token
  [timeframe]
  (cond
    (keyword? timeframe) (name timeframe)
    (string? timeframe) timeframe
    :else "default"))

(defn- normalize-asset-token
  [asset]
  (let [asset* (cond
                 (keyword? asset) (name asset)
                 (string? asset) asset
                 :else "default")]
    (if (seq asset*)
      (js/encodeURIComponent asset*)
      "default")))

(defn- visible-range-storage-key
  [timeframe asset]
  (str visible-range-storage-key-prefix
       ":"
       visible-range-storage-key-version
       ":"
       (normalize-timeframe-token timeframe)
       ":"
       (normalize-asset-token asset)))

(defn- normalize-visible-range-record
  [asset timeframe raw]
  (when (map? raw)
    (when-let [range-data (normalize-visible-range (or (:range raw)
                                                       raw))]
      {:id (or (:id raw)
               (visible-range-storage-key timeframe asset))
       :asset asset
       :timeframe (normalize-timeframe-token timeframe)
       :saved-at-ms (parse-saved-at-ms (:saved-at-ms raw))
       :range range-data})))

(defn- build-visible-range-record
  [asset timeframe range-data now-ms-fn]
  (when-let [normalized (normalize-visible-range range-data)]
    {:id (visible-range-storage-key timeframe asset)
     :asset asset
     :timeframe (normalize-timeframe-token timeframe)
     :saved-at-ms (now-ms-fn)
     :range normalized}))

(defn- newer-cache-record
  [a b]
  (cond
    (and a b)
    (if (>= (:saved-at-ms a 0)
            (:saved-at-ms b 0))
      a
      b)

    a
    a

    b
    b

    :else
    nil))

(defn- persist-visible-range-record-to-local-storage!
  [asset timeframe record]
  (try
    (platform/local-storage-set! (visible-range-storage-key timeframe asset)
                                 (js/JSON.stringify (clj->js record)))
    true
    (catch :default _
      false)))

(defn- load-visible-range-record-from-local-storage
  [asset timeframe storage-get]
  (try
    (let [raw (storage-get (visible-range-storage-key timeframe asset))]
      (when (seq raw)
        (normalize-visible-range-record asset
                                        timeframe
                                        (js->clj (js/JSON.parse raw) :keywordize-keys true))))
    (catch :default _
      nil)))

(defn- load-visible-range-record-from-indexed-db!
  [asset timeframe]
  (-> (indexed-db/get-json! indexed-db/chart-visible-range-store
                            (visible-range-storage-key timeframe asset))
      (.then (fn [record]
               (when record
                 (normalize-visible-range-record asset timeframe record))))))

(defn- persist-visible-range-record-to-indexed-db!
  [asset timeframe record]
  (indexed-db/put-json! indexed-db/chart-visible-range-store
                        (visible-range-storage-key timeframe asset)
                        record))

(defn- persist-visible-range-to-storage-set!
  [asset timeframe range-data storage-set!]
  (when-let [normalized (normalize-visible-range range-data)]
    (chart-contracts/assert-visible-range! normalized
                                           {:boundary :chart-interop/persist-visible-range
                                            :timeframe timeframe
                                            :asset asset})
    (try
      (storage-set!
       (visible-range-storage-key timeframe asset)
       (js/JSON.stringify (clj->js normalized)))
      true
      (catch :default _
        false))))

(defn- persist-visible-range!
  ([asset timeframe range-data]
   (persist-visible-range! asset timeframe range-data {}))
  ([asset timeframe range-data {:keys [now-ms-fn
                                       persist-indexed-db-fn
                                       persist-local-storage-fn]
                                :or {now-ms-fn platform/now-ms
                                     persist-indexed-db-fn persist-visible-range-record-to-indexed-db!
                                     persist-local-storage-fn persist-visible-range-record-to-local-storage!}}]
   (if-let [record (build-visible-range-record asset timeframe range-data now-ms-fn)]
     (do
       (chart-contracts/assert-visible-range! (:range record)
                                              {:boundary :chart-interop/persist-visible-range
                                               :timeframe timeframe
                                               :asset asset})
       (-> (->promise (persist-indexed-db-fn asset timeframe record))
           (.then (fn [persisted?]
                    (when-not persisted?
                      (persist-local-storage-fn asset timeframe record))
                    persisted?))
           (.catch (fn [_]
                     (persist-local-storage-fn asset timeframe record)
                     false))))
     (js/Promise.resolve false))))

(defn load-persisted-visible-range!
  ([asset timeframe]
   (load-persisted-visible-range! asset timeframe {}))
  ([asset timeframe {:keys [storage-get
                            load-indexed-db-fn
                            persist-indexed-db-fn]
                     :or {storage-get platform/local-storage-get
                          load-indexed-db-fn load-visible-range-record-from-indexed-db!
                          persist-indexed-db-fn persist-visible-range-record-to-indexed-db!}}]
   (let [local-record (load-visible-range-record-from-local-storage asset timeframe storage-get)]
     (-> (->promise (load-indexed-db-fn asset timeframe))
         (.catch (fn [_]
                   nil))
         (.then (fn [indexed-db-record]
                  (let [selected-record (newer-cache-record indexed-db-record local-record)]
                    (when (and local-record
                               (not= selected-record indexed-db-record))
                      (-> (->promise (persist-indexed-db-fn asset timeframe local-record))
                          (.catch (fn [_]
                                    nil))))
                    (:range selected-record))))))))

(defn- candle-time-seconds
  [candle]
  (let [raw-time (or (:time candle)
                     (get candle "time")
                     (:t candle)
                     (get candle "t"))
        parsed (parse-range-number raw-time)]
    (when (some? parsed)
      (if (> parsed 100000000000)
        (/ parsed 1000)
        parsed))))

(defn- infer-candles-time-domain
  [candles]
  (let [times (->> candles
                   (keep candle-time-seconds)
                   sort
                   vec)]
    (when (seq times)
      (let [deltas (->> (map - (rest times) times)
                        (filter pos?)
                        vec)
            interval (if (seq deltas)
                       (apply min deltas)
                       1)]
        {:first-time (first times)
         :last-time (peek times)
         :interval interval}))))

(defn- logical-range-valid-for-candles?
  [{:keys [from to]} candles]
  (let [candle-count (count candles)
        max-logical (+ (dec candle-count) logical-range-overhang-bars)
        min-logical (- logical-range-overhang-bars)]
    (and (pos? candle-count)
         (< from to)
         (<= from max-logical)
         (>= from min-logical)
         (<= to max-logical)
         (>= to min-logical))))

(defn- time-range-valid-for-candles?
  [{:keys [from to]} candles]
  (when-let [{:keys [first-time last-time interval]} (infer-candles-time-domain candles)]
    (let [margin (* interval time-range-overhang-bars)
          min-time (- first-time margin)
          max-time (+ last-time margin)]
      (and (< from to)
           (<= from max-time)
           (>= from min-time)
           (<= to max-time)
           (>= to min-time)))))

(defn- persisted-range-valid-for-candles?
  [{:keys [kind] :as persisted} candles]
  (and (seq candles)
       (case kind
         :logical (logical-range-valid-for-candles? persisted candles)
         :time (time-range-valid-for-candles? persisted candles)
         false)))

(defn- apply-visible-range!
  [time-scale {:keys [kind from to]}]
  (try
    (case kind
      :time (if (fn? (.-setVisibleRange ^js time-scale))
              (do
                (.setVisibleRange ^js time-scale
                                  (clj->js {:from from
                                            :to to}))
                true)
              false)
      :logical (if (fn? (.-setVisibleLogicalRange ^js time-scale))
                 (do
                   (.setVisibleLogicalRange ^js time-scale
                                            (clj->js {:from from
                                                      :to to}))
                   true)
                 false)
      false)
    (catch :default _
      false)))

(defn- apply-recent-default-visible-range!
  [chart candles]
  (let [time-scale (.timeScale ^js chart)
        candle-count (count candles)]
    (when (and time-scale (pos? candle-count))
      (when (fn? (.-fitContent ^js time-scale))
        (try
          (.fitContent ^js time-scale)
          (catch :default _
            nil)))
      (if (> candle-count default-recent-logical-bars)
        (let [to (+ (dec candle-count) default-recent-right-offset-bars)
              from (- candle-count default-recent-logical-bars)]
          (when (fn? (.-setVisibleLogicalRange ^js time-scale))
            (try
              (.setVisibleLogicalRange ^js time-scale
                                       (clj->js {:from from :to to}))
              (catch :default _
                nil))))
        (if (= 1 candle-count)
          (when (fn? (.-setVisibleLogicalRange ^js time-scale))
            (try
              (.setVisibleLogicalRange ^js time-scale
                                       (clj->js {:from (- single-candle-centered-logical-half-width)
                                                 :to single-candle-centered-logical-half-width}))
              (catch :default _
                nil)))
          (do
            (when (fn? (.-setRightOffset ^js time-scale))
              (try
                (.setRightOffset ^js time-scale default-recent-right-offset-bars)
                (catch :default _
                  nil)))
            (when (fn? (.-scrollToRealTime ^js time-scale))
              (try
                (.scrollToRealTime ^js time-scale)
                (catch :default _
                  nil)))))))))

(defn apply-default-visible-range!
  [chart candles]
  (apply-recent-default-visible-range! chart candles))

(defn- visible-range-from-time-scale
  [time-scale]
  (or (try
        (when (fn? (.-getVisibleLogicalRange ^js time-scale))
          (some-> (.getVisibleLogicalRange ^js time-scale)
                  (js->clj :keywordize-keys true)
                  (assoc :kind :logical)
                  normalize-visible-range))
        (catch :default _
          nil))
      (try
        (when (fn? (.-getVisibleRange ^js time-scale))
          (some-> (.getVisibleRange ^js time-scale)
                  (js->clj :keywordize-keys true)
                  (assoc :kind :time)
                  normalize-visible-range))
        (catch :default _
          nil))))

(defn- range-candidate->data
  [kind range]
  (let [range-data (cond
                     (map? range) range
                     (some? range) (js->clj range :keywordize-keys true)
                     :else nil)]
    (when (some? range-data)
      (normalize-visible-range (assoc range-data :kind kind)))))

(defn apply-persisted-visible-range!
  "Apply persisted visible range (asset + timeframe) to chart time scale if available."
  ([chart timeframe]
   (apply-persisted-visible-range! chart timeframe {}))
  ([chart timeframe {:keys [storage-get
                            asset
                            candles
                            allow-apply-fn
                            fallback-to-default?
                            load-persisted-visible-range-fn]
                     :or {storage-get platform/local-storage-get
                          allow-apply-fn (constantly true)
                          fallback-to-default? true
                          load-persisted-visible-range-fn load-persisted-visible-range!}}]
   (let [time-scale (.timeScale ^js chart)]
     (-> (->promise (load-persisted-visible-range-fn asset timeframe {:storage-get storage-get}))
         (.then (fn [persisted]
                  (let [persisted-valid? (persisted-range-valid-for-candles? persisted candles)
                        can-apply? (boolean (allow-apply-fn))
                        persisted-applied? (if (and time-scale persisted-valid? can-apply?)
                                             (do
                                               (chart-contracts/assert-visible-range! persisted
                                                                      {:boundary :chart-interop/load-visible-range
                                                                       :timeframe timeframe
                                                                       :asset asset})
                                               (apply-visible-range! time-scale persisted))
                                             false)]
                    (when (and time-scale
                               (seq candles)
                               fallback-to-default?
                               (not persisted-applied?)
                               can-apply?)
                      (apply-recent-default-visible-range! chart candles))
                    persisted-applied?)))))))

(defn subscribe-visible-range-persistence!
  "Subscribe to visible-range changes and persist them by asset + timeframe."
  ([chart timeframe]
   (subscribe-visible-range-persistence! chart timeframe {}))
  ([chart timeframe {:keys [storage-set!
                            asset
                            debounce-ms
                            persist-visible-range-fn
                            set-timeout-fn
                            clear-timeout-fn
                            on-visible-range-change!]
                     :or {debounce-ms visible-range-write-debounce-ms
                          set-timeout-fn platform/set-timeout!
                          clear-timeout-fn platform/clear-timeout!
                          on-visible-range-change! (fn [] nil)}}]
   (let [persist-visible-range!* (or persist-visible-range-fn
                                     (if storage-set!
                                       (fn [asset* timeframe* range-data]
                                         (js/Promise.resolve
                                          (persist-visible-range-to-storage-set! asset* timeframe* range-data storage-set!)))
                                       persist-visible-range!))
         time-scale (.timeScale ^js chart)]
     (if-not time-scale
       (fn [] nil)
       (let [pending-range (atom nil)
             pending-timeout-id (atom nil)
             interaction-notified? (atom false)
             notify-visible-range-change! (fn []
                                            (when-not @interaction-notified?
                                              (reset! interaction-notified? true)
                                              (on-visible-range-change!)))
             flush-persist! (fn []
                              (let [range-data @pending-range]
                                (reset! pending-range nil)
                                (reset! pending-timeout-id nil)
                                (reset! interaction-notified? false)
                                (when range-data
                                  (persist-visible-range!* asset timeframe range-data))))
             queue-persist! (fn [range-data]
                              (when range-data
                                (reset! pending-range range-data)
                                (when-let [timeout-id @pending-timeout-id]
                                  (clear-timeout-fn timeout-id))
                                (reset! pending-timeout-id
                                        (set-timeout-fn flush-persist! debounce-ms))))
             persist-current! (fn []
                                (when-let [range-data (visible-range-from-time-scale time-scale)]
                                  (queue-persist! range-data)))
             logical-handler (fn [range]
                               (notify-visible-range-change!)
                               (if-let [range-data (range-candidate->data :logical range)]
                                 (queue-persist! range-data)
                                 (persist-current!)))
             time-handler (fn [range]
                            (notify-visible-range-change!)
                            (if-let [range-data (range-candidate->data :time range)]
                              (queue-persist! range-data)
                              (persist-current!)))
             unsubscribe! (cond
                            (fn? (.-subscribeVisibleLogicalRangeChange ^js time-scale))
                            (do
                              (.subscribeVisibleLogicalRangeChange ^js time-scale logical-handler)
                              (fn []
                                (try
                                  (.unsubscribeVisibleLogicalRangeChange ^js time-scale logical-handler)
                                  (catch :default _
                                    nil))))

                            (fn? (.-subscribeVisibleTimeRangeChange ^js time-scale))
                            (do
                              (.subscribeVisibleTimeRangeChange ^js time-scale time-handler)
                              (fn []
                                (try
                                  (.unsubscribeVisibleTimeRangeChange ^js time-scale time-handler)
                                  (catch :default _
                                    nil))))

                            :else
                            (fn [] nil))]
         (fn []
           (when-let [timeout-id @pending-timeout-id]
             (clear-timeout-fn timeout-id))
           (flush-persist!)
           (unsubscribe!)))))))
