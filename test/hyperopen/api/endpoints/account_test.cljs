(ns hyperopen.api.endpoints.account-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.endpoints.account :as account]))

(deftest request-user-funding-history-paginates-forward-by-time-test
  (async done
    (let [calls (atom [])
          post-info! (fn [body _opts]
                       (swap! calls conj body)
                       (let [start-time (get body "startTime")]
                         (js/Promise.resolve
                          (cond
                            (= start-time 1000)
                            [{:time-ms 1000} {:time-ms 2000}]

                            (= start-time 2001)
                            [{:time-ms 3000}]

                            :else
                            []))))
          normalize-rows-fn identity
          sort-rows-fn (fn [rows]
                         (->> rows
                              (sort-by :time-ms >)
                              vec))]
      (-> (account/request-user-funding-history! post-info!
                                                 normalize-rows-fn
                                                 sort-rows-fn
                                                 "0xabc"
                                                 1000
                                                 5000
                                                 {})
          (.then (fn [rows]
                   (is (= [3000 2000 1000] (mapv :time-ms rows)))
                   (is (= [1000 2001 3001] (mapv #(get % "startTime") @calls)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-user-funding-history-supports-wrapped-payloads-test
  (async done
    (let [post-info! (fn [body _opts]
                       (let [start-time (get body "startTime")]
                         (js/Promise.resolve
                          (if (= start-time 0)
                            {:data {:fundings [{:time 1000
                                                :delta {:type "funding"
                                                        :coin "HYPE"
                                                        :usdc "1.0"
                                                        :szi "2.0"
                                                        :fundingRate "0.0001"}}]}}
                            {:data {:fundings []}}))))
          normalize-rows-fn (fn [rows]
                              (mapv (fn [row]
                                      {:time-ms (:time row)
                                       :coin (get-in row [:delta :coin])})
                                    rows))
          sort-rows-fn identity]
      (-> (account/request-user-funding-history! post-info!
                                                 normalize-rows-fn
                                                 sort-rows-fn
                                                 "0xabc"
                                                 0
                                                 5000
                                                 {})
          (.then (fn [rows]
                   (is (= 1 (count rows)))
                   (is (= [{:time-ms 1000
                            :coin "HYPE"}]
                          rows))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-user-funding-history-short-circuits-without-address-test
  (async done
    (let [calls (atom 0)
          post-info! (fn [_body _opts]
                       (swap! calls inc)
                       (js/Promise.resolve []))]
      (-> (account/request-user-funding-history! post-info!
                                                 identity
                                                 identity
                                                 nil
                                                 0
                                                 5000
                                                 {})
          (.then (fn [rows]
                   (is (= [] rows))
                   (is (= 0 @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-user-funding-history-omits-non-numeric-time-bounds-test
  (async done
    (let [calls (atom [])
          post-info! (fn [body opts]
                       (swap! calls conj [body opts])
                       (js/Promise.resolve []))]
      (-> (account/request-user-funding-history! post-info!
                                                 identity
                                                 identity
                                                 "0xabc"
                                                 "1000"
                                                 nil
                                                 nil)
          (.then (fn [rows]
                   (is (= [] rows))
                   (let [[body opts] (first @calls)]
                     (is (= {"type" "userFunding"
                             "user" "0xabc"}
                            body))
                     (is (= {:priority :high}
                            opts)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-user-funding-history-supports-several-payload-shapes-test
  (async done
    (let [run-request (fn [payload]
                        (account/request-user-funding-history!
                         (fn [_body _opts]
                           (js/Promise.resolve payload))
                         identity
                         identity
                         "0xabc"
                         0
                         0
                         {}))]
      (-> (js/Promise.all
           #js [(run-request [{:time-ms 1 :id :sequential}])
                (run-request {:fundings [{:time-ms 5 :id :fundings}]})
                (run-request {:userFunding [{:time-ms 2 :id :user-funding}]})
                (run-request {:userFundings [{:time-ms 3 :id :user-fundings}]})
                (run-request {:data [{:time-ms 4 :id :data-seq}]})
                (run-request {:data {:fundings [{:time-ms 6 :id :nested-fundings}]}})
                (run-request {:data {:userFunding [{:time-ms 7 :id :nested-user-funding}]}})
                (run-request {:data {:userFundings [{:time-ms 8 :id :nested-user-fundings}]}})
                (run-request {:data {:unexpected true}})
                (run-request "not-a-map-or-seq")])
          (.then
           (fn [results]
             (let [results* (vec (array-seq results))]
               (is (= [{:time-ms 1 :id :sequential}] (nth results* 0)))
               (is (= [{:time-ms 5 :id :fundings}] (nth results* 1)))
               (is (= [{:time-ms 2 :id :user-funding}] (nth results* 2)))
               (is (= [{:time-ms 3 :id :user-fundings}] (nth results* 3)))
               (is (= [{:time-ms 4 :id :data-seq}] (nth results* 4)))
               (is (= [{:time-ms 6 :id :nested-fundings}] (nth results* 5)))
               (is (= [{:time-ms 7 :id :nested-user-funding}] (nth results* 6)))
               (is (= [{:time-ms 8 :id :nested-user-fundings}] (nth results* 7)))
               (is (= [] (nth results* 8)))
               (is (= [] (nth results* 9))))
             (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-user-funding-history-stops-when-next-page-start-does-not-advance-test
  (async done
    (let [calls (atom [])
          post-info! (fn [body _opts]
                       (swap! calls conj body)
                       (js/Promise.resolve [{:time-ms 999}]))]
      (-> (account/request-user-funding-history! post-info!
                                                 identity
                                                 identity
                                                 "0xabc"
                                                 1000
                                                 5000
                                                 {})
          (.then (fn [rows]
                   (is (= [{:time-ms 999}] rows))
                   (is (= [1000] (mapv #(get % "startTime") @calls)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-user-funding-history-warns-on-non-empty-page-when-normalization-drops-all-rows-test
  (async done
    (let [warnings (atom [])
          console-object (or (.-console js/globalThis) #js {})
          original-warn (.-warn console-object)
          post-info! (fn [_body _opts]
                       (js/Promise.resolve [{:time 1000
                                             :delta {:type "funding"
                                                     :coin "HYPE"
                                                     :usdc "1.0"
                                                     :szi "2.0"
                                                     :fundingRate "0.0001"}}]))
          normalize-rows-fn (fn [_rows] [])
          sort-rows-fn identity]
      (set! (.-warn console-object)
            (fn [& args]
              (swap! warnings conj (vec args))))
      (-> (account/request-user-funding-history! post-info!
                                                 normalize-rows-fn
                                                 sort-rows-fn
                                                 "0xabc"
                                                 0
                                                 5000
                                                 {})
          (.then (fn [rows]
                   (is (= [] rows))
                   (is (= 1 (count @warnings)))
                   (let [[message payload] (first @warnings)
                         payload* (js->clj payload :keywordize-keys true)]
                     (is (= "Funding history normalization dropped all rows on a non-empty page."
                            message))
                     (is (= "funding-history-normalization-drop" (:event payload*)))
                     (is (= 1 (:raw-row-count payload*)))
                     (is (= 0 (:start-time-ms payload*)))
                     (is (= 5000 (:end-time-ms payload*))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally (fn []
                      (set! (.-warn console-object) original-warn)))))))

(deftest request-user-funding-history-skips-warning-when-console-warn-is-not-a-function-test
  (async done
    (let [console-object (or (.-console js/globalThis) #js {})
          original-warn (.-warn console-object)
          post-info! (fn [_body _opts]
                       (js/Promise.resolve [{:time-ms 1000}]))
          normalize-rows-fn (fn [_rows] [])]
      (set! (.-warn console-object) nil)
      (-> (account/request-user-funding-history! post-info!
                                                 normalize-rows-fn
                                                 identity
                                                 "0xabc"
                                                 0
                                                 5000
                                                 {})
          (.then (fn [rows]
                   (is (= [] rows))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally (fn []
                      (set! (.-warn console-object) original-warn)))))))

(deftest request-spot-clearinghouse-state-short-circuits-without-address-test
  (async done
    (let [calls (atom 0)
          post-info! (fn [_body _opts]
                       (swap! calls inc)
                       (js/Promise.resolve {}))]
      (-> (account/request-spot-clearinghouse-state! post-info! nil {})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-spot-clearinghouse-state-builds-request-body-test
  (let [calls (atom [])
        post-info! (fn [body opts]
                     (swap! calls conj [body opts])
                     (js/Promise.resolve {}))]
    (account/request-spot-clearinghouse-state! post-info! "0xabc" {:priority :low})
    (is (= {"type" "spotClearinghouseState"
            "user" "0xabc"}
           (ffirst @calls)))
    (is (= {:priority :low}
           (second (first @calls))))))

(deftest request-user-abstraction-builds-dedupe-key-per-address-test
  (async done
    (let [calls (atom [])
          post-info! (fn [body opts]
                       (swap! calls conj [body opts])
                       (js/Promise.resolve "unifiedAccount"))]
      (-> (account/request-user-abstraction! post-info! "0xAbC" {})
          (.then (fn [_]
                   (let [[body opts] (first @calls)]
                     (is (= {"type" "userAbstraction"
                             "user" "0xAbC"}
                            body))
                     (is (= [:user-abstraction "0xabc"] (:dedupe-key opts)))
                     (done))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-user-abstraction-short-circuits-without-address-test
  (async done
    (let [calls (atom 0)
          post-info! (fn [_body _opts]
                       (swap! calls inc)
                       (js/Promise.resolve nil))]
      (-> (account/request-user-abstraction! post-info! nil {})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-user-abstraction-allows-explicit-dedupe-key-override-test
  (let [calls (atom [])
        post-info! (fn [body opts]
                     (swap! calls conj [body opts])
                     (js/Promise.resolve "default"))]
    (account/request-user-abstraction! post-info!
                                       "0xabc"
                                       {:priority :low
                                        :dedupe-key :explicit})
    (is (= {"type" "userAbstraction"
            "user" "0xabc"}
           (ffirst @calls)))
    (is (= {:priority :low
            :dedupe-key :explicit}
           (second (first @calls))))))

(deftest normalize-user-abstraction-mode-maps-known-values-test
  (is (= :unified (account/normalize-user-abstraction-mode "unifiedAccount")))
  (is (= :unified (account/normalize-user-abstraction-mode "portfolioMargin")))
  (is (= :unified (account/normalize-user-abstraction-mode " dexAbstraction ")))
  (is (= :classic (account/normalize-user-abstraction-mode "default")))
  (is (= :classic (account/normalize-user-abstraction-mode "disabled")))
  (is (= :classic (account/normalize-user-abstraction-mode "  unknown  ")))
  (is (= :classic (account/normalize-user-abstraction-mode nil))))

(deftest request-clearinghouse-state-uses-optional-dex-test
  (let [calls (atom [])
        post-info! (fn [body opts]
                     (swap! calls conj [body opts])
                     (js/Promise.resolve {}))]
    (account/request-clearinghouse-state! post-info! "0xabc" nil {})
    (account/request-clearinghouse-state! post-info! "0xabc" "" {})
    (account/request-clearinghouse-state! post-info! "0xabc" "vault" {:priority :low})
    (is (= [{"type" "clearinghouseState"
             "user" "0xabc"}
            {"type" "clearinghouseState"
             "user" "0xabc"}
            {"type" "clearinghouseState"
             "user" "0xabc"
             "dex" "vault"}]
           (mapv first @calls)))
    (is (= [{:priority :high}
            {:priority :high}
            {:priority :low}]
           (mapv second @calls)))))

(deftest request-clearinghouse-state-short-circuits-without-address-test
  (async done
    (let [calls (atom 0)
          post-info! (fn [_body _opts]
                       (swap! calls inc)
                       (js/Promise.resolve {}))]
      (-> (account/request-clearinghouse-state! post-info! nil "vault" {})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-user-funding-history-stops-when-next-page-start-exceeds-end-time-test
  (async done
    (let [calls (atom [])
          post-info! (fn [body _opts]
                       (swap! calls conj body)
                       (js/Promise.resolve [{:time-ms 1001}]))]
      (-> (account/request-user-funding-history! post-info!
                                                 identity
                                                 identity
                                                 "0xabc"
                                                 1000
                                                 1001
                                                 {})
          (.then (fn [rows]
                   (is (= [{:time-ms 1001}] rows))
                   (is (= [1000] (mapv #(get % "startTime") @calls)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

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
                            ["alltime" :all-time]
                            ["all-time" :all-time]
                            ["perpday" :perp-day]
                            ["perp-day" :perp-day]
                            ["perpweek" :perp-week]
                            ["perp-week" :perp-week]
                            ["perpmonth" :perp-month]
                            ["perp-month" :perp-month]
                            ["perpalltime" :perp-all-time]
                            ["perp-all-time" :perp-all-time]
                            ["custom-range" :custom-range]]]
    (is (= {expected {:input input}}
           (account/normalize-portfolio-summary [[input {:input input}]]))))
  (is (= {}
         (account/normalize-portfolio-summary [[nil {:input :nil-key}]
                                               ["   " {:input :blank-key}]]))))

(deftest request-portfolio-short-circuits-without-address-test
  (async done
    (let [calls (atom 0)
          post-info! (fn [_body _opts]
                       (swap! calls inc)
                       (js/Promise.resolve {:data {"day" {:equity "1"}}}))]
      (-> (account/request-portfolio! post-info! nil {})
          (.then (fn [result]
                   (is (= {} result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-portfolio-builds-dedupe-key-and-normalizes-response-test
  (async done
    (let [calls (atom [])
          post-info! (fn [body opts]
                       (swap! calls conj [body opts])
                       (js/Promise.resolve {:data {"day" {:equity "1"}
                                                  "perpAllTime" {:equity "2"}}}))]
      (-> (account/request-portfolio! post-info! "0xAbC" {:priority :low})
          (.then (fn [summary]
                   (let [[body opts] (first @calls)]
                     (is (= {"type" "portfolio"
                             "user" "0xAbC"}
                            body))
                     (is (= {:priority :low
                             :dedupe-key [:portfolio "0xabc"]}
                            opts))
                     (is (= {:day {:equity "1"}
                             :perp-all-time {:equity "2"}}
                            summary))
                     (done))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-portfolio-defaults-priority-and-dedupe-when-opts-are-nil-test
  (async done
    (let [calls (atom [])
          post-info! (fn [body opts]
                       (swap! calls conj [body opts])
                       (js/Promise.resolve {:data {"month" {:equity "10"}}}))]
      (-> (account/request-portfolio! post-info! "0xAbC" nil)
          (.then (fn [summary]
                   (is (= {"type" "portfolio"
                           "user" "0xAbC"}
                          (ffirst @calls)))
                   (is (= {:priority :high
                           :dedupe-key [:portfolio "0xabc"]}
                          (second (first @calls))))
                   (is (= {:month {:equity "10"}}
                          summary))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-portfolio-allows-explicit-dedupe-key-override-test
  (async done
    (let [calls (atom [])
          post-info! (fn [body opts]
                       (swap! calls conj [body opts])
                       (js/Promise.resolve {:data {"day" {:equity "1"}}}))]
      (-> (account/request-portfolio! post-info!
                                      "0xAbC"
                                      {:priority :low
                                       :dedupe-key :explicit})
          (.then (fn [_summary]
                   (is (= {"type" "portfolio"
                           "user" "0xAbC"}
                          (ffirst @calls)))
                   (is (= {:priority :low
                           :dedupe-key :explicit}
                          (second (first @calls))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-user-fees-short-circuits-without-address-test
  (async done
    (let [calls (atom 0)
          post-info! (fn [_body _opts]
                       (swap! calls inc)
                       (js/Promise.resolve {:fees []}))]
      (-> (account/request-user-fees! post-info! nil {})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest request-user-fees-builds-dedupe-key-and-honors-override-test
  (let [calls (atom [])
        post-info! (fn [body opts]
                     (swap! calls conj [body opts])
                     (js/Promise.resolve {:fees []}))]
    (account/request-user-fees! post-info! "0xAbC" {:priority :low})
    (account/request-user-fees! post-info! "0xAbC"
                                {:priority :low
                                 :dedupe-key :explicit})
    (is (= [{"type" "userFees"
             "user" "0xAbC"}
            {"type" "userFees"
             "user" "0xAbC"}]
           (mapv first @calls)))
    (is (= [{:priority :low
             :dedupe-key [:user-fees "0xabc"]}
            {:priority :low
             :dedupe-key :explicit}]
           (mapv second @calls)))))

(deftest request-user-fees-defaults-priority-and-dedupe-when-opts-are-nil-test
  (let [calls (atom [])
        post-info! (fn [body opts]
                     (swap! calls conj [body opts])
                     (js/Promise.resolve {:fees []}))]
    (account/request-user-fees! post-info! "0xAbC" nil)
    (is (= {"type" "userFees"
            "user" "0xAbC"}
           (ffirst @calls)))
    (is (= {:priority :high
            :dedupe-key [:user-fees "0xabc"]}
           (second (first @calls))))))
