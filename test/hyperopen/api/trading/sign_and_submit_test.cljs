(ns hyperopen.api.trading.sign-and-submit-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.trading :as trading]
            [hyperopen.api.trading.test-support :as support]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.utils.hl-signing :as signing]))

(deftest submit-order-agent-signs-locally-and-persists-nonce-cursor-test
  (async done
    (let [store (support/ready-agent-store 1700000005555)
          sign-calls (atom [])
          persist-calls (atom [])
          fetch-calls (atom [])
          original-load agent-session/load-agent-session-by-mode
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          restore-fetch! (support/install-fetch-stub!
                          (fn [url opts]
                            (swap! fetch-calls conj [url opts])
                            (js/Promise.resolve
                             (support/json-response {:status "ok"}))))]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              {:agent-address "0x8fd379246834eac74b8419ffda202cf8051f7a03"
               :private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
               :nonce-cursor 1700000005555}))
      (set! agent-session/persist-agent-session-by-mode!
            (fn [wallet-address storage-mode session]
              (swap! persist-calls conj [wallet-address storage-mode session])
              true))
      (set! signing/sign-l1-action-with-private-key!
            (fn [private-key action nonce & opts]
              (swap! sign-calls conj [private-key action nonce opts])
              (js/Promise.resolve
               (clj->js {:r "0x01"
                         :s "0x02"
                         :v 27}))))
      (-> (trading/submit-order! store
                                 support/owner-address
                                 {:type "order"
                                  :orders []
                                  :grouping "na"})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= 1 (count @sign-calls)))
                   (is (= 1 (count @fetch-calls)))
                   (is (= 1 (count @persist-calls)))
                   (let [[_ _ _ sign-opts] (first @sign-calls)
                         sign-opts-map (apply hash-map sign-opts)
                         [_ fetch-opts] (first @fetch-calls)
                         payload (support/fetch-body->map fetch-opts)]
                     (is (nil? (:vault-address sign-opts-map)))
                     (is (false? (contains? payload :vaultAddress))))
                   (is (number? (get-in @store [:wallet :agent :nonce-cursor])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
           (fn []
             (set! agent-session/load-agent-session-by-mode original-load)
             (set! agent-session/persist-agent-session-by-mode! original-persist)
             (set! signing/sign-l1-action-with-private-key! original-sign)
             (restore-fetch!)))))))

(deftest submit-order-retries-on-nonce-error-once-test
  (async done
    (let [store (support/ready-agent-store 1700000006666)
          sign-nonces (atom [])
          fetch-count (atom 0)
          original-load agent-session/load-agent-session-by-mode
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          restore-fetch! (support/install-fetch-stub!
                          (fn [_url _opts]
                            (swap! fetch-count inc)
                            (if (= 1 @fetch-count)
                              (js/Promise.resolve
                               (support/json-response {:status "err"
                                                       :error "nonce too low"}))
                              (js/Promise.resolve
                               (support/json-response {:status "ok"})))))]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              {:agent-address "0x88f9b82462f6c4bf4a0fb15e5c3971559a316e7f"
               :private-key "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
               :nonce-cursor 1700000006666}))
      (set! agent-session/persist-agent-session-by-mode!
            (fn [_wallet-address _storage-mode _session] true))
      (set! signing/sign-l1-action-with-private-key!
            (fn [_private-key _action nonce & opts]
              (is (nil? (:vault-address (apply hash-map opts))))
              (swap! sign-nonces conj nonce)
              (js/Promise.resolve
               (clj->js {:r "0x01"
                         :s "0x02"
                         :v 27}))))
      (-> (trading/submit-order! store
                                 support/owner-address
                                 {:type "order"
                                  :orders []
                                  :grouping "na"})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= 2 @fetch-count))
                   (is (= 2 (count @sign-nonces)))
                   (is (> (second @sign-nonces) (first @sign-nonces)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
           (fn []
             (set! agent-session/load-agent-session-by-mode original-load)
             (set! agent-session/persist-agent-session-by-mode! original-persist)
             (set! signing/sign-l1-action-with-private-key! original-sign)
             (restore-fetch!)))))))

(deftest submit-order-errors-when-agent-session-is-missing-test
  (async done
    (let [store (atom {:wallet {:agent {:status :ready
                                        :storage-mode :session}}})
          original-load agent-session/load-agent-session-by-mode]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode] nil))
      (-> (trading/submit-order! store
                                 support/owner-address
                                 {:type "order"
                                  :orders []
                                  :grouping "na"})
          (.then (fn [_]
                   (is false "Expected missing agent session to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"Agent session unavailable" (str err)))
                    (done)))
          (.finally
           (fn []
             (set! agent-session/load-agent-session-by-mode original-load)))))))

(deftest submit-order-reconciles-agent-address-from-private-key-before-signing-test
  (async done
    (let [store (support/ready-agent-store 1700000012222)
          persisted (atom [])
          original-load agent-session/load-agent-session-by-mode
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          restore-fetch! (support/install-fetch-stub!
                          (fn [_url _opts]
                            (js/Promise.resolve
                             (support/json-response {:status "ok"}))))]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              {:agent-address "0x1111111111111111111111111111111111111111"
               :private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
               :nonce-cursor 1700000012222}))
      (set! agent-session/persist-agent-session-by-mode!
            (fn [wallet-address storage-mode session]
              (swap! persisted conj [wallet-address storage-mode session])
              true))
      (set! signing/sign-l1-action-with-private-key!
            (fn [_private-key _action _nonce & _]
              (js/Promise.resolve
               (clj->js {:r "0x01"
                         :s "0x02"
                         :v 27}))))
      (-> (trading/submit-order! store
                                 support/owner-address
                                 {:type "order"
                                  :orders []
                                  :grouping "na"})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= 2 (count @persisted)))
                   (is (= "0x8fd379246834eac74b8419ffda202cf8051f7a03"
                          (get-in (nth @persisted 0) [2 :agent-address])))
                   (is (= "0x8fd379246834eac74b8419ffda202cf8051f7a03"
                          (get-in @store [:wallet :agent :agent-address])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
           (fn []
             (set! agent-session/load-agent-session-by-mode original-load)
             (set! agent-session/persist-agent-session-by-mode! original-persist)
             (set! signing/sign-l1-action-with-private-key! original-sign)
             (restore-fetch!)))))))

(deftest submit-order-preserves-agent-session-when-vault-is-not-registered-test
  (async done
    (let [store (support/ready-agent-store 1700000009999)
          cleared (atom [])
          persisted (atom [])
          original-load agent-session/load-agent-session-by-mode
          original-clear agent-session/clear-agent-session-by-mode!
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          restore-fetch! (support/install-fetch-stub!
                          (fn [_url _opts]
                            (js/Promise.resolve
                             (support/json-response {:status "err"
                                                     :response "Vault not registered: 0xabc"}))))]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              {:agent-address "0x46a23e25df9a0f6c18729dda9ad1af3b6a131160"
               :private-key "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
               :nonce-cursor 1700000009999}))
      (set! agent-session/clear-agent-session-by-mode!
            (fn [wallet-address storage-mode]
              (swap! cleared conj [wallet-address storage-mode])
              true))
      (set! agent-session/persist-agent-session-by-mode!
            (fn [wallet-address storage-mode session]
              (swap! persisted conj [wallet-address storage-mode session])
              true))
      (set! signing/sign-l1-action-with-private-key!
            (fn [_private-key _action _nonce & _]
              (js/Promise.resolve
               (clj->js {:r "0x01"
                         :s "0x02"
                         :v 27}))))
      (-> (trading/submit-order! store
                                 support/owner-address
                                 {:type "order"
                                  :orders []
                                  :grouping "na"})
          (.then (fn [resp]
                   (is (= "err" (:status resp)))
                   (is (re-find #"Vault not registered" (str (:response resp))))
                   (is (= [] @cleared))
                   (is (= 1 (count @persisted)))
                   (is (= :ready (get-in @store [:wallet :agent :status])))
                   (is (number? (get-in @store [:wallet :agent :nonce-cursor])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
           (fn []
             (set! agent-session/load-agent-session-by-mode original-load)
             (set! agent-session/clear-agent-session-by-mode! original-clear)
             (set! agent-session/persist-agent-session-by-mode! original-persist)
             (set! signing/sign-l1-action-with-private-key! original-sign)
             (restore-fetch!)))))))

(deftest sign-and-post-agent-action-private-helper-passes-vault-and-expiry-test
  (async done
    (let [store (support/ready-agent-store 1700000018000)
          sign-calls (atom [])
          fetch-calls (atom [])
          persisted (atom [])
          original-load agent-session/load-agent-session-by-mode
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          restore-fetch! (support/install-fetch-stub!
                          (fn [url opts]
                            (swap! fetch-calls conj [url opts])
                            (js/Promise.resolve
                             (support/json-response {:status "ok"}))))]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              {:agent-address "0x8fd379246834eac74b8419ffda202cf8051f7a03"
               :private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
               :nonce-cursor 1700000018000}))
      (set! agent-session/persist-agent-session-by-mode!
            (fn [wallet-address storage-mode session]
              (swap! persisted conj [wallet-address storage-mode session])
              true))
      (set! signing/sign-l1-action-with-private-key!
            (fn [private-key action nonce & opts]
              (swap! sign-calls conj [private-key action nonce opts])
              (js/Promise.resolve
               (clj->js {:r "0x01"
                         :s "0x02"
                         :v 27}))))
      (-> (@#'hyperopen.api.trading/sign-and-post-agent-action!
           store
           support/owner-address
           {:type "order"
            :orders []
            :grouping "na"}
           :vault-address "0xABCDEF1234"
           :expires-after 1700000019000
           :is-mainnet false
           :max-nonce-retries 0)
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= 1 (count @sign-calls)))
                   (is (= 1 (count @fetch-calls)))
                   (is (= 1 (count @persisted)))
                   (let [[_ _ _ sign-opts] (first @sign-calls)
                         sign-opts-map (apply hash-map sign-opts)
                         [_ fetch-opts] (first @fetch-calls)
                         payload (support/fetch-body->map fetch-opts)]
                     (is (= "0xabcdef1234" (:vault-address sign-opts-map)))
                     (is (= 1700000019000 (:expires-after sign-opts-map)))
                     (is (false? (:is-mainnet sign-opts-map)))
                     (is (= "0xabcdef1234" (:vaultAddress payload)))
                     (is (= 1700000019000 (:expiresAfter payload))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
           (fn []
             (set! agent-session/load-agent-session-by-mode original-load)
             (set! agent-session/persist-agent-session-by-mode! original-persist)
             (set! signing/sign-l1-action-with-private-key! original-sign)
             (restore-fetch!)))))))
