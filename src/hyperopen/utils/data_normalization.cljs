(ns hyperopen.utils.data-normalization)

(defn- parse-float
  [value]
  (let [n (js/parseFloat value)]
    (when-not (js/isNaN n) n)))

(defn- active-asset-context?
  [funding]
  (let [day-ntl-vlm (parse-float (:dayNtlVlm funding))
        open-interest (parse-float (:openInterest funding))]
    (and (number? day-ntl-vlm)
         (> day-ntl-vlm 0)
         (number? open-interest)
         (> open-interest 0))))

(defn build-asset-context-meta-index
  "Build static metadata indexed by asset context position.
   Each entry is reused across dynamic assetCtx updates."
  [meta]
  (let [{:keys [universe marginTables]} (or meta {})
        margin-map (into {} (or marginTables []))]
    (->> (map-indexed vector (or universe []))
         (keep (fn [[idx {:keys [name marginTableId] :as info}]]
                 (when (some? name)
                   {:idx idx
                    :asset-key (keyword name)
                    :info info
                    :margin (margin-map marginTableId)})))
         vec)))

(defn patch-asset-contexts
  "Incrementally patch normalized asset contexts from static meta index
   and the latest dynamic asset context vector."
  [asset-contexts meta-index asset-ctxs]
  (let [ctxs (vec (or asset-ctxs []))
        seed (or asset-contexts {})]
    (reduce (fn [acc {:keys [idx asset-key info margin]}]
              (let [funding (nth ctxs idx nil)
                    keep? (and funding (active-asset-context? funding))
                    prev-entry (get acc asset-key)]
                (if keep?
                  (let [next-entry {:idx idx
                                    :info info
                                    :margin margin
                                    :funding funding}]
                    (if (= prev-entry next-entry)
                      acc
                      (assoc acc asset-key next-entry)))
                  (if (some? prev-entry)
                    (dissoc acc asset-key)
                    acc))))
            seed
            (or meta-index []))))

(defn normalize-asset-contexts
  "Takes the raw vector `data` returned by /info(type=metaAndAssetCtxs)`
   and returns a map keyed by asset keyword, each value containing:
   * :info        → entry from universe
   * :margin      → resolved margin-table
   * :funding     → funding/latestPx struct
   * :idx         → original index (handy for other endpoint look-ups)"
  [data]
  (let [[meta asset-ctxs] data
        meta-index (build-asset-context-meta-index meta)]
    (patch-asset-contexts {} meta-index asset-ctxs)))

(defn preprocess-webdata2
  "Given a response map with keys
     :meta       → {:universe […] :marginTables […]}
     :assetCtxs  → [ {…} {…} … ]
   return the [meta assetCtxs] vector that normalize-asset-contexts needs."
  [{:keys [meta assetCtxs]}]
  [(select-keys meta [:universe :marginTables])
   (vec assetCtxs)])
