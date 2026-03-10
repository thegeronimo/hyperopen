(ns hyperopen.wallet.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.core-bootstrap.test-support.effect-extractors :as effect-extractors]
            [hyperopen.wallet.actions :as wallet-actions]))

(def ^:private enable-agent-trading-heavy-effect-ids
  #{:effects/enable-agent-trading})

(deftest connect-and-disconnect-wallet-actions-emit-effects-test
  (is (= [[:effects/connect-wallet]]
         (wallet-actions/connect-wallet-action {})))
  (is (= [[:effects/disconnect-wallet]]
         (wallet-actions/disconnect-wallet-action {}))))

(deftest close-agent-recovery-modal-action-clears-open-flag-test
  (is (= [[:effects/save [:wallet :agent :recovery-modal-open?] false]]
         (wallet-actions/close-agent-recovery-modal-action {}))))

(deftest enable-agent-trading-action-emits-approving-before-effect-test
  (let [state {:wallet {:connected? true
                        :address "0xabc"
                        :agent {:storage-mode :session}}}
        effects (wallet-actions/enable-agent-trading-action state identity)
        immediate (first effects)
        io-effect (second effects)]
    (is (= :effects/save-many (first immediate)))
    (is (= [[:wallet :agent :status] :approving]
           (-> immediate second first)))
    (is (= [[:wallet :agent :error] nil]
           (-> immediate second second)))
    (is (effect-extractors/projection-before-heavy? effects enable-agent-trading-heavy-effect-ids))
    (is (effect-extractors/phase-order-valid? effects enable-agent-trading-heavy-effect-ids))
    (is (empty? (effect-extractors/duplicate-heavy-effect-ids effects enable-agent-trading-heavy-effect-ids)))
    (is (= [:effects/enable-agent-trading {:storage-mode :session}] io-effect))))

(deftest enable-agent-trading-action-errors-when-wallet-not-connected-test
  (let [state {:wallet {:connected? false
                        :address nil
                        :agent {:storage-mode :session}}}
        effects (wallet-actions/enable-agent-trading-action state identity)
        immediate (first effects)]
    (is (= :effects/save-many (first immediate)))
    (is (= [[:wallet :agent :status] :error]
           (-> immediate second first)))
    (is (= [[:wallet :agent :error] "Connect your wallet before enabling trading."]
           (-> immediate second second)))))

(deftest set-agent-storage-mode-action-emits-effect-only-on-change-test
  (let [state {:wallet {:agent {:storage-mode :session}}}]
    (is (= [[:effects/set-agent-storage-mode :local]]
           (wallet-actions/set-agent-storage-mode-action state :local identity)))
    (is (= []
           (wallet-actions/set-agent-storage-mode-action state :session identity)))))

(deftest copy-wallet-address-action-emits-address-payload-test
  (is (= [[:effects/copy-wallet-address "0xabc"]]
         (wallet-actions/copy-wallet-address-action {:wallet {:address "0xabc"}})))
  (is (= [[:effects/copy-wallet-address nil]]
         (wallet-actions/copy-wallet-address-action {:wallet {:address nil}}))))

(deftest actions-accept-plain-js-normalizers-test
  (let [normalize-js (js/Function. "value"
                                   "return (value === 'SESSION') ? 'session-js' : 'local-js';")
        connected-state {:wallet {:connected? true
                                  :address "0xabc"
                                  :agent {:storage-mode "SESSION"}}}
        storage-state {:wallet {:agent {:storage-mode "SESSION"}}}]
    (is (= [[:effects/save-many [[[:wallet :agent :status] :approving]
                                 [[:wallet :agent :error] nil]]]
            [:effects/enable-agent-trading {:storage-mode "session-js"}]]
           (wallet-actions/enable-agent-trading-action connected-state normalize-js)))
    (is (= [[:effects/set-agent-storage-mode "local-js"]]
           (wallet-actions/set-agent-storage-mode-action storage-state "LOCAL" normalize-js)))
    (is (= []
           (wallet-actions/set-agent-storage-mode-action storage-state "SESSION" normalize-js)))))
