(ns hyperopen.utils.data-normalization-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.utils.data-normalization :refer [normalize-asset-contexts preprocess-webdata2]]))

(deftest preprocess-webdata2-test
  (testing "preprocess-webdata2 returns correct [meta assetCtxs] vector"
    (let [input {:meta {:universe [{:name "BTC"}] :marginTables [{:id 1}]}
                 :assetCtxs [{:foo "bar"}]}
          [meta asset-ctxs] (preprocess-webdata2 input)]
      (is (= meta {:universe [{:name "BTC"}] :marginTables [{:id 1}]}))
      (is (= asset-ctxs [{:foo "bar"}])))))

(deftest normalize-asset-contexts-test
  (testing "normalize-asset-contexts filters and normalizes correctly"
    (let [universe [{:name "BTC" :marginTableId 1}
                    {:name "ETH" :marginTableId 2}]
          marginTables [{1 {:leverage 10}} {2 {:leverage 5}}]
          funding [{:dayNtlVlm "100" :openInterest "50"}
                   {:dayNtlVlm "0" :openInterest "0"}]
          data [{:universe universe :marginTables marginTables} funding]
          result (normalize-asset-contexts data)]
      (is (contains? result :BTC))
      (is (not (contains? result :ETH)))
      (is (= (get-in result [:BTC :margin]) {:leverage 10}))
      (is (= (get-in result [:BTC :funding :dayNtlVlm]) "100"))))

  (testing "normalize-asset-contexts handles empty input"
    (let [data [{:universe [] :marginTables []} []]
          result (normalize-asset-contexts data)]
      (is (= result {})))))

;; Tests are run by the test runner, not automatically on namespace load 