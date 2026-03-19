(ns hyperopen.core-bootstrap.agent-trading-lifecycle-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.core.compat :as core]
            [hyperopen.wallet.agent-session :as agent-session]))

(deftest enable-agent-trading-action-emits-approving-projection-before-effect-test
  (let [state {:wallet {:connected? true
                        :address "0xabc"
                        :agent {:storage-mode :session}}}
        effects (core/enable-agent-trading-action state)
        [save-effect io-effect] effects
        path-values (second save-effect)]
    (is (= :effects/save-many (first save-effect)))
    (is (= [[:wallet :agent :status] :approving]
           (first path-values)))
    (is (= [[:wallet :agent :error] nil]
           (second path-values)))
    (is (= [:effects/enable-agent-trading {:storage-mode :session}] io-effect))))

(deftest enable-agent-trading-action-errors-when-wallet-is-not-connected-test
  (let [state {:wallet {:connected? false
                        :address nil
                        :agent {:storage-mode :session}}}
        effects (core/enable-agent-trading-action state)
        [save-effect] effects
        path-values (second save-effect)]
    (is (= 1 (count effects)))
    (is (= :effects/save-many (first save-effect)))
    (is (= [[:wallet :agent :status] :error]
           (first path-values)))
    (is (= [[:wallet :agent :error] "Connect your wallet before enabling trading."]
           (second path-values)))))

(deftest set-agent-storage-mode-action-emits-effect-when-mode-changes-test
  (let [state {:wallet {:agent {:storage-mode :session}}}
        effects (core/set-agent-storage-mode-action state :local)]
    (is (= [[:effects/set-agent-storage-mode :local]]
           effects))))

(deftest set-agent-storage-mode-action-noops-when-mode-is-unchanged-test
  (let [state {:wallet {:agent {:storage-mode :session}}}
        effects (core/set-agent-storage-mode-action state :session)]
    (is (= [] effects))))

(deftest set-agent-storage-mode-effect-clears-sessions-and-resets-agent-state-test
  (let [store (atom {:wallet {:connected? true
                              :address "0xabc"
                              :agent {:status :ready
                                      :storage-mode :session
                                      :agent-address "0xagent"
                                      :nonce-cursor 1700000001111}}})
        cleared (atom [])
        persisted-modes (atom [])]
    (with-redefs [agent-session/clear-agent-session-by-mode!
                  (fn [wallet-address storage-mode]
                    (swap! cleared conj [wallet-address storage-mode])
                    true)
                  agent-session/persist-storage-mode-preference!
                  (fn [storage-mode]
                    (swap! persisted-modes conj storage-mode)
                    true)]
      (core/set-agent-storage-mode nil store :local)
      (is (= [["0xabc" :session]
              ["0xabc" :local]]
             @cleared))
      (is (= [:local] @persisted-modes))
      (is (= :not-ready (get-in @store [:wallet :agent :status])))
      (is (= :local (get-in @store [:wallet :agent :storage-mode])))
      (is (str/includes? (str (get-in @store [:wallet :agent :error]))
                         "Enable Trading again.")))))

(deftest restore-agent-storage-mode-applies-preference-before-wallet-bootstrap-test
  (let [store (atom {:wallet {:agent {:storage-mode :session}}})]
    (with-redefs [agent-session/load-storage-mode-preference
                  (fn
                    ([] :local)
                    ([_missing-default] :local))]
      (core/restore-agent-storage-mode! store)
      (is (= :local
             (get-in @store [:wallet :agent :storage-mode]))))))

(deftest enable-agent-trading-effect-sets-ready-state-on-success-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :approving
                                        :storage-mode :session}}})
          persisted (atom nil)
          original-create agent-session/create-agent-credentials!
          original-build-action agent-session/build-approve-agent-action
          original-approve trading-api/approve-agent!
          original-persist agent-session/persist-agent-session-by-mode!]
      (set! agent-session/create-agent-credentials!
            (fn []
              {:private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
               :agent-address "0x9999999999999999999999999999999999999999"}))
      (set! agent-session/build-approve-agent-action
            (fn [agent-address nonce & _]
              {:type "approveAgent"
               :agentAddress agent-address
               :nonce nonce
               :hyperliquidChain "Mainnet"
               :signatureChainId "0x66eee"}))
      (set! trading-api/approve-agent!
            (fn [_ owner-address action]
              (is (= "0xabc" owner-address))
              (is (= "approveAgent" (:type action)))
              (js/Promise.resolve
               #js {:json (fn []
                            (js/Promise.resolve #js {:status "ok"}))})))
      (set! agent-session/persist-agent-session-by-mode!
            (fn [wallet-address storage-mode session]
              (reset! persisted [wallet-address storage-mode session])))
      (letfn [(restore! []
                (set! agent-session/create-agent-credentials! original-create)
                (set! agent-session/build-approve-agent-action original-build-action)
                (set! trading-api/approve-agent! original-approve)
                (set! agent-session/persist-agent-session-by-mode! original-persist))]
        (core/enable-agent-trading nil store {:storage-mode :session})
        (js/setTimeout
         (fn []
           (is (= :ready (get-in @store [:wallet :agent :status])))
           (is (= "0x9999999999999999999999999999999999999999"
                  (get-in @store [:wallet :agent :agent-address])))
           (is (number? (get-in @store [:wallet :agent :last-approved-at])))
           (is (nil? (get-in @store [:wallet :agent :private-key])))
           (is (= "0xabc" (first @persisted)))
           (is (= :session (second @persisted)))
           (restore!)
           (done))
         0)))))

(deftest enable-agent-trading-effect-sets-error-state-on-failure-test
  (async done
    (let [store (atom {:wallet {:address "0xabc"
                                :agent {:status :approving
                                        :storage-mode :session}}})
          persisted (atom nil)
          original-create agent-session/create-agent-credentials!
          original-build-action agent-session/build-approve-agent-action
          original-approve trading-api/approve-agent!
          original-persist agent-session/persist-agent-session-by-mode!]
      (set! agent-session/create-agent-credentials!
            (fn []
              {:private-key "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
               :agent-address "0x8888888888888888888888888888888888888888"}))
      (set! agent-session/build-approve-agent-action
            (fn [agent-address nonce & _]
              {:type "approveAgent"
               :agentAddress agent-address
               :nonce nonce
               :hyperliquidChain "Mainnet"
               :signatureChainId "0x66eee"}))
      (set! trading-api/approve-agent!
            (fn [_ _ _]
              (js/Promise.resolve
               #js {:json (fn []
                            (js/Promise.resolve #js {:status "err"
                                                     :error "bad sig"}))})))
      (set! agent-session/persist-agent-session-by-mode!
            (fn [wallet-address storage-mode session]
              (reset! persisted [wallet-address storage-mode session])))
      (letfn [(restore! []
                (set! agent-session/create-agent-credentials! original-create)
                (set! agent-session/build-approve-agent-action original-build-action)
                (set! trading-api/approve-agent! original-approve)
                (set! agent-session/persist-agent-session-by-mode! original-persist))]
        (core/enable-agent-trading nil store {:storage-mode :session})
        (js/setTimeout
         (fn []
           (is (= :error (get-in @store [:wallet :agent :status])))
           (is (str/includes? (str (get-in @store [:wallet :agent :error]))
                              "bad sig"))
           (is (nil? @persisted))
           (restore!)
           (done))
         0)))))

(deftest enable-agent-trading-effect-sets-error-state-on-sync-exception-test
  (let [store (atom {:wallet {:address "0xabc"
                              :agent {:status :approving
                                      :storage-mode :session}}})
        original-create agent-session/create-agent-credentials!]
    (set! agent-session/create-agent-credentials!
          (fn []
            (throw (js/Error. "secure random unavailable"))))
    (try
      (core/enable-agent-trading nil store {:storage-mode :session})
      (is (= :error (get-in @store [:wallet :agent :status])))
      (is (str/includes? (str (get-in @store [:wallet :agent :error]))
                         "secure random unavailable"))
      (finally
        (set! agent-session/create-agent-credentials! original-create)))))
