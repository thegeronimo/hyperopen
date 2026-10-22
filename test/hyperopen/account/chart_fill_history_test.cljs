(ns hyperopen.account.chart-fill-history-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [hyperopen.account.chart-fill-history :as history]
            [hyperopen.account.chart-fill-history-effects :as history-effects]
            [hyperopen.api.projections :as api-projections]
            [hyperopen.chart.actions :as chart-actions]
            [hyperopen.startup.runtime.account-state :as account-state]))

(def ^:private addr "0xAbC0000000000000000000000000000000000001")
(def ^:private addr-lc "0xabc0000000000000000000000000000000000001")
(def ^:private other "0xdef0000000000000000000000000000000000002")

;; realistic epoch milliseconds (after the 2023 history floor)
(def ^:private t0 1790000000000)
(def ^:private hour (* 60 60 1000))

(defn- fill [tid time-ms]
  {:coin "HYPE" :tid tid :side "B" :px "10" :sz "1" :time time-ms})

(defn- fills
  "Fills tid from..to-1, one per second starting at t0 + from seconds."
  [from to]
  (mapv #(fill % (+ t0 (* 1000 %))) (range from to)))

(defn- at [i] (+ t0 (* 1000 i)))

(deftest merge-dedupes-by-trade-id-sorts-newest-first-and-caps-test
  (let [merged (history/merge-fills [(fill 1 (at 1)) (fill 2 (at 2))]
                                    [(fill 2 (at 2)) (fill 3 (at 3))])]
    (is (= [3 2 1] (mapv :tid merged))))
  (testing "fills of one order with distinct trade ids all count"
    (is (= 2 (count (history/merge-fills [] [(assoc (fill 1 (at 1)) :oid 9)
                                             (assoc (fill 2 (at 1)) :oid 9)])))))
  (testing "capped at the API's 10000-fill horizon"
    (is (= history/max-rows (count (history/merge-fills (fills 0 6000) (fills 6000 10500)))))))

(deftest snapshot-scopes-history-to-the-account-test
  (let [state (history/apply-snapshot {} addr (fills 0 10) {:rest-page? true})]
    (is (= addr-lc (get-in state [:chart-trades :address])))
    (is (= 10 (count (history/fills-for state (.toUpperCase addr-lc)))) "address match is case-insensitive")
    (is (true? (get-in state [:chart-trades :exhausted?])) "a clearly short REST page holds the whole history")
    (is (= 0 (get-in state [:chart-trades :covered-from-ms])))
    (is (nil? (history/fills-for state other)))
    (testing "a snapshot for another account replaces, never mixes"
      (let [state* (history/apply-snapshot state other [(fill 99 (at 99))] {:rest-page? true})]
        (is (= [99] (mapv :tid (history/fills-for state* other))))))
    (testing "websocket snapshots never mark the history complete"
      (is (false? (get-in (history/apply-snapshot {} addr (fills 0 10)) [:chart-trades :exhausted?]))))
    (testing "an aggregated page of 1500 rows is not trusted to be complete"
      (is (false? (get-in (history/apply-snapshot {} addr (fills 0 1500) {:rest-page? true})
                          [:chart-trades :exhausted?]))))))

(deftest a-cut-off-snapshot-leaves-its-oldest-millisecond-uncovered-test
  (let [state (history/apply-snapshot {} addr (fills 100 2100) {:rest-page? true})]
    (is (= (inc (at 100)) (get-in state [:chart-trades :covered-from-ms])))
    (is (= (at 100) (history/next-older-end-ms state addr t0))
        "the next window ends at, and so refetches, the boundary millisecond")))

(deftest live-fills-are-not-capped-at-200-test
  (let [state (-> {}
                  (history/apply-snapshot addr (fills 0 250))
                  (history/apply-live addr [(fill 1000 (at 1000))]))]
    (is (= 251 (count (history/fills-for state addr))))
    (is (= {} (history/apply-live {} addr [(fill 1 (at 1))])) "ignored until a snapshot exists")))

(deftest rest-pages-are-authoritative-over-per-trade-websocket-rows-test
  ;; one taker order sweeping three levels: the websocket streams 3 trades,
  ;; REST returns them as 1 aggregated row
  (let [ws-rows [(fill 11 (at 5)) (fill 12 (at 5)) (fill 13 (at 5))]
        rest-row (assoc (fill 11 (at 5)) :sz "3")]
    (testing "websocket first, then REST: the REST page replaces its span"
      (let [state (-> {}
                      (history/apply-snapshot addr ws-rows)
                      (history/apply-snapshot addr [rest-row (fill 1 (at 1))] {:rest-page? true}))]
        (is (= ["3" "1"] (mapv :sz (history/fills-for state addr))))))
    (testing "REST first, then a websocket snapshot or live rows inside its span are dropped"
      (let [state (-> {}
                      (history/apply-snapshot addr [rest-row (fill 1 (at 1))] {:rest-page? true})
                      (history/apply-snapshot addr ws-rows)
                      (history/apply-live addr [(fill 13 (at 5)) (fill 20 (at 9))]))]
        (is (= [20 11 1] (mapv :tid (history/fills-for state addr))))))))

(deftest next-older-end-ms-pages-only-when-candles-reach-past-covered-history-test
  (let [state (history/apply-snapshot {} addr (fills 100 2100) {:rest-page? true})]
    (is (= (at 100) (history/next-older-end-ms state addr t0)))
    (is (nil? (history/next-older-end-ms state addr (at 200))) "candles start after the covered history")
    (is (nil? (history/next-older-end-ms state other t0)))
    (is (nil? (history/next-older-end-ms (history/begin-older-page state addr (at 100)) addr t0))
        "one window in flight at a time")
    (is (nil? (history/next-older-end-ms (history/apply-snapshot {} addr (fills 0 10) {:rest-page? true})
                                         addr t0))
        "exhausted")))

(deftest older-window-merges-only-for-the-request-in-flight-test
  (let [end (at 100)
        start (- end hour)
        state (-> {}
                  (history/apply-snapshot addr (fills 100 2100) {:rest-page? true})
                  (history/begin-older-page addr end))]
    (is (= hour (get-in state [:chart-trades :window-ms]))
        "first window sized like the newest page's span, at least an hour")
    (testing "a response for a different window is dropped"
      (is (= state (history/apply-older-window state addr 12345 0 (fills 0 100) {}))))
    (testing "the matching window replaces its span, extends coverage and clears the in-flight marker"
      (let [state* (history/apply-older-window state addr end start (fills 0 101) {})]
        (is (= 2100 (count (history/fills-for state* addr))) "the boundary-millisecond fill is not doubled")
        (is (= start (get-in state* [:chart-trades :covered-from-ms])))
        (is (nil? (get-in state* [:chart-trades :loading-end-ms])))
        (is (false? (get-in state* [:chart-trades :exhausted?])))
        (is (= (dec start) (history/next-older-end-ms state* addr 0)))))
    (testing "an empty window still extends coverage and widens the next one"
      (let [state* (history/apply-older-window state addr end start [] {})]
        (is (= start (get-in state* [:chart-trades :covered-from-ms])))
        (is (= (* 2 hour) (get-in state* [:chart-trades :window-ms])))
        (is (false? (get-in state* [:chart-trades :exhausted?])) "an empty window alone never ends paging")))
    (testing "paging ends at the 2023 history floor"
      (let [state* (history/apply-older-window state addr end history/history-floor-ms [] {})]
        (is (true? (get-in state* [:chart-trades :exhausted?])))))
    (testing "a truncated window keeps coverage and stops paging"
      (let [state* (history/apply-older-window state addr end start (fills 60 100) {:truncated? true})]
        (is (= (inc (at 100)) (get-in state* [:chart-trades :covered-from-ms])))
        (is (true? (get-in state* [:chart-trades :exhausted?])))))
    (testing "a complete snapshot that landed meanwhile is not undone"
      (let [state* (-> state
                       (history/apply-snapshot addr (fills 0 10) {:rest-page? true})
                       (history/apply-older-window addr end start [] {}))]
        (is (= 0 (get-in state* [:chart-trades :covered-from-ms])))
        (is (true? (get-in state* [:chart-trades :exhausted?])))))
    (testing "an error stops paging instead of retrying every frame"
      (let [state* (history/fail-older-page state addr end)]
        (is (true? (get-in state* [:chart-trades :exhausted?])))
        (is (nil? (get-in state* [:chart-trades :loading-end-ms])))))))

(deftest account-reset-drops-the-chart-history-test
  (is (not (contains? (account-state/reset-account-surface-state
                       (history/apply-snapshot {} addr (fills 0 3)))
                      :chart-trades))))

(deftest rest-fills-projection-seeds-history-only-for-the-current-account-test
  (let [state {:wallet {:address addr}}]
    (testing "without a requested address only the shared fills update"
      (is (nil? (:chart-trades (api-projections/apply-user-fills-success state (fills 0 3))))))
    (testing "a response for the effective account seeds the chart history"
      (let [state* (api-projections/apply-user-fills-success state addr (fills 0 3))]
        (is (= (fills 0 3) (get-in state* [:orders :fills])))
        (is (= 3 (count (history/fills-for state* addr))))))
    (testing "a late response for a previous account never enters the history"
      (let [state* (api-projections/apply-user-fills-success state other (fills 0 3))]
        (is (nil? (:chart-trades state*)))))))

(deftest request-older-chart-fills-action-guards-test
  (let [state (merge {:wallet {:address addr}}
                     (history/apply-snapshot {} addr (fills 100 2100) {:rest-page? true}))]
    (is (= [[:effects/fetch-older-chart-fills {:address addr-lc :end-ms (at 100)}]]
           (chart-actions/request-older-chart-fills state {:end-ms (at 100)})))
    (is (= [] (chart-actions/request-older-chart-fills state {:end-ms (at 200)}))
        "not older than the covered history")
    (is (= [] (chart-actions/request-older-chart-fills
               (history/begin-older-page state addr (at 100)) {:end-ms (at 100)})))
    (is (= [] (chart-actions/request-older-chart-fills {:wallet {:address other}} {:end-ms 1})))))

(deftest fetch-older-chart-fills-effect-drops-a-response-after-an-account-switch-test
  (async done
    (let [store (atom (history/apply-snapshot {} addr (fills 100 2100) {:rest-page? true}))
          resolve* (atom nil)
          request-fn (fn [address window _]
                       (is (= addr-lc address))
                       (is (= {:start-ms (- (at 100) hour) :end-ms (at 100) :aggregate? false} window))
                       (js/Promise. (fn [resolve _] (reset! resolve* resolve))))
          p (history-effects/fetch-older-chart-fills! {:store store :address addr-lc :end-ms (at 100)
                                                       :request-fn request-fn})]
      (is (= (at 100) (get-in @store [:chart-trades :loading-end-ms])))
      ;; account switch while the request is in flight
      (swap! store #(history/apply-snapshot % other [(fill 7 (at 7))] {:rest-page? true}))
      (@resolve* (fills 0 100))
      (.then p (fn [_]
                 (is (= [7] (mapv :tid (history/fills-for @store other))))
                 (done))))))

(deftest fetch-older-window-pages-forward-until-a-short-page-test
  ;; userFillsByTime returns the OLDEST 2000 at or after startTime
  (async done
    (let [base (+ t0 (* 10 hour))
          store (atom (history/apply-snapshot {} addr (mapv #(fill (+ 100000 %) (+ base (* 1000 %))) (range 2000))
                                              {:rest-page? true}))
          end (history/next-older-end-ms @store addr 0)
          start (- end hour)
          calls (atom [])
          request-fn (fn [_ window _]
                       (swap! calls conj window)
                       (js/Promise.resolve
                        (if (= 1 (count @calls))
                          (mapv #(fill % (+ start %)) (range 2000))
                          ;; ...including the boundary-millisecond fill the REST page
                          ;; already held (the window is authoritative for it)
                          (conj (mapv #(fill (+ 1999 %) (+ start 1999 %)) (range 10))
                                (fill 100000 end)))))]
      (.then (history-effects/fetch-older-chart-fills! {:store store :address addr :end-ms end
                                                        :request-fn request-fn})
             (fn [_]
               (is (= [{:start-ms start :end-ms end :aggregate? false}
                       {:start-ms (+ start 1999) :end-ms end :aggregate? false}]
                      @calls)
                   "second page starts at the last returned time")
               (is (= (+ 2000 2009) (count (history/fills-for @store addr))) "boundary duplicate merged")
               (is (= start (get-in @store [:chart-trades :covered-from-ms])))
               (done))))))
