(ns hyperopen.views.account-info.tabs.positions
  (:require [clojure.string :as str]
            [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.account.history.position-reduce :as position-reduce]
            [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.views.account-info.cache-keys :as cache-keys]
            [hyperopen.views.account-info.mobile-cards :as mobile-cards]
            [hyperopen.views.account-info.position-margin-modal :as position-margin-modal]
            [hyperopen.views.account-info.position-reduce-popover :as position-reduce-popover]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.position-tpsl-modal :as position-tpsl-modal]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.sort-kernel :as sort-kernel]
            [hyperopen.views.account-info.table :as table]))

(defn- empty-state [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div.text-sm.opacity-70.mt-2 "No data available"]])

(def positions-direction-filter-options
  [[:all "All"]
   [:long "Long"]
   [:short "Short"]])

(def positions-direction-filter-labels
  (into {} positions-direction-filter-options))

(defn calculate-mark-price [position-data]
  (or (:markPx position-data)
      (:markPrice position-data)
      (:entryPx position-data)))

(defn- display-coin [position-data]
  (let [coin (:coin position-data)
        parsed (shared/parse-coin-namespace coin)]
    (or (:base parsed)
        (shared/non-blank-text coin)
        "-")))

(defn- dex-chip-label [position-data]
  (let [explicit-dex (shared/non-blank-text (:dex position-data))
        parsed-prefix (some-> (:coin position-data) shared/parse-coin-namespace :prefix)]
    (or explicit-dex parsed-prefix)))

(defn- position-side [position-data]
  (let [size-num (shared/parse-optional-num (:szi position-data))]
    (cond
      (and (number? size-num) (neg? size-num)) :short
      (and (number? size-num) (pos? size-num)) :long
      :else :flat)))

(def ^:private cross-margin-mode-tokens
  #{"cross" "crossmargin"})

(def ^:private isolated-margin-mode-tokens
  #{"isolated" "isolatedmargin" "nocross" "strictisolated"})

(defn- parse-optional-boolean [value]
  (cond
    (true? value) true
    (false? value) false

    (string? value)
    (let [text (-> value str str/trim str/lower-case)]
      (case text
        "true" true
        "false" false
        nil))

    :else nil))

(defn- normalize-margin-mode [value]
  (let [token (some-> value
                      str
                      str/trim
                      str/lower-case
                      (str/replace #"[\s_\-]" ""))]
    (cond
      (contains? cross-margin-mode-tokens token) :cross
      (contains? isolated-margin-mode-tokens token) :isolated
      :else nil)))

(defn- position-margin-mode [position-data]
  (let [position (or (:position position-data) {})
        leverage-type-mode (normalize-margin-mode
                            (or (get-in position [:leverage :type])
                                (get-in position [:leverage :mode])
                                (:leverageType position)
                                (:leverage-type position)))
        is-cross? (or (parse-optional-boolean (:isCross position))
                      (parse-optional-boolean (:is-cross position))
                      (parse-optional-boolean (:isCross position-data))
                      (parse-optional-boolean (:is-cross position-data)))
        boolean-mode (when (some? is-cross?)
                       (if is-cross? :cross :isolated))
        margin-mode (normalize-margin-mode
                     (or (:marginMode position)
                         (:margin-mode position)
                         (:marginMode position-data)
                         (:margin-mode position-data)))]
    (or leverage-type-mode boolean-mode margin-mode)))

(defn- margin-mode-display-label [margin-mode]
  (case margin-mode
    :cross "Cross"
    :isolated "Isolated"
    nil))

(defn positions-direction-filter-key [positions-state]
  (let [raw-direction (:direction-filter positions-state)
        direction-filter (cond
                           (keyword? raw-direction) raw-direction
                           (string? raw-direction) (keyword (str/lower-case raw-direction))
                           :else :all)]
    (if (contains? positions-direction-filter-labels direction-filter)
      direction-filter
      :all)))

(defn- position-entry [row]
  (if (and (map? row) (map? (:position row)))
    (:position row)
    row))

(defn filter-positions-by-direction [positions direction-filter]
  (let [positions* (or positions [])]
    (case direction-filter
      :long (filterv #(= :long (position-side (position-entry %))) positions*)
      :short (filterv #(= :short (position-side (position-entry %))) positions*)
      (vec positions*))))

(defn- position-matches-coin-search?
  [position-row query]
  (let [entry (position-entry position-row)
        coin (:coin entry)
        base-coin (some-> coin shared/parse-coin-namespace :base)
        dex (:dex position-row)]
    (or (shared/coin-matches-search? coin query)
        (shared/coin-matches-search? base-coin query)
        (shared/coin-matches-search? dex query))))

(defn- filter-positions-by-coin-search
  [positions coin-search]
  (let [query (shared/normalize-coin-search-query coin-search)
        positions* (or positions [])]
    (if (str/blank? query)
      (vec positions*)
      (->> positions*
           (filterv #(position-matches-coin-search? % query))))))

(defn- absolute-size-text [size]
  (let [size-text (shared/non-blank-text size)]
    (cond
      (and size-text (str/starts-with? size-text "-")) (subs size-text 1)
      (and size-text (str/starts-with? size-text "+")) (subs size-text 1)
      size-text size-text
      :else (if-let [size-num (shared/parse-optional-num size)]
              (str (js/Math.abs size-num))
              "0"))))

(defn format-position-size [position-data]
  (let [size (absolute-size-text (:szi position-data))
        coin (display-coin position-data)]
    (str size " " coin)))

(defn- explainable-value-node
  ([value-node explanation]
   (explainable-value-node value-node explanation {}))
  ([value-node explanation {:keys [underlined?]
                            :or {underlined? true}}]
   (if explanation
    [:span {:class (into ["group" "relative" "inline-flex" "items-center"]
                         (when underlined?
                           ["underline" "decoration-dashed" "underline-offset-2"]))}
     value-node
     [:span {:class ["pointer-events-none"
                     "absolute"
                     "left-1/2"
                     "-translate-x-1/2"
                     "top-full"
                     "z-[120]"
                     "mt-2"
                     "w-56"
                     "rounded-md"
                     "bg-gray-800"
                     "px-2.5"
                     "py-1.5"
                     "text-left"
                     "text-xs"
                     "leading-tight"
                     "text-gray-100"
                     "whitespace-normal"
                     "spectate-lg"
                     "opacity-0"
                     "transition-opacity"
                     "duration-200"
                     "group-hover:opacity-100"
                     "group-focus-within:opacity-100"]}
      explanation]]
    value-node)))

(defn- format-pnl-inline [pnl-num pnl-percent]
  (if (and (number? pnl-num) (number? pnl-percent))
    (let [value-prefix (cond
                         (pos? pnl-num) "+$"
                         (neg? pnl-num) "-$"
                         :else "$")
          pct-prefix (cond
                       (pos? pnl-percent) "+"
                       (neg? pnl-percent) "-"
                       :else "")
          value-text (str value-prefix (shared/format-currency (js/Math.abs pnl-num)))
          pct-text (str "(" pct-prefix (.toFixed (js/Math.abs pnl-percent) 1) "%)")]
      (str value-text " " pct-text))
    "--"))

(defn- funding-display-value [funding-num]
  (when (number? funding-num)
    (- funding-num)))

(defn- format-funding-tooltip [all-time-funding since-change-funding]
  (let [all-time-text (if (number? all-time-funding)
                        (str "$" (shared/format-currency all-time-funding))
                        "--")
        since-change-text (if (number? since-change-funding)
                            (str "$" (shared/format-currency since-change-funding))
                            "--")]
    (str "All-time: " all-time-text " Since change: " since-change-text)))

(def ^:private max-liquidation-display-chars 6)

(defn- count-integer-digits [num]
  (let [abs-value (js/Math.abs num)]
    (if (< abs-value 1)
      1
      (inc (js/Math.floor (/ (js/Math.log abs-value)
                             (js/Math.log 10)))))))

(defn- format-liquidation-price [value]
  (if-let [num (shared/parse-optional-num value)]
    (let [integer-digits (count-integer-digits num)
          decimal-digits (if (>= integer-digits max-liquidation-display-chars)
                           0
                           (max 0 (- max-liquidation-display-chars integer-digits 1)))]
      (or (fmt/format-currency-with-digits num 0 decimal-digits)
          "N/A"))
    "N/A"))

(defn- valid-trigger-price
  [value]
  (let [num (shared/parse-optional-num value)]
    (when (and (number? num)
               (pos? num))
      value)))

(defn- resolve-position-trigger-price
  [position-data side]
  (let [pos (or (:position position-data) {})
        candidates (case side
                     :tp [(:position-tp-trigger-px position-data)
                          (:tp-trigger-px position-data)
                          (:tpTriggerPx position-data)
                          (:takeProfitPx position-data)
                          (:takeProfitTriggerPx position-data)
                          (:tpPx position-data)
                          (:tpTriggerPx pos)
                          (:takeProfitPx pos)
                          (:takeProfitTriggerPx pos)
                          (:tpPx pos)]
                     :sl [(:position-sl-trigger-px position-data)
                          (:sl-trigger-px position-data)
                          (:slTriggerPx position-data)
                          (:stopLossPx position-data)
                          (:stopLossTriggerPx position-data)
                          (:slPx position-data)
                          (:slTriggerPx pos)
                          (:stopLossPx pos)
                          (:stopLossTriggerPx pos)
                          (:slPx pos)]
                     [])]
    (some valid-trigger-price candidates)))

(defn- tpsl-cell-copy
  [position-data]
  (let [tp-trigger (resolve-position-trigger-price position-data :tp)
        sl-trigger (resolve-position-trigger-price position-data :sl)
        tp-text (if tp-trigger
                  (shared/format-trade-price tp-trigger)
                  "--")
        sl-text (if sl-trigger
                  (shared/format-trade-price sl-trigger)
                  "--")]
    (str tp-text " / " sl-text)))

(defn- edit-icon []
  [:svg {:class ["h-3" "w-3" "shrink-0"]
         :viewBox "0 0 20 20"
         :fill "none"
         :stroke "currentColor"
         :stroke-width "1.8"
         :aria-hidden true}
   [:path {:d "M4 13.5V16h2.5L14 8.5 11.5 6 4 13.5Z"}]
   [:path {:d "M10.5 7 13 9.5"}]])

(defn position-unique-key [position-data]
  (projections/position-unique-key position-data))

(defn collect-positions [webdata2 perp-dex-states]
  (projections/collect-positions webdata2 perp-dex-states))

(defn- position-value-copy [position-value-num]
  (if (number? position-value-num)
    (str (shared/format-currency position-value-num) " USDC")
    "--"))

(defn- position-margin-copy [margin margin-mode-label]
  (str "$" (shared/format-currency margin)
       (when margin-mode-label
         (str " (" margin-mode-label ")"))))

(defn- funding-value-node [display-funding]
  [:span {:class [(cond
                    (and (number? display-funding) (neg? display-funding)) "text-error"
                    (and (number? display-funding) (pos? display-funding)) "text-success"
                    :else "text-trading-text")
                  "num"]}
   (if (number? display-funding)
     (str "$" (shared/format-currency display-funding))
     "--")])

(defn- mobile-position-coin-node [position-data side]
  (let [pos (:position position-data)
        chip-classes (shared/position-chip-classes-for-side side)
        coin-label (display-coin pos)
        dex-label (dex-chip-label {:coin (:coin pos)
                                   :dex (:dex position-data)})
        leverage (get-in pos [:leverage :value])]
    [:span {:class ["flex" "min-w-0" "items-center" "gap-1"]}
     [:span {:class ["truncate" "font-medium" "leading-4" "text-trading-text"]} coin-label]
     (when (some? leverage)
       [:span {:class chip-classes} (str leverage "x")])
     (when dex-label
       [:span {:class chip-classes} dex-label])]))

(defn- mobile-position-action-button [label action]
  [:button {:type "button"
            :class ["inline-flex"
                    "items-center"
                    "justify-start"
                    "bg-transparent"
                    "p-0"
                    "text-sm"
                    "font-medium"
                    "leading-none"
                    "text-trading-text"
                    "transition-colors"
                    "hover:text-[#7fffe4]"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"
                    "focus-visible:text-[#7fffe4]"
                    "whitespace-nowrap"]
            :on {:click action}}
   label])

(def ^:private mobile-position-card-shell-classes
  ["overflow-hidden"
   "rounded-lg"
   "border"
   "border-[#17313d]"
   "bg-[#08161f]"])

(def ^:private mobile-position-card-button-classes
  ["w-full"
   "px-3.5"
   "py-3"
   "text-left"
   "transition-colors"
   "hover:bg-[#0c1b24]"
   "focus:outline-none"
   "focus:ring-0"
   "focus:ring-offset-0"])

(def ^:private mobile-position-card-summary-grid-classes
  ["grid"
   "grid-cols-[minmax(0,1.75fr)_minmax(0,0.95fr)_minmax(0,1.05fr)_auto]"
   "items-start"
   "gap-x-2.5"
   "gap-y-2"])

(def ^:private mobile-position-card-expanded-container-classes
  ["border-t"
   "border-[#17313d]"
   "px-3.5"
   "py-3"])

(defn position-row
  ([position-data]
   (position-row position-data nil nil nil))
  ([position-data tpsl-modal]
   (position-row position-data tpsl-modal nil nil))
  ([position-data tpsl-modal reduce-popover]
   (position-row position-data tpsl-modal reduce-popover nil))
  ([position-data tpsl-modal reduce-popover margin-modal]
   (let [pos (:position position-data)
         side (position-side pos)
         chip-classes (shared/position-chip-classes-for-side side)
         coin-cell-style (shared/position-coin-cell-style-for-side side)
         coin-tone-class (shared/position-side-tone-class side)
         size-tone-class (shared/position-side-size-class side)
         coin-label (display-coin pos)
         dex-label (dex-chip-label {:coin (:coin pos)
                                    :dex (:dex position-data)})
         leverage (get-in pos [:leverage :value])
         position-value (:positionValue pos)
         position-value-num (shared/parse-optional-num position-value)
         entry-price (:entryPx pos)
         mark-price (calculate-mark-price pos)
         pnl-num (shared/parse-optional-num (:unrealizedPnl pos))
         pnl-percent (some-> (:returnOnEquity pos) shared/parse-optional-num (* 100))
         pnl-color-class (cond
                           (and (number? pnl-num) (pos? pnl-num)) "text-success"
                           (and (number? pnl-num) (neg? pnl-num)) "text-error"
                           :else "text-trading-text")
         liq-price (:liquidationPx pos)
         margin (:marginUsed pos)
         margin-mode-label (some-> (position-margin-mode position-data)
                                   margin-mode-display-label)
         funding-num (shared/parse-optional-num (get-in pos [:cumFunding :allTime]))
         since-change-funding-num (or (shared/parse-optional-num (get-in pos [:cumFunding :sinceChange]))
                                      (shared/parse-optional-num (get-in pos [:cumFunding :since-change]))
                                      (shared/parse-optional-num (get-in pos [:cumFunding :sinceOpen]))
                                      (shared/parse-optional-num (get-in pos [:cumFunding :since-open])))
         display-funding (funding-display-value funding-num)
         display-since-change-funding (funding-display-value since-change-funding-num)
         funding-tooltip (when (number? display-funding)
                           (format-funding-tooltip display-funding display-since-change-funding))
         liq-explanation (or (shared/non-blank-text (:liquidationExplanation pos))
                             (shared/non-blank-text (:liquidation-explanation pos))
                             (shared/non-blank-text (:liquidation-explanation position-data)))
         tpsl-copy (tpsl-cell-copy position-data)
         active-modal?
         (and (position-tpsl/open? tpsl-modal)
              (= (projections/position-unique-key position-data)
                 (:position-key tpsl-modal)))
         active-reduce-popover?
         (and (position-reduce/open? reduce-popover)
              (= (projections/position-unique-key position-data)
                 (:position-key reduce-popover)))
         active-margin-modal?
         (and (position-margin/open? margin-modal)
              (= (projections/position-unique-key position-data)
                 (:position-key margin-modal)))]
     [:div {:class ["grid"
                    shared/positions-grid-template-class
                    "gap-2"
                    "py-0"
                    "pr-3"
                    shared/positions-grid-min-width-class
                    "hover:bg-base-300"
                    "items-center"
                    "text-sm"]}
      [:div {:class ["flex" "items-center" "gap-1.5" "self-stretch" "min-w-[150px]"]
             :style coin-cell-style}
       (shared/coin-select-control
        (:coin pos)
        [:span {:class ["flex" "items-center" "gap-1.5" "min-w-0"]}
         [:span {:class ["font-medium" "whitespace-nowrap" "shrink-0" coin-tone-class]} coin-label]
         (when (some? leverage)
           [:span {:class chip-classes} (str leverage "x")])
         (when dex-label
           [:span {:class chip-classes} dex-label])]
        {:extra-classes ["w-full" "justify-start" "text-left"]})]
      [:div {:class ["text-left" "font-semibold" "num" size-tone-class]} (format-position-size pos)]
      [:div.text-left.font-semibold.num
       (if (number? position-value-num)
         (str (shared/format-currency position-value-num) " USDC")
         "--")]
      [:div.text-left.font-semibold.num (shared/format-trade-price entry-price)]
      [:div.text-left.font-semibold.num (shared/format-trade-price mark-price)]
      [:div {:class ["text-left" "font-semibold" "num" pnl-color-class]}
       (format-pnl-inline pnl-num pnl-percent)]
      [:div.text-left.font-semibold.num
       (explainable-value-node
        (format-liquidation-price liq-price)
        liq-explanation)]
      [:div {:class ["text-left" "relative" "font-semibold" "num"]}
       [:div {:class ["inline-flex" "items-center" "gap-0.5" "whitespace-nowrap"]}
        [:span {:class ["inline-flex" "items-baseline" "gap-1" "whitespace-nowrap" "select-text"]}
         [:span {:class ["num"]}
          (str "$" (shared/format-currency margin))]
         (when margin-mode-label
           [:span {:class ["text-xs" "font-medium" "text-trading-text-secondary"]}
            (str "(" margin-mode-label ")")])]
        [:button {:class ["inline-flex"
                          "h-4"
                          "w-4"
                          "items-center"
                          "justify-center"
                          "shrink-0"
                          "bg-transparent"
                          "p-0"
                          "text-trading-green"
                          "hover:text-[#7fffe4]"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"
                          "focus-visible:outline-none"
                          "focus-visible:ring-0"
                          "focus-visible:ring-offset-0"
                          "focus-visible:text-[#7fffe4]"]
                  :type "button"
                  :aria-label "Edit Margin"
                  :data-position-margin-trigger "true"
                  :on {:click [[:actions/open-position-margin-modal position-data :event.currentTarget/bounds]]}}
         (edit-icon)]]
       (when active-margin-modal?
         (position-margin-modal/position-margin-modal-view margin-modal))]
      [:div.text-left.font-semibold.num
       (explainable-value-node
        [:span {:class [(cond
                          (and (number? display-funding) (neg? display-funding)) "text-error"
                          (and (number? display-funding) (pos? display-funding)) "text-success"
                          :else "text-trading-text")
                        "num"]}
         (if (number? display-funding)
           (str "$" (shared/format-currency display-funding))
           "--")]
        funding-tooltip
        {:underlined? false})]
      [:div {:class ["text-left" "relative"]}
       [:button {:class ["inline-flex"
                         "w-full"
                         "justify-start"
                         "bg-transparent"
                         "p-0"
                         "font-semibold"
                         "text-trading-green"
                         "transition-colors"
                         "focus:outline-none"
                         "focus:ring-0"
                         "focus:ring-offset-0"
                         "focus:shadow-none"
                         "focus-visible:outline-none"
                         "focus-visible:ring-0"
                         "focus-visible:ring-offset-0"
                         "hover:text-[#7fffe4]"
                         "focus-visible:text-[#7fffe4]"
                         "whitespace-nowrap"]
                 :type "button"
                 :data-position-reduce-trigger "true"
                 :on {:click [[:actions/open-position-reduce-popover position-data :event.currentTarget/bounds]]}}
        "Reduce"]
       (when active-reduce-popover?
         (position-reduce-popover/position-reduce-popover-view reduce-popover))]
      [:div {:class ["text-left" "relative"]}
       [:div {:class ["inline-flex" "items-center" "gap-0.5" "whitespace-nowrap"]}
        [:span {:class ["font-normal" "text-trading-text" "whitespace-nowrap" "select-text"]} tpsl-copy]
        [:button {:class ["inline-flex"
                          "h-4"
                          "w-4"
                          "items-center"
                          "justify-center"
                          "shrink-0"
                          "bg-transparent"
                          "p-0"
                          "text-trading-green"
                          "hover:text-[#7fffe4]"
                          "focus:outline-none"
                          "focus:ring-0"
                          "focus:ring-offset-0"
                          "focus-visible:outline-none"
                          "focus-visible:ring-0"
                          "focus-visible:ring-offset-0"
                          "focus-visible:text-[#7fffe4]"]
                  :type "button"
                  :aria-label "Edit TP/SL"
                  :data-position-tpsl-trigger "true"
                  :on {:click [[:actions/open-position-tpsl-modal position-data :event.currentTarget/bounds]]}}
         (edit-icon)]]
       (when active-modal?
         (position-tpsl-modal/position-tpsl-modal-view tpsl-modal))]])))

(defn- mobile-position-card [expanded-row-id position-data tpsl-modal reduce-popover margin-modal]
  (let [pos (:position position-data)
        side (position-side pos)
        row-id (some-> (position-unique-key position-data) str str/trim)
        expanded? (= expanded-row-id row-id)
        position-value-num (shared/parse-optional-num (:positionValue pos))
        pnl-num (shared/parse-optional-num (:unrealizedPnl pos))
        pnl-percent (some-> (:returnOnEquity pos) shared/parse-optional-num (* 100))
        pnl-color-class (cond
                          (and (number? pnl-num) (pos? pnl-num)) "text-success"
                          (and (number? pnl-num) (neg? pnl-num)) "text-error"
                          :else "text-trading-text")
        margin-mode-label (some-> (position-margin-mode position-data)
                                  margin-mode-display-label)
        funding-num (shared/parse-optional-num (get-in pos [:cumFunding :allTime]))
        display-funding (funding-display-value funding-num)
        active-modal? (and (position-tpsl/open? tpsl-modal)
                           (= (position-unique-key position-data)
                              (:position-key tpsl-modal)))
        active-reduce-popover? (and (position-reduce/open? reduce-popover)
                                    (= (position-unique-key position-data)
                                       (:position-key reduce-popover)))
        active-margin-modal? (and (position-margin/open? margin-modal)
                                  (= (position-unique-key position-data)
                                     (:position-key margin-modal)))]
    (mobile-cards/expandable-card
     {:data-role (str "mobile-position-card-" row-id)
      :expanded? expanded?
      :toggle-actions [[:actions/toggle-account-info-mobile-card :positions row-id]]
      :card-classes mobile-position-card-shell-classes
      :button-classes mobile-position-card-button-classes
      :summary-grid-classes mobile-position-card-summary-grid-classes
      :expanded-container-classes mobile-position-card-expanded-container-classes
      :summary-items [(mobile-cards/summary-item "Coin"
                                                 (mobile-position-coin-node position-data side)
                                                 {:root-classes ["pr-1"]
                                                  :value-classes ["font-medium" "leading-4"]})
                      (mobile-cards/summary-item "Size"
                                                 (format-position-size pos)
                                                 {:value-classes ["num" "font-medium" "leading-4" "whitespace-nowrap"]})
                      (mobile-cards/summary-item "PNL (ROE %)"
                                                 [:span {:class ["num" pnl-color-class]}
                                                  (format-pnl-inline pnl-num pnl-percent)]
                                                 {:value-classes ["font-medium" "leading-4" "whitespace-nowrap"]})]
      :detail-content
      [:div {:class ["space-y-3"]}
       (mobile-cards/detail-grid
        "grid-cols-3"
        [(mobile-cards/detail-item "Entry Price"
                                   (shared/format-trade-price (:entryPx pos))
                                   {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
         (mobile-cards/detail-item "Mark Price"
                                   (shared/format-trade-price (calculate-mark-price pos))
                                   {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
         (mobile-cards/detail-item "Liq. Price"
                                   (format-liquidation-price (:liquidationPx pos))
                                   {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
         (mobile-cards/detail-item "Position Value"
                                   (position-value-copy position-value-num)
                                   {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
         (mobile-cards/detail-item "Margin"
                                   (position-margin-copy (:marginUsed pos) margin-mode-label)
                                   {:value-classes ["num" "font-medium" "whitespace-nowrap"]})
         (mobile-cards/detail-item "TP/SL"
                                   (tpsl-cell-copy position-data)
                                   {:value-classes ["font-medium" "whitespace-nowrap"]})
         (mobile-cards/detail-item "Funding"
                                   (funding-value-node display-funding)
                                   {:value-classes ["font-medium" "whitespace-nowrap"]})])
       [:div {:class ["border-t" "border-[#17313d]" "pt-2.5"]}
        [:div {:class ["relative" "flex" "flex-wrap" "items-center" "gap-x-5" "gap-y-2"]}
         (mobile-position-action-button
          "Close"
          [[:actions/open-position-reduce-popover position-data :event.currentTarget/bounds]])
         (mobile-position-action-button
          "Margin"
          [[:actions/open-position-margin-modal position-data :event.currentTarget/bounds]])
         (mobile-position-action-button
          "TP/SL"
          [[:actions/open-position-tpsl-modal position-data :event.currentTarget/bounds]])
         (when active-reduce-popover?
           (position-reduce-popover/position-reduce-popover-view reduce-popover))
         (when active-margin-modal?
           (position-margin-modal/position-margin-modal-view margin-modal))
         (when active-modal?
           (position-tpsl-modal/position-tpsl-modal-view tpsl-modal))]]]})))

(defn sort-positions-by-column [positions column direction]
  (sort-kernel/sort-rows-by-column
   positions
   {:column column
    :direction direction
    :accessor-by-column
    {"Coin" (fn [pos] (:coin (:position pos)))
     "Size" (fn [pos] (or (shared/parse-optional-num (:szi (:position pos))) 0))
     "Position Value" (fn [pos] (or (shared/parse-optional-num (:positionValue (:position pos))) 0))
     "Entry Price" (fn [pos] (or (shared/parse-optional-num (:entryPx (:position pos))) 0))
     "Mark Price" (fn [pos] (or (shared/parse-optional-num (calculate-mark-price (:position pos))) 0))
     "PNL (ROE %)" (fn [pos] (or (shared/parse-optional-num (:unrealizedPnl (:position pos))) 0))
     "Liq. Price" (fn [pos] (let [liq (:liquidationPx (:position pos))]
                              (if liq
                                (or (shared/parse-optional-num liq) js/Number.MAX_VALUE)
                                js/Number.MAX_VALUE)))
     "Margin" (fn [pos] (or (shared/parse-optional-num (:marginUsed (:position pos))) 0))
     "Funding" (fn [pos] (or (funding-display-value
                              (shared/parse-optional-num (get-in (:position pos) [:cumFunding :allTime])))
                             0))}}))

(defonce ^:private sorted-positions-cache (atom nil))

(defn reset-positions-sort-cache! []
  (reset! sorted-positions-cache nil))

(defn- memoized-sorted-positions [positions direction-filter sort-state coin-search]
  (let [column (:column sort-state)
        direction (:direction sort-state)
        cache @sorted-positions-cache
        row-match (cache-keys/rows-match-state positions
                                               (:positions cache)
                                               (:positions-signature cache))
        cache-hit? (and (map? cache)
                        (:same-input? row-match)
                        (= direction-filter (:direction-filter cache))
                        (= coin-search (:coin-search cache))
                        (= column (:column cache))
                        (= direction (:direction cache)))]
    (if cache-hit?
      (:result cache)
      (let [direction-filtered (filter-positions-by-direction positions direction-filter)
            search-filtered (filter-positions-by-coin-search direction-filtered coin-search)
            result (vec (sort-positions-by-column search-filtered column direction))]
        (reset! sorted-positions-cache {:positions positions
                                        :positions-signature (:signature row-match)
                                        :direction-filter direction-filter
                                        :coin-search coin-search
                                        :column column
                                        :direction direction
                                        :result result})
        result))))

(def ^:private pnl-header-explanation
  "Mark price is used to estimate unrealized PNL. Only trade prices are used for realized PNL.")

(def ^:private margin-header-explanation
  "For isolated positions, margin includes unrealized pnl.")

(def ^:private funding-header-explanation
  "Net funding payments since the position was opened. Hover for all-time and since changed.")

(defn sortable-header
  ([column-name sort-state]
   (sortable-header column-name sort-state nil))
  ([column-name sort-state explanation]
   (table/sortable-header-button column-name
                                 sort-state
                                 :actions/sort-positions
                                 {:explanation explanation})))

(defn position-table-header
  ([sort-state]
   (position-table-header sort-state []))
  ([sort-state extra-classes]
   [:div {:class (into ["grid"
                        shared/positions-grid-template-class
                        "gap-2"
                        "py-1"
                        "pr-3"
                        shared/positions-grid-min-width-class
                        "bg-base-200"]
                       extra-classes)}
    [:div.text-left.pl-3 (sortable-header "Coin" sort-state)]
    [:div.text-left (sortable-header "Size" sort-state)]
    [:div.text-left (sortable-header "Position Value" sort-state)]
    [:div.text-left (sortable-header "Entry Price" sort-state)]
    [:div.text-left (sortable-header "Mark Price" sort-state)]
    [:div.text-left (sortable-header "PNL (ROE %)" sort-state pnl-header-explanation)]
    [:div.text-left (sortable-header "Liq. Price" sort-state)]
    [:div.text-left (sortable-header "Margin" sort-state margin-header-explanation)]
    [:div.text-left (sortable-header "Funding" sort-state funding-header-explanation)]
    [:div.text-left
     [:button {:class (into ["w-full"
                             "text-left"
                             "focus:outline-none"
                             "focus:ring-1"
                             "focus:ring-[#8a96a6]/40"
                             "focus:ring-offset-0"
                             "focus:shadow-none"]
                            (concat table/header-base-text-classes
                                    table/sortable-header-interaction-classes))
               :type "button"
               :on {:click [[:actions/trigger-close-all-positions]]}}
      "Close All"]]
    [:div.text-left (table/non-sortable-header "TP/SL")]]))

(defn- positions-tab-content-from-rows
  [positions sort-state tpsl-modal reduce-popover margin-modal positions-state]
  (let [positions* (or positions [])
        direction-filter (positions-direction-filter-key positions-state)
        coin-search (:coin-search positions-state "")
        expanded-row-id (get-in positions-state [:mobile-expanded-card :positions])
        sorted-positions (if (seq positions*)
                           (memoized-sorted-positions positions* direction-filter sort-state coin-search)
                           [])]
    (if (seq sorted-positions)
      [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]}
       (position-table-header sort-state ["hidden" "lg:grid"])
       (into [:div {:class ["hidden"
                            "lg:block"
                            "flex-1"
                            "min-h-0"
                            "overflow-y-auto"
                            "scrollbar-hide"]
                   :data-role "account-tab-rows-viewport"}]
             (map (fn [position]
                    ^{:key (position-unique-key position)}
                    (position-row position tpsl-modal reduce-popover margin-modal))
                  sorted-positions))
       (into [:div {:class ["lg:hidden"
                            "flex-1"
                            "min-h-0"
                            "overflow-y-auto"
                            "scrollbar-hide"
                            "space-y-2.5"
                            "px-2.5"
                            "py-2"]
                   :data-role "positions-mobile-cards-viewport"}]
             (map (fn [position]
                    ^{:key (str "mobile-" (position-unique-key position))}
                    (mobile-position-card expanded-row-id
                                          position
                                          tpsl-modal
                                          reduce-popover
                                          margin-modal))
                  sorted-positions))]
      (empty-state "No active positions"))))

(defn positions-tab-content
  ([positions sort-state]
   (positions-tab-content-from-rows positions sort-state nil nil nil {}))
  ([positions-or-webdata2 sort-state tpsl-modal-or-perp-dex-states]
   (if (and (map? positions-or-webdata2)
            (contains? positions-or-webdata2 :clearinghouseState))
     (positions-tab-content-from-rows
      (collect-positions positions-or-webdata2 tpsl-modal-or-perp-dex-states)
      sort-state
      nil
      nil
      nil
      {})
     (positions-tab-content-from-rows positions-or-webdata2 sort-state tpsl-modal-or-perp-dex-states nil nil {})))
  ([positions-or-webdata2 sort-state tpsl-modal-or-perp-dex-states tpsl-modal-or-positions-state]
   (if (and (map? positions-or-webdata2)
            (contains? positions-or-webdata2 :clearinghouseState))
     (positions-tab-content-from-rows
      (collect-positions positions-or-webdata2 tpsl-modal-or-perp-dex-states)
      sort-state
      tpsl-modal-or-positions-state
      nil
      nil
      {})
     (positions-tab-content-from-rows
      positions-or-webdata2
      sort-state
      tpsl-modal-or-perp-dex-states
      nil
      nil
      tpsl-modal-or-positions-state)))
  ([positions-or-webdata2 sort-state tpsl-modal-or-perp-dex-states reduce-popover-or-modal positions-state]
   (if (and (map? positions-or-webdata2)
            (contains? positions-or-webdata2 :clearinghouseState))
     (positions-tab-content-from-rows
      (collect-positions positions-or-webdata2 tpsl-modal-or-perp-dex-states)
      sort-state
      reduce-popover-or-modal
      nil
      nil
      positions-state)
     (positions-tab-content-from-rows
      positions-or-webdata2
      sort-state
      tpsl-modal-or-perp-dex-states
      reduce-popover-or-modal
      nil
      positions-state)))
  ([positions-or-webdata2 sort-state tpsl-modal-or-perp-dex-states reduce-popover-or-tpsl margin-modal-or-positions-state positions-state]
   (if (and (map? positions-or-webdata2)
            (contains? positions-or-webdata2 :clearinghouseState))
     (positions-tab-content-from-rows
      (collect-positions positions-or-webdata2 tpsl-modal-or-perp-dex-states)
      sort-state
      reduce-popover-or-tpsl
      margin-modal-or-positions-state
      nil
      positions-state)
     (positions-tab-content-from-rows
      positions-or-webdata2
      sort-state
      tpsl-modal-or-perp-dex-states
      reduce-popover-or-tpsl
      margin-modal-or-positions-state
      positions-state)))
  ([webdata2 sort-state perp-dex-states tpsl-modal reduce-popover margin-modal positions-state]
   (positions-tab-content-from-rows
    (collect-positions webdata2 perp-dex-states)
    sort-state
    tpsl-modal
    reduce-popover
    margin-modal
    positions-state)))
