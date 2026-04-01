(ns hyperopen.api.endpoints.leaderboard-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.endpoints.leaderboard :as leaderboard]
            [hyperopen.test-support.async :as async-support]))

(defn- ok-response
  [payload]
  #js {:ok true
       :status 200
       :json (fn []
               (js/Promise.resolve (clj->js payload)))})

(deftest normalize-window-performance-keeps-supported-metrics-and-negative-values-test
  (is (= {:pnl -2
          :roi 0
          :volume 9}
         (leaderboard/normalize-window-performance
          {:pnl " -2 "
           :roi "nope"
           :vlm "9"
           :ignored 4})))
  (is (nil? (leaderboard/normalize-window-performance []))))

(deftest request-leaderboard-normalizes-json-window-performance-payload-test
  (async done
    (let [calls (atom [])
          fetch-fn (fn [url init]
                     (swap! calls conj [url init])
                     (js/Promise.resolve
                      {:leaderboardRows [{:ethAddress "0xABC"
                                         :accountValue "12.5"
                                         :displayName "Desk"
                                         :prize "3"
                                         :windowPerformances [["month" {:pnl "2"
                                                                        :roi "0.1"
                                                                        :vlm "9"}]
                                                              ["allTime" {:pnl "-5"
                                                                          :roi "0.2"
                                                                          :volume "4"}]]}]}))]
      (-> (leaderboard/request-leaderboard! fetch-fn "https://leaderboard.test" {:fetch-opts {:cache "no-store"}})
          (.then (fn [rows]
                   (let [[called-url init] (first @calls)]
                     (is (= "https://leaderboard.test" called-url))
                     (is (= {:method "GET"
                             :cache "no-store"}
                            (js->clj init :keywordize-keys true))))
                   (is (= [{:eth-address "0xabc"
                            :account-value 12.5
                            :display-name "Desk"
                            :prize 3
                            :window-performances {:day {:pnl 0 :roi 0 :volume 0}
                                                  :week {:pnl 0 :roi 0 :volume 0}
                                                  :month {:pnl 2 :roi 0.1 :volume 9}
                                                  :all-time {:pnl -5 :roi 0.2 :volume 4}}}]
                          rows))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-leaderboard-rejects-non-ok-response-test
  (async done
    (let [fetch-fn (fn [_url _init]
                     (js/Promise.resolve #js {:ok false
                                              :status 503}))]
      (-> (leaderboard/request-leaderboard! fetch-fn "https://leaderboard.test" {})
          (.then (fn [_]
                   (is false "Expected non-ok response to reject")
                   (done)))
          (.catch (fn [err]
                    (is (= 503 (aget err "status")))
                    (done)))))))

(deftest normalize-leaderboard-row-defaults-missing-metrics-and-window-performance-test
  (is (= {:eth-address "0xabc"
          :account-value 0
          :display-name nil
          :prize 0
          :window-performances {:day {:pnl 0 :roi 0 :volume 0}
                                :week {:pnl 0 :roi 0 :volume 0}
                                :month {:pnl 0 :roi 0 :volume 0}
                                :all-time {:pnl 0 :roi 0 :volume 0}}}
         (leaderboard/normalize-leaderboard-row
          {:ethAddress "0xABC"
           :displayName " "
           :windowPerformances nil})))
  (is (nil? (leaderboard/normalize-leaderboard-row []))))

(deftest normalize-leaderboard-rows-rejects-non-sequential-payloads-test
  (is (= []
         (leaderboard/normalize-leaderboard-rows nil)))
  (is (= []
         (leaderboard/normalize-leaderboard-rows {:leaderboardRows 1})))
  (is (= [{:eth-address "0xabc"
           :account-value 12
           :display-name "Desk"
           :prize 3
           :window-performances {:day {:pnl 0 :roi 0 :volume 0}
                                 :week {:pnl 0 :roi 0 :volume 0}
                                 :month {:pnl 0 :roi 0 :volume 0}
                                 :all-time {:pnl 0 :roi 0 :volume 0}}}]
         (leaderboard/normalize-leaderboard-rows
          {:leaderboardRows [{:ethAddress "0xABC"
                             :accountValue "12"
                             :displayName "Desk"
                             :prize "3"
                             :windowPerformances []}]}))))
