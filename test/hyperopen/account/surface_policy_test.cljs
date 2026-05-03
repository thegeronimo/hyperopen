(ns hyperopen.account.surface-policy-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.surface-policy :as surface-policy]))

(def ^:private address
  "0x1111111111111111111111111111111111111111")

(deftest normalize-dex-names-filters-shape-guards-and-dedupes-test
  (is (= ["dex-a" "dex-b"]
         (surface-policy/normalize-dex-names
          ["dex-a" " " nil "dex-b" "dex-a"])))
  (is (= ["dex-c" "dex-d"]
         (surface-policy/normalize-dex-names
          {:dex-names ["dex-c" "" "dex-d" "dex-c"]})))
  (is (= []
         (surface-policy/normalize-dex-names nil))))

(deftest websocket-topic-helpers-use-health-projection-selectors-test
  (let [state {:websocket {:health {:transport {:state :connected
                                                :freshness :live}
                                    :streams {["openOrders" nil address nil nil]
                                              {:topic "openOrders"
                                               :status :n-a
                                               :subscribed? true
                                               :descriptor {:type "openOrders"
                                                            :user "0x1111111111111111111111111111111111111111"}}
                                              ["clearinghouseState" nil address "vault" nil]
                                              {:topic "clearinghouseState"
                                               :status :idle
                                               :subscribed? true
                                               :descriptor {:type "clearinghouseState"
                                                            :user "0x1111111111111111111111111111111111111111"
                                                            :dex "vault"}}}}}}]
    (is (true? (surface-policy/topic-usable-for-address?
                state
                "openOrders"
                "0x1111111111111111111111111111111111111111")))
    (is (true? (surface-policy/topic-subscribed-for-address-and-dex?
                state
                "clearinghouseState"
                "0x1111111111111111111111111111111111111111"
                "vault")))
    (is (false? (surface-policy/topic-usable-for-address-and-dex?
                 state
                 "clearinghouseState"
                 "0x1111111111111111111111111111111111111111"
                 "vault")))))

(deftest spot-refresh-surface-active-follows-route-tab-and-modal-rules-test
  (is (true? (surface-policy/spot-refresh-surface-active?
              {:router {:path "/trade"}
               :account-info {:selected-tab :balances}})))
  (is (true? (surface-policy/spot-refresh-surface-active?
              {:router {:path "/trade"}
               :account-info {:selected-tab :outcomes}})))
  (is (false? (surface-policy/spot-refresh-surface-active?
               {:router {:path "/trade"}
                :account-info {:selected-tab :funding-history}})))
  (is (true? (surface-policy/spot-refresh-surface-active?
              {:router {:path "/portfolio"}
               :account-info {:selected-tab :funding-history}
               :funding-ui {:modal {:open? true}}}))))
