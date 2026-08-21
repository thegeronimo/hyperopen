(ns hyperopen.websocket.orderbook-l2-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.platform :as platform]
            [hyperopen.websocket.orderbook-l2 :as l2]))

;; A real frame captured from wss://api-ui.hyperliquid.xyz/ws on 2026-08-21. Keeping a
;; genuine payload here is what proves the wire format is understood -- a hand-written
;; fixture would only prove the parser agrees with itself.
(def ^:private captured-delta-blob
  (str "VZM7bgMxDETvwlog+JH4cZlcIZ2xVdoUQZLO8N0jN5JYLt7OaMiRHvAJN3j7eIcGf3BjD1cZZGnd"
       "GnzB7X5/wPf8xd2FkKDB7/xixxzpHZ5tYc6FCdNMC/QDsoRVqR00o3vV9kUVeVBycZZFGbuHRaF8"
       "0JBej90DEYbRyFNKex5GN41RaCxjweC0qh2LBrITVa0uymiUWsYlWZSQU3IOdLWjhj0SYTdmO3OJ"
       "HGoiqeuQfTJhT+XpvRuUXRKjd/NiHKfxiFesLVValJFNqKxDeVGZVMs2VBbkeW9GVe7AAy1olAK1"
       "H1KZm5Izse4CZzrhKIvqO5MiE1Nx7mcoUedZ0XU1+Hm9iNnGaGzX9fwH"))

(defn- prices [side]
  (mapv #(js/parseFloat (:px %)) side))

(deftest build-subscription-uses-the-short-coin-key-test
  ;; The server rejects {:type "l2" :coin "BTC"} with a JSON parse error; the key is `:c`.
  (is (= {:type "l2" :c "BTC"} (l2/build-subscription "BTC"))))

(deftest eligible-only-for-unaggregated-books-test
  (testing "the default full-precision mode contributes no nSigFigs"
    (is (true? (l2/eligible? {})))
    (is (true? (l2/eligible? nil))))
  (testing "every aggregated mode the subscription builder honours is ineligible"
    (is (false? (l2/eligible? {:nSigFigs 2})))
    (is (false? (l2/eligible? {:nSigFigs 3})))
    (is (false? (l2/eligible? {:nSigFigs 4})))
    (is (false? (l2/eligible? {:nSigFigs 5}))))
  (testing "an nSigFigs the subscription builder would discard leaves the book unaggregated"
    (is (true? (l2/eligible? {:nSigFigs 9})))))

(deftest frame-discrimination-test
  (let [snapshot-frame {:s {:coin "BTC" :time 1 :levels [[] []]}}
        delta-frame {:c "abc"}]
    (is (some? (l2/snapshot-payload snapshot-frame)))
    (is (nil? (l2/delta-blob snapshot-frame)))
    (is (nil? (l2/snapshot-payload delta-frame)))
    (is (= "abc" (l2/delta-blob delta-frame)))
    (testing "an empty blob is not a delta"
      (is (nil? (l2/delta-blob {:c ""}))))))

(deftest snapshot-sides-sorts-bids-descending-and-asks-ascending-test
  (let [[bids asks] (l2/snapshot-sides
                     {:coin "BTC"
                      :time 1
                      :levels [[{:px "98" :sz "1"} {:px "100" :sz "2"} {:px "99" :sz "3"}]
                               [{:px "103" :sz "1"} {:px "101" :sz "2"} {:px "102" :sz "3"}]]})]
    (is (= [100 99 98] (prices bids)))
    (is (= [101 102 103] (prices asks)))
    (testing "level maps are passed through unchanged for the existing book builder"
      (is (= {:px "100" :sz "2"} (first bids))))))

(deftest parse-delta-normalizes-short-keys-test
  (let [delta (l2/parse-delta
               (js/JSON.stringify
                (clj->js {"c" "BTC"
                          "t" 1787325069646
                          "l" [[{"p" "100" "s" "1"}] [{"p" "101" "s" "2"}]]
                          "r" [[3] [4 5]]})))]
    (is (= "BTC" (:coin delta)))
    (is (= 1787325069646 (:time delta)))
    (is (= [[{:px "100" :sz "1"}] [{:px "101" :sz "2"}]] (:upserts delta)))
    (is (= [[3] [4 5]] (:removals delta))))
  (testing "a delta with neither upserts nor removals still parses to empty sides"
    (let [delta (l2/parse-delta (js/JSON.stringify (clj->js {"c" "BTC" "t" 1})))]
      (is (= [[] []] (:upserts delta)))
      (is (= [[] []] (:removals delta)))))
  (testing "non-delta text yields nil rather than throwing"
    (is (nil? (l2/parse-delta nil)))
    (is (nil? (l2/parse-delta "")))))

(deftest apply-delta-upserts-by-price-test
  (let [sides [[{:px "100" :sz "1"} {:px "99" :sz "2"}]
               [{:px "101" :sz "1"}]]]
    (testing "an existing price is updated in place"
      (let [[bids _] (l2/apply-delta sides {:upserts [[{:px "99" :sz "7"}] []]
                                            :removals [[] []]})]
        (is (= [100 99] (prices bids)))
        (is (= "7" (:sz (second bids))))))
    (testing "an unseen price is inserted and the side re-sorted"
      (let [[bids _] (l2/apply-delta sides {:upserts [[{:px "99.5" :sz "3"}] []]
                                            :removals [[] []]})]
        (is (= [100 99.5 99] (prices bids)))))
    (testing "an upsert drops the stale order count rather than reporting a wrong one"
      (let [[bids _] (l2/apply-delta [[{:px "100" :sz "1" :n 4}] []]
                                     {:upserts [[{:px "100" :sz "9"}] []]
                                      :removals [[] []]})]
        (is (= {:px "100" :sz "9"} (first bids)))))))

(deftest apply-delta-removes-by-index-test
  (let [sides [[{:px "100" :sz "1"} {:px "99" :sz "2"} {:px "98" :sz "3"}]
               [{:px "101" :sz "1"} {:px "102" :sz "2"}]]
        [bids asks] (l2/apply-delta sides {:upserts [[] []]
                                           :removals [[1] [0]]})]
    (is (= [100 98] (prices bids)))
    (is (= [102] (prices asks)))))

(deftest apply-delta-removes-before-upserting-test
  ;; The load-bearing ordering rule. `:r` indexes the PRE-upsert side, so an upsert that
  ;; changes the ordering shifts every later index if it is applied first.
  ;;
  ;; bids [100 99 98], remove index 1, upsert a new best bid of 101:
  ;;   remove-first : [100 98] -> insert 101 -> [101 100 98]   <- correct
  ;;   upsert-first : [101 100 99 98] -> drop index 1 -> [101 99 98]
  ;;
  ;; Verified against live `l2Book` snapshots at identical `time` values: remove-first
  ;; matched 19/19 across BTC and ETH, upsert-first matched 2/19.
  (let [sides [[{:px "100" :sz "1"} {:px "99" :sz "2"} {:px "98" :sz "3"}] []]
        [bids _] (l2/apply-delta sides {:upserts [[{:px "101" :sz "5"}] []]
                                        :removals [[1] []]})]
    (is (= [101 100 98] (prices bids)))
    (is (not= [101 99 98] (prices bids)))))

(deftest apply-delta-handles-multiple-removals-on-one-side-test
  ;; Removal indices are all resolved against the same original ordering, so they must
  ;; not shift as earlier entries are dropped.
  (let [sides [[] [{:px "101" :sz "1"} {:px "102" :sz "2"} {:px "103" :sz "3"} {:px "104" :sz "4"}]]
        [_ asks] (l2/apply-delta sides {:upserts [[] []]
                                        :removals [[] [0 2]]})]
    (is (= [102 104] (prices asks)))))

(deftest test-runtime-can-inflate-raw-deflate-test
  ;; The suite runs on Node, which has supported DecompressionStream('deflate-raw') since
  ;; v18. Asserting it here means a runner change cannot silently skip the decode test
  ;; below into its no-op branch.
  (is (true? (platform/inflate-raw-base64-supported?))))

(deftest captured-delta-decodes-and-parses-test
  (if-not (platform/inflate-raw-base64-supported?)
    (is true "runtime cannot inflate raw DEFLATE; decode path is exercised in the browser")
    (async done
      (-> (platform/inflate-raw-base64! captured-delta-blob)
          (.then (fn [text]
                   (let [delta (l2/parse-delta text)]
                     (is (= "BTC" (:coin delta)))
                     (is (= 1787325069646 (:time delta)))
                     (testing "both sides carry upserts and the ask side carries removals"
                       (is (= 13 (count (first (:upserts delta)))))
                       (is (= 13 (count (second (:upserts delta)))))
                       (is (= [] (first (:removals delta))))
                       (is (= [5 16] (second (:removals delta)))))
                     (testing "upsert levels use the same key shape the book builder consumes"
                       (is (= {:px "77720.0" :sz "17.95974"}
                              (first (first (:upserts delta)))))))
                   (done)))
          (.catch (fn [error]
                    (is false (str "decode failed: " error))
                    (done)))))))
