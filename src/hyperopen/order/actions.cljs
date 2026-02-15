(ns hyperopen.order.actions
  (:require [clojure.string :as str]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.order.effects :as order-effects]
            [hyperopen.state.trading :as trading]))

(defn- normalize-order-entry-mode [mode]
  (let [candidate (cond
                    (keyword? mode) mode
                    (string? mode) (keyword mode)
                    :else :market)]
    (if (contains? #{:market :limit :pro} candidate)
      candidate
      :market)))

(defn- current-order-form-ui [state]
  (trading/order-form-ui-state state))

(defn select-order-entry-mode [state mode]
  (let [mode* (normalize-order-entry-mode mode)
        form (:order-form state)
        ui-state (current-order-form-ui state)
        close-pro-dropdown? (contains? #{:market :limit} mode*)
        next-type (case mode*
                    :market :market
                    :limit :limit
                    (trading/normalize-pro-order-type (:type form)))
        normalized (trading/normalize-order-form state
                                                 (assoc form
                                                        :entry-mode mode*
                                                        :type next-type))
        next-form (-> (trading/sync-size-from-percent state normalized)
                      (assoc :error nil))
        next-ui (trading/effective-order-form-ui
                 next-form
                 (assoc ui-state
                        :pro-order-type-dropdown-open?
                        (if close-pro-dropdown?
                          false
                          (boolean (:pro-order-type-dropdown-open? ui-state)))))]
    [[:effects/save-many [[[:order-form] next-form]
                          [[:order-form-ui] next-ui]]]]))

(defn select-pro-order-type [state order-type]
  (let [form (:order-form state)
        ui-state (current-order-form-ui state)
        next-type (trading/normalize-pro-order-type order-type)
        normalized (trading/normalize-order-form state
                                                 (assoc form
                                                        :entry-mode :pro
                                                        :type next-type))
        next-form (-> (trading/sync-size-from-percent state normalized)
                      (assoc :error nil))
        next-ui (trading/effective-order-form-ui
                 next-form
                 (assoc ui-state :pro-order-type-dropdown-open? false))]
    [[:effects/save-many [[[:order-form] next-form]
                          [[:order-form-ui] next-ui]]]]))

(defn toggle-pro-order-type-dropdown [state]
  (let [ui-state (current-order-form-ui state)
        open? (boolean (:pro-order-type-dropdown-open? ui-state))]
    [[:effects/save-many [[[:order-form-ui :pro-order-type-dropdown-open?] (not open?)]]]]))

(defn close-pro-order-type-dropdown [_state]
  [[:effects/save-many [[[:order-form-ui :pro-order-type-dropdown-open?] false]]]])

(defn handle-pro-order-type-dropdown-keydown [state key]
  (if (= key "Escape")
    (close-pro-order-type-dropdown state)
    []))

(defn set-order-ui-leverage [state leverage]
  (let [form (:order-form state)
        normalized (trading/normalize-ui-leverage state leverage)
        updated (assoc form :ui-leverage normalized)
        next-form (-> (trading/sync-size-from-percent state updated)
                      (assoc :error nil))]
    [[:effects/save-many [[[:order-form] next-form]]]]))

(defn set-order-size-percent [state percent]
  (let [form (:order-form state)
        next-form (-> (trading/apply-size-percent state form percent)
                      (assoc :error nil))]
    [[:effects/save-many [[[:order-form] next-form]]]]))

(defn set-order-size-display [state value]
  (let [raw-value (str (or value ""))
        form (:order-form state)
        normalized-form (trading/normalize-order-form state form)
        reference-price (trading/reference-price state normalized-form)
        parsed-display-size (trading/parse-num raw-value)
        canonical-size (when (and (number? parsed-display-size)
                                  (pos? parsed-display-size)
                                  (number? reference-price)
                                  (pos? reference-price))
                         (trading/base-size-string state (/ parsed-display-size reference-price)))
        updated (assoc form
                       :size-display raw-value
                       :size (or canonical-size ""))
        next-form (cond
                    (str/blank? raw-value)
                    (assoc updated :size "" :size-percent 0)

                    (seq canonical-size)
                    (trading/sync-size-percent-from-size state updated)

                    :else
                    (assoc updated :size-percent 0))
        next-form* (assoc next-form :error nil)]
    [[:effects/save-many [[[:order-form] next-form*]]]]))

(defn focus-order-price-input [state]
  (let [form (:order-form state)
        ui-state (current-order-form-ui state)
        normalized-form (trading/normalize-order-form state form)
        raw-price (or (:price normalized-form) "")
        fallback-price (when (and (trading/limit-like-type? (:type normalized-form))
                                  (str/blank? raw-price))
                         (trading/effective-limit-price-string state normalized-form))
        should-capture-fallback? (seq fallback-price)
        updated (cond-> form
                  should-capture-fallback? (assoc :price fallback-price))
        next-form (if (and should-capture-fallback?
                           (pos? (or (trading/parse-num (:size-percent updated)) 0)))
                    (trading/sync-size-from-percent state updated)
                    updated)
        next-ui (trading/effective-order-form-ui
                 next-form
                 (assoc ui-state :price-input-focused? true))]
    [[:effects/save-many [[[:order-form] next-form]
                          [[:order-form-ui] next-ui]]]]))

(defn blur-order-price-input [state]
  (let [form (:order-form state)
        ui-state (current-order-form-ui state)
        next-ui (trading/effective-order-form-ui
                 form
                 (assoc ui-state :price-input-focused? false))]
    [[:effects/save-many [[[:order-form-ui] next-ui]]]]))

(defn set-order-price-to-mid [state]
  (let [form (:order-form state)
        normalized-form (trading/normalize-order-form state form)
        mid-price-string (trading/mid-price-string state normalized-form)
        updated (if (seq mid-price-string)
                  (assoc form :price mid-price-string)
                  form)
        next-form (if (and (seq mid-price-string)
                           (pos? (or (trading/parse-num (:size-percent updated)) 0)))
                    (trading/sync-size-from-percent state updated)
                    updated)
        next-form* (assoc next-form :error nil)]
    [[:effects/save-many [[[:order-form] next-form*]]]]))

(defn toggle-order-tpsl-panel [state]
  (let [form (:order-form state)
        ui-state (current-order-form-ui state)
        normalized-form (trading/normalize-order-form state form)]
    (if (= :scale (:type normalized-form))
      []
      (let [next-open? (not (boolean (:tpsl-panel-open? ui-state)))
            next-form (cond-> form
                        (not next-open?) (assoc-in [:tp :enabled?] false)
                        (not next-open?) (assoc-in [:sl :enabled?] false))
            next-form* (assoc next-form :error nil)
            next-ui (trading/effective-order-form-ui
                     next-form*
                     (assoc ui-state :tpsl-panel-open? next-open?))]
        [[:effects/save-many [[[:order-form] next-form*]
                              [[:order-form-ui] next-ui]]]]))))

(defn update-order-form [state path value]
  (let [v (cond
            (= path [:type]) (keyword value)
            (= path [:side]) (keyword value)
            (= path [:tif]) (keyword value)
            :else value)
        form (:order-form state)
        updated (assoc-in form path v)
        next-form (cond
                    (= path [:type])
                    (let [typed (-> updated
                                    (update :type trading/normalize-order-type)
                                    (assoc :entry-mode (trading/entry-mode-for-type (:type updated))))
                          normalized (trading/normalize-order-form state typed)]
                      (trading/sync-size-from-percent state normalized))

                    (= path [:size])
                    (trading/sync-size-percent-from-size state updated)

                    (or (= path [:price]) (= path [:side]))
                    (if (pos? (or (trading/parse-num (:size-percent updated)) 0))
                      (trading/sync-size-from-percent state updated)
                      updated)

                    :else
                    updated)
        next-form* (assoc next-form :error nil)]
    [[:effects/save-many [[[:order-form] next-form*]]]]))

(defn submit-order [state]
  (let [raw-form (:order-form state)
        agent-ready? (= :ready (get-in state [:wallet :agent :status]))
        submit-policy (trading/submit-policy state raw-form {:mode :submit
                                                             :agent-ready? agent-ready?})
        form (:form submit-policy)
        request (:request submit-policy)
        reason (:reason submit-policy)
        error-message (:error-message submit-policy)]
    (cond
      reason
      [[:effects/save [:order-form :error] error-message]]

      :else
      [[:effects/save [:order-form :error] nil]
       [:effects/save [:order-form] form]
       [:effects/api-submit-order request]])))

(defn prune-canceled-open-orders
  [state request]
  (order-effects/prune-canceled-open-orders state request))

(defn cancel-order [state order]
  (let [agent-ready? (= :ready (get-in state [:wallet :agent :status]))
        request (trading-api/build-cancel-order-request state order)]
    (cond
      (not agent-ready?)
      [[:effects/save [:orders :cancel-error] "Enable trading before cancelling orders."]]

      (map? request)
      [[:effects/api-cancel-order request]]

      :else
      [[:effects/save [:orders :cancel-error] "Missing asset or order id."]])))
