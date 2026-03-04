(ns hyperopen.views.vaults.vm-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.vaults.vm :as vm]))

(def sample-state
  {:wallet {:address "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"}
   :vaults-ui {:search-query ""
               :filter-leading? true
               :filter-deposited? true
               :filter-others? true
               :filter-closed? false
               :snapshot-range :month
               :user-vaults-page-size 10
               :user-vaults-page 1
               :sort {:column :tvl
                      :direction :desc}}
   :vaults {:loading {:index? false
                      :summaries? false}
            :errors {:index nil
                     :summaries nil}
            :user-equity-by-address {"0x2222222222222222222222222222222222222222" {:equity 25}}
            :merged-index-rows [{:name "Hyperliquidity Provider (HLP)"
                                 :vault-address "0x1111111111111111111111111111111111111111"
                                 :leader "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
                                 :tvl 120
                                 :apr 0.12
                                 :relationship {:type :parent}
                                 :is-closed? false
                                 :create-time-ms (- (.now js/Date) (* 3 24 60 60 1000))
                                 :snapshot-by-key {:month [0.1 0.2]}}
                                {:name "Beta"
                                 :vault-address "0x2222222222222222222222222222222222222222"
                                 :leader "0x3333333333333333333333333333333333333333"
                                 :tvl 80
                                 :apr 0.08
                                 :relationship {:type :normal}
                                 :is-closed? false
                                 :create-time-ms (- (.now js/Date) (* 8 24 60 60 1000))
                                 :snapshot-by-key {:month [0.05 0.09]}}
                                {:name "Gamma"
                                 :vault-address "0x3333333333333333333333333333333333333333"
                                 :leader "0x4444444444444444444444444444444444444444"
                                 :tvl 50
                                 :apr 0.03
                                 :relationship {:type :normal}
                                 :is-closed? false
                                 :create-time-ms (- (.now js/Date) (* 5 24 60 60 1000))
                                 :snapshot-by-key {:month [0.01 0.02]}}
                                {:name "Closed Vault"
                                 :vault-address "0x4444444444444444444444444444444444444444"
                                 :leader "0x5555555555555555555555555555555555555555"
                                 :tvl 999
                                 :apr 0.4
                                 :relationship {:type :normal}
                                 :is-closed? true
                                 :create-time-ms (- (.now js/Date) (* 1 24 60 60 1000))
                                 :snapshot-by-key {:month [0.03]}}
                                {:name "Child Vault"
                                 :vault-address "0x5555555555555555555555555555555555555555"
                                 :leader "0x6666666666666666666666666666666666666666"
                                 :tvl 777
                                 :apr 0.22
                                 :relationship {:type :child}
                                 :is-closed? false
                                 :create-time-ms (- (.now js/Date) (* 4 24 60 60 1000))
                                 :snapshot-by-key {:month [0.03]}}]}})

(deftest vault-route-helper-parses-list-and-detail-routes-test
  (is (true? (vm/vault-route? "/vaults")))
  (is (false? (vm/vault-detail-route? "/vaults")))
  (is (= "0x1234567890abcdef1234567890abcdef12345678"
         (vm/selected-vault-address "/vaults/0x1234567890abcdef1234567890abcdef12345678")))
  (is (false? (vm/vault-route? "/trade"))))

(deftest vault-list-vm-groups-filters-and-sorts-rows-test
  (let [view-model (vm/vault-list-vm sample-state)]
    (is (= 3 (:visible-count view-model)))
    (is (= 250 (:total-visible-tvl view-model)))
    (is (= ["Hyperliquidity Provider (HLP)"]
           (mapv :name (:protocol-rows view-model))))
    (is (= ["Beta" "Gamma"]
           (mapv :name (:user-rows view-model))))
    (is (= ["Beta" "Gamma"]
           (mapv :name (:visible-user-rows view-model))))
    (is (= 2 (get-in view-model [:user-pagination :total-rows])))
    (is (= 10 (get-in view-model [:user-pagination :page-size])))
    (is (= 1 (get-in view-model [:user-pagination :page])))
    (is (= 1 (get-in view-model [:user-pagination :page-count])))
    (is (= ["Beta" "Hyperliquidity Provider (HLP)" "Gamma"]
           (mapv :name (:rows view-model)))))
  (let [view-model (vm/vault-list-vm (assoc-in sample-state [:vaults-ui :search-query] "beta"))]
    (is (= ["Beta"] (mapv :name (:rows view-model)))))
  (let [view-model (vm/vault-list-vm (-> sample-state
                                         (assoc-in [:vaults-ui :filter-leading?] false)
                                         (assoc-in [:vaults-ui :filter-deposited?] false)
                                         (assoc-in [:vaults-ui :filter-others?] true)))]
    (is (= ["Gamma"] (mapv :name (:rows view-model)))))
  (let [view-model (vm/vault-list-vm (assoc-in sample-state [:vaults-ui :filter-closed?] true))]
    (is (= 4 (:visible-count view-model)))))

(deftest vault-list-vm-paginates-user-vault-rows-test
  (let [extra-user-rows [{:name "Delta"
                          :vault-address "0x6666666666666666666666666666666666666666"
                          :leader "0x7777777777777777777777777777777777777777"
                          :tvl 60
                          :apr 0.02
                          :relationship {:type :normal}
                          :is-closed? false
                          :create-time-ms (- (.now js/Date) (* 6 24 60 60 1000))
                          :snapshot-by-key {:month [0.02]}}
                         {:name "Epsilon"
                          :vault-address "0x7777777777777777777777777777777777777777"
                          :leader "0x8888888888888888888888888888888888888888"
                          :tvl 55
                          :apr 0.01
                          :relationship {:type :normal}
                          :is-closed? false
                          :create-time-ms (- (.now js/Date) (* 7 24 60 60 1000))
                          :snapshot-by-key {:month [0.01]}}
                         {:name "Zeta"
                          :vault-address "0x8888888888888888888888888888888888888888"
                          :leader "0x9999999999999999999999999999999999999999"
                          :tvl 40
                          :apr 0.03
                          :relationship {:type :normal}
                          :is-closed? false
                          :create-time-ms (- (.now js/Date) (* 9 24 60 60 1000))
                          :snapshot-by-key {:month [0.03]}}
                         {:name "Eta"
                          :vault-address "0x9999999999999999999999999999999999999999"
                          :leader "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                          :tvl 35
                          :apr 0.04
                          :relationship {:type :normal}
                          :is-closed? false
                          :create-time-ms (- (.now js/Date) (* 10 24 60 60 1000))
                          :snapshot-by-key {:month [0.04]}}]
        view-model (vm/vault-list-vm (-> sample-state
                                         (assoc-in [:vaults :merged-index-rows]
                                                   (into (get-in sample-state [:vaults :merged-index-rows])
                                                         extra-user-rows))
                                         (assoc-in [:vaults-ui :user-vaults-page-size] 5)
                                         (assoc-in [:vaults-ui :user-vaults-page] 2)))]
    (is (= 1 (count (:visible-user-rows view-model))))
    (is (= 6 (get-in view-model [:user-pagination :total-rows])))
    (is (= 2 (get-in view-model [:user-pagination :page-count])))
    (is (= 2 (get-in view-model [:user-pagination :page])))))

(deftest vault-list-vm-uses-snapshot-fallback-keys-for-extended-ranges-test
  (let [view-model (vm/vault-list-vm (assoc-in sample-state [:vaults-ui :snapshot-range] :one-year))
        beta-row (some #(when (= "Beta" (:name %))
                          %)
                       (:rows view-model))]
    (is (= [5 9] (:snapshot-series beta-row))))
  (let [state-with-all-time (assoc-in sample-state
                                      [:vaults :merged-index-rows 1 :snapshot-by-key]
                                      {:month [0.05 0.09]
                                       :all-time [0.2 0.3]})
        view-model (vm/vault-list-vm (assoc-in state-with-all-time [:vaults-ui :snapshot-range] :one-year))
        beta-row (some #(when (= "Beta" (:name %))
                          %)
                       (:rows view-model))]
    (is (= [20 30] (:snapshot-series beta-row)))))
