(ns hyperopen.api.trading.internal-seams-test
  (:require [hyperopen.runtime.validation :as validation]
            [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.trading :as trading]
            [hyperopen.api.gateway.orders.commands :as commands]
            [hyperopen.api.trading.test-support :as support]
            [hyperopen.platform :as platform]
            [hyperopen.schema.contracts]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.agent-session-crypto :as agent-session-crypto]
            [hyperopen.utils.hl-signing :as signing]))

(declare rejected-thenable)

(defn- resolved-thenable
  [value]
  #js {:then (fn [on-resolve]
               (try
                 (resolved-thenable (if on-resolve
                                      (on-resolve value)
                                      value))
                 (catch :default err
                   (rejected-thenable err))))
       :catch (fn [_]
                (resolved-thenable value))})

(defn- rejected-thenable
  [err]
  #js {:then (fn [_]
               (rejected-thenable err))
       :catch (fn [on-reject]
                (try
                  (resolved-thenable (if on-reject
                                       (on-reject err)
                                       err))
                  (catch :default catch-err
                    (rejected-thenable catch-err))))})

(deftest safe-private-key->agent-address-catches-errors-test
  (with-redefs [hyperopen.wallet.agent-session-crypto/private-key->agent-address
                (fn [_]
                  (throw (js/Error. "boom")))]
    (is (nil? (@#'hyperopen.api.trading/safe-private-key->agent-address
               "0xbroken")))))

(deftest next-nonce-falls-back-to-now-and-remains-monotonic-test
  (with-redefs [platform/now-ms (fn [] 1700000015000)]
    (is (= 1700000015000
           (@#'hyperopen.api.trading/next-nonce nil)))
    (is (= 1700000015000
           (@#'hyperopen.api.trading/next-nonce "bad")))
    (is (= 1700000015000
           (@#'hyperopen.api.trading/next-nonce 1700000014999)))
    (is (= 1700000016001
           (@#'hyperopen.api.trading/next-nonce 1700000016000)))))

(deftest debug-exchange-simulator-set-clear-and-snapshot-test
  (try
    (let [payload {:signedActions {:default {:responses [{:status "ok"}]}}}]
      (is (true? (trading/set-debug-exchange-simulator! payload)))
      (is (= {:installed true
              :config payload
              :calls []}
             (trading/debug-exchange-simulator-snapshot)))
      (is (true? (trading/clear-debug-exchange-simulator!)))
      (is (nil? (trading/debug-exchange-simulator-snapshot))))
    (finally
      (trading/clear-debug-exchange-simulator!))))

(deftest debug-exchange-simulator-snapshot-records-approve-agent-consumption-test
  (async done
    (let [original-sign signing/sign-approve-agent-action!
          cleanup! (fn []
                     (trading/clear-debug-exchange-simulator!)
                     (set! signing/sign-approve-agent-action! original-sign))
          action {:type "approveAgent"
                  :agentAddress "0x9999999999999999999999999999999999999999"
                  :nonce 1700000006666
                  :hyperliquidChain "Mainnet"
                  :signatureChainId "0x66eee"}]
      (set! signing/sign-approve-agent-action!
            (fn [_address _action]
              (js/Promise.resolve
               (clj->js {:r "0x1"
                         :s "0x2"
                         :v 27}))))
      (trading/set-debug-exchange-simulator!
       {:approveAgent {:responses [{:status "ok"}]}})
      (-> (trading/approve-agent! (atom {}) support/owner-address action)
          (.then (fn [resp]
                   (-> (.json resp)
                       (.then
                        (fn [_body]
                          (let [snapshot (trading/debug-exchange-simulator-snapshot)]
                            (is (true? (:installed snapshot)))
                            (is (= []
                                   (get-in snapshot [:config :approveAgent :responses])))
                            (is (= [{:paths [[:approveAgent]]
                                     :matchedPath [:approveAgent]
                                     :responseStatus "ok"
                                     :remainingResponses 0}]
                                   (:calls snapshot)))
                            (cleanup!)
                            (done)))))))
          (.catch (fn [err]
                    (cleanup!)
                    (is false (str "Unexpected error: " err))
                    (done)))))))

(deftest parse-json-private-helper-parses-text-json-and-validates-contract-test
  (async done
    (let [assert-call (atom nil)
          response #js {:status 202
                        :text (fn []
                                (resolved-thenable
                                 "{\"status\":\"ok\",\"response\":{\"data\":1}}"))}]
      (with-redefs [validation/validation-enabled? (constantly true)
                    validation/assert-exchange-response!
                    (fn [payload context]
                      (reset! assert-call [payload context]))]
        (-> (@#'hyperopen.api.trading/parse-json! response)
            (.then (fn [parsed]
                     (is (= {:status "ok"
                             :response {:data 1}}
                            parsed))
                     (is (= [parsed {:boundary :api-trading/parse-json}]
                            @assert-call))
                     (done)))
            (.catch (fn [err]
                      (is false (str "Unexpected error: " err))
                      (done))))))))

(deftest nonce-error-response-detects-nonce-specific-errors-test
  (is (true? (@#'hyperopen.api.trading/nonce-error-response?
              {:status "err"
               :error "Nonce too low"})))
  (is (true? (@#'hyperopen.api.trading/nonce-error-response?
              {:status "ok"
               :message "nonce mismatch"})))
  (is (false? (@#'hyperopen.api.trading/nonce-error-response?
               {:status "err"
                :error "   "})))
  (is (false? (@#'hyperopen.api.trading/nonce-error-response?
               {:status "err"
                :error ""})))
  (is (false? (@#'hyperopen.api.trading/nonce-error-response?
               {:status "err"
                :message "   "})))
  (is (false? (@#'hyperopen.api.trading/nonce-error-response?
               {:status "err"
                :error "rate limit exceeded"})))
  (is (false? (@#'hyperopen.api.trading/nonce-error-response?
               {:status "ok"
                :message "wallet missing"}))))

(deftest parse-chain-id-int-and-user-signing-context-fallback-test
  (with-redefs [agent-session/default-signature-chain-id-for-environment
                (fn [is-mainnet]
                  (if is-mainnet
                    "0xa4b1"
                    "0x66eee"))]
    (is (= 42161
           (@#'hyperopen.api.trading/parse-chain-id-int "0xa4b1")))
    (is (= 42161
           (@#'hyperopen.api.trading/parse-chain-id-int "42161")))
    (is (= {:signature-chain-id "0xa4b1"
            :hyperliquid-chain "Mainnet"}
           (@#'hyperopen.api.trading/resolve-user-signing-context
            (atom {:wallet {}}))))
    ;; Rabby rejects typed data whose domain chainId differs from the wallet's
    ;; active chain, so any wallet chain is used as-is.
    (is (= {:signature-chain-id "0x1"
            :hyperliquid-chain "Mainnet"}
           (@#'hyperopen.api.trading/resolve-user-signing-context
            (atom {:wallet {:chain-id "0x1"}}))))
    (is (= {:signature-chain-id "0x2105"
            :hyperliquid-chain "Mainnet"}
           (@#'hyperopen.api.trading/resolve-user-signing-context
            (atom {:wallet {:chain-id 8453}}))))
    (is (= {:signature-chain-id "0x66eee"
            :hyperliquid-chain "Testnet"}
           (@#'hyperopen.api.trading/resolve-user-signing-context
            (atom {:wallet {:chain-id "0x66eee"}}))))))

(deftest post-signed-action-private-helper-includes-optional-fields-test
  (async done
    (let [fetch-call (atom nil)
          assert-call (atom nil)
          restore-fetch! (support/install-fetch-stub!
                          (fn [url opts]
                            (reset! fetch-call [url opts])
                            (js/Promise.resolve #js {:ok true})))
          action {:type "order"
                  :orders []
                  :grouping "na"}
          signature {:r "0x1"
                     :s "0x2"
                     :v 27}]
      (with-redefs [validation/validation-enabled? (constantly true)
                    validation/assert-signed-exchange-payload!
                    (fn [payload context]
                      (reset! assert-call [payload context]))]
        (let [request (@#'hyperopen.api.trading/post-signed-action!
                       action
                       1700000017000
                       signature
                       {:vault-address "0xABCDEF"
                        :expires-after 1700000017999})]
          (.finally
           (.catch
            (.then request
                   (fn [_]
                     (let [[url fetch-opts] @fetch-call
                           payload (support/fetch-body->map fetch-opts)
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
             (restore-fetch!))))))))

(deftest post-signed-action-private-helper-defaults-schedule-cancel-when-debug-simulator-installed-test
  (async done
    (let [fetch-called? (atom false)
          restore-fetch! (support/install-fetch-stub!
                          (fn [_url _opts]
                            (reset! fetch-called? true)
                            (js/Promise.resolve
                             #js {:status 200
                                  :json (fn []
                                          (js/Promise.resolve
                                           #js {:status "network"}))
                                  :text (fn []
                                          (js/Promise.resolve
                                           "{\"status\":\"network\"}"))})))
          action {:type "scheduleCancel"
                  :time 1700000100000}
          signature {:r "0x1"
                     :s "0x2"
                     :v 27}]
      (trading/set-debug-exchange-simulator!
       {:approveAgent {:responses []}})
      (-> (@#'hyperopen.api.trading/post-signed-action!
           action
           1700000007777
           signature)
          (.then (fn [resp]
                   (-> (.json resp)
                       (.then
                        (fn [body]
                          (let [snapshot (trading/debug-exchange-simulator-snapshot)]
                            (is (false? @fetch-called?))
                            (is (= {:status "ok"}
                                   (js->clj body :keywordize-keys true)))
                            (is (= [{:paths [[:signedActions "scheduleCancel"]
                                             [:signedActions :default]]
                                     :matchedPath [:signedActions "scheduleCancel"]
                                     :request {:action action
                                               :nonce 1700000007777
                                               :signature signature}
                                     :responseStatus "ok"
                                     :remainingResponses nil
                                     :defaulted true}]
                                   (:calls snapshot)))
                            (done)))))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
           (fn []
             (trading/clear-debug-exchange-simulator!)
             (restore-fetch!)))))))

;; WS6 — simulator-only transport proof: the encoded spot wire asset id (and the
;; spot reduce-only suppression) survive the real build -> post-signed-action!
;; seam end to end, without any live exchange round-trip.

(deftest spot-order-signed-payload-carries-encoded-asset-id-test
  (async done
    (let [command-context {:active-asset "PURR/USDC"
                           :asset-idx 10000
                           :market {:market-type :spot :szDecimals 0}}
          form {:type :limit :side :buy :size "1" :price "100"}
          {:keys [action]} (commands/build-order-action command-context form)
          signature {:r "0x1" :s "0x2" :v 27}]
      (trading/set-debug-exchange-simulator!
       {:signedActions {"order" {:responses [{:status "ok"}]}}})
      (-> (@#'hyperopen.api.trading/post-signed-action! action 1700000007777 signature)
          (.then (fn [_resp]
                   (let [call (first (:calls (trading/debug-exchange-simulator-snapshot)))]
                     (is (= "order" (get-in call [:request :action :type])))
                     (is (= 10000 (get-in call [:request :action :orders 0 :a])))
                     (is (false? (get-in call [:request :action :orders 0 :r])))
                     (done))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
           (fn []
             (trading/clear-debug-exchange-simulator!)))))))

(deftest spot-twap-signed-payload-carries-encoded-asset-id-and-suppresses-reduce-only-test
  (async done
    (let [command-context {:active-asset "PURR/USDC"
                           :asset-idx 10000
                           :market {:market-type :spot :szDecimals 0}}
          ;; reduce-only true on the form must be forced false for spot.
          form {:type :twap :side :buy :size "10" :reduce-only true :twap {:minutes "30"}}
          {:keys [action]} (commands/build-twap-action command-context form)
          signature {:r "0x1" :s "0x2" :v 27}]
      (trading/set-debug-exchange-simulator!
       {:signedActions {"twapOrder" {:responses [{:status "ok"}]}}})
      (-> (@#'hyperopen.api.trading/post-signed-action! action 1700000007777 signature)
          (.then (fn [_resp]
                   (let [call (first (:calls (trading/debug-exchange-simulator-snapshot)))]
                     (is (= "twapOrder" (get-in call [:request :action :type])))
                     (is (= 10000 (get-in call [:request :action :twap :a])))
                     (is (false? (get-in call [:request :action :twap :r])))
                     (done))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
           (fn []
             (trading/clear-debug-exchange-simulator!)))))))

(deftest post-signed-action-private-helper-preserves-safe-integer-nonce-test
  (async done
    (let [fetch-call (atom nil)
          assert-calls (atom 0)
          restore-fetch! (support/install-fetch-stub!
                          (fn [url opts]
                            (reset! fetch-call [url opts])
                            (js/Promise.resolve #js {:ok true})))
          safe-nonce 9007199254740991
          action {:type "order"
                  :orders []
                  :grouping "na"}
          signature {:r "0x1"
                     :s "0x2"
                     :v 27}]
      (with-redefs [validation/validation-enabled? (constantly false)
                    validation/assert-signed-exchange-payload!
                    (fn [_payload _context]
                      (swap! assert-calls inc))]
        (let [request (@#'hyperopen.api.trading/post-signed-action!
                       action
                       safe-nonce
                       signature)]
          (.finally
           (.catch
            (.then request
                   (fn [_]
                     (let [[url fetch-opts] @fetch-call
                           payload (support/fetch-body->map fetch-opts)]
                       (is (= trading/exchange-url url))
                       (is (= safe-nonce (:nonce payload)))
                       (is (= 0 @assert-calls))
                       (done))))
            (fn [err]
              (is false (str "Unexpected error: " err))
              (done)))
           (fn []
             (restore-fetch!))))))))

(deftest approve-agent-prefers-debug-exchange-simulator-over-fetch-test
  (async done
    (let [fetch-called? (atom false)
          original-sign signing/sign-approve-agent-action!
          restore-fetch! (support/install-fetch-stub!
                          (fn [_url _opts]
                            (reset! fetch-called? true)
                                          (js/Promise.resolve #js {:ok true})))
          cleanup! (fn []
                     (trading/clear-debug-exchange-simulator!)
                     (set! signing/sign-approve-agent-action! original-sign)
                     (restore-fetch!))
          action {:type "approveAgent"
                  :agentAddress "0x9999999999999999999999999999999999999999"
                  :nonce 1700000005555
                  :hyperliquidChain "Mainnet"
                  :signatureChainId "0x66eee"}]
      (set! signing/sign-approve-agent-action!
            (fn [_address _action]
              (js/Promise.resolve
               (clj->js {:r "0x1"
                         :s "0x2"
                         :v 27}))))
      (trading/set-debug-exchange-simulator!
       {:approveAgent {:responses [{:status "ok"
                                    :response {:source "simulator"}}]}})
      (-> (trading/approve-agent! (atom {}) support/owner-address action)
          (.then (fn [resp]
                   (-> (.json resp)
                       (.then (fn [body]
                                (is (false? @fetch-called?))
                                (is (= {:status "ok"
                                        :response {:source "simulator"}}
                                       (js->clj body :keywordize-keys true)))
                                (cleanup!)
                                (done))))))
          (.catch (fn [err]
                    (cleanup!)
                    (is false (str "Unexpected error: " err))
                    (done)))))))
