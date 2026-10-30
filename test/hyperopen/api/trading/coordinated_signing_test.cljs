(ns hyperopen.api.trading.coordinated-signing-test
  "Shared signer nonce reservation, network agreement and delivery tracking
  (HO-SPEC-014 v2.1 sections 3.2, 8.1-8.4; VR-09, VR-10, VR-13)."
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.trading :as trading]
            [hyperopen.api.trading.delivery :as delivery]
            [hyperopen.api.trading.nonce-coordinator :as nonce-coordinator]
            [hyperopen.api.trading.test-support :as support]
            [hyperopen.platform]
            [hyperopen.platform.locks :as locks]
            [hyperopen.test-support.api-stubs :as api-stubs]
            [hyperopen.utils.hl-signing :as signing]
            [hyperopen.wallet.agent-session :as agent-session]))

(def ^:private agent-address "0x8fd379246834eac74b8419ffda202cf8051f7a03")
(def ^:private order-action {:type "order" :orders [] :grouping "na"})

(defn- with-agent
  "Install a ready agent session, a signing stub and a fetch stub for the
  duration of the promise returned by `f`."
  [{:keys [cursor fetch]} f]
  (let [sign-calls (atom [])
        fetch-calls (atom [])
        orig-load agent-session/load-agent-session-by-mode
        orig-persist agent-session/persist-agent-session-by-mode!
        orig-sign signing/sign-l1-action-with-private-key!
        orig-now hyperopen.platform/now-ms
        clock (atom (.now js/Date))
        restore-fetch! (support/install-fetch-stub!
                        (fn [url opts]
                          (swap! fetch-calls conj [url opts])
                          ((or fetch (fn [] (js/Promise.resolve (support/json-response {:status "ok"})))))))]
    (nonce-coordinator/reset-memory-cursors!)
    (locks/reset-in-tab-state!)
    ;; An advancing clock owned by this test, independent of other suites.
    (set! hyperopen.platform/now-ms (fn [] (swap! clock inc)))
    (set! agent-session/load-agent-session-by-mode
          (fn [_ _] {:agent-address agent-address
                     :private-key "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                     :nonce-cursor cursor}))
    (set! agent-session/persist-agent-session-by-mode! (fn [& _] true))
    (set! signing/sign-l1-action-with-private-key! (api-stubs/signing-stub sign-calls))
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (f {:store (support/ready-agent-store cursor)
                           :sign-calls sign-calls :fetch-calls fetch-calls})))
        (.finally (fn []
                    (set! agent-session/load-agent-session-by-mode orig-load)
                    (set! agent-session/persist-agent-session-by-mode! orig-persist)
                    (set! signing/sign-l1-action-with-private-key! orig-sign)
                    (set! hyperopen.platform/now-ms orig-now)
                    (restore-fetch!))))))

(defn- live-opts-now [] {:expires-after (+ (hyperopen.platform/now-ms) 30000) :allow-in-tab-coordination? true
                         :allow-unrecorded-attempt? true})
(def ^:private live-opts (live-opts-now))

(defn- payload-nonce [[_url opts]] (:nonce (support/fetch-body->map opts)))

(deftest testnet-signatures-are-refused-before-signing-test
  (async done
    (-> (with-agent {:cursor 1}
          (fn [{:keys [store sign-calls fetch-calls]}]
            (-> (trading/submit-order! store support/owner-address order-action {:is-mainnet false})
                (.then (fn [_] (is false "must reject"))
                       (fn [e]
                         (is (re-find #"testnet" (.-message e)))
                         (is (empty? @sign-calls) "nothing was signed")
                         (is (empty? @fetch-calls) "nothing was sent"))))))
        (.finally done))))

(deftest concurrent-producers-never-share-a-nonce-test
  ;; VR-10: a manual order and two other producers on the same agent at once.
  (async done
    (let [cursor (+ (hyperopen.platform/now-ms) 600000)]
      (-> (with-agent {:cursor cursor}
            (fn [{:keys [store fetch-calls]}]
              (-> (js/Promise.all
                   #js [(trading/submit-order! store support/owner-address order-action {:max-nonce-retries 0})
                        (trading/cancel-order! store support/owner-address {:type "cancel" :cancels []}
                                               {:max-nonce-retries 0})
                        (trading/submit-order! store support/owner-address order-action
                                               {:vault-address "0xsub" :max-nonce-retries 0})])
                  (.then (fn [_]
                           (let [nonces (mapv payload-nonce @fetch-calls)]
                             (is (= 3 (count (distinct nonces))) (pr-str nonces))
                             (is (every? #(> % cursor) nonces)
                                 "every nonce is above the session cursor, even far ahead of the clock")))))))
          (.catch (fn [e] (is false (str e))))
          (.finally done)))))

(deftest delivery-response-and-attempt-ordering-test
  (async done
    (let [events (atom [])]
      (-> (with-agent {:cursor 1
                       :fetch (fn [] (swap! events conj :http)
                                (js/Promise.resolve (support/json-response
                                                     {:status "ok" :response {:type "order" :data {:statuses []}}})))}
            (fn [{:keys [store]}]
              (-> (delivery/sign-and-post-with-delivery!
                   store support/owner-address order-action
                   {:expires-after (+ (hyperopen.platform/now-ms) 30000)
                    :allow-in-tab-coordination? true
                    :before-send! (fn [ctx] (swap! events conj [:guard (number? (:nonce ctx))]) true)
                    :on-send-attempt! (fn [_] (swap! events conj :attempt-committed) (js/Promise.resolve true))})
                  (.then (fn [r]
                           (is (= :response (:delivery r)))
                           (is (= "ok" (get-in r [:body :status])))
                           (is (number? (:nonce r)))
                           (is (true? (:attempt-recorded? r)))
                           (is (= [:attempt-committed [:guard true] :http] @events)
                               "spec 8.1: committed attempt record (7), final guard (8), then HTTP"))))))
          (.catch (fn [e] (is false (str e))))
          (.finally done)))))

(deftest delivery-not-sent-only-when-provably-before-http-test
  (async done
    (-> (with-agent {:cursor 1}
          (fn [{:keys [store fetch-calls]}]
            (-> (delivery/sign-and-post-with-delivery!
                 store support/owner-address order-action {:before-send! (fn [_] false) :expires-after (+ (hyperopen.platform/now-ms) 30000) :allow-in-tab-coordination? true
                  :on-send-attempt! (fn [_] (js/Promise.resolve true))})
                (.then (fn [r]
                         (is (= [:not-sent :final-guard] [(:delivery r) (:phase r)]))
                         (is (true? (:attempt-recorded? r)) "the caller must record the proven pre-send outcome")
                         (is (empty? @fetch-calls))))
                (.then (fn [_]
                         (delivery/sign-and-post-with-delivery!
                          store support/owner-address order-action
                          {:on-send-attempt! (fn [_] (js/Promise.reject (js/Error. "QuotaExceededError")))
                           :expires-after (+ (hyperopen.platform/now-ms) 30000) :allow-in-tab-coordination? true})))
                (.then (fn [r]
                         (is (= [:not-sent :send-attempt-record] [(:delivery r) (:phase r)]))
                         (is (re-find #"Quota" (:error r)))
                         (is (empty? @fetch-calls) "a failed attempt commit never sends")))
                (.then (fn [_]
                         (delivery/sign-and-post-with-delivery!
                          store support/owner-address order-action {:is-mainnet false :expires-after (+ (hyperopen.platform/now-ms) 30000)
                                                    :allow-unrecorded-attempt? true})))
                (.then (fn [r] (is (= [:not-sent :network-mismatch] [(:delivery r) (:phase r)])))))))
        (.catch (fn [e] (is false (str e))))
        (.finally done))))

(deftest delivery-unknown-after-http-invocation-test
  (async done
    (-> (with-agent {:cursor 1 :fetch (fn [] (js/Promise.reject (js/Error. "network timeout")))}
          (fn [{:keys [store]}]
            (-> (delivery/sign-and-post-with-delivery! store support/owner-address order-action live-opts)
                (.then (fn [r]
                         (is (= :unknown (:delivery r)) "a timeout after send is NOT proof of non-placement")
                         (is (number? (:nonce r))))))))
        (.then (fn [_]
                 (with-agent {:cursor 1 :fetch (fn [] (js/Promise.resolve
                                                       #js {:ok false :status 502
                                                            :text (fn [] (js/Promise.resolve "<html>Bad gateway</html>"))}))}
                   (fn [{:keys [store]}]
                     (-> (delivery/sign-and-post-with-delivery! store support/owner-address order-action live-opts)
                         (.then (fn [r]
                                  (is (= :unknown (:delivery r))
                                      "an unreadable body is unknown, never a venue rejection"))))))))
        (.catch (fn [e] (is false (str e))))
        (.finally done))))

(deftest delivery-preconditions-are-not-sent-test
  (async done
    (-> (with-agent {:cursor 1}
          (fn [{:keys [store fetch-calls]}]
            (-> (delivery/sign-and-post-with-delivery! store support/owner-address order-action
                                                       {:allow-in-tab-coordination? true})
                (.then (fn [r] (is (= [:not-sent :invalid-options] [(:delivery r) (:phase r)])
                                   "spec 8.3: every attempt carries a deadline")))
                (.then (fn [_] (delivery/sign-and-post-with-delivery!
                                store support/owner-address order-action
                                {:expires-after (- (hyperopen.platform/now-ms) 1) :allow-in-tab-coordination? true
                                 :allow-unrecorded-attempt? true})))
                (.then (fn [r] (is (= [:not-sent :deadline-elapsed] [(:delivery r) (:phase r)]))))
                (.then (fn [_] (delivery/sign-and-post-with-delivery!
                                store support/owner-address order-action
                                {:expires-after (+ (hyperopen.platform/now-ms) 30000) :allow-unrecorded-attempt? true})))
                (.then (fn [r]
                         (is (= [:not-sent :coordination-unavailable] [(:delivery r) (:phase r)])
                             "without Web Locks a Vol Range send is refused")
                         (is (empty? @fetch-calls)))))))
        (.catch (fn [e] (is false (str e))))
        (.finally done))))

(deftest delivery-timeout-and-non-2xx-are-unknown-test
  (async done
    (-> (with-agent {:cursor 1 :fetch (fn [] (js/Promise. (fn [_ _] nil)))}
          (fn [{:keys [store]}]
            (-> (delivery/sign-and-post-with-delivery! store support/owner-address order-action
                                                       (assoc (live-opts-now) :timeout-ms 20))
                (.then (fn [r] (is (= :unknown (:delivery r)) "a stalled request still settles"))))))
        (.then (fn [_]
                 (with-agent {:cursor 1 :fetch (fn [] (js/Promise.resolve
                                                       #js {:ok false :status 503
                                                            :text (fn [] (js/Promise.resolve
                                                                          "{\"status\":\"err\",\"response\":\"busy\"}"))}))}
                   (fn [{:keys [store]}]
                     (-> (delivery/sign-and-post-with-delivery! store support/owner-address order-action (live-opts-now))
                         (.then (fn [r]
                                  (is (= :unknown (:delivery r))
                                      "a proxy's JSON on a 5xx is never a venue rejection")
                                  (is (= 503 (:http-status r))))))))))
        (.catch (fn [e] (is false (str e))))
        (.finally done))))

(deftest signing-and-cancel-survive-storage-failure-test
  ;; Review B1 / spec 7.4: identified cancellation must not depend on storage.
  (async done
    (let [orig-get hyperopen.platform/local-storage-get
          orig-set hyperopen.platform/local-storage-set!]
      (set! hyperopen.platform/local-storage-get (fn [_] (throw (js/Error. "SecurityError"))))
      (set! hyperopen.platform/local-storage-set! (fn [_ _] (throw (js/Error. "QuotaExceededError"))))
      (-> (with-agent {:cursor 1}
            (fn [{:keys [store fetch-calls]}]
              (-> (trading/cancel-order! store support/owner-address {:type "cancel" :cancels []}
                                         {:max-nonce-retries 0})
                  (.then (fn [_] (trading/submit-order! store support/owner-address order-action
                                                        {:max-nonce-retries 0})))
                  (.then (fn [_] (is (= 2 (count @fetch-calls)) "both were signed and sent"))))))
          (.catch (fn [e] (is false (str e))))
          (.finally (fn []
                      (set! hyperopen.platform/local-storage-get orig-get)
                      (set! hyperopen.platform/local-storage-set! orig-set)
                      (done)))))))

(deftest delivery-requires-an-attempt-record-and-bounds-the-pre-send-phase-test
  (async done
    (-> (with-agent {:cursor 1}
          (fn [{:keys [store fetch-calls]}]
            (-> (delivery/sign-and-post-with-delivery!
                 store support/owner-address order-action
                 {:expires-after (+ (hyperopen.platform/now-ms) 30000) :allow-in-tab-coordination? true})
                (.then (fn [r] (is (= [:not-sent :invalid-options] [(:delivery r) (:phase r)])
                                   "spec 8.1 step 7 is mandatory")))
                (.then (fn [_]
                         (delivery/sign-and-post-with-delivery!
                          store support/owner-address order-action
                          {:expires-after (+ (hyperopen.platform/now-ms) 30000) :allow-in-tab-coordination? true
                           :pre-send-timeout-ms 20
                           :on-send-attempt! (fn [_] (js/Promise. (fn [_ _] nil)))})))
                (.then (fn [r]
                         (is (= [:not-sent :pre-send-timeout] [(:delivery r) (:phase r)]))
                         (is (empty? @fetch-calls) "an abandoned pre-send phase can never send"))))))
        (.catch (fn [e] (is false (str e))))
        (.finally done))))
