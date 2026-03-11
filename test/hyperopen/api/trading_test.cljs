(ns hyperopen.api.trading-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.platform :as platform]
            [hyperopen.api.trading :as trading]
            [hyperopen.api.trading.test-support :as support]
            [hyperopen.utils.hl-signing :as signing]
            [hyperopen.wallet.agent-session :as agent-session]))

(deftest build-cancel-order-request-public-seam-produces-cancel-action-test
  (is (= {:action {:type "cancel"
                   :cancels [{:a 12 :o 307891000622}]}}
         (trading/build-cancel-order-request
          {:asset-contexts {}
           :asset-selector {:market-by-key {}}}
          {:order {:assetIdx "12"
                   :oid "307891000622"}}))))

(deftest build-cancel-orders-request-public-seam-produces-batched-cancel-action-test
  (is (= {:action {:type "cancel"
                   :cancels [{:a 12 :o 307891000622}
                             {:a 13 :o 307891000623}]}}
         (trading/build-cancel-orders-request
          {:asset-contexts {}
           :asset-selector {:market-by-key {}}}
          [{:order {:assetIdx "12"
                    :oid "307891000622"}}
           {:order {:assetIdx "13"
                    :oid "307891000623"}}]))))

(deftest submit-order-public-seam-rejects-when-session-is-missing-test
  (async done
    (let [store (atom {:wallet {:agent {:status :ready
                                        :storage-mode :session}}})
          original-load agent-session/load-agent-session-by-mode]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              nil))
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

(deftest submit-vault-transfer-public-seam-rejects-when-session-is-missing-test
  (async done
    (let [store (atom {:wallet {:agent {:status :ready
                                        :storage-mode :session}}})
          original-load agent-session/load-agent-session-by-mode]
      (set! agent-session/load-agent-session-by-mode
            (fn [_wallet-address _storage-mode]
              nil))
      (-> (trading/submit-vault-transfer! store
                                          support/owner-address
                                          {:type "vaultTransfer"
                                           :vaultAddress "0x1234567890abcdef1234567890abcdef12345678"
                                           :isDeposit true
                                           :usd 1000000})
          (.then (fn [_]
                   (is false "Expected missing agent session to reject")
                   (done)))
          (.catch (fn [err]
                    (is (re-find #"Agent session unavailable" (str err)))
                    (done)))
          (.finally
           (fn []
             (set! agent-session/load-agent-session-by-mode original-load)))))))

(deftest submit-usd-class-transfer-signs-user-action-and-posts-request-test
  (async done
    (let [store (atom {:wallet {:chain-id "0xa4b1"
                                :user-signed-nonce-cursor 1700000005000}})
          sign-calls (atom [])
          fetch-calls (atom [])
          original-now platform/now-ms
          original-sign signing/sign-usd-class-transfer-action!
          restore-fetch! (support/install-fetch-stub!
                          (fn [url opts]
                            (swap! fetch-calls conj [url opts])
                            (js/Promise.resolve (support/json-response {:status "ok"}))))]
      (set! platform/now-ms (fn [] 1700000001000))
      (set! signing/sign-usd-class-transfer-action!
            (fn [address action]
              (swap! sign-calls conj [address action])
              (js/Promise.resolve
               (clj->js {:r "0x01"
                         :s "0x02"
                         :v 27}))))
      (-> (trading/submit-usd-class-transfer! store
                                              support/owner-address
                                              {:type "usdClassTransfer"
                                               :amount "10"
                                               :toPerp true})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= 1 (count @sign-calls)))
                   (is (= 1 (count @fetch-calls)))
                   (let [[signed-address signed-action] (first @sign-calls)
                         [_ fetch-opts] (first @fetch-calls)
                         payload (support/fetch-body->map fetch-opts)]
                     (is (= support/owner-address signed-address))
                     (is (= "0xa4b1" (:signatureChainId signed-action)))
                     (is (= "Mainnet" (:hyperliquidChain signed-action)))
                     (is (= 1700000005001 (:nonce signed-action)))
                     (is (= "10" (:amount signed-action)))
                     (is (= true (:toPerp signed-action)))
                     (is (= 1700000005001 (:nonce payload)))
                     (is (= signed-action (:action payload)))
                     (is (= {:r "0x01" :s "0x02" :v 27}
                            (:signature payload)))
                     (is (= 1700000005001
                            (get-in @store [:wallet :user-signed-nonce-cursor]))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
           (fn []
             (set! platform/now-ms original-now)
             (set! signing/sign-usd-class-transfer-action! original-sign)
             (restore-fetch!)))))))

(deftest submit-send-asset-signs-user-action-with-nonce-field-test
  (async done
    (let [store (atom {:wallet {:chain-id "0xa4b1"
                                :user-signed-nonce-cursor 1700000005500}})
          sign-calls (atom [])
          fetch-calls (atom [])
          original-now platform/now-ms
          original-sign signing/sign-send-asset-action!
          restore-fetch! (support/install-fetch-stub!
                          (fn [url opts]
                            (swap! fetch-calls conj [url opts])
                            (js/Promise.resolve (support/json-response {:status "ok"}))))]
      (set! platform/now-ms (fn [] 1700000001500))
      (set! signing/sign-send-asset-action!
            (fn [address action]
              (swap! sign-calls conj [address action])
              (js/Promise.resolve
               (clj->js {:r "0x03"
                         :s "0x04"
                         :v 27}))))
      (-> (trading/submit-send-asset! store
                                      support/owner-address
                                      {:type "sendAsset"
                                       :destination "0x1234567890abcdef1234567890abcdef12345678"
                                       :sourceDex "spot"
                                       :destinationDex "spot"
                                       :token "BTC"
                                       :amount "0.25"
                                       :fromSubAccount ""})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= 1 (count @sign-calls)))
                   (is (= 1 (count @fetch-calls)))
                   (let [[signed-address signed-action] (first @sign-calls)
                         [_ fetch-opts] (first @fetch-calls)
                         payload (support/fetch-body->map fetch-opts)]
                     (is (= support/owner-address signed-address))
                     (is (= "0xa4b1" (:signatureChainId signed-action)))
                     (is (= "Mainnet" (:hyperliquidChain signed-action)))
                     (is (= 1700000005501 (:nonce signed-action)))
                     (is (= "BTC" (:token signed-action)))
                     (is (= "spot" (:sourceDex signed-action)))
                     (is (= "spot" (:destinationDex signed-action)))
                     (is (= "0.25" (:amount signed-action)))
                     (is (= 1700000005501 (:nonce payload)))
                     (is (= signed-action (:action payload)))
                     (is (= {:r "0x03" :s "0x04" :v 27}
                            (:signature payload)))
                     (is (= 1700000005501
                            (get-in @store [:wallet :user-signed-nonce-cursor]))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
           (fn []
             (set! platform/now-ms original-now)
             (set! signing/sign-send-asset-action! original-sign)
             (restore-fetch!)))))))

(deftest submit-withdraw3-signs-user-action-with-time-field-test
  (async done
    (let [store (atom {:wallet {:chain-id "0x66eee"
                                :user-signed-nonce-cursor 1700000006000}})
          sign-calls (atom [])
          fetch-calls (atom [])
          original-now platform/now-ms
          original-sign signing/sign-withdraw3-action!
          restore-fetch! (support/install-fetch-stub!
                          (fn [url opts]
                            (swap! fetch-calls conj [url opts])
                            (js/Promise.resolve (support/json-response {:status "ok"}))))]
      (set! platform/now-ms (fn [] 1700000002000))
      (set! signing/sign-withdraw3-action!
            (fn [address action]
              (swap! sign-calls conj [address action])
              (js/Promise.resolve
               (clj->js {:r "0x0a"
                         :s "0x0b"
                         :v 28}))))
      (-> (trading/submit-withdraw3! store
                                     support/owner-address
                                     {:type "withdraw3"
                                      :amount "6.5"
                                      :destination "0x1234567890abcdef1234567890abcdef12345678"})
          (.then (fn [resp]
                   (is (= "ok" (:status resp)))
                   (is (= 1 (count @sign-calls)))
                   (is (= 1 (count @fetch-calls)))
                   (let [[signed-address signed-action] (first @sign-calls)
                         [_ fetch-opts] (first @fetch-calls)
                         payload (support/fetch-body->map fetch-opts)]
                     (is (= support/owner-address signed-address))
                     (is (= "0x66eee" (:signatureChainId signed-action)))
                     (is (= "Testnet" (:hyperliquidChain signed-action)))
                     (is (= 1700000006001 (:time signed-action)))
                     (is (= "6.5" (:amount signed-action)))
                     (is (= "0x1234567890abcdef1234567890abcdef12345678"
                            (:destination signed-action)))
                     (is (= 1700000006001 (:nonce payload)))
                     (is (= signed-action (:action payload)))
                     (is (= {:r "0x0a" :s "0x0b" :v 28}
                            (:signature payload)))
                     (is (= 1700000006001
                            (get-in @store [:wallet :user-signed-nonce-cursor]))))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
           (fn []
             (set! platform/now-ms original-now)
             (set! signing/sign-withdraw3-action! original-sign)
             (restore-fetch!)))))))
