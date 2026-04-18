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
        api-vm (vm/header-vm {:router {:path "/API"}})]
    (is (= [:trade :portfolio :funding :vaults :staking :leaderboard]
           (mapv :id (:desktop-nav-items funding-vm))))
    (is (true? (some->> (:desktop-nav-items funding-vm)
                        (some #(when (= :funding (:id %)) (:active? %))))))
    (is (true? (some->> (get-in leaderboard-vm [:mobile-nav :secondary-items])
                        (some #(when (= :leaderboard (:id %)) (:active? %))))))
    (is (= "header-more-link-api"
           (get-in api-vm [:more-nav :items 0 :more-data-role])))
    (is (true? (get-in api-vm [:more-nav :active?])))))

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
                                                 :show-fill-markers? false}})
        sections (get-in result [:settings :sections])
        session-row (row-by-id sections :session :storage-mode)
        open-orders-row (row-by-id sections :confirmations :confirm-open-orders)
        close-position-row (row-by-id sections :confirmations :confirm-close-position)
        market-orders-row (row-by-id sections :confirmations :confirm-market-orders)
        sound-row (row-by-id sections :alerts :sound-on-fill)
        fill-markers-row (row-by-id sections :display :fill-markers)]
    (is (= [:session :confirmations :alerts :display]
           (mapv :id sections)))
    (is (= "trading-settings-storage-mode-row" (:data-role session-row)))
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
    (is (= "Fill markers" (:title fill-markers-row)))))

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
