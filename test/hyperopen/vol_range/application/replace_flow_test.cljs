(ns hyperopen.vol-range.application.replace-flow-test
  "Pure steps of a ladder update (HO-SPEC-014 sections 10.2, 13.4; VR-15,
  VR-16): ticket patches, the freeze and the predecessor check."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.trading.vol-range-draft :as vol-range-draft]
            [hyperopen.trading.vol-range-transitions :as transitions]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.ladder-settings :as ladder-settings]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.application.remediations :as remediations]
            [hyperopen.vol-range.application.replace-flow :as replace-flow]
            [hyperopen.vol-range.fixtures :as fx]))

(def settings-13-2
  (ladder-settings/settings-from-plan (planner/prepare-plan (fx/plan-input)) fx/draft-13-2))

(defn- apply-patch [patch]
  (remediations/apply-draft-patch (vol-range-draft/default-vol-range-draft) patch))

(defn- paths [patch] (mapv first patch))

;; ---------------------------------------------------------------------------
;; Ticket patches
;; ---------------------------------------------------------------------------

(deftest settings-draft-patch-test
  (let [patch (ladder-settings/settings->draft-patch settings-13-2)]
    (is (= (count patch) (count (distinct (paths patch)))) "each path once")
    (is (every? #(contains? transitions/draft-path-whitelist %) (paths patch)) "only whitelisted draft paths")
    (is (= (:draft settings-13-2) (apply-patch patch)) "the deployed draft round-trips into the ticket")
    (is (= [] (ladder-settings/settings->draft-patch nil)))
    (is (= [] (ladder-settings/settings->draft-patch (assoc settings-13-2 :draft nil))))))

(deftest preview-patch-from-13-2-settings-test
  ;; Spec 13.4: 33 live buy units after the 76.4 buy was rejected, 30 sell.
  (let [patch (replace-flow/preview-patch settings-13-2 {:buy "33" :sell "30"})
        draft (apply-patch patch)]
    (is (= (count patch) (count (distinct (paths patch)))))
    (is (every? #(contains? transitions/draft-path-whitelist %) (paths patch)))
    (is (= (-> (:draft settings-13-2)
               (assoc-in [:buy :quantity] "33")
               (assoc-in [:sell :quantity] "30"))
           draft)
        "the deployed settings with the live remainder")
    (is (= "2900" (get-in draft [:buy :max-usdc-debit])) "a deployed cap is kept")
    (is (= "80" (get-in draft [:anchor :custom-price])))
    (is (= 4 (get-in draft [:buy :count])))))

(deftest usdc-entered-sides-become-base-and-the-buy-cap-rule-test
  (let [usdc-draft (-> fx/draft-13-2
                       (assoc :buy {:enabled? true :amount-unit :quote :quote-amount "2888" :quantity ""
                                    :max-usdc-debit "" :count 4 :start-sigma "0.75" :end-sigma "2.25"
                                    :skew "1.857142857143"})
                       (assoc-in [:sell :amount-unit] :quote)
                       (assoc-in [:sell :quote-amount] "2652"))
        settings (assoc settings-13-2 :draft (vol-range-draft/normalize-vol-range-draft usdc-draft))
        draft (apply-patch (replace-flow/preview-patch settings {:buy "40" :sell "30"}))]
    (is (= [:base "40" true] ((juxt :amount-unit :quantity :enabled?) (:buy draft))))
    (is (= [:base "30" true] ((juxt :amount-unit :quantity :enabled?) (:sell draft))))
    (is (= "2888" (get-in draft [:buy :max-usdc-debit])) "buys entered in USDC: that amount caps the debit")
    (testing "blank cap with base buys stays blank (Use plan total applies)"
      (let [settings (assoc-in settings-13-2 [:draft :buy :max-usdc-debit] "")]
        (is (= "" (get-in (apply-patch (replace-flow/preview-patch settings {:buy "40" :sell "30"}))
                          [:buy :max-usdc-debit])))))
    (testing "an entered cap wins over the USDC amount"
      (let [settings (assoc-in settings [:draft :buy :max-usdc-debit] "2500")]
        (is (= "2500" (get-in (apply-patch (replace-flow/preview-patch settings {:buy "40" :sell "30"}))
                              [:buy :max-usdc-debit])))))))

(deftest preview-patch-without-recorded-settings-only-sets-quantities-test
  (is (= [[[:buy :amount-unit] :base] [[:buy :quantity] "33"] [[:buy :enabled?] true]
          [[:sell :amount-unit] :base] [[:sell :quantity] "0"] [[:sell :enabled?] false]]
         (replace-flow/preview-patch nil {:buy "33" :sell "0"}))))

(def basis
  {:ready? true :blocking []
   :remaining {:buy "40" :sell "27"} :filled {:buy "0" :sell "3"} :filled-during-update {:buy "0" :sell "3"}
   :rejected [{:logical-id "buy-0" :side :buy :rung-index 0 :price "76.4" :original-base "7" :reason "crossed"}]})

(deftest quantities-patch-test
  (testing "the frozen remainder by default; rejected members are never included silently"
    (is (= [[[:buy :amount-unit] :base] [[:buy :quantity] "40"] [[:buy :enabled?] true]
            [[:sell :amount-unit] :base] [[:sell :quantity] "27"] [[:sell :enabled?] true]]
           (replace-flow/quantities-patch basis #{})))
    (is (= {:buy "40" :sell "27"} (replace-flow/included-quantities basis nil))))
  (testing "an explicitly selected rejected buy adds its full quantity"
    (is (= {:buy "47" :sell "27"} (replace-flow/included-quantities basis #{"buy-0"})))
    (is (some #{[[:buy :quantity] "47"]} (replace-flow/quantities-patch basis #{"buy-0"}))))
  (testing "ids that are not listed rejected members add nothing"
    (is (= {:buy "40" :sell "27"} (replace-flow/included-quantities basis #{"sell-2"}))))
  (testing "a side with nothing to place is disabled"
    (let [patch (replace-flow/quantities-patch (assoc basis :remaining {:buy "0" :sell "27"}) #{})]
      (is (some #{[[:buy :enabled?] false]} patch))
      (is (some #{[[:buy :quantity] "0"]} patch))
      (is (some #{[[:sell :enabled?] true]} patch)))))

(deftest phase-after-confirm-test
  (is (= :canceled (replace-flow/phase-after-confirm {:ready? true})))
  (is (= :blocked (replace-flow/phase-after-confirm {:ready? false :blocking [:replace/legs-still-open]})))
  (is (= :blocked (replace-flow/phase-after-confirm nil))))

;; ---------------------------------------------------------------------------
;; Freeze and predecessor admission
;; ---------------------------------------------------------------------------

(def c1 "0x484f5652000000000000000000000001")
(def c2 "0x484f5652000000000000000000000002")
(def scope {:network :hyperliquid-mainnet :account-address "0xabc" :coin "SYN/USDC" :asset-id 10001})

(defn- live-grid [grid-id & [extra]]
  (-> (lifecycle/new-grid (merge {:grid-id grid-id :scope scope :scope-key "k" :operation-id (str grid-id "-op")
                                  :plan-id "p" :expires-after-ms 31000 :now-ms 1000
                                  :legs [{:leg-id "buy-0" :side :buy :rung-index 0 :price "76.4" :size "7" :cloid c1}
                                         {:leg-id "sell-0" :side :sell :rung-index 0 :price "84" :size "10"
                                          :cloid c2}]}
                                 extra))
      (lifecycle/mark-sending (str grid-id "-op") "a1" 1500)
      (evidence/apply-placement-delivery (str grid-id "-op")
                                         {:delivery :response
                                          :leg-results [{:cloid c1 :oid "11" :placement-state :accepted
                                                         :order-state :resting}
                                                        {:cloid c2 :oid "12" :placement-state :accepted
                                                         :order-state :resting}]}
                                         2000)))

(defn- fill-row [sz tid]
  {:coin "SYN/USDC" :px "84" :sz sz :side "A" :time 2500 :oid 12 :tid tid :fee "0" :feeToken "USDC"
   :builderFee "0" :crossed false})

(defn- canceled [g]
  (-> g
      (evidence/apply-order-statuses
       [{:id "11" :status "canceled" :order {:order {:coin "SYN/USDC" :oid 11 :sz "7" :origSz "7"}}}
        {:id "12" :status "canceled" :order {:order {:coin "SYN/USDC" :oid 12 :sz "8" :origSz "10"}}}]
       5000)
      (lifecycle/note-fills-read 9000)))

(deftest freeze-captures-membership-and-fills-test
  (let [g (evidence/ingest-fills (live-grid "old") {:account-address "0xabc" :rows [(fill-row "2" 1)]
                                                    :received-at-ms 3000})]
    (is (= {:frozen-cloids [c1 c2] :filled-at-freeze {c1 "0" c2 "2"}} (replace-flow/freeze g)))))

(defn- records [& grids] (mapv #(jr/grid-record % 10000) grids))

(defn- predecessor [old]
  (merge (select-keys (replace-flow/freeze old) [:frozen-cloids])
         {:grid-id "old" :remaining {:buy "7" :sell "8"} :filled-during-update {:buy "0" :sell "0"}
          :included-rejected []}))

(deftest predecessor-blockers-test
  (let [live (live-grid "old")
        p (predecessor live)
        settled (canceled (evidence/ingest-fills live {:account-address "0xabc" :rows [(fill-row "2" 1)]
                                                       :received-at-ms 3000}))]
    (testing "a settled predecessor in the same scope admits the replacement"
      (is (= [] (replace-flow/predecessor-blockers (records settled) p "k"))))
    (testing ":replace/predecessor-missing"
      (is (= [:replace/predecessor-missing] (replace-flow/predecessor-blockers (records) p "k")))
      (is (= [:replace/predecessor-missing]
             (replace-flow/predecessor-blockers (records settled) (assoc p :grid-id "other") "k")))
      (is (= [:replace/predecessor-missing]
             (replace-flow/predecessor-blockers (records settled) (assoc p :frozen-cloids []) "k")))
      (is (= [:replace/predecessor-missing]
             (replace-flow/predecessor-blockers (records settled) (update p :frozen-cloids conj "0xdead") "k"))
          "a frozen membership that is not the ladder's legs"))
    (testing ":replace/predecessor-scope"
      (is (= [:replace/predecessor-scope] (replace-flow/predecessor-blockers (records settled) p "other-k"))))
    (testing ":replace/predecessor-not-settled"
      (is (= [:replace/predecessor-not-settled] (replace-flow/predecessor-blockers (records live) p "k"))
          "old legs still open")
      (let [unknown (-> live
                        (lifecycle/add-cancel-operation {:operation-id "cx" :cloids [c1 c2]
                                                         :expires-after-ms 40000 :now-ms 3000})
                        (lifecycle/mark-sending "cx" "a2" 3001)
                        (evidence/apply-cancel-delivery "cx" {:delivery :unknown} 3002))]
        (is (= [:replace/predecessor-not-settled] (replace-flow/predecessor-blockers (records unknown) p "k"))
            "an unknown cancel outcome"))
      (is (= [:replace/predecessor-not-settled]
             (replace-flow/predecessor-blockers (records (dissoc settled :fills-through-ms)) p "k"))
          "fills not covered"))
    (testing ":replace/already-replaced"
      (let [successor (live-grid "new" {:predecessor p})]
        (is (= [:replace/already-replaced] (replace-flow/predecessor-blockers (records settled successor) p "k")))
        (testing "a successor that never placed anything does not use up the old ladder"
          (let [refused (-> (lifecycle/new-grid {:grid-id "new" :scope scope :scope-key "k" :operation-id "n-op"
                                                 :plan-id "p" :expires-after-ms 31000 :now-ms 1000 :predecessor p
                                                 :legs [{:leg-id "buy-0" :side :buy :rung-index 0 :price "76.4"
                                                         :size "7" :cloid "0x484f5652000000000000000000000009"}]})
                            (lifecycle/mark-sending "n-op" "a3" 1500)
                            (evidence/apply-placement-delivery
                             "n-op" {:delivery :response
                                     :leg-results [{:cloid "0x484f5652000000000000000000000009"
                                                    :placement-state :rejected :reason "crossed"}]}
                             2000))]
            (is (= :not-placed (lifecycle/status refused)))
            (is (= [] (replace-flow/predecessor-blockers (records settled refused) p "k")))))))))
