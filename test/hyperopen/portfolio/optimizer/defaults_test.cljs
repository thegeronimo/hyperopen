(ns hyperopen.portfolio.optimizer.defaults-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.defaults :as defaults]))

(deftest default-draft-preserves-v1-model-layer-contract-test
  (let [draft (defaults/default-draft)]
    (is (= :draft (:status draft)))
    (is (= :minimum-variance (get-in draft [:objective :kind])))
    (is (= :historical-mean (get-in draft [:return-model :kind])))
    (is (= :ledoit-wolf-dense (get-in draft [:risk-model :kind])))
    (is (= false (get-in draft [:metadata :dirty?])))
    (is (= false (get-in draft [:constraints :long-only?])))
    (is (= false (get-in draft [:constraints :include-spot?])))
    (is (= 2.0 (get-in draft [:constraints :gross-max])))
    (is (= 1.0 (get-in draft [:constraints :net-min])))
    (is (= 1.0 (get-in draft [:constraints :net-max])))
    (is (= 0.5 (get-in draft [:constraints :max-asset-weight])))
    (is (= 0.0 (get-in draft [:constraints :dust-usdc])))
    (is (= 0.03 (get-in draft [:constraints :rebalance-tolerance])))
    (is (= 1.0 (get-in draft [:constraints :max-turnover])))
    (is (contains? (:constraints draft) :max-asset-weight))
    (is (contains? (:constraints draft) :gross-max))
    (is (contains? (:constraints draft) :net-min))
    (is (contains? (:constraints draft) :net-max))
    (is (contains? (:constraints draft) :dust-usdc))
    (is (contains? (:constraints draft) :perp-leverage))
    (is (contains? (:constraints draft) :allowlist))
    (is (contains? (:constraints draft) :blocklist))
    (is (contains? (:constraints draft) :max-turnover))
    (is (contains? (:constraints draft) :rebalance-tolerance))
    (is (contains? (:constraints draft) :include-spot?))
    (is (contains? draft :history-assumptions))
    (is (= {} (:history-assumptions draft)))))

(deftest default-history-assumption-seeds-editable-conservative-anchors-test
  (let [conservative (defaults/default-history-assumption :conservative)]
    ;; Conservative is the only behavior, and its volatility/return anchors are
    ;; pre-seeded (editable) so the user can accept or revise them rather than
    ;; starting from blank.
    (is (= {:behavior :conservative
            :expected-return 0.0
            :volatility 0.8
            :max-weight 0.03
            :correlation-floor 0.75}
           conservative))
    (is (= {:behavior :proxy
            :expected-return 0.0
            :volatility 0.8
            :max-weight 0.05
            :proxy {:instrument-ids []
                    :relationship-strength :medium
                    :prior-weights nil}}
           (defaults/default-history-assumption :proxy))
        "The engine-backed :proxy behavior seeds multi-proxy defaults.")
    (is (nil? (defaults/default-history-assumption :unknown)))))

(deftest default-optimizer-state-preserves-run-and-scenario-contract-test
  (is (= {:status :idle
          :run-id nil
          :scenario-id nil
          :request-signature nil
          :started-at-ms nil
          :completed-at-ms nil
          :error nil
          :result nil}
         (:run-state (defaults/default-optimizer-state))))
  (is (= {:status :idle
          :request-signature nil
          :started-at-ms nil
          :completed-at-ms nil
          :error nil
          :warnings []}
         (:history-load-state (defaults/default-optimizer-state))))
  (is (= {:status :idle
          :contract-version nil
          :request-id nil
          :dataset-version nil
          :loaded-at-ms nil
          :instruments-by-backend-id {}
          :backend-id-by-local-id {}
          :warnings []
          :error nil}
         (:history-discovery (defaults/default-optimizer-state))))
  (is (= {:status :idle
          :run-id nil
          :scenario-id nil
          :started-at-ms nil
          :completed-at-ms nil
          :active-step nil
          :overall-percent 0
          :steps []
          :error nil}
         (:optimization-progress (defaults/default-optimizer-state))))
  (is (= {:loaded-id nil
          :status :idle
          :read-only? false}
         (:active-scenario (defaults/default-optimizer-state))))
  (is (= {:ordered-ids []
          :by-id {}}
         (get-in (defaults/default-optimizer-state)
                 [:scenario-index])))
  (is (= {:status :idle
          :scenario-id nil
          :updated-at-ms nil
          :snapshots []
          :error nil}
         (:tracking (defaults/default-optimizer-state)))))

(deftest default-optimizer-ui-state-matches-route-query-defaults-test
  (is (= {:list-filter :active
          :list-sort :updated-desc
          :universe-search-query ""
          :universe-search-active-index 0
          :universe-search-type-filter :all
          :universe-search-quote-filter :all
          :proxy-search-queries {}
          :objective-menu-open? false
          :scenario-menu-open? false
          :objective-menu-selection nil
          :objective-menu-view-order []
          :objective-menu-view-drafts {}
          :black-litterman-editor {:selected-kind :absolute
                                   :drafts {:absolute {:instrument-id nil
                                                       :return-text ""
                                                       :return-text-touched? false
                                                       :confidence :medium
                                                       :horizon :3m
                                                       :notes ""}
                                            :relative {:instrument-id nil
                                                       :comparator-instrument-id nil
                                                       :direction :outperform
                                                       :return-text ""
                                                       :return-text-touched? false
                                                       :confidence :medium
                                                       :horizon :3m
                                                       :notes ""}}
                                   :editing-view-id nil
                                   :errors {}
                                   :clear-confirmation-open? false}
          :workspace-panel :setup
          :results-tab :recommendation
          :diagnostics-tab :conditioning
          :frontier-overlay-mode :standalone
          :constrain-frontier? false
          :refinement-depth nil
          :exposure-zoom-level nil
          :return-views-filter :all
          :stale-auto-recompute {:request-signature nil
                                 :input-signature nil
                                 :scenario-id nil}}
         (defaults/default-optimizer-ui-state))))
