(ns hyperopen.views.portfolio.account-activity
  "The portfolio Account Activity table: a sub-tab strip over the non-funding
   ledger, with sortable columns and pagination.

   This ships inside the route-lazy `:portfolio_route` module, because
   `portfolio-view` is that module's entry. It deliberately does NOT live under
   `views/account_info/**`, which compiles into `:account_surfaces` and would
   carry this portfolio-only table onto the trade route for nothing."
  (:require [clojure.string :as str]
            [hyperopen.domain.account-activity :as account-activity]
            [hyperopen.views.account-info.history-pagination :as history-pagination]
            [hyperopen.views.account-info.shared :as account-shared]
            [hyperopen.views.account-info.table :as account-table]
            [hyperopen.views.portfolio.account-activity.model :as model]))

(def ^:private column-track
  {:time "minmax(10rem,1.2fr)"
   :status "minmax(5.5rem,.7fr)"
   :asset "minmax(4.5rem,.6fr)"
   :action "minmax(7rem,.9fr)"
   :from "minmax(7rem,.9fr)"
   :to "minmax(7rem,.9fr)"
   :destination "minmax(7.5rem,.9fr)"
   :account-change "minmax(9rem,1.1fr)"
   :usd-value "minmax(7rem,.9fr)"
   :fee "minmax(5.5rem,.7fr)"})

(def ^:private column-min-width
  {10 "min-w-[1080px]"
   8 "min-w-[880px]"})

(defn- grid-style
  [columns]
  {:grid-template-columns (str/join " " (map column-track columns))})

(defn- grid-classes
  [columns base]
  (into base [(get column-min-width (count columns) "min-w-[880px]")]))

(def ^:private header-base-classes
  ["grid" "gap-2" "bg-base-200" "px-3" "py-1" "text-sm" "font-medium"])

(def ^:private row-base-classes
  ["grid" "gap-2" "px-3" "py-px" "text-sm" "hover:bg-base-300"])

;; --- sub-tab strip --------------------------------------------------------

(defn- sub-tab-button
  [{:keys [id label]} selected-sub-tab]
  [:button {:type "button"
            :class (into ["whitespace-nowrap"
                          "border-b-2"
                          "px-3"
                          "py-2"
                          "text-sm"
                          "transition-colors"
                          "focus:outline-none"]
                         (if (= id selected-sub-tab)
                           ["border-trading-green" "text-trading-text" "font-medium"]
                           ["border-transparent"
                            "text-trading-text-secondary"
                            "hover:text-trading-text"]))
            :aria-pressed (= id selected-sub-tab)
            :data-role (str "account-activity-sub-tab-" (name id))
            :on {:click [[:actions/set-portfolio-account-activity-sub-tab id]]}}
   label])

(defn- sub-tab-strip
  [sub-tabs selected-sub-tab]
  [:div {:class ["border-b" "border-base-300" "min-w-0" "overflow-x-auto" "scrollbar-hide"]
         :data-role "account-activity-sub-tab-strip"}
   (into [:div {:class ["flex" "min-w-max" "items-center"]}]
         (map (fn [sub-tab]
                ^{:key (str "account-activity-sub-tab-" (name (:id sub-tab)))}
                (sub-tab-button sub-tab selected-sub-tab)))
         sub-tabs)])

;; --- header ---------------------------------------------------------------

(defn- header-cell
  [column sort-state]
  [:div (account-table/sortable-header-button
         (account-activity/column-label column)
         sort-state
         :actions/sort-portfolio-account-activity)])

(defn- table-header
  [columns sort-state]
  (into [:div {:class (grid-classes columns header-base-classes)
               :style (grid-style columns)}]
        (map (fn [column]
               ^{:key (str "account-activity-header-" (name column))}
               (header-cell column sort-state)))
        columns))

;; --- cells ----------------------------------------------------------------

(defn- transaction-link
  [{:keys [explorer-url hash]}]
  (when (and explorer-url hash)
    [:a {:href explorer-url
         :target "_blank"
         :rel "noreferrer"
         :class ["inline-flex"
                 "items-center"
                 "text-trading-green"
                 "transition-colors"
                 "hover:text-trading-green/80"
                 "focus:outline-none"
                 "focus:ring-0"
                 "focus:ring-offset-0"]
         :aria-label "Open transaction in Hyperliquid explorer"
         :title hash}
     (account-shared/external-link-icon ["h-3" "w-3" "shrink-0"])]))

(defn- signed-amount-class
  [signed-amount]
  (cond
    (and (number? signed-amount) (pos? signed-amount)) "text-success"
    (and (number? signed-amount) (neg? signed-amount)) "text-error"
    :else "text-trading-text"))

(defn- text-cell
  [value]
  [:div {:class ["text-left" "truncate" "text-trading-text"]} (or value "--")])

(defn- row-cell
  [column row]
  (case column
    :time [:div {:class ["flex" "items-center" "gap-1.5" "min-w-0"]}
           [:span {:class ["truncate"]}
            (account-shared/format-funding-history-time (:time-ms row))]
           (transaction-link row)]

    :status (text-cell (:status-label row))
    :asset (text-cell (:asset row))

    :action [:div {:class ["text-left" "truncate" "font-semibold" "text-trading-text"]}
             (or (:action-label row) "--")]

    :from (text-cell (:from-label row))
    :to (text-cell (:to-label row))

    :destination
    (if-let [address (model/truncate-address (:destination-address row))]
      [:div {:class ["text-left" "truncate" "text-trading-text"]
             :title (:destination-address row)}
       address]
      (text-cell nil))

    :account-change
    [:div {:class ["text-left" "num" (signed-amount-class (:signed-amount row))]}
     (or (:amount-text row) "--")]

    :usd-value
    [:div {:class ["text-left" "num" "text-trading-text"]
           :title (when (:usd-value-estimated? row)
                    "Estimated from the current spot price")}
     (or (:usd-value-text row) "--")]

    :fee [:div {:class ["text-left" "num" "text-trading-text"]} (or (:fee-text row) "--")]

    (text-cell nil)))

(defn- activity-row
  [columns row]
  ;; `into` targets the tag vector directly: handing Replicant a list as a
  ;; single child renders it stringified.
  (with-meta
    (into [:div {:class (grid-classes columns row-base-classes)
                 :style (grid-style columns)}]
          (map #(row-cell % row))
          columns)
    {:key (:id row)}))

;; --- states ---------------------------------------------------------------

(defn- empty-state
  [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div {:class ["mt-2" "text-sm" "text-trading-text-secondary"]} "No data available"]])

(def ^:private pagination-config
  {:page-size-id "account-activity-page-size"
   :page-size-aria-label "Account activity rows per page"
   :page-size-action :actions/set-portfolio-account-activity-page-size
   :prev-aria-label "Previous account activity page"
   :prev-action :actions/prev-portfolio-account-activity-page
   :next-aria-label "Next account activity page"
   :next-action :actions/next-portfolio-account-activity-page
   :page-input-id "account-activity-page-input"
   :page-input-aria-label "Jump to account activity page"
   :page-input-action :actions/set-portfolio-account-activity-page-input
   :page-input-keydown-action :actions/handle-portfolio-account-activity-page-input-keydown
   :go-aria-label "Go to account activity page"
   :go-action :actions/apply-portfolio-account-activity-page-input})

(defn account-activity-table
  "The Account Activity panel for `state`."
  [state]
  (let [{:keys [sub-tab sub-tabs columns sort rows pagination loading? error total-rows]}
        (model/account-activity-model state)

        sub-tab-label (some (fn [{:keys [id label]}]
                              (when (= id sub-tab) label))
                            sub-tabs)

        body-rows (cond
                    error [(empty-state (str error))]
                    loading? [(empty-state "Loading account activity...")]
                    (seq rows) (mapv #(activity-row columns %) rows)
                    (= :all sub-tab) [(empty-state "No account activity")]
                    :else [(empty-state (str "No " sub-tab-label))])

        footer (when (and (not error)
                          (not loading?)
                          (> total-rows (:page-size pagination)))
                 (history-pagination/history-pagination-controls pagination pagination-config))]
    [:div {:class ["flex" "h-full" "min-h-0" "min-w-0" "flex-col"]
           :data-role "portfolio-account-activity"}
     (sub-tab-strip sub-tabs sub-tab)
     [:div {:class ["flex-1" "min-h-0" "min-w-0"]
            :data-role "portfolio-deposits-withdrawals-table"}
      (account-table/tab-table-content
       (table-header columns sort)
       body-rows
       footer)]]))
