(ns hyperopen.api.trading.internal-seams-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.trading :as trading]
            [hyperopen.api.trading.test-support :as support]
            [hyperopen.platform :as platform]
            [hyperopen.schema.contracts :as contracts]
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
      (is (= payload
             (trading/debug-exchange-simulator-snapshot)))
      (is (true? (trading/clear-debug-exchange-simulator!)))
      (is (nil? (trading/debug-exchange-simulator-snapshot))))
    (finally
      (trading/clear-debug-exchange-simulator!))))

(deftest parse-json-private-helper-parses-text-json-and-validates-contract-test
  (async done
    (let [assert-call (atom nil)
          response #js {:status 202
                        :text (fn []
                                (resolved-thenable
                                 "{\"status\":\"ok\",\"response\":{\"data\":1}}"))}]
      (with-redefs [contracts/validation-enabled? (constantly true)
                    contracts/assert-exchange-response!
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
            (atom {:wallet {}}))))))

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
      (with-redefs [contracts/validation-enabled? (constantly true)
                    contracts/assert-signed-exchange-payload!
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
      (with-redefs [contracts/validation-enabled? (constantly false)
                    contracts/assert-signed-exchange-payload!
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
                                (done))))))
          (.catch (fn [err]
                    (is false (str "Unexpected error: " err))
                    (done)))
          (.finally
           (fn []
             (trading/clear-debug-exchange-simulator!)
             (set! signing/sign-approve-agent-action! original-sign)
             (restore-fetch!)))))))
