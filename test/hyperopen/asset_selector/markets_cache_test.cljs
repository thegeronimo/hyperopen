(ns hyperopen.asset-selector.markets-cache-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.asset-selector.markets :as markets]
            [hyperopen.asset-selector.markets-cache :as markets-cache]
            [hyperopen.core-bootstrap.test-support.browser-mocks :as browser-mocks]
            [hyperopen.platform :as platform]
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
                  :mark 0.21}
                 {:key "outcome:0"
                  :coin "#0"
                  :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                  :title "BTC above 78213 on May 3 at 2:00 AM?"
                  :base "BTC"
                  :quote "USDH"
                  :market-type :outcome
                  :category :outcome
                  :outcome-id 0
                  :period "1d"
                  :expiry-ms 1777788000000
                  :target-price "78213"
                  :asset-id 100000000
                  :outcome-sides [{:side-index 0 :side-name "Yes" :coin "#0" :asset-id 100000000}
                                  {:side-index 1 :side-name "No" :coin "#1" :asset-id 100000001}]
                  :volume24h 3000
                  :mark 0.58}]
        state {:asset-selector {:sort-by :volume
                                :sort-direction :desc}}
        cached (markets-cache/build-asset-selector-markets-cache markets state)]
    (is (= ["BTC above 78213 on May 3 at 2:00 AM?" "PURR/USDC" "ETH-USDC"] (mapv :symbol cached)))
    (is (= [:outcome :spot :perp] (mapv :market-type cached)))
    (is (= [100000000 0 110003] (mapv :asset-id cached)))
    (is (= [0 1 2] (mapv :cache-order cached)))
    (is (= "1d" (:period (first cached))))
    (is (= [{:side-index 0 :side-name "Yes" :coin "#0" :asset-id 100000000}
            {:side-index 1 :side-name "No" :coin "#1" :asset-id 100000001}]
           (:outcome-sides (first cached))))
    (is (nil? (:mark (second cached))))
    (is (false? (:hip3-eligible? (nth cached 2))))
    (is (= :strict-isolated (:margin-mode (nth cached 2))))
    (is (true? (:only-isolated? (nth cached 2))))))

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

(deftest restore-asset-selector-markets-cache-state-defaults-expired-active-outcome-to-btc-test
  (let [expired-outcome {:key "outcome:1"
                         :coin "#10"
                         :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                         :base "BTC"
                         :market-type :outcome
                         :expiry-ms 1777788000000
                         :outcome-sides [{:side-index 0 :coin "#10"}
                                         {:side-index 1 :coin "#11"}]}
        btc-market {:key "perp:BTC"
                    :coin "BTC"
                    :symbol "BTC-USDC"
                    :base "BTC"
                    :market-type :perp}
        state {:active-asset "#10"
               :selected-asset "#10"
               :active-market nil
               :asset-selector {:markets []
                                :market-by-key {}
                                :market-index-by-key {}
                                :phase :bootstrap}}
        result (with-redefs [platform/now-ms (fn [] 1777874400000)]
                 (markets-cache/restore-asset-selector-markets-cache-state
                  state
                  [expired-outcome btc-market]
                  markets/resolve-market-by-coin))]
    (is (= "BTC" (:active-asset result)))
    (is (= "BTC" (:selected-asset result)))
    (is (= btc-market (:active-market result)))
    (is (= [expired-outcome btc-market]
           (get-in result [:asset-selector :markets])))))

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
