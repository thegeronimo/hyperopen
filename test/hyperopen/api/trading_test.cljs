(ns hyperopen.api.trading-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.api.trading :as trading]
            [hyperopen.utils.hl-signing :as signing]))

(deftest approve-agent-signs-and-posts-exchange-payload-test
  (async done
    (let [signed-payload (atom nil)
          fetch-call (atom nil)
          original-sign signing/sign-approve-agent-action!
          original-fetch (.-fetch js/globalThis)
          action {:type "approveAgent"
                  :agentAddress "0x9999999999999999999999999999999999999999"
                  :nonce 1700000004444
                  :hyperliquidChain "Mainnet"
                  :signatureChainId "0x66eee"}]
      (set! signing/sign-approve-agent-action!
            (fn [address action*]
              (reset! signed-payload [address action*])
              (js/Promise.resolve
               (clj->js {:r "0x1"
                         :s "0x2"
                         :v 27}))))
      (set! (.-fetch js/globalThis)
            (fn [url opts]
              (reset! fetch-call [url (js->clj opts :keywordize-keys true)])
              (js/Promise.resolve #js {:ok true})))
      (-> (trading/approve-agent! (atom {}) "0xowner" action)
          (.then (fn [_]
                   (let [[signed-address signed-action] @signed-payload
                         [url opts] @fetch-call
                         parsed-body (js->clj (js/JSON.parse (:body opts)) :keywordize-keys true)]
                     (is (= "0xowner" signed-address))
                     (is (= action signed-action))
                     (is (= trading/exchange-url url))
                     (is (= action (:action parsed-body)))
                     (is (= 1700000004444 (:nonce parsed-body)))
                     (is (= {:r "0x1" :s "0x2" :v 27}
                            (:signature parsed-body)))
                     (done))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
           (fn []
             (set! signing/sign-approve-agent-action! original-sign)
             (set! (.-fetch js/globalThis) original-fetch)))))))

(deftest submit-order-agent-signs-locally-and-persists-nonce-cursor-test
  (async done
    (let [store (atom {:wallet {:agent {:status :ready
                                        :storage-mode :session
                                        :nonce-cursor 1700000005555}}})
          sign-calls (atom [])
          persist-calls (atom [])
          fetch-calls (atom [])
          original-load agent-session/load-agent-session-by-mode
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          original-fetch (.-fetch js/globalThis)]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              {:agent-address "0x9999999999999999999999999999999999999999"
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
      (set! (.-fetch js/globalThis)
            (fn [url opts]
              (swap! fetch-calls conj [url (js->clj opts :keywordize-keys true)])
              (js/Promise.resolve
               #js {:ok true
                    :json (fn []
                            (js/Promise.resolve #js {:status "ok"}))})))
      (-> (trading/submit-order! store
                                 "0xowner"
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
                         payload (js->clj (js/JSON.parse (:body fetch-opts)) :keywordize-keys true)]
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
             (set! (.-fetch js/globalThis) original-fetch)))))))

(deftest submit-order-retries-on-nonce-error-once-test
  (async done
    (let [store (atom {:wallet {:agent {:status :ready
                                        :storage-mode :session
                                        :nonce-cursor 1700000006666}}})
          sign-nonces (atom [])
          fetch-count (atom 0)
          original-load agent-session/load-agent-session-by-mode
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          original-fetch (.-fetch js/globalThis)]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              {:agent-address "0x8888888888888888888888888888888888888888"
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
      (set! (.-fetch js/globalThis)
            (fn [_url _opts]
              (swap! fetch-count inc)
              (if (= 1 @fetch-count)
                (js/Promise.resolve
                 #js {:ok true
                      :json (fn []
                              (js/Promise.resolve #js {:status "err"
                                                       :error "nonce too low"}))})
                (js/Promise.resolve
                 #js {:ok true
                      :json (fn []
                              (js/Promise.resolve #js {:status "ok"}))}))))
      (-> (trading/submit-order! store
                                 "0xowner"
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
             (set! (.-fetch js/globalThis) original-fetch)))))))

(deftest submit-order-errors-when-agent-session-is-missing-test
  (async done
    (let [store (atom {:wallet {:agent {:status :ready
                                        :storage-mode :session}}})
          original-load agent-session/load-agent-session-by-mode]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode] nil))
      (-> (trading/submit-order! store
                                 "0xowner"
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

(deftest submit-order-invalidates-agent-session-when-api-wallet-is-missing-test
  (async done
    (let [store (atom {:wallet {:agent {:status :ready
                                        :storage-mode :session
                                        :nonce-cursor 1700000007777}}})
          cleared (atom [])
          persisted (atom 0)
          original-load agent-session/load-agent-session-by-mode
          original-clear agent-session/clear-agent-session-by-mode!
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          original-fetch (.-fetch js/globalThis)]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              {:agent-address "0x7777777777777777777777777777777777777777"
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
      (set! signing/sign-l1-action-with-private-key!
            (fn [_private-key _action _nonce & _]
              (js/Promise.resolve
               (clj->js {:r "0x01"
                         :s "0x02"
                         :v 27}))))
      (set! (.-fetch js/globalThis)
            (fn [_url _opts]
              (js/Promise.resolve
               #js {:ok true
                    :json (fn []
                            (js/Promise.resolve
                             #js {:status "err"
                                  :error "User or API Wallet 0x7777777777777777777777777777777777777777 does not exist."}))})))
      (-> (trading/submit-order! store
                                 "0xowner"
                                 {:type "order"
                                  :orders []
                                  :grouping "na"})
          (.then (fn [resp]
                   (is (= "err" (:status resp)))
                   (is (re-find #"Enable Trading again"
                                (str (:error resp))))
                   (is (= [["0xowner" :session]] @cleared))
                   (is (= 0 @persisted))
                   (is (= :error (get-in @store [:wallet :agent :status])))
                   (is (re-find #"Enable Trading again"
                                (str (get-in @store [:wallet :agent :error]))))
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
             (set! (.-fetch js/globalThis) original-fetch)))))))

(deftest cancel-order-invalidates-agent-session-when-api-wallet-is-missing-test
  (async done
    (let [store (atom {:wallet {:agent {:status :ready
                                        :storage-mode :session
                                        :nonce-cursor 1700000008888}}})
          cleared (atom [])
          persisted (atom 0)
          original-load agent-session/load-agent-session-by-mode
          original-clear agent-session/clear-agent-session-by-mode!
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          original-fetch (.-fetch js/globalThis)]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              {:agent-address "0x6666666666666666666666666666666666666666"
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
      (set! signing/sign-l1-action-with-private-key!
            (fn [_private-key _action _nonce & _]
              (js/Promise.resolve
               (clj->js {:r "0x01"
                         :s "0x02"
                         :v 27}))))
      (set! (.-fetch js/globalThis)
            (fn [_url _opts]
              (js/Promise.resolve
               #js {:ok true
                    :json (fn []
                            (js/Promise.resolve
                             #js {:status "err"
                                  :response "User or API Wallet 0x6666666666666666666666666666666666666666 does not exist."}))})))
      (-> (trading/cancel-order! store
                                 "0xowner"
                                 {:type "cancel"
                                  :cancels [{:a 0 :o 1}]})
          (.then (fn [resp]
                   (is (= "err" (:status resp)))
                   (is (re-find #"Enable Trading again"
                                (str (:error resp))))
                   (is (= [["0xowner" :session]] @cleared))
                   (is (= 0 @persisted))
                   (is (= :error (get-in @store [:wallet :agent :status])))
                   (is (re-find #"Enable Trading again"
                                (str (get-in @store [:wallet :agent :error]))))
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
             (set! (.-fetch js/globalThis) original-fetch)))))))

(deftest submit-order-preserves-agent-session-when-vault-is-not-registered-test
  (async done
    (let [store (atom {:wallet {:agent {:status :ready
                                        :storage-mode :session
                                        :nonce-cursor 1700000009999}}})
          cleared (atom [])
          persisted (atom [])
          original-load agent-session/load-agent-session-by-mode
          original-clear agent-session/clear-agent-session-by-mode!
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          original-fetch (.-fetch js/globalThis)]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              {:agent-address "0x5555555555555555555555555555555555555555"
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
      (set! (.-fetch js/globalThis)
            (fn [_url _opts]
              (js/Promise.resolve
               #js {:ok true
                    :json (fn []
                            (js/Promise.resolve
                             #js {:status "err"
                                  :response "Vault not registered: 0xabc"}))})))
      (-> (trading/submit-order! store
                                 "0xowner"
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
             (set! (.-fetch js/globalThis) original-fetch)))))))
