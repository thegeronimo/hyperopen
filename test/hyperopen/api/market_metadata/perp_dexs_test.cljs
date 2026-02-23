(ns hyperopen.api.market-metadata.perp-dexs-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.api.market-metadata.perp-dexs :as perp-dexs]))

(deftest normalize-perp-dex-payload-supports-map-shapes-test
  (let [canonical (perp-dexs/normalize-perp-dex-payload
                   {:dex-names ["dex-a"]
                    :fee-config-by-name {"dex-a" {:deployer-fee-scale 0.25}}})
        legacy (perp-dexs/normalize-perp-dex-payload
                {:perp-dexs ["dex-b"]
                 :perp-dex-fee-config-by-name {"dex-b" {:deployer-fee-scale 0.5}}})]
    (is (= {:dex-names ["dex-a"]
            :fee-config-by-name {"dex-a" {:deployer-fee-scale 0.25}}}
           canonical))
    (is (= {:dex-names ["dex-b"]
            :fee-config-by-name {"dex-b" {:deployer-fee-scale 0.5}}}
           legacy))))

(deftest normalize-perp-dex-payload-supports-sequential-response-test
  (let [payload [{:name "vault"}
                 {:name "scaled"
                  :deployerFeeScale "0.25"}
                 {:name "partner"
                  :deployer-fee-scale 0.1}
                 {:name ""}
                 {:foo "bar"}
                 "dex-from-string"]
        normalized (perp-dexs/normalize-perp-dex-payload payload)]
    (is (= ["vault" "scaled" "partner" "dex-from-string"]
           (:dex-names normalized)))
    (is (= {"scaled" {:deployer-fee-scale 0.25}
            "partner" {:deployer-fee-scale 0.1}}
           (:fee-config-by-name normalized)))))

(deftest payload->dex-names-returns-deterministic-defaults-test
  (is (= ["dex-a" "dex-b"]
         (perp-dexs/payload->dex-names {:dex-names ["dex-a" "dex-b"]})))
  (is (= ["dex-a"]
         (perp-dexs/payload->dex-names [{:name "dex-a"} {:name ""}])))
  (is (= []
         (perp-dexs/payload->dex-names nil))))
