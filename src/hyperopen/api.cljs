(ns hyperopen.api
  (:require [clojure.string :as str]))

(defn normalise-asset-contexts
  "Takes the raw vector `data` returned by /info(type=metaAndAssetCtxs)`
   and returns a map keyed by asset keyword, each value containing:
   * :info        → entry from universe
   * :margin      → resolved margin-table
   * :funding     → funding/latestPx struct
   * :idx         → original index (handy for other endpoint look-ups)"
  [data]
  (let [[{:keys [universe marginTables]} funding] data
        ;; 1. margin-table-id -> table
        margin-map (into {} marginTables)]
    ;; 2. merge everything per asset
    (reduce
      (fn [m [idx {:keys [name marginTableId] :as info}]]
        (assoc m (keyword name)
                  {:idx     idx
                   :info    info
                   :margin  (margin-map marginTableId)
                   :funding (nth funding idx)}))
      {}
      (map-indexed vector universe))))

(defn fetch-asset-contexts! [store]
  (println "Fetching perpetual asset contexts...")
  (-> (js/fetch "https://api.hyperliquid.xyz/info"
                (clj->js {:method "POST"
                          :headers {"Content-Type" "application/json"}
                          :body (js/JSON.stringify (clj->js {"type" "metaAndAssetCtxs"}))}))
      (.then #(.json %))
      (.then #(let [data (js->clj % :keywordize-keys true)
                    normalised (normalise-asset-contexts data)]
                (swap! store assoc-in [:asset-contexts] normalised)
                (println "Loaded" (count normalised) "assets")))
      (.catch #(do (println "Error fetching asset contexts:" %)
                   (swap! store assoc-in [:asset-contexts :error] (str %)))))) 