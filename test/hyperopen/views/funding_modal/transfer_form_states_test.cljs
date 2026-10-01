(ns hyperopen.views.funding-modal.transfer-form-states-test
  "Transfer form states beyond the happy path: a submit in flight, the
   blocked cards' other fixes and links, disabled assets, the draft's
   message, checking states, and the dialog's keyboard trap."
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

(defn- form
  [state]
  (transfer/render-content
   (:transfer (with-redefs [platform/now-ms (constantly support/now-ms)]
                (funding-actions/funding-modal-view-model state)))))

(defn- shell
  [state]
  (with-redefs [platform/now-ms (constantly support/now-ms)]
    (funding-modal/funding-modal-view state)))

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

(defn- tag
  [node]
  (when (vector? node) (first node)))

;; --- keyboard trap -----------------------------------------------------------------

(defn- focusable?
  "What the dialog's trap selector would match, before its visibility
   check: a link, an enabled control, or a non-negative tabindex."
  [node]
  (let [a (attrs node)]
    (boolean
     (or (and (= :a (tag node)) (some? (:href a)))
         (and (contains? #{:button :input :select :textarea} (tag node)) (not (:disabled a)))
         (and (some? (:tabindex a)) (not= "-1" (str (:tabindex a))))))))

(defn- focusables-in-hidden-slots
  "Focusable nodes under an ancestor hidden by class. The browser skips them,
   but a trap that checks only a node's own style would count them, and one
   that is the last focusable lets Tab leave the dialog."
  [node]
  (->> (hiccup/find-all-nodes node hidden?)
       (mapcat (fn [hidden-node]
                 (mapcat #(hiccup/find-all-nodes % focusable?) (hiccup/node-children hidden-node))))
       (mapv #(select-keys (attrs %) [:data-role :href]))))

(defn- run-state
  [modal-overrides run]
  (support/state (merge modal-overrides {:transfer-evm run :amount-input "100"})))

(deftest no-focusable-control-hides-in-a-hidden-slot-test
  (doseq [[label state]
          [["a verified token with the submit disabled"
            (support/state (assoc spot->evm :transfer-asset support/purr-index))]
           ["an unverified token's notice"
            (support/state (assoc spot->evm :transfer-asset support/six-index))]
           ["a gas-blocked draft"
            (support/with-evm-entry (support/state (assoc evm->spot :transfer-asset support/purr-index))
              (assoc support/evm-entry :native-wei "0"))]
           ["the Perps <-> Spot route" (support/state {:to-perp? true :amount-input "5"})]
           ["progress" (run-state (assoc evm->spot :transfer-asset support/hype-index)
                                  {:phase :running :flow-id "f" :route :evm->core
                                   :steps [{:id :send :label "Send" :status :active}]})]
           ["pending" (run-state (assoc evm->spot :transfer-asset support/hype-index)
                                 {:phase :pending :flow-id "f" :tx-hash "0xabc"})]
           ["a failure that may have moved funds"
            (run-state (assoc spot->evm :transfer-asset support/purr-index)
                       {:phase :failed :flow-id "f" :maybe-sent? true :error "Transfer failed: x"})]
           ["success into Spot" (run-state (assoc evm->spot :transfer-asset support/hype-index)
                                           {:phase :succeeded :flow-id "f" :arrival :arriving})]
           ["success into HyperEVM" (run-state (assoc spot->evm :transfer-asset support/purr-index)
                                               {:phase :succeeded :flow-id "f" :arrival :arriving})]]]
    (testing label
      (is (= [] (focusables-in-hidden-slots (shell state)))))))

(deftest verified-tokens-render-no-explorer-link-test
  (let [node (form (support/state (assoc spot->evm :transfer-asset support/purr-index)))]
    (is (hidden? (by-role node "funding-transfer-verification-notice")))
    (is (nil? (by-role node "funding-transfer-token-explorer"))
        "the link renders only with its notice")))

;; --- a submit in flight --------------------------------------------------------------

(deftest a-submit-in-flight-locks-every-control-test
  (let [node (form (support/state (assoc spot->evm :transfer-asset support/hype-index
                                         :amount-input "250" :submitting? true)))
        places (concat (hiccup/node-children (by-role node "funding-transfer-from-group"))
                       (hiccup/node-children (by-role node "funding-transfer-to-group")))
        radios (hiccup/find-all-nodes (by-role node "funding-transfer-asset-list")
                                      #(= "radio" (:type (attrs %))))
        buttons (map #(by-role node %) ["funding-transfer-swap" "funding-transfer-max"
                                        "funding-transfer-percent-25" "funding-transfer-percent-50"
                                        "funding-transfer-percent-75" "funding-transfer-submit"])]
    (is (= 6 (count places)))
    (doseq [place places]
      (is (= "true" (:aria-disabled (attrs place))) (:data-role (attrs place)))
      (is (nil? (:on (attrs place))) (:data-role (attrs place))))
    (is (seq radios))
    (doseq [radio radios]
      (is (true? (:disabled (attrs radio))) (:value (attrs radio)))
      (is (nil? (:on (attrs radio))) (:value (attrs radio))))
    (is (true? (:disabled (attrs (by-role node "funding-transfer-amount-input")))))
    (doseq [button buttons]
      (is (true? (:disabled (attrs button))) (:data-role (attrs button)))
      (is (nil? (:on (attrs button))) (:data-role (attrs button))))
    (is (= "Submitting..." (text (by-role node "funding-transfer-submit"))))))

;; --- blocked cards -------------------------------------------------------------------

(deftest refused-network-switch-offers-the-capability-retry-test
  (let [node (form (assoc-in (support/state (assoc evm->spot :transfer-asset support/purr-index))
                             [:hyperevm :wallet-capabilities "default"] {:chain-switch :unsupported}))
        card (by-role node "funding-transfer-blocked")
        retry (by-role card "funding-transfer-capability-retry")]
    (is (= "no-chain-switch" (:data-blocked-code (attrs card))))
    (is (str/includes? (text card) "couldn't switch to HyperEVM"))
    (is (= "Try switching again" (text retry))
        "it re-enables the form; it does not resubmit like Try again")
    (is (= {:click [[:actions/retry-funding-transfer-capability]]} (:on (attrs retry))))
    (is (nil? (by-role node "funding-transfer-gas-fix")))))

(deftest a-move-still-confirming-links-its-transaction-test
  (let [node (form (assoc-in (support/state (assoc evm->spot :transfer-asset support/purr-index))
                             [:hyperevm :in-flight support/owner]
                             {:status :pending :hashes ["0xabc"] :waiting-receipt? false}))
        card (by-role node "funding-transfer-blocked")
        link (by-role card "funding-transfer-blocked-explorer")]
    (is (= "in-flight" (:data-blocked-code (attrs card))))
    (is (= "https://hyperevmscan.io/tx/0xabc" (:href (attrs link))))
    (is (= "noreferrer noopener" (:rel (attrs link))))
    (is (nil? (by-role (by-role card "funding-transfer-blocked-status") "funding-transfer-blocked-explorer"))
        "the link sits outside the live region")))

(deftest the-live-region-reads-the-message-not-the-fix-test
  (let [node (form (support/with-evm-entry
                     (support/state (assoc evm->spot :transfer-asset support/purr-index))
                     (assoc support/evm-entry :native-wei "0")))
        region (by-role node "funding-transfer-blocked-region")
        live (by-role node "funding-transfer-blocked-status")]
    (is (nil? (:role (attrs region))) "the card around the fix is not live")
    (is (= ["status" "polite"] ((juxt :role :aria-live) (attrs live))))
    (is (str/includes? (text live) "You need HYPE on HyperEVM to pay gas"))
    (is (nil? (by-role live "funding-transfer-gas-fix")) "the fix button is outside it")
    (is (= ["status" "polite"]
           ((juxt :role :aria-live) (attrs (by-role node "funding-transfer-fix-status"))))
        "the top-up's progress has its own live line")))

(deftest checking-keeps-the-details-in-place-test
  (let [node (form (support/with-evm-entry
                     (support/state (assoc evm->spot :transfer-asset support/purr-index))
                     {:status :loading :requested-at-ms 1}))
        card (by-role node "funding-transfer-blocked")]
    (is (= "true" (:data-checking (attrs card))))
    (is (= "Checking HyperEVM balances…" (text (by-role card "funding-transfer-blocked-status"))))
    (is (not (contains? (hiccup/node-class-set card) "border-ho-warn/40")) "a quiet line, not a warning card")
    (is (not (hidden? (by-role node "funding-transfer-details")))
        "the cards and summary stay, so the form does not jump while a read lands")))

(deftest a-loading-source-shows-no-empty-claim-test
  (let [node (form (support/with-evm-entry (support/state evm->spot) {:status :loading :requested-at-ms 1}))]
    (is (hidden? (by-role node "funding-transfer-asset-empty")))
    (is (= "Checking HyperEVM balances…"
           (text (by-role node "funding-transfer-blocked-status"))))))

;; --- assets --------------------------------------------------------------------------

(deftest an-unmovable-asset-explains-itself-test
  (let [node (form (support/state (assoc spot->evm :transfer-asset support/hype-index)))
        option (by-role node "funding-transfer-asset-option-122")
        radio (hiccup/find-first-node option #(= "radio" (:type (attrs %))))
        reason (hiccup/find-first-node option #(= "funding-transfer-asset-reason-122" (:id (attrs %))))]
    (is (true? (:disabled (attrs radio))))
    (is (nil? (:on (attrs radio))))
    (is (= "funding-transfer-asset-reason-122" (:aria-describedby (attrs radio))))
    (is (not (hidden? reason)))
    (is (= "This token's HyperEVM link can't be verified, so it can't be moved here." (text reason)))))

;; --- the draft's message -------------------------------------------------------------

(deftest the-draft-message-sits-above-the-submit-test
  (let [state (support/state (assoc spot->evm :transfer-asset support/hype-index :amount-input "500"))
        node (shell state)
        footer (by-role node "funding-transfer-actions")
        message (by-role footer "funding-transfer-message")
        children (vec (hiccup/find-all-nodes footer #(contains? #{"funding-transfer-message"
                                                                  "funding-transfer-submit"}
                                                                (:data-role (attrs %)))))]
    (is (= "Amount exceeds available balance." (text message)))
    (is (not (hidden? message)))
    (is (= ["funding-transfer-message" "funding-transfer-submit"]
           (mapv (comp :data-role attrs) children))
        "inside the sticky footer, right above the submit it explains")
    (is (= ["status" "polite"]
           ((juxt :role :aria-live) (attrs (by-role footer "funding-transfer-message-region")))))
    (is (= "funding-transfer-message" (:id (attrs message))))
    (is (= "funding-transfer-message" (:aria-describedby (attrs (by-role node "funding-transfer-submit")))))
    (let [input (by-role node "funding-transfer-amount-input")]
      (is (= ["true" "funding-transfer-message"] ((juxt :aria-invalid :aria-describedby) (attrs input)))))
    (is (nil? (by-role node "funding-status")) "the shell's red line is never used for Transfer"))
  (testing "a valid draft has an empty, hidden message and no description"
    (let [node (form (support/state (assoc spot->evm :transfer-asset support/hype-index :amount-input "250")))]
      (is (hidden? (by-role node "funding-transfer-message")))
      (is (nil? (:aria-describedby (attrs (by-role node "funding-transfer-submit")))))
      (is (nil? (:aria-invalid (attrs (by-role node "funding-transfer-amount-input"))))))))

(deftest a-finished-move-shows-no-stale-draft-error-test
  (let [node (shell (support/state (assoc spot->evm :transfer-asset support/hype-index :amount-input "500"
                                          :transfer-evm {:phase :succeeded :flow-id "f" :route :core->evm
                                                         :arrival :arrived
                                                         :result {:amount "500" :symbol "HYPE"
                                                                  :from :spot :to :hyperevm}})))]
    (is (some? (by-role node "funding-transfer-success")))
    (is (nil? (by-role node "funding-status"))
        "the draft now exceeds the refreshed balance, but the success view never says so")
    (is (nil? (by-role node "funding-transfer-message")))))

;; --- panel ---------------------------------------------------------------------------

(deftest the-sticky-footer-reaches-over-the-panel-padding-test
  (let [footer (by-role (form (support/state {:to-perp? true})) "funding-transfer-actions")]
    (is (= {:bottom "calc(-1 * var(--funding-panel-pad-bottom, 1rem))"
            :margin-bottom "calc(-1 * var(--funding-panel-pad-bottom, 1rem))"
            :padding-bottom "var(--funding-panel-pad-bottom, 1rem)"}
           (:style (attrs footer))))
    (is (contains? (hiccup/node-class-set footer) "sticky")))
  (let [state (assoc (support/state {:to-perp? true})
                     :funding-ui {:modal (support/modal {:to-perp? true
                                                         :anchor {:left 10 :right 60 :top 700 :bottom 730
                                                                  :viewport-width 375
                                                                  :viewport-height 812}})})
        sheet (by-role (shell state) "funding-modal")]
    (is (= "max(env(safe-area-inset-bottom), 1rem)"
           (get-in (attrs sheet) [:style :--funding-panel-pad-bottom]))
        "the phone sheet publishes its safe-area padding for the footer")
    (is (contains? (hiccup/node-class-set sheet) "scroll-pb-28")
        "a control reached with Tab scrolls clear of the footer")))
