(ns hyperopen.account.history.actions
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.account.history.position-reduce :as position-reduce]
            [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.domain.funding-history :as funding-history]
            [hyperopen.platform :as platform]
            [hyperopen.utils.parse :as parse-utils]))

(def ^:private order-history-page-size-options
  #{25 50 100})

(def ^:private default-order-history-page-size
  50)

(def ^:private order-history-freshness-ttl-ms
  60000)

(def ^:private open-orders-sortable-columns
  #{"Time" "Type" "Coin" "Direction" "Size" "Original Size" "Order Value" "Price"})

(def ^:private open-orders-sort-directions
  #{:asc :desc})

(def ^:private open-orders-direction-filter-options
  #{:all :long :short})

(def ^:private positions-direction-filter-options
  #{:all :long :short})

(def ^:private trade-history-direction-filter-options
  #{:all :long :short})

(def ^:private order-history-status-options
  #{:all :long :short})

(def ^:private account-info-coin-search-tabs
  #{:balances :positions :open-orders :trade-history :order-history})

(defn- default-funding-history-filters []
  (let [now (platform/now-ms)]
    (funding-history/normalize-funding-history-filters
     {:start-time-ms 0
      :end-time-ms now}
     now)))

(defn default-funding-history-state []
  (let [filters (default-funding-history-filters)]
    {:filters filters
     :draft-filters filters
     :sort {:column "Time" :direction :desc}
     :filter-open? false
     :coin-search ""
     :coin-suggestions-open? false
     :page-size default-order-history-page-size
     :page 1
     :page-input "1"
     :loading? false
     :error nil
     :request-id 0}))

(defn default-order-history-state []
  {:sort {:column "Time" :direction :desc}
   :status-filter :all
   :coin-search ""
   :filter-open? false
   :page-size default-order-history-page-size
   :page 1
   :page-input "1"
   :loading? false
   :error nil
   :request-id 0
   :loaded-at-ms nil
   :loaded-for-address nil})

(defn default-trade-history-state []
  {:sort {:column "Time" :direction :desc}
   :direction-filter :all
   :coin-search ""
   :filter-open? false
   :page-size default-order-history-page-size
   :page 1
   :page-input "1"})

(defn normalize-order-history-page-size
  ([value]
   (normalize-order-history-page-size value nil))
  ([value locale]
   (let [candidate (parse-utils/parse-localized-int-value value locale)]
    (if (contains? order-history-page-size-options candidate)
      candidate
      default-order-history-page-size))))

(defn normalize-order-history-page
  ([value]
   (normalize-order-history-page value nil nil))
  ([value max-page]
   (normalize-order-history-page value max-page nil))
  ([value max-page locale]
   (let [candidate (max 1 (or (parse-utils/parse-localized-int-value value locale) 1))
         max-page* (when (some? max-page)
                     (max 1 (or (parse-utils/parse-localized-int-value max-page locale) 1)))]
     (if max-page*
       (min candidate max-page*)
       candidate))))

(defn restore-open-orders-sort-settings! [store]
  (let [stored-column (or (platform/local-storage-get "open-orders-sort-by") "Time")
        stored-direction (keyword (or (platform/local-storage-get "open-orders-sort-direction") "desc"))
        column (if (contains? open-orders-sortable-columns stored-column)
                 stored-column
                 "Time")
        direction (if (contains? open-orders-sort-directions stored-direction)
                    stored-direction
                    :desc)]
    (swap! store update-in [:account-info] merge {:open-orders-sort {:column column
                                                                     :direction direction}})))

(defn restore-order-history-pagination-settings! [store]
  (let [page-size (normalize-order-history-page-size
                   (platform/local-storage-get "order-history-page-size"))]
    (swap! store update-in [:account-info :order-history] merge
           {:page-size page-size
            :page 1
            :page-input "1"})))

(defn restore-funding-history-pagination-settings! [store]
  (let [page-size (normalize-order-history-page-size
                   (platform/local-storage-get "funding-history-page-size"))]
    (swap! store update-in [:account-info :funding-history] merge
           {:page-size page-size
            :page 1
            :page-input "1"})))

(defn restore-trade-history-pagination-settings! [store]
  (let [page-size (normalize-order-history-page-size
                   (platform/local-storage-get "trade-history-page-size"))]
    (swap! store update-in [:account-info :trade-history] merge
           {:page-size page-size
            :page 1
            :page-input "1"})))

(defn- parse-datetime-local-ms
  [value]
  (let [text (str/trim (str (or value "")))]
    (when (seq text)
      (let [parsed (.parse js/Date text)]
        (when (and (number? parsed)
                   (not (js/isNaN parsed)))
          (js/Math.floor parsed))))))

(defn funding-history-filters
  [state]
  (funding-history/normalize-funding-history-filters
   (get-in state [:account-info :funding-history :filters])
   (platform/now-ms)))

(defn- funding-history-draft-filters
  [state]
  (funding-history/normalize-funding-history-filters
   (or (get-in state [:account-info :funding-history :draft-filters])
       (funding-history-filters state))
   (platform/now-ms)))

(defn funding-history-request-id
  [state]
  (get-in state [:account-info :funding-history :request-id] 0))

(defn order-history-request-id
  [state]
  (get-in state [:account-info :order-history :request-id] 0))

(defn- normalize-address
  [address]
  (let [text (some-> address str str/trim str/lower-case)]
    (when (seq text)
      text)))

(defn- order-history-fresh?
  [state now-ms]
  (let [loaded-at-ms (get-in state [:account-info :order-history :loaded-at-ms])
        loaded-for-address (normalize-address
                            (get-in state [:account-info :order-history :loaded-for-address]))
        effective-address (normalize-address (account-context/effective-account-address state))
        error (get-in state [:account-info :order-history :error])]
    (and (number? loaded-at-ms)
         (number? now-ms)
         (some? effective-address)
         (= effective-address loaded-for-address)
         (nil? error)
         (let [age-ms (- now-ms loaded-at-ms)]
           (<= 0 age-ms order-history-freshness-ttl-ms)))))

(defn- normalize-order-history-status-filter
  [status]
  (let [status* (cond
                  (keyword? status) status
                  (string? status) (keyword (str/lower-case status))
                  :else :all)]
    (if (contains? order-history-status-options status*)
      status*
      :all)))

(defn- normalize-account-info-tab
  [tab]
  (let [tab* (cond
               (keyword? tab) tab
               (string? tab) (keyword (str/lower-case tab))
               :else :balances)]
    (if (contains? account-info-coin-search-tabs tab*)
      tab*
      :balances)))

(defn- normalize-coin-search-value
  [value]
  (if (string? value)
    value
    (str (or value ""))))

(defn- normalize-open-orders-direction-filter
  [direction-filter]
  (let [direction* (cond
                     (keyword? direction-filter) direction-filter
                     (string? direction-filter) (keyword (str/lower-case direction-filter))
                     :else :all)]
    (if (contains? open-orders-direction-filter-options direction*)
      direction*
      :all)))

(defn- normalize-positions-direction-filter
  [direction-filter]
  (let [direction* (cond
                     (keyword? direction-filter) direction-filter
                     (string? direction-filter) (keyword (str/lower-case direction-filter))
                     :else :all)]
    (if (contains? positions-direction-filter-options direction*)
      direction*
      :all)))

(defn- normalize-trade-history-direction-filter
  [direction-filter]
  (let [direction* (cond
                     (keyword? direction-filter) direction-filter
                     (string? direction-filter) (keyword (str/lower-case direction-filter))
                     :else :all)]
    (if (contains? trade-history-direction-filter-options direction*)
      direction*
      :all)))

(defn- filtered-funding-rows
  [state filters]
  (funding-history/filter-funding-history-rows
   (get-in state [:orders :fundings-raw] [])
   filters))

(defn select-account-info-tab [state tab]
  (cond
    (= tab :funding-history)
    (let [filters (funding-history-filters state)
          request-id (inc (funding-history-request-id state))
          projected (filtered-funding-rows state filters)]
      [[:effects/save-many [[[:account-info :selected-tab] tab]
                            [[:account-info :funding-history :filters] filters]
                            [[:account-info :funding-history :draft-filters] filters]
                            [[:account-info :funding-history :coin-search] ""]
                            [[:account-info :funding-history :coin-suggestions-open?] false]
                            [[:account-info :funding-history :loading?] true]
                            [[:account-info :funding-history :error] nil]
                            [[:account-info :funding-history :request-id] request-id]
                            [[:orders :fundings] projected]]]
       [:effects/api-fetch-user-funding-history request-id]])

    (= tab :order-history)
    (let [order-history-loading? (true? (get-in state [:account-info :order-history :loading?]))
          should-fetch? (and (not order-history-loading?)
                             (not (order-history-fresh? state (platform/now-ms))))]
      (if should-fetch?
        (let [request-id (inc (order-history-request-id state))]
          [[:effects/save-many [[[:account-info :selected-tab] tab]
                                [[:account-info :order-history :loading?] true]
                                [[:account-info :order-history :error] nil]
                                [[:account-info :order-history :request-id] request-id]]]
           [:effects/api-fetch-historical-orders request-id]])
        [[:effects/save [:account-info :selected-tab] tab]]))

    :else
    [[:effects/save [:account-info :selected-tab] tab]]))

(defn set-funding-history-filters [_state path value]
  (let [path* (if (vector? path) path [path])
        full-path (into [:account-info :funding-history] path*)
        value* (case path*
                 [:draft-filters :start-time-ms] (parse-datetime-local-ms value)
                 [:draft-filters :end-time-ms] (parse-datetime-local-ms value)
                 [:filters :start-time-ms] (parse-datetime-local-ms value)
                 [:filters :end-time-ms] (parse-datetime-local-ms value)
                 [:coin-search] (normalize-coin-search-value value)
                 [:coin-suggestions-open?] (boolean value)
                 value)]
    [[:effects/save full-path value*]]))

(defn toggle-funding-history-filter-open [state]
  (let [open? (boolean (get-in state [:account-info :funding-history :filter-open?]))
        filters (funding-history-filters state)
        draft-filters (if open?
                        (funding-history-draft-filters state)
                        filters)]
    [[:effects/save-many [[[:account-info :funding-history :filter-open?] (not open?)]
                          [[:account-info :funding-history :draft-filters] draft-filters]
                          [[:account-info :funding-history :coin-search] ""]
                          [[:account-info :funding-history :coin-suggestions-open?] false]]]]))

(defn- normalize-funding-history-filter-coin
  [coin]
  (let [coin* (some-> coin str str/trim)]
    (when (seq coin*)
      coin*)))

(defn toggle-funding-history-filter-coin [state coin]
  (if-let [coin* (normalize-funding-history-filter-coin coin)]
    (let [draft-filters (funding-history-draft-filters state)
          current-set (or (:coin-set draft-filters) #{})
          next-set (if (contains? current-set coin*)
                     (disj current-set coin*)
                     (conj current-set coin*))]
      [[:effects/save [:account-info :funding-history :draft-filters]
        (assoc draft-filters :coin-set next-set)]])
    []))

(defn add-funding-history-filter-coin [state coin]
  (if-let [coin* (normalize-funding-history-filter-coin coin)]
    (let [draft-filters (funding-history-draft-filters state)
          current-set (or (:coin-set draft-filters) #{})
          next-set (conj current-set coin*)]
      [[:effects/save-many [[[:account-info :funding-history :draft-filters]
                             (assoc draft-filters :coin-set next-set)]
                            [[:account-info :funding-history :coin-search] ""]
                            [[:account-info :funding-history :coin-suggestions-open?] true]]]])
    []))

(defn handle-funding-history-coin-search-keydown [state key top-coin]
  (cond
    (= key "Enter")
    (add-funding-history-filter-coin state top-coin)

    (= key "Escape")
    [[:effects/save [:account-info :funding-history :coin-suggestions-open?] false]]

    :else
    []))

(defn reset-funding-history-filter-draft [state]
  (let [filters (funding-history-filters state)]
    [[:effects/save-many [[[:account-info :funding-history :draft-filters] filters]
                          [[:account-info :funding-history :filter-open?] false]
                          [[:account-info :funding-history :coin-search] ""]
                          [[:account-info :funding-history :coin-suggestions-open?] false]]]]))

(defn apply-funding-history-filters [state]
  (let [current-filters (funding-history-filters state)
        draft-filters (funding-history-draft-filters state)
        time-range-changed?
        (not= (select-keys current-filters [:start-time-ms :end-time-ms])
              (select-keys draft-filters [:start-time-ms :end-time-ms]))
        projected (filtered-funding-rows state draft-filters)
        request-id (inc (funding-history-request-id state))
        base-effects [[:effects/save-many [[[:account-info :funding-history :filters] draft-filters]
                                           [[:account-info :funding-history :draft-filters] draft-filters]
                                           [[:account-info :funding-history :filter-open?] false]
                                           [[:account-info :funding-history :coin-search] ""]
                                           [[:account-info :funding-history :coin-suggestions-open?] false]
                                           [[:account-info :funding-history :page] 1]
                                           [[:account-info :funding-history :page-input] "1"]
                                           [[:orders :fundings] projected]]]]]
    (if time-range-changed?
      (into base-effects
            [[:effects/save-many [[[:account-info :funding-history :loading?] true]
                                  [[:account-info :funding-history :error] nil]
                                  [[:account-info :funding-history :request-id] request-id]]]
             [:effects/api-fetch-user-funding-history request-id]])
      base-effects)))

(defn view-all-funding-history [state]
  (let [current-filters (funding-history-filters state)
        next-filters (assoc current-filters
                            :start-time-ms 0
                            :end-time-ms (platform/now-ms))
        projected (filtered-funding-rows state next-filters)
        request-id (inc (funding-history-request-id state))]
    [[:effects/save-many [[[:account-info :funding-history :filters] next-filters]
                          [[:account-info :funding-history :draft-filters] next-filters]
                          [[:account-info :funding-history :filter-open?] false]
                          [[:account-info :funding-history :coin-search] ""]
                          [[:account-info :funding-history :coin-suggestions-open?] false]
                          [[:account-info :funding-history :page] 1]
                          [[:account-info :funding-history :page-input] "1"]
                          [[:account-info :funding-history :loading?] true]
                          [[:account-info :funding-history :error] nil]
                          [[:account-info :funding-history :request-id] request-id]
                          [[:orders :fundings] projected]]]
     [:effects/api-fetch-user-funding-history request-id]]))

(defn export-funding-history-csv [state]
  (let [filters (funding-history-filters state)
        rows (filtered-funding-rows state filters)]
    [[:effects/export-funding-history-csv rows]]))

(defn sort-positions [state column]
  (let [current-sort (get-in state [:account-info :positions-sort])
        current-column (:column current-sort)
        current-direction (:direction current-sort)
        new-direction (if (and (= current-column column) (= current-direction :asc))
                        :desc
                        :asc)]
    [[:effects/save [:account-info :positions-sort] {:column column :direction new-direction}]]))

(defn sort-balances [state column]
  (let [current-sort (get-in state [:account-info :balances-sort])
        current-column (:column current-sort)
        current-direction (:direction current-sort)
        new-direction (if (and (= current-column column) (= current-direction :asc))
                        :desc
                        :asc)]
    [[:effects/save [:account-info :balances-sort] {:column column :direction new-direction}]]))

(defn sort-open-orders [state column]
  (let [current-sort (get-in state [:account-info :open-orders-sort])
        current-column (:column current-sort)
        current-direction (:direction current-sort)
        new-direction (if (= current-column column)
                        (if (= current-direction :asc) :desc :asc)
                        (if (contains? #{"Time" "Size" "Original Size" "Order Value" "Price"} column)
                          :desc
                          :asc))]
    [[:effects/save [:account-info :open-orders-sort] {:column column
                                                       :direction new-direction}]
     [:effects/local-storage-set "open-orders-sort-by" column]
     [:effects/local-storage-set "open-orders-sort-direction" (name new-direction)]]))

(defn toggle-open-orders-direction-filter-open [state]
  (let [open? (boolean (get-in state [:account-info :open-orders :filter-open?]))]
    [[:effects/save [:account-info :open-orders :filter-open?] (not open?)]]))

(defn set-open-orders-direction-filter [_state direction-filter]
  (let [direction* (normalize-open-orders-direction-filter direction-filter)]
    [[:effects/save-many [[[:account-info :open-orders :direction-filter] direction*]
                          [[:account-info :open-orders :filter-open?] false]]]]))

(defn toggle-positions-direction-filter-open [state]
  (let [open? (boolean (get-in state [:account-info :positions :filter-open?]))]
    [[:effects/save [:account-info :positions :filter-open?] (not open?)]]))

(defn set-positions-direction-filter [_state direction-filter]
  (let [direction* (normalize-positions-direction-filter direction-filter)]
    [[:effects/save-many [[[:account-info :positions :direction-filter] direction*]
                          [[:account-info :positions :filter-open?] false]]]]))

(defn sort-funding-history [state column]
  (let [current-sort (get-in state
                             [:account-info :funding-history :sort]
                             {:column "Time" :direction :desc})
        current-column (:column current-sort)
        current-direction (:direction current-sort)
        new-direction (if (= current-column column)
                        (if (= current-direction :asc) :desc :asc)
                        (if (contains? #{"Time" "Size" "Payment" "Rate"} column)
                          :desc
                          :asc))]
    [[:effects/save-many [[[:account-info :funding-history :sort]
                           {:column column :direction new-direction}]
                          [[:account-info :funding-history :page] 1]
                          [[:account-info :funding-history :page-input] "1"]]]]))

(defn set-funding-history-page-size [state page-size]
  (let [locale (get-in state [:ui :locale])
        page-size* (normalize-order-history-page-size page-size locale)]
    [[:effects/save-many [[[:account-info :funding-history :page-size] page-size*]
                          [[:account-info :funding-history :page] 1]
                          [[:account-info :funding-history :page-input] "1"]]]
     [:effects/local-storage-set "funding-history-page-size" (str page-size*)]]))

(defn set-funding-history-page [state page max-page]
  (let [locale (get-in state [:ui :locale])
        page* (normalize-order-history-page page max-page locale)]
    [[:effects/save-many [[[:account-info :funding-history :page] page*]
                          [[:account-info :funding-history :page-input] (str page*)]]]]))

(defn next-funding-history-page [state max-page]
  (let [current-page (get-in state [:account-info :funding-history :page] 1)]
    (set-funding-history-page state (inc current-page) max-page)))

(defn prev-funding-history-page [state max-page]
  (let [current-page (get-in state [:account-info :funding-history :page] 1)]
    (set-funding-history-page state (dec current-page) max-page)))

(defn set-funding-history-page-input [_state input-value]
  [[:effects/save [:account-info :funding-history :page-input]
    (if (string? input-value)
      input-value
      (str (or input-value "")))]] )

(defn apply-funding-history-page-input [state max-page]
  (let [raw-value (get-in state [:account-info :funding-history :page-input] "")
        locale (get-in state [:ui :locale])
        page* (normalize-order-history-page raw-value max-page locale)]
    [[:effects/save-many [[[:account-info :funding-history :page] page*]
                          [[:account-info :funding-history :page-input] (str page*)]]]]))

(defn handle-funding-history-page-input-keydown [state key max-page]
  (if (= key "Enter")
    (apply-funding-history-page-input state max-page)
    []))

(defn set-trade-history-page-size [state page-size]
  (let [locale (get-in state [:ui :locale])
        page-size* (normalize-order-history-page-size page-size locale)]
    [[:effects/save-many [[[:account-info :trade-history :page-size] page-size*]
                          [[:account-info :trade-history :page] 1]
                          [[:account-info :trade-history :page-input] "1"]]]
     [:effects/local-storage-set "trade-history-page-size" (str page-size*)]]))

(defn set-trade-history-page [state page max-page]
  (let [locale (get-in state [:ui :locale])
        page* (normalize-order-history-page page max-page locale)]
    [[:effects/save-many [[[:account-info :trade-history :page] page*]
                          [[:account-info :trade-history :page-input] (str page*)]]]]))

(defn next-trade-history-page [state max-page]
  (let [current-page (get-in state [:account-info :trade-history :page] 1)]
    (set-trade-history-page state (inc current-page) max-page)))

(defn prev-trade-history-page [state max-page]
  (let [current-page (get-in state [:account-info :trade-history :page] 1)]
    (set-trade-history-page state (dec current-page) max-page)))

(defn set-trade-history-page-input [_state input-value]
  [[:effects/save [:account-info :trade-history :page-input]
    (if (string? input-value)
      input-value
      (str (or input-value "")))]] )

(defn apply-trade-history-page-input [state max-page]
  (let [raw-value (get-in state [:account-info :trade-history :page-input] "")
        locale (get-in state [:ui :locale])
        page* (normalize-order-history-page raw-value max-page locale)]
    [[:effects/save-many [[[:account-info :trade-history :page] page*]
                          [[:account-info :trade-history :page-input] (str page*)]]]]))

(defn handle-trade-history-page-input-keydown [state key max-page]
  (if (= key "Enter")
    (apply-trade-history-page-input state max-page)
    []))

(defn sort-trade-history [state column]
  (let [current-sort (get-in state
                             [:account-info :trade-history :sort]
                             {:column "Time" :direction :desc})
        current-column (:column current-sort)
        current-direction (:direction current-sort)
        desc-columns #{"Time" "Price" "Size" "Trade Value" "Fee" "Closed PNL"}
        new-direction (if (= current-column column)
                        (if (= current-direction :asc) :desc :asc)
                        (if (contains? desc-columns column)
                          :desc
                          :asc))]
    [[:effects/save-many [[[:account-info :trade-history :sort]
                           {:column column :direction new-direction}]
                          [[:account-info :trade-history :page] 1]
                          [[:account-info :trade-history :page-input] "1"]]]]))

(defn toggle-trade-history-direction-filter-open [state]
  (let [open? (boolean (get-in state [:account-info :trade-history :filter-open?]))]
    [[:effects/save [:account-info :trade-history :filter-open?] (not open?)]]))

(defn set-trade-history-direction-filter [_state direction-filter]
  (let [direction* (normalize-trade-history-direction-filter direction-filter)]
    [[:effects/save-many [[[:account-info :trade-history :direction-filter] direction*]
                          [[:account-info :trade-history :filter-open?] false]
                          [[:account-info :trade-history :page] 1]
                          [[:account-info :trade-history :page-input] "1"]]]]))

(defn sort-order-history [state column]
  (let [current-sort (get-in state
                             [:account-info :order-history :sort]
                             {:column "Time" :direction :desc})
        current-column (:column current-sort)
        current-direction (:direction current-sort)
        desc-columns #{"Time" "Size" "Filled Size" "Order Value" "Price" "Order ID"}
        new-direction (if (= current-column column)
                        (if (= current-direction :asc) :desc :asc)
                        (if (contains? desc-columns column)
                          :desc
                          :asc))]
    [[:effects/save-many [[[:account-info :order-history :sort]
                           {:column column :direction new-direction}]
                          [[:account-info :order-history :page] 1]
                          [[:account-info :order-history :page-input] "1"]]]]))

(defn toggle-order-history-filter-open [state]
  (let [open? (boolean (get-in state [:account-info :order-history :filter-open?]))]
    [[:effects/save [:account-info :order-history :filter-open?] (not open?)]]))

(defn set-order-history-status-filter [_state status-filter]
  (let [status* (normalize-order-history-status-filter status-filter)]
    [[:effects/save-many [[[:account-info :order-history :status-filter] status*]
                          [[:account-info :order-history :filter-open?] false]
                          [[:account-info :order-history :page] 1]
                          [[:account-info :order-history :page-input] "1"]]]]))

(defn set-account-info-coin-search [_state tab search-value]
  (let [tab* (normalize-account-info-tab tab)
        search* (normalize-coin-search-value search-value)]
    (case tab*
      :positions
      [[:effects/save [:account-info :positions :coin-search] search*]]

      :open-orders
      [[:effects/save [:account-info :open-orders :coin-search] search*]]

      :trade-history
      [[:effects/save-many [[[:account-info :trade-history :coin-search] search*]
                            [[:account-info :trade-history :page] 1]
                            [[:account-info :trade-history :page-input] "1"]]]]

      :order-history
      [[:effects/save-many [[[:account-info :order-history :coin-search] search*]
                            [[:account-info :order-history :page] 1]
                            [[:account-info :order-history :page-input] "1"]]]]

      [[:effects/save [:account-info :balances-coin-search] search*]])))

(defn set-order-history-page-size [state page-size]
  (let [locale (get-in state [:ui :locale])
        page-size* (normalize-order-history-page-size page-size locale)]
    [[:effects/save-many [[[:account-info :order-history :page-size] page-size*]
                          [[:account-info :order-history :page] 1]
                          [[:account-info :order-history :page-input] "1"]]]
     [:effects/local-storage-set "order-history-page-size" (str page-size*)]]))

(defn set-order-history-page [state page max-page]
  (let [locale (get-in state [:ui :locale])
        page* (normalize-order-history-page page max-page locale)]
    [[:effects/save-many [[[:account-info :order-history :page] page*]
                          [[:account-info :order-history :page-input] (str page*)]]]]))

(defn next-order-history-page [state max-page]
  (let [current-page (get-in state [:account-info :order-history :page] 1)]
    (set-order-history-page state (inc current-page) max-page)))

(defn prev-order-history-page [state max-page]
  (let [current-page (get-in state [:account-info :order-history :page] 1)]
    (set-order-history-page state (dec current-page) max-page)))

(defn set-order-history-page-input [_state input-value]
  [[:effects/save [:account-info :order-history :page-input]
    (if (string? input-value)
      input-value
      (str (or input-value "")))]] )

(defn apply-order-history-page-input [state max-page]
  (let [raw-value (get-in state [:account-info :order-history :page-input] "")
        locale (get-in state [:ui :locale])
        page* (normalize-order-history-page raw-value max-page locale)]
    [[:effects/save-many [[[:account-info :order-history :page] page*]
                          [[:account-info :order-history :page-input] (str page*)]]]]))

(defn handle-order-history-page-input-keydown [state key max-page]
  (if (= key "Enter")
    (apply-order-history-page-input state max-page)
    []))

(defn refresh-order-history [state]
  (let [request-id (inc (order-history-request-id state))
        selected? (= :order-history (get-in state [:account-info :selected-tab]))]
    [[:effects/save-many [[[:account-info :order-history :request-id] request-id]
                          [[:account-info :order-history :loading?] selected?]
                          [[:account-info :order-history :error] nil]]]
     [:effects/api-fetch-historical-orders request-id]]))

(defn set-hide-small-balances [_state checked]
  [[:effects/save [:account-info :hide-small-balances?] checked]])

(defn open-position-tpsl-modal
  ([state position-data]
   [[:effects/save-many [[[:positions-ui :tpsl-modal]
                          (assoc (position-tpsl/from-position-row position-data)
                                 :locale (get-in state [:ui :locale]))]
                         [[:positions-ui :reduce-popover]
                          (position-reduce/default-popover-state)]
                         [[:positions-ui :margin-modal]
                          (position-margin/default-modal-state)]]]])
  ([state position-data trigger-bounds]
   [[:effects/save-many [[[:positions-ui :tpsl-modal]
                          (assoc (position-tpsl/from-position-row position-data trigger-bounds)
                                 :locale (get-in state [:ui :locale]))]
                         [[:positions-ui :reduce-popover]
                          (position-reduce/default-popover-state)]
                         [[:positions-ui :margin-modal]
                          (position-margin/default-modal-state)]]]]))

(defn close-position-tpsl-modal [_state]
  [[:effects/save [:positions-ui :tpsl-modal]
    (position-tpsl/default-modal-state)]])

(defn handle-position-tpsl-modal-keydown [state key]
  (if (= key "Escape")
    (close-position-tpsl-modal state)
    []))

(defn set-position-tpsl-modal-field [state path value]
  (let [locale (get-in state [:ui :locale])
        modal (assoc (or (get-in state [:positions-ui :tpsl-modal])
                         (position-tpsl/default-modal-state))
                     :locale locale)
        path* (if (vector? path) path [path])]
    [[:effects/save [:positions-ui :tpsl-modal]
      (position-tpsl/set-modal-field modal path* value)]]))

(defn set-position-tpsl-configure-amount [state checked]
  (let [modal (assoc (or (get-in state [:positions-ui :tpsl-modal])
                         (position-tpsl/default-modal-state))
                     :locale (get-in state [:ui :locale]))]
    [[:effects/save [:positions-ui :tpsl-modal]
      (position-tpsl/set-configure-amount modal checked)]]))

(defn set-position-tpsl-limit-price [state checked]
  (let [modal (assoc (or (get-in state [:positions-ui :tpsl-modal])
                         (position-tpsl/default-modal-state))
                     :locale (get-in state [:ui :locale]))]
    [[:effects/save [:positions-ui :tpsl-modal]
      (position-tpsl/set-limit-price modal checked)]]))

(defn trigger-close-all-positions [_state]
  [])

(defn open-position-reduce-popover
  ([state position-data]
   [[:effects/save-many [[[:positions-ui :reduce-popover]
                          (assoc (position-reduce/from-position-row position-data)
                                 :locale (get-in state [:ui :locale]))]
                         [[:positions-ui :tpsl-modal]
                          (position-tpsl/default-modal-state)]
                         [[:positions-ui :margin-modal]
                          (position-margin/default-modal-state)]]]])
  ([state position-data trigger-bounds]
   [[:effects/save-many [[[:positions-ui :reduce-popover]
                          (assoc (position-reduce/from-position-row position-data trigger-bounds)
                                 :locale (get-in state [:ui :locale]))]
                         [[:positions-ui :tpsl-modal]
                          (position-tpsl/default-modal-state)]
                         [[:positions-ui :margin-modal]
                          (position-margin/default-modal-state)]]]]))

(defn close-position-reduce-popover [_state]
  [[:effects/save [:positions-ui :reduce-popover]
    (position-reduce/default-popover-state)]])

(defn handle-position-reduce-popover-keydown [state key]
  (if (= key "Escape")
    (close-position-reduce-popover state)
    []))

(defn set-position-reduce-popover-field [state path value]
  (let [popover (assoc (or (get-in state [:positions-ui :reduce-popover])
                           (position-reduce/default-popover-state))
                       :locale (get-in state [:ui :locale]))
        path* (if (vector? path) path [path])]
    [[:effects/save [:positions-ui :reduce-popover]
      (position-reduce/set-popover-field popover path* value)]]))

(defn set-position-reduce-size-percent [state percent]
  (let [popover (assoc (or (get-in state [:positions-ui :reduce-popover])
                           (position-reduce/default-popover-state))
                       :locale (get-in state [:ui :locale]))]
    [[:effects/save [:positions-ui :reduce-popover]
      (position-reduce/set-size-percent popover percent)]]))

(defn set-position-reduce-limit-price-to-mid [state]
  (let [popover (assoc (or (get-in state [:positions-ui :reduce-popover])
                           (position-reduce/default-popover-state))
                       :locale (get-in state [:ui :locale]))]
    [[:effects/save [:positions-ui :reduce-popover]
      (position-reduce/set-limit-price-to-mid popover)]]))

(defn submit-position-reduce-close [state]
  (let [popover (assoc (or (get-in state [:positions-ui :reduce-popover])
                           (position-reduce/default-popover-state))
                       :locale (get-in state [:ui :locale]))
        result (position-reduce/prepare-submit state popover)]
    (if-not (:ok? result)
      [[:effects/save [:positions-ui :reduce-popover]
        (assoc popover :error (:display-message result))]]
      [[:effects/save [:positions-ui :reduce-popover]
        (assoc popover :error nil)]
       [:effects/api-submit-order (:request result)]])))

(defn open-position-margin-modal
  ([state position-data]
   [[:effects/save-many [[[:positions-ui :margin-modal]
                          (position-margin/from-position-row state position-data)]
                         [[:positions-ui :tpsl-modal]
                          (position-tpsl/default-modal-state)]
                         [[:positions-ui :reduce-popover]
                          (position-reduce/default-popover-state)]]]])
  ([state position-data trigger-bounds]
   [[:effects/save-many [[[:positions-ui :margin-modal]
                          (position-margin/from-position-row state position-data trigger-bounds)]
                         [[:positions-ui :tpsl-modal]
                          (position-tpsl/default-modal-state)]
                         [[:positions-ui :reduce-popover]
                          (position-reduce/default-popover-state)]]]]))

(defn close-position-margin-modal [_state]
  [[:effects/save [:positions-ui :margin-modal]
    (position-margin/default-modal-state)]])

(defn handle-position-margin-modal-keydown [state key]
  (if (= key "Escape")
    (close-position-margin-modal state)
    []))

(defn set-position-margin-modal-field [state path value]
  (let [modal (assoc (or (get-in state [:positions-ui :margin-modal])
                         (position-margin/default-modal-state))
                     :locale (get-in state [:ui :locale]))
        path* (if (vector? path) path [path])]
    [[:effects/save [:positions-ui :margin-modal]
      (position-margin/set-modal-field modal path* value)]]))

(defn set-position-margin-amount-percent [state percent]
  (let [modal (assoc (or (get-in state [:positions-ui :margin-modal])
                         (position-margin/default-modal-state))
                     :locale (get-in state [:ui :locale]))]
    [[:effects/save [:positions-ui :margin-modal]
      (position-margin/set-amount-percent modal percent)]]))

(defn set-position-margin-amount-to-max [state]
  (let [modal (assoc (or (get-in state [:positions-ui :margin-modal])
                         (position-margin/default-modal-state))
                     :locale (get-in state [:ui :locale]))]
    [[:effects/save [:positions-ui :margin-modal]
      (position-margin/set-amount-to-max modal)]]))

(defn submit-position-margin-update [state]
  (let [modal (or (get-in state [:positions-ui :margin-modal])
                  (position-margin/default-modal-state))
        result (position-margin/prepare-submit state modal)]
    (if-not (:ok? result)
      [[:effects/save-many [[[:positions-ui :margin-modal :submitting?] false]
                            [[:positions-ui :margin-modal :error] (:display-message result)]]]]
      [[:effects/save-many [[[:positions-ui :margin-modal :submitting?] true]
                            [[:positions-ui :margin-modal :error] nil]]]
       [:effects/api-submit-position-margin (:request result)]])))

(defn submit-position-tpsl [state]
  (let [modal (or (get-in state [:positions-ui :tpsl-modal])
                  (position-tpsl/default-modal-state))
        result (position-tpsl/prepare-submit state modal)]
    (if-not (:ok? result)
      [[:effects/save-many [[[:positions-ui :tpsl-modal :submitting?] false]
                            [[:positions-ui :tpsl-modal :error] (:display-message result)]]]]
      [[:effects/save-many [[[:positions-ui :tpsl-modal :submitting?] true]
                            [[:positions-ui :tpsl-modal :error] nil]]]
       [:effects/api-submit-position-tpsl (:request result)]])))
