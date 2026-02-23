(ns hyperopen.schema.api-market-contracts-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.schema.api-market-contracts :as api-market-contracts]))

(deftest assert-normalized-perp-dex-metadata-accepts-canonical-shape-test
  (let [payload {:dex-names ["vault" "scaled"]
                 :fee-config-by-name {"scaled" {:deployer-fee-scale 0.25}}}]
    (is (true? (api-market-contracts/normalized-perp-dex-metadata-valid? payload)))
    (is (= payload
           (api-market-contracts/assert-normalized-perp-dex-metadata!
            payload
            {:boundary :test/contracts})))))

(deftest assert-normalized-perp-dex-metadata-rejects-non-canonical-top-level-shape-test
  (is (thrown-with-msg?
       js/Error
       #"perp DEX metadata contract validation failed"
       (api-market-contracts/assert-normalized-perp-dex-metadata!
        {:perp-dexs ["vault"]}
        {:boundary :test/contracts}))))

(deftest assert-normalized-perp-dex-metadata-rejects-fee-config-key-not-in-dex-names-test
  (is (thrown-with-msg?
       js/Error
       #"perp DEX metadata contract validation failed"
       (api-market-contracts/assert-normalized-perp-dex-metadata!
        {:dex-names ["vault"]
         :fee-config-by-name {"scaled" {:deployer-fee-scale 0.25}}}
        {:boundary :test/contracts}))))

(deftest assert-normalized-perp-dex-metadata-rejects-non-numeric-fee-scale-test
  (is (thrown-with-msg?
       js/Error
       #"perp DEX metadata contract validation failed"
       (api-market-contracts/assert-normalized-perp-dex-metadata!
        {:dex-names ["vault"]
         :fee-config-by-name {"vault" {:deployer-fee-scale "0.25"}}}
        {:boundary :test/contracts}))))
