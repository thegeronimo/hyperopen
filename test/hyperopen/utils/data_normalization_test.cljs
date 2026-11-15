(ns hyperopen.utils.data-normalization-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.utils.data-normalization :refer [build-asset-context-meta-index
                                                        normalize-asset-contexts
                                                        patch-asset-contexts
                                                        preprocess-webdata2]]))

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

(deftest build-asset-context-meta-index-test
  (let [meta {:universe [{:name "BTC" :marginTableId 1}
                         {:name "ETH" :marginTableId 2}]
              :marginTables [{1 {:leverage 10}}
                             {2 {:leverage 5}}]}
        result (build-asset-context-meta-index meta)]
    (is (= [{:idx 0
             :asset-key :BTC
             :info {:name "BTC" :marginTableId 1}
             :margin {:leverage 10}}
            {:idx 1
             :asset-key :ETH
             :info {:name "ETH" :marginTableId 2}
             :margin {:leverage 5}}]
           result))))

(deftest patch-asset-contexts-incremental-update-test
  (let [meta {:universe [{:name "BTC" :marginTableId 1}
                         {:name "ETH" :marginTableId 2}]
              :marginTables [{1 {:leverage 10}}
                             {2 {:leverage 5}}]}
        meta-index (build-asset-context-meta-index meta)
        btc-active {:dayNtlVlm "100" :openInterest "10"}
        eth-inactive {:dayNtlVlm "0" :openInterest "0"}
        eth-active {:dayNtlVlm "200" :openInterest "20"}
        btc-inactive {:dayNtlVlm "0" :openInterest "0"}
        first-pass (patch-asset-contexts {} meta-index [btc-active eth-inactive])
        second-pass (patch-asset-contexts first-pass meta-index [btc-active eth-inactive])
        third-pass (patch-asset-contexts second-pass meta-index [btc-active eth-active])
        fourth-pass (patch-asset-contexts third-pass meta-index [btc-inactive eth-active])]
    (is (contains? first-pass :BTC))
    (is (not (contains? first-pass :ETH)))
    (is (identical? first-pass second-pass))
    (is (identical? (get second-pass :BTC) (get third-pass :BTC)))
    (is (contains? third-pass :ETH))
    (is (not (contains? fourth-pass :BTC)))
    (is (contains? fourth-pass :ETH))))
