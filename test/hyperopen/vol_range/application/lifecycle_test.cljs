(ns hyperopen.vol-range.application.lifecycle-test
  "Pure lifecycle rules (HO-SPEC-014 sections 8.4-8.6, 9.2, 10.1; VR-12, VR-14,
  VR-18)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.application.lifecycle-recovery :as recovery]
            [hyperopen.vol-range.application.wire :as wire]))

(def c1 "0x484f5652000000000000000000000001")
(def c2 "0x484f5652000000000000000000000002")

(def scope {:network :hyperliquid-mainnet :account-address "0xabc" :coin "SYN/USDC" :asset-id 10001})

(defn- grid []
  (-> (lifecycle/new-grid {:grid-id "g" :scope scope :scope-key "k" :operation-id "op" :plan-id "p"
                           :expires-after-ms 31000 :now-ms 1000
                           :legs [{:leg-id "buy-0" :side :buy :rung-index 0 :price "76.4" :size "7" :cloid c1}
                                  {:leg-id "sell-0" :side :sell :rung-index 0 :price "84" :size "10" :cloid c2}]})
      (lifecycle/mark-sending "op" "a1" 1500)))

(defn- resting-response [g]
  (evidence/apply-placement-delivery g "op" {:delivery :response
                                             :leg-results [{:cloid c1 :oid "11" :placement-state :accepted :order-state :resting}
                                                           {:cloid c2 :oid "12" :placement-state :accepted :order-state :resting}]}
                                     2000))

(def fill-row {:coin "SYN/USDC" :px "84" :sz "3" :side "A" :time 2500 :oid 12 :tid 77 :fee "0.1"
               :feeToken "USDC" :builderFee "0" :crossed false})

(deftest a-fill-is-counted-once-across-snapshots-and-increments-test
  ;; VR-14
  (let [g (resting-response (grid))
        ingest #(evidence/ingest-fills %1 {:network :hyperliquid-mainnet :account-address "0xABC"
                                           :rows %2 :received-at-ms 3000})
        g (-> g (ingest [fill-row]) (ingest [fill-row fill-row]))
        leg (get-in g [:legs c2])]
    (is (= 1 (count (:fills g))))
    (is (= {:filled "3" :remaining "7" :canceled "0"} (lifecycle/leg-quantities g leg)))
    (testing "a reported filled size floors the ledger instead of adding to it"
      (let [g (update-in g [:legs c2] assoc :reported-filled-base "3")]
        (is (= "3" (:filled (lifecycle/leg-quantities g (get-in g [:legs c2])))))))
    (testing "the same trade id on another asset is a different fill"
      (is (= 1 (count (:fills (ingest g [(assoc fill-row :coin "OTHER/USDC")]))))
          "and it is not this grid's fill at all"))))

(deftest evidence-for-another-account-or-market-is-ignored-test
  ;; VR-18
  (let [g (grid)
        snap {:account-address "0xdef" :requested-at-ms 5000 :received-at-ms 5001 :complete? true
              :open-orders [{:coin "SYN/USDC" :cloid c1 :oid 11 :sz "7" :origSz "7"}]}]
    (is (= g (evidence/apply-orders-snapshot g snap)))
    (is (= g (evidence/apply-orders-snapshot g (assoc snap :account-address "0xabc"
                                                       :open-orders [{:coin "BTC/USDC" :cloid c1 :oid 11
                                                                      :sz "7" :origSz "7"}]))))
    (is (= g (evidence/ingest-fills g {:account-address "0xdef" :rows [fill-row] :received-at-ms 1})))))

(deftest absence-never-proves-non-placement-test
  (let [g (evidence/apply-placement-delivery (grid) "op" {:delivery :unknown :error "timeout"} 2000)
        snap {:account-address "0xabc" :requested-at-ms 5000 :received-at-ms 5001 :complete? true :open-orders []}
        g (evidence/apply-orders-snapshot g snap)
        g (evidence/apply-order-statuses g [{:id c1 :status "unknownOid"}] 6000)]
    (is (= :unknown (get-in g [:legs c1 :placement-state])))
    (is (= :checking (lifecycle/status g)))
    (is (= [c1 c2] (sort (lifecycle/status-lookup-ids g))))))

(deftest accepted-legs-are-never-downgraded-and-terminal-legs-never-resurrected-test
  (let [g (resting-response (grid))
        g (evidence/apply-order-statuses g [{:id "11" :status "canceled"
                                             :order {:order {:coin "SYN/USDC" :oid 11 :sz "7" :origSz "7"}}}]
                                         3000)
        stale {:account-address "0xabc" :requested-at-ms 2500 :received-at-ms 3500 :complete? true
               :open-orders [{:coin "SYN/USDC" :cloid c1 :oid 11 :sz "7" :origSz "7"}]}
        g (evidence/apply-orders-snapshot g stale)]
    (is (= :terminal (get-in g [:legs c1 :order-state])) "an older snapshot cannot resurrect it")
    (is (= :accepted (get-in g [:legs c1 :placement-state])))))

(deftest unrecognized-response-leaves-legs-unknown-test
  ;; VR-12 through the lifecycle: an unfamiliar entry is never success.
  (let [g (evidence/apply-placement-delivery (grid) "op"
                                             {:delivery :response
                                              :leg-results [{:cloid c1 :placement-state :unknown}
                                                            {:cloid c2 :oid "12" :placement-state :accepted
                                                             :order-state :resting}]}
                                             2000)]
    (is (= :checking (lifecycle/status g)))
    (is (= :unresolved (get-in g [:operations "op" :state])))))

(deftest order-action-uses-the-signing-field-order-test
  (let [a (wire/order-action 10001 [{:side :sell :rung-index 0 :price "84" :size "10" :cloid c2}
                                    {:side :buy :rung-index 0 :price "76.4" :size "7" :cloid c1}])]
    (is (= [:type :orders :grouping] (keys a)))
    (is (= [:a :b :p :s :r :t :c] (keys (first (:orders a)))))
    (is (= [true false] (map :b (:orders a))) "buys first")
    (is (every? #(= {:limit {:tif "Alo"}} (:t %)) (:orders a)))
    (is (every? #(false? (:r %)) (:orders a)))
    (is (nil? (wire/order-action 10001 [])) "no empty action")))

(deftest cancel-actions-target-each-order-once-test
  (let [[by-oid by-cloid] (wire/cancel-actions 10001 [{:cloid c1 :oid "11"} {:cloid c2 :oid nil}])]
    (is (= {:type "cancel" :cancels [{:a 10001 :o 11}]} (:action by-oid)))
    (is (= {:type "cancelByCloid" :cancels [{:asset 10001 :cloid c2}]} (:action by-cloid)))))

(deftest merge-keeps-the-most-advanced-copy-test
  (let [base (resting-response (grid))
        stale base
        advanced (-> base
                     (evidence/apply-order-statuses [{:id "11" :status "filled"
                                                      :order {:order {:coin "SYN/USDC" :oid 11 :sz "0" :origSz "7"}}}]
                                                    3000))
        merged (recovery/merge-grids advanced stale)]
    (is (= :terminal (get-in merged [:legs c1 :order-state])) "an older copy never reopens a leg")
    (is (= :resolved (get-in merged [:operations "op" :state])))))

(deftest a-not-resting-cancel-with-an-open-order-fails-after-its-deadline-test
  (let [g (-> (resting-response (grid))
              (lifecycle/add-cancel-operation {:operation-id "cx" :cloids [c1] :expires-after-ms 40000 :now-ms 10000})
              (evidence/apply-cancel-delivery "cx" {:delivery :response
                                                    :results [{:cloid c1 :cancel-state :not-resting}]}
                                              10001))
        open [{:id "11" :status "open" :order {:order {:coin "SYN/USDC" :oid 11 :sz "7" :origSz "7"}}}]]
    (is (= :checking (lifecycle/status (evidence/apply-order-statuses g open 50000)))
        "still possibly canceled before the deadline")
    (let [g (evidence/apply-order-statuses g open 90000)]
      (is (= :failed (get-in g [:legs c1 :cancel-state])))
      (is (= :live (lifecycle/status g))))))

(deftest an-unsent-cancel-restores-an-earlier-unknown-cancel-test
  (let [g (-> (resting-response (grid))
              (lifecycle/add-cancel-operation {:operation-id "cx" :cloids [c1] :expires-after-ms 40000 :now-ms 10000})
              (evidence/apply-cancel-delivery "cx" {:delivery :unknown} 10001)
              (lifecycle/add-cancel-operation {:operation-id "cy" :cloids [c1] :expires-after-ms 50000 :now-ms 20000})
              (evidence/apply-cancel-delivery "cy" {:delivery :not-sent} 20001))]
    (is (= :unknown (get-in g [:legs c1 :cancel-state])))
    (is (= :checking (lifecycle/status g)))))

(def ^:private settings
  {:plan-id "p" :draft {:buy {:quantity "40"}} :limits {:core "120" :target "150" :maximum "190"}
   :totals {:leg-count 2} :inventory-outcomes {:maximum "190"}})

(defn- grid-with-settings []
  (-> (lifecycle/new-grid {:grid-id "g" :scope scope :scope-key "k" :operation-id "op" :plan-id "p"
                           :expires-after-ms 31000 :now-ms 1000 :settings settings
                           :legs [{:leg-id "buy-0" :side :buy :rung-index 0 :price "76.4" :size "7" :cloid c1}
                                  {:leg-id "sell-0" :side :sell :rung-index 0 :price "84" :size "10" :cloid c2}]})
      (lifecycle/mark-sending "op" "a1" 1500)))

(deftest new-grid-records-settings-test
  (is (= settings (:settings (grid-with-settings))))
  (is (not (contains? (grid) :settings)) "a grid placed without settings has no key at all")
  (is (= (dissoc (grid-with-settings) :settings) (grid)) "settings change nothing else"))

(deftest merge-grids-keeps-settings-test
  (let [old-copy (grid-with-settings)
        new-copy (resting-response old-copy)]
    (testing "an older copy and a newer copy of a ladder placed with settings"
      (is (= settings (:settings (recovery/merge-grids old-copy new-copy))))
      (is (= settings (:settings (recovery/merge-grids new-copy old-copy)))))
    (testing "a copy without settings never erases them"
      (let [merged (recovery/merge-grids new-copy (dissoc old-copy :settings))]
        (is (= settings (:settings merged)))
        (is (= :resolved (get-in merged [:operations "op" :state])) "evidence still merges")))
    (testing "a ladder placed before settings were recorded stays without them"
      (is (not (contains? (recovery/merge-grids (grid) (resting-response (grid))) :settings))))))

;; ---------------------------------------------------------------------------
;; Replacement over a frozen membership (spec 10.2, 13.4)
;; ---------------------------------------------------------------------------

(def c3 "0x484f5652000000000000000000000003")
(def c4 "0x484f5652000000000000000000000004")

(defn- three-leg-grid
  "buy-0 (7, oid 11), sell-0 (10, oid 12) and sell-1 (10, oid 14), all resting."
  []
  (-> (lifecycle/new-grid {:grid-id "g" :scope scope :scope-key "k" :operation-id "op" :plan-id "p"
                           :expires-after-ms 31000 :now-ms 1000
                           :legs [{:leg-id "buy-0" :side :buy :rung-index 0 :price "76.4" :size "7" :cloid c1}
                                  {:leg-id "sell-0" :side :sell :rung-index 0 :price "84" :size "10" :cloid c2}
                                  {:leg-id "sell-1" :side :sell :rung-index 1 :price "86" :size "10" :cloid c4}]})
      (lifecycle/mark-sending "op" "a1" 1500)
      (evidence/apply-placement-delivery "op" {:delivery :response
                                               :leg-results [{:cloid c1 :oid "11" :placement-state :accepted
                                                              :order-state :resting}
                                                             {:cloid c2 :oid "12" :placement-state :accepted
                                                              :order-state :resting}
                                                             {:cloid c4 :oid "14" :placement-state :accepted
                                                              :order-state :resting}]}
                                         2000)))

(defn- canceled-status [oid sz orig]
  {:id oid :status "canceled" :order {:order {:coin "SYN/USDC" :oid (js/parseInt oid) :sz sz :origSz orig}}})

(defn- fill [tid sz time]
  (assoc fill-row :tid tid :sz sz :time time))

(defn- ingest [g rows at]
  (evidence/ingest-fills g {:account-address "0xabc" :rows rows :received-at-ms at}))

(deftest replace-membership-freezes-working-legs-across-repairs-test
  (let [g (-> (three-leg-grid)
              (evidence/apply-order-statuses [(canceled-status "14" "10" "10")] 3000)
              (lifecycle/add-cancel-operation {:operation-id "cx" :cloids [c2] :expires-after-ms 40000
                                               :now-ms 3100}))]
    (is (= [c1 c2] (lifecycle/replace-membership g))
        "a leg canceled earlier is out; a leg whose cancel was requested is in")))

(deftest frozen-basis-counts-only-the-frozen-legs-test
  (let [before (-> (three-leg-grid)
                   ;; Canceled from Open Orders before the update started.
                   (evidence/apply-order-statuses [(canceled-status "14" "10" "10")] 3000)
                   ;; 2 of sell-0 filled before the update started.
                   (ingest [(fill 1 "2" 2500)] 3000))
        frozen {:frozen-cloids (lifecycle/replace-membership before)
                :filled-at-freeze {c1 "0" c2 "2"}}
        after (-> before
                  ;; 3 more fill while the cancel is pending, then both cancels land.
                  (ingest [(fill 2 "3" 3500)] 4000)
                  (evidence/apply-order-statuses [(canceled-status "11" "7" "7") (canceled-status "12" "5" "10")]
                                                 5000)
                  (lifecycle/note-fills-read 9000))
        basis (lifecycle/replacement-basis after frozen)]
    (is (= [c1 c2] (:frozen-cloids frozen)))
    (is (:ready? basis) (pr-str (:blocking basis)))
    (is (= {:buy "7" :sell "5"} (:remaining basis)) "the earlier-canceled sell-1 adds nothing")
    (is (= {:buy "0" :sell "3"} (:filled-during-update basis)) "fills before the freeze are not during the update")
    (is (= {:buy "0" :sell "5"} (:filled basis)) "lifetime fills are kept for the record")
    (testing "the 1-arity still sums every canceled leg"
      (is (= "15" (get-in (lifecycle/replacement-basis after) [:remaining :sell]))))
    (testing "a missing freeze record means nothing had filled at the freeze"
      (is (= "5" (get-in (lifecycle/replacement-basis after (dissoc frozen :filled-at-freeze))
                         [:filled-during-update :sell]))))))

(deftest a-leg-added-after-the-freeze-blocks-and-never-adds-test
  (let [g (three-leg-grid)
        frozen {:frozen-cloids [c1 c2] :filled-at-freeze {}}
        canceled (-> g
                     (evidence/apply-order-statuses [(canceled-status "11" "7" "7") (canceled-status "12" "10" "10")
                                                     (canceled-status "14" "10" "10")]
                                                    5000)
                     (lifecycle/note-fills-read 9000))
        ;; c4 was not in the freeze (say, a repair from another tab) and is still open.
        open-extra (update-in canceled [:legs c4] assoc :order-state :resting :terminal-reason nil)]
    (is (:ready? (lifecycle/replacement-basis canceled frozen)))
    (is (= {:buy "7" :sell "10"} (:remaining (lifecycle/replacement-basis canceled frozen)))
        "c4 is canceled but was not frozen, so it adds nothing")
    (let [basis (lifecycle/replacement-basis open-extra frozen)]
      (is (not (:ready? basis)))
      (is (= [:replace/legs-still-open] (:blocking basis)) "blocking is judged over the whole ladder"))))

(deftest a-repaired-member-counts-once-and-historical-rejections-are-ignored-test
  (let [g (-> (lifecycle/new-grid {:grid-id "g" :scope scope :scope-key "k" :operation-id "op" :plan-id "p"
                                   :expires-after-ms 31000 :now-ms 1000
                                   :legs [{:leg-id "buy-0" :side :buy :rung-index 0 :price "76.4" :size "7" :cloid c1}
                                          {:leg-id "sell-0" :side :sell :rung-index 0 :price "84" :size "10"
                                           :cloid c2}]})
              (lifecycle/mark-sending "op" "a1" 1500)
              (evidence/apply-placement-delivery "op" {:delivery :response
                                                       :leg-results [{:cloid c1 :placement-state :rejected
                                                                      :reason "Post only order would have immediately matched"}
                                                                     {:cloid c2 :oid "12" :placement-state :accepted
                                                                      :order-state :resting}]}
                                                 2000))
        repaired (-> (:grid (lifecycle/add-repair g {:operation-id "rp" :legs [{:logical-id "buy-0" :cloid c3
                                                                                 :price "76.16" :size "7"}]
                                                     :expires-after-ms 40000 :now-ms 3000}))
                     (lifecycle/mark-sending "rp" "a2" 3100)
                     (evidence/apply-placement-delivery "rp" {:delivery :response
                                                              :leg-results [{:cloid c3 :oid "13" :placement-state :accepted
                                                                             :order-state :resting}]}
                                                        3200))
        frozen-cloids (lifecycle/replace-membership repaired)
        done (-> repaired
                 (evidence/apply-order-statuses [(canceled-status "12" "10" "10") (canceled-status "13" "7" "7")] 5000)
                 (lifecycle/note-fills-read 9000))
        basis (lifecycle/replacement-basis done {:frozen-cloids frozen-cloids :filled-at-freeze {}})]
    (is (= [c3 c2] frozen-cloids) "the repaired member's current leg, never its rejected copy")
    (is (:ready? basis))
    (is (= {:buy "7" :sell "10"} (:remaining basis)) "the repaired member counts once")
    (is (= [] (:rejected basis)) "the historical rejected copy is not offered for revival")
    (testing "a current rejected member is listed separately"
      (let [basis (lifecycle/replacement-basis
                   (-> g
                       (evidence/apply-order-statuses [(canceled-status "12" "10" "10")] 5000)
                       (lifecycle/note-fills-read 9000))
                   {:frozen-cloids [c2]})]
        (is (= {:buy "0" :sell "10"} (:remaining basis)))
        (is (= ["buy-0"] (mapv :logical-id (:rejected basis))))))))

(deftest new-grid-records-a-predecessor-test
  (let [p {:grid-id "old" :frozen-cloids [c1] :remaining {:buy "7" :sell "0"}
           :filled-during-update {:buy "0" :sell "0"} :included-rejected []}
        g (lifecycle/new-grid {:grid-id "g" :scope scope :scope-key "k" :operation-id "op" :plan-id "p"
                               :expires-after-ms 31000 :now-ms 1000 :predecessor p
                               :legs [{:leg-id "buy-0" :side :buy :rung-index 0 :price "76.4" :size "7" :cloid c1}]})]
    (is (= p (:predecessor g)))
    (is (not (contains? (grid) :predecessor)) "an ordinary ladder has no key at all")))

(deftest merge-grids-keeps-the-predecessor-test
  (let [p {:grid-id "old" :frozen-cloids [c1] :remaining {:buy "7" :sell "0"}
           :filled-during-update {:buy "0" :sell "0"} :included-rejected []}
        with-p (assoc (resting-response (grid)) :predecessor p)]
    (is (= p (:predecessor (recovery/merge-grids with-p (dissoc with-p :predecessor))))
        "a copy without the lineage never erases it")
    (is (= p (:predecessor (recovery/merge-grids nil with-p))))
    (is (not (contains? (recovery/merge-grids (grid) (resting-response (grid))) :predecessor)))))
