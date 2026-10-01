(ns hyperopen.funding.application.transfer-capacity-refresh-test
  "A HyperEVM -> Core draft trusts the HyperCore bridge balance for a
   minute. These tests pin that the draft never dead-ends on it: edits,
   MAX, percent and submit read it again when due, the balance poller names
   the open draft's token, and every Transfer action's effects pass the
   runtime emission contract."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.funding.actions :as funding-actions]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-preview]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.hyperevm.domain.bridge :as bridge]
            [hyperopen.platform :as platform]
            [hyperopen.runtime.effect-order-contract :as effect-order-contract]
            [hyperopen.schema.contracts :as contracts]))

(def ^:private fetch-hype
  [:effects/fetch-hyperevm-core-bridge-balance support/hype-index
   "0x2222222222222222222222222222222222222222"])

(def ^:private fetch-activation
  [:effects/fetch-hyperevm-core-account-status support/owner])

(def ^:private stale-ms
  "Past the minute the bridge balance (read at `support/now-ms`) is trusted."
  (+ support/now-ms bridge/core-capacity-max-age-ms 1000))

(defn- hype-in
  [overrides]
  (support/state (merge {:transfer-from :hyperevm
                         :transfer-to :spot
                         :transfer-asset support/hype-index}
                        overrides)))

(defn- at
  [now-ms f]
  (with-redefs [platform/now-ms (constantly now-ms)]
    (f)))

(defn- reads
  [effects]
  (filterv #(contains? #{:effects/fetch-hyperevm-core-bridge-balance
                         :effects/fetch-hyperevm-core-account-status}
                       (first %))
           effects))

(defn- preview
  [state now-ms]
  (evm-preview/evm-transfer-preview state (get-in state [:funding-ui :modal]) now-ms))

(deftest a-stale-bridge-balance-is-read-again-and-the-form-recovers-test
  (let [state (hype-in {:amount-input "2"})]
    (testing "a minute later the preview waits on the bridge balance"
      (is (= "Checking the bridge balance…" (:display-message (preview state stale-ms)))))
    (testing "typing, MAX, percent and submit read it again"
      (at stale-ms
          (fn []
            (is (= [fetch-hype] (reads (funding-actions/enter-funding-transfer-amount state "3"))))
            (is (= [fetch-hype] (funding-actions/set-funding-amount-to-max state))
                "an unknown MAX keeps the typed amount and only reads again")
            (is (= [fetch-hype] (funding-actions/set-funding-transfer-amount-percent state 50)))
            (let [effects (funding-actions/submit-funding-transfer state)]
              (is (= [fetch-hype] (reads effects)))
              (is (= effects (effect-order-contract/assert-action-effect-order!
                              :actions/submit-funding-transfer effects {:phase :test})))))))
    (testing "once the read lands the draft is submittable again"
      (let [state* (bridge/apply-core-system-balance
                    state support/hype-index
                    {:balances [{:coin "HYPE" :token support/hype-index
                                 :total "51277474.28" :hold "0"}]}
                    stale-ms)]
        (is (:ok? (preview state* stale-ms)))
        (at stale-ms
            (fn []
              (is (= "12.499" (:amount-input (support/saved-modal
                                              (funding-actions/set-funding-amount-to-max state*)))))
              (is (= [] (reads (funding-actions/enter-funding-transfer-amount state* "3")))
                  "a fresh balance is not read again")))))))

(deftest a-read-in-flight-or-failed-is-bounded-test
  (testing "a read sent moments ago is not sent again until the retry gap"
    (let [requested (bridge/mark-core-system-balance-requested
                     (hype-in {}) support/hype-index (- stale-ms 2000))]
      (at stale-ms
          #(is (= [] (reads (funding-actions/enter-funding-transfer-amount requested "1")))))
      (at (+ stale-ms bridge/core-capacity-retry-ms)
          #(is (= [fetch-hype]
                  (reads (funding-actions/enter-funding-transfer-amount requested "1")))))))
  (testing "a failed read says so rather than \"Checking…\", and is retried"
    (let [failed (bridge/apply-core-system-balance-error (hype-in {}) support/hype-index "429")]
      (is (= "The bridge balance couldn't be read. Retrying…"
             (:display-message (preview failed support/now-ms))))
      (at support/now-ms
          #(is (= [fetch-hype]
                  (reads (funding-actions/enter-funding-transfer-amount failed "1"))))))))

(deftest an-unknown-activation-is-read-again-test
  (let [state (update-in (hype-in {}) [:hyperevm :core-account] dissoc support/owner)]
    (at support/now-ms
        #(is (= [fetch-hype fetch-activation]
                (reads (funding-actions/enter-funding-transfer-amount state "1")))))))

(deftest the-poller-names-the-open-drafts-token-when-due-test
  (let [due-ms (+ support/now-ms bridge/core-capacity-refresh-ms)]
    (is (= support/hype-index (funding-actions/transfer-capacity-refresh-index (hype-in {}) due-ms))
        "refreshed before the minute runs out")
    (is (nil? (funding-actions/transfer-capacity-refresh-index (hype-in {}) support/now-ms))
        "a fresh balance")
    (is (nil? (funding-actions/transfer-capacity-refresh-index
               (support/state {:transfer-from :spot :transfer-to :hyperevm
                               :transfer-asset support/hype-index})
               due-ms))
        "HyperCore -> HyperEVM needs no HyperCore read")
    (is (nil? (funding-actions/transfer-capacity-refresh-index
               (assoc-in (hype-in {}) [:funding-ui :modal :open?] false) due-ms))
        "a closed modal")
    (is (nil? (funding-actions/transfer-capacity-refresh-index
               (hype-in {:transfer-evm {:phase :running}}) due-ms))
        "a run that may be on chain")
    (is (= support/usdc-index
           (funding-actions/transfer-capacity-refresh-index
            (update-in (hype-in {:transfer-asset support/usdc-index})
                       [:hyperevm :core-account] dissoc support/owner)
            support/now-ms))
        "USDC needs only the activation read")))

(defn- emission-valid?
  [action-id effects]
  (= effects (contracts/assert-emitted-effects!
              effects {:phase :action-emission :action-id action-id})))

(deftest every-transfer-action-emits-contract-valid-effects-test
  ;; Actions are unit-tested through the facade, which skips the runtime's
  ;; emission validation; run it here so a bad path (a string key in an
  ;; `:effects/save` path) fails in `npm test`, not only in a debug build.
  (at stale-ms
      (fn []
        (let [evm-in (update-in (hype-in {:amount-input "2"})
                                [:hyperevm :core-account] dissoc support/owner)
              ;; The HyperEVM bridge reading is current at this clock (the
              ;; poll stamps it), so MAX and percent have a cap to fill.
              core-out (assoc-in (support/state {:transfer-from :spot :transfer-to :hyperevm
                                                 :transfer-asset support/purr-index
                                                 :amount-input "100"})
                                 [:hyperevm :bridge :evm-system-read-at-ms support/purr-index]
                                 stale-ms)
              failed-run (-> (hype-in {:transfer-evm {:phase :failed}})
                             (assoc-in [:wallet :selected-provider-id] "io.rabby")
                             (assoc-in [:hyperevm :wallet-capabilities "io.rabby"]
                                       {:chain-switch :unsupported}))
              cases [[:actions/open-funding-transfer-modal
                      (funding-actions/open-funding-transfer-modal
                       evm-in nil nil {:from :hyperevm :to :spot :asset "HYPE"})]
                     [:actions/set-funding-transfer-location
                      (funding-actions/set-funding-transfer-location core-out :from :hyperevm)]
                     [:actions/swap-funding-transfer-locations
                      (funding-actions/swap-funding-transfer-locations core-out)]
                     [:actions/select-funding-transfer-asset
                      (funding-actions/select-funding-transfer-asset evm-in support/purr-index)]
                     [:actions/set-funding-transfer-direction
                      (funding-actions/set-funding-transfer-direction evm-in true)]
                     [:actions/enter-funding-transfer-amount
                      (funding-actions/enter-funding-transfer-amount evm-in "1")]
                     [:actions/set-funding-amount-to-max
                      (funding-actions/set-funding-amount-to-max core-out)]
                     [:actions/set-funding-transfer-amount-percent
                      (funding-actions/set-funding-transfer-amount-percent core-out 25)]
                     [:actions/submit-funding-transfer
                      (funding-actions/submit-funding-transfer core-out)]
                     [:actions/submit-funding-transfer
                      (funding-actions/submit-funding-transfer evm-in)]
                     [:actions/submit-funding-transfer-gas-topup
                      (funding-actions/submit-funding-transfer-gas-topup evm-in)]
                     [:actions/reset-funding-transfer-evm
                      (funding-actions/reset-funding-transfer-evm failed-run)]
                     [:actions/retry-funding-transfer-capability
                      (funding-actions/retry-funding-transfer-capability failed-run)]
                     [:actions/add-funding-transfer-token-to-wallet
                      (funding-actions/add-funding-transfer-token-to-wallet core-out)]]]
          (doseq [[action-id effects] cases]
            (is (seq effects) (str action-id " emits effects"))
            (is (emission-valid? action-id effects) (str action-id)))))))
