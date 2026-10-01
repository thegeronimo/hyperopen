(ns hyperopen.views.account-info.tabs.balances
  (:require [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.projections.balances-hyperevm :as balances-hyperevm]
            [hyperopen.views.account-info.projections.balances-moves :as move-targets]
            [hyperopen.views.account-info.shared :as shared]
            [hyperopen.views.account-info.tabs.balances.desktop :as balances-desktop]
            [hyperopen.views.account-info.tabs.balances.location-filter :as location-filter]
            [hyperopen.views.account-info.tabs.balances.mobile :as balances-mobile]
            [hyperopen.views.account-info.tabs.balances.shared :as balances-shared]))

(defn- empty-state
  ([message]
   (empty-state message "No data available"))
  ([message detail]
   [:div.flex.flex-col.items-center.justify-center.py-12.text-base-content
    [:div.text-lg.font-medium message]
    [:div {:class (cond-> ["mt-2" "text-sm" "text-trading-text-secondary"]
                    (nil? detail) (conj "hidden"))}
     (or detail "")]]))

(defn- balances-empty-state
  "What an empty table says. Filtered to HyperEVM it names why nothing
   shows (an unread or unreadable HyperEVM account, Hide Small Balances, or
   the search) instead of claiming there is no data."
  [location-filter location-rows visible-rows hyperevm-status note]
  (let [state-node (if (= :hyperevm location-filter)
                     (empty-state (location-filter/empty-message
                                   (cond
                                     (empty? location-rows) hyperevm-status
                                     (empty? visible-rows) :hidden-small
                                     :else :no-match))
                                  nil)
                     (empty-state "No balance data available"))]
    (if note
      [:div {:class ["flex" "h-full" "min-h-0" "flex-col"]}
       state-node
       note]
      state-node)))

(defn build-balance-rows [webdata2 spot-data]
  (projections/build-balance-rows webdata2 spot-data nil))

(defn build-balance-rows-for-account [webdata2 spot-data account]
  (projections/build-balance-rows webdata2 spot-data account))

(defn sort-balances-by-column [rows column direction]
(balances-shared/sort-balances-by-column rows column direction))

(def sortable-balances-header
  balances-desktop/sortable-balances-header)

(def balance-row
  balances-desktop/balance-row)

(def balance-table-header
  balances-desktop/balance-table-header)

(defn balances-tab-content
  ([balance-rows hide-small? sort-state]
   (balances-tab-content balance-rows hide-small? sort-state "" {}))
  ([balance-rows hide-small? sort-state coin-search]
   (balances-tab-content balance-rows hide-small? sort-state coin-search {}))
  ([balance-rows hide-small? sort-state coin-search options]
   (let [{:keys [mobile-expanded-card read-only? location-filter hyperevm-status
                 hyperevm-moves-blocked-message]}
         (balances-shared/normalize-balances-options options)
         location-filter* (balances-hyperevm/normalize-location-filter location-filter)
         rows* (or balance-rows [])
         has-hyperevm-rows? (boolean (some balances-hyperevm/hyperevm-row? rows*))
         core-chips? (and (= :all location-filter*) has-hyperevm-rows?)
         ;; Under All, a first HyperEVM read still pending or failed is
         ;; said in the note, so an empty HyperEVM side never reads as
         ;; "nothing on HyperEVM". (The HyperEVM filter's empty state says
         ;; it there instead.)
         status-line (when (= :all location-filter*)
                       (location-filter/status-message hyperevm-status))
         ;; An account-level block (a subaccount, no wallet) shows wherever
         ;; a rendered move is disabled by it, HyperEVM rows or not.
         moves-blocked? (and (not read-only?)
                             (some? hyperevm-moves-blocked-message)
                             (some move-targets/hyperevm-target? rows*))
         note-visible? (boolean (or has-hyperevm-rows?
                                    (= :hyperevm location-filter*)
                                    status-line
                                    moves-blocked?))
         note-opts {:blocked-message hyperevm-moves-blocked-message
                    :status-line status-line}
         location-rows (balances-hyperevm/filter-rows-by-location rows* location-filter*)
         visible-rows (if hide-small?
                        (filter (fn [row]
                                  ;; An unpriced HyperEVM token's value is
                                  ;; unknown, not small, so it stays.
                                  (or (balances-hyperevm/unpriced-row? row)
                                      (>= (shared/parse-num (:usdc-value row)) 1)))
                                location-rows)
                        location-rows)
         search-filtered-rows (balances-shared/filter-balances-by-coin-search visible-rows coin-search)
         sorted-rows (if (:column sort-state)
                       (balances-shared/sort-balances-by-column search-filtered-rows
                                                                (:column sort-state)
                                                                (:direction sort-state))
                       search-filtered-rows)
         expanded-row-id (:balances mobile-expanded-card)
         decorate (fn [idx row]
                    (assoc row
                           :available-balance-tooltip-position (if (zero? idx) :bottom :top)
                           ;; A disabled move's reason is two lines tall, so
                           ;; it opens downward on the first two rows.
                           :move-reason-position (if (< idx 2) :bottom :top)
                           :location-chip (location-filter/chip-location row core-chips?)))]
     (if (seq search-filtered-rows)
       [:div (cond-> {:class ["flex" "h-full" "min-h-0" "flex-col"]}
               core-chips? (assoc :style {:--balances-coin-min "116px"}))
        (balances-desktop/balance-table-header sort-state read-only? ["hidden" "lg:grid"])
        (into [:div {:class ["hidden"
                             "lg:block"
                             "flex-1"
                             "min-h-0"
                             "min-w-0"
                             "overflow-auto"
                             "scrollbar-hide"]
                    :data-role "account-tab-rows-viewport"}]
              (map-indexed (fn [idx row]
                             ^{:key (:key row)}
                             (balances-desktop/balance-row (decorate idx row)
                                                           {:read-only? read-only?}))
                           sorted-rows))
        (location-filter/hyperevm-note :desktop note-visible? note-opts)
        (location-filter/hyperevm-note :mobile note-visible? note-opts)
        (into [:div {:class ["lg:hidden"
                             "flex-1"
                             "min-h-0"
                             "overflow-y-auto"
                             "scrollbar-hide"
                             "space-y-2.5"
                             "px-2.5"
                             "pt-2"
                             "pb-[calc(6rem+env(safe-area-inset-bottom))]"]
                    :data-role "balances-mobile-cards-viewport"}]
              (map-indexed (fn [idx row]
                             ^{:key (str "mobile-" (:key row))}
                             (balances-mobile/mobile-balance-card expanded-row-id
                                                                  (decorate idx row)
                                                                  {:read-only? read-only?}))
                           sorted-rows))]
       (balances-empty-state location-filter*
                             location-rows
                             visible-rows
                             hyperevm-status
                             (when note-visible?
                               (location-filter/hyperevm-note :any true note-opts)))))))
