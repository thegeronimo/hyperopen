(ns hyperopen.api
  (:require [clojure.string :as str]
            [hyperopen.api.info-runtime :as info-runtime]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.platform :as platform]
            [hyperopen.utils.data-normalization :refer [normalize-asset-contexts]]
            [hyperopen.utils.interval :refer [interval-to-milliseconds]]))

(def info-url "https://api.hyperliquid.xyz/info")
(def ^:private info-max-retries 4)
(def ^:private info-base-retry-ms 400)
(def ^:private info-max-retry-ms 5000)
(def default-funding-history-window-ms funding-history/default-window-ms)

(defonce ^:private public-webdata2-cache (atom nil))
(defonce ^:private ensure-perp-dexs-flight (atom nil))

(defn- now-ms []
  (platform/now-ms))

(defn funding-position-side
  [signed-size]
  (funding-history/funding-position-side signed-size))

(defn funding-history-row-id
  [time-ms coin signed-size payment-usdc funding-rate]
  (funding-history/funding-history-row-id time-ms coin signed-size payment-usdc funding-rate))

(defn normalize-info-funding-row
  [row]
  (funding-history/normalize-info-funding-row row))

(defn normalize-info-funding-rows
  [rows]
  (funding-history/normalize-info-funding-rows rows))

(defn normalize-ws-funding-row
  [row]
  (funding-history/normalize-ws-funding-row row))

(defn normalize-ws-funding-rows
  [rows]
  (funding-history/normalize-ws-funding-rows rows))

(defn sort-funding-history-rows
  [rows]
  (funding-history/sort-funding-history-rows rows))

(defn merge-funding-history-rows
  [existing incoming]
  (funding-history/merge-funding-history-rows existing incoming))

(defn normalize-funding-history-filters
  ([filters]
   (normalize-funding-history-filters filters (now-ms)))
  ([filters now]
   (funding-history/normalize-funding-history-filters filters now default-funding-history-window-ms)))

(defn filter-funding-history-rows
  [rows filters]
  (let [filters* (normalize-funding-history-filters filters)]
    (funding-history/filter-funding-history-rows rows filters*)))

(defn- normalize-priority
  [priority]
  (info-runtime/normalize-priority priority))

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

(defn- wait-ms
  [ms]
  (info-runtime/wait-ms ms))

(defn- maybe-wait-for-cooldown!
  []
  (info-runtime/maybe-wait-for-cooldown! wait-ms))

(defn- enqueue-request!
  [priority request-fn]
  (info-runtime/enqueue-request! priority request-fn))

(defn- enqueue-info-request!
  [priority request-fn]
  (info-runtime/enqueue-info-request! priority request-fn maybe-wait-for-cooldown!))

(defn- track-rate-limit!
  []
  (info-runtime/track-rate-limit!))

(defn- mark-rate-limit-cooldown!
  [delay-ms]
  (info-runtime/bump-cooldown! delay-ms))

(defn- with-single-flight!
  [dedupe-key promise-fn]
  (info-runtime/with-single-flight! dedupe-key promise-fn))

(defn get-request-stats
  []
  (info-runtime/get-request-stats))

(defn reset-request-runtime!
  []
  (info-runtime/reset-request-runtime!)
  (reset! public-webdata2-cache nil)
  (reset! ensure-perp-dexs-flight nil)
  nil)

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
                   (mark-rate-limit-cooldown! delay-ms))
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
                   (mark-rate-limit-cooldown! delay-ms))
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
      (let [now (now-ms)
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
   (swap! store api-projections/begin-spot-meta-load)
   (-> (post-info! {"type" "spotMeta"}
                   (merge {:priority :high}
                          opts))
       (.then #(.json %))
       (.then (fn [payload]
                (let [data (js->clj payload :keywordize-keys true)]
                  (swap! store api-projections/apply-spot-meta-success data)
                  data)))
       (.catch (fn [err]
                 (println "Error fetching spot meta:" err)
                 (swap! store api-projections/apply-spot-meta-error err)
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
     (swap! store api-projections/begin-asset-selector-load phase)
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
              (let [market-state (build-market-state
                                  store
                                  phase
                                  dexs*
                                  spot-meta-loaded
                                  spot-asset-ctxs
                                  (array-seq perp-results))]
                (swap! store api-projections/apply-asset-selector-success phase market-state)
                (:markets market-state))))))
      (fn [err]
        (println "Error fetching asset selector markets:" err)
        (swap! store api-projections/apply-asset-selector-error err)
        (js/Promise.reject err)))))))

(defn fetch-spot-clearinghouse-state!
  ([store address]
   (fetch-spot-clearinghouse-state! store address {}))
  ([store address opts]
   (if-not address
     (js/Promise.resolve nil)
     (do
       (println "Fetching spot clearinghouse state...")
       (swap! store api-projections/begin-spot-balances-load)
       (-> (post-info! {"type" "spotClearinghouseState"
                        "user" address}
                       (merge {:priority :high}
                              opts))
           (.then #(.json %))
           (.then (fn [payload]
                    (let [data (js->clj payload :keywordize-keys true)]
                      (swap! store api-projections/apply-spot-balances-success data)
                      data)))
           (.catch (fn [err]
                     (println "Error fetching spot balances:" err)
                     (swap! store api-projections/apply-spot-balances-error err)
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
