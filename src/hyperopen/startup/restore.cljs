(ns hyperopen.startup.restore
  (:require [hyperopen.wallet.agent-session :as agent-session]))

(defn restore-agent-storage-mode!
  [store]
  (let [storage-mode (agent-session/load-storage-mode-preference)]
    (swap! store assoc-in [:wallet :agent :storage-mode] storage-mode)))

(defn restore-active-asset!
  [store {:keys [connected?-fn dispatch! load-active-market-display-fn]}]
  (when (nil? (:active-asset @store))
    (let [stored-asset (js/localStorage.getItem "active-asset")
          asset (if (seq stored-asset) stored-asset "BTC")
          cached-market (load-active-market-display-fn asset)]
      (swap! store
             (fn [state]
               (cond-> (assoc state :active-asset asset :selected-asset asset)
                 (map? cached-market) (assoc :active-market cached-market))))
      (when-not (seq stored-asset)
        (js/localStorage.setItem "active-asset" asset))
      (when (connected?-fn)
        (dispatch! store nil [[:actions/subscribe-to-asset asset]])))))
