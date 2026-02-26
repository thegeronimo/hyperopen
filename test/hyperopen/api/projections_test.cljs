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
    (is (= nil (get-in success [:spot :error-category])))
    (is (= false (get-in failed [:spot :loading-meta?])))
    (is (= "Error: nope" (get-in failed [:spot :error])))
    (is (= :unexpected (get-in failed [:spot :error-category])))))

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
    (is (= nil (get-in success [:asset-selector :error-category])))
    (is (= false (get-in failed [:asset-selector :loading?])))
    (is (= "timeout" (get-in failed [:asset-selector :error])))
    (is (= :transport (get-in failed [:asset-selector :error-category])))))

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
    (is (= nil (get-in success [:spot :error-category])))
    (is (= false (get-in failed [:spot :loading-balances?])))
    (is (= "Error: unavailable" (get-in failed [:spot :error])))
    (is (= :unexpected (get-in failed [:spot :error-category])))))

(deftest order-candle-and-perp-projections-target-expected-state-paths-test
  (let [state {:orders {}
               :candles {}
               :perp-dex-clearinghouse {}
               :perp-dexs []
               :perp-dex-fee-config-by-name {}
               :asset-contexts {}}
        asset-contexts (projections/apply-asset-contexts-success state {:BTC {:idx 0}})
        asset-contexts-error (projections/apply-asset-contexts-error state (js/Error. "asset-contexts"))
        perp-dexs (projections/apply-perp-dexs-success state ["vault"])
        perp-dexs-with-config (projections/apply-perp-dexs-success
                               state
                               {:dex-names ["vault" "scaled"]
                                :fee-config-by-name {"scaled" {:deployer-fee-scale 0.1}}})
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
    (is (= :unexpected (get-in asset-contexts-error [:asset-contexts :error-category])))
    (is (= ["vault"] (:perp-dexs perp-dexs)))
    (is (= {} (:perp-dex-fee-config-by-name perp-dexs)))
    (is (= ["vault" "scaled"] (:perp-dexs perp-dexs-with-config)))
    (is (= {"scaled" {:deployer-fee-scale 0.1}}
           (:perp-dex-fee-config-by-name perp-dexs-with-config)))
    (is (= "Error: perp-dexs" (:perp-dexs-error perp-dexs-error)))
    (is (= :unexpected (:perp-dexs-error-category perp-dexs-error)))
    (is (= [{:oid 1}] (get-in open-orders [:orders :open-orders-snapshot])))
    (is (= [{:oid 2}] (get-in open-orders-by-dex [:orders :open-orders-snapshot-by-dex "vault"])))
    (is (= "Error: open-orders" (get-in open-orders-error [:orders :open-error])))
    (is (= :unexpected (get-in open-orders-error [:orders :open-error-category])))
    (is (= [{:tid 1}] (get-in fills [:orders :fills])))
    (is (= "Error: fills" (get-in fills-error [:orders :fills-error])))
    (is (= :unexpected (get-in fills-error [:orders :fills-error-category])))
    (is (= [{:t 1}] (get-in candle [:candles "BTC" :1h])))
    (is (= "Error: candles" (get-in candle-error [:candles "BTC" :1h :error])))
    (is (= :unexpected (get-in candle-error [:candles "BTC" :1h :error-category])))
    (is (= {:margin 10} (get-in clearinghouse [:perp-dex-clearinghouse "vault"])))
    (is (= "Error: clearinghouse" (:perp-dex-clearinghouse-error clearinghouse-error)))
    (is (= :unexpected (:perp-dex-clearinghouse-error-category clearinghouse-error)))))

(deftest vault-list-projections-update-loading-success-and-error-paths-test
  (let [state {:vaults {:index-rows []
                        :recent-summaries []
                        :merged-index-rows []
                        :loading {:index? false
                                  :summaries? false}
                        :errors {:index "stale-index"
                                 :summaries "stale-summaries"}
                        :loaded-at-ms {:index nil
                                       :summaries nil}}}
        index-loading (projections/begin-vault-index-load state)
        index-success (projections/apply-vault-index-success
                       index-loading
                       [{:vault-address "0x1"
                         :name "Index One"}
                        {:vault-address "0x2"
                         :name "Index Two"}])
        summaries-loading (projections/begin-vault-summaries-load index-success)
        summaries-success (projections/apply-vault-summaries-success
                           summaries-loading
                           [{:vault-address "0x2"
                             :name "Summary Two"}
                            {:vault-address "0x3"
                             :name "Summary Three"}])
        index-error (projections/apply-vault-index-error index-loading (js/Error. "index-fail"))
        summaries-error (projections/apply-vault-summaries-error summaries-loading "summary-fail")]
    (is (= true (get-in index-loading [:vaults :loading :index?])))
    (is (= nil (get-in index-loading [:vaults :errors :index])))
    (is (= ["0x1" "0x2"] (mapv :vault-address (get-in index-success [:vaults :index-rows]))))
    (is (= ["0x1" "0x2"] (mapv :vault-address (get-in index-success [:vaults :merged-index-rows]))))
    (is (= false (get-in index-success [:vaults :loading :index?])))
    (is (number? (get-in index-success [:vaults :loaded-at-ms :index])))
    (is (= true (get-in summaries-loading [:vaults :loading :summaries?])))
    (is (= ["0x1" "0x2" "0x3"]
           (mapv :vault-address (get-in summaries-success [:vaults :merged-index-rows]))))
    (is (= "Summary Two"
           (:name (second (get-in summaries-success [:vaults :merged-index-rows])))))
    (is (= false (get-in summaries-success [:vaults :loading :summaries?])))
    (is (number? (get-in summaries-success [:vaults :loaded-at-ms :summaries])))
    (is (= "Error: index-fail" (get-in index-error [:vaults :errors :index])))
    (is (= false (get-in index-error [:vaults :loading :index?])))
    (is (= "summary-fail" (get-in summaries-error [:vaults :errors :summaries])))
    (is (= false (get-in summaries-error [:vaults :loading :summaries?])))))

(deftest vault-equities-and-detail-projections-track-per-address-state-test
  (let [state {:vaults {:user-equities []
                        :user-equity-by-address {}
                        :details-by-address {}
                        :webdata-by-vault {}
                        :loading {:user-equities? false
                                  :details-by-address {}
                                  :webdata-by-vault {}}
                        :errors {:user-equities nil
                                 :details-by-address {}
                                 :webdata-by-vault {}}
                        :loaded-at-ms {:user-equities nil
                                       :details-by-address {}
                                       :webdata-by-vault {}}}}
        equities-loading (projections/begin-user-vault-equities-load state)
        equities-success (projections/apply-user-vault-equities-success
                          equities-loading
                          [{:vault-address "0xA"
                            :equity 10}
                           {:vault-address "0xB"
                            :equity 20}])
        equities-error (projections/apply-user-vault-equities-error equities-loading (js/Error. "equity-fail"))
        details-loading (projections/begin-vault-details-load state "0xA")
        details-success (projections/apply-vault-details-success details-loading "0xA" {:name "Vault A"})
        details-error (projections/apply-vault-details-error details-loading "0xA" "details-fail")
        webdata-loading (projections/begin-vault-webdata2-load state "0xA")
        webdata-success (projections/apply-vault-webdata2-success webdata-loading "0xA" {:fills [1]})
        webdata-error (projections/apply-vault-webdata2-error webdata-loading "0xA" (js/Error. "webdata-fail"))]
    (is (= true (get-in equities-loading [:vaults :loading :user-equities?])))
    (is (= [{:vault-address "0xA" :equity 10}
            {:vault-address "0xB" :equity 20}]
           (get-in equities-success [:vaults :user-equities])))
    (is (= {:vault-address "0xA" :equity 10}
           (get-in equities-success [:vaults :user-equity-by-address "0xa"])))
    (is (= false (get-in equities-success [:vaults :loading :user-equities?])))
    (is (number? (get-in equities-success [:vaults :loaded-at-ms :user-equities])))
    (is (= "Error: equity-fail" (get-in equities-error [:vaults :errors :user-equities])))
    (is (= true (get-in details-loading [:vaults :loading :details-by-address "0xa"])))
    (is (= {:name "Vault A"} (get-in details-success [:vaults :details-by-address "0xa"])))
    (is (= false (get-in details-success [:vaults :loading :details-by-address "0xa"])))
    (is (number? (get-in details-success [:vaults :loaded-at-ms :details-by-address "0xa"])))
    (is (= "details-fail" (get-in details-error [:vaults :errors :details-by-address "0xa"])))
    (is (= true (get-in webdata-loading [:vaults :loading :webdata-by-vault "0xa"])))
    (is (= {:fills [1]} (get-in webdata-success [:vaults :webdata-by-vault "0xa"])))
    (is (= false (get-in webdata-success [:vaults :loading :webdata-by-vault "0xa"])))
    (is (number? (get-in webdata-success [:vaults :loaded-at-ms :webdata-by-vault "0xa"])))
    (is (= "Error: webdata-fail"
           (get-in webdata-error [:vaults :errors :webdata-by-vault "0xa"])))))

(deftest user-abstraction-projection-ignores-stale-address-updates-test
  (let [state {:wallet {:address "0xabc"}
               :account {:mode :classic}}
        snapshot {:mode :unified
                  :abstraction-raw "unifiedAccount"}
        matched (projections/apply-user-abstraction-snapshot state "0xabc" snapshot)
        stale (projections/apply-user-abstraction-snapshot state "0xdef" snapshot)]
    (is (= snapshot (:account matched)))
    (is (= {:mode :classic} (:account stale)))))
