(ns hyperopen.asset-selector.markets-cache-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.asset-selector.markets-cache :as markets-cache]
            [hyperopen.core-bootstrap.test-support.browser-mocks :as browser-mocks]
            [hyperopen.test-support.async :as async-support]))

(deftest build-asset-selector-markets-cache-sorts-and-normalizes-test
  (let [markets [{:key "perp:ETH"
                  :coin "ETH"
                  :symbol "ETH-USDC"
                  :base "ETH"
                  :quote "USDC"
                  :market-type :perp
                  :dex "hyna"
                  :idx 3
                 :perp-dex-index 1
                 :asset-id 110003
                 :hip3? true
                 :hip3-eligible? false
                 :margin-mode :strict-isolated
                 :only-isolated? true
                 :volume24h 1000
                 :mark 1900.1}
                 {:key "spot:PURR/USDC"
                  :coin "PURR/USDC"
                  :symbol "PURR/USDC"
                  :base "PURR"
                  :quote "USDC"
                  :market-type :spot
                  :idx 0
                  :asset-id 0
                  :volume24h 2000
                  :mark 0.21}]
        state {:asset-selector {:sort-by :volume
                                :sort-direction :desc}}
        cached (markets-cache/build-asset-selector-markets-cache markets state)]
    (is (= ["PURR/USDC" "ETH-USDC"] (mapv :symbol cached)))
    (is (= [:spot :perp] (mapv :market-type cached)))
    (is (= [0 110003] (mapv :asset-id cached)))
    (is (= [0 1] (mapv :cache-order cached)))
    (is (nil? (:mark (first cached))))
    (is (false? (:hip3-eligible? (second cached))))
    (is (= :strict-isolated (:margin-mode (second cached))))
    (is (true? (:only-isolated? (second cached))))))

(deftest restore-asset-selector-markets-cache-state-hydrates-when-empty-test
  (let [cached-markets [{:key "perp:ETH"
                         :coin "ETH"
                         :symbol "ETH-USDC"
                         :base "ETH"
                         :market-type :perp
                         :asset-id 7}
                        {:key "spot:PURR/USDC"
                         :coin "PURR/USDC"
                         :symbol "PURR/USDC"
                         :base "PURR"
                         :market-type :spot}]
        state {:active-asset "ETH"
               :active-market nil
               :asset-selector {:markets []
                                :market-by-key {}
                                :market-index-by-key {}
                                :phase :bootstrap}}
        result (markets-cache/restore-asset-selector-markets-cache-state
                state
                cached-markets
                markets/resolve-market-by-coin)]
    (is (= cached-markets (get-in result [:asset-selector :markets])))
    (is (= {"perp:ETH" 0
            "spot:PURR/USDC" 1}
           (get-in result [:asset-selector :market-index-by-key])))
    (is (= "ETH" (get-in result [:active-market :coin])))
    (is (= 7 (get-in result [:active-market :asset-id])))
    (is (= true (get-in result [:asset-selector :cache-hydrated?])))))

(deftest restore-asset-selector-markets-cache-state-keeps-existing-markets-test
  (let [existing-market {:key "perp:BTC"
                         :coin "BTC"
                         :symbol "BTC-USDC"
                         :base "BTC"
                         :market-type :perp}
        state {:asset-selector {:markets [existing-market]
                                :market-by-key {"perp:BTC" existing-market}
                                :phase :full}}
        result (markets-cache/restore-asset-selector-markets-cache-state
                state
                [{:key "perp:ETH" :coin "ETH" :symbol "ETH-USDC" :base "ETH"}]
                markets/resolve-market-by-coin)]
    (is (= state result))))

(deftest restore-asset-selector-markets-cache-state-upgrades-existing-active-market-from-cache-test
  (let [cached-market {:key "perp:xyz:SILVER"
                       :coin "xyz:SILVER"
                       :symbol "SILVER-USDC"
                       :base "SILVER"
                       :dex "xyz"
                       :maxLeverage 25
                       :market-type :perp
                       :asset-id 110001}
        state {:active-asset "xyz:SILVER"
               :active-market {:coin "xyz:SILVER"
                               :symbol "SILVER-USDC"
                               :dex "xyz"
                               :market-type :perp}
               :asset-selector {:markets []
                                :market-by-key {}
                                :market-index-by-key {}
                                :phase :bootstrap}}
        result (markets-cache/restore-asset-selector-markets-cache-state
                state
                [cached-market]
                markets/resolve-market-by-coin)]
    (is (= cached-market
           (get-in result [:asset-selector :market-by-key "perp:xyz:SILVER"])))
    (is (= "perp:xyz:SILVER"
           (get-in result [:active-market :key])))
    (is (= 25
           (get-in result [:active-market :maxLeverage])))
    (is (= 110001
           (get-in result [:active-market :asset-id])))
    (is (= true
           (get-in result [:asset-selector :cache-hydrated?])))))

(deftest persist-and-load-asset-selector-markets-cache-roundtrip-test
  (async done
    (browser-mocks/with-test-indexed-db
      (fn []
        (let [markets [{:key "perp:ETH"
                        :coin "ETH"
                        :symbol "ETH-USDC"
                        :base "ETH"
                        :quote "USDC"
                        :market-type :perp
                        :volume24h 1000}
                       {:key "spot:PURR/USDC"
                        :coin "PURR/USDC"
                        :symbol "PURR/USDC"
                        :base "PURR"
                        :quote "USDC"
                        :market-type :spot
                        :volume24h 2000}]
              state {:asset-selector {:sort-by :volume
                                      :sort-direction :desc}}]
          (-> (markets-cache/persist-asset-selector-markets-cache! markets state)
              (.then (fn [persisted?]
                       (is (true? persisted?))
                       (-> (markets-cache/load-asset-selector-markets-cache)
                           (.then (fn [cached]
                                    (is (= ["PURR/USDC" "ETH-USDC"]
                                           (mapv :symbol cached)))
                                    (is (= [:spot :perp]
                                           (mapv :market-type cached)))
                                    (done)))
                           (.catch (async-support/unexpected-error done)))))
              (.catch (async-support/unexpected-error done))))))))

(deftest load-asset-selector-markets-cache-falls-back-to-local-storage-test
  (async done
    (let [record {:saved-at-ms 50
                  :rows [{:key "perp:ETH"
                          :coin "ETH"
                          :symbol "ETH-USDC"
                          :base "ETH"
                          :market-type :perp}]}]
      (-> (markets-cache/load-asset-selector-markets-cache
           {:load-indexed-db-fn (fn []
                                  nil)
            :load-local-storage-fn (fn []
                                     record)})
          (.then (fn [cached]
                   (is (= [{:key "perp:ETH"
                            :coin "ETH"
                            :symbol "ETH-USDC"
                            :base "ETH"
                            :market-type :perp}]
                          cached))
                   (done)))
          (.catch (async-support/unexpected-error done))))))
