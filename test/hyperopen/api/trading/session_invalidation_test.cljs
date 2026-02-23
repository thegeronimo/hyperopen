(ns hyperopen.api.trading.session-invalidation-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.trading :as trading]
            [hyperopen.test-support.api-stubs :as api-stubs]
            [hyperopen.test-support.async :as async-support]
            [hyperopen.api.trading.test-support :as support]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.utils.hl-signing :as signing]))

(deftest submit-order-invalidates-agent-session-when-api-wallet-is-missing-test
  (async done
    (let [store (support/ready-agent-store 1700000007777)
          cleared (atom [])
          persisted (atom 0)
          original-load agent-session/load-agent-session-by-mode
          original-clear agent-session/clear-agent-session-by-mode!
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          restore-fetch! (support/install-fetch-stub!
                          (fn [_url _opts]
                            (js/Promise.resolve
                             (support/json-response
                              {:status "err"
                               :error "User or API Wallet 0x7777777777777777777777777777777777777777 does not exist."}))))]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              {:agent-address "0xe8acf143afbf8b1371a20ea934d334180190eac1"
               :private-key "0xcccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
               :nonce-cursor 1700000007777}))
      (set! agent-session/clear-agent-session-by-mode!
            (fn [wallet-address storage-mode]
              (swap! cleared conj [wallet-address storage-mode])
              true))
      (set! agent-session/persist-agent-session-by-mode!
            (fn [_wallet-address _storage-mode _session]
              (swap! persisted inc)
              true))
      (set! signing/sign-l1-action-with-private-key! (api-stubs/signing-stub))
      (-> (trading/submit-order! store
                                 support/owner-address
                                 {:type "order"
                                  :orders []
                                  :grouping "na"})
          (.then (fn [resp]
                   (is (= "err" (:status resp)))
                   (is (re-find #"Enable Trading again"
                                (str (:error resp))))
                   (is (= [[support/owner-address :session]] @cleared))
                   (is (= 0 @persisted))
                   (is (= :error (get-in @store [:wallet :agent :status])))
                   (is (re-find #"Enable Trading again"
                                (str (get-in @store [:wallet :agent :error]))))
                   (done)))
          (.catch (async-support/unexpected-error done))
          (.finally
           (fn []
             (set! agent-session/load-agent-session-by-mode original-load)
             (set! agent-session/clear-agent-session-by-mode! original-clear)
             (set! agent-session/persist-agent-session-by-mode! original-persist)
             (set! signing/sign-l1-action-with-private-key! original-sign)
             (restore-fetch!)))))))

(deftest cancel-order-invalidates-agent-session-when-api-wallet-is-missing-test
  (async done
    (let [store (support/ready-agent-store 1700000008888)
          cleared (atom [])
          persisted (atom 0)
          original-load agent-session/load-agent-session-by-mode
          original-clear agent-session/clear-agent-session-by-mode!
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          restore-fetch! (support/install-fetch-stub!
                          (fn [_url _opts]
                            (js/Promise.resolve
                             (support/json-response
                              {:status "err"
                               :response "User or API Wallet 0x6666666666666666666666666666666666666666 does not exist."}))))]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              {:agent-address "0xa84585fb6728f413d4d89ec972c45e94686bf38e"
               :private-key "0xdddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
               :nonce-cursor 1700000008888}))
      (set! agent-session/clear-agent-session-by-mode!
            (fn [wallet-address storage-mode]
              (swap! cleared conj [wallet-address storage-mode])
              true))
      (set! agent-session/persist-agent-session-by-mode!
            (fn [_wallet-address _storage-mode _session]
              (swap! persisted inc)
              true))
      (set! signing/sign-l1-action-with-private-key! (api-stubs/signing-stub))
      (-> (trading/cancel-order! store
                                 support/owner-address
                                 {:type "cancel"
                                  :cancels [{:a 0 :o 1}]})
          (.then (fn [resp]
                   (is (= "err" (:status resp)))
                   (is (re-find #"Enable Trading again"
                                (str (:error resp))))
                   (is (= [[support/owner-address :session]] @cleared))
                   (is (= 0 @persisted))
                   (is (= :error (get-in @store [:wallet :agent :status])))
                   (is (re-find #"Enable Trading again"
                                (str (get-in @store [:wallet :agent :error]))))
                   (done)))
          (.catch (async-support/unexpected-error done))
          (.finally
           (fn []
             (set! agent-session/load-agent-session-by-mode original-load)
             (set! agent-session/clear-agent-session-by-mode! original-clear)
             (set! agent-session/persist-agent-session-by-mode! original-persist)
             (set! signing/sign-l1-action-with-private-key! original-sign)
             (restore-fetch!)))))))

(deftest cancel-order-preserves-agent-session-when-user-role-confirms-agent-test
  (async done
    (let [store (support/ready-agent-store 1700000011111)
          cleared (atom [])
          persisted (atom 0)
          info-lookups (atom 0)
          original-load agent-session/load-agent-session-by-mode
          original-clear agent-session/clear-agent-session-by-mode!
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          restore-fetch! (support/install-fetch-stub!
                          (fn [url _opts]
                            (if (= trading/info-url url)
                              (do
                                (swap! info-lookups inc)
                                (js/Promise.resolve
                                 (support/json-response {:role "agent"
                                                         :data {:user support/owner-address}})))
                              (js/Promise.resolve
                               (support/json-response
                                {:status "err"
                                 :response "User or API Wallet 0x8fd379246834eac74b8419ffda202cf8051f7a03 does not exist."})))))]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              {:agent-address "0x8fd379246834eac74b8419ffda202cf8051f7a03"
               :private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
               :nonce-cursor 1700000011111}))
      (set! agent-session/clear-agent-session-by-mode!
            (fn [wallet-address storage-mode]
              (swap! cleared conj [wallet-address storage-mode])
              true))
      (set! agent-session/persist-agent-session-by-mode!
            (fn [_wallet-address _storage-mode _session]
              (swap! persisted inc)
              true))
      (set! signing/sign-l1-action-with-private-key! (api-stubs/signing-stub))
      (-> (trading/cancel-order! store
                                 support/owner-address
                                 {:type "cancel"
                                  :cancels [{:a 0 :o 1}]})
          (.then (fn [resp]
                   (is (= "err" (:status resp)))
                   (is (re-find #"Preserved local trading key"
                                (str (:error resp))))
                   (is (= 1 @info-lookups))
                   (is (= [] @cleared))
                   (is (= 0 @persisted))
                   (is (= :ready (get-in @store [:wallet :agent :status])))
                   (done)))
          (.catch (async-support/unexpected-error done))
          (.finally
           (fn []
             (set! agent-session/load-agent-session-by-mode original-load)
             (set! agent-session/clear-agent-session-by-mode! original-clear)
             (set! agent-session/persist-agent-session-by-mode! original-persist)
             (set! signing/sign-l1-action-with-private-key! original-sign)
             (restore-fetch!)))))))

(deftest cancel-order-preserves-agent-session-when-user-role-lookup-fails-test
  (async done
    (let [store (support/ready-agent-store 1700000013333)
          cleared (atom [])
          persisted (atom 0)
          info-lookups (atom 0)
          original-load agent-session/load-agent-session-by-mode
          original-clear agent-session/clear-agent-session-by-mode!
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          restore-fetch! (support/install-fetch-stub!
                          (fn [url _opts]
                            (if (= trading/info-url url)
                              (do
                                (swap! info-lookups inc)
                                (js/Promise.reject (js/Error. "network-failure")))
                              (js/Promise.resolve
                               (support/json-response
                                {:status "err"
                                 :response "User or API Wallet 0x8fd379246834eac74b8419ffda202cf8051f7a03 does not exist."})))))]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              {:agent-address "0x8fd379246834eac74b8419ffda202cf8051f7a03"
               :private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
               :nonce-cursor 1700000013333}))
      (set! agent-session/clear-agent-session-by-mode!
            (fn [wallet-address storage-mode]
              (swap! cleared conj [wallet-address storage-mode])
              true))
      (set! agent-session/persist-agent-session-by-mode!
            (fn [_wallet-address _storage-mode _session]
              (swap! persisted inc)
              true))
      (set! signing/sign-l1-action-with-private-key! (api-stubs/signing-stub))
      (-> (trading/cancel-order! store
                                 support/owner-address
                                 {:type "cancel"
                                  :cancels [{:a 0 :o 1}]})
          (.then (fn [resp]
                   (is (= "err" (:status resp)))
                   (is (re-find #"Preserved local trading key"
                                (str (:error resp))))
                   (is (= 1 @info-lookups))
                   (is (= [] @cleared))
                   (is (= 0 @persisted))
                   (is (= :ready (get-in @store [:wallet :agent :status])))
                   (done)))
          (.catch (async-support/unexpected-error done))
          (.finally
           (fn []
             (set! agent-session/load-agent-session-by-mode original-load)
             (set! agent-session/clear-agent-session-by-mode! original-clear)
             (set! agent-session/persist-agent-session-by-mode! original-persist)
             (set! signing/sign-l1-action-with-private-key! original-sign)
             (restore-fetch!)))))))

(deftest should-invalidate-missing-api-wallet-session-returns-true-without-agent-address-test
  (async done
    (-> (@#'hyperopen.api.trading/should-invalidate-missing-api-wallet-session!
         support/owner-address
         {:agent-address "   "})
        (.then (fn [invalidate?]
                 (is (true? invalidate?))
                 (done)))
        (.catch (async-support/unexpected-error done)))))
