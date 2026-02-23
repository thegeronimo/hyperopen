(ns hyperopen.api.fetch-compat
  (:require [clojure.string :as str]))

(defn- perp-dex-names
  [payload]
  (cond
    (map? payload)
    (vec (or (:dex-names payload)
             (:perp-dexs payload)
             []))

    (sequential? payload)
    (vec (keep (fn [entry]
                 (cond
                   (string? entry) entry
                   (map? entry) (:name entry)
                   :else nil))
               payload))

    :else
    []))

(defn fetch-asset-contexts!
  [{:keys [log-fn
           request-asset-contexts!
           apply-asset-contexts-success
           apply-asset-contexts-error]}
   store
   opts]
  (log-fn "Fetching perpetual asset contexts...")
  (-> (request-asset-contexts! opts)
      (.then (fn [normalised]
               (swap! store apply-asset-contexts-success normalised)
               (log-fn "Loaded" (count normalised) "assets")
               normalised))
      (.catch (fn [err]
                (log-fn "Error fetching asset contexts:" err)
                (swap! store apply-asset-contexts-error err)
                (js/Promise.reject err)))))

(defn fetch-perp-dexs!
  [{:keys [log-fn
           request-perp-dexs!
           apply-perp-dexs-success
           apply-perp-dexs-error]}
   store
   opts]
  (log-fn "Fetching perp DEX list...")
  (-> (request-perp-dexs! opts)
      (.then (fn [payload]
               (swap! store apply-perp-dexs-success payload)
               (perp-dex-names payload)))
      (.catch (fn [err]
                (log-fn "Error fetching perp DEX list:" err)
                (swap! store apply-perp-dexs-error err)
                (js/Promise.reject err)))))

(defn fetch-candle-snapshot!
  [{:keys [log-fn
           request-candle-snapshot!
           apply-candle-snapshot-success
           apply-candle-snapshot-error]}
   store
   {:keys [interval bars priority]}]
  (let [active-asset (:active-asset @store)]
    (if (nil? active-asset)
      (do
        (log-fn "No active asset selected, skipping candle fetch")
        (js/Promise.resolve nil))
      (let [interval-s (name interval)]
        (log-fn "Fetching" bars interval-s "bars for" active-asset)
        (-> (request-candle-snapshot! active-asset
                                      :interval interval
                                      :bars bars
                                      :priority priority)
            (.then (fn [data]
                     (swap! store apply-candle-snapshot-success active-asset interval data)
                     data))
            (.catch (fn [err]
                      (log-fn "Error fetching" err)
                      (swap! store apply-candle-snapshot-error active-asset interval err)
                      (js/Promise.reject err))))))))

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
      (.then (fn [data]
               (swap! store apply-open-orders-success dex data)
               data))
      (.catch (fn [err]
                (log-fn "Error fetching open orders:" err)
                (swap! store apply-open-orders-error err)
                (js/Promise.reject err))))))

(defn fetch-user-fills!
  [{:keys [log-fn
           request-user-fills!
           apply-user-fills-success
           apply-user-fills-error]}
   store
   address
   opts]
  (-> (request-user-fills! address opts)
      (.then (fn [data]
               (swap! store apply-user-fills-success data)
               data))
      (.catch (fn [err]
                (log-fn "Error fetching user fills:" err)
                (swap! store apply-user-fills-error err)
                (js/Promise.reject err)))))

(defn fetch-historical-orders!
  [{:keys [log-fn request-historical-orders!]}
   address
   opts]
  (-> (request-historical-orders! address opts)
      (.catch (fn [err]
                (log-fn "Error fetching historical orders:" err)
                (js/Promise.reject err)))))

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
      (.then (fn [data]
               (swap! store apply-spot-meta-success data)
               data))
      (.catch (fn [err]
                (log-fn "Error fetching spot meta:" err)
                (swap! store apply-spot-meta-error err)
                (js/Promise.reject err)))))

(defn ensure-perp-dexs!
  [{:keys [ensure-perp-dexs-data!
           apply-perp-dexs-success
           apply-perp-dexs-error]}
   store
   opts]
  (-> (ensure-perp-dexs-data! store opts)
      (.then (fn [payload]
               (swap! store apply-perp-dexs-success payload)
               (perp-dex-names payload)))
      (.catch (fn [err]
                (swap! store apply-perp-dexs-error err)
                (js/Promise.reject err)))))

(defn ensure-spot-meta!
  [{:keys [ensure-spot-meta-data!
           apply-spot-meta-success
           apply-spot-meta-error]}
   store
   opts]
  (-> (ensure-spot-meta-data! store opts)
      (.then (fn [meta]
               (swap! store apply-spot-meta-success meta)
               meta))
      (.catch (fn [err]
                (swap! store apply-spot-meta-error err)
                (js/Promise.reject err)))))

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
      (.catch
       (fn [err]
         (log-fn "Error fetching asset selector markets:" err)
         (swap! store apply-asset-selector-error err)
         (js/Promise.reject err)))))

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
          (.then (fn [data]
                   (swap! store apply-spot-balances-success data)
                   data))
          (.catch (fn [err]
                    (log-fn "Error fetching spot balances:" err)
                    (swap! store apply-spot-balances-error err)
                    (js/Promise.reject err)))))))

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
          (.catch (fn [err]
                    (log-fn "Error fetching user abstraction:" err)
                    (js/Promise.reject err)))))))

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
      (.then (fn [data]
               (swap! store apply-perp-dex-clearinghouse-success dex data)
               data))
      (.catch (fn [err]
                (log-fn "Error fetching clearinghouse state:" err)
                (swap! store apply-perp-dex-clearinghouse-error err)
                (js/Promise.reject err)))))

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
