(ns hyperopen.portfolio.application.summary
  (:require [clojure.string :as str]
            [hyperopen.portfolio.metrics.history :as portfolio-history]
            [hyperopen.portfolio.application.history :as vm-history]))

(def ^:private base-summary-range-order
  [:day :week :month :three-month :six-month :one-year :two-year :all-time])

(def ^:private base-summary-range-set
  (set base-summary-range-order))

(def ^:private derivable-summary-ranges
  #{:three-month :six-month :one-year :two-year})

(def ^:private summary-key-aliases
  {"day" :day
   "week" :week
   "month" :month
   "3m" :three-month
   "3-m" :three-month
   "3month" :three-month
   "3-month" :three-month
   "threemonth" :three-month
   "three-month" :three-month
   "three-months" :three-month
   "quarter" :three-month
   "6m" :six-month
   "6-m" :six-month
   "6month" :six-month
   "6-month" :six-month
   "sixmonth" :six-month
   "six-month" :six-month
   "six-months" :six-month
   "halfyear" :six-month
   "half-year" :six-month
   "1y" :one-year
   "1-y" :one-year
   "1year" :one-year
   "1-year" :one-year
   "oneyear" :one-year
   "one-year" :one-year
   "one-years" :one-year
   "year" :one-year
   "2y" :two-year
   "2-y" :two-year
   "2year" :two-year
   "2-year" :two-year
   "twoyear" :two-year
   "two-year" :two-year
   "two-years" :two-year
   "alltime" :all-time
   "all-time" :all-time})

(defn- normalized-summary-token
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      (let [token (-> text
                      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                      str/lower-case
                      (str/replace #"[^a-z0-9]+" "-")
                      (str/replace #"(^-+)|(-+$)" ""))]
        (when (seq token)
          token)))))

(defn- scoped-summary-key
  [scope base-key]
  (if (= scope :perps)
    (keyword (str "perp-" (name base-key)))
    base-key))

(defn- base-summary-key
  [time-range]
  (if (base-summary-range-set time-range)
    time-range
    :month))

(defn- aliased-summary-key
  [token]
  (let [[scope alias-token]
        (if (str/starts-with? token "perp")
          [:perps (str/replace-first token #"^perp-?" "")]
          [:all token])]
    (some->> alias-token
             summary-key-aliases
             (scoped-summary-key scope))))

(defn- ordered-summary-base-candidates
  [base-key]
  (let [[lower-ranges higher-ranges] (split-with #(not= % base-key) base-summary-range-order)]
    (vec (concat higher-ranges (reverse lower-ranges)))))

(defn canonical-summary-key
  [value]
  (when-let [token (normalized-summary-token value)]
    (or (aliased-summary-key token)
        (keyword token))))

(defn normalize-summary-by-key
  [summary-by-key]
  (reduce-kv (fn [acc key value]
               (let [summary-key (canonical-summary-key key)]
                 (if (and summary-key
                          (map? value))
                   (assoc acc summary-key value)
                   acc)))
             {}
             (or summary-by-key {})))

(defn selected-summary-key
  [scope time-range]
  (scoped-summary-key scope (base-summary-key time-range)))

(defn summary-key-candidates
  [scope time-range]
  (mapv (partial scoped-summary-key scope)
        (ordered-summary-base-candidates (base-summary-key time-range))))

(defn- scope-all-time-key
  [scope]
  (scoped-summary-key scope :all-time))

(defn all-time-summary-key
  [scope]
  (scope-all-time-key scope))

(declare selected-summary-context)

(defn- derived-summary-cutoff-ms
  [summary-time-range end-time-ms]
  (when (derivable-summary-ranges summary-time-range)
    (vm-history/summary-window-cutoff-ms summary-time-range end-time-ms)))

(defn derived-summary-entry
  [summary-by-key scope summary-time-range]
  (when-let [base-summary (get summary-by-key (scope-all-time-key scope))]
    (let [account-rows (vm-history/normalized-history-rows
                        (vm-history/account-value-history-rows base-summary))
          pnl-rows (vm-history/normalized-history-rows
                    (vm-history/pnl-history-rows base-summary))
          end-time-ms (or (some-> account-rows last :time-ms)
                          (some-> pnl-rows last :time-ms))
          cutoff-ms (derived-summary-cutoff-ms summary-time-range end-time-ms)]
      (when (number? cutoff-ms)
        (let [account-window (vm-history/history-window-rows account-rows cutoff-ms)
              pnl-window (vm-history/history-window-rows pnl-rows cutoff-ms)
              base-pnl (some-> pnl-window first :value)
              pnl-window* (vm-history/rebase-history-rows pnl-window (or base-pnl 0))]
          (when (or (seq account-window)
                    (seq pnl-window*))
            {:accountValueHistory account-window
             :pnlHistory pnl-window*}))))))

(defn- aligned-summary-points
  [summary]
  (vec (portfolio-history/aligned-account-pnl-points summary)))

(defn- points-end-time-ms
  [points]
  (some-> points last :time-ms))

(defn- summary-time-range-token
  [time-range]
  (base-summary-key time-range))

(defn- bounded-returns-window
  [points cutoff-ms]
  (let [points* (vec (or points []))]
    (if (number? cutoff-ms)
      (let [anchor-point (last (filter (fn [{:keys [time-ms]}]
                                         (and (number? time-ms)
                                              (<= time-ms cutoff-ms)))
                                       points*))
            points-in-window (vec (filter (fn [{:keys [time-ms]}]
                                            (and (number? time-ms)
                                                 (> time-ms cutoff-ms)))
                                          points*))
            window-points (cond
                            anchor-point
                            (into [{:time-ms cutoff-ms
                                    :account-value (:account-value anchor-point)
                                    :pnl-value (:pnl-value anchor-point)}]
                                  points-in-window)

                            :else
                            points-in-window)]
        {:points window-points
         :cutoff-ms cutoff-ms
         :complete-window? (some? anchor-point)})
      {:points points*
       :cutoff-ms nil
       :complete-window? (seq points*)})))

(defn- points->returns-summary
  [points]
  (when-let [base-pnl (some-> points first :pnl-value)]
    {:accountValueHistory (mapv (fn [{:keys [time-ms account-value]}]
                                  [time-ms account-value])
                                points)
     :pnlHistory (mapv (fn [{:keys [time-ms pnl-value]}]
                         [time-ms (- pnl-value base-pnl)])
                       points)}))

(defn returns-history-context
  ([summary-by-key scope time-range]
   (returns-history-context summary-by-key
                            scope
                            time-range
                            (selected-summary-context summary-by-key scope time-range)))
  ([summary-by-key scope time-range summary-context]
   (let [summary-by-key* (normalize-summary-by-key summary-by-key)
         requested-range (summary-time-range-token time-range)
         selected-entry (:entry summary-context)
         selected-points (aligned-summary-points selected-entry)
         selected-end-time-ms (points-end-time-ms selected-points)
         all-time-key (scope-all-time-key scope)
         all-time-entry (get summary-by-key* all-time-key)
         all-time-points (aligned-summary-points all-time-entry)
         all-time-end-time-ms (points-end-time-ms all-time-points)
         all-time-usable? (and (not= requested-range :all-time)
                               (seq all-time-points)
                               (or (nil? selected-end-time-ms)
                                   (>= all-time-end-time-ms selected-end-time-ms)))
         source-points (if all-time-usable?
                         all-time-points
                         selected-points)
         effective-range (if all-time-usable?
                           requested-range
                           (vm-history/benchmark-time-range requested-range
                                                            (:effective-key summary-context)))
         cutoff-ms (let [end-time-ms (points-end-time-ms source-points)]
                     (when (and (number? end-time-ms)
                                (not= effective-range :all-time))
                       (vm-history/summary-window-cutoff-ms effective-range end-time-ms)))
         {:keys [points complete-window?] :as window-context}
         (bounded-returns-window source-points cutoff-ms)
         summary (points->returns-summary points)
         first-time-ms (some-> points first :time-ms)
         last-time-ms (some-> points last :time-ms)
         window-start-ms (or first-time-ms cutoff-ms)
         source (cond
                  all-time-usable? :windowed-all-time
                  (seq points) (if (number? cutoff-ms)
                                 :windowed-selected
                                 :selected-summary)
                  :else :empty)]
     {:summary summary
      :requested-range requested-range
      :effective-range effective-range
      :source-key (if all-time-usable?
                    all-time-key
                    (:source-key summary-context))
      :source source
      :cutoff-ms (:cutoff-ms window-context)
      :window-start-ms window-start-ms
      :window-end-ms last-time-ms
      :first-time-ms first-time-ms
      :last-time-ms last-time-ms
      :point-count (count points)
      :complete-window? (boolean (and (seq points)
                                      complete-window?))
      :has-data? (boolean (seq points))})))

(defn selected-summary-entry
  [summary-by-key scope time-range]
  (:entry (selected-summary-context summary-by-key scope time-range)))

(defn selected-summary-context
  [summary-by-key scope time-range]
  (let [summary-by-key* (normalize-summary-by-key summary-by-key)
        requested-key (selected-summary-key scope time-range)
        source-key (scope-all-time-key scope)]
    (cond
      (contains? summary-by-key* requested-key)
      {:entry (get summary-by-key* requested-key)
       :requested-key requested-key
       :effective-key requested-key
       :source-key requested-key
       :source :direct}

      :else
      (if-let [derived-entry (derived-summary-entry summary-by-key* scope time-range)]
        {:entry derived-entry
         :requested-key requested-key
         :effective-key requested-key
         :source-key source-key
         :source :derived}
        (if-let [fallback-key (some #(when (contains? summary-by-key* %)
                                       %)
                                    (summary-key-candidates scope time-range))]
          {:entry (get summary-by-key* fallback-key)
           :requested-key requested-key
           :effective-key fallback-key
           :source-key fallback-key
           :source :fallback}
          (if-let [[first-key first-entry] (first summary-by-key*)]
            {:entry first-entry
             :requested-key requested-key
             :effective-key first-key
             :source-key first-key
             :source :first}
            {:entry nil
             :requested-key requested-key
             :effective-key nil
             :source-key nil
             :source nil}))))))

(defn pnl-delta
  [summary]
  (let [values (keep vm-history/history-point-value (or (:pnlHistory summary) []))]
    (when (seq values)
      (- (last values) (first values)))))

(defn max-drawdown-ratio
  [summary]
  (let [pnl-history (vec (or (:pnlHistory summary) []))
        account-history (vec (or (:accountValueHistory summary) []))]
    (when (and (seq pnl-history)
               (seq account-history))
      (loop [idx 0
             peak-pnl 0
             peak-account-value 0
             max-ratio 0]
        (if (>= idx (count pnl-history))
          max-ratio
          (let [pnl (vm-history/history-point-value (nth pnl-history idx))
                max-ratio* (if (and (number? pnl)
                                    (number? peak-account-value)
                                    (pos? peak-account-value))
                             (max max-ratio (/ (- peak-pnl pnl) peak-account-value))
                             max-ratio)
                account-value-at-index (vm-history/history-point-value (nth account-history idx nil))
                [peak-pnl* peak-account-value*]
                (if (and (number? pnl)
                         (>= pnl peak-pnl))
                  [pnl (if (number? account-value-at-index)
                         account-value-at-index
                         peak-account-value)]
                  [peak-pnl peak-account-value])]
            (recur (inc idx)
                   peak-pnl*
                   peak-account-value*
                   max-ratio*)))))))
