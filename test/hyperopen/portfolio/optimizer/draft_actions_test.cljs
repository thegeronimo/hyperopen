(ns hyperopen.portfolio.optimizer.draft-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.actions :as actions]))

(deftest objective-menu-actions-select-apply-and-rerun-test
  (let [state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"}]
                                               :objective {:kind :max-sharpe}
                                               :return-model {:kind :historical-mean}
                                               :metadata {:dirty? false}}}}
               :portfolio-ui {:optimizer {:objective-menu-selection :minimum-volatility}}}]
    (is (= [[:effects/save-many
             [[[:portfolio-ui :optimizer :objective-menu-open?] true]
              [[:portfolio-ui :optimizer :objective-menu-selection] :max-sharpe]
              [[:portfolio-ui :optimizer :objective-menu-target-sigma] nil]]]]
           (actions/open-portfolio-optimizer-objective-menu state)))
    (is (= [[:effects/save
             [:portfolio-ui :optimizer :objective-menu-selection]
             :target-volatility]]
           (actions/select-portfolio-optimizer-objective-menu-option
            state
            "targetVolatility")))
    (is (= [[:effects/save-many
             [[[:portfolio-ui :optimizer :objective-menu-open?] false]
              [[:portfolio-ui :optimizer :objective-menu-selection] nil]
              [[:portfolio-ui :optimizer :objective-menu-target-sigma] nil]]]]
           (actions/close-portfolio-optimizer-objective-menu state)))
    (is (= [[:effects/save-many
             [[[:portfolio-ui :optimizer :objective-menu-open?] false]
              [[:portfolio-ui :optimizer :objective-menu-selection] nil]
              [[:portfolio-ui :optimizer :objective-menu-target-sigma] nil]]]]
           (actions/handle-portfolio-optimizer-objective-menu-keydown
            state
            "Escape")))
    (is (= []
           (actions/handle-portfolio-optimizer-objective-menu-keydown
            state
            "Enter")))
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :objective]
               {:kind :minimum-variance}]
              [[:portfolio-ui :optimizer :objective-menu-open?] false]
              [[:portfolio-ui :optimizer :objective-menu-selection] nil]
              [[:portfolio-ui :optimizer :objective-menu-target-sigma] nil]
              [[:portfolio-ui :optimizer :target-sigma-draft] nil]
              [[:portfolio :optimizer :draft :metadata :dirty?] true]]]
            [:effects/run-portfolio-optimizer-pipeline]]
           (actions/apply-portfolio-optimizer-objective-menu-selection-and-run
            state)))))

(deftest objective-menu-apply-preserves-current-objective-parameter-test
  (let [state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"}]
                                               :objective {:kind :target-volatility
                                                           :target-volatility 0.22}
                                               :return-model {:kind :historical-mean}
                                               :metadata {:dirty? false}}}}
               :portfolio-ui {:optimizer {:objective-menu-selection :target-volatility}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :objective]
               {:kind :target-volatility
                :target-volatility 0.22}]
              [[:portfolio-ui :optimizer :objective-menu-open?] false]
              [[:portfolio-ui :optimizer :objective-menu-selection] nil]
              [[:portfolio-ui :optimizer :objective-menu-target-sigma] nil]
              [[:portfolio-ui :optimizer :target-sigma-draft] nil]
              [[:portfolio :optimizer :draft :metadata :dirty?] true]]]
            [:effects/run-portfolio-optimizer-pipeline]]
           (actions/apply-portfolio-optimizer-objective-menu-selection-and-run
            state))
        "re-applying target-volatility keeps the user-chosen sigma instead of the 12% preset")))

(deftest objective-menu-apply-max-sharpe-keeps-authored-views-as-is-test
  ;; Applying Maximum Sharpe attaches the views-aware return model with the
  ;; draft's authored views untouched — no normalization pass, no re-authoring.
  (let [state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"}]
                                               :objective {:kind :minimum-variance}
                                               :return-model {:kind :black-litterman
                                                              :views [{:kind :absolute
                                                                       :instrument-id "perp:BTC"
                                                                       :return 0.2
                                                                       :confidence 0.75
                                                                       :weights {"perp:BTC" 1}}]}}}}
               :portfolio-ui {:optimizer {:objective-menu-selection :max-sharpe}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :objective]
               {:kind :max-sharpe}]
              [[:portfolio :optimizer :draft :return-model]
               {:kind :black-litterman
                :views [{:kind :absolute
                         :instrument-id "perp:BTC"
                         :return 0.2
                         :confidence 0.75
                         :weights {"perp:BTC" 1}}]}]
              [[:portfolio-ui :optimizer :objective-menu-open?] false]
              [[:portfolio-ui :optimizer :objective-menu-selection] nil]
              [[:portfolio-ui :optimizer :objective-menu-target-sigma] nil]
              [[:portfolio-ui :optimizer :target-sigma-draft] nil]
              [[:portfolio :optimizer :draft :metadata :dirty?] true]]]
            [:effects/run-portfolio-optimizer-pipeline]]
           (actions/apply-portfolio-optimizer-objective-menu-selection-and-run
            state)))))

(deftest objective-menu-apply-max-sharpe-hydrates-views-from-wallet-library-test
  ;; An old historical-mean draft picking Maximum Sharpe gets the wallet's
  ;; remembered views for universe instruments, not an empty slate.
  (let [state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"}
                                                          {:instrument-id "perp:ETH"}]
                                               :objective {:kind :minimum-variance}
                                               :return-model {:kind :historical-mean}}
                                       :view-library {"perp:BTC" {:instrument-id "perp:BTC"
                                                                  :return 0.2
                                                                  :confidence-level :high
                                                                  :updated-at-ms 1700000000000}
                                                      "perp:SOL" {:instrument-id "perp:SOL"
                                                                  :return 0.3
                                                                  :confidence-level :low
                                                                  :updated-at-ms 1700000000000}}}}
               :portfolio-ui {:optimizer {:objective-menu-selection :max-sharpe}}}
        effects (actions/apply-portfolio-optimizer-objective-menu-selection-and-run state)
        saved-values (second (first effects))
        return-model (second (second saved-values))]
    (is (= {:kind :black-litterman
            :views [{:id "bl_view_1"
                     :kind :absolute
                     :instrument-id "perp:BTC"
                     :return 0.2
                     :confidence-level :high
                     :confidence 0.75
                     :confidence-variance 0.25
                     :horizon :3m
                     :weights {"perp:BTC" 1}}]}
           return-model)
        "Only remembered views for CURRENT universe instruments hydrate — perp:SOL stays in the library.")))

(deftest objective-menu-apply-non-sharpe-objective-keeps-views-model-test
  ;; Views are an input policy, not an objective: picking Minimum volatility no
  ;; longer downgrades the return model to historical-mean.
  (let [state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"}]
                                               :objective {:kind :max-sharpe}
                                               :return-model {:kind :black-litterman
                                                              :views [{:kind :absolute
                                                                       :instrument-id "perp:BTC"
                                                                       :return 0.2
                                                                       :confidence 0.75
                                                                       :weights {"perp:BTC" 1}}]}}}}
               :portfolio-ui {:optimizer {:objective-menu-selection :minimum-volatility}}}]
    (is (= [[:effects/save-many
             [[[:portfolio :optimizer :draft :objective]
               {:kind :minimum-variance}]
              [[:portfolio-ui :optimizer :objective-menu-open?] false]
              [[:portfolio-ui :optimizer :objective-menu-selection] nil]
              [[:portfolio-ui :optimizer :objective-menu-target-sigma] nil]
              [[:portfolio-ui :optimizer :target-sigma-draft] nil]
              [[:portfolio :optimizer :draft :metadata :dirty?] true]]]
            [:effects/run-portfolio-optimizer-pipeline]]
           (actions/apply-portfolio-optimizer-objective-menu-selection-and-run
            state)))))

(deftest inline-view-edits-buffer-only-outside-views-model-test
  ;; With a historical/EW estimator the panel is a note, not an editor: typing
  ;; and stepping only move the display buffer, and confidence is a no-op.
  (let [state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"}
                                                           {:instrument-id "perp:ETH"}
                                                           {:instrument-id "perp:HYPE"}]
                                               :objective {:kind :minimum-variance}
                                               :return-model {:kind :historical-mean}}}}
               :portfolio-ui {:optimizer {:objective-menu-view-drafts
                                           {:perp:BTC {:return-text "18"
                                                       :confidence :medium}}}}}]
    (is (= [[:effects/save
             [:portfolio-ui :optimizer :objective-menu-view-drafts
              :perp:BTC
              :return-text]
             "19.25"]]
           (actions/set-portfolio-optimizer-objective-menu-view-return
            state
            "perp:BTC"
            "19.25")))
    (is (= []
           (actions/set-portfolio-optimizer-objective-menu-view-confidence
            state
            "perp:BTC"
            "high")))
    (is (= [[:effects/save
             [:portfolio-ui :optimizer :objective-menu-view-drafts
              :perp:BTC
              :return-text]
             "18.5"]]
           (actions/step-portfolio-optimizer-objective-menu-view-return
            state
            "perp:BTC"
            :up)))
    (is (= [[:effects/save
             [:portfolio-ui :optimizer :objective-menu-view-drafts
              :perp:BTC
              :return-text]
             "17.5"]]
           (actions/step-portfolio-optimizer-objective-menu-view-return
            state
            "perp:BTC"
            "ArrowDown")))
    (is (= []
           (actions/add-portfolio-optimizer-objective-menu-view
            (assoc-in state
                      [:portfolio-ui :optimizer :objective-menu-view-order]
                      ["perp:BTC" "perp:ETH"]))))))

(def ^:private views-model-state
  ;; A views-aware draft with one authored view. Universe has no history data, so
  ;; the implied baseline is unknown — exactly the worst case the actions must
  ;; stay honest in.
  {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"}
                                              {:instrument-id "perp:ETH"}]
                                   :objective {:kind :max-sharpe}
                                   :return-model
                                   {:kind :black-litterman
                                    :views [{:id "bl_view_1"
                                             :kind :absolute
                                             :instrument-id "perp:BTC"
                                             :return 0.2
                                             :confidence-level :high
                                             :confidence 0.75
                                             :confidence-variance 0.25
                                             :horizon :3m
                                             :weights {"perp:BTC" 1}}]}
                                   :metadata {:dirty? false}}}}})

(def ^:private views-path
  [:portfolio :optimizer :draft :return-model :views])

(def ^:private dirty-path*
  [:portfolio :optimizer :draft :metadata :dirty?])

(deftest inline-view-return-edit-authors-view-and-syncs-library-test
  ;; Typing a parseable return on a fresh row authors the view immediately and
  ;; upserts it into the wallet's view library.
  (let [effects (actions/set-portfolio-optimizer-objective-menu-view-return
                 views-model-state
                 "perp:ETH"
                 "12.5")]
    (is (= [[:effects/save-many
             [[views-path
               [{:id "bl_view_1"
                 :kind :absolute
                 :instrument-id "perp:BTC"
                 :return 0.2
                 :confidence-level :high
                 :confidence 0.75
                 :confidence-variance 0.25
                 :horizon :3m
                 :weights {"perp:BTC" 1}}
                {:id "bl_view_2"
                 :kind :absolute
                 :instrument-id "perp:ETH"
                 :return 0.125
                 :confidence-level :medium
                 :confidence 0.5
                 :confidence-variance 0.5
                 :horizon :3m
                 :weights {"perp:ETH" 1}}]]
              [[:portfolio-ui :optimizer :objective-menu-view-drafts :perp:ETH :return-text]
               "12.5"]
              [dirty-path* true]]]
            [:effects/sync-portfolio-optimizer-view-library
             {:upserts [{:instrument-id "perp:ETH"
                         :return 0.125
                         :confidence-level :medium}]
              :removes []}]]
           effects))))

(deftest inline-view-return-edit-updates-existing-view-preserving-confidence-test
  (let [effects (actions/set-portfolio-optimizer-objective-menu-view-return
                 views-model-state
                 "perp:BTC"
                 "25")
        saved-views (get-in (vec effects) [0 1 0 1])]
    (is (= [{:id "bl_view_1"
             :kind :absolute
             :instrument-id "perp:BTC"
             :return 0.25
             :confidence-level :high
             :confidence 0.75
             :confidence-variance 0.25
             :horizon :3m
             :weights {"perp:BTC" 1}}]
           saved-views)
        "Editing the return keeps the user's confidence level.")))

(deftest inline-view-blank-return-resets-row-to-implied-test
  (let [effects (actions/set-portfolio-optimizer-objective-menu-view-return
                 views-model-state
                 "perp:BTC"
                 "")]
    (is (= [[:effects/sync-portfolio-optimizer-view-library
             {:upserts []
              :removes ["perp:BTC"]}]
            [:effects/save-many
             [[views-path []]
              [[:portfolio-ui :optimizer :objective-menu-view-drafts :perp:BTC :return-text]
               ""]
              [dirty-path* true]]]]
           effects)
        "Blank text clears the authored view and removes the library entry (library remove FIRST, so the hydrate watcher can't resurrect it)."))
  (is (= [[:effects/save
           [:portfolio-ui :optimizer :objective-menu-view-drafts :perp:ETH :return-text]
           ""]]
         (actions/set-portfolio-optimizer-objective-menu-view-return
          views-model-state
          "perp:ETH"
          ""))
      "Blank text on an already-implied row only clears the buffer."))

(deftest inline-view-unparseable-text-keeps-existing-view-test
  (is (= [[:effects/save
           [:portfolio-ui :optimizer :objective-menu-view-drafts :perp:BTC :return-text]
           "-"]]
         (actions/set-portfolio-optimizer-objective-menu-view-return
          views-model-state
          "perp:BTC"
          "-"))
      "Mid-typing text (\"-\") moves only the buffer; the authored view survives."))

(deftest inline-view-confidence-reweights-authored-view-test
  (let [effects (actions/set-portfolio-optimizer-objective-menu-view-confidence
                 views-model-state
                 "perp:BTC"
                 "low")]
    (is (= [[:effects/save-many
             [[views-path
               [{:id "bl_view_1"
                 :kind :absolute
                 :instrument-id "perp:BTC"
                 :return 0.2
                 :confidence-level :low
                 :confidence 0.25
                 :confidence-variance 0.75
                 :horizon :3m
                 :weights {"perp:BTC" 1}}]]
              [dirty-path* true]]]
            [:effects/sync-portfolio-optimizer-view-library
             {:upserts [{:instrument-id "perp:BTC"
                         :return 0.2
                         :confidence-level :low}]
              :removes []}]]
           effects))))

(deftest inline-view-confidence-adopts-buffer-value-on-implied-row-test
  ;; Confidence on an implied row adopts the shown value as the user's view —
  ;; here the typing buffer holds the shown value.
  (let [state (assoc-in views-model-state
                        [:portfolio-ui :optimizer :objective-menu-view-drafts
                         :perp:ETH :return-text]
                        "18")
        effects (actions/set-portfolio-optimizer-objective-menu-view-confidence
                 state
                 "perp:ETH"
                 "high")
        saved-views (get-in (vec effects) [0 1 0 1])]
    (is (= {:id "bl_view_2"
            :kind :absolute
            :instrument-id "perp:ETH"
            :return 0.18
            :confidence-level :high
            :confidence 0.75
            :confidence-variance 0.25
            :horizon :3m
            :weights {"perp:ETH" 1}}
           (last saved-views))))
  ;; With no buffer, no authored view, and no computable baseline (this fixture
  ;; has no history), there is nothing honest to adopt: no-op.
  (is (= []
         (actions/set-portfolio-optimizer-objective-menu-view-confidence
          views-model-state
          "perp:ETH"
          "high"))))

(deftest inline-view-step-authors-view-from-effective-value-test
  (let [effects (actions/step-portfolio-optimizer-objective-menu-view-return
                 views-model-state
                 "perp:BTC"
                 :up)
        saved-views (get-in (vec effects) [0 1 0 1])
        buffer-text (get-in (vec effects) [0 1 1 1])]
    (is (= 0.205 (:return (first saved-views))))
    (is (= "20.5" buffer-text))
    (is (= [:effects/sync-portfolio-optimizer-view-library
            {:upserts [{:instrument-id "perp:BTC"
                        :return 0.205
                        :confidence-level :high}]
             :removes []}]
           (last effects)))))

(deftest remove-inline-view-resets-row-and-library-test
  (let [state (assoc-in views-model-state
                        [:portfolio-ui :optimizer :objective-menu-view-drafts]
                        {:perp:BTC {:return-text "20"}})
        effects (actions/remove-portfolio-optimizer-objective-menu-view
                 state
                 "perp:BTC")]
    (is (= [[:effects/sync-portfolio-optimizer-view-library
             {:upserts []
              :removes ["perp:BTC"]}]
            [:effects/save-many
             [[views-path []]
              [[:portfolio-ui :optimizer :objective-menu-view-order] ["perp:ETH"]]
              [[:portfolio-ui :optimizer :objective-menu-view-drafts] {}]
              [dirty-path* true]]]]
           effects)))
  ;; Removing a row that has no authored view stays a UI-only cleanup.
  (is (= [[:effects/save-many
           [[[:portfolio-ui :optimizer :objective-menu-view-order] ["perp:BTC"]]
            [[:portfolio-ui :optimizer :objective-menu-view-drafts] {}]]]]
         (actions/remove-portfolio-optimizer-objective-menu-view
          (assoc-in views-model-state
                    [:portfolio :optimizer :draft :return-model :views]
                    [])
          "perp:ETH"))))

(deftest remove-inline-view-preserves-relative-views-test
  (let [state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:BTC"}
                                                           {:instrument-id "perp:ETH"}]
                                               :objective {:kind :max-sharpe}
                                               :return-model
                                               {:kind :black-litterman
                                                :views [{:id "abs-eth"
                                                         :kind :absolute
                                                         :instrument-id "perp:ETH"
                                                         :return 0.12
                                                         :confidence 0.5
                                                         :weights {"perp:ETH" 1}}
                                                        {:id "rel-eth-btc"
                                                         :kind :relative
                                                         :instrument-id "perp:ETH"
                                                         :comparator-instrument-id "perp:BTC"
                                                         :return 0.04
                                                         :confidence 0.5
                                                         :weights {"perp:ETH" 1
                                                                   "perp:BTC" -1}}]}
                                               :metadata {:dirty? false}}}}}
        effects (actions/remove-portfolio-optimizer-objective-menu-view state "perp:ETH")
        ;; effects = [library-sync, save-many]; the saved views ride the save.
        saved-views (get-in (vec effects) [1 1 0 1])]
    (is (= [{:id "rel-eth-btc"
             :kind :relative
             :instrument-id "perp:ETH"
             :comparator-instrument-id "perp:BTC"
             :return 0.04
             :confidence 0.5
             :weights {"perp:ETH" 1
                       "perp:BTC" -1}}]
           saved-views)
        "Reset-to-implied removes only the ABSOLUTE view; relative views survive.")))

(deftest set-draft-constraint-normalizes-supported-values-test
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :max-asset-weight]
                                0.42]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-constraint
          {}
          :max-asset-weight
          "0.42")))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :long-only?]
                                true]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-constraint
          {}
          :long-only?
          true)))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :include-spot?]
                                true]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-constraint
          {}
          :include-spot?
          true)))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :max-turnover]
                                nil]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-constraint
          {}
          :max-turnover
          nil)))
  (is (= []
         (actions/set-portfolio-optimizer-constraint
          {}
          :gross-max
          "not-a-number"))))

(deftest set-draft-objective-parameter-updates-supported-targets-test
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :objective :target-return]
                                0.18]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-objective-parameter
          {}
          :target-return
          "0.18")))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :objective :target-volatility]
                                0.22]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-objective-parameter
          {}
          "targetVolatility"
          "0.22")))
  (is (= []
         (actions/set-portfolio-optimizer-objective-parameter
          {}
          :unknown
          "0.1"))))

(deftest set-draft-execution-assumption-normalizes-supported-values-test
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :execution-assumptions :fallback-slippage-bps]
                                35]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-execution-assumption
          {}
          :fallback-slippage-bps
          "35")))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :execution-assumptions :manual-capital-usdc]
                                100000]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-execution-assumption
          {}
          :manual-capital-usdc
          "100000")))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :execution-assumptions :manual-capital-usdc]
                                nil]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-execution-assumption
          {}
          :manual-capital-usdc
          "")))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :execution-assumptions :default-order-type]
                                :market]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-execution-assumption
          {}
          :default-order-type
          "market")))
  (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :execution-assumptions :fee-mode]
                                :taker]
                               [[:portfolio :optimizer :draft :metadata :dirty?]
                                true]]]]
         (actions/set-portfolio-optimizer-execution-assumption
          {}
          :fee-mode
          :taker)))
  (is (= []
         (actions/set-portfolio-optimizer-execution-assumption
          {}
          :fallback-slippage-bps
          "not-a-number"))))

(deftest set-draft-instrument-filter-updates-allowlist-and-blocklist-test
  (let [state {:portfolio {:optimizer {:draft {:constraints {:allowlist ["perp:BTC"]
                                                             :blocklist ["spot:PURR"]}}}}}]
    (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :allowlist]
                                  ["perp:BTC" "perp:ETH"]]
                                 [[:portfolio :optimizer :draft :metadata :dirty?]
                                  true]]]]
           (actions/set-portfolio-optimizer-instrument-filter
            state
            :allowlist
            "perp:ETH"
            true)))
    (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :blocklist]
                                  []]
                                 [[:portfolio :optimizer :draft :metadata :dirty?]
                                  true]]]]
           (actions/set-portfolio-optimizer-instrument-filter
            state
            :blocklist
            "spot:PURR"
            false)))
    (is (= []
           (actions/set-portfolio-optimizer-instrument-filter
            state
            :unknown
            "perp:BTC"
            true)))))

(deftest set-draft-asset-override-updates-row-level-constraints-test
  (let [state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:ETH"
                                                            :market-type :perp}
                                                           {:instrument-id "spot:PURR"
                                                            :market-type :spot}]
                                           :constraints {:held-locks ["perp:BTC"]
                                                         :asset-overrides {"perp:BTC" {:max-weight 0.1}}}}}}}]
    (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :asset-overrides]
                                  {"perp:BTC" {:max-weight 0.1}
                                   "perp:ETH" {:max-weight 0.28}}]
                                 [[:portfolio :optimizer :draft :metadata :dirty?]
                                  true]]]]
           (actions/set-portfolio-optimizer-asset-override
            state
            :max-weight
            "perp:ETH"
            "0.28"))
        "The save-many contract only allows keyword paths, so instrument-keyed maps are saved whole.")
    (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :held-locks]
                                  ["perp:BTC" "perp:ETH"]]
                                 [[:portfolio :optimizer :draft :metadata :dirty?]
                                  true]]]]
           (actions/set-portfolio-optimizer-asset-override
            state
            :held-lock?
            "perp:ETH"
            true)))
    (is (= [[:effects/save-many [[[:portfolio :optimizer :draft :constraints :perp-leverage]
                                  {"perp:ETH" {:max-weight 0.5}}]
                                 [[:portfolio :optimizer :draft :metadata :dirty?]
                                  true]]]]
           (actions/set-portfolio-optimizer-asset-override
            state
            :perp-max-weight
            "perp:ETH"
            "0.5")))
    (is (= []
           (actions/set-portfolio-optimizer-asset-override
            state
            :max-weight
            "perp:ETH"
            "not-a-number")))
    (is (= []
           (actions/set-portfolio-optimizer-asset-override
            state
            :perp-max-weight
            "spot:PURR"
            "0.5")))))

(def ^:private history-assumptions-path
  [:portfolio :optimizer :draft :history-assumptions])

(def ^:private history-reference-instruments-path
  [:portfolio :optimizer :draft :proxy-reference-instruments])

(def ^:private dirty-path
  [:portfolio :optimizer :draft :metadata :dirty?])

(defn- ha-state
  [assumptions]
  {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:NEW"}
                                              {:instrument-id "perp:BTC"}]
                                   :history-assumptions assumptions
                                   :metadata {:dirty? false}}}}})

(deftest set-history-assumption-mode-seeds-conservative-anchors-test
  (is (= [[:effects/save-many
           [[history-assumptions-path
             {"perp:NEW" {:behavior :conservative
                          :expected-return 0.0
                          :volatility 0.8
                          :max-weight 0.03
                          :correlation-floor 0.75}}]
            [dirty-path true]]]
          [:effects/sync-portfolio-optimizer-assumption-library
           {:upserts [{:instrument-id "perp:NEW"
                       :assumption {:behavior :conservative
                                    :expected-return 0.0
                                    :volatility 0.8
                                    :max-weight 0.03
                                    :correlation-floor 0.75}
                       :reference-instruments []}]}]]
         (actions/set-portfolio-optimizer-history-assumption-mode
          (ha-state {}) "perp:NEW" :conservative))
      "Choosing the conservative behavior on a fresh asset seeds editable anchors, saves the whole map, and remembers the entry in the wallet library.")
  (is (= [[:effects/save-many
           [[history-assumptions-path
             {"perp:NEW" {:behavior :proxy
                          :expected-return 0.0
                          :volatility 0.8
                          :max-weight 0.05
                          :proxy {:instrument-ids []
                                  :relationship-strength :medium
                                  :prior-weights nil}}}]
            [dirty-path true]]]
          [:effects/sync-portfolio-optimizer-assumption-library
           {:upserts [{:instrument-id "perp:NEW"
                       :assumption {:behavior :proxy
                                    :expected-return 0.0
                                    :volatility 0.8
                                    :max-weight 0.05
                                    :proxy {:instrument-ids []
                                            :relationship-strength :medium
                                            :prior-weights nil}}
                       :reference-instruments []}]}]]
         (actions/set-portfolio-optimizer-history-assumption-mode
          (ha-state {}) "perp:NEW" :proxy))
      "Choosing proxy behavior seeds the multi-proxy defaults.")
  (is (= [] (actions/set-portfolio-optimizer-history-assumption-mode
             (ha-state {}) "perp:NEW" :bogus))
      "An unknown mode is a no-op.")
  (is (= [] (actions/set-portfolio-optimizer-history-assumption-mode
             (ha-state {}) " " :conservative))
      "A blank instrument-id is a no-op."))

(def ^:private proxy-entry-fixture
  {:behavior :proxy
   :expected-return 0.0
   :volatility 0.8
   :max-weight 0.05
   :proxy {:instrument-ids ["perp:BTC"]
           :relationship-strength :medium
           :prior-weights nil}})

(defn- ha-state-with-universe
  [assumptions universe]
  (assoc-in (ha-state assumptions)
            [:portfolio :optimizer :draft :universe]
            universe))

(deftest set-history-assumption-proxy-asset-toggles-membership-test
  ;; perp:ETH is a universe member here, so no catalog resolution is needed.
  (let [with-proxy (ha-state-with-universe {"perp:NEW" proxy-entry-fixture}
                                           [{:instrument-id "perp:NEW"}
                                            {:instrument-id "perp:BTC"}
                                            {:instrument-id "perp:ETH"}])]
    (is (= ["perp:BTC" "perp:ETH"]
           (get-in (actions/set-portfolio-optimizer-history-assumption-proxy-asset
                    with-proxy "perp:NEW" "perp:ETH" true)
                   [0 1 0 1 "perp:NEW" :proxy :instrument-ids]))
        "Enabling adds the proxy id.")
    (is (= []
           (get-in (actions/set-portfolio-optimizer-history-assumption-proxy-asset
                    with-proxy "perp:NEW" "perp:BTC" false)
                   [0 1 0 1 "perp:NEW" :proxy :instrument-ids]))
        "Disabling removes it.")
    (is (= [] (actions/set-portfolio-optimizer-history-assumption-proxy-asset
               with-proxy "perp:NEW" "perp:NEW" true))
        "An asset can never proxy itself.")
    (is (= [] (actions/set-portfolio-optimizer-history-assumption-proxy-asset
               with-proxy "perp:NEW" "perp:ZZZ" true))
        "An out-of-universe proxy the catalog can't resolve is a no-op.")
    (is (= [] (actions/set-portfolio-optimizer-history-assumption-proxy-asset
               (ha-state {"perp:NEW" {:behavior :conservative
                                      :correlation-floor 0.75}})
               "perp:NEW" "perp:BTC" true))
        "Proxy toggles only apply to proxy-mode entries.")))

(deftest set-history-assumption-proxy-asset-catalog-reference-only-test
  ;; A proxy outside the universe is resolved from the market catalog, stored as
  ;; a reference-only instrument, and its history is prefetched - so the engine
  ;; can synthesize covariance from it without it ever becoming a holding.
  (let [state (-> (ha-state {"perp:NEW" proxy-entry-fixture})
                  (assoc-in [:asset-selector :market-by-key "perp:SOL"]
                            {:key "perp:SOL" :market-type :perp :coin "SOL"
                             :symbol "SOL-USDC"})
                  (assoc-in [:portfolio :optimizer :history-discovery]
                            {:backend-id-by-local-id {"perp:SOL" "hl:perp:SOL"}}))
        effects (actions/set-portfolio-optimizer-history-assumption-proxy-asset
                 state "perp:NEW" "perp:SOL" true)
        path-values (get-in effects [0 1])
        by-path (into {} (map (fn [[p v]] [p v])) path-values)
        refs (get by-path history-reference-instruments-path)]
    (is (= ["perp:BTC" "perp:SOL"]
           (get-in effects [0 1 0 1 "perp:NEW" :proxy :instrument-ids]))
        "The catalog proxy is added to the assumption.")
    (is (= ["perp:SOL"] (mapv :instrument-id refs))
        "It is stored as a reference-only proxy instrument (outside the universe).")
    (is (= :perp (:market-type (first refs))) "Catalog metadata is resolved.")
    (is (= "hl:perp:SOL" (:optimizer-history/instrument-id (first refs)))
        "Backend history identity is decorated on; without it alignment rejects the proxy as identity-ambiguous.")
    (is (some (fn [effect]
                (= [:portfolio :optimizer :history-prefetch] (first effect)))
              path-values)
        "Its history is enqueued for prefetch so covariance can use it.")
    ;; perp:BTC is a universe member, so adding it never creates a reference.
    (let [btc-effects (actions/set-portfolio-optimizer-history-assumption-proxy-asset
                       (assoc-in state [:portfolio :optimizer :draft :universe]
                                 [{:instrument-id "perp:NEW"} {:instrument-id "perp:SOL"}])
                       "perp:NEW" "perp:SOL" true)]
      (is (empty? (get (into {} (get-in btc-effects [0 1]))
                       history-reference-instruments-path))
          "An in-universe proxy is not duplicated into reference-instruments."))))

(deftest set-history-assumption-relationship-strength-test
  (let [with-proxy (ha-state {"perp:NEW" proxy-entry-fixture})]
    (is (= :high
           (get-in (actions/set-portfolio-optimizer-history-assumption-relationship-strength
                    with-proxy "perp:NEW" :high)
                   [0 1 0 1 "perp:NEW" :proxy :relationship-strength])))
    (is (= [] (actions/set-portfolio-optimizer-history-assumption-relationship-strength
               with-proxy "perp:NEW" :extreme))
        "Unknown strengths are a no-op.")))

(deftest apply-and-reset-history-assumption-test
  (let [with-proxy (ha-state {"perp:NEW" proxy-entry-fixture})
        applied (actions/apply-portfolio-optimizer-history-assumption
                 with-proxy "perp:NEW")]
    (is (= {:source :user :acknowledged? true}
           (get-in applied [0 1 0 1 "perp:NEW" :metadata]))
        "Apply acknowledges the configuration (presentation only).")
    (is (= [] (actions/apply-portfolio-optimizer-history-assumption
               with-proxy "perp:GONE")))
    (let [edited (ha-state {"perp:NEW" (assoc proxy-entry-fixture
                                              :volatility 1.2
                                              :metadata {:source :user
                                                         :acknowledged? true})})
          reset (actions/reset-portfolio-optimizer-history-assumption
                 edited "perp:NEW")]
      (is (= (assoc-in proxy-entry-fixture [:proxy :instrument-ids] [])
             (get-in reset [0 1 0 1 "perp:NEW"]))
          "Reset re-seeds the behavior's defaults (fresh proxy list, no ack)."))
    (let [vol-edit (actions/set-portfolio-optimizer-history-assumption-expected-volatility
                    (ha-state {"perp:NEW" (assoc proxy-entry-fixture
                                                 :metadata {:source :user
                                                            :acknowledged? true})})
                    "perp:NEW" "90")]
      (is (nil? (get-in vol-edit [0 1 0 1 "perp:NEW" :metadata :acknowledged?]))
          "Editing a field reopens the acknowledgment."))))

(deftest set-history-assumption-mode-reselect-is-a-no-op-test
  (let [filled (ha-state {"perp:NEW" {:behavior :conservative
                                      :expected-return 0.25
                                      :volatility 0.9
                                      :max-weight 0.03
                                      :correlation-floor 0.75}})]
    (is (= [] (actions/set-portfolio-optimizer-history-assumption-mode
               filled "perp:NEW" :conservative))
        "Re-selecting the current (only) mode is a no-op so user input is never discarded.")))

(deftest set-history-assumption-fields-parse-percent-on-existing-entry-test
  (let [conservative (ha-state {"perp:NEW" {:behavior :conservative
                                            :expected-return nil
                                            :volatility nil
                                            :max-weight 0.03
                                            :correlation-floor 0.75}})]
    (is (= [[:effects/save-many
             [[history-assumptions-path
               {"perp:NEW" {:behavior :conservative
                            :expected-return 0.25
                            :volatility nil
                            :max-weight 0.03
                            :correlation-floor 0.75}}]
              [dirty-path true]]]
            [:effects/sync-portfolio-optimizer-assumption-library
             {:upserts [{:instrument-id "perp:NEW"
                         :assumption {:behavior :conservative
                                      :expected-return 0.25
                                      :volatility nil
                                      :max-weight 0.03
                                      :correlation-floor 0.75}
                         :reference-instruments []}]}]]
           (actions/set-portfolio-optimizer-history-assumption-expected-return
            conservative "perp:NEW" "25"))
        "Percent text is stored as a decimal (25% => 0.25) and the edit is remembered in the wallet library.")
    (is (= 0.9
           (get-in (actions/set-portfolio-optimizer-history-assumption-expected-volatility
                    conservative "perp:NEW" "90")
                   [0 1 0 1 "perp:NEW" :volatility])))
    (is (= 0.05
           (get-in (actions/set-portfolio-optimizer-history-assumption-max-weight-cap
                    conservative "perp:NEW" "5")
                   [0 1 0 1 "perp:NEW" :max-weight])))
    (is (= [] (actions/set-portfolio-optimizer-history-assumption-expected-return
               (ha-state {}) "perp:NEW" "25"))
        "Field setters no-op until a mode has seeded an entry.")
    (is (= [] (actions/set-portfolio-optimizer-history-assumption-expected-return
               conservative "perp:NEW" "")))))

(deftest history-assumption-field-edit-prunes-stale-reference-instruments-test
  ;; Live 2026-07-09: BTC lingered in :proxy-reference-instruments after it
  ;; joined the universe, and the field-edit save path skipped reconciliation,
  ;; so nothing ever cleaned it up (the request builder then evicted the real
  ;; portfolio asset as "reference-only"). Every assumption save reconciles.
  (let [state (assoc-in (ha-state {"perp:NEW" proxy-entry-fixture})
                        history-reference-instruments-path
                        [{:instrument-id "perp:BTC"}])
        effects (actions/set-portfolio-optimizer-history-assumption-expected-volatility
                 state "perp:NEW" "90")
        path-values (get-in effects [0 1])]
    (is (= [history-reference-instruments-path []]
           (some #(when (= history-reference-instruments-path (first %)) %)
                 path-values))
        "An ordinary field edit prunes the stale in-universe reference.")))

(deftest clear-history-assumption-removes-entry-test
  (let [proxy (ha-state {"perp:NEW" {:behavior :conservative
                                     :expected-return 0.25
                                     :volatility 0.9
                                     :max-weight 0.05
                                     :correlation-floor 0.75}
                         "perp:OTHER" {:behavior :conservative
                                       :expected-return nil
                                       :volatility 0.5
                                       :max-weight 0.03
                                       :correlation-floor 0.75}})]
    (is (= [[:effects/sync-portfolio-optimizer-assumption-library
             {:removes ["perp:NEW"]}]
            [:effects/save-many
             [[history-assumptions-path
               {"perp:OTHER" {:behavior :conservative
                              :expected-return nil
                              :volatility 0.5
                              :max-weight 0.03
                              :correlation-floor 0.75}}]
              [dirty-path true]]]]
           (actions/clear-portfolio-optimizer-history-assumption proxy "perp:NEW"))
        "Clearing removes only the target entry, preserves the rest, and forgets the entry in the wallet library BEFORE the draft write (so the hydrate watcher can't resurrect it).")
    (is (= [] (actions/clear-portfolio-optimizer-history-assumption
               (ha-state {}) "perp:NEW"))
        "Clearing an absent assumption is a no-op.")))

(def ^:private queue-active-path
  [:portfolio-ui :optimizer :history-assumption-active])

(deftest set-history-assumption-active-moves-the-queue-test
  (is (= [[:effects/save queue-active-path "perp:NEW"]]
         (actions/set-portfolio-optimizer-history-assumption-active
          (ha-state {}) "perp:NEW"))
      "A rail pill / Prev / Skip writes the asset the queue is asking about.")
  (is (= [[:effects/save queue-active-path nil]]
         (actions/set-portfolio-optimizer-history-assumption-active (ha-state {}) " "))
      "A blank id clears the pointer, handing the queue back to its default."))

(deftest apply-history-assumption-acknowledges-the-entry-test
  ;; Apply IS the queue's Accept: it records the user's yes on the entry and
  ;; nothing else. Run readiness never depends on it — it only decides whether
  ;; the queue still owes this asset a turn.
  (let [state (ha-state {"perp:NEW" {:behavior :conservative
                                     :expected-return 0.0
                                     :volatility 0.8
                                     :max-weight 0.03
                                     :correlation-floor 0.75}})
        effects (vec (actions/apply-portfolio-optimizer-history-assumption state "perp:NEW"))]
    (is (true? (get-in effects [0 1 0 1 "perp:NEW" :metadata :acknowledged?]))
        "The entry is acknowledged.")
    (is (= [] (actions/apply-portfolio-optimizer-history-assumption (ha-state {}) "perp:NEW"))
        "Acknowledging an absent assumption is a no-op.")))

(deftest hydrate-history-assumption-library-gap-fills-draft-test
  (let [conservative {:behavior :conservative
                      :expected-return 0.0
                      :volatility 0.8
                      :max-weight 0.03
                      :correlation-floor 0.75}
        state (-> (ha-state {})
                  (assoc-in [:portfolio :optimizer :assumption-library]
                            {"perp:NEW" {:instrument-id "perp:NEW"
                                         :entry conservative
                                         :reference-instruments []
                                         :updated-at-ms 1}}))]
    (is (= [[:effects/save-many
             [[history-assumptions-path {"perp:NEW" conservative}]]]]
           (actions/hydrate-portfolio-optimizer-history-assumption-library state))
        "A remembered assumption gap-fills the universe member WITHOUT marking the draft dirty."))
  (is (= [] (actions/hydrate-portfolio-optimizer-history-assumption-library (ha-state {})))
      "An empty library hydrates nothing.")
  (let [existing {:behavior :conservative
                  :expected-return 0.1
                  :volatility 1.2
                  :max-weight 0.03
                  :correlation-floor 0.75}
        state (-> (ha-state {"perp:NEW" existing})
                  (assoc-in [:portfolio :optimizer :assumption-library]
                            {"perp:NEW" {:instrument-id "perp:NEW"
                                         :entry {:behavior :conservative}
                                         :reference-instruments []
                                         :updated-at-ms 1}}))]
    (is (= [] (actions/hydrate-portfolio-optimizer-history-assumption-library state))
        "An existing draft entry always wins - hydration never clobbers edits.")))

(deftest hydrate-history-assumption-library-restores-reference-proxies-test
  ;; A remembered proxy basket may reference proxies outside the universe;
  ;; hydration must restore them as reference-only instruments and prefetch
  ;; their history, or the hydrated card would block on missing proxy history.
  (let [sol {:instrument-id "perp:SOL" :market-type :perp :coin "SOL"}
        proxy-entry {:behavior :proxy
                     :expected-return 0.0
                     :volatility 0.8
                     :max-weight 0.05
                     :proxy {:instrument-ids ["perp:BTC" "perp:SOL"]
                             :relationship-strength :medium
                             :prior-weights nil}}
        state (-> (ha-state {})
                  (assoc-in [:portfolio :optimizer :assumption-library]
                            {"perp:NEW" {:instrument-id "perp:NEW"
                                         :entry proxy-entry
                                         :reference-instruments [sol]
                                         :updated-at-ms 1}}))
        effects (actions/hydrate-portfolio-optimizer-history-assumption-library state)
        path-values (get-in (vec effects) [0 1])]
    (is (some (fn [[path value]]
                (and (= history-reference-instruments-path path)
                     (= [sol] value)))
              path-values)
        "The out-of-universe proxy re-enters the reference instruments.")
    (is (some (fn [effect]
                (= :effects/load-portfolio-optimizer-history (first effect)))
              effects)
        "Its history is prefetched so covariance synthesis has data.")))

(deftest remove-universe-instrument-clears-history-assumption-test
  (let [state {:portfolio {:optimizer {:draft {:universe [{:instrument-id "perp:NEW"}
                                                          {:instrument-id "perp:BTC"}]
                                               :constraints {:asset-overrides {}
                                                             :perp-leverage {}
                                                             :allowlist []
                                                             :blocklist []
                                                             :held-locks []}
                                               :history-assumptions
                                               {"perp:NEW" {:behavior :conservative
                                                            :expected-return nil
                                                            :volatility 0.9
                                                            :max-weight 0.03
                                                            :correlation-floor 0.75}}
                                               :metadata {:dirty? false}}}}}
        effects (actions/remove-portfolio-optimizer-universe-instrument state "perp:NEW")
        path-values (get-in (vec effects) [0 1])]
    (is (some (fn [[path value]]
                (and (= history-assumptions-path path)
                     (= {} value)))
              path-values)
        "Removing an instrument also drops its stale history assumption.")))
