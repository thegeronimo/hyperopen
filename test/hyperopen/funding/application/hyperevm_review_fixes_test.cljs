(ns hyperopen.funding.application.hyperevm-review-fixes-test
  "Submit-effect rules the Milestone 9 review added: a HyperCore -> HyperEVM
   send voids its token's HyperEVM bridge reading until a later read lands
   (so a second move, from a reopened modal while the first POST is in
   flight, is checked against a balance that shows the first), and a run's
   outcome is toasted only once the modal no longer shows it, with grouped
   amounts."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.funding.application.submit-effects :as effects]
            [hyperopen.funding.domain.evm-transfer-preview :as evm-preview]
            [hyperopen.funding.test-support.hyperevm-effects :as h]
            [hyperopen.funding.test-support.hyperevm-transfer :as support]
            [hyperopen.test-support.async :as async-support]))

(defn- deferred-send
  "A `submit-send-asset!` that answers only when the returned `release!` is
   called."
  []
  (let [release (atom nil)]
    {:release! (fn [resp] (@release resp))
     :submit-send-asset! (fn [& _]
                           (js/Promise. (fn [resolve _] (reset! release resolve))))}))

(deftest a-core-to-evm-send-voids-the-bridge-reading-until-a-later-read-test
  (async done
    (let [store (h/store-for (assoc h/purr-to-evm :amount-input "1000"))
          {:keys [release! submit-send-asset!]} (deferred-send)
          {:keys [deps]} (h/harness store (h/core->evm @store support/purr-index "1000")
                                    {:submit-send-asset! submit-send-asset!})
          sent-at (fn [] (get-in @store [:hyperevm :bridge :core->evm-sent-at-ms support/purr-index]))
          second-draft (fn []
                         (evm-preview/evm-transfer-preview
                          @store
                          (assoc (get-in @store [:funding-ui :modal]) :transfer-evm nil)
                          (+ support/now-ms 1000)))
          submitted (effects/api-submit-funding-transfer! deps)]
      (is (= support/now-ms (sent-at)) "stamped before the POST answers")
      (is (= "Checking the bridge balance…"
             (get-in (second-draft) [:blocked :message]))
          "a second move while the first POST is in flight waits for a fresh read")
      (release! {:status "ok"})
      (-> submitted
          (.then (fn [_]
                   (is (= support/now-ms (sent-at)) "and again once HyperCore answered")
                   (swap! store assoc-in [:hyperevm :bridge :evm-system-read-at-ms support/purr-index]
                          (+ support/now-ms 6000))
                   (is (nil? (get-in (evm-preview/evm-transfer-preview
                                      @store
                                      (assoc (get-in @store [:funding-ui :modal]) :transfer-evm nil)
                                      (+ support/now-ms 7000))
                                     [:blocked]))
                       "a read requested after the send settled lifts the block")
                   (done)))
          (.catch (async-support/unexpected-error done))))))

(deftest an-outcome-is-toasted-only-once-the-modal-stopped-showing-it-test
  (async done
    (let [store (h/store-for (assoc h/purr-to-evm :amount-input "1000"))
          {:keys [release! submit-send-asset!]} (deferred-send)
          {:keys [deps toasts]} (h/harness store (h/core->evm @store support/purr-index "1000")
                                           {:submit-send-asset! submit-send-asset!})
          submitted (effects/api-submit-funding-transfer! deps)]
      ;; The user closes the modal while the move is in flight.
      (swap! store assoc-in [:funding-ui :modal :open?] false)
      (release! {:status "ok"})
      (-> submitted
          (.then (fn [_]
                   (is (= [[:success "Sent 1,000 PURR to HyperEVM."]] @toasts)
                       "the toast carries the result, grouped like the modal's own copy")
                   (done)))
          (.catch (async-support/unexpected-error done))))))
