(ns hyperopen.api.endpoints.account.funding-history
  (:require [clojure.string :as str]
            [hyperopen.api.endpoints.account.common :as common]
            [hyperopen.api.request-policy :as request-policy]))

(defn- strip-user-funding-pagination-opts
  [opts]
  (dissoc (or opts {})
          :wait-ms-fn
          :user-funding-page-min-delay-ms
          :user-funding-page-max-delay-ms
          :user-funding-page-size))

(defn- user-funding-page-delay-ms
  [rows opts]
  (let [{:keys [min-delay-ms max-delay-ms page-size]}
        (request-policy/user-funding-pagination-policy opts)
        row-count (max 0 (count (or rows [])))
        load-factor (max 1 (/ row-count page-size))
        scaled-delay-ms (js/Math.ceil (* min-delay-ms load-factor))]
    (-> scaled-delay-ms
        (max min-delay-ms)
        (min max-delay-ms))))

(defn- user-funding-request-body
  [address start-time-ms end-time-ms]
  (cond-> {"type" "userFunding"
           "user" address}
    (number? start-time-ms) (assoc "startTime" (js/Math.floor start-time-ms))
    (number? end-time-ms) (assoc "endTime" (js/Math.floor end-time-ms))))

(defn- fetch-user-funding-page!
  [post-info! address start-time-ms end-time-ms opts]
  (let [requested-address (some-> address str str/lower-case)
        start-time* (when (number? start-time-ms)
                      (js/Math.floor start-time-ms))
        end-time* (when (number? end-time-ms)
                    (js/Math.floor end-time-ms))
        request-opts (strip-user-funding-pagination-opts opts)
        opts* (request-policy/apply-info-request-policy
               :user-funding-history
               (merge {:priority :high
                       :dedupe-key [:user-funding-history requested-address start-time* end-time*]}
                      request-opts))]
    (post-info! (user-funding-request-body address start-time-ms end-time-ms)
                opts*)))

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
   address start-time-ms end-time-ms opts acc wait-ms-fn]
  (-> (fetch-user-funding-page! post-info! address start-time-ms end-time-ms opts)
      (.then
       (fn [payload]
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
                 (let [delay-ms (user-funding-page-delay-ms rows opts)]
                   (-> ((or wait-ms-fn common/wait-ms) delay-ms)
                       (.then
                        (fn []
                          (fetch-user-funding-history-loop! post-info!
                                                            normalize-info-funding-rows-fn
                                                            sort-funding-history-rows-fn
                                                            address
                                                            next-start-ms
                                                            end-time-ms
                                                            opts
                                                            acc*
                                                            wait-ms-fn)))))))
             (sort-funding-history-rows-fn acc)))))))

(defn request-user-funding-history!
  [post-info! normalize-info-funding-rows-fn sort-funding-history-rows-fn
   address start-time-ms end-time-ms opts]
  (if-not address
    (js/Promise.resolve [])
    (let [opts* (merge {:priority :high}
                       (or opts {}))
          wait-ms-fn (or (:wait-ms-fn opts*)
                         common/wait-ms)]
      (fetch-user-funding-history-loop! post-info!
                                        normalize-info-funding-rows-fn
                                        sort-funding-history-rows-fn
                                        address
                                        start-time-ms
                                        end-time-ms
                                        opts*
                                        []
                                        wait-ms-fn))))
