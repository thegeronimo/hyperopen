(ns hyperopen.api.endpoints.account-portfolio-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.test-support.api-stubs :as api-stubs]
            [hyperopen.test-support.async :as async-support]
            [hyperopen.api.endpoints.account :as account]))

(deftest normalize-portfolio-summary-supports-shape-variants-test
  (is (= {:day {:account "day" :equity "1"}
          :perp-all-time {:key "perpAllTime" :equity "2"}
          :week {:equity "3"}
          :custom-range {:range " customRange " :equity "4"}}
         (account/normalize-portfolio-summary
          [{:account "day" :equity "1"}
           {:key "perpAllTime" :equity "2"}
           ["week" {:equity "3"}]
           {:range " customRange " :equity "4"}
           ["month" 10]
           ["bad"]
           {:equity "missing-key"}
           :invalid])))
  (is (= {:month {:equity "5"}}
         (account/normalize-portfolio-summary
          {:data {"month" {:equity "5"}}})))
  (is (= {:all-time {:equity "6"}}
         (account/normalize-portfolio-summary
          {:portfolio {"all-time" {:equity "6"}}})))
  (is (= {:perp-week {:equity "7"}}
         (account/normalize-portfolio-summary
          {:perpWeek {:equity "7"}})))
  (is (= {}
         (account/normalize-portfolio-summary "not-a-map-or-seq"))))

(deftest normalize-portfolio-summary-normalizes-all-range-key-variants-test
  (doseq [[input expected] [["day" :day]
                            ["week" :week]
                            ["month" :month]
                            ["3m" :three-month]
                            ["3M" :three-month]
                            ["threeMonth" :three-month]
                            ["quarter" :three-month]
                            ["three-months" :three-month]
                            ["6m" :six-month]
                            ["sixMonth" :six-month]
                            ["half-year" :six-month]
                            ["six-months" :six-month]
                            ["1y" :one-year]
                            ["1Y" :one-year]
                            ["year" :one-year]
                            ["one-years" :one-year]
                            ["2y" :two-year]
                            ["2Y" :two-year]
                            ["two-year" :two-year]
                            ["two-years" :two-year]
                            ["alltime" :all-time]
                            ["all-time" :all-time]
                            ["perpday" :perp-day]
                            ["perp-day" :perp-day]
                            ["perpweek" :perp-week]
                            ["perp-week" :perp-week]
                            ["perpmonth" :perp-month]
                            ["perp-month" :perp-month]
                            ["perp3M" :perp-three-month]
                            ["perpquarter" :perp-three-month]
                            ["perp-three-months" :perp-three-month]
                            ["perpSixMonth" :perp-six-month]
                            ["perp-half-year" :perp-six-month]
                            ["perp-six-months" :perp-six-month]
                            ["perp1Y" :perp-one-year]
                            ["perpyear" :perp-one-year]
                            ["perp-one-years" :perp-one-year]
                            ["perp2Y" :perp-two-year]
                            ["perp-two-year" :perp-two-year]
                            ["perp-two-years" :perp-two-year]
                            ["perpalltime" :perp-all-time]
                            ["perp-all-time" :perp-all-time]
                            ["custom-range" :custom-range]]]
    (is (= {expected {:input input}}
           (account/normalize-portfolio-summary [[input {:input input}]]))))
  (is (= {:perp-custom-range {:input "perpCustomRange"}}
         (account/normalize-portfolio-summary
          [["perpCustomRange" {:input "perpCustomRange"}]])))
  (is (= {}
         (account/normalize-portfolio-summary [[nil {:input :nil-key}]
                                               ["   " {:input :blank-key}]]))))

(deftest request-portfolio-short-circuits-without-address-test
  (async done
    (let [calls (atom 0)
          post-info! (api-stubs/post-info-stub
                      (fn [_body _opts]
                        (swap! calls inc)
                        {:data {"day" {:equity "1"}}}))]
      (-> (account/request-portfolio! post-info! nil {})
          (.then (fn [result]
                   (is (= {} result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-portfolio-builds-dedupe-key-and-normalizes-response-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub calls {:data {"day" {:equity "1"}
                                                             "perpAllTime" {:equity "2"}}})]
      (-> (account/request-portfolio! post-info! "0xAbC" {:priority :low})
          (.then (fn [summary]
                   (let [[body opts] (first @calls)]
                     (is (= {"type" "portfolio"
                             "user" "0xAbC"}
                            body))
                     (is (= {:priority :low
                             :dedupe-key [:portfolio "0xabc"]
                             :cache-ttl-ms 8000}
                            opts))
                     (is (= {:day {:equity "1"}
                             :perp-all-time {:equity "2"}}
                            summary))
                     (done))))
          (.catch (async-support/unexpected-error done))))))

(deftest request-portfolio-defaults-priority-and-dedupe-when-opts-are-nil-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub calls {:data {"month" {:equity "10"}}})]
      (-> (account/request-portfolio! post-info! "0xAbC" nil)
          (.then (fn [summary]
                   (is (= {"type" "portfolio"
                           "user" "0xAbC"}
                          (ffirst @calls)))
                   (is (= {:priority :high
                           :dedupe-key [:portfolio "0xabc"]
                           :cache-ttl-ms 8000}
                          (second (first @calls))))
                   (is (= {:month {:equity "10"}}
                          summary))
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest request-portfolio-allows-explicit-dedupe-key-override-test
  (async done
    (let [calls (atom [])
          post-info! (api-stubs/post-info-stub calls {:data {"day" {:equity "1"}}})]
      (-> (account/request-portfolio! post-info!
                                      "0xAbC"
                                      {:priority :low
                                       :dedupe-key :explicit})
          (.then (fn [_summary]
                   (is (= {"type" "portfolio"
                           "user" "0xAbC"}
                          (ffirst @calls)))
                   (is (= {:priority :low
                           :dedupe-key :explicit
                           :cache-ttl-ms 8000}
                          (second (first @calls))))
                   (done)))
          (.catch (async-support/unexpected-error done))))))
