(ns hyperopen.views.vaults.detail.activity
  (:require [clojure.string :as str]
            [hyperopen.router :as router]
            [hyperopen.views.ui.performance-metrics-tooltip :as metrics-tooltip]
            [hyperopen.views.vaults.detail.chart-view :as chart]
            [hyperopen.views.vaults.detail.format :as vf]
            [hyperopen.wallet.core :as wallet]))

(defn- format-activity-count [count]
  (cond
    (not (number? count)) nil
    (<= count 0) nil
    (>= count 100) "100+"
    :else (str count)))

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

(defn- format-signed-percent-from-decimal [value]
  (when (vf/finite-number? value)
    (let [pct (* value 100)
          rounded (/ (js/Math.round (* pct 100)) 100)
          rounded* (if (== rounded -0) 0 rounded)
          sign (cond
                 (pos? rounded*) "+"
                 (neg? rounded*) "-"
                 :else "")
          magnitude (.toFixed (js/Math.abs rounded*) 2)]
      (str sign magnitude "%"))))

(defn- format-ratio-value [value]
  (when (vf/finite-number? value)
    (.toFixed value 2)))

(defn- format-integer-value [value]
  (when (vf/finite-number? value)
    (str (js/Math.round value))))

(defn- format-metric-value [kind value]
  (case kind
    :percent (or (format-signed-percent-from-decimal value) "--")
    :ratio (or (format-ratio-value value) "--")
    :integer (or (format-integer-value value) "--")
    :date (if (and (string? value)
                   (seq (str/trim value)))
            value
            "--")
    "--"))

(defn- low-confidence-metric-title
  [reason]
  (case reason
    :daily-coverage-gate-failed "Estimated from incomplete daily coverage."
    :psr-gate-failed "Estimated from limited daily history."
    :drawdown-reliability-gate-failed "Estimated from sparse drawdown observations."
    :drawdown-unavailable "Estimated from sparse drawdown observations."
    :rolling-window-span-insufficient "Estimated from limited history in this window."
    :benchmark-coverage-gate-failed "Estimated from limited benchmark overlap."
    "Low-confidence estimate."))

(def ^:private low-confidence-reason-order
  [:daily-coverage-gate-failed
   :psr-gate-failed
   :drawdown-reliability-gate-failed
   :drawdown-unavailable
   :rolling-window-span-insufficient
   :benchmark-coverage-gate-failed])

(defn- ordered-low-confidence-reasons
  [reasons]
  (let [reason-set (disj (set reasons) nil)
        known-reasons (filter reason-set low-confidence-reason-order)
        unknown-reasons (sort (remove (set low-confidence-reason-order) reason-set))]
    (vec (concat known-reasons unknown-reasons))))

(defn- low-confidence-banner-summary
  [reasons]
  (let [reason-set (set reasons)]
    (cond
      (empty? reason-set)
      nil

      (every? #{:daily-coverage-gate-failed :psr-gate-failed} reason-set)
      "Some metrics are estimated from incomplete daily data."

      (every? #{:drawdown-reliability-gate-failed :drawdown-unavailable} reason-set)
      "Some metrics are estimated from sparse drawdown data."

      (= reason-set #{:benchmark-coverage-gate-failed})
      "Some metrics are estimated from limited benchmark overlap."

      :else
      "Some metrics are estimated from incomplete or limited data.")))

(defn- low-confidence-info-icon
  [classes]
  [:svg {:viewBox "0 0 20 20"
         :fill "none"
         :stroke "currentColor"
         :class classes
         :aria-hidden true}
   [:circle {:cx "10"
             :cy "10"
             :r "7.25"
             :stroke-width "1.6"}]
   [:path {:d "M10 8.2v4.1"
           :stroke-linecap "round"
           :stroke-width "1.6"}]
   [:circle {:cx "10"
             :cy "5.8"
             :r "0.95"
             :fill "currentColor"
             :stroke "none"}]])

(defn- estimated-metrics-banner
  [reasons]
  (when-let [summary (low-confidence-banner-summary reasons)]
    [:div {:class ["group"
                   "relative"
                   "rounded-lg"
                   "border"
                   "px-3"
                   "py-2.5"]
           :style {:border-color "rgba(66, 102, 128, 0.52)"
                   :background "linear-gradient(135deg, rgba(18, 53, 79, 0.58) 0%, rgba(14, 40, 61, 0.52) 100%)"}
           :data-role "vault-detail-performance-metrics-estimated-banner"
           :tab-index 0}
     [:div {:class ["flex" "min-w-0" "items-start" "gap-2.5"]}
      (low-confidence-info-icon ["mt-0.5" "h-4" "w-4" "shrink-0" "text-[#8fc7ff]"])
      [:div {:class ["min-w-0" "text-sm" "leading-5" "text-[#d5e8ff]"]}
       summary]]
     (metrics-tooltip/estimated-banner-tooltip reasons
                                               "vault-detail-performance-metrics-estimated-banner"
                                               low-confidence-metric-title)]))

(defn- performance-metric-value-cell
  ([kind value]
   (performance-metric-value-cell kind value nil))
  ([kind value {:keys [status] :as attrs}]
   (let [attrs* (dissoc attrs :status :reason :metric-label :metric-description)
         formatted-value (format-metric-value kind value)
         tone-class (if (= status :low-confidence)
                      "text-[#9fb4bb]"
                      "text-trading-text")]
     [:span (merge {:class ["justify-self-start"
                            "text-left"
                            "text-sm"
                            tone-class]}
                   attrs*)
      [:span {:class (into []
                           (when (not= kind :date)
                             ["num"]))}
       formatted-value]])))

(defn- resolved-benchmark-metric-columns
  [{:keys [benchmark-columns benchmark-selected? benchmark-label benchmark-coin]}]
  (let [columns (->> (or benchmark-columns [])
                     (keep (fn [{:keys [coin label]}]
                             (let [coin* (some-> coin str str/trim)
                                   label* (some-> label str str/trim)]
                               (when (seq coin*)
                                 {:coin coin*
                                  :label (or label* coin*)}))))
                     vec)]
    (if (seq columns)
      columns
      [{:coin (or (some-> benchmark-coin str str/trim)
                  "__benchmark__")
        :label (if benchmark-selected?
                 (or benchmark-label "Benchmark")
                 "Benchmark")}])))

(defn- benchmark-row-value
  [row coin]
  (let [values (:benchmark-values row)]
    (if (and (map? values)
             (contains? values coin))
      (get values coin)
      (:benchmark-value row))))

(defn- benchmark-row-status
  [row coin]
  (let [statuses (:benchmark-statuses row)]
    (if (and (map? statuses)
             (contains? statuses coin))
      (get statuses coin)
      (:benchmark-status row))))

(defn- benchmark-row-reason
  [row coin]
  (let [reasons (:benchmark-reasons row)]
    (if (and (map? reasons)
             (contains? reasons coin))
      (get reasons coin)
      (:benchmark-reason row))))

(defn- performance-metric-row-reasons
  [row benchmark-columns]
  (ordered-low-confidence-reasons
   (concat [(when (= :low-confidence (:portfolio-status row))
              (:portfolio-reason row))]
           (keep (fn [{:keys [coin]}]
                   (when (= :low-confidence (benchmark-row-status row coin))
                     (benchmark-row-reason row coin)))
                 benchmark-columns))))

(defn- performance-metric-row-estimated?
  [row benchmark-columns]
  (boolean (seq (performance-metric-row-reasons row benchmark-columns))))

(defn- visible-low-confidence-reasons
  [groups benchmark-columns]
  (ordered-low-confidence-reasons
   (mapcat #(performance-metric-row-reasons % benchmark-columns)
           (mapcat :rows groups))))

(defn- metric-value-present?
  [kind value]
  (not= "--" (format-metric-value kind value)))

(defn- performance-metric-row-visible?
  [{:keys [kind value] :as row} benchmark-columns]
  (let [portfolio-value (if (contains? row :portfolio-value)
                          (:portfolio-value row)
                          value)]
    (or (metric-value-present? kind portfolio-value)
        (some (fn [{:keys [coin]}]
                (metric-value-present? kind (benchmark-row-value row coin)))
              benchmark-columns))))

(defn- performance-metrics-grid-style
  [benchmark-column-count]
  {:grid-template-columns (str/join " "
                                    (concat ["220px"]
                                            (repeat benchmark-column-count "132px")
                                            ["132px"]))})

(defn- performance-metric-row [{:keys [key label description kind value] :as row} benchmark-columns grid-style]
  (let [portfolio-value (if (contains? row :portfolio-value)
                          (:portfolio-value row)
                          value)
        estimated-row? (performance-metric-row-estimated? row benchmark-columns)]
    [:div {:class ["grid"
                   "items-center"
                   "justify-items-start"
                   "gap-3"
                   "hover:bg-[#0e2630]"]
           :style grid-style
           :data-role (str "vault-detail-performance-metric-" (name key))}
     [:span {:class ["group"
                     "relative"
                     "inline-flex"
                     "items-center"
                     "gap-1"
                     "text-sm"]
             :style {:color (if estimated-row?
                              "#95A8B0"
                              "#F6FEFD")}
             :data-role (str "vault-detail-performance-metric-" (name key) "-label")}
      label
      (when estimated-row?
        [:span {:class ["text-xs" "font-semibold" "leading-none" "text-[#8fc7ff]"]
                :data-role (str "vault-detail-performance-metric-" (name key) "-estimated-mark")}
         "~"])
      (metrics-tooltip/metric-label-tooltip label
                                            description
                                            (str "vault-detail-performance-metric-" (name key) "-label"))]
     (for [{:keys [coin]} benchmark-columns]
       (let [cell-data-role (str "vault-detail-performance-metric-" (name key) "-benchmark-value-" coin)]
         ^{:key (str "vault-detail-performance-metric-" (name key) "-benchmark-" coin)}
         (performance-metric-value-cell kind
                                        (benchmark-row-value row coin)
                                        {:status (benchmark-row-status row coin)
                                         :data-role cell-data-role})))
     (performance-metric-value-cell kind
                                    portfolio-value
                                    {:status (:portfolio-status row)
                                     :data-role (str "vault-detail-performance-metric-" (name key) "-vault-value")})]))

(defn- performance-metrics-card [{:keys [benchmark-selected?
                                         benchmark-label
                                         benchmark-columns
                                         benchmark-coin
                                         loading?
                                         groups
                                         timeframe-options
                                         timeframe-menu-open?
                                         selected-timeframe]}]
  (let [benchmark-columns* (resolved-benchmark-metric-columns {:benchmark-columns benchmark-columns
                                                               :benchmark-selected? benchmark-selected?
                                                               :benchmark-label benchmark-label
                                                               :benchmark-coin benchmark-coin})
        grid-style (performance-metrics-grid-style (count benchmark-columns*))
        visible-groups (->> (or groups [])
                            (keep (fn [{:keys [rows] :as group}]
                                    (let [rows* (->> (or rows [])
                                                     (filter #(performance-metric-row-visible? % benchmark-columns*))
                                                     vec)]
                                     (when (seq rows*)
                                        (assoc group :rows rows*)))))
                            vec)
        visible-reasons (visible-low-confidence-reasons visible-groups benchmark-columns*)]
    [:div {:class ["relative" "flex" "flex-1" "min-h-0" "flex-col"]
           :data-role "vault-detail-performance-metrics-card"}
     (when loading?
       [:div {:class ["absolute" "inset-0" "z-10" "flex" "items-center" "justify-center" "bg-[#071820]/70" "backdrop-blur-sm"]
              :data-role "vault-detail-performance-metrics-loading-overlay"
              :role "status"
              :aria-live "polite"}
        [:div {:class ["flex" "max-w-[250px]" "flex-col" "items-center" "gap-2.5" "px-4" "text-center"]}
         [:span {:class ["loading" "loading-spinner" "loading-lg" "text-[#66e3c5]"]
                 :aria-hidden true}]
         [:span {:class ["text-sm" "font-medium" "text-trading-text"]}
          "Loading benchmark history"]
         [:span {:class ["text-xs" "leading-5" "text-[#9fb4bb]"]}
          "Vault metrics stay visible while benchmark comparisons finish in the background."]]])
     [:div {:class ["grid"
                    "items-center"
                    "justify-items-start"
                    "gap-3"
                    "border-b"
                    "border-[#1f3b3c]"
                    "bg-[#0a232d]"
                    "px-4"
                    "py-2.5"]
            :style grid-style}
      [:div {:class ["flex" "min-w-0" "items-center" "justify-between" "gap-2"]}
       [:span {:class ["text-xs" "font-medium" "uppercase" "tracking-wide" "text-[#8aa0a7]"]}
        "Metric"]
       (when (and (seq timeframe-options)
                  (keyword? selected-timeframe))
         (chart/chart-timeframe-menu {:timeframe-options timeframe-options
                                      :open? timeframe-menu-open?
                                      :toggle-action :actions/toggle-vault-detail-performance-metrics-timeframe-dropdown
                                      :close-action :actions/close-vault-detail-performance-metrics-timeframe-dropdown
                                      :selected-timeframe selected-timeframe
                                      :data-role-prefix "vault-detail-performance-metrics-timeframe"}))]
      (for [[idx {:keys [coin label]}] (map-indexed vector benchmark-columns*)]
        ^{:key (str "vault-detail-performance-metrics-benchmark-label-" coin)}
        [:span {:class ["justify-self-start" "text-xs" "font-medium" "uppercase" "tracking-wide" "text-left" "text-[#8aa0a7]"]
                :data-role (if (zero? idx)
                             "vault-detail-performance-metrics-benchmark-label"
                             (str "vault-detail-performance-metrics-benchmark-label-" coin))}
         label])
      [:span {:class ["justify-self-start" "text-xs" "font-medium" "uppercase" "tracking-wide" "text-left" "text-[#8aa0a7]"]}
       "Vault"]]
     [:div {:class ["flex-1"
                    "min-h-0"
                    "space-y-2.5"
                    "overflow-y-auto"
                    "scrollbar-hide"
                    "px-4"
                    "py-3"
                    "focus:outline-none"
                    "focus:ring-1"
                    "focus:ring-inset"
                    "focus:ring-[#2c6666]"]
            :data-role "vault-detail-performance-metrics-scroll-region"
            :role "region"
            :aria-label "Vault performance metrics"
            :tab-index 0}
      (estimated-metrics-banner visible-reasons)
      (for [[idx {:keys [id rows]}] (map-indexed vector visible-groups)]
        ^{:key (str "vault-detail-performance-metrics-group-" (name id))}
        [:div {:class (into ["space-y-1.5"]
                            (when (pos? idx)
                              ["border-t" "border-[#1f3b3c]" "pt-2.5"]))}
         (for [{:keys [key] :as row} rows]
           ^{:key (str "vault-detail-performance-metric-row-" (name key))}
           (performance-metric-row row benchmark-columns* grid-style))])]]))

(defn- sort-header-button
  [tab {:keys [id label]} sort-state]
  (let [active? (= id (:column sort-state))
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
              :on {:click [[:actions/sort-vault-detail-activity tab id]]}}
     [:span label]
     (when icon
       [:span {:class ["text-xs" "opacity-70"]}
        icon])]))

(defn- table-header [tab columns sort-state]
  [:thead
   [:tr {:class ["border-b" "border-[#1b3237]" "bg-transparent" "text-xs" "font-medium" "text-[#949e9c]"]}
    (for [{:keys [id] :as column} columns]
      ^{:key (str "activity-header-" (name id))}
      [:th {:class ["px-4" "py-2" "text-left" "whitespace-nowrap" "font-medium"]}
       (sort-header-button tab column sort-state)])]])

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

(defn- side-tone-class
  [side-key]
  (case side-key
    :long "text-[#1fa67d]"
    :short "text-[#ed7088]"
    "text-trading-text"))

(defn- side-coin-tone-class
  [side-key]
  (case side-key
    :long "text-[#97fce4]"
    :short "text-[#eaafb8]"
    "text-trading-text"))

(defn- side-coin-cell-style
  [side-key]
  (case side-key
    :long {:background "linear-gradient(90deg,rgb(31,166,125) 0px,rgb(31,166,125) 4px,rgba(11,50,38,0.92) 4px,transparent 100%)"
           :padding-left "12px"}
    :short {:background "linear-gradient(90deg,rgb(237,112,136) 0px,rgb(237,112,136) 4px,rgba(52,36,46,0.92) 4px,transparent 100%)"
            :padding-left "12px"}
    nil))

(defn- interactive-value-class
  []
  ["underline" "decoration-dotted" "underline-offset-2"])

(defn- status-tone-class
  [status-key]
  (case status-key
    :positive "text-[#1fa67d]"
    :negative "text-[#ed7088]"
    :neutral "text-[#9aa7ad]"
    "text-trading-text"))

(defn- ledger-type-tone-class
  [type-key]
  (case type-key
    :deposit "text-[#1fa67d]"
    :withdraw "text-[#ed7088]"
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

(defn- position-row-key
  [{:keys [coin size entry-price]}]
  (str "position-" coin "-" size "-" entry-price))

(defn- position-coin-click-actions
  [coin]
  (when-let [coin* (some-> coin str str/trim not-empty)]
    [[:actions/select-asset coin*]
     [:actions/navigate (router/trade-route-path coin*)]]))

(defn- position-coin-cell
  [{:keys [coin leverage side-key]}]
  [:td {:class (into activity-cell-class ["whitespace-nowrap"])
        :style (side-coin-cell-style side-key)}
   (if-let [click-actions (position-coin-click-actions coin)]
     [:button {:type "button"
               :class ["inline-flex"
                       "items-center"
                       "gap-1"
                       "bg-transparent"
                       "p-0"
                       "text-left"
                       "focus:outline-none"
                       "focus:ring-0"
                       "focus:ring-offset-0"]
               :data-role "vault-detail-position-coin-select"
               :on {:click click-actions}}
      [:span {:class [(side-coin-tone-class side-key)]}
       coin]
      (when (number? leverage)
        [:span {:class [(side-tone-class side-key)]}
         (str leverage "x")])]
     [:span {:class [(side-coin-tone-class side-key)]}
      (or coin "—")])])

(defn- position-value-text
  [position-value]
  (if (number? position-value)
    (str (vf/format-currency position-value) " USDC")
    "—"))

(defn- position-pnl-text
  [pnl roe]
  (if (number? pnl)
    (str (vf/format-currency pnl {:missing "—"}) " (" (vf/format-percent roe) ")")
    "—"))

(defn- position-liq-price-text
  [liq-price]
  (if (number? liq-price)
    (vf/format-price liq-price)
    "N/A"))

(defn- position-margin-text
  [margin]
  (if (number? margin)
    (str (vf/format-currency margin) " (Cross)")
    "—"))

(defn- position-row
  [{:keys [size side-key position-value entry-price mark-price pnl roe liq-price margin funding] :as row}]
  [:tr {:class activity-row-class}
   (position-coin-cell row)
   [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (side-tone-class side-key)])}
    (vf/format-size size)]
   [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
    (position-value-text position-value)]
   [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
    (vf/format-price entry-price)]
   [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
    (vf/format-price mark-price)]
   [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class pnl)])}
    (position-pnl-text pnl roe)]
   [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
    (position-liq-price-text liq-price)]
   [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
    (position-margin-text margin)]
   [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class funding)])}
    (vf/format-currency funding)]])

(defn- balances-table [rows sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[760px]" "border-collapse"]}
    (table-header :balances columns sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [coin total available usdc-value]} rows]
         ^{:key (str "balance-" coin "-" total)}
         [:tr {:class activity-row-class}
          [:td {:class (into activity-cell-class ["whitespace-nowrap" "text-[#f6fefd]"])}
           (or coin "—")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
           (vf/format-balance-quantity total)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])}
           [:span {:class (into ["text-[#f6fefd]"] (interactive-value-class))}
            (vf/format-balance-quantity available)]]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
           (vf/format-currency usdc-value)]])
       (empty-table-row (count columns) "No balances available."))]]])

(defn- positions-table [rows sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1285px]" "border-collapse"]}
    (table-header :positions columns sort-state)
    [:tbody
     (if (seq rows)
       (for [row rows]
         ^{:key (position-row-key row)}
         (position-row row))
       (empty-table-row (count columns) "No active positions."))]]])

(defn- open-orders-table [rows sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[960px]" "border-collapse"]}
    (table-header :open-orders columns sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [time-ms coin side side-key size price trigger-price]} rows]
         ^{:key (str "open-order-" time-ms "-" coin "-" size "-" price)}
         [:tr {:class activity-row-class}
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])} (vf/format-time time-ms)]
          [:td {:class (into activity-cell-class ["whitespace-nowrap"])
                :style (side-coin-cell-style side-key)}
           [:span {:class [(side-coin-tone-class side-key)]}
            (or coin "—")]]
          [:td {:class (into activity-cell-class ["whitespace-nowrap" (side-tone-class side-key)])} (or side "—")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (side-tone-class side-key)])} (vf/format-size size)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (vf/format-price price)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (vf/format-price trigger-price)]])
       (empty-table-row (count columns) "No open orders."))]]])

(defn- twap-table [rows sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1260px]" "border-collapse"]}
    (table-header :twap columns sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [coin size executed-size average-price running-label reduce-only? creation-time-ms]} rows]
         ^{:key (str "twap-" coin "-" creation-time-ms "-" size)}
         [:tr {:class activity-row-class}
          [:td {:class (into activity-cell-class ["whitespace-nowrap" "text-[#f6fefd]"])} (or coin "—")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (vf/format-size size)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (vf/format-size executed-size)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (vf/format-price average-price)]
          [:td {:class (into activity-cell-class ["whitespace-nowrap" "text-[#f6fefd]"])} (or running-label "—")]
          [:td {:class (into activity-cell-class ["whitespace-nowrap" (if (true? reduce-only?) "text-[#ed7088]" "text-[#1fa67d]")])}
           (if (true? reduce-only?) "Yes" "No")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (vf/format-time creation-time-ms)]
          [:td {:class (into activity-cell-class ["whitespace-nowrap" "text-[#8f9ea5]"])} "—"]])
       (empty-table-row (count columns) "No TWAPs yet."))]]])

(defn- fills-table [rows loading? error sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1180px]" "border-collapse"]}
    (table-header :trade-history columns sort-state)
    [:tbody
     (cond
       (seq error)
       (error-table-row (count columns) error)

       loading?
       (empty-table-row (count columns) "Loading trade history...")

       (seq rows)
       (for [{:keys [time-ms coin side side-key size price trade-value fee closed-pnl]} rows]
         ^{:key (str "fill-" time-ms "-" coin "-" size "-" price)}
         [:tr {:class activity-row-class}
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])} (vf/format-time time-ms)]
          [:td {:class (into activity-cell-class ["whitespace-nowrap"])
                :style (side-coin-cell-style side-key)}
           [:span {:class [(side-coin-tone-class side-key)]}
            (or coin "—")]]
          [:td {:class (into activity-cell-class ["whitespace-nowrap" (side-tone-class side-key)])}
           (or side "—")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (vf/format-price price)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (vf/format-size size)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (vf/format-currency trade-value)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (vf/format-currency fee)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class closed-pnl)])}
           (vf/format-currency closed-pnl)]])

       :else
       (empty-table-row (count columns) "No recent fills."))]]])

(defn- funding-history-table [rows loading? error sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[920px]" "border-collapse"]}
    (table-header :funding-history columns sort-state)
    [:tbody
     (cond
       (seq error)
       (error-table-row (count columns) error)

       loading?
       (empty-table-row (count columns) "Loading funding history...")

       (seq rows)
       (for [{:keys [time-ms coin funding-rate position-size side-key payment]} rows]
         ^{:key (str "funding-" time-ms "-" coin "-" funding-rate "-" payment)}
         [:tr {:class activity-row-class}
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])} (vf/format-time time-ms)]
          [:td {:class (into activity-cell-class ["whitespace-nowrap"])
                :style (side-coin-cell-style side-key)}
           [:span {:class [(side-coin-tone-class side-key)]}
            (or coin "—")]]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (vf/format-funding-rate funding-rate)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (side-tone-class side-key)])} (vf/format-size position-size)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class payment)])}
           (vf/format-currency payment)]])

       :else
       (empty-table-row (count columns) "No funding history available."))]]])

(defn- order-history-table [rows loading? error sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1040px]" "border-collapse"]}
    (table-header :order-history columns sort-state)
    [:tbody
     (cond
       (seq error)
       (error-table-row (count columns) error)

       loading?
       (empty-table-row (count columns) "Loading order history...")

       (seq rows)
       (for [{:keys [time-ms coin side side-key type size price status status-key]} rows]
         ^{:key (str "order-history-" time-ms "-" coin "-" side "-" size)}
         [:tr {:class activity-row-class}
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])} (vf/format-time time-ms)]
          [:td {:class (into activity-cell-class ["whitespace-nowrap"])
                :style (side-coin-cell-style side-key)}
           [:span {:class [(side-coin-tone-class side-key)]}
            (or coin "—")]]
          [:td {:class (into activity-cell-class ["whitespace-nowrap" (side-tone-class side-key)])} (or side "—")]
          [:td {:class (into activity-cell-class ["whitespace-nowrap" "text-[#f6fefd]"])} (or type "—")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (vf/format-size size)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (vf/format-price price)]
          [:td {:class (into activity-cell-class ["whitespace-nowrap" (status-tone-class status-key)])}
           (or status "—")]])

       :else
       (empty-table-row (count columns) "No order history available."))]]])

(defn- ledger-table [rows loading? error sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[880px]" "border-collapse"]}
    (table-header :deposits-withdrawals columns sort-state)
    [:tbody
     (cond
       (seq error)
       (error-table-row (count columns) error)

       loading?
       (empty-table-row (count columns) "Loading deposits and withdrawals...")

       (seq rows)
       (for [{:keys [time-ms type-key type-label amount signed-amount hash]} rows]
         ^{:key (str "ledger-" time-ms "-" hash)}
         [:tr {:class activity-row-class}
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap"])} (vf/format-time time-ms)]
          [:td {:class (into activity-cell-class ["whitespace-nowrap" (ledger-type-tone-class type-key)])}
           (or type-label "—")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class signed-amount)])}
           (vf/format-currency (or signed-amount amount))]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#97fce4]"])
                :title hash}
           [:span {:class (interactive-value-class)}
            (vf/short-hash hash)]]])

       :else
       (empty-table-row (count columns) "No deposits or withdrawals available."))]]])

(defn- depositors-table [rows sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[980px]" "border-collapse"]}
    (table-header :depositors columns sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [address vault-amount unrealized-pnl all-time-pnl days-following]} rows]
         ^{:key (str "depositor-" address)}
         [:tr {:class activity-row-class}
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (or (wallet/short-addr address) "—")]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])} (vf/format-currency vault-amount)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class unrealized-pnl)])}
           (vf/format-currency unrealized-pnl)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" (position-pnl-class all-time-pnl)])}
           (vf/format-currency all-time-pnl)]
          [:td {:class (into activity-cell-num-class ["whitespace-nowrap" "text-[#f6fefd]"])}
           (if (number? days-following)
             (str days-following)
             "—")]])
       (empty-table-row (count columns) "No depositors available."))]]])

(defn activity-panel [{:keys [selected-activity-tab
                               activity-tabs
                               activity-table-config
                               performance-metrics
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
  (let [sort-state-by-tab (or activity-sort-state-by-tab {})
        selected-filter* (or activity-direction-filter :all)
        table-config-by-tab (or activity-table-config {})
        table-config (get table-config-by-tab selected-activity-tab)
        filter-enabled? (true? (:supports-direction-filter? table-config))
        table-columns (fn [tab]
                        (vec (or (get-in table-config-by-tab [tab :columns]) [])))]
    [:section {:class (into ["rounded-2xl"
                             "border"
                             "border-[#1b3237]"
                             "bg-[#071820]"
                             "overflow-hidden"
                             "w-full"
                             "flex"
                             "min-h-0"
                             "flex-col"]
                            (when (= selected-activity-tab :performance-metrics)
                              ["max-h-[75vh]" "xl:max-h-[46rem]"]))
               :data-role "vault-detail-activity-panel"}
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
                        "spectate-lg"]}
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
       :performance-metrics (performance-metrics-card performance-metrics)
       :balances (balances-table activity-balances
                                 (get sort-state-by-tab :balances)
                                 (table-columns :balances))
       :positions (positions-table activity-positions
                                   (get sort-state-by-tab :positions)
                                   (table-columns :positions))
       :open-orders (open-orders-table activity-open-orders
                                       (get sort-state-by-tab :open-orders)
                                       (table-columns :open-orders))
       :twap (twap-table activity-twaps
                         (get sort-state-by-tab :twap)
                         (table-columns :twap))
       :trade-history (fills-table activity-fills
                                   (true? (:trade-history activity-loading))
                                   (:trade-history activity-errors)
                                   (get sort-state-by-tab :trade-history)
                                   (table-columns :trade-history))
       :funding-history (funding-history-table activity-funding-history
                                               (true? (:funding-history activity-loading))
                                               (:funding-history activity-errors)
                                               (get sort-state-by-tab :funding-history)
                                               (table-columns :funding-history))
       :order-history (order-history-table activity-order-history
                                           (true? (:order-history activity-loading))
                                           (:order-history activity-errors)
                                           (get sort-state-by-tab :order-history)
                                           (table-columns :order-history))
       :deposits-withdrawals (ledger-table activity-deposits-withdrawals
                                           (true? (:deposits-withdrawals activity-loading))
                                           (:deposits-withdrawals activity-errors)
                                           (get sort-state-by-tab :deposits-withdrawals)
                                           (table-columns :deposits-withdrawals))
       :depositors (depositors-table activity-depositors
                                     (get sort-state-by-tab :depositors)
                                     (table-columns :depositors))
       [:div {:class ["px-4" "py-6" "text-sm" "text-[#8ea2aa]"]}
        "This activity stream is not available yet for vaults."])]))
