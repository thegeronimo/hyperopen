(ns hyperopen.portfolio.optimizer.universe-keyboard-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]))

(deftest keyboard-navigation-wraps-active-candidate-test
  (let [base-state {:portfolio-ui {:optimizer {:universe-search-active-index 0}}}]
    (is (= [[:effects/save
             [:portfolio-ui :optimizer :universe-search-active-index]
             1]]
           (actions/handle-portfolio-optimizer-universe-search-keydown
            base-state
            "ArrowDown"
            ["perp:BTC" "perp:ETH"])))
    (is (= [[:effects/save
             [:portfolio-ui :optimizer :universe-search-active-index]
             1]]
           (actions/handle-portfolio-optimizer-universe-search-keydown
            base-state
            "ArrowUp"
            ["perp:BTC" "perp:ETH"])))
    (is (= []
           (actions/handle-portfolio-optimizer-universe-search-keydown
            base-state
            "ArrowDown"
            [])))))

(deftest enter-adds-active-candidate-test
  (let [eth-instrument {:instrument-id "perp:ETH"
                        :market-type :perp
                        :coin "ETH"
                        :shortable? true
                        :symbol "ETH-USDC"}
        state {:portfolio-ui {:optimizer {:universe-search-active-index 1}}
               :portfolio {:optimizer {:draft {:universe []}}}
               :asset-selector
               {:market-by-key {"perp:BTC" {:key "perp:BTC"
                                            :market-type :perp
                                            :coin "BTC"}
                                "perp:ETH" {:key "perp:ETH"
                                            :market-type :perp
                                            :coin "ETH"
                                            :symbol "ETH-USDC"}}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :universe]
               [eth-instrument]]
              [[:portfolio-ui :optimizer :universe-search-query]
               ""]
              [[:portfolio-ui :optimizer :universe-search-active-index]
               0]
              [[:portfolio :optimizer :history-prefetch]
               {:queue [eth-instrument]
                :active-instrument-id nil
                :by-instrument-id
                {"perp:ETH" {:status :queued
                             :started-at-ms nil
                             :completed-at-ms nil
                             :error nil
                             :warnings []}}}]
              [[:portfolio :optimizer :draft :metadata :dirty?]
               true]]]
            [:effects/load-portfolio-optimizer-history
             {:source :selection-prefetch
              :queue? true
              :merge? true}]]
           (actions/handle-portfolio-optimizer-universe-search-keydown
            state
            "Enter"
            ["perp:BTC" "perp:ETH"])))))
