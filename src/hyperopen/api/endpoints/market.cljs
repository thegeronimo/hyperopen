(ns hyperopen.api.endpoints.market
  (:require [hyperopen.asset-selector.markets :as markets]
            [hyperopen.asset-selector.perp-annotations :as perp-annotations]
            [hyperopen.api.market-metadata.perp-dexs :as perp-dexs]
            [hyperopen.api.request-policy :as request-policy]
            [hyperopen.utils.data-normalization :refer [normalize-asset-contexts]]
            [hyperopen.utils.interval :refer [interval-to-milliseconds]]))

(defn request-asset-contexts!
  [post-info! opts]
  (let [opts* (request-policy/apply-info-request-policy
               :asset-contexts
               (merge {:priority :high
                       :dedupe-key :asset-contexts}
                      opts))]
    (-> (post-info! {"type" "metaAndAssetCtxs"}
                    opts*)
      (.then normalize-asset-contexts))))

(defn request-meta-and-asset-ctxs!
  [post-info! dex opts]
  (let [body (cond-> {"type" "metaAndAssetCtxs"}
               (and dex (not= dex "")) (assoc "dex" dex))
        dedupe-key (or (:dedupe-key opts)
                       (if (seq dex)
                         [:meta-and-asset-ctxs dex]
                         :asset-contexts))
        opts* (request-policy/apply-info-request-policy
               :meta-and-asset-ctxs
               (merge {:priority :high
                       :dedupe-key dedupe-key}
                      opts))]
    (post-info! body
                opts*)))

(defn request-perp-dexs!
  [post-info! opts]
  (let [opts* (request-policy/apply-info-request-policy
               :perp-dexs
               (merge {:priority :high
                       :dedupe-key :perp-dexs}
                      opts))]
    (-> (post-info! {"type" "perpDexs"}
                    opts*)
      (.then perp-dexs/normalize-perp-dex-payload))))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- explicit-end-time-ms
  [end-time-ms]
  (let [candidate (cond
                    (finite-number? end-time-ms) end-time-ms
                    (string? end-time-ms) (js/Number end-time-ms)
                    :else js/NaN)]
    (when (and (finite-number? candidate)
               (pos? candidate))
      (js/Math.floor candidate))))

(defn request-candle-snapshot!
  [post-info! now-ms-fn coin {:keys [interval bars priority end-time-ms]
                              :or {interval :1d bars 330 priority :high}
                              :as opts}]
  (if (nil? coin)
    (js/Promise.resolve nil)
    (let [explicit-end (explicit-end-time-ms end-time-ms)
          end (or explicit-end (now-ms-fn))
          ms (interval-to-milliseconds interval)
          start (- end (* bars ms))
          interval-s (name interval)
          historical-window? (some? explicit-end)
          window-key (cond-> [:candle-snapshot coin interval-s bars]
                       historical-window? (conj end))
          body {"type" "candleSnapshot"
                "req" {"coin" coin
                       "interval" interval-s
                       "startTime" start
                       "endTime" end}}
          request-opts (request-policy/apply-info-request-policy
                        :candle-snapshot
                        (merge {:priority priority
                                :dedupe-key window-key
                                :cache-key window-key}
                               (dissoc (or opts {})
                                       :interval
                                       :bars
                                       :end-time-ms
                                       :priority)))]
      (post-info! body request-opts))))

(defn request-l2-book-snapshot!
  [post-info! coin opts]
  (let [coin* (some-> coin str .trim)]
    (if-not (seq coin*)
      (js/Promise.resolve nil)
      (let [opts* (or opts {})
            body {"type" "l2Book"
                  "coin" coin*}
            request-opts (request-policy/apply-info-request-policy
                          :l2-book-snapshot
                          (merge {:priority :low
                                  :dedupe-key [:l2-book-snapshot coin*]
                                  :cache-key [:l2-book-snapshot coin*]}
                                 opts*))]
        (post-info! body request-opts)))))

(defn request-spot-meta!
  [post-info! opts]
  (post-info! {"type" "spotMeta"}
              (request-policy/apply-info-request-policy
               :spot-meta
               (merge {:priority :high
                       :dedupe-key :spot-meta}
                      opts))))

(defn request-outcome-meta!
  [post-info! opts]
  (post-info! {"type" "outcomeMeta"}
              (request-policy/apply-info-request-policy
               :outcome-meta
               (merge {:priority :high
                       :dedupe-key :outcome-meta}
                      opts))))

(defn request-perp-annotations!
  "Hyperliquid's curated HIP-3 display names, categories and search keywords,
  normalized to `{coin annotation}`."
  [post-info! opts]
  (-> (post-info! {"type" "perpConciseAnnotations"}
                  (request-policy/apply-info-request-policy
                   :perp-annotations
                   (merge {:priority :low
                           :dedupe-key :perp-annotations}
                          opts)))
      (.then perp-annotations/normalize-perp-annotations)))

(defn request-perp-annotation!
  [post-info! coin opts]
  (if-not (and (string? coin) (seq coin))
    (js/Promise.resolve nil)
    (post-info! {"type" "perpAnnotation"
                 "coin" coin}
                (request-policy/apply-info-request-policy
                 :perp-annotation
                 (merge {:priority :low
                         :dedupe-key [:perp-annotation coin]
                         :cache-key [:perp-annotation coin]}
                        opts)))))

(defn normalize-spot-meta-and-asset-ctxs
  "Shape the spotMetaAndAssetCtxs tuple [spotMeta spotAssetCtxs] like the
  spot slice of the retired webData2 aggregate so downstream consumers
  (market-loader, api-service snapshot cache) keep reading :spotAssetCtxs."
  [payload]
  (if (sequential? payload)
    {:spotMeta (first payload)
     :spotAssetCtxs (second payload)}
    {}))

(defn request-public-webdata2!
  "Public spot market snapshot. Historically {\"type\" \"webData2\"} with the
  zero address; Hyperliquid deprecated webData2, so this now posts
  spotMetaAndAssetCtxs and normalizes to the same consumer-facing shape."
  [post-info! opts]
  (-> (post-info! {"type" "spotMetaAndAssetCtxs"}
                  (request-policy/apply-info-request-policy
                   :public-webdata2
                   (merge {:priority :high
                           :dedupe-key :public-webdata2}
                          opts)))
      (.then normalize-spot-meta-and-asset-ctxs)))

(def ^:private default-market-funding-history-page-size
  500)

(defn- parse-decimal
  [value]
  (cond
    (number? value)
    (when (finite-number? value)
      value)

    (string? value)
    (let [parsed (js/parseFloat value)]
      (when (finite-number? parsed)
        parsed))

    :else
    nil))

(defn- parse-ms
  [value]
  (when-let [parsed (parse-decimal value)]
    (js/Math.floor parsed)))

(defn- normalize-market-funding-history-row
  [row]
  (when (map? row)
    (let [time-ms (or (parse-ms (:time row))
                      (parse-ms (:time-ms row)))
          coin (when (string? (:coin row))
                 (:coin row))
          funding-rate (parse-decimal (or (:fundingRate row)
                                          (:funding-rate row)))
          premium (parse-decimal (:premium row))]
      (when (and (number? time-ms)
                 (seq coin)
                 (number? funding-rate))
        {:coin coin
         :time-ms time-ms
         :time time-ms
         :funding-rate-raw funding-rate
         :fundingRate funding-rate
         :premium premium}))))

(defn- normalize-market-funding-history-rows
  [rows]
  (->> rows
       (keep normalize-market-funding-history-row)
       (sort-by :time-ms)
       vec))

(defn- market-funding-history-seq
  [payload]
  (cond
    (sequential? payload)
    payload

    (map? payload)
    (let [data (:data payload)
          nested (or (:fundingHistory payload)
                     (:funding-history payload)
                     (when (map? data)
                       (or (:fundingHistory data)
                           (:funding-history data)))
                     data)]
      (if (sequential? nested)
        nested
        []))

    :else
    []))

(defn- normalize-positive-int
  [value fallback]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (if (and (number? num)
             (not (js/isNaN num))
             (pos? num))
      (js/Math.floor num)
      fallback)))

(defn- market-funding-history-page-size
  [opts]
  (normalize-positive-int (:market-funding-history-page-size opts)
                          default-market-funding-history-page-size))

(defn- strip-market-funding-history-pagination-opts
  [opts]
  (dissoc (or opts {})
          :start-time-ms
          :end-time-ms
          :startTime
          :endTime
          :market-funding-history-page-size))

(defn- market-funding-history-request-body
  [coin start-time-ms end-time-ms]
  (cond-> {"type" "fundingHistory"
           "coin" coin}
    (number? start-time-ms) (assoc "startTime" (js/Math.floor start-time-ms))
    (number? end-time-ms) (assoc "endTime" (js/Math.floor end-time-ms))))

(defn- market-funding-history-dedupe-key
  [coin start-time-ms end-time-ms opts]
  (if (contains? opts :dedupe-key)
    [:market-funding-history-page
     (:dedupe-key opts)
     start-time-ms
     end-time-ms]
    [:market-funding-history coin start-time-ms end-time-ms]))

(defn- market-funding-history-cache-key
  [start-time-ms end-time-ms opts]
  (when (contains? opts :cache-key)
    [:market-funding-history-page-cache
     (:cache-key opts)
     start-time-ms
     end-time-ms]))

(defn- market-funding-history-request-opts
  [coin start-time-ms end-time-ms opts]
  (let [start-time* (when (number? start-time-ms)
                      (js/Math.floor start-time-ms))
        end-time* (when (number? end-time-ms)
                    (js/Math.floor end-time-ms))
        opts* (strip-market-funding-history-pagination-opts opts)
        request-opts (cond-> (merge {:priority :high
                                     :dedupe-key (market-funding-history-dedupe-key
                                                  coin
                                                  start-time*
                                                  end-time*
                                                  opts*)}
                                    (dissoc opts* :dedupe-key :cache-key))
                       (contains? opts* :cache-key)
                       (assoc :cache-key (market-funding-history-cache-key
                                          start-time*
                                          end-time*
                                          opts*)))]
    (request-policy/apply-info-request-policy
     :market-funding-history
     request-opts)))

(defn- fetch-market-funding-history-page!
  [post-info! coin start-time-ms end-time-ms opts]
  (post-info! (market-funding-history-request-body coin
                                                   start-time-ms
                                                   end-time-ms)
              (market-funding-history-request-opts coin
                                                   start-time-ms
                                                   end-time-ms
                                                   opts)))

(defn- merge-market-funding-history-rows
  [existing incoming]
  (->> (concat (or existing []) (or incoming []))
       (reduce (fn [acc row]
                 (assoc acc (:time-ms row) row))
               {})
       vals
       (sort-by :time-ms)
       vec))

(defn- request-market-funding-history-loop!
  [post-info! coin start-time-ms end-time-ms opts acc]
  (-> (fetch-market-funding-history-page! post-info! coin start-time-ms end-time-ms opts)
      (.then
       (fn [payload]
         (let [raw-rows (market-funding-history-seq payload)
               rows (normalize-market-funding-history-rows raw-rows)
               acc* (merge-market-funding-history-rows acc rows)
               last-time-ms (some-> rows last :time-ms)
               next-start-ms (when (number? last-time-ms)
                               (inc last-time-ms))
               capped-page? (>= (count raw-rows)
                                (market-funding-history-page-size opts))
               advanced? (and (number? next-start-ms)
                              (not= next-start-ms start-time-ms))
               within-request-window? (or (not (number? end-time-ms))
                                          (<= next-start-ms end-time-ms))]
           (if (and capped-page?
                    advanced?
                    within-request-window?)
             (request-market-funding-history-loop! post-info!
                                                   coin
                                                   next-start-ms
                                                   end-time-ms
                                                   opts
                                                   acc*)
             acc*))))))

(defn request-market-funding-history!
  [post-info! coin opts]
  (let [coin* (some-> coin str .trim)
        start-time-ms (or (:start-time-ms opts)
                          (:startTime opts))
        end-time-ms (or (:end-time-ms opts)
                        (:endTime opts))]
    (if-not (seq coin*)
      (js/Promise.resolve [])
      (request-market-funding-history-loop! post-info!
                                            coin*
                                            start-time-ms
                                            end-time-ms
                                            opts
                                            []))))

(defn request-predicted-fundings!
  [post-info! opts]
  (post-info! {"type" "predictedFundings"}
              (request-policy/apply-info-request-policy
               :predicted-fundings
               (merge {:priority :high
                       :dedupe-key :predicted-fundings}
                      opts))))

(defn- build-market-index-by-key
  [markets]
  (reduce-kv (fn [acc idx market]
               (if-let [market-key (:key market)]
                 (assoc acc market-key idx)
                 acc))
             {}
             (vec (or markets []))))

(defn build-market-state
  ([now-ms-fn active-asset phase dexs spot-meta spot-asset-ctxs perp-results]
   (build-market-state now-ms-fn
                       active-asset
                       phase
                       dexs
                       spot-meta
                       spot-asset-ctxs
                       perp-results
                       {:outcomes [] :questions []}))
  ([now-ms-fn active-asset phase dexs spot-meta spot-asset-ctxs perp-results outcome-meta]
   (let [dexs-with-default (if (= phase :bootstrap)
                             [nil]
                             (vec (cons nil (vec dexs))))
         token-by-index (into {}
                              (map (fn [{:keys [index name]}]
                                     [index name]))
                              (:tokens spot-meta))
         perp-markets (->> (map-indexed vector (map vector dexs-with-default perp-results))
                           (mapcat (fn [[perp-dex-index [dex [meta asset-ctxs]]]]
                                     (markets/build-perp-markets
                                      meta
                                      asset-ctxs
                                      token-by-index
                                      :dex dex
                                      :perp-dex-index perp-dex-index)))
                           vec)
         spot-markets (markets/build-spot-markets spot-meta spot-asset-ctxs)
         outcome-markets (markets/build-outcome-markets outcome-meta spot-asset-ctxs)
         all-markets (vec (concat perp-markets spot-markets outcome-markets))
         market-by-key (into {}
                             (map (fn [m] [(:key m) m]))
                             all-markets)
         market-index-by-key (build-market-index-by-key all-markets)
         active-market (when active-asset
                         (markets/resolve-market-by-coin
                          market-by-key
                          active-asset))]
     {:markets all-markets
      :market-by-key market-by-key
      :market-index-by-key market-index-by-key
      :active-market active-market
      :loaded-at-ms (now-ms-fn)})))
