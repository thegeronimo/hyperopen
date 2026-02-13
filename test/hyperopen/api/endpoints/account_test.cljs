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

(deftest normalize-user-abstraction-mode-maps-known-values-test
  (is (= :unified (account/normalize-user-abstraction-mode "unifiedAccount")))
  (is (= :unified (account/normalize-user-abstraction-mode "portfolioMargin")))
  (is (= :classic (account/normalize-user-abstraction-mode "default")))
  (is (= :classic (account/normalize-user-abstraction-mode nil))))

(deftest request-clearinghouse-state-uses-optional-dex-test
  (let [calls (atom [])
        post-info! (fn [body opts]
                     (swap! calls conj [body opts])
                     (js/Promise.resolve {}))]
    (account/request-clearinghouse-state! post-info! "0xabc" nil {})
    (account/request-clearinghouse-state! post-info! "0xabc" "vault" {:priority :low})
    (is (= [{"type" "clearinghouseState"
             "user" "0xabc"}
            {"type" "clearinghouseState"
             "user" "0xabc"
             "dex" "vault"}]
           (mapv first @calls)))
    (is (= [{:priority :high}
            {:priority :low}]
           (mapv second @calls)))))
