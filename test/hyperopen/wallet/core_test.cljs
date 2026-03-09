(ns hyperopen.wallet.core-test
  (:require [cljs.test :refer-macros [async deftest is]]
            [hyperopen.wallet.agent-session :as agent-session]
            [hyperopen.wallet.core :as wallet]))

(defn- fulfilled-thenable
  [value]
  (let [thenable #js {}]
    (set! (.-then thenable)
          (fn [on-fulfilled]
            (on-fulfilled value)
            thenable))
    (set! (.-catch thenable)
          (fn [_]
            thenable))
    thenable))

(defn- rejected-thenable
  [error]
  (let [thenable #js {}]
    (set! (.-then thenable)
          (fn [_]
            thenable))
    (set! (.-catch thenable)
          (fn [on-rejected]
            (on-rejected error)
            thenable))
    thenable))

(deftest set-disconnected-clears-agent-session-and-resets-state-test
  (let [store (atom {:wallet {:connected? true
                              :address "0xabc"
                              :chain-id "0x1"
                              :agent {:status :ready
                                      :storage-mode :session
                                      :agent-address "0xagent"}}})
        cleared (atom [])]
    (with-redefs [agent-session/default-agent-state
                  (fn [& {:keys [storage-mode]}]
                    {:status :not-ready
                     :storage-mode (or storage-mode :session)})
                  agent-session/clear-agent-session-by-mode!
                  (fn [wallet-address storage-mode]
                    (swap! cleared conj [wallet-address storage-mode]))]
      (wallet/set-disconnected! store)
      (is (= [["0xabc" :session]] @cleared))
      (is (= false (get-in @store [:wallet :connected?])))
      (is (nil? (get-in @store [:wallet :address])))
      (is (= "0x1" (get-in @store [:wallet :chain-id])))
      (is (= :not-ready (get-in @store [:wallet :agent :status]))))))

(deftest set-connected-account-switch-clears-prior-session-and-loads-new-session-test
  (let [store (atom {:wallet {:connected? true
                              :address "0xold"
                              :agent {:status :ready
                                      :storage-mode :session
                                      :agent-address "0xoldagent"}}})
        cleared (atom [])
        loaded (atom [])]
    (with-redefs [agent-session/default-agent-state
                  (fn [& {:keys [storage-mode]}]
                    {:status :not-ready
                     :storage-mode (or storage-mode :session)})
                  agent-session/clear-agent-session-by-mode!
                  (fn [wallet-address storage-mode]
                    (swap! cleared conj [wallet-address storage-mode]))
                  agent-session/load-agent-session-by-mode
                  (fn [wallet-address storage-mode]
                    (swap! loaded conj [wallet-address storage-mode])
                    {:agent-address "0xnewagent"
                     :last-approved-at 1700000003333
                     :nonce-cursor 1700000003333})]
      (wallet/set-connected! store "0xnew")
      (is (= [["0xold" :session]] @cleared))
      (is (= [["0xnew" :session]] @loaded))
      (is (= true (get-in @store [:wallet :connected?])))
      (is (= "0xnew" (get-in @store [:wallet :address])))
      (is (= :ready (get-in @store [:wallet :agent :status])))
      (is (= "0xnewagent" (get-in @store [:wallet :agent :agent-address]))))))

(deftest set-connected-notifies-handler-when-notify-option-enabled-test
  (let [store (atom {:wallet {:connected? false
                              :address nil
                              :connecting? false
                              :agent {:status :not-ready
                                      :storage-mode :session}}})
        notified (atom [])]
    (with-redefs [agent-session/default-agent-state
                  (fn [& {:keys [storage-mode]}]
                    {:status :not-ready
                     :storage-mode (or storage-mode :session)})
                  agent-session/load-agent-session-by-mode
                  (fn [_ _] nil)]
      (wallet/set-on-connected-handler!
       (fn [_ address]
         (swap! notified conj address)))
      (try
        (wallet/set-connected! store "0xabc" :notify-connected? true)
        (is (= ["0xabc"] @notified))
        (finally
          (wallet/clear-on-connected-handler!))))))

(deftest set-connected-skips-handler-when-notify-option-not-set-test
  (let [store (atom {:wallet {:connected? false
                              :address nil
                              :connecting? true
                              :agent {:status :not-ready
                                      :storage-mode :session}}})
        notified (atom [])]
    (with-redefs [agent-session/default-agent-state
                  (fn [& {:keys [storage-mode]}]
                    {:status :not-ready
                     :storage-mode (or storage-mode :session)})
                  agent-session/load-agent-session-by-mode
                  (fn [_ _] nil)]
      (wallet/set-on-connected-handler!
       (fn [_ address]
         (swap! notified conj address)))
      (try
        (wallet/set-connected! store "0xabc")
        (is (= [] @notified))
        (finally
          (wallet/clear-on-connected-handler!))))))

(deftest request-connection-success-notifies-connected-handler-path-test
  (let [store (atom {:wallet {:connected? false
                              :address nil
                              :connecting? false
                              :error nil}})
        connected-calls (atom [])
        disconnected-calls (atom 0)
        original-queue-microtask js/queueMicrotask]
    (set! js/queueMicrotask (fn [f] (f)))
    (try
      (with-redefs [wallet/has-provider? (fn [] true)
                    wallet/provider (fn []
                                      (let [thenable #js {}]
                                        (set! (.-then thenable)
                                              (fn [on-fulfilled]
                                                (on-fulfilled #js ["0xabc"])
                                                thenable))
                                        (set! (.-catch thenable)
                                              (fn [_]
                                                thenable))
                                        #js {:request (fn [_] thenable)}))
                    wallet/set-connected! (fn [store' addr & {:keys [notify-connected?]}]
                                            (swap! connected-calls conj [store' addr notify-connected?]))
                    wallet/set-disconnected! (fn [_]
                                               (swap! disconnected-calls inc))]
        (wallet/request-connection! store)
        (is (= 1 (count @connected-calls)))
        (is (= "0xabc" (second (first @connected-calls))))
        (is (true? (nth (first @connected-calls) 2)))
        (is (= 0 @disconnected-calls)))
      (finally
        (set! js/queueMicrotask original-queue-microtask)))))

(deftest check-connection-success-restores-wallet-without-notify-handler-test
  (let [store (atom {:wallet {:connected? false
                              :address nil
                              :connecting? false
                              :error nil}})
        connected-calls (atom [])
        disconnected-calls (atom 0)
        original-queue-microtask js/queueMicrotask]
    (set! js/queueMicrotask (fn [f] (f)))
    (try
      (with-redefs [wallet/has-provider? (fn [] true)
                    wallet/provider (fn []
                                      (let [thenable #js {}]
                                        (set! (.-then thenable)
                                              (fn [on-fulfilled]
                                                (on-fulfilled #js ["0xabc"])
                                                thenable))
                                        (set! (.-catch thenable)
                                              (fn [_]
                                                thenable))
                                        #js {:request (fn [_] thenable)}))
                    wallet/set-connected! (fn [store' addr & {:keys [notify-connected?]}]
                                            (swap! connected-calls conj [store' addr notify-connected?]))
                    wallet/set-disconnected! (fn [_]
                                               (swap! disconnected-calls inc))]
        (wallet/check-connection! store)
        (is (= 1 (count @connected-calls)))
        (is (= "0xabc" (second (first @connected-calls))))
        (is (nil? (nth (first @connected-calls) 2)))
        (is (= 0 @disconnected-calls)))
      (finally
        (set! js/queueMicrotask original-queue-microtask)))))

(deftest provider-and-short-address-helpers-test
  (let [original-window (aget js/globalThis "window")
        window* (or original-window #js {})
        original-ethereum (aget window* "ethereum")]
    (try
      (aset js/globalThis "window" window*)
      (aset window* "ethereum" #js {:request (fn [_] nil)})
      (is (some? (wallet/provider)))
      (is (true? (wallet/has-provider?)))
      (is (= "0x1234…cdef" (wallet/short-addr "0x1234567890abcdef")))
      (is (nil? (wallet/short-addr nil)))
      (finally
        (aset window* "ethereum" original-ethereum)
        (if (some? original-window)
          (aset js/globalThis "window" original-window)
          (js-delete js/globalThis "window"))))))

(deftest set-disconnected-skips-clearing-session-when-wallet-address-missing-test
  (let [store (atom {:wallet {:connected? true
                              :address nil
                              :chain-id "0x1"
                              :agent {:status :ready
                                      :storage-mode :local}}})
        cleared (atom 0)]
    (with-redefs [agent-session/default-agent-state
                  (fn [& {:keys [storage-mode]}]
                    {:status :not-ready
                     :storage-mode (or storage-mode :local)})
                  agent-session/clear-agent-session-by-mode!
                  (fn [_ _]
                    (swap! cleared inc))]
      (wallet/set-disconnected! store)
      (is (= 0 @cleared))
      (is (= false (get-in @store [:wallet :connected?]))))))

(deftest set-connected-does-not-clear-session-when-address-is-unchanged-test
  (let [store (atom {:wallet {:connected? true
                              :address "0xAbC"
                              :agent {:status :ready
                                      :storage-mode :session}}})
        cleared (atom [])
        loaded (atom [])]
    (with-redefs [agent-session/default-agent-state
                  (fn [& {:keys [storage-mode]}]
                    {:status :not-ready
                     :storage-mode (or storage-mode :session)})
                  agent-session/clear-agent-session-by-mode!
                  (fn [wallet-address storage-mode]
                    (swap! cleared conj [wallet-address storage-mode]))
                  agent-session/load-agent-session-by-mode
                  (fn [wallet-address storage-mode]
                    (swap! loaded conj [wallet-address storage-mode])
                    nil)]
      (wallet/set-connected! store "0xabc")
      (is (empty? @cleared))
      (is (= [["0xabc" :session]] @loaded))
      (is (= "0xabc" (get-in @store [:wallet :address]))))))

(deftest set-chain-and-set-error-update-wallet-fields-test
  (let [store (atom {:wallet {:chain-id nil
                              :connecting? true
                              :error nil}})]
    (wallet/set-chain! store "0xa4b1")
    (wallet/set-error! store (js/Error. "boom"))
    (is (= "0xa4b1" (get-in @store [:wallet :chain-id])))
    (is (= "boom" (get-in @store [:wallet :error])))
    (is (false? (get-in @store [:wallet :connecting?])))))

(deftest check-connection-branches-cover-provider-missing-empty-and-error-test
  (let [store (atom {:wallet {:connected? false
                              :address nil
                              :connecting? false
                              :error nil}})
        disconnected-calls (atom 0)
        connected-calls (atom [])
        error-calls (atom [])]
    (with-redefs [wallet/has-provider? (fn [] false)
                  wallet/set-disconnected! (fn [_]
                                             (swap! disconnected-calls inc))]
      (wallet/check-connection! store)
      (is (= 1 @disconnected-calls)))
    (with-redefs [wallet/has-provider? (fn [] true)
                  wallet/provider (fn []
                                    #js {:request (fn [_]
                                                    (fulfilled-thenable #js []))})
                  wallet/set-connected! (fn [store' addr & {:keys [notify-connected?]}]
                                          (swap! connected-calls conj [store' addr notify-connected?]))
                  wallet/set-disconnected! (fn [_]
                                             (swap! disconnected-calls inc))]
      (wallet/check-connection! store)
      (is (= 2 @disconnected-calls))
      (is (empty? @connected-calls)))
    (with-redefs [wallet/has-provider? (fn [] true)
                  wallet/provider (fn []
                                    #js {:request (fn [_]
                                                    (rejected-thenable (js/Error. "rpc failed")))})
                  wallet/set-error! (fn [_ err]
                                      (swap! error-calls conj (.-message err)))]
      (wallet/check-connection! store)
      (is (= ["rpc failed"] @error-calls)))))

(deftest request-connection-branches-cover-provider-missing-empty-and-error-test
  (let [store (atom {:wallet {:connected? false
                              :address nil
                              :connecting? false
                              :error nil}})
        disconnected-calls (atom 0)
        connected-calls (atom [])
        error-calls (atom [])]
    (with-redefs [wallet/has-provider? (fn [] false)
                  wallet/set-disconnected! (fn [_]
                                             (swap! disconnected-calls inc))]
      (wallet/request-connection! store)
      (is (= 1 @disconnected-calls)))
    (with-redefs [wallet/has-provider? (fn [] true)
                  wallet/provider (fn []
                                    #js {:request (fn [_]
                                                    (fulfilled-thenable #js []))})
                  wallet/set-connected! (fn [store' addr & {:keys [notify-connected?]}]
                                          (swap! connected-calls conj [store' addr notify-connected?]))
                  wallet/set-disconnected! (fn [_]
                                             (swap! disconnected-calls inc))]
      (wallet/request-connection! store)
      (is (= 2 @disconnected-calls))
      (is (empty? @connected-calls)))
    (with-redefs [wallet/has-provider? (fn [] true)
                  wallet/provider (fn []
                                    #js {:request (fn [_]
                                                    (rejected-thenable (js/Error. "request failed")))})
                  wallet/set-error! (fn [_ err]
                                      (swap! error-calls conj (.-message err)))]
      (wallet/request-connection! store)
      (is (= ["request failed"] @error-calls)))))

(deftest notify-connected-catches-handler-errors-and-warns-test
  (let [warns (atom [])
        original-console (.-console js/globalThis)]
    (set! (.-console js/globalThis)
          #js {:warn (fn [message err]
                       (swap! warns conj [message (.-message err)]))})
    (wallet/set-on-connected-handler!
     (fn [_ _]
       (throw (js/Error. "handler boom"))))
    (try
      (@#'hyperopen.wallet.core/notify-connected! (atom {}) "0xabc")
      (is (= [["Wallet connected handler failed" "handler boom"]] @warns))
      (finally
        (wallet/clear-on-connected-handler!)
        (set! (.-console js/globalThis) original-console)))))

(deftest attach-listeners-installs-once-and-forwards-provider-events-test
  (let [store (atom {:wallet {:connected? false
                              :address nil}})
        listeners (atom {})
        set-connected-calls (atom [])
        set-disconnected-calls (atom 0)
        set-chain-calls (atom [])]
    (reset! wallet/listeners-installed? false)
    (with-redefs [wallet/has-provider? (fn [] true)
                  wallet/provider (fn []
                                    #js {:on (fn [event-name callback]
                                               (swap! listeners assoc event-name callback))})
                  wallet/set-connected! (fn [_ addr & _]
                                          (swap! set-connected-calls conj addr))
                  wallet/set-disconnected! (fn [_]
                                             (swap! set-disconnected-calls inc))
                  wallet/set-chain! (fn [_ chain-id]
                                      (swap! set-chain-calls conj chain-id))]
      (wallet/attach-listeners! store)
      (wallet/attach-listeners! store)
      (is (= #{"accountsChanged" "chainChanged"}
             (set (keys @listeners))))
      ((get @listeners "accountsChanged") #js ["0xabc"])
      ((get @listeners "accountsChanged") #js [])
      ((get @listeners "chainChanged") "0xa4b1")
      (is (= ["0xabc"] @set-connected-calls))
      (is (= 1 @set-disconnected-calls))
      (is (= ["0xa4b1"] @set-chain-calls)))
    (reset! wallet/listeners-installed? false)))

(deftest init-wallet-invokes-listener-attach-and-connection-check-test
  (let [store (atom {:wallet {:connected? false}})
        calls (atom [])]
    (with-redefs [wallet/attach-listeners! (fn [store-arg]
                                             (swap! calls conj [:attach (= store store-arg)]))
                  wallet/check-connection! (fn [store-arg]
                                             (swap! calls conj [:check (= store store-arg)]))]
      (wallet/init-wallet! store)
      (is (= [[:attach true] [:check true]] @calls)))))

(deftest provider-override-precedes-window-ethereum-and-reset-clears-listener-state-test
  (let [original-window (aget js/globalThis "window")
        window* (or original-window #js {})
        original-ethereum (aget window* "ethereum")
        override #js {:request (fn [_] nil)}]
    (reset! wallet/listeners-installed? true)
    (try
      (aset js/globalThis "window" window*)
      (aset window* "ethereum" #js {:request (fn [_] "window-provider")})
      (wallet/set-provider-override! override)
      (is (identical? override (wallet/provider)))
      (wallet/reset-provider-listener-state!)
      (is (false? @wallet/listeners-installed?))
      (wallet/clear-provider-override!)
      (is (= "window-provider" (.request (wallet/provider) nil)))
      (finally
        (wallet/clear-provider-override!)
        (aset window* "ethereum" original-ethereum)
        (if (some? original-window)
          (aset js/globalThis "window" original-window)
          (js-delete js/globalThis "window"))))))

(deftest wallet-status-renders-all-main-states-test
  (is (= [:span.text-red-600 "Wallet error: denied"]
         (second (wallet/wallet-status {:wallet {:error "denied"}}))))
  (is (= [:span.text-white.opacity-80 "Connecting…"]
         (second (wallet/wallet-status {:wallet {:connecting? true}}))))
  (let [connected-view (wallet/wallet-status
                        {:wallet {:connected? true
                                  :address "0x1234567890abcdef"
                                  :chain-id "0x1"}})
        chain-pill (nth (second connected-view) 2)]
    (is (= :<> (first (second connected-view))))
    (is (= [:span.text-sm.text-white.opacity-60.ml-1 " chain 0x1"] chain-pill)))
  (is (= [:span.text-white.opacity-80 "Not connected"]
         (second (wallet/wallet-status {:wallet {:connected? false}})))))

(deftest connect-button-reflects-connected-and-connecting-state-test
  (let [ready-button (wallet/connect-button {:wallet {:connected? false
                                                      :connecting? false}})
        connecting-button (wallet/connect-button {:wallet {:connected? false
                                                           :connecting? true}})
        connected-button (wallet/connect-button {:wallet {:connected? true
                                                          :connecting? false}})]
    (is (false? (get-in ready-button [1 :disabled])))
    (is (= "Connect Wallet" (nth ready-button 2)))
    (is (true? (get-in connecting-button [1 :disabled])))
    (is (= "Connecting…" (nth connecting-button 2)))
    (is (true? (get-in connected-button [1 :disabled])))
    (is (= "Connected" (nth connected-button 2)))))
