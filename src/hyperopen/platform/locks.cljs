(ns hyperopen.platform.locks
  "Exclusive async critical sections shared by every tab of this origin.

  Uses the Web Locks API (`navigator.locks`) when present. Without it, an
  in-tab promise queue serializes callers in this tab only, and
  `coordination-kind` reports `:in-tab-only` so callers never claim cross-tab
  safety they do not have.")

(defonce ^:private in-tab-tails (atom {}))
(defonce ^:private in-tab-outstanding (atom {}))

(defn- web-locks []
  (let [locks (some-> js/globalThis .-navigator .-locks)]
    (when (and locks (fn? (.-request locks))) locks)))

(defn coordination-kind
  "`:web-locks` when cross-tab locks are available, else `:in-tab-only`."
  []
  (if (web-locks) :web-locks :in-tab-only))

(defn- in-tab-exclusive!
  "Queue `f` behind every earlier holder of `lock-name` in this tab. The lock is
  outstanding from the moment it is queued until `f` settles."
  [lock-name f]
  (swap! in-tab-outstanding update lock-name (fnil inc 0))
  (let [prev (get @in-tab-tails lock-name (js/Promise.resolve nil))
        run (-> prev
                (.then (fn [_] (f)))
                (.finally (fn [] (swap! in-tab-outstanding update lock-name dec))))]
    ;; The tail never rejects, so one failed holder does not poison the queue.
    (swap! in-tab-tails assoc lock-name (.catch run (fn [_] nil)))
    run))

(defn with-exclusive-lock!
  "Run `f` (returning a value or a Promise) while holding the exclusive lock
  `lock-name`, after every earlier holder. Resolves or rejects with f's result."
  [lock-name f]
  (if-let [locks (web-locks)]
    (.request locks lock-name #js {:mode "exclusive"} (fn [_lock] (f)))
    (in-tab-exclusive! lock-name f)))

(defn with-exclusive-lock-kind!
  "`with-exclusive-lock!` that calls `(f kind)` with the coordination actually
  in force: :web-locks, or :in-tab-only when Web Locks is missing or the
  request itself fails (opaque origin, inactive document). A rejection from
  `f` itself is never retried."
  [lock-name f]
  (if-let [locks (web-locks)]
    (let [started? (atom false)]
      (-> (js/Promise.resolve nil)
          (.then (fn [_]
                   (.request locks lock-name #js {:mode "exclusive"}
                             (fn [_lock] (reset! started? true) (f :web-locks)))))
          (.catch (fn [e]
                    (if @started?
                      (throw e)
                      (in-tab-exclusive! lock-name #(f :in-tab-only)))))))
    (in-tab-exclusive! lock-name #(f :in-tab-only))))

(defn with-exclusive-lock-if-available!
  "Run `f` only if `lock-name` is free right now, never waiting. Resolves to
  {:acquired? true :value v} or {:acquired? false}."
  [lock-name f]
  (if-let [locks (web-locks)]
    (.request locks lock-name #js {:mode "exclusive" :ifAvailable true}
              (fn [lock]
                (if (nil? lock)
                  {:acquired? false}
                  (-> (js/Promise.resolve nil)
                      (.then (fn [_] (f)))
                      (.then (fn [v] {:acquired? true :value v}))))))
    (if (pos? (get @in-tab-outstanding lock-name 0))
      (js/Promise.resolve {:acquired? false})
      (-> (in-tab-exclusive! lock-name f)
          (.then (fn [v] {:acquired? true :value v}))))))

(defn reset-in-tab-state!
  "Test helper: forget in-tab queues."
  []
  (reset! in-tab-tails {})
  (reset! in-tab-outstanding {}))
