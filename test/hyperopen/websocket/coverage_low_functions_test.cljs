(ns hyperopen.websocket.coverage-low-functions-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api.market-metadata.facade :as market-metadata]
            [hyperopen.api.market-metadata.perp-dexs :as perp-dexs]
            [hyperopen.platform :as platform]
            [hyperopen.schema.contracts :as contracts]
            [hyperopen.state.trading.order-form-key-policy :as key-policy]
            [hyperopen.telemetry :as telemetry]))

(defn- reset-telemetry-state!
  []
  (reset! @#'hyperopen.telemetry/event-log [])
  (reset! @#'hyperopen.telemetry/event-seq 0))

(deftest ws-platform-low-function-coverage-smoke-test
  (let [orig-confirm (.-confirm js/globalThis)
        orig-set-timeout (.-setTimeout js/globalThis)
        orig-set-interval (.-setInterval js/globalThis)
        orig-clear-timeout (.-clearTimeout js/globalThis)
        orig-clear-interval (.-clearInterval js/globalThis)
        calls (atom [])]
    (try
      (set! (.-confirm js/globalThis) (fn [msg]
                                        (swap! calls conj [:confirm msg])
                                        true))
      (set! (.-setTimeout js/globalThis) (fn [f ms]
                                           (swap! calls conj [:set-timeout ms])
                                           (when (fn? f) (f))
                                           :timeout-id))
      (set! (.-setInterval js/globalThis) (fn [f ms]
                                            (swap! calls conj [:set-interval ms])
                                            (when (fn? f) (f))
                                            :interval-id))
      (set! (.-clearTimeout js/globalThis) (fn [id]
                                             (swap! calls conj [:clear-timeout id])
                                             :cleared-timeout))
      (set! (.-clearInterval js/globalThis) (fn [id]
                                              (swap! calls conj [:clear-interval id])
                                              :cleared-interval))

      (is (number? (platform/now-ms)))
      (is (number? (platform/random-value)))
      (is (true? (platform/confirm! "ws confirm")))
      (is (= :timeout-id (platform/set-timeout! (fn [] nil) 5)))
      (is (= :interval-id (platform/set-interval! (fn [] nil) 7)))
      (is (= :cleared-timeout (platform/clear-timeout! :timeout-id)))
      (is (= :cleared-interval (platform/clear-interval! :interval-id)))
      (platform/local-storage-set! "hyperopen-ws" "1")
      (platform/local-storage-get "hyperopen-ws")
      (platform/local-storage-remove! "hyperopen-ws")
      (let [orig-queue-microtask (.-queueMicrotask js/globalThis)
            orig-request-animation-frame (.-requestAnimationFrame js/globalThis)]
        (try
          (set! (.-queueMicrotask js/globalThis) nil)
          (set! (.-requestAnimationFrame js/globalThis) nil)
          (with-redefs [hyperopen.platform/set-timeout! (fn [f _]
                                                          (f)
                                                          :fallback)]
            (is (= :fallback (platform/queue-microtask! (fn [] nil))))
            (is (= :fallback (platform/request-animation-frame! (fn [_] nil)))))
          (finally
            (set! (.-queueMicrotask js/globalThis) orig-queue-microtask)
            (set! (.-requestAnimationFrame js/globalThis) orig-request-animation-frame))))
      (is (seq @calls))
      (finally
        (set! (.-confirm js/globalThis) orig-confirm)
        (set! (.-setTimeout js/globalThis) orig-set-timeout)
        (set! (.-setInterval js/globalThis) orig-set-interval)
        (set! (.-clearTimeout js/globalThis) orig-clear-timeout)
        (set! (.-clearInterval js/globalThis) orig-clear-interval)))))

(deftest ws-telemetry-low-function-coverage-smoke-test
  (reset-telemetry-state!)
  (with-redefs [hyperopen.telemetry/dev-enabled? (constantly true)
                hyperopen.platform/now-ms (constantly 777)]
    (telemetry/clear-events!)
    (is (= [] (telemetry/events)))
    (is (= :ws/event (:event (telemetry/emit! :ws/event {:phase :ws})) ))
    (telemetry/log! "ws" :coverage {:ok true})
    (is (= 2 (count (telemetry/events))))
    (is (string? (telemetry/events-json)))
    (telemetry/clear-events!)
    (is (= [] (telemetry/events))))
  (with-redefs [hyperopen.telemetry/dev-enabled? (constantly false)]
    (is (nil? (telemetry/emit! :disabled {})))))

(deftest ws-schema-contracts-low-function-coverage-smoke-test
  (let [ctx {:suite :ws}
        valid-state {:active-asset nil
                     :active-market nil
                     :asset-selector {:markets []
                                      :market-by-key {}
                                      :favorites #{}
                                      :loaded-icons #{}
                                      :missing-icons #{}}
                     :wallet {:agent {}}
                     :websocket {}
                     :websocket-ui {}
                     :router {:path "/"}
                     :order-form {}
                     :order-form-ui {:pro-order-type-dropdown-open? false
                                     :margin-mode-dropdown-open? false
                                     :leverage-popover-open? false
                                     :size-unit-dropdown-open? false
                                     :tpsl-unit-dropdown-open? false
                                     :tif-dropdown-open? false
                                     :price-input-focused? false
                                     :tpsl-panel-open? false
                                     :entry-mode :limit
                                     :ui-leverage 20
                                     :leverage-draft 20
                                     :margin-mode :cross
                                     :size-input-mode :quote
                                     :size-input-source :manual
                                     :size-display ""}
                     :order-form-runtime {:submitting? false
                                          :error nil}}
        signed {:action {:type "order"}
                :nonce 1
                :signature {:r "0x1" :s "0x2" :v 27}}]
    (is (set? (contracts/contracted-action-ids)))
    (is (set? (contracts/contracted-effect-ids)))
    (is (set? (contracts/action-ids-using-any-args)))

    (is (= [[:wallet :address] "0xabc"]
           (contracts/assert-action-args! :actions/update-order-form
                                          [[:wallet :address] "0xabc"]
                                          ctx)))
    (is (= [12]
           (contracts/assert-action-args! :actions/next-order-history-page
                                          [12]
                                          ctx)))
    (is (thrown-with-msg?
         js/Error
         #"action payload"
         (contracts/assert-action-args! :actions/next-order-history-page
                                        ["12"]
                                        ctx)))

    (is (= [12]
           (contracts/assert-effect-args! :effects/api-fetch-user-funding-history
                                          [12]
                                          ctx)))
    (is (= [:interval :1m :bars 3]
           (contracts/assert-effect-args! :effects/fetch-candle-snapshot
                                          [:interval :1m :bars 3]
                                          ctx)))
    (is (= []
           (contracts/assert-effect-args! :effects/fetch-asset-selector-markets [] ctx)))

    (is (= [:effects/connect-wallet]
           (contracts/assert-effect-call! [:effects/connect-wallet]
                                          ctx)))
    (is (= [[:effects/connect-wallet]
            [:effects/disconnect-wallet]]
           (contracts/assert-emitted-effects! [[:effects/connect-wallet]
                                               [:effects/disconnect-wallet]]
                                              ctx)))

    (is (= valid-state (contracts/assert-app-state! valid-state ctx)))
    (is (= signed (contracts/assert-signed-exchange-payload! signed ctx)))))

(deftest ws-market-metadata-perp-dexs-normalization-coverage-test
  (let [canonical (perp-dexs/normalize-perp-dex-payload
                   {:dex-names ["dex-a"]
                    :fee-config-by-name {"dex-a" {:deployer-fee-scale 0.2}}})
        legacy (perp-dexs/normalize-perp-dex-payload
                {:perp-dexs ["dex-b"]
                 :perp-dex-fee-config-by-name {"dex-b" {:deployer-fee-scale 0.3}}})
        sequential (perp-dexs/normalize-perp-dex-payload
                    ["dex-c"
                     {:name "dex-d" :deployerFeeScale "0.5"}
                     {:name "dex-e" :deployer-fee-scale 0.75}
                     {:name "dex-f" :deployerFeeScale "nan-value"}
                     {:name ""}
                     {:foo "bar"}
                     42
                     nil])]
    (is (= {:dex-names ["dex-a"]
            :fee-config-by-name {"dex-a" {:deployer-fee-scale 0.2}}}
           canonical))
    (is (= {:dex-names ["dex-b"]
            :fee-config-by-name {"dex-b" {:deployer-fee-scale 0.3}}}
           legacy))
    (is (= ["dex-c" "dex-d" "dex-e" "dex-f"] (:dex-names sequential)))
    (is (= {"dex-d" {:deployer-fee-scale 0.5}
            "dex-e" {:deployer-fee-scale 0.75}}
           (:fee-config-by-name sequential)))
    (is (= {:dex-names []
            :fee-config-by-name {}}
           (perp-dexs/normalize-perp-dex-payload :unsupported)))
    (is (= ["dex-a"] (perp-dexs/payload->dex-names {:dex-names ["dex-a"]})))
    (is (= [] (perp-dexs/payload->dex-names nil)))))

(deftest ws-market-metadata-facade-success-coverage-test
  (async done
    (let [store (atom {})
          fetch-calls (atom [])
          ensure-calls (atom [])
          ensure-names-calls (atom [])
          fetch-payload [{:name "dex-a" :deployerFeeScale "0.25"}
                         "dex-b"
                         nil]
          ensure-payload {:dex-names ["dex-c"]}
          deps-for-fetch {:store store
                          :log-fn (fn [& _] nil)
                          :request-perp-dexs! (fn [opts]
                                                (swap! fetch-calls conj opts)
                                                (js/Promise.resolve fetch-payload))
                          :apply-perp-dexs-success (fn [state projected]
                                                     (assoc state :fetched projected))
                          :apply-perp-dexs-error (fn [state err]
                                                   (assoc state :fetch-error (.-message err)))}
          deps-for-ensure {:store store
                           :ensure-perp-dexs-data! (fn [store* opts]
                                                     (swap! ensure-calls conj [store* opts])
                                                     (js/Promise.resolve ensure-payload))
                           :apply-perp-dexs-success (fn [state projected]
                                                      (assoc state :ensured projected))
                           :apply-perp-dexs-error (fn [state err]
                                                    (assoc state :ensure-error (.-message err)))}
          deps-for-names {:ensure-perp-dexs-data! (fn [opts]
                                                    (swap! ensure-names-calls conj opts)
                                                    (js/Promise.resolve ["dex-d" nil {:name "dex-e"} {:name ""}]))}]
      (-> (market-metadata/fetch-and-apply-perp-dex-metadata! deps-for-fetch {:priority :high})
          (.then (fn [fetch-result]
                   (is (= ["dex-a" "dex-b"] fetch-result))
                   (is (= fetch-payload (:fetched @store)))
                   (is (= [{:priority :high}] @fetch-calls))
                   (market-metadata/ensure-and-apply-perp-dex-metadata! deps-for-ensure {:priority :low})))
          (.then (fn [ensure-result]
                   (is (= ["dex-c"] (vec ensure-result)))
                   (is (= ensure-payload (:ensured @store)))
                   (is (= [[store {:priority :low}]] @ensure-calls))
                   (market-metadata/ensure-perp-dex-names! deps-for-names {:priority :bulk})))
          (.then (fn [named-result]
                   (is (= ["dex-d" "dex-e"] named-result))
                   (is (= [{:priority :bulk}] @ensure-names-calls))
                   (is (= ["dex-z"]
                          (market-metadata/payload->named-dex-names
                           [{:name "dex-z"} nil {:name ""}])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected market metadata success-path error: " err))
                    (done)))))))

(deftest ws-market-metadata-facade-error-coverage-test
  (async done
    (let [store (atom {})
          logs (atom [])
          deps-for-fetch-error {:store store
                                :log-fn (fn [& args]
                                          (swap! logs conj args))
                                :request-perp-dexs! (fn [_]
                                                      (js/Promise.reject (js/Error. "fetch failed")))
                                :apply-perp-dexs-success (fn [state payload]
                                                           (assoc state :unexpected payload))
                                :apply-perp-dexs-error (fn [state err]
                                                         (assoc state :fetch-error (.-message err)))}
          deps-for-ensure-error {:store store
                                 :ensure-perp-dexs-data! (fn [_ _]
                                                           (js/Promise.reject (js/Error. "ensure failed")))
                                 :apply-perp-dexs-success (fn [state payload]
                                                            (assoc state :unexpected payload))
                                 :apply-perp-dexs-error (fn [state err]
                                                          (assoc state :ensure-error (.-message err)))}]
      (-> (market-metadata/fetch-and-apply-perp-dex-metadata! deps-for-fetch-error {:priority :low})
          (.then (fn [_]
                   (is false "Expected fetch-and-apply-perp-dex-metadata! to reject")
                   (done)))
          (.catch (fn [fetch-err]
                    (is (re-find #"fetch failed" (str fetch-err)))
                    (is (= "fetch failed" (:fetch-error @store)))
                    (is (= "Error fetching perp DEX list:"
                           (ffirst @logs)))
                    (-> (market-metadata/ensure-and-apply-perp-dex-metadata!
                         deps-for-ensure-error
                         {:priority :low})
                        (.then (fn [_]
                                 (is false "Expected ensure-and-apply-perp-dex-metadata! to reject")
                                 (done)))
                        (.catch (fn [ensure-err]
                                  (is (re-find #"ensure failed" (str ensure-err)))
                                  (is (= "ensure failed" (:ensure-error @store)))
                                  (done))))))))))

(deftest ws-order-form-key-policy-low-function-coverage-smoke-test
  (let [raw-form {:type :limit
                  :price "100"
                  :entry-mode :pro
                  :ui-leverage 20
                  :margin-mode :cross
                  :size-input-mode :quote
                  :size-input-source :manual
                  :size-display "10"
                  :pro-order-type-dropdown-open? true
                  :margin-mode-dropdown-open? true
                  :leverage-popover-open? false
                  :leverage-draft 20
                  :size-unit-dropdown-open? true
                  :tpsl-unit-dropdown-open? false
                  :tif-dropdown-open? true
                  :price-input-focused? false
                  :tpsl-panel-open? true
                  :submitting? true
                  :error "oops"}
        ui-key (first key-policy/ui-owned-order-form-keys)
        legacy-key (first key-policy/legacy-order-form-compatibility-keys)]
    (is (true? (key-policy/ui-owned-order-form-key? ui-key)))
    (is (false? (key-policy/ui-owned-order-form-key? :type)))
    (is (true? (key-policy/legacy-order-form-compatibility-key? legacy-key)))
    (is (false? (key-policy/legacy-order-form-compatibility-key? :type)))
    (is (true? (key-policy/deprecated-canonical-order-form-key? ui-key)))
    (is (true? (key-policy/deprecated-canonical-order-form-key? legacy-key)))
    (is (false? (key-policy/deprecated-canonical-order-form-key? :type)))
    (is (true? (key-policy/canonical-write-blocked-order-form-path? [ui-key])))
    (is (false? (key-policy/canonical-write-blocked-order-form-path? [:type])))

    (is (= (select-keys raw-form key-policy/ui-owned-order-form-keys)
           (key-policy/order-form-ui-overrides-from-form raw-form)))
    (is (= {}
           (key-policy/order-form-ui-overrides-from-form nil)))

    (is (= (reduce dissoc raw-form key-policy/ui-owned-order-form-keys)
           (key-policy/strip-ui-owned-order-form-keys raw-form)))
    (is (= {}
           (key-policy/strip-ui-owned-order-form-keys nil)))

    (is (= (reduce dissoc raw-form key-policy/legacy-order-form-compatibility-keys)
           (key-policy/strip-legacy-order-form-compatibility-keys raw-form)))
    (is (= {}
           (key-policy/strip-legacy-order-form-compatibility-keys nil)))

    (is (= (reduce dissoc raw-form key-policy/deprecated-canonical-order-form-keys)
           (key-policy/strip-deprecated-canonical-order-form-keys raw-form)))
    (is (= {}
           (key-policy/strip-deprecated-canonical-order-form-keys nil)))))
