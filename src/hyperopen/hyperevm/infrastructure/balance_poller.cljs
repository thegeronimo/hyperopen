(ns hyperopen.hyperevm.infrastructure.balance-poller
  "Keeps HyperEVM balances fresh in the background.

   - A store watcher dispatches a refresh, debounced, when the cheap
     `watch-fingerprint` changes: the shown or owner address, whether each
     has an entry (an account reset clears them), spotMeta's linked-token
     count, or whether a HyperEVM surface is up.
   - An interval ticks every 4 s. It refreshes every 30 s while a surface is
     up and the document is visible, and on every tick while a fast poll
     runs (after a transfer).

   Every refresh goes through `:actions/refresh-hyperevm-balances`, whose pure
   refresh plan applies freshness, the loading guard, the receipt-wait pause
   and the rate-limit backoff (30 s -> 60 s -> 120 s), so a tick is cheap
   when nothing is due. The poller never forces a refresh: a fast poll
   already drops the freshness window, and forcing would also skip the
   loading guard and the receipt-wait pause.

   The public RPC allows 100 requests per minute per IP and each refresh is
   one request per address, so the steady state is 2-4 requests a minute.

   The same tick keeps an open HyperEVM -> Core Transfer draft's HyperCore
   reads (bridge balance, activation) current. The injected
   `capacity-refresh-index-fn` (the funding context's
   `transfer-capacity-refresh-index`) names the token whose reads are due,
   and the poller dispatches `:actions/refresh-hyperevm-bridge-capacity` for
   it at most once per `bridge/core-capacity-retry-ms`. These are HyperCore
   info reads, not HyperEVM RPC calls.

   It also settles HyperEVM -> Core moves whose foreground receipt wait
   ended in \"pending\": while any exists
   (`transfer-state/pending-in-flight`), it dispatches
   `:actions/check-hyperevm-in-flight` at most once per
   `in-flight-check-ms`, which reads each pending hash's receipt (one RPC
   call) until the transaction resolves and its entry is cleared. The gap
   widens as the move ages (`transfer-state/in-flight-check-interval-ms`):
   a transaction replaced or dropped in the wallet never gets a receipt."
  (:require [hyperopen.hyperevm.domain.balances :as balances]
            [hyperopen.hyperevm.domain.bridge :as bridge]
            [hyperopen.hyperevm.domain.transfer-state :as transfer-state]
            [hyperopen.platform :as platform]))

(def ^:private watch-key ::hyperevm-balance-poller)

(def default-debounce-ms 250)

(def default-tick-ms
  "The fast-poll cadence, and the resolution of the normal one."
  4000)

(def default-interval-ms 30000)

(def default-in-flight-check-ms
  "Between receipt reads of a move left confirming in the background."
  8000)

(defonce ^:private enabled-state
  (atom true))

(defonce ^:private installed-timer
  (atom nil))

(defn enabled?
  []
  (true? @enabled-state))

(defn set-enabled!
  "Debug kill switch (reachable through HYPEROPEN_DEBUG), so browser tests
   can stop background HyperEVM reads deterministically. Returns the new
   value."
  [enabled?*]
  (reset! enabled-state (boolean enabled?*)))

(defn- document-visible?
  []
  (or (not (exists? js/document))
      (not= "hidden" (.-visibilityState js/document))))

(defn- stop-installed-timer!
  []
  (when-let [{:keys [id clear-interval-fn]} @installed-timer]
    (clear-interval-fn id)
    (reset! installed-timer nil)))

(defn install-hyperevm-balance-poller!
  "Install the watcher and interval. Timers, clock, visibility and the kill
   switch are injectable; re-installing (dev reload) replaces the previous
   interval instead of stacking another."
  [{:keys [store dispatch! now-ms-fn set-timeout-fn set-interval-fn clear-interval-fn
           visible?-fn enabled?-fn debounce-ms tick-ms interval-ms
           capacity-refresh-index-fn capacity-retry-ms in-flight-check-ms]
    :or {now-ms-fn platform/now-ms
         set-timeout-fn platform/set-timeout!
         set-interval-fn platform/set-interval!
         clear-interval-fn platform/clear-interval!
         visible?-fn document-visible?
         enabled?-fn enabled?
         debounce-ms default-debounce-ms
         tick-ms default-tick-ms
         interval-ms default-interval-ms
         capacity-refresh-index-fn (constantly nil)
         capacity-retry-ms bridge/core-capacity-retry-ms
         in-flight-check-ms default-in-flight-check-ms}}]
  (let [pending? (atom false)
        last-fingerprint (atom ::none)
        last-poll-ms (atom nil)
        last-capacity-ms (atom nil)
        last-in-flight-ms (atom nil)
        refresh! (fn [opts]
                   (when (enabled?-fn)
                     (dispatch! store nil [[:actions/refresh-hyperevm-balances
                                            (assoc opts :now-ms (now-ms-fn))]])))
        fire! (fn []
                (reset! pending? false)
                (refresh! {}))
        refresh-capacity! (fn [state now]
                            (when (and (enabled?-fn)
                                       (visible?-fn)
                                       (or (nil? @last-capacity-ms)
                                           (>= (- now @last-capacity-ms) capacity-retry-ms)))
                              (when-let [index (capacity-refresh-index-fn state now)]
                                (reset! last-capacity-ms now)
                                (dispatch! store nil [[:actions/refresh-hyperevm-bridge-capacity
                                                       index]]))))
        check-in-flight! (fn [state now]
                           (when-let [gap-ms (and (enabled?-fn)
                                                  (visible?-fn)
                                                  (not (balances/backing-off? state now))
                                                  (transfer-state/in-flight-check-interval-ms
                                                   state now in-flight-check-ms))]
                             (when (or (nil? @last-in-flight-ms)
                                       (>= (- now @last-in-flight-ms) gap-ms))
                               (reset! last-in-flight-ms now)
                               (dispatch! store nil [[:actions/check-hyperevm-in-flight]]))))
        tick! (fn []
                (let [state @store
                      now (now-ms-fn)]
                  (cond
                    (balances/fast-polling? state now)
                    (refresh! {})

                    (and (balances/surface-active? state)
                         (visible?-fn)
                         (or (nil? @last-poll-ms)
                             (>= (- now @last-poll-ms) interval-ms)))
                    (do (reset! last-poll-ms now)
                        (refresh! {})))
                  (refresh-capacity! state now)
                  (check-in-flight! state now)))]
    (stop-installed-timer!)
    (reset! installed-timer {:id (set-interval-fn tick! tick-ms)
                             :clear-interval-fn clear-interval-fn})
    (remove-watch store watch-key)
    (add-watch store watch-key
               (fn [_ _ _ new-state]
                 (let [fingerprint (balances/watch-fingerprint new-state)]
                   (when (not= fingerprint @last-fingerprint)
                     (reset! last-fingerprint fingerprint)
                     (when-not @pending?
                       (reset! pending? true)
                       (set-timeout-fn fire! debounce-ms))))))
    store))
