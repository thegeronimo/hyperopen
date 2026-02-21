(ns hyperopen.api.trading-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.trading :as trading]
            [hyperopen.api.trading.test-support :as support]
            [hyperopen.wallet.agent-session :as agent-session]))

(deftest build-cancel-order-request-public-seam-produces-cancel-action-test
  (is (= {:action {:type "cancel"
                   :cancels [{:a 12 :o 307891000622}]}}
         (trading/build-cancel-order-request
          {:asset-contexts {}
           :asset-selector {:market-by-key {}}}
          {:order {:assetIdx "12"
                   :oid "307891000622"}}))))

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
