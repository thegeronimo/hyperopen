(ns hyperopen.vol-range.application.replace-model-test
  "Update dialog, ticket banner, review lineage and dock models (HO-SPEC-014
  sections 10.2, 11.2, 11.4, 13.4): old versus proposed rows from the 13.2
  ladder, the exact intermediate message, remaining and filled texts,
  rejected members unchecked by default, lineage in both directions and
  exact-key contracts."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.schema.vol-range-repair-replace-contracts :as contracts]
            [hyperopen.vol-range.application.draft-scope :as draft-scope]
            [hyperopen.vol-range.application.ladder-settings :as ladder-settings]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.application.replace-model :as rm]
            [hyperopen.vol-range.fixtures :as fixtures]
            [hyperopen.vol-range.state-fixtures :as sf]))

(def plan (planner/prepare-plan (fixtures/plan-input)))
(def created-ms 1793196120000)   ; 2026-10-28 14:02 UTC
(def successor-ms 1793196540000) ; 2026-10-28 14:09 UTC

(defn- cloid [i] (str "0x" (.padStart (str i) 32 "0")))

(defn- placed-grid
  "The 13.2 ladder in the ticket's scope, `rejected` members definitively
  rejected and the others resting."
  ([] (placed-grid #{}))
  ([rejected] (placed-grid rejected {:settings (ladder-settings/settings-from-plan plan fixtures/draft-13-2)}))
  ([rejected extra]
   (let [legs (vec (map-indexed (fn [i l] (assoc l :cloid (cloid (inc i)))) (:legs plan)))
         g (-> (lifecycle/new-grid (merge {:grid-id "g1"
                                           :scope {:account-address sf/owner :symbol "VRT" :quote-symbol "USDC"}
                                           :scope-key (draft-scope/current-scope-key (sf/base-state))
                                           :operation-id "op" :plan-id "plan-1" :legs legs
                                           :model (select-keys (:model plan) [:anchor :volatility :tilt :shifted-anchor])
                                           :expires-after-ms 31000 :now-ms created-ms}
                                          extra))
               (lifecycle/mark-sending "op" "a1" (+ created-ms 500)))]
     (evidence/apply-placement-delivery
      g "op"
      {:delivery :response
       :leg-results (map-indexed (fn [i {:keys [leg-id] :as l}]
                                   (if (contains? rejected leg-id)
                                     {:cloid (:cloid l) :placement-state :rejected :reason "Post only"}
                                     {:cloid (:cloid l) :oid (str (+ 100 i)) :placement-state :accepted
                                      :order-state :resting}))
                                 legs)}
      (+ created-ms 1000)))))

(defn- state-with
  ([grid] (state-with grid nil))
  ([grid update]
   (-> (sf/base-state)
       (assoc-in [:vol-range :execution] {:enabled? true :grids {(:grid-id grid) grid}})
       (assoc :vol-range-dock {:current-scope-key (:scope-key grid)})
       (assoc-in [:vol-range :replace]
                 (when update (merge {:grid-id (:grid-id grid) :scope-key (:scope-key grid) :started-at-ms 5
                                      :dialog-open? true :include-rejected #{} :settings-recorded? true}
                                     update))))))

(def ready-basis
  {:ready? true :blocking [] :remaining {:buy "40" :sell "27"} :filled {:buy "0" :sell "3"}
   :filled-during-update {:buy "0" :sell "3"}
   :rejected [{:logical-id "buy-0" :side :buy :rung-index 0 :price "76.4" :original-base "7" :reason "Post only"}]})

(defn- canceled [extra] (merge {:phase :canceled :frozen-cloids [(cloid 2)] :filled-at-freeze {} :basis ready-basis}
                               extra))

(defn- row [model field] (first (filter #(= field (:field %)) (:compare model))))

;; ---------------------------------------------------------------------------
;; Dialog
;; ---------------------------------------------------------------------------

(deftest preview-compares-the-deployed-ladder-with-the-proposal-test
  (let [m (rm/replace-dialog-model (state-with (placed-grid #{"buy-0"}) {:phase :preview}) plan)]
    (is (contracts/replace-model-valid? m))
    (is (= "Update ladder" (:title m)))
    (is (= ["1 Preview" "2 Cancel old ladder" "3 Review replacement"] (mapv :label (:steps m))))
    (is (= [true false false] (mapv :current? (:steps m))))
    (is (= rm/explainer (:explainer m)))
    (is (= rm/quantity-note (:quantity-note m)))
    (is (nil? (:settings-note m)) "settings were recorded")
    (is (= [:anchor :volatility :tilt :buys :sells :buy-fee-reserve :max-buy-debit :all-buys-first :all-sells-first]
           (mapv :field (:compare m))))
    (testing "old versus proposed"
      (is (= "6.00%" (:current (row m :volatility)) (:proposed (row m :volatility))))
      (is (= "Off" (:current (row m :tilt))))
      (is (= "80 · Custom anchor" (:current (row m :anchor)) (:proposed (row m :anchor))))
      (is (= "3 orders · 74 to 69.2 · 33 VRT" (:current (row m :buys)))
          "the rejected buy is not live; nearest to farthest price; the live remainder")
      (is (= "4 orders · 76.4 to 69.2 · 40 VRT" (:proposed (row m :buys))))
      (is (= "3 orders · 86 to 90.8 · 30 VRT" (:current (row m :sells)) (:proposed (row m :sells))))
      (is (= "1.1552 USDC" (:current (row m :buy-fee-reserve))))
      (is (= "2,889.1552 USDC" (:current (row m :max-buy-debit))))
      (is (= "190 VRT" (:current (row m :all-buys-first))))
      (is (= "120 VRT" (:current (row m :all-sells-first)))))
    (testing "controls"
      (is (= {:label "Cancel current ladder to update" :enabled? true :busy? false
              :aria-label "Cancel the 6 open orders of the VRT/USDC ladder"}
             (get-in m [:actions :cancel-old])))
      (is (= "Edit in ticket" (get-in m [:actions :edit :label])))
      (is (= "Stop updating" (get-in m [:actions :abandon :label])))
      (is (nil? (get-in m [:actions :use-quantities])))
      (is (nil? (get-in m [:actions :check]))))))

(deftest preview-without-recorded-settings-says-so-test
  (let [g (placed-grid #{} {})
        m (rm/replace-dialog-model (state-with g {:phase :preview :settings-recorded? false}) plan)]
    (is (contracts/replace-model-valid? m))
    (is (= rm/settings-note (:settings-note m)))
    (is (= "Not recorded" (:current (row m :buy-fee-reserve)) (:current (row m :all-sells-first))))
    (is (= "6.00%" (:current (row m :volatility))) "the model is on every grid")))

(deftest closed-or-unknown-renders-nothing-test
  (is (nil? (rm/replace-dialog-model (state-with (placed-grid) {:phase :preview :dialog-open? false}) plan)))
  (is (nil? (rm/replace-dialog-model (state-with (placed-grid)) plan)))
  (is (nil? (rm/replace-dialog-model (assoc-in (state-with (placed-grid) {:phase :preview})
                                               [:vol-range :execution :grids] {})
                                     plan))))

(deftest canceling-and-blocked-phases-test
  (let [g (placed-grid)
        canceling (rm/replace-dialog-model
                   (assoc-in (state-with g {:phase :canceling :frozen-cloids [(cloid 1)]})
                             [:vol-range :execution :busy [:replace-cancel "g1"]] true)
                   plan)]
    (is (contracts/replace-model-valid? canceling))
    (is (= "Canceling the old ladder…" (:phase-text canceling)))
    (is (= [false true false] (mapv :current? (:steps canceling))))
    (is (empty? (:compare canceling)))
    (is (nil? (get-in canceling [:actions :cancel-old]))))
  (testing "blocked texts, Cancel remaining orders only for open legs"
    (let [blocked (fn [codes] (rm/replace-dialog-model
                               (state-with (placed-grid) {:phase :blocked :frozen-cloids [(cloid 1) (cloid 2)]
                                                          :basis {:ready? false :blocking codes}})
                               plan))
          open (blocked [:replace/legs-still-open])
          unknown (blocked [:replace/unresolved-outcomes])]
      (is (contracts/replace-model-valid? open))
      (is (= ["2 old orders are still open."] (:blocking open)))
      (is (= "Cancel remaining orders" (get-in open [:actions :cancel-remaining :label])))
      (is (= "Check again" (get-in open [:actions :check :label])))
      (is (= ["Some old orders have an unknown status. The replacement can't be reviewed until they are settled."]
             (:blocking unknown)))
      (is (nil? (get-in unknown [:actions :cancel-remaining])))
      (is (nil? (get-in unknown [:actions :use-quantities])) "no Review replacement while blocked")
      (is (= ["Fills during the cancel aren't confirmed yet."] (:blocking (blocked [:replace/fills-incomplete]))))
      (is (= ["The cancellation couldn't be confirmed. Check again."] (:blocking (blocked [:replace/check-failed])))))))

(deftest canceled-shows-the-exact-message-and-the-remainder-test
  (let [m (rm/replace-dialog-model (state-with (placed-grid #{"buy-0"}) (canceled {})) plan)]
    (is (contracts/replace-model-valid? m))
    (is (= "Old ladder canceled. Review the updated quantities before placing the replacement." (:message m)))
    (is (= "Unfilled remainder: buys 40 VRT, sells 27 VRT." (:remaining-text m)))
    (is (= "Filled while canceling: sells 3 VRT." (:filled-text m)))
    (is (nil? (:included-text m)))
    (is (= [{:logical-id "buy-0" :label "Include Buy 7 at 76.4" :selected? false :enabled? true}] (:rejected m))
        "rejected members are never included by default")
    (is (= [false false true] (mapv :current? (:steps m))))
    (is (= {:label "Review replacement" :enabled? true :busy? false :aria-label nil}
           (get-in m [:actions :use-quantities]))))
  (testing "a selected rejected member adds its quantity"
    (let [m (rm/replace-dialog-model (state-with (placed-grid #{"buy-0"}) (canceled {:include-rejected #{"buy-0"}}))
                                     plan)]
      (is (true? (:selected? (first (:rejected m)))))
      (is (= "With the selected orders: buys 47 VRT, sells 27 VRT." (:included-text m)))))
  (testing "nothing filled while canceling"
    (is (nil? (:filled-text (rm/replace-dialog-model
                             (state-with (placed-grid) (canceled {:basis (assoc ready-basis :filled-during-update
                                                                                {:buy "0" :sell "0"})}))
                             plan)))))
  (testing "busy: selection and Review replacement wait"
    (let [m (rm/replace-dialog-model
             (assoc-in (state-with (placed-grid) (canceled {})) [:vol-range :execution :busy [:replace-confirm "g1"]] true)
             plan)]
      (is (false? (:enabled? (first (:rejected m)))))
      (is (false? (get-in m [:actions :use-quantities :enabled?]))))))

(deftest a-stale-update-only-offers-stop-updating-test
  (let [m (rm/replace-dialog-model (state-with (placed-grid) {:phase :canceled :scope-key "other" :basis ready-basis})
                                   plan)]
    (is (contracts/replace-model-valid? m))
    (is (str/includes? (:stale-text m) "Open VRT/USDC to continue"))
    (is (nil? (:message m)))
    (is (= [:abandon] (vec (keep (fn [[k v]] (when v k)) (:actions m)))))))

;; ---------------------------------------------------------------------------
;; Banner
;; ---------------------------------------------------------------------------

(deftest banner-per-phase-test
  (let [banner (fn [u & [f]] (rm/banner-model ((or f identity) (state-with (placed-grid) u))))
        preview (banner {:phase :preview})
        ready (banner (canceled {:phase :ready}))
        canceled-banner (banner (canceled {}))]
    (is (nil? (rm/banner-model (state-with (placed-grid)))))
    (doseq [b [preview canceled-banner ready (banner {:phase :canceling}) (banner {:phase :blocked})]]
      (is (contracts/replace-banner-valid? b) (pr-str b)))
    (is (= "Updating the VRT/USDC ladder · step 1 of 3" (:title preview)))
    (is (true? (:replaces-footer? preview)))
    (is (true? (:open-update? preview)))
    (is (nil? (:refresh-balances preview)))
    (is (= "Canceling the old ladder…" (:message (banner {:phase :canceling}))))
    (is (true? (:busy? (banner {:phase :canceling}))))
    (is (= rm/intermediate-message (:message canceled-banner)))
    (is (= "Replacing the ladder created 2026-10-28 14:02 UTC" (:lineage canceled-banner)))
    (is (true? (:replaces-footer? canceled-banner)))
    (testing "ready: the normal footer renders under the banner"
      (is (false? (:replaces-footer? ready)))
      (is (false? (:open-update? ready)))
      (is (= {:enabled? true :busy? false} (:refresh-balances ready)))
      (is (= rm/intermediate-message (:message ready))))
    (testing "another market: no footer swap"
      (let [stale (banner {:phase :preview :scope-key "other"})]
        (is (false? (:replaces-footer? stale)))
        (is (= "Open VRT/USDC to continue updating this ladder." (:message stale)))))))

;; ---------------------------------------------------------------------------
;; Review lineage
;; ---------------------------------------------------------------------------

(def predecessor
  {:grid-id "g1" :frozen-cloids [(cloid 2)] :remaining {:buy "40" :sell "27"}
   :filled-during-update {:buy "0" :sell "3"} :included-rejected []})

(defn- review-state [pred draft]
  (-> (state-with (placed-grid #{"buy-0"}))
      (assoc-in [:vol-range :frozen-context :replace :predecessor] pred)
      (assoc-in [:vol-range :frozen-context :draft] draft)))

(defn- draft-with [buy sell]
  (-> fixtures/draft-13-2
      (assoc-in [:buy :quantity] buy) (assoc-in [:buy :amount-unit] :base) (assoc-in [:buy :enabled?] true)
      (assoc-in [:sell :quantity] sell) (assoc-in [:sell :amount-unit] :base) (assoc-in [:sell :enabled?] true)))

(deftest review-lineage-test
  (is (nil? (rm/review-replacement (state-with (placed-grid)))) "an ordinary review has no lineage")
  (let [r (rm/review-replacement (review-state predecessor (draft-with "40" "27")))]
    (is (= {:lineage-text "Replaces the ladder created 2026-10-28 14:02 UTC."
            :remaining-text "Unfilled remainder: buys 40 VRT, sells 27 VRT."
            :filled-text "Filled while canceling: sells 3 VRT."
            :rejected-text "Rejected orders from the old ladder are not included."
            :edited-text nil}
           r)))
  (testing "an included rejected order and the default quantities"
    (let [r (rm/review-replacement (review-state (assoc predecessor :included-rejected ["buy-0"]) (draft-with "47" "27")))]
      (is (= "Rejected orders included: Buy 7 (old price 76.4)" (:rejected-text r)))
      (is (nil? (:edited-text r)))))
  (testing "edited quantities"
    (is (= "You changed the quantities from the remainder."
           (:edited-text (rm/review-replacement (review-state predecessor (draft-with "40" "20"))))))
    (is (= "You changed the quantities from the remainder."
           (:edited-text (rm/review-replacement
                          (review-state predecessor (assoc-in (draft-with "40" "27") [:sell :amount-unit] :quote)))))))
  (testing "a disabled zero side is not an edit"
    (is (nil? (:edited-text (rm/review-replacement
                             (review-state (assoc predecessor :remaining {:buy "40" :sell "0"})
                                           (-> (draft-with "40" "0") (assoc-in [:sell :enabled?] false)))))))))

;; ---------------------------------------------------------------------------
;; Dock
;; ---------------------------------------------------------------------------

(deftest preview-update-row-control-test
  (let [g (placed-grid #{"buy-0"})
        control (fn [s] (rm/row-control s (get-in s [:vol-range :execution :grids "g1"])))]
    (is (= {:visible? true :enabled? true :label "Preview update"
            :aria-label "Preview an update of the VRT/USDC ladder" :hint nil}
           (control (state-with g))))
    (testing "hidden"
      (doseq [[label s] [["placement off" (assoc-in (state-with g) [:vol-range :execution :enabled?] false)]
                         ["recovered" (state-with (assoc g :recovered? true))]
                         ["canceling" (state-with (assoc g :cancel-requested-at-ms 9))]
                         ["update past preview" (state-with g {:phase :canceling})]
                         ["unresolved" (state-with (assoc-in g [:legs (cloid 2) :placement-state] :unknown))]]]
        (is (false? (:visible? (control s))) label)))
    (testing "another market: disabled with the hint"
      (let [c (control (assoc (state-with g) :vol-range-dock {:current-scope-key "other"}))]
        (is (false? (:enabled? c)))
        (is (= "Open VRT/USDC to repair or update this ladder." (:hint c)))))
    (testing "disabled while a repair review is open or an update is past preview elsewhere"
      (is (false? (:enabled? (control (assoc-in (state-with g) [:vol-range :repair] {:grid-id "g1"})))))
      (is (false? (:enabled? (control (assoc-in (state-with g) [:vol-range :execution :busy [:replace-cancel "g1"]] true))))
          "a stopped update's cancel of this ladder is still running")
      (is (false? (:enabled? (control (assoc-in (state-with g) [:vol-range :replace]
                                                {:grid-id "g0" :scope-key (:scope-key g) :phase :canceled}))))))
    (is (true? (:visible? (control (state-with g {:phase :preview})))) "Preview update restarts a preview")))

(deftest lineage-in-both-directions-test
  (let [old (placed-grid)
        new-grid (-> (placed-grid #{} {:grid-id "g2" :now-ms successor-ms
                                       :predecessor (assoc predecessor :grid-id "g1")})
                     (assoc :grid-id "g2" :created-at-ms successor-ms))
        s (assoc-in (state-with old) [:vol-range :execution :grids "g2"] new-grid)]
    (is (= "Replaced by the ladder created 2026-10-28 14:09 UTC" (rm/lineage s old)))
    (is (= "Replaces the ladder created 2026-10-28 14:02 UTC" (rm/lineage s new-grid)))
    (is (nil? (rm/lineage (state-with old) old)))
    (testing "the old ladder is no longer in the store"
      (is (= "Replaces an earlier ladder" (rm/lineage (assoc-in s [:vol-range :execution :grids] {"g2" new-grid})
                                                      new-grid))))))

(deftest update-status-messages-test
  (let [s (state-with (placed-grid) {:phase :blocked :frozen-cloids [(cloid 1)]})
        msg #(rm/status-message s (assoc % :grid-id "g1" :update-started-at-ms 5))]
    (is (= rm/intermediate-message (msg {:op :replace-cancel :status :ready})))
    (is (= rm/intermediate-message (msg {:op :replace-confirm :status :ready})))
    (is (= "Balances refreshed. Review the replacement before placing it."
           (rm/status-message (state-with (placed-grid) {:phase :ready})
                              {:op :replace-confirm :status :ready :grid-id "g1" :update-started-at-ms 5})))
    (is (= "The old ladder isn't fully canceled yet. 1 old order is still open."
           (msg {:op :replace-cancel :status :blocked :errors [:replace/legs-still-open]})))
    (is (str/starts-with? (msg {:op :replace-cancel :status :busy}) "Nothing was canceled."))
    (is (= "The cancellation couldn't be confirmed. Check again. timeout"
           (msg {:op :replace-confirm :status :error :error "timeout"})))
    (is (= "Update stopped. Your ladder was not changed."
           (msg {:op :replace-stop :status :stopped :old-ladder :unchanged})))
    (is (= "Update stopped. The old ladder stays canceled; no new orders were placed."
           (msg {:op :replace-stop :status :stopped :old-ladder :canceled})))
    (is (= "Update stopped. No new orders were placed. Some of the old ladder's orders may still be open."
           (msg {:op :replace-stop :status :stopped :old-ladder :unsettled})))))

(deftest a-result-after-stop-updating-is-announced-as-stopped-test
  ;; Stop updating while the cancel runs; the cancel's result arrives later.
  (let [g (placed-grid)
        stopped (state-with g)
        msg (fn [s r] (rm/status-message s (assoc r :grid-id "g1" :update-started-at-ms 5)))]
    (is (= "Update stopped. The old ladder was canceled; no new orders were placed."
           (msg stopped {:op :replace-cancel :status :ready}))
        "never asks to review a replacement that no longer exists")
    (is (not= rm/intermediate-message (msg stopped {:op :replace-confirm :status :ready})))
    (is (= (str "Update stopped. No new orders were placed. The old ladder isn't fully canceled yet. "
                "7 old orders are still open.")
           (msg stopped {:op :replace-cancel :status :blocked :errors [:replace/legs-still-open]})))
    (is (= "Update stopped. Nothing was canceled; your ladder was not changed."
           (msg stopped {:op :replace-cancel :status :refused :errors [:signer/agent-not-ready]})))
    (is (= (str "Update stopped. No new orders were placed. Some of the old ladder's orders may still be open. "
                "The cancellation couldn't be confirmed.")
           (msg stopped {:op :replace-cancel :status :error :error "Timed out; try again."})))
    (testing "an update of the same ladder started after the stop is not the one answered"
      (is (= "Update stopped. The old ladder was canceled; no new orders were placed."
             (msg (state-with g {:phase :preview :started-at-ms 9}) {:op :replace-cancel :status :ready}))))))
