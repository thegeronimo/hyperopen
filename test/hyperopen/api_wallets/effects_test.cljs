(ns hyperopen.api-wallets.effects-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api-wallets.domain.policy :as policy]
            [hyperopen.api-wallets.effects :as effects]
            [hyperopen.wallet.agent-session :as agent-session]))

(def owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def generated-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(defn- clear-errors
  [state]
  (-> state
      (assoc-in [:api-wallets :errors :extra-agents] nil)
      (assoc-in [:api-wallets :errors :default-agent] nil)))

(deftest load-api-wallets-uses-owner-webdata2-snapshot-when-available-test
  (async done
    (let [extra-agent-calls (atom [])
          user-webdata2-calls (atom 0)
          store (atom {:router {:path "/API"}
                       :wallet {:address owner-address}
                       :webdata2 {:serverTime 1700000000000
                                  :agentAddress generated-address}
                       :api-wallets {:errors {:extra-agents "stale-extra"
                                              :default-agent "stale-default"}}})]
      (-> (effects/load-api-wallets!
           {:store store
            :request-extra-agents! (fn [address opts]
                                     (swap! extra-agent-calls conj [address opts])
                                     (js/Promise.resolve [{:row-kind :named
                                                           :name "Desk"
                                                           :address generated-address}]))
            :request-user-webdata2! (fn [_address _opts]
                                      (swap! user-webdata2-calls inc)
                                      (js/Promise.resolve {}))
            :apply-api-wallets-extra-agents-success (fn [state rows]
                                                      (assoc-in state [:api-wallets :extra-agents] rows))
            :apply-api-wallets-extra-agents-error (fn [state err]
                                                    (assoc-in state [:api-wallets :errors :extra-agents] err))
            :apply-api-wallets-default-agent-success (fn [state address snapshot]
                                                       (assoc state :default-agent-snapshot [address snapshot]))
            :apply-api-wallets-default-agent-error (fn [state address err]
                                                     (assoc state :default-agent-error [address err]))
            :clear-api-wallets-errors clear-errors
            :reset-api-wallets (fn [state]
                                 (assoc state :reset? true))
            :now-ms-fn (fn [] 999)
            :force-refresh? false})
          (.then (fn [_]
                   (is (= [[owner-address {:priority :high}]]
                          @extra-agent-calls))
                   (is (= 0 @user-webdata2-calls))
                   (is (= [{:row-kind :named
                            :name "Desk"
                            :address generated-address}]
                          (get-in @store [:api-wallets :extra-agents])))
                   (is (= [owner-address {:serverTime 1700000000000
                                          :agentAddress generated-address}]
                          (:default-agent-snapshot @store)))
                   (is (nil? (get-in @store [:api-wallets :errors :extra-agents])))
                   (is (nil? (get-in @store [:api-wallets :errors :default-agent])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected load-api-wallets success-path error: " err))
                    (done)))))))

(deftest load-api-wallets-resets-state-when-owner-wallet-is-missing-test
  (async done
    (let [extra-agent-calls (atom 0)
          store (atom {:router {:path "/API"}
                       :wallet {:address nil}
                       :api-wallets {:extra-agents [{:name "stale"}]}})]
      (-> (effects/load-api-wallets!
           {:store store
            :request-extra-agents! (fn [_address _opts]
                                     (swap! extra-agent-calls inc)
                                     (js/Promise.resolve []))
            :request-user-webdata2! (fn [_address _opts]
                                      (swap! extra-agent-calls inc)
                                      (js/Promise.resolve {}))
            :apply-api-wallets-extra-agents-success (fn [state rows]
                                                      (assoc-in state [:api-wallets :extra-agents] rows))
            :apply-api-wallets-extra-agents-error (fn [state err]
                                                    (assoc state :error err))
            :apply-api-wallets-default-agent-success (fn [state _address _snapshot]
                                                       (assoc state :default-loaded? true))
            :apply-api-wallets-default-agent-error (fn [state _address err]
                                                     (assoc state :default-error err))
            :clear-api-wallets-errors clear-errors
            :reset-api-wallets (fn [state]
                                 (-> state
                                     (assoc :reset? true)
                                     (assoc-in [:api-wallets :extra-agents] [])))})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @extra-agent-calls))
                   (is (= true (:reset? @store)))
                   (is (= [] (get-in @store [:api-wallets :extra-agents])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected load-api-wallets missing-owner error: " err))
                    (done)))))))

(deftest api-authorize-api-wallet-success-resets-transient-state-and-refreshes-test
  (async done
    (let [approve-calls (atom [])
          refresh-calls (atom [])
          store (atom {:wallet {:address owner-address}
                       :api-wallets {:server-time-ms 1700000000000}
                       :api-wallets-ui {:form {:name "Desk"
                                               :address generated-address
                                               :days-valid "30"}
                                        :form-error "stale"
                                        :generated {:address generated-address
                                                    :private-key "0xpriv"}
                                        :modal {:open? true
                                                :type :authorize
                                                :submitting? true
                                                :error nil}}})]
      (-> (effects/api-authorize-api-wallet!
           {:store store
            :approve-agent-request! (fn [opts]
                                      (swap! approve-calls conj
                                             (select-keys opts
                                                          [:owner-address
                                                           :agent-address
                                                           :private-key
                                                           :agent-name
                                                           :days-valid
                                                           :server-time-ms
                                                           :persist-session?]))
                                      (js/Promise.resolve {:status :ok}))
            :load-api-wallets! (fn [opts]
                                 (swap! refresh-calls conj
                                        (select-keys opts [:force-refresh?]))
                                 (js/Promise.resolve :reloaded))
            :runtime-error-message (fn [err]
                                     (str err))})
          (.then (fn [result]
                   (is (= :reloaded result))
                   (is (= [{:owner-address owner-address
                            :agent-address generated-address
                            :private-key "0xpriv"
                            :agent-name "Desk"
                            :days-valid "30"
                            :server-time-ms 1700000000000
                            :persist-session? false}]
                          @approve-calls))
                   (is (= [{:force-refresh? true}] @refresh-calls))
                   (is (= (policy/default-form)
                          (get-in @store [:api-wallets-ui :form])))
                   (is (= (policy/default-modal-state)
                          (get-in @store [:api-wallets-ui :modal])))
                   (is (= (policy/default-generated-state)
                          (get-in @store [:api-wallets-ui :generated])))
                   (is (nil? (get-in @store [:api-wallets-ui :form-error])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected api-authorize-api-wallet success-path error: " err))
                    (done)))))))

(deftest api-remove-api-wallet-default-row-clears-sessions-and-refreshes-test
  (async done
    (let [approve-calls (atom [])
          cleared-sessions (atom [])
          refresh-calls (atom [])
          store (atom {:wallet {:address owner-address
                                :agent {:status :ready
                                        :storage-mode :session
                                        :agent-address generated-address}}
                       :api-wallets-ui {:generated {:address generated-address
                                                    :private-key "0xpriv"}
                                        :form {:name "Desk"
                                               :address generated-address
                                               :days-valid "30"}
                                        :modal {:open? true
                                                :type :remove
                                                :submitting? true
                                                :row {:row-kind :default
                                                      :name "app.hyperopen.xyz"
                                                      :address generated-address
                                                      :valid-until-ms 1700000000000}}}})]
      (-> (effects/api-remove-api-wallet!
           {:store store
            :approve-agent-request! (fn [opts]
                                      (swap! approve-calls conj
                                             (select-keys opts
                                                          [:owner-address
                                                           :agent-address
                                                           :agent-name
                                                           :persist-session?]))
                                      (js/Promise.resolve {:status :ok}))
            :load-api-wallets! (fn [opts]
                                 (swap! refresh-calls conj
                                        (select-keys opts [:force-refresh?]))
                                 (js/Promise.resolve :reloaded))
            :clear-agent-session-by-mode! (fn [address mode]
                                            (swap! cleared-sessions conj [address mode]))
            :default-agent-state (fn [& {:keys [storage-mode]}]
                                   {:status :not-ready
                                    :storage-mode storage-mode
                                    :agent-address nil})
            :runtime-error-message (fn [err]
                                     (str err))})
          (.then (fn [result]
                   (is (= :reloaded result))
                   (is (= [{:owner-address owner-address
                            :agent-address agent-session/zero-address
                            :agent-name nil
                            :persist-session? false}]
                          @approve-calls))
                   (is (= [[owner-address :local]
                           [owner-address :session]]
                          @cleared-sessions))
                   (is (= [{:force-refresh? true}] @refresh-calls))
                   (is (= :not-ready (get-in @store [:wallet :agent :status])))
                   (is (= :session (get-in @store [:wallet :agent :storage-mode])))
                   (is (= (policy/default-modal-state)
                          (get-in @store [:api-wallets-ui :modal])))
                   (is (= (policy/default-generated-state)
                          (get-in @store [:api-wallets-ui :generated])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected api-remove-api-wallet success-path error: " err))
                    (done)))))))
