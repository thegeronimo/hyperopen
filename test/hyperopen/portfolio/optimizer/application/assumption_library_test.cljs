(ns hyperopen.portfolio.optimizer.application.assumption-library-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.assumption-library :as library]))

(def ^:private conservative-entry
  {:behavior :conservative
   :expected-return 0.0
   :volatility 0.8
   :max-weight 0.03
   :correlation-floor 0.75})

(def ^:private proxy-entry
  {:behavior :proxy
   :expected-return 0.0
   :volatility 0.8
   :max-weight 0.05
   :proxy {:instrument-ids ["perp:BTC" "perp:SOL"]
           :relationship-strength :medium
           :prior-weights nil}})

(def ^:private sol-reference
  {:instrument-id "perp:SOL" :market-type :perp :coin "SOL"})

(deftest apply-sync-stamps-upserts-and-drops-removes-test
  (let [entries {"perp:OLD" {:instrument-id "perp:OLD"
                             :entry conservative-entry
                             :reference-instruments []
                             :updated-at-ms 1}}
        result (library/apply-sync
                entries
                {:upserts [{:instrument-id "perp:NEW"
                            :assumption proxy-entry
                            :reference-instruments [sol-reference]}]
                 :removes ["perp:OLD"]}
                4242)]
    (is (= {"perp:NEW" {:instrument-id "perp:NEW"
                        :entry proxy-entry
                        :reference-instruments [sol-reference]
                        :updated-at-ms 4242}}
           result)))
  (testing "an upsert without a behavior never enters the library"
    (is (= {} (library/apply-sync {} {:upserts [{:instrument-id "perp:NEW"
                                                 :assumption {}}]} 1))))
  (testing "blank ids are ignored"
    (is (= {} (library/apply-sync {} {:removes [" " nil]} 1)))))

(deftest entry-reference-instruments-picks-only-referenced-proxies-test
  (is (= [sol-reference]
         (library/entry-reference-instruments
          [sol-reference {:instrument-id "perp:DOGE"}]
          proxy-entry))
      "Only instruments the entry actually references as proxies ride along.")
  (is (= [] (library/entry-reference-instruments nil proxy-entry)))
  (is (= [] (library/entry-reference-instruments [sol-reference] conservative-entry))
      "A conservative entry references no proxies."))

(deftest record-round-trip-test
  (let [entries {"perp:NEW" {:instrument-id "perp:NEW"
                             :entry conservative-entry
                             :reference-instruments []
                             :updated-at-ms 9}}
        record (library/library-record "0xabc" entries)]
    (is (library/usable-record? record))
    (is (= entries (library/record->entries record)))
    (is (= {} (library/record->entries {:version 999}))
        "A future/unknown record shape degrades to an empty library.")))

(deftest hydrate-assumptions-gap-fills-universe-instruments-test
  (let [entries {"perp:NEW" {:instrument-id "perp:NEW"
                             :entry proxy-entry
                             :reference-instruments [sol-reference]
                             :updated-at-ms 1}
                 "perp:ELSEWHERE" {:instrument-id "perp:ELSEWHERE"
                                   :entry conservative-entry
                                   :reference-instruments []
                                   :updated-at-ms 1}}
        result (library/hydrate-assumptions
                {:assumptions {}
                 :universe [{:instrument-id "perp:NEW"}
                            {:instrument-id "perp:BTC"}]
                 :entries entries
                 :reference-instruments []})]
    (is (= {"perp:NEW" proxy-entry} (:assumptions result))
        "Only universe members hydrate; the remembered off-universe asset waits.")
    (is (= [sol-reference] (:reference-instruments result))
        "The out-of-universe proxy is re-admitted as a reference instrument.")
    (is (= [sol-reference] (:new-reference-instruments result))
        "It is reported as new so the caller can prefetch its history.")))

(deftest hydrate-assumptions-never-clobbers-draft-entries-test
  (let [draft-entry (assoc conservative-entry :volatility 1.5)]
    (is (nil? (library/hydrate-assumptions
               {:assumptions {"perp:NEW" draft-entry}
                :universe [{:instrument-id "perp:NEW"}]
                :entries {"perp:NEW" {:instrument-id "perp:NEW"
                                      :entry conservative-entry
                                      :reference-instruments []
                                      :updated-at-ms 1}}
                :reference-instruments []}))
        "A universe fully covered by draft entries hydrates nothing (nil = no change).")))

(deftest hydrate-assumptions-skips-references-already-present-or-in-universe-test
  (let [entries {"perp:NEW" {:instrument-id "perp:NEW"
                             :entry proxy-entry
                             :reference-instruments [sol-reference]
                             :updated-at-ms 1}}]
    (testing "proxy now inside the universe needs no reference instrument"
      (let [result (library/hydrate-assumptions
                    {:assumptions {}
                     :universe [{:instrument-id "perp:NEW"}
                                {:instrument-id "perp:SOL"}]
                     :entries entries
                     :reference-instruments []})]
        (is (= [] (:new-reference-instruments result)))
        (is (= [] (:reference-instruments result)))))
    (testing "already-tracked reference instruments are not duplicated"
      (let [result (library/hydrate-assumptions
                    {:assumptions {}
                     :universe [{:instrument-id "perp:NEW"}]
                     :entries entries
                     :reference-instruments [sol-reference]})]
        (is (= [] (:new-reference-instruments result)))
        (is (= [sol-reference] (:reference-instruments result)))))))

(deftest hydrate-assumptions-prunes-carried-references-that-joined-universe-test
  (let [result (library/hydrate-assumptions
                {:assumptions {}
                 :universe [{:instrument-id "perp:NEW"}
                            {:instrument-id "perp:SOL"}]
                 :entries {"perp:NEW" {:instrument-id "perp:NEW"
                                       :entry proxy-entry
                                       :reference-instruments []
                                       :updated-at-ms 1}}
                 :reference-instruments [sol-reference]})]
    (is (= [] (:reference-instruments result))
        "A carried-over reference that is now a universe member is pruned.")
    (is (= [] (:new-reference-instruments result)))))
