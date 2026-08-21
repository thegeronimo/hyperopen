(ns hyperopen.websocket.orderbook-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.platform :as platform]
            [hyperopen.state.trading :as trading]
            [hyperopen.state.trading.test-support :as support]
            [hyperopen.trading.order-form-transitions :as transitions]
            [hyperopen.websocket.market-projection-runtime :as market-runtime]
            [hyperopen.websocket.orderbook :as orderbook]
            [hyperopen.websocket.orderbook-policy :as policy]
            [hyperopen.websocket.client :as ws-client]))

(defn- reset-orderbook-state!
  []
  (orderbook/reset-l2-decode-chain!)
  (reset! orderbook/orderbook-state {:subscriptions {}
                                     :l2-subscriptions {}
                                     :l2-sides {}
                                     :books {}}))

(deftest create-orderbook-data-handler-coalesces-burst-updates-per-frame-test
  (reset-orderbook-state!)
  (market-runtime/reset-market-projection-runtime!)
  (try
    (let [store (atom {:orderbooks {}})
          store-write-count (atom 0)
          schedule-count (atom 0)
          scheduled-callback (atom nil)
          watch-key ::store-write-counter
          payload-a {:channel "l2Book"
                     :data {:coin "BTC"
                            :levels [[{:px "100" :sz "2"}]
                                     [{:px "101" :sz "3"}]]
                            :time 1}}
          payload-b {:channel "l2Book"
                     :data {:coin "BTC"
                            :levels [[{:px "99" :sz "4"}]
                                     [{:px "102" :sz "5"}]]
                            :time 2}}]
      (add-watch store watch-key
                 (fn [_ _ old-state new-state]
                   (when (not= old-state new-state)
                     (swap! store-write-count inc))))
      (with-redefs [platform/request-animation-frame! (fn [f]
                                                        (swap! schedule-count inc)
                                                        (reset! scheduled-callback f)
                                                        :raf-id)
                    policy/sort-bids identity
                    policy/sort-asks identity]
        (let [handler (orderbook/create-orderbook-data-handler store)]
          (handler payload-a)
          (handler payload-b)
          (is (= 1 @schedule-count))
          (is (= 0 @store-write-count))
          ;; Local module state still tracks latest payload immediately.
          (is (= 2 (get-in @orderbook/orderbook-state [:books "BTC" :timestamp])))
          (@scheduled-callback 16)
          (is (= 1 @store-write-count))
          (let [book (get-in @store [:orderbooks "BTC"])]
            (is (= [{:px "99" :sz "4"}] (:bids book)))
            (is (= [{:px "102" :sz "5"}] (:asks book)))
            (is (= 2 (:timestamp book)))
            (is (= [{:px "99" :sz "4" :px-num 99 :sz-num 4}]
                   (get-in book [:render :display-bids])))
            (is (= [{:px "102" :sz "5" :px-num 102 :sz-num 5}]
                   (get-in book [:render :display-asks])))
            (is (= [{:px "99" :sz "4" :px-num 99 :sz-num 4 :cum-size 4 :cum-value 396}]
                   (get-in book [:render :bids-with-totals])))
            (is (= [{:px "102" :sz "5" :px-num 102 :sz-num 5 :cum-size 5 :cum-value 510}]
                   (get-in book [:render :asks-with-totals])))
            (is (= {:px "99" :sz "4" :px-num 99 :sz-num 4}
                   (get-in book [:render :best-bid])))
            (is (= {:px "102" :sz "5" :px-num 102 :sz-num 5}
                   (get-in book [:render :best-ask]))))))
      (remove-watch store watch-key))
    (finally
      (reset-orderbook-state!)
      (market-runtime/reset-market-projection-runtime!))))

(deftest create-orderbook-data-handler-reprojects-active-order-form-on-ask-tick-test
  (reset-orderbook-state!)
  (market-runtime/reset-market-projection-runtime!)
  (try
    (let [base (support/spot-buy-state {:ask "1.00" :usdc "100"})
          ;; Classic spot account with a committed 100 USDC buy at best-ask 1.00.
          committed (support/apply-order-form-transition
                     base
                     (transitions/set-order-size-display base "100"))
          store (atom committed)
          scheduled-callback (atom nil)]
      (with-redefs [platform/request-animation-frame! (fn [f]
                                                        (reset! scheduled-callback f)
                                                        :raf-id)]
        (let [handler (orderbook/create-orderbook-data-handler store)]
          ;; Best-ask for the active spot market ticks up ~1%.
          (handler {:channel "l2Book"
                    :data {:coin "PURR"
                           :levels [[{:px "0.99" :sz "1000"}]
                                    [{:px "1.01" :sz "1000"}]]
                           :time 2}})
          (@scheduled-callback 16)
          (let [form (trading/order-form-draft @store)]
            (is (= [{:px "1.01" :sz "1000"}]
                   (:asks (get-in @store [:orderbooks "PURR"]))))
            ;; The displayed commitment is preserved while the canonical size is
            ;; re-projected, so the affordability check no longer false-rejects.
            (is (= "100" (:size-display form)))
            (is (empty? (support/validation-codes
                         (trading/validate-order-form @store form))))))))
    (finally
      (reset-orderbook-state!)
      (market-runtime/reset-market-projection-runtime!))))

(deftest unsubscribe-orderbook-updates-local-state-atomically-test
  (reset-orderbook-state!)
  (try
    (reset! orderbook/orderbook-state {:subscriptions {"BTC" {:type "l2Book" :coin "BTC"}}
                                       :books {"BTC" {:bids [{:px "100"}]
                                                      :asks [{:px "101"}]}}})
    (let [write-count (atom 0)
          watch-key ::orderbook-write-counter]
      (add-watch orderbook/orderbook-state watch-key
                 (fn [_ _ old-state new-state]
                   (when (not= old-state new-state)
                     (swap! write-count inc))))
      (with-redefs [ws-client/send-message! (fn [_] true)]
        (orderbook/unsubscribe-orderbook! "BTC"))
      (is (= 1 @write-count))
      (is (nil? (get-in @orderbook/orderbook-state [:subscriptions "BTC"])))
      (is (nil? (get-in @orderbook/orderbook-state [:books "BTC"])))
      (remove-watch orderbook/orderbook-state watch-key))
    (finally
      (reset-orderbook-state!))))

(deftest create-orderbook-data-handler-skips-store-projection-for-timestamp-only-refresh-test
  (reset-orderbook-state!)
  (market-runtime/reset-market-projection-runtime!)
  (try
    (let [store (atom {:orderbooks {}})
          store-write-count (atom 0)
          schedule-count (atom 0)
          scheduled-callbacks (atom [])
          watch-key ::duplicate-store-write-counter
          payload-a {:channel "l2Book"
                     :data {:coin "BTC"
                            :levels [[{:px "100" :sz "2"}]
                                     [{:px "101" :sz "3"}]]
                            :time 1}}
          payload-b {:channel "l2Book"
                     :data {:coin "BTC"
                            :levels [[{:px "100" :sz "2"}]
                                     [{:px "101" :sz "3"}]]
                            :time 2}}]
      (add-watch store watch-key
                 (fn [_ _ old-state new-state]
                   (when (not= old-state new-state)
                     (swap! store-write-count inc))))
      (with-redefs [platform/request-animation-frame! (fn [f]
                                                        (swap! schedule-count inc)
                                                        (swap! scheduled-callbacks conj f)
                                                        (keyword (str "raf-" @schedule-count)))]
        (let [handler (orderbook/create-orderbook-data-handler store)]
          (handler payload-a)
          (is (= 1 @schedule-count))
          ((first @scheduled-callbacks) 16)
          (is (= 1 @store-write-count))
          (is (= 1 (:timestamp (get-in @store [:orderbooks "BTC"]))))
          (handler payload-b)
          (is (= 1 @schedule-count))
          (is (= 1 @store-write-count))
          (is (= 2 (get-in @orderbook/orderbook-state [:books "BTC" :timestamp])))
          (is (= 1 (get-in @store [:orderbooks "BTC" :timestamp])))))
      (remove-watch store watch-key))
    (finally
      (reset-orderbook-state!)
      (market-runtime/reset-market-projection-runtime!))))

;; ---------------------------------------------------------------------------
;; Fast incremental `l2` channel
;;
;; Hyperliquid throttles the documented `l2Book` channel to roughly one snapshot
;; every 5.4s. The undocumented `l2` channel carries the same book at roughly 550ms
;; as one snapshot plus compressed deltas. Both stay subscribed: `l2Book` bootstraps
;; the book, backstops a missing `l2` channel, and keeps the websocket freshness
;; surface (keyed on the "l2Book" topic) working unchanged.

(defn- sent-subscriptions [sent method]
  (->> @sent
       (filter #(= method (:method %)))
       (mapv :subscription)))

(defn- with-captured-sends [f]
  (let [sent (atom [])]
    (with-redefs [ws-client/send-message! (fn [message] (swap! sent conj message) true)]
      (f sent))
    sent))

(deftest subscribe-orderbook-adds-the-fast-l2-stream-alongside-l2book-test
  (reset-orderbook-state!)
  (try
    (let [sent (with-captured-sends
                 (fn [_]
                   (with-redefs [platform/inflate-raw-base64-supported? (constantly true)]
                     (orderbook/subscribe-orderbook! "BTC" {}))))]
      (is (= [{:type "l2Book" :coin "BTC"} {:type "l2" :c "BTC"}]
             (sent-subscriptions sent "subscribe")))
      (is (= {:type "l2" :c "BTC"}
             (get-in @orderbook/orderbook-state [:l2-subscriptions "BTC"]))))
    (finally
      (reset-orderbook-state!))))

(deftest subscribe-orderbook-skips-the-fast-stream-when-the-runtime-cannot-inflate-test
  ;; No DecompressionStream('deflate-raw') means no delta decoding, so the book stays
  ;; on `l2Book` alone and keeps working at the old cadence with nothing else to detect.
  (reset-orderbook-state!)
  (try
    (let [sent (with-captured-sends
                 (fn [_]
                   (with-redefs [platform/inflate-raw-base64-supported? (constantly false)]
                     (orderbook/subscribe-orderbook! "BTC" {}))))]
      (is (= [{:type "l2Book" :coin "BTC"}] (sent-subscriptions sent "subscribe")))
      (is (nil? (get-in @orderbook/orderbook-state [:l2-subscriptions "BTC"]))))
    (finally
      (reset-orderbook-state!))))

(deftest subscribe-orderbook-drops-the-fast-stream-for-aggregated-precision-test
  ;; `l2` silently ignores nSigFigs, so the coarse precision modes must fall back to
  ;; the aggregated `l2Book` subscription rather than silently serving a full-precision
  ;; book. Switching away must also discard the sides the deltas were mutating.
  (reset-orderbook-state!)
  (try
    (with-redefs [platform/inflate-raw-base64-supported? (constantly true)]
      (with-redefs [ws-client/send-message! (fn [_] true)]
        (orderbook/subscribe-orderbook! "BTC" {}))
      (swap! orderbook/orderbook-state assoc-in [:l2-sides "BTC"] [[{:px "100" :sz "1"}] []])
      (let [sent (with-captured-sends
                   (fn [_] (orderbook/subscribe-orderbook! "BTC" {:nSigFigs 3})))]
        ;; The unaggregated `l2Book` subscription is replaced by the aggregated one, and
        ;; the fast stream is dropped entirely because it cannot honour nSigFigs.
        (is (= [{:type "l2Book" :coin "BTC"} {:type "l2" :c "BTC"}]
               (sent-subscriptions sent "unsubscribe")))
        (is (= [{:type "l2Book" :coin "BTC" :nSigFigs 3}]
               (sent-subscriptions sent "subscribe")))
        (is (nil? (get-in @orderbook/orderbook-state [:l2-subscriptions "BTC"])))
        (is (nil? (get-in @orderbook/orderbook-state [:l2-sides "BTC"])))))
    (finally
      (reset-orderbook-state!))))

(deftest unsubscribe-orderbook-clears-both-streams-test
  (reset-orderbook-state!)
  (try
    (reset! orderbook/orderbook-state {:subscriptions {"BTC" {:type "l2Book" :coin "BTC"}}
                                       :l2-subscriptions {"BTC" {:type "l2" :c "BTC"}}
                                       :l2-sides {"BTC" [[{:px "100" :sz "1"}] []]}
                                       :books {"BTC" {:bids [] :asks []}}})
    (let [sent (with-captured-sends
                 (fn [_] (orderbook/unsubscribe-orderbook! "BTC")))]
      (is (= [{:type "l2Book" :coin "BTC"} {:type "l2" :c "BTC"}]
             (sent-subscriptions sent "unsubscribe")))
      (is (nil? (get-in @orderbook/orderbook-state [:l2-subscriptions "BTC"])))
      (is (nil? (get-in @orderbook/orderbook-state [:l2-sides "BTC"])))
      (is (nil? (get-in @orderbook/orderbook-state [:books "BTC"]))))
    (finally
      (reset-orderbook-state!))))

(deftest l2-snapshot-seeds-sides-and-publishes-the-book-test
  (reset-orderbook-state!)
  (market-runtime/reset-market-projection-runtime!)
  (try
    (let [store (atom {:orderbooks {}})
          scheduled-callback (atom nil)]
      (with-redefs [platform/request-animation-frame! (fn [f]
                                                        (reset! scheduled-callback f)
                                                        :raf-id)]
        (let [handler (orderbook/create-l2-data-handler store)]
          (handler {:channel "l2"
                    :data {:s {:coin "BTC"
                               :time 10
                               :levels [[{:px "99" :sz "2"} {:px "100" :sz "1"}]
                                        [{:px "102" :sz "3"} {:px "101" :sz "4"}]]}}})
          (is (= [[{:px "100" :sz "1"} {:px "99" :sz "2"}]
                  [{:px "101" :sz "4"} {:px "102" :sz "3"}]]
                 (get-in @orderbook/orderbook-state [:l2-sides "BTC"])))
          (@scheduled-callback 16)
          (is (= 10 (get-in @store [:orderbooks "BTC" :timestamp]))))))
    (finally
      (reset-orderbook-state!)
      (market-runtime/reset-market-projection-runtime!))))

(deftest l2-delta-applies-to-the-seeded-sides-test
  ;; The decode is asynchronous, so the inflate seam is injected rather than redefined:
  ;; a `with-redefs` would already have unwound by the time the promise chain resolves.
  (reset-orderbook-state!)
  (market-runtime/reset-market-projection-runtime!)
  (async done
    (let [store (atom {:orderbooks {}})
          delta-json (js/JSON.stringify
                      (clj->js {"c" "BTC"
                                "t" 20
                                "l" [[{"p" "98" "s" "5"}] []]
                                "r" [[1] []]}))
          handler (orderbook/create-l2-data-handler
                   store
                   (fn [_] (js/Promise.resolve delta-json)))]
      (handler {:channel "l2"
                :data {:s {:coin "BTC"
                           :time 10
                           :levels [[{:px "100" :sz "1"} {:px "99" :sz "2"}]
                                    [{:px "101" :sz "4"}]]}}})
      (is (= 10 (get-in @orderbook/orderbook-state [:books "BTC" :timestamp])))
      (handler {:channel "l2" :data {:c "blob"}})
      ;; A macrotask lands after every pending microtask, so the chain has resolved.
      (js/setTimeout
       (fn []
         ;; Removal index 1 drops the 99 bid, then the 98 upsert is inserted.
         (is (= [[{:px "100" :sz "1"} {:px "98" :sz "5"}] [{:px "101" :sz "4"}]]
                (get-in @orderbook/orderbook-state [:l2-sides "BTC"])))
         (is (= 20 (get-in @orderbook/orderbook-state [:books "BTC" :timestamp])))
         (reset-orderbook-state!)
         (market-runtime/reset-market-projection-runtime!)
         (done))
       0))))

(deftest l2-delta-without-a-seeding-snapshot-is-ignored-test
  (reset-orderbook-state!)
  (market-runtime/reset-market-projection-runtime!)
  (async done
    (let [store (atom {:orderbooks {}})
          delta-json (js/JSON.stringify (clj->js {"c" "BTC" "t" 20 "l" [[] []] "r" [[] []]}))
          handler (orderbook/create-l2-data-handler
                   store
                   (fn [_] (js/Promise.resolve delta-json)))]
      (handler {:channel "l2" :data {:c "blob"}})
      (js/setTimeout
       (fn []
         (is (nil? (get-in @orderbook/orderbook-state [:l2-sides "BTC"])))
         (is (nil? (get-in @orderbook/orderbook-state [:books "BTC"])))
         (reset-orderbook-state!)
         (market-runtime/reset-market-projection-runtime!)
         (done))
       0))))

(deftest l2-delta-decode-failure-leaves-the-book-untouched-test
  ;; A rejected inflate must not poison the shared decode chain or the stored book.
  (reset-orderbook-state!)
  (market-runtime/reset-market-projection-runtime!)
  (async done
    (let [store (atom {:orderbooks {}})
          handler (orderbook/create-l2-data-handler
                   store
                   (fn [_] (js/Promise.reject (js/Error. "bad payload"))))]
      (handler {:channel "l2"
                :data {:s {:coin "BTC"
                           :time 10
                           :levels [[{:px "100" :sz "1"}] [{:px "101" :sz "4"}]]}}})
      (handler {:channel "l2" :data {:c "blob"}})
      (js/setTimeout
       (fn []
         (is (= [[{:px "100" :sz "1"}] [{:px "101" :sz "4"}]]
                (get-in @orderbook/orderbook-state [:l2-sides "BTC"])))
         (is (= 10 (get-in @orderbook/orderbook-state [:books "BTC" :timestamp])))
         (reset-orderbook-state!)
         (market-runtime/reset-market-projection-runtime!)
         (done))
       0))))

(deftest stale-l2book-snapshot-does-not-drag-a-newer-book-backwards-test
  ;; Once deltas are flowing the stored book is usually newer than the 5s `l2Book`
  ;; snapshot. Replaying the older one would make the ladder stutter every 5 seconds.
  (reset-orderbook-state!)
  (market-runtime/reset-market-projection-runtime!)
  (try
    (let [store (atom {:orderbooks {}})
          scheduled-callbacks (atom [])]
      (with-redefs [platform/request-animation-frame! (fn [f]
                                                        (swap! scheduled-callbacks conj f)
                                                        :raf-id)]
        (let [handler (orderbook/create-orderbook-data-handler store)]
          (handler {:channel "l2Book"
                    :data {:coin "BTC"
                           :levels [[{:px "100" :sz "1"}] [{:px "101" :sz "1"}]]
                           :time 100}})
          ((first @scheduled-callbacks) 16)
          (is (= 100 (get-in @store [:orderbooks "BTC" :timestamp])))
          (handler {:channel "l2Book"
                    :data {:coin "BTC"
                           :levels [[{:px "90" :sz "9"}] [{:px "111" :sz "9"}]]
                           :time 50}})
          (is (= 100 (get-in @orderbook/orderbook-state [:books "BTC" :timestamp])))
          (is (= [{:px "100" :sz "1"}] (:bids (get-in @store [:orderbooks "BTC"]))))
          (testing "a snapshot at or beyond the stored time still applies"
            (handler {:channel "l2Book"
                      :data {:coin "BTC"
                             :levels [[{:px "95" :sz "7"}] [{:px "105" :sz "7"}]]
                             :time 100}})
            (is (= [{:px "95" :sz "7"}]
                   (:bids (get-in @orderbook/orderbook-state [:books "BTC"]))))))))
    (finally
      (reset-orderbook-state!)
      (market-runtime/reset-market-projection-runtime!))))

(deftest l2-snapshot-seeds-a-one-sided-book-test
  ;; A thin market with no resting asks must still seed its sides, otherwise deltas can
  ;; never be positioned and the coin silently stays on the throttled `l2Book` stream.
  (reset-orderbook-state!)
  (market-runtime/reset-market-projection-runtime!)
  (try
    (let [store (atom {:orderbooks {}})]
      (with-redefs [platform/request-animation-frame! (fn [_] :raf-id)]
        (let [handler (orderbook/create-l2-data-handler store)]
          (handler {:channel "l2"
                    :data {:s {:coin "THIN"
                               :time 10
                               :levels [[{:px "1" :sz "5"}] []]}}})
          (is (= [[{:px "1" :sz "5"}] []]
                 (get-in @orderbook/orderbook-state [:l2-sides "THIN"])))
          (is (= 10 (get-in @orderbook/orderbook-state [:books "THIN" :timestamp]))))))
    (finally
      (reset-orderbook-state!)
      (market-runtime/reset-market-projection-runtime!))))
