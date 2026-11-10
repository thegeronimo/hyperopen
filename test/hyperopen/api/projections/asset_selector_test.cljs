(ns hyperopen.api.projections.asset-selector-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.api.projections.asset-selector :as asset-selector]))

(deftest asset-selector-success-projection-prefers-full-phase-when-bootstrap-finishes-late-test
  (let [state {:asset-selector {:phase :full
                                :cache-hydrated? false
                                :loading? true
                                :error "kept"
                                :error-category :transport
                                :markets [{:key :existing}]
                                :market-by-key {:existing {:key :existing}}
                                :market-index-by-key {:existing 0}}
               :active-market {:coin "BTC"}}
        market-state {:markets [{:key :new}]
                      :market-by-key {:new {:key :new}}
                      :market-index-by-key {:new 0}
                      :active-market {:coin "ETH"}
                      :loaded-at-ms 999}
        next-state (asset-selector/apply-asset-selector-success state :bootstrap market-state)]
    (is (= [{:key :existing}] (get-in next-state [:asset-selector :markets])))
    (is (= {:existing {:key :existing}} (get-in next-state [:asset-selector :market-by-key])))
    (is (= {:existing 0} (get-in next-state [:asset-selector :market-index-by-key])))
    (is (= :full (get-in next-state [:asset-selector :phase])))
    (is (= false (get-in next-state [:asset-selector :cache-hydrated?])))
    (is (= {:coin "BTC"} (:active-market next-state)))
    (is (= "kept" (get-in next-state [:asset-selector :error])))
    (is (= :transport (get-in next-state [:asset-selector :error-category])))
    (is (= 999 (get-in next-state [:asset-selector :loaded-at-ms])))
    (is (= false (get-in next-state [:asset-selector :loading?])))))

(deftest asset-selector-bootstrap-after-cache-hydrated-full-state-replaces-cache-test
  (let [state {:asset-selector {:phase :full
                                :cache-hydrated? true
                                :loading? true
                                :error "stale"
                                :error-category :transport
                                :markets [{:key :cached}]
                                :market-by-key {:cached {:key :cached}}
                                :market-index-by-key {:cached 0}}
               :active-market {:coin "BTC"}}
        market-state {:markets [{:key :bootstrap}]
                      :market-by-key {:bootstrap {:key :bootstrap}}
                      :active-market {:coin "ETH"}
                      :loaded-at-ms 1000}
        next-state (asset-selector/apply-asset-selector-success state :bootstrap market-state)]
    (is (= [{:key :bootstrap}] (get-in next-state [:asset-selector :markets])))
    (is (= {:bootstrap {:key :bootstrap}} (get-in next-state [:asset-selector :market-by-key])))
    (is (= {:bootstrap 0} (get-in next-state [:asset-selector :market-index-by-key])))
    (is (= :bootstrap (get-in next-state [:asset-selector :phase])))
    (is (= false (get-in next-state [:asset-selector :cache-hydrated?])))
    (is (= {:coin "ETH"} (:active-market next-state)))
    (is (nil? (get-in next-state [:asset-selector :error])))
    (is (nil? (get-in next-state [:asset-selector :error-category])))))

(deftest asset-selector-projections-update-full-market-state-and-errors-test
  (let [state {:asset-selector {:phase :bootstrap
                                :loading? false
                                :error "old"}
               :active-market nil}
        loading (asset-selector/begin-asset-selector-load state :full)
        market-state {:markets [{:key :btc}]
                      :market-by-key {:btc {:key :btc}}
                      :active-market {:coin "BTC"}
                      :loaded-at-ms 123}
        success (asset-selector/apply-asset-selector-success loading :full market-state)
        failed (asset-selector/apply-asset-selector-error loading "timeout")]
    (is (= true (get-in loading [:asset-selector :loading?])))
    (is (= :full (get-in loading [:asset-selector :phase])))
    (is (= [{:key :btc}] (get-in success [:asset-selector :markets])))
    (is (= {:btc {:key :btc}} (get-in success [:asset-selector :market-by-key])))
    (is (= {:btc 0} (get-in success [:asset-selector :market-index-by-key])))
    (is (= {:coin "BTC"} (:active-market success)))
    (is (= false (get-in success [:asset-selector :loading?])))
    (is (= nil (get-in success [:asset-selector :error])))
    (is (= nil (get-in success [:asset-selector :error-category])))
    (is (= false (get-in failed [:asset-selector :loading?])))
    (is (= "timeout" (get-in failed [:asset-selector :error])))
    (is (= :transport (get-in failed [:asset-selector :error-category])))
    (testing "a failed full load is not latched as :full"
      (is (= :bootstrap (get-in failed [:asset-selector :phase])))
      (is (= :full (get-in (-> success
                               (asset-selector/begin-asset-selector-load :full)
                               (asset-selector/apply-asset-selector-error "timeout"))
                           [:asset-selector :phase]))
          "a refresh that fails keeps the catalog that did load"))))
