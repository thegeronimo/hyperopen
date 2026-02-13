(ns hyperopen.views.account-info.history-pagination
  (:require [hyperopen.views.account-info.shared :as shared]))

(def order-history-page-size-options
  [25 50 100])

(def ^:private order-history-page-size-option-set
  (set order-history-page-size-options))

(def default-order-history-page-size
  50)

(defn normalize-order-history-page-size [value]
  (let [page-size (shared/parse-optional-int value)]
    (if (contains? order-history-page-size-option-set page-size)
      page-size
      default-order-history-page-size)))

(defn normalize-order-history-page
  ([value]
   (normalize-order-history-page value nil))
  ([value max-page]
   (let [candidate (max 1 (or (shared/parse-optional-int value) 1))
         max-page* (when (some? max-page)
                     (max 1 (or (shared/parse-optional-int max-page) 1)))]
     (if max-page*
       (min candidate max-page*)
       candidate))))

(defn paginate-history-rows [rows pagination-state]
  (let [total-rows (count rows)
        page-size (normalize-order-history-page-size (:page-size pagination-state))
        page-count (max 1 (int (js/Math.ceil (/ total-rows page-size))))
        requested-page (normalize-order-history-page (:page pagination-state))
        safe-page (normalize-order-history-page requested-page page-count)
        start-idx (* (dec safe-page) page-size)
        end-idx (min total-rows (+ start-idx page-size))
        page-rows (if (< start-idx total-rows)
                    (subvec rows start-idx end-idx)
                    [])
        raw-page-input (some-> (:page-input pagination-state) str)
        page-input (if (= raw-page-input (str requested-page))
                     (str safe-page)
                     (or raw-page-input (str safe-page)))]
    {:rows page-rows
     :total-rows total-rows
     :page-size page-size
     :page safe-page
     :page-count page-count
     :page-input page-input}))

(def ^:private pagination-container-classes
  ["border-t" "border-base-300" "bg-base-100"])

(def ^:private pagination-content-classes
  ["flex" "flex-wrap" "items-center" "justify-between" "gap-3" "px-3" "py-2" "text-xs"])

(def ^:private pagination-section-classes
  ["flex" "items-center" "gap-2"])

(def ^:private pagination-page-size-select-classes
  ["select" "select-sm" "select-bordered" "h-8" "min-h-8" "w-24" "pl-2" "pr-8" "text-sm" "leading-5"])

(def ^:private pagination-nav-button-classes
  ["btn" "btn-xs" "btn-ghost" "h-6" "min-h-6" "min-w-6"])

(def ^:private pagination-go-button-classes
  ["btn" "btn-xs" "btn-primary" "h-6" "min-h-6" "min-w-6"])

(defn history-pagination-controls
  [{:keys [total-rows page-size page page-count page-input]}
   {:keys [page-size-id
           page-size-aria-label
           page-size-action
           prev-aria-label
           prev-action
           next-aria-label
           next-action
           page-input-id
           page-input-aria-label
           page-input-action
           page-input-keydown-action
           go-aria-label
           go-action]}]
  (let [on-first-page? (<= page 1)
        on-last-page? (>= page page-count)]
    [:div {:class pagination-container-classes}
     [:div {:class pagination-content-classes}
      [:div {:class pagination-section-classes}
       [:label {:for page-size-id
                :class ["font-medium" "text-trading-text-secondary"]}
        "Rows"]
       [:select {:id page-size-id
                 :class pagination-page-size-select-classes
                 :aria-label page-size-aria-label
                 :value (str page-size)
                 :on {:change [[page-size-action [:event.target/value]]]}}
        (for [size order-history-page-size-options]
          ^{:key size}
          [:option {:value (str size)}
           (str size)])]
       [:span {:class ["text-trading-text-secondary"]}
        (str "Total: " total-rows)]]
      [:div {:class pagination-section-classes}
       [:button {:class pagination-nav-button-classes
                 :aria-label prev-aria-label
                 :disabled on-first-page?
                 :on {:click [[prev-action page-count]]}}
        "Prev"]
       [:span {:class ["min-w-[5.5rem]" "text-center" "text-trading-text-secondary"]}
        (str "Page " page " of " page-count)]
       [:button {:class pagination-nav-button-classes
                 :aria-label next-aria-label
                 :disabled on-last-page?
                 :on {:click [[next-action page-count]]}}
        "Next"]]
      [:div {:class pagination-section-classes}
       [:label {:for page-input-id
                :class ["font-medium" "text-trading-text-secondary"]}
        "Jump"]
       [:input {:id page-input-id
                :class ["input" "input-xs" "input-bordered" "h-6" "min-h-6" "w-16" "text-xs"]
                :type "text"
                :inputmode "numeric"
                :pattern "[0-9]*"
                :aria-label page-input-aria-label
                :value page-input
                :on {:input [[page-input-action [:event.target/value]]]
                     :change [[page-input-action [:event.target/value]]]
                     :keydown [[page-input-keydown-action [:event/key] page-count]]}}]
       [:button {:class pagination-go-button-classes
                 :aria-label go-aria-label
                 :on {:click [[go-action page-count]]}}
        "Go"]]]]))

(def ^:private trade-history-pagination-config
  {:page-size-id "trade-history-page-size"
   :page-size-aria-label "Trade rows per page"
   :page-size-action :actions/set-trade-history-page-size
   :prev-aria-label "Previous trade page"
   :prev-action :actions/prev-trade-history-page
   :next-aria-label "Next trade page"
   :next-action :actions/next-trade-history-page
   :page-input-id "trade-history-page-input"
   :page-input-aria-label "Jump to trade page"
   :page-input-action :actions/set-trade-history-page-input
   :page-input-keydown-action :actions/handle-trade-history-page-input-keydown
   :go-aria-label "Go to trade page"
   :go-action :actions/apply-trade-history-page-input})

(def ^:private funding-history-pagination-config
  {:page-size-id "funding-history-page-size"
   :page-size-aria-label "Funding rows per page"
   :page-size-action :actions/set-funding-history-page-size
   :prev-aria-label "Previous funding page"
   :prev-action :actions/prev-funding-history-page
   :next-aria-label "Next funding page"
   :next-action :actions/next-funding-history-page
   :page-input-id "funding-history-page-input"
   :page-input-aria-label "Jump to funding page"
   :page-input-action :actions/set-funding-history-page-input
   :page-input-keydown-action :actions/handle-funding-history-page-input-keydown
   :go-aria-label "Go to funding page"
   :go-action :actions/apply-funding-history-page-input})

(def ^:private order-history-pagination-config
  {:page-size-id "order-history-page-size"
   :page-size-aria-label "Rows per page"
   :page-size-action :actions/set-order-history-page-size
   :prev-aria-label "Previous page"
   :prev-action :actions/prev-order-history-page
   :next-aria-label "Next page"
   :next-action :actions/next-order-history-page
   :page-input-id "order-history-page-input"
   :page-input-aria-label "Jump to page"
   :page-input-action :actions/set-order-history-page-input
   :page-input-keydown-action :actions/handle-order-history-page-input-keydown
   :go-aria-label "Go to page"
   :go-action :actions/apply-order-history-page-input})

(defn trade-history-pagination-controls [pagination]
  (history-pagination-controls pagination trade-history-pagination-config))

(defn funding-history-pagination-controls [pagination]
  (history-pagination-controls pagination funding-history-pagination-config))

(defn order-history-pagination-controls [pagination]
  (history-pagination-controls pagination order-history-pagination-config))
