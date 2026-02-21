(ns hyperopen.api.trading.approve-agent-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.trading :as trading]
            [hyperopen.api.trading.test-support :as support]
            [hyperopen.utils.hl-signing :as signing]))

(deftest approve-agent-signs-and-posts-exchange-payload-test
  (async done
    (let [signed-payload (atom nil)
          fetch-call (atom nil)
          original-sign signing/sign-approve-agent-action!
          restore-fetch! (support/install-fetch-stub!
                          (fn [url opts]
                            (reset! fetch-call [url opts])
                            (js/Promise.resolve #js {:ok true})))
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
      (-> (trading/approve-agent! (atom {}) support/owner-address action)
          (.then (fn [_]
                   (let [[signed-address signed-action] @signed-payload
                         [url fetch-opts] @fetch-call
                         parsed-body (support/fetch-body->map fetch-opts)]
                     (is (= support/owner-address signed-address))
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
             (restore-fetch!)))))))
