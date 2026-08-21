(ns hyperopen.pnl-share.naming
  "Naming helpers shared by the share-card view layer and the pure actions.

   This lives outside hyperopen.views.* so the actions and the effect adapter
   can use it: dev/check_namespace_boundaries.clj requires a dated exception for
   any non-view namespace that imports a view."
  (:require [clojure.string :as str]))

(defn base-symbol
  "Strips a HIP-3 venue prefix: xyz:NVDA becomes NVDA."
  [coin]
  (let [coin* (some-> coin str str/trim)]
    (when (seq coin*)
      (let [index (str/index-of coin* ":")]
        (if (and index (< (inc index) (count coin*)))
          (subs coin* (inc index))
          coin*)))))

(defn- slug
  [value fallback]
  (or (some-> value
              str
              str/lower-case
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"^-+|-+$" "")
              not-empty)
      fallback))

(defn- utc-date
  [ms]
  (when (number? ms)
    (let [date (js/Date. ms)
          pad (fn [n] (if (< n 10) (str "0" n) (str n)))]
      (str (.getUTCFullYear date)
           "-" (pad (inc (.getUTCMonth date)))
           "-" (pad (.getUTCDate date))))))

(defn card-file-name
  "hyperopen-sol-long-2026-08-21.png"
  [coin-label side now-ms]
  (str "hyperopen-"
       (slug (base-symbol coin-label) "position")
       "-"
       (slug (case side
               :long "long"
               :short "short"
               nil)
             "position")
       "-"
       (or (utc-date now-ms) "card")
       ".png"))
