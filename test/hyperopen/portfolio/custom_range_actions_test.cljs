(ns hyperopen.portfolio.custom-range-actions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.actions :as actions]
            [hyperopen.portfolio.custom-range :as custom-range]))

(def ^:private replace-shareable-route-query-effect
  [:effects/replace-shareable-route-query])

(defn- ms [iso] (.getTime (js/Date. iso)))

(def ^:private domain-from (ms "2024-08-24T00:00:00.000Z"))
(def ^:private domain-to (ms "2026-08-24T00:00:00.000Z"))
(def ^:private bounds {:left 0 :width 1000 :right 1000 :top 0 :bottom 56})

(def ^:private dropdown-closers
  [[[:portfolio-ui :summary-scope-dropdown-open?] false]
   [[:portfolio-ui :summary-time-range-dropdown-open?] false]
   [[:portfolio-ui :performance-metrics-time-range-dropdown-open?] false]])

(defn- save-many
  [path-values]
  [:effects/save-many (into (vec path-values) dropdown-closers)])

(defn- state-with
  ([range] (state-with range nil))
  ([range drag]
   {:portfolio-ui (cond-> {:summary-time-range :one-year}
                    range (assoc :summary-custom-range range)
                    drag (assoc :summary-range-drag drag))}))

(deftest open-seeds-the-strip-with-the-window-on-screen-test
  (let [seed {:from (ms "2026-03-03T00:00:00.000Z")
              :to (ms "2026-06-12T00:00:00.000Z")}]
    (is (= [(save-many [[[:portfolio-ui :summary-range-strip] :chart]
                        [[:portfolio-ui :summary-range-drag] nil]
                        [[:portfolio-ui :summary-custom-range] seed]])
            replace-shareable-route-query-effect]
           (actions/open-portfolio-summary-custom-range {} :chart (:from seed) (:to seed))))
    (testing "opening PUBLISHES the window and refetches: the seed is day-snapped and a
              custom range reads its candles at a different interval than the preset,
              so a silent open would leave the benchmark unfetched and the URL stale"
      (let [effects (actions/open-portfolio-summary-custom-range
                     {:portfolio-ui {:returns-benchmark-coins ["BTC"]}}
                     :chart
                     (:from seed)
                     (:to seed))]
        (is (= replace-shareable-route-query-effect (second effects)))
        (is (= [:effects/fetch-candle-snapshot :coin "BTC" :interval :1d :bars 5000]
               (nth effects 2)))))
    (testing "the strip opens in the panel that asked for it, so Custom... from the
              tearsheet does not reveal a strip on a card the trader scrolled past"
      (let [[_ path-values] (first (actions/open-portfolio-summary-custom-range
                                    {} :metrics (:from seed) (:to seed)))]
        (is (= :metrics (some (fn [[path value]]
                                (when (= path [:portfolio-ui :summary-range-strip]) value))
                              path-values))))
      (testing "and an unrecognized target falls back to the chart rather than hiding it"
        (let [[_ path-values] (first (actions/open-portfolio-summary-custom-range
                                      {} :bogus (:from seed) (:to seed)))]
          (is (= :chart (some (fn [[path value]]
                                (when (= path [:portfolio-ui :summary-range-strip]) value))
                              path-values))))))
    (testing "an unusable seed still opens the strip rather than failing, and stays quiet"
      (is (= [(save-many [[[:portfolio-ui :summary-range-strip] :chart]
                          [[:portfolio-ui :summary-range-drag] nil]])]
             (actions/open-portfolio-summary-custom-range {} :chart nil nil))))))

(deftest close-collapses-the-strip-without-touching-the-range-test
  (is (= [(save-many [[[:portfolio-ui :summary-range-strip] nil]
                      [[:portfolio-ui :summary-range-drag] nil]])]
         (actions/close-portfolio-summary-custom-range {}))))

(deftest drag-start-anchors-a-fresh-sweep-test
  (let [effects (actions/start-portfolio-summary-custom-range-drag
                 (state-with nil) 500 bounds domain-from domain-to)
        [_ path-values] (first effects)
        by-path (into {} (map (fn [[path value]] [path value])) path-values)]
    (is (= :chart (get by-path [:portfolio-ui :summary-range-strip])))
    (is (= :end (get by-path [:portfolio-ui :summary-range-drag])))
    (testing "a fresh sweep is anchored where the pointer went down"
      (let [range (get by-path [:portfolio-ui :summary-custom-range])]
        (is (= (:from range) (:to range)))))
    (testing "degenerate geometry dispatches nothing rather than a bad range"
      (is (= [] (actions/start-portfolio-summary-custom-range-drag
                 (state-with nil) 500 {:left 0 :width 0} domain-from domain-to))))))

(deftest drag-update-is-projection-only-test
  (let [range {:from (custom-range/fraction->time 0.25 {:from domain-from :to domain-to})
               :to (custom-range/fraction->time 0.75 {:from domain-from :to domain-to})}
        effects (actions/update-portfolio-summary-custom-range-drag
                 (state-with range :end) 600 bounds 1 domain-from domain-to)]
    (testing "a pointer sample never emits a URL rewrite or a fetch"
      (is (= 1 (count effects)))
      (is (= :effects/save-many (first (first effects)))))
    (testing "a released button ends the gesture instead of moving the range"
      (is (= [] (actions/update-portfolio-summary-custom-range-drag
                 (state-with range :end) 600 bounds 0 domain-from domain-to))))))

(deftest drag-end-commits-once-and-always-fetches-the-widest-candles-test
  (let [week {:from (ms "2026-08-18T00:00:00.000Z")
              :to (ms "2026-08-24T00:00:00.000Z")}
        state (assoc-in (state-with week :end)
                        [:portfolio-ui :returns-benchmark-coins] ["BTC"])
        effects (actions/end-portfolio-summary-custom-range-drag state)]
    (is (= (save-many [[[:portfolio-ui :summary-range-drag] nil]])
           (first effects)))
    (is (= replace-shareable-route-query-effect (second effects)))
    (testing "a custom range ALWAYS uses the all-time candle request.
              Sizing the request to the span would only reach back ~1.3x the span,
              and candles are fetched as the newest N bars ending NOW — so a short
              window sitting in the past would come back with no overlapping candles
              and the benchmark would vanish or rebase to the wrong origin."
      (is (= [:effects/fetch-candle-snapshot :coin "BTC" :interval :1d :bars 5000]
             (nth effects 2))))
    (testing "the same request regardless of span"
      (let [wide {:from (ms "2024-09-01T00:00:00.000Z")
                  :to (ms "2026-08-24T00:00:00.000Z")}
            past {:from (ms "2025-01-01T00:00:00.000Z")
                  :to (ms "2025-04-01T00:00:00.000Z")}]
        (doseq [range* [wide past]]
          (let [fx (actions/end-portfolio-summary-custom-range-drag
                    (assoc-in (state-with range* :end)
                              [:portfolio-ui :returns-benchmark-coins] ["BTC"]))]
            (is (= [:effects/fetch-candle-snapshot :coin "BTC" :interval :1d :bars 5000]
                   (nth fx 2)))))))
    (testing "presets are untouched by that rule"
      (is (= {:interval :12h :bars 900}
             (actions/returns-benchmark-candle-request :one-year))))
    (testing "a pointer-up with no drag in flight is a plain click and costs nothing"
      (is (= [] (actions/end-portfolio-summary-custom-range-drag (state-with week nil)))))))

(deftest benchmark-fetch-sites-read-the-effective-window-test
  (let [past {:from (ms "2025-01-01T00:00:00.000Z")
              :to (ms "2025-04-01T00:00:00.000Z")}
        state {:portfolio-ui {:summary-time-range :one-year
                              :summary-custom-range past
                              :returns-benchmark-coins ["BTC"]}}
        candle-effect (fn [effects]
                        (some (fn [effect]
                                (when (= :effects/fetch-candle-snapshot (first effect))
                                  effect))
                              effects))]
    (testing "switching to the Returns tab while a custom window is applied fetches at the
              window's interval, not the preset's — otherwise it stores bars nobody reads"
      (is (= [:effects/fetch-candle-snapshot :coin "BTC" :interval :1d :bars 5000]
             (candle-effect (actions/select-portfolio-chart-tab state :returns)))))
    (testing "and so does adding a benchmark while a custom window is applied"
      (is (= [:effects/fetch-candle-snapshot :coin "ETH" :interval :1d :bars 5000]
             (candle-effect (actions/select-portfolio-returns-benchmark state "ETH")))))
    (testing "with no custom window the preset still drives the request"
      (is (= [:effects/fetch-candle-snapshot :coin "BTC" :interval :12h :bars 900]
             (candle-effect (actions/select-portfolio-chart-tab
                             (update state :portfolio-ui dissoc :summary-custom-range)
                             :returns)))))))

(deftest selecting-a-preset-clears-the-custom-range-test
  (let [effects (actions/select-portfolio-summary-time-range
                 (state-with {:from domain-from :to domain-to} nil)
                 :three-month)
        [_ path-values] (first effects)
        by-path (into {} (map (fn [[path value]] [path value])) path-values)]
    (is (= :three-month (get by-path [:portfolio-ui :summary-time-range])))
    (testing "the custom window is dropped and the strip collapses"
      (is (nil? (get by-path [:portfolio-ui :summary-custom-range])))
      (is (nil? (get by-path [:portfolio-ui :summary-range-strip])))
      (is (nil? (get by-path [:portfolio-ui :summary-range-drag]))))
    (testing "the preset is still persisted as a keyword name"
      (is (= [:effects/local-storage-set "portfolio-summary-time-range" "three-month"]
             (second effects))))))

(deftest effective-range-prefers-the-custom-window-test
  (let [range {:from domain-from :to domain-to}]
    (is (= range (actions/effective-summary-time-range (state-with range))))
    (is (= :one-year (actions/effective-summary-time-range (state-with nil))))
    (testing "a malformed stored range falls back to the preset rather than breaking"
      (is (= :one-year (actions/effective-summary-time-range
                        {:portfolio-ui {:summary-time-range :one-year
                                        :summary-custom-range {:from domain-from}}}))))))
