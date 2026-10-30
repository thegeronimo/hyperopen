(ns hyperopen.api.trading.nonce-coordinator-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.api.trading.nonce-coordinator :as nc]
            [hyperopen.platform :as platform]
            [hyperopen.platform.locks :as locks]))

(defn- with-frozen-clock
  "Freeze platform/now-ms until the promise from `f` settles. `with-redefs`
  would restore the clock before queued lock bodies run."
  [ms f]
  (let [orig platform/now-ms]
    (set! platform/now-ms (constantly ms))
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (f)))
        (.finally (fn [] (set! platform/now-ms orig))))))

(deftest next-nonce-is-strictly-above-cursor-and-floor-test
  (is (= 1000 (nc/next-nonce 1000 nil nil)))
  (is (= 1001 (nc/next-nonce 1000 1000 nil)) "same millisecond as the cursor still advances")
  (is (= 5001 (nc/next-nonce 1000 5000 nil)) "a cursor ahead of the clock wins")
  (is (= 7001 (nc/next-nonce 1000 5000 7000)) "a floor (session cursor or retry) wins")
  (is (= 2000 (nc/next-nonce 2000 1500 1200)))
  (is (= 1000 (nc/next-nonce 1000.9 js/NaN nil)) "non-finite cursors are ignored"))

(deftest keys-are-per-network-and-normalized-signer-test
  (is (= (nc/cursor-key :hyperliquid-mainnet "0xABC ") (nc/cursor-key :hyperliquid-mainnet "0xabc")))
  (is (not= (nc/cursor-key :hyperliquid-mainnet "0xabc") (nc/cursor-key :hyperliquid-testnet "0xabc")))
  (is (= :hyperliquid-mainnet (nc/network-for-options {})))
  (is (= :hyperliquid-testnet (nc/network-for-options {:is-mainnet false}))))

(deftest concurrent-reservations-are-unique-and-increasing-test
  (async done
    (nc/reset-memory-cursors!)
    (locks/reset-in-tab-state!)
    ;; A frozen clock is the worst case: every producer reserves in the same ms.
    (-> (with-frozen-clock
         1700000000000
         (fn []
           (js/Promise.all
            (clj->js (for [i (range 25)]
                       (nc/reserve-nonce! {:signer (if (even? i) "0xAgent" "0xagent")}))))))
          (.then (fn [results]
                   (let [nonces (map :nonce (array-seq results))]
                     (is (= 25 (count (distinct nonces))) "two subaccounts on one agent share one sequence")
                     (is (= (range 1700000000000 1700000000025) (sort nonces))))))
          (.catch (fn [e] (is false (str e))))
          (.finally done))))

(deftest reservation-respects-floor-and-rejects-without-signer-test
  (async done
    (nc/reset-memory-cursors!)
    (locks/reset-in-tab-state!)
    (-> (with-frozen-clock
         100
         (fn []
           (-> (nc/reserve-nonce! {:signer "0xs" :floor 500})
          (.then (fn [{:keys [nonce coordination network]}]
                   (is (= 501 nonce))
                   (is (= :hyperliquid-mainnet network))
                   (is (= :degraded coordination) "a nonce far ahead of the clock is never claimed cross-tab safe")))
          (.then (fn [_] (nc/reserve-nonce! {:signer "0xs"})))
          (.then (fn [{:keys [nonce]}] (is (= 502 nonce) "the stored cursor carries forward")))
          (.then (fn [_] (nc/reserve-nonce! {:signer nil})))
          (.then (fn [_] (is false "must reject without a signer"))
                 (fn [e] (is (re-find #"signer" (.-message e))))))))
        (.finally done))))

(deftest a-failing-lock-holder-does-not-poison-the-queue-test
  (async done
    (locks/reset-in-tab-state!)
    (let [order (atom [])]
      (-> (js/Promise.all
           #js [(-> (locks/with-exclusive-lock! "q" (fn [] (swap! order conj 1) (throw (js/Error. "boom"))))
                    (.catch (fn [_] :failed)))
                (locks/with-exclusive-lock! "q" (fn [] (swap! order conj 2) :ok))])
          (.then (fn [results]
                   (is (= [:failed :ok] (vec (array-seq results))))
                   (is (= [1 2] @order))))
          (.finally done)))))

(deftest if-available-never-waits-test
  (async done
    (locks/reset-in-tab-state!)
    (let [release (atom nil)
          held (locks/with-exclusive-lock! "a" (fn [] (js/Promise. (fn [resolve _] (reset! release resolve)))))]
      (-> (locks/with-exclusive-lock-if-available! "a" (fn [] :ran))
          (.then (fn [r] (is (= {:acquired? false} r)) (@release :done) held))
          (.then (fn [_] (locks/with-exclusive-lock-if-available! "a" (fn [] :ran))))
          (.then (fn [r] (is (= {:acquired? true :value :ran} r))))
          (.finally done)))))

(deftest coordination-is-claimed-only-when-the-clock-passes-the-nonce-test
  ;; Second review M1/M2: the holder waits out a lead of at most 2 ms
  ;; synchronously; a larger lead (cursor or floor ahead of the clock, clock
  ;; stepped back) releases at once and is :degraded.
  (async done
    (nc/reset-memory-cursors!)
    (locks/reset-in-tab-state!)
    (let [clock (atom 5000)
          orig platform/now-ms]
      (set! platform/now-ms (fn [] (swap! clock inc)))
      (-> (nc/reserve-nonce! {:signer "0xhold"})
          (.then (fn [{:keys [nonce coordination]}]
                   (is (= :in-tab-only coordination) "node has no Web Locks; the clock passed the nonce")
                   (is (> @clock nonce))))
          (.then (fn [_] (nc/reserve-nonce! {:signer "0xhold" :floor (+ @clock 500)})))
          (.then (fn [{:keys [coordination]}] (is (= :degraded coordination))))
          (.catch (fn [e] (is false (str e))))
          (.finally (fn [] (set! platform/now-ms orig) (done)))))))

(deftest storage-failures-degrade-instead-of-blocking-test
  ;; Review B1: a throwing localStorage must never block signing (cancels included).
  (async done
    (nc/reset-memory-cursors!)
    (locks/reset-in-tab-state!)
    (let [orig-get platform/local-storage-get
          orig-set platform/local-storage-set!]
      (set! platform/local-storage-get (fn [_] (throw (js/Error. "SecurityError"))))
      (set! platform/local-storage-set! (fn [_ _] (throw (js/Error. "QuotaExceededError"))))
      (-> (nc/reserve-nonce! {:signer "0xq"})
          (.then (fn [a]
                   (is (= :degraded (:coordination a)))
                   (-> (nc/reserve-nonce! {:signer "0xq"})
                       (.then (fn [b] (is (> (:nonce b) (:nonce a)) "still monotonic from memory"))))))
          (.catch (fn [e] (is false (str e))))
          (.finally (fn []
                      (set! platform/local-storage-get orig-get)
                      (set! platform/local-storage-set! orig-set)
                      (done)))))))

(deftest far-future-cursors-are-ignored-test
  ;; Review m2: a cursor beyond the venue window (T + 1 day) would make every
  ;; action fail; it is ignored instead.
  (is (nil? (nc/usable-cursor 1000 (+ 1000 nc/max-cursor-lead-ms 1))))
  (is (= 5000 (nc/usable-cursor 1000 5000))))
