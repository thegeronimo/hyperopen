(ns hyperopen.views.account-info.tabs.balances.hyperevm-reasons-test
  "Why a Balances move or HyperEVM row is missing or disabled is visible
   text: the note under the table, a mobile card's reason line, and the
   HyperEVM filter's empty states. Rendered from app state through the real
   view-model."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-transfer-preview]
            [hyperopen.funding.test-support.hyperevm-transfer :as fixture]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.account-info.tabs.balances.location-filter :as location-filter]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]))

(def ^:private state (fixture/state))

(def ^:private master-only
  "HyperEVM transfers are available for the master account only.")

(defn- panel
  [state*]
  (account-info-view/account-info-panel state*))

(defn- by-role
  [node role]
  (hiccup/find-by-data-role node role))

(defn- class-set
  [node]
  (hiccup/node-class-set node))

(defn- shown-text
  "The text of the node with data-role `role`, or nil when it is absent or
   hidden by class."
  [content role]
  (let [node (by-role content role)]
    (when (and node (not (contains? (class-set node) "hidden")))
      (apply str (hiccup/collect-strings node)))))

(defn- desktop-row
  [content role]
  (some #(when (by-role % role) %)
        (hiccup/node-children (by-role content "account-tab-rows-viewport"))))

(defn- with-account-info
  [state* k v]
  (assoc-in state* [:account-info k] v))

(def ^:private nothing-on-hyperevm
  (assoc fixture/evm-entry :native-wei "0" :token-units {}))

(def ^:private failed-first-read
  {:status :error :stale? false :error "rate limited" :error-kind :rate-limit})

(deftest a-subaccount-sees-the-master-only-reason-without-hyperevm-rows-test
  (let [subaccount-state (-> state
                             (assoc :account-context
                                    {:subaccounts {:rows [{:sub-account-user fixture/subaccount
                                                           :master fixture/owner}]
                                                   :selected-address fixture/subaccount}})
                             (hyperevm-balances/apply-loading {:addresses [fixture/subaccount]
                                                               :requested-at-ms 5})
                             (hyperevm-balances/apply-success fixture/subaccount 5
                                                              {:native-wei "0"
                                                               :token-units {}
                                                               :gas-price-wei fixture/gas-price-wei}
                                                              #{0 1}
                                                              6))
        content (panel subaccount-state)]
    (is (nil? (by-role content "location-chip-hyperevm")) "the subaccount holds nothing on HyperEVM")
    (is (= "true" (get-in (by-role content "balances-move-spot-150-hyperevm") [1 :aria-disabled])))
    (is (contains? (class-set (by-role content "balances-hyperevm-note")) "lg:block")
        "the note shows because a rendered move is disabled by the account")
    (is (= master-only (shown-text content "balances-hyperevm-moves-blocked")))
    (is (= master-only (shown-text content "balances-hyperevm-moves-blocked-mobile")))))

(deftest a-pending-or-failed-first-read-is-said-under-all-test
  (let [loading (panel (assoc-in state [:hyperevm :balances :by-address] {}))
        failed-state (fixture/with-evm-entry state failed-first-read)
        failed (panel failed-state)]
    (is (= (:checking-balances evm-transfer-preview/messages)
           (shown-text loading "balances-hyperevm-status")))
    (is (contains? (class-set (by-role loading "balances-hyperevm-note")) "lg:block"))
    (is (= (:balances-unavailable evm-transfer-preview/messages)
           (shown-text failed "balances-hyperevm-status")))
    (is (= (:balances-unavailable evm-transfer-preview/messages)
           (shown-text failed "balances-hyperevm-status-mobile")))
    (testing "silent once read, and under the HyperCore filter"
      (is (nil? (shown-text (panel state) "balances-hyperevm-status")))
      (let [nothing (panel (fixture/with-evm-entry state nothing-on-hyperevm))]
        (is (nil? (shown-text nothing "balances-hyperevm-status")))
        (is (contains? (class-set (by-role nothing "balances-hyperevm-note")) "hidden")))
      (is (nil? (shown-text (panel (with-account-info failed-state :balances-location-filter :hypercore))
                            "balances-hyperevm-status"))))))

(deftest a-mobile-card-shows-a-disabled-move-reason-as-text-test
  (let [expanded (fn [row-key]
                   (panel (with-account-info state :mobile-expanded-card {:balances row-key})))
        six-card (by-role (expanded "spot-6") "mobile-balance-card-spot-6")
        to-evm (by-role six-card "balances-move-mobile-spot-6-hyperevm")
        reasons (by-role six-card "balances-moves-mobile-reasons-spot-6")
        reason-line (hiccup/find-first-node reasons #(= (get-in to-evm [1 :aria-describedby])
                                                        (get-in % [1 :id])))]
    (is (= "true" (get-in to-evm [1 :aria-disabled])))
    (is (not (contains? (class-set reasons) "hidden")))
    (is (= [(:bridge-empty-evm evm-transfer-preview/messages)]
           (hiccup/collect-strings reason-line))
        "the line the action points at is the visible reason")
    (is (not (contains? (class-set reason-line) "sr-only")))
    (testing "a card whose moves are all enabled keeps the slot, hidden"
      (let [purr-card (by-role (expanded "spot-1") "mobile-balance-card-spot-1")]
        (is (contains? (class-set (by-role purr-card "balances-moves-mobile-reasons-spot-1"))
                       "hidden"))))))

(deftest hyperevm-rows-show-no-hypercore-send-or-repay-test
  (let [content (panel state)
        hype-row (desktop-row content "balances-move-hyperevm-150-spot")
        cells (vec (hiccup/node-children hype-row))]
    (is (= [] (hiccup/collect-strings (nth cells 5))) "Send cell")
    (is (= [] (hiccup/collect-strings (nth cells 7))) "Repay cell")
    (is (= ["Send"] (hiccup/collect-strings (nth (vec (hiccup/node-children
                                                       (desktop-row content "balances-move-spot-150-hyperevm")))
                                                 5)))
        "a HyperCore row keeps its Send"))
  (let [content (panel (with-account-info state :mobile-expanded-card {:balances "hyperevm-150"}))
        card (by-role content "mobile-balance-card-hyperevm-150")
        send (hiccup/find-first-node card #(contains? (hiccup/direct-texts %) "Send"))]
    (is (nil? send) "the HyperEVM card's Send slot is empty and hidden")))

(deftest hide-small-balances-keeps-unpriced-hyperevm-tokens-test
  (let [unpriced (-> state
                     (update-in [:asset-selector :market-by-key] dissoc "spot:PURR")
                     (update-in [:spot :clearinghouse-state :balances]
                                (fn [rows] (vec (remove #(= "PURR" (:coin %)) rows))))
                     (with-account-info :hide-small-balances? true))]
    (is (some? (desktop-row (panel unpriced) "balances-move-hyperevm-1-spot"))
        "an unknown value is not a small one")))

(deftest hyperevm-filter-says-what-hid-its-rows-test
  (let [dust (fixture/with-evm-entry state (assoc fixture/evm-entry
                                                  ;; 0.01 HYPE, about $0.45
                                                  :native-wei "10000000000000000"
                                                  :token-units {}))
        evm-filter (with-account-info dust :balances-location-filter :hyperevm)
        strings (fn [state*] (set (hiccup/collect-strings (panel state*))))]
    (is (contains? (strings (with-account-info evm-filter :hide-small-balances? true))
                   location-filter/hidden-small-message))
    (is (contains? (strings (with-account-info evm-filter :balances-coin-search "PURR"))
                   location-filter/no-match-message))
    (is (not (contains? (strings (with-account-info evm-filter :hide-small-balances? true))
                        "No data available")))))
