(ns hyperopen.views.account-info.tabs.funding-history
  (:require [hyperopen.views.account-info.history-pagination :as history-pagination]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.table :as table]))

(def default-funding-history-sort
  {:column "Time" :direction :desc})

(defn funding-history-sort-state [funding-history-state]
  (merge default-funding-history-sort
         (or (:sort funding-history-state) {})))

(defn- empty-state [message]
  [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
   [:div.text-lg.font-medium message]
   [:div.text-sm.opacity-70.mt-2 "No data available"]])

(defn- datetime-local-value [time-ms]
  (when time-ms
    (let [d (js/Date. time-ms)
          pad2 (fn [v] (.padStart (str v) 2 "0"))]
      (str (.getFullYear d)
           "-"
           (pad2 (inc (.getMonth d)))
           "-"
           (pad2 (.getDate d))
           "T"
           (pad2 (.getHours d))
           ":"
           (pad2 (.getMinutes d))))))

(defn- funding-filter-coin-label [coin]
  (let [coin* (shared/non-blank-text coin)
        parsed (shared/parse-coin-namespace coin*)
        base-label (or (:base parsed) coin* "-")
        prefix-label (:prefix parsed)]
    [:span {:class ["flex" "items-center" "gap-1.5" "min-w-0"]}
     [:span {:class ["truncate"]} base-label]
     (when prefix-label
       [:span {:class shared/position-chip-classes} prefix-label])]))

(defn- funding-side-value [row]
  (or (:position-side row)
      (let [signed-size (shared/parse-num (or (:position-size-raw row)
                                              (:positionSize row)))]
        (cond
          (pos? signed-size) :long
          (neg? signed-size) :short
          :else :flat))))

(defn- funding-side-label [position-side]
  (case position-side
    :long "Long"
    :short "Short"
    :flat "Flat"
    "Flat"))

(defn- funding-side-class [position-side]
  (case position-side
    :long "text-success"
    :short "text-error"
    :flat "text-base-content"
    "text-base-content"))

(defn- funding-size-text [row]
  (let [size (js/Math.abs (shared/parse-num (or (:position-size-raw row)
                                                 (:positionSize row)
                                                 (:size-raw row))))
        {:keys [base-label]} (shared/resolve-coin-display (:coin row) {})
        coin (or (shared/non-blank-text base-label) "-")]
    (str (.toLocaleString (js/Number. size)
                          "en-US"
                          #js {:minimumFractionDigits 3
                               :maximumFractionDigits 6})
         " "
         coin)))

(defn- funding-payment-node [row]
  (let [payment (shared/parse-num (or (:payment-usdc-raw row)
                                       (:payment row)))
        color-class (cond
                      (neg? payment) "text-error"
                      (pos? payment) "text-success"
                      :else "text-base-content")]
    [:span {:class [color-class "num"]}
     (str (.toLocaleString (js/Number. payment)
                           "en-US"
                           #js {:minimumFractionDigits 4
                                :maximumFractionDigits 6})
          " USDC")]))

(defn- funding-rate-node [row]
  (let [rate (shared/parse-num (or (:funding-rate-raw row)
                                    (:fundingRate row)))
        color-class (cond
                      (neg? rate) "text-error"
                      (pos? rate) "text-success"
                      :else "text-base-content")]
    [:span {:class [color-class "num"]}
     (str (.toFixed (* 100 rate) 4) "%")]))

(defn- funding-coin-options [fundings-raw]
  (->> fundings-raw
       (map :coin)
       (filter string?)
       distinct
       sort
       vec))

(defn funding-history-controls [funding-history-state fundings-raw]
  (let [filters (or (:filters funding-history-state) {})
        draft-filters (or (:draft-filters funding-history-state) filters)
        coin-set (or (:coin-set draft-filters) #{})
        filter-open? (boolean (:filter-open? funding-history-state))
        loading? (boolean (:loading? funding-history-state))
        error (:error funding-history-state)
        status-open? (or loading? (some? error))
        start-time-ms (:start-time-ms draft-filters)
        end-time-ms (:end-time-ms draft-filters)
        coin-options (funding-coin-options fundings-raw)]
    (when (or status-open? filter-open?)
      [:div {:class ["border-b" "border-base-300" "bg-base-200"]}
       (when status-open?
         [:div {:class ["flex" "items-center" "gap-2" "px-4" "py-2" "text-sm"]}
          (when loading?
            [:span {:class ["text-xs" "text-trading-text-secondary"]} "Loading..."])
          (when error
            [:span {:class ["text-xs" "text-error"]} (str error)])])
       (when filter-open?
         [:div {:class (into ["grid" "grid-cols-1" "gap-3" "p-4" "text-sm" "md:grid-cols-2"]
                             (when status-open?
                               ["border-t" "border-base-300"]))}
          [:div {:class ["space-y-2"]}
           [:label {:class ["text-xs" "font-medium" "text-trading-text-secondary"]}
            "Start Time"]
           [:input.input.input-sm.input-bordered.w-full
            {:type "datetime-local"
             :value (or (datetime-local-value start-time-ms) "")
             :on {:change [[:actions/set-funding-history-filters [:draft-filters :start-time-ms] :event.target/value]]}}]]
          [:div {:class ["space-y-2"]}
           [:label {:class ["text-xs" "font-medium" "text-trading-text-secondary"]}
            "End Time"]
           [:input.input.input-sm.input-bordered.w-full
            {:type "datetime-local"
             :value (or (datetime-local-value end-time-ms) "")
             :on {:change [[:actions/set-funding-history-filters [:draft-filters :end-time-ms] :event.target/value]]}}]]
          [:div {:class ["space-y-2" "md:col-span-2"]}
           [:label {:class ["text-xs" "font-medium" "text-trading-text-secondary"]}
            "Coins"]
           (if (seq coin-options)
             [:div {:class ["flex" "max-h-28" "flex-wrap" "gap-2" "overflow-y-auto" "rounded-md" "border" "border-base-300" "bg-base-100" "p-2"]}
              (for [coin coin-options]
                ^{:key coin}
                [:label {:class ["flex" "items-center" "gap-1" "rounded-md" "px-1" "py-px" "hover:bg-base-200"]}
                 [:input {:class ["h-4"
                                  "w-4"
                                  "rounded-[3px]"
                                  "border"
                                  "border-base-300"
                                  "bg-transparent"
                                  "trade-toggle-checkbox"
                                  "transition-colors"
                                  "focus:outline-none"
                                  "focus:ring-0"
                                  "focus:ring-offset-0"
                                  "focus:shadow-none"]
                          :type "checkbox"
                          :checked (contains? coin-set coin)
                          :on {:change [[:actions/toggle-funding-history-filter-coin coin]]}}]
                 (funding-filter-coin-label coin)])]
             [:div {:class ["text-xs" "text-trading-text-secondary"]}
              "No coin data available for current range."])]
          [:div {:class ["flex" "items-center" "justify-end" "gap-2" "md:col-span-2"]}
           [:button {:class ["btn" "btn-xs" "btn-ghost" "h-8" "px-3" "text-xs" "font-medium" "min-w-[4.5rem]"]
                     :on {:click [[:actions/reset-funding-history-filter-draft]]}}
            "Cancel"]
           [:button {:class ["btn" "btn-xs" "btn-primary" "h-8" "px-3" "text-xs" "font-medium" "min-w-[4.5rem]"]
                     :on {:click [[:actions/apply-funding-history-filters]]}}
            "Apply"]]])])))

(defn- funding-row-sort-id [row]
  (or (:id row)
      (str (or (:time-ms row) (:time row) 0)
           "|"
           (or (:coin row) "")
           "|"
           (or (:position-size-raw row) (:positionSize row) (:size-raw row) 0)
           "|"
           (or (:payment-usdc-raw row) (:payment row) 0)
           "|"
           (or (:funding-rate-raw row) (:fundingRate row) 0))))

(defn sort-funding-history-by-column [rows column direction]
  (let [sort-fn (case column
                  "Time" (fn [row]
                           (shared/parse-num (or (:time-ms row) (:time row))))
                  "Coin" (fn [row]
                           (or (:coin row) ""))
                  "Size" (fn [row]
                           (js/Math.abs
                            (shared/parse-num (or (:position-size-raw row)
                                                   (:positionSize row)
                                                   (:size-raw row)))))
                  "Position Side" (fn [row]
                                    (name (funding-side-value row)))
                  "Payment" (fn [row]
                              (shared/parse-num (or (:payment-usdc-raw row)
                                                     (:payment row))))
                  "Rate" (fn [row]
                           (shared/parse-num (or (:funding-rate-raw row)
                                                  (:fundingRate row))))
                  (fn [_] 0))
        sorted (sort-by (fn [row]
                          [(sort-fn row)
                           (funding-row-sort-id row)])
                        rows)]
    (if (= direction :desc)
      (reverse sorted)
      sorted)))

(defn sortable-funding-history-header [column-name sort-state]
  (table/sortable-header-button column-name sort-state :actions/sort-funding-history))

(defn- funding-coin-node [coin]
  (let [{:keys [base-label prefix-label]} (shared/resolve-coin-display coin {})]
    [:span {:class ["flex" "items-center" "gap-1.5" "min-w-0"]}
     [:span {:class ["truncate" "font-semibold"]
             :style {:color "rgb(151, 252, 228)"}}
      base-label]
     (when prefix-label
       [:span {:class shared/position-chip-classes} prefix-label])]))

(defn funding-history-table [fundings funding-history-state]
  (let [sort-state (funding-history-sort-state funding-history-state)
        sorted-fundings (vec (sort-funding-history-by-column fundings
                                                             (:column sort-state)
                                                             (:direction sort-state)))
        {:keys [rows] :as pagination} (history-pagination/paginate-history-rows sorted-fundings funding-history-state)]
    (if (seq sorted-fundings)
      (table/tab-table-content
       [:div.grid.grid-cols-6.gap-2.py-1.px-3.bg-base-200.text-sm.font-medium
        [:div (sortable-funding-history-header "Time" sort-state)]
        [:div.text-left (sortable-funding-history-header "Coin" sort-state)]
        [:div.text-left (sortable-funding-history-header "Size" sort-state)]
        [:div.text-left (sortable-funding-history-header "Position Side" sort-state)]
        [:div.text-left (sortable-funding-history-header "Payment" sort-state)]
        [:div.text-left (sortable-funding-history-header "Rate" sort-state)]]
       (for [funding-row rows]
         ^{:key (funding-row-sort-id funding-row)}
         [:div.grid.grid-cols-6.gap-2.py-px.px-3.hover:bg-base-300.text-sm
          [:div (shared/format-funding-history-time (or (:time-ms funding-row) (:time funding-row)))]
          [:div.text-left (funding-coin-node (:coin funding-row))]
          [:div.text-left.num (funding-size-text funding-row)]
          [:div.text-left
           (let [position-side (funding-side-value funding-row)]
             [:span {:class (funding-side-class position-side)}
              (funding-side-label position-side)])]
          [:div.text-left.num (funding-payment-node funding-row)]
          [:div.text-left.num (funding-rate-node funding-row)]])
       (history-pagination/funding-history-pagination-controls pagination))
      (if (:loading? funding-history-state)
        (empty-state "Loading funding history...")
        (empty-state "No funding history")))))

(defn funding-history-tab-content
  ([fundings]
   (funding-history-table fundings {}))
  ([fundings funding-history-state fundings-raw]
   [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]}
    (funding-history-controls funding-history-state fundings-raw)
    (funding-history-table fundings funding-history-state)]))
