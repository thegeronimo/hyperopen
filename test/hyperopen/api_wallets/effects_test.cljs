(ns hyperopen.api-wallets.effects-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.api-wallets.application.ui-state :as ui-state]
            [hyperopen.api-wallets.effects :as effects]
            [hyperopen.wallet.agent-session :as agent-session]))

(def owner-address
  "0x1234567890abcdef1234567890abcdef12345678")

(def spectate-address
  "0x9999999999999999999999999999999999999999")

(def generated-address
  "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd")

(defn- clear-errors
  [state]
  (-> state
      (assoc-in [:api-wallets :errors :extra-agents] nil)
      (assoc-in [:api-wallets :errors :default-agent] nil)))

(deftest load-api-wallets-resolves-without-requesting-when-route-is-inactive-test
  (async done
    (let [extra-agent-calls (atom 0)
          user-webdata2-calls (atom 0)
          store (atom {:router {:path "/trade"}
                       :wallet {:address owner-address}})]
      (-> (effects/load-api-wallets!
           {:store store
            :request-extra-agents! (fn [_address _opts]
                                     (swap! extra-agent-calls inc)
                                     (js/Promise.resolve []))
            :request-user-webdata2! (fn [_address _opts]
                                      (swap! user-webdata2-calls inc)
                                      (js/Promise.resolve {}))})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= 0 @extra-agent-calls))
                   (is (= 0 @user-webdata2-calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected inactive-route error: " err))
                    (done)))))))

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

(deftest load-api-wallets-force-refreshes-and-fetches-remote-webdata-when-local-snapshot-is-not-reusable-test
  (async done
    (let [extra-agent-calls (atom [])
          user-webdata2-calls (atom [])
          store (atom {:router {:path "/api?tab=manage"}
                       :wallet {:address owner-address}
                       :account-context {:spectate-mode {:active? true
                                                         :address spectate-address}}
                       :webdata2 {:server-time "1700000000000"
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
            :request-user-webdata2! (fn [address opts]
                                      (swap! user-webdata2-calls conj [address opts])
                                      (js/Promise.resolve {:server-time "1700000000000"
                                                           :agentAddress generated-address}))
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
            :now-ms-fn (fn [] 777)
            :force-refresh? true})
          (.then (fn [_]
                   (is (= [[owner-address
                            {:priority :high
                             :cache-ttl-ms 1
                             :dedupe-key [:extra-agents owner-address 777]
                             :cache-key [:extra-agents owner-address 777]}]]
                          @extra-agent-calls))
                   (is (= [[owner-address
                            {:priority :high
                             :cache-ttl-ms 1
                             :dedupe-key [:user-webdata2 owner-address 777]
                             :cache-key [:user-webdata2 owner-address 777]}]]
                          @user-webdata2-calls))
                   (is (= [owner-address {:server-time "1700000000000"
                                          :agentAddress generated-address}]
                          (:default-agent-snapshot @store)))
                   (is (nil? (get-in @store [:api-wallets :errors :extra-agents])))
                   (is (nil? (get-in @store [:api-wallets :errors :default-agent])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected load-api-wallets force-refresh error: " err))
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

(deftest load-api-wallets-collects-request-errors-without-rejecting-test
  (async done
    (let [store (atom {:router {:path "/API"}
                       :wallet {:address owner-address}
                       :webdata2 {:serverTime nil}
                       :api-wallets {:errors {:extra-agents "stale-extra"
                                              :default-agent "stale-default"}}})]
      (-> (effects/load-api-wallets!
           {:store store
            :request-extra-agents! (fn [_address _opts]
                                     (js/Promise.reject "extra failed"))
            :request-user-webdata2! (fn [_address _opts]
                                      (js/Promise.reject "default failed"))
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
            :now-ms-fn (fn [] 123)
            :force-refresh? true})
          (.then (fn [result]
                   (is (nil? result))
                   (is (= "extra failed"
                          (get-in @store [:api-wallets :errors :extra-agents])))
                   (is (= [owner-address "default failed"]
                          (:default-agent-error @store)))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected load-api-wallets error-collection rejection: " err))
                    (done)))))))

(deftest api-load-api-wallets-wrapper-defaults-force-refresh-to-false-test
  (let [captured-opts (atom nil)]
    (with-redefs [effects/load-api-wallets!
                  (fn [opts]
                    (reset! captured-opts opts)
                    :loaded)]
      (is (= :loaded
             (effects/api-load-api-wallets! {:store :test-store
                                             :request-extra-agents! :extra
                                             :request-user-webdata2! :webdata2})))
      (is (= {:store :test-store
              :request-extra-agents! :extra
              :request-user-webdata2! :webdata2
              :force-refresh? false}
             @captured-opts)))))

(deftest generate-api-wallet-success-populates-form-and-generated-state-test
  (let [store (atom {:api-wallets-ui {:form {:name "Desk"
                                             :address ""
                                             :days-valid ""}
                                      :generated {:address nil
                                                  :private-key nil}
                                      :form-error "stale"}})]
    (is (nil?
         (effects/generate-api-wallet!
          {:store store
           :create-agent-credentials! (fn []
                                        {:private-key "0xpriv"
                                         :agent-address generated-address})
           :runtime-error-message (fn [err]
                                    (str err))})))
    (is (= generated-address
           (get-in @store [:api-wallets-ui :form :address])))
    (is (= {:address generated-address
            :private-key "0xpriv"}
           (get-in @store [:api-wallets-ui :generated])))
    (is (nil? (get-in @store [:api-wallets-ui :form-error])))))

(deftest generate-api-wallet-failure-surfaces-runtime-error-message-test
  (let [store (atom {:api-wallets-ui {:form {:name "Desk"
                                             :address ""
                                             :days-valid ""}
                                      :generated {:address nil
                                                  :private-key nil}
                                      :form-error nil}})]
    (effects/generate-api-wallet!
     {:store store
      :create-agent-credentials! (fn []
                                   (throw (js/Error. "generation failed")))
      :runtime-error-message (fn [err]
                               (.-message err))})
    (is (= "generation failed"
           (get-in @store [:api-wallets-ui :form-error])))))

(deftest api-authorize-api-wallet-validation-error-updates-modal-state-without-requesting-approval-test
  (let [approve-calls (atom 0)
        store (atom {:wallet {:address owner-address}
                     :api-wallets-ui {:form {:name ""
                                             :address generated-address
                                             :days-valid ""}
                                      :modal {:open? true
                                              :type :authorize
                                              :submitting? true
                                              :error nil}}})]
    (effects/api-authorize-api-wallet!
     {:store store
      :approve-agent-request! (fn [_opts]
                                (swap! approve-calls inc)
                                (js/Promise.resolve :ok))
      :load-api-wallets! (fn [_opts]
                           (js/Promise.resolve :reloaded))
      :runtime-error-message (fn [err]
                               (str err))})
    (is (= 0 @approve-calls))
    (is (= "Enter an API wallet name."
           (get-in @store [:api-wallets-ui :modal :error])))
    (is (false? (get-in @store [:api-wallets-ui :modal :submitting?])))))

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
                   (is (= (ui-state/default-form)
                          (get-in @store [:api-wallets-ui :form])))
                   (is (= (ui-state/default-modal-state)
                          (get-in @store [:api-wallets-ui :modal])))
                   (is (= (ui-state/default-generated-state)
                          (get-in @store [:api-wallets-ui :generated])))
                   (is (nil? (get-in @store [:api-wallets-ui :form-error])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected api-authorize-api-wallet success-path error: " err))
                    (done)))))))

(deftest api-authorize-api-wallet-rejection-surfaces-modal-error-and-skips-refresh-test
  (async done
    (let [refresh-calls (atom 0)
          store (atom {:wallet {:address owner-address}
                       :api-wallets {:server-time-ms 1700000000000}
                       :api-wallets-ui {:form {:name "Desk"
                                               :address generated-address
                                               :days-valid "30"}
                                        :generated {:address generated-address
                                                    :private-key "0xpriv"}
                                        :modal {:open? true
                                                :type :authorize
                                                :submitting? true
                                                :error nil}}})]
      (-> (effects/api-authorize-api-wallet!
           {:store store
            :approve-agent-request! (fn [_opts]
                                      (js/Promise.reject (js/Error. "approval failed")))
            :load-api-wallets! (fn [_opts]
                                 (swap! refresh-calls inc)
                                 (js/Promise.resolve :reloaded))
            :runtime-error-message (fn [err]
                                     (.-message err))})
          (.then (fn [_]
                   (is (= 0 @refresh-calls))
                   (is (= "approval failed"
                          (get-in @store [:api-wallets-ui :modal :error])))
                   (is (false? (get-in @store [:api-wallets-ui :modal :submitting?])))
                   (is (= true (get-in @store [:api-wallets-ui :modal :open?])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected api-authorize-api-wallet rejection-path error: " err))
                    (done)))))))

(deftest api-remove-api-wallet-validation-error-requires-selected-row-test
  (let [approve-calls (atom 0)
        store (atom {:wallet {:address owner-address}
                     :api-wallets-ui {:modal {:open? true
                                              :type :remove
                                              :submitting? true
                                              :row nil}}})]
    (effects/api-remove-api-wallet!
     {:store store
      :approve-agent-request! (fn [_opts]
                                (swap! approve-calls inc)
                                (js/Promise.resolve :ok))
      :load-api-wallets! (fn [_opts]
                           (js/Promise.resolve :reloaded))
      :clear-agent-session-by-mode! (fn [_address _mode]
                                      nil)
      :default-agent-state (fn [& _]
                             {:status :not-ready})
      :runtime-error-message (fn [err]
                               (str err))})
    (is (= 0 @approve-calls))
    (is (= "Select an API wallet row to remove."
           (get-in @store [:api-wallets-ui :modal :error])))
    (is (false? (get-in @store [:api-wallets-ui :modal :submitting?])))))

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
                   (is (= (ui-state/default-modal-state)
                          (get-in @store [:api-wallets-ui :modal])))
                   (is (= (ui-state/default-generated-state)
                          (get-in @store [:api-wallets-ui :generated])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected api-remove-api-wallet success-path error: " err))
                    (done)))))))

(deftest api-remove-api-wallet-named-row-refreshes-without-clearing-sessions-test
  (async done
    (let [approve-calls (atom [])
          cleared-sessions (atom [])
          refresh-calls (atom [])
          store (atom {:wallet {:address owner-address
                                :agent {:status :ready
                                        :storage-mode :local
                                        :agent-address generated-address}}
                       :api-wallets-ui {:generated {:address generated-address
                                                    :private-key "0xpriv"}
                                        :form {:name "Desk"
                                               :address generated-address
                                               :days-valid "30"}
                                        :modal {:open? true
                                                :type :remove
                                                :submitting? true
                                                :row {:row-kind :named
                                                      :name "Desk"
                                                      :approval-name "Desk approval"
                                                      :address generated-address}}}})]
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
                            :agent-name "Desk approval"
                            :persist-session? false}]
                          @approve-calls))
                   (is (= [] @cleared-sessions))
                   (is (= [{:force-refresh? true}] @refresh-calls))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected api-remove-api-wallet named-row error: " err))
                    (done)))))))

(deftest api-remove-api-wallet-rejection-surfaces-modal-error-and-skips_refresh-test
  (async done
    (let [cleared-sessions (atom [])
          refresh-calls (atom 0)
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
                                                      :address generated-address}}}})]
      (-> (effects/api-remove-api-wallet!
           {:store store
            :approve-agent-request! (fn [_opts]
                                      (js/Promise.reject (js/Error. "remove failed")))
            :load-api-wallets! (fn [_opts]
                                 (swap! refresh-calls inc)
                                 (js/Promise.resolve :reloaded))
            :clear-agent-session-by-mode! (fn [address mode]
                                            (swap! cleared-sessions conj [address mode]))
            :default-agent-state (fn [& {:keys [storage-mode]}]
                                   {:status :not-ready
                                    :storage-mode storage-mode
                                    :agent-address nil})
            :runtime-error-message (fn [err]
                                     (.-message err))})
          (.then (fn [_]
                   (is (= 0 @refresh-calls))
                   (is (= [] @cleared-sessions))
                   (is (= "remove failed"
                          (get-in @store [:api-wallets-ui :modal :error])))
                   (is (false? (get-in @store [:api-wallets-ui :modal :submitting?])))
                   (done)))
          (.catch (fn [err]
                    (is false (str "Unexpected api-remove-api-wallet rejection-path error: " err))
                    (done)))))))
