(ns hyperopen.account.history.position-overlay-actions
  (:require [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.account.history.position-reduce :as position-reduce]
            [hyperopen.account.history.position-tpsl :as position-tpsl]
            [hyperopen.trading-settings :as trading-settings]))

(defn- tpsl-modal-with-locale
  [state]
  (assoc (or (get-in state [:positions-ui :tpsl-modal])
             (position-tpsl/default-modal-state))
         :locale (get-in state [:ui :locale])))

(defn- reduce-popover-with-locale
  [state]
  (assoc (or (get-in state [:positions-ui :reduce-popover])
             (position-reduce/default-popover-state))
         :locale (get-in state [:ui :locale])))

(defn- margin-modal-with-locale
  [state]
  (assoc (or (get-in state [:positions-ui :margin-modal])
             (position-margin/default-modal-state))
         :locale (get-in state [:ui :locale])))

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
  (let [modal (tpsl-modal-with-locale state)
        path* (if (vector? path) path [path])]
    [[:effects/save [:positions-ui :tpsl-modal]
      (position-tpsl/set-modal-field modal path* value)]]))

(defn set-position-tpsl-configure-amount [state checked]
  (let [modal (tpsl-modal-with-locale state)]
    [[:effects/save [:positions-ui :tpsl-modal]
      (position-tpsl/set-configure-amount modal checked)]]))

(defn set-position-tpsl-limit-price [state checked]
  (let [modal (tpsl-modal-with-locale state)]
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
  (let [popover (reduce-popover-with-locale state)
        path* (if (vector? path) path [path])]
    [[:effects/save [:positions-ui :reduce-popover]
      (position-reduce/set-popover-field popover path* value)]]))

(defn set-position-reduce-size-percent [state percent]
  (let [popover (reduce-popover-with-locale state)]
    [[:effects/save [:positions-ui :reduce-popover]
      (position-reduce/set-size-percent popover percent)]]))

(defn set-position-reduce-limit-price-to-mid [state]
  (let [popover (reduce-popover-with-locale state)]
    [[:effects/save [:positions-ui :reduce-popover]
      (position-reduce/set-limit-price-to-mid popover)]]))

(def ^:private confirm-close-position-message
  "Submit this close order?\n\nDisable close-position confirmation in Trading settings if you prefer one-click closes.")

(defn submit-position-reduce-close [state]
  (let [popover (reduce-popover-with-locale state)
        result (position-reduce/prepare-submit state popover)]
    (if-not (:ok? result)
      [[:effects/save [:positions-ui :reduce-popover]
        (assoc popover :error (:display-message result))]]
      (let [next-popover (assoc popover :error nil)]
        (if (trading-settings/confirm-close-position? state)
          [[:effects/confirm-api-submit-order {:variant :close-position
                                               :message confirm-close-position-message
                                               :request (:request result)
                                               :path-values [[[:positions-ui :reduce-popover] next-popover]]}]]
          [[:effects/save [:positions-ui :reduce-popover]
            next-popover]
           [:effects/api-submit-order (:request result)]])))))

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
  (let [modal (margin-modal-with-locale state)
        path* (if (vector? path) path [path])]
    [[:effects/save [:positions-ui :margin-modal]
      (position-margin/set-modal-field modal path* value)]]))

(defn set-position-margin-amount-percent [state percent]
  (let [modal (margin-modal-with-locale state)]
    [[:effects/save [:positions-ui :margin-modal]
      (position-margin/set-amount-percent modal percent)]]))

(defn set-position-margin-amount-to-max [state]
  (let [modal (margin-modal-with-locale state)]
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
