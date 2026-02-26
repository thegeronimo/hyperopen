(ns hyperopen.api.endpoints.account
  (:require [clojure.string :as str]))

(defn- user-funding-request-body
  [address start-time-ms end-time-ms]
  (cond-> {"type" "userFunding"
           "user" address}
    (number? start-time-ms) (assoc "startTime" (js/Math.floor start-time-ms))
    (number? end-time-ms) (assoc "endTime" (js/Math.floor end-time-ms))))

(defn- fetch-user-funding-page!
  [post-info! address start-time-ms end-time-ms opts]
  (post-info! (user-funding-request-body address start-time-ms end-time-ms)
              (merge {:priority :high}
                     opts)))

(defn- funding-history-seq
  [payload]
  (cond
    (sequential? payload)
    payload

    (map? payload)
    (let [data (:data payload)
          nested (or (:fundings payload)
                     (:userFunding payload)
                     (:userFundings payload)
                     (when (map? data)
                       (or (:fundings data)
                           (:userFunding data)
                           (:userFundings data)))
                     data)]
      (if (sequential? nested) nested []))

    :else
    []))

(defn- warn-funding-normalization-drop!
  [start-time-ms end-time-ms raw-rows]
  (let [console-object (some-> js/globalThis .-console)
        warn-fn (some-> console-object .-warn)]
    (when (and (fn? warn-fn)
               (seq raw-rows))
      (.warn console-object
             "Funding history normalization dropped all rows on a non-empty page."
             (clj->js {:event "funding-history-normalization-drop"
                       :start-time-ms start-time-ms
                       :end-time-ms end-time-ms
                       :raw-row-count (count raw-rows)})))))

(defn- fetch-user-funding-history-loop!
  [post-info! normalize-info-funding-rows-fn sort-funding-history-rows-fn
   address start-time-ms end-time-ms opts acc]
  (-> (fetch-user-funding-page! post-info! address start-time-ms end-time-ms opts)
      (.then (fn [payload]
               (let [raw-rows (funding-history-seq payload)
                     rows (normalize-info-funding-rows-fn raw-rows)]
                 (when (and (seq raw-rows)
                            (empty? rows))
                   (warn-funding-normalization-drop! start-time-ms end-time-ms raw-rows))
                 (if (seq rows)
                   (let [max-time-ms (apply max (map :time-ms rows))
                         next-start-ms (inc max-time-ms)
                         acc* (into acc rows)
                         exhausted? (or (nil? max-time-ms)
                                        (= next-start-ms start-time-ms)
                                        (and (number? end-time-ms)
                                             (> next-start-ms end-time-ms)))]
                     (if exhausted?
                       (sort-funding-history-rows-fn acc*)
                       (fetch-user-funding-history-loop! post-info!
                                                        normalize-info-funding-rows-fn
                                                        sort-funding-history-rows-fn
                                                        address
                                                        next-start-ms
                                                        end-time-ms
                                                        opts
                                                        acc*)))
                   (sort-funding-history-rows-fn acc)))))))

(defn request-user-funding-history!
  [post-info! normalize-info-funding-rows-fn sort-funding-history-rows-fn
   address start-time-ms end-time-ms opts]
  (if-not address
    (js/Promise.resolve [])
    (fetch-user-funding-history-loop! post-info!
                                      normalize-info-funding-rows-fn
                                      sort-funding-history-rows-fn
                                      address
                                      start-time-ms
                                      end-time-ms
                                      (merge {:priority :high}
                                             (or opts {}))
                                      [])))

(defn request-spot-clearinghouse-state!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve nil)
    (post-info! {"type" "spotClearinghouseState"
                 "user" address}
                (merge {:priority :high}
                       opts))))

(defn normalize-user-abstraction-mode
  [abstraction]
  (let [abstraction* (some-> abstraction str str/trim)]
    (case abstraction*
      "unifiedAccount" :unified
      "portfolioMargin" :unified
      "dexAbstraction" :unified
      "default" :classic
      "disabled" :classic
      :classic)))

(defn request-user-abstraction!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve nil)
    (let [requested-address (some-> address str str/lower-case)
          opts* (merge {:priority :high
                        :dedupe-key [:user-abstraction requested-address]}
                       opts)]
      (post-info! {"type" "userAbstraction"
                   "user" address}
                  opts*))))

(defn request-clearinghouse-state!
  [post-info! address dex opts]
  (if-not address
    (js/Promise.resolve nil)
    (let [body (cond-> {"type" "clearinghouseState"
                        "user" address}
                 (and dex (not= dex "")) (assoc "dex" dex))]
      (post-info! body
                  (merge {:priority :high}
                         opts)))))

(defn- normalize-portfolio-summary-key
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      (let [token (-> text
                      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                      str/lower-case
                      (str/replace #"[^a-z0-9]+" "-")
                      (str/replace #"(^-+)|(-+$)" ""))]
        (case token
          "day" :day
          "week" :week
          "month" :month
          "alltime" :all-time
          "all-time" :all-time
          "perpday" :perp-day
          "perp-day" :perp-day
          "perpweek" :perp-week
          "perp-week" :perp-week
          "perpmonth" :perp-month
          "perp-month" :perp-month
          "perpalltime" :perp-all-time
          "perp-all-time" :perp-all-time
          (keyword token))))))

(defn- portfolio-summary-pairs
  [payload]
  (cond
    (sequential? payload)
    (keep (fn [entry]
            (cond
              (and (sequential? entry)
                   (= 2 (count entry)))
              (let [[k v] entry]
                [k v])

              (map? entry)
              (let [account-key (or (:account entry)
                                    (:key entry)
                                    (:range entry))]
                (when account-key
                  [account-key entry]))

              :else
              nil))
          payload)

    (map? payload)
    (let [data (:data payload)]
      (cond
        (map? data)
        (seq data)

        (map? (:portfolio payload))
        (seq (:portfolio payload))

        :else
        (seq payload)))

    :else
    []))

(defn normalize-portfolio-summary
  [payload]
  (reduce (fn [acc [raw-key row]]
            (let [summary-key (normalize-portfolio-summary-key raw-key)]
              (if (and summary-key (map? row))
                (assoc acc summary-key row)
                acc)))
          {}
          (portfolio-summary-pairs payload)))

(defn request-portfolio!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve {})
    (let [requested-address (some-> address str str/lower-case)
          opts* (merge {:priority :high
                        :dedupe-key [:portfolio requested-address]}
                       opts)]
      (-> (post-info! {"type" "portfolio"
                       "user" address}
                      opts*)
          (.then normalize-portfolio-summary)))))

(defn request-user-fees!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve nil)
    (let [requested-address (some-> address str str/lower-case)
          opts* (merge {:priority :high
                        :dedupe-key [:user-fees requested-address]}
                       opts)]
      (post-info! {"type" "userFees"
                   "user" address}
                  opts*))))

(defn- non-funding-ledger-updates-seq
  [payload]
  (cond
    (sequential? payload)
    (vec payload)

    (map? payload)
    (let [data (:data payload)
          nested (or (:nonFundingLedgerUpdates payload)
                     (:userNonFundingLedgerUpdates payload)
                     (when (map? data)
                       (or (:nonFundingLedgerUpdates data)
                           (:userNonFundingLedgerUpdates data)))
                     data)]
      (if (sequential? nested)
        (vec nested)
        []))

    :else
    []))

(defn request-user-non-funding-ledger-updates!
  [post-info! address start-time-ms end-time-ms opts]
  (if-not address
    (js/Promise.resolve [])
    (let [requested-address (some-> address str str/lower-case)
          start-time* (when (number? start-time-ms)
                        (js/Math.floor start-time-ms))
          end-time* (when (number? end-time-ms)
                      (js/Math.floor end-time-ms))
          body (cond-> {"type" "userNonFundingLedgerUpdates"
                        "user" address}
                 (number? start-time*) (assoc "startTime" start-time*)
                 (number? end-time*) (assoc "endTime" end-time*))
          opts* (merge {:priority :high
                        :dedupe-key [:user-non-funding-ledger requested-address start-time* end-time*]}
                       opts)]
      (-> (post-info! body opts*)
          (.then non-funding-ledger-updates-seq)))))
