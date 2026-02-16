(ns hyperopen.api.trading-test
  (:require [cljs.test :refer-macros [async deftest is]]
    [hyperopen.wallet.agent-session :as agent-session]
    [hyperopen.api.trading :as trading]
    [hyperopen.platform :as platform]
    [hyperopen.schema.contracts :as contracts]
    [hyperopen.utils.hl-signing :as signing]))

(deftest resolve-cancel-order-oid-normalizes-supported-wire-keys-test
  (is (= 42
         (trading/resolve-cancel-order-oid {:oid "42"})))
  (is (= 42
         (trading/resolve-cancel-order-oid {:o "42"})))
  (is (= 42
         (trading/resolve-cancel-order-oid {:order {:oid "42"}})))
  (is (= 42
         (trading/resolve-cancel-order-oid {:order {:o "42"}}))))

(deftest build-cancel-order-request-normalizes-order-shape-and-integer-fields-test
  (let [state {:asset-contexts {}
               :asset-selector {:market-by-key {}}}
        request (trading/build-cancel-order-request state {:order {:assetIdx "12"
                                                                   :oid "307891000622"}})]
    (is (= {:action {:type "cancel"
                     :cancels [{:a 12
                                :o 307891000622}]}}
           request))))

(deftest build-cancel-order-request-falls-back-to-market-and-context-index-test
  (let [market-fallback-state {:asset-contexts {}
                               :asset-selector {:market-by-key {"perp:SOL" {:coin "SOL"
                                                                            :idx 7}}}}
        context-fallback-state {:asset-contexts {:BTC {:idx "5"}}
                                :asset-selector {:market-by-key {}}}]
    (is (= {:action {:type "cancel"
                     :cancels [{:a 7 :o 99}]}}
           (trading/build-cancel-order-request market-fallback-state {:coin "SOL"
                                                                      :oid "99"})))
    (is (= {:action {:type "cancel"
                     :cancels [{:a 5 :o 88}]}}
           (trading/build-cancel-order-request context-fallback-state {:coin "BTC"
                                                                       :oid "88"})))))

(deftest build-cancel-order-request-returns-nil-when-required-fields-are-missing-test
  (is (nil? (trading/build-cancel-order-request {:asset-contexts {}
                                                 :asset-selector {:market-by-key {}}}
                                                {:coin "BTC"})))
  (is (nil? (trading/build-cancel-order-request {:asset-contexts {}
                                                 :asset-selector {:market-by-key {}}}
                                                {:oid 101}))))

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

(deftest cancel-order-preserves-agent-session-when-user-role-confirms-agent-test
  (async done
    (let [store (atom {:wallet {:agent {:status :ready
                                        :storage-mode :session
                                        :nonce-cursor 1700000011111}}})
          cleared (atom [])
          persisted (atom 0)
          info-lookups (atom 0)
          original-load agent-session/load-agent-session-by-mode
          original-clear agent-session/clear-agent-session-by-mode!
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          original-fetch (.-fetch js/globalThis)]
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
      (set! signing/sign-l1-action-with-private-key!
            (fn [_private-key _action _nonce & _]
              (js/Promise.resolve
               (clj->js {:r "0x01"
                         :s "0x02"
                         :v 27}))))
      (set! (.-fetch js/globalThis)
            (fn [url _opts]
              (if (= trading/info-url url)
                (do
                  (swap! info-lookups inc)
                  (js/Promise.resolve
                   #js {:ok true
                        :json (fn []
                                (js/Promise.resolve
                                 #js {:role "agent"
                                      :data #js {:user "0xowner"}}))}))
                (js/Promise.resolve
                 #js {:ok true
                      :json (fn []
                              (js/Promise.resolve
                               #js {:status "err"
                                    :response "User or API Wallet 0x8fd379246834eac74b8419ffda202cf8051f7a03 does not exist."}))}))))
      (-> (trading/cancel-order! store
                                 "0xowner"
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

(deftest submit-order-reconciles-agent-address-from-private-key-before-signing-test
  (async done
    (let [store (atom {:wallet {:agent {:status :ready
                                        :storage-mode :session
                                        :nonce-cursor 1700000012222}}})
          persisted (atom [])
          original-load agent-session/load-agent-session-by-mode
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          original-fetch (.-fetch js/globalThis)]
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
      (set! (.-fetch js/globalThis)
            (fn [_url _opts]
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

(deftest safe-private-key->agent-address-catches-errors-test
  (with-redefs [hyperopen.wallet.agent-session/private-key->agent-address
                (fn [_]
                  (throw (js/Error. "boom")))]
    (is (nil? (@#'hyperopen.api.trading/safe-private-key->agent-address
               "0xbroken")))))

(deftest next-nonce-falls-back-to-now-and-remains-monotonic-test
  (with-redefs [hyperopen.platform/now-ms (fn [] 1700000015000)]
    (is (= 1700000015000
           (@#'hyperopen.api.trading/next-nonce nil)))
    (is (= 1700000015000
           (@#'hyperopen.api.trading/next-nonce "bad")))
    (is (= 1700000015000
           (@#'hyperopen.api.trading/next-nonce 1700000014999)))
    (is (= 1700000016001
           (@#'hyperopen.api.trading/next-nonce 1700000016000)))))

(deftest post-signed-action-private-helper-includes-optional-fields-test
  (async done
    (let [fetch-call (atom nil)
          assert-call (atom nil)
          original-fetch (.-fetch js/globalThis)
          action {:type "order"
                  :orders []
                  :grouping "na"}
          signature {:r "0x1"
                     :s "0x2"
                     :v 27}]
      (set! (.-fetch js/globalThis)
            (fn [url opts]
              (reset! fetch-call [url (js->clj opts :keywordize-keys true)])
              (js/Promise.resolve #js {:ok true})))
      (with-redefs [hyperopen.schema.contracts/validation-enabled? (constantly true)
                    hyperopen.schema.contracts/assert-signed-exchange-payload!
                    (fn [payload context]
                      (reset! assert-call [payload context]))]
        (let [request (@#'hyperopen.api.trading/post-signed-action!
                       action
                       1700000017000
                       signature
                       :vault-address "0xABCDEF"
                       :expires-after 1700000017999)]
          (.finally
           (.catch
            (.then request
                   (fn [_]
                     (let [[url fetch-opts] @fetch-call
                           payload (js->clj (js/JSON.parse (:body fetch-opts))
                                            :keywordize-keys true)
                           [asserted-payload asserted-context] @assert-call]
                       (is (= trading/exchange-url url))
                       (is (= "0xABCDEF" (:vaultAddress payload)))
                       (is (= 1700000017999 (:expiresAfter payload)))
                       (is (= signature (:signature payload)))
                       (is (= payload asserted-payload))
                       (is (= {:boundary :api-trading/post-signed-action
                               :action-type "order"}
                              asserted-context))
                       (done))))
            (fn [err]
              (is false (str "Unexpected error: " err))
              (done)))
           (fn []
             (set! (.-fetch js/globalThis) original-fetch))))))))

(deftest sign-and-post-agent-action-private-helper-passes-vault-and-expiry-test
  (async done
    (let [store (atom {:wallet {:agent {:status :ready
                                        :storage-mode :session
                                        :nonce-cursor 1700000018000}}})
          sign-calls (atom [])
          fetch-calls (atom [])
          persisted (atom [])
          original-load agent-session/load-agent-session-by-mode
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          original-fetch (.-fetch js/globalThis)]
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
      (set! (.-fetch js/globalThis)
            (fn [url opts]
              (swap! fetch-calls conj [url (js->clj opts :keywordize-keys true)])
              (js/Promise.resolve
               #js {:ok true
                    :json (fn []
                            (js/Promise.resolve #js {:status "ok"}))})))
      (-> (@#'hyperopen.api.trading/sign-and-post-agent-action!
           store
           "0xowner"
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
                         payload (js->clj (js/JSON.parse (:body fetch-opts))
                                          :keywordize-keys true)]
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
             (set! (.-fetch js/globalThis) original-fetch)))))))

(deftest cancel-order-preserves-agent-session-when-user-role-lookup-fails-test
  (async done
    (let [store (atom {:wallet {:agent {:status :ready
                                        :storage-mode :session
                                        :nonce-cursor 1700000013333}}})
          cleared (atom [])
          persisted (atom 0)
          info-lookups (atom 0)
          original-load agent-session/load-agent-session-by-mode
          original-clear agent-session/clear-agent-session-by-mode!
          original-persist agent-session/persist-agent-session-by-mode!
          original-sign signing/sign-l1-action-with-private-key!
          original-fetch (.-fetch js/globalThis)]
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
      (set! signing/sign-l1-action-with-private-key!
            (fn [_private-key _action _nonce & _]
              (js/Promise.resolve
               (clj->js {:r "0x01"
                         :s "0x02"
                         :v 27}))))
      (set! (.-fetch js/globalThis)
            (fn [url _opts]
              (if (= trading/info-url url)
                (do
                  (swap! info-lookups inc)
                  (js/Promise.reject (js/Error. "network-failure")))
                (js/Promise.resolve
                 #js {:ok true
                      :json (fn []
                              (js/Promise.resolve
                               #js {:status "err"
                                    :response "User or API Wallet 0x8fd379246834eac74b8419ffda202cf8051f7a03 does not exist."}))}))))
      (-> (trading/cancel-order! store
                                 "0xowner"
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

(deftest should-invalidate-missing-api-wallet-session-returns-true-without-agent-address-test
  (async done
    (-> (@#'hyperopen.api.trading/should-invalidate-missing-api-wallet-session!
         "0xowner"
         {:agent-address "   "})
        (.then (fn [invalidate?]
                 (is (true? invalidate?))
                 (done)))
        (.catch (fn [err]
                  (is false (str "Unexpected error: " err))
                  (done))))))
