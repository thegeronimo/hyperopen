(ns hyperopen.vaults.custom-range-actions-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vaults.application.detail-commands :as detail-commands]
            [hyperopen.vaults.infrastructure.routes :as routes]))

(def ^:private replace-shareable-route-query-effect
  [:effects/replace-shareable-route-query])

(defn- ms [iso] (.getTime (js/Date. iso)))

(def ^:private domain-from (ms "2024-08-24T00:00:00.000Z"))
(def ^:private domain-to (ms "2026-08-24T00:00:00.000Z"))
(def ^:private bounds {:left 0 :width 1000})
(def ^:private vault-address "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private deps
  {:parse-vault-route-fn routes/parse-vault-route})

(defn- save-many
  [path-values]
  [:effects/save-many
   (into (vec path-values)
         [[[:vaults-ui :detail-chart-timeframe-dropdown-open?] false]
          [[:vaults-ui :detail-performance-metrics-timeframe-dropdown-open?] false]])])

(defn- detail-state
  ([range] (detail-state range nil))
  ([range drag]
   {:vaults-ui (cond-> {:snapshot-range :month
                        :detail-chart-series :returns
                        :detail-returns-benchmark-coins ["BTC"]}
                 range (assoc :detail-custom-range range)
                 drag (assoc :detail-range-drag drag))
    :router {:path (str "/vaults/" vault-address)}}))

(deftest open-seeds-the-vault-strip-test
  (let [seed {:from (ms "2026-03-03T00:00:00.000Z")
              :to (ms "2026-06-12T00:00:00.000Z")}]
    (testing "off the detail route there is nothing to fetch, but the window is still published"
      (is (= [(save-many [[[:vaults-ui :detail-range-strip] :chart]
                          [[:vaults-ui :detail-range-drag] nil]
                          [[:vaults-ui :detail-custom-range] seed]])
              replace-shareable-route-query-effect]
             (detail-commands/open-vault-detail-custom-range deps {} :chart (:from seed) (:to seed)))))
    (testing "on the detail route it also refetches at the custom window's interval"
      (let [effects (detail-commands/open-vault-detail-custom-range
                     deps (detail-state nil) :chart (:from seed) (:to seed))]
        (is (= replace-shareable-route-query-effect (second effects)))
        (is (= [:effects/fetch-candle-snapshot
                :coin "BTC" :interval :1d :bars 5000
                :detail-route-vault-address vault-address]
               (nth effects 2)))))))

(deftest vault-strip-opens-in-the-panel-that-asked-for-it-test
  (let [seed {:from (ms "2026-03-03T00:00:00.000Z")
              :to (ms "2026-06-12T00:00:00.000Z")}
        target-of (fn [effects]
                    (let [[_ path-values] (first effects)]
                      (some (fn [[path value]]
                              (when (= path [:vaults-ui :detail-range-strip]) value))
                            path-values)))]
    (is (= :metrics (target-of (detail-commands/open-vault-detail-custom-range
                                deps {} :metrics (:from seed) (:to seed)))))
    (is (= :chart (target-of (detail-commands/open-vault-detail-custom-range
                              deps {} :bogus (:from seed) (:to seed)))))))

(deftest close-collapses-the-vault-strip-test
  (is (= [(save-many [[[:vaults-ui :detail-range-strip] nil]
                      [[:vaults-ui :detail-range-drag] nil]])]
         (detail-commands/close-vault-detail-custom-range {}))))

(deftest vault-drag-update-is-projection-only-test
  (let [range {:from (ms "2026-01-01T00:00:00.000Z")
               :to (ms "2026-06-01T00:00:00.000Z")}
        effects (detail-commands/update-vault-detail-custom-range-drag
                 (detail-state range :end) 700 bounds 1 domain-from domain-to)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (first (first effects))))
    (testing "a released button ends the gesture"
      (is (= [] (detail-commands/update-vault-detail-custom-range-drag
                 (detail-state range :end) 700 bounds 0 domain-from domain-to))))))

(deftest vault-drag-end-commits-once-test
  (let [week {:from (ms "2026-08-18T00:00:00.000Z")
              :to (ms "2026-08-24T00:00:00.000Z")}
        effects (detail-commands/end-vault-detail-custom-range-drag
                 deps
                 (detail-state week :end))]
    (is (= (save-many [[[:vaults-ui :detail-range-drag] nil]]) (first effects)))
    (is (= replace-shareable-route-query-effect (second effects)))
    (testing "a custom range always fetches the widest candle window, scoped to the vault"
      (is (= [:effects/fetch-candle-snapshot
              :coin "BTC" :interval :1d :bars 5000
              :detail-route-vault-address vault-address]
             (nth effects 2))))
    (testing "a pointer-up with no drag in flight costs nothing"
      (is (= [] (detail-commands/end-vault-detail-custom-range-drag
                 deps
                 (detail-state week nil)))))))

(deftest effective-vault-range-prefers-the-custom-window-test
  (let [range {:from domain-from :to domain-to}]
    (is (= range (detail-commands/effective-vault-detail-range (detail-state range))))
    (is (= :month (detail-commands/effective-vault-detail-range (detail-state nil))))))
