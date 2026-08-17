(ns hyperopen.account.context-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.context :as account-context]))

(deftest normalize-address-handles-trim-case-and-invalid-values-test
  (is (= "0x1111111111111111111111111111111111111111"
         (account-context/normalize-address " 0x1111111111111111111111111111111111111111 ")))
  (is (= "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
         (account-context/normalize-address "0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")))
  (is (nil? (account-context/normalize-address "")))
  (is (nil? (account-context/normalize-address "0xabc")))
  (is (nil? (account-context/normalize-address "not-an-address"))))

(deftest normalize-watchlist-filters-invalid-and-deduplicates-test
  (is (= [{:address "0x1111111111111111111111111111111111111111"
           :label "Primary"}
          {:address "0x2222222222222222222222222222222222222222"
           :label "Treasury"}]
         (account-context/normalize-watchlist
           ["0x1111111111111111111111111111111111111111"
            {:address "0x1111111111111111111111111111111111111111"
             :label "Primary"}
            {"address" "0x2222222222222222222222222222222222222222"
             "label" "Treasury"}
            "bad"]))))

(deftest watchlist-entry-upsert-and-remove-support-labeled-entries-test
  (let [initial [{:address "0x1111111111111111111111111111111111111111"
                  :label nil}
                 {:address "0x2222222222222222222222222222222222222222"
                  :label "Treasury"}]]
    (is (= [{:address "0x1111111111111111111111111111111111111111"
             :label "Primary"}
            {:address "0x2222222222222222222222222222222222222222"
             :label "Treasury"}]
           (account-context/upsert-watchlist-entry
            initial
            "0x1111111111111111111111111111111111111111"
            "Primary")))
    (is (= [{:address "0x1111111111111111111111111111111111111111"
             :label nil}]
           (account-context/remove-watchlist-entry
            initial
            "0x2222222222222222222222222222222222222222")))))

(deftest spectate-label-resolves-watchlist-label-for-active-address-test
  (let [spectate "0x2222222222222222222222222222222222222222"
        watchlist [{:address "0x1111111111111111111111111111111111111111"
                    :label "Primary"}
                   {:address spectate
                    :label "Treasury"}]
        state {:account-context {:spectate-mode {:active? true
                                                 :address spectate}
                                 :watchlist watchlist}}]
    (is (= "Treasury" (account-context/spectate-label state)))
    (is (= "Treasury"
           (account-context/spectate-label
            (assoc-in state
                      [:account-context :spectate-mode :address]
                      "0x2222222222222222222222222222222222222222 "))))
    (is (nil? (account-context/spectate-label
               (assoc-in state
                         [:account-context :spectate-mode :address]
                         "0x3333333333333333333333333333333333333333"))))
    (is (nil? (account-context/spectate-label
               (assoc-in state [:account-context :watchlist] [{:address spectate
                                                               :label "  "}]))))
    (is (nil? (account-context/spectate-label {:account-context {:watchlist watchlist}})))))

(deftest effective-account-address-prefers-spectate-when-active-test
  (let [owner "0x1111111111111111111111111111111111111111"
        spectate "0x2222222222222222222222222222222222222222"]
    (is (= spectate
           (account-context/effective-account-address
            {:wallet {:address owner}
             :account-context {:spectate-mode {:active? true
                                            :address spectate}}})))
    (is (= owner
           (account-context/effective-account-address
            {:wallet {:address owner}
             :account-context {:spectate-mode {:active? false
                                            :address spectate}}})))
    (is (= owner
           (account-context/effective-account-address
           {:wallet {:address owner}
             :account-context {:spectate-mode {:active? true
                                            :address "bad"}}})))))

(deftest effective-account-address-prefers-trader-portfolio-route-over-spectate-and-owner-test
  (let [owner "0x1111111111111111111111111111111111111111"
        spectate "0x2222222222222222222222222222222222222222"
        trader "0x3333333333333333333333333333333333333333"]
    (is (= trader
           (account-context/effective-account-address
            {:wallet {:address owner}
             :router {:path (str "/portfolio/trader/" trader)}
             :account-context {:spectate-mode {:active? true
                                               :address spectate}}})))
    (is (= trader
           (account-context/trader-portfolio-address
            {:router {:path (str "/portfolio/trader/" trader)}})))
    (is (true? (account-context/trader-portfolio-route-active?
                {:router {:path (str "/portfolio/trader/" trader)}})))))

(deftest subaccounts-owner-unified-uses-mode-for-current-owner-only-test
  (let [owner-a "0x1111111111111111111111111111111111111111"
        owner-b "0x2222222222222222222222222222222222222222"]
    (is (false?
         (account-context/subaccounts-owner-unified?
          {:wallet {:address owner-b}
           :account {:mode :classic}
           :account-context {:subaccounts {:loaded-for-owner owner-b
                                           :owner-mode {:owner owner-a
                                                        :mode :unified}}}}))
        "A stale unified owner-mode from another owner must not force sendAsset.")
    (is (false?
         (account-context/subaccounts-owner-unified?
          {:wallet {:address owner-b}
           :account {:mode :unified}
           :account-context {:subaccounts {:loaded-for-owner owner-b
                                           :owner-mode {:owner owner-a
                                                        :mode :classic}}}}))
        "A mismatched scoped owner-mode must not fall back to possibly stale active account mode.")
    (is (true?
         (account-context/subaccounts-owner-unified?
          {:wallet {:address owner-b}
           :account {:mode :classic}
           :account-context {:subaccounts {:loaded-for-owner owner-b
                                           :owner-mode {:owner owner-b
                                                        :mode :unified}}}}))
        "A matching unified owner-mode is authoritative over the active account mode.")
    (is (false?
         (account-context/subaccounts-owner-unified?
          {:wallet {:address owner-b}
           :account {:mode :unified}
           :account-context {:subaccounts {:loaded-for-owner owner-b
                                           :owner-mode {:owner owner-b
                                                        :mode :classic}}}}))
        "A matching classic owner-mode is authoritative over the active account mode.")
    (is (false?
         (account-context/subaccounts-owner-unified?
          {:wallet {:address owner-b}
           :account {:mode :unified}
           :account-context {:subaccounts {:loaded-for-owner owner-b
                                           :owner-mode {:owner owner-b
                                                        :mode nil}}}}))
        "A pending scoped owner-mode must wait for the current owner's loaded mode.")
    (is (true?
         (account-context/subaccounts-owner-unified?
          {:wallet {:address owner-b}
           :account {:mode :unified}
           :account-context {:subaccounts {:loaded-for-owner owner-b
                                           :owner-mode nil}}}))
        "Minimal legacy state without an owner-mode record still falls back to active account mode.")
    (is (false?
         (account-context/subaccounts-owner-unified?
          {:wallet {:address owner-b}
           :account {:mode :unified}
           :account-context {:subaccounts {:loaded-for-owner owner-b
                                           :owner-mode :unified}}}))
        "Unscoped legacy owner-mode must not override active account mode.")))

(defn- subaccount-state
  [overrides]
  (let [owner "0x1111111111111111111111111111111111111111"
        selected "0x2222222222222222222222222222222222222222"
        row {:name "Desk A"
             :sub-account-user selected
             :master owner
             :clearinghouse-state {:marginSummary {:accountValue "123.45"}}
             :spot-state {:balances []}}]
    (merge {:wallet {:address owner}
            :account-context {:spectate-mode {:active? false
                                              :address nil}
                              :subaccounts {:rows [row]
                                            :selected-address selected}}}
           overrides)))

(deftest selected-owned-subaccount-becomes-read-and-trading-target-test
  (let [owner "0x1111111111111111111111111111111111111111"
        selected "0x2222222222222222222222222222222222222222"
        row {:name "Desk A"
             :sub-account-user selected
             :master owner
             :clearinghouse-state {:marginSummary {:accountValue "123.45"}}
             :spot-state {:balances []}}
        state (subaccount-state {:account-context {:spectate-mode {:active? false
                                                                    :address nil}
                                                   :subaccounts {:rows [row]
                                                                 :selected-address selected}}})]
    (is (= selected
           (account-context/selected-subaccount-address state)))
    (is (= row
           (account-context/selected-subaccount-row state)))
    (is (true? (account-context/selected-subaccount-owned-by-owner? state)))
    (is (= selected
           (account-context/effective-account-address state)))
    (is (= selected
           (account-context/active-trading-account-address state)))
    (is (= selected
           (account-context/exchange-vault-address state)))
    (is (= selected
           (account-context/live-user-stream-address state)))
    (is (true? (account-context/mutations-allowed? state)))
    (is (nil? (account-context/mutations-blocked-message state)))))

(deftest native-staking-account-address-is-owner-scoped-except-on-inspection-routes-test
  (let [owner "0x1111111111111111111111111111111111111111"
        selected "0x2222222222222222222222222222222222222222"
        spectate "0x3333333333333333333333333333333333333333"
        trader "0x4444444444444444444444444444444444444444"
        normal-state (subaccount-state {})
        spectate-state (subaccount-state
                        {:account-context {:spectate-mode {:active? true
                                                           :address spectate}
                                           :subaccounts {:rows [{:sub-account-user selected
                                                                :master owner}]
                                                         :selected-address selected}}})
        trader-state (subaccount-state
                      {:router {:path (str "/portfolio/trader/" trader)}
                       :account-context {:spectate-mode {:active? true
                                                         :address spectate}
                                         :subaccounts {:rows [{:sub-account-user selected
                                                              :master owner}]
                                                       :selected-address selected}}})]
    (is (= owner (account-context/native-staking-account-address normal-state)))
    (is (= spectate (account-context/native-staking-account-address spectate-state)))
    (is (= trader (account-context/native-staking-account-address trader-state)))
    (is (nil? (account-context/native-staking-account-address {})))))

(deftest selected-subaccount-preserves-master-default-and-read-only-overrides-test
  (let [owner "0x1111111111111111111111111111111111111111"
        selected "0x2222222222222222222222222222222222222222"
        spectate "0x3333333333333333333333333333333333333333"
        trader "0x4444444444444444444444444444444444444444"
        no-selection-state (subaccount-state
                            {:account-context {:spectate-mode {:active? false
                                                               :address nil}
                                               :subaccounts {:rows []
                                                             :selected-address nil}}})
        spectate-state (subaccount-state
                        {:account-context {:spectate-mode {:active? true
                                                           :address spectate}
                                           :subaccounts {:rows [{:name "Desk A"
                                                                :sub-account-user selected
                                                                :master owner}]
                                                         :selected-address selected}}})
        trader-state (subaccount-state
                      {:router {:path (str "/portfolio/trader/" trader)}
                       :account-context {:spectate-mode {:active? false
                                                         :address nil}
                                         :subaccounts {:rows [{:name "Desk A"
                                                              :sub-account-user selected
                                                              :master owner}]
                                                       :selected-address selected}}})]
    (is (= owner
           (account-context/effective-account-address no-selection-state)))
    (is (= owner
           (account-context/active-trading-account-address no-selection-state)))
    (is (nil? (account-context/exchange-vault-address no-selection-state)))
    (is (= owner
           (account-context/live-user-stream-address no-selection-state)))
    (is (= spectate
           (account-context/effective-account-address spectate-state)))
    (is (= spectate
           (account-context/live-user-stream-address spectate-state)))
    (is (= selected
           (account-context/active-trading-account-address spectate-state)))
    (is (false? (account-context/mutations-allowed? spectate-state)))
    (is (= account-context/spectate-mode-read-only-message
           (account-context/mutations-blocked-message spectate-state)))
    (is (= trader
           (account-context/effective-account-address trader-state)))
    (is (nil? (account-context/live-user-stream-address trader-state)))
    (is (= selected
           (account-context/active-trading-account-address trader-state)))
    (is (false? (account-context/mutations-allowed? trader-state)))
    (is (= account-context/trader-portfolio-read-only-message
           (account-context/mutations-blocked-message trader-state)))))

(deftest stale-or-unowned-selected-subaccount-blocks-mutations-test
  (let [owner "0x1111111111111111111111111111111111111111"
        selected "0x2222222222222222222222222222222222222222"
        other-master "0x5555555555555555555555555555555555555555"
        stale-state (subaccount-state
                     {:account-context {:spectate-mode {:active? false
                                                        :address nil}
                                        :subaccounts {:rows []
                                                      :selected-address selected}}})
        unowned-state (subaccount-state
                       {:account-context {:spectate-mode {:active? false
                                                          :address nil}
                                          :subaccounts {:rows [{:name "External"
                                                               :sub-account-user selected
                                                               :master other-master}]
                                                        :selected-address selected}}})]
    (is (nil? (account-context/exchange-vault-address stale-state)))
    (is (false? (account-context/mutations-allowed? stale-state)))
    (is (re-find #"Subaccount"
                 (str (account-context/mutations-blocked-message stale-state))))
    (is (nil? (account-context/exchange-vault-address unowned-state)))
    (is (false? (account-context/mutations-allowed? unowned-state)))
    (is (re-find #"Subaccount"
                 (str (account-context/mutations-blocked-message unowned-state))))
    (is (false? (account-context/selected-subaccount-owned-by-owner? unowned-state)))))

(deftest mutations-allowed-is-disabled-during-spectate-mode-test
  (is (false? (account-context/mutations-allowed?
               {:account-context {:spectate-mode {:active? true
                                               :address "0x1111111111111111111111111111111111111111"}}})))
  (is (true? (account-context/mutations-allowed?
              {:account-context {:spectate-mode {:active? false
                                              :address "0x1111111111111111111111111111111111111111"}}}))))

(deftest mutations-allowed-is-disabled-on-trader-portfolio-route-test
  (let [trader "0x3333333333333333333333333333333333333333"]
    (is (false? (account-context/mutations-allowed?
                 {:router {:path (str "/portfolio/trader/" trader)}})))
    (is (= account-context/trader-portfolio-read-only-message
           (account-context/mutations-blocked-message
            {:router {:path (str "/portfolio/trader/" trader)}})))))

(deftest user-stream-subscriptions-are-disabled-only-on-trader-portfolio-routes-test
  (let [owner "0x1111111111111111111111111111111111111111"
        spectate "0x2222222222222222222222222222222222222222"
        trader "0x3333333333333333333333333333333333333333"]
    (is (true? (account-context/user-stream-subscriptions-enabled?
                {:wallet {:address owner}
                 :router {:path "/portfolio"}})))
    (is (true? (account-context/user-stream-subscriptions-enabled?
                {:wallet {:address owner}
                 :account-context {:spectate-mode {:active? true
                                                   :address spectate}}})))
    (is (false? (account-context/user-stream-subscriptions-enabled?
                 {:wallet {:address owner}
                  :router {:path (str "/portfolio/trader/" trader)}})))
    (is (= spectate
           (account-context/live-user-stream-address
            {:wallet {:address owner}
             :account-context {:spectate-mode {:active? true
                                               :address spectate}}})))
    (is (nil? (account-context/live-user-stream-address
               {:wallet {:address owner}
                :router {:path (str "/portfolio/trader/" trader)}})))))
