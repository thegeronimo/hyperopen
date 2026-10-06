(ns hyperopen.views.account-equity.hyperevm-line-test
  "The Account Equity panel's \"HyperEVM · Not margin\" line: shown only once
   the shown account's HyperEVM holdings are read and worth something, its
   Move link opens Transfer preset to HyperEVM -> Spot, read-only views get
   no link, a subaccount gets a disabled link with its reason as visible
   text, and the legacy trading figures around it never change. Its known USD
   value does contribute to the classic grouped total, despite remaining
   unavailable as position margin."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.test-support.hyperevm-transfer :as fixture]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.account-equity.hyperevm-line :as hyperevm-line]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.projections.hyperevm-funds :as hyperevm-funds]))

(defn- reset-caches!
  []
  (hyperevm-funds/reset-hyperevm-funds-cache!)
  (derived-cache/reset-derived-cache!)
  (account-equity-view/reset-account-equity-metrics-cache!))

(use-fixtures :each
  (fn [f]
    (reset-caches!)
    (f)
    (reset-caches!)))

(def ^:private state
  (assoc-in (fixture/state) [:router :path] "/trade"))

(def ^:private spectated "0x5555555555555555555555555555555555555555")

(defn- by-role
  [node role]
  (hiccup/find-by-data-role node role))

(defn- text
  [node]
  (str/join "" (hiccup/collect-strings node)))

(defn- classes
  [node]
  (hiccup/node-class-set node))

(defn- line-root
  [node]
  (hiccup/find-first-node node #(contains? (classes %) "space-y-1")))

(defn- rendered
  [state*]
  (hyperevm-line/hyperevm-line (hyperevm-line/hyperevm-line-model state*)))

(deftest the-line-shows-what-the-shown-account-holds-on-hyperevm-test
  (let [model (hyperevm-line/hyperevm-line-model state)
        node (rendered state)]
    (is (:visible? model))
    (is (< (js/Math.abs (- (:usd model) 1808.5)) 1e-6))
    (is (= "account-equity-hyperevm-line" (get-in node [1 :data-role])))
    (is (not (contains? (classes node) "hidden")))
    (is (= "$1,808.50" (text (by-role node "account-equity-hyperevm-value"))))
    (is (str/includes? (text node) "HyperEVM"))
    (is (str/includes? (text node) "Not margin"))))

(deftest the-move-link-opens-hyperevm-to-spot-test
  (let [node (rendered state)
        move (by-role node "account-equity-hyperevm-move")]
    (is (= [[:actions/open-funding-transfer-modal
             :event.currentTarget/bounds
             "account-equity-hyperevm-move"
             {:from :hyperevm :to :spot}]]
           (get-in move [1 :on :click])))
    (is (= "Move funds from HyperEVM to Spot" (get-in move [1 :aria-label])))
    (is (nil? (get-in move [1 :aria-disabled])))
    (is (nil? (by-role node "account-equity-hyperevm-move-reason")))
    (is (contains? (classes (hiccup/find-first-node
                             node #(= "account-equity-hyperevm-move-reason" (get-in % [1 :id]))))
                   "hidden")
        "the reason slot stays, hidden, while the link works")))

(deftest nothing-read-or-nothing-held-keeps-the-slot-hidden-test
  (testing "before the first read"
    (let [loading (hyperevm-balances/apply-loading (assoc-in state [:hyperevm :balances :by-address] {})
                                                   {:addresses [fixture/owner] :requested-at-ms 5})
          node (rendered loading)]
      (is (false? (:visible? (hyperevm-line/hyperevm-line-model loading))))
      (is (contains? (classes node) "hidden"))
      (is (not (str/includes? (text node) "$")) "no fake $0.00")
      (is (not (str/includes? (text node) "--")))))
  (testing "a failed first read"
    (let [failed (fixture/with-evm-entry state {:status :error :error "x" :error-kind :rate-limit})]
      (is (contains? (classes (rendered failed)) "hidden"))))
  (testing "an empty HyperEVM wallet"
    (let [empty-wallet (fixture/with-evm-entry state (assoc fixture/evm-entry :native-wei "0" :token-units {}))]
      (is (contains? (classes (rendered empty-wallet)) "hidden"))))
  (testing "a nil model (the panel rendered without one)"
    (let [node (hyperevm-line/hyperevm-line nil)]
      (is (contains? (classes node) "hidden"))
      (is (nil? (by-role node "account-equity-hyperevm-move"))))))

(deftest read-only-views-show-the-funds-without-a-move-link-test
  (let [watched (-> state
                    (assoc :account-context {:spectate-mode {:active? true :address spectated}})
                    (assoc-in [:hyperevm :balances :by-address spectated]
                              (assoc fixture/evm-entry :token-units {fixture/usdc-index "5000000"})))
        model (hyperevm-line/hyperevm-line-model watched)
        node (rendered watched)]
    (is (:visible? model))
    (is (nil? (:move-action model)))
    (is (not (contains? (classes node) "hidden")))
    (is (nil? (by-role node "account-equity-hyperevm-move")) "no link to disable")
    (is (contains? (classes (hiccup/find-first-node node #(= "Move" (last %)))) "hidden"))))

(deftest a-subaccount-sees-a-disabled-move-and-why-test
  (let [sub (-> state
                (assoc :account-context {:subaccounts {:rows [{:sub-account-user fixture/subaccount
                                                               :master fixture/owner}]
                                                       :selected-address fixture/subaccount}})
                (assoc-in [:hyperevm :balances :by-address fixture/subaccount] fixture/evm-entry))
        node (rendered sub)
        move (by-role node "account-equity-hyperevm-move")
        reason (by-role node "account-equity-hyperevm-move-reason")]
    (is (= "true" (get-in move [1 :aria-disabled])))
    (is (nil? (get-in move [1 :on :click])))
    (is (= "account-equity-hyperevm-move-reason" (get-in move [1 :aria-describedby])))
    (is (= "account-equity-hyperevm-move-reason" (get-in reason [1 :id])))
    (is (not (contains? (classes reason) "hidden")) "visible text, on phones too")
    (is (= account-context/hyperevm-master-only-message (text reason)))))

(deftest the-model-changes-only-when-the-line-would-test
  (let [poll (fn [state* requested-at-ms native-wei gas-price-wei]
               (-> state*
                   (hyperevm-balances/apply-loading {:addresses [fixture/owner]
                                                     :requested-at-ms requested-at-ms})
                   (hyperevm-balances/apply-success fixture/owner requested-at-ms
                                                    {:native-wei native-wei
                                                     :token-units (:token-units fixture/evm-entry)
                                                     :gas-price-wei gas-price-wei}
                                                    (:token-indexes fixture/evm-entry)
                                                    (inc requested-at-ms))))
        first-read (poll state 100 (:native-wei fixture/evm-entry) fixture/gas-price-wei)
        refreshing (hyperevm-balances/apply-loading first-read {:addresses [fixture/owner]
                                                                :requested-at-ms 200})
        same-again (poll first-read 200 (:native-wei fixture/evm-entry) "150000000")
        spent (poll same-again 300 "12400000000000000000" "150000000")
        model hyperevm-line/hyperevm-line-model]
    (is (= (model first-read) (model refreshing)) "a read in flight keeps the last values")
    (is (= (model first-read) (model same-again)) "timestamps and gas price do not show")
    (is (not= (model same-again) (model spent)))
    (is (= (model first-read)
           (model (assoc-in first-read [:funding-ui :modal :focus-return-token] 9)))
        "another opener's focus return does not touch the line")
    (is (not= (model first-read)
              (model (update-in first-read [:funding-ui :modal] assoc
                                :focus-return-data-role hyperevm-line/move-data-role
                                :focus-return-token 9)))
        "focus comes back to the Move link it opened from")))

(deftest the-panels-render-the-line-beside-spot-and-perps-test
  (let [model (hyperevm-line/hyperevm-line-model state)]
    (testing "classic"
      (let [panel (account-equity-view/account-equity-view state {:hyperevm-line model
                                                                  :show-funding-actions? false})]
        (is (= "$1,808.50" (text (by-role panel "account-equity-hyperevm-value"))))))
    (testing "unified"
      (let [unified (assoc state :account {:mode :unified})
            panel (account-equity-view/account-equity-view unified {:hyperevm-line model
                                                                    :show-funding-actions? false})]
        (is (some? (by-role panel "account-equity-hyperevm-line")))))
    (testing "without a model the slot is there and hidden"
      (let [panel (account-equity-view/account-equity-view state {:show-funding-actions? false})]
        (is (nil? (by-role panel "account-equity-hyperevm-line")))
        (is (some? (line-root panel)))))))

(deftest classic-grouped-total-includes-hyperevm-without-changing-trading-figures-test
  (let [without (assoc state :hyperevm (hyperevm-balances/default-state))
        state-with-confirmed-empty-vaults
        (assoc state
               :vaults {:user-equities []
                        :user-equities-for-address
                        (account-context/effective-account-address state)
                        :loading {:user-equities? false}
                        :errors {:user-equities nil}})
        metrics-of (fn [state*]
                     (reset-caches!)
                     (account-equity-view/account-equity-metrics state*))
        baseline (metrics-of (assoc without :vaults (:vaults state-with-confirmed-empty-vaults)))
        with-evm (metrics-of state-with-confirmed-empty-vaults)]
    (is (some? (:spot-equity baseline)))
    ;; Account Value, Balance and cross risk remain the HyperCore trading
    ;; figures. HyperEVM has value, but cannot margin a position.
    (is (= (select-keys baseline [:account-value-display
                                  :base-balance
                                  :maintenance-margin
                                  :cross-margin-ratio
                                  :cross-account-leverage])
           (select-keys with-evm [:account-value-display
                                  :base-balance
                                  :maintenance-margin
                                  :cross-margin-ratio
                                  :cross-account-leverage])))
    (is (< (js/Math.abs (- (:hyperevm-equity with-evm) 1808.5)) 1e-6))
    (is (= (+ (:account-value-display with-evm)
              (:vault-equity with-evm)
              (:hyperevm-equity with-evm))
           (:total-account-value-display with-evm)))))

(deftest dust-below-a-cent-keeps-the-line-hidden-test
  (let [dust (fixture/with-evm-entry state (assoc fixture/evm-entry
                                                  ;; 0.00000001 HYPE left after gas.
                                                  :native-wei "10000000000"
                                                  :token-units {}))
        cent (fixture/with-evm-entry state (assoc fixture/evm-entry
                                                  ;; 0.0002 HYPE at 44.68: $0.0089.
                                                  :native-wei "200000000000000"
                                                  :token-units {}))
        dust-with-confirmed-vaults
        (assoc dust :vaults {:user-equities []
                             :user-equities-for-address fixture/owner
                             :loading {:user-equities? false}
                             :errors {:user-equities nil}})
        dust-metrics (account-equity-view/account-equity-metrics dust-with-confirmed-vaults)]
    (is (pos? (:usd (hyperevm-funds/hyperevm-funds dust))) "the dust is priced above zero")
    (is (false? (:visible? (hyperevm-line/hyperevm-line-model dust))))
    (is (contains? (classes (rendered dust)) "hidden"))
    (is (not (str/includes? (text (rendered dust)) "$0.00")))
    ;; Hiding a value that rounds below one cent is a line-layout decision;
    ;; it must not silently remove owned USD from the classic grouped total.
    (is (pos? (:hyperevm-equity dust-metrics)))
    (is (< (:hyperevm-equity dust-metrics) 0.01))
    (is (= (+ (:account-value-display dust-metrics)
              (:vault-equity dust-metrics)
              (:hyperevm-equity dust-metrics))
           (:total-account-value-display dust-metrics)))
    (is (= "$0.01" (text (by-role (rendered cent) "account-equity-hyperevm-value")))
        "from the first amount that rounds to a cent, the line shows")))

(deftest holdings-with-no-price-show-the-line-unpriced-test
  (let [purr-only (-> state
                      (update-in [:asset-selector :market-by-key] dissoc "spot:PURR")
                      (fixture/with-evm-entry (assoc fixture/evm-entry
                                                     :native-wei "0"
                                                     :token-units {fixture/purr-index "50000000000000000000"})))
        model (hyperevm-line/hyperevm-line-model purr-only)
        node (rendered purr-only)]
    (is (zero? (:usd model)))
    (is (true? (:visible? model)) "the funds exist even though their value is unknown")
    (is (= "Unpriced" (text (by-role node "account-equity-hyperevm-value"))))
    (is (= [[:actions/open-funding-transfer-modal
             :event.currentTarget/bounds
             "account-equity-hyperevm-move"
             {:from :hyperevm :to :spot}]]
           (get-in (by-role node "account-equity-hyperevm-move") [1 :on :click]))
        "Move stays reachable")))

(deftest a-read-with-a-chunk-never-answered-hides-the-line-test
  (let [lost (-> state
                 (assoc-in [:hyperevm :balances :by-address] {})
                 (hyperevm-balances/apply-loading {:addresses [fixture/owner] :requested-at-ms 100})
                 (hyperevm-balances/apply-success fixture/owner 100
                                                  {:native-wei (:native-wei fixture/evm-entry)
                                                   :token-units {}
                                                   :unread-token-indexes [fixture/usdc-index fixture/purr-index]
                                                   :gas-price-wei fixture/gas-price-wei}
                                                  (:token-indexes fixture/evm-entry)
                                                  101))]
    (is (= :partial (:status (hyperevm-funds/hyperevm-funds lost))))
    (is (false? (:visible? (hyperevm-line/hyperevm-line-model lost)))
        "a partial figure is not shown as what the account holds")))
