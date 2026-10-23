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
        leaderboard-vm (vm/header-vm {:router {:path "/leaderboard"}})
        api-vm (vm/header-vm {:router {:path "/API"}})
        subaccounts-vm (vm/header-vm {:router {:path "/subAccounts"}})]
    (is (= [:trade :portfolio :optimize :funding :vaults :earn :staking :referrals :leaderboard]
           (mapv :id (:desktop-nav-items funding-vm))))
    (is (true? (some->> (:desktop-nav-items funding-vm)
                        (some #(when (= :funding (:id %)) (:active? %))))))
    (is (true? (some->> (get-in leaderboard-vm [:mobile-nav :secondary-items])
                        (some #(when (= :leaderboard (:id %)) (:active? %))))))
    (is (= "header-more-link-api"
           (get-in api-vm [:more-nav :items 0 :more-data-role])))
    (is (= "header-more-link-subaccounts"
           (get-in subaccounts-vm [:more-nav :items 1 :more-data-role])))
    (is (true? (get-in api-vm [:more-nav :active?])))
    (is (true? (get-in subaccounts-vm [:more-nav :active?])))))

(deftest header-vm-projects-wallet-enable-trading-state-test
  (let [approving-vm (vm/header-vm {:wallet {:connected? true
                                             :address connected-address
                                             :agent {:status :approving}}})
        locked-vm (vm/header-vm {:wallet {:connected? true
                                          :address connected-address
                                          :agent {:status :locked}}})
        ready-vm (vm/header-vm {:wallet {:connected? true
                                         :address connected-address
                                         :agent {:status :ready}}})]
    (is (= (subs connected-address 0 6)
           (subs (get-in approving-vm [:wallet :trigger-label]) 0 6)))
    (is (= "Awaiting signature..."
           (get-in approving-vm [:wallet :enable-trading :label])))
    (is (true? (get-in approving-vm [:wallet :enable-trading :disabled?])))
    (is (= "Unlock Trading"
           (get-in locked-vm [:wallet :enable-trading :label])))
    (is (= [[:actions/unlock-agent-trading]]
           (get-in locked-vm [:wallet :enable-trading :action])))
    (is (false? (get-in locked-vm [:wallet :enable-trading :disabled?])))
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
                                                 :show-chart-trades? true
                                                 :open-order-safety-mode :extended}})
        sections (get-in result [:settings :sections])
        session-row (row-by-id sections :session :storage-mode)
        safety-row (row-by-id sections :open-orders :open-order-safety-mode)
        open-orders-row (row-by-id sections :confirmations :confirm-open-orders)
        close-position-row (row-by-id sections :confirmations :confirm-close-position)
        market-orders-row (row-by-id sections :confirmations :confirm-market-orders)
        sound-row (row-by-id sections :alerts :sound-on-fill)
        chart-trades-row (row-by-id sections :display :chart-trades)]
    (is (= [:session :open-orders :confirmations :alerts :display :appearance]
           (mapv :id sections)))
    (is (= "trading-settings-storage-mode-row" (:data-role session-row)))
    (is (= "Open order safety" (:title safety-row)))
    (is (= "Account/vault-wide offline cancel behavior." (:hint safety-row)))
    (is (= :choice (:kind safety-row)))
    (is (= ["strict" "extended" "off"]
           (mapv :value (:options safety-row))))
    (is (= ["Strict" "4h" "Off"]
           (mapv :label (:options safety-row))))
    (is (= ["Cancels open orders if Hyperopen stops refreshing for about 1 minute."
            "Keeps the dead-man switch, but gives this account or vault about 4 hours offline before canceling."
            "Clears Hyperliquid scheduled cancel. GTC orders stay live until filled, manually canceled, or rejected."]
           (mapv :tooltip (:options safety-row))))
    (is (= [false true false]
           (mapv :active? (:options safety-row))))
    (is (= [[:actions/set-open-order-safety-mode "off"]]
           (-> safety-row :options (nth 2) :action)))
    (is (= "These settings live on this device only."
           (get-in result [:settings :footer-note])))
    (is (not (contains? (:settings result) :keydown-action)))
    (is (= "Remember session on this device?"
           (get-in session-row [:confirmation :title])))
    (is (= "Changes trading persistence on this device and will require Enable Trading again."
           (get-in session-row [:confirmation :body])))
    (is (= "Confirm open orders" (:title open-orders-row)))
    (is (= [[:actions/request-agent-storage-mode-change true]]
           (:on-change session-row)))
    (is (= [[:actions/set-confirm-open-orders-enabled false]]
           (:on-change open-orders-row)))
    (is (= [[:actions/set-confirm-close-position-enabled true]]
           (:on-change close-position-row)))
    (is (= "Confirm market orders" (:title market-orders-row)))
    (is (true? (:checked? market-orders-row)))
    (is (= [[:actions/set-confirm-market-orders-enabled false]]
           (:on-change market-orders-row)))
    (is (= "Sound on fill" (:title sound-row)))
    (is (false? (:checked? sound-row)))
    (is (= [[:actions/set-sound-on-fill-enabled true]]
           (:on-change sound-row)))
    (is (= "Trades on chart" (:title chart-trades-row)))
    (is (= "trading-settings-chart-trades-row" (:data-role chart-trades-row)))
    (is (true? (:checked? chart-trades-row)))
    (is (= [[:actions/set-chart-trades-enabled false]]
           (:on-change chart-trades-row)))))

(deftest header-vm-projects-theme-choice-row-test
  (let [result (vm/header-vm {:ui {:theme "institutional"}
                              :header-ui {:settings-open? true}})
        sections (get-in result [:settings :sections])
        theme-row (row-by-id sections :appearance :ui-theme)]
    (is (= :choice (:kind theme-row)))
    (is (= "trading-settings-ui-theme-row" (:data-role theme-row)))
    (is (= ["dark" "institutional" "hyperdegen"]
           (mapv :value (:options theme-row))))
    (is (= ["HyperLiquid" "Institutional" "HyperDegen"]
           (mapv :label (:options theme-row))))
    (is (= [false true false]
           (mapv :active? (:options theme-row))))
    (is (= [[:actions/set-ui-theme "hyperdegen"]]
           (-> theme-row :options (nth 2) :action)))))

(deftest header-vm-brand-voice-test
  (let [degen (vm/header-vm {:ui {:theme "hyperdegen"}})
        plain (vm/header-vm {})]
    (is (= {:wordmark "HyperDegen" :mark "HD" :tagline "formerly responsible"}
           (:brand degen)))
    (is (= {:wordmark "HyperOpen" :mark "HO" :tagline nil}
           (:brand plain)))))

(deftest header-vm-degen-voice-relabels-nav-test
  (let [degen (vm/header-vm {:ui {:theme "hyperdegen"}})
        plain (vm/header-vm {})]
    (is (= ["Trade (Gamble)" "Portfolio (Hope)" "Optimize (Cope)" "Funding (Brrr)"
            "Vaults (LOL)" "Earn (Yield Farm)" "Staking (Zzz)" "Referrals (Spam)" "Leaderboard (Flex)"]
           (mapv :label (:desktop-nav-items degen))))
    (is (= ["Trade" "Portfolio" "Optimize" "Funding" "Vaults" "Earn" "Staking" "Referrals"
            "Leaderboard"]
           (mapv :label (:desktop-nav-items plain))))
    (is (= ["API (Nerds)" "Sub-Accounts (Alts)"]
           (mapv :label (get-in degen [:more-nav :items]))))
    (is (= ["Trade (Gamble)" "Portfolio (Hope)" "Funding (Brrr)" "Vaults (LOL)"]
           (mapv :label (get-in degen [:mobile-nav :primary-items]))))
    (is (= ["Optimize (Cope)" "Earn (Yield Farm)" "Staking (Zzz)" "Referrals (Spam)" "Leaderboard (Flex)"]
           (mapv :label (get-in degen [:mobile-nav :secondary-items]))))
    (is (= ["Trade" "Portfolio" "Funding" "Vaults"]
           (mapv :label (get-in plain [:mobile-nav :primary-items]))))))

(deftest header-vm-theme-choice-defaults-to-dark-test
  (let [result (vm/header-vm {:header-ui {:settings-open? true}})
        theme-row (row-by-id (get-in result [:settings :sections])
                             :appearance
                             :ui-theme)]
    (is (= [true false false]
           (mapv :active? (:options theme-row))))))

(deftest header-vm-projects-passkey-session-toggle-when-remembered-session-is-enabled-test
  (let [result (vm/header-vm {:wallet {:agent {:storage-mode :local
                                               :status :ready
                                               :local-protection-mode :passkey
                                               :passkey-supported? true}}
                              :header-ui {:settings-open? true}})
        sections (get-in result [:settings :sections])
        passkey-row (row-by-id sections :session :local-protection-mode)]
    (is (= "Lock trading with passkey" (:title passkey-row)))
    (is (true? (:checked? passkey-row)))
    (is (false? (:disabled? passkey-row)))
    (is (= [[:actions/request-agent-local-protection-mode-change :plain]]
           (:on-change passkey-row)))
    (is (nil? (:confirmation passkey-row)))
    (is (nil? (:helper-copy passkey-row)))
    (is (= "Trading stays remembered on this device, but you will need one passkey unlock after a browser restart before orders can be signed again."
           (:tooltip passkey-row)))))

(deftest header-vm-disables-passkey-downgrade-while-trading-is-locked-test
  (let [result (vm/header-vm {:wallet {:agent {:status :locked
                                               :storage-mode :local
                                               :local-protection-mode :passkey
                                               :passkey-supported? true}}
                              :header-ui {:settings-open? true}})
        sections (get-in result [:settings :sections])
        passkey-row (row-by-id sections :session :local-protection-mode)]
    (is (true? (:checked? passkey-row)))
    (is (true? (:disabled? passkey-row)))
    (is (nil? (:helper-copy passkey-row)))
    (is (= "Unlock trading before turning off passkey protection."
           (:tooltip passkey-row)))))

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

(deftest header-vm-projects-spectate-aware-desktop-and-more-hrefs-test
  (let [result (vm/header-vm {:router {:path "/trade"}
                              :account-context {:spectate-mode {:active? true
                                                                 :address connected-address
                                                                 :started-at-ms 1}}})
        portfolio-item (some #(when (= :portfolio (:id %)) %) (:desktop-nav-items result))
        trade-item (some #(when (= :trade (:id %)) %) (:desktop-nav-items result))
        more-api-item (first (get-in result [:more-nav :items]))]
    (is (= "/portfolio?spectate=0x1234567890abcdef1234567890abcdef12345678"
           (:href portfolio-item)))
    (is (= "/trade?spectate=0x1234567890abcdef1234567890abcdef12345678"
           (:href trade-item)))
    (is (= "/api?spectate=0x1234567890abcdef1234567890abcdef12345678"
           (:href more-api-item)))
    (is (= [[:actions/navigate "/portfolio"]]
           (:action portfolio-item)))
    (is (= [[:actions/navigate "/api"]]
           (:action more-api-item)))))
