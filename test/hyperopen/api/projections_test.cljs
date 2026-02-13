(ns hyperopen.api.projections-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api.projections :as projections]))

(deftest spot-meta-projections-apply-single-transition-state-test
  (let [state {:spot {:loading-meta? false
                      :error "stale"}}
        loading (projections/begin-spot-meta-load state)
        success (projections/apply-spot-meta-success loading {:tokens [{:name "USDC"}]})
        failed (projections/apply-spot-meta-error loading (js/Error. "nope"))]
    (is (= true (get-in loading [:spot :loading-meta?])))
    (is (= {:tokens [{:name "USDC"}]} (get-in success [:spot :meta])))
    (is (= false (get-in success [:spot :loading-meta?])))
    (is (= nil (get-in success [:spot :error])))
    (is (= false (get-in failed [:spot :loading-meta?])))
    (is (= "Error: nope" (get-in failed [:spot :error])))))

(deftest asset-selector-success-projection-prefers-full-phase-when-bootstrap-finishes-late-test
  (let [state {:asset-selector {:phase :full
                                :cache-hydrated? false
                                :loading? true
                                :error "kept"
                                :markets [{:key :existing}]}
               :active-market {:coin "BTC"}}
        market-state {:markets [{:key :new}]
                      :market-by-key {:new {:key :new}}
                      :active-market {:coin "ETH"}
                      :loaded-at-ms 999}
        next-state (projections/apply-asset-selector-success state :bootstrap market-state)]
    (is (= [{:key :existing}] (get-in next-state [:asset-selector :markets])))
    (is (= "kept" (get-in next-state [:asset-selector :error])))
    (is (= 999 (get-in next-state [:asset-selector :loaded-at-ms])))
    (is (= false (get-in next-state [:asset-selector :loading?])))))

(deftest asset-selector-projections-update-full-market-state-and-errors-test
  (let [state {:asset-selector {:phase :bootstrap
                                :loading? false
                                :error "old"}
               :active-market nil}
        loading (projections/begin-asset-selector-load state :full)
        market-state {:markets [{:key :btc}]
                      :market-by-key {:btc {:key :btc}}
                      :active-market {:coin "BTC"}
                      :loaded-at-ms 123}
        success (projections/apply-asset-selector-success loading :full market-state)
        failed (projections/apply-asset-selector-error loading "timeout")]
    (is (= true (get-in loading [:asset-selector :loading?])))
    (is (= :full (get-in loading [:asset-selector :phase])))
    (is (= [{:key :btc}] (get-in success [:asset-selector :markets])))
    (is (= {:btc {:key :btc}} (get-in success [:asset-selector :market-by-key])))
    (is (= {:coin "BTC"} (:active-market success)))
    (is (= false (get-in success [:asset-selector :loading?])))
    (is (= nil (get-in success [:asset-selector :error])))
    (is (= false (get-in failed [:asset-selector :loading?])))
    (is (= "timeout" (get-in failed [:asset-selector :error])))))

(deftest spot-balances-projections-update-success-and-error-paths-test
  (let [state {:spot {:loading-balances? false
                      :error nil}}
        loading (projections/begin-spot-balances-load state)
        success (projections/apply-spot-balances-success loading {:balances [1 2 3]})
        failed (projections/apply-spot-balances-error loading (js/Error. "unavailable"))]
    (is (= true (get-in loading [:spot :loading-balances?])))
    (is (= {:balances [1 2 3]} (get-in success [:spot :clearinghouse-state])))
    (is (= false (get-in success [:spot :loading-balances?])))
    (is (= nil (get-in success [:spot :error])))
    (is (= false (get-in failed [:spot :loading-balances?])))
    (is (= "Error: unavailable" (get-in failed [:spot :error])))))
