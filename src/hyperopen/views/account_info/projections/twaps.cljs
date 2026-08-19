(ns hyperopen.views.account-info.projections.twaps
  (:require [clojure.string :as str]
            [hyperopen.platform :as platform]
            [hyperopen.views.account-info.projections.parse :as parse]))

(def ^:private minute-ms
  60000)

(defn- now-ms []
  (platform/now-ms))

(defn- parse-minutes
  [value]
  (when-let [minutes (parse/parse-optional-int value)]
    (max 0 minutes)))

(defn- twap-minutes
  [state]
  (or (some-> (or (:minutes state)
                  (:m state))
              parse-minutes)
      (some-> (or (:durationMs state)
                  (:totalDurationMs state)
                  (:duration state)
                  (:totalMs state))
              parse/parse-optional-num
              (/ minute-ms)
              js/Math.floor)))

(defn- twap-duration-ms
  [state]
  (or (some-> (or (:durationMs state)
                  (:totalDurationMs state)
                  (:totalMs state))
              parse/parse-optional-num)
      (when-let [duration (some-> (:duration state)
                                  parse/parse-optional-num)]
        (if (< duration minute-ms)
          (* duration 1000)
          duration))
      (some-> (twap-minutes state)
              (* minute-ms))))

(defn- active-twap-entry
  [entry]
  (cond
    (and (vector? entry)
         (= 2 (count entry))
         (map? (second entry)))
    {:twap-id (parse/parse-optional-int (first entry))
     :state (second entry)}

    (and (map? entry)
         (map? (:state entry)))
    {:twap-id (some parse/parse-optional-int
                    [(:twapId entry)
                     (:twap-id entry)
                     (:id entry)
                     (get-in entry [:state :twapId])])
     :state (:state entry)}

    (map? entry)
    {:twap-id (some parse/parse-optional-int
                    [(:twapId entry)
                     (:twap-id entry)
                     (:id entry)])
     :state entry}

    :else
    nil))

(defn- display-duration
  "Human runtime label. Carries a day bucket because the venue now accepts runtimes up to
   7 days, which would otherwise render as an unreadable 168h 0m."
  [duration-ms]
  (when-let [duration-ms* (parse/parse-optional-num duration-ms)]
    (let [seconds (max 0 (js/Math.floor (/ duration-ms* 1000)))
          days (js/Math.floor (/ seconds 86400))
          hours (js/Math.floor (/ (mod seconds 86400) 3600))
          minutes (js/Math.floor (/ (mod seconds 3600) 60))]
      (if (pos? days)
        (str days "d " hours "h " minutes "m")
        (str hours "h " minutes "m")))))

(defn- twap-stop-price
  "Price at which the venue terminates the order (the ticket's Max/Min Price), or nil when
   the order carries none. Arrives on the raw payload as camelCase :stopPx because the
   websocket layer stores provider frames verbatim."
  [state]
  (some parse/parse-optional-num [(:stopPx state) (:stop-px state)]))

(defn- twap-trigger
  "Activation condition, or nil when the order carries none. Wire shape is
   {:px <price> :above <bool>}; :above says whether the mark must rise to the trigger or
   fall to it."
  [state]
  (let [raw (:trigger state)]
    (when (map? raw)
      (when-let [px (parse/parse-optional-num (:px raw))]
        {:trigger-px px
         :trigger-above? (true? (parse/boolean-value (:above raw)))}))))

(defn- elapsed-duration
  [elapsed-ms]
  (when-let [elapsed-ms* (parse/parse-optional-num elapsed-ms)]
    (let [seconds (max 0 (js/Math.floor (/ elapsed-ms* 1000)))
          hours (js/Math.floor (/ seconds 3600))
          minutes (js/Math.floor (/ (mod seconds 3600) 60))
          secs (mod seconds 60)]
      (str (.padStart (str hours) 2 "0")
           ":"
           (.padStart (str minutes) 2 "0")
           ":"
           (.padStart (str secs) 2 "0")))))

(defn- running-label
  [start-ms total-ms now-ms*]
  (let [start-ms* (parse/parse-time-ms start-ms)
        total-ms* (parse/parse-optional-num total-ms)
        elapsed-ms (when (number? start-ms*)
                     (max 0 (- (or now-ms* (now-ms))
                               start-ms*)))
        elapsed-label (elapsed-duration elapsed-ms)
        total-label (display-duration total-ms*)]
    (cond
      (and elapsed-label total-label) (str elapsed-label " / " total-label)
      total-label total-label
      elapsed-label elapsed-label
      :else "—")))

(defn- twap-average-price
  [state]
  (let [explicit (some parse/parse-optional-num
                       [(:avgPx state)
                        (:averagePx state)
                        (:avgPrice state)
                        (:averagePrice state)])
        executed-size (some parse/parse-optional-num
                            [(:executedSz state)
                             (:filledSz state)
                             (:executedSize state)
                             (:filled state)])
        executed-ntl (some parse/parse-optional-num
                           [(:executedNtl state)
                            (:executedNotional state)
                            (:filledNtl state)
                            (:executedUsd state)
                            (:filledUsd state)])]
    (or explicit
        (when (and (number? executed-size)
                   (pos? executed-size)
                   (number? executed-ntl))
          (/ executed-ntl executed-size)))))

(defn- twap-status
  [value]
  (let [status-map (cond
                     (map? value) value
                     (string? value) {:status value}
                     (keyword? value) {:status (name value)}
                     :else {})
        status-key (some-> (:status status-map)
                           str
                           str/trim
                           str/lower-case
                           keyword)
        status-label (some-> status-key name str/capitalize)
        description (parse/non-blank-text
                     (or (:description status-map)
                         (:error status-map)
                         (:message status-map)))]
    {:status-key status-key
     :status-label (or status-label "--")
     :status-tooltip description}))

(defn normalize-active-twap-row
  ([entry]
   (normalize-active-twap-row entry (now-ms)))
  ([entry now-ms*]
   (when-let [{:keys [twap-id state]} (active-twap-entry entry)]
     (let [state* (or state {})
           finished? (true? (parse/boolean-value (:finished state*)))
           coin (parse/non-blank-text (:coin state*))
           creation-time-ms (some parse/parse-time-ms
                                  [(:timestamp state*)
                                   (:creationTime state*)
                                   (:createdAt state*)
                                   (:time state*)])
           total-ms (twap-duration-ms state*)
           executed-size (or (some parse/parse-optional-num
                                   [(:executedSz state*)
                                    (:filledSz state*)
                                    (:executedSize state*)
                                    (:filled state*)])
                             0)
           size (some parse/parse-optional-num
                      [(:sz state*)
                       (:totalSz state*)
                       (:size state*)])
           average-price (twap-average-price state*)
           reduce-only? (true? (parse/boolean-value (:reduceOnly state*)))
           trigger (twap-trigger state*)]
       (when (and (not finished?)
                  coin
                  (number? creation-time-ms))
         (merge
          {:twap-id twap-id
           :coin coin
           :size size
           :executed-size executed-size
           :average-price average-price
           :running-label (running-label creation-time-ms total-ms now-ms*)
           :running-ms (when (number? creation-time-ms)
                         (max 0 (- now-ms* creation-time-ms)))
           :total-ms total-ms
           :reduce-only? reduce-only?
           :stop-price (twap-stop-price state*)
           :side (parse/non-blank-text (:side state*))
           :creation-time-ms creation-time-ms}
          (or trigger {:trigger-px nil :trigger-above? nil})))))))

(defn normalized-active-twaps
  ([rows]
   (normalized-active-twaps rows (now-ms)))
  ([rows now-ms*]
   (->> (or rows [])
        (keep #(normalize-active-twap-row % now-ms*))
        (sort-by (fn [{:keys [creation-time-ms]}]
                   (or creation-time-ms 0))
                 >)
        vec)))

(defn normalize-twap-history-row
  [row]
  (when (map? row)
    (let [state (if (map? (:state row))
                  (:state row)
                  row)
          coin (parse/non-blank-text (:coin state))
          size (some parse/parse-optional-num
                     [(:sz state)
                      (:totalSz state)
                      (:size state)])
          executed-size (or (some parse/parse-optional-num
                                  [(:executedSz state)
                                   (:filledSz state)
                                   (:executedSize state)
                                   (:filled state)])
                            0)
          average-price (twap-average-price state)
          reduce-only? (true? (parse/boolean-value (:reduceOnly state)))
          randomize? (true? (parse/boolean-value (:randomize state)))
          trigger (twap-trigger state)
          total-ms (twap-duration-ms state)
          {:keys [status-key status-label status-tooltip]} (twap-status (:status row))
          time-ms (some parse/parse-time-ms
                        [(:time row)
                         (:timestamp row)
                         (:createdAt row)
                         (:timestamp state)])]
      (when coin
        (merge
         {:time-ms time-ms
          :coin coin
          :size size
          :executed-size executed-size
          :average-price average-price
          :total-runtime-label (or (display-duration total-ms) "—")
          :reduce-only? reduce-only?
          :randomize? randomize?
          :stop-price (twap-stop-price state)
          :status-key status-key
          :status-label status-label
          :status-tooltip status-tooltip
          :side (parse/non-blank-text (:side state))}
         (or trigger {:trigger-px nil :trigger-above? nil}))))))

(defn normalized-twap-history
  [rows]
  (->> (or rows [])
       (keep normalize-twap-history-row)
       (sort-by (fn [{:keys [time-ms]}]
                  (or time-ms 0))
                >)
       vec))

(defn normalize-twap-slice-fill
  [row]
  (cond
    (map? (:fill row)) (:fill row)
    (map? row) row
    :else nil))

(defn normalized-twap-slice-fills
  [rows]
  (->> (or rows [])
       (keep normalize-twap-slice-fill)
       (sort-by (fn [row]
                  (or (parse/parse-time-ms (:time row))
                      (parse/parse-time-ms (:timestamp row))
                      0))
                >)
       vec))
