(ns hyperopen.core-public-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.asset-selector.actions :as asset-actions]
            [hyperopen.core.compat :as compat]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.order.actions :as order-actions]))

(deftest core-compat-exposes-public-action-aliases-test
  (let [state {:active-asset "ETH"
               :asset-selector {:open? true}
               :order-form {:entry-mode :pro
                            :type :stop-market}
               :active-market {:mark-price 101.0
                               :mid-price 101.0}}
        market {:coin "BTC" :symbol "BTC"}
        select-asset-effects (asset-actions/select-asset state market)
        select-order-entry-effects (order-actions/select-order-entry-mode state :market)]
    (is (= select-asset-effects
           (compat/select-asset state market)))
    (is (= select-order-entry-effects
           (compat/select-order-entry-mode state :market)))
    (is (= [[:effects/fetch-asset-selector-markets]]
           (compat/refresh-asset-markets state)))
    (is (= [[:effects/api-load-user-data "0xabc"]]
           (compat/load-user-data state "0xabc")))
    ;; Merged over the modal defaults, which modal-state-test pins literally,
    ;; so a new default key is checked once there rather than in every opener.
    (is (= [[:effects/load-surface-module :funding-modal]
            [:effects/save [:funding-ui :modal]
             (merge (funding-actions/default-funding-modal-state)
                    {:open? true
                     :mode :legacy
                     :legacy-kind :history})]]
           (compat/set-funding-modal state :history)))))
