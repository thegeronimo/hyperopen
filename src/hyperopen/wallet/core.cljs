(ns hyperopen.wallet.core
  (:require [clojure.string :as str]
            [hyperopen.wallet.agent-lockbox :as agent-lockbox]
            [hyperopen.wallet.agent-session :as agent-session]))

;; ---------- Provider helpers -------------------------------------------------

(declare listeners-installed?
         provider-listener-state
         provider-listener-store)

(defonce ^:private provider-override
  (atom nil))

(defonce ^:private provider-listener-state
  (atom nil))

(defonce ^:private provider-listener-store
  (atom nil))

(defn set-provider-override!
  [provider]
  (reset! provider-override provider)
  provider)

(defn clear-provider-override!
  []
  (reset! provider-override nil)
  true)

(defn- provider-remove-listener!
  [provider event-name handler]
  (let [remove-listener (when (some? provider)
                          (aget provider "removeListener"))
        off-fn (when (some? provider)
                 (aget provider "off"))]
    (cond
      (fn? remove-listener)
      (js-invoke provider "removeListener" event-name handler)

      (fn? off-fn)
      (js-invoke provider "off" event-name handler)

      :else
      nil)))

(defn- detach-provider-listeners!
  []
  (when-let [{:keys [provider accounts-changed chain-changed]} @provider-listener-state]
    (when accounts-changed
      (provider-remove-listener! provider "accountsChanged" accounts-changed))
    (when chain-changed
      (provider-remove-listener! provider "chainChanged" chain-changed)))
  (reset! provider-listener-state nil)
  (reset! provider-listener-store nil)
  (reset! listeners-installed? false))

(defn reset-provider-listener-state!
  []
  (detach-provider-listeners!)
  true)

(defn ^js provider []
  (or @provider-override
      (some-> js/globalThis .-window .-ethereum)))

(defn has-provider? [] (some? (provider)))

(defn short-addr [a]
  (when a (str (subs a 0 6) "…" (subs a (- (count a) 4)))))

(defonce ^:private on-connected-handler
  (atom nil))

(defn set-on-connected-handler!
  [handler]
  (reset! on-connected-handler handler))

(defn clear-on-connected-handler!
  []
  (reset! on-connected-handler nil))

(defn current-on-connected-handler
  []
  @on-connected-handler)

(defn- notify-connected!
  [store address]
  (when-let [handler @on-connected-handler]
    (try
      (handler store address)
      (catch :default err
        (when-let [console (.-console js/globalThis)]
          (.warn console "Wallet connected handler failed" err))))))

(defn- current-agent-storage-mode
  [wallet-state]
  (agent-session/normalize-storage-mode (get-in wallet-state [:agent :storage-mode])))

(defn- current-agent-local-protection-mode
  [wallet-state]
  (agent-session/normalize-local-protection-mode
   (get-in wallet-state [:agent :local-protection-mode])))

(defn- clear-persisted-agent-session!
  [wallet-address storage-mode local-protection-mode]
  (agent-session/clear-persisted-agent-session! wallet-address
                                                storage-mode
                                                local-protection-mode)
  (when (= :passkey (agent-session/normalize-local-protection-mode local-protection-mode))
    (agent-lockbox/delete-locked-session! wallet-address)))

(defn- load-persisted-agent-session
  [wallet-address storage-mode local-protection-mode]
  (or (when-let [cached-session (agent-lockbox/load-unlocked-session wallet-address)]
        (assoc cached-session
               :persisted-kind :raw
               :storage-mode storage-mode
               :local-protection-mode local-protection-mode))
      (agent-session/load-persisted-agent-session-snapshot wallet-address
                                                           storage-mode
                                                           local-protection-mode)))

(defn- persisted-session->agent-state
  [persisted storage-mode local-protection-mode]
  (if (and (map? persisted)
           (seq (:agent-address persisted)))
    {:status (if (= :locked (:persisted-kind persisted)) :locked :ready)
     :agent-address (:agent-address persisted)
     :storage-mode storage-mode
     :local-protection-mode local-protection-mode
     :last-approved-at (:last-approved-at persisted)
     :error nil
     :nonce-cursor (:nonce-cursor persisted)}
    (agent-session/default-agent-state :storage-mode storage-mode
                                       :local-protection-mode local-protection-mode)))

;; ---------- Core EIP-1102 actions -------------------------------------------

(defn set-disconnected! [store]
  (swap! store update-in [:wallet]
         (fn [wallet-state]
           (let [wallet-address (:address wallet-state)
                 storage-mode (current-agent-storage-mode wallet-state)
                 local-protection-mode (current-agent-local-protection-mode wallet-state)
                 passkey-supported? (true? (get-in wallet-state [:agent :passkey-supported?]))]
             (when (seq wallet-address)
               (clear-persisted-agent-session! wallet-address
                                              storage-mode
                                              local-protection-mode)
               (agent-lockbox/clear-unlocked-session! wallet-address))
             {:connected? false
              :address nil
              :chain-id (:chain-id wallet-state) ; keep last chain id
              :connecting? false
              :error nil
              :agent (agent-session/default-agent-state :storage-mode storage-mode
                                                        :local-protection-mode local-protection-mode
                                                        :passkey-supported? passkey-supported?)}))))

(defn set-connected!
  [store addr & {:keys [notify-connected?]
                 :or {notify-connected? false}}]
  (swap! store update-in [:wallet]
         (fn [wallet-state]
           (let [previous-address (:address wallet-state)
                 storage-mode (current-agent-storage-mode wallet-state)
                 local-protection-mode (current-agent-local-protection-mode wallet-state)
                 passkey-supported? (true? (get-in wallet-state [:agent :passkey-supported?]))
                 previous-address* (some-> previous-address str str/lower-case)
                 next-address* (some-> addr str str/lower-case)]
             (when (and (seq previous-address*)
                        (seq next-address*)
                        (not= previous-address* next-address*))
               (clear-persisted-agent-session! previous-address
                                              storage-mode
                                              local-protection-mode)
               (agent-lockbox/clear-unlocked-session! previous-address))
             (let [persisted (load-persisted-agent-session addr
                                                           storage-mode
                                                           local-protection-mode)]
               (-> wallet-state
                   (merge {:connected? true
                           :address addr
                           :connecting? false
                           :error nil})
                   (assoc :agent (persisted-session->agent-state persisted
                                                                 storage-mode
                                                                 local-protection-mode))
                   (assoc-in [:agent :passkey-supported?] passkey-supported?))))))
  (when notify-connected?
    (notify-connected! store addr)))

(defn set-chain! [store chain-id]
  (swap! store assoc-in [:wallet :chain-id] chain-id))

(defn set-error! [store e]
  (swap! store update-in [:wallet] merge {:error (.-message e)
                                          :connecting? false}))

(defn- apply-accounts-connection!
  ([store accounts]
   (apply-accounts-connection! store accounts nil))
  ([store accounts notify-connected?]
   (if (seq accounts)
     (if (some? notify-connected?)
       (set-connected! store (first accounts) :notify-connected? notify-connected?)
       (set-connected! store (first accounts)))
     (set-disconnected! store))))

(defn ->js [m] (clj->js m))

(defn check-connection! [store]
  (if-not (has-provider?)
    (set-disconnected! store)
    (-> (.request (provider) (->js {:method "eth_accounts"}))
        (.then (fn [accounts]
                 (apply-accounts-connection! store accounts)))
        (.catch #(set-error! store %)))))

;; Only call this from a user gesture (button click)
(defn request-connection! [store]
  (if-not (has-provider?)
    (set-disconnected! store)
    (do
      ;; Set connecting state
      (swap! store update-in [:wallet] merge {:connecting? true :error nil})
      ;; Request accounts
      (-> (.request (provider) (->js {:method "eth_requestAccounts"}))
          (.then (fn [accounts]
                   (apply-accounts-connection! store accounts true)))
          (.catch #(set-error! store %))))))

;; ---------- Event listeners (accounts/chain) --------------------------------

(defonce listeners-installed? (atom false))

(defn attach-listeners! [store]
  (reset! provider-listener-store store)
  (let [provider* (provider)
        current-listeners @provider-listener-state]
    (cond
      (nil? provider*)
      (detach-provider-listeners!)

      (not (fn? (some-> provider* .-on)))
      (detach-provider-listeners!)

      (and current-listeners
           (identical? provider* (:provider current-listeners))
           (not (or (fn? (some-> provider* .-removeListener))
                    (fn? (some-> provider* .-off)))))
      ;; Some providers expose `on` without any listener-removal API. Keep the
      ;; existing callbacks attached and only refresh the current store ref.
      (reset! listeners-installed? true)

      :else
      (do
        (detach-provider-listeners!)
        (reset! provider-listener-store store)
        (let [listener-id (js-obj)
              accounts-changed
              (fn [accounts]
                (when-let [active-store (when (identical? listener-id
                                                          (:listener-id @provider-listener-state))
                                          @provider-listener-store)]
                  (apply-accounts-connection! active-store accounts)))
              chain-changed
              (fn [chain-id]
                ;; chainId comes as hex string per EIP-1193, e.g. "0x1"
                (when-let [active-store (when (identical? listener-id
                                                          (:listener-id @provider-listener-state))
                                          @provider-listener-store)]
                  (set-chain! active-store chain-id)))]
          (reset! provider-listener-state {:provider provider*
                                           :listener-id listener-id
                                           :accounts-changed accounts-changed
                                           :chain-changed chain-changed})
          (.on provider* "accountsChanged" accounts-changed)
          (.on provider* "chainChanged" chain-changed)
          (reset! listeners-installed? true))))))

(defn init-wallet! [store]
  (attach-listeners! store)
  (check-connection! store))

;; ---------- Replicant view components ----------------------------------------

(defn wallet-status [state]
  (let [{:keys [connected? address chain-id error connecting?]}
        (get-in state [:wallet])]
    (into
     [:div.flex.items-center.gap-2]
     (cond
       error [[:span.text-red-600 (str "Wallet error: " error)]]
       connecting? [[:span.text-white.opacity-80 "Connecting…"]]
       connected? (cond-> [[:span.inline-block.px-2.py-1.rounded.bg-teal-700.text-teal-100.text-sm
                            (str "Connected " (short-addr address))]]
                    chain-id
                    (conj [:span.text-sm.text-white.opacity-60.ml-1
                           (str " chain " chain-id)]))
       :else [[:span.text-white.opacity-80 "Not connected"]]))))

(defn connect-button [state]
  (let [{:keys [connected? connecting?]} (get-in state [:wallet])]
    [:button.bg-teal-600.hover:bg-teal-700.text-teal-100.px-4.py-2.rounded-lg.font-medium.transition-colors
     {:disabled (or connected? connecting?)
      :on {:click [[:actions/connect-wallet]]}}
     (cond
       connecting? "Connecting…"
       connected?  "Connected"
       :else       "Connect Wallet")]))
