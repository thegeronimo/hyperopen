(ns hyperopen.api.fetch-compat
  (:require [clojure.string :as str]
            [hyperopen.api.market-metadata.facade :as market-metadata]
            [hyperopen.api.promise-effects :as promise-effects]))

(defn fetch-asset-contexts!
  [{:keys [log-fn
           request-asset-contexts!
           apply-asset-contexts-success
           apply-asset-contexts-error]}
   store
   opts]
  (log-fn "Fetching perpetual asset contexts...")
  (let [handle-success (promise-effects/apply-success-and-return
                        store
                        apply-asset-contexts-success)]
    (-> (request-asset-contexts! opts)
        (.then (fn [normalised]
                 (let [rows (handle-success normalised)]
                   (log-fn "Loaded" (count rows) "assets")
                   rows)))
        (.catch (promise-effects/log-apply-error-and-reject
                 log-fn
                 "Error fetching asset contexts:"
                 store
                 apply-asset-contexts-error)))))

(defn fetch-perp-dexs!
  [{:keys [log-fn
           request-perp-dexs!
           apply-perp-dexs-success
           apply-perp-dexs-error]}
   store
   opts]
  (log-fn "Fetching perp DEX list...")
  (market-metadata/fetch-and-apply-perp-dex-metadata!
   {:store store
    :log-fn log-fn
    :request-perp-dexs! request-perp-dexs!
    :apply-perp-dexs-success apply-perp-dexs-success
    :apply-perp-dexs-error apply-perp-dexs-error}
   opts))

(defn fetch-candle-snapshot!
  [{:keys [log-fn
   request-candle-snapshot!
   apply-candle-snapshot-success
   apply-candle-snapshot-error]}
   store
   {:keys [interval bars priority end-time-ms]}]
  (let [active-asset (:active-asset @store)]
    (if (nil? active-asset)
      (do
        (log-fn "No active asset selected, skipping candle fetch")
        (js/Promise.resolve nil))
      (let [interval-s (name interval)]
        (log-fn "Fetching" bars interval-s "bars for" active-asset
                (when end-time-ms
                  (str "ending at " end-time-ms)))
        (-> (request-candle-snapshot! active-asset
                                      :interval interval
                                      :bars bars
                                      :end-time-ms end-time-ms
                                      :priority priority)
            (.then (promise-effects/apply-success-and-return
                    store
                    apply-candle-snapshot-success
                    active-asset
                    interval))
            (.catch (promise-effects/log-apply-error-and-reject
                     log-fn
                     "Error fetching"
                     store
                     apply-candle-snapshot-error
                     active-asset
                     interval)))))))

(defn fetch-frontend-open-orders!
  [{:keys [log-fn
           request-frontend-open-orders!
           apply-open-orders-success
           apply-open-orders-error]}
   store
   address
   opts]
  (let [opts* (or opts {})
        dex (:dex opts*)]
    (-> (request-frontend-open-orders! address opts*)
        (.then (promise-effects/apply-success-and-return
                store
                apply-open-orders-success
                dex))
        (.catch (promise-effects/log-apply-error-and-reject
                 log-fn
                 "Error fetching open orders:"
                 store
                 apply-open-orders-error)))))

(defn fetch-user-fills!
  [{:keys [log-fn
           request-user-fills!
           apply-user-fills-success
           apply-user-fills-error]}
   store
   address
   opts]
  (-> (request-user-fills! address opts)
      (.then (promise-effects/apply-success-and-return
              store
              apply-user-fills-success
              address))
      (.catch (promise-effects/log-apply-error-and-reject
               log-fn
               "Error fetching user fills:"
               store
               apply-user-fills-error))))

(defn fetch-historical-orders!
  [{:keys [log-fn request-historical-orders!]}
   address
   opts]
  (-> (request-historical-orders! address opts)
      (.catch (promise-effects/log-error-and-reject
               log-fn
               "Error fetching historical orders:"))))

(defn fetch-spot-meta!
  [{:keys [log-fn
           request-spot-meta!
           begin-spot-meta-load
           apply-spot-meta-success
           apply-spot-meta-error]}
   store
   opts]
  (log-fn "Fetching spot metadata...")
  (swap! store begin-spot-meta-load)
  (-> (request-spot-meta! opts)
      (.then (promise-effects/apply-success-and-return
              store
              apply-spot-meta-success))
      (.catch (promise-effects/log-apply-error-and-reject
               log-fn
               "Error fetching spot meta:"
               store
               apply-spot-meta-error))))

(defn ensure-perp-dexs!
  [{:keys [ensure-perp-dexs-data!
           apply-perp-dexs-success
           apply-perp-dexs-error]}
   store
   opts]
  (market-metadata/ensure-and-apply-perp-dex-metadata!
   {:store store
    :ensure-perp-dexs-data! ensure-perp-dexs-data!
    :apply-perp-dexs-success apply-perp-dexs-success
    :apply-perp-dexs-error apply-perp-dexs-error}
   opts))

(defn ensure-spot-meta!
  [{:keys [ensure-spot-meta-data!
           apply-spot-meta-success
           apply-spot-meta-error]}
   store
   opts]
  (-> (ensure-spot-meta-data! store opts)
      (.then (promise-effects/apply-success-and-return
              store
              apply-spot-meta-success))
      (.catch (promise-effects/apply-error-and-reject
               store
               apply-spot-meta-error))))

(defn fetch-asset-selector-markets!
  [{:keys [log-fn
           request-asset-selector-markets!
           begin-asset-selector-load
           apply-spot-meta-success
           apply-asset-selector-success
           apply-asset-selector-error]}
   store
   opts]
  (swap! store begin-asset-selector-load
         (if (= :bootstrap (:phase opts)) :bootstrap :full))
  (-> (request-asset-selector-markets! store opts)
      (.then (fn [{:keys [phase spot-meta market-state]}]
               (when apply-spot-meta-success
                 (swap! store apply-spot-meta-success spot-meta))
               (swap! store apply-asset-selector-success phase market-state)
               (:markets market-state)))
      (.catch (promise-effects/log-apply-error-and-reject
               log-fn
               "Error fetching asset selector markets:"
               store
               apply-asset-selector-error))))

(defn fetch-spot-clearinghouse-state!
  [{:keys [log-fn
           request-spot-clearinghouse-state!
           begin-spot-balances-load
           apply-spot-balances-success
           apply-spot-balances-error]}
   store
   address
   opts]
  (if-not address
    (js/Promise.resolve nil)
    (do
      (log-fn "Fetching spot clearinghouse state...")
      (swap! store begin-spot-balances-load)
      (-> (request-spot-clearinghouse-state! address opts)
          (.then (promise-effects/apply-success-and-return
                  store
                  apply-spot-balances-success))
          (.catch (promise-effects/log-apply-error-and-reject
                   log-fn
                   "Error fetching spot balances:"
                   store
                   apply-spot-balances-error))))))

(defn fetch-user-abstraction!
  [{:keys [log-fn
           request-user-abstraction!
           normalize-user-abstraction-mode
           apply-user-abstraction-snapshot]}
   store
   address
   opts]
  (if-not address
    (js/Promise.resolve {:mode :classic
                         :abstraction-raw nil})
    (let [requested-address (some-> address str str/lower-case)]
      (-> (request-user-abstraction! address opts)
          (.then (fn [payload]
                   (let [snapshot {:mode (normalize-user-abstraction-mode payload)
                                   :abstraction-raw payload}]
                     (swap! store apply-user-abstraction-snapshot requested-address snapshot)
                     snapshot)))
          (.catch (promise-effects/log-error-and-reject
                   log-fn
                   "Error fetching user abstraction:"))))))

(defn fetch-clearinghouse-state!
  [{:keys [log-fn
           request-clearinghouse-state!
           apply-perp-dex-clearinghouse-success
           apply-perp-dex-clearinghouse-error]}
   store
   address
   dex
   opts]
  (-> (request-clearinghouse-state! address dex opts)
      (.then (promise-effects/apply-success-and-return
              store
              apply-perp-dex-clearinghouse-success
              dex))
      (.catch (promise-effects/log-apply-error-and-reject
               log-fn
               "Error fetching clearinghouse state:"
               store
               apply-perp-dex-clearinghouse-error))))

(defn fetch-perp-dex-clearinghouse-states!
  [{:keys [fetch-clearinghouse-state!]}
   store
   address
   dex-names
   opts]
  (if (and address (seq dex-names))
    (js/Promise.all
     (into-array
      (map (fn [dex]
             (fetch-clearinghouse-state! store address dex opts))
           dex-names)))
    (js/Promise.resolve nil)))
