(ns hyperopen.views.portfolio.funds-locations-test
  "The Portfolio \"Where your funds are\" strip: total value adds HyperEVM to
   Total Equity and only there, unknown HyperEVM data never reads as a
   total, each connector opens Transfer preset to its pair, and a connector
   that cannot move says why in visible text."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.funding.test-support.hyperevm-transfer :as fixture]
            [hyperopen.hyperevm.domain.balances :as hyperevm-balances]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.projections.hyperevm-funds :as hyperevm-funds]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.portfolio.test-support :as portfolio-support]
            [hyperopen.views.portfolio.funds-locations :as funds-locations]
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

(def ^:private summary
  {:total-equity 30800.31
   :perps-account-equity 8420.18
   :spot-account-equity 22380.13})

(def ^:private state
  (assoc-in (fixture/state) [:router :path] "/portfolio"))

(def ^:private spectated "0x5555555555555555555555555555555555555555")

(def ^:private evm-usd
  "12.5 HYPE at 44.68, 1,240 USDC and 50 PURR at 0.2."
  (+ (* 12.5 44.68) 1240 (* 50 0.2)))

(defn- close?
  [a b]
  (and (number? a) (< (js/Math.abs (- a b)) 1e-6)))

(defn- model
  [state*]
  (funds-locations/funds-locations-model state* summary))

(defn- strip
  [state*]
  (funds-locations/funds-locations-strip (model state*)))

(defn- by-role
  [node role]
  (hiccup/find-by-data-role node role))

(defn- text
  [node]
  (str/join "" (hiccup/collect-strings node)))

(defn- classes
  [node]
  (hiccup/node-class-set node))

(defn- connector
  [model* id]
  (some #(when (= id (:id %)) %) (:connectors model*)))

(defn- with-subaccount
  [state* entry]
  (-> state*
      (assoc :account-context {:subaccounts {:rows [{:sub-account-user fixture/subaccount
                                                     :master fixture/owner}]
                                             :selected-address fixture/subaccount}})
      (assoc-in [:hyperevm :balances :by-address fixture/subaccount] entry)))

(defn- spectating
  [state* entry]
  (-> state*
      (assoc :account-context {:spectate-mode {:active? true :address spectated}})
      (assoc-in [:hyperevm :balances :by-address spectated] entry)))

(deftest the-shown-account-hyperevm-holdings-are-summed-from-the-balances-rows-test
  (let [funds (hyperevm-funds/hyperevm-funds state)]
    (is (= :ready (:status funds)))
    (is (close? evm-usd (:usd funds)))
    (is (= 3 (:token-count funds)))
    (is (= ["USDC" "HYPE" "PURR"] (:symbols funds)) "largest USD value first")
    (is (= :ok (:gas-status funds)))
    (is (= "12.5" (:native-hype-text funds)))
    (is (identical? funds (hyperevm-funds/hyperevm-funds state))
        "memoized: the same inputs give the same object")))

(deftest total-value-adds-hyperevm-to-total-equity-test
  (let [m (model state)
        node (strip state)]
    (is (:visible? m))
    (is (= (str "$" "32,608.81") (get-in m [:total :value-text])))
    (is (= "$32,608.81" (text (by-role node "portfolio-funds-total-value"))))
    (is (= "Total Equity $30,800.31" (text (by-role node "portfolio-funds-total-status")))
        "the HyperCore part, named as the summary card names it")
    (is (contains? (classes (by-role node "portfolio-funds-total-includes")) "hidden")
        "nothing in vaults or Earn, no note")
    (is (str/includes? (text (by-role node "portfolio-funds-total")) "HyperEVM funds are not margin"))
    (is (= "$8,420.18" (text (by-role node "portfolio-funds-card-perps-value"))))
    (is (= "$22,380.13" (text (by-role node "portfolio-funds-card-spot-value"))))
    (is (= "$1,808.50" (text (by-role node "portfolio-funds-card-evm-value"))))))

(deftest the-cards-name-what-each-place-holds-test
  (let [node (strip state)]
    (is (= "Section" (str/capitalize (name (first node)))) "a section element")
    (is (= "Where your funds are" (get-in node [1 :aria-label])))
    (is (= "USDC collateral · no open positions"
           (text (by-role node "portfolio-funds-card-perps-detail"))))
    (is (str/starts-with? (text (by-role node "portfolio-funds-card-spot-detail")) "6 tokens · "))
    (is (str/ends-with? (text (by-role node "portfolio-funds-card-spot-detail")) " +3"))
    (is (str/includes? (text (by-role node "portfolio-funds-card-evm-detail")) "3 tokens"))
    (is (= "· gas: 12.50 HYPE" (text (by-role node "portfolio-funds-evm-gas-status"))))
    (is (= "ok" (get-in (by-role node "portfolio-funds-evm-gas-status") [1 :data-gas-tone])))
    (is (some? (by-role node "location-chip-perps")))
    (is (some? (by-role node "location-chip-spot")))
    (is (some? (by-role node "location-chip-hyperevm")))))

(deftest an-unpriced-hyperevm-token-is-counted-and-named-test
  (let [no-purr-price (update-in state [:asset-selector :market-by-key] dissoc "spot:PURR")
        node (strip no-purr-price)]
    (is (= 1 (:unpriced-count (hyperevm-funds/hyperevm-funds no-purr-price))))
    (is (str/starts-with? (text (by-role node "portfolio-funds-card-evm-detail")) "3 tokens, 1 unpriced"))
    (is (= "$1,798.50" (text (by-role node "portfolio-funds-card-evm-value")))
        "the value is what the priced tokens are worth")))

(deftest low-gas-is-said-in-words-test
  (let [low (fixture/with-evm-entry state (assoc fixture/evm-entry :native-wei "10000000000000"))
        node (strip low)
        gas (by-role node "portfolio-funds-evm-gas-status")]
    (is (= "· Low gas: 0.00001 HYPE" (text gas)))
    (is (contains? (classes gas) "text-ho-warn"))))

(deftest unknown-hyperevm-data-never-reads-as-a-total-test
  (testing "before the first read lands"
    (let [loading (hyperevm-balances/apply-loading (assoc-in state [:hyperevm :balances :by-address] {})
                                                   {:addresses [fixture/owner] :requested-at-ms 5})
          m (model loading)
          node (strip loading)]
      (is (= "—" (get-in m [:total :value-text])))
      (is (= "HyperEVM balances loading" (text (by-role node "portfolio-funds-total-status"))))
      (is (= :muted (get-in m [:total :tone])))
      (is (not (contains? (classes (by-role node "portfolio-funds-total-status")) "text-ho-warn"))
          "loading on every page load is not a warning")
      (is (= "Loading…" (text (by-role node "portfolio-funds-card-evm-value"))))
      (is (not (str/includes? (text node) "$0.00")) "no fake zero anywhere")
      (is (not (str/includes? (text (by-role node "portfolio-funds-total")) "$"))
          "no HyperCore-only figure posing as the total")))
  (testing "when the first read failed"
    (let [failed (fixture/with-evm-entry state {:status :error :error "rate limited" :error-kind :rate-limit})
          node (strip failed)]
      (is (= "—" (text (by-role node "portfolio-funds-total-value"))))
      (is (= "HyperEVM balances unavailable" (text (by-role node "portfolio-funds-total-status"))))
      (is (= :warn (get-in (model failed) [:total :tone])))
      (is (contains? (classes (by-role node "portfolio-funds-total-status")) "text-ho-warn"))
      (is (= "—" (text (by-role node "portfolio-funds-card-evm-value"))))))
  (testing "a retry of the failed first read keeps saying unavailable"
    (let [failed (fixture/with-evm-entry state {:status :error :error "rate limited" :error-kind :rate-limit})
          retrying (hyperevm-balances/apply-loading failed {:addresses [fixture/owner] :requested-at-ms 7})
          node (strip retrying)]
      (is (= :loading (:status (hyperevm-balances/entry retrying fixture/owner))) "the retry is in flight")
      (is (= "HyperEVM balances unavailable" (text (by-role node "portfolio-funds-total-status"))))
      (is (= "—" (text (by-role node "portfolio-funds-card-evm-value"))))
      (is (= (model failed) (model retrying)) "the strip does not flip on every retry")))
  (testing "a failed refresh keeps the last read (stale-while-revalidate)"
    (let [refreshing (-> state
                         (hyperevm-balances/apply-loading {:addresses [fixture/owner] :requested-at-ms 9})
                         (hyperevm-balances/apply-error fixture/owner 9 {:message "rate limited" :kind :rate-limit}))]
      (is (= "$32,608.81" (get-in (model refreshing) [:total :value-text]))))))

(deftest connectors-open-transfer-preset-to-their-pair-test
  (let [m (model state)
        node (strip state)
        perps-spot (by-role node "portfolio-funds-connector-perps-spot")
        spot-evm (by-role node "portfolio-funds-connector-spot-evm")]
    (is (= [:actions/open-funding-transfer-modal :event.currentTarget/bounds
            "portfolio-funds-connector-perps-spot" {:from :perps :to :spot}]
           (:action (connector m :perps-spot))))
    (is (= [[:actions/open-funding-transfer-modal :event.currentTarget/bounds
             "portfolio-funds-connector-perps-spot" {:from :perps :to :spot}]]
           (get-in perps-spot [1 :on :click])))
    (is (= [[:actions/open-funding-transfer-modal :event.currentTarget/bounds
             "portfolio-funds-connector-spot-evm" {:from :spot :to :hyperevm}]]
           (get-in spot-evm [1 :on :click])))
    (is (= "Transfer between Perps and Spot" (get-in perps-spot [1 :aria-label])))
    (is (= "Transfer between Spot and HyperEVM" (get-in spot-evm [1 :aria-label])))
    (is (nil? (get-in spot-evm [1 :aria-disabled])))
    (is (contains? (classes (by-role node "portfolio-funds-reasons")) "hidden")
        "nothing disabled, no reason line")))

(deftest a-connector-that-opened-the-modal-gets-focus-back-test
  (let [opened (assoc-in state [:funding-ui :modal] {:focus-return-data-role funds-locations/spot-evm-data-role
                                                      :focus-return-token 3})
        node (strip opened)]
    (is (fn? (get-in (by-role node "portfolio-funds-connector-spot-evm") [1 :replicant/on-render])))
    (is (nil? (get-in (by-role node "portfolio-funds-connector-perps-spot") [1 :replicant/on-render])))))

(deftest a-subaccount-keeps-perps-spot-and-sees-why-hyperevm-is-off-test
  (let [sub (with-subaccount state fixture/evm-entry)
        node (strip sub)
        perps-spot (by-role node "portfolio-funds-connector-perps-spot")
        spot-evm (by-role node "portfolio-funds-connector-spot-evm")
        reason-id (get-in spot-evm [1 :aria-describedby])]
    (is (some? (get-in perps-spot [1 :on :click])) "Perps <-> Spot works for a subaccount")
    (is (= "true" (get-in spot-evm [1 :aria-disabled])))
    (is (nil? (get-in spot-evm [1 :on :click])))
    (is (some? reason-id))
    (is (= account-context/hyperevm-master-only-message (text (by-role node reason-id)))
        "the described-by target is the visible reason line")
    (is (not (contains? (classes (by-role node "portfolio-funds-reasons")) "hidden")))
    (is (not (contains? (classes (by-role node "portfolio-funds-reasons")) "lg:hidden"))
        "the line shows at every width: a tap on a touch screen shows no tooltip")
    (let [tooltip (by-role node "portfolio-funds-connector-spot-evm-tooltip")]
      (is (= account-context/hyperevm-master-only-message (text tooltip)))
      (is (contains? (classes tooltip) "lg:block"))
      (is (contains? (classes tooltip) "group-focus-within/connector:opacity-100")
          "keyboard focus shows it too"))))

(deftest spectate-mode-shows-the-spectated-funds-read-only-test
  (let [watched (spectating state (assoc fixture/evm-entry :native-wei "1000000000000000000" :token-units {}))
        m (model watched)
        node (strip watched)
        reasons (:reasons m)]
    (is (:visible? m))
    (is (= "$44.68" (text (by-role node "portfolio-funds-card-evm-value"))) "the spectated wallet's 1 HYPE")
    (is (every? :disabled? (:connectors m)))
    (is (= 1 (count reasons)) "both connectors share one reason line")
    (is (= account-context/spectate-mode-read-only-message (:text (first reasons))))
    (is (= #{(:id (first reasons))}
           (set (map #(get-in (by-role node %) [1 :aria-describedby])
                     ["portfolio-funds-connector-perps-spot" "portfolio-funds-connector-spot-evm"]))))))

(deftest a-unified-account-has-one-trading-card-and-one-connector-test
  (let [unified (assoc state :account {:mode :unified})
        m (model unified)
        node (strip unified)]
    (is (:unified? m))
    (is (= [:spot-evm] (mapv :id (:connectors m))))
    (is (nil? (by-role node "portfolio-funds-connector-perps-spot")))
    (is (nil? (by-role node "portfolio-funds-card-perps")))
    (is (nil? (by-role node "portfolio-funds-card-spot")))
    (is (= "$22,380.13" (text (by-role node "portfolio-funds-card-trading-value"))))
    (is (str/includes? (text (by-role node "portfolio-funds-card-trading")) "Trading account"))
    (is (some? (by-role node "portfolio-funds-connector-spot-evm")))))

(deftest no-account-hides-the-strip-test
  (let [nobody (dissoc state :wallet)
        node (strip nobody)]
    (is (false? (:visible? (model nobody))))
    (is (contains? (classes node) "hidden"))
    (is (= "portfolio-funds-strip" (get-in node [1 :data-role])) "the slot is still there")
    (is (empty? (hiccup/node-children node)))))

(deftest a-poll-that-changes-nothing-shown-leaves-the-model-equal-test
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
        same-again (poll first-read 200 (:native-wei fixture/evm-entry) "120000000")
        spent (poll same-again 300 "12400000000000000000" "120000000")]
    (is (= (model first-read) (model same-again))
        "new timestamps and gas price over the same balances render the same strip")
    (is (not= (model same-again) (model spent)) "a balance change shows")))

(deftest the-portfolio-page-renders-the-strip-above-the-summary-test
  (let [page-state (merge portfolio-support/sample-state
                          (select-keys state [:wallet :spot :webdata2 :asset-selector :hyperevm :router]))
        page (portfolio-view/portfolio-view page-state)
        children (vec (hiccup/node-children page))
        position-of (fn [role]
                      (first (keep-indexed (fn [i child]
                                             (when (hiccup/find-by-data-role child role) i))
                                           children)))
        summary-total (:total-equity (:summary (portfolio-vm/portfolio-vm page-state)))
        strip-node (by-role page "portfolio-funds-strip")]
    (is (some? strip-node))
    (is (< (position-of "portfolio-funds-strip")
           (position-of "portfolio-account-summary-card")))
    (is (= (text (by-role strip-node "portfolio-funds-total-value"))
           (str "$" (.toLocaleString (+ summary-total evm-usd) "en-US"
                                     #js {:minimumFractionDigits 2 :maximumFractionDigits 2})))
        "total value is the summary's Total Equity plus HyperEVM")))
