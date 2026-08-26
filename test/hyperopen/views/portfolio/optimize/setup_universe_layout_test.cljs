(ns hyperopen.views.portfolio.optimize.setup-universe-layout-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.universe-candidates :as universe-candidates]
            [hyperopen.views.portfolio.optimize.setup-universe :as setup-universe]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.portfolio.optimize.setup-layout-fixtures :refer [node-children find-first-node collect-strings node-by-role child-roles node-text click-actions input-actions keydown-actions day-start-ms summary-from-points class-token-set count-nodes btc-instrument eth-instrument black-litterman-ready-readiness black-litterman-ready-draft black-litterman-empty-readiness black-litterman-empty-draft candle-rows]]))

(deftest setup-universe-skips-candidate-search-while-query-empty-test
  (let [calls (atom 0)
        view-node (with-redefs [universe-candidates/candidate-markets
                                (fn
                                  ([_state _universe _query]
                                   (swap! calls inc)
                                   [{:key "perp:BTC"
                                     :market-type :perp
                                     :coin "BTC"
                                     :symbol "BTC-USDC"}])
                                  ([_state _universe _query _opts]
                                   (swap! calls inc)
                                   [{:key "perp:BTC"
                                     :market-type :perp
                                     :coin "BTC"
                                     :symbol "BTC-USDC"}]))]
                    (setup-universe/universe-section
                     {:portfolio-ui {:optimizer {:universe-search-query ""}}}
                     {:universe []}))]
    (is (= 0 @calls))
    (is (nil? (node-by-role view-node "portfolio-optimizer-universe-search-results")))))

(deftest setup-universe-shows-holdings-loading-state-while-seed-pending-test
  ;; While readiness reports :holdings-loading the panel must present the wait
  ;; (active "My holdings" segment, loading copy, skeleton rows) instead of the
  ;; empty-universe copy that asks the user to add assets manually.
  (let [view-node (setup-universe/universe-section
                   {:portfolio-ui {:optimizer {:universe-search-query ""}}}
                   {:universe []}
                   {:readiness {:status :blocked
                                :reason :holdings-loading
                                :runnable? false}})
        panel (node-by-role view-node "portfolio-optimizer-universe-panel")
        loading-block (node-by-role view-node "portfolio-optimizer-universe-holdings-loading")
        source-button (node-by-role view-node "portfolio-optimizer-universe-use-current")
        strings (collect-strings view-node)]
    (is (= "true" (get-in panel [1 :data-holdings-loading])))
    (is (some? loading-block))
    (is (some #{"Loading holdings…"} (collect-strings loading-block)))
    (is (some #{"Fetching the current portfolio for this account."}
              (collect-strings loading-block)))
    (is (= "true" (get-in source-button [1 :data-active]))
        "The pending source shows as active, not as a manual command.")
    (is (some #{"My holdings"} (collect-strings source-button)))
    (is (not (some #{"No assets selected yet."} strings)))))

(deftest setup-universe-keeps-empty-state-copy-without-holdings-loading-test
  (let [view-node (setup-universe/universe-section
                   {:portfolio-ui {:optimizer {:universe-search-query ""}}}
                   {:universe []})
        strings (collect-strings view-node)]
    (is (nil? (node-by-role view-node "portfolio-optimizer-universe-holdings-loading")))
    (is (some #{"No assets selected yet."} strings))))

(deftest setup-universe-search-renders-as-single-integrated-control-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio-ui {:optimizer {:universe-search-query "TIA"}}
                    :portfolio {:optimizer {:draft {:universe []
                                                     :constraints {:long-only? false}}}}})
        search-shell (node-by-role view-node "portfolio-optimizer-universe-search-shell")
        search-icon (node-by-role view-node "portfolio-optimizer-universe-search-icon")
        search-input (node-by-role view-node "portfolio-optimizer-universe-search-input")
        clear-button (node-by-role view-node "portfolio-optimizer-universe-search-clear")
        add-hint (node-by-role view-node "portfolio-optimizer-universe-search-add-hint")]
    (is (some? search-shell))
    (is (= "true" (get-in search-shell [1 :data-searching])))
    (is (contains? (class-token-set search-shell)
                   "portfolio-optimizer-universe-search-shell"))
    (is (some? search-icon))
    (is (contains? (class-token-set search-icon)
                   "portfolio-optimizer-universe-search-affordance"))
    (is (contains? (class-token-set search-input)
                   "portfolio-optimizer-universe-search-field"))
    (is (some? clear-button))
    (is (contains? (class-token-set clear-button)
                   "portfolio-optimizer-universe-search-affordance"))
    (is (some? add-hint))
    (is (= ["↵ add"] (collect-strings add-hint)))
    (is (contains? (class-token-set add-hint)
                   "portfolio-optimizer-universe-search-add-hint"))
    (is (not (contains? (class-token-set add-hint) "bg-warning/10")))
    (is (not (contains? (class-token-set add-hint) "bg-base-200")))
    (is (not (contains? (class-token-set add-hint) "uppercase")))
    (is (not (contains? (class-token-set add-hint) "tracking-[0.1em]")))))

(deftest setup-universe-search-renders-vault-candidates-test
  (let [vault-address "0x1111111111111111111111111111111111111111"
        view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio-ui {:optimizer {:universe-search-query "vault"}}
                    :portfolio {:optimizer {:draft {:universe []
                                                     :constraints {:long-only? false}}}}
                    :asset-selector {:markets []}
                    :vaults {:merged-index-rows [{:name "Alpha Yield"
                                                 :vault-address vault-address
                                                 :relationship {:type :normal}
                                                 :tvl 500}]}})
        search-input (node-by-role view-node "portfolio-optimizer-universe-search-input")
        vault-row (node-by-role view-node
                                (str "portfolio-optimizer-universe-candidate-row-vault:"
                                     vault-address))
        add-button (node-by-role view-node
                                 (str "portfolio-optimizer-universe-add-vault:"
                                      vault-address))
        strings (set (collect-strings view-node))]
    (is (= "Search ticker, name, or vault (e.g. TIA, AVAX, Solana, HLP...)"
           (get-in search-input [1 :placeholder])))
    (is (some? vault-row))
    (is (contains? strings "Alpha Yield"))
    ;; The per-row type tag was replaced by a sticky group header: with a full,
    ;; grouped result list the type is stated once per section instead of
    ;; repeated on every row of a narrow rail.
    (is (some? (node-by-role
                view-node
                "portfolio-optimizer-universe-candidate-group-header-vault")))
    (is (contains? strings "Vaults"))
    (is (= [[:actions/add-portfolio-optimizer-universe-instrument
             (str "vault:" vault-address)]]
           (click-actions vault-row)))
    (is (= [[:actions/add-portfolio-optimizer-universe-instrument
             (str "vault:" vault-address)]]
           (click-actions add-button)))))

(deftest setup-universe-search-candidates-do-not-render-history-status-chip-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio-ui {:optimizer {:universe-search-query "btc"}}
                    :portfolio {:optimizer {:draft {:universe []
                                                     :constraints {:long-only? false}}}}
                    :asset-selector
                    {:markets [{:key "perp:BTC"
                                :market-type :perp
                                :coin "BTC"
                                :symbol "BTC-USDC"
                                :base "BTC"
                                :quote "USDC"
                                :volume24h 961000000}]}})
        candidate-row (node-by-role view-node
                                    "portfolio-optimizer-universe-candidate-row-perp:BTC")
        candidate-strings (set (collect-strings candidate-row))]
    (is (some? candidate-row))
    (is (not (contains? candidate-strings "pending")))
    (is (not (contains? candidate-strings "unchecked")))
    (is (not (contains? candidate-strings "sufficient")))
    (is (= [[:actions/add-portfolio-optimizer-universe-instrument "perp:BTC"]]
           (click-actions candidate-row)))))

(deftest setup-selected-universe-history-statuses-reflect-readiness-test
  (let [base-state {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [btc-instrument]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true}}}}}
        selected-row (fn [state]
                       (-> (portfolio-view/portfolio-view state)
                           (node-by-role "portfolio-optimizer-universe-selected-row-perp:BTC")))
        row-status (fn [state] (get-in (selected-row state) [1 :data-history-status]))
        row-strings (fn [state] (set (collect-strings (selected-row state))))
        queued-state (assoc-in base-state
                               [:portfolio :optimizer :history-prefetch
                                :by-instrument-id "perp:BTC"]
                               {:status :queued
                                :started-at-ms nil
                                :completed-at-ms nil
                                :error nil
                                :warnings []})
        loading-state (assoc-in base-state
                                [:portfolio :optimizer :history-load-state]
                                {:status :loading
                                 :request-signature {:universe [btc-instrument]}})
        missing-state (assoc-in base-state
                                [:portfolio :optimizer :history-load-state]
                                {:status :succeeded
                                 :request-signature {:universe [btc-instrument]}})
        insufficient-state (-> base-state
                               (assoc-in [:portfolio :optimizer :history-load-state]
                                         {:status :succeeded
                                          :request-signature {:universe [btc-instrument]}})
                               (assoc-in [:portfolio :optimizer :history-data
                                          :candle-history-by-coin "BTC"]
                                         (candle-rows [[1000 100]])))
        sufficient-state (-> base-state
                             (assoc-in [:portfolio :optimizer :history-load-state]
                                       {:status :succeeded
                                        :request-signature {:universe [btc-instrument]}})
                             (assoc-in [:portfolio :optimizer :history-data
                                        :candle-history-by-coin "BTC"]
                                       (candle-rows [[1000 100]
                                                     [2000 101]])))]
    ;; The row no longer paints per-status chips — those diagnostics moved to the
    ;; grouped Readiness panel. The computed status still rides on the row's
    ;; :data-history-status attribute (a QA/telemetry hook), so the status
    ;; mapping stays asserted here.
    (is (= "pending" (row-status base-state)))
    (is (= "queued" (row-status queued-state)))
    (is (= "loading" (row-status loading-state)))
    (is (= "missing" (row-status missing-state)))
    (is (= "insufficient" (row-status insufficient-state)))
    (is (contains? #{"sufficient" "stale"} (row-status sufficient-state)))
    ;; And none of the solver-readiness jargon is rendered on the row itself.
    (doseq [state [base-state queued-state loading-state missing-state
                   insufficient-state sufficient-state]]
      (let [strings (row-strings state)]
        (doseq [word ["pending" "queued" "loading" "missing" "insufficient"
                      "sufficient" "shared gap" "Short history"]]
          (is (not (contains? strings word))
              (str "The universe row must not paint the '" word "' diagnostic chip.")))))))

(deftest setup-selected-universe-row-renders-position-side-controls-test
  (let [spot-instrument {:instrument-id "spot:PURR"
                         :market-type :spot
                         :coin "PURR"
                         :symbol "PURR/USDC"
                         :shortable? false
                         :position-side :long}
        view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [(assoc btc-instrument
                                                           :shortable? true
                                                           :position-side :long)
                                                    (assoc eth-instrument
                                                           :shortable? true
                                                           :position-side :short)
                                                    spot-instrument]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? false}}}}})
        btc-long (node-by-role view-node
                               "portfolio-optimizer-universe-side-long-perp:BTC")
        btc-short (node-by-role view-node
                                "portfolio-optimizer-universe-side-short-perp:BTC")
        eth-short (node-by-role view-node
                                "portfolio-optimizer-universe-side-short-perp:ETH")
        spot-short (node-by-role view-node
                                 "portfolio-optimizer-universe-side-short-spot:PURR")]
    (is (some? btc-long))
    (is (some? btc-short))
    (is (some? eth-short))
    (is (= "true" (get-in btc-long [1 :data-selected])))
    (is (= "true" (get-in eth-short [1 :data-selected])))
    (is (contains? (class-token-set btc-long) "bg-success/70"))
    (is (not (contains? (class-token-set btc-long) "bg-error/70")))
    (is (contains? (class-token-set eth-short) "bg-error/70"))
    (is (not (contains? (class-token-set eth-short) "bg-success/70")))
    (is (= [[:actions/set-portfolio-optimizer-universe-instrument-side
             "perp:BTC"
             :short]]
           (click-actions btc-short)))
    (is (= true (get-in spot-short [1 :disabled])))
    (is (nil? (click-actions spot-short)))))

(deftest setup-selected-universe-remove-label-uses-visible-identity-test
  (let [spot-instrument {:instrument-id "spot:PURR"
                         :market-type :spot
                         :coin "PURR"
                         :symbol "PURR/USDC"
                         :shortable? false
                         :position-side :long}
        view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [spot-instrument]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? false}}}}})
        remove-button (node-by-role view-node
                                    "portfolio-optimizer-universe-remove-spot:PURR")]
    (is (= "Remove PURR/USDC spot"
           (get-in remove-button [1 :aria-label])))
    (is (= [[:actions/remove-portfolio-optimizer-universe-instrument
             "spot:PURR"]]
           (click-actions remove-button)))))

(deftest setup-selected-vault-row-reflects-shared-gap-status-without-a-chip-test
  (let [vault-a "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        vault-b "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        vault-a-id (str "vault:" vault-a)
        vault-b-id (str "vault:" vault-b)
        a0 (day-start-ms "2026-04-01")
        a1 (day-start-ms "2026-04-02")
        b0 (day-start-ms "2026-04-10")
        b1 (day-start-ms "2026-04-11")
        view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id vault-a-id
                                                     :market-type :vault
                                                     :coin vault-a-id
                                                     :vault-address vault-a
                                                     :name "Vault A"}
                                                    {:instrument-id vault-b-id
                                                     :market-type :vault
                                                     :coin vault-b-id
                                                     :vault-address vault-b
                                                     :name "Vault B"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true}}
                                 :runtime {:as-of-ms (+ b1 (* 24 60 60 1000))
                                           :stale-after-ms (* 2 24 60 60 1000)}
                                 :history-data
                                 {:vault-details-by-address
                                  {vault-a {:portfolio
                                            {:month (summary-from-points [[a0 100 0]
                                                                         [a1 101 1]])}}
                                   vault-b {:portfolio
                                            {:month (summary-from-points [[b0 100 0]
                                                                         [b1 101 1]])}}}}}}})
        row (node-by-role view-node
                          (str "portfolio-optimizer-universe-selected-row-" vault-a-id))
        row-text (node-text row)]
    ;; The misaligned-history status is still computed and exposed on the row's
    ;; :data-history-status attribute; it is no longer painted as a "shared gap"
    ;; chip — that detail now lives in the grouped Readiness panel.
    (is (= "shared-gap" (get-in row [1 :data-history-status])))
    (is (not (str/includes? row-text "shared gap")))))

(deftest setup-universe-search-skips-blank-lookups-but-renders-nonblank-candidates-test
  (let [vault-address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        candidate-key (str "vault:" vault-address)
        calls (atom [])
        stub-candidates [{:key candidate-key
                          :market-type :vault
                          :coin candidate-key
                          :vault-address vault-address
                          :name "Latency Probe"
                          :symbol "Latency Probe"
                          :tvl 42}]
        candidate-markets-stub (fn
                                 ([_state _universe query]
                                  (swap! calls conj query)
                                  stub-candidates)
                                 ([_state _universe query _opts]
                                  (swap! calls conj query)
                                  stub-candidates))
        blank-view (with-redefs [universe-candidates/candidate-markets
                                 candidate-markets-stub]
                    (setup-universe/universe-section
                     {:portfolio-ui {:optimizer {:universe-search-query "   "}}}
                     {:universe []}))
        blank-input (node-by-role blank-view "portfolio-optimizer-universe-search-input")
        nonblank-view (with-redefs [universe-candidates/candidate-markets
                                    candidate-markets-stub]
                       (setup-universe/universe-section
                        {:portfolio-ui {:optimizer {:universe-search-query "vault"}}}
                        {:universe []}))
        nonblank-input (node-by-role nonblank-view "portfolio-optimizer-universe-search-input")]
    (is (= ["vault"] @calls))
    (is (nil? (node-by-role blank-view "portfolio-optimizer-universe-search-results")))
    (is (nil? (node-by-role blank-view "portfolio-optimizer-universe-search-results-empty")))
    (is (nil? (node-by-role blank-view
                            (str "portfolio-optimizer-universe-candidate-row-"
                                 candidate-key))))
    (is (nil? (get-in blank-input [1 :aria-activedescendant])))
    (is (= [[:actions/handle-portfolio-optimizer-universe-search-keydown
             [:event/key]
             []]]
           (keydown-actions blank-input)))
    (is (some? (node-by-role nonblank-view "portfolio-optimizer-universe-search-results")))
    (is (some? (node-by-role nonblank-view
                             (str "portfolio-optimizer-universe-candidate-row-"
                                  candidate-key))))
    (is (= [[:actions/handle-portfolio-optimizer-universe-search-keydown
             [:event/key]
             [candidate-key]]]
           (keydown-actions nonblank-input)))
    (is (= [[:actions/add-portfolio-optimizer-universe-instrument candidate-key]]
           (click-actions
            (node-by-role nonblank-view
                          (str "portfolio-optimizer-universe-add-" candidate-key)))))))
