(ns hyperopen.views.account-info.derived-cache
  (:require [hyperopen.views.account-info.projections :as projections]))

(defonce ^:private balance-rows-cache (atom nil))
(defonce ^:private positions-cache (atom nil))
(defonce ^:private open-orders-cache (atom nil))

(def ^:dynamic *build-balance-rows* projections/build-balance-rows)
(def ^:dynamic *collect-positions* projections/collect-positions)
(def ^:dynamic *normalized-open-orders* projections/normalized-open-orders)

(defn same-spot-markets?
  "True when two catalogs hold the very same spot markets (by identity).
   Balance rows read only spot markets from the catalog, while live ticks
   replace perp and outcome entries many times a second; comparing the spot
   entries alone keeps the rows when only those other entries moved. Cheap:
   pointer comparisons, no deep equality."
  [a b]
  (or (identical? a b)
      (and (map? a)
           (map? b)
           (= (count a) (count b))
           (reduce-kv (fn [_ market-key market]
                        (if (or (not= :spot (:market-type market))
                                (identical? market (get b market-key)))
                          true
                          (reduced false)))
                      true
                      a)
           (reduce-kv (fn [_ market-key market]
                        (if (or (not= :spot (:market-type market))
                                (contains? a market-key))
                          true
                          (reduced false)))
                      true
                      b))))

(defn memoized-balance-rows
  [webdata2 spot-data account market-by-key perp-dex-states]
  (let [cache @balance-rows-cache
        cache-hit? (and (map? cache)
                        (identical? webdata2 (:webdata2 cache))
                        (identical? spot-data (:spot-data cache))
                        (identical? account (:account cache))
                        (same-spot-markets? market-by-key (:market-by-key cache))
                        (identical? perp-dex-states (:perp-dex-states cache)))]
    (if cache-hit?
      (:result cache)
      (let [result (*build-balance-rows* webdata2 spot-data account market-by-key perp-dex-states)]
        (reset! balance-rows-cache {:webdata2 webdata2
                                    :spot-data spot-data
                                    :account account
                                    :market-by-key market-by-key
                                    :perp-dex-states perp-dex-states
                                    :result result})
        result))))

(defn memoized-positions
  [webdata2 perp-dex-states]
  (let [cache @positions-cache
        cache-hit? (and (map? cache)
                        (identical? webdata2 (:webdata2 cache))
                        (identical? perp-dex-states (:perp-dex-states cache)))]
    (if cache-hit?
      (:result cache)
      (let [result (*collect-positions* webdata2 perp-dex-states)]
        (reset! positions-cache {:webdata2 webdata2
                                 :perp-dex-states perp-dex-states
                                 :result result})
        result))))

(defn memoized-open-orders
  ([orders snapshot snapshot-by-dex]
   (memoized-open-orders orders snapshot snapshot-by-dex nil))
  ([orders snapshot snapshot-by-dex pending-cancel-oids]
   (let [cache @open-orders-cache
         cache-hit? (and (map? cache)
                         (identical? orders (:orders cache))
                         (identical? snapshot (:snapshot cache))
                         (identical? snapshot-by-dex (:snapshot-by-dex cache))
                         (identical? pending-cancel-oids (:pending-cancel-oids cache)))]
     (if cache-hit?
       (:result cache)
       (let [result (*normalized-open-orders* orders
                                              snapshot
                                              snapshot-by-dex
                                              pending-cancel-oids)]
         (reset! open-orders-cache {:orders orders
                                    :snapshot snapshot
                                    :snapshot-by-dex snapshot-by-dex
                                    :pending-cancel-oids pending-cancel-oids
                                    :result result})
         result)))))

(defn reset-derived-cache!
  []
  (reset! balance-rows-cache nil)
  (reset! positions-cache nil)
  (reset! open-orders-cache nil))
