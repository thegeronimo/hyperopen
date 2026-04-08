(ns hyperopen.wallet.agent-runtime-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [clojure.string :as str]
            [hyperopen.wallet.agent-runtime :as agent-runtime]
            [hyperopen.wallet.agent-session :as agent-session]))

(defn- fake-storage []
  (let [store (atom {})]
    #js {:setItem (fn [k v] (swap! store assoc (str k) (str v)))
         :getItem (fn [k] (get @store (str k)))
         :removeItem (fn [k] (swap! store dissoc (str k)))
         :clear (fn [] (reset! store {}))}))

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

(deftest set-agent-local-protection-mode-migrates-ready-plain-session-to-passkey-test
  (async done
    (let [persisted-modes (atom [])
          persisted-metadata (atom [])
          cleared-raw-sessions (atom [])
          cached-sessions (atom [])
          cleared-unlocked-sessions (atom [])
          default-state-calls (atom 0)
          store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready
                                        :storage-mode :local
                                        :local-protection-mode :plain
                                        :agent-address "0xagent"
                                        :last-approved-at 42
                                        :nonce-cursor 84}}})]
      (agent-runtime/set-agent-local-protection-mode!
       {:store store
        :local-protection-mode :passkey
        :normalize-local-protection-mode identity
        :normalize-storage-mode identity
        :load-agent-session-by-mode (fn [_address _mode]
                                      {:agent-address "0xagent"
                                       :private-key "0xpriv"
                                       :last-approved-at 42
                                       :nonce-cursor 84})
        :load-unlocked-session (fn [_address] nil)
        :clear-agent-session-by-mode! (fn [address mode]
                                        (swap! cleared-raw-sessions conj [address mode])
                                        true)
        :clear-unlocked-session! (fn [address]
                                   (swap! cleared-unlocked-sessions conj address))
        :cache-unlocked-session! (fn [address session]
                                   (swap! cached-sessions conj [address session])
                                   session)
        :create-locked-session! (fn [{:keys [wallet-address session]}]
                                  (js/Promise.resolve
                                   {:metadata {:agent-address (:agent-address session)
                                               :credential-id "cred"
                                               :prf-salt "salt"
                                               :last-approved-at (:last-approved-at session)
                                               :nonce-cursor (:nonce-cursor session)}
                                    :session (assoc session
                                                    :storage-mode :local
                                                    :local-protection-mode :passkey)}))
        :delete-locked-session! (fn [_address]
                                  (js/Promise.resolve true))
        :persist-passkey-session-metadata! (fn [address metadata]
                                             (swap! persisted-metadata conj [address metadata])
                                             true)
        :clear-passkey-session-metadata! (fn [_address] true)
        :persist-local-protection-mode-preference! (fn [mode]
                                                     (swap! persisted-modes conj mode)
                                                     true)
        :default-agent-state (fn [& _]
                               (swap! default-state-calls inc)
                               {:status :not-ready})
        :persist-session-error "persist failed"
        :missing-session-error "missing session"})
      (js/setTimeout
       (fn []
         (try
           (is (= [:passkey] @persisted-modes))
           (is (= [["0xabc" :local]] @cleared-raw-sessions))
           (is (empty? @cleared-unlocked-sessions))
           (is (= 0 @default-state-calls))
           (is (= [["0xabc"
                    {:agent-address "0xagent"
                     :credential-id "cred"
                     :prf-salt "salt"
                     :last-approved-at 42
                     :nonce-cursor 84}]]
                  @persisted-metadata))
           (is (= :ready (get-in @store [:wallet :agent :status])))
           (is (= :passkey (get-in @store [:wallet :agent :local-protection-mode])))
           (is (= "0xagent" (get-in @store [:wallet :agent :agent-address])))
           (is (nil? (get-in @store [:wallet :agent :error])))
           (is (= :passkey
                  (get-in (second (first @cached-sessions)) [:local-protection-mode])))
           (finally
             (done))))
       0))))

(deftest set-agent-local-protection-mode-migrates-ready-passkey-session-to-plain-test
  (async done
    (let [persisted-modes (atom [])
          persisted-sessions (atom [])
          cleared-passkey-metadata (atom [])
          cached-sessions (atom [])
          default-state-calls (atom 0)
          store (atom {:wallet {:address "0xabc"
                                :agent {:status :ready
                                        :storage-mode :local
                                        :local-protection-mode :passkey
                                        :agent-address "0xagent"
                                        :last-approved-at 42
                                        :nonce-cursor 84}}})]
      (agent-runtime/set-agent-local-protection-mode!
       {:store store
        :local-protection-mode :plain
        :normalize-local-protection-mode identity
        :normalize-storage-mode identity
        :load-unlocked-session (fn [_address]
                                 {:agent-address "0xagent"
                                  :private-key "0xpriv"
                                  :last-approved-at 42
                                  :nonce-cursor 84})
        :persist-agent-session-by-mode! (fn [address mode session]
                                          (swap! persisted-sessions conj [address mode session])
                                          true)
        :delete-locked-session! (fn [_address]
                                  (js/Promise.resolve true))
        :clear-passkey-session-metadata! (fn [address]
                                           (swap! cleared-passkey-metadata conj address)
                                           true)
        :cache-unlocked-session! (fn [address session]
                                   (swap! cached-sessions conj [address session])
                                   session)
        :persist-local-protection-mode-preference! (fn [mode]
                                                     (swap! persisted-modes conj mode)
                                                     true)
        :default-agent-state (fn [& _]
                               (swap! default-state-calls inc)
                               {:status :not-ready})
        :persist-session-error "persist failed"
        :missing-session-error "missing session"})
      (js/setTimeout
       (fn []
         (try
           (is (= [:plain] @persisted-modes))
           (is (= ["0xabc"] @cleared-passkey-metadata))
           (is (= [["0xabc"
                    :local
                    {:agent-address "0xagent"
                     :private-key "0xpriv"
                     :last-approved-at 42
                     :nonce-cursor 84
                     :storage-mode :local
                     :local-protection-mode :passkey}]]
                  @persisted-sessions))
           (is (= 0 @default-state-calls))
           (is (= :ready (get-in @store [:wallet :agent :status])))
           (is (= :plain (get-in @store [:wallet :agent :local-protection-mode])))
           (is (= :plain
                  (get-in (second (first @cached-sessions)) [:local-protection-mode])))
           (finally
             (done))))
       0))))

(deftest set-agent-local-protection-mode-blocks-locked-passkey-downgrade-without-live-session-test
  (let [persisted-modes (atom [])
        cleared-sessions (atom [])
        cleared-unlocked-sessions (atom [])
        default-state-calls (atom 0)
        store (atom {:wallet {:address "0xabc"
                              :agent {:status :locked
                                      :storage-mode :local
                                      :local-protection-mode :passkey
                                      :agent-address "0xagent"}}})]
    (agent-runtime/set-agent-local-protection-mode!
     {:store store
      :local-protection-mode :plain
      :normalize-local-protection-mode identity
      :normalize-storage-mode identity
      :load-unlocked-session (fn [_address] nil)
      :clear-agent-session-by-mode! (fn [address mode]
                                      (swap! cleared-sessions conj [address mode])
                                      true)
      :clear-unlocked-session! (fn [address]
                                 (swap! cleared-unlocked-sessions conj address))
      :persist-local-protection-mode-preference! (fn [mode]
                                                   (swap! persisted-modes conj mode)
                                                   true)
      :default-agent-state (fn [& _]
                             (swap! default-state-calls inc)
                             {:status :not-ready})
      :unlock-required-error "Unlock trading before turning off passkey protection."})
    (is (empty? @persisted-modes))
    (is (empty? @cleared-sessions))
    (is (empty? @cleared-unlocked-sessions))
    (is (= 0 @default-state-calls))
    (is (= :locked (get-in @store [:wallet :agent :status])))
    (is (= :passkey (get-in @store [:wallet :agent :local-protection-mode])))
    (is (= "Unlock trading before turning off passkey protection."
           (get-in @store [:wallet :agent :error])))))

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
                                        :storage-mode :session
                                        :recovery-modal-open? true}}})]
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
           (is (false? (get-in @store [:wallet :agent :recovery-modal-open?])))
           (finally
             (done))))
       0))))

(deftest enable-agent-trading-migrates-legacy-device-label-before-approval-test
  (async done
    (let [original-local-storage (.-localStorage js/globalThis)
          storage (fake-storage)]
      (set! (.-localStorage js/globalThis) storage)
      (.setItem storage "hyperopen:agent-device-label:v1" "Hyperopen Device 72905e")
      (let [store (atom {:wallet {:address "0x111"
                                  :chain-id 42161
                                  :agent {:status :approving
                                          :storage-mode :local}}})
            captured-agent-name (atom nil)]
        (agent-runtime/enable-agent-trading!
         {:store store
          :options {:storage-mode :local}
          :create-agent-credentials! (fn []
                                       {:private-key "0xpriv"
                                        :agent-address "0x999"})
          :now-ms-fn (fn [] 1700000000000)
          :normalize-storage-mode identity
          :normalize-local-protection-mode identity
          :ensure-device-label! agent-session/ensure-device-label!
          :default-signature-chain-id-for-environment (fn [_] 1)
          :build-approve-agent-action (fn [agent-address nonce & {:keys [agent-name]}]
                                        (reset! captured-agent-name agent-name)
                                        {:agentAddress agent-address
                                         :nonce nonce
                                         :agentName agent-name})
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
             (is (= "Hyperopen 72905e" @captured-agent-name))
             (is (= "Hyperopen 72905e"
                    (.getItem storage "hyperopen:agent-device-label:v1")))
             (finally
               (set! (.-localStorage js/globalThis) original-local-storage)
               (done))))
         0)))))

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
