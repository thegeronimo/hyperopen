(ns hyperopen.workbench.scenes.account.portfolio-chart-scenes
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.workbench.support.state :as ws]
            [hyperopen.views.portfolio-view :as portfolio-view]))

(portfolio/configure-scenes
  {:title "Portfolio Chart"
   :collection :account})

(def ^:private time-a
  (.getTime (js/Date. 2026 1 19 2 4 0)))

(def ^:private time-b
  (.getTime (js/Date. 2026 1 26 8 30 0)))

(def ^:private time-c
  (.getTime (js/Date. 2026 2 3 11 15 0)))

(defn- portfolio-state
  [overrides]
  (ws/deep-merge
   (ws/build-state
    {:router {:path "/portfolio"}
     :account {:mode :classic}
     :portfolio-ui {:summary-scope :all
                    :summary-time-range :month
                    :chart-tab :returns
                    :account-info-tab :performance-metrics
                    :summary-scope-dropdown-open? false
                    :summary-time-range-dropdown-open? false
                    :performance-metrics-time-range-dropdown-open? false
                    :returns-benchmark-coins ["SPY" "QQQ"]
                    :returns-benchmark-coin "SPY"
                    :returns-benchmark-search ""
                    :returns-benchmark-suggestions-open? false}
     :portfolio {:summary-by-key {:month {:pnlHistory [[time-a 0]
                                                       [time-b 0]
                                                       [time-c 0]]
                                          :accountValueHistory [[time-a 100]
                                                                [time-b 112]
                                                                [time-c 124]]
                                          :vlm 2255561.85}}
                 :user-fees {:userCrossRate 0.00045
                             :userAddRate 0.00015
                             :dailyUserVlm [{:exchange 100
                                             :userCross 70
                                             :userAdd 30}
                                            {:exchange 50
                                             :userCross 20
                                             :userAdd 10}]}}
     :account-info {:selected-tab :balances
                    :loading false
                    :error nil
                    :hide-small-balances? false
                    :balances-sort {:column nil :direction :asc}
                    :positions-sort {:column nil :direction :asc}
                    :open-orders-sort {:column "Time" :direction :desc}}
     :orders {:open-orders []
              :open-orders-snapshot []
              :open-orders-snapshot-by-dex {}
              :fills []
              :fundings []
              :order-history []}
     :borrow-lend {:total-supplied-usd 0}
     :spot {:meta nil
            :clearinghouse-state nil}
     :perp-dex-clearinghouse {}
     :asset-selector {:markets [{:coin "SPY"
                                 :symbol "SPY"
                                 :market-type :spot
                                 :cache-order 1}
                                {:coin "QQQ"
                                 :symbol "QQQ"
                                 :market-type :spot
                                 :cache-order 2}]}
     :candles {"SPY" {:1h [{:t time-a :c 50}
                           {:t time-b :c 55}
                           {:t time-c :c 57}]}
               "QQQ" {:1h [{:t time-a :c 70}
                           {:t time-b :c 73}
                           {:t time-c :c 79}]}}})
   overrides))

(defn- portfolio-scene
  [state]
  (layout/page-shell
   (layout/desktop-shell {:class ["max-w-[1440px]"]}
    (portfolio-view/portfolio-view state))))

(portfolio/defscene returns-with-benchmarks
  []
  (portfolio-scene (portfolio-state {})))

(portfolio/defscene account-value
  []
  (portfolio-scene
   (portfolio-state {:portfolio-ui {:chart-tab :account-value}
                     :portfolio {:summary-by-key {:month {:accountValueHistory [[time-a 11840]
                                                                                [time-b 12120]
                                                                                [time-c 12460]]
                                                  :pnlHistory [[time-a 0]
                                                               [time-b 0]
                                                               [time-c 0]]}}}})))

(portfolio/defscene pnl
  []
  (portfolio-scene
   (portfolio-state {:portfolio-ui {:chart-tab :pnl}
                     :portfolio {:summary-by-key {:month {:pnlHistory [[time-a -420]
                                                                  [time-b 110]
                                                                  [time-c 760]]
                                                  :accountValueHistory [[time-a 11840]
                                                                        [time-b 11950]
                                                                        [time-c 12460]]}}}})))

(portfolio/defscene benchmark-search-open
  []
  (portfolio-scene
   (portfolio-state {:portfolio-ui {:chart-tab :returns
                                    :returns-benchmark-search "SO"
                                    :returns-benchmark-suggestions-open? true}
                     :asset-selector {:markets [{:coin "SPY"
                                                 :symbol "SPY"
                                                 :market-type :spot
                                                 :cache-order 1}
                                                {:coin "QQQ"
                                                 :symbol "QQQ"
                                                 :market-type :spot
                                                 :cache-order 2}
                                                {:coin "SOL"
                                                 :symbol "SOL"
                                                 :market-type :spot
                                                 :cache-order 3}]}})))
