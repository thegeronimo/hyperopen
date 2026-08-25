(ns hyperopen.api.projections.market-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api.projections.market :as market]))

(deftest spot-meta-projections-apply-single-transition-state-test
  (let [state {:spot {:loading-meta? false
                      :error "stale"}}
        loading (market/begin-spot-meta-load state)
        success (market/apply-spot-meta-success loading {:tokens [{:name "USDC"}]})
        failed (market/apply-spot-meta-error loading (js/Error. "nope"))]
    (is (= true (get-in loading [:spot :loading-meta?])))
    (is (= {:tokens [{:name "USDC"}]} (get-in success [:spot :meta])))
    (is (= false (get-in success [:spot :loading-meta?])))
    (is (= nil (get-in success [:spot :error])))
    (is (= nil (get-in success [:spot :error-category])))
    (is (= false (get-in failed [:spot :loading-meta?])))
    (is (= "Error: nope" (get-in failed [:spot :error])))
    (is (= :unexpected (get-in failed [:spot :error-category])))))

(deftest spot-balances-projections-update-success-and-error-paths-test
  (let [state {:spot {:loading-balances? false
                      :error nil}}
        loading (market/begin-spot-balances-load state)
        success (market/apply-spot-balances-success loading {:balances [1 2 3]})
        failed (market/apply-spot-balances-error loading (js/Error. "unavailable"))]
    (is (= true (get-in loading [:spot :loading-balances?])))
    (is (= {:balances [1 2 3]} (get-in success [:spot :clearinghouse-state])))
    (is (= false (get-in success [:spot :loading-balances?])))
    (is (= nil (get-in success [:spot :error])))
    (is (= nil (get-in success [:spot :error-category])))
    (is (= false (get-in failed [:spot :loading-balances?])))
    (is (= "Error: unavailable" (get-in failed [:spot :error])))
    (is (= :unexpected (get-in failed [:spot :error-category])))))

(deftest market-candle-and-perp-projections-target-expected-state-paths-test
  (let [state {:candles {}
               :perp-dex-clearinghouse {}
               :perp-dexs []
               :perp-dex-fee-config-by-name {}
               :asset-contexts {}}
        asset-contexts (market/apply-asset-contexts-success state {:BTC {:idx 0}})
        asset-contexts-error (market/apply-asset-contexts-error state (js/Error. "asset-contexts"))
        perp-dexs (market/apply-perp-dexs-success state ["vault"])
        perp-dexs-with-config (market/apply-perp-dexs-success
                               state
                               {:dex-names ["vault" "scaled"]
                                :fee-config-by-name {"scaled" {:deployer-fee-scale 0.1}}})
        perp-dexs-error (market/apply-perp-dexs-error state (js/Error. "perp-dexs"))
        candle (market/apply-candle-snapshot-success state "BTC" :1h [{:t 1}])
        candle-error (market/apply-candle-snapshot-error state "BTC" :1h (js/Error. "candles"))
        clearinghouse (market/apply-perp-dex-clearinghouse-success state "vault" {:margin 10})
        clearinghouse-error (market/apply-perp-dex-clearinghouse-error state (js/Error. "clearinghouse"))]
    (is (= {:BTC {:idx 0}} (:asset-contexts asset-contexts)))
    (is (= "Error: asset-contexts" (get-in asset-contexts-error [:asset-contexts :error])))
    (is (= :unexpected (get-in asset-contexts-error [:asset-contexts :error-category])))
    (is (= ["vault"] (:perp-dexs perp-dexs)))
    (is (= {} (:perp-dex-fee-config-by-name perp-dexs)))
    (is (= ["vault" "scaled"] (:perp-dexs perp-dexs-with-config)))
    (is (= {"scaled" {:deployer-fee-scale 0.1}}
           (:perp-dex-fee-config-by-name perp-dexs-with-config)))
    (is (= "Error: perp-dexs" (:perp-dexs-error perp-dexs-error)))
    (is (= :unexpected (:perp-dexs-error-category perp-dexs-error)))
    (is (= [{:t 1}] (get-in candle [:candles "BTC" :1h])))
    (is (= "Error: candles" (get-in candle-error [:candles "BTC" :1h :error])))
    (is (= :unexpected (get-in candle-error [:candles "BTC" :1h :error-category])))
    (is (= {:margin 10} (get-in clearinghouse [:perp-dex-clearinghouse "vault"])))
    (is (= "Error: clearinghouse" (:perp-dex-clearinghouse-error clearinghouse-error)))
    (is (= :unexpected (:perp-dex-clearinghouse-error-category clearinghouse-error)))))

(deftest candle-snapshot-success-merges-dedupes-and-sorts-existing-rows-test
  (let [state {:candles {"BTC" {:1d [{:t 3000 :c "103"}
                                     {:t 4000 :c "104"}]}}}
        next-state (market/apply-candle-snapshot-success
                    state
                    "BTC"
                    :1d
                    [{:t 1000 :c "101"}
                     {:t 3000 :c "103-updated"}
                     {:t 2000 :c "102"}])]
    (is (= [{:t 1000 :c "101"}
            {:t 2000 :c "102"}
            {:t 3000 :c "103-updated"}
            {:t 4000 :c "104"}]
           (get-in next-state [:candles "BTC" :1d])))))

(deftest apply-default-clearinghouse-success-writes-base-bucket-and-preserves-siblings-test
  (let [state {:webdata2 {:clearinghouseState {:marginSummary {:accountValue "1"}}
                          :spotAssetCtxs [{:coin "PURR"}]}}
        data {:marginSummary {:accountValue "74"}
              :assetPositions [{:position {:coin "WLD" :szi "-58.9"}}]}
        next-state (market/apply-default-clearinghouse-success state data)]
    (is (= data (get-in next-state [:webdata2 :clearinghouseState])))
    (is (= [{:coin "PURR"}] (get-in next-state [:webdata2 :spotAssetCtxs])))))

(deftest candle-snapshot-error-on-a-warm-slot-records-without-throwing-test
  ;; Regression: the success path stores a plain VECTOR of rows, so the error
  ;; path's `(assoc-in state [:candles coin interval :error] ...)` resolved to
  ;; associating a keyword key into a vector and threw. It threw inside the
  ;; `swap!` that runs in the fetch's `.catch`, so a failed refresh of an
  ;; already-populated slot recorded nothing and cleared no pending flag.
  (let [warm (market/apply-candle-snapshot-success {:candles {}}
                                                   "BTC"
                                                   :1d
                                                   [{:t 1000 :c "101"}
                                                    {:t 2000 :c "102"}])
        failed (market/apply-candle-snapshot-error warm "BTC" :1d (js/Error. "boom"))
        entry (get-in failed [:candles "BTC" :1d])]
    (is (map? entry)
        "a slot carrying an error normalizes to the map shape every reader already handles")
    (is (= [{:t 1000 :c "101"}
            {:t 2000 :c "102"}]
           (:rows entry))
        "the rows already fetched survive the failure")
    (is (= "Error: boom" (:error entry)))
    (is (= :unexpected (:error-category entry)))))

(deftest candle-snapshot-success-after-an-error-clears-the-error-and-keeps-rows-test
  (let [warm (market/apply-candle-snapshot-success {:candles {}}
                                                   "BTC"
                                                   :1d
                                                   [{:t 1000 :c "101"}])
        failed (market/apply-candle-snapshot-error warm "BTC" :1d (js/Error. "boom"))
        recovered (market/apply-candle-snapshot-success failed
                                                        "BTC"
                                                        :1d
                                                        [{:t 2000 :c "102"}])
        entry (get-in recovered [:candles "BTC" :1d])]
    (is (= [{:t 1000 :c "101"}
            {:t 2000 :c "102"}]
           entry)
        "a later success replaces the error map with a plain row vector, merging what was already stored")))
