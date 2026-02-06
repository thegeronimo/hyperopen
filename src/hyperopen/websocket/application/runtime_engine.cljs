(ns hyperopen.websocket.application.runtime-engine
  (:require [cljs.core.async :as async :refer [<! >! chan close! put!]])
  (:require-macros [cljs.core.async.macros :refer [go-loop]]))

(defn safe-put! [channel value]
  (when channel
    (try
      (boolean (put! channel value))
      (catch :default _
        false))))

(defn start-engine!
  [{:keys [initial-state
           reducer
           interpret-effect!
           context
           mailbox-size
           effects-size
           now-ms]
    :or {mailbox-size 4096
         effects-size 4096
         now-ms (fn [] (.now js/Date))}}]
  (let [mailbox-ch (chan (async/buffer mailbox-size))
        effects-ch (chan (async/buffer effects-size))
        state (atom initial-state)
        closed? (atom false)
        dispatch! (fn [msg]
                    (safe-put! mailbox-ch
                               (if (number? (:ts msg))
                                 msg
                                 (assoc msg :ts (now-ms)))))]
    (go-loop []
      (when-let [msg (<! mailbox-ch)]
        (try
          (let [result (reducer @state msg)
                resolved-state (or (:next-state result) (:state result) @state)
                effects (:effects result)]
            (reset! state resolved-state)
            (doseq [fx (or effects [])]
              (>! effects-ch fx)))
          (catch :default e
            (>! effects-ch {:fx/type :fx/dead-letter
                            :reason :engine-reducer-failure
                            :error e
                            :message msg})))
        (recur)))
    (go-loop []
      (when-let [fx (<! effects-ch)]
        (try
          (interpret-effect! (assoc context
                                    :dispatch! dispatch!
                                    :get-state (fn [] @state))
                             fx)
          (catch :default e
            (println "Runtime effect interpreter failed:" e "for effect" fx)))
        (recur)))
    {:mailbox-ch mailbox-ch
     :effects-ch effects-ch
     :state state
     :dispatch! dispatch!
     :stop! (fn []
              (when-not @closed?
                (reset! closed? true)
                (close! mailbox-ch)
                (close! effects-ch)))}))

(defn dispatch! [engine msg]
  (when-let [f (:dispatch! engine)]
    (f msg)))

(defn stop-engine! [engine]
  (when-let [f (:stop! engine)]
    (f)))
