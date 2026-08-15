(ns hyperopen.portfolio.optimizer.application.view-model-history-assumption-recommendation-test
  "Card view-model coverage for backend default-assumption recommendations
  (split from view-model-history-assumption-cards-test to stay under its
  size cap)."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.application.view-model :as view-model]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(def ^:private btc-instrument
  {:instrument-id "perp:BTC"
   :market-type :perp
   :coin "BTC"
   :symbol "BTC-USDC"
   :name "Bitcoin"})

(def ^:private new-perp-instrument
  {:instrument-id "perp:NEW"
   :market-type :perp
   :coin "NEW"})

(def ^:private recommendation-discovery
  {:backend-id-by-local-id {"perp:NEW" "hl:perp:NEW"
                            "perp:BTC" "hl:perp:BTC"}
   :instruments-by-backend-id
   {"hl:perp:NEW" {:instrument-id "hl:perp:NEW"
                   :aliases {:hyperopen-market-key "perp:NEW"}
                   :history {:status :available}
                   :default-assumption
                   {:approach "proxy"
                    :members [{:instrument-id "hl:perp:BTC" :role "anchor"}
                              {:instrument-id "external:tiingo:SMH"
                               :role "sector_peer"}]
                    :relationship-strength "medium"
                    :rationale "BTC anchors the basket."}}
    "hl:perp:BTC" {:instrument-id "hl:perp:BTC"
                   :aliases {:hyperopen-market-key "perp:BTC"}
                   :display-symbol "BTC"
                   :history {:status :available}}
    "external:tiingo:SMH" {:instrument-id "external:tiingo:SMH"
                           :instrument-kind :external-proxy
                           :display-symbol "SMH"
                           :basket-member-only true
                           :history {:status :missing}}}})

(def ^:private universe [btc-instrument new-perp-instrument])

(def ^:private readiness
  {:request {:requested-universe universe
             :universe universe
             :objective {:kind :minimum-variance}
             :history {:eligible-instruments universe
                       :raw-price-series-by-instrument
                       {"perp:BTC" (vec (repeat 1079 {:close 1}))
                        "perp:NEW" (vec (repeat 40 {:close 1}))}}}
   :blocking-warnings []})

(def ^:private load-state
  {:status :succeeded :request-signature {:universe universe}})

(deftest history-assumption-cards-surface-backend-recommendations-test
  ;; A mode-less card carries the server's default-assumption recommendation:
  ;; the suggested approach, the members split by whether the backend can
  ;; serve their history today, and the one-click apply action. Choosing a
  ;; mode by hand dismisses it — an authored entry always wins.
  (let [state (assoc-in {} contracts/history-discovery-path
                        recommendation-discovery)
        draft {:universe universe
               :objective {:kind :minimum-variance}
               :history-assumptions {}}
        model (view-model/history-assumption-cards
               state draft readiness load-state {})
        rec (:recommendation (first (:cards model)))]
    (is (= 1 (:recommended-count model)))
    (is (= :actions/apply-portfolio-optimizer-recommended-history-assumptions
           (get-in model [:recommended-actions :apply-all])))
    (is (= :proxy (:approach rec)))
    (is (= "Model on similar assets" (:approach-label rec)))
    (is (true? (:applicable? rec)))
    (is (= [["perp:BTC" "BTC" true] ["external:tiingo:SMH" "SMH" false]]
           (mapv (juxt :instrument-id :label :available?) (:members rec)))
        "Servable members lead; the data-less member renders dimmed.")
    (is (= 1 (:held-count rec)))
    (is (= "Medium" (:relationship-label rec)))
    (is (= "BTC anchors the basket." (:rationale rec)))
    (is (= :actions/apply-portfolio-optimizer-recommended-history-assumption
           (get-in rec [:actions :apply-one])))
    (let [configured (assoc draft :history-assumptions
                            {"perp:NEW" {:behavior :conservative
                                         :expected-return 0.0
                                         :volatility 0.8
                                         :max-weight 0.03
                                         :correlation-floor 0.75}})
          model* (view-model/history-assumption-cards
                  state configured readiness load-state {})]
      (is (nil? (:recommendation (first (:cards model*))))
          "A card with a chosen mode never re-offers the recommendation.")
      (is (zero? (:recommended-count model*))))))

(deftest history-assumption-rail-model-passes-recommendation-aggregates-through-test
  ;; The right rail renders its own "Apply all recommended (N)" shortcut, so the
  ;; rail model must carry the SAME aggregates the center banner keys off —
  ;; passed through from the cards model, never recomputed.
  (let [state (assoc-in {} contracts/history-discovery-path
                        recommendation-discovery)
        draft {:universe universe
               :objective {:kind :minimum-variance}
               :history-assumptions {}}
        rail (view-model/history-assumption-rail-model
              state draft readiness load-state {})]
    (is (= 1 (:recommended-count rail)))
    (is (= :actions/apply-portfolio-optimizer-recommended-history-assumptions
           (get-in rail [:recommended-actions :apply-all])))
    (let [configured (assoc draft :history-assumptions
                            {"perp:NEW" {:behavior :conservative
                                         :expected-return 0.0
                                         :volatility 0.8
                                         :max-weight 0.03
                                         :correlation-floor 0.75}})
          rail* (view-model/history-assumption-rail-model
                 state configured readiness load-state {})]
      (is (zero? (:recommended-count rail*))
          "A configured asset stops counting toward the rail shortcut."))))

(deftest history-assumption-cards-recommendation-unapplicable-when-all-members-held-test
  ;; When the backend cannot serve ANY recommended member yet, the card still
  ;; explains the recommendation but one-click apply is withheld — applying it
  ;; would park the card in a permanently blocked state.
  (let [discovery (assoc-in recommendation-discovery
                            [:instruments-by-backend-id "hl:perp:NEW"
                             :default-assumption :members]
                            [{:instrument-id "external:tiingo:SMH"
                              :role "sector_peer"}])
        state (assoc-in {} contracts/history-discovery-path discovery)
        model (view-model/history-assumption-cards
               state
               {:universe universe
                :objective {:kind :minimum-variance}
                :history-assumptions {}}
               readiness load-state {})
        rec (:recommendation (first (:cards model)))]
    (is (some? rec) "The recommendation still explains itself...")
    (is (false? (:applicable? rec)) "...but cannot apply.")
    (is (zero? (:recommended-count model))
        "An unapplicable recommendation never counts toward the bulk banner.")))
