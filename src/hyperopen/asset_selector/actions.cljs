(ns hyperopen.asset-selector.actions
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.account.spectate-mode-links :as spectate-mode-links]
            [hyperopen.asset-selector.list-metrics :as list-metrics]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.router :as router]
            [hyperopen.utils.parse :as parse-utils]
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

(def ^:private funding-hypothetical-default-value
  1000)

(def asset-selector-default-render-limit
  list-metrics/default-render-limit)

(def ^:private asset-selector-render-limit-step
  80)

(def ^:private asset-selector-render-prefetch-px
  320)

(def ^:private asset-selector-row-height-px
  list-metrics/row-height-px)

(def ^:private asset-selector-viewport-height-px
  list-metrics/viewport-height-px)

(def ^:private asset-selector-scroll-throttle-ms
  90)

(def ^:private asset-selector-shortcut-open-key
  "k")

(def ^:private asset-selector-shortcut-favorite-key
  "s")

(def ^:private sync-asset-selector-active-ctx-subscriptions-effect
  [:effects/sync-asset-selector-active-ctx-subscriptions])

(declare toggle-asset-dropdown
         close-asset-dropdown
         select-asset
         toggle-asset-favorite)

(defn- append-selector-subscription-sync
  [effects]
  (if (seq effects)
    (conj (vec effects) sync-asset-selector-active-ctx-subscriptions-effect)
    []))

(defn- parse-int-value
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/parseInt value 10)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num)))
      (js/Math.floor num))))

(defn- parse-finite-number
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (let [text (str/trim value)]
                                (if (seq text)
                                  (js/Number text)
                                  js/NaN))
              :else js/NaN)]
    (when (and (number? num)
               (js/isFinite num))
      num)))

(defn- normalize-decimal-input
  [value]
  (-> (str (or value ""))
      (str/replace #"\$" "")
      str/trim))

(defn- parse-decimal-input
  ([value]
   (parse-decimal-input value nil))
  ([value locale]
   (parse-utils/parse-localized-currency-decimal
    (normalize-decimal-input value)
    locale)))

(defn- normalize-coin-key
  [coin]
  (let [text (some-> coin str str/trim)]
    (when (seq text)
      (str/upper-case text))))

(defn- trade-route-sync-effects
  [state canonical-coin]
  (let [current-route (some-> (get-in state [:router :path]) str str/trim)
        trade-route-active? (and (seq current-route)
                                 (router/trade-route? current-route))
        target-route (router/trade-route-path canonical-coin)]
    (if (and trade-route-active?
             (seq canonical-coin)
             (not= (router/normalize-path current-route)
                   target-route))
      (let [browser-route (spectate-mode-links/spectate-url-path
                           target-route
                           (when (account-context/spectate-mode-active? state)
                             (account-context/spectate-address state)))]
        [[:effects/save [:router :path] target-route]
         [:effects/push-state browser-route]])
      [])))

(defn- format-fixed
  [value digits]
  (if (and (number? value)
           (js/isFinite value))
    (.toFixed value digits)
    ""))

(defn- default-hypothetical-size-input
  [mark]
  (let [mark* (parse-finite-number mark)]
    (when (and (number? mark*)
               (pos? mark*))
      (format-fixed (/ funding-hypothetical-default-value mark*) 4))))

(defn- default-hypothetical-entry
  [mark]
  {:size-input (or (default-hypothetical-size-input mark) "")
   :value-input (format-fixed funding-hypothetical-default-value 2)})

(defn- hypothetical-entry
  [state coin mark]
  (let [stored (get-in state [:funding-ui :hypothetical-position-by-coin coin])]
    (merge (default-hypothetical-entry mark)
           (if (map? stored) stored {}))))

(defn- parse-time-ms
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (parse-utils/parse-localized-currency-decimal value)
              :else js/NaN)]
    (when (and (number? num)
               (not (js/isNaN num))
               (>= num 0))
      num)))

(defn- normalize-shortcut-key
  [key]
  (some-> key str str/lower-case))

(defn- normalize-shortcut-market-keys
  [market-keys]
  (if (sequential? market-keys)
    (vec (keep (fn [market-key]
                 (when (string? market-key)
                   market-key))
               market-keys))
    []))

(defn- market-key-index
  [market-keys market-key]
  (some (fn [[idx candidate]]
          (when (= candidate market-key)
            idx))
        (map-indexed vector market-keys)))

(defn- highlighted-or-selected-market-key
  [state market-keys]
  (let [highlighted-market-key (get-in state [:asset-selector :highlighted-market-key])
        selected-market-key (get-in state [:active-market :key])]
    (cond
      (some? (market-key-index market-keys highlighted-market-key))
      highlighted-market-key

      (some? (market-key-index market-keys selected-market-key))
      selected-market-key

      :else
      (first market-keys))))

(defn- move-highlighted-market-key
  [state market-keys step]
  (if (empty? market-keys)
    []
    (let [current-market-key (highlighted-or-selected-market-key state market-keys)
          current-index (or (market-key-index market-keys current-market-key) 0)
          max-index (dec (count market-keys))
          next-index (-> (+ current-index step)
                         (max 0)
                         (min max-index))
          next-market-key (nth market-keys next-index)
          current-highlighted-market-key (get-in state [:asset-selector :highlighted-market-key])]
      (if (= next-market-key current-highlighted-market-key)
        []
        [[:effects/save [:asset-selector :highlighted-market-key] next-market-key]]))))

(defn- select-highlighted-market
  [state market-keys]
  (let [market-key (highlighted-or-selected-market-key state market-keys)
        market (get-in state [:asset-selector :market-by-key market-key])]
    (if (map? market)
      (select-asset state market)
      [])))

(defn- toggle-highlighted-market-favorite
  [state market-keys]
  (if-let [market-key (highlighted-or-selected-market-key state market-keys)]
    (toggle-asset-favorite state market-key)
    []))

(defn handle-asset-selector-shortcut
  [state key meta-key? ctrl-key? market-keys]
  (let [normalized-key (normalize-shortcut-key key)
        meta-or-ctrl? (or (true? meta-key?) (true? ctrl-key?))
        open-shortcut? (and meta-or-ctrl?
                            (= normalized-key asset-selector-shortcut-open-key))
        favorite-shortcut? (and meta-or-ctrl?
                                (= normalized-key asset-selector-shortcut-favorite-key))
        selector-visible? (= :asset-selector (get-in state [:asset-selector :visible-dropdown]))
        market-keys* (normalize-shortcut-market-keys market-keys)]
    (cond
      open-shortcut?
      (if selector-visible?
        []
        (toggle-asset-dropdown state :asset-selector))

      (not selector-visible?)
      []

      (= key "Escape")
      (close-asset-dropdown state)

      (= key "ArrowDown")
      (move-highlighted-market-key state market-keys* 1)

      (= key "ArrowUp")
      (move-highlighted-market-key state market-keys* -1)

      (= key "Enter")
      (select-highlighted-market state market-keys*)

      favorite-shortcut?
      (toggle-highlighted-market-favorite state market-keys*)

      :else
      [])))

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
                            [[:asset-selector :last-render-limit-increase-ms] nil]
                            [[:asset-selector :highlighted-market-key] nil]))
        effects [[:effects/save-many path-values]
                 sync-asset-selector-active-ctx-subscriptions-effect]]
    (cond-> effects
      should-refresh?
      (conj [:effects/fetch-asset-selector-markets]))))

(defn close-asset-dropdown
  [_state]
  [[:effects/save-many [[[:asset-selector :visible-dropdown] nil]
                        [[:asset-selector :scroll-top] 0]
                        [[:asset-selector :render-limit] asset-selector-default-render-limit]
                        [[:asset-selector :last-render-limit-increase-ms] nil]
                        [[:asset-selector :highlighted-market-key] nil]]]
   sync-asset-selector-active-ctx-subscriptions-effect])

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
                                          [[:asset-selector :search-term] ""]
                                          [[:asset-selector :scroll-top] 0]
                                          [[:asset-selector :render-limit] asset-selector-default-render-limit]
                                          [[:asset-selector :last-render-limit-increase-ms] nil]
                                          [[:asset-selector :highlighted-market-key] nil]
                                          [[:orderbook-ui :price-aggregation-dropdown-visible?] false]
                                          [[:orderbook-ui :size-unit-dropdown-visible?] false]
                                          [[:active-market] resolved-market]]
                                   reset-order-form
                                   (conj [[:order-form] reset-order-form])
                                   reset-order-form-ui
                                   (conj [[:order-form-ui] reset-order-form-ui]))
        immediate-ui-effects [[:effects/save-many immediate-ui-path-values]
                              sync-asset-selector-active-ctx-subscriptions-effect]
        unsubscribe-effects (if current-asset
                             [[:effects/unsubscribe-active-asset current-asset]
                              [:effects/unsubscribe-orderbook current-asset]
                              [:effects/unsubscribe-trades current-asset]]
                             [])
        trade-route-effects (trade-route-sync-effects state canonical-coin)
        subscribe-effects [[:effects/subscribe-active-asset canonical-coin]
                           [:effects/subscribe-orderbook canonical-coin]
                           [:effects/subscribe-trades canonical-coin]]
        subscribe-effects* (cond-> subscribe-effects
                             (seq canonical-coin)
                             (conj [:effects/sync-active-asset-funding-predictability canonical-coin]))]
    (into immediate-ui-effects
          (into trade-route-effects
                (into unsubscribe-effects subscribe-effects*)))))

(defn update-asset-search
  [_state value]
  (append-selector-subscription-sync
   [[:effects/save-many [[[:asset-selector :search-term] (str value)]
                         [[:asset-selector :scroll-top] 0]
                         [[:asset-selector :render-limit] asset-selector-default-render-limit]
                         [[:asset-selector :last-render-limit-increase-ms] nil]
                         [[:asset-selector :highlighted-market-key] nil]]]]))

(defn update-asset-selector-sort
  [state sort-field]
  (let [current-sort (get-in state [:asset-selector :sort-by])
        current-direction (get-in state [:asset-selector :sort-direction] :asc)
        new-direction (if (= current-sort sort-field)
                        (if (= current-direction :asc) :desc :asc)
                        :desc)]
    (append-selector-subscription-sync
     [[:effects/save-many [[[:asset-selector :sort-by] sort-field]
                           [[:asset-selector :sort-direction] new-direction]
                           [[:asset-selector :scroll-top] 0]
                           [[:asset-selector :render-limit] asset-selector-default-render-limit]
                           [[:asset-selector :last-render-limit-increase-ms] nil]
                           [[:asset-selector :highlighted-market-key] nil]]]
      [:effects/local-storage-set asset-selector-sort-by-storage-key (name sort-field)]
      [:effects/local-storage-set asset-selector-sort-direction-storage-key (name new-direction)]])))

(defn toggle-asset-selector-strict
  [state]
  (let [new-value (not (get-in state [:asset-selector :strict?] false))]
    (append-selector-subscription-sync
     [[:effects/save-many [[[:asset-selector :strict?] new-value]
                           [[:asset-selector :scroll-top] 0]
                           [[:asset-selector :render-limit] asset-selector-default-render-limit]
                           [[:asset-selector :last-render-limit-increase-ms] nil]
                           [[:asset-selector :highlighted-market-key] nil]]]
      [:effects/local-storage-set asset-selector-strict-storage-key (str new-value)]])))

(defn toggle-asset-favorite
  [state market-key]
  (let [favorites (get-in state [:asset-selector :favorites] #{})
        new-favorites (if (contains? favorites market-key)
                        (disj favorites market-key)
                        (conj favorites market-key))]
    (append-selector-subscription-sync
     [[:effects/save-many [[[:asset-selector :favorites] new-favorites]
                           [[:asset-selector :render-limit] asset-selector-default-render-limit]
                           [[:asset-selector :last-render-limit-increase-ms] nil]
                           [[:asset-selector :highlighted-market-key] nil]]]
      [:effects/local-storage-set-json asset-selector-favorites-storage-key (vec new-favorites)]])))

(defn set-asset-selector-favorites-only
  [_state enabled?]
  (append-selector-subscription-sync
   [[:effects/save-many [[[:asset-selector :favorites-only?] (boolean enabled?)]
                         [[:asset-selector :scroll-top] 0]
                         [[:asset-selector :render-limit] asset-selector-default-render-limit]
                         [[:asset-selector :last-render-limit-increase-ms] nil]
                         [[:asset-selector :highlighted-market-key] nil]]]]))

(defn set-asset-selector-tab
  [_state tab]
  (append-selector-subscription-sync
   [[:effects/save-many [[[:asset-selector :active-tab] tab]
                         [[:asset-selector :scroll-top] 0]
                         [[:asset-selector :render-limit] asset-selector-default-render-limit]
                         [[:asset-selector :last-render-limit-increase-ms] nil]
                         [[:asset-selector :highlighted-market-key] nil]]]
    [:effects/local-storage-set asset-selector-active-tab-storage-key (name tab)]]))

(defn set-asset-selector-scroll-top
  [state scroll-top]
  (let [raw-scroll-top (max 0 (or (parse-int-value scroll-top) 0))
        next-scroll-top (-> (/ raw-scroll-top asset-selector-row-height-px)
                            js/Math.floor
                            (* asset-selector-row-height-px))]
    (if (= next-scroll-top (get-in state [:asset-selector :scroll-top] 0))
      []
      (append-selector-subscription-sync
       [[:effects/save [:asset-selector :scroll-top] next-scroll-top]]))))

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
      (append-selector-subscription-sync
       [[:effects/save [:asset-selector :render-limit] next-limit]])
      [])))

(defn show-all-asset-selector-markets
  [state]
  (let [markets (get-in state [:asset-selector :markets] [])
        total (count markets)
        current-limit (current-asset-selector-render-limit state total)]
    (if (> total current-limit)
      (append-selector-subscription-sync
       [[:effects/save [:asset-selector :render-limit] total]])
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
       (append-selector-subscription-sync
        [[:effects/save-many (cond-> [[[:asset-selector :render-limit] next-limit]]
                               (some? current-event-ms)
                               (conj [[:asset-selector :last-render-limit-increase-ms] current-event-ms])
                               scroll-changed?
                               (conj [[:asset-selector :scroll-top] next-scroll-top]))]])
       (if scroll-changed?
         (append-selector-subscription-sync
          [[:effects/save [:asset-selector :scroll-top] next-scroll-top]])
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

(defn set-funding-tooltip-visible
  [state tooltip-id visible?]
  (let [tooltip-id* (some-> tooltip-id str str/trim)
        current-visible-id (get-in state [:funding-ui :tooltip :visible-id])
        next-visible-id (cond
                          (and (true? visible?) (seq tooltip-id*)) tooltip-id*
                          (= current-visible-id tooltip-id*) nil
                          :else current-visible-id)]
    (if (= current-visible-id next-visible-id)
      []
      [[:effects/save [:funding-ui :tooltip :visible-id] next-visible-id]])))

(defn set-funding-tooltip-pinned
  [state tooltip-id pinned?]
  (let [tooltip-id* (some-> tooltip-id str str/trim)
        current-pinned-id (get-in state [:funding-ui :tooltip :pinned-id])
        next-pinned-id (cond
                         (and (true? pinned?) (seq tooltip-id*)) tooltip-id*
                         (= current-pinned-id tooltip-id*) nil
                         :else current-pinned-id)]
    (if (= current-pinned-id next-pinned-id)
      []
      [[:effects/save [:funding-ui :tooltip :pinned-id] next-pinned-id]])))

(defn set-funding-hypothetical-size
  [state coin mark size-input]
  (if-let [coin* (normalize-coin-key coin)]
    (let [locale (get-in state [:ui :locale])
          size-input* (normalize-decimal-input size-input)
          mark* (parse-finite-number mark)
          size* (parse-decimal-input size-input* locale)
          next-value (when (and (number? mark*)
                                (pos? mark*)
                                (number? size*))
                       (* (js/Math.abs size*) mark*))
          by-coin (or (get-in state [:funding-ui :hypothetical-position-by-coin]) {})
          next-entry (cond-> (hypothetical-entry state coin* mark)
                       true (assoc :size-input size-input*)
                       (number? next-value) (assoc :value-input (format-fixed next-value 2)))
          next-by-coin (assoc by-coin coin* next-entry)]
      [[:effects/save [:funding-ui :hypothetical-position-by-coin] next-by-coin]])
    []))

(defn set-funding-hypothetical-value
  [state coin mark value-input]
  (if-let [coin* (normalize-coin-key coin)]
    (let [locale (get-in state [:ui :locale])
          value-input* (normalize-decimal-input value-input)
          mark* (parse-finite-number mark)
          value* (parse-decimal-input value-input* locale)
          sign (if (or (str/starts-with? value-input* "-")
                       (str/starts-with? value-input* "−"))
                 -1
                 1)
          value-magnitude* (when (number? value*)
                             (js/Math.abs value*))
          next-size (when (and (number? mark*)
                               (pos? mark*)
                               (number? value-magnitude*))
                      (* sign (/ value-magnitude* mark*)))
          by-coin (or (get-in state [:funding-ui :hypothetical-position-by-coin]) {})
          next-entry (cond-> (hypothetical-entry state coin* mark)
                       true (assoc :value-input value-input*)
                       (number? next-size) (assoc :size-input (format-fixed next-size 4)))
          next-by-coin (assoc by-coin coin* next-entry)]
      [[:effects/save [:funding-ui :hypothetical-position-by-coin] next-by-coin]])
    []))
