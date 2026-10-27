(ns hyperopen.trading.vol-range-draft-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading.order-form-ownership :as ownership]
            [hyperopen.trading.order-form-state :as order-form-state]
            [hyperopen.trading.vol-range-draft :as draft]
            [hyperopen.vol-range.actions :as actions]
            [hyperopen.vol-range.fixtures :as fx]
            [hyperopen.vol-range.state-fixtures :as sf]))

(def legacy-scale {:start "10" :end "20" :count 7 :skew "1.50"})

(deftest legacy-scale-normalizes-identically-test
  (testing "a legacy Scale map keeps exactly the four ordinary keys"
    (is (= {:start "10" :end "20" :count 7 :skew "1.50"}
           (order-form-state/normalize-scale-form legacy-scale)))
    (is (= {:start "" :end "" :count 5 :skew "1.00"}
           (order-form-state/normalize-scale-form nil)))
    (is (= {:start "" :end "" :count 5 :skew "1.00"}
           (order-form-state/normalize-scale-form (:scale (order-form-state/default-order-form))))))
  (testing "the default order form is unchanged and reads as Price range"
    (is (= {:start "" :end "" :count 5 :skew "1.00"}
           (:scale (order-form-state/default-order-form))))
    (is (= :price-range (draft/scale-mode (order-form-state/default-order-form))))
    (is (= {:start "" :end "" :count 5 :skew "1.00"}
           (:scale (order-form-state/normalize-order-form (order-form-state/default-order-form))))))
  (testing "missing, legacy and unknown modes read as Price range"
    (is (= :price-range (draft/scale-mode {:scale legacy-scale})))
    (is (= :price-range (draft/scale-mode {:scale {:mode :bogus}})))
    (is (= :price-range (draft/scale-mode {:scale {:mode "nonsense"}})))
    (is (= :price-range (draft/scale-mode nil)))
    (is (= :volatility-range (draft/scale-mode {:scale {:mode :volatility-range}})))
    (is (= :volatility-range (draft/scale-mode {:scale {:mode "volatility-range"}})))))

(deftest default-draft-matches-spec-7-1-test
  (is (= {:anchor {:mode :vwap-24h :custom-price ""}
          :volatility {:mode :auto :manual-daily-pct ""}
          :inventory {:protected-core "" :target "" :maximum ""
                      :tilt-enabled? false :tilt-strength "0.45"}
          :buy {:enabled? true :amount-unit :base :quantity "" :quote-amount "" :max-usdc-debit "" :count 4
                :start-sigma "0.75" :end-sigma "2.25" :skew "1.00"}
          :sell {:enabled? false :amount-unit :base :quantity "" :quote-amount "" :count 3
                 :start-sigma "1.25" :end-sigma "2.25" :skew "1.00"}}
         (draft/default-vol-range-draft)))
  (is (= (draft/default-vol-range-draft) (draft/normalize-vol-range-draft nil)))
  (is (= (draft/default-vol-range-draft) (draft/normalize-vol-range-draft {}))))

(deftest normalize-vol-range-draft-coercions-test
  (let [normalized (draft/normalize-vol-range-draft
                    {:anchor {:mode "custom" :custom-price 80}
                     :volatility {:mode "manual" :manual-daily-pct 6.5}
                     :inventory {:tilt-enabled? "yes" :tilt-strength 0.5 :protected-core nil}
                     :buy {:enabled? nil :count "6" :quantity 7 :skew 1.5}
                     :sell {:count 3.0 :junk 1}
                     :unknown {:x 1}})]
    (is (= :custom (get-in normalized [:anchor :mode])))
    (is (= "80" (get-in normalized [:anchor :custom-price])))
    (is (= :manual (get-in normalized [:volatility :mode])))
    (is (= "6.5" (get-in normalized [:volatility :manual-daily-pct])))
    (is (true? (get-in normalized [:inventory :tilt-enabled?])))
    (is (= "0.5" (get-in normalized [:inventory :tilt-strength])))
    (is (= "" (get-in normalized [:inventory :protected-core])))
    (is (false? (get-in normalized [:buy :enabled?])))
    (is (= "6" (get-in normalized [:buy :count])) "string counts stay as given")
    (is (= "7" (get-in normalized [:buy :quantity])))
    (is (= "1.5" (get-in normalized [:buy :skew])))
    (is (= 3 (get-in normalized [:sell :count])))
    (is (not (contains? normalized :unknown)))
    (is (not (contains? (:sell normalized) :junk))))
  (testing "unknown enums fall back to the defaults"
    (let [normalized (draft/normalize-vol-range-draft {:anchor {:mode :bogus} :volatility {:mode "x"}})]
      (is (= :vwap-24h (get-in normalized [:anchor :mode])))
      (is (= :auto (get-in normalized [:volatility :mode]))))))

(deftest full-draft-survives-normalization-ownership-and-save-many-test
  (let [state (sf/base-state)
        form (:order-form state)
        expected (draft/normalize-vol-range-draft fx/draft-13-2)]
    (testing "order-form-draft keeps the mode and the nested draft"
      (let [normalized (trading/order-form-draft state)]
        (is (= :volatility-range (get-in normalized [:scale :mode])))
        (is (= expected (get-in normalized [:scale :vol-range])))
        (is (= (select-keys legacy-scale [:start :end :count :skew])
               (select-keys (order-form-state/normalize-scale-form
                             (assoc legacy-scale :mode :volatility-range :vol-range fx/draft-13-2))
                            [:start :end :count :skew])))))
    (testing "enforce-field-ownership and persist-order-form preserve it"
      (let [owned (ownership/enforce-field-ownership state {:order-form (trading/order-form-draft state)})]
        (is (= expected (get-in owned [:order-form :scale :vol-range])))
        (is (= :volatility-range (get-in owned [:order-form :scale :mode]))))
      (let [persisted (trading/persist-order-form (trading/order-form-draft state))]
        (is (= expected (get-in persisted [:scale :vol-range])))
        (is (= :volatility-range (get-in persisted [:scale :mode])))))
    (testing "the real save-many path values round-trip the draft"
      (let [effects (actions/update-vol-range-draft state [:buy :quantity] "41")
            next-state (sf/apply-effects state effects)
            read-back (trading/order-form-draft next-state)]
        (is (= "41" (get-in read-back [:scale :vol-range :buy :quantity])))
        (is (= :volatility-range (get-in read-back [:scale :mode])))
        (is (= (assoc-in expected [:buy :quantity] "41")
               (get-in read-back [:scale :vol-range])))
        (is (= (select-keys (:scale (trading/order-form-draft state)) [:start :end :count :skew])
               (select-keys (:scale read-back) [:start :end :count :skew])))))
    (is (map? form))))

(deftest unscoped-export-projection-blanks-exactly-the-scoped-paths-test
  (let [form (-> (sf/base-state)
                 :order-form
                 (assoc-in [:scale :vol-range :anchor :mode] :custom)
                 (assoc-in [:scale :vol-range :inventory :tilt-enabled?] true)
                 (assoc-in [:scale :vol-range :sell :enabled?] true)
                 (assoc-in [:scale :vol-range :sell :quantity] "5")
                 (assoc-in [:scale :vol-range :buy :count] 6))
        projected (draft/unscoped-export-projection form)]
    (is (= ["" "" "" "" "" "" "" "" ""]
           (mapv #(get-in projected (into [:scale :vol-range] %)) draft/scoped-draft-paths)))
    (testing "every other path is untouched"
      (let [blank (fn [f] (reduce (fn [acc path] (assoc-in acc (into [:scale :vol-range] path) ""))
                                  f draft/scoped-draft-paths))]
        (is (= (blank form) projected))))
    (is (= :custom (get-in projected [:scale :vol-range :anchor :mode])))
    (is (= :volatility-range (get-in projected [:scale :mode])))
    (is (= 6 (get-in projected [:scale :vol-range :buy :count])))
    (is (true? (get-in projected [:scale :vol-range :inventory :tilt-enabled?])))
    (testing "forms without a draft are returned unchanged"
      (let [legacy (order-form-state/default-order-form)]
        (is (= legacy (draft/unscoped-export-projection legacy)))))
    (testing "the localized numeric paths include the scoped ones"
      (is (every? draft/localized-numeric-draft-paths draft/scoped-draft-paths))
      (is (contains? draft/localized-numeric-draft-paths [:volatility :manual-daily-pct]))
      (is (contains? draft/localized-numeric-draft-paths [:sell :skew])))))
