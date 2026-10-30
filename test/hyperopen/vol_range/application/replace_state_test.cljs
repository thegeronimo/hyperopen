(ns hyperopen.vol-range.application.replace-state-test
  "Predicates over an in-progress ladder update (HO-SPEC-014 section 10.2):
  review blocking, the planner's account and the frozen lineage. The
  namespace stays A0-scan safe (VR-24)."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.draft-scope :as draft-scope]
            [hyperopen.vol-range.application.planner :as planner]
            [hyperopen.vol-range.application.replace-state :as replace-state]
            [hyperopen.vol-range.fixtures :as fx]
            [hyperopen.vol-range.state-fixtures :as sf]))

(def old-grid
  {:grid-id "old" :scope-key "set-below"
   :legs {"0xc1" {:cloid "0xc1" :oid "11" :side :buy}
          "0xc2" {:cloid "0xc2" :oid "12" :side :sell}
          "0xc3" {:cloid "0xc3" :oid nil :side :sell}}})

(defn- state-with
  ([update] (state-with update {}))
  ([update {:keys [scope-key grids]}]
   (let [s (sf/base-state)
         key (or scope-key (draft-scope/current-scope-key s))]
     (-> s
         (assoc-in [:vol-range :execution :grids] (or grids {"old" (assoc old-grid :scope-key key)}))
         (assoc-in [:vol-range :replace] (when update (assoc update :grid-id "old" :scope-key key)))))))

(def refreshed-account (fx/account-snapshot {:base {:total "147" :hold "0" :available "147"} :observed-at-ms 9000}))

(def basis {:ready? true :blocking [] :remaining {:buy "40" :sell "27"} :filled {:buy "0" :sell "3"}
            :filled-during-update {:buy "0" :sell "3"} :rejected []})

(deftest current-and-phase-test
  (let [s (state-with {:phase :preview})]
    (is (some? (draft-scope/current-scope-key s)) "the fixture has a scope")
    (is (true? (replace-state/current? s)))
    (is (= :preview (replace-state/phase s))))
  (is (false? (replace-state/current? (state-with nil))))
  (is (false? (replace-state/current? (state-with {:phase :preview} {:scope-key "another-market"})))
      "an update for another market or account is stale")
  (is (false? (replace-state/current? (state-with {:phase :preview} {:grids {}})))
      "the ladder is no longer in the store"))

(deftest review-blocked-per-phase-test
  (doseq [phase [:preview :canceling :blocked :canceled]]
    (is (true? (replace-state/review-blocked? (state-with {:phase phase}))) (str phase)))
  (is (false? (replace-state/review-blocked? (state-with {:phase :ready}))) "the replacement review is open")
  (is (false? (replace-state/review-blocked? (state-with nil))) "no update")
  (is (false? (replace-state/review-blocked? (state-with {:phase :preview} {:scope-key "another-market"})))
      "an update left on another market does not block this ticket"))

(deftest account-for-plan-test
  (let [account (fx/account-snapshot {:open-orders [{:oid "11" :side :buy :price "76.4" :remaining "7"}
                                                    {:oid "12" :side :sell :price "86" :remaining "10"}
                                                    {:oid "99" :side :buy :price "70" :remaining "1"}]})]
    (testing "in preview the old ladder's own orders are not existing exposure"
      (is (= [{:oid "99" :side :buy :price "70" :remaining "1"}]
             (:open-orders (replace-state/account-for-plan (state-with {:phase :preview}) account)))))
    (testing "after the cancel is confirmed the refreshed account replaces the snapshot"
      (doseq [phase [:canceled :ready]]
        (is (= refreshed-account
               (replace-state/account-for-plan (state-with {:phase phase :refreshed {:requested-at-ms 8000
                                                                                     :account refreshed-account}})
                                               account))
            (str phase))))
    (testing "otherwise unchanged"
      (doseq [s [(state-with {:phase :canceling}) (state-with {:phase :blocked}) (state-with nil)
                 (state-with {:phase :canceled})
                 (state-with {:phase :preview} {:scope-key "another-market"})
                 (state-with {:phase :ready :refreshed {:account refreshed-account}} {:scope-key "another-market"})]]
        (is (= account (replace-state/account-for-plan s account)))))))

(defn- dsum [xs] (precision/decimal->string (reduce precision/add (precision/parse-decimal "0")
                                                    (map precision/parse-decimal xs))))

(defn- ds [x] (precision/decimal->string x))
(defn- pd [x] (precision/parse-decimal x))

(defn- held-account
  "A 3000 USDC / 150 VRT account whose resting `orders` hold their funds:
  a buy price x remaining USDC, a sell its remaining VRT."
  [orders]
  (let [side-sum (fn [side f] (dsum (map f (filter #(= side (:side %)) orders))))
        quote-hold (side-sum :buy #(ds (precision/mul (pd (:price %)) (pd (:remaining %)))))
        base-hold (side-sum :sell :remaining)]
    (fx/account-snapshot
     {:quote {:total "3000" :hold quote-hold :available (ds (precision/sub (pd "3000") (pd quote-hold)))}
      :base {:total "150" :hold base-hold :available (ds (precision/sub (pd "150") (pd base-hold)))}
      :open-orders orders})))

(deftest preview-releases-the-old-ladders-holds-test
  ;; The 13.2 ladder live on a 3000 USDC / 150 VRT account: its resting buys
  ;; hold about 2900 USDC. The update reuses those funds once they are
  ;; canceled, so the preview must not count them twice.
  (let [plan (planner/prepare-plan (fx/plan-input))
        legs (vec (map-indexed (fn [i l] (assoc l :cloid (str "0xc" i) :oid (str (+ 100 i)))) (:legs plan)))
        key (draft-scope/current-scope-key (sf/base-state))
        grid {:grid-id "old" :scope-key key :legs (into {} (map (juxt :cloid identity)) legs)}
        orders (mapv (fn [l] {:oid (:oid l) :side (:side l) :price (:price l) :remaining (:size l)}) legs)
        s (state-with {:phase :preview} {:grids {"old" grid}})
        account (held-account orders)
        used (replace-state/account-for-plan s account)
        plan-with #(planner/prepare-plan (fx/plan-input {:account %}))]
    (is (= :ready (:status plan)) "the fixture ladder")
    (is (pos? (precision/cmp (pd (get-in account [:quote :hold])) (pd "2800"))) "the buys hold about 2900 USDC")
    (is (= [] (:open-orders used)) "the old ladder's orders are not existing exposure")
    (is (= {:total "3000" :hold "0" :available "3000"} (:quote used)) "the old buys' holds are released")
    (is (= {:total "150" :hold "0" :available "150"} (:base used)) "the old sells' holds are released")
    (is (= :invalid (:status (plan-with (assoc account :open-orders [])))) "with the holds left in it looks unfunded")
    (is (= :ready (:status (plan-with used))) "the update the old ladder's funds pay for is ready")
    (testing "another order keeps its hold"
      (let [other {:oid "99" :side :buy :price "70" :remaining "1"}
            used (replace-state/account-for-plan s (held-account (conj orders other)))]
        (is (= [other] (:open-orders used)))
        (is (= {:total "3000" :hold "70" :available "2930"} (:quote used)))))
    (testing "a release never exceeds the hold or the total"
      (let [thin (assoc account :quote {:total "3000" :hold "100" :available "2900"})]
        (is (= {:total "3000" :hold "0" :available "3000"} (:quote (replace-state/account-for-plan s thin))))))))

(deftest a-confirmed-read-gives-way-to-newer-store-balances-test
  ;; After the cancel is confirmed the planner uses that read, but only
  ;; until the store's own balances change (a transfer, another order).
  (let [live (fx/account-snapshot {:open-orders [{:oid "11" :side :buy :price "76.4" :remaining "7"}
                                                 {:oid "99" :side :buy :price "70" :remaining "1"}]})
        seen (replace-state/store-balances live)]
    (doseq [phase [:canceled :ready]]
      (let [s (state-with {:phase phase :refreshed {:account refreshed-account :store-balances seen}})]
        (is (= refreshed-account (replace-state/account-for-plan s live)) (str phase " store unchanged"))
        (let [moved (assoc live :base {:total "160" :hold "0" :available "160"})
              used (replace-state/account-for-plan s moved)]
          (is (= {:total "160" :hold "0" :available "160"} (:base used)) (str phase " the newer store balances"))
          (is (= (:quote live) (:quote used)) "holds are not released again")
          (is (= ["99"] (map :oid (:open-orders used))) "the canceled old orders are left out"))))
    (testing "without recorded store balances the confirmed read is kept"
      (is (= refreshed-account
             (replace-state/account-for-plan (state-with {:phase :ready :refreshed {:account refreshed-account}})
                                             (assoc live :base {:total "160" :hold "0" :available "160"})))))))

(deftest frozen-lineage-test
  (let [ready {:phase :ready :frozen-cloids ["0xc1" "0xc2"] :basis basis :include-rejected #{"buy-0"}}]
    (is (= {:grid-id "old" :frozen-cloids ["0xc1" "0xc2"] :remaining {:buy "40" :sell "27"}
            :filled-during-update {:buy "0" :sell "3"} :included-rejected ["buy-0"]}
           (replace-state/frozen-lineage (state-with ready))))
    (is (= [] (:included-rejected (replace-state/frozen-lineage (state-with (dissoc ready :include-rejected))))))
    (is (nil? (replace-state/frozen-lineage (state-with (assoc ready :phase :canceled)))))
    (is (nil? (replace-state/frozen-lineage (state-with (assoc-in ready [:basis :ready?] false)))))
    (is (nil? (replace-state/frozen-lineage (state-with (assoc ready :frozen-cloids [])))))
    (is (nil? (replace-state/frozen-lineage (state-with ready {:scope-key "another-market"}))))))

(def ^:private fs (js/require "fs"))

(deftest requires-no-execution-foundation-test
  (let [text (.readFileSync fs "src/hyperopen/vol_range/application/replace_state.cljs" "utf8")]
    (doseq [needle ["hyperopen.vol-range.application.lifecycle" "hyperopen.vol-range.application.replace-flow"
                    "hyperopen.vol-range.application.revalidation" "hyperopen.vol-range.application.journal-records"
                    "hyperopen.vol-range.infrastructure" "hyperopen.api.trading" "nonce"]]
      (is (not (str/includes? text needle)) needle))))
