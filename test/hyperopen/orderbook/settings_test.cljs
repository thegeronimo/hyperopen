(ns hyperopen.orderbook.settings-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.orderbook.settings :as settings]
            [hyperopen.platform :as platform]))

(defn- local-storage-get-stub
  [values]
  (fn [key]
    (get values key)))

(deftest restore-orderbook-ui-loads-valid-persisted-values-test
  (let [store (atom {:orderbook-ui {:size-unit-dropdown-visible? true}})]
    (with-redefs [platform/local-storage-get (local-storage-get-stub {"orderbook-size-unit" "quote"
                                                                      "orderbook-active-tab" "trades"
                                                                      "orderbook-price-aggregation-by-coin" "{\"BTC\":\"sf3\",\"ETH\":\"sf2\"}"})]
      (settings/restore-orderbook-ui! store))
    (is (= :quote (get-in @store [:orderbook-ui :size-unit])))
    (is (= :trades (get-in @store [:orderbook-ui :active-tab])))
    (is (= {"BTC" :sf3 "ETH" :sf2}
           (get-in @store [:orderbook-ui :price-aggregation-by-coin])))
    (is (true? (get-in @store [:orderbook-ui :size-unit-dropdown-visible?])))))

(deftest restore-orderbook-ui-falls-back-for-invalid-or-missing-storage-test
  (let [store (atom {:orderbook-ui {}})]
    (with-redefs [platform/local-storage-get (local-storage-get-stub {"orderbook-size-unit" "invalid"
                                                                      "orderbook-active-tab" "invalid"
                                                                      "orderbook-price-aggregation-by-coin" ""})]
      (settings/restore-orderbook-ui! store))
    (is (= :base (get-in @store [:orderbook-ui :size-unit])))
    (is (= :orderbook (get-in @store [:orderbook-ui :active-tab])))
    (is (= {} (get-in @store [:orderbook-ui :price-aggregation-by-coin])))))

(deftest restore-orderbook-ui-ignores-invalid-aggregation-entries-test
  (let [store (atom {:orderbook-ui {}})]
    (with-redefs [platform/local-storage-get (local-storage-get-stub {"orderbook-price-aggregation-by-coin" "{\"BTC\":\"sf4\",\"ETH\":\"bogus\",\"\":\"sf3\",\"SOL\":2,\"DOGE\":\"sf2\"}"})]
      (settings/restore-orderbook-ui! store))
    (is (= {"BTC" :sf4 "DOGE" :sf2}
           (get-in @store [:orderbook-ui :price-aggregation-by-coin])))))

(deftest restore-orderbook-ui-recovers-from-json-parse-errors-test
  (let [store (atom {:orderbook-ui {}})]
    (with-redefs [platform/local-storage-get (local-storage-get-stub {"orderbook-price-aggregation-by-coin" "{"})]
      (settings/restore-orderbook-ui! store))
    (is (= {} (get-in @store [:orderbook-ui :price-aggregation-by-coin])))))

(deftest select-orderbook-size-unit-normalizes-and-persists-test
  (is (= [[:effects/save [:orderbook-ui :size-unit] :quote]
          [:effects/save [:orderbook-ui :size-unit-dropdown-visible?] false]
          [:effects/local-storage-set "orderbook-size-unit" "quote"]]
         (settings/select-orderbook-size-unit {} :quote)))
  (is (= [[:effects/save [:orderbook-ui :size-unit] :base]
          [:effects/save [:orderbook-ui :size-unit-dropdown-visible?] false]
          [:effects/local-storage-set "orderbook-size-unit" "base"]]
         (settings/select-orderbook-size-unit {} :something-else))))

(deftest select-orderbook-price-aggregation-persists-and-resubscribes-with-active-coin-test
  (is (= [[:effects/save [:orderbook-ui :price-aggregation-by-coin] {"BTC" :sf3}]
          [:effects/save [:orderbook-ui :price-aggregation-dropdown-visible?] false]
          [:effects/local-storage-set-json "orderbook-price-aggregation-by-coin" {"BTC" :sf3}]
          [:effects/subscribe-orderbook "BTC"]]
         (settings/select-orderbook-price-aggregation {:active-asset "BTC"
                                                       :orderbook-ui {:price-aggregation-by-coin {}}}
                                                      :sf3))))

(deftest select-orderbook-price-aggregation-normalizes-mode-and-skips-resubscribe-without-active-coin-test
  (is (= [[:effects/save [:orderbook-ui :price-aggregation-by-coin] {"ETH" :sf2}]
          [:effects/save [:orderbook-ui :price-aggregation-dropdown-visible?] false]
          [:effects/local-storage-set-json "orderbook-price-aggregation-by-coin" {"ETH" :sf2}]]
         (settings/select-orderbook-price-aggregation {:active-asset nil
                                                       :orderbook-ui {:price-aggregation-by-coin {"ETH" :sf2}}}
                                                      :invalid-mode))))

(deftest select-orderbook-tab-normalizes-keyword-string-and-invalid-inputs-test
  (is (= [[:effects/save [:orderbook-ui :active-tab] :trades]
          [:effects/save [:orderbook-ui :size-unit-dropdown-visible?] false]
          [:effects/save [:orderbook-ui :price-aggregation-dropdown-visible?] false]
          [:effects/local-storage-set "orderbook-active-tab" "trades"]]
         (settings/select-orderbook-tab {} "trades")))
  (is (= [[:effects/save [:orderbook-ui :active-tab] :orderbook]
          [:effects/save [:orderbook-ui :size-unit-dropdown-visible?] false]
          [:effects/save [:orderbook-ui :price-aggregation-dropdown-visible?] false]
          [:effects/local-storage-set "orderbook-active-tab" "orderbook"]]
         (settings/select-orderbook-tab {} :unknown))))
