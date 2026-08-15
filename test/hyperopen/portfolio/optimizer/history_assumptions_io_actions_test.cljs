(ns hyperopen.portfolio.optimizer.history-assumptions-io-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.portfolio.optimizer.actions :as actions]
            [hyperopen.portfolio.optimizer.application.setup-readiness :as setup-readiness]
            [hyperopen.portfolio.optimizer.application.view-model :as view-model]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(def ^:private now-ms 1751900000000)

(def ^:private sol-market
  {:key "perp:SOL" :coin "SOL" :market-type :perp :volume 50})

;; BTC carries a full year+ of candles so it reads as a VERIFIED proxy
;; candidate; WLFI has none (it is the short-history asset needing a proxy).
(def ^:private btc-candles
  (mapv (fn [i] {:time i :close "100"}) (range 400)))

(def ^:private base-state
  {:asset-selector {:markets [{:key "perp:BTC" :coin "BTC" :market-type :perp :volume 100}
                              sol-market]
                    :market-by-key {"perp:BTC" {:key "perp:BTC" :coin "BTC"
                                                :market-type :perp}
                                    "perp:SOL" sol-market}}
   :portfolio {:optimizer
               {:history-data {:candle-history-by-coin {"BTC" btc-candles}}
                :draft {:universe [{:instrument-id "perp:WLFI" :coin "WLFI"
                                    :market-type :perp}
                                   {:instrument-id "perp:BTC" :coin "BTC"
                                    :market-type :perp}]
                        :objective {:kind :minimum-variance}
                        :history-assumptions {}
                        :metadata {:dirty? false}}}}})

(def ^:private wlfi-entry
  ;; A started (incomplete) proxy entry: enrolls WLFI in the workflow without
  ;; needing loaded history in the fixture (unloaded history reads :pending,
  ;; which — correctly — never flags a card or an export row).
  {:behavior :proxy
   :expected-return 0.0
   :volatility 0.8
   :max-weight 0.05
   :proxy {:instrument-ids ["perp:BTC"]
           :relationship-strength :medium
           :prior-weights nil}})

(def ^:private enrolled-state
  (assoc-in base-state
            (conj contracts/draft-history-assumptions-path "perp:WLFI")
            wlfi-entry))

(defn- effect-by-id
  [effects effect-id]
  (some #(when (= effect-id (first %)) %) effects))

(deftest export-downloads-template-and-notes-success-test
  (with-redefs [platform/now-ms (constantly now-ms)]
    (let [effects (actions/export-portfolio-optimizer-history-assumptions enrolled-state)
          [download-id payload] (effect-by-id
                                 effects
                                 :effects/download-portfolio-optimizer-history-assumptions-file)
          [_ note-path note] (effect-by-id effects :effects/save)
          document (:document payload)
          [wlfi] (:assets document)]
      (is (= :effects/download-portfolio-optimizer-history-assumptions-file download-id))
      (is (= (str "history-assumptions-workflow-" now-ms ".json") (:filename payload)))
      (is (= "proxy-workflow" (:export-scope document)))
      ;; only the short-history asset exports: BTC has a full year of candles
      ;; (:ok adequacy) so it is never carded (same rule as the cards).
      (is (= 1 (:count payload)))
      (is (= "perp:WLFI" (:instrument-id wlfi)))
      (is (= "proxy" (:approach wlfi)))
      (is (= "unverified" (:history-status wlfi)))
      ;; the in-universe proxy labels from the universe symbol map.
      (is (= [{:instrument-id "perp:BTC" :symbol "BTC" :weight nil}]
             (:proxies wlfi)))
      ;; the file carries ONLY the workflow assets — no candidate menu of any
      ;; kind (the agent proposes proxies; import validation drops bad ones).
      (is (not (contains? document :proxy-candidates)))
      (is (vector? (:instructions document)))
      (is (= "minimum-variance" (:optimization-objective document)))
      (is (= contracts/ui-history-assumptions-io-note-path note-path))
      (is (= :success (:kind note))))))

(deftest export-universe-scope-covers-included-universe-without-candidate-menu-test
  (with-redefs [platform/now-ms (constantly now-ms)]
    (let [effects (actions/export-portfolio-optimizer-history-assumptions
                   enrolled-state :universe)
          [_ payload] (effect-by-id
                       effects
                       :effects/download-portfolio-optimizer-history-assumptions-file)
          document (:document payload)]
      (is (= (str "history-assumptions-universe-" now-ms ".json") (:filename payload)))
      (is (= "universe" (:export-scope document)))
      ;; every universe asset exports; the entry-less one with a null approach,
      ;; each labeled with its own history-status.
      (is (= [["perp:WLFI" "proxy" "unverified"] ["perp:BTC" nil "verified"]]
             (mapv (juxt :instrument-id :approach :history-status) (:assets document))))
      ;; no separate candidate menu — the assets list already carries every
      ;; asset with its history-status.
      (is (not (contains? document :proxy-candidates))))))

(deftest export-universe-scope-drops-blocklisted-assets-test
  (with-redefs [platform/now-ms (constantly now-ms)]
    (let [state (assoc-in enrolled-state
                          (conj contracts/draft-constraints-path :blocklist)
                          ["perp:BTC"])
          effects (actions/export-portfolio-optimizer-history-assumptions
                   state :universe)
          [_ payload] (effect-by-id
                       effects
                       :effects/download-portfolio-optimizer-history-assumptions-file)
          document (:document payload)]
      ;; the excluded asset leaves the rows.
      (is (= ["perp:WLFI"] (mapv :instrument-id (:assets document)))))))

(deftest export-workflow-assets-match-the-visible-cards-test
  ;; Guard against the export's target list drifting from the workflow section
  ;; the user sees: both derive from the same build-readiness, so the exported
  ;; `assets` must equal the cards (never the whole universe). Here BTC has a
  ;; full year of history (not carded), so only WLFI is a workflow asset.
  (with-redefs [platform/now-ms (constantly now-ms)]
    (let [readiness (setup-readiness/build-readiness enrolled-state)
          draft (get-in enrolled-state contracts/draft-path)
          load-state (get-in enrolled-state contracts/history-load-state-path)
          cards (:cards (view-model/history-assumption-cards
                         enrolled-state draft readiness load-state nil))
          card-ids (set (map :instrument-id cards))
          effects (actions/export-portfolio-optimizer-history-assumptions enrolled-state)
          [_ payload] (effect-by-id
                       effects
                       :effects/download-portfolio-optimizer-history-assumptions-file)
          asset-ids (set (map :instrument-id (get-in payload [:document :assets])))]
      (is (= #{"perp:WLFI"} card-ids))
      (is (= card-ids asset-ids)
          "workflow export assets must equal the workflow cards, not the universe"))))

(deftest export-with-nothing-in-workflow-notes-error-test
  (let [state (assoc-in base-state contracts/draft-universe-path [])
        effects (actions/export-portfolio-optimizer-history-assumptions state)
        [effect-id path note] (first effects)]
    (is (= 1 (count effects)))
    (is (= :effects/save effect-id))
    (is (= contracts/ui-history-assumptions-io-note-path path))
    (is (= :error (:kind note)))))

(deftest import-opens-file-picker-test
  (is (= [[:effects/pick-portfolio-optimizer-history-assumptions-file]]
         (actions/import-portfolio-optimizer-history-assumptions base-state))))

(deftest apply-imported-authors-entry-refs-library-and-note-test
  (let [state base-state
        data {"assets" [{"instrument-id" "perp:WLFI"
                         "approach" "proxy"
                         "proxies" [{"symbol" "BTC"} {"symbol" "SOL"}]
                         "relationship-strength" "high"
                         "rationale" "Anchor plus ecosystem."}]}
        effects (actions/apply-imported-portfolio-optimizer-history-assumptions state data)
        [_ path-values] (effect-by-id effects :effects/save-many)
        values (into {} path-values)
        entry (get-in values [contracts/draft-history-assumptions-path "perp:WLFI"])
        [_ sync] (effect-by-id effects :effects/sync-portfolio-optimizer-assumption-library)
        note (last effects)]
    (is (= :proxy (:behavior entry)))
    (is (= ["perp:BTC" "perp:SOL"] (get-in entry [:proxy :instrument-ids])))
    (is (= :high (get-in entry [:proxy :relationship-strength])))
    (is (= {:source :agent-import :acknowledged? true :rationale "Anchor plus ecosystem."}
           (:metadata entry)))
    (is (= true (get values contracts/draft-dirty-path)))
    ;; the out-of-universe proxy is stored as a reference-only instrument.
    (is (= ["perp:SOL"]
           (mapv :instrument-id
                 (get values contracts/draft-proxy-reference-instruments-path))))
    ;; one library upsert, carrying the entry and its reference instruments.
    (is (= ["perp:WLFI"] (mapv :instrument-id (:upserts sync))))
    (is (= entry (:assumption (first (:upserts sync)))))
    (is (= ["perp:SOL"]
           (mapv :instrument-id
                 (:reference-instruments (first (:upserts sync))))))
    (is (= :effects/save (first note)))
    (is (= contracts/ui-history-assumptions-io-note-path (second note)))
    (is (= :success (:kind (nth note 2))))))

(deftest apply-imported-invalid-file-only-notes-error-test
  (let [effects (actions/apply-imported-portfolio-optimizer-history-assumptions
                 base-state nil)]
    (is (= 1 (count effects)))
    (is (= :error (:kind (nth (first effects) 2))))))

(deftest apply-imported-nothing-filled-notes-error-and-changes-nothing-test
  (let [data {"assets" [{"instrument-id" "perp:WLFI" "approach" nil}]}
        effects (actions/apply-imported-portfolio-optimizer-history-assumptions
                 base-state data)]
    (is (= 1 (count effects)))
    (is (= :error (:kind (nth (first effects) 2))))))

(deftest apply-imported-in-universe-basket-adds-no-reference-instruments-test
  (let [data {"assets" [{"instrument-id" "perp:WLFI"
                         "approach" "proxy"
                         "proxies" [{"symbol" "BTC"}]}]}
        effects (actions/apply-imported-portfolio-optimizer-history-assumptions
                 base-state data)
        [_ path-values] (effect-by-id effects :effects/save-many)
        values (into {} path-values)]
    (is (some? (get-in values [contracts/draft-history-assumptions-path "perp:WLFI"])))
    (is (not (contains? values contracts/draft-proxy-reference-instruments-path)))))

(deftest dismiss-note-clears-it-test
  (is (= [[:effects/save contracts/ui-history-assumptions-io-note-path nil]]
         (actions/dismiss-portfolio-optimizer-history-assumptions-io-note base-state))))
