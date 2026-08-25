(ns hyperopen.portfolio.optimizer.application.view-model-setup-boundary-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]
            [hyperopen.portfolio.optimizer.application.view-model :as view-model]))

(def ^:private btc-instrument
  {:instrument-id "perp:BTC"
   :market-type :perp
   :coin "BTC"
   :symbol "BTC-USDC"
   :name "Bitcoin"})

(def ^:private eth-instrument
  {:instrument-id "perp:ETH"
   :market-type :perp
   :coin "ETH"
   :symbol "ETH-USDC"
   :name "Ethereum"})

(deftest universe-section-model-projects-render-ready-row-labels-test
  (let [calls (atom [])
        selected-instrument (assoc btc-instrument
                                   :volume24h 99000000)
        candidate {:key "spot:@1"
                   :instrument-id "spot:@1"
                   :market-type :spot
                   :coin "@1"
                   :symbol "PURR/USDC"
                   :base "PURR"
                   :name "Purr"
                   :volume24h 125000000
                   :liquidity-label "thin"}
        candidate-markets-stub (fn
                                 ([_state _universe query]
                                  (swap! calls conj query)
                                  [candidate])
                                 ([_state _universe query _opts]
                                  (swap! calls conj query)
                                  [candidate]))
        model (with-redefs [universe-candidates/candidate-markets
                            candidate-markets-stub]
                (view-model/universe-section-model
                 {:portfolio-ui {:optimizer {:universe-search-query "purr"}}}
                 {:universe [selected-instrument]}
                 {:readiness {:request {:universe [selected-instrument]}}
                  :history-load-state {:status :idle}
                  :history-status-by-id {"perp:BTC" :aligned}}))]
    (is (= ["purr"] @calls))
    ;; An aligned (:sufficient) asset is "all clear" so the history chip is
    ;; suppressed (by-exception display): no :history-label / :history-tone.
    (is (= [{:instrument-id "perp:BTC"
             :market-type :perp
             :primary-label "BTC"
             :secondary-label "Bitcoin"
             :history-status :sufficient
             :liquidity-label "deep"}]
           (mapv #(select-keys % [:instrument-id
                                  :market-type
                                  :primary-label
                                  :secondary-label
                                  :history-status
                                  :history-label
                                  :history-tone
                                  :liquidity-label])
                 (:selected-rows model))))
    (is (= [{:market-key "spot:@1"
             :market-type :spot
             :active? true
             :label "PURR/USDC"
             :name "Purr"
             :adv-label "$125M"}]
           (mapv #(select-keys % [:market-key
                                  :market-type
                                  :active?
                                  :label
                                  :name
                                  :adv-label])
                 (:candidate-rows model))))))

(deftest readiness-panel-model-projects-copy-and-warning-rows-test
  (let [readiness {:status :blocked
                   :reason :incomplete-history
                   :request {:requested-universe [btc-instrument]
                             :universe []
                             :warnings []}
                   :blocking-warnings [{:code :missing-candle-history
                                        :instrument-id "perp:BTC"
                                        :coin "BTC"}]}
        failed-load {:status :failed
                     :error {:message "history endpoint unavailable"}}
        failed-model (view-model/readiness-panel-model readiness failed-load)
        missing-model (view-model/readiness-panel-model
                       {:status :blocked
                        :reason :missing-universe
                        :warnings []}
                       {:status :idle})]
    (is (= "History load failed. Existing history, if any, is retained."
           (:copy failed-model)))
    (is (= "history endpoint unavailable" (:error-message failed-model)))
    ;; Warnings are now GROUPED by code: one row per kind with a count + affected-asset list. A
    ;; single warning is a count-1 group whose message is the per-asset message. Groups built
    ;; from blocking-warnings carry :severity :blocking so the panel can rank them.
    (is (= [{:code :missing-candle-history
             :code-label "missing-candle-history"
             :count 1
             :severity :blocking
             :message "Bitcoin: no candle history returned for BTC."
             :assets [{:instrument-id "perp:BTC" :label "Bitcoin"}]}]
           (:warnings failed-model)))
    (is (= "Data health" (:title failed-model)))
    (is (= :blocked (get-in failed-model [:status :level])))
    (is (= "Select a universe before running."
           (:copy missing-model)))
    (is (= :blocked (get-in missing-model [:status :level])))))

(deftest group-readiness-warnings-collapses-same-code-warnings-test
  (let [readiness {:status :ready
                   :runnable? true
                   :request {:requested-universe [btc-instrument eth-instrument]
                             :warnings []}
                   :warnings [{:code :stale-history :instrument-id "perp:BTC"}
                              {:code :stale-history :instrument-id "perp:ETH"}
                              {:code :insufficient-common-history :instrument-id "perp:BTC"}]}
        {:keys [warnings]} (view-model/readiness-panel-model readiness {:status :idle})]
    (is (= 2 (count warnings)) "three raw warnings collapse into two code groups")
    (let [stale (first (filter #(= :stale-history (:code %)) warnings))]
      (is (= 2 (:count stale)) "both stale rows collapse into one group with count 2")
      (is (str/includes? (:message stale) "stale"))
      (is (= ["Bitcoin" "Ethereum"] (mapv :label (:assets stale)))
          "the affected-asset labels are resolved from the requested universe")
      (is (= ["perp:BTC" "perp:ETH"] (mapv :instrument-id (:assets stale)))))
    (let [stale (first (filter #(= :stale-history (:code %)) warnings))]
      ;; Stale cache is an informational NOTE, not a caution: a refresh adds
      ;; at most the newest day of data, which does not move a covariance
      ;; estimate or the allocation (owner review 2026-07-04).
      (is (= :info (:severity stale)))
      (is (some? (:detail stale)) "stale groups carry a plain-language context line"))
    (let [insufficient (first (filter #(= :insufficient-common-history (:code %)) warnings))]
      (is (= 1 (:count insufficient)))
      (is (= ["perp:BTC"] (mapv :instrument-id (:assets insufficient))))
      ;; Provider limits are not user-fixable here — informational, never an
      ;; action item.
      (is (= :info (:severity insufficient)))
      ;; Provider-limit groups lead with the human headline, never the raw
      ;; provider/vendor message.
      (is (= "History source is limited — not enough shared history across the selected assets."
             (:message insufficient))))))

(deftest setup-summary-model-projects-summary-row-data-test
  (let [formatters {:labelize {:risk-adjusted "Risk Adjusted"
                               :historical-mean "Historical Mean"
                               :max-sharpe "Max Sharpe"
                               :conservative "Conservative"}
                    :percent-label (fn [value] (str (* 100 value) "%"))}
        draft {:universe [btc-instrument eth-instrument]
               :objective {:kind :max-sharpe}
               :return-model {:kind :historical-mean}
               :constraints {:gross-max 1.5
                             :max-asset-weight 0.4}}
        model (view-model/setup-summary-model draft formatters)
        row-by-label (into {}
                           (map (juxt :label identity))
                           (:summary-rows model))]
    (is (= :max-sharpe (:active-preset model))
        "Risk-adjusted / Use my views consolidated: a max-sharpe objective IS the Maximum Sharpe preset.")
    (is (false? (:black-litterman? model)))
    (is (= "Max Sharpe" (get-in row-by-label ["Preset" :title])))
    (is (= "Historical Mean" (get-in row-by-label ["Expected Returns" :title])))
    (is (= "Max Sharpe" (get-in row-by-label ["Objective" :title])))
    (is (= "Gross ≤ 1.50× · Max asset 40%" (get-in row-by-label ["Portfolio exposure" :title]))))
  (is (true? (:black-litterman?
              (view-model/setup-summary-model
               {:return-model {:kind :black-litterman}}
               {:labelize name
                :percent-label str})))))

(deftest setup-summary-card-equal-risk-renders-net-as-output-only-test
  (let [constraints {:gross-min 1.95
                     :gross-max 2.05
                     :net-min -0.75
                     :net-max 0.25
                     :net-band-pct 0.10
                     :max-asset-weight 0.5}
        equal-risk (view-model/setup-summary-card-model
                    {:universe [btc-instrument eth-instrument]
                     :objective {:kind :equal-risk}
                     :constraints constraints})
        minimum-risk (view-model/setup-summary-card-model
                      {:universe [btc-instrument eth-instrument]
                       :objective {:kind :minimum-variance}
                       :constraints constraints})]
    (is (= {:gross-label "2.00×"
            :net-label "Resulting net"
            :direction :neutral
            :net-output-only? true}
           (:exposure-target equal-risk))
        "Equal Risk summary must not present stored net min/max as the selected exposure target.")
    (is (= ["Gross" "2.00× selected"] (first (:exposure-rows equal-risk)))
        "Equal Risk still summarizes the selected gross target.")
    (is (= ["Resulting net" "Determined by Equal Risk from covariance and selected sides"]
           (second (:exposure-rows equal-risk)))
        "Equal Risk should render output-only resulting-net language instead of stored net target values.")
    (is (= {:gross-label "2.00×"
            :net-label "-0.25×"
            :direction :short}
           (:exposure-target minimum-risk))
        "Other objectives keep the gross + net target summary.")
    (is (= ["Net" "-0.75×–+0.25× neutral range ±10.0% of gross"]
           (second (:exposure-rows minimum-risk))))))

(deftest constraints-summary-line-uses-readable-exposure-copy-test
  (is (= "Gross 4.80×–5.80× · Net +0.55×–1.55× long · Max asset 574% · Rebalance 3.0 pp"
         (view-model/constraints-summary-line
          {:gross-min 4.8
           :gross-max 5.8
           :net-min 0.55
           :net-max 1.55
           :max-asset-weight 5.74
           :rebalance-tolerance 0.03})))
  (is (= "Gross ≤ 2.00× · Net +1.00× long · Max asset 50% · Rebalance 3.0 pp"
         (view-model/constraints-summary-line
          {:gross-max 2
           :net-min 1
           :net-max 1
           :max-asset-weight 0.5
           :rebalance-tolerance 0.03})))
  (is (= "Gross ≤ 3.00× · Net -0.25×–+0.25× neutral range · Max asset 25%"
         (view-model/constraints-summary-line
          {:gross-max 3
           :net-min -0.25
           :net-max 0.25
           :max-asset-weight 0.25}))))

(deftest black-litterman-cards-model-projects-preview-card-data-test
  (let [formatters {:pct (fn [value] (str "pct:" value))
                    :signed-view-pct (fn [value] (str "view:" value))
                    :signed-delta (fn [value] (str "delta:" value))}
        view {:id "view-1"
              :kind :relative
              :instrument-id "perp:BTC"
              :comparator-instrument-id "perp:ETH"
              :direction :outperform
              :return 0.3
              :confidence 0.2}
        draft {:universe [btc-instrument eth-instrument]
               :return-model {:kind :black-litterman
                              :views [view]}}
        readiness {:request {:universe [btc-instrument eth-instrument]
                             :return-model {:kind :black-litterman
                                            :views [view]}}}
        preview {:status :ready
                 :rows [{:instrument-id "perp:BTC"
                         :label "BTC"
                         :prior-return 0.2
                         :posterior-return 0.21}
                        {:instrument-id "perp:ETH"
                         :label "ETH"
                         :prior-return 0.3
                         :posterior-return 0.28}]}
        model (view-model/black-litterman-cards-model draft
                                                       readiness
                                                       preview
                                                       formatters)
        cards-by-role (into {}
                            (map (juxt :role identity))
                            (:cards model))
        market-card (get cards-by-role
                         "portfolio-optimizer-setup-use-my-views-card-market-reference")
        views-card (get cards-by-role
                        "portfolio-optimizer-setup-use-my-views-card-your-views")
        output-card (get cards-by-role
                         "portfolio-optimizer-setup-use-my-views-card-combined-output")
        output-note (some #(when (= :note (:kind %)) %)
                          (:rows output-card))]
    (is (= ["Market reference" "Your views" "Combined output"]
           (mapv :label (:cards model))))
    (is (= [{:kind :value
             :label "ETH"
             :value-label "pct:0.3"
             :value-tone :prior}
            {:kind :value
             :label "BTC"
             :value-label "pct:0.2"
             :value-tone :prior}]
           (mapv #(select-keys % [:kind :label :value-label :value-tone])
                 (:rows market-card))))
    (is (= [{:kind :view
             :label "BTC > ETH by"
             :return-label "view:0.3"
             :confidence-label "low"}]
           (mapv #(select-keys % [:kind
                                  :label
                                  :return-label
                                  :confidence-label])
                 (:rows views-card))))
    (is (some #(and (= :combined-output (:kind %))
                    (= "BTC" (:label %))
                    (= "pct:0.2" (:prior-label %))
                    (= "pct:0.21" (:posterior-label %))
                    (= "delta:0.009999999999999981" (:delta-label %)))
              (:rows output-card)))
    (is (str/includes? (:copy output-note)
                       "Low confidence on BTC means your view:0.3 view only pulls posterior to pct:0.21"))))

(def ^:private new-perp-instrument
  {:instrument-id "perp:NEW"
   :market-type :perp
   :coin "NEW"})

(deftest universe-row-carries-assumption-badge-test
  (let [draft {:universe [btc-instrument new-perp-instrument]
               :objective {:kind :minimum-variance}
               :history-assumptions
               {"perp:NEW" {:behavior :conservative
                            :expected-return nil
                            :volatility 0.9
                            :max-weight 0.03
                            :correlation-floor 0.75}}}
        model (view-model/universe-section-model
               {}
               draft
               {:readiness {:request {:universe [btc-instrument]
                                      :objective {:kind :minimum-variance}}}
                :history-load-state {:status :idle}
                :history-status-by-id {"perp:BTC" :aligned}})
        badge-by-id (into {} (map (juxt :instrument-id :assumption-badge-label))
                          (:selected-rows model))
        tooltip-by-id (into {} (map (juxt :instrument-id :assumption-badge-tooltip))
                            (:selected-rows model))]
    (is (= "Ready" (get badge-by-id "perp:BTC")))
    (is (= "Conservative" (get badge-by-id "perp:NEW"))
        "A complete conservative assumption shows the Conservative badge.")
    (is (str/starts-with? (get tooltip-by-id "perp:NEW") "Treated cautiously")
        "The Conservative chip carries a hover explanation.")
    (is (nil? (get tooltip-by-id "perp:BTC"))
        "Plain Ready rows carry no tooltip.")))

(deftest universe-row-modeled-badge-and-tooltip-test
  ;; A complete proxy (basket-modeled) assumption reads "Modeled" — named for its
  ;; result, not the "proxy" mechanism — so it never collides with the
  ;; unconfigured "Needs proxy" cue, and its chip carries a hover explanation.
  (let [draft {:universe [btc-instrument new-perp-instrument]
               :objective {:kind :minimum-variance}
               :history-assumptions
               {"perp:NEW" {:behavior :proxy
                            :expected-return 0.0
                            :volatility 0.8
                            :max-weight 0.05
                            :proxy {:instrument-ids ["perp:BTC"]
                                    :relationship-strength :medium
                                    :prior-weights nil}}}}
        model (view-model/universe-section-model
               {}
               draft
               {:readiness {:request {:universe [btc-instrument]
                                      :objective {:kind :minimum-variance}}}
                :history-load-state {:status :idle}
                :history-status-by-id {"perp:BTC" :aligned}})
        row (some #(when (= "perp:NEW" (:instrument-id %)) %) (:selected-rows model))]
    (is (= "Modeled" (:assumption-badge-label row))
        "The configured basket-modeled stance reads 'Modeled', not 'Proxy behavior'.")
    (is (str/includes? (:assumption-badge-tooltip row) "modeled from a basket of similar assets")
        "Its chip hover explains that returns are modeled from similar assets.")))

(deftest run-verdict-reflects-readiness-test
  ;; ONE vocabulary for "ready": the footer pill and the rail Status row both
  ;; consume this verdict, so a green "Ready to run" can never sit next to a
  ;; visible readiness warning elsewhere on the page. (Exposure-policy
  ;; compliance no longer folds in here — comparing the CURRENT portfolio
  ;; against a freshly-edited policy is the normal state, not a warning.)
  (let [clean {:status :ok :runnable? true :warnings [] :blocking-warnings []}
        loaded {:status :succeeded}]
    (is (= {:level :ready :label "Ready to run" :warning-count 0}
           (view-model/run-verdict clean loaded)))
    (is (= :loading
           (:level (view-model/run-verdict {:reason :history-loading}
                                           {:status :loading}))))
    (let [blocked (view-model/run-verdict {:status :blocked
                                           :reason :missing-history-assumptions
                                           :blocking-warnings []
                                           :warnings []}
                                          loaded)]
      (is (= :blocked (:level blocked)))
      (is (= "Action needed" (:label blocked))))
    (let [cautioned (view-model/run-verdict
                     {:status :ok :runnable? true :blocking-warnings []
                      :request {:requested-universe [btc-instrument]}
                      :warnings [{:code :source-fetch-failed
                                  :instrument-id "perp:BTC"
                                  :message "History fetch failed for BTC."}]}
                     loaded)]
      (is (= :caution (:level cautioned)))
      (is (= "Ready with 1 warning" (:label cautioned))))))

(deftest run-verdict-reports-refreshing-not-loading-over-a-loaded-bundle-test
  ;; The rail Status row read a blocking "Loading…" for the whole background
  ;; refresh on every repeat visit, over a bundle that was already on screen.
  ;; readiness-status gates on a two-armed `or`, and the SECOND arm reads the
  ;; load-state directly — so this also pins that narrowing build-readiness alone
  ;; is not enough: with :refreshing? set, {:status :loading} must not win.
  (let [refreshing {:status :ready
                    :runnable? true
                    :refreshing? true
                    :blocking-warnings []
                    :warnings []}
        verdict (view-model/run-verdict refreshing {:status :loading})]
    (is (= :refreshing (:level verdict)))
    (is (= "Refreshing…" (:label verdict))))
  ;; A cold load — nothing usable behind it — is unchanged.
  (let [cold (view-model/run-verdict {:reason :history-loading}
                                     {:status :loading})]
    (is (= :loading (:level cold)))
    (is (= "Loading…" (:label cold)))))
