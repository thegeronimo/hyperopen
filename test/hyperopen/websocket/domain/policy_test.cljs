(ns hyperopen.websocket.domain.policy-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.websocket.domain.policy :as policy]))

(deftest topic-tier-policy-defaults-test
  (testing "Known topics map to expected tiers"
    (is (= :market (policy/topic->tier policy/default-channel-tier-policy "trades")))
    (is (= :lossless (policy/topic->tier policy/default-channel-tier-policy "userFills"))))
  (testing "Unknown topics default to :lossless"
    (is (= :lossless (policy/topic->tier policy/default-channel-tier-policy "newTopic")))))

(deftest topic-tier-policy-extension-test
  (let [extended (policy/merge-tier-policy policy/default-channel-tier-policy
                                           {"newTicker" :market
                                            "customLedger" :lossless})]
    (testing "Policy can be extended without editing runtime case logic"
      (is (= :market (policy/topic->tier extended "newTicker")))
      (is (= :lossless (policy/topic->tier extended "customLedger")))
      (is (= :lossless (policy/topic->tier extended "unknown"))))))

