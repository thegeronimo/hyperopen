(ns hyperopen.views.funding-modal.transfer-view-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.platform :as platform]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.funding-modal :as funding-modal]
            [hyperopen.views.funding-modal.transfer :as transfer]))

(def ^:private spot->evm {:transfer-from :spot :transfer-to :hyperevm})
(def ^:private evm->spot {:transfer-from :hyperevm :transfer-to :spot})

(defn- view-model
  [state]
  (with-redefs [platform/now-ms (constantly support/now-ms)]
    (funding-actions/funding-modal-view-model state)))

(defn- form
  [state]
  (transfer/render-content (:transfer (view-model state))))

(defn- by-role
  [node role]
  (hiccup/find-by-data-role node role))

(defn- attrs
  [node]
  (hiccup/node-attrs node))

(defn- hidden?
  [node]
  (contains? (hiccup/node-class-set node) "hidden"))

(defn- text
  [node]
  (str/join "" (hiccup/collect-strings node)))

(defn- no-gas
  [state]
  (support/with-evm-entry state (assoc support/evm-entry :native-wei "0")))

(def ^:private subaccount-selected
  {:selected-address support/subaccount
   :rows [{:sub-account-user support/subaccount :master support/owner}]})

(deftest core-to-evm-form-follows-the-design-test
  (let [node (form (support/state (assoc spot->evm :transfer-asset support/hype-index
                                         :amount-input "250")))]
    (testing "From and To are labelled segmented groups with pressed state"
      (let [from-group (by-role node "funding-transfer-from-group")
            to-group (by-role node "funding-transfer-to-group")
            spot (by-role from-group "funding-transfer-from-spot")
            evm (by-role to-group "funding-transfer-to-hyperevm")]
        (is (= "group" (:role (attrs from-group))))
        (is (= "funding-transfer-from-label" (:aria-labelledby (attrs from-group))))
        (is (some? (hiccup/find-first-node node #(= "funding-transfer-from-label" (:id (attrs %))))))
        (is (= ["Perps" "Spot" "HyperEVM"] (mapv text (hiccup/node-children from-group))))
        (is (= "true" (:aria-pressed (attrs spot))))
        (is (= "true" (:aria-pressed (attrs evm))))
        (is (= "false" (:aria-pressed (attrs (by-role from-group "funding-transfer-from-perps")))))
        (is (= {:click [[:actions/set-funding-transfer-location :from :perps]]}
               (:on (attrs (by-role from-group "funding-transfer-from-perps")))))))
    (testing "each side shows its place chip and balance"
      (let [from-balance (by-role node "funding-transfer-from-balance")
            to-balance (by-role node "funding-transfer-to-balance")]
        (is (some? (by-role from-balance "location-chip-spot")))
        (is (str/includes? (text from-balance) "412.08 HYPE"))
        (is (some? (by-role to-balance "location-chip-hyperevm")))
        (is (str/includes? (text to-balance) "12.50 HYPE"))))
    (testing "the swap button is labelled and swaps"
      (let [swap (by-role node "funding-transfer-swap")]
        (is (= "Swap direction" (:aria-label (attrs swap))))
        (is (= {:click [[:actions/swap-funding-transfer-locations]]} (:on (attrs swap))))))
    (testing "the asset list is a fieldset of native radios"
      (let [fieldset (by-role node "funding-transfer-asset-list")
            legend (hiccup/find-first-node fieldset #(= :legend (first %)))
            radios (hiccup/find-all-nodes fieldset #(= "radio" (:type (attrs %))))
            hype (first radios)]
        (is (= :fieldset (first fieldset)))
        (is (not (hidden? fieldset)))
        (is (= "Asset" (text legend)))
        (is (= #{"funding-transfer-asset"} (set (map (comp :name attrs) radios))))
        (is (true? (:checked (attrs hype))))
        (is (= "150" (:value (attrs hype))))
        (is (= {:change [[:actions/select-funding-transfer-asset 150]]} (:on (attrs hype))))
        (is (every? #(false? (:checked (attrs %))) (rest radios)))
        (is (contains? (hiccup/node-class-set
                        (hiccup/find-first-node fieldset #(contains? (hiccup/node-class-set %)
                                                                     "overflow-y-auto")))
                       "max-h-32")
            "a long list scrolls inside its own box")))
    (testing "the amount keeps its input id and data-role, with MAX, percents and USD"
      (let [input (by-role node "funding-transfer-amount-input")]
        (is (= "funding-transfer-amount-input-field" (:id (attrs input))))
        (is (= "250" (:value (attrs input))))
        (is (some? (hiccup/find-first-node node #(and (= :label (first %))
                                                      (= "funding-transfer-amount-input-field"
                                                         (:for (attrs %)))))))
        (is (= "HYPE" (text (by-role node "funding-transfer-amount-symbol"))))
        (is (= {:click [[:actions/set-funding-amount-to-max]]}
               (:on (attrs (by-role node "funding-transfer-max")))))
        (is (= {:click [[:actions/set-funding-transfer-amount-percent 50]]}
               (:on (attrs (by-role node "funding-transfer-percent-50")))))
        (is (= "≈ $11,170.00" (text (by-role node "funding-transfer-usd-estimate"))))
        (is (= "Available 412.08 HYPE" (text (by-role node "funding-transfer-available"))))))
    (testing "balances before and after, then the locked destination and route rows"
      (is (= "Spot after162.08 HYPE−250.00" (text (by-role node "funding-transfer-balance-from"))))
      (is (= "HyperEVM after262.50 HYPE+250.00" (text (by-role node "funding-transfer-balance-to"))))
      (let [summary (by-role node "funding-transfer-summary")]
        (is (= :dl (first summary)))
        (is (= ["Destination" "Network fee" "Arrives" "Wallet network"]
               (mapv text (hiccup/find-all-nodes summary #(= :dt (first %))))))
        (is (str/includes? (text summary) "Your wallet 0x123…5678"))
        (is (some? (hiccup/find-first-node summary #(= :svg (first %))))
            "the destination carries the lock icon")))
    (testing "the submit carries the amount and dispatches the submit"
      (let [submit (by-role node "funding-transfer-submit")]
        (is (= "Move 250 HYPE to HyperEVM" (text submit)))
        (is (false? (:disabled (attrs submit))))
        (is (= {:click [[:actions/submit-funding-transfer]]} (:on (attrs submit))))))
    (testing "the blocked region and its live line are always present, empty when nothing blocks"
      (let [region (by-role node "funding-transfer-blocked-region")
            live (by-role region "funding-transfer-blocked-status")]
        (is (= ["status" "polite"] ((juxt :role :aria-live) (attrs live))))
        (is (= "" (text live)))
        (is (nil? (by-role region "funding-transfer-blocked")))))))

(deftest legacy-route-renders-the-usdc-form-test
  (let [node (form (support/state {:to-perp? true :amount-input "12"}))]
    (is (hidden? (by-role node "funding-transfer-asset-list"))
        "Perps <-> Spot always moves USDC, so its asset list is hidden, not removed")
    (is (= "true" (:aria-pressed (attrs (by-role node "funding-transfer-from-spot")))))
    (is (= "true" (:aria-pressed (attrs (by-role node "funding-transfer-to-perps")))))
    (is (= "USDC" (text (by-role node "funding-transfer-amount-symbol"))))
    (is (= "Transfer" (text (by-role node "funding-transfer-submit"))))
    (is (hidden? (by-role node "funding-transfer-place-reasons"))
        "no place is unavailable for a connected master account")))

(deftest unavailable-places-explain-themselves-test
  (let [node (form (assoc-in (support/state {:to-perp? true})
                             [:account-context :subaccounts] subaccount-selected))
        reasons (by-role node "funding-transfer-place-reasons")
        reason-node (first (hiccup/node-children reasons))
        from-evm (by-role node "funding-transfer-from-hyperevm")
        to-evm (by-role node "funding-transfer-to-hyperevm")]
    (is (not (hidden? reasons)))
    (is (= ["HyperEVM transfers are available for the master account only."]
           (mapv text (hiccup/node-children reasons)))
        "the reason shows once, as visible text, even though both sides use it")
    (doseq [option [from-evm to-evm]]
      (is (= "true" (:aria-disabled (attrs option))) "stays focusable, marked disabled")
      (is (nil? (:disabled (attrs option))))
      (is (= (:id (attrs reason-node)) (:aria-describedby (attrs option))))
      (is (nil? (:on (attrs option))) "choosing it does nothing"))))

(deftest gas-blocked-card-offers-the-one-click-fix-test
  (let [base (no-gas (support/state (assoc evm->spot :transfer-asset support/purr-index)))
        node (form base)
        card (by-role node "funding-transfer-blocked")
        fix (by-role card "funding-transfer-gas-fix")]
    (is (= "no-evm-gas" (:data-blocked-code (attrs card))))
    (is (str/includes? (text card) "You need HYPE on HyperEVM to pay gas"))
    (is (str/includes? (text card) "You have 0 HYPE there."))
    (is (= "Send 0.05 HYPE from Spot to HyperEVM" (text fix)))
    (is (= {:click [[:actions/submit-funding-transfer-gas-topup]]} (:on (attrs fix))))
    (is (= "From your 412.08 HYPE on Spot · no network switch · a few seconds"
           (text (by-role card "funding-transfer-fix-status"))))
    (is (= "Add gas to continue" (text (by-role node "funding-transfer-submit"))))
    (is (true? (:disabled (attrs (by-role node "funding-transfer-submit")))))
    (is (hidden? (by-role node "funding-transfer-details"))
        "balances and summary give way to the card that says what to do")
    (testing "submitting and sent disable the fix (focusably) and say why"
      (doseq [[status message] [[:submitting "Sending 0.05 HYPE…"]
                                [:sent "Sent 0.05 HYPE. Waiting for it to arrive…"]]]
        (let [card (by-role (form (assoc-in base [:funding-ui :modal :transfer-gas-topup] {:status status}))
                            "funding-transfer-blocked")
              fix (by-role card "funding-transfer-gas-fix")]
          (is (= "true" (:aria-disabled (attrs fix))) (str status))
          (is (nil? (:disabled (attrs fix)))
              "never natively disabled: a pressed fix keeps keyboard focus")
          (is (= "true" (:aria-busy (attrs fix))) (str status))
          (is (nil? (:on (attrs fix))) (str status))
          (is (= (name status) (:data-fix-status (attrs fix))))
          (is (= message (text (by-role card "funding-transfer-fix-status")))))))
    (testing "a failed top-up re-enables the fix and shows the error"
      (let [card (by-role (form (assoc-in base [:funding-ui :modal :transfer-gas-topup]
                                          {:status :failed :error "Exchange rejected."}))
                          "funding-transfer-blocked")]
        (is (some? (:on (attrs (by-role card "funding-transfer-gas-fix")))))
        (is (= "Exchange rejected." (text (by-role card "funding-transfer-fix-status"))))))
    (testing "too little Spot HYPE disables the fix with a visible reason"
      (let [card (by-role (form (assoc-in base [:spot :clearinghouse-state :balances]
                                          [{:coin "HYPE" :token 150 :total "0.02" :hold "0"}]))
                          "funding-transfer-blocked")
            fix (by-role card "funding-transfer-gas-fix")
            reason (by-role card "funding-transfer-fix-reason")]
        (is (= "true" (:aria-disabled (attrs fix))))
        (is (nil? (:on (attrs fix))))
        (is (= "You need at least 0.05 HYPE on Spot." (text reason)))
        (is (not (hidden? reason)))
        (is (= (:id (attrs reason)) (:aria-describedby (attrs fix))))))))

(deftest unknown-hyperevm-data-never-offers-the-gas-fix-test
  (let [node (form (support/with-evm-entry
                     (support/state (assoc evm->spot :transfer-asset support/purr-index))
                     {:status :loading :requested-at-ms 1}))
        card (by-role node "funding-transfer-blocked")]
    (is (str/includes? (text card) "Checking HyperEVM balances…"))
    (is (nil? (by-role card "funding-transfer-gas-fix")))))

(deftest verification-notice-links-the-contract-test
  (let [node (form (support/state (assoc spot->evm :transfer-asset support/six-index)))
        notice (by-role node "funding-transfer-verification-notice")
        link (by-role notice "funding-transfer-token-explorer")]
    (is (not (hidden? notice)))
    (is (str/includes? (text notice) "SIX is linked to contract 0x41d…151f on HyperEVM."))
    (is (= "https://hyperevmscan.io/token/0x41de34fc45a770ebcd50200be93f080b4b05151f"
           (:href (attrs link))))
    (is (= "noreferrer noopener" (:rel (attrs link)))))
  (is (hidden? (by-role (form (support/state (assoc spot->evm :transfer-asset support/hype-index)))
                        "funding-transfer-verification-notice"))
      "verified tokens carry no notice"))

(deftest empty-source-says-so-test
  (let [node (form (support/with-evm-entry (support/state evm->spot)
                     (assoc support/evm-entry :native-wei "0" :token-units {})))
        empty-copy (by-role node "funding-transfer-asset-empty")]
    (is (not (hidden? empty-copy)))
    (is (= "No linked tokens on HyperEVM yet." (text empty-copy)))))

(deftest modal-shell-caps-its-height-and-restores-focus-to-every-opener-test
  (let [state (assoc (support/state (assoc spot->evm :transfer-asset support/hype-index))
                     :funding-ui {:modal (support/modal (assoc spot->evm
                                                               :transfer-asset support/hype-index
                                                               :anchor {:left 900 :right 1000 :top 760
                                                                        :bottom 790
                                                                        :viewport-width 1280
                                                                        :viewport-height 800}))})
        node (with-redefs [platform/now-ms (constantly support/now-ms)]
               (funding-modal/funding-modal-view state))
        panel (by-role node "funding-modal")]
    (is (contains? (hiccup/node-class-set panel) "overflow-y-auto"))
    (is (contains? (hiccup/node-class-set panel) "max-h-[calc(100vh-24px)]"))
    (is (contains? (hiccup/node-class-set panel) "scroll-pb-28")
        "a control reached with Tab scrolls clear of the sticky footer")
    (is (= "88px" (get-in (attrs panel) [:style :top]))
        "the Transfer popover is placed as a 700px panel, so it sits higher")
    (is (= "calc(100vh - 88px - 12px)" (get-in (attrs panel) [:style :max-height]))
        "an anchored popover never runs past the viewport")
    (is (contains? (hiccup/node-class-set (by-role node "funding-modal-layer")) "z-[262]")
        "the modal layer sits above the app's fixed footer and the trade panes")
    (is (some? (by-role panel "funding-transfer-form")))))
