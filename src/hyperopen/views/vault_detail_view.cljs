(ns hyperopen.views.vault-detail-view
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.wallet.core :as wallet]
            [hyperopen.views.vaults.detail-vm :as detail-vm]))

(defn- format-currency
  ([value]
   (format-currency value {:missing "—"}))
  ([value {:keys [missing]
           :or {missing "—"}}]
   (if (number? value)
     (or (fmt/format-currency value)
         "$0.00")
     missing)))

(defn- format-price
  [value]
  (if (number? value)
    (fmt/format-trade-price-plain value)
    "—"))

(defn- format-size
  [value]
  (if (number? value)
    (.toFixed value 4)
    "—"))

(defn- format-percent
  ([value]
   (format-percent value {:missing "—"}))
  ([value {:keys [missing
                  signed?
                  decimals]
           :or {missing "—"
                signed? true
                decimals 2}}]
   (if (number? value)
     (let [n value
           sign (cond
                  (and signed? (pos? n)) "+"
                  (neg? n) "-"
                  :else "")]
       (str sign (.toFixed (js/Math.abs n) decimals) "%"))
     missing)))

(defn- format-funding-rate
  [value]
  (if (number? value)
    (str (.toFixed (* 100 value) 4) "%")
    "—"))

(defn- format-time
  [time-ms]
  (or (fmt/format-local-date-time time-ms)
      "—"))

(defn- format-chart-axis-value
  [axis-kind value]
  (let [n (if (number? value) value 0)
        n* (if (== n -0) 0 n)]
    (case axis-kind
      :pnl (or (fmt/format-large-currency n*) "$0")
      :account-value (or (fmt/format-large-currency n*) "$0")
      (or (fmt/format-large-currency n*) "$0"))))

(defn- format-chart-tooltip-value
  [axis-kind value]
  (let [n (if (number? value) value 0)
        n* (if (== n -0) 0 n)]
    (case axis-kind
      :pnl (format-currency n* {:missing "$0.00"})
      :account-value (format-currency n* {:missing "$0.00"})
      (format-currency n* {:missing "$0.00"}))))

(defn- clamp-number
  [value min-value max-value]
  (cond
    (< value min-value) min-value
    (> value max-value) max-value
    :else value))

(def ^:private axis-label-fallback-char-width-px
  7.5)

(def ^:private axis-label-horizontal-padding-px
  30)

(def ^:private axis-label-min-gutter-width-px
  56)

(def ^:private axis-label-measure-context
  (delay
    (when (and (exists? js/document)
               (some? js/document))
      (let [canvas (.createElement js/document "canvas")]
        (.getContext canvas "2d")))))

(defn- axis-label-width-px [text]
  (let [context @axis-label-measure-context]
    (if context
      (do
        ;; Match chart tick labels rendered at 12px for reliable gutter width.
        (set! (.-font context) "12px \"Inter Variable\", system-ui, -apple-system, \"Segoe UI\", sans-serif")
        (-> context
            (.measureText text)
            .-width))
      (* axis-label-fallback-char-width-px (count text)))))

(defn- y-axis-gutter-width [axis-kind y-ticks]
  (let [widest-label-px (->> y-ticks
                             (map (fn [{:keys [value]}]
                                    (axis-label-width-px (format-chart-axis-value axis-kind value))))
                             (reduce max 0))
        gutter-width (+ widest-label-px axis-label-horizontal-padding-px)]
    (js/Math.ceil (max axis-label-min-gutter-width-px gutter-width))))

(defn- chart-tooltip-label
  [summary-time-range axis-kind {:keys [time-ms value]}]
  (let [time-label (if (= summary-time-range :day)
                     (or (fmt/format-local-time-hh-mm-ss time-ms) "—")
                     (or (fmt/format-local-date-time time-ms) "—"))]
    (str time-label ": " (format-chart-tooltip-value axis-kind value))))

(defn- short-hash
  [value]
  (if (and (string? value)
           (> (count value) 12))
    (str (subs value 0 8) "..." (subs value (- (count value) 6)))
    (or value "—")))

(defn- metric-value-size-classes
  [value]
  (let [value-length (count (str (or value "")))]
    (cond
      (> value-length 16) ["text-[18px]" "sm:text-[22px]" "lg:text-[30px]"]
      (> value-length 12) ["text-[20px]" "sm:text-[24px]" "lg:text-[34px]"]
      :else ["text-[22px]" "sm:text-[28px]" "lg:text-[38px]"])))

(defn- metric-card
  [{:keys [label value accent]}]
  [:div {:class ["rounded-xl"
                 "border"
                 "border-[#1a3a37]"
                 "bg-[#091a23]/88"
                 "min-w-0"
                 "px-3.5"
                 "py-3"
                 "shadow-[inset_0_0_0_1px_rgba(8,38,45,0.35)]"]}
   [:div {:class ["text-xs"
                  "uppercase"
                  "tracking-[0.08em]"
                  "text-[#8ba0a7]"]}
    label]
   [:div {:class (into ["mt-1.5"
                        "num"
                        "leading-[1.08]"
                        "font-semibold"]
                       (concat (metric-value-size-classes value)
                               (case accent
                                 :positive ["text-[#5de2c0]"]
                                 :negative ["text-[#e59ca8]"]
                                 ["text-trading-text"])))}
    value]])

(defn- format-activity-count [count]
  (cond
    (not (number? count)) nil
    (<= count 0) nil
    (>= count 100) "100+"
    :else (str count)))

(defn- detail-tab-button [{:keys [value label]} selected-tab]
  [:button {:type "button"
            :class (into ["border-b"
                          "px-3"
                          "py-2.5"
                          "text-sm"
                          "font-medium"
                          "transition-colors"]
                         (if (= value selected-tab)
                           ["border-[#66e3c5]" "text-trading-text"]
                           ["border-transparent" "text-[#8ea0a7]" "hover:text-trading-text"]))
            :on {:click [[:actions/set-vault-detail-tab value]]}}
   label])

(defn- chart-series-button [{:keys [value label]} selected-series]
  [:button {:type "button"
            :class (into ["rounded-md"
                          "border"
                          "px-2.5"
                          "py-1"
                          "text-xs"
                          "transition-colors"]
                         (if (= value selected-series)
                           ["border-[#2f5e58]" "bg-[#0d252f]" "text-trading-text"]
                           ["border-transparent" "text-[#8ea4ab]" "hover:text-trading-text"]))
            :on {:click [[:actions/set-vault-detail-chart-series value]]}}
   label])

(defn- chart-timeframe-menu [{:keys [timeframe-options selected-timeframe]}]
  [:label {:class ["inline-flex"
                   "items-center"
                   "gap-1.5"
                   "rounded-md"
                   "border"
                   "border-[#1f3b3c]"
                   "bg-[#071e25]"
                   "px-2.5"
                   "py-1"
                   "text-xs"
                   "text-trading-text"]}
   [:span "Range "]
   [:select {:class ["bg-transparent"
                     "text-trading-text"
                     "outline-none"
                     "focus:outline-none"
                     "focus:ring-0"
                     "focus:border-transparent"
                     "focus-visible:outline-none"
                     "focus-visible:ring-0"
                     "border-none"
                     "p-0"
                     "pr-4"
                     "text-xs"]
             :value (name selected-timeframe)
             :on {:change [[:actions/set-vaults-snapshot-range [:event.target/value]]]}}
    (for [{:keys [value label]} timeframe-options]
      ^{:key (str "vault-chart-timeframe-" (name value))}
      [:option {:value (name value)}
       label])]])

(defn- activity-tab-button [{:keys [value label count]} selected-tab]
  [:button {:type "button"
            :class (into ["whitespace-nowrap"
                          "border-b"
                          "px-4"
                          "py-2.5"
                          "text-sm"
                          "font-normal"
                          "transition-colors"]
                         (if (= value selected-tab)
                           ["border-[#303030]" "text-[#f6fefd]"]
                           ["border-[#303030]" "text-[#949e9c]" "hover:text-[#f6fefd]"]))
            :on {:click [[:actions/set-vault-detail-activity-tab value]]}}
   (if-let [count-label (format-activity-count count)]
     (str label " (" count-label ")")
     label)])

(defn- render-address-list [addresses]
  (when (seq addresses)
    [:div {:class ["space-y-1.5"]}
     [:div {:class ["text-[#8da0a6]"]}
      "This vault uses the following vaults as component strategies:"]
     (for [address addresses]
       ^{:key (str "component-vault-" address)}
       [:div {:class ["num" "break-all" "text-[#33d1b7]"]}
        address])]))

(defn- render-about-panel [{:keys [description leader relationship]}]
  (let [component-addresses (or (:child-addresses relationship) [])
        parent-address (:parent-address relationship)]
    [:div {:class ["space-y-3" "px-3" "pb-3" "pt-2" "text-sm"]}
     [:div
      [:div {:class ["text-[#8da0a6]"]} "Leader"]
      [:div {:class ["num" "font-medium" "text-trading-text"]}
       (or (wallet/short-addr leader) "—")]]
     [:div
      [:div {:class ["text-[#8da0a6]"]} "Description"]
      [:p {:class ["mt-1" "leading-5" "text-trading-text"]}
       (if (seq description)
         description
         "No vault description available.")]]
     (when parent-address
       [:div {:class ["text-[#8da0a6]"]}
        "Parent strategy: "
        [:button {:type "button"
                  :class ["num" "text-[#66e3c5]" "hover:underline"]
                  :on {:click [[:actions/navigate (str "/vaults/" parent-address)]]}}
         parent-address]])
     (render-address-list component-addresses)]))

(defn- render-vault-performance-panel [{:keys [snapshot]}]
  [:div {:class ["grid" "grid-cols-2" "gap-3" "px-3" "pb-3" "pt-2" "text-sm"]}
   [:div
    [:div {:class ["text-[#8da0a6]"]} "24H"]
    [:div {:class ["num" "font-medium" "text-trading-text"]} (format-percent (:day snapshot))]]
   [:div
    [:div {:class ["text-[#8da0a6]"]} "7D"]
    [:div {:class ["num" "font-medium" "text-trading-text"]} (format-percent (:week snapshot))]]
   [:div
    [:div {:class ["text-[#8da0a6]"]} "30D"]
    [:div {:class ["num" "font-medium" "text-trading-text"]} (format-percent (:month snapshot))]]
   [:div
    [:div {:class ["text-[#8da0a6]"]} "All-time"]
    [:div {:class ["num" "font-medium" "text-trading-text"]} (format-percent (:all-time snapshot))]]])

(defn- render-your-performance-panel [metrics]
  [:div {:class ["space-y-3" "px-3" "pb-3" "pt-2" "text-sm"]}
   [:div
    [:div {:class ["text-[#8da0a6]"]} "Your Deposits"]
    [:div {:class ["num" "font-medium" "text-trading-text"]}
     (format-currency (:your-deposit metrics))]]
   [:div
    [:div {:class ["text-[#8da0a6]"]} "All-time Earned"]
    [:div {:class ["num" "font-medium" "text-trading-text"]}
     (format-currency (:all-time-earned metrics))]]])

(defn- render-tab-panel [{:keys [selected-tab] :as vm}]
  (case selected-tab
    :vault-performance (render-vault-performance-panel vm)
    :your-performance (render-your-performance-panel (:metrics vm))
    (render-about-panel vm)))

(defn- relationship-links [{:keys [relationship]}]
  (case (:type relationship)
    :child
    (when-let [parent-address (:parent-address relationship)]
      [:div {:class ["mt-1.5" "text-xs" "text-[#8fa3aa]"]}
       "Parent strategy: "
       [:button {:type "button"
                 :class ["num" "text-[#66e3c5]" "hover:underline"]
                 :on {:click [[:actions/navigate (str "/vaults/" parent-address)]]}}
        (wallet/short-addr parent-address)]])

    nil))

(def ^:private activity-direction-filter-tabs
  #{:positions
    :open-orders
    :twap
    :trade-history
    :funding-history
    :order-history})

(defn- activity-direction-filter-enabled?
  [activity-tab]
  (contains? activity-direction-filter-tabs activity-tab))

(defn- sort-header-button
  [tab label sort-state]
  (let [active? (= label (:column sort-state))
        direction (:direction sort-state)
        icon (when active?
               (if (= :asc direction) "↑" "↓"))]
    [:button {:type "button"
              :class (into ["group"
                            "inline-flex"
                            "items-center"
                            "gap-1"
                            "text-xs"
                            "font-medium"
                            "text-[#949e9c]"
                            "transition-colors"
                            "hover:text-[#f6fefd]"]
                           (when active?
                             ["text-[#f6fefd]"]))
              :on {:click [[:actions/sort-vault-detail-activity tab label]]}}
     [:span label]
     (when icon
       [:span {:class ["text-xs" "opacity-70"]}
        icon])]))

(defn- table-header [tab labels sort-state]
  [:thead
   [:tr {:class ["border-b" "border-[#1b3237]" "bg-transparent" "text-xs" "font-medium" "text-[#949e9c]"]}
    (for [label labels]
      ^{:key (str "activity-header-" label)}
      [:th {:class ["px-4" "py-2" "text-left" "whitespace-nowrap" "font-medium"]}
       (sort-header-button tab label sort-state)])]])

(defn- empty-table-row [col-span message]
  [:tr
   [:td {:col-span col-span
         :class ["px-4" "py-6" "text-left" "text-sm" "text-[#8f9ea5]"]}
    message]])

(defn- error-table-row [col-span message]
  [:tr
   [:td {:col-span col-span
         :class ["px-4" "py-6" "text-left" "text-sm" "text-red-300"]}
    message]])

(defn- position-pnl-class [pnl]
  (cond
    (and (number? pnl) (pos? pnl)) "text-[#1fa67d]"
    (and (number? pnl) (neg? pnl)) "text-[#ed7088]"
    :else "text-trading-text"))

(defn- normalize-side
  [value]
  (case (cond
          (keyword? value) (some-> value name str/lower-case)
          :else (some-> value str str/trim str/lower-case))
    ("long" "buy" "b") :long
    ("short" "sell" "a" "s") :short
    nil))

(defn- side-tone-class
  [value]
  (case (normalize-side value)
    :long "text-[#1fa67d]"
    :short "text-[#ed7088]"
    "text-trading-text"))

(defn- side-coin-tone-class
  [value]
  (case (normalize-side value)
    :long "text-[#97fce4]"
    :short "text-[#eaafb8]"
    "text-trading-text"))

(defn- side-coin-cell-style
  [value]
  (case (normalize-side value)
    :long {:background "linear-gradient(90deg,rgb(31,166,125) 0px,rgb(31,166,125) 4px,rgba(11,50,38,0.92) 4px,transparent 100%)"
           :padding-left "12px"}
    :short {:background "linear-gradient(90deg,rgb(237,112,136) 0px,rgb(237,112,136) 4px,rgba(52,36,46,0.92) 4px,transparent 100%)"
            :padding-left "12px"}
    nil))

(defn- interactive-value-class
  []
  ["underline" "decoration-dotted" "underline-offset-2"])

(defn- status-tone-class
  [status]
  (let [status* (some-> status str str/trim str/lower-case)]
    (cond
      (or (= status* "filled")
          (= status* "complete")
          (= status* "completed")
          (= status* "open")
          (= status* "active")) "text-[#1fa67d]"
      (or (= status* "rejected")
          (= status* "error")
          (= status* "failed")) "text-[#ed7088]"
      (or (= status* "canceled")
          (= status* "cancelled")
          (= status* "closed")) "text-[#9aa7ad]"
      :else "text-trading-text")))

(defn- ledger-type-tone-class
  [type-label]
  (case (some-> type-label str str/lower-case)
    "deposit" "text-[#1fa67d]"
    "withdraw" "text-[#ed7088]"
    "text-trading-text"))

(def ^:private activity-row-class
  ["border-b"
   "border-[#1b3237]"
   "text-sm"
   "text-[#f6fefd]"
   "transition-colors"
   "hover:bg-[#0d2028]/40"])

(def ^:private activity-cell-class
  ["px-4" "py-2.5"])

(def ^:private activity-cell-num-class
  ["px-4" "py-2.5" "num"])

(defn- balances-table [rows sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[760px]" "border-collapse"]}
    (table-header :balances ["Coin" "Total Balance" "Available Balance" "USDC Value"] sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [coin total available usdc-value]} rows]
         ^{:key (str "balance-" coin "-" total)}
         [:tr {:class activity-row-class}
          [:td {:class (into activity-cell-class ["whitespace-nowrap" "text-[#f6fefd]"])}
           (or coin "—")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
           (format-currency total)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])}
           [:span {:class (into ["text-[#f6fefd]"] (interactive-value-class))}
            (format-currency available)]]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
           (format-currency usdc-value)]])
       (empty-table-row 4 "No balances available."))]]])

(defn- positions-table [rows sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1285px]" "border-collapse"]}
    (table-header :positions ["Coin" "Size" "Position Value" "Entry Price" "Mark Price" "PNL (ROE %)" "Liq. Price" "Margin" "Funding"] sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [coin leverage size position-value entry-price mark-price pnl roe liq-price margin funding]} rows]
         (let [side (if (number? size)
                      (if (neg? size) :short :long)
                      nil)]
           ^{:key (str "position-" coin "-" size "-" entry-price)}
           [:tr {:class activity-row-class}
            [:td {:class (into activity-cell-class ["whitespace-nowrap"])
                  :style (side-coin-cell-style side)}
             [:span {:class [(side-coin-tone-class side)]}
              (or coin "—")]
             (when (number? leverage)
               [:span {:class ["ml-1" (side-tone-class side)]}
                (str leverage "x")])]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (side-tone-class side)])}
             (format-size size)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
             (if (number? position-value)
               (str (fmt/format-currency position-value) " USDC")
               "—")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
             (format-price entry-price)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
             (format-price mark-price)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class pnl)])}
             (if (number? pnl)
               (str (format-currency pnl {:missing "—"}) " (" (format-percent roe) ")")
               "—")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
             (if (number? liq-price)
               (format-price liq-price)
               "N/A")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
             (if (number? margin)
               (str (format-currency margin) " (Cross)")
               "—")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class funding)])}
             (format-currency funding)]]))
       (empty-table-row 9 "No active positions."))]]])

(defn- open-orders-table [rows sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[960px]" "border-collapse"]}
    (table-header :open-orders ["Time" "Coin" "Side" "Size" "Price" "Trigger"] sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [time-ms coin side size price trigger-price]} rows]
         (let [side* (normalize-side side)]
           ^{:key (str "open-order-" time-ms "-" coin "-" size "-" price)}
           [:tr {:class activity-row-class}
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])} (format-time time-ms)]
            [:td {:class (into activity-cell-class ["whitespace-nowrap"])
                  :style (side-coin-cell-style side*)}
             [:span {:class [(side-coin-tone-class side*)]}
              (or coin "—")]]
            [:td {:class (into activity-cell-class ["whitespace-nowrap" (side-tone-class side*)])} (or side "—")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (side-tone-class side*)])} (format-size size)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-price price)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-price trigger-price)]]))
       (empty-table-row 6 "No open orders."))]]])

(defn- twap-table [rows sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1260px]" "border-collapse"]}
    (table-header :twap ["Coin" "Size" "Executed Size" "Average Price" "Running Time / Total" "Reduce Only" "Creation Time" "Terminate"] sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [coin size executed-size average-price running-label reduce-only? creation-time-ms]} rows]
         ^{:key (str "twap-" coin "-" creation-time-ms "-" size)}
         [:tr {:class activity-row-class}
          [:td {:class (into activity-cell-class ["whitespace-nowrap" "text-[#f6fefd]"])} (or coin "—")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-size size)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-size executed-size)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-price average-price)]
          [:td {:class (into activity-cell-class ["whitespace-nowrap" "text-[#f6fefd]"])} (or running-label "—")]
          [:td {:class (into activity-cell-class ["whitespace-nowrap" (if (true? reduce-only?) "text-[#ed7088]" "text-[#1fa67d]")])}
           (if (true? reduce-only?) "Yes" "No")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-time creation-time-ms)]
          [:td {:class (into activity-cell-class ["whitespace-nowrap" "text-[#8f9ea5]"])} "—"]])
       (empty-table-row 8 "No TWAPs yet."))]]])

(defn- fills-table [rows loading? error sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1180px]" "border-collapse"]}
    (table-header :trade-history ["Time" "Coin" "Side" "Price" "Size" "Trade Value" "Fee" "Closed PNL"] sort-state)
    [:tbody
     (cond
       (seq error)
       (error-table-row 8 error)

       loading?
       (empty-table-row 8 "Loading trade history...")

       (seq rows)
       (for [{:keys [time-ms coin side size price trade-value fee closed-pnl]} rows]
         (let [side* (normalize-side side)]
           ^{:key (str "fill-" time-ms "-" coin "-" size "-" price)}
           [:tr {:class activity-row-class}
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])} (format-time time-ms)]
            [:td {:class (into activity-cell-class ["whitespace-nowrap"])
                  :style (side-coin-cell-style side*)}
             [:span {:class [(side-coin-tone-class side*)]}
              (or coin "—")]]
            [:td {:class (into activity-cell-class ["whitespace-nowrap" (side-tone-class side*)])}
             (or side "—")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-price price)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-size size)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-currency trade-value)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-currency fee)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class closed-pnl)])}
             (format-currency closed-pnl)]]))

       :else
       (empty-table-row 8 "No recent fills."))]]])

(defn- funding-history-table [rows loading? error sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[920px]" "border-collapse"]}
    (table-header :funding-history ["Time" "Coin" "Funding Rate" "Position Size" "Payment"] sort-state)
    [:tbody
     (cond
       (seq error)
       (error-table-row 5 error)

       loading?
       (empty-table-row 5 "Loading funding history...")

       (seq rows)
       (for [{:keys [time-ms coin funding-rate position-size payment]} rows]
         (let [side (if (number? position-size)
                      (if (neg? position-size) :short :long)
                      nil)]
           ^{:key (str "funding-" time-ms "-" coin "-" funding-rate "-" payment)}
           [:tr {:class activity-row-class}
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])} (format-time time-ms)]
            [:td {:class (into activity-cell-class ["whitespace-nowrap"])
                  :style (side-coin-cell-style side)}
             [:span {:class [(side-coin-tone-class side)]}
              (or coin "—")]]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-funding-rate funding-rate)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (side-tone-class side)])} (format-size position-size)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class payment)])}
             (format-currency payment)]]))

       :else
       (empty-table-row 5 "No funding history available."))]]])

(defn- order-history-table [rows loading? error sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1040px]" "border-collapse"]}
    (table-header :order-history ["Time" "Coin" "Side" "Type" "Size" "Price" "Status"] sort-state)
    [:tbody
     (cond
       (seq error)
       (error-table-row 7 error)

       loading?
       (empty-table-row 7 "Loading order history...")

       (seq rows)
       (for [{:keys [time-ms coin side type size price status]} rows]
         (let [side* (normalize-side side)]
           ^{:key (str "order-history-" time-ms "-" coin "-" side "-" size)}
           [:tr {:class activity-row-class}
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])} (format-time time-ms)]
            [:td {:class (into activity-cell-class ["whitespace-nowrap"])
                  :style (side-coin-cell-style side*)}
             [:span {:class [(side-coin-tone-class side*)]}
              (or coin "—")]]
            [:td {:class (into activity-cell-class ["whitespace-nowrap" (side-tone-class side*)])} (or side "—")]
            [:td {:class (into activity-cell-class ["whitespace-nowrap" "text-[#f6fefd]"])} (or type "—")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-size size)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-price price)]
            [:td {:class (into activity-cell-class ["whitespace-nowrap" (status-tone-class status)])}
             (or status "—")]]))

       :else
       (empty-table-row 7 "No order history available."))]]])

(defn- ledger-table [rows loading? error sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[880px]" "border-collapse"]}
    (table-header :deposits-withdrawals ["Time" "Type" "Amount" "Tx Hash"] sort-state)
    [:tbody
     (cond
       (seq error)
       (error-table-row 4 error)

       loading?
       (empty-table-row 4 "Loading deposits and withdrawals...")

       (seq rows)
       (for [{:keys [time-ms type-label amount hash]} rows]
         (let [signed-amount (if (= (some-> type-label str/lower-case) "withdraw")
                               (when (number? amount) (- (js/Math.abs amount)))
                               amount)]
           ^{:key (str "ledger-" time-ms "-" hash)}
           [:tr {:class activity-row-class}
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])} (format-time time-ms)]
            [:td {:class (into activity-cell-class ["whitespace-nowrap" (ledger-type-tone-class type-label)])}
             (or type-label "—")]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class signed-amount)])}
             (format-currency amount)]
            [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#97fce4]"])
                  :title hash}
             [:span {:class (interactive-value-class)}
              (short-hash hash)]]]))

       :else
       (empty-table-row 4 "No deposits or withdrawals available."))]]])

(defn- depositors-table [rows sort-state]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[980px]" "border-collapse"]}
    (table-header :depositors ["Depositor" "Vault Amount" "Unrealized PNL" "All-time PNL" "Days Following"] sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [address vault-amount unrealized-pnl all-time-pnl days-following]} rows]
         ^{:key (str "depositor-" address)}
         [:tr {:class activity-row-class}
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (or (wallet/short-addr address) "—")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (format-currency vault-amount)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class unrealized-pnl)])}
           (format-currency unrealized-pnl)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class all-time-pnl)])}
           (format-currency all-time-pnl)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
           (if (number? days-following)
             (str days-following)
             "—")]])
       (empty-table-row 5 "No depositors available."))]]])

(defn- activity-panel [{:keys [selected-activity-tab
                               activity-tabs
                               activity-direction-filter
                               activity-filter-open?
                               activity-filter-options
                               activity-sort-state-by-tab
                               activity-loading
                               activity-errors
                               activity-balances
                               activity-positions
                               activity-open-orders
                               activity-twaps
                               activity-fills
                               activity-funding-history
                               activity-order-history
                               activity-deposits-withdrawals
                               activity-depositors]}]
  (let [filter-enabled? (activity-direction-filter-enabled? selected-activity-tab)
        sort-state-by-tab (or activity-sort-state-by-tab {})
        selected-filter* (or activity-direction-filter :all)]
    [:section {:class ["rounded-2xl"
                       "border"
                       "border-[#1b3237]"
                       "bg-[#071820]"
                       "overflow-hidden"
                       "w-full"]}
     [:div {:class ["flex" "items-center" "justify-between" "border-b" "border-[#1b3237]" "bg-transparent" "gap-2" "pr-3"]}
      [:div {:class ["min-w-0" "overflow-x-auto"]}
       [:div {:class ["flex" "min-w-max" "items-center"]}
        (for [tab activity-tabs]
          ^{:key (str "activity-tab-" (name (:value tab)))}
          (activity-tab-button tab selected-activity-tab))]]
      [:div {:class ["relative" "hidden" "md:flex" "items-center"]}
       [:button {:type "button"
                 :disabled (not filter-enabled?)
                 :class (into ["inline-flex"
                               "items-center"
                               "gap-1"
                               "text-xs"
                               "text-[#949e9c]"
                               "transition-colors"]
                              (if filter-enabled?
                                ["cursor-pointer" "hover:text-[#f6fefd]"]
                                ["cursor-not-allowed" "opacity-50"]))
                 :on {:click [[:actions/toggle-vault-detail-activity-filter-open]]}}
        "Filter"
        [:span "⌄"]]
       (when (and filter-enabled?
                  activity-filter-open?)
         [:div {:class ["absolute"
                        "right-0"
                        "top-full"
                        "z-30"
                        "mt-1.5"
                        "w-32"
                        "overflow-hidden"
                        "rounded-md"
                        "border"
                        "border-[#204046]"
                        "bg-[#081f29]"
                        "shadow-lg"]}
          (for [{:keys [value label]} activity-filter-options]
            ^{:key (str "vault-detail-activity-filter-" (name value))}
            [:button {:type "button"
                      :class (into ["flex"
                                    "w-full"
                                    "items-center"
                                    "justify-between"
                                    "px-3"
                                    "py-2"
                                    "text-left"
                                    "text-sm"
                                    "text-[#c7d5da]"
                                    "transition-colors"
                                    "hover:bg-[#0e2630]"
                                    "hover:text-[#f6fefd]"]
                                   (when (= value selected-filter*)
                                     ["bg-[#0e2630]" "text-[#f6fefd]"]))
                      :on {:click [[:actions/set-vault-detail-activity-direction-filter value]]}}
             [:span label]
             (when (= value selected-filter*)
               [:span {:class ["text-xs" "text-[#66e3c5]"]}
                "●"])])])]]
     (case selected-activity-tab
       :balances (balances-table activity-balances (get sort-state-by-tab :balances))
       :positions (positions-table activity-positions (get sort-state-by-tab :positions))
       :open-orders (open-orders-table activity-open-orders (get sort-state-by-tab :open-orders))
       :twap (twap-table activity-twaps (get sort-state-by-tab :twap))
       :trade-history (fills-table activity-fills
                                   (true? (:trade-history activity-loading))
                                   (:trade-history activity-errors)
                                   (get sort-state-by-tab :trade-history))
       :funding-history (funding-history-table activity-funding-history
                                               (true? (:funding-history activity-loading))
                                               (:funding-history activity-errors)
                                               (get sort-state-by-tab :funding-history))
       :order-history (order-history-table activity-order-history
                                           (true? (:order-history activity-loading))
                                           (:order-history activity-errors)
                                           (get sort-state-by-tab :order-history))
       :deposits-withdrawals (ledger-table activity-deposits-withdrawals
                                           (true? (:deposits-withdrawals activity-loading))
                                           (:deposits-withdrawals activity-errors)
                                           (get sort-state-by-tab :deposits-withdrawals))
       :depositors (depositors-table activity-depositors (get sort-state-by-tab :depositors))
       [:div {:class ["px-4" "py-6" "text-sm" "text-[#8ea2aa]"]}
        "This activity stream is not available yet for vaults."])]))

(defn vault-detail-view
  [state]
  (let [{:keys [kind
                vault-address
                invalid-address?
                loading?
                error
                relationship
                tabs
                selected-tab
                metrics
                chart] :as vm} (detail-vm/vault-detail-vm state)
        vault-name (:name vm)
        month-return (:past-month-return metrics)
        month-return-accent (cond
                              (and (number? month-return) (pos? month-return)) :positive
                              (and (number? month-return) (neg? month-return)) :negative
                              :else nil)]
    [:div
     {:class ["w-full" "app-shell-gutter" "py-4" "space-y-4"]
      :data-parity-id "vault-detail-root"}
     (cond
       invalid-address?
       [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4" "text-sm" "text-trading-text-secondary"]}
        "Invalid vault address."]

       (not= kind :detail)
       [:div {:class ["rounded-xl" "border" "border-base-300" "bg-base-100/95" "p-4" "text-sm" "text-trading-text-secondary"]}
        "Select a vault to view details."]

       :else
       [:div {:class ["space-y-4"]}
        [:section {:class ["rounded-2xl"
                           "border"
                           "border-[#19423e]"
                           "px-4"
                           "py-4"
                           "lg:px-6"
                           "bg-[radial-gradient(circle_at_82%_18%,rgba(41,186,147,0.20),transparent_42%),linear-gradient(180deg,#06382f_0%,#082029_56%,#051721_100%)]"]}
         [:div {:class ["flex" "flex-col" "gap-3" "lg:flex-row" "lg:items-start" "lg:justify-between"]}
          [:div {:class ["min-w-0"]}
           [:div {:class ["mb-2" "flex" "items-center" "gap-2" "text-xs" "text-[#8da5aa]"]}
            [:button {:type "button"
                      :class ["hover:text-trading-text"]
                      :on {:click [[:actions/navigate "/vaults"]]}}
             "Vaults"]
            [:span ">"]
            [:span {:class ["truncate"]} vault-name]]
           [:h1 {:class ["text-[34px]"
                         "leading-[1.02]"
                         "font-semibold"
                         "tracking-tight"
                         "text-trading-text"
                         "sm:text-[44px]"
                         "xl:text-[56px]"
                         "break-words"]}
            vault-name]
           [:div {:class ["mt-1.5" "num" "text-sm" "text-[#89a1a8]"]}
            (or (wallet/short-addr vault-address) vault-address)]
           (relationship-links {:relationship relationship})]
          [:div {:class ["grid" "w-full" "grid-cols-2" "gap-2" "lg:w-auto" "lg:flex"]}
           [:button
            {:type "button"
             :disabled true
             :class ["rounded-lg"
                     "border"
                     "border-[#2a4b4b]"
                     "bg-[#08202a]/55"
                     "px-4"
                     "py-2"
                     "text-sm"
                     "text-[#6c8e93]"
                     "opacity-70"
                     "cursor-not-allowed"]}
            "Withdraw"]
           [:button
            {:type "button"
             :disabled true
             :class ["rounded-lg"
                     "border"
                     "border-[#2a4b4b]"
                     "bg-[#08202a]/55"
                     "px-4"
                     "py-2"
                     "text-sm"
                     "text-[#6c8e93]"
                     "opacity-70"
                     "cursor-not-allowed"]}
            "Deposit"]]]
         [:div {:class ["mt-4" "grid" "grid-cols-2" "gap-2.5" "lg:mt-5" "lg:gap-3" "xl:grid-cols-4"]}
          (metric-card {:label "TVL"
                        :value (format-currency (:tvl metrics) {:missing "$0.00"})})
          (metric-card {:label "Past Month Return"
                        :value (format-percent month-return {:signed? false
                                                            :decimals 0})
                        :accent month-return-accent})
          (metric-card {:label "Your Deposits"
                        :value (format-currency (:your-deposit metrics))})
          (metric-card {:label "All-time Earned"
                        :value (format-currency (:all-time-earned metrics))})]]

        (when loading?
          [:div {:class ["rounded-xl" "border" "border-[#1f3d3d]" "bg-[#081820]" "px-4" "py-2.5" "text-sm" "text-[#8fa6ad]"]}
           "Loading vault details..."])

        (when error
          [:div {:class ["rounded-lg" "border" "border-red-500/40" "bg-red-900/20" "px-3" "py-2" "text-sm" "text-red-200"]}
           error])

        [:div {:class ["grid" "gap-3" "lg:grid-cols-[minmax(280px,1fr)_minmax(0,3fr)]"]}
         [:section {:class ["rounded-2xl"
                            "border"
                            "border-[#1b393a]"
                            "bg-[#071820]"]}
          [:div {:class ["flex" "items-center" "border-b" "border-[#1f3b3c]"]}
           (for [tab tabs]
             ^{:key (str "vault-detail-tab-" (name (:value tab)))}
             (detail-tab-button tab selected-tab))]
          (render-tab-panel vm)]

         (let [axis-kind (:axis-kind chart)
               y-ticks (:y-ticks chart)
               y-axis-width (y-axis-gutter-width axis-kind y-ticks)
               plot-left (+ y-axis-width 10)
               point-count (count (:points chart))
               hovered-point (get-in chart [:hover :point])
               hover-active? (boolean (get-in chart [:hover :active?]))
               hover-line-left-pct (when hover-active?
                                    (* 100 (:x-ratio hovered-point)))
               hover-tooltip-top-pct (when hover-active?
                                      (clamp-number (- (* 100 (:y-ratio hovered-point)) 8)
                                                    8
                                                    92))
               hover-tooltip-right? (when hover-active?
                                      (> hover-line-left-pct 74))
               hover-label (when hover-active?
                             (chart-tooltip-label (:selected-timeframe chart)
                                                  axis-kind
                                                  hovered-point))]
           [:section {:class ["rounded-2xl"
                              "border"
                              "border-[#1b393a]"
                              "bg-[#071820]"
                              "p-3"]}
            [:div {:class ["flex" "items-center" "justify-between" "gap-2" "border-b" "border-[#1f3b3c]" "pb-2"]}
             [:div {:class ["flex" "items-center" "gap-2"]}
              (for [{:keys [value label]} (:series-tabs chart)]
                ^{:key (str "chart-series-" (name value))}
                (chart-series-button {:value value
                                      :label label}
                                     (:selected-series chart)))]
             (chart-timeframe-menu {:timeframe-options (:timeframe-options chart)
                                    :selected-timeframe (:selected-timeframe chart)})]
            [:div {:class ["relative" "mt-3" "h-[260px]"]}
             [:div {:class ["absolute" "left-0" "top-0" "bottom-0"]
                    :style {:width (str y-axis-width "px")}}
              (for [{:keys [value y-ratio]} y-ticks]
                ^{:key (str "vault-chart-tick-" y-ratio "-" value)}
                [:span {:class ["absolute"
                                "right-2"
                                "-translate-y-1/2"
                                "num"
                                "text-right"
                                "text-xs"
                                "text-[#8aa0a7]"]
                        :style {:top (str (* 100 y-ratio) "%")}}
                 (format-chart-axis-value axis-kind value)])
              [:div {:class ["absolute"
                             "right-0"
                             "top-0"
                             "bottom-0"
                             "border-l"
                             "border-[#1f3b3c]"]}]
              (for [{:keys [y-ratio]} y-ticks]
                ^{:key (str "vault-chart-axis-tick-" y-ratio)}
                [:div {:class ["absolute"
                               "right-0"
                               "w-1.5"
                               "border-t"
                               "border-[#1f3b3c]"]
                       :style {:top (str (* 100 y-ratio) "%")}}])]
             [:div {:class ["absolute" "right-2" "top-0" "bottom-0" "cursor-crosshair"]
                    :style {:left (str plot-left "px")}
                    :on {:mousemove [[:actions/set-vault-detail-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                         :mouseenter [[:actions/set-vault-detail-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                         :pointermove [[:actions/set-vault-detail-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                         :pointerenter [[:actions/set-vault-detail-chart-hover [:event/clientX] [:event.currentTarget/bounds] point-count]]
                         :mouseleave [[:actions/clear-vault-detail-chart-hover]]
                         :pointerleave [[:actions/clear-vault-detail-chart-hover]]
                         :mouseout [[:actions/clear-vault-detail-chart-hover]]}}
              [:svg {:viewBox "0 0 100 100"
                     :preserveAspectRatio "none"
                     :class ["h-full" "w-full"]}
               [:line {:x1 0
                       :x2 100
                       :y1 100
                       :y2 100
                       :stroke "rgba(140, 157, 165, 0.30)"
                       :stroke-width 0.8
                       :vector-effect "non-scaling-stroke"}]
               (when (seq (:path chart))
                 [:path {:d (:path chart)
                         :fill "none"
                         :stroke "#e7ecef"
                         :stroke-width 0.85
                         :vector-effect "non-scaling-stroke"}])]
              (when hover-active?
                [:div {:class ["absolute"
                               "top-0"
                               "bottom-0"
                               "w-px"
                               "-translate-x-1/2"
                               "pointer-events-none"
                               "bg-[#9fb3be]"
                               "z-10"]
                       :style {:left (str hover-line-left-pct "%")}}])
              (when hover-active?
                [:div {:class ["absolute"
                               "pointer-events-none"
                               "rounded-sm"
                               "px-1.5"
                               "py-0.5"
                               "text-xs"
                               "font-medium"
                               "text-white"
                               "shadow-sm"
                               "z-20"]
                       :style {:left (str hover-line-left-pct "%")
                               :top (str hover-tooltip-top-pct "%")
                               :transform (if hover-tooltip-right?
                                            "translate(calc(-100% - 8px), -50%)"
                                            "translate(8px, -50%)")
                               :background-color "rgba(0, 0, 0, 0.85)"
                               :white-space "nowrap"}}
                 hover-label])]]])]

        (activity-panel vm)])]))
