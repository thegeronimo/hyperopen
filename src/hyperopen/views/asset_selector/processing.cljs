(ns hyperopen.views.asset-selector.processing
  (:require [hyperopen.asset-selector.query :as query]))

(defn matches-search? [asset search-term strict?]
  (query/matches-search? asset search-term strict?))

(defn tab-match? [asset active-tab strict?]
  (query/tab-match? asset active-tab strict?))

(defn filter-and-sort-assets
  [assets search-term sort-key sort-direction favorites favorites-only? strict? active-tab]
  (query/filter-and-sort-assets assets
                                search-term
                                sort-key
                                sort-direction
                                favorites
                                favorites-only?
                                strict?
                                active-tab))

(defonce processed-assets-cache
  (atom nil))

(defn processed-assets-market-signature
  [markets]
  (mapv (fn [{:keys [key symbol coin base market-type category hip3? hip3-eligible? cache-order]}]
          [key symbol coin base market-type category hip3? hip3-eligible? cache-order])
        markets))

(defn processed-assets-market-by-key
  [markets]
  (persistent!
    (reduce (fn [acc market]
              (if-let [market-key (:key market)]
                (assoc! acc market-key market)
                acc))
            (transient {})
            markets)))

(defn ordered-market-keys->assets
  [ordered-market-keys market-by-key]
  (into []
        (keep #(get market-by-key %))
        ordered-market-keys))

(defn reset-processed-assets-cache! []
  (reset! processed-assets-cache nil))

(defn processed-assets
  ([markets search-term sort-key sort-direction favorites favorites-only? strict? active-tab]
   (processed-assets markets nil search-term sort-key sort-direction favorites favorites-only? strict? active-tab))
  ([markets market-by-key search-term sort-key sort-direction favorites favorites-only? strict? active-tab]
   (let [cache @processed-assets-cache
         market-signature (processed-assets-market-signature markets)
         cache-hit? (and (map? cache)
                         (= market-signature (:market-signature cache))
                         (= favorites (:favorites cache))
                         (= search-term (:search-term cache))
                         (= sort-key (:sort-key cache))
                         (= sort-direction (:sort-direction cache))
                         (= favorites-only? (:favorites-only? cache))
                         (= strict? (:strict? cache))
                         (= active-tab (:active-tab cache)))
         market-by-key* (or market-by-key
                            (:market-by-key cache)
                            (processed-assets-market-by-key markets))
         exact-input-hit? (and cache-hit?
                               (identical? markets (:markets cache))
                               (identical? market-by-key* (:market-by-key cache)))]
     (cond
       exact-input-hit?
       (:result cache)

       cache-hit?
       (let [result (ordered-market-keys->assets (:ordered-market-keys cache) market-by-key*)]
         (reset! processed-assets-cache (assoc cache
                                               :markets markets
                                               :market-by-key market-by-key*
                                               :result result))
         result)

       :else
       (let [result (filter-and-sort-assets markets search-term sort-key sort-direction
                                            favorites favorites-only? strict? active-tab)]
         (reset! processed-assets-cache {:market-signature market-signature
                                         :markets markets
                                         :market-by-key market-by-key*
                                         :favorites favorites
                                         :search-term search-term
                                         :sort-key sort-key
                                         :sort-direction sort-direction
                                         :favorites-only? favorites-only?
                                         :strict? strict?
                                         :active-tab active-tab
                                         :ordered-market-keys (mapv :key result)
                                         :result result})
         result)))))
