(ns hyperopen.portfolio.account-activity-actions
  "Sub-tab, sort and pagination actions for the portfolio Account Activity table.

   Every action here emits only `:effects/save-many`, which the runtime
   classifies as a projection effect. That is why none of them appear in
   `runtime/effect_order_contract.cljs` or in the Lean formal surface: those
   govern ordering of heavy IO, and there is none here."
  (:require [hyperopen.account.history.shared :as history-shared]
            [hyperopen.domain.account-activity :as account-activity]))

(def state-path
  [:portfolio-ui :account-activity])

(def sub-tab-path (conj state-path :sub-tab))
(def sort-path (conj state-path :sort))
(def page-path (conj state-path :page))
(def page-size-path (conj state-path :page-size))
(def page-input-path (conj state-path :page-input))

(def default-sort
  {:column "Time" :direction :desc})

(def ^:private descending-first-columns
  "Columns whose first click should sort largest/newest first."
  #{"Time" "Account Change" "USD Value" "Fee"})

(defn default-account-activity-state
  []
  {:sub-tab account-activity/default-sub-tab
   :sort default-sort
   :page 1
   :page-size history-shared/default-order-history-page-size
   :page-input "1"})

(defn- reset-page-entries
  []
  [[page-path 1]
   [page-input-path "1"]])

(defn set-portfolio-account-activity-sub-tab
  "Select a sub-tab, returning to page 1.

   The page reset is deliberate and is a departure from the reference client,
   whose page index survives a sub-tab change -- so moving from a three-page
   sub-tab to a one-page one while on page 3 leaves an empty table under a
   hidden pagination footer."
  [_state sub-tab]
  [(into [:effects/save-many]
         [(into [[sub-tab-path (account-activity/normalize-sub-tab sub-tab)]]
                (reset-page-entries))])])

(defn sort-portfolio-account-activity
  "Toggle the sort for a column, resetting to page 1.

   Re-clicking the active column flips direction; moving to a new column starts
   descending for the numeric and time columns and ascending for the textual
   ones, which is the reference's behaviour."
  [state column]
  (let [{current-column :column current-direction :direction}
        (get-in state sort-path default-sort)

        direction (if (= current-column column)
                    (if (= current-direction :asc) :desc :asc)
                    (if (contains? descending-first-columns column) :desc :asc))]
    [(into [:effects/save-many]
           [(into [[sort-path {:column column :direction direction}]]
                  (reset-page-entries))])]))

(defn set-portfolio-account-activity-page-size
  [state page-size]
  (let [locale (get-in state [:ui :locale])
        page-size* (history-shared/normalize-order-history-page-size page-size locale)]
    [(into [:effects/save-many]
           [(into [[page-size-path page-size*]]
                  (reset-page-entries))])]))

(defn set-portfolio-account-activity-page
  [state page max-page]
  (let [locale (get-in state [:ui :locale])
        page* (history-shared/normalize-order-history-page page max-page locale)]
    [[:effects/save-many [[page-path page*]
                          [page-input-path (str page*)]]]]))

(defn next-portfolio-account-activity-page
  [state max-page]
  (set-portfolio-account-activity-page state
                                       (inc (get-in state page-path 1))
                                       max-page))

(defn prev-portfolio-account-activity-page
  [state max-page]
  (set-portfolio-account-activity-page state
                                       (dec (get-in state page-path 1))
                                       max-page))

(defn set-portfolio-account-activity-page-input
  [_state input-value]
  [[:effects/save page-input-path (if (string? input-value)
                                    input-value
                                    (str (or input-value "")))]])

(defn apply-portfolio-account-activity-page-input
  [state max-page]
  (let [locale (get-in state [:ui :locale])
        page* (history-shared/normalize-order-history-page
               (get-in state page-input-path "")
               max-page
               locale)]
    [[:effects/save-many [[page-path page*]
                          [page-input-path (str page*)]]]]))

(defn handle-portfolio-account-activity-page-input-keydown
  [state key max-page]
  (if (= key "Enter")
    (apply-portfolio-account-activity-page-input state max-page)
    []))
