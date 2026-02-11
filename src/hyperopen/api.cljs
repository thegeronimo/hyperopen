(ns hyperopen.api
  (:require [clojure.string :as str]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.utils.data-normalization :refer [normalize-asset-contexts]]
            [hyperopen.utils.interval :refer [interval-to-milliseconds]]))

(def info-url "https://api.hyperliquid.xyz/info")
(def ^:private info-max-retries 4)
(def ^:private info-base-retry-ms 400)
(def ^:private info-max-retry-ms 5000)
(def ^:private info-max-inflight 4)
(def ^:private high-priority-burst 3)
(def default-funding-history-window-ms (* 7 24 60 60 1000))

(defonce ^:private info-cooldown-until-ms (atom 0))
(defonce ^:private single-flight-promises (atom {}))
(defonce ^:private public-webdata2-cache (atom nil))
(defonce ^:private ensure-perp-dexs-flight (atom nil))
(defonce ^:private request-runtime
  (atom {:inflight 0
         :queues {:high []
                  :low []}
         :high-burst 0
         :stats {:started {:high 0 :low 0}
                 :completed {:high 0 :low 0}
                 :rate-limited 0
                 :max-inflight-observed 0}}))

(declare pump-request-queue!)

(defn- wait-ms
  [ms]
  (js/Promise.
   (fn [resolve _]
     (js/setTimeout resolve ms))))

(defn- now-ms []
  (.now js/Date))

(defn- finite-number?
  [value]
  (and (number? value)
       (not (js/isNaN value))
       (js/isFinite value)))

(defn- parse-decimal
  [value]
  (cond
    (number? value)
    (when (finite-number? value) value)

    (string? value)
    (let [num (js/parseFloat value)]
      (when (finite-number? num) num))

    :else nil))

(defn- parse-ms
  [value]
  (when-let [num (parse-decimal value)]
    (js/Math.floor num)))

(defn funding-position-side
  [signed-size]
  (cond
    (pos? signed-size) :long
    (neg? signed-size) :short
    :else :flat))

(defn funding-history-row-id
  [time-ms coin signed-size payment-usdc funding-rate]
  (str time-ms "|" coin "|" signed-size "|" payment-usdc "|" funding-rate))

(defn- normalize-funding-row
  [{:keys [time-ms coin signed-size payment-usdc funding-rate source]}]
  (let [time-ms* (parse-ms time-ms)
        signed-size* (parse-decimal signed-size)
        payment-usdc* (parse-decimal payment-usdc)
        funding-rate* (parse-decimal funding-rate)
        coin* (when (string? coin) coin)]
    (when (and time-ms*
               coin*
               (number? signed-size*)
               (number? payment-usdc*)
               (number? funding-rate*))
      {:id (funding-history-row-id time-ms* coin* signed-size* payment-usdc* funding-rate*)
       :time-ms time-ms*
       :time time-ms*
       :coin coin*
       :size-raw (js/Math.abs signed-size*)
       :position-size-raw signed-size*
       :positionSize signed-size*
       :position-side (funding-position-side signed-size*)
       :payment-usdc-raw payment-usdc*
       :payment payment-usdc*
       :funding-rate-raw funding-rate*
       :fundingRate funding-rate*
       :source source})))

(defn normalize-info-funding-row
  [row]
  (let [delta (:delta row)
        funding-delta? (or (nil? (:type delta))
                           (= "funding" (:type delta)))]
    (when (and (map? delta) funding-delta?)
      (normalize-funding-row {:time-ms (:time row)
                              :coin (:coin delta)
                              :signed-size (:szi delta)
                              :payment-usdc (:usdc delta)
                              :funding-rate (:fundingRate delta)
                              :source :info}))))

(defn normalize-info-funding-rows
  [rows]
  (->> rows
       (map normalize-info-funding-row)
       (remove nil?)
       vec))

(defn normalize-ws-funding-row
  [row]
  (normalize-funding-row {:time-ms (:time row)
                          :coin (:coin row)
                          :signed-size (:szi row)
                          :payment-usdc (:usdc row)
                          :funding-rate (:fundingRate row)
                          :source :ws}))

(defn normalize-ws-funding-rows
  [rows]
  (->> rows
       (map normalize-ws-funding-row)
       (remove nil?)
       vec))

(defn sort-funding-history-rows
  [rows]
  (->> rows
       (sort-by (fn [row]
                  [(- (or (:time-ms row) 0))
                   (or (:id row) "")]))
       vec))

(defn merge-funding-history-rows
  [existing incoming]
  (->> (concat (or existing []) (or incoming []))
       (reduce (fn [acc row]
                 (if (and (map? row) (seq (:id row)))
                   (assoc acc (:id row) row)
                   acc))
               {})
       vals
       sort-funding-history-rows
       vec))

(defn normalize-funding-history-filters
  ([filters]
   (normalize-funding-history-filters filters (now-ms)))
  ([filters now]
   (let [coin-set (->> (or (:coin-set filters) #{})
                       (keep (fn [coin]
                               (when (and (string? coin)
                                          (seq coin))
                                 coin)))
                       set)
         default-end (or (parse-ms now) 0)
         default-start (max 0 (- default-end default-funding-history-window-ms))
         start-candidate (parse-ms (:start-time-ms filters))
         end-candidate (parse-ms (:end-time-ms filters))
         start-time-ms (or start-candidate default-start)
         end-time-ms (or end-candidate default-end)
         [start-ms end-ms] (if (> start-time-ms end-time-ms)
                             [end-time-ms start-time-ms]
                             [start-time-ms end-time-ms])]
     {:coin-set coin-set
      :start-time-ms start-ms
      :end-time-ms end-ms})))

(defn filter-funding-history-rows
  [rows filters]
  (let [{:keys [coin-set start-time-ms end-time-ms]} (normalize-funding-history-filters filters)
        use-coin-filter? (seq coin-set)]
    (->> (or rows [])
         (filter (fn [row]
                   (let [time-ms (:time-ms row)
                         coin (:coin row)]
                     (and (number? time-ms)
                          (>= time-ms start-time-ms)
                          (<= time-ms end-time-ms)
                          (or (not use-coin-filter?)
                              (contains? coin-set coin))))))
         sort-funding-history-rows
         vec)))

(defn- normalize-priority
  [priority]
  (if (= priority :low) :low :high))

(defn- retryable-status?
  [status]
  (contains? #{429 500 502 503 504} status))

(defn- retry-delay-ms
  [attempt]
  (let [exp-delay (* info-base-retry-ms (js/Math.pow 2 attempt))
        capped-delay (min exp-delay info-max-retry-ms)]
    (js/Math.round capped-delay)))

(defn- make-http-error
  [status]
  (let [err (js/Error. (str "Hyperliquid /info request failed with HTTP " status))]
    (aset err "status" status)
    err))

(defn- maybe-wait-for-cooldown!
  []
  (let [remaining-ms (- @info-cooldown-until-ms (now-ms))]
    (if (pos? remaining-ms)
      (wait-ms remaining-ms)
      (js/Promise.resolve nil))))

(defn- next-request-priority
  [{:keys [queues high-burst]}]
  (let [has-high? (seq (get queues :high))
        has-low? (seq (get queues :low))]
    (cond
      (and has-high?
           (or (not has-low?)
               (< high-burst high-priority-burst)))
      :high

      has-low?
      :low

      has-high?
      :high

      :else
      nil)))

(defn- dequeue-request-task!
  []
  (let [selected (atom nil)]
    (swap! request-runtime
           (fn [state]
             (let [priority (next-request-priority state)
                   inflight (:inflight state)]
               (if (or (nil? priority)
                       (>= inflight info-max-inflight))
                 state
                 (let [queue (get-in state [:queues priority])
                       task (first queue)
                       next-inflight (inc inflight)]
                   (reset! selected task)
                   (-> state
                       (assoc :inflight next-inflight)
                       (assoc :high-burst (if (= priority :high)
                                            (inc (:high-burst state))
                                            0))
                       (assoc-in [:queues priority] (vec (rest queue)))
                       (update-in [:stats :started priority] (fnil inc 0))
                       (update-in [:stats :max-inflight-observed] (fnil max 0) next-inflight)))))))
    @selected))

(defn- mark-request-complete!
  [priority]
  (let [priority* (normalize-priority priority)]
    (swap! request-runtime
           (fn [state]
             (-> state
                 (update :inflight #(max 0 (dec %)))
                 (update-in [:stats :completed priority*] (fnil inc 0))))))
  (pump-request-queue!))

(defn- start-request-task!
  [{:keys [priority request-fn resolve reject]}]
  (try
    (-> (js/Promise.resolve (request-fn))
        (.then (fn [result]
                 (resolve result)
                 result))
        (.catch (fn [err]
                  (reject err)
                  (js/Promise.reject err)))
        (.finally (fn []
                    (mark-request-complete! priority))))
    (catch :default e
      (reject e)
      (mark-request-complete! priority))))

(defn- pump-request-queue!
  []
  (loop []
    (when (< (:inflight @request-runtime) info-max-inflight)
      (when-let [task (dequeue-request-task!)]
        (start-request-task! task)
        (recur)))))

(defn- enqueue-request!
  [priority request-fn]
  (let [priority* (normalize-priority priority)]
    (js/Promise.
     (fn [resolve reject]
       (swap! request-runtime update-in [:queues priority*]
              (fnil conj [])
              {:priority priority*
               :request-fn request-fn
               :resolve resolve
               :reject reject})
       (pump-request-queue!)))))

(defn- enqueue-info-request!
  [priority request-fn]
  (enqueue-request!
   priority
   (fn []
     (-> (maybe-wait-for-cooldown!)
         (.then (fn []
                  (request-fn)))))))

(defn- track-rate-limit!
  []
  (swap! request-runtime update-in [:stats :rate-limited] (fnil inc 0)))

(defn- clone-promise-result
  [value]
  ;; Single-flight can fan out one fetch response to multiple consumers.
  ;; Each consumer needs an independent body stream for `.json`.
  (if (and value (fn? (aget value "clone")))
    (try
      (.clone value)
      (catch :default _
        value))
    value))

(defn- with-single-flight!
  [dedupe-key promise-fn]
  (if (nil? dedupe-key)
    (promise-fn)
    (if-let [existing (get @single-flight-promises dedupe-key)]
      (.then existing clone-promise-result)
      (let [tracked-ref (atom nil)
            tracked
            (-> (promise-fn)
                (.finally
                 (fn []
                   (let [tracked* @tracked-ref]
                     (swap! single-flight-promises
                            (fn [state]
                              (if (identical? (get state dedupe-key) tracked*)
                                (dissoc state dedupe-key)
                                state)))))))]
        (reset! tracked-ref tracked)
        (swap! single-flight-promises assoc dedupe-key tracked)
        (.then tracked clone-promise-result)))))

(defn get-request-stats
  []
  (:stats @request-runtime))

(defn reset-request-runtime!
  []
  (reset! info-cooldown-until-ms 0)
  (reset! single-flight-promises {})
  (reset! public-webdata2-cache nil)
  (reset! ensure-perp-dexs-flight nil)
  (reset! request-runtime
          {:inflight 0
           :queues {:high []
                    :low []}
           :high-burst 0
           :stats {:started {:high 0 :low 0}
                   :completed {:high 0 :low 0}
                   :rate-limited 0
                   :max-inflight-observed 0}}))

(defn- post-info-request!
  [body opts attempt]
  (let [priority (normalize-priority (:priority opts))]
    (-> (enqueue-info-request!
         priority
         (fn []
           (js/fetch info-url
                     (clj->js {:method "POST"
                               :headers {"Content-Type" "application/json"}
                               :body (js/JSON.stringify (clj->js body))}))))
        (.then
         (fn [resp]
           (let [status (.-status resp)]
             (cond
               (.-ok resp)
               resp

               (and (retryable-status? status)
                    (< attempt info-max-retries))
               (let [delay-ms (retry-delay-ms attempt)]
                 (when (= status 429)
                   (track-rate-limit!)
                   (swap! info-cooldown-until-ms max (+ (now-ms) delay-ms)))
                 (println "Rate-limited /info request, retrying in" delay-ms "ms. status:" status "attempt:" (inc attempt))
                 (-> (wait-ms delay-ms)
                     (.then (fn []
                              (post-info-request! body opts (inc attempt))))))

               :else
               (throw (make-http-error status))))))
        (.catch
         (fn [err]
           (let [status (aget err "status")]
             (if (and (< attempt info-max-retries)
                      (or (nil? status)
                          (retryable-status? status)))
               (let [delay-ms (retry-delay-ms attempt)]
                 (when (= status 429)
                   (track-rate-limit!)
                   (swap! info-cooldown-until-ms max (+ (now-ms) delay-ms)))
                 (println "Error during /info request, retrying in" delay-ms "ms. attempt:" (inc attempt) "error:" err)
                 (-> (wait-ms delay-ms)
                     (.then (fn []
                              (post-info-request! body opts (inc attempt))))))
               (js/Promise.reject err))))))))

(defn- post-info!
  ([body]
   (post-info! body {}))
  ([body opts]
   (let [opts* (merge {:priority :high} (or opts {}))
         dedupe-key (:dedupe-key opts*)]
     (with-single-flight!
      dedupe-key
      (fn []
        (post-info-request! body (dissoc opts* :dedupe-key) 0)))))
  ([body opts attempt]
   (post-info-request! body (merge {:priority :high} (or opts {})) attempt)))

(defn- dex-names-from-response
  [data]
  (->> data
       (keep (fn [entry]
               (when (and (map? entry)
                          (seq (:name entry)))
                 (:name entry))))
       vec))

(defn fetch-asset-contexts!
  ([store]
   (fetch-asset-contexts! store {}))
  ([store opts]
   (println "Fetching perpetual asset contexts...")
   (-> (post-info! {"type" "metaAndAssetCtxs"}
                   (merge {:priority :high} opts))
       (.then #(.json %))
       (.then (fn [payload]
                (let [data (js->clj payload :keywordize-keys true)
                      normalised (normalize-asset-contexts data)]
                  (swap! store assoc-in [:asset-contexts] normalised)
                  (println "Loaded" (count normalised) "assets")
                  normalised)))
       (.catch (fn [err]
                 (println "Error fetching asset contexts:" err)
                 (swap! store assoc-in [:asset-contexts :error] (str err))
                 (js/Promise.reject err))))))

(defn fetch-meta-and-asset-ctxs!
  "Fetch metaAndAssetCtxs for the default perp DEX or a named DEX."
  ([dex]
   (fetch-meta-and-asset-ctxs! dex {}))
  ([dex opts]
   (let [body (cond-> {"type" "metaAndAssetCtxs"}
                (and dex (not= dex "")) (assoc "dex" dex))
         dedupe-key (or (:dedupe-key opts)
                        (if (seq dex)
                          [:meta-and-asset-ctxs dex]
                          :meta-and-asset-ctxs-default))]
     (-> (post-info! body
                     (merge {:priority :high
                             :dedupe-key dedupe-key}
                            opts))
         (.then #(.json %))
         (.then #(js->clj % :keywordize-keys true))))))

(defn fetch-perp-dexs!
  "Fetch the list of available perp DEXes. The default DEX is omitted from
  the response, so we only store named DEXes."
  ([store]
   (fetch-perp-dexs! store {}))
  ([store opts]
   (println "Fetching perp DEX list...")
   (-> (post-info! {"type" "perpDexs"}
                   (merge {:priority :high} opts))
       (.then #(.json %))
       (.then (fn [payload]
                (let [data (js->clj payload :keywordize-keys true)
                      dex-names (dex-names-from-response data)]
                  (swap! store assoc-in [:perp-dexs] dex-names)
                  dex-names)))
       (.catch (fn [err]
                 (println "Error fetching perp DEX list:" err)
                 (swap! store assoc-in [:perp-dexs-error] (str err))
                 (js/Promise.reject err))))))

(defn fetch-candle-snapshot!
  "Fetch `bars` worth of candles for the active asset at keyword interval (e.g. :1m, :1h).
   Defaults to :1d interval and 330 bars if not specified."
  [store & {:keys [interval bars priority]
            :or {interval :1d bars 330 priority :high}}]
  (let [active-asset (:active-asset @store)]
    (if (nil? active-asset)
      (do
        (println "No active asset selected, skipping candle fetch")
        (js/Promise.resolve nil))
      (let [now (js/Date.now)
            ms (interval-to-milliseconds interval)
            start (- now (* bars ms))
            interval-s (name interval)
            body {"type" "candleSnapshot"
                  "req" {"coin" active-asset
                          "interval" interval-s
                          "startTime" start
                          "endTime" now}}]
        (println "Fetching" bars interval-s "bars for" active-asset)
        (-> (post-info! body {:priority priority})
            (.then #(.json %))
            (.then (fn [payload]
                     (let [data (js->clj payload :keywordize-keys true)]
                       (swap! store assoc-in [:candles active-asset interval] data)
                       data)))
            (.catch (fn [err]
                      (println "Error fetching" err)
                      (swap! store assoc-in [:candles active-asset interval :error] (str err))
                      (js/Promise.reject err))))))))

(defn fetch-frontend-open-orders!
  ([store address]
   (fetch-frontend-open-orders! store address nil {}))
  ([store address dex-or-opts]
   (if (map? dex-or-opts)
     (fetch-frontend-open-orders! store address nil dex-or-opts)
     (fetch-frontend-open-orders! store address dex-or-opts {})))
  ([store address dex opts]
   (let [body (cond-> {"type" "frontendOpenOrders"
                       "user" address}
                (and dex (not= dex "")) (assoc "dex" dex))]
     (-> (post-info! body
                     (merge {:priority :high}
                            opts))
         (.then #(.json %))
         (.then (fn [payload]
                  (let [data (js->clj payload :keywordize-keys true)]
                    (if (and dex (not= dex ""))
                      (swap! store assoc-in [:orders :open-orders-snapshot-by-dex dex] data)
                      (swap! store assoc-in [:orders :open-orders-snapshot] data))
                    data)))
         (.catch (fn [err]
                   (println "Error fetching open orders:" err)
                   (swap! store assoc-in [:orders :open-error] (str err))
                   (js/Promise.reject err)))))))

(defn fetch-user-fills!
  ([store address]
   (fetch-user-fills! store address {}))
  ([store address opts]
   (-> (post-info! {"type" "userFills"
                    "user" address
                    "aggregateByTime" true}
                   (merge {:priority :high}
                          opts))
       (.then #(.json %))
       (.then (fn [payload]
                (let [data (js->clj payload :keywordize-keys true)]
                  (swap! store assoc-in [:orders :fills] data)
                  data)))
       (.catch (fn [err]
                 (println "Error fetching user fills:" err)
                 (swap! store assoc-in [:orders :fills-error] (str err))
                 (js/Promise.reject err))))))

(defn- historical-orders-seq
  [payload]
  (cond
    (sequential? payload)
    payload

    (map? payload)
    (let [nested (or (:orders payload)
                     (:historicalOrders payload)
                     (:data payload))]
      (if (sequential? nested) nested []))

    :else
    []))

(defn- normalize-historical-order-row
  [row]
  (when (map? row)
    (let [order (:order row)]
      (if (map? order)
        row
        (assoc row :order row)))))

(defn fetch-historical-orders!
  ([store address]
   (fetch-historical-orders! store address {}))
  ([_store address opts]
   (if-not address
     (js/Promise.resolve [])
     (-> (post-info! {"type" "historicalOrders"
                      "user" address}
                     (merge {:priority :high}
                            opts))
         (.then #(.json %))
         (.then (fn [payload]
                  (->> (js->clj payload :keywordize-keys true)
                       historical-orders-seq
                       (map normalize-historical-order-row)
                       (remove nil?)
                       vec)))
         (.catch (fn [err]
                   (println "Error fetching historical orders:" err)
                   (js/Promise.reject err)))))))

(defn- user-funding-request-body
  [address start-time-ms end-time-ms]
  (cond-> {"type" "userFunding"
           "user" address}
    (number? start-time-ms) (assoc "startTime" (js/Math.floor start-time-ms))
    (number? end-time-ms) (assoc "endTime" (js/Math.floor end-time-ms))))

(defn- fetch-user-funding-page!
  [address start-time-ms end-time-ms opts]
  (-> (post-info! (user-funding-request-body address start-time-ms end-time-ms)
                  (merge {:priority :high}
                         opts))
      (.then #(.json %))
      (.then #(js->clj % :keywordize-keys true))))

(defn- fetch-user-funding-history-loop!
  [address start-time-ms end-time-ms opts acc]
  (-> (fetch-user-funding-page! address start-time-ms end-time-ms opts)
      (.then (fn [payload]
               (let [rows (normalize-info-funding-rows payload)]
                 (if (seq rows)
                   (let [max-time-ms (apply max (map :time-ms rows))
                         next-start-ms (inc max-time-ms)
                         acc* (into acc rows)
                         exhausted? (or (nil? max-time-ms)
                                        (= next-start-ms start-time-ms)
                                        (and (number? end-time-ms)
                                             (> next-start-ms end-time-ms)))]
                     (if exhausted?
                       (sort-funding-history-rows acc*)
                       (fetch-user-funding-history-loop! address next-start-ms end-time-ms opts acc*)))
                   (sort-funding-history-rows acc)))))))

(defn fetch-user-funding-history!
  ([store address]
   (fetch-user-funding-history! store address {}))
  ([_store address opts]
   (if-not address
     (js/Promise.resolve [])
     (let [{:keys [start-time-ms end-time-ms]} (normalize-funding-history-filters opts)]
       (fetch-user-funding-history-loop! address
                                         start-time-ms
                                         end-time-ms
                                         (merge {:priority :high}
                                                (or opts {}))
                                         [])))))

(defn fetch-spot-meta!
  ([store]
   (fetch-spot-meta! store {}))
  ([store opts]
   (println "Fetching spot metadata...")
   (swap! store assoc-in [:spot :loading-meta?] true)
   (-> (post-info! {"type" "spotMeta"}
                   (merge {:priority :high}
                          opts))
       (.then #(.json %))
       (.then (fn [payload]
                (let [data (js->clj payload :keywordize-keys true)]
                  (swap! store assoc-in [:spot :meta] data)
                  (swap! store assoc-in [:spot :loading-meta?] false)
                  (swap! store assoc-in [:spot :error] nil)
                  data)))
       (.catch (fn [err]
                 (println "Error fetching spot meta:" err)
                 (swap! store assoc-in [:spot :loading-meta?] false)
                 (swap! store assoc-in [:spot :error] (str err))
                 (js/Promise.reject err))))))

(defn fetch-spot-meta-raw!
  "Fetch spot meta and return the parsed response without touching state."
  ([]
   (fetch-spot-meta-raw! {}))
  ([opts]
   (-> (post-info! {"type" "spotMeta"}
                   (merge {:priority :high}
                          opts))
       (.then #(.json %))
       (.then #(js->clj % :keywordize-keys true)))))

(defn fetch-public-webdata2!
  "Fetch a public WebData2 snapshot to access spotAssetCtxs."
  ([]
   (fetch-public-webdata2! {}))
  ([opts]
   (-> (post-info! {"type" "webData2"
                    "user" "0x0000000000000000000000000000000000000000"}
                   (merge {:priority :high}
                          opts))
       (.then #(.json %))
       (.then #(js->clj % :keywordize-keys true)))))

(defn ensure-perp-dexs!
  ([store]
   (ensure-perp-dexs! store {}))
  ([store opts]
   (let [existing (get-in @store [:perp-dexs])]
     (if (seq existing)
       (js/Promise.resolve existing)
       (if-let [inflight @ensure-perp-dexs-flight]
         inflight
         (let [tracked-ref (atom nil)
               tracked (-> (fetch-perp-dexs! store
                                             (merge {:dedupe-key :perp-dexs}
                                                    opts))
                           (.finally
                            (fn []
                              (let [tracked* @tracked-ref]
                                (when (identical? @ensure-perp-dexs-flight tracked*)
                                  (reset! ensure-perp-dexs-flight nil))))))]
           (reset! tracked-ref tracked)
           (reset! ensure-perp-dexs-flight tracked)
           tracked))))))

(defn ensure-spot-meta!
  ([store]
   (ensure-spot-meta! store {}))
  ([store opts]
   (if-let [meta (get-in @store [:spot :meta])]
     (js/Promise.resolve meta)
     (fetch-spot-meta! store
                       (merge {:dedupe-key :spot-meta}
                              opts)))))

(defn ensure-public-webdata2!
  ([]
   (ensure-public-webdata2! {}))
  ([opts]
   (let [force? (boolean (:force? opts))
         opts* (dissoc opts :force?)]
     (if (and (not force?) @public-webdata2-cache)
       (js/Promise.resolve @public-webdata2-cache)
       (-> (fetch-public-webdata2!
            (merge {:dedupe-key :public-webdata2}
                   opts*))
           (.then (fn [snapshot]
                    (reset! public-webdata2-cache snapshot)
                    snapshot)))))))

(defn- build-market-state
  [store phase dexs spot-meta spot-asset-ctxs perp-results]
  (let [dexs-with-default (if (= phase :bootstrap)
                            [nil]
                            (vec (cons nil (vec dexs))))
        token-by-index (into {}
                             (map (fn [{:keys [index name]}]
                                    [index name]))
                             (:tokens spot-meta))
        perp-markets (->> (map vector dexs-with-default perp-results)
                          (mapcat (fn [[dex [meta asset-ctxs]]]
                                    (markets/build-perp-markets
                                     meta
                                     asset-ctxs
                                     token-by-index
                                     :dex dex)))
                          vec)
        spot-markets (markets/build-spot-markets spot-meta spot-asset-ctxs)
        all-markets (vec (concat perp-markets spot-markets))
        market-by-key (into {}
                            (map (fn [m] [(:key m) m]))
                            all-markets)
        active-asset (:active-asset @store)
        active-market (when active-asset
                        (markets/resolve-market-by-coin
                         market-by-key
                         active-asset))]
    {:markets all-markets
     :market-by-key market-by-key
     :active-market active-market
     :loaded-at-ms (now-ms)}))

(defn fetch-asset-selector-markets!
  "Fetch and build a unified market list for the asset selector.

   Options:
   - :phase :bootstrap|:full"
  ([store]
   (fetch-asset-selector-markets! store {:phase :full}))
  ([store opts]
   (let [phase (if (= :bootstrap (:phase opts)) :bootstrap :full)
         priority (if (= phase :bootstrap) :high :low)
         base-promises (js/Promise.all
                        (clj->js [(ensure-perp-dexs! store {:priority priority})
                                  (ensure-spot-meta! store {:priority priority})
                                  (ensure-public-webdata2! {:priority priority})]))]
     (println "Fetching asset selector markets. phase:" (name phase))
     (swap! store assoc-in [:asset-selector :loading?] true)
     (swap! store assoc-in [:asset-selector :phase] phase)
     (.catch
      (.then
       base-promises
       (fn [[dexs-loaded spot-meta-loaded webdata2]]
         (let [dexs* (vec (remove nil? dexs-loaded))
               dexs-with-default (if (= phase :bootstrap)
                                   [nil]
                                   (vec (cons nil dexs*)))
               perp-promises (->> dexs-with-default
                                  (map (fn [dex]
                                         (fetch-meta-and-asset-ctxs!
                                          dex
                                          {:priority priority})))
                                  (into-array))
               spot-asset-ctxs (:spotAssetCtxs webdata2)]
           (.then
            (js/Promise.all perp-promises)
            (fn [perp-results]
              (let [{:keys [markets market-by-key active-market loaded-at-ms]}
                    (build-market-state store phase dexs* spot-meta-loaded spot-asset-ctxs (array-seq perp-results))]
                (swap! store
                       (fn [state]
                         (let [current-phase (get-in state [:asset-selector :phase])
                               cache-hydrated? (boolean (get-in state [:asset-selector :cache-hydrated?]))
                               prefer-current? (and (= phase :bootstrap)
                                                    (= current-phase :full)
                                                    (not cache-hydrated?))]
                           (if prefer-current?
                             (-> state
                                 (assoc-in [:asset-selector :loaded-at-ms] loaded-at-ms))
                             (-> state
                                 (assoc-in [:asset-selector :markets] markets)
                                 (assoc-in [:asset-selector :market-by-key] market-by-key)
                                 (assoc :active-market (or active-market (:active-market state)))
                                 (assoc-in [:asset-selector :loaded-at-ms] loaded-at-ms)
                                 (assoc-in [:asset-selector :phase] phase)
                                 (assoc-in [:asset-selector :cache-hydrated?] false)
                                 (assoc-in [:asset-selector :error] nil)))
                           )))
                ;; Always clear loading when this phase resolves.
                (swap! store assoc-in [:asset-selector :loading?] false)
                markets)))))
      (fn [err]
        (println "Error fetching asset selector markets:" err)
        (swap! store assoc-in [:asset-selector :loading?] false)
        (swap! store assoc-in [:asset-selector :error] (str err))
        (js/Promise.reject err)))))))

(defn fetch-spot-clearinghouse-state!
  ([store address]
   (fetch-spot-clearinghouse-state! store address {}))
  ([store address opts]
   (if-not address
     (js/Promise.resolve nil)
     (do
       (println "Fetching spot clearinghouse state...")
       (swap! store assoc-in [:spot :loading-balances?] true)
       (-> (post-info! {"type" "spotClearinghouseState"
                        "user" address}
                       (merge {:priority :high}
                              opts))
           (.then #(.json %))
           (.then (fn [payload]
                    (let [data (js->clj payload :keywordize-keys true)]
                      (swap! store assoc-in [:spot :clearinghouse-state] data)
                      (swap! store assoc-in [:spot :loading-balances?] false)
                      (swap! store assoc-in [:spot :error] nil)
                      data)))
           (.catch (fn [err]
                     (println "Error fetching spot balances:" err)
                     (swap! store assoc-in [:spot :loading-balances?] false)
                     (swap! store assoc-in [:spot :error] (str err))
                     (js/Promise.reject err))))))))

(defn- normalize-user-abstraction-mode
  [abstraction]
  (let [abstraction* (some-> abstraction str str/trim)]
    (case abstraction*
      "unifiedAccount" :unified
      "portfolioMargin" :unified
      "dexAbstraction" :unified
      "default" :classic
      "disabled" :classic
      :classic)))

(defn fetch-user-abstraction!
  "Fetch account abstraction mode for a user and project normalized account mode.
   Supported normalized modes:
   - :unified  => unifiedAccount / portfolioMargin / dexAbstraction
   - :classic  => default / disabled / nil / unknown"
  ([store address]
   (fetch-user-abstraction! store address {}))
  ([store address opts]
   (if-not address
     (js/Promise.resolve {:mode :classic
                          :abstraction-raw nil})
     (let [requested-address (some-> address str str/lower-case)
           opts* (merge {:priority :high
                         :dedupe-key [:user-abstraction requested-address]}
                        opts)]
       (-> (post-info! {"type" "userAbstraction"
                        "user" address}
                       opts*)
           (.then #(.json %))
           (.then (fn [payload]
                    (let [abstraction (js->clj payload)
                          mode (normalize-user-abstraction-mode abstraction)
                          snapshot {:mode mode
                                    :abstraction-raw abstraction}
                          active-address (some-> (get-in @store [:wallet :address]) str str/lower-case)]
                      ;; Guard against stale async writes after address switches.
                      (when (= requested-address active-address)
                        (swap! store assoc :account snapshot))
                      snapshot)))
           (.catch (fn [err]
                     (println "Error fetching user abstraction:" err)
                     (js/Promise.reject err))))))))

(defn fetch-clearinghouse-state!
  "Fetch clearinghouse state for a specific perp DEX."
  ([store address dex]
   (fetch-clearinghouse-state! store address dex {}))
  ([store address dex opts]
   (if-not address
     (js/Promise.resolve nil)
     (let [body (cond-> {"type" "clearinghouseState"
                         "user" address}
                  (and dex (not= dex "")) (assoc "dex" dex))]
       (-> (post-info! body
                       (merge {:priority :high}
                              opts))
           (.then #(.json %))
           (.then (fn [payload]
                    (let [data (js->clj payload :keywordize-keys true)]
                      (swap! store assoc-in [:perp-dex-clearinghouse dex] data)
                      data)))
           (.catch (fn [err]
                     (println "Error fetching clearinghouse state:" err)
                     (swap! store assoc-in [:perp-dex-clearinghouse-error] (str err))
                     (js/Promise.reject err))))))))

(defn fetch-perp-dex-clearinghouse-states!
  "Fetch clearinghouse state for all named perp DEXes."
  ([store address dex-names]
   (fetch-perp-dex-clearinghouse-states! store address dex-names {}))
  ([store address dex-names opts]
   (if (and address (seq dex-names))
     (js/Promise.all
      (into-array
       (map (fn [dex]
              (fetch-clearinghouse-state! store address dex opts))
            dex-names)))
     (js/Promise.resolve nil))))
