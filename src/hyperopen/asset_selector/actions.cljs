(ns hyperopen.asset-selector.actions
  (:require [hyperopen.asset-selector.markets :as markets]
            [hyperopen.state.trading :as trading]))

(def ^:private asset-selector-sort-by-storage-key
  "asset-selector-sort-by")

(def ^:private asset-selector-sort-direction-storage-key
  "asset-selector-sort-direction")

(def ^:private asset-selector-strict-storage-key
  "asset-selector-strict")

(def ^:private asset-selector-favorites-storage-key
  "asset-selector-favorites")

(def ^:private asset-selector-active-tab-storage-key
  "asset-selector-active-tab")

(def asset-selector-default-render-limit
  120)

(def ^:private asset-selector-render-limit-step
  80)

(def ^:private asset-selector-render-prefetch-px
  320)

(def ^:private asset-selector-row-height-px
  48)

(def ^:private asset-selector-viewport-height-px
  384)

(def ^:private asset-selector-scroll-throttle-ms
  90)

(defn- parse-int-value
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(defn- parse-time-ms
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseFloat value)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num))
               (>= num 0))
      num)))

(defn toggle-asset-dropdown
  [state coin]
  (let [current-dropdown (get-in state [:asset-selector :visible-dropdown])
        next-dropdown (if (= current-dropdown coin) nil coin)
        should-refresh? (and (= coin :asset-selector)
                             (some? next-dropdown)
                             (or (empty? (get-in state [:asset-selector :markets]))
                                 (not= :full (get-in state [:asset-selector :phase]))))
        path-values (cond-> [[[:asset-selector :visible-dropdown] next-dropdown]]
                      (and (= coin :asset-selector) (some? next-dropdown))
                      (conj [[:asset-selector :scroll-top] 0]
                            [[:asset-selector :render-limit] asset-selector-default-render-limit]
                            [[:asset-selector :last-render-limit-increase-ms] nil]))
        effects [[:effects/save-many path-values]]]
    (cond-> effects
      should-refresh?
      (conj [:effects/fetch-asset-selector-markets]))))

(defn close-asset-dropdown
  [_state]
  [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                        [[:asset-selector :scroll-top] 0]
                        [[:asset-selector :render-limit] asset-selector-default-render-limit]
                        [[:asset-selector :last-render-limit-increase-ms] nil]]]])

(defn select-asset
  [state market-or-coin]
  (let [market-by-key (get-in state [:asset-selector :market-by-key] {})
        input-coin (cond
                     (map? market-or-coin) (:coin market-or-coin)
                     (string? market-or-coin) market-or-coin
                     :else nil)
        market (cond
                 (map? market-or-coin)
                 (or (markets/resolve-market-by-coin market-by-key input-coin)
                     market-or-coin)

                 (string? market-or-coin)
                 (markets/resolve-market-by-coin market-by-key market-or-coin)

                 :else nil)
        coin (or (:coin market) input-coin)
        resolved-market (or market
                            (when (string? coin)
                              (markets/resolve-market-by-coin market-by-key coin)))
        canonical-coin (or (:coin resolved-market) coin)
        current-asset (get-in state [:active-asset])
        switched-asset? (and (seq canonical-coin)
                             (not= canonical-coin current-asset))
        reset-order-form (when (and switched-asset?
                                    (map? (:order-form state)))
                           (-> (:order-form state)
                               (assoc :price "")
                               (trading/persist-order-form)))
        reset-order-form-ui (when switched-asset?
                              (assoc (merge (trading/default-order-form-ui)
                                            (or (:order-form-ui state) {}))
                                     :price-input-focused? false))
        immediate-ui-path-values (cond-> [[[:asset-selector :visible-dropdown] nil]
                                          [[:asset-selector :scroll-top] 0]
                                          [[:asset-selector :render-limit] asset-selector-default-render-limit]
                                          [[:asset-selector :last-render-limit-increase-ms] nil]
                                          [[:orderbook-ui :price-aggregation-dropdown-visible?] false]
                                          [[:orderbook-ui :size-unit-dropdown-visible?] false]
                                          [[:active-market] resolved-market]]
                                   reset-order-form
                                   (conj [[:order-form] reset-order-form])
                                   reset-order-form-ui
                                   (conj [[:order-form-ui] reset-order-form-ui]))
        immediate-ui-effects [[:effects/save-many immediate-ui-path-values]]
        unsubscribe-effects (if current-asset
                             [[:effects/unsubscribe-active-asset current-asset]
                              [:effects/unsubscribe-orderbook current-asset]
                              [:effects/unsubscribe-trades current-asset]]
                             [])
        subscribe-effects [[:effects/subscribe-active-asset canonical-coin]
                           [:effects/subscribe-orderbook canonical-coin]
                           [:effects/subscribe-trades canonical-coin]]]
    (into immediate-ui-effects
          (into unsubscribe-effects subscribe-effects))))

(defn update-asset-search
  [_state value]
  [[:effects/save-many [[[:asset-selector :search-term] (str value)]
                        [[:asset-selector :scroll-top] 0]
                        [[:asset-selector :render-limit] asset-selector-default-render-limit]
                        [[:asset-selector :last-render-limit-increase-ms] nil]]]])

(defn update-asset-selector-sort
  [state sort-field]
  (let [current-sort (get-in state [:asset-selector :sort-by])
        current-direction (get-in state [:asset-selector :sort-direction] :asc)
        new-direction (if (= current-sort sort-field)
                        (if (= current-direction :asc) :desc :asc)
                        :desc)]
    [[:effects/save-many [[[:asset-selector :sort-by] sort-field]
                          [[:asset-selector :sort-direction] new-direction]
                          [[:asset-selector :scroll-top] 0]
                          [[:asset-selector :render-limit] asset-selector-default-render-limit]
                          [[:asset-selector :last-render-limit-increase-ms] nil]]]
     [:effects/local-storage-set asset-selector-sort-by-storage-key (name sort-field)]
     [:effects/local-storage-set asset-selector-sort-direction-storage-key (name new-direction)]]))

(defn toggle-asset-selector-strict
  [state]
  (let [new-value (not (get-in state [:asset-selector :strict?] false))]
    [[:effects/save-many [[[:asset-selector :strict?] new-value]
                          [[:asset-selector :scroll-top] 0]
                          [[:asset-selector :render-limit] asset-selector-default-render-limit]
                          [[:asset-selector :last-render-limit-increase-ms] nil]]]
     [:effects/local-storage-set asset-selector-strict-storage-key (str new-value)]]))

(defn toggle-asset-favorite
  [state market-key]
  (let [favorites (get-in state [:asset-selector :favorites] #{})
        new-favorites (if (contains? favorites market-key)
                        (disj favorites market-key)
                        (conj favorites market-key))]
    [[:effects/save-many [[[:asset-selector :favorites] new-favorites]
                          [[:asset-selector :render-limit] asset-selector-default-render-limit]
                          [[:asset-selector :last-render-limit-increase-ms] nil]]]
     [:effects/local-storage-set-json asset-selector-favorites-storage-key (vec new-favorites)]]))

(defn set-asset-selector-favorites-only
  [_state enabled?]
  [[:effects/save-many [[[:asset-selector :favorites-only?] (boolean enabled?)]
                        [[:asset-selector :scroll-top] 0]
                        [[:asset-selector :render-limit] asset-selector-default-render-limit]
                        [[:asset-selector :last-render-limit-increase-ms] nil]]]])

(defn set-asset-selector-tab
  [_state tab]
  [[:effects/save-many [[[:asset-selector :active-tab] tab]
                        [[:asset-selector :scroll-top] 0]
                        [[:asset-selector :render-limit] asset-selector-default-render-limit]
                        [[:asset-selector :last-render-limit-increase-ms] nil]]]
   [:effects/local-storage-set asset-selector-active-tab-storage-key (name tab)]])

(defn set-asset-selector-scroll-top
  [state scroll-top]
  (let [raw-scroll-top (max 0 (or (parse-int-value scroll-top) 0))
        next-scroll-top (-> (/ raw-scroll-top asset-selector-row-height-px)
                            js/Math.floor
                            (* asset-selector-row-height-px))]
    (if (= next-scroll-top (get-in state [:asset-selector :scroll-top] 0))
      []
      [[:effects/save [:asset-selector :scroll-top] next-scroll-top]])))

(defn- current-asset-selector-render-limit
  [state total]
  (if (pos? total)
    (-> (or (parse-int-value (get-in state [:asset-selector :render-limit]))
            asset-selector-default-render-limit)
        (max 1)
        (min total))
    0))

(defn increase-asset-selector-render-limit
  [state]
  (let [markets (get-in state [:asset-selector :markets] [])
        total (count markets)
        current-limit (current-asset-selector-render-limit state total)
        next-limit (min total (+ current-limit asset-selector-render-limit-step))]
    (if (> next-limit current-limit)
      [[:effects/save [:asset-selector :render-limit] next-limit]]
      [])))

(defn show-all-asset-selector-markets
  [state]
  (let [markets (get-in state [:asset-selector :markets] [])
        total (count markets)
        current-limit (current-asset-selector-render-limit state total)]
    (if (> total current-limit)
      [[:effects/save [:asset-selector :render-limit] total]]
      [])))

(defn maybe-increase-asset-selector-render-limit
  ([state scroll-top]
   (maybe-increase-asset-selector-render-limit state scroll-top nil))
  ([state scroll-top event-time-ms]
   (let [markets (get-in state [:asset-selector :markets] [])
         total (count markets)
         current-limit (current-asset-selector-render-limit state total)
         scroll-top* (max 0 (or (parse-int-value scroll-top) 0))
         next-scroll-top (-> (/ scroll-top* asset-selector-row-height-px)
                             js/Math.floor
                             (* asset-selector-row-height-px))
         current-scroll-top (get-in state [:asset-selector :scroll-top] 0)
         scroll-changed? (not= next-scroll-top current-scroll-top)
         current-event-ms (parse-time-ms event-time-ms)
         rendered-height (* current-limit asset-selector-row-height-px)
         near-bottom? (and (pos? rendered-height)
                           (>= (+ scroll-top*
                                  asset-selector-viewport-height-px
                                  asset-selector-render-prefetch-px)
                               rendered-height))
         last-increase-ms (parse-time-ms (get-in state [:asset-selector :last-render-limit-increase-ms]))
         throttle-open? (or (nil? current-event-ms)
                            (nil? last-increase-ms)
                            (>= (- current-event-ms last-increase-ms)
                                asset-selector-scroll-throttle-ms))
         next-limit (if (and near-bottom? throttle-open?)
                      (min total (+ current-limit asset-selector-render-limit-step))
                      current-limit)]
     (if (> next-limit current-limit)
       [[:effects/save-many (cond-> [[[:asset-selector :render-limit] next-limit]]
                              (some? current-event-ms)
                              (conj [[:asset-selector :last-render-limit-increase-ms] current-event-ms])
                              scroll-changed?
                              (conj [[:asset-selector :scroll-top] next-scroll-top]))]]
       (if scroll-changed?
         [[:effects/save [:asset-selector :scroll-top] next-scroll-top]]
         [])))))

(defn apply-asset-icon-status-updates
  [state status-by-market]
  (let [loaded-icons (get-in state [:asset-selector :loaded-icons] #{})
        missing-icons (get-in state [:asset-selector :missing-icons] #{})
        [next-loaded-icons next-missing-icons]
        (reduce-kv
          (fn [[loaded missing] market-key status]
            (if-not (seq market-key)
              [loaded missing]
              (case status
                :loaded [(conj loaded market-key) (disj missing market-key)]
                :missing [(disj loaded market-key) (conj missing market-key)]
                [loaded missing])))
          [loaded-icons missing-icons]
          (or status-by-market {}))]
    {:loaded-icons next-loaded-icons
     :missing-icons next-missing-icons
     :changed? (or (not= loaded-icons next-loaded-icons)
                   (not= missing-icons next-missing-icons))}))

(defn mark-loaded-asset-icon
  [state market-key]
  (if-not (seq market-key)
    []
    (let [{:keys [changed?]} (apply-asset-icon-status-updates state {market-key :loaded})]
      (if changed?
        [[:effects/queue-asset-icon-status {:market-key market-key
                                            :status :loaded}]]
        []))))

(defn mark-missing-asset-icon
  [state market-key]
  (if-not (seq market-key)
    []
    (let [{:keys [changed?]} (apply-asset-icon-status-updates state {market-key :missing})]
      (if changed?
        [[:effects/queue-asset-icon-status {:market-key market-key
                                            :status :missing}]]
        []))))
