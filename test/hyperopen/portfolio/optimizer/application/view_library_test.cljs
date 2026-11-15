(ns hyperopen.portfolio.optimizer.application.view-library-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.view-library :as view-library]))

(deftest apply-sync-upserts-stamp-and-removes-drop-test
  (let [entries {"perp:ETH" {:instrument-id "perp:ETH" :return 0.1
                             :confidence-level :low :updated-at-ms 1}}
        result (view-library/apply-sync
                entries
                {:upserts [{:instrument-id "perp:BTC"
                            :return 0.2
                            :confidence-level :high}
                           {:instrument-id "perp:SOL" :return nil}]
                 :removes ["perp:ETH" "  "]}
                4242)]
    (is (= {"perp:BTC" {:instrument-id "perp:BTC" :return 0.2
                        :confidence-level :high :updated-at-ms 4242}}
           result))))

(deftest view->upsert-accepts-only-authored-absolute-views-test
  (is (= {:instrument-id "perp:BTC" :return 0.2 :confidence-level :high}
         (view-library/view->upsert {:kind :absolute :instrument-id "perp:BTC"
                                     :return 0.2 :confidence-level :high})))
  (is (nil? (view-library/view->upsert {:kind :relative :instrument-id "perp:BTC"
                                        :comparator-instrument-id "perp:ETH"
                                        :return 0.05}))
      "Relative views are draft-scoped; they never enter the per-asset library.")
  (is (nil? (view-library/view->upsert {:kind :absolute :instrument-id "perp:BTC"
                                        :return nil}))))

(deftest record-round-trip-test
  (let [entries {"perp:BTC" {:instrument-id "perp:BTC" :return 0.2
                             :confidence-level :medium :updated-at-ms 7}}
        record (view-library/library-record "0xabc" entries)]
    (is (= entries (view-library/record->entries record)))
    (is (= {} (view-library/record->entries nil)))
    (is (= {} (view-library/record->entries {:version 999 :entries entries}))
        "A future/unknown record version reads as empty, never as garbage.")))

(deftest hydrate-views-fills-gaps-without-touching-authored-views-test
  (let [universe [{:instrument-id "perp:BTC"}
                  {:instrument-id "perp:ETH"}
                  {:instrument-id "perp:SOL"}]
        existing [{:id "bl_view_1" :kind :absolute :instrument-id "perp:BTC"
                   :return 0.99 :confidence-level :low :confidence 0.25
                   :confidence-variance 0.75 :horizon :3m :weights {"perp:BTC" 1}}]
        entries {"perp:BTC" {:instrument-id "perp:BTC" :return 0.2
                             :confidence-level :high :updated-at-ms 1}
                 "perp:ETH" {:instrument-id "perp:ETH" :return 0.12
                             :confidence-level :medium :updated-at-ms 2}
                 "perp:DOGE" {:instrument-id "perp:DOGE" :return 0.5
                              :confidence-level :high :updated-at-ms 3}}
        hydrated (view-library/hydrate-views existing entries universe)]
    (is (= [{:id "bl_view_1" :kind :absolute :instrument-id "perp:BTC"
             :return 0.99 :confidence-level :low :confidence 0.25
             :confidence-variance 0.75 :horizon :3m :weights {"perp:BTC" 1}}
            {:id "bl_view_2" :kind :absolute :instrument-id "perp:ETH"
             :return 0.12 :confidence-level :medium :confidence 0.5
             :confidence-variance 0.5 :horizon :3m :weights {"perp:ETH" 1}}]
           hydrated)
        "The draft's view wins for perp:BTC; perp:ETH hydrates from the library; perp:DOGE stays out (not in universe).")
    (is (= [] (view-library/hydrate-views nil {} universe))
        "No views and no library is a valid empty state.")))

(deftest hydration-gap-detects-uncovered-universe-members-test
  (let [entries {"perp:BTC" {:instrument-id "perp:BTC" :return 0.2
                             :confidence-level :high :updated-at-ms 1}}]
    (is (true? (view-library/hydration-gap?
                []
                entries
                [{:instrument-id "perp:BTC"}]))
        "A universe member the library remembers with no authored view is a gap.")
    (is (false? (view-library/hydration-gap?
                 [{:kind :absolute :instrument-id "perp:BTC" :return 0.3}]
                 entries
                 [{:instrument-id "perp:BTC"}]))
        "An authored absolute view covers the member — no gap.")
    (is (false? (view-library/hydration-gap?
                 []
                 entries
                 [{:instrument-id "perp:ETH"}]))
        "A remembered entry for an out-of-universe asset opens no gap.")
    (is (true? (view-library/hydration-gap?
                [{:kind :relative :instrument-id "perp:BTC"
                  :comparator-instrument-id "perp:ETH" :return 0.05}]
                entries
                [{:instrument-id "perp:BTC"}]))
        "A relative view does not cover the member; the absolute gap remains.")
    (is (false? (view-library/hydration-gap?
                 []
                 {}
                 [{:instrument-id "perp:BTC"}])))))
