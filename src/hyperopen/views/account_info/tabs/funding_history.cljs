(ns hyperopen.views.account-info.tabs.funding-history
  (:require [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.utils.formatting :as fmt]
            [hyperopen.ui.table.sort-kernel :as sort-kernel]
            [hyperopen.views.account-info.history-pagination :as history-pagination]
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
  (fmt/format-local-datetime-input-value time-ms))

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
    (str (or (fmt/format-intl-number size
                                     {:minimumFractionDigits 3
                                      :maximumFractionDigits 6})
             "0.000")
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
     (str (or (fmt/format-intl-number payment
                                      {:minimumFractionDigits 4
                                       :maximumFractionDigits 6})
              "0.0000")
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

(defn- funding-filter-selected-coins [coin-set]
  (->> coin-set
       (filter string?)
       sort
       vec))

(defn- funding-filter-coin-candidates [coin-options coin-set coin-search]
  (let [query (shared/normalize-coin-search-query coin-search)]
    (->> coin-options
         (remove #(contains? coin-set %))
         (filter #(shared/coin-matches-search? % query))
         vec)))

(defn- funding-filter-empty-message [coin-options coin-candidates coin-search]
  (cond
    (empty? coin-options)
    "No coin data available for current range."

    (seq coin-candidates)
    nil

    (seq (shared/normalize-coin-search-query coin-search))
    "No matching coins."

    :else
    "All coins selected."))

(defn- funding-filter-selected-chip [coin]
  [:span {:class ["inline-flex"
                  "max-w-full"
                  "items-center"
                  "gap-1"
                  "rounded-md"
                  "border"
                  "border-base-300"
                  "bg-base-200"
                  "px-1.5"
                  "py-1"]}
   [:span {:class ["min-w-0"]} (funding-filter-coin-label coin)]
   [:button {:type "button"
             :class ["inline-flex"
                     "h-6"
                     "w-6"
                     "items-center"
                     "justify-center"
                     "rounded"
                     "text-trading-text-secondary"
                     "transition-colors"
                     "hover:bg-base-300"
                     "hover:text-trading-text"
                     "focus:outline-none"
                     "focus:ring-0"
                     "focus:ring-offset-0"]
             :aria-label (str "Remove " coin " filter")
             :on {:click [[:actions/toggle-funding-history-filter-coin coin]]}}
    "x"]])

(defn- funding-filter-suggestion-row [coin]
  [:button {:type "button"
            :class ["flex"
                    "w-full"
                    "items-center"
                    "justify-start"
                    "rounded"
                    "px-2"
                    "py-1.5"
                    "text-left"
                    "text-xs"
                    "transition-colors"
                    "hover:bg-base-300"
                    "focus:outline-none"
                    "focus:ring-0"
                    "focus:ring-offset-0"]
            :on {:mousedown [[:actions/add-funding-history-filter-coin coin]]}}
   (funding-filter-coin-label coin)])

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
        coin-options (funding-coin-options fundings-raw)
        coin-search (:coin-search funding-history-state "")
        suggestions-open? (boolean (:coin-suggestions-open? funding-history-state))
        selected-coins (funding-filter-selected-coins coin-set)
        coin-candidates (funding-filter-coin-candidates coin-options coin-set coin-search)
        top-coin (first coin-candidates)
        empty-message (funding-filter-empty-message coin-options coin-candidates coin-search)]
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
           [:div {:class ["space-y-2"]}
            [:div {:class ["rounded-md" "border" "border-base-300" "bg-base-100" "px-2" "py-2"]}
             [:div {:class ["flex" "max-h-24" "flex-wrap" "items-center" "gap-2" "overflow-y-auto"]}
              (for [coin selected-coins]
                ^{:key (str "funding-filter-chip-" coin)}
                (funding-filter-selected-chip coin))
              [:input {:id "funding-history-coin-search"
                       :class ["h-8"
                               "min-w-[8rem]"
                               "flex-1"
                               "border-0"
                               "bg-transparent"
                               "px-1"
                               "text-xs"
                               "text-trading-text"
                               "focus:outline-none"
                               "focus:ring-0"
                               "focus:ring-offset-0"]
                       :type "search"
                       :placeholder "Search and press Enter"
                       :aria-label "Search funding coins"
                       :autocomplete "off"
                       :spellcheck false
                       :value (or coin-search "")
                       :on {:input [[:actions/set-funding-history-filters :coin-search [:event.target/value]]]
                            :focus [[:actions/set-funding-history-filters :coin-suggestions-open? true]]
                            :blur [[:actions/set-funding-history-filters :coin-suggestions-open? false]]
                            :keydown [[:actions/handle-funding-history-coin-search-keydown [:event/key] top-coin]]}}]]
             (when suggestions-open?
               [:div {:class ["max-h-40" "overflow-y-auto" "rounded-md" "border" "border-base-300" "bg-base-100" "p-1"]}
                (if (seq coin-candidates)
                  (for [coin coin-candidates]
                    ^{:key (str "funding-filter-suggestion-" coin)}
                    (funding-filter-suggestion-row coin))
                  [:div {:class ["px-2" "py-1.5" "text-xs" "text-trading-text-secondary"]}
                   (or empty-message "No matching coins.")])])]]
          [:div {:class ["flex" "items-center" "justify-end" "gap-2" "md:col-span-2"]}
           [:button {:class ["btn" "btn-xs" "btn-spectate" "h-8" "px-3" "text-xs" "font-medium" "min-w-[4.5rem]"]
                     :on {:click [[:actions/reset-funding-history-filter-draft]]}}
           "Cancel"]
           [:button {:class ["btn" "btn-xs" "btn-primary" "h-8" "px-3" "text-xs" "font-medium" "min-w-[4.5rem]"]
                      :on {:click [[:actions/apply-funding-history-filters]]}}
             "Apply"]]]])])))

(defn- funding-row-sort-id [row]
  (or (:id row)
      (funding-history/funding-history-row-id
       (or (:time-ms row) (:time row) 0)
       (or (:coin row) "")
       (or (:position-size-raw row) (:positionSize row) (:size-raw row) 0)
       (or (:payment-usdc-raw row) (:payment row) 0)
       (or (:funding-rate-raw row) (:fundingRate row) 0))))

(defn- funding-row-time [row]
  (or (:time-ms row) (:time row)))

(defn- funding-row-coin [row]
  (or (:coin row) ""))

(defn- funding-row-signed-size [row]
  (or (:position-size-raw row)
      (:positionSize row)
      (:size-raw row)))

(defn- funding-row-payment [row]
  (or (:payment-usdc-raw row)
      (:payment row)))

(defn- funding-row-rate [row]
  (or (:funding-rate-raw row)
      (:fundingRate row)))

(defn sort-funding-history-by-column [rows column direction]
  (sort-kernel/sort-rows-by-column
   rows
   {:column column
    :direction direction
    :accessor-by-column
    {"Time" (fn [row]
              (shared/parse-num (funding-row-time row)))
     "Coin" (fn [row]
              (funding-row-coin row))
     "Size" (fn [row]
              (js/Math.abs (shared/parse-num (funding-row-signed-size row))))
     "Position Side" (fn [row]
                       (name (funding-side-value row)))
     "Payment" (fn [row]
                 (shared/parse-num (funding-row-payment row)))
     "Rate" (fn [row]
              (shared/parse-num (funding-row-rate row)))}
    :tie-breaker funding-row-sort-id}))

(defonce ^:private sorted-funding-history-cache (atom nil))

(defn reset-funding-history-sort-cache! []
  (reset! sorted-funding-history-cache nil))

(defn- memoized-sorted-funding-history [fundings sort-state]
  (let [column (:column sort-state)
        direction (:direction sort-state)
        cache @sorted-funding-history-cache
        cache-hit? (and (map? cache)
                        (identical? fundings (:fundings cache))
                        (= column (:column cache))
                        (= direction (:direction cache)))]
    (if cache-hit?
      (:result cache)
      (let [result (vec (sort-funding-history-by-column fundings column direction))]
        (reset! sorted-funding-history-cache {:fundings fundings
                                              :column column
                                              :direction direction
                                              :result result})
        result))))

(defn sortable-funding-history-header [column-name sort-state]
  (table/sortable-header-button column-name sort-state :actions/sort-funding-history))

(defn- funding-coin-node [coin]
  (let [{:keys [base-label prefix-label]} (shared/resolve-coin-display coin {})]
    (shared/coin-select-control
     coin
     [:span {:class ["flex" "items-center" "gap-1.5" "min-w-0"]}
      [:span {:class ["truncate" "font-semibold"]
              :style {:color "rgb(151, 252, 228)"}}
       base-label]
      (when prefix-label
        [:span {:class shared/position-chip-classes} prefix-label])]
     {:extra-classes ["w-full" "justify-start" "text-left"]})))

(defn funding-history-table [fundings funding-history-state]
  (let [sort-state (funding-history-sort-state funding-history-state)
        sorted-fundings (memoized-sorted-funding-history fundings sort-state)
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
          [:div (shared/format-funding-history-time (funding-row-time funding-row))]
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
