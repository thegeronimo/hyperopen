(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-view-library-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer :as adapters]
            [hyperopen.test-support.async :as async-support]))

(def ^:private address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(defn- store-with
  [{:keys [entries wallet]}]
  (atom (merge {:wallet {:address address}}
               wallet
               {:portfolio {:optimizer {:view-library (or entries {})}}})))

(deftest load-view-library-effect-hydrates-state-mirror-test
  (async done
    (let [stored {:version 1
                  :address address
                  :entries {"perp:BTC" {:instrument-id "perp:BTC"
                                        :return 0.2
                                        :confidence-level :high
                                        :updated-at-ms 1000}}}
          store (store-with {})]
      (with-redefs [adapters/*load-view-library!* (fn [_addr] (js/Promise.resolve stored))]
        (-> (adapters/load-portfolio-optimizer-view-library-effect nil store)
            (.then (fn [_]
                     (is (= (:entries stored)
                            (get-in @store [:portfolio :optimizer :view-library])))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest load-view-library-effect-tolerates-unusable-records-test
  (async done
    (let [store (store-with {:entries {"perp:BTC" {:instrument-id "perp:BTC"
                                                   :return 0.1
                                                   :confidence-level :low
                                                   :updated-at-ms 1}}})]
      (with-redefs [adapters/*load-view-library!* (fn [_addr] (js/Promise.resolve {:version 999}))]
        (-> (adapters/load-portfolio-optimizer-view-library-effect nil store)
            (.then (fn [_]
                     (is (= {} (get-in @store [:portfolio :optimizer :view-library]))
                         "an unusable record resolves to an empty library, never garbage")
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest sync-view-library-effect-stamps-upserts-and-persists-test
  (async done
    (let [saved (atom nil)
          store (store-with {:entries {"perp:ETH" {:instrument-id "perp:ETH"
                                                   :return 0.1
                                                   :confidence-level :low
                                                   :updated-at-ms 1}}})]
      (with-redefs [adapters/*now-ms* (fn [] 4242)
                    adapters/*save-view-library!*
                    (fn [addr record]
                      (reset! saved [addr record])
                      (js/Promise.resolve true))]
        (-> (adapters/sync-portfolio-optimizer-view-library-effect
             nil
             store
             {:upserts [{:instrument-id "perp:BTC"
                         :return 0.2
                         :confidence-level :high}]
              :removes ["perp:ETH"]})
            (.then (fn [_]
                     (let [[addr record] @saved
                           expected {"perp:BTC" {:instrument-id "perp:BTC"
                                                 :return 0.2
                                                 :confidence-level :high
                                                 :updated-at-ms 4242}}]
                       (is (= address addr))
                       (is (= expected (:entries record))
                           "the upsert is stamped and the removed entry is gone")
                       (is (= expected
                              (get-in @store [:portfolio :optimizer :view-library]))
                           "state mirrors what was persisted")
                       (done))))
            (.catch (async-support/unexpected-error done)))))))

(deftest sync-view-library-effect-updates-state-but-skips-persist-when-read-only-test
  (async done
    (let [saved (atom nil)
          store (atom {:account-context {:spectate-mode {:active? true
                                                         :address address}}
                       :portfolio {:optimizer {:view-library {}}}})]
      (with-redefs [adapters/*now-ms* (fn [] 7)
                    adapters/*save-view-library!*
                    (fn [addr record]
                      (reset! saved [addr record])
                      (js/Promise.resolve true))]
        (-> (adapters/sync-portfolio-optimizer-view-library-effect
             nil
             store
             {:upserts [{:instrument-id "perp:BTC"
                         :return 0.2
                         :confidence-level :medium}]})
            (.then (fn [result]
                     (is (false? result))
                     (is (nil? @saved) "no IndexedDB write in read-only mode")
                     (is (= 0.2
                            (get-in @store [:portfolio :optimizer :view-library
                                            "perp:BTC" :return]))
                         "the in-memory mirror still updates so the session works")
                     (done)))
            (.catch (async-support/unexpected-error done)))))))
