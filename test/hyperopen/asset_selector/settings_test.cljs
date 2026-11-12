(ns hyperopen.asset-selector.settings-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.asset-selector.settings :as settings]
            [hyperopen.platform :as platform]))

(defn- local-storage-get-stub
  [values]
  (fn [key]
    (get values key)))

(deftest restore-asset-selector-sort-settings-loads-valid-storage-values-test
  (let [store (atom {:asset-selector {:favorites-only? true}})]
    (with-redefs [platform/local-storage-get
                  (local-storage-get-stub {"asset-selector-sort-by" "price"
                                           "asset-selector-sort-direction" "asc"
                                           "asset-selector-strict" "true"
                                           "asset-selector-include-outcomes" "true"
                                           "asset-selector-favorites" "[\"perp:BTC\",\"spot:PURR/USDC\"]"
                                           "asset-selector-active-tab" "outcome"})]
      (settings/restore-asset-selector-sort-settings! store))
    (is (= :price (get-in @store [:asset-selector :sort-by])))
    (is (= :asc (get-in @store [:asset-selector :sort-direction])))
    (is (= true (get-in @store [:asset-selector :strict?])))
    (is (= true (get-in @store [:asset-selector :include-outcomes?])))
    (is (= #{"perp:BTC" "spot:PURR/USDC"}
           (get-in @store [:asset-selector :favorites])))
    (is (= :outcome (get-in @store [:asset-selector :active-tab])))
    (is (= true (get-in @store [:asset-selector :favorites-only?])))))

(deftest toggle-asset-selector-include-outcomes-flips-and-persists-test
  (is (= [[:effects/save-many [[[:asset-selector :include-outcomes?] true]
                               [[:asset-selector :highlighted-market-key] nil]]]
          [:effects/local-storage-set "asset-selector-include-outcomes" "true"]
          [:effects/sync-asset-selector-active-ctx-subscriptions]]
         (settings/toggle-asset-selector-include-outcomes {:asset-selector {}}))
      "off by default, so the first toggle switches outcomes on")
  (is (= [:effects/local-storage-set "asset-selector-include-outcomes" "false"]
         (second (settings/toggle-asset-selector-include-outcomes
                  {:asset-selector {:include-outcomes? true}})))))

(deftest restore-asset-selector-sort-settings-falls-back-for-invalid-values-test
  (let [store (atom {:asset-selector {}})]
    (with-redefs [platform/local-storage-get
                  (local-storage-get-stub {"asset-selector-sort-by" "not-real"
                                           "asset-selector-sort-direction" "not-real"
                                           "asset-selector-strict" nil
                                           "asset-selector-favorites" "{"
                                           "asset-selector-active-tab" "not-real"})]
      (settings/restore-asset-selector-sort-settings! store))
    (is (= :volume (get-in @store [:asset-selector :sort-by])))
    (is (= :desc (get-in @store [:asset-selector :sort-direction])))
    (is (= false (get-in @store [:asset-selector :strict?])))
    (is (= false (get-in @store [:asset-selector :include-outcomes?])) "outcomes stay off until asked for")
    (is (= #{} (get-in @store [:asset-selector :favorites])))
    (is (= :all (get-in @store [:asset-selector :active-tab])))))

(deftest restore-asset-selector-sort-settings-coerces-non-sequential-favorites-to-empty-set-test
  (let [store (atom {:asset-selector {}})]
    (with-redefs [platform/local-storage-get
                  (local-storage-get-stub {"asset-selector-favorites" "{\"coin\":\"BTC\"}"
                                           "asset-selector-strict" "false"})]
      (settings/restore-asset-selector-sort-settings! store))
    (is (= false (get-in @store [:asset-selector :strict?])))
    (is (= #{} (get-in @store [:asset-selector :favorites])))))
