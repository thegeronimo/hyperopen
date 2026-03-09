(ns hyperopen.wallet.core
  (:require [clojure.string :as str]
            [hyperopen.wallet.agent-session :as agent-session]))

;; ---------- Provider helpers -------------------------------------------------

(declare listeners-installed?)

(defonce ^:private provider-override
  (atom nil))

(defn set-provider-override!
  [provider]
  (reset! provider-override provider)
  provider)

(defn clear-provider-override!
  []
  (reset! provider-override nil)
  true)

(defn reset-provider-listener-state!
  []
  (reset! listeners-installed? false)
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

(defn- persisted-session->agent-state
  [persisted storage-mode]
  (if (and (map? persisted)
           (seq (:agent-address persisted)))
    {:status :ready
     :agent-address (:agent-address persisted)
     :storage-mode storage-mode
     :last-approved-at (:last-approved-at persisted)
     :error nil
     :nonce-cursor (:nonce-cursor persisted)}
    (agent-session/default-agent-state :storage-mode storage-mode)))

;; ---------- Core EIP-1102 actions -------------------------------------------

(defn set-disconnected! [store]
  (swap! store update-in [:wallet]
         (fn [wallet-state]
           (let [wallet-address (:address wallet-state)
                 storage-mode (current-agent-storage-mode wallet-state)]
             (when (seq wallet-address)
               (agent-session/clear-agent-session-by-mode! wallet-address storage-mode))
             {:connected? false
              :address nil
              :chain-id (:chain-id wallet-state) ; keep last chain id
              :connecting? false
              :error nil
              :agent (agent-session/default-agent-state :storage-mode storage-mode)}))))

(defn set-connected!
  [store addr & {:keys [notify-connected?]
                 :or {notify-connected? false}}]
  (swap! store update-in [:wallet]
         (fn [wallet-state]
           (let [previous-address (:address wallet-state)
                 storage-mode (current-agent-storage-mode wallet-state)
                 previous-address* (some-> previous-address str str/lower-case)
                 next-address* (some-> addr str str/lower-case)]
             (when (and (seq previous-address*)
                        (seq next-address*)
                        (not= previous-address* next-address*))
               (agent-session/clear-agent-session-by-mode! previous-address storage-mode))
             (let [persisted (agent-session/load-agent-session-by-mode addr storage-mode)]
               (-> wallet-state
                   (merge {:connected? true
                           :address addr
                           :connecting? false
                           :error nil})
                   (assoc :agent (persisted-session->agent-state persisted storage-mode)))))))
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
  (when (and (has-provider?) (not @listeners-installed?))
    (reset! listeners-installed? true)
    (.on (provider) "accountsChanged"
         (fn [accounts]
           (apply-accounts-connection! store accounts)))
    (.on (provider) "chainChanged"
         (fn [chain-id]
           ;; chainId comes as hex string per EIP-1193, e.g. "0x1"
           (set-chain! store chain-id)))))

(defn init-wallet! [store]
  (attach-listeners! store)
  (check-connection! store))

;; ---------- Replicant view components ----------------------------------------

(defn wallet-status [state]
  (let [{:keys [connected? address chain-id error connecting?]}
        (get-in state [:wallet])]
    [:div.flex.items-center.gap-2
     (cond
       error            [:span.text-red-600 (str "Wallet error: " error)]
       connecting?      [:span.text-white.opacity-80 "Connecting…"]
       connected?       [:<>
                         [:span.inline-block.px-2.py-1.rounded.bg-teal-700.text-teal-100.text-sm
                          (str "Connected " (short-addr address))]
                         (when chain-id
                           [:span.text-sm.text-white.opacity-60.ml-1
                            (str " chain " chain-id)])]
       :else            [:span.text-white.opacity-80 "Not connected"])]))

(defn connect-button [state]
  (let [{:keys [connected? connecting?]} (get-in state [:wallet])]
    [:button.bg-teal-600.hover:bg-teal-700.text-teal-100.px-4.py-2.rounded-lg.font-medium.transition-colors
     {:disabled (or connected? connecting?)
      :on {:click [[:actions/connect-wallet]]}}
     (cond
       connecting? "Connecting…"
       connected?  "Connected"
       :else       "Connect Wallet")]))
