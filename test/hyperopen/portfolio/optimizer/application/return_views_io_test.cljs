(ns hyperopen.portfolio.optimizer.application.return-views-io-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.application.return-views :as return-views]
            [hyperopen.portfolio.optimizer.application.return-views-io :as io]))

(def ^:private now-ms 1751400000000)

(def ^:private universe
  [{:instrument-id "perp:BTC" :coin "BTC"}
   {:instrument-id "perp:ETH" :coin "ETH"}
   {:instrument-id "perp:SOL" :coin "SOL"}])

(def ^:private views
  [{:id "v1" :kind :absolute :instrument-id "perp:BTC"
    :return 0.15 :confidence-level :high :confidence 0.75
    :weights {"perp:BTC" 1}}])

(def ^:private implied
  {"perp:BTC" 0.112 "perp:ETH" 0.094 "perp:SOL" 0.31})

(defn- payload
  []
  (io/export-payload
   {:rows (return-views/rows {:universe universe
                              :views views
                              :library-entries {}
                              :implied-returns-by-instrument implied
                              :now-ms now-ms})
    :universe universe
    :implied-returns-by-instrument implied
    :now-ms now-ms}))

(deftest export-payload-envelope-test
  (let [{:keys [filename count document]} (payload)]
    (is (= "return-views-1751400000000.json" filename))
    (is (= 3 count))
    (is (= io/export-document-type (:type document)))
    (is (= 1 (:version document)))
    (is (= now-ms (:exported-at document)))
    (is (seq (:instructions document)))))

(deftest export-user-row-carries-return-and-confidence-test
  (let [entry (first (get-in (payload) [:document :entries]))]
    (is (= {:instrument-id "perp:BTC"
            :symbol "BTC"
            :source "user"
            :return-percent 15
            :implied-return-percent 11.2
            :confidence "high"}
           entry))))

(deftest export-implied-row-has-null-return-test
  (let [entry (second (get-in (payload) [:document :entries]))]
    (is (= {:instrument-id "perp:ETH"
            :symbol "ETH"
            :source "implied"
            :return-percent nil
            :implied-return-percent 9.4
            :confidence nil}
           entry))))

(deftest import-unedited-export-is-a-no-op-test
  (let [{:keys [document]} (payload)
        data (js->clj (clj->js document))
        plan (io/import-plan {:views views :universe universe :data data})]
    (is (= :ok (:status plan)))
    (is (= 1 (:applied plan)))
    (is (= 2 (:unchanged plan)))
    (is (= 0 (:unknown plan)))
    (is (= views (mapv #(dissoc % :confidence-variance) (:views plan))))))

(deftest import-fills-implied-rows-as-user-views-test
  (let [data {"entries" [{"instrument-id" "perp:ETH"
                          "return-percent" 12.5
                          "confidence" "low"}]}
        plan (io/import-plan {:views views :universe universe :data data})
        added (last (:views plan))]
    (is (= :ok (:status plan)))
    (is (= 1 (:applied plan)))
    (is (= ["perp:ETH"] (:applied-instrument-ids plan)))
    (is (= :absolute (:kind added)))
    (is (= "perp:ETH" (:instrument-id added)))
    (is (= 0.125 (:return added)))
    (is (= :low (:confidence-level added)))
    (is (= {"perp:ETH" 1} (:weights added)))
    (is (= [{:instrument-id "perp:ETH" :return 0.125 :confidence-level :low}]
           (:upserts plan)))))

(deftest import-updates-existing-view-and-preserves-confidence-test
  (let [data {"entries" [{"instrument-id" "perp:BTC" "return-percent" "20%"}]}
        plan (io/import-plan {:views views :universe universe :data data})
        updated (first (:views plan))]
    (is (= 1 (:applied plan)))
    (is (= "v1" (:id updated)))
    (is (= 0.2 (:return updated)))
    (is (= :high (:confidence-level updated)))))

(deftest import-resolves-by-symbol-when-id-missing-test
  (let [data [{"symbol" "sol" "return-percent" 31}]
        plan (io/import-plan {:views [] :universe universe :data data})]
    (is (= 1 (:applied plan)))
    (is (= "perp:SOL" (:instrument-id (first (:views plan)))))))

(deftest import-counts-unknown-and-blank-entries-test
  (let [data {"entries" [{"instrument-id" "perp:DOGE" "return-percent" 5}
                         {"instrument-id" "perp:ETH" "return-percent" nil}
                         {"instrument-id" "perp:SOL" "return-percent" "  "}]}
        plan (io/import-plan {:views views :universe universe :data data})]
    (is (= :ok (:status plan)))
    (is (= 0 (:applied plan)))
    (is (= 2 (:unchanged plan)))
    (is (= 1 (:unknown plan)))
    (is (= views (:views plan)))))

(deftest import-invalid-confidence-defaults-to-medium-test
  (let [data [{"instrument-id" "perp:ETH" "return-percent" 8 "confidence" "extreme"}]
        plan (io/import-plan {:views [] :universe universe :data data})]
    (is (= :medium (:confidence-level (first (:views plan)))))))

(deftest import-rejects-unusable-files-test
  (testing "not a map/array"
    (is (= {:status :error :reason :invalid}
           (io/import-plan {:views [] :universe universe :data "hello"}))))
  (testing "envelope without entries"
    (is (= {:status :error :reason :invalid}
           (io/import-plan {:views [] :universe universe :data {"type" "x"}}))))
  (testing "empty entries"
    (is (= {:status :error :reason :empty}
           (io/import-plan {:views [] :universe universe :data {"entries" []}})))))

(deftest import-messages-test
  (is (= "Applied 2 views · 1 left as-is · 1 unknown asset skipped"
         (io/import-success-message {:applied 2 :unchanged 1 :unknown 1})))
  (is (= "Applied 1 view" (io/import-success-message {:applied 1 :unchanged 0 :unknown 0})))
  (is (= "No views applied — every `return-percent` was empty."
         (io/import-success-message {:applied 0 :unchanged 3 :unknown 0}))))
