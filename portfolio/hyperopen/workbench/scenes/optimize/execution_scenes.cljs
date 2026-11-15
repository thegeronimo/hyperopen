(ns hyperopen.workbench.scenes.optimize.execution-scenes
  "Workbench scenes for the Execution tab (views.portfolio.optimize.execution-tab).
  Each scene seeds the app-state shape execution-tab-model reads — the staged plan at
  [:portfolio :optimizer :execution-modal] plus the run-state at
  [:portfolio :optimizer :execution] — so every phase band (staged / armed / running /
  done / halted / read-only / empty) can be eyeballed in isolation inside the
  .portfolio-optimizer scope."
  (:require [portfolio.replicant :as portfolio]
            [hyperopen.workbench.support.layout :as layout]
            [hyperopen.views.portfolio.optimize.execution-tab :as execution-tab]))

(portfolio/configure-scenes
  {:title "Execution"
   :collection :optimize})

(def ^:private labels
  {"perp:BTC" "BTC" "spot:SOL" "SOL" "perp:ETH" "ETH" "perp:HYPE" "HYPE" "perp:ARB" "ARB"
   "perp:EWZ" "EWZ" "perp:REZ" "REZ"})

(def ^:private plan-rows
  [{:row-id "perp:BTC" :instrument-id "perp:BTC" :instrument-type :perp :side :sell
    :quantity 8.0 :order-type :market :delta-notional-usd -174720 :status :ready
    :cost {:source :snapshot :age-ms 3000 :depth-status :full-visible-depth
           :slippage-bps 5.2 :estimated-slippage-usd 91
           :spread-bps 3.5 :spread-usd 61 :impact-bps 1.7 :impact-usd 30
           :fee-bps 4.5 :estimated-fee-usd 52 :maker-fee-bps 1.5 :maker-fee-usd 17}}
   {:row-id "spot:SOL" :instrument-id "spot:SOL" :instrument-type :spot :side :buy
    :quantity 410.0 :order-type :market :delta-notional-usd 78395 :status :ready
    :cost {:source :snapshot :age-ms 1000 :depth-status :full-visible-depth
           :slippage-bps 4.1 :estimated-slippage-usd 32
           :spread-bps 2.6 :spread-usd 20 :impact-bps 1.5 :impact-usd 12
           :fee-bps 4.5 :estimated-fee-usd 23 :maker-fee-bps 1.5 :maker-fee-usd 8}}
   {:row-id "perp:ETH" :instrument-id "perp:ETH" :instrument-type :perp :side :buy
    :quantity 21.5 :order-type :market :delta-notional-usd 76452 :status :ready
    :cost {:source :orderbook :age-ms 500 :depth-status :insufficient-visible-depth
           :slippage-bps 9.1 :estimated-slippage-usd 70
           :spread-bps 3.0 :spread-usd 23 :impact-bps 6.1 :impact-usd 47
           :fee-bps 4.5 :estimated-fee-usd 30 :maker-fee-bps 1.5 :maker-fee-usd 10}}
   {:row-id "perp:HYPE" :instrument-id "perp:HYPE" :instrument-type :perp :side :buy
    :quantity 35.0 :order-type :market :delta-notional-usd 21871 :status :ready
    :cost {:source :snapshot :age-ms 2000 :depth-status :full-visible-depth
           :slippage-bps 6.0 :estimated-slippage-usd 13
           :spread-bps 3.5 :spread-usd 8 :impact-bps 2.5 :impact-usd 5
           :fee-bps 4.5 :estimated-fee-usd 11 :maker-fee-bps 1.5 :maker-fee-usd 4}}
   {:row-id "perp:EWZ" :instrument-id "perp:EWZ" :instrument-type :perp :side :buy
    :quantity 2.47 :order-type :market :delta-notional-usd 87 :status :ready
    :cost {:source :snapshot :age-ms 4000 :depth-status :insufficient-visible-depth
           :slippage-bps 45.4 :estimated-slippage-usd 0.4
           :spread-bps 40.1 :spread-usd 0.35 :impact-bps 5.3 :impact-usd 0.05
           :fee-bps 4.5 :estimated-fee-usd 0.04 :maker-fee-bps 1.5 :maker-fee-usd 0.01}}
   {:row-id "perp:ARB" :instrument-id "perp:ARB" :instrument-type :perp :side :buy
    :quantity 4.2 :order-type :market :delta-notional-usd 8 :status :blocked
    :reason :below-min-notional}
   {:row-id "perp:REZ" :instrument-id "perp:REZ" :instrument-type :perp :side :sell
    :quantity 2680.5 :order-type :market :delta-notional-usd -8.4 :status :skipped
    :reason :within-tolerance :tolerance 0.03}])

(def ^:private summary
  {:ready-count 5 :blocked-count 1 :skipped-count 1
   :gross-ready-notional-usd 351525 :estimated-fees-usd 116 :estimated-slippage-usd 206
   :margin {:after-utilization 0.42 :after-used-usd 482000
            :capital-usd 1200000 :free-margin-usd 718000
            :before-gross-leverage 1.58 :after-gross-leverage 1.64
            :warning :none}})

(defn- plan
  ([] (plan nil))
  ([{:keys [disabled-message margin]}]
   {:scenario-id "sce_demo"
    :status :partially-blocked
    :execution-disabled? (boolean disabled-message)
    :disabled-reason (when disabled-message :read-only)
    :disabled-message disabled-message
    :summary (cond-> summary margin (assoc :margin margin))
    :rows plan-rows}))

(def ^:private ready-rows (vec (filter #(= :ready (:status %)) plan-rows)))

(defn- modal
  [overrides]
  (merge {:open? true :plan (plan) :submitting? false :error nil
          :phase :staged :default-order-type :recommended
          :overrides {} :params {} :open-row nil}
         overrides))

(defn- state
  [modal-state exec-state]
  {:portfolio {:optimizer {:last-successful-run {:result {:labels-by-instrument labels}}
                           :execution-modal modal-state
                           :execution exec-state}}})

(defn- ledger
  [status rows]
  {:attempt-id "exec_demo" :scenario-id "sce_demo" :status status :rows rows})

(defn- shell
  [content]
  (layout/page-shell
   (layout/desktop-shell
    [:div {:class ["portfolio-optimizer" "w-full" "p-6"]}
     content])))

(portfolio/defscene staged
  []
  (shell (execution-tab/execution-tab
          (state (modal {:overrides {"perp:ETH" :limit} :open-row "perp:BTC"})
                 {:status :idle :history []}))))

(portfolio/defscene staged-market-all-high-cost
  []
  (shell (execution-tab/execution-tab
          (state (modal {:default-order-type :market})
                 {:status :idle :history []}))))

(portfolio/defscene armed
  []
  (shell (execution-tab/execution-tab
          (state (modal {:phase :armed}) {:status :idle :history []}))))

(portfolio/defscene running
  []
  (shell (execution-tab/execution-tab
          (state (modal {:submitting? true}) {:status :submitting :history []}))))

(portfolio/defscene done
  []
  (let [realized {"perp:BTC" 4.8 "spot:SOL" 3.6 "perp:ETH" 11.2 "perp:HYPE" 5.5}
        rows (mapv (fn [row]
                     (let [bps (realized (:row-id row))]
                       (cond-> (assoc row :status :submitted)
                         bps (assoc :realized {:slippage-bps bps
                                               :slippage-usd (* (js/Math.abs (:delta-notional-usd row))
                                                                (/ bps 10000))}))))
                   ready-rows)]
    (shell (execution-tab/execution-tab
            (state (modal {}) {:status :executed :history [(ledger :executed rows)]})))))

(portfolio/defscene halted
  []
  (let [rows [(assoc (nth plan-rows 0) :status :submitted)
              (assoc (nth plan-rows 1) :status :submitted)
              (assoc (nth plan-rows 2) :status :failed :reason :exchange-error
                     :error {:message "Order submit failed: exchange down"})
              (assoc (nth plan-rows 3) :status :blocked :reason :missing-capital-base)]]
    (shell (execution-tab/execution-tab
            (state (modal {:error "perp:ETH rejected — insufficient cross-margin buffer."})
                   {:status :partially-executed :history [(ledger :partially-executed rows)]})))))

(portfolio/defscene read-only
  []
  (shell (execution-tab/execution-tab
          (state (modal {:plan (plan {:disabled-message
                                      "Spectate Mode is read-only. Stop Spectate Mode to place trades or move funds."})})
                 {:status :idle :history []}))))

(portfolio/defscene empty-state
  []
  (shell (execution-tab/execution-tab
          (state (modal {:plan nil}) {:status :idle :history []}))))
