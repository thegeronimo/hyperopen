(ns hyperopen.api.default-account-history-test
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [hyperopen.api.default :as api]))

(use-fixtures
  :each
  {:before (fn []
             (api/reset-request-runtime!))
   :after (fn []
            (api/reset-request-runtime!))})

(deftest fetch-user-funding-history-paginates-until-empty-page-test
  (async done
    (let [calls (atom [])
          original-post-info hyperopen.api.default/post-info!]
      (set! hyperopen.api.default/post-info!
            (fn post-info-mock
              ([body]
               (post-info-mock body {}))
              ([body _opts]
               (swap! calls conj body)
               (let [start-time (get body "startTime")
                     payload (cond
                               (= start-time 1000)
                               [{:time 1000
                                 :delta {:type "funding"
                                         :coin "HYPE"
                                         :usdc "1.0"
                                         :szi "10.0"
                                         :fundingRate "0.0001"}}
                                {:time 2000
                                 :delta {:type "funding"
                                         :coin "BTC"
                                         :usdc "-1.0"
                                         :szi "-3.0"
                                         :fundingRate "-0.0002"}}]
                               (= start-time 2001)
                               [{:time 3000
                                 :delta {:type "funding"
                                         :coin "ETH"
                                         :usdc "0.5"
                                         :szi "4.0"
                                         :fundingRate "0.0003"}}]
                               :else
                               [])]
                 (js/Promise.resolve payload)))
              ([body opts _attempt]
               (post-info-mock body opts))))
      (-> (api/fetch-user-funding-history! (atom {}) "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                           {:start-time-ms 1000
                                            :end-time-ms 5000})
          (.then (fn [rows]
                   (is (= 3 (count rows)))
                   (is (= [3000 2000 1000] (mapv :time-ms rows)))
                   (is (= [1000 2001 3001] (mapv #(get % "startTime") @calls)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
            (fn []
              (set! hyperopen.api.default/post-info! original-post-info)))))))
