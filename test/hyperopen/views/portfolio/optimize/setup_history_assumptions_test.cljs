(ns hyperopen.views.portfolio.optimize.setup-history-assumptions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio.optimize.setup-history-assumptions :as setup-history-assumptions]
            [hyperopen.views.portfolio.optimize.test-support :as ts]))

(def ^:private btc {:instrument-id "perp:BTC" :market-type :perp :coin "BTC"})
(def ^:private new-perp {:instrument-id "perp:NEW" :market-type :perp :coin "NEW"})

(defn- section
  [assumption blocking-warnings]
  (setup-history-assumptions/history-assumptions-section
   {:state {}
    :draft {:universe [btc new-perp]
            :objective {:kind :minimum-variance}
            :history-assumptions {"perp:NEW" assumption}}
    :readiness {:request {:requested-universe [btc new-perp]
                          :universe [btc]
                          :objective {:kind :minimum-variance}}
                :blocking-warnings blocking-warnings}
    ;; history has loaded (it requested perp:NEW), so the no-history card shows.
    :history-load-state {:status :succeeded
                         :request-signature {:universe [btc new-perp]}}}))

(deftest history-assumptions-section-renders-active-question-and-dispatches-actions-test
  (let [node (section {:behavior :conservative
                       :expected-return nil
                       :volatility nil
                       :max-weight 0.03
                       :correlation-floor 0.75}
                      [{:code :history-assumption-incomplete
                        :instrument-id "perp:NEW"
                        :missing :volatility
                        :message "NEW needs a modeled annual volatility."}])
        card (ts/node-by-role node "portfolio-optimizer-history-assumption-card-perp:NEW")
        volatility-input (ts/node-by-role node "portfolio-optimizer-history-assumption-volatility-perp:NEW")
        cap-input (ts/node-by-role node "portfolio-optimizer-history-assumption-max-weight-perp:NEW")
        clear-button (ts/node-by-role node "portfolio-optimizer-history-assumption-clear-perp:NEW")]
    (is (some? (ts/node-by-role node "portfolio-optimizer-history-assumptions-section")))
    (is (some? (ts/node-by-role node "portfolio-optimizer-history-assumptions-queue")))
    (is (some? card))
    (is (some #{"What does NEW behave like?"} (ts/collect-strings card))
        "The queue asks one question about the asset it is on.")
    (is (= [:actions/set-portfolio-optimizer-history-assumption-expected-volatility
            "perp:NEW" [:event.target/value]]
           (first (ts/input-actions volatility-input))))
    (is (= [:actions/set-portfolio-optimizer-history-assumption-max-weight-cap
            "perp:NEW" [:event.target/value]]
           (first (ts/input-actions cap-input))))
    (is (= [:actions/clear-portfolio-optimizer-history-assumption "perp:NEW"]
           (first (ts/click-actions clear-button))))
    (is (some #{"NEW needs a modeled annual volatility."}
              (ts/collect-strings card))
        "Field-level errors are surfaced on the active question.")))

(deftest history-assumptions-section-manual-controls-are-opt-in-test
  ;; Recommendation first: the hand-editing surface sits behind one disclosure
  ;; that is never :open from state, so a computed open can never re-assert
  ;; itself against the user's own toggle.
  (let [node (section {:behavior :conservative :max-weight 0.03} [])
        adjust (ts/node-by-role node "portfolio-optimizer-history-assumption-adjust-perp:NEW")]
    (is (some? adjust))
    (is (= :details (first adjust)))
    (is (nil? (ts/node-attr adjust :open)))
    (is (some #{"Adjust by hand"} (ts/collect-strings adjust)))))

(deftest history-assumptions-section-unconfigured-asset-offers-both-modes-test
  ;; A thin-history asset with no entry yet offers both behaviors as mode tabs;
  ;; it stays excluded until the user picks one.
  (let [node (setup-history-assumptions/history-assumptions-section
              {:state {}
               :draft {:universe [btc new-perp]
                       :objective {:kind :minimum-variance}
                       :history-assumptions {}}
               :readiness {:request {:requested-universe [btc new-perp]
                                     :universe [btc]
                                     :objective {:kind :minimum-variance}}
                           :blocking-warnings []}
               :history-load-state {:status :succeeded
                                    :request-signature {:universe [btc new-perp]}}})
        proxy-mode (ts/node-by-role node "portfolio-optimizer-history-assumption-mode-perp:NEW-proxy")
        conservative-mode (ts/node-by-role node "portfolio-optimizer-history-assumption-mode-perp:NEW-conservative")]
    (is (some? proxy-mode) "The model-on-similar-assets mode is offered.")
    (is (some #{"Model on similar assets"} (ts/collect-strings proxy-mode))
        "The proxy mode button names what it does, not 'proxy behavior'.")
    (is (= [:actions/set-portfolio-optimizer-history-assumption-mode "perp:NEW" :proxy]
           (first (ts/click-actions proxy-mode))))
    (is (some? conservative-mode))
    (is (some #{"Assume worst case"} (ts/collect-strings conservative-mode)))
    (is (= [:actions/set-portfolio-optimizer-history-assumption-mode "perp:NEW" :conservative]
           (first (ts/click-actions conservative-mode))))))

(def ^:private eth {:instrument-id "perp:ETH" :market-type :perp :coin "ETH"})

(def ^:private proxy-entry
  {:behavior :proxy
   :expected-return 0.0
   :volatility 0.8
   :max-weight 0.05
   :proxy {:instrument-ids ["perp:BTC"]
           :relationship-strength :medium
           :prior-weights nil}})

(defn- proxy-section
  [objective-kind]
  (setup-history-assumptions/history-assumptions-section
   {:state {}
    :draft {:universe [btc eth new-perp]
            :objective {:kind objective-kind}
            :constraints {:max-asset-weight 0.5}
            :history-assumptions {"perp:NEW" proxy-entry}}
    :readiness {:request {:requested-universe [btc eth new-perp]
                          :universe [btc eth]
                          :objective {:kind objective-kind}
                          :history {:eligible-instruments [btc eth]}}
                :blocking-warnings []}
    :history-load-state {:status :succeeded
                         :request-signature {:universe [btc eth new-perp]}}}))

(deftest history-assumptions-section-proxy-question-renders-workflow-controls-test
  (let [node (proxy-section :minimum-variance)
        remove-chip (ts/node-by-role node "portfolio-optimizer-history-assumption-proxy-remove-perp:NEW-perp:BTC")
        search-input (ts/node-by-role node "portfolio-optimizer-history-assumption-proxy-search-perp:NEW")
        relationship-high (ts/node-by-role node "portfolio-optimizer-history-assumption-relationship-perp:NEW-high")
        guardrails (ts/node-by-role node "portfolio-optimizer-history-assumption-guardrails-perp:NEW")
        volatility-input (ts/node-by-role node "portfolio-optimizer-history-assumption-volatility-perp:NEW")
        cap-input (ts/node-by-role node "portfolio-optimizer-history-assumption-max-weight-perp:NEW")
        apply-button (ts/node-by-role node "portfolio-optimizer-history-assumption-apply-perp:NEW")
        reset-button (ts/node-by-role node "portfolio-optimizer-history-assumption-reset-perp:NEW")
        status (ts/node-by-role node "portfolio-optimizer-history-assumption-status-perp:NEW")]
    (is (some? (ts/node-by-role node "portfolio-optimizer-history-assumptions-section")))
    (is (some? remove-chip))
    (is (= [:actions/set-portfolio-optimizer-history-assumption-proxy-asset
            "perp:NEW" "perp:BTC" false]
           (first (ts/click-actions remove-chip)))
        "Chip x removes the proxy.")
    (is (some? search-input) "The basket picker is a catalog search input.")
    (is (= [:actions/set-portfolio-optimizer-history-assumption-proxy-search
            "perp:NEW" [:event.target/value]]
           (first (ts/input-actions search-input)))
        "Typing updates the per-asset proxy search query.")
    (is (some? relationship-high))
    (is (= [:actions/set-portfolio-optimizer-history-assumption-relationship-strength
            "perp:NEW" :high]
           (first (ts/click-actions relationship-high))))
    (is (some? guardrails) "Volatility + cap live in a guardrails drawer.")
    (is (= :details (first guardrails))
        "The guardrails are a collapsed disclosure, not primary inputs.")
    (is (nil? (ts/node-attr guardrails :open))
        "The drawer starts collapsed and is never forced open from state.")
    (is (some #{"80% vol · 5% max"} (ts/collect-strings guardrails))
        "Collapsed, the drawer summarizes the auto-set values.")
    (is (some #{"Auto-set"} (ts/collect-strings guardrails))
        "Seed values are labeled auto-set.")
    (is (some #{"Modeled annual volatility"} (ts/collect-strings guardrails))
        "The volatility input names the model's use, not a user forecast.")
    (is (some #{"Max allocation cap"} (ts/collect-strings guardrails)))
    (is (= [:actions/set-portfolio-optimizer-history-assumption-expected-volatility
            "perp:NEW" [:event.target/value]]
           (first (ts/input-actions volatility-input)))
        "The volatility input still commits edits from inside the drawer.")
    (is (= [:actions/set-portfolio-optimizer-history-assumption-max-weight-cap
            "perp:NEW" [:event.target/value]]
           (first (ts/input-actions cap-input))))
    (is (= [:actions/apply-portfolio-optimizer-history-assumption "perp:NEW"]
           (first (ts/click-actions apply-button))))
    (is (= [:actions/reset-portfolio-optimizer-history-assumption "perp:NEW"]
           (first (ts/click-actions reset-button))))
    (is (some #{"Ready to accept"} (ts/collect-strings status))
        "Engine-backed but unaccepted names the one thing still owed.")
    (is (= "configured" (ts/node-attr status :data-status))
        "The raw engine-backing status rides on untouched for the pinned contract.")))

(deftest history-assumptions-section-folds-every-diagnostic-behind-one-toggle-test
  ;; The exposure story (prior -> regression -> final basket -> confidence ->
  ;; specific risk -> window) used to be five always-open panels. It is now one
  ;; disclosure whose HEAD still carries the answer, so opening is a choice to
  ;; see the workings, never the only way to read the model.
  (let [node (proxy-section :minimum-variance)
        detail (ts/node-by-role node "portfolio-optimizer-history-assumption-model-detail")
        line (ts/node-by-role node "portfolio-optimizer-history-assumption-model-line")
        strings (ts/collect-strings detail)]
    (is (some? detail))
    (is (= :details (first detail)))
    (is (nil? (ts/node-attr detail :open))
        "The detail block is DOM state, never :open from app state.")
    (is (= ["BTC 100% · medium similarity · 80% vol · 5% max"]
           (ts/collect-strings line))
        "The head reads the final modeled basket, never the prior dressed up as it.")
    (is (some #{"Equal-weight fallback - BTC 100%"} strings)
        "The equal prior is labeled a fallback.")
    (is (some #{"No return overlap with the proxies yet. Using the prior only."} strings)
        "Without overlap the regression row says so instead of faking weights.")
    (is (some #{"BTC 100% after confidence shrinkage (q 0%)"} strings))
    (is (some #{"Low - R² sets confidence, not weights"} strings))
    (is (some #{"High - risk the basket cannot explain"} strings))
    (is (some #(and (string? %)
                    (re-find #"Confidence decides how far the regression can move the prior" %))
              strings))))

(deftest history-assumptions-section-proxy-search-results-click-adds-and-clears-test
  ;; Full-catalog typeahead: a matching catalog asset (SOL, not in the universe)
  ;; shows as a result; clicking it adds the proxy and clears the search buffer.
  (let [sol {:key "perp:SOL" :market-type :perp :coin "SOL" :symbol "SOL-USDC" :volume24h 999}
        node (setup-history-assumptions/history-assumptions-section
              {:state {:asset-selector {:markets [sol]}
                       :portfolio-ui {:optimizer {:proxy-search-queries {"perp:NEW" "SOL"}}}}
               :draft {:universe [btc eth new-perp]
                       :objective {:kind :minimum-variance}
                       :constraints {:max-asset-weight 0.5}
                       :history-assumptions {"perp:NEW" proxy-entry}}
               :readiness {:request {:requested-universe [btc eth new-perp]
                                     :universe [btc eth]
                                     :objective {:kind :minimum-variance}
                                     :history {:eligible-instruments [btc eth]}}
                           :blocking-warnings []}
               :history-load-state {:status :succeeded
                                    :request-signature {:universe [btc eth new-perp]}}})
        option (ts/node-by-role node "portfolio-optimizer-history-assumption-proxy-option-perp:NEW-perp:SOL")]
    (is (some? option) "The out-of-universe catalog match (SOL) is a selectable result.")
    (is (= [[:actions/set-portfolio-optimizer-history-assumption-proxy-asset
             "perp:NEW" "perp:SOL" true]
            [:actions/set-portfolio-optimizer-history-assumption-proxy-search "perp:NEW" ""]]
           (ts/click-actions option))
        "Clicking adds the proxy, then clears the search buffer.")))

(deftest history-assumptions-section-proxy-return-input-only-for-return-seeking-test
  (is (nil? (ts/node-by-role (proxy-section :minimum-variance)
                             "portfolio-optimizer-history-assumption-return-perp:NEW"))
      "Minimum variance does not ask for an expected return.")
  (is (some? (ts/node-by-role (proxy-section :max-sharpe)
                              "portfolio-optimizer-history-assumption-return-perp:NEW"))
      "Return-seeking objectives do."))

(deftest history-assumptions-section-offers-manual-entry-when-no-cards-test
  ;; Even when every selected asset has adequate history the workflow stays
  ;; reachable: the user may judge an asset statistically unsound on their own
  ;; (user feedback 2026-07-05 - "how would I factor load SOPH?") and start it
  ;; by hand. Choosing an asset from the dropdown seeds proxy mode.
  (let [node (setup-history-assumptions/history-assumptions-section
              {:draft {:universe [btc eth]
                       :objective {:kind :minimum-variance}
                       :history-assumptions {}}
               :readiness {:request {:requested-universe [btc eth]
                                     :universe [btc eth]
                                     :objective {:kind :minimum-variance}}
                           :blocking-warnings []}})
        add-select (ts/node-by-role node "portfolio-optimizer-history-assumption-workflow-add")]
    (is (some? (ts/node-by-role node "portfolio-optimizer-history-assumptions-section"))
        "The section renders in compact form so the manual entry point exists.")
    (is (some? (ts/node-by-role node "portfolio-optimizer-history-assumptions-empty")))
    (is (nil? (ts/node-by-role node "portfolio-optimizer-history-assumptions-queue"))
        "An empty queue renders its note, not an empty rail and footer.")
    (is (some? add-select))
    (is (= [:actions/set-portfolio-optimizer-history-assumption-mode
            [:event.target/value] :proxy]
           (first (ts/change-actions add-select)))
        "Choosing an asset seeds proxy mode for it.")
    (is (= ["" "perp:BTC" "perp:ETH"]
           (mapv #(get-in % [1 :value]) (subvec add-select 2)))
        "Every selected asset is offered (placeholder first), as real option siblings.")
    (is (nil? (ts/node-by-role node "portfolio-optimizer-history-assumptions-count"))
        "The workflow count is hidden while no asset is in the queue.")))

(deftest history-assumptions-section-add-dropdown-excludes-carded-assets-test
  (let [node (proxy-section :minimum-variance)
        add-select (ts/node-by-role node "portfolio-optimizer-history-assumption-workflow-add")]
    (is (some? add-select))
    (is (= ["" "perp:BTC" "perp:ETH"]
           (mapv #(get-in % [1 :value]) (subvec add-select 2)))
        "perp:NEW is already in the queue, so only the remaining assets are addable.")))

(deftest history-assumptions-section-add-dropdown-shows-day-counts-ascending-test
  ;; The dropdown ranks assets by native return-day count ASCENDING with the
  ;; count in the label, so the user can proxy out the most limiting assets
  ;; (the ones capping the shared covariance window) first instead of blind.
  (let [node (setup-history-assumptions/history-assumptions-section
              {:state {}
               :draft {:universe [btc eth]
                       :objective {:kind :minimum-variance}
                       :history-assumptions {}}
               :readiness {:request {:requested-universe [btc eth]
                                     :universe [btc eth]
                                     :objective {:kind :minimum-variance}
                                     :history {:eligible-instruments [btc eth]
                                               :raw-price-series-by-instrument
                                               {"perp:BTC" (vec (repeat 1079 {:close 1}))
                                                "perp:ETH" (vec (repeat 403 {:close 1}))}}}
                           :blocking-warnings []}
               :history-load-state {:status :succeeded
                                    :request-signature {:universe [btc eth]}}})
        add-select (ts/node-by-role node "portfolio-optimizer-history-assumption-workflow-add")
        options (subvec add-select 2)]
    (is (= ["" "perp:ETH" "perp:BTC"]
           (mapv #(get-in % [1 :value]) options))
        "Fewest days of returns first (ETH 403 < BTC 1079), not universe order.")
    (is (= ["ETH (403 days)" "BTC (1079 days)"]
           (mapv #(nth % 2) (rest options)))
        "Each option shows the asset's day count in parentheses.")))

(def ^:private acknowledged-proxy-entry
  (assoc proxy-entry :metadata {:source :user :acknowledged? true}))

(defn- acknowledged-section
  [state]
  (setup-history-assumptions/history-assumptions-section
   {:state state
    :draft {:universe [btc eth new-perp]
            :objective {:kind :minimum-variance}
            :constraints {:max-asset-weight 0.5}
            :history-assumptions {"perp:NEW" acknowledged-proxy-entry}}
    :readiness {:request {:requested-universe [btc eth new-perp]
                          :universe [btc eth]
                          :objective {:kind :minimum-variance}
                          :history {:eligible-instruments [btc eth]}}
                :blocking-warnings []}
    :history-load-state {:status :succeeded
                         :request-signature {:universe [btc eth new-perp]}}}))

(deftest history-assumptions-section-settled-asset-reads-as-done-test
  ;; Complete AND acknowledged: the queue owes this asset nothing, so the
  ;; question flips to "change it?" and the CTA stops asking for a decision.
  (let [node (acknowledged-section {})
        card (ts/node-by-role node "portfolio-optimizer-history-assumption-card-perp:NEW")
        count-label (ts/node-by-role node "portfolio-optimizer-history-assumptions-count")
        settled (ts/node-by-role node "portfolio-optimizer-history-assumptions-settled")
        section-node (ts/node-by-role node "portfolio-optimizer-history-assumptions-section")]
    (is (some #{"NEW is modeled - change it?"} (ts/collect-strings card)))
    (is (some #{"Keep this"} (ts/collect-strings card)))
    (is (some #{"Current model"} (ts/collect-strings card)))
    (is (some #{"Accepted"} (ts/collect-strings card))
        "Green and \"Accepted\" are reserved for a decision the user made.")
    (is (= ["1 of 1 settled"] (ts/collect-strings count-label)))
    (is (= ["1 of 1 settled"] (ts/collect-strings settled)))
    (is (nil? (ts/node-attr section-node :open))
        "Nothing needs the user, so the section rests closed.")))

(deftest history-assumptions-section-rail-selects-without-settling-test
  ;; The rail is the queue's orientation device: every asset always visible, one
  ;; click to move, and moving settles nothing.
  (let [other {:instrument-id "perp:OTHER" :market-type :perp :coin "OTHER"}
        node (setup-history-assumptions/history-assumptions-section
              {:state {:portfolio-ui {:optimizer {:history-assumption-active "perp:OTHER"}}}
               :draft {:universe [btc new-perp other]
                       :objective {:kind :minimum-variance}
                       :history-assumptions {}}
               :readiness {:request {:requested-universe [btc new-perp other]
                                     :universe [btc]
                                     :objective {:kind :minimum-variance}}
                           :blocking-warnings []}
               :history-load-state {:status :succeeded
                                    :request-signature {:universe [btc new-perp other]}}})
        pill (ts/node-by-role node "portfolio-optimizer-history-assumption-pill-perp:NEW")
        active-pill (ts/node-by-role node "portfolio-optimizer-history-assumption-pill-perp:OTHER")
        card (ts/node-by-role node "portfolio-optimizer-history-assumption-card-perp:OTHER")
        prev-button (ts/node-by-role node "portfolio-optimizer-history-assumptions-queue-prev")
        skip-button (ts/node-by-role node "portfolio-optimizer-history-assumptions-queue-skip")]
    (is (some? pill))
    (is (= [:actions/set-portfolio-optimizer-history-assumption-active "perp:NEW"]
           (first (ts/click-actions pill)))
        "A rail pill only moves the queue.")
    (is (= "true" (ts/node-attr active-pill :aria-pressed))
        "The requested asset is the one the queue is on.")
    (is (some? card) "The queue asks about the requested asset, not the first one.")
    (is (= [:actions/set-portfolio-optimizer-history-assumption-active "perp:NEW"]
           (first (ts/click-actions prev-button))))
    (is (= [:actions/set-portfolio-optimizer-history-assumption-active "perp:NEW"]
           (first (ts/click-actions skip-button)))
        "Skip advances without accepting anything.")))

(deftest history-assumptions-section-advance-accepts-then-moves-on-test
  ;; "Accept & next" carries the model panel's OWN accept action so the two can
  ;; never disagree about what accepting means, then hops to the next asset.
  (let [other {:instrument-id "perp:OTHER" :market-type :perp :coin "OTHER"}
        node (setup-history-assumptions/history-assumptions-section
              {:state {}
               :draft {:universe [btc eth new-perp other]
                       :objective {:kind :minimum-variance}
                       :constraints {:max-asset-weight 0.5}
                       :history-assumptions {"perp:NEW" proxy-entry}}
               :readiness {:request {:requested-universe [btc eth new-perp other]
                                     :universe [btc eth]
                                     :objective {:kind :minimum-variance}
                                     :history {:eligible-instruments [btc eth]}}
                           :blocking-warnings []}
               :history-load-state {:status :succeeded
                                    :request-signature {:universe [btc eth new-perp other]}}})
        advance (ts/node-by-role node "portfolio-optimizer-history-assumptions-queue-advance")]
    (is (some? advance))
    (is (= [[:actions/apply-portfolio-optimizer-history-assumption "perp:NEW"]
            [:actions/set-portfolio-optimizer-history-assumption-active "perp:OTHER"]]
           (ts/click-actions advance)))))

(defn- loading-proxy-section
  "Same proxy asset, but rendered while its history fetch is still in flight
  (aggregate load :loading + a non-idle prefetch queue)."
  []
  (setup-history-assumptions/history-assumptions-section
   {:state {:optimizer {:history-prefetch
                        {:queue []
                         :active-instrument-id "perp:BTC"
                         :by-instrument-id {"perp:BTC" {:status :loading}}}}}
    :draft {:universe [btc eth new-perp]
            :objective {:kind :minimum-variance}
            :constraints {:max-asset-weight 0.5}
            :history-assumptions {"perp:NEW" proxy-entry}}
    :readiness {:request {:requested-universe [btc eth new-perp]
                          :universe []
                          :objective {:kind :minimum-variance}}
                :blocking-warnings []}
    :history-load-state {:status :loading}}))

(deftest history-assumptions-section-surfaces-in-flight-history-loading-test
  (let [node (loading-proxy-section)
        banner (ts/node-by-role node "portfolio-optimizer-history-assumptions-loading-banner")
        status (ts/node-by-role node "portfolio-optimizer-history-assumption-status-perp:NEW")
        apply-button (ts/node-by-role node "portfolio-optimizer-history-assumption-apply-perp:NEW")
        apply-note (ts/node-by-role node "portfolio-optimizer-history-assumption-apply-loading-perp:NEW")
        advance (ts/node-by-role node "portfolio-optimizer-history-assumptions-queue-advance")]
    (is (some? banner) "The section carries an aggregate loading banner.")
    (is (some #(and (string? %) (re-find #"Loading proxy history for 1 asset" %))
              (ts/collect-strings banner)))
    (is (= "true" (ts/node-attr status :data-loading)))
    (is (some #{"Loading history…"} (ts/collect-strings status))
        "The status chip says loading instead of a mid-flight verdict.")
    (is (true? (ts/node-attr apply-button :disabled))
        "Accept is held while history is fetching.")
    (is (true? (ts/node-attr advance :disabled))
        "The footer's Accept is held by the same verdict, never a looser one.")
    (is (some? apply-note) "The hold explains itself as waiting, not broken.")))

(deftest history-assumptions-section-settled-load-shows-no-loading-ui-test
  (let [node (proxy-section :minimum-variance)]
    (is (nil? (ts/node-by-role node "portfolio-optimizer-history-assumptions-loading-banner")))
    (is (nil? (ts/node-attr
               (ts/node-by-role node "portfolio-optimizer-history-assumption-status-perp:NEW")
               :data-loading)))
    (is (nil? (ts/node-by-role node "portfolio-optimizer-history-assumption-apply-loading-perp:NEW")))))
