(ns hyperopen.vol-range.registration-test
  "Requiring the contracts and effect-order namespaces makes any registration
  drift (registered vs contracted actions, effect-order policies) throw at load."
  (:require [clojure.set :as set]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.registry.runtime :as runtime-registry]
            [hyperopen.runtime.collaborators :as collaborators]
            [hyperopen.runtime.collaborators.vol-range :as vol-range-collaborators]
            [hyperopen.runtime.effect-order-contract :as effect-order]
            [hyperopen.schema.contracts :as contracts]
            [hyperopen.schema.contracts.action-args :as action-args]
            [hyperopen.schema.order-form-command-catalog :as catalog]
            [hyperopen.vol-range.actions :as actions]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.repair-actions :as repair-actions]
            [hyperopen.vol-range.state-fixtures :as sf]
            [hyperopen.views.trade.order-form-runtime-gateway :as gateway]
            [hyperopen.views.trade.vol-range.commands :as commands]))

(def vol-range-action-ids
  #{:actions/select-scale-mode
    :actions/update-vol-range-draft
    :actions/set-vol-range-sell-quantity-percent
    :actions/apply-vol-range-remediation
    :actions/open-vol-range-review
    :actions/close-vol-range-review
    :actions/handle-vol-range-review-keydown
    :actions/set-vol-range-ui-flag
    :actions/refresh-vol-range-daily-candles})

(def vol-range-command-ids
  #{:order-form/select-scale-mode
    :order-form/update-vol-range-draft
    :order-form/set-vol-range-sell-quantity-percent
    :order-form/apply-vol-range-remediation
    :order-form/open-vol-range-review
    :order-form/close-vol-range-review
    :order-form/handle-vol-range-review-keydown
    :order-form/set-vol-range-ui-flag
    :order-form/refresh-vol-range-daily-candles})

(deftest actions-are-registered-contracted-and-catalogued-test
  (is (set/subset? vol-range-action-ids (runtime-registry/registered-action-ids)))
  (is (set/subset? vol-range-action-ids (contracts/contracted-action-ids)))
  (is (set/subset? vol-range-action-ids (catalog/catalog-action-ids)))
  (is (set/subset? vol-range-command-ids (catalog/supported-command-ids)))
  (is (every? #(contains? action-args/action-args-spec-by-id %) vol-range-action-ids))
  (testing "catalog rows pair each command with its action and handler"
    (let [rows (filter #(contains? vol-range-action-ids (:action-id %)) (catalog/catalog-entries))]
      (is (= vol-range-command-ids (set (map :command-id rows))))
      (is (= (set (keys (vol-range-collaborators/action-deps))) (set (map :handler-key rows))))))
  (testing "the :vol-range collaborator map is part of the runtime action deps"
    (let [deps (collaborators/runtime-action-deps {})]
      (is (= (set (keys (vol-range-collaborators/action-deps))) (set (keys (:vol-range deps)))))
      (is (every? fn? (vals (:vol-range deps)))))))

(deftest effect-order-policies-test
  (is (effect-order/covered-action? :actions/select-scale-mode))
  (is (effect-order/covered-action? :actions/refresh-vol-range-daily-candles))
  (doseq [action-id [:actions/select-scale-mode :actions/refresh-vol-range-daily-candles]]
    (is (= {:required-phase-order [:projection :heavy-io]
            :require-projection-before-heavy? true
            :allow-duplicate-heavy-effects? false
            :heavy-effect-ids #{:effects/fetch-candle-snapshot}}
           (effect-order/action-policy action-id)))
    (let [fetch [:effects/fetch-candle-snapshot :coin "@107" :interval :1d :bars 16]]
      (is (some? (effect-order/assert-action-effect-order!
                  action-id [[:effects/save-many [[[:vol-range :ui :show-bands?] true]]] fetch] {})))
      (is (thrown? js/Error (effect-order/assert-action-effect-order! action-id [fetch] {})))
      (is (thrown? js/Error (effect-order/assert-action-effect-order!
                             action-id [fetch [:effects/save-many []]] {}))))))

(deftest action-arg-specs-test
  (let [ok! (fn [action-id args] (is (some? (contracts/assert-action-args! action-id args {})) (pr-str [action-id args])))
        bad! (fn [action-id args] (is (thrown? js/Error (contracts/assert-action-args! action-id args {}))
                                      (pr-str [action-id args])))]
    (doseq [mode [:price-range :volatility-range "price-range" "volatility-range"]]
      (ok! :actions/select-scale-mode [mode]))
    (bad! :actions/select-scale-mode [:other])
    (bad! :actions/select-scale-mode [])
    (ok! :actions/update-vol-range-draft [[:buy :quantity] "40"])
    (ok! :actions/update-vol-range-draft [[:buy :enabled?] true])
    (ok! :actions/update-vol-range-draft [[:buy :count] [:event.target/value]])
    (bad! :actions/update-vol-range-draft [["buy" "quantity"] "40"])
    (bad! :actions/update-vol-range-draft [[] "40"])
    (bad! :actions/update-vol-range-draft [[:buy :quantity]])
    (doseq [pct [25 50 100 "25" "50" "100"]]
      (ok! :actions/set-vol-range-sell-quantity-percent [pct]))
    (bad! :actions/set-vol-range-sell-quantity-percent [30])
    (ok! :actions/apply-vol-range-remediation [:use-plan-total])
    (ok! :actions/apply-vol-range-remediation ["set-maximum-all-buys"])
    (bad! :actions/apply-vol-range-remediation [42])
    (ok! :actions/open-vol-range-review [])
    (bad! :actions/open-vol-range-review [1])
    (ok! :actions/close-vol-range-review [])
    (ok! :actions/refresh-vol-range-daily-candles [])
    (bad! :actions/refresh-vol-range-daily-candles [1])
    (ok! :actions/set-vol-range-ui-flag [:show-bands? true])
    (ok! :actions/set-vol-range-ui-flag [:advanced-open? false])
    (bad! :actions/set-vol-range-ui-flag [:review-open? true])
    (bad! :actions/set-vol-range-ui-flag [:show-bands? "yes"])))

(deftest command-constructors-resolve-to-registered-actions-test
  (let [gw (gateway/default-gateway)
        runtime (fn [command] (gateway/command->runtime-actions gw command))]
    (is (= [[:actions/select-scale-mode :volatility-range]] (runtime (commands/select-volatility-range))))
    (is (= [[:actions/select-scale-mode :price-range]] (runtime (commands/select-price-range))))
    (is (= [[:actions/update-vol-range-draft [:buy :quantity] [:event.target/value]]]
           (runtime (commands/update-draft-from-input [:buy :quantity]))))
    (is (= [[:actions/update-vol-range-draft [:sell :enabled?] [:event.target/checked]]]
           (runtime (commands/update-draft-from-checkbox [:sell :enabled?]))))
    (is (= [[:actions/set-vol-range-sell-quantity-percent 50]] (runtime (commands/set-sell-quantity-percent 50))))
    (is (= [[:actions/apply-vol-range-remediation :use-plan-total]]
           (runtime (commands/apply-remediation :use-plan-total))))
    (is (= [[:actions/open-vol-range-review]] (runtime (commands/open-review))))
    (is (= [[:actions/close-vol-range-review]] (runtime (commands/close-review))))
    (is (= [[:actions/set-vol-range-ui-flag :show-bands? false]] (runtime (commands/set-ui-flag :show-bands? false))))
    (is (= [[:actions/refresh-vol-range-daily-candles]] (runtime (commands/refresh-daily-candles))))
    (testing "every constructor arg list satisfies the action arg contract"
      (doseq [command [(commands/select-volatility-range) (commands/set-sell-quantity-percent 25)
                       (commands/apply-remediation :anchor-to-market) (commands/open-review)
                       (commands/set-ui-flag :advanced-open? true)]
              :let [[[action-id & args]] (runtime command)]]
        (is (some? (contracts/assert-action-args! action-id (vec args) {})))))))

(deftest emitted-effects-satisfy-effect-contracts-test
  (with-redefs [snapshots/fee-snapshot sf/with-synthetic-fees]
    (let [legacy (-> (sf/base-state)
                     (assoc-in [:order-form :scale] {:start "" :end "" :count 5 :skew "1.00"})
                     (update :vol-range dissoc :draft-binding))
          crossing (assoc-in (sf/base-state) [:orderbooks "@107"] {:bids [{:px "75.89"}] :asks [{:px "75.91"}]})
          now sf/now-ms]
      (doseq [[action-id effects]
              [[:actions/select-scale-mode (actions/select-scale-mode legacy :volatility-range now)]
               [:actions/select-scale-mode (actions/select-scale-mode (sf/base-state) :price-range now)]
               [:actions/update-vol-range-draft (actions/update-vol-range-draft (sf/base-state) [:buy :quantity] "41")]
               [:actions/update-vol-range-draft (actions/update-vol-range-draft legacy [:buy :quantity] "41")]
               [:actions/apply-vol-range-remediation (actions/apply-vol-range-remediation crossing :start-buys-at now)]
               [:actions/open-vol-range-review (actions/open-vol-range-review (sf/base-state) now)]
               [:actions/close-vol-range-review (actions/close-vol-range-review (sf/base-state))]
               [:actions/set-vol-range-ui-flag (actions/set-vol-range-ui-flag (sf/base-state) :show-bands? false)]
               [:actions/refresh-vol-range-daily-candles (actions/refresh-vol-range-daily-candles (sf/base-state) now)]]]
        (is (seq effects) (str action-id))
        (is (some? (contracts/assert-emitted-effects! effects {:action-id action-id})) (str action-id))
        (when (effect-order/covered-action? action-id)
          (is (some? (effect-order/assert-action-effect-order! action-id effects {}))))))))

;; ---------------------------------------------------------------------------
;; Release A repair (stage R3)
;; ---------------------------------------------------------------------------

(def repair-handlers
  {:actions/open-vol-ladder-repair :open-vol-ladder-repair
   :actions/refresh-vol-ladder-repair :refresh-vol-ladder-repair
   :actions/toggle-vol-ladder-repair-leg :toggle-vol-ladder-repair-leg
   :actions/close-vol-ladder-repair :close-vol-ladder-repair
   :actions/handle-vol-ladder-repair-keydown :handle-vol-ladder-repair-keydown
   :actions/submit-vol-ladder-repair :submit-vol-ladder-repair})

(deftest repair-actions-are-registered-and-contracted-test
  (let [ids (set (keys repair-handlers))]
    (is (set/subset? ids (runtime-registry/registered-action-ids)))
    (is (set/subset? ids (contracts/contracted-action-ids)))
    (is (every? #(contains? action-args/action-args-spec-by-id %) ids)))
  (testing "handlers are in the Release A group, never in the A0 catalog"
    (let [deps (collaborators/runtime-action-deps {})]
      (is (every? #(fn? (get-in deps [:vol-range-execution %])) (vals repair-handlers)))
      (is (not-any? #(contains? (vol-range-collaborators/action-deps) %) (vals repair-handlers)))
      (is (not-any? #(contains? (set (map :action-id (catalog/catalog-entries))) %) (keys repair-handlers))))))

(deftest repair-action-arg-specs-test
  (let [ok! (fn [action-id args] (is (some? (contracts/assert-action-args! action-id args {})) (pr-str [action-id args])))
        bad! (fn [action-id args] (is (thrown? js/Error (contracts/assert-action-args! action-id args {}))
                                      (pr-str [action-id args])))]
    (ok! :actions/open-vol-ladder-repair ["grid-1"])
    (bad! :actions/open-vol-ladder-repair [])
    (bad! :actions/open-vol-ladder-repair [""])
    (ok! :actions/refresh-vol-ladder-repair [])
    (bad! :actions/refresh-vol-ladder-repair ["grid-1"])
    (ok! :actions/toggle-vol-ladder-repair-leg ["buy-0"])
    (bad! :actions/toggle-vol-ladder-repair-leg [:buy-0])
    (bad! :actions/toggle-vol-ladder-repair-leg [])
    (ok! :actions/close-vol-ladder-repair [])
    (ok! :actions/handle-vol-ladder-repair-keydown ["Escape"])
    (bad! :actions/handle-vol-ladder-repair-keydown [])
    (ok! :actions/submit-vol-ladder-repair [])
    (bad! :actions/submit-vol-ladder-repair [{:legs []}])))

(deftest repair-effects-satisfy-effect-contracts-test
  (doseq [effects [[[:effects/save-many [[[:vol-range :repair] {:grid-id "g1" :status :loading}]]]
                    [:effects/vol-range-execute :repair-preview {:grid-id "g1"}]]
                   [[:effects/save-many [[[:vol-range :repair :selected] #{"buy-0"}]]]]
                   [[:effects/vol-range-execute :repair {:grid-id "g1" :proposal-id "rp-1"
                                                         :legs [{:logical-id "buy-0" :price "76.16" :size "7"}]}]]
                   (repair-actions/close-vol-ladder-repair {})
                   (repair-actions/handle-vol-ladder-repair-keydown {} "Escape")]]
    (is (some? (contracts/assert-emitted-effects! effects {:action-id :actions/open-vol-ladder-repair}))
        (pr-str effects)))
  (testing "the operation set grows only by the repair ops and the live-monitoring resync"
    (doseq [op [:place :cancel :refresh :sync :reconcile-open :repair-preview :repair :resync]]
      (is (some? (contracts/assert-effect-args! :effects/vol-range-execute [op {}] {})) (str op)))
    (doseq [op [:replace :replace-cancel :replace-confirm :nonsense]]
      (is (thrown? js/Error (contracts/assert-effect-args! :effects/vol-range-execute [op {}] {})) (str op)))))

;; ---------------------------------------------------------------------------
;; Live monitoring resync (live-fills stage 6)
;; ---------------------------------------------------------------------------

(deftest resync-action-is-registered-and-contracted-test
  (is (contains? (runtime-registry/registered-action-ids) :actions/resync-vol-ladders))
  (is (contains? (contracts/contracted-action-ids) :actions/resync-vol-ladders))
  (is (some? (contracts/assert-action-args! :actions/resync-vol-ladders [] {})))
  (is (thrown? js/Error (contracts/assert-action-args! :actions/resync-vol-ladders [3] {})))
  (testing "the handler is in the Release A group, never in the A0 catalog"
    (let [deps (collaborators/runtime-action-deps {})]
      (is (fn? (get-in deps [:vol-range-execution :resync-vol-ladders])))
      (is (not (contains? (vol-range-collaborators/action-deps) :resync-vol-ladders)))
      (is (not (contains? (set (map :action-id (catalog/catalog-entries))) :actions/resync-vol-ladders)))))
  (is (some? (contracts/assert-emitted-effects! [[:effects/vol-range-execute :resync {:epoch 3}]]
                                                {:action-id :actions/resync-vol-ladders}))))
