(ns hyperopen.views.funding-modal.transfer-review-fixes-test
  "Transfer view details the Milestone 9 review fixed: what the success
   cards say while a move arrives, what the blocked card announces, the
   amount field's accessible names and descriptions, decorative arrows, a
   disabled asset's readable reason, the stacked swap icon, the network
   retry's label, and where focus goes when the blocked card's fix goes
   away."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.platform :as platform]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.funding-modal :as funding-modal]
            [hyperopen.views.funding-modal.transfer :as transfer]
            [hyperopen.views.funding-modal.transfer-parts :as parts]))

(def ^:private spot->evm {:transfer-from :spot :transfer-to :hyperevm})
(def ^:private evm->spot {:transfer-from :hyperevm :transfer-to :spot})

(defn- view-model
  [state]
  (with-redefs [platform/now-ms (constantly support/now-ms)]
    (funding-actions/funding-modal-view-model state)))

(defn- form
  [state]
  (transfer/render-content (:transfer (view-model state))))

(defn- shell
  [state]
  (with-redefs [platform/now-ms (constantly support/now-ms)]
    (funding-modal/funding-modal-view state)))

(defn- by-role [node role] (hiccup/find-by-data-role node role))
(defn- attrs [node] (hiccup/node-attrs node))
(defn- classes [node] (hiccup/node-class-set node))
(defn- hidden? [node] (contains? (classes node) "hidden"))
(defn- text [node] (str/join "" (hiccup/collect-strings node)))

;; --- success cards ------------------------------------------------------------------

(defn- purr-success
  [arrival]
  (support/state (assoc spot->evm :transfer-asset support/purr-index :amount-input "100"
                        :transfer-evm {:phase :succeeded :flow-id "f" :route :core->evm
                                       :steps [{:id :sign :label "Move 100 PURR to HyperEVM"
                                                :status :done :detail "Confirmed"}]
                                       :arrival arrival
                                       :result {:amount "100" :symbol "PURR" :from :spot :to :hyperevm
                                                :owner support/owner :started-at-ms support/now-ms}})))

(deftest success-cards-show-the-move-until-it-arrives-test
  (testing "arriving (and slow): the move itself, never a stale balance under \"Sent\""
    (doseq [arrival [:arriving :slow]]
      (let [success (by-role (shell (purr-success arrival)) "funding-transfer-success")]
        (is (= "Sent" (text (by-role success "funding-transfer-result-from-label"))) (str arrival))
        (is (str/ends-with? (text (by-role success "funding-transfer-result-from")) "−100 PURR"))
        (is (= "Arriving" (text (by-role success "funding-transfer-result-to-label"))) (str arrival))
        (is (str/ends-with? (text (by-role success "funding-transfer-result-to")) "+100 PURR")))))
  (testing "arrived: each side's balance now, labelled as such"
    (let [success (by-role (shell (purr-success :arrived)) "funding-transfer-success")]
      (is (= "Spot now" (text (by-role success "funding-transfer-result-from-label"))))
      (is (= "HyperEVM now" (text (by-role success "funding-transfer-result-to-label"))))
      (is (str/ends-with? (text (by-role success "funding-transfer-result-from")) "9,800.00 PURR")))))

;; --- blocked card -----------------------------------------------------------------------

(deftest the-gas-card-announces-its-title-not-the-changing-estimate-test
  (let [node (form (support/with-evm-entry
                     (support/state (assoc evm->spot :transfer-asset support/purr-index))
                     (assoc support/evm-entry :native-wei "0")))
        live (by-role node "funding-transfer-blocked-status")
        detail (by-role node "funding-transfer-blocked-detail")]
    (is (= "You need HYPE on HyperEVM to pay gas" (text live))
        "each gas-price poll changes the estimate; it must not be read out again")
    (is (not (hidden? detail)))
    (is (str/includes? (text detail) "This move needs about"))
    (is (nil? (by-role live "funding-transfer-blocked-detail")))))

(deftest an-untitled-block-still-announces-its-message-test
  (let [node (form (assoc-in (support/state (assoc evm->spot :transfer-asset support/purr-index))
                             [:hyperevm :in-flight support/owner]
                             {:flow-id "x" :status :pending :hashes ["0xabc"] :waiting-receipt? false}))]
    (is (str/includes? (text (by-role node "funding-transfer-blocked-status"))
                       "reload the page to start another"))
    (is (hidden? (by-role node "funding-transfer-blocked-detail")))))

;; --- amount field ---------------------------------------------------------------------

(deftest the-amount-field-names-and-describes-its-controls-test
  (let [node (form (support/state (assoc evm->spot :transfer-asset support/hype-index)))
        input (by-role node "funding-transfer-amount-input")]
    (testing "the percent chips say what they are a share of"
      (is (= "25% of the maximum" (:aria-label (attrs (by-role node "funding-transfer-percent-25"))))))
    (testing "the MAX notice describes the input"
      (is (= "funding-transfer-amount-notice"
             (:id (attrs (by-role node "funding-transfer-amount-notice")))))
      (is (= "funding-transfer-amount-notice" (:aria-describedby (attrs input))))
      (is (nil? (:aria-invalid (attrs input))) "nothing typed is not invalid"))
    (testing "a known MAX is read with its amount"
      (is (str/starts-with? (:aria-label (attrs (by-role node "funding-transfer-max")))
                            "Use the maximum: "))))
  (testing "an unknown MAX is not read out as \"--\""
    (let [node (form (assoc-in (support/state (assoc spot->evm :transfer-asset support/purr-index))
                               [:hyperevm :bridge :evm-system-units] {}))]
      (is (= "Use the maximum" (:aria-label (attrs (by-role node "funding-transfer-max")))))))
  (testing "Available is what may move: HyperEVM HYPE keeps its gas reserve"
    (is (= "Available 12.499 HYPE"
           (text (by-role (form (support/state (assoc evm->spot :transfer-asset support/hype-index)))
                          "funding-transfer-available"))))))

;; --- links, assets, swap, retry --------------------------------------------------------

(deftest explorer-links-hide-the-arrow-and-say-they-open-a-tab-test
  (let [link (parts/explorer-link "https://hyperevmscan.io/tx/0x1" "View on explorer" "x")
        [arrow note] (filter vector? (hiccup/node-children link))]
    (is (= "_blank" (:target (attrs link))))
    (is (= "true" (:aria-hidden (attrs arrow))))
    (is (= " ↗" (text arrow)))
    (is (contains? (classes note) "sr-only"))
    (is (= " (opens in a new tab)" (text note)))))

(deftest a-disabled-asset-keeps-its-reason-readable-test
  ;; SIX's HyperEVM bridge is empty, so toward HyperEVM it is listed disabled.
  (let [node (form (support/state (assoc spot->evm :transfer-asset support/purr-index)))
        row (by-role node (str "funding-transfer-asset-option-" support/six-index))
        reason (hiccup/find-first-node row #(= (str "funding-transfer-asset-reason-" support/six-index)
                                               (:id (attrs %))))]
    (is (str/includes? (text reason) "HyperEVM bridge is empty"))
    (is (not (contains? (classes row) "opacity-70")) "the row itself is not dimmed")
    (is (not (contains? (classes reason) "opacity-70")))
    (is (contains? (classes reason) "text-ho-text-secondary"))
    (is (contains? (classes (hiccup/find-first-node row #(= :input (first %)))) "opacity-70")
        "the radio is dimmed instead")))

(deftest the-swap-icon-turns-with-the-stacked-places-test
  (is (every? (classes (parts/swap-icon)) ["rotate-90" "sm:rotate-0"])))

(deftest the-network-retry-says-it-only-re-enables-the-form-test
  (let [state (support/state (assoc evm->spot :transfer-asset support/purr-index
                                    :transfer-evm {:phase :failed :flow-id "f" :route :evm->core
                                                   :steps [] :arrival :idle :maybe-sent? false
                                                   :error "Your wallet couldn't switch"}))
        node (shell (assoc-in state [:hyperevm :wallet-capabilities "default"]
                              {:chain-switch :unsupported}))
        retry (by-role node "funding-transfer-try-again")]
    (is (= "Try switching again" (text retry)))
    (is (= {:click [[:actions/retry-funding-transfer-capability]]} (:on (attrs retry))))))

;; --- focus after the fix goes away -------------------------------------------------------

(defn- call-hook
  [hook life-cycle memory node]
  (let [remembered (atom ::none)]
    (hook {:replicant/life-cycle life-cycle
           :replicant/node node
           :replicant/memory memory
           :replicant/remember #(reset! remembered %)})
    @remembered))

(deftest focus-moves-to-the-form-when-the-fix-goes-away-test
  (let [timeouts (atom [])
        focused (atom [])
        target #js {:focus #(swap! focused conj :submit)}
        form-node #js {:querySelector (fn [selector]
                                        (when (str/includes? selector "funding-transfer-submit")
                                          target))}
        node #js {:isConnected true
                  :closest (fn [_] form-node)}]
    (let [original-document (.-document js/globalThis)
          body #js {}
          document #js {:body body :activeElement body}]
      ;; The focused fix left the DOM, so the browser put focus on the body.
      (set! (.-document js/globalThis) document)
      (try
        (with-redefs [platform/set-timeout! (fn [f ms] (swap! timeouts conj ms) (f))]
          (is (= true (call-hook (parts/fix-focus-hook true) :replicant.life-cycle/mount nil node)))
          (is (= [] @timeouts) "nothing moves while the fix is there")
          (is (= false (call-hook (parts/fix-focus-hook false) :replicant.life-cycle/update true node)))
          (is (= [0] @timeouts) "a tick later, after the dialog's own focus")
          (is (= [:submit] @focused) "the submit (or amount) gets focus, not the Close button")
          (call-hook (parts/fix-focus-hook false) :replicant.life-cycle/update false node)
          (is (= [0] @timeouts) "no fix before, nothing to restore")
          (set! (.-activeElement document) #js {:focused "elsewhere"})
          (call-hook (parts/fix-focus-hook false) :replicant.life-cycle/update true node)
          (is (= [0] @timeouts) "focus the user put elsewhere is left alone"))
        (finally
          (set! (.-document js/globalThis) original-document))))))
