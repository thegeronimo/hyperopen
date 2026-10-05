(ns hyperopen.views.vaults.detail.position-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.vaults.detail.position :as position-view]
            [hyperopen.views.vaults.detail.position-chart :as position-chart]))

(def ^:private vault-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def ^:private open-position
  {:status :open
   :viewer-address "0xffffffffffffffffffffffffffffffffffffffff"
   :value 32486.2
   :cost-basis 30412.8
   :unrealized 2073.4
   :unrealized-pct 6.8175
   :realized 412.8
   :all-time-earned 2486.2
   :first-deposit-ms 1773273600000
   :position-start-ms 1773273600000
   :position-start-exact? true
   :days-held 206
   :vault-return-since-start-pct 8.66
   :composition {:direction :gain :basis-share 0.936 :pnl-share 0.064}
   :locked? false
   :ledger-status :ready
   :transfer-count 2
   :transfers [{:kind :withdraw :time-ms 1787184000000 :hash "0xw" :amount 5000
                :realized 412.8 :commission 0 :current-position? true}
               {:kind :deposit :time-ms 1773273600000 :hash "0xd" :amount 25000
                :vault-return-since-pct 8.66 :current-position? true}]})

(defn- text-of
  [node]
  (set (hiccup/collect-strings node)))

(defn- by-role
  [node role]
  (hiccup/find-first-node node #(= role (get-in % [1 :data-role]))))

(deftest open-position-band-shows-basis-pnl-and-transfers-test
  (let [band (position-view/position-band open-position {:can-open-deposit? true} vault-address)
        text (text-of band)]
    (is (some? (by-role band "vault-position-band")))
    (is (contains? text "Your position"))
    (is (contains? text "Withdrawable now"))
    (is (contains? (text-of (by-role band "vault-position-cost-basis")) "$30,412.80"))
    (is (contains? (text-of (by-role band "vault-position-unrealized")) "+$2,073.40"))
    (is (contains? (text-of (by-role band "vault-position-all-time"))
                   "+$412.80 realized via withdrawals"))
    (is (some? (by-role band "vault-position-composition")))
    (is (= 2 (count (hiccup/find-all-nodes band #(= "vault-position-transfer"
                                                    (get-in % [1 :data-role]))))))
    (is (contains? text "≈ vault +8.66% since"))
    (is (contains? text "realized +$412.80"))))

(deftest locked-position-shows-time-left-test
  (let [band (position-view/position-band (assoc open-position
                                                 :locked? true
                                                 :lockup-remaining-ms (* (+ 72 6) 3600000))
                                          {}
                                          vault-address)]
    (is (contains? (text-of (by-role band "vault-position-lockup")) "Locked · 3d 6h left"))))

(deftest loading-ledger-keeps-totals-and-shows-skeleton-test
  (let [band (position-view/position-band (assoc open-position :ledger-status :loading :transfers [])
                                          {}
                                          vault-address)]
    (is (contains? (text-of (by-role band "vault-position-value")) "$32,486.20"))
    (is (some? (by-role band "vault-position-transfers-loading")))))

(deftest empty-and-spectating-states-test
  (let [own (position-view/position-band {:status :none :viewer-address "0xf"}
                                         {:can-open-deposit? true}
                                         vault-address)
        spectated (position-view/position-band {:status :none :viewer-address "0xf" :spectating? true}
                                               {:can-open-deposit? true}
                                               vault-address)]
    (is (contains? (text-of own) "You haven't deposited into this vault"))
    (is (= [[:actions/open-vault-transfer-modal vault-address :deposit]]
           (get-in (hiccup/find-first-node own #(= :button (first %))) [1 :on :click])))
    (is (contains? (text-of spectated) "This account has no deposit in this vault"))
    (is (nil? (hiccup/find-first-node spectated #(= :button (first %)))))
    (is (nil? (position-view/position-band {:status :none} {} vault-address)))))

(deftest signed-currency-uses-a-real-minus-sign-test
  (is (= "−$587.93" (position-view/format-signed-currency -587.93)))
  (is (= "+$0.00" (position-view/format-signed-currency 0)))
  (is (= "—" (position-view/format-signed-currency nil))))

(def ^:private series
  {:start-ms 0
   :end-ms 1000
   :points [{:time-ms 0 :value 100 :basis 100}
            {:time-ms 500 :value 110 :basis 100}
            {:time-ms 500 :value 160 :basis 150}
            {:time-ms 1000 :value 170 :basis 150}]
   :markers [{:kind :deposit :time-ms 0 :amount 100}
             {:kind :deposit :time-ms 500 :amount 50}]})

(deftest chart-geometry-projects-series-and-one-hover-sample-per-time-test
  (let [{:keys [samples markers value-path basis-path area-path]} (position-chart/chart-geometry series)]
    (is (= [0 500 1000] (mapv :time-ms samples)))
    (is (= 160 (:value (second samples))) "the after-transfer point wins")
    (is (= [0 25 75] (mapv :band-left-pct samples)))
    (is (= [25 50 25] (mapv :band-width-pct samples)))
    (is (= [0 50] (mapv :x-pct markers)))
    (is (= 4 (count (re-seq #"[ML]" value-path))))
    (is (= 4 (count (re-seq #"[ML]" basis-path))))
    (is (re-find #" Z$" area-path))))

(deftest open-band-renders-the-chart-when-a-series-exists-test
  (let [band (position-view/position-band (assoc open-position :series series) {} vault-address)]
    (is (some? (by-role band "vault-position-chart")))
    (is (= 2 (count (hiccup/find-all-nodes band #(= "vault-position-chart-marker" (get-in % [1 :data-role]))))))
    (is (= 3 (count (hiccup/find-all-nodes band #(= "vault-position-chart-sample" (get-in % [1 :data-role])))))))
  (is (nil? (by-role (position-view/position-band open-position {} vault-address) "vault-position-chart"))))

(deftest near-zero-values-are-not-coloured-as-gains-or-losses-test
  (let [band (position-view/position-band (assoc open-position
                                                 :vault-return-since-start-pct -0.0004
                                                 :days-held 0)
                                          {}
                                          vault-address)
        cell (by-role band "vault-position-vault-return")]
    (is (some? (hiccup/find-first-node cell #(contains? (hiccup/node-class-set %) "text-trading-text"))))
    (is (nil? (hiccup/find-first-node cell #(contains? (hiccup/node-class-set %) "text-ho-sell"))))
    (is (some #(re-find #"opened today" %) (hiccup/collect-strings band)))))

(deftest long-transfer-lists-fold-older-rows-behind-a-disclosure-test
  (let [transfers (mapv (fn [i] {:kind :deposit :time-ms (- 1000 i) :hash (str "0x" i)
                                 :amount 10 :current-position? true})
                        (range 9))
        band (position-view/position-band (assoc open-position :transfers transfers :transfer-count 9)
                                          {}
                                          vault-address)
        older (by-role band "vault-position-older-transfers")]
    (is (= 9 (count (hiccup/find-all-nodes band #(= "vault-position-transfer" (get-in % [1 :data-role]))))))
    (is (= 3 (count (hiccup/find-all-nodes older #(= "vault-position-transfer" (get-in % [1 :data-role]))))))
    (is (contains? (set (hiccup/collect-strings older)) "Show 3 older transfers"))))

(deftest chart-domain-never-pads-below-zero-test
  (is (= 0 (:lo (position-chart/chart-geometry
                 {:start-ms 0 :end-ms 10
                  :points [{:time-ms 0 :value 5000 :basis 5000}
                           {:time-ms 10 :value 455000 :basis 455000}]
                  :markers []})))))
