(ns hyperopen.api
  (:require [clojure.string :as str]
            [hyperopen.api.endpoints.account :as account-endpoints]
            [hyperopen.api.endpoints.market :as market-endpoints]
            [hyperopen.api.endpoints.orders :as order-endpoints]
            [hyperopen.api.info-client :as info-client]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.platform :as platform]))

(def info-url (:info-url info-client/default-config))
(def default-funding-history-window-ms funding-history/default-window-ms)

(def ^:private default-info-client-config
  (merge info-client/default-config
         {:info-url info-url}))

(defonce ^:private info-client-instance
  (atom (info-client/make-info-client
         {:config default-info-client-config
          :log-fn println})))

(defonce ^:private public-webdata2-cache (atom nil))
(defonce ^:private ensure-perp-dexs-flight (atom nil))

(declare request-spot-clearinghouse-state!)
(declare request-user-abstraction!)
(declare ensure-perp-dexs-data!)
(declare request-asset-selector-markets!)

(defn- now-ms []
  (platform/now-ms))

(defn- active-info-client
  []
  @info-client-instance)

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
  ((:normalize-priority (active-info-client)) priority))

(defn- wait-ms
  [ms]
  ((:wait-ms (active-info-client)) ms))

(defn- maybe-wait-for-cooldown!
  []
  ((:maybe-wait-for-cooldown! (active-info-client))))

(defn- enqueue-request!
  [priority request-fn]
  ((:enqueue-request! (active-info-client)) priority request-fn))

(defn- enqueue-info-request!
  [priority request-fn]
  ((:enqueue-info-request! (active-info-client)) priority request-fn))

(defn- track-rate-limit!
  []
  ((:track-rate-limit! (active-info-client))))

(defn- mark-rate-limit-cooldown!
  [delay-ms]
  ((:mark-rate-limit-cooldown! (active-info-client)) delay-ms))

(defn- with-single-flight!
  [dedupe-key promise-fn]
  ((:with-single-flight! (active-info-client)) dedupe-key promise-fn))

(defn get-request-stats
  []
  ((:get-request-stats (active-info-client))))

(defn reset-request-runtime!
  []
  ((:reset! (active-info-client)))
  (reset! public-webdata2-cache nil)
  (reset! ensure-perp-dexs-flight nil)
  nil)

(defn- post-info!
  ([body]
   (post-info! body {}))
  ([body opts]
   ((:request-info! (active-info-client)) body opts))
  ([body opts attempt]
   ((:request-info! (active-info-client)) body opts attempt)))

(defn request-asset-contexts!
  ([] (request-asset-contexts! {}))
  ([opts]
   (market-endpoints/request-asset-contexts! post-info! opts)))

(defn fetch-asset-contexts!
  ([store]
   (fetch-asset-contexts! store {}))
  ([store opts]
   (println "Fetching perpetual asset contexts...")
   (-> (request-asset-contexts! opts)
       (.then (fn [normalised]
                (swap! store api-projections/apply-asset-contexts-success normalised)
                (println "Loaded" (count normalised) "assets")
                normalised))
       (.catch (fn [err]
                 (println "Error fetching asset contexts:" err)
                 (swap! store api-projections/apply-asset-contexts-error err)
                 (js/Promise.reject err))))))

(defn fetch-meta-and-asset-ctxs!
  "Fetch metaAndAssetCtxs for the default perp DEX or a named DEX."
  ([dex]
   (fetch-meta-and-asset-ctxs! dex {}))
  ([dex opts]
   (market-endpoints/request-meta-and-asset-ctxs! post-info! dex opts)))

(defn request-perp-dexs!
  ([] (request-perp-dexs! {}))
  ([opts]
   (market-endpoints/request-perp-dexs! post-info! opts)))

(defn fetch-perp-dexs!
  "Fetch the list of available perp DEXes. The default DEX is omitted from
  the response, so we only store named DEXes."
  ([store]
   (fetch-perp-dexs! store {}))
  ([store opts]
   (println "Fetching perp DEX list...")
   (-> (request-perp-dexs! opts)
       (.then (fn [dex-names]
                (swap! store api-projections/apply-perp-dexs-success dex-names)
                dex-names))
       (.catch (fn [err]
                 (println "Error fetching perp DEX list:" err)
                 (swap! store api-projections/apply-perp-dexs-error err)
                 (js/Promise.reject err))))))

(defn request-candle-snapshot!
  [coin & {:keys [interval bars priority]
           :or {interval :1d bars 330 priority :high}}]
  (market-endpoints/request-candle-snapshot! post-info!
                                             now-ms
                                             coin
                                             {:interval interval
                                              :bars bars
                                              :priority priority}))

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
      (let [interval-s (name interval)]
        (println "Fetching" bars interval-s "bars for" active-asset)
        (-> (request-candle-snapshot! active-asset
                                      :interval interval
                                      :bars bars
                                      :priority priority)
            (.then (fn [data]
                     (swap! store api-projections/apply-candle-snapshot-success active-asset interval data)
                     data))
            (.catch (fn [err]
                      (println "Error fetching" err)
                      (swap! store api-projections/apply-candle-snapshot-error active-asset interval err)
                      (js/Promise.reject err))))))))

(defn request-frontend-open-orders!
  ([address]
   (request-frontend-open-orders! address nil {}))
  ([address dex-or-opts]
   (if (map? dex-or-opts)
     (request-frontend-open-orders! address nil dex-or-opts)
     (request-frontend-open-orders! address dex-or-opts {})))
  ([address dex opts]
   (order-endpoints/request-frontend-open-orders! post-info! address dex opts)))

(defn fetch-frontend-open-orders!
  ([store address]
   (fetch-frontend-open-orders! store address nil {}))
  ([store address dex-or-opts]
  (if (map? dex-or-opts)
     (fetch-frontend-open-orders! store address nil dex-or-opts)
     (fetch-frontend-open-orders! store address dex-or-opts {})))
  ([store address dex opts]
   (-> (request-frontend-open-orders! address dex opts)
       (.then (fn [data]
                (swap! store api-projections/apply-open-orders-success dex data)
                data))
       (.catch (fn [err]
                 (println "Error fetching open orders:" err)
                 (swap! store api-projections/apply-open-orders-error err)
                 (js/Promise.reject err))))))

(defn request-user-fills!
  ([address]
   (request-user-fills! address {}))
  ([address opts]
   (order-endpoints/request-user-fills! post-info! address opts)))

(defn fetch-user-fills!
  ([store address]
   (fetch-user-fills! store address {}))
  ([store address opts]
   (-> (request-user-fills! address opts)
       (.then (fn [data]
                (swap! store api-projections/apply-user-fills-success data)
                data))
       (.catch (fn [err]
                 (println "Error fetching user fills:" err)
                 (swap! store api-projections/apply-user-fills-error err)
                 (js/Promise.reject err))))))

(defn fetch-historical-orders!
  ([store address]
   (fetch-historical-orders! store address {}))
  ([_store address opts]
   (-> (order-endpoints/request-historical-orders! post-info! address opts)
       (.catch (fn [err]
                 (println "Error fetching historical orders:" err)
                 (js/Promise.reject err))))))

(defn request-historical-orders!
  ([address]
   (request-historical-orders! address {}))
  ([address opts]
   (fetch-historical-orders! nil address opts)))

(defn fetch-user-funding-history!
  ([store address]
   (fetch-user-funding-history! store address {}))
  ([_store address opts]
   (if-not address
     (js/Promise.resolve [])
     (let [{:keys [start-time-ms end-time-ms]} (normalize-funding-history-filters opts)]
       (account-endpoints/request-user-funding-history! post-info!
                                                        normalize-info-funding-rows
                                                        sort-funding-history-rows
                                                        address
                                                        start-time-ms
                                                        end-time-ms
                                                        opts)))))

(defn request-user-funding-history!
  ([address]
   (request-user-funding-history! address {}))
  ([address opts]
   (fetch-user-funding-history! nil address opts)))

(defn request-spot-meta!
  ([] (request-spot-meta! {}))
  ([opts]
   (market-endpoints/request-spot-meta! post-info! opts)))

(defn fetch-spot-meta!
  ([store]
   (fetch-spot-meta! store {}))
  ([store opts]
   (println "Fetching spot metadata...")
   (swap! store api-projections/begin-spot-meta-load)
   (-> (request-spot-meta! opts)
       (.then (fn [data]
                (swap! store api-projections/apply-spot-meta-success data)
                data))
       (.catch (fn [err]
                 (println "Error fetching spot meta:" err)
                 (swap! store api-projections/apply-spot-meta-error err)
                 (js/Promise.reject err))))))

(defn fetch-spot-meta-raw!
  "Fetch spot meta and return the parsed response without touching state."
  ([]
   (fetch-spot-meta-raw! {}))
  ([opts]
   (request-spot-meta! opts)))

(defn request-public-webdata2!
  "Fetch a public WebData2 snapshot to access spotAssetCtxs."
  ([]
   (request-public-webdata2! {}))
  ([opts]
   (market-endpoints/request-public-webdata2! post-info! opts)))

(defn fetch-public-webdata2!
  ([]
   (fetch-public-webdata2! {}))
  ([opts]
   (request-public-webdata2! opts)))

(defn ensure-perp-dexs!
  ([store]
   (ensure-perp-dexs! store {}))
  ([store opts]
   (-> (ensure-perp-dexs-data! store opts)
       (.then (fn [dex-names]
                (swap! store api-projections/apply-perp-dexs-success dex-names)
                dex-names))
       (.catch (fn [err]
                 (swap! store api-projections/apply-perp-dexs-error err)
                 (js/Promise.reject err))))))

(defn ensure-perp-dexs-data!
  ([store]
   (ensure-perp-dexs-data! store {}))
  ([store opts]
   (let [existing (get-in @store [:perp-dexs])]
     (if (seq existing)
       (js/Promise.resolve existing)
       (if-let [inflight @ensure-perp-dexs-flight]
         inflight
         (let [tracked-ref (atom nil)
               tracked (-> (request-perp-dexs!
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

(defn ensure-spot-meta-data!
  ([store]
   (ensure-spot-meta-data! store {}))
  ([store opts]
   (if-let [meta (get-in @store [:spot :meta])]
     (js/Promise.resolve meta)
     (request-spot-meta! (merge {:dedupe-key :spot-meta}
                                opts)))))

(defn ensure-spot-meta!
  ([store]
   (ensure-spot-meta! store {}))
  ([store opts]
   (-> (ensure-spot-meta-data! store opts)
       (.then (fn [meta]
                (swap! store api-projections/apply-spot-meta-success meta)
                meta))
       (.catch (fn [err]
                 (swap! store api-projections/apply-spot-meta-error err)
                 (js/Promise.reject err))))))

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

(defn fetch-asset-selector-markets!
  "Fetch and build a unified market list for the asset selector.

   Options:
   - :phase :bootstrap|:full"
  ([store]
   (fetch-asset-selector-markets! store {:phase :full}))
  ([store opts]
   (swap! store api-projections/begin-asset-selector-load
          (if (= :bootstrap (:phase opts)) :bootstrap :full))
   (-> (request-asset-selector-markets! store opts)
       (.then (fn [{:keys [phase market-state]}]
                (swap! store api-projections/apply-asset-selector-success phase market-state)
                (:markets market-state)))
       (.catch
        (fn [err]
          (println "Error fetching asset selector markets:" err)
          (swap! store api-projections/apply-asset-selector-error err)
          (js/Promise.reject err))))))

(defn request-asset-selector-markets!
  ([store]
   (request-asset-selector-markets! store {:phase :full}))
  ([store opts]
   (let [phase (if (= :bootstrap (:phase opts)) :bootstrap :full)
         priority (if (= phase :bootstrap) :high :low)
         base-promises (js/Promise.all
                        (clj->js [(ensure-perp-dexs-data! store {:priority priority})
                                  (ensure-spot-meta-data! store {:priority priority})
                                  (ensure-public-webdata2! {:priority priority})]))]
     (println "Fetching asset selector markets. phase:" (name phase))
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
	             (let [market-state (market-endpoints/build-market-state
	                                 now-ms
	                                 store
	                                 phase
	                                 dexs*
	                                 spot-meta-loaded
	                                 spot-asset-ctxs
	                                 (array-seq perp-results))]
	               {:phase phase
	                :market-state market-state})))))))))

(defn fetch-spot-clearinghouse-state!
  ([store address]
   (fetch-spot-clearinghouse-state! store address {}))
  ([store address opts]
   (if-not address
     (js/Promise.resolve nil)
     (do
      (println "Fetching spot clearinghouse state...")
      (swap! store api-projections/begin-spot-balances-load)
       (-> (request-spot-clearinghouse-state! address opts)
           (.then (fn [data]
                    (swap! store api-projections/apply-spot-balances-success data)
                    data))
           (.catch (fn [err]
                     (println "Error fetching spot balances:" err)
                     (swap! store api-projections/apply-spot-balances-error err)
                     (js/Promise.reject err))))))))

(defn request-spot-clearinghouse-state!
  ([address]
   (request-spot-clearinghouse-state! address {}))
  ([address opts]
   (account-endpoints/request-spot-clearinghouse-state! post-info! address opts)))

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
     (let [requested-address (some-> address str str/lower-case)]
       (-> (request-user-abstraction! address opts)
           (.then (fn [payload]
                    (let [abstraction payload
                          mode (account-endpoints/normalize-user-abstraction-mode abstraction)
                          snapshot {:mode mode
                                    :abstraction-raw abstraction}]
                      (swap! store api-projections/apply-user-abstraction-snapshot requested-address snapshot)
                      snapshot)))
           (.catch (fn [err]
                     (println "Error fetching user abstraction:" err)
                     (js/Promise.reject err))))))))

(defn request-user-abstraction!
  ([address]
   (request-user-abstraction! address {}))
  ([address opts]
   (account-endpoints/request-user-abstraction! post-info! address opts)))

(defn request-clearinghouse-state!
  ([address dex]
   (request-clearinghouse-state! address dex {}))
  ([address dex opts]
   (account-endpoints/request-clearinghouse-state! post-info! address dex opts)))

(defn fetch-clearinghouse-state!
  "Fetch clearinghouse state for a specific perp DEX."
  ([store address dex]
   (fetch-clearinghouse-state! store address dex {}))
  ([store address dex opts]
   (-> (request-clearinghouse-state! address dex opts)
       (.then (fn [data]
                (swap! store api-projections/apply-perp-dex-clearinghouse-success dex data)
                data))
       (.catch (fn [err]
                 (println "Error fetching clearinghouse state:" err)
                 (swap! store api-projections/apply-perp-dex-clearinghouse-error err)
                 (js/Promise.reject err))))))

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
