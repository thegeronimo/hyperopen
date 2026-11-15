(ns hyperopen.portfolio.optimizer.return-views-io-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.optimizer.actions :as actions]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(def ^:private now-ms 1751400000000)

(def ^:private bl-state
  {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC" :coin "BTC"}
                                              {:instrument-id "perp:ETH" :coin "ETH"}]
                                   :objective {:kind :max-sharpe}
                                   :return-model
                                   {:kind :black-litterman
                                    :views [{:id "v1" :kind :absolute
                                             :instrument-id "perp:BTC"
                                             :return 0.15
                                             :confidence-level :high
                                             :confidence 0.75
                                             :weights {"perp:BTC" 1}}]}
                                   :metadata {:dirty? false}}}}})

(deftest export-downloads-payload-and-notes-success-test
  (with-redefs [platform/now-ms (constantly now-ms)]
    (let [[[download-id payload] [note-id path note]]
          (actions/export-portfolio-optimizer-return-views bl-state)]
      (is (= :effects/download-portfolio-optimizer-return-views-file download-id))
      (is (= "return-views-1751400000000.json" (:filename payload)))
      (is (= 2 (:count payload)))
      (is (= ["perp:BTC" "perp:ETH"]
             (mapv :instrument-id (get-in payload [:document :entries]))))
      (is (= [15 nil]
             (mapv :return-percent (get-in payload [:document :entries]))))
      (is (= :effects/save note-id))
      (is (= contracts/ui-return-views-io-note-path path))
      (is (= :success (:kind note))))))

(deftest export-with-empty-universe-notes-error-test
  (let [state (assoc-in bl-state contracts/draft-universe-path [])
        [[effect-id path note] :as effects]
        (actions/export-portfolio-optimizer-return-views state)]
    (is (= 1 (count effects)))
    (is (= :effects/save effect-id))
    (is (= contracts/ui-return-views-io-note-path path))
    (is (= :error (:kind note)))))

(deftest import-opens-file-picker-test
  (is (= [[:effects/pick-portfolio-optimizer-return-views-file]]
         (actions/import-portfolio-optimizer-return-views bl-state))))

(deftest apply-imported-authors-views-syncs-library-and-clears-buffers-test
  (let [state (assoc-in bl-state
                        contracts/ui-objective-menu-view-drafts-path
                        {:perp:ETH {:return-text "3"}
                         :perp:BTC {:return-text "9"}})
        data {"entries" [{"instrument-id" "perp:ETH" "return-percent" 12.5
                          "confidence" "low"}]}
        effects (actions/apply-imported-portfolio-optimizer-return-views state data)
        [save-many sync note] effects
        path-values (into {} (second save-many))]
    (is (= 3 (count effects)))
    (is (= :effects/save-many (first save-many)))
    (is (= 2 (count (get path-values contracts/draft-return-model-views-path))))
    (is (= 0.125 (:return (last (get path-values contracts/draft-return-model-views-path)))))
    (is (= {:perp:BTC {:return-text "9"}}
           (get path-values contracts/ui-objective-menu-view-drafts-path)))
    (is (= true (get path-values contracts/draft-dirty-path)))
    (is (= [:effects/sync-portfolio-optimizer-view-library
            {:upserts [{:instrument-id "perp:ETH" :return 0.125 :confidence-level :low}]
             :removes []}]
           sync))
    (is (= :effects/save (first note)))
    (is (= :success (:kind (nth note 2))))))

(deftest apply-imported-invalid-file-only-notes-error-test
  (let [effects (actions/apply-imported-portfolio-optimizer-return-views bl-state nil)]
    (is (= 1 (count effects)))
    (is (= :error (:kind (nth (first effects) 2))))))

(deftest apply-imported-nothing-filled-notes-error-and-changes-nothing-test
  (let [data {"entries" [{"instrument-id" "perp:ETH" "return-percent" nil}]}
        effects (actions/apply-imported-portfolio-optimizer-return-views bl-state data)]
    (is (= 1 (count effects)))
    (is (= :error (:kind (nth (first effects) 2))))))

(deftest apply-imported-refuses-when-views-are-off-test
  (let [state (assoc-in bl-state
                        (conj contracts/draft-return-model-path :kind)
                        :historical-mean)
        data {"entries" [{"instrument-id" "perp:ETH" "return-percent" 5}]}
        effects (actions/apply-imported-portfolio-optimizer-return-views state data)]
    (is (= 1 (count effects)))
    (is (= :error (:kind (nth (first effects) 2))))))

(deftest dismiss-note-clears-it-test
  (is (= [[:effects/save contracts/ui-return-views-io-note-path nil]]
         (actions/dismiss-portfolio-optimizer-return-views-io-note bl-state))))
