(ns hyperopen.vol-range.a0-boundary-test
  "VR-24: Release A0 cannot construct a signable action, claim a signing counter
  or write an intent, whatever the flag combination."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.config :as config]
            [hyperopen.order.actions :as order-actions]
            [hyperopen.runtime.collaborators.vol-range :as vol-range-collaborators]
            [hyperopen.state.trading :as trading]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.state-fixtures :as sf]))

(def now sf/now-ms)

(def allowed-effects #{:effects/save :effects/save-many :effects/fetch-candle-snapshot})

(defn- ready-state [] (sf/base-state))

(defn- crossing-state []
  (assoc-in (sf/base-state) [:orderbooks "@107"] {:bids [{:px "75.89"}] :asks [{:px "75.91"}]}))

(defn- blank-state []
  (-> (sf/base-state)
      (assoc-in [:order-form :scale :vol-range :inventory :maximum] "")
      (assoc-in [:order-form :scale :vol-range :buy :max-usdc-debit] "")))

(defn- legacy-state []
  (-> (sf/base-state)
      (assoc-in [:order-form :scale] {:start "70" :end "75" :count 5 :skew "1.00"})
      (update :vol-range dissoc :draft-binding)))

(defn- perp-state []
  (assoc (sf/base-state) :active-market {:coin "BTC" :market-type :perp :base "BTC" :quote "USDC"
                                         :szDecimals 5}))

(defn- no-wallet-state [] (assoc (sf/base-state) :wallet {}))

(def fixture-states
  [["ready" ready-state] ["crossing" crossing-state] ["blank-limits" blank-state]
   ["legacy" legacy-state] ["perp" perp-state] ["no-wallet" no-wallet-state]])

(defn- calls
  "Every handler with representative arguments (valid and invalid)."
  [state]
  (let [h (vol-range-collaborators/action-deps)
        call (fn [k & args] [k (apply (get h k) state args)])]
    [(call :select-scale-mode :volatility-range)
     (call :select-scale-mode :price-range)
     (call :select-scale-mode "garbage")
     (call :update-vol-range-draft [:buy :quantity] "41")
     (call :update-vol-range-draft [:inventory :maximum] "190")
     (call :update-vol-range-draft [:anchor :mode] "market-price")
     (call :update-vol-range-draft [:not :allowed] "x")
     (call :set-vol-range-sell-quantity-percent 25)
     (call :set-vol-range-sell-quantity-percent 100)
     (call :apply-vol-range-remediation :set-maximum-all-buys)
     (call :apply-vol-range-remediation :use-plan-total)
     (call :apply-vol-range-remediation :anchor-to-market)
     (call :apply-vol-range-remediation :start-buys-at)
     (call :apply-vol-range-remediation :nonsense)
     (call :open-vol-range-review)
     (call :close-vol-range-review)
     (call :set-vol-range-ui-flag :show-bands? false)
     (call :set-vol-range-ui-flag :advanced-open? true)
     (call :refresh-vol-range-daily-candles)]))

(defn- effect-ids [effects] (mapv first effects))

(defn- walk-keywords [x]
  (cond (keyword? x) [x]
        (map? x) (mapcat walk-keywords (concat (keys x) (vals x)))
        (coll? x) (mapcat walk-keywords x)
        :else []))

(def forbidden-effect-ids
  #{:effects/api-submit-order :effects/confirm-api-submit-order :effects/api-cancel-order
    :effects/api-submit-position-tpsl :effects/api-submit-position-margin})

(defn- check-flag-combination! [label config-override]
  (with-redefs [config/config (config-override config/config)
                snapshots/fee-snapshot sf/with-synthetic-fees]
    (doseq [[state-label make-state] fixture-states
            [handler effects] (calls (make-state))]
      (let [context (str label " / " state-label " / " handler)]
        (is (vector? effects) context)
        (is (every? allowed-effects (effect-ids effects))
            (str context " emitted " (pr-str (effect-ids effects))))
        (is (not-any? forbidden-effect-ids (walk-keywords effects)) context)))))

(deftest vol-range-handlers-only-emit-state-and-candle-effects-test
  (check-flag-combination! "preview on" identity)
  (check-flag-combination! "preview off" #(assoc-in % [:vol-range :preview-enabled?] false))
  (check-flag-combination! "no vol-range config" #(dissoc % :vol-range))
  (check-flag-combination! "execution flag injected"
                           #(-> % (assoc :execution-enabled? true)
                                (assoc-in [:vol-range :execution-enabled?] true)
                                (assoc-in [:vol-range :preview-enabled?] true)))
  (check-flag-combination! "execution flag injected, preview off"
                           #(-> % (assoc :execution-enabled? true)
                                (assoc-in [:vol-range :execution-enabled?] true)
                                (assoc-in [:vol-range :preview-enabled?] false))))

(defn- submit-state []
  (-> (sf/base-state)
      (assoc-in [:wallet :agent :status] :ready)
      (assoc-in [:order-form :size] "10")
      (assoc-in [:order-form :side] :buy)
      (assoc-in [:order-form :scale] {:mode :volatility-range :start "70" :end "75" :count 5 :skew "1.00"
                                      :vol-range (get-in (sf/base-state) [:order-form :scale :vol-range])})))

(deftest ordinary-submit-never-builds-a-request-in-volatility-range-test
  (let [state (submit-state)
        form (trading/order-form-draft state)]
    (testing "the shared submit policy refuses first in both modes"
      (let [submit (trading/submit-policy state form {:mode :submit :agent-ready? true})
            view (trading/submit-policy state form {:mode :view})]
        (is (= :vol-range-preview-only (:reason submit)))
        (is (nil? (:request submit)))
        (is (true? (:disabled? submit)))
        (is (= "Placement isn't available yet." (:error-message submit)))
        (is (= :vol-range-preview-only (:reason view)))
        (is (nil? (:request view)))))
    (testing "submit-order emits no order effect"
      (let [effects (order-actions/submit-order state)]
        (is (not-any? #{:effects/api-submit-order :effects/confirm-api-submit-order} (effect-ids effects)))
        (is (= [[:effects/save [:order-form-runtime :error] "Placement isn't available yet."]] effects))))
    (testing "an unlocked or locked agent still builds nothing"
      (doseq [status [:locked :unlocking :ready nil]]
        (let [s (assoc-in state [:wallet :agent :status] status)
              effects (order-actions/submit-order s)]
          (is (not-any? forbidden-effect-ids (walk-keywords effects)) (str status))
          (is (not-any? #{:effects/api-submit-order :effects/confirm-api-submit-order}
                        (effect-ids effects))))))
    (testing "stale ordinary Scale values do not matter"
      (let [s (assoc-in state [:order-form :scale :start] "1")
            effects (order-actions/submit-order s)]
        (is (not-any? #{:effects/api-submit-order} (effect-ids effects)))))
    (testing "Price range mode still reaches the ordinary policy"
      (let [s (assoc-in state [:order-form :scale :mode] :price-range)
            policy (trading/submit-policy s (trading/order-form-draft s) {:mode :submit :agent-ready? true})]
        (is (not= :vol-range-preview-only (:reason policy)))))))

;; ---------------------------------------------------------------------------
;; Static scan
;; ---------------------------------------------------------------------------

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- list-files [dir]
  (if (.existsSync fs dir)
    (vec (mapcat (fn [entry]
                   (let [p (.join path dir entry)]
                     (if (.isDirectory (.statSync fs p))
                       (list-files p)
                       [p])))
                 (array-seq (.readdirSync fs dir))))
    []))

(def ^:private forbidden-substrings
  ["hyperopen.api.trading" "hyperopen.order.effects" "hyperopen.utils.hl-signing"
   "hyperopen.api.gateway.orders" "hyperopen.platform.indexed-db" "api-submit-order" "nonce"])

(def ^:private execution-foundations
  "Release A execution code (journal, entropy, raw reads, wire mapping, the
  order lifecycle and its executor). It may touch storage, nonces and
  signing; the A0 preview must never require it."
  #{"src/hyperopen/vol_range/infrastructure/journal.cljs"
    "src/hyperopen/vol_range/infrastructure/entropy.cljs"
    "src/hyperopen/vol_range/infrastructure/reads.cljs"
    "src/hyperopen/vol_range/infrastructure/executor.cljs"
    "src/hyperopen/vol_range/infrastructure/executor_place.cljs"
    "src/hyperopen/vol_range/infrastructure/executor_cancel.cljs"
    "src/hyperopen/vol_range/application/journal_records.cljs"
    "src/hyperopen/vol_range/application/lifecycle.cljs"
    "src/hyperopen/vol_range/application/lifecycle_evidence.cljs"
    "src/hyperopen/vol_range/application/lifecycle_recovery.cljs"
    "src/hyperopen/vol_range/application/live_evidence.cljs"
    "src/hyperopen/vol_range/application/grid_store.cljs"
    "src/hyperopen/vol_range/infrastructure/live_ingress.cljs"
    "src/hyperopen/vol_range/application/fill_records.cljs"
    "src/hyperopen/vol_range/application/revalidation.cljs"
    "src/hyperopen/vol_range/application/repair_plan.cljs"
    "src/hyperopen/vol_range/application/repair_model.cljs"
    "src/hyperopen/vol_range/application/replace_flow.cljs"
    "src/hyperopen/vol_range/application/replace_model.cljs"
    "src/hyperopen/vol_range/infrastructure/executor_replace.cljs"
    "src/hyperopen/vol_range/application/wire.cljs"
    "src/hyperopen/vol_range/application/dock_model.cljs"
    "src/hyperopen/vol_range/execution_actions.cljs"
    "src/hyperopen/vol_range/repair_actions.cljs"
    "src/hyperopen/vol_range/replace_actions.cljs"
    "src/hyperopen/vol_range/infrastructure/execution_gate.cljs"
    "src/hyperopen/vol_range/infrastructure/execution_watcher.cljs"
    "src/hyperopen/vol_range/application/campaign.cljs"})

(def ^:private execution-foundation-namespaces
  ["hyperopen.vol-range.infrastructure.journal" "hyperopen.vol-range.infrastructure.entropy"
   "hyperopen.vol-range.infrastructure.reads" "hyperopen.vol-range.application.journal-records"
   "hyperopen.vol-range.infrastructure.executor" "hyperopen.vol-range.application.lifecycle"
   "hyperopen.vol-range.application.revalidation" "hyperopen.vol-range.application.wire"
   "hyperopen.vol-range.application.live-evidence"
   "hyperopen.vol-range.application.grid-store"
   "hyperopen.vol-range.infrastructure.live-ingress"
   "hyperopen.vol-range.application.fill-records"
   "hyperopen.vol-range.application.replace-flow"
   "hyperopen.vol-range.application.campaign"
   "hyperopen.api.trading.delivery" "hyperopen.api.trading.nonce-coordinator"
   "hyperopen.api.trading.action-coordinator"])

(deftest vol-range-sources-reference-no-order-signing-or-storage-test
  (let [trading-dir "src/hyperopen/trading"
        files (-> []
                  (into (remove execution-foundations
                                (filter #(str/ends-with? % ".cljs") (list-files "src/hyperopen/vol_range"))))
                  (into (filter #(str/starts-with? (.basename path %) "vol_range_")
                                (list-files trading-dir)))
                  (into (filter #(str/ends-with? % ".cljs") (list-files "src/hyperopen/views/trade/vol_range")))
                  (into ["src/hyperopen/runtime/collaborators/vol_range.cljs"
                         "src/hyperopen/schema/vol_range_contracts.cljs"
                         "src/hyperopen/schema/contracts/vol_range_action_args.cljs"]))]
    (testing "the scan is not vacuous"
      (is (>= (count files) 20))
      (is (some #(str/ends-with? % "vol_range/actions.cljs") files))
      (is (some #(str/ends-with? % "trading/vol_range_transitions.cljs") files))
      (is (some #(str/ends-with? % "views/trade/vol_range/commands.cljs") files)))
    (doseq [file files
            :let [text (.readFileSync fs file "utf8")]
            needle forbidden-substrings]
      (is (not (str/includes? text needle)) (str file " mentions " needle)))))

(deftest preview-sources-never-require-execution-foundations-test
  ;; VR-24 stays true while stage 4 foundations exist: nothing on an A0
  ;; preview path can reach a journal write, a nonce reservation or a send.
  (let [files (-> []
                  (into (remove execution-foundations
                                (filter #(str/ends-with? % ".cljs") (list-files "src/hyperopen/vol_range"))))
                  (into (filter #(str/ends-with? % ".cljs") (list-files "src/hyperopen/views/trade/vol_range")))
                  (into (filter #(str/starts-with? (.basename path %) "vol_range_")
                                (list-files "src/hyperopen/trading"))))]
    (is (some #(str/ends-with? % "trading/vol_range_transitions.cljs") files))
    (is (some #(str/ends-with? % "application/replace_state.cljs") files) "the update predicates stay scanned")
    (is (some #(str/ends-with? % "application/ladder_settings.cljs") files))
    (is (some #(str/ends-with? % "views/trade/vol_range/replace_dialog.cljs") files)
        "the update dialog and banner stay scanned")
    (is (some #(str/ends-with? % "application/review_model.cljs") files)
        "the review model reaches the lineage only through the view boundary")
    (doseq [file files
            :let [text (.readFileSync fs file "utf8")]
            ns-name execution-foundation-namespaces]
      (is (not (str/includes? text ns-name)) (str file " requires " ns-name)))))
