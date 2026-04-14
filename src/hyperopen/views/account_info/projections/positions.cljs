(ns hyperopen.views.account-info.projections.positions
  (:require [hyperopen.account.history.position-identity :as position-identity]
            [hyperopen.views.account-info.projections.coins :as coins]
            [hyperopen.views.account-info.projections.parse :as parse]))

(def parse-optional-num parse/parse-optional-num)
(def non-blank-text parse/non-blank-text)

(defn position-unique-key [position-data]
  (position-identity/position-unique-key position-data))

(defn- mark-price-by-market-name
  [state]
  (let [meta (:meta state)
        universe (or (:universe meta) [])
        asset-ctxs (vec (or (:assetCtxs state) []))]
    (reduce (fn [acc [idx info]]
              (let [market-name (non-blank-text (:name info))
                    mark-px (parse-optional-num (:markPx (nth asset-ctxs idx nil)))]
                (if (and (seq market-name)
                         (number? mark-px)
                         (pos? mark-px))
                  (assoc acc market-name mark-px)
                  acc)))
            {}
            (map-indexed vector universe))))

(defn- position-mark-candidates
  [position-row]
  (let [position (or (:position position-row) {})
        coin (non-blank-text (:coin position))
        dex (non-blank-text (:dex position-row))
        base-coin (some-> coin coins/parse-coin-namespace :base non-blank-text)]
    (->> [coin
          (when (and (seq dex)
                     (seq base-coin))
            (str dex ":" base-coin))
          base-coin]
         (remove nil?)
         distinct)))

(defn- position-mark-price
  [position-row]
  (let [position (or (:position position-row) {})]
    (or (parse-optional-num (:markPx position))
        (parse-optional-num (:markPrice position)))))

(defn- row-provided-mark-price
  [position-row]
  (or (position-mark-price position-row)
      (parse-optional-num (:markPx position-row))
      (parse-optional-num (:markPrice position-row))))

(defn- resolve-mark-price
  [mark-by-market-name position-row]
  (some (fn [candidate]
          (get mark-by-market-name candidate))
        (position-mark-candidates position-row)))

(defn- enrich-position-row
  [position-row dex mark-by-market-name]
  (when (map? position-row)
    (let [row* (assoc position-row :dex dex)
          nested-mark (position-mark-price row*)
          resolved-mark (or (row-provided-mark-price row*)
                            (resolve-mark-price mark-by-market-name row*))]
      (if (and (number? resolved-mark)
               (map? (:position row*))
               (not (number? nested-mark)))
        (assoc-in row* [:position :markPx] resolved-mark)
        row*))))

(defn collect-positions [webdata2 perp-dex-states]
  (let [base-mark-by-market-name (mark-price-by-market-name webdata2)
        base-positions (->> (or (get-in webdata2 [:clearinghouseState :assetPositions]) [])
                            (keep #(enrich-position-row % nil base-mark-by-market-name)))
        extra-positions (->> (or perp-dex-states {})
                             (mapcat (fn [[dex state]]
                                       (let [mark-by-market-name (mark-price-by-market-name state)]
                                         (->> (or (:assetPositions state) [])
                                              (keep #(enrich-position-row % dex mark-by-market-name)))))))
        combined (->> (concat base-positions extra-positions)
                      (remove nil?))]
    (second
     (reduce (fn [[seen acc] pos]
               (let [k (position-unique-key pos)]
                 (if (contains? seen k)
                   [seen acc]
                   [(conj seen k) (conj acc pos)])))
             [#{} []]
             combined))))
