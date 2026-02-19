(ns hyperopen.views.trade.order-form-commands-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.trade.order-form-commands :as commands]
            [hyperopen.views.trade.order-form-intent-adapter :as intent-adapter]
            [hyperopen.views.trade.order-form-runtime-gateway :as runtime-gateway]))

(def ^:private representative-command-builders
  [(commands/select-entry-market)
   (commands/select-entry-limit)
   (commands/toggle-pro-order-type-dropdown)
   (commands/close-pro-order-type-dropdown)
   (commands/handle-pro-order-type-dropdown-keydown commands/event-key)
   (commands/toggle-size-unit-dropdown)
   (commands/close-size-unit-dropdown)
   (commands/handle-size-unit-dropdown-keydown commands/event-key)
   (commands/select-pro-order-type :scale)
   (commands/set-order-ui-leverage 25)
   (commands/update-order-form [:side] :buy)
   (commands/set-order-side :sell)
   (commands/set-limit-price-input)
   (commands/set-order-size-display-input)
   (commands/set-order-size-input-mode :base)
   (commands/set-order-size-percent-input)
   (commands/focus-order-price-input)
   (commands/blur-order-price-input)
   (commands/set-order-price-to-mid)
   (commands/toggle-order-tpsl-panel)
   (commands/toggle-reduce-only)
   (commands/toggle-post-only)
   (commands/set-tif-input)
   (commands/set-trigger-price-input)
   (commands/set-scale-start-input)
   (commands/set-scale-end-input)
   (commands/set-scale-count-input)
   (commands/set-scale-skew-input)
   (commands/set-twap-minutes-input)
   (commands/toggle-twap-randomize)
   (commands/toggle-tp-enabled)
   (commands/set-tp-trigger-input)
   (commands/toggle-tp-market)
   (commands/set-tp-limit-input)
   (commands/toggle-sl-enabled)
   (commands/set-sl-trigger-input)
   (commands/toggle-sl-market)
   (commands/set-sl-limit-input)
   (commands/submit-order)])

(deftest order-form-command-builders-return-semantic-intents-test
  (is (= {:command-id :order-form/select-entry-mode
          :args [:market]}
         (commands/select-entry-market)))
  (is (= {:command-id :order-form/set-order-ui-leverage
          :args [25]}
         (commands/set-order-ui-leverage 25)))
  (is (= {:command-id :order-form/update-order-form
          :args [[:side] :sell]}
         (commands/set-order-side :sell))))

(deftest intent-adapter-translates-to-runtime-action-vectors-test
  (is (= [[:actions/select-order-entry-mode :limit]]
         (intent-adapter/command->actions
          (commands/select-entry-limit))))
  (is (= [[:actions/update-order-form [:price] [:event.target/value]]]
         (intent-adapter/command->actions
          (commands/set-limit-price-input))))
  (is (= [[:actions/set-order-size-input-mode :base]]
         (intent-adapter/command->actions
          (commands/set-order-size-input-mode :base))))
  (is (= [[:actions/update-order-form [:reduce-only] [:event.target/checked]]]
         (intent-adapter/command->actions
          (commands/toggle-reduce-only)))))

(deftest runtime-gateway-covers-all-command-builders-test
  (let [supported-ids (runtime-gateway/supported-command-ids)]
    (is (seq supported-ids))
    (doseq [command representative-command-builders]
      (is (contains? supported-ids (:command-id command))
          (str "missing command id in runtime gateway map: " (pr-str command)))
      (let [runtime-actions (intent-adapter/command->actions command)]
        (is (vector? runtime-actions))
        (is (seq runtime-actions))
        (is (= "actions" (namespace (ffirst runtime-actions))))))))

(deftest runtime-gateway-rejects-invalid-command-shapes-test
  (is (thrown-with-msg?
       js/Error
       #"order-form command contract validation failed"
       (intent-adapter/command->actions {:command-id :order-form/select-entry-mode})))
  (is (thrown-with-msg?
       js/Error
       #"order-form command contract validation failed"
       (intent-adapter/command->actions {:command-id :order-form/unknown
                                         :args []}))))
