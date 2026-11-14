(ns hyperopen.views.portfolio.vm.vaults-tab-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.metrics :as portfolio-metrics]
            [hyperopen.portfolio.vault-attribution-selector :as selector]
            [hyperopen.portfolio.vault-attribution.test-fixtures :as fx]
            [hyperopen.views.portfolio.vaults-tab-fixtures :as tf]
            [hyperopen.views.portfolio.vm.vaults-tab :as vm]
            [hyperopen.views.portfolio.vm.vaults-tab-chart :as chart]))

(defn- model
  [& slot-args]
  (vm/vaults-tab-model (tf/state-with (apply tf/ready-slot slot-args))))

(defn- row
  [m vault]
  (some #(when (= vault (:vault-address %)) %) (:rows m)))

(deftest states-test
  (testing "no account"
    (is (= :none (:status (vm/vaults-tab-model {})))))
  (testing "idle and loading with nothing held"
    (is (= :loading (:status (vm/vaults-tab-model (tf/state-with {:status :idle})))))
    (is (= :loading (:status (vm/vaults-tab-model
                              (tf/state-with {:status :loading :for-address tf/address}))))))
  (testing "error"
    (let [m (vm/vaults-tab-model (tf/state-with {:status :error :for-address tf/address :error "boom"}))]
      (is (= :error (:status m)))
      (is (nil? (:rows m)))))
  (testing "ready but no vault deposits"
    (let [m (vm/vaults-tab-model (tf/state-without-live
                                  (tf/ready-slot :ledger-rows [] :details-by-vault {})))]
      (is (= :empty (:status m)))
      (is (nil? (:cards m)))))
  (testing "refreshing keeps the held figures"
    (let [m (model :status :loading)]
      (is (= :ready (:status m)))
      (is (true? (:refreshing? m)))
      (is (seq (:rows m)))))
  (testing "partial"
    (is (= "Some vault histories failed to load; figures may be incomplete."
           (:partial-note (model :errors-by-vault {fx/vault-b "x"}))))
    (is (= "Vault history may be incomplete: the ledger is too long to load in full."
           (:partial-note (model :ledger-partial? true))))
    (is (nil? (:partial-note (model)))))
  (testing "a failed refresh keeps the figures and says so"
    (let [m (model :status :error :error "boom")]
      (is (= :ready (:status m)))
      (is (seq (:rows m)))
      (is (= vm/refresh-error-note (:refresh-error m)))))
  (testing "excluded vaults are counted"
    (is (= "1 vault excluded: incomplete history" (:excluded-note (model))))
    (is (= "2 vaults excluded: incomplete history" (vm/excluded-note {:incomplete-count 2})))
    (is (nil? (vm/excluded-note {:incomplete-count 0})))))

(deftest cards-test
  (let [m (model)
        {:keys [pnl money-weighted time-weighted equity]} (:cards m)
        totals (get-in (selector/model (tf/state-with (tf/ready-slot))) [:attribution :totals])]
    (is (= :ready (:status m)))
    (testing "vault pnl"
      (is (= (if (neg? (:pnl totals)) "-" "+") (subs (get-in pnl [:value :text]) 0 1)))
      (is (re-find #"^[+-]\$[\d,]+\.\d\d$" (get-in pnl [:value :text])))
      (is (re-find #"^Realized [+-]\$[\d,.]+ · Unrealized [+-]\$[\d,.]+$" (:detail pnl))))
    (testing "money-weighted"
      (is (re-find #"^[+-]\d+\.\d% / yr$" (get-in money-weighted [:value :text])))
      (is (= "What your deposits earned, timing included" (:detail money-weighted))))
    (testing "time-weighted comes from the real returns code and is marked estimated"
      (let [entry (get-in (selector/model (tf/state-with (tf/ready-slot)))
                          [:attribution :summary-entries :vault-all-time])
            expected (second (peek (portfolio-metrics/returns-history-rows-from-summary entry)))]
        (is (number? expected))
        (is (= expected (vm/time-weighted-return-pct
                         (:attribution (selector/model (tf/state-with (tf/ready-slot)))))))
        (is (true? (get-in time-weighted [:value :estimated?])))
        (is (re-find #"^[+-]\d+\.\d%$" (get-in time-weighted [:value :text]))))
      (is (= "Vault picks alone, ignoring deposit timing" (:detail time-weighted))))
    (testing "equity"
      ;; complete vaults only: the orphan's 500 is excluded (and counted)
      (is (= "$1,800.00" (get-in equity [:value :text])))
      (is (re-find #"^Net deposited \$" (:detail equity))))))

(deftest unknown-is-never-zero-test
  (is (nil? (vm/time-weighted-return-pct nil)))
  (is (nil? (vm/time-weighted-return-pct {:summary-entries {}})))
  (is (= "--" (vm/held-label nil))))

(deftest rows-test
  (let [m (model)
        open-row (row m fx/vault-a)
        closed-row (row m fx/vault-b)
        orphan (row m tf/vault-c)]
    (is (= [fx/vault-a tf/vault-c fx/vault-b] (mapv :vault-address (:rows m)))
        "open by value, then closed")
    (testing "open vault"
      (is (= "HLP" (:name open-row)))
      (is (= "/vaults/0xdfc24b077bc1425ad1dea75bcb6f8158e10df303" (:route open-row)))
      (is (= "Open" (:status-label open-row)))
      (is (= "$1,500.00" (:deposited open-row)))
      (is (= "$1,800.00" (:value open-row)))
      (is (= "13 mo" (:held open-row)))
      (is (true? (get-in open-row [:while-held :estimated?]))
          "per-vault while-held is the same estimate as the total")
      (is (nil? (:incomplete-note open-row))))
    (testing "closed vault with no name"
      (is (= "0x1e37…8d5e" (:name closed-row)))
      (is (= "Closed" (:status-label closed-row)))
      (is (= "--" (:value closed-row)))
      (is (= "$2,200.00" (:withdrawn closed-row)))
      (is (= "+$200.00" (get-in closed-row [:pnl :text])))
      (is (= :positive (get-in closed-row [:pnl :tone])))
      (is (= "7 mo" (:held closed-row))))
    (testing "incomplete flows show --"
      (is (= "Deposit history incomplete" (:incomplete-note orphan)))
      (is (= "--" (get-in orphan [:pnl :text])))
      (is (= "--" (get-in orphan [:money-weighted :text])))
      (is (= "--" (get-in orphan [:while-held :text])))
      (is (= :none (get-in orphan [:pnl :tone]))))
    (testing "all vaults row"
      (is (true? (get-in m [:total :while-held :estimated?])))
      (is (= (get-in m [:cards :time-weighted :value :text])
             (get-in m [:total :while-held :text]))))))

(deftest held-label-test
  (is (= "0 d" (vm/held-label 1000)))
  (is (= "45 d" (vm/held-label (* 45 fx/day))))
  (is (= "59 d" (vm/held-label (* 59.9 fx/day))))
  (is (= "20 mo" (vm/held-label (* 609 fx/day)))))

(deftest open-count-test
  (is (= 2 (vm/open-count (selector/model (tf/state-with (tf/ready-slot))))))
  (is (nil? (vm/open-count (selector/model {}))))
  (is (nil? (vm/open-count (selector/model (tf/state-with {:status :loading :for-address tf/address}))))))

(deftest footnote-test
  (is (nil? (vm/footnote {})))
  (let [text (vm/footnote {:daily-from-ms fx/now})]
    (is (re-find #"^Estimated before .+ from vault history sampled about every 12 days\. Dollar PNL and money-weighted return are exact\.$" text))))

(deftest chart-model-test
  (let [m (model)
        c (:chart m)]
    (is (some? c))
    (is (re-find #"^[\d.]+,[\d.]+ " (:value-points c)))
    (is (= 4 (count (:ticks c))))
    (is (every? #(re-find #"^-?\$" (:label %)) (:ticks c)))
    (testing "markers sit on the net-deposited line"
      (is (= 4 (count (:markers c))))
      (is (every? #(<= 0 (:left %) 100) (:markers c)))
      (is (every? #(<= 0 (:top %) 100) (:markers c)))
      (is (some #(re-find #"^Deposit \$1,000\.00 · HLP · \d{4}-\d{2}-\d{2}$" (:title %)) (:markers c)))
      (is (some #(and (= :withdraw (:kind %))
                      (re-find #"^Withdrawal \$2,200\.00 · 0x1e37…8d5e|0x1e37a337ed460039d1b15bd3bc489de789768d5e" (:title %)))
                (:markers c)))))
  (testing "too few points draws nothing"
    (is (nil? (chart/chart-model {:series [{:time-ms 1 :value 1 :net-deposited 1}]})))))

(deftest marker-cap-test
  (let [markers (mapv (fn [i] {:time-ms i :kind :deposit :amount (double (mod (* i 7) 101))
                               :vault-address fx/vault-a :name "HLP"})
                      (range 200))
        top (chart/top-markers markers)]
    (is (= chart/max-markers (count top)))
    (is (apply <= (map :time-ms top)) "time order")
    (let [kept-min (apply min (map :amount top))
          dropped (remove (set top) markers)]
      (is (every? #(<= (:amount %) kept-min) dropped) "largest amounts kept"))))

(defn- model-without-live
  [& slot-args]
  (vm/vaults-tab-model (tf/state-without-live (apply tf/ready-slot slot-args))))

(deftest unknown-value-row-test
  (let [m (model-without-live :details-by-vault {fx/vault-b {:name nil}}
                              :errors-by-vault {fx/vault-a "boom"})
        r (row m fx/vault-a)]
    (is (= "Current value unavailable" (:incomplete-note r)))
    (is (= "--" (:value r)))
    (is (= "--" (get-in r [:pnl :text])))))

(deftest no-complete-vault-totals-render-unknown-test
  (let [m (model-without-live :ledger-rows [(fx/deposit (- fx/now fx/day) fx/vault-a "100")]
                              :details-by-vault {}
                              :errors-by-vault {fx/vault-a "boom"})]
    (is (= "--" (get-in m [:cards :pnl :value :text])))
    (is (= "Realized -- · Unrealized --" (get-in m [:cards :pnl :detail])))
    (is (= "--" (get-in m [:total :pnl :text])))))

(deftest chart-axis-never-below-zero-for-positive-values-test
  (let [series [{:time-ms 1 :value 0 :net-deposited 0}
                {:time-ms 2 :value 5000000 :net-deposited 4000000}
                {:time-ms 3 :value 5700000 :net-deposited 4000000}]
        c (chart/chart-model {:series series :markers []})]
    (is (every? #(not (re-find #"^-" (:label %))) (:ticks c)))
    (is (re-find #"^\$0(\.0+)?$" (:label (first (:ticks c))))))
  (testing "negative values still get room below"
    (let [c (chart/chart-model {:series [{:time-ms 1 :value -100 :net-deposited 0}
                                         {:time-ms 2 :value 100 :net-deposited 0}]
                                :markers []})]
      (is (re-find #"^-" (:label (first (:ticks c))))))))

(deftest distribution-marker-title-test
  (is (re-find #"^Distribution \$1,100\.00 · HLP · "
               (chart/marker-title {:kind :withdraw :source :distribution :amount 1100
                                    :time-ms fx/now :name "HLP"}))))
