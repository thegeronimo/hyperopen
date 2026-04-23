(ns hyperopen.portfolio.optimizer.application.orderbook-loader-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.orderbook-loader :as orderbook-loader]))

(deftest build-orderbook-subscription-plan-requests-only-needed-missing-or-stale-books-test
  (let [state {:orderbooks {"BTC" {:timestamp 9900
                                   :render {:best-bid {:px-num 99}
                                            :best-ask {:px-num 101}}}
                            "ETH" {:timestamp 2000
                                   :bids [{:px "1990"}]
                                   :asks [{:px "2010"}]}}}
        plan (orderbook-loader/build-orderbook-subscription-plan
              state
              [{:coin "BTC" :needs-cost-estimate? true}
               {:coin "ETH" :needs-cost-estimate? true}
               {:coin "PURR" :needs-cost-estimate? true}
               {:coin "IGNORED" :needs-cost-estimate? false}
               {:needs-cost-estimate? true}]
              {:now-ms 10000
               :stale-after-ms 1000})]
    (is (= ["ETH" "PURR"] (:coins-to-subscribe plan)))
    (is (= [[:effects/subscribe-orderbook "ETH"]
            [:effects/subscribe-orderbook "PURR"]]
           (:effects plan)))
    (is (= {:best-bid {:px-num 99}
            :best-ask {:px-num 101}
            :source :live-orderbook
            :stale? false}
           (get-in plan [:available-by-coin "BTC"])))
    (is (= {:best-bid {:px "1990"}
            :best-ask {:px "2010"}
            :source :stale-orderbook
            :stale? true
            :age-ms 8000}
           (get-in plan [:available-by-coin "ETH"])))
    (is (= [{:code :missing-orderbook-coin}
            {:code :missing-orderbook
             :coin "PURR"}]
           (:warnings plan)))))

(deftest orderbook-cost-context-uses-live-book-or-fallback-label-test
  (is (= {:coin "BTC"
          :source :live-orderbook
          :best-bid {:px-num 99}
          :best-ask {:px-num 101}
          :stale? false}
         (orderbook-loader/orderbook-cost-context
          {:orderbooks {"BTC" {:timestamp 10000
                               :render {:best-bid {:px-num 99}
                                        :best-ask {:px-num 101}}}}}
          "BTC"
          {:now-ms 10500
           :stale-after-ms 1000})))
  (is (= {:coin "PURR"
          :source :fallback-cost-assumption
          :best-bid nil
          :best-ask nil
          :stale? true
          :fallback-bps 25}
         (orderbook-loader/orderbook-cost-context
          {:orderbooks {}}
          "PURR"
          {:fallback-bps 25}))))
