(ns hyperopen.core-bootstrap.order-effects.position-margin-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.account.context :as account-context]
            [hyperopen.account.history.position-margin :as position-margin]
            [hyperopen.api.trading :as trading-api]
            [hyperopen.core-bootstrap.order-effects.test-support :as support]
            [hyperopen.order.effects :as order-effects]))

(deftest api-submit-position-margin-effect-validates-preconditions-test
  (let [dispatched (atom [])
        deps (support/position-submit-deps dispatched)
        spectate-mode-store (atom (support/base-position-store
                                   :margin-modal
                                   {:account-context {:spectate-mode {:active? true
                                                                      :address "0x1234567890abcdef1234567890abcdef12345678"}}}))
        missing-wallet-store (atom (support/base-position-store
                                    :margin-modal
                                    {:wallet {:agent {:status :ready}}}))
        not-ready-store (atom (support/base-position-store
                               :margin-modal
                               {:wallet {:address "0xabc"
                                         :agent {:status :not-ready}}}))]
    (order-effects/api-submit-position-margin deps nil spectate-mode-store {:action {:type "updateIsolatedMargin"}})
    (is (= false (get-in @spectate-mode-store [:positions-ui :margin-modal :submitting?])))
    (is (= account-context/spectate-mode-read-only-message
           (get-in @spectate-mode-store [:positions-ui :margin-modal :error])))
    (is (= {:kind :error
            :message account-context/spectate-mode-read-only-message}
           (get-in @spectate-mode-store [:ui :toast])))

    (order-effects/api-submit-position-margin deps nil missing-wallet-store {:action {:type "updateIsolatedMargin"}})
    (is (= false (get-in @missing-wallet-store [:positions-ui :margin-modal :submitting?])))
    (is (= "Connect your wallet before updating margin."
           (get-in @missing-wallet-store [:positions-ui :margin-modal :error])))
    (is (= {:kind :error
            :message "Connect your wallet before updating margin."}
           (get-in @missing-wallet-store [:ui :toast])))

    (order-effects/api-submit-position-margin deps nil not-ready-store {:action {:type "updateIsolatedMargin"}})
    (is (= false (get-in @not-ready-store [:positions-ui :margin-modal :submitting?])))
    (is (= "Enable trading before updating margin."
           (get-in @not-ready-store [:positions-ui :margin-modal :error])))
    (is (= {:kind :error
            :message "Enable trading before updating margin."}
           (get-in @not-ready-store [:ui :toast])))
    (is (= [] @dispatched))))

(deftest api-submit-position-margin-effect-success-resets-modal-and-refreshes-surfaces-test
  (async done
    (let [store (atom (support/base-position-store
                       :margin-modal
                       {:positions-ui {:margin-modal {:submitting? true
                                                      :error "old-error"}}}))
          dispatched (atom [])
          refresh-calls (atom [])
          clearinghouse-calls (atom [])
          deps (support/position-submit-deps dispatched)
          restore-refresh-mocks! (support/install-account-refresh-mocks! refresh-calls clearinghouse-calls [])
          original-submit-order trading-api/submit-order!]
      (set! trading-api/submit-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "ok"})))
      (order-effects/api-submit-position-margin deps nil store {:action {:type "updateIsolatedMargin"}})
      (js/setTimeout
       (fn []
         (try
           (is (= (position-margin/default-modal-state)
                  (get-in @store [:positions-ui :margin-modal])))
           (is (= {:kind :success
                   :message "Margin updated."}
                  (get-in @store [:ui :toast])))
           (is (= [[[:actions/refresh-order-history]]]
                  @dispatched))
           (is (= 1 (count @refresh-calls)))
           (is (= 1 (count @clearinghouse-calls)))
           (finally
             (restore-refresh-mocks!)
             (set! trading-api/submit-order! original-submit-order)
             (done))))
       0))))

(deftest api-submit-position-margin-effect-surfaces-exchange-errors-test
  (async done
    (let [store (atom (support/base-position-store
                       :margin-modal
                       {:positions-ui {:margin-modal {:submitting? true
                                                      :error nil}}}))
          deps (support/position-submit-deps (atom []))
          original-submit-order trading-api/submit-order!]
      (set! trading-api/submit-order!
            (fn [_store _address _action]
              (js/Promise.resolve {:status "error"
                                   :response {:type "error"
                                              :data "margin rejected"}})))
      (order-effects/api-submit-position-margin deps nil store {:action {:type "updateIsolatedMargin"}})
      (js/setTimeout
       (fn []
         (try
           (is (= false (get-in @store [:positions-ui :margin-modal :submitting?])))
           (is (= "margin rejected" (get-in @store [:positions-ui :margin-modal :error])))
           (is (= {:kind :error
                   :message "Margin update failed: margin rejected"}
                  (get-in @store [:ui :toast])))
           (finally
             (set! trading-api/submit-order! original-submit-order)
             (done))))
       0))))

(deftest api-submit-position-margin-effect-handles-runtime-errors-test
  (async done
    (let [store (atom (support/base-position-store
                       :margin-modal
                       {:positions-ui {:margin-modal {:submitting? true
                                                      :error nil}}}))
          deps (support/position-submit-deps (atom []))
          original-submit-order trading-api/submit-order!]
      (set! trading-api/submit-order!
            (fn [_store _address _action]
              (js/Promise.reject (js/Error. "transport failure"))))
      (order-effects/api-submit-position-margin deps nil store {:action {:type "updateIsolatedMargin"}})
      (js/setTimeout
       (fn []
         (try
           (is (= false (get-in @store [:positions-ui :margin-modal :submitting?])))
           (is (= "transport failure" (get-in @store [:positions-ui :margin-modal :error])))
           (is (= {:kind :error
                   :message "Margin update failed: transport failure"}
                  (get-in @store [:ui :toast])))
           (finally
             (set! trading-api/submit-order! original-submit-order)
             (done))))
       0))))
