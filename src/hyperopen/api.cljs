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
        margin-map (into {} marginTables)]
    (->> (map-indexed vector universe)
         ;; Filter out assets with zero volume and open interest
         (filter (fn [[idx {:keys [name] :as info}]]
                   (let [funding-data (nth funding idx)
                         day-ntl-vlm (js/parseFloat (:dayNtlVlm funding-data))
                         open-interest (js/parseFloat (:openInterest funding-data))]
                     (and (not (js/isNaN day-ntl-vlm)) (> day-ntl-vlm 0)
                          (not (js/isNaN open-interest)) (> open-interest 0)))))
         ;; Build normalized map
         (reduce (fn [m [idx {:keys [name marginTableId] :as info}]]
                   (assoc m (keyword name)
                          {:idx     idx
                           :info    info
                           :margin  (margin-map marginTableId)
                           :funding (nth funding idx)}))
                 {}))))

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