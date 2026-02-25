(ns hyperopen.views.asset-icon-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.views.asset-icon :as asset-icon]))

(deftest market-icon-key-normalization-and-alias-test
  (testing "keeps direct keys for regular perps and spot symbols"
    (is (= "BTC" (asset-icon/market-icon-key {:coin "BTC" :base "BTC"})))
    (is (= "PURR_spot" (asset-icon/market-icon-key {:coin "PURR/USDC" :base "PURR"})))
    (is (= "MEOW_spot" (asset-icon/market-icon-key {:coin "@123"
                                                    :symbol "MEOW/USDC"
                                                    :base "MEOW"
                                                    :market-type :spot})))
    (is (= "MEOW_spot" (asset-icon/market-icon-key {:coin "@123"
                                                    :base "MEOW"
                                                    :market-type :spot}))))

  (testing "applies k-prefix normalization but preserves km namespace"
    (is (= "PEPE" (asset-icon/market-icon-key {:coin "kPEPE" :base "PEPE"})))
    (is (= "km:BTC" (asset-icon/market-icon-key {:coin "km:BTC" :base "BTC"}))))

  (testing "maps known cross-dex aliases to available icon keys"
    (is (= "xyz:MSFT" (asset-icon/market-icon-key {:coin "cash:MSFT" :base "MSFT"})))
    (is (= "flx:COPPER" (asset-icon/market-icon-key {:coin "xyz:COPPER" :base "COPPER"})))
    (is (= "ADA" (asset-icon/market-icon-key {:coin "hyna:ADA" :base "ADA"}))))

  (testing "retains namespaced keys when no alias is needed"
    (is (= "xyz:XYZ100" (asset-icon/market-icon-key {:coin "xyz:XYZ100" :base "XYZ100"})))))

(deftest market-icon-url-builds-canonical-path-test
  (is (= "https://app.hyperliquid.xyz/coins/xyz:MSFT.svg"
         (asset-icon/market-icon-url {:coin "cash:MSFT" :base "MSFT"})))
  (is (= "https://app.hyperliquid.xyz/coins/flx:COPPER.svg"
         (asset-icon/market-icon-url {:coin "xyz:COPPER" :base "COPPER"})))
  (is (= "https://app.hyperliquid.xyz/coins/PURR_spot.svg"
         (asset-icon/market-icon-url {:coin "PURR/USDC" :base "PURR"})))
  (is (= "https://app.hyperliquid.xyz/coins/MEOW_spot.svg"
         (asset-icon/market-icon-url {:coin "@123"
                                      :symbol "MEOW/USDC"
                                      :base "MEOW"
                                      :market-type :spot}))))
