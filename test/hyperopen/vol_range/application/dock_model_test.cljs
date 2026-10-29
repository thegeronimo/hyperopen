(ns hyperopen.vol-range.application.dock-model-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.dock-model :as dm]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]))

(def c (fn [n] (str "0x484f56520000000000000000000000" (if (< n 10) (str "0" n) n))))
(def scope {:network :hyperliquid-mainnet :account-address "0xabc" :coin "SYN/USDC" :symbol "SYN"
            :quote-symbol "USDC" :asset-id 10001})

(defn- seven-legs []
  (vec (concat (map-indexed (fn [i [p s]] {:leg-id (str "buy-" i) :side :buy :rung-index i :price p :size s
                                          :cloid (c (inc i))})
                            [["76.4" "7"] ["74.0" "9"] ["71.6" "11"] ["69.2" "13"]])
               (map-indexed (fn [i p] {:leg-id (str "sell-" i) :side :sell :rung-index i :price p :size "10"
                                       :cloid (c (+ 5 i))})
                            ["84" "86" "88"]))))

(defn- placed [rejected-cloids]
  (let [legs (seven-legs)
        g (-> (lifecycle/new-grid {:grid-id "g" :scope scope :scope-key "k" :operation-id "op" :plan-id "p"
                                   :expires-after-ms 31000 :now-ms 1000 :legs legs
                                   :model {:anchor {:mode :custom :price "80"} :volatility {:daily-fraction "0.06"}}})
              (lifecycle/mark-sending "op" "a" 1500))]
    (evidence/apply-placement-delivery
     g "op" {:delivery :response
             :leg-results (map-indexed (fn [i l]
                                         (if (contains? rejected-cloids (:cloid l))
                                           {:cloid (:cloid l) :placement-state :rejected :reason "Post only order would have immediately matched"}
                                           {:cloid (:cloid l) :oid (str (+ 100 i)) :placement-state :accepted :order-state :resting}))
                                       legs)}
     2000)))

(deftest partial-deployment-row-states-the-spec-message-test
  (let [row (dm/grid-row {} (placed #{(c 1)}))]
    (is (= :partially-deployed (:status row)))
    (is (= "Partially deployed" (:status-label row)))
    (is (= "1 order was not placed. 6 other orders remain active." (:partial-message row)))
    (is (= "33" (get-in row [:counts :live-buy-units])))
    (is (:can-cancel? row))
    (is (= "Rejected" (:status-label (first (:legs row)))))
    (is (= "SYN/USDC" (:market row)))))

(deftest unknown-outcomes-are-flagged-and-never-reassuring-test
  (let [g (evidence/apply-placement-delivery
           (-> (lifecycle/new-grid {:grid-id "g" :scope scope :scope-key "k" :operation-id "op" :plan-id "p"
                                    :expires-after-ms 31000 :now-ms 1000 :legs (seven-legs)})
               (lifecycle/mark-sending "op" "a" 1500))
           "op" {:delivery :unknown} 2000)
        row (dm/grid-row {} g)]
    (is (= "Checking status" (:status-label row)))
    (is (:attention? row))
    (is (every? #(= "Checking" (:status-label %)) (:legs row)))))

(def owner "0x1234567890abcdef1234567890abcdef12345678")

(def live-monitoring
  {:account-address owner :status :live :reasons [] :epoch 4 :last-tick-ms 1000
   :resync {:state :idle :attempt 0}})

(deftest review-commit-respects-the-gate-and-the-local-cap-test
  (let [state {:wallet {:address owner}
               :vol-range {:execution {:enabled? true :max-ladder-usdc "150" :monitoring live-monitoring}
                           :frozen-review {:status :ready :legs [{:price "60" :size "1" :notional "60"}
                                                                 {:price "60" :size "1" :notional "60"}]}}}]
    (is (= {:visible? true :enabled? true :placing? false :label "Place 2 orders" :error nil}
           (dissoc (dm/review-commit state) :note)))
    (testing "over the cap"
      (is (false? (:enabled? (dm/review-commit (assoc-in state [:vol-range :execution :max-ladder-usdc] "100"))))))
    (testing "no cap set"
      (let [m (dm/review-commit (assoc-in state [:vol-range :execution :max-ladder-usdc] nil))]
        (is (:enabled? m))
        (is (= "Post-only orders." (:note m)))))
    (testing "opted out"
      (is (false? (:visible? (dm/review-commit (assoc-in state [:vol-range :execution :enabled?] false))))))
    (testing "placing"
      (let [m (dm/review-commit (assoc-in state [:vol-range :execution :busy [:place]] true))]
        (is (:placing? m))
        (is (false? (:enabled? m)))))
    (testing "live monitoring has not caught up"
      (doseq [[path v] [[[:vol-range :execution :monitoring :status] :resyncing]
                        [[:vol-range :execution :monitoring :status] :stale]
                        [[:vol-range :execution :monitoring] nil]
                        [[:wallet :address] "0xfedcba0987654321fedcba0987654321fedcba09"]]]
        (let [m (dm/review-commit (assoc-in state path v))]
          (is (false? (:enabled? m)) (pr-str path v))
          (is (= "Placement waits for live order and fill updates to catch up." (:note m))))))))

(deftest review-commit-shows-why-its-own-plan-did-not-place-test
  (let [state {:vol-range {:execution {:enabled? true
                                       :last {:op :place :status :journal-unavailable :plan-id "p1"}}
                           :frozen-review {:status :ready :plan-id "p1"
                                           :legs [{:price "60" :size "1" :notional "60"}]}}}]
    (is (re-find #"browser storage" (:error (dm/review-commit state))))
    (testing "another plan's refusal is not shown"
      (is (nil? (:error (dm/review-commit (assoc-in state [:vol-range :frozen-review :plan-id] "p2"))))))
    (testing "hidden while a new attempt is in flight"
      (is (nil? (:error (dm/review-commit (assoc-in state [:vol-range :execution :busy [:place]] true))))))
    (testing "a cancel result is not a placement refusal"
      (is (nil? (:error (dm/review-commit (assoc-in state [:vol-range :execution :last :op] :cancel))))))))

(deftest status-message-explains-a-refused-send-test
  (is (re-find #"Nothing was sent"
               (dm/status-message {:vol-range {:execution {:last {:op :place :status :not-placed :delivery :not-sent
                                                                   :errors [:guard/book-stale]}}}})))
  (is (re-find #"changed since review"
               (dm/status-message {:vol-range {:execution {:last {:op :place :status :admission-failed}}}}))))

(deftest the-dock-only-shows-the-active-accounts-ladders-test
  (let [owner "0x1234567890abcdef1234567890abcdef12345678"
        g (assoc-in (placed #{}) [:scope :account-address] owner)
        state {:vol-range {:execution {:grids {"g" g}}}}]
    (is (= 1 (count (:rows (dm/dock (assoc state :wallet {:address (.toUpperCase owner)}))))))
    (is (empty? (:rows (dm/dock (assoc state :wallet {:address "0xfedcba0987654321fedcba0987654321fedcba09"}))))
        "after an account switch the previous account's ladders are not shown")))

(deftest cancel-messages-are-about-the-ladder-test
  (is (re-find #"still in flight"
               (dm/status-message {:vol-range {:execution {:last {:op :cancel :status :busy}}}}))))

(deftest rows-carry-the-repair-control-test
  (let [state {:vol-range {:execution {:enabled? true}} :vol-range-dock {:current-scope-key "k"}}]
    (is (= {:visible? true :enabled? true :count 1}
           (select-keys (:repair (dm/grid-row state (placed #{(c 1)}))) [:visible? :enabled? :count])))
    (is (false? (get-in (dm/grid-row state (placed #{})) [:repair :visible?])))
    (is (false? (get-in (dm/grid-row {} (placed #{(c 1)})) [:repair :visible?])) "placement off")))

(deftest rows-carry-the-update-control-and-lineage-test
  (let [state {:vol-range {:execution {:enabled? true}} :vol-range-dock {:current-scope-key "k"}}
        row (dm/grid-row state (placed #{}))]
    (is (= {:visible? true :enabled? true :label "Preview update"
            :aria-label "Preview an update of the SYN/USDC ladder" :hint nil}
           (:update row)))
    (is (nil? (:lineage row)))
    (is (true? (get-in (dm/grid-row state (placed #{(c 1)})) [:update :visible?])) "partially deployed")
    (is (false? (get-in (dm/grid-row {} (placed #{})) [:update :visible?])) "placement off")
    (is (= "Open SYN/USDC to repair or update this ladder."
           (get-in (dm/grid-row (assoc state :vol-range-dock {:current-scope-key "other"}) (placed #{}))
                   [:update :hint]))))
  (let [old (placed #{})
        new-grid (assoc (placed #{}) :grid-id "g2" :created-at-ms 1793196540000
                        :predecessor {:grid-id "g" :frozen-cloids [(c 1)]})
        state {:vol-range {:execution {:enabled? true :grids {"g" (assoc old :created-at-ms 1793196120000)
                                                              "g2" new-grid}}}}]
    (is (= "Replaced by the ladder created 2026-10-28 14:09 UTC"
           (:lineage (dm/grid-row state (get-in state [:vol-range :execution :grids "g"])))))
    (is (= "Replaces the ladder created 2026-10-28 14:02 UTC" (:lineage (dm/grid-row state new-grid))))))

(deftest update-status-messages-test
  (let [msg (fn [last-result]
              (dm/status-message {:vol-range {:execution {:last (assoc last-result :grid-id "g" :update-started-at-ms 5)}
                                              :replace {:grid-id "g" :started-at-ms 5 :phase :canceling}}}))]
    (is (= "Old ladder canceled. Review the updated quantities before placing the replacement."
           (msg {:op :replace-cancel :status :ready})))
    (is (= "Update stopped. Your ladder was not changed."
           (msg {:op :replace-stop :status :stopped :old-ladder :unchanged})))
    (is (= "Update stopped. The old ladder stays canceled; no new orders were placed."
           (msg {:op :replace-stop :status :stopped :old-ladder :canceled})))
    (is (= "Update stopped. No new orders were placed. Some of the old ladder's orders may still be open."
           (msg {:op :replace-stop :status :stopped :old-ladder :unsettled})))
    (is (= "The old ladder isn't fully canceled yet. Some old orders have an unknown status. The replacement can't be reviewed until they are settled."
           (msg {:op :replace-confirm :status :blocked :errors [:replace/unresolved-outcomes]})))
    (is (not (re-find #"^Ladder " (msg {:op :replace-confirm :status :error}))) "never the generic fallback")))

(deftest a-refused-replacement-points-at-refresh-balances-test
  ;; The replacement plans from the balances read at the cancel's
  ;; confirmation; reviewing again alone may re-freeze the same legs.
  (let [last-result {:op :place :status :admission-failed :errors [:guard/plan-changed]}]
    (is (= (str "The market or your balances changed since review. "
                "Refresh balances on the update banner, then review the ladder again.")
           (dm/status-message {:vol-range {:execution {:last (assoc last-result :replacement? true)}}})))
    (is (= "The market or your balances changed since review. Review the ladder again. (plan-changed)"
           (dm/status-message {:vol-range {:execution {:last (assoc last-result :replacement? false)}}}))
        "an ordinary review keeps its text")))

(deftest repair-status-messages-test
  (let [msg (fn [last-result] (dm/status-message {:vol-range {:execution {:last last-result}}}))]
    (is (= "Repair sent. 7 of 7 orders active."
           (msg {:op :repair :status :live :delivery :response :counts {:members 7 :live 7 :rejected 0}})))
    (is (= "1 order was not placed. 6 other orders remain active."
           (msg {:op :repair :status :partially-deployed :delivery :response
                 :counts {:members 7 :live 6 :rejected 1}})))
    (is (= "Checking the repair's outcome. Nothing will be resent."
           (msg {:op :repair :status :checking :delivery :unknown})))
    (is (nil? (msg {:op :repair-preview :status :ok})) "never \"Ladder ok.\" for a review")
    (is (re-find #"Nothing was sent" (msg {:op :repair :status :refused :errors [:repair/proposal-mismatch]})))))

(defn- line [monitoring & [extra]]
  (:monitoring (dm/dock (merge {:wallet {:address owner}
                                :vol-range {:execution {:enabled? true :monitoring monitoring}}}
                               extra))))

(deftest the-monitoring-line-test
  (let [m (fn [status reasons & [resync]]
            (assoc live-monitoring :status status :reasons reasons
                   :resync (or resync {:state :idle :attempt 0})))]
    (is (= {:status :live :reason nil :attention? false :text "Live updates on"} (line live-monitoring)))
    (doseq [[monitoring status reason text]
            [[(m :stale [:transport-down]) :stale :transport-down
              "Live updates paused while reconnecting. Your orders stay on the exchange."]
             [(m :stale [:streams-elsewhere]) :stale :streams-elsewhere
              "Live updates paused while another account is shown."]
             [(m :stale [:streams-not-ready]) :stale :streams-not-ready "Connecting live updates…"]
             [(m :stale [:awaiting-streams]) :stale :awaiting-streams "Connecting live updates…"]
             [(m :resyncing [:catching-up] {:state :required :attempt 0}) :resyncing :catching-up
              "Catching up on fills and orders…"]
             [(m :resyncing [:reconnected] {:state :running :attempt 0 :started-at-ms 1}) :resyncing :reconnected
              "Catching up on fills and orders…"]
             [(m :resyncing [:suspended] {:state :failed :attempt 1 :next-at-ms 9}) :resyncing :suspended
              "Couldn't catch up yet. Retrying automatically."]
             [(m :resyncing [:suspended] {:state :running :attempt 2}) :resyncing :suspended
              "Couldn't catch up yet. Retrying automatically."]]]
      (is (= {:status status :reason reason :attention? true :text text} (line monitoring)) (pr-str monitoring))))
  (testing "no monitoring yet, or a map for another account, reads as connecting"
    (is (= "Connecting live updates…" (:text (line nil))))
    (is (= :stale (:status (line (assoc live-monitoring :account-address "0xfedcba0987654321fedcba0987654321fedcba09"))))))
  (testing "no line without a trading account, or with placement off and no monitoring"
    (is (nil? (line live-monitoring {:wallet {}})))
    (is (nil? (:monitoring (dm/dock {:wallet {:address owner} :vol-range {:execution {:enabled? false}}}))))))

(deftest monitoring-refusals-use-the-refusal-copy-test
  (let [text "Nothing was placed: live order and fill updates were catching up. Try again in a moment."
        msg (fn [last-result] (dm/status-message {:vol-range {:execution {:last last-result}}}))]
    (is (= text (msg {:op :place :status :refused :errors [:monitoring/resync-required]})))
    (testing "a final-guard refusal: the executor's codes next to delivery's text"
      (is (= text (msg {:op :place :status :not-placed :delivery :not-sent :phase :final-guard
                        :errors [:monitoring/suspended] :error "[:monitoring/suspended]"})))
      (is (= text (msg {:op :repair :status :partially-deployed :delivery :not-sent :phase :final-guard
                        :errors [:monitoring/stale] :error "[:monitoring/stale]"}))))
    (is (= text (msg {:op :repair :status :refused :errors [:monitoring/stale]})))
    (is (re-find #"Nothing was sent: guard/book-stale|book-stale"
                 (msg {:op :place :status :not-placed :delivery :not-sent :errors [:guard/book-stale]}))
        "other refusals keep their texts")))

(def ^:private spot-scope (assoc scope :market-type :spot :spot-pair-index 1 :base-token-id "9001"))

(defn- tracked-campaign [t0]
  {:schema-version 1 :record-id "campaign:cmp-1" :kind :campaign :campaign-id "cmp-1"
   :campaign-key "hyperliquid-mainnet|0xabc|spot|9001|1" :t0-ms t0 :saved-at-ms t0})

(deftest rows-carry-the-tracking-line-test
  ;; Spec 12.1: when the market's tracking began; no figures in the dock.
  (let [t0 1761054120000 ; 2025-10-21 13:42 UTC
        g (-> (placed #{}) (assoc :scope spot-scope :created-at-ms (+ t0 500)))
        state {:vol-range {:campaigns {"cmp-1" (tracked-campaign t0)}}}]
    (is (= {:text "Tracking since Oct 21, 13:42 UTC"} (:tracking (dm/grid-row state g))))
    (is (= {:text "Not tracked"} (:tracking (dm/grid-row state (assoc g :recovered? true))))
        "recovered orders are never tracked")
    (is (= {:text "Not tracked"} (:tracking (dm/grid-row {} g))) "no campaign loaded")
    (is (= {:text "Not tracked"} (:tracking (dm/grid-row state (assoc g :created-at-ms (- t0 1)))))
        "a ladder placed before tracking began")
    (is (= {:text "Not tracked"}
           (:tracking (dm/grid-row state (assoc-in g [:scope :spot-pair-index] 2))))
        "another market's campaign")))
