(ns hyperopen.views.header.vm-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.header.vm :as vm]))

(def connected-address
  "0x1234567890abcdef1234567890abcdef12345678")

(defn- row-by-id
  [sections section-id row-id]
  (->> sections
       (some #(when (= section-id (:id %)) %))
       :rows
       (some #(when (= row-id (:id %)) %))))

(deftest header-vm-centralizes-route-aware-nav-state-test
  (let [funding-vm (vm/header-vm {:router {:path "/fundingComparison"}})
        referrals-vm (vm/header-vm {:router {:path "/referrals"}})
        api-vm (vm/header-vm {:router {:path "/API"}})]
    (is (= [:trade :portfolio :funding :earn :vaults :staking :referrals :leaderboard]
           (mapv :id (:desktop-nav-items funding-vm))))
    (is (true? (some->> (:desktop-nav-items funding-vm)
                        (some #(when (= :funding (:id %)) (:active? %))))))
    (is (true? (some->> (get-in referrals-vm [:mobile-nav :secondary-items])
                        (some #(when (= :referrals (:id %)) (:active? %))))))
    (is (= "header-more-link-api"
           (get-in api-vm [:more-nav :items 0 :more-data-role])))
    (is (true? (get-in api-vm [:more-nav :active?])))))

(deftest header-vm-projects-wallet-enable-trading-state-test
  (let [approving-vm (vm/header-vm {:wallet {:connected? true
                                             :address connected-address
                                             :agent {:status :approving}}})
        ready-vm (vm/header-vm {:wallet {:connected? true
                                         :address connected-address
                                         :agent {:status :ready}}})]
    (is (= (subs connected-address 0 6)
           (subs (get-in approving-vm [:wallet :trigger-label]) 0 6)))
    (is (= "Awaiting signature..."
           (get-in approving-vm [:wallet :enable-trading :label])))
    (is (true? (get-in approving-vm [:wallet :enable-trading :disabled?])))
    (is (nil? (get-in ready-vm [:wallet :enable-trading])))))

(deftest header-vm-projects-data-driven-settings-sections-test
  (let [result (vm/header-vm {:wallet {:agent {:storage-mode :session}}
                              :header-ui {:settings-open? true
                                          :settings-confirmation {:kind :agent-storage-mode
                                                                  :next-mode :local}}
                              :trading-settings {:fill-alerts-enabled? true
                                                 :confirm-open-orders? true
                                                 :confirm-close-position? false
                                                 :animate-orderbook? true
                                                 :show-fill-markers? false}})
        sections (get-in result [:settings :sections])
        session-row (row-by-id sections :session :storage-mode)
        open-orders-row (row-by-id sections :confirmations :confirm-open-orders)
        fill-markers-row (row-by-id sections :display :fill-markers)]
    (is (= [:session :confirmations :alerts :display]
           (mapv :id sections)))
    (is (= "trading-settings-storage-mode-row" (:data-role session-row)))
    (is (= "Remember session on this device?"
           (get-in session-row [:confirmation :title])))
    (is (= "Changes trading persistence on this device and will require Enable Trading again."
           (get-in session-row [:confirmation :body])))
    (is (= "Confirm open orders" (:title open-orders-row)))
    (is (= "Fill markers" (:title fill-markers-row)))))

(deftest header-vm-projects-spectate-copy-from-state-test
  (let [inactive-vm (vm/header-vm {})
        active-vm (vm/header-vm {:account-context {:spectate-mode {:active? true
                                                                   :address connected-address
                                                                   :started-at-ms 1}}
                                 :spectate-ui {:modal-open? false
                                               :search connected-address
                                               :last-search connected-address
                                               :search-error nil}
                                 :watchlist [connected-address]
                                 :watchlist-loaded? true})]
    (is (= "Open Spectate Mode"
           (get-in inactive-vm [:spectate :button-label])))
    (is (= "Inspect another wallet in read-only mode. Click to open Spectate Mode and choose an address."
           (get-in inactive-vm [:spectate :tooltip-copy])))
    (is (= "Manage Spectate Mode"
           (get-in active-vm [:spectate :button-label])))
    (is (= "Spectate Mode is active. Click to manage the address you are viewing or stop spectating."
           (get-in active-vm [:spectate :tooltip-copy])))))
