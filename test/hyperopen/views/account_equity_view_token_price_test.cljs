(ns hyperopen.views.account-equity-view-token-price-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-equity-view :as view]))

(deftest token-price-usd-respects-price-source-precedence-and-fallbacks-test
  (let [token-price-usd @#'view/token-price-usd]
    (is (= 3
           (token-price-usd {"ABC" {:coin "ABC"
                                    :total-balance "2"
                                    :usdc-value "6"}}
                            {"spot:ABC" {:base "ABC"
                                         :quote "USDC"
                                         :mark "5"}}
                            "ABC")))
    (is (= 5
           (token-price-usd {}
                            {"spot:ABC" {:base "ABC"
                                         :quote "USDC"
                                         :mark "5"}}
                            "ABC")))
    (is (= 0.5
           (token-price-usd {}
                            {"spot:USDT" {:base "USDC"
                                          :quote "USDT"
                                          :mark "2"}}
                            "USDT")))
    (is (= 4
           (token-price-usd {}
                            {"spot:ABC/USDC" {:coin "ABC/USDC"
                                              :market-type :spot
                                              :base "ABC"
                                              :quote "USDC"
                                              :mark "4"}}
                            "ABC")))
    (is (= 1
           (token-price-usd {}
                            {}
                            "USDH")))
    (is (nil? (token-price-usd {}
                               {"spot:ABC" {:base "ABC"
                                            :quote "BTC"
                                            :mark "5"}}
                               "ABC")))
    (is (nil? (token-price-usd {}
                               {"spot:ZERO" {:base "ZERO"
                                             :quote "USDC"
                                             :mark "0"}}
                               "ZERO")))
    (is (nil? (token-price-usd {"ZERO" {:coin "ZERO"
                                        :total-balance "0"
                                        :usdc-value "10"}}
                               {}
                               "ZERO")))
    (is (nil? (token-price-usd {}
                               {}
                               nil)))))
