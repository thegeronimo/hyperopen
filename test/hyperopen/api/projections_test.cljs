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

(deftest order-candle-and-perp-projections-target-expected-state-paths-test
  (let [state {:orders {}
               :candles {}
               :perp-dex-clearinghouse {}
               :perp-dexs []
               :asset-contexts {}}
        asset-contexts (projections/apply-asset-contexts-success state {:BTC {:idx 0}})
        asset-contexts-error (projections/apply-asset-contexts-error state (js/Error. "asset-contexts"))
        perp-dexs (projections/apply-perp-dexs-success state ["vault"])
        perp-dexs-error (projections/apply-perp-dexs-error state (js/Error. "perp-dexs"))
        open-orders (projections/apply-open-orders-success state nil [{:oid 1}])
        open-orders-by-dex (projections/apply-open-orders-success state "vault" [{:oid 2}])
        open-orders-error (projections/apply-open-orders-error state (js/Error. "open-orders"))
        fills (projections/apply-user-fills-success state [{:tid 1}])
        fills-error (projections/apply-user-fills-error state (js/Error. "fills"))
        candle (projections/apply-candle-snapshot-success state "BTC" :1h [{:t 1}])
        candle-error (projections/apply-candle-snapshot-error state "BTC" :1h (js/Error. "candles"))
        clearinghouse (projections/apply-perp-dex-clearinghouse-success state "vault" {:margin 10})
        clearinghouse-error (projections/apply-perp-dex-clearinghouse-error state (js/Error. "clearinghouse"))]
    (is (= {:BTC {:idx 0}} (:asset-contexts asset-contexts)))
    (is (= "Error: asset-contexts" (get-in asset-contexts-error [:asset-contexts :error])))
    (is (= ["vault"] (:perp-dexs perp-dexs)))
    (is (= "Error: perp-dexs" (:perp-dexs-error perp-dexs-error)))
    (is (= [{:oid 1}] (get-in open-orders [:orders :open-orders-snapshot])))
    (is (= [{:oid 2}] (get-in open-orders-by-dex [:orders :open-orders-snapshot-by-dex "vault"])))
    (is (= "Error: open-orders" (get-in open-orders-error [:orders :open-error])))
    (is (= [{:tid 1}] (get-in fills [:orders :fills])))
    (is (= "Error: fills" (get-in fills-error [:orders :fills-error])))
    (is (= [{:t 1}] (get-in candle [:candles "BTC" :1h])))
    (is (= "Error: candles" (get-in candle-error [:candles "BTC" :1h :error])))
    (is (= {:margin 10} (get-in clearinghouse [:perp-dex-clearinghouse "vault"])))
    (is (= "Error: clearinghouse" (:perp-dex-clearinghouse-error clearinghouse-error)))))

(deftest user-abstraction-projection-ignores-stale-address-updates-test
  (let [state {:wallet {:address "0xabc"}
               :account {:mode :classic}}
        snapshot {:mode :unified
                  :abstraction-raw "unifiedAccount"}
        matched (projections/apply-user-abstraction-snapshot state "0xabc" snapshot)
        stale (projections/apply-user-abstraction-snapshot state "0xdef" snapshot)]
    (is (= snapshot (:account matched)))
    (is (= {:mode :classic} (:account stale)))))
