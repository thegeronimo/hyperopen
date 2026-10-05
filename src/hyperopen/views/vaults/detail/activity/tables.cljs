(ns hyperopen.views.vaults.detail.activity.tables
  (:require [clojure.string :as str]
            [hyperopen.router :as router]
            [hyperopen.views.vaults.detail.activity.table-chrome :as chrome]
            [hyperopen.views.vaults.detail.format :as vf]
            [hyperopen.wallet.core :as wallet]))

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
  [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap"])
        :style (chrome/side-coin-cell-style side-key)}
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
      [:span {:class [(chrome/side-coin-tone-class side-key)]}
       coin]
      (when (number? leverage)
        [:span {:class [(chrome/side-tone-class side-key)]}
         (str leverage "x")])]
     [:span {:class [(chrome/side-coin-tone-class side-key)]}
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
  [:tr {:class chrome/activity-row-class}
   (position-coin-cell row)
   [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" (chrome/side-tone-class side-key)])}
    (vf/format-size size)]
   [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])}
    (position-value-text position-value)]
   [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])}
    (vf/format-price entry-price)]
   [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])}
    (vf/format-price mark-price)]
   [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" (chrome/position-pnl-class pnl)])}
    (position-pnl-text pnl roe)]
   [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])}
    (position-liq-price-text liq-price)]
   [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])}
    (position-margin-text margin)]
   [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" (chrome/position-pnl-class funding)])}
    (vf/format-currency funding)]])

(defn balances-table [rows sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[760px]" "border-collapse"]}
    (chrome/table-header :balances columns sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [coin total available usdc-value]} rows]
         ^{:key (str "balance-" coin "-" total)}
         [:tr {:class chrome/activity-row-class}
          [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap" "text-ho-text"])}
           (or coin "—")]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])}
           (vf/format-balance-quantity total)]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap"])}
           [:span {:class (into ["text-ho-text"] (chrome/interactive-value-class))}
            (vf/format-balance-quantity available)]]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])}
           (vf/format-currency usdc-value)]])
       (chrome/empty-table-row (count columns) "No balances available."))]]])

(defn positions-table [rows sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1285px]" "border-collapse"]}
    (chrome/table-header :positions columns sort-state)
    [:tbody
     (if (seq rows)
       (for [row rows]
         ^{:key (position-row-key row)}
         (position-row row))
       (chrome/empty-table-row (count columns) "No active positions."))]]])

(defn open-orders-table [rows sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[960px]" "border-collapse"]}
    (chrome/table-header :open-orders columns sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [time-ms coin side side-key size price trigger-price]} rows]
         ^{:key (str "open-order-" time-ms "-" coin "-" size "-" price)}
         [:tr {:class chrome/activity-row-class}
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap"])} (vf/format-time time-ms)]
          [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap"])
                :style (chrome/side-coin-cell-style side-key)}
           [:span {:class [(chrome/side-coin-tone-class side-key)]}
            (or coin "—")]]
          [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap" (chrome/side-tone-class side-key)])} (or side "—")]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" (chrome/side-tone-class side-key)])} (vf/format-size size)]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])} (vf/format-price price)]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])} (vf/format-price trigger-price)]])
       (chrome/empty-table-row (count columns) "No open orders."))]]])

(defn twap-table [rows sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1260px]" "border-collapse"]}
    (chrome/table-header :twap columns sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [coin size executed-size average-price running-label reduce-only? creation-time-ms]} rows]
         ^{:key (str "twap-" coin "-" creation-time-ms "-" size)}
         [:tr {:class chrome/activity-row-class}
          [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap" "text-ho-text"])} (or coin "—")]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])} (vf/format-size size)]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])} (vf/format-size executed-size)]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])} (vf/format-price average-price)]
          [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap" "text-ho-text"])} (or running-label "—")]
          [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap" (if (true? reduce-only?) "text-ho-sell-hi" "text-[#1fa67d]")])}
           (if (true? reduce-only?) "Yes" "No")]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])} (vf/format-time creation-time-ms)]
          [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap" "text-[#8f9ea5]"])} "—"]])
       (chrome/empty-table-row (count columns) "No TWAPs yet."))]]])

(defn fills-table [rows loading? error sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1180px]" "border-collapse"]}
    (chrome/table-header :trade-history columns sort-state)
    [:tbody
     (cond
       (seq error)
       (chrome/error-table-row (count columns) error)

       loading?
       (chrome/empty-table-row (count columns) "Loading trade history...")

       (seq rows)
       (for [{:keys [time-ms coin side side-key size price trade-value fee closed-pnl]} rows]
         ^{:key (str "fill-" time-ms "-" coin "-" size "-" price)}
         [:tr {:class chrome/activity-row-class}
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap"])} (vf/format-time time-ms)]
          [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap"])
                :style (chrome/side-coin-cell-style side-key)}
           [:span {:class [(chrome/side-coin-tone-class side-key)]}
            (or coin "—")]]
          [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap" (chrome/side-tone-class side-key)])}
           (or side "—")]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])} (vf/format-price price)]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])} (vf/format-size size)]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])} (vf/format-currency trade-value)]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])} (vf/format-currency fee)]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" (chrome/position-pnl-class closed-pnl)])}
           (vf/format-currency closed-pnl)]])

       :else
       (chrome/empty-table-row (count columns) "No recent fills."))]]])

(defn funding-history-table [rows loading? error sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[920px]" "border-collapse"]}
    (chrome/table-header :funding-history columns sort-state)
    [:tbody
     (cond
       (seq error)
       (chrome/error-table-row (count columns) error)

       loading?
       (chrome/empty-table-row (count columns) "Loading funding history...")

       (seq rows)
       (for [{:keys [time-ms coin funding-rate position-size side-key payment]} rows]
         ^{:key (str "funding-" time-ms "-" coin "-" funding-rate "-" payment)}
         [:tr {:class chrome/activity-row-class}
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap"])} (vf/format-time time-ms)]
          [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap"])
                :style (chrome/side-coin-cell-style side-key)}
           [:span {:class [(chrome/side-coin-tone-class side-key)]}
            (or coin "—")]]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])} (vf/format-funding-rate funding-rate)]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" (chrome/side-tone-class side-key)])} (vf/format-size position-size)]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" (chrome/position-pnl-class payment)])}
           (vf/format-currency payment)]])

       :else
       (chrome/empty-table-row (count columns) "No funding history available."))]]])

(defn order-history-table [rows loading? error sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[1040px]" "border-collapse"]}
    (chrome/table-header :order-history columns sort-state)
    [:tbody
     (cond
       (seq error)
       (chrome/error-table-row (count columns) error)

       loading?
       (chrome/empty-table-row (count columns) "Loading order history...")

       (seq rows)
       (for [{:keys [time-ms coin side side-key type size price status status-key]} rows]
         ^{:key (str "order-history-" time-ms "-" coin "-" side "-" size)}
         [:tr {:class chrome/activity-row-class}
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap"])} (vf/format-time time-ms)]
          [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap"])
                :style (chrome/side-coin-cell-style side-key)}
           [:span {:class [(chrome/side-coin-tone-class side-key)]}
            (or coin "—")]]
          [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap" (chrome/side-tone-class side-key)])} (or side "—")]
          [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap" "text-ho-text"])} (or type "—")]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])} (vf/format-size size)]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])} (vf/format-price price)]
          [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap" (chrome/status-tone-class status-key)])}
           (or status "—")]])

       :else
       (chrome/empty-table-row (count columns) "No order history available."))]]])

(defn ledger-table [rows loading? error sort-state columns]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   [:table {:class ["w-full" "min-w-[880px]" "border-collapse"]}
    (chrome/table-header :deposits-withdrawals columns sort-state)
    [:tbody
     (cond
       (seq error)
       (chrome/error-table-row (count columns) error)

       loading?
       (chrome/empty-table-row (count columns) "Loading deposits and withdrawals...")

       (seq rows)
       (for [{:keys [time-ms type-key type-label amount signed-amount hash]} rows]
         ^{:key (str "ledger-" time-ms "-" hash)}
         [:tr {:class chrome/activity-row-class}
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap"])} (vf/format-time time-ms)]
          [:td {:class (into chrome/activity-cell-class ["whitespace-nowrap" (chrome/ledger-type-tone-class type-key)])}
           (or type-label "—")]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" (chrome/position-pnl-class signed-amount)])}
           (vf/format-currency (or signed-amount amount))]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-accent-bright"])
                :title hash}
           [:span {:class (chrome/interactive-value-class)}
            (vf/short-hash hash)]]])

       :else
       (chrome/empty-table-row (count columns) "No deposits or withdrawals available."))]]])

(defn- copy-icon
  []
  [:svg {:viewBox "0 0 16 16"
         :class ["h-3.5" "w-3.5" "shrink-0"]
         :fill "none"
         :stroke "currentColor"
         :stroke-width 1.5
         :aria-hidden true}
   [:rect {:x 5.5 :y 5.5 :width 8 :height 8 :rx 1.5}]
   [:path {:d "M10.5 5.5V3.5a1 1 0 0 0-1-1h-6a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2"}]])

(defn depositor-address-cell
  "Short address at rest; on hover or keyboard focus the full address and a
   copy icon replace it in place (a floating tooltip would be clipped by the
   table's scroll box). Both forms share one grid cell and only swap
   visibility, so the column is always sized for the full address and hovering
   never reflows the table. Click copies the full address."
  [{:keys [address leader?]}]
  [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])}
   (if address
     [:button {:type "button"
               :class ["group/addr" "inline-flex" "items-center" "gap-1.5" "rounded" "text-left"
                       "hover:text-ho-accent-hi" "focus-visible:text-ho-accent-hi"
                       "focus-visible:outline-none" "focus-visible:ring-1" "focus-visible:ring-ho-accent"]
               :aria-label (str "Copy address " address)
               :data-role "vault-depositor-address"
               :on {:click [[:actions/copy-spectate-mode-watchlist-address address]]}}
      (when leader?
        [:span {:class ["rounded" "bg-ho-accent-soft" "px-1.5" "text-xs" "text-ho-accent-hi"]} "Leader"])
      [:span {:class ["grid"]}
       [:span {:class ["col-start-1" "row-start-1"
                       "group-hover/addr:invisible" "group-focus-visible/addr:invisible"]}
        (wallet/short-addr address)]
       [:span {:class ["invisible" "col-start-1" "row-start-1"
                       "group-hover/addr:visible" "group-focus-visible/addr:visible"]
               :aria-hidden true}
        address]]
      [:span {:class ["opacity-0" "group-hover/addr:opacity-100" "group-focus-visible/addr:opacity-100"]}
       (copy-icon)]]
     "—")])

(defn- copy-feedback-note
  [{:keys [kind message]}]
  (when (seq message)
    [:div {:class ["sticky" "left-0" "top-0" "z-10" "flex" "justify-end" "px-4" "py-1.5"]
           :role "status"
           :aria-live "polite"
           :data-role "vault-depositor-copy-feedback"}
     [:span {:class ["rounded-full" "border" "px-2.5" "py-0.5" "text-xs"
                     (if (= :error kind)
                       "border-ho-border-sell"
                       "border-ho-border-accent")
                     (if (= :error kind)
                       "text-ho-sell"
                       "text-ho-accent-hi")
                     "bg-ho-bg-deep"]}
      message]]))

(defn depositors-table [rows sort-state columns & [copy-feedback]]
  [:div {:class ["overflow-auto" "max-h-[540px]"]}
   (copy-feedback-note copy-feedback)
   [:table {:class ["w-full" "min-w-[980px]" "border-collapse"]}
    (chrome/table-header :depositors columns sort-state)
    [:tbody
     (if (seq rows)
       (for [{:keys [address vault-amount unrealized-pnl all-time-pnl days-following] :as row} rows]
         ^{:key (str "depositor-" address)}
         [:tr {:class chrome/activity-row-class}
          (depositor-address-cell row)
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])} (vf/format-currency vault-amount)]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" (chrome/position-pnl-class unrealized-pnl)])}
           (vf/format-currency unrealized-pnl)]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" (chrome/position-pnl-class all-time-pnl)])}
           (vf/format-currency all-time-pnl)]
          [:td {:class (into chrome/activity-cell-num-class ["whitespace-nowrap" "text-ho-text"])}
           (if (number? days-following)
             (str days-following)
             "—")]])
       (chrome/empty-table-row (count columns) "No depositors available."))]]])
