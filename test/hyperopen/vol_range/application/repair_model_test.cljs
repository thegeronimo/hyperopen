(ns hyperopen.vol-range.application.repair-model-test
  "The rejected-order review model, the dock's repair control and the repair
  live-region texts (HO-SPEC-014 sections 8.6, 11.2, 11.4, 13.4). 13.4
  partial deployment: the 76.4 buy rejected, BBO 76.00/76.35 -> 76.16 x 7."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.schema.vol-range-repair-replace-contracts :as contracts]
            [hyperopen.vol-range.application.ladder-settings :as ladder-settings]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.application.repair-model :as rm]
            [hyperopen.vol-range.application.repair-plan :as repair-plan]
            [hyperopen.vol-range.fixtures :as fixtures]))

(def addr "0x1234567890abcdef1234567890abcdef12345678")
(def scope {:network :hyperliquid-mainnet :account-address addr :coin "@9001" :asset-id 10001
            :spot-pair-index 1 :symbol "VRT" :quote-symbol "USDC" :sz-decimals 2})
(def plan (planner/prepare-plan (fixtures/plan-input)))
(def rejection "Post only order would have immediately matched, bbo was 76.39@76.41. asset=10001")
(defn- cloid [i] (str "0x" (.padStart (str i) 32 "0")))

(defn- partial-grid
  ([] (partial-grid {}))
  ([{:keys [settings? rejected] :or {settings? true rejected #{"buy-0"}}}]
   (let [legs (vec (map-indexed (fn [i l] (assoc l :cloid (cloid (inc i)))) (:legs plan)))
         g (-> (lifecycle/new-grid {:grid-id "g" :scope scope :scope-key "k" :operation-id "op"
                                    :plan-id "plan-1" :legs legs
                                    :model (select-keys (:model plan) [:anchor :volatility :tilt :shifted-anchor])
                                    :settings (when settings?
                                                (ladder-settings/settings-from-plan plan fixtures/draft-13-2))
                                    :expires-after-ms 31000 :now-ms 1000})
               (lifecycle/mark-sending "op" "a1" 1500))]
     (evidence/apply-placement-delivery
      g "op"
      {:delivery :response
       :leg-results (map-indexed (fn [i {:keys [leg-id] :as l}]
                                   (if (contains? rejected leg-id)
                                     {:cloid (:cloid l) :placement-state :rejected :reason rejection}
                                     {:cloid (:cloid l) :oid (str (+ 100 i)) :placement-state :accepted
                                      :order-state :resting}))
                                 legs)}
      2000))))

(defn- survivor-orders [grid]
  (->> (lifecycle/current-legs grid)
       (filter lifecycle/live-leg?)
       (mapv (fn [l] {:oid (:oid l) :side (:side l) :price (:price l) :remaining (:original-base l)}))))

(defn- proposal
  ([grid] (proposal grid "76.00" "76.35" {}))
  ([grid bid ask account-overrides]
   (assoc (repair-plan/propose {:grid grid
                                :market (fixtures/market-snapshot {:bbo {:bid bid :ask ask :observed-at-ms 1000}})
                                :account (fixtures/account-snapshot
                                          (merge {:open-orders (survivor-orders grid)} account-overrides))
                                :fees fixtures/synthetic-fees
                                ;; 2023-11-14 22:13:20 UTC
                                :now-ms 1700000000000})
          :grid-id "g" :status :ready :proposal-id "rp-1" :error nil :errors [])))

(defn- deep-merge [a b] (cond (and (map? a) (map? b)) (merge-with deep-merge a b) (nil? b) a :else b))

(defn- state
  ([grid] (state grid (proposal grid)))
  ([grid repair & [extra]]
   (deep-merge {:vol-range {:execution {:enabled? true :grids {"g" grid}}
                            :repair repair}
                :vol-range-dock {:current-scope-key "k"}}
               extra)))

(deftest ready-model-shows-the-frozen-repair-test
  (let [m (rm/repair-model (state (partial-grid)))
        [leg] (:legs m)]
    (is (contracts/repair-model-valid? m))
    (is (= :ready (:status m)))
    (is (= "Review rejected orders" (:title m)))
    (is (= "VRT/USDC · 0x1234…5678 · Post-only" (:identity-line m)))
    (is (= "Only the selected orders are sent. The 6 active orders in this ladder are not touched." (:intro m)))
    (is (nil? (:loading-text m)))
    (is (= {:logical-id "buy-0" :side :buy :side-label "Buy" :selected? true :select-label "Include Buy 7 VRT"
            :rejected-price "76.4" :price "76.16" :size "7" :notional "533.12" :distance "0.8σ"
            :note "Moved away from the book: 76.4 would cross the best ask 76.35." :reason rejection}
           leg))
    (is (= [{:label "If all buys fill first" :value "190 VRT"}
            {:label "If all sells fill first" :value "120 VRT"}
            {:label "Hold at most" :value "190 VRT"}
            {:label "Keep at least" :value "120 VRT"}]
           (:bounds m)))
    (is (nil? (:limits-warning m)))
    (is (= [] (:errors m)))
    (is (= "Prices as of 22:13:20 UTC" (:as-of m)))
    (is (= {:label "Place 1 repair order" :enabled? true :busy? false} (:submit m)))
    (is (= {:enabled? true :busy? false} (:refresh m)))))

(deftest a-price-that-no-longer-crosses-is-kept-test
  (let [g (partial-grid)
        m (rm/repair-model (state g (proposal g "75.00" "76.50" {})))]
    (is (= "76.4" (:price (first (:legs m)))))
    (is (= "The original price no longer crosses the book." (:note (first (:legs m)))))))

(deftest selection-and-pluralization-test
  (let [g (partial-grid {:rejected #{"buy-0" "buy-1"}})
        p (proposal g)
        m (rm/repair-model (state g p))]
    (is (= 2 (count (:legs m))))
    (is (= "Place 2 repair orders" (get-in m [:submit :label])))
    (is (= "Only the selected orders are sent. The 5 active orders in this ladder are not touched." (:intro m)))
    (testing "nothing selected disables the commit"
      (let [m (rm/repair-model (state g (assoc p :selected #{})))]
        (is (= {:label "Place 0 repair orders" :enabled? false :busy? false} (:submit m)))
        (is (every? false? (map :selected? (:legs m))))))
    (testing "one deselected"
      (let [m (rm/repair-model (state g (assoc p :selected #{"buy-1"})))]
        (is (= "Place 1 repair order" (get-in m [:submit :label])))
        (is (= [false true] (map :selected? (:legs m))))))))

(deftest loading-and-error-states-test
  (let [g (partial-grid)]
    (let [m (rm/repair-model (state g {:grid-id "g" :status :loading}))]
      (is (contracts/repair-model-valid? m))
      (is (= :loading (:status m)))
      (is (= "Reading the current book…" (:loading-text m)))
      (is (= [] (:legs m)))
      (is (false? (get-in m [:submit :enabled?])))
      (is (= {:enabled? false :busy? true} (:refresh m))))
    (let [m (rm/repair-model (state g {:grid-id "g" :status :error :errors [:guard/scope-changed] :error nil}))]
      (is (contracts/repair-model-valid? m))
      (is (= ["Open VRT/USDC to repair or update this ladder."] (:errors m))))
    (let [m (rm/repair-model (state g {:grid-id "g" :status :error :errors [] :error "Timed out"}))]
      (is (= ["Something went wrong. Timed out"] (:errors m))))))

(deftest closed-or-unknown-ladder-has-no-model-test
  (is (nil? (rm/repair-model (state (partial-grid) nil))))
  (is (nil? (rm/repair-model (state (partial-grid) {:grid-id "other" :status :loading}))))
  (is (contracts/repair-model-valid? nil)))

(deftest bound-errors-and-the-limits-warning-test
  (testing "an external deposit takes the buy repair above the maximum"
    (let [g (partial-grid)
          m (rm/repair-model (state g (proposal g "76.00" "76.35" {:base {:total "160" :hold "0" :available "160"}})))]
      (is (= ["These orders could take inventory above the maximum set for this ladder."] (:errors m)))
      (is (false? (get-in m [:submit :enabled?])))))
  (testing "a ladder without recorded limits warns and keeps balance checks only"
    (let [g (partial-grid {:settings? false})
          m (rm/repair-model (state g))]
      (is (contracts/repair-model-valid? m))
      (is (= "This ladder's inventory limits weren't recorded when it was placed. Only balance checks apply."
             (:limits-warning m)))
      (is (= "Not recorded" (:value (nth (:bounds m) 2))))
      (is (true? (get-in m [:submit :enabled?])))))
  (testing "not enough USDC"
    (let [g (partial-grid)
          m (rm/repair-model (state g (proposal g "76.00" "76.35" {:quote {:total "100" :hold "0" :available "100"}})))]
      (is (= ["Not enough available USDC for these orders."] (:errors m))))))

(deftest unrepairable-texts-test
  (let [g (assoc-in (partial-grid) [:model :volatility :daily-fraction] nil)
        m (rm/repair-model (state g))]
    (is (= [] (:legs m)))
    (is (= [{:logical-id "buy-0"
             :text "Can't repair Buy 7 at 76.4: the ladder's model at placement is unavailable."}]
           (:unrepairable m)))
    (is (contracts/repair-model-valid? m))))

(deftest a-refused-send-is-shown-only-for-its-own-proposal-test
  (let [g (partial-grid)
        s (state g (proposal g) {:vol-range {:execution {:last {:op :repair :status :partially-deployed
                                                                :delivery :not-sent
                                                                :error [:repair/crosses-book]
                                                                :proposal-id "rp-1"}}}})]
    (is (= ["The market moved since these prices were set. Refresh prices and review again."]
           (:errors (rm/repair-model s))))
    (is (= [] (:errors (rm/repair-model (assoc-in s [:vol-range :repair :proposal-id] "rp-2"))))
        "after Refresh prices the old refusal is gone")
    (is (= [] (:errors (rm/repair-model (assoc-in s [:vol-range :execution :busy [:repair "g"]] true))))
        "hidden while a new send is in flight")
    (testing "texts for other refusals"
      (doseq [[last-result text]
              [[{:status :refused :errors [:repair/proposal-mismatch]}
                "This review is out of date. Refresh prices and review again."]
               [{:status :admission-failed :errors [:repair/protected-core-breach]}
                "These orders could take inventory below this ladder's protected core."]
               [{:status :blocked :errors [:grid/cancel-requested]}
                "This ladder is being canceled, so it can't be repaired."]
               [{:status :admission-failed :errors [:grid/unresolved]}
                "Some orders in this ladder are still being checked. Refresh status first."]
               [{:status :admission-failed :errors [:guard/book-stale :guard/fees-stale]}
                "Prices or balances are out of date. Refresh prices and try again."]
               [{:status :admission-failed :errors [:repair/insufficient-base]}
                "Not enough available VRT for these orders."]
               [{:status :busy} "Another placement for this account is in progress. Try again in a moment."]
               [{:status :admission-failed :errors [:wire/invalid-action]} "Something went wrong. (invalid-action)"]]]
        (is (= [text] (:errors (rm/repair-model (assoc-in s [:vol-range :execution :last]
                                                          (assoc last-result :op :repair :proposal-id "rp-1")))))
            (pr-str last-result))))))

(deftest sending-state-test
  (let [g (partial-grid)
        m (rm/repair-model (state g (proposal g) {:vol-range {:execution {:busy {[:repair "g"] true}}}}))]
    (is (= {:label "Placing…" :enabled? false :busy? true} (:submit m)))
    (is (false? (get-in m [:refresh :enabled?])))))

(deftest another-market-disables-the-send-test
  (let [m (rm/repair-model (assoc-in (state (partial-grid)) [:vol-range-dock :current-scope-key] "other"))]
    (is (false? (get-in m [:submit :enabled?])))
    (is (= ["Open VRT/USDC to repair or update this ladder."] (:errors m)))))

(deftest row-control-test
  (let [g (partial-grid)
        base {:vol-range {:execution {:enabled? true}} :vol-range-dock {:current-scope-key "k"}}]
    (is (= {:visible? true :enabled? true :busy? false :count 1 :label "Repair 1 rejected order"
            :aria-label "Repair 1 rejected order in the VRT/USDC ladder" :hint nil}
           (rm/row-control base g)))
    (is (= "Repair 2 rejected orders"
           (:label (rm/row-control base (partial-grid {:rejected #{"buy-0" "sell-2"}})))))
    (testing "hidden without candidates, for recovered or canceling ladders, while unresolved, placement off or updating"
      (is (false? (:visible? (rm/row-control base (partial-grid {:rejected #{}})))))
      (is (false? (:visible? (rm/row-control base (assoc g :recovered? true)))))
      (is (false? (:visible? (rm/row-control base (assoc g :cancel-requested-at-ms 5)))))
      (is (false? (:visible? (rm/row-control base (assoc-in g [:operations "late" :state] :unresolved)))))
      (is (false? (:visible? (rm/row-control (assoc-in base [:vol-range :execution :enabled?] false) g))))
      (is (false? (:visible? (rm/row-control (assoc-in base [:vol-range :replace :grid-id] "g") g)))))
    (testing "another market: disabled with the hint"
      (let [c (rm/row-control (assoc-in base [:vol-range-dock :current-scope-key] "other") g)]
        (is (:visible? c))
        (is (false? (:enabled? c)))
        (is (= "Open VRT/USDC to repair or update this ladder." (:hint c)))))
    (testing "busy while the review is read or the repair is sent"
      (let [c (rm/row-control (assoc-in base [:vol-range :execution :busy [:repair-preview "g"]] true) g)]
        (is (true? (:enabled? c)) "stays focusable; the action ignores the click")
        (is (true? (:busy? c)))))))

(deftest status-messages-test
  (is (nil? (rm/status-message {:op :repair-preview :status :ok})) "a ready review is not announced")
  (is (str/starts-with? (rm/status-message {:op :repair-preview :status :refused :errors [:guard/scope-changed]}
                                           {:market "VRT/USDC"})
                        "Couldn't prepare the repair review. Open VRT/USDC"))
  (is (= "Repair sent. 7 of 7 orders active."
         (rm/status-message {:op :repair :status :live :delivery :response
                             :counts {:members 7 :live 7 :rejected 0}})))
  (is (= "1 order was not placed. 6 other orders remain active."
         (rm/status-message {:op :repair :status :partially-deployed :delivery :response
                             :counts {:members 7 :live 6 :rejected 1}})))
  (is (= "Checking the repair's outcome. Nothing will be resent."
         (rm/status-message {:op :repair :status :checking :delivery :unknown})))
  (is (= "Nothing was sent. The market moved since these prices were set. Refresh prices and review again."
         (rm/status-message {:op :repair :status :partially-deployed :delivery :not-sent
                             :error [:repair/crosses-book]})))
  (is (str/starts-with? (rm/status-message {:op :repair :status :error :error "boom"})
                        "Something went wrong with the repair. Refresh status")
      "an error after admission never claims nothing was sent"))

(deftest no-repair-control-for-a-closed-or-never-placed-ladder-test
  (let [base {:vol-range {:execution {:enabled? true}} :vol-range-dock {:current-scope-key "k"}}
        not-placed (partial-grid {:rejected (set (map :leg-id (:legs plan)))})
        closed (reduce (fn [g l] (if (lifecycle/live-leg? l)
                                   (assoc-in g [:legs (:cloid l)] (assoc l :order-state :terminal
                                                                         :terminal-reason :canceled))
                                   g))
                       (partial-grid) (lifecycle/current-legs (partial-grid)))]
    (is (= :not-placed (lifecycle/status not-placed)))
    (is (= 7 (:rejected (lifecycle/counts not-placed))))
    (is (= :closed (lifecycle/status closed)))
    (doseq [g [not-placed closed]]
      (is (= {:visible? false :enabled? false :busy? false :count 0}
             (select-keys (rm/row-control base g) [:visible? :enabled? :busy? :count]))))))

(deftest refusal-texts-name-the-reason-test
  (is (= "Nothing was sent. This ladder has no active orders left, so it can't be repaired."
         (rm/status-message {:op :repair :status :blocked :errors [:grid/closed]})))
  (is (= (str "Nothing was sent. Funds held for an earlier placement are still being confirmed. "
              "Refresh prices in a few seconds.")
         (rm/status-message {:op :repair :status :admission-failed :errors [:repair/funds-pending]})))
  (is (= "Nothing was sent. Repairs need browser storage, which is unavailable."
         (rm/status-message {:op :repair :status :partially-deployed :delivery :not-sent
                             :error [:journal/unavailable]})))
  (testing "a review whose funds are only held by a pending reservation cannot be sent"
    (let [g (partial-grid)
          p (assoc (repair-plan/propose {:grid g
                                         :market (fixtures/market-snapshot
                                                  {:bbo {:bid "76.00" :ask "76.35" :observed-at-ms 1000}})
                                         :account (fixtures/account-snapshot
                                                   {:open-orders (survivor-orders g)
                                                    :quote {:total "3000" :hold "2366" :available "634"}})
                                         :pending {:usdc "2900" :base "0"}
                                         :fees fixtures/synthetic-fees :now-ms 1700000000000})
                   :grid-id "g" :status :ready :proposal-id "rp-1" :error nil :errors [])
          m (rm/repair-model (state g p))]
      (is (contracts/repair-model-valid? m))
      (is (false? (get-in m [:submit :enabled?])))
      (is (= ["Funds held for an earlier placement are still being confirmed. Refresh prices in a few seconds."]
             (:errors m))))))
