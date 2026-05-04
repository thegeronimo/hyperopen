(ns hyperopen.startup.restore-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.platform :as platform]
            [hyperopen.startup.restore :as startup-restore]
            [hyperopen.wallet.agent-session :as agent-session]))

(defn- restore-trading-settings-fn
  []
  (resolve 'hyperopen.startup.restore/restore-trading-settings!))

(deftest restore-agent-storage-mode-uses-local-default-for-missing-preference-test
  (let [store (atom {})
        defaults (atom [])]
    (with-redefs [agent-session/load-storage-mode-preference
                  (fn
                    ([] :local)
                    ([missing-default]
                     (swap! defaults conj missing-default)
                     :local))]
      (startup-restore/restore-agent-storage-mode! store)
      (is (= [:local] @defaults))
      (is (= :local (get-in @store [:wallet :agent :storage-mode]))))))

(deftest restore-agent-storage-mode-preserves-existing-stored-choice-test
  (let [store (atom {})]
    (with-redefs [agent-session/load-storage-mode-preference
                  (fn
                    ([] :local)
                    ([_missing-default] :local))]
      (startup-restore/restore-agent-storage-mode! store)
      (is (= :local (get-in @store [:wallet :agent :storage-mode]))))))

(deftest restore-spectate-mode-preferences-loads-watchlist-and-search-test
  (let [store (atom {:account-context {:spectate-ui {:search-error "old"}}})]
    (with-redefs [platform/local-storage-get (fn [key]
                                               (case key
                                                 "spectate-mode-watchlist:v1"
                                                 "[{\"address\":\"0x1111111111111111111111111111111111111111\",\"label\":\"Core\"},\"bad\",\"0x2222222222222222222222222222222222222222\"]"
                                                 "spectate-mode-last-search:v1"
                                                 " 0x3333333333333333333333333333333333333333 "
                                                 nil))]
      (startup-restore/restore-spectate-mode-preferences! store)
      (is (= [{:address "0x1111111111111111111111111111111111111111"
               :label "Core"}
              {:address "0x2222222222222222222222222222222222222222"
               :label nil}]
             (get-in @store [:account-context :watchlist])))
      (is (= true (get-in @store [:account-context :watchlist-loaded?])))
      (is (= "0x3333333333333333333333333333333333333333"
             (get-in @store [:account-context :spectate-ui :search])))
      (is (= "0x3333333333333333333333333333333333333333"
             (get-in @store [:account-context :spectate-ui :last-search])))
      (is (= "" (get-in @store [:account-context :spectate-ui :label])))
      (is (nil? (get-in @store [:account-context :spectate-ui :editing-watchlist-address])))
      (is (nil? (get-in @store [:account-context :spectate-ui :search-error]))))))

(deftest restore-spectate-mode-preferences-falls-back-on-malformed-storage-test
  (let [store (atom {:account-context (account-context/default-account-context-state)})]
    (with-redefs [platform/local-storage-get (fn [key]
                                               (case key
                                                 "spectate-mode-watchlist:v1"
                                                 "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa,not-valid,0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                                 "spectate-mode-last-search:v1"
                                                 " "
                                                 nil))]
      (startup-restore/restore-spectate-mode-preferences! store)
      (is (= [{:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
               :label nil}
              {:address "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
               :label nil}]
             (get-in @store [:account-context :watchlist])))
      (is (= "" (get-in @store [:account-context :spectate-ui :search])))
      (is (= "" (get-in @store [:account-context :spectate-ui :last-search])))
      (is (= "" (get-in @store [:account-context :spectate-ui :label])))
      (is (nil? (get-in @store [:account-context :spectate-ui :editing-watchlist-address]))))))

(deftest restore-spectate-mode-preferences-migrates-legacy-ghost-storage-test
  (let [store (atom {:account-context (account-context/default-account-context-state)})
        set-calls (atom [])
        remove-calls (atom [])]
    (with-redefs [platform/local-storage-get (fn [key]
                                               (case key
                                                 "spectate-mode-watchlist:v1" nil
                                                 "spectate-mode-last-search:v1" nil
                                                 "ghost-mode-watchlist:v1"
                                                 "[{\"address\":\"0x1111111111111111111111111111111111111111\",\"label\":\"Core\"}]"
                                                 "ghost-mode-last-search:v1"
                                                 " 0x2222222222222222222222222222222222222222 "
                                                 nil))
                  platform/local-storage-set! (fn [key value]
                                                (swap! set-calls conj [key value]))
                  platform/local-storage-remove! (fn [key]
                                                   (swap! remove-calls conj key))]
      (startup-restore/restore-spectate-mode-preferences! store)
      (is (= [{:address "0x1111111111111111111111111111111111111111"
               :label "Core"}]
             (get-in @store [:account-context :watchlist])))
      (is (= "0x2222222222222222222222222222222222222222"
             (get-in @store [:account-context :spectate-ui :search])))
      (is (= "0x2222222222222222222222222222222222222222"
             (get-in @store [:account-context :spectate-ui :last-search])))
      (is (= [["spectate-mode-watchlist:v1"
               "[{\"address\":\"0x1111111111111111111111111111111111111111\",\"label\":\"Core\"}]"]
              ["spectate-mode-last-search:v1"
               "0x2222222222222222222222222222222222222222"]]
             @set-calls))
      (is (= ["shadow-mode-watchlist:v1"
              "ghost-mode-watchlist:v1"
              "shadow-mode-last-search:v1"
              "ghost-mode-last-search:v1"]
             @remove-calls)))))

(deftest restore-spectate-mode-preferences-migrates-legacy-shadow-storage-test
  (let [store (atom {:account-context (account-context/default-account-context-state)})
        set-calls (atom [])
        remove-calls (atom [])]
    (with-redefs [platform/local-storage-get (fn [key]
                                               (case key
                                                 "spectate-mode-watchlist:v1" nil
                                                 "spectate-mode-last-search:v1" nil
                                                 "shadow-mode-watchlist:v1"
                                                 "[{\"address\":\"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"label\":\"Primary\"}]"
                                                 "shadow-mode-last-search:v1"
                                                 " 0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb "
                                                 "ghost-mode-watchlist:v1"
                                                 "[{\"address\":\"0x1111111111111111111111111111111111111111\",\"label\":\"Ghost\"}]"
                                                 "ghost-mode-last-search:v1"
                                                 "0x2222222222222222222222222222222222222222"
                                                 nil))
                  platform/local-storage-set! (fn [key value]
                                                (swap! set-calls conj [key value]))
                  platform/local-storage-remove! (fn [key]
                                                   (swap! remove-calls conj key))]
      (startup-restore/restore-spectate-mode-preferences! store)
      (is (= [{:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
               :label "Primary"}]
             (get-in @store [:account-context :watchlist])))
      (is (= "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
             (get-in @store [:account-context :spectate-ui :search])))
      (is (= [["spectate-mode-watchlist:v1"
               "[{\"address\":\"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"label\":\"Primary\"}]"]
              ["spectate-mode-last-search:v1"
               "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"]]
             @set-calls))
      (is (= ["shadow-mode-watchlist:v1"
              "ghost-mode-watchlist:v1"
              "shadow-mode-last-search:v1"
              "ghost-mode-last-search:v1"]
             @remove-calls)))))

(deftest restore-spectate-mode-preferences-prefers-spectate-storage-over-shadow-and-ghost-test
  (let [store (atom {:account-context (account-context/default-account-context-state)})
        set-calls (atom [])
        remove-calls (atom [])]
    (with-redefs [platform/local-storage-get (fn [key]
                                               (case key
                                                 "spectate-mode-watchlist:v1"
                                                 "[{\"address\":\"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"label\":\"Primary\"}]"
                                                 "spectate-mode-last-search:v1"
                                                 "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                                 "shadow-mode-watchlist:v1"
                                                 "[{\"address\":\"0x9999999999999999999999999999999999999999\",\"label\":\"Shadow\"}]"
                                                 "shadow-mode-last-search:v1"
                                                 "0x8888888888888888888888888888888888888888"
                                                 "ghost-mode-watchlist:v1"
                                                 "[{\"address\":\"0x1111111111111111111111111111111111111111\",\"label\":\"Legacy\"}]"
                                                 "ghost-mode-last-search:v1"
                                                 "0x2222222222222222222222222222222222222222"
                                                 nil))
                  platform/local-storage-set! (fn [key value]
                                                (swap! set-calls conj [key value]))
                  platform/local-storage-remove! (fn [key]
                                                   (swap! remove-calls conj key))]
      (startup-restore/restore-spectate-mode-preferences! store)
      (is (= [{:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
               :label "Primary"}]
             (get-in @store [:account-context :watchlist])))
      (is (= "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
             (get-in @store [:account-context :spectate-ui :search])))
      (is (empty? @set-calls))
      (is (empty? @remove-calls)))))

(deftest restore-trading-settings-valid-storage-restores-fill-alerts-disabled-test
  (let [store (atom {})
        restore-fn (restore-trading-settings-fn)]
    (is (some? restore-fn))
    (when restore-fn
      (with-redefs [platform/local-storage-get (fn [key]
                                                 (case key
                                                   "hyperopen:trading-settings:v1"
                                                   "{\"fill-alerts-enabled?\":false}"
                                                   nil))]
        (restore-fn store)
        (is (= false (get-in @store [:trading-settings :fill-alerts-enabled?])))))))

(deftest restore-trading-settings-missing-storage-defaults-fill-alerts-enabled-to-true-test
  (let [store (atom {})
        restore-fn (restore-trading-settings-fn)]
    (is (some? restore-fn))
    (when restore-fn
      (with-redefs [platform/local-storage-get (constantly nil)]
        (restore-fn store)
        (is (= true (get-in @store [:trading-settings :fill-alerts-enabled?])))))))

(deftest restore-trading-settings-missing-storage-defaults-phase-1-5-settings-test
  (let [store (atom {})
        restore-fn (restore-trading-settings-fn)]
    (is (some? restore-fn))
    (when restore-fn
      (with-redefs [platform/local-storage-get (constantly nil)]
        (restore-fn store)
        (is (= true (get-in @store [:trading-settings :animate-orderbook?])))
        (is (= false (get-in @store [:trading-settings :show-fill-markers?])))
        (is (= false (get-in @store [:trading-settings :confirm-open-orders?])))
        (is (= false (get-in @store [:trading-settings :confirm-close-position?])))
        (is (= true (get-in @store [:trading-settings :confirm-market-orders?])))
        (is (= false (get-in @store [:trading-settings :sound-on-fill?])))))))

(deftest restore-trading-settings-valid-storage-restores-phase-1-5-settings-test
  (let [store (atom {})
        restore-fn (restore-trading-settings-fn)]
    (is (some? restore-fn))
    (when restore-fn
      (with-redefs [platform/local-storage-get (fn [key]
                                                 (case key
                                                   "hyperopen:trading-settings:v1"
                                                   "{\"fill-alerts-enabled?\":false,\"animate-orderbook?\":false,\"show-fill-markers?\":true,\"confirm-open-orders?\":false,\"confirm-close-position?\":false}"
                                                   nil))]
        (restore-fn store)
        (is (= false (get-in @store [:trading-settings :fill-alerts-enabled?])))
        (is (= false (get-in @store [:trading-settings :animate-orderbook?])))
        (is (= true (get-in @store [:trading-settings :show-fill-markers?])))
        (is (= false (get-in @store [:trading-settings :confirm-open-orders?])))
        (is (= false (get-in @store [:trading-settings :confirm-close-position?])))))))

(deftest restore-trading-settings-missing-confirmation-keys-defaults-to-disabled-test
  (let [store (atom {})
        restore-fn (restore-trading-settings-fn)]
    (is (some? restore-fn))
    (when restore-fn
      (with-redefs [platform/local-storage-get (fn [key]
                                                 (case key
                                                   "hyperopen:trading-settings:v1"
                                                   "{\"fill-alerts-enabled?\":true,\"animate-orderbook?\":true,\"show-fill-markers?\":false}"
                                                   nil))]
        (restore-fn store)
        (is (= false (get-in @store [:trading-settings :confirm-open-orders?])))
        (is (= false (get-in @store [:trading-settings :confirm-close-position?])))))))

(deftest restore-trading-settings-malformed-storage-falls-back-safely-test
  (let [store (atom {:trading-settings {:fill-alerts-enabled? false}})
        restore-fn (restore-trading-settings-fn)]
    (is (some? restore-fn))
    (when restore-fn
      (with-redefs [platform/local-storage-get (fn [key]
                                                 (case key
                                                   "hyperopen:trading-settings:v1"
                                                   "{not-json"
                                                   nil))]
        (restore-fn store)
        (is (= true (get-in @store [:trading-settings :fill-alerts-enabled?])))))))

(deftest restore-spectate-mode-url-activates-spectate-without-mutating-watchlist-test
  (let [store (atom {:account-context {:spectate-mode {:active? false
                                                       :address nil
                                                       :started-at-ms nil}
                                       :spectate-ui {:search "0x1111111111111111111111111111111111111111"
                                                     :last-search "0x1111111111111111111111111111111111111111"
                                                     :label "Existing"
                                                     :editing-watchlist-address "0x1111111111111111111111111111111111111111"
                                                     :search-error "old"}
                                       :watchlist [{:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                                    :label "Core"}]
                                       :watchlist-loaded? true}})]
    (startup-restore/restore-spectate-mode-url!
     store
     "?spectate=0xABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD"
     1710000000000)
    (is (= true (get-in @store [:account-context :spectate-mode :active?])))
    (is (= "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
           (get-in @store [:account-context :spectate-mode :address])))
    (is (= 1710000000000
           (get-in @store [:account-context :spectate-mode :started-at-ms])))
    (is (= "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
           (get-in @store [:account-context :spectate-ui :search])))
    (is (= "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd"
           (get-in @store [:account-context :spectate-ui :last-search])))
    (is (= [{:address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
             :label "Core"}]
           (get-in @store [:account-context :watchlist])))))

(deftest restore-spectate-mode-url-ignores-invalid-query-values-test
  (let [store (atom {:account-context (account-context/default-account-context-state)})]
    (startup-restore/restore-spectate-mode-url! store "?spectate=not-an-address" 1710000000000)
    (is (= false (get-in @store [:account-context :spectate-mode :active?])))
    (is (nil? (get-in @store [:account-context :spectate-mode :address])))))

(deftest restore-trade-route-tab-normalizes-url-query-tab-test
  (let [store (atom {:account-info {:selected-tab :balances}})]
    (startup-restore/restore-trade-route-tab! store "?tab=orderHistory")
    (is (= :order-history
           (get-in @store [:account-info :selected-tab])))
    (startup-restore/restore-trade-route-tab! store "?tab=accountActivity")
    (is (= :funding-history
           (get-in @store [:account-info :selected-tab])))))

(deftest restore-trade-route-tab-ignores-unknown-url-query-tab-test
  (let [store (atom {:account-info {:selected-tab :positions}})]
    (startup-restore/restore-trade-route-tab! store "?tab=not-a-tab")
    (is (= :positions
           (get-in @store [:account-info :selected-tab])))))

(deftest restore-active-asset-prefers-market-query-over-route-and-storage-test
  (let [store (atom {:router {:path "/trade/BTC"}
                     :active-asset nil
                     :selected-asset nil
                     :active-market nil})
        local-storage-set-calls (atom [])]
    (with-redefs [platform/local-storage-get (fn [key]
                                               (case key
                                                 "active-asset" "ETH"
                                                 nil))
                  platform/local-storage-set! (fn [key value]
                                                (swap! local-storage-set-calls conj [key value]))]
      (startup-restore/restore-active-asset!
       store
       {:connected?-fn (constantly false)
        :dispatch! (fn [& _] nil)
        :load-active-market-display-fn (fn [_] nil)
        :search "?market=CL&tab=positions"}))
    (is (= "CL" (:active-asset @store)))
    (is (= "CL" (:selected-asset @store)))
    (is (= [["active-asset" "CL"]]
           @local-storage-set-calls))))

(deftest restore-active-asset-defaults-expired-cached-outcome-to-btc-test
  (let [expired-outcome {:key "outcome:1"
                         :coin "#10"
                         :symbol "BTC above 78213 on May 3 at 2:00 AM?"
                         :base "BTC"
                         :market-type :outcome
                         :expiry-ms 1777788000000
                         :outcome-sides [{:side-index 0 :coin "#10"}
                                         {:side-index 1 :coin "#11"}]}
        store (atom {:router {:path "/trade"}
                     :active-asset nil
                     :selected-asset nil
                     :active-market nil})
        local-storage-set-calls (atom [])
        loaded-assets (atom [])]
    (with-redefs [platform/now-ms (fn [] 1777874400000)
                  platform/local-storage-get (fn [key]
                                               (case key
                                                 "active-asset" "#10"
                                                 nil))
                  platform/local-storage-set! (fn [key value]
                                                (swap! local-storage-set-calls conj [key value]))]
      (startup-restore/restore-active-asset!
       store
       {:connected?-fn (constantly false)
        :dispatch! (fn [& _] nil)
        :load-active-market-display-fn (fn [asset]
                                         (swap! loaded-assets conj asset)
                                         (when (= "#10" asset)
                                           expired-outcome))}))
    (is (= ["#10"] @loaded-assets))
    (is (= "BTC" (:active-asset @store)))
    (is (= "BTC" (:selected-asset @store)))
    (is (nil? (:active-market @store)))
    (is (= [["active-asset" "BTC"]]
           @local-storage-set-calls))))
