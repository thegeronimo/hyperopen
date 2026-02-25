(ns hyperopen.schema.order-form-command-catalog-test
  (:require [clojure.set :as set]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.registry.runtime :as runtime-registry]
            [hyperopen.schema.order-form-command-catalog :as command-catalog]))

(deftest order-form-command-catalog-entry-uniqueness-test
  (let [entries (command-catalog/catalog-entries)
        command-ids (map :command-id entries)
        action-ids (map :action-id entries)]
    (is (seq entries))
    (doseq [entry entries]
      (is (= #{:command-id :action-id :handler-key}
             (set (keys entry))))
      (is (keyword? (:command-id entry)))
      (is (= "order-form" (namespace (:command-id entry))))
      (is (keyword? (:action-id entry)))
      (is (= "actions" (namespace (:action-id entry))))
      (is (keyword? (:handler-key entry))))
    (is (= (count entries) (count (set command-ids)))
        (str "Duplicate order-form command ids in catalog: " (pr-str entries)))
    (is (= (count entries) (count (set action-ids)))
        (str "Duplicate action ids in catalog: " (pr-str entries)))
    (is (= (set command-ids)
           (command-catalog/supported-command-ids)))
    (is (= (set action-ids)
           (command-catalog/catalog-action-ids)))))

(deftest order-form-command-catalog-runtime-bindings-match-entries-test
  (let [entries (command-catalog/catalog-entries)
        expected-bindings (mapv (juxt :action-id :handler-key) entries)]
    (is (= expected-bindings
           (command-catalog/runtime-action-bindings)))))

(deftest order-form-command-catalog-actions-are-runtime-registered-test
  (let [registered-action-ids (runtime-registry/registered-action-ids)
        catalog-action-ids (command-catalog/catalog-action-ids)]
    (is (empty? (set/difference catalog-action-ids registered-action-ids))
        (str "Order-form command catalog action bindings missing from runtime registration. "
             "missing="
             (pr-str (set/difference catalog-action-ids registered-action-ids))))))
