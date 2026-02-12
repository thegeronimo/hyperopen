(ns hyperopen.wallet.actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.wallet.actions :as wallet-actions]))

(deftest connect-and-disconnect-wallet-actions-emit-effects-test
  (is (= [[:effects/connect-wallet]]
         (wallet-actions/connect-wallet-action {})))
  (is (= [[:effects/disconnect-wallet]]
         (wallet-actions/disconnect-wallet-action {}))))

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
