(ns hyperopen.portfolio.optimizer.recommended-assumptions-actions-test
  "Actions coverage for one-click backend-recommended history assumptions:
  the bulk funnel (draft save + reference reconcile + prefetch + library sync
  + note), the single-card variant, and the honesty guards (existing entries
  win; members the backend cannot serve are held back and disclosed)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.actions :as actions]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(def ^:private btc-instrument
  {:instrument-id "perp:BTC" :market-type :perp :coin "BTC"})

(def ^:private wlfi-instrument
  {:instrument-id "perp:WLFI" :market-type :perp :coin "WLFI"})

(def ^:private gram-instrument
  {:instrument-id "perp:GRAM" :market-type :perp :coin "GRAM"})

(def ^:private universe [btc-instrument wlfi-instrument gram-instrument])

(defn- candles
  [n]
  (mapv (fn [i] {:time i :close "100"}) (range n)))

(def ^:private coin-row
  {:instrument-id "external:tiingo:COIN"
   :instrument-kind :external-proxy
   :display-symbol "COIN"
   :basket-member-only true
   :history {:status :available}})

(def ^:private discovery
  {:backend-id-by-local-id {"perp:BTC" "hl:perp:BTC"
                            "perp:WLFI" "hl:perp:WLFI"
                            "perp:GRAM" "hl:perp:GRAM"}
   :instruments-by-backend-id
   {"hl:perp:BTC" {:instrument-id "hl:perp:BTC"
                   :aliases {:hyperopen-market-key "perp:BTC"}
                   :display-symbol "BTC"
                   :history {:status :available}}
    "hl:perp:WLFI" {:instrument-id "hl:perp:WLFI"
                    :aliases {:hyperopen-market-key "perp:WLFI"}
                    :history {:status :available}
                    :default-assumption
                    {:approach "proxy"
                     :members [{:instrument-id "hl:perp:BTC" :role "anchor"}
                               {:instrument-id "external:tiingo:COIN"
                                :role "sector_peer"}
                               {:instrument-id "external:tiingo:SMH"
                                :role "sector_peer"}]
                     :relationship-strength "high"
                     :rationale "BTC anchors; COIN is the listed twin."}}
    "hl:perp:GRAM" {:instrument-id "hl:perp:GRAM"
                    :aliases {:hyperopen-market-key "perp:GRAM"}
                    :history {:status :available}
                    :default-assumption {:approach "conservative"
                                         :members []
                                         :rationale "No defensible basket."}}
    "external:tiingo:COIN" coin-row
    "external:tiingo:SMH" {:instrument-id "external:tiingo:SMH"
                           :instrument-kind :external-proxy
                           :display-symbol "SMH"
                           :basket-member-only true
                           :history {:status :missing}}}})

(def ^:private base-state
  (-> {:asset-selector {:markets [{:key "perp:BTC" :coin "BTC"
                                   :market-type :perp :volume 100}]
                        :market-by-key {"perp:BTC" {:key "perp:BTC" :coin "BTC"
                                                    :market-type :perp}}}
       :portfolio {:optimizer
                   {:history-data {:candle-history-by-coin {"BTC" (candles 400)
                                                            "WLFI" (candles 40)
                                                            "GRAM" (candles 10)}}
                    :draft {:universe universe
                            :objective {:kind :minimum-variance}
                            :history-assumptions {}
                            :metadata {:dirty? false}}}}}
      (assoc-in contracts/history-discovery-path discovery)
      (assoc-in contracts/history-load-state-path
                {:status :succeeded
                 :request-signature {:universe universe}})))

(defn- effect-by-id
  [effects effect-id]
  (some #(when (= effect-id (first %)) %) effects))

(defn- save-many-value
  [effects path]
  (let [[_ path-values] (effect-by-id effects :effects/save-many)]
    (some (fn [[p v]] (when (= p path) v)) path-values)))

(deftest bulk-apply-configures-every-pending-recommendation-test
  (let [effects (actions/apply-portfolio-optimizer-recommended-history-assumptions
                 base-state)
        assumptions (save-many-value effects contracts/draft-history-assumptions-path)
        refs (save-many-value effects
                              contracts/draft-proxy-reference-instruments-path)
        wlfi (get assumptions "perp:WLFI")
        gram (get assumptions "perp:GRAM")
        [_ sync] (effect-by-id effects
                               :effects/sync-portfolio-optimizer-assumption-library)
        [_ note-path note] (effect-by-id effects :effects/save)]
    (is (= #{"perp:WLFI" "perp:GRAM"} (set (keys assumptions)))
        "Both short-history assets configure; BTC (ample history) is untouched.")
    (is (= :proxy (:behavior wlfi)))
    (is (= ["perp:BTC" "external:tiingo:COIN"]
           (get-in wlfi [:proxy :instrument-ids]))
        "The servable members apply; the data-less SMH member is held back.")
    (is (= :high (get-in wlfi [:proxy :relationship-strength])))
    (is (nil? (get-in wlfi [:proxy :prior-weights]))
        "No backend weights -> the equal-weight prior.")
    (is (= {:source :backend-recommendation
            :acknowledged? true
            :rationale "BTC anchors; COIN is the listed twin."}
           (:metadata wlfi)))
    (is (= :conservative (:behavior gram)))
    (is (= 0.8 (:volatility gram)))
    (is (= "No defensible basket." (get-in gram [:metadata :rationale])))
    (is (= ["external:tiingo:COIN"] (mapv :instrument-id refs))
        "The external member is stored as a reference-only instrument.")
    (is (= "external:tiingo:COIN"
           (:optimizer-history/instrument-id (first refs)))
        "The reference carries its backend history identity for alignment.")
    (is (some? (save-many-value effects contracts/history-prefetch-path))
        "The new reference instrument is queued for a history fetch...")
    (is (some? (effect-by-id effects :effects/load-portfolio-optimizer-history))
        "...and the prefetch loop is kicked off.")
    (is (= ["perp:WLFI" "perp:GRAM"] (mapv :instrument-id (:upserts sync)))
        "Both entries mirror into the wallet's assumption library.")
    (is (= [(first refs)]
           (:reference-instruments (first (:upserts sync))))
        "The library upsert carries the external reference for later hydration.")
    (is (= contracts/ui-history-assumptions-io-note-path note-path))
    (is (= :success (:kind note)))
    (is (str/includes? (:message note) "Applied 2 recommended assumptions"))
    (is (str/includes? (:message note) "SMH")
        "The held-back member is disclosed by name.")))

(deftest single-apply-configures-one-card-test
  (let [effects (actions/apply-portfolio-optimizer-recommended-history-assumption
                 base-state "perp:GRAM")
        assumptions (save-many-value effects contracts/draft-history-assumptions-path)
        [_ _ note] (effect-by-id effects :effects/save)]
    (is (= ["perp:GRAM"] (vec (keys assumptions)))
        "Only the clicked card configures.")
    (is (= :conservative (get-in assumptions ["perp:GRAM" :behavior])))
    (is (nil? (save-many-value effects
                               contracts/draft-proxy-reference-instruments-path))
        "A conservative apply never rewrites the reference instruments.")
    (is (= :success (:kind note)))
    (is (= "Applied 1 recommended assumption" (:message note)))))

(deftest existing-entries-always-win-test
  (let [configured (assoc-in base-state
                             (conj contracts/draft-history-assumptions-path
                                   "perp:WLFI")
                             {:behavior :conservative
                              :expected-return 0.0
                              :volatility 0.8
                              :max-weight 0.03
                              :correlation-floor 0.75})
        effects (actions/apply-portfolio-optimizer-recommended-history-assumptions
                 configured)
        assumptions (save-many-value effects contracts/draft-history-assumptions-path)]
    (is (= :conservative (get-in assumptions ["perp:WLFI" :behavior]))
        "The user's authored entry is never overwritten...")
    (is (= :conservative (get-in assumptions ["perp:GRAM" :behavior]))
        "...while the still-pending recommendation applies.")
    (testing "a single-card apply on the configured asset is an honest no-op"
      (let [effects* (actions/apply-portfolio-optimizer-recommended-history-assumption
                      configured "perp:WLFI")
            [effect-id _ note] (first effects*)]
        (is (= 1 (count effects*)))
        (is (= :effects/save effect-id))
        (is (= :error (:kind note)))
        (is (str/includes? (:message note) "No recommendations applied"))))))

(deftest run-click-on-blocked-draft-auto-applies-recommendations-then-runs-test
  (let [effects (actions/run-portfolio-optimizer-from-draft base-state)
        assumptions (save-many-value effects contracts/draft-history-assumptions-path)
        [_ _ note] (effect-by-id effects :effects/save)]
    (is (= [:effects/run-portfolio-optimizer-pipeline] (last effects))
        "The pipeline effect still runs, after the apply projections.")
    (is (= #{"perp:WLFI" "perp:GRAM"} (set (keys assumptions)))
        "Every pending recommendation applies, exactly like the banner click.")
    (is (some? (effect-by-id effects
                             :effects/sync-portfolio-optimizer-assumption-library))
        "The auto-apply mirrors into the wallet library like any apply.")
    (is (= :success (:kind note))
        "The outcome note discloses what Run just did on the user's behalf.")))

(deftest run-click-on-blocked-draft-without-recommendations-runs-plain-test
  (let [no-recs (update-in base-state
                           (conj contracts/history-discovery-path
                                 :instruments-by-backend-id)
                           (fn [rows]
                             (into {}
                                   (map (fn [[id row]]
                                          [id (dissoc row :default-assumption)]))
                                   rows)))
        effects (actions/run-portfolio-optimizer-from-draft no-recs)]
    (is (= [[:effects/run-portfolio-optimizer-pipeline]] effects))))

(deftest run-click-on-runnable-draft-never-auto-applies-test
  (let [runnable (-> base-state
                     (assoc-in contracts/draft-universe-path
                               [btc-instrument wlfi-instrument])
                     (assoc-in contracts/history-load-state-path
                               {:status :succeeded
                                :request-signature
                                {:universe [btc-instrument wlfi-instrument]}}))
        effects (actions/run-portfolio-optimizer-from-draft runnable)]
    (is (contains? (save-many-value
                    (actions/apply-portfolio-optimizer-recommended-history-assumptions
                     runnable)
                    contracts/draft-history-assumptions-path)
                   "perp:WLFI")
        "Sanity: a recommendation IS still pending on the runnable draft…")
    (is (= [[:effects/run-portfolio-optimizer-pipeline]] effects)
        "…and the Run click leaves it untouched.")))

(deftest all-members-unavailable-is-disclosed-not-half-applied-test
  (let [held-only (assoc-in base-state
                            (conj contracts/history-discovery-path
                                  :instruments-by-backend-id
                                  "hl:perp:WLFI"
                                  :default-assumption
                                  :members)
                            [{:instrument-id "external:tiingo:SMH"
                              :role "sector_peer"}])
        effects (actions/apply-portfolio-optimizer-recommended-history-assumption
                 held-only "perp:WLFI")
        [effect-id _ note] (first effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save effect-id))
    (is (= :error (:kind note)))
    (is (str/includes? (:message note) "waiting on backend member history"))
    (is (str/includes? (:message note) "SMH"))))
