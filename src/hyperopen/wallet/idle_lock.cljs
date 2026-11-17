(ns hyperopen.wallet.idle-lock
  "Lock a passkey-protected trading session after a period without input.

   With \"Lock trading with passkey\" on, the trading key is decrypted once and
   then kept in memory until the page is reloaded. A key that sits unlocked in
   a tab nobody is watching can still sign orders, so after `idle-limit-ms`
   without pointer, key, wheel or touch input the key is dropped from memory
   and the next signature asks for the passkey again. Open orders are not affected; they live on Hyperliquid.

   Sessions without passkey protection are never touched: their key is
   restored without user interaction anyway, so locking it would add friction
   without adding protection."
  (:require [hyperopen.platform :as platform]
            [hyperopen.wallet.agent-lockbox :as agent-lockbox]
            [hyperopen.wallet.agent-runtime :as agent-runtime]
            [hyperopen.wallet.agent-session :as agent-session]))

(def check-interval-ms
  30000)

(def ^:private activity-events
  ["pointerdown" "keydown" "wheel" "touchstart"])

(defonce ^:private last-activity-ms
  (atom nil))

(defonce ^:private installed?
  (atom false))

(def idle-limit-ms
  "Idle time after which a passkey-protected trading key is locked."
  (* 30 60 1000))

(defn passkey-session-unlocked?
  "True when the connected wallet has a passkey-protected trading session
   that is currently unlocked."
  [state]
  (let [wallet (:wallet state)
        agent (:agent wallet)]
    (boolean
     (and (:connected? wallet)
          (seq (:address wallet))
          (= :ready (:status agent))
          (= :local (agent-session/normalize-storage-mode (:storage-mode agent)))
          (= :passkey (agent-session/normalize-local-protection-mode
                       (:local-protection-mode agent)))))))

(defn should-lock?
  "True when the unlocked passkey session has been idle for at least the
   inactivity limit."
  [{:keys [state now-ms last-activity-ms]}]
  (boolean
   (and (number? now-ms)
        (number? last-activity-ms)
        (passkey-session-unlocked? state)
        (>= (- now-ms last-activity-ms) idle-limit-ms))))

(defn- lock!
  [store]
  (agent-runtime/lock-agent-trading!
   {:store store
    :normalize-storage-mode agent-session/normalize-storage-mode
    :normalize-local-protection-mode agent-session/normalize-local-protection-mode
    :clear-unlocked-session! agent-lockbox/clear-unlocked-session!}))

(defn check!
  "Lock the trading key when the page has been idle for too long. Returns true
   when it locked."
  ([store]
   (check! store {}))
  ([store {:keys [now-ms-fn lock-fn]
           :or {now-ms-fn platform/now-ms
                lock-fn lock!}}]
   (if (should-lock? {:state @store
                      :now-ms (now-ms-fn)
                      :last-activity-ms @last-activity-ms})
     (do
       (lock-fn store)
       true)
     false)))

(defn note-activity!
  ([]
   (note-activity! (platform/now-ms)))
  ([now-ms]
   (reset! last-activity-ms now-ms)))

(defn install!
  "Start watching for inactivity. Safe to call more than once. The limit is
   read from the store on every check, so changing the setting needs no
   reinstall. Each input event checks before it counts as activity, so the
   first click after a long absence locks instead of extending the session.
   Timers are throttled in background tabs, so the tab becoming visible
   triggers a check as well."
  [store]
  (when (and (not @installed?)
             (exists? js/window)
             (fn? (.-addEventListener js/window)))
    (reset! installed? true)
    (note-activity!)
    (let [on-activity (fn [_]
                        (check! store)
                        (note-activity!))]
      (doseq [event-name activity-events]
        (.addEventListener js/window event-name on-activity
                           #js {:capture true :passive true})))
    (when (exists? js/document)
      (.addEventListener js/document "visibilitychange"
                         (fn [_]
                           (when (= "visible" (.-visibilityState js/document))
                             (check! store)))))
    (platform/set-interval! #(check! store) check-interval-ms)
    true))
