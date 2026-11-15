(ns hyperopen.runtime.effect-adapters.portfolio-optimizer-assumption-library-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.runtime.effect-adapters.portfolio-optimizer :as adapters]
            [hyperopen.test-support.async :as async-support]))

(def ^:private address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(def ^:private conservative-entry
  {:behavior :conservative
   :expected-return 0.0
   :volatility 0.8
   :max-weight 0.03
   :correlation-floor 0.75})

(defn- store-with
  [{:keys [entries wallet]}]
  (atom (merge {:wallet {:address address}}
               wallet
               {:portfolio {:optimizer {:assumption-library (or entries {})}}})))

(deftest load-assumption-library-effect-hydrates-state-mirror-test
  (async done
    (let [stored {:version 1
                  :address address
                  :entries {"perp:NEW" {:instrument-id "perp:NEW"
                                        :entry conservative-entry
                                        :reference-instruments []
                                        :updated-at-ms 1000}}}
          store (store-with {})]
      (with-redefs [adapters/*load-assumption-library!*
                    (fn [_addr] (js/Promise.resolve stored))]
        (-> (adapters/load-portfolio-optimizer-assumption-library-effect nil store)
            (.then (fn [_]
                     (is (= (:entries stored)
                            (get-in @store [:portfolio :optimizer :assumption-library])))
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest load-assumption-library-effect-tolerates-unusable-records-test
  (async done
    (let [store (store-with {:entries {"perp:NEW" {:instrument-id "perp:NEW"
                                                   :entry conservative-entry
                                                   :reference-instruments []
                                                   :updated-at-ms 1}}})]
      (with-redefs [adapters/*load-assumption-library!*
                    (fn [_addr] (js/Promise.resolve {:version 999}))]
        (-> (adapters/load-portfolio-optimizer-assumption-library-effect nil store)
            (.then (fn [_]
                     (is (= {} (get-in @store [:portfolio :optimizer :assumption-library]))
                         "an unusable record resolves to an empty library, never garbage")
                     (done)))
            (.catch (async-support/unexpected-error done)))))))

(deftest sync-assumption-library-effect-stamps-upserts-and-persists-test
  (async done
    (let [saved (atom nil)
          store (store-with {:entries {"perp:OLD" {:instrument-id "perp:OLD"
                                                   :entry conservative-entry
                                                   :reference-instruments []
                                                   :updated-at-ms 1}}})]
      (with-redefs [adapters/*now-ms* (fn [] 4242)
                    adapters/*save-assumption-library!*
                    (fn [addr record]
                      (reset! saved [addr record])
                      (js/Promise.resolve true))]
        (-> (adapters/sync-portfolio-optimizer-assumption-library-effect
             nil
             store
             {:upserts [{:instrument-id "perp:NEW"
                         :assumption conservative-entry
                         :reference-instruments []}]
              :removes ["perp:OLD"]})
            (.then (fn [_]
                     (let [[addr record] @saved
                           expected {"perp:NEW" {:instrument-id "perp:NEW"
                                                 :entry conservative-entry
                                                 :reference-instruments []
                                                 :updated-at-ms 4242}}]
                       (is (= address addr))
                       (is (= expected (:entries record))
                           "the upsert is stamped and the removed entry is gone")
                       (is (= expected
                              (get-in @store [:portfolio :optimizer :assumption-library]))
                           "state mirrors what was persisted")
                       (done))))
            (.catch (async-support/unexpected-error done)))))))

(deftest sync-assumption-library-effect-updates-state-but-skips-persist-when-read-only-test
  (async done
    (let [saved (atom nil)
          store (atom {:account-context {:spectate-mode {:active? true
                                                         :address address}}
                       :portfolio {:optimizer {:assumption-library {}}}})]
      (with-redefs [adapters/*now-ms* (fn [] 7)
                    adapters/*save-assumption-library!*
                    (fn [addr record]
                      (reset! saved [addr record])
                      (js/Promise.resolve true))]
        (-> (adapters/sync-portfolio-optimizer-assumption-library-effect
             nil
             store
             {:upserts [{:instrument-id "perp:NEW"
                         :assumption conservative-entry
                         :reference-instruments []}]})
            (.then (fn [result]
                     (is (false? result))
                     (is (nil? @saved) "no IndexedDB write in read-only mode")
                     (is (= conservative-entry
                            (get-in @store [:portfolio :optimizer :assumption-library
                                            "perp:NEW" :entry]))
                         "the in-memory mirror still updates so the session works")
                     (done)))
            (.catch (async-support/unexpected-error done)))))))
