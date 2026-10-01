(ns hyperopen.views.portfolio.funds-locations-reconcile-test
  "The \"Where your funds are\" strip reconciles with the page around it:
   its total names the HyperCore part by the summary card's own label and
   says what sits in vaults and Earn, it never shows a figure while the
   HyperCore snapshot or a HyperEVM chunk is unknown, holdings with no price
   read as unpriced rather than $0.00, and a disabled connector's tooltip
   is anchored to its button, hoverable and dismissible."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [hyperopen.funding.test-support.hyperevm-transfer :as fixture]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.projections.hyperevm-funds :as hyperevm-funds]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.portfolio.funds-locations :as funds-locations]
            [hyperopen.views.portfolio.test-support :as portfolio-support]
            [hyperopen.views.portfolio.vm :as portfolio-vm]))

(defn- reset-caches!
  []
  (hyperevm-funds/reset-hyperevm-funds-cache!)
  (derived-cache/reset-derived-cache!)
  (portfolio-vm/reset-portfolio-vm-cache!)
  (reset! portfolio-vm/last-metrics-request nil))

(use-fixtures :each
  (fn [f]
    (reset-caches!)
    (f)
    (reset-caches!)))

(def ^:private state
  (assoc-in (fixture/state) [:router :path] "/portfolio"))

(defn- by-role
  [node role]
  (hiccup/find-by-data-role node role))

(defn- text
  [node]
  (str/join "" (hiccup/collect-strings node)))

(defn- classes
  [node]
  (hiccup/node-class-set node))

(defn- dollars
  "\"$1,234.56\" -> 1234.56."
  [s]
  (js/parseFloat (str/replace (str s) #"[$,]" "")))

(defn- strip
  [state* summary]
  (funds-locations/funds-locations-strip
   (funds-locations/funds-locations-model state* summary)))

(defn- page
  [state*]
  (portfolio-view/portfolio-view
   (merge portfolio-support/sample-state
          (select-keys state* [:wallet :spot :webdata2 :asset-selector :hyperevm :router :account]))))

(defn- summary-rows
  "`{label value}` of the Portfolio summary card's rows."
  [page-node]
  (let [card (by-role page-node "portfolio-account-summary-card")]
    (into {}
          (map (fn [row]
                 (let [[label value] (hiccup/node-children row)]
                   [(text label) (text value)])))
          (hiccup/find-all-nodes card #(contains? (classes %) "summary-kv-row")))))

;; --- labels and reconciliation --------------------------------------------------

(deftest a-unified-account-with-vault-equity-never-shows-two-values-under-one-label-test
  (let [unified (-> state
                    (assoc :account {:mode :unified})
                    (assoc-in [:webdata2 :totalVaultEquity] "500"))
        page-node (page unified)
        rows (summary-rows page-node)
        strip-node (by-role page-node "portfolio-funds-strip")
        strip-text (text strip-node)
        total-status (text (by-role strip-node "portfolio-funds-total-status"))]
    (is (= "$500.00" (get rows "Vault Equity")) "the account holds vault equity")
    (is (some? (get rows "Trading Equity")) "the unified summary's own Trading Equity row")
    (is (not (str/includes? (str/lower-case strip-text) "trading equity"))
        "the strip never reuses the summary's Trading Equity label for another figure")
    (is (= (str "Total Equity " (get rows "Total Equity")) total-status)
        "the strip's HyperCore figure is the summary's Total Equity, under the same label")
    (is (= (get rows "Trading Equity")
           (text (by-role strip-node "portfolio-funds-card-trading-value")))
        "the Trading account card is the summary's Trading Equity")
    (is (= "Includes $500.00 in vaults"
           (text (by-role strip-node "portfolio-funds-total-includes")))
        "a unified account's Total Equity leaves Earn out, so only vaults are named")
    (is (< (js/Math.abs (- (dollars (text (by-role strip-node "portfolio-funds-total-value")))
                           (+ (dollars (text (by-role strip-node "portfolio-funds-card-trading-value")))
                              (dollars (text (by-role strip-node "portfolio-funds-card-evm-value")))
                              500)))
           0.011)
        "the cards plus the vault note add up to the total")))

(deftest a-classic-strip-reconciles-its-cards-with-the-total-test
  (let [summary {:total-equity (+ 8420.18 22380.13 500 250)
                 :perps-account-equity 8420.18
                 :spot-account-equity 22380.13
                 :vault-equity 500
                 :earn-balance 250}
        node (strip state summary)
        value #(dollars (text (by-role node %)))]
    (is (= "Includes $750.00 in vaults and Earn"
           (text (by-role node "portfolio-funds-total-includes"))))
    (is (not (contains? (classes (by-role node "portfolio-funds-total-includes")) "hidden")))
    (is (= "Total Equity $31,550.31" (text (by-role node "portfolio-funds-total-status"))))
    (is (< (js/Math.abs (- (value "portfolio-funds-total-value")
                           (+ (value "portfolio-funds-card-perps-value")
                              (value "portfolio-funds-card-spot-value")
                              (value "portfolio-funds-card-evm-value")
                              750)))
           0.011))
    (testing "Earn alone, and sub-cent amounts, are named or dropped as they are"
      (is (= "Includes $250.00 in Earn"
             (text (by-role (strip state (assoc summary :vault-equity 0)) "portfolio-funds-total-includes"))))
      (is (contains? (classes (by-role (strip state (assoc summary :vault-equity 0.001 :earn-balance 0))
                                       "portfolio-funds-total-includes"))
                     "hidden")))))

;; --- HyperCore readiness --------------------------------------------------------

(def ^:private zero-summary
  "What the portfolio view-model reports before the HyperCore snapshot loads."
  {:total-equity 0 :perps-account-equity 0 :spot-account-equity 0 :vault-equity 0 :earn-balance 0})

(def ^:private core-unloaded
  "A ready HyperEVM read, but the account lifecycle has just cleared the
   HyperCore snapshot (an identity change)."
  (-> state
      (assoc :webdata2 nil)
      (assoc-in [:spot :clearinghouse-state] nil)))

(deftest an-unloaded-hypercore-snapshot-is-never-a-zero-or-a-total-test
  (let [m (funds-locations/funds-locations-model core-unloaded zero-summary)
        node (strip core-unloaded zero-summary)
        strip-text (text node)]
    (is (= :ready (:status (hyperevm-funds/hyperevm-funds core-unloaded))) "HyperEVM landed first")
    (is (= "—" (text (by-role node "portfolio-funds-total-value")))
        "never the HyperEVM-only figure as the total")
    (is (= "HyperCore balances loading" (text (by-role node "portfolio-funds-total-status"))))
    (is (= :muted (get-in m [:total :tone])))
    (is (= "Loading…" (text (by-role node "portfolio-funds-card-perps-value"))))
    (is (= "Loading…" (text (by-role node "portfolio-funds-card-spot-value"))))
    (is (= "Checking HyperCore balances…" (text (by-role node "portfolio-funds-card-perps-detail"))))
    (is (not (str/includes? strip-text "$0.00")) "no fake zero")
    (is (not (str/includes? strip-text "no open positions")))
    (is (not (str/includes? strip-text "No tokens")))
    (is (= "$1,808.50" (text (by-role node "portfolio-funds-card-evm-value")))
        "the HyperEVM card still shows what it knows")
    (testing "a unified account's Trading account card"
      (let [unified (assoc core-unloaded :account {:mode :unified})
            node* (strip unified zero-summary)]
        (is (= "Loading…" (text (by-role node* "portfolio-funds-card-trading-value"))))))
    (testing "a Perps state without Spot balances is still loading"
      (let [perps-only (assoc-in state [:spot :clearinghouse-state] nil)]
        (is (= "—" (text (by-role (strip perps-only zero-summary) "portfolio-funds-total-value"))))))))

(deftest both-ledgers-loading-and-a-failed-spot-read-say-so-test
  (let [both (hyperevm-balances/apply-loading (assoc-in core-unloaded [:hyperevm :balances :by-address] {})
                                              {:addresses [fixture/owner] :requested-at-ms 5})
        spot-failed (assoc-in core-unloaded [:spot :error] "timeout")
        failed-node (strip spot-failed zero-summary)]
    (is (= "Balances loading" (text (by-role (strip both zero-summary) "portfolio-funds-total-status"))))
    (is (= "HyperCore balances unavailable" (text (by-role failed-node "portfolio-funds-total-status"))))
    (is (contains? (classes (by-role failed-node "portfolio-funds-total-status")) "text-ho-warn"))
    (is (= "—" (text (by-role failed-node "portfolio-funds-card-spot-value"))))))

;; --- partial HyperEVM reads -------------------------------------------------------

(defn- read-evm
  [state* requested-at-ms result]
  (-> state*
      (hyperevm-balances/apply-loading {:addresses [fixture/owner] :requested-at-ms requested-at-ms})
      (hyperevm-balances/apply-success fixture/owner requested-at-ms
                                       (merge {:native-wei (:native-wei fixture/evm-entry)
                                               :token-units (:token-units fixture/evm-entry)
                                               :gas-price-wei fixture/gas-price-wei}
                                              result)
                                       (:token-indexes fixture/evm-entry)
                                       (inc requested-at-ms))))

(def ^:private summary
  {:total-equity 30800.31 :perps-account-equity 8420.18 :spot-account-equity 22380.13})

(deftest a-hyperevm-chunk-never-read-is-not-a-total-test
  (let [unread (assoc-in state [:hyperevm :balances :by-address] {})
        lost (read-evm unread 100 {:token-units {fixture/usdc-index "1240000000"}
                                   :unread-token-indexes [fixture/purr-index 197]})
        node (strip lost summary)]
    (is (= :partial (:status (hyperevm-funds/hyperevm-funds lost))))
    (is (= "—" (text (by-role node "portfolio-funds-total-value"))))
    (is (= "Some HyperEVM balances unavailable" (text (by-role node "portfolio-funds-total-status"))))
    (is (contains? (classes (by-role node "portfolio-funds-total-status")) "text-ho-warn"))
    (is (= "—" (text (by-role node "portfolio-funds-card-evm-value"))))
    (testing "the next read answers the chunk"
      (is (= "$32,608.81" (text (by-role (strip (read-evm lost 200 {}) summary)
                                         "portfolio-funds-total-value")))))
    (testing "a call that fails on every read (JOFF) leaves the total known"
      (let [joff (read-evm unread 100 {:failed-token-indexes [fixture/joff-index]})]
        (is (= "$32,608.81" (text (by-role (strip joff summary) "portfolio-funds-total-value"))))))))

;; --- unpriced holdings ------------------------------------------------------------

(deftest holdings-with-no-price-read-unpriced-not-zero-test
  (let [purr-only (-> state
                      (update-in [:asset-selector :market-by-key] dissoc "spot:PURR")
                      (fixture/with-evm-entry (assoc fixture/evm-entry
                                                     :native-wei "0"
                                                     :token-units {fixture/purr-index "50000000000000000000"})))
        node (strip purr-only summary)]
    (is (= 1 (:unpriced-count (hyperevm-funds/hyperevm-funds purr-only))))
    (is (= "Unpriced" (text (by-role node "portfolio-funds-card-evm-value"))))
    (is (str/starts-with? (text (by-role node "portfolio-funds-card-evm-detail")) "1 token, 1 unpriced"))))

;; --- the disabled connector's tooltip ------------------------------------------

(deftest a-disabled-connector-tooltip-sits-under-its-button-and-can-be-dismissed-test
  (let [sub (-> state
                (assoc :account-context {:subaccounts {:rows [{:sub-account-user fixture/subaccount
                                                               :master fixture/owner}]
                                                       :selected-address fixture/subaccount}})
                (assoc-in [:hyperevm :balances :by-address fixture/subaccount] fixture/evm-entry))
        node (strip sub summary)
        slot (by-role node "portfolio-funds-connector-spot-evm-slot")
        group (hiccup/find-first-node slot #(contains? (classes %) "group/connector"))
        tooltip (by-role node "portfolio-funds-connector-spot-evm-tooltip")
        tooltip-classes (classes tooltip)]
    (is (not (contains? (classes slot) "group/connector"))
        "the grid slot stretches to the card row, so it is not the anchor")
    (is (contains? (classes group) "relative"))
    (is (some? (by-role group "portfolio-funds-connector-spot-evm")) "the group wraps the button")
    (is (some? (by-role group "portfolio-funds-connector-spot-evm-tooltip")) "and its tooltip")
    (is (every? fn? (vals (select-keys (get-in group [1 :on]) [:keydown :mouseenter :mouseleave
                                                              :focusin :focusout])))
        "Escape handling on the group")
    (is (= 5 (count (get-in group [1 :on]))))
    (is (contains? tooltip-classes "top-full"))
    (is (contains? tooltip-classes "pt-1.5") "the gap to the button is part of the tooltip")
    (is (not (contains? tooltip-classes "pointer-events-none")) "the pointer can move onto it")
    (is (contains? tooltip-classes "invisible") "a closed tooltip catches no pointer")
    (is (contains? tooltip-classes "group-hover/connector:visible"))
    (is (contains? tooltip-classes "group-data-[tooltip-dismissed=true]/connector:!invisible"))
    (testing "an enabled connector has no Escape handling to do"
      (let [enabled-group (hiccup/find-first-node
                           (by-role node "portfolio-funds-connector-perps-spot-slot")
                           #(contains? (classes %) "group/connector"))]
        (is (nil? (get-in enabled-group [1 :on])))))))
