(ns hyperopen.views.account-info.cache-keys-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.cache-keys :as cache-keys]))

(deftest rows-signature-is-stable-for-equivalent-content-test
  (let [rows [{:id 1 :coin "ETH" :px "100"}
              {:id 2 :coin "BTC" :px "90"}]
        cloned (into [] rows)]
    (is (= (cache-keys/rows-signature rows)
           (cache-keys/rows-signature cloned)))
    (is (not= (cache-keys/rows-signature rows)
              (cache-keys/rows-signature (assoc-in cloned [1 :px] "91"))))))

(deftest rows-match-state-uses-identity-fast-path-and-signature-fallback-test
  (let [rows [{:id 1 :coin "ETH"}]
        cached-signature {:count 1 :rolling-hash 123 :xor-hash 7}
        identity-calls (atom 0)]
    (with-redefs [cache-keys/rows-signature
                  (fn [_rows]
                    (swap! identity-calls inc)
                    {:count 1 :rolling-hash 999 :xor-hash 99})]
      (is (= {:same-input? true :signature cached-signature}
             (cache-keys/rows-match-state rows rows cached-signature)))
      (is (= 0 @identity-calls))
      (is (= {:same-input? true
              :signature {:count 1 :rolling-hash 999 :xor-hash 99}}
             (cache-keys/rows-match-state rows rows nil)))
      (is (= 1 @identity-calls))))
  (let [rows [{:id 1 :coin "ETH"}]
        cloned (into [] rows)
        signature (cache-keys/rows-signature rows)
        changed (assoc-in cloned [0 :coin] "BTC")]
    (is (= {:same-input? true
            :signature signature}
           (cache-keys/rows-match-state cloned rows signature)))
    (is (false? (:same-input? (cache-keys/rows-match-state changed rows signature))))))

(deftest value-match-state-falls-back-to-signature-under-identity-churn-test
  (let [market-by-key {"spot:@230" {:coin "@230" :symbol "SOL/USDC" :base "SOL"}}
        signature (cache-keys/value-signature market-by-key)
        churned (into {} market-by-key)
        changed {"spot:@230" {:coin "@230" :symbol "SOL/USDC" :base "SOL"}
                 "spot:@231" {:coin "@231" :symbol "ETH/USDC" :base "ETH"}}]
    (is (= {:same-input? true
            :signature signature}
           (cache-keys/value-match-state churned market-by-key signature)))
    (is (false? (:same-input? (cache-keys/value-match-state changed market-by-key signature))))))
