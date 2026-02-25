(ns hyperopen.views.trade.order-form-runtime-gateway-test
  (:require [cljs.test :refer-macros [deftest is]]
            [goog.object :as gobj]
            [hyperopen.schema.order-form-command-catalog :as command-catalog]
            [hyperopen.views.trade.order-form-commands :as commands]
            [hyperopen.views.trade.order-form-intent-adapter :as intent-adapter]
            [hyperopen.views.trade.order-form-runtime-gateway :as runtime-gateway]))

(deftest runtime-gateway-default-and-map-constructors-implement-protocol-test
  (is (satisfies? runtime-gateway/OrderFormRuntimeGateway
                  (runtime-gateway/default-gateway)))
  (is (satisfies? runtime-gateway/OrderFormRuntimeGateway
                  (runtime-gateway/map->DefaultOrderFormRuntimeGateway {})))
  (is (satisfies? runtime-gateway/OrderFormRuntimeGateway
                  (runtime-gateway/map->DefaultOrderFormRuntimeGateway
                   (runtime-gateway/default-gateway)))))

(deftest runtime-gateway-translates-command-ids-and-placeholder-args-test
  (let [command {:command-id :order-form/update-order-form
                 :args [[:price] :order-form.event/target-value]}
        expected [[:actions/update-order-form [:price] [:event.target/value]]]]
    (is (= expected
           (runtime-gateway/command->runtime-actions
            (runtime-gateway/default-gateway)
            command)))
    (is (= expected
           (runtime-gateway/command->runtime-actions
            (runtime-gateway/map->DefaultOrderFormRuntimeGateway {})
            command)))))

(deftest runtime-gateway-supported-command-ids-expose-core-actions-test
  (let [supported-ids (runtime-gateway/supported-command-ids)]
    (is (set? supported-ids))
    (is (= supported-ids
           (command-catalog/supported-command-ids)))
    (is (contains? supported-ids :order-form/select-entry-mode))
    (is (contains? supported-ids :order-form/toggle-size-unit-dropdown))
    (is (contains? supported-ids :order-form/toggle-tpsl-unit-dropdown))
    (is (contains? supported-ids :order-form/toggle-tif-dropdown))
    (is (contains? supported-ids :order-form/set-order-size-input-mode))
    (is (contains? supported-ids :order-form/update-order-form))
    (is (contains? supported-ids :order-form/submit-order))))

(deftest runtime-gateway-rejects-invalid-command-contracts-test
  (is (thrown-with-msg?
       js/Error
       #"order-form command contract validation failed"
       (runtime-gateway/command->runtime-actions
        (runtime-gateway/default-gateway)
        {:command-id :order-form/select-entry-mode})))
  (is (thrown-with-msg?
       js/Error
       #"order-form command contract validation failed"
       (runtime-gateway/command->runtime-actions
        (runtime-gateway/default-gateway)
        {:command-id :order-form/update-order-form
         :args [[:size] :order-form.event/unknown]}))))

(deftest runtime-gateway-missing-protocol-path-throws-test
  (is (thrown-with-msg?
       js/Error
       #"No protocol method"
       (runtime-gateway/command->runtime-actions
        nil
        (commands/select-entry-market)))))

(deftest runtime-gateway-dynamic-dispatch-table-fallbacks-test
  (let [dispatch-fn runtime-gateway/command->runtime-actions
        previous-object (gobj/get dispatch-fn "object")
        previous-default (gobj/get dispatch-fn "_")
        restore! (fn [k v]
                   (if (identical? js/undefined v)
                     (gobj/remove dispatch-fn k)
                     (gobj/set dispatch-fn k v)))]
    (try
      (gobj/set dispatch-fn
                "object"
                (fn [_ _]
                  [[:actions/object-dispatch]]))
      (is (= [[:actions/object-dispatch]]
             (runtime-gateway/command->runtime-actions
              #js {}
              (commands/select-entry-market))))

      (gobj/remove dispatch-fn "object")
      (gobj/set dispatch-fn
                "_"
                (fn [_ _]
                  [[:actions/default-dispatch]]))
      (is (= [[:actions/default-dispatch]]
             (runtime-gateway/command->runtime-actions
              #js {}
              (commands/select-entry-market))))
      (finally
        (restore! "object" previous-object)
        (restore! "_" previous-default)))))

(deftest runtime-gateway-record-generated-map-operations-test
  (let [gateway (runtime-gateway/default-gateway)
        with-foo (assoc gateway :foo 1)
        with-bar (conj gateway [:bar 2])
        with-baz (conj gateway {:baz 3})
        without-foo (dissoc with-foo :foo)]
    (is (= 1 (:foo with-foo)))
    (is (= 2 (:bar with-bar)))
    (is (= 3 (:baz with-baz)))
    (is (= 0 (count gateway)))
    (is (= 1 (count with-foo)))
    (is (nil? (seq gateway)))
    (is (seq with-foo))
    (is (not (contains? without-foo :foo)))
    (is (= (hash with-foo) (hash with-foo)))
    (is (= {:tag :runtime}
           (meta (with-meta gateway {:tag :runtime})))))) 

(deftest intent-adapter-default-and-explicit-gateway-arities-test
  (is (= [[:actions/select-order-entry-mode :market]]
         (intent-adapter/command->actions
          (commands/select-entry-market))))
  (let [calls (atom [])
        gateway (reify runtime-gateway/OrderFormRuntimeGateway
                  (command->runtime-actions [_ command]
                    (swap! calls conj command)
                    [[:actions/custom (:command-id command)]]))]
    (is (= [[:actions/custom :order-form/submit-order]]
           (intent-adapter/command->actions gateway (commands/submit-order))))
    (is (= [(commands/submit-order)] @calls))))

(deftest intent-adapter-invalid-arity-throws-test
  (is (thrown-with-msg?
       js/Error
       #"Invalid arity"
       (apply intent-adapter/command->actions [])))
  (is (thrown-with-msg?
       js/Error
       #"Invalid arity"
       (apply intent-adapter/command->actions
              [(commands/select-entry-market) :extra :args]))))
