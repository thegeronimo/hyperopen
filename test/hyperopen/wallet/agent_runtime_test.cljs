(ns hyperopen.wallet.agent-runtime-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [clojure.string :as str]
            [hyperopen.wallet.agent-runtime :as agent-runtime]))

(deftest set-agent-storage-mode-clears-sessions-and-resets-agent-state-test
  (let [cleared (atom [])
        persisted-modes (atom [])
        store (atom {:wallet {:address "0xabc"
                              :agent {:status :ready
                                      :storage-mode :session
                                      :agent-address "0xagent"}}})]
    (agent-runtime/set-agent-storage-mode!
     {:store store
      :storage-mode :local
      :normalize-storage-mode identity
      :clear-agent-session-by-mode! (fn [address mode]
                                      (swap! cleared conj [address mode]))
      :persist-storage-mode-preference! (fn [mode]
                                          (swap! persisted-modes conj mode))
      :default-agent-state (fn [& {:keys [storage-mode]}]
                             {:status :not-ready
                              :storage-mode storage-mode
                              :agent-address nil})
      :agent-storage-mode-reset-message "Trading persistence updated. Enable Trading again."})
    (is (= [["0xabc" :session]
            ["0xabc" :local]]
           @cleared))
    (is (= [:local] @persisted-modes))
    (is (= :not-ready (get-in @store [:wallet :agent :status])))
    (is (= :local (get-in @store [:wallet :agent :storage-mode])))
    (is (str/includes? (str (get-in @store [:wallet :agent :error]))
                       "Trading persistence updated"))))

(deftest enable-agent-trading-sets-error-when-wallet-missing-test
  (let [store (atom {:wallet {:address nil
                              :agent {:status :approving}}})]
    (agent-runtime/enable-agent-trading!
     {:store store
      :options {:storage-mode :session}
      :create-agent-credentials! (fn [] nil)
      :now-ms-fn (fn [] 1)
      :normalize-storage-mode identity
      :default-signature-chain-id-for-environment (fn [_] 1)
      :build-approve-agent-action (fn [& _] nil)
      :approve-agent! (fn [& _] (js/Promise.resolve nil))
      :persist-agent-session-by-mode! (fn [& _] true)
      :runtime-error-message (fn [err] (str err))
      :exchange-response-error (fn [resp] (pr-str resp))})
    (is (= :error (get-in @store [:wallet :agent :status])))
    (is (= "Connect your wallet before enabling trading."
           (get-in @store [:wallet :agent :error])))))

(deftest enable-agent-trading-sets-ready-state-on-success-test
  (async done
    (let [store (atom {:wallet {:address "0x111"
                                :chain-id 42161
                                :agent {:status :approving
                                        :storage-mode :session}}})]
      (agent-runtime/enable-agent-trading!
       {:store store
        :options {:storage-mode :session}
        :create-agent-credentials! (fn []
                                     {:private-key "0xpriv"
                                      :agent-address "0x999"})
        :now-ms-fn (fn [] 1700000000000)
        :normalize-storage-mode identity
        :default-signature-chain-id-for-environment (fn [_] 1)
        :build-approve-agent-action (fn [agent-address nonce & _]
                                      {:agentAddress agent-address
                                       :nonce nonce})
        :approve-agent! (fn [& _]
                          (js/Promise.resolve #js {:json (fn []
                                                          (js/Promise.resolve #js {:status "ok"}))}))
        :persist-agent-session-by-mode! (fn [& _] true)
        :runtime-error-message (fn [err] (str err))
        :exchange-response-error (fn [resp] (pr-str resp))})
      (js/setTimeout
       (fn []
         (try
           (is (= :ready (get-in @store [:wallet :agent :status])))
           (is (= "0x999" (get-in @store [:wallet :agent :agent-address])))
           (is (= 1700000000000 (get-in @store [:wallet :agent :last-approved-at])))
           (is (= nil (get-in @store [:wallet :agent :error])))
           (finally
             (done))))
       0))))

(deftest exchange-response-error-falls-back-through-known-fields-test
  (is (= "explicit error"
         (agent-runtime/exchange-response-error {:error "explicit error"
                                                 :response "response"
                                                 :message "message"})))
  (is (= "response"
         (agent-runtime/exchange-response-error {:response "response"
                                                 :message "message"})))
  (is (= "message"
         (agent-runtime/exchange-response-error {:message "message"})))
  (is (= "{:status \"no-error\"}"
         (agent-runtime/exchange-response-error {:status "no-error"}))))

(deftest runtime-error-message-supports-js-and-clj-shapes-test
  (is (= "boom"
         (agent-runtime/runtime-error-message (js/Error. "boom"))))
  (is (= "plain js message"
         (agent-runtime/runtime-error-message #js {:message "plain js message"})))
  (is (= "nested data message"
         (agent-runtime/runtime-error-message #js {:data #js {:message "nested data message"}})))
  (is (= "map message"
         (agent-runtime/runtime-error-message {:message "map message"})))
  (is (= "map data message"
         (agent-runtime/runtime-error-message {:data {:message "map data message"}})))
  (is (= "map error message"
         (agent-runtime/runtime-error-message {:error {:message "map error message"}})))
  (is (= "42"
         (agent-runtime/runtime-error-message 42))))

(deftest set-agent-storage-mode-noops-when-mode-unchanged-and-clears-none-when-address-missing-test
  (let [store (atom {:wallet {:address nil
                              :agent {:status :ready
                                      :storage-mode :session
                                      :agent-address "0xagent"}}})
        cleared (atom [])
        persisted-modes (atom [])
        default-state-calls (atom 0)]
    (agent-runtime/set-agent-storage-mode!
     {:store store
      :storage-mode :session
      :normalize-storage-mode identity
      :clear-agent-session-by-mode! (fn [address mode]
                                      (swap! cleared conj [address mode]))
      :persist-storage-mode-preference! (fn [mode]
                                          (swap! persisted-modes conj mode))
      :default-agent-state (fn [& {:keys [storage-mode]}]
                             (swap! default-state-calls inc)
                             {:status :not-ready
                              :storage-mode storage-mode
                              :agent-address nil})
      :agent-storage-mode-reset-message "reset"})
    (is (empty? @cleared))
    (is (empty? @persisted-modes))
    (is (= 0 @default-state-calls))
    (agent-runtime/set-agent-storage-mode!
     {:store store
      :storage-mode :local
      :normalize-storage-mode identity
      :clear-agent-session-by-mode! (fn [address mode]
                                      (swap! cleared conj [address mode]))
      :persist-storage-mode-preference! (fn [mode]
                                          (swap! persisted-modes conj mode))
      :default-agent-state (fn [& {:keys [storage-mode]}]
                             (swap! default-state-calls inc)
                             {:status :not-ready
                              :storage-mode storage-mode
                              :agent-address nil})
      :agent-storage-mode-reset-message "reset"})
    (is (empty? @cleared))
    (is (= [:local] @persisted-modes))
    (is (= 1 @default-state-calls))
    (is (= :local (get-in @store [:wallet :agent :storage-mode])))))

(deftest approve-agent-request-encodes-name-and-skips-session-persistence-when-disabled-test
  (async done
    (let [store (atom {:wallet {:address "0xowner"
                                :chain-id nil}})
          format-calls (atom [])
          build-calls (atom [])
          approve-calls (atom [])
          persist-calls (atom [])]
      (-> (agent-runtime/approve-agent-request!
           {:store store
            :owner-address "0xowner"
            :agent-address "0x999"
            :private-key "0xpriv"
            :storage-mode :session
            :is-mainnet false
            :agent-name "Desk"
            :days-valid "30"
            :server-time-ms 1700000000000
            :persist-session? false
            :now-ms-fn (fn [] 1700000000000)
            :normalize-storage-mode identity
            :default-signature-chain-id-for-environment (fn [is-mainnet]
                                                          (if is-mainnet
                                                            "0xa4b1"
                                                            "0x66eee"))
            :build-approve-agent-action (fn [agent-address nonce & {:keys [agent-name
                                                                           is-mainnet
                                                                           signature-chain-id]}]
                                          (swap! build-calls conj {:agent-address agent-address
                                                                   :nonce nonce
                                                                   :agent-name agent-name
                                                                   :is-mainnet is-mainnet
                                                                   :signature-chain-id signature-chain-id})
                                          {:agentAddress agent-address
                                           :nonce nonce
                                           :agentName agent-name
                                           :signatureChainId signature-chain-id})
            :format-agent-name-with-valid-until (fn [name server-time-ms days-valid]
                                                  (swap! format-calls conj [name server-time-ms days-valid])
                                                  "Desk valid_until 1702592000000")
            :approve-agent! (fn [_store owner-address action]
                              (swap! approve-calls conj [owner-address action])
                              (js/Promise.resolve #js {:json (fn []
                                                              (js/Promise.resolve #js {:status "ok"}))}))
            :persist-agent-session-by-mode! (fn [& args]
                                              (swap! persist-calls conj args)
                                              true)
            :runtime-error-message (fn [err] (str err))
            :exchange-response-error (fn [resp] (pr-str resp))})
          (.then
           (fn [result]
             (is (= [["Desk" 1700000000000 "30"]] @format-calls))
             (is (= [{:agent-address "0x999"
                      :nonce 1700000000000
                      :agent-name "Desk valid_until 1702592000000"
                      :is-mainnet false
                      :signature-chain-id "0x66eee"}]
                    @build-calls))
             (is (= [["0xowner"
                      {:agentAddress "0x999"
                       :nonce 1700000000000
                       :agentName "Desk valid_until 1702592000000"
                       :signatureChainId "0x66eee"}]]
                    @approve-calls))
             (is (empty? @persist-calls))
             (is (= "0xowner" (:owner-address result)))
             (is (= "0x999" (:agent-address result)))
             (is (= :session (:storage-mode result)))
             (is (= 1700000000000 (:last-approved-at result)))
             (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected approve-agent-request success-path error: " err))
                    (done)))))))

(deftest approve-agent-request-rejects-with-missing-owner-error-test
  (async done
    (-> (agent-runtime/approve-agent-request!
         {:store (atom {:wallet {:address nil}})
          :owner-address nil
          :agent-address "0x999"
          :now-ms-fn (fn [] 1)
          :normalize-storage-mode identity
          :default-signature-chain-id-for-environment (fn [_] "0xa4b1")
          :build-approve-agent-action (fn [& _] nil)
          :format-agent-name-with-valid-until (fn [& _] nil)
          :approve-agent! (fn [& _] (js/Promise.resolve nil))
          :persist-agent-session-by-mode! (fn [& _] true)
          :runtime-error-message (fn [err] (str err))
          :exchange-response-error (fn [resp] (pr-str resp))
          :missing-owner-error "Connect first."})
        (.then (fn [_]
                 (is false "Expected missing-owner approve-agent-request rejection")
                 (done)))
        (.catch (fn [err]
                  (is (= "Connect first." (.-message err)))
                  (done))))))

(deftest enable-agent-trading-sets-error-when-session-persist-fails-test
  (async done
    (let [store (atom {:wallet {:address "0x111"
                                :chain-id "0xa4b1"
                                :agent {:status :approving
                                        :storage-mode :session}}})]
      (agent-runtime/enable-agent-trading!
       {:store store
        :options {:storage-mode :session}
        :create-agent-credentials! (fn []
                                     {:private-key "0xpriv"
                                      :agent-address "0x999"})
        :now-ms-fn (fn [] 1700000000001)
        :normalize-storage-mode identity
        :default-signature-chain-id-for-environment (fn [_] "0xdefault")
        :build-approve-agent-action (fn [agent-address nonce & _]
                                      {:agentAddress agent-address
                                       :nonce nonce})
        :approve-agent! (fn [& _]
                          (js/Promise.resolve #js {:json (fn []
                                                          (js/Promise.resolve #js {:status "ok"}))}))
        :persist-agent-session-by-mode! (fn [& _] false)
        :runtime-error-message (fn [err] (str err))
        :exchange-response-error (fn [resp] (pr-str resp))})
      (js/setTimeout
       (fn []
         (try
           (is (= :error (get-in @store [:wallet :agent :status])))
           (is (= "Unable to persist agent credentials."
                  (get-in @store [:wallet :agent :error])))
           (finally
             (done))))
       0))))

(deftest enable-agent-trading-sets-error-when-exchange-response-not-ok-test
  (async done
    (let [store (atom {:wallet {:address "0x111"
                                :chain-id "0xa4b1"
                                :agent {:status :approving
                                        :storage-mode :session}}})
          exchange-errors (atom [])]
      (agent-runtime/enable-agent-trading!
       {:store store
        :options {:storage-mode :session}
        :create-agent-credentials! (fn []
                                     {:private-key "0xpriv"
                                      :agent-address "0x999"})
        :now-ms-fn (fn [] 1700000000002)
        :normalize-storage-mode identity
        :default-signature-chain-id-for-environment (fn [_] "0xdefault")
        :build-approve-agent-action (fn [agent-address nonce & _]
                                      {:agentAddress agent-address
                                       :nonce nonce})
        :approve-agent! (fn [& _]
                          (js/Promise.resolve #js {:json (fn []
                                                          (js/Promise.resolve #js {:status "error"
                                                                                   :response "exchange rejected"}))}))
        :persist-agent-session-by-mode! (fn [& _] true)
        :runtime-error-message (fn [err] (str err))
        :exchange-response-error (fn [resp]
                                   (swap! exchange-errors conj resp)
                                   "exchange rejected")})
      (js/setTimeout
       (fn []
         (try
           (is (= 1 (count @exchange-errors)))
           (is (= :error (get-in @store [:wallet :agent :status])))
           (is (= "exchange rejected" (get-in @store [:wallet :agent :error])))
           (finally
             (done))))
       0))))

(deftest enable-agent-trading-handles-promise-rejection-and-sync-exceptions-test
  (async done
    (let [store-rejected (atom {:wallet {:address "0x111"
                                         :chain-id "0xa4b1"
                                         :agent {:status :approving
                                                 :storage-mode :session}}})
          store-thrown (atom {:wallet {:address "0x222"
                                       :chain-id nil
                                       :agent {:status :approving
                                               :storage-mode :local}}})
          runtime-error-calls (atom [])]
      (agent-runtime/enable-agent-trading!
       {:store store-rejected
        :options {:storage-mode :session}
        :create-agent-credentials! (fn []
                                     {:private-key "0xpriv"
                                      :agent-address "0x999"})
        :now-ms-fn (fn [] 1700000000003)
        :normalize-storage-mode identity
        :default-signature-chain-id-for-environment (fn [_] "0xdefault")
        :build-approve-agent-action (fn [agent-address nonce & _]
                                      {:agentAddress agent-address
                                       :nonce nonce})
        :approve-agent! (fn [& _]
                          (js/Promise.reject (js/Error. "rpc down")))
        :persist-agent-session-by-mode! (fn [& _] true)
        :runtime-error-message (fn [err]
                                 (swap! runtime-error-calls conj (.-message err))
                                 "runtime failure")
        :exchange-response-error (fn [resp] (pr-str resp))})
      (agent-runtime/enable-agent-trading!
       {:store store-thrown
        :options {:storage-mode :local}
        :create-agent-credentials! (fn []
                                     (throw (js/Error. "keygen failed")))
        :now-ms-fn (fn [] 1700000000004)
        :normalize-storage-mode identity
        :default-signature-chain-id-for-environment (fn [_] "0xdefault")
        :build-approve-agent-action (fn [& _] nil)
        :approve-agent! (fn [& _] (js/Promise.resolve nil))
        :persist-agent-session-by-mode! (fn [& _] true)
        :runtime-error-message (fn [err]
                                 (swap! runtime-error-calls conj (.-message err))
                                 "sync runtime failure")
        :exchange-response-error (fn [resp] (pr-str resp))})
      (js/setTimeout
       (fn []
         (try
           (is (some #{"rpc down"} @runtime-error-calls))
           (is (some #{"keygen failed"} @runtime-error-calls))
           (is (= "runtime failure" (get-in @store-rejected [:wallet :agent :error])))
           (is (= "sync runtime failure" (get-in @store-thrown [:wallet :agent :error])))
           (finally
             (done))))
       0))))

(deftest enable-agent-trading-uses-default-signature-chain-id-when-wallet-chain-missing-test
  (async done
    (let [store (atom {:wallet {:address "0x111"
                                :chain-id nil
                                :agent {:status :approving
                                        :storage-mode :session}}})
          captured-signature-chain-id (atom nil)]
      (agent-runtime/enable-agent-trading!
       {:store store
        :options {:storage-mode :session
                  :is-mainnet false}
        :create-agent-credentials! (fn []
                                     {:private-key "0xpriv"
                                      :agent-address "0x999"})
        :now-ms-fn (fn [] 1700000000005)
        :normalize-storage-mode identity
        :default-signature-chain-id-for-environment (fn [is-mainnet]
                                                      (if is-mainnet
                                                        "0xa4b1"
                                                        "0x66eee"))
        :build-approve-agent-action (fn [_ _ & {:keys [signature-chain-id]}]
                                      (reset! captured-signature-chain-id signature-chain-id)
                                      {:signatureChainId signature-chain-id})
        :approve-agent! (fn [& _]
                          (js/Promise.resolve #js {:json (fn []
                                                          (js/Promise.resolve #js {:status "ok"}))}))
        :persist-agent-session-by-mode! (fn [& _] true)
        :runtime-error-message (fn [err] (str err))
        :exchange-response-error (fn [resp] (pr-str resp))})
      (js/setTimeout
       (fn []
         (try
           (is (= "0x66eee" @captured-signature-chain-id))
           (is (= :ready (get-in @store [:wallet :agent :status])))
           (finally
             (done))))
       0))))
