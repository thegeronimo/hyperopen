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

(deftest websocket-topic-helpers-accept-base-dex-selector-test
  (let [state {:websocket {:health {:transport {:state :connected
                                                :freshness :live}
                                    :streams {["clearinghouseState" nil address "" nil]
                                              {:topic "clearinghouseState"
                                               :status :n-a
                                               :subscribed? true
                                               :descriptor {:type "clearinghouseState"
                                                            :user address
                                                            :dex ""}}}}}}]
    (is (true? (surface-policy/topic-usable-for-address-and-dex?
                state
                "clearinghouseState"
                address
                "")))
    (is (true? (surface-policy/topic-subscribed-for-address-and-dex?
                state
                "clearinghouseState"
                address
                "")))
    (is (nil? (surface-policy/topic-usable-for-address-and-dex?
               state
               "clearinghouseState"
               address
               nil)))))
