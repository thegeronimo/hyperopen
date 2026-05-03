(ns hyperopen.views.account-info.tab-registry)

(def ^:private base-tab-definitions
  (array-map
   :balances {:label "Balances"}
   :positions {:label "Positions"}
   :outcomes {:label "Outcomes"}
   :open-orders {:label "Open Orders"}
   :twap {:label "TWAP"}
   :trade-history {:label "Trade History"}
   :funding-history {:label "Funding History"}
   :order-history {:label "Order History"}))

(def available-tabs
  (vec (keys base-tab-definitions)))

(def tab-labels
  (into {}
        (map (fn [[tab {:keys [label]}]]
               [tab label]))
        base-tab-definitions))

(defn- normalize-panel-classes
  [panel-classes]
  (let [classes* (->> (or panel-classes [])
                      (filter string?)
                      vec)]
    (when (seq classes*)
      classes*)))

(defn- normalize-extra-tab
  [{:keys [id label] :as tab}]
  (when (and (keyword? id)
             (string? label)
             (seq label))
    {:id id
     :label label
     :content (:content tab)
     :render (:render tab)
     :panel-classes (normalize-panel-classes (:panel-classes tab))
     :panel-style (when (map? (:panel-style tab))
                    (:panel-style tab))}))

(defn normalized-extra-tabs
  [extra-tabs]
  (->> (or extra-tabs [])
       (keep normalize-extra-tab)
       vec))

(defn- apply-tab-label-overrides
  [tab-definitions label-overrides]
  (reduce-kv (fn [acc tab label]
               (if (and (keyword? tab)
                        (string? label)
                        (seq label)
                        (contains? acc tab))
                 (assoc-in acc [tab :label] label)
                 acc))
             tab-definitions
             (or label-overrides {})))

(defn- merged-tab-definitions
  [extra-tabs label-overrides]
  (let [extra-tabs* (normalized-extra-tabs extra-tabs)
        extra-tab-ids (set (map :id extra-tabs*))
        base-tab-pairs (remove (fn [[tab _]]
                                 (contains? extra-tab-ids tab))
                               base-tab-definitions)]
    (apply-tab-label-overrides
     (into (array-map)
           (concat (map (fn [{:keys [id label]}]
                          [id {:label label}])
                        extra-tabs*)
                   base-tab-pairs))
     label-overrides)))

(defn- ordered-tab-ids
  [tab-definitions tab-order]
  (let [tabs* (vec (keys tab-definitions))
        ordered-prefix (->> (or tab-order [])
                            (filter #(contains? tab-definitions %))
                            distinct
                            vec)
        ordered-set (set ordered-prefix)]
    (into ordered-prefix
          (remove ordered-set tabs*))))

(defn available-tabs-for
  [extra-tabs tab-order label-overrides]
  (ordered-tab-ids (merged-tab-definitions extra-tabs label-overrides)
                   tab-order))

(defn tab-labels-for
  [extra-tabs label-overrides]
  (into {}
        (map (fn [[tab {:keys [label]}]]
               [tab label]))
        (merged-tab-definitions extra-tabs label-overrides)))

(defn tab-label
  ([tab counts]
   (tab-label tab counts tab-labels))
  ([tab counts labels]
   (let [base (get labels tab (name tab))
         count (get counts tab)]
     (cond
       (and (contains? #{:positions :outcomes :open-orders :twap} tab)
            (number? count)
            (pos? count))
       (str base " (" count ")")

       (and (contains? #{:positions :outcomes :open-orders :twap} tab)
            (number? count))
       base

       (and (= tab :balances) (number? count))
       (str base " (" count ")")

       :else base))))
