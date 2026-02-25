(ns hyperopen.state.trading.order-form-key-policy-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.set :as set]
            [hyperopen.state.trading.order-form-key-policy :as key-policy]
            [hyperopen.trading.order-form-state :as order-form-state]))

(deftest key-policy-canonical-classifications-are-unique-and-explicit-test
  (let [ui-keys key-policy/ui-owned-order-form-keys
        legacy-keys key-policy/legacy-order-form-compatibility-keys
        deprecated-keys key-policy/deprecated-canonical-order-form-keys]
    (is (= (count ui-keys) (count (set ui-keys))))
    (is (= (count legacy-keys) (count (set legacy-keys))))
    (is (= (count deprecated-keys) (count (set deprecated-keys))))
    (is (= #{}
           (set/intersection key-policy/ui-owned-order-form-key-set
                             key-policy/legacy-order-form-compatibility-key-set)))
    (is (= (set deprecated-keys)
           (set/union key-policy/ui-owned-order-form-key-set
                      key-policy/legacy-order-form-compatibility-key-set)))))

(deftest key-policy-order-form-ui-state-keys-match-ui-default-shape-test
  (is (= key-policy/order-form-ui-state-keys
         (set (keys (order-form-state/default-order-form-ui))))))

(deftest key-policy-canonical-write-blocked-paths-map-directly-from-key-policy-test
  (is (= key-policy/canonical-write-blocked-order-form-paths
         (set (map vector key-policy/deprecated-canonical-order-form-keys))))
  (is (every? true?
              (map key-policy/canonical-write-blocked-order-form-path?
                   key-policy/canonical-write-blocked-order-form-paths)))
  (is (false? (key-policy/canonical-write-blocked-order-form-path? [:type])))
  (is (false? (key-policy/canonical-write-blocked-order-form-path? [:tp :trigger]))))

(deftest key-policy-strip-and-select-helpers-respect-boundaries-test
  (let [raw-form {:type :limit
                  :price "100"
                  :entry-mode :pro
                  :ui-leverage 25
                  :size-input-mode :base
                  :size-input-source :percent
                  :size-display "10"
                  :pro-order-type-dropdown-open? true
                  :tpsl-panel-open? true
                  :submitting? true
                  :error "oops"}]
    (is (= {:entry-mode :pro
            :ui-leverage 25
            :size-input-mode :base
            :size-input-source :percent
            :size-display "10"}
           (key-policy/order-form-ui-overrides-from-form raw-form)))
    (is (= {:type :limit
            :price "100"
            :pro-order-type-dropdown-open? true
            :tpsl-panel-open? true
            :submitting? true
            :error "oops"}
           (key-policy/strip-ui-owned-order-form-keys raw-form)))
    (is (= {:type :limit
            :price "100"
            :entry-mode :pro
            :ui-leverage 25
            :size-input-mode :base
            :size-input-source :percent
            :size-display "10"}
           (key-policy/strip-legacy-order-form-compatibility-keys raw-form)))
    (is (= {:type :limit
            :price "100"}
           (key-policy/strip-deprecated-canonical-order-form-keys raw-form)))))
