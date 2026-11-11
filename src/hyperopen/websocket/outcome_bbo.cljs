(ns hyperopen.websocket.outcome-bbo
  "Top-of-book (`bbo`) feed for outcome sides. The selector list and the
   ticket's answer rows quote every contract they show by the one chance rule,
   which needs each side's best bid and ask; only the active side has a full
   l2Book. This runtime holds one `bbo` subscription per wanted side-0 coin
   (the set is decided purely by `asset-selector.query/outcome-top-of-book-coins`)
   and writes each message through
   `market-live-projection/apply-outcome-top-of-book-update`, which mirrors it
   onto side 1.

   The venue's `bbo` channel only pushes when the top of book CHANGES, so a
   quiet book never reports after subscribe. Each newly subscribed coin with no
   top of book yet is therefore seeded ONCE per subscription lifetime from a
   REST `l2Book` snapshot (through the app's info client, so rate limits
   surface as usual), at most `max-concurrent-seeds` at a time; a seed that
   lands after a `bbo` message for the same lifetime is dropped.

   Quotes are batched: every message received within `write-interval-ms` is
   written in one store write (≤4 writes a second however busy the books), a
   message repeating the pending quote queues nothing, and a write that
   changes no bid or ask leaves the state untouched (no render).

   A catalog reload rebuilds the outcome markets without bid/ask. The sync
   that follows it re-applies the last top known for each coin that stays
   subscribed but lost its quote, in one batched write, rather than leaving
   it blank until its book next changes.

   Which answers a row shows depends on their quotes, so while the selector
   list is open the wanted set is recomputed (debounced) after quotes land,
   through the resync function the startup wiring registers."
  (:require [clojure.set :as set]
            [hyperopen.asset-selector.market-index :as market-index]
            [hyperopen.asset-selector.market-live-projection :as market-live-projection]
            [hyperopen.platform :as platform]
            [hyperopen.websocket.client :as ws-client]
            [hyperopen.websocket.market-projection-runtime :as market-projection-runtime]))

(def max-concurrent-seeds
  "REST snapshots in flight at once, so a 60-coin panel never bursts."
  8)

(def ^:private resync-delay-ms 400)

(def write-interval-ms
  "Top-of-book writes are batched: every quote that arrives within this
   window lands in one store write (at most 4 writes a second). Each write
   re-prices the catalog, header and ticket, so a busy book must not write
   per message."
  250)

(def ^:private default-runtime
  {:coins #{}
   :generation {}
   :bbo-seen #{}
   :seed-queue []
   :in-flight 0})

(defonce ^:private runtime
  (atom default-runtime))

(defonce ^:private hooks
  ;; {:store :request-snapshot! :resync!} registered by `init!`.
  (atom {}))

(defonce ^:private resync-timer
  (atom nil))

(defonce ^:private pending-tops
  ;; coin -> {:bid :ask} received but not yet written to the store.
  (atom {}))

(defonce ^:private known-tops
  ;; coin -> the last top received for a subscribed coin. A catalog reload
  ;; rebuilds the outcome markets without quotes, and the venue pushes `bbo`
  ;; only on change, so these are re-applied after it (no network).
  (atom {}))

(defonce ^:private flush-timer
  (atom nil))

(defonce ^:private last-flush-ms
  (atom nil))

;; ---------------------------------------------------------------------------
;; Pure

(defn- level-price
  [level]
  (when (map? level) (or (:px level) (get level "px"))))

(defn bbo-message->top
  "{:coin :bid :ask} from a `bbo` channel message (`{:channel \"bbo\" :data
   {:coin :time :bbo [bid-level ask-level]}}`, a level being nil for an empty
   side), or nil when the message is not one."
  [message]
  (when (and (map? message) (= "bbo" (:channel message)))
    (let [{:keys [coin bbo]} (:data message)]
      (when (and (string? coin) (sequential? bbo))
        {:coin coin
         :bid (level-price (first bbo))
         :ask (level-price (second bbo))}))))

(defn snapshot->top
  "{:bid :ask} from an `l2Book` info snapshot (`{:levels [bids asks]}`, best
   first). An empty or one-sided book gives nil for the missing side, which
   the projection writes as a known-empty side (\"No market\"/\"Not traded\",
   never a dash). Nil when the response is not a book."
  [snapshot]
  (let [levels (when (map? snapshot) (or (:levels snapshot) (get snapshot "levels")))]
    (when (sequential? levels)
      {:bid (level-price (first (first levels)))
       :ask (level-price (first (second levels)))})))

(defn subscription-diff
  "Pure: which coins to subscribe and unsubscribe to go from `current` to
   `desired`, each sorted."
  [current desired]
  (let [current* (set current)
        desired* (set (remove nil? desired))]
    {:subscribe (vec (sort (set/difference desired* current*)))
     :unsubscribe (vec (sort (set/difference current* desired*)))}))

(defn top-of-book-known-coins
  "Side coins of the catalog's outcome markets that already carry a fed
   bid/ask (either key)."
  [state]
  (into #{}
        (comp (filter #(= :outcome (:market-type %)))
              (mapcat (fn [market]
                        (concat (:outcome-sides market)
                                (mapcat :sides (:question-options market)))))
              (filter #(or (contains? % :bid) (contains? % :ask)))
              (keep :coin))
        (vals (get-in state [:asset-selector :market-by-key]))))

(defn- side-quoted?
  [market coin]
  (some #(and (= coin (:coin %)) (or (contains? % :bid) (contains? % :ask)))
        (concat (:outcome-sides market) (mapcat :sides (:question-options market)))))

(defn unquoted-coins
  "Pure: of `coins`, those some catalog market holds without a fed bid/ask
   on that side (as after a catalog reload). A coin no market holds is not
   listed: there is nothing to write it onto."
  [state coins]
  (let [index (market-index/outcome-token-index (get-in state [:asset-selector :market-by-key] {}))]
    (vec (filter (fn [coin]
                   (let [holders (get index coin)]
                     (and (seq holders) (not-any? #(side-quoted? % coin) holders))))
                 coins))))

(defn seed-coins
  "Pure seeding decision: of the coins just subscribed, those whose side has
   no top of book yet (a coin already fed — by a bbo or an earlier seed — is
   not fetched again)."
  [state subscribed-coins]
  (let [known (top-of-book-known-coins state)]
    (vec (remove known subscribed-coins))))

(defn accept-seed?
  "A seed result is written only for the subscription lifetime it was asked
   for, and only if no `bbo` message has arrived in that lifetime."
  [{:keys [coins generation bbo-seen]} coin seed-generation]
  (and (contains? coins coin)
       (= seed-generation (get generation coin))
       (not (contains? bbo-seen coin))))

(defn apply-subscription-change
  "Runtime after subscribing `subscribe` and unsubscribing `unsubscribe`:
   a new subscription starts a new lifetime (generation) with no bbo seen,
   and its seeds join the queue."
  [rt {:keys [subscribe unsubscribe]} seeds]
  (let [rt* (merge default-runtime rt)
        dropped (set unsubscribe)]
    (-> rt*
        (update :coins #(-> (set/difference % dropped) (into subscribe)))
        (update :bbo-seen #(set/difference % dropped (set subscribe)))
        (update :generation (fn [g] (reduce #(update %1 %2 (fnil inc 0)) g subscribe)))
        (update :seed-queue (fn [queue]
                              (into (vec (remove dropped queue)) seeds))))))

;; ---------------------------------------------------------------------------
;; Effects

(defn enqueue-top
  "Pure: `pending` (coin -> top not yet written) after a message for `coin`.
   A message repeating the pending top changes nothing (identical map back)."
  [pending coin top]
  (let [top* (select-keys top [:bid :ask])]
    (if (= top* (get pending coin))
      pending
      (assoc pending coin top*))))

(defn next-flush-delay-ms
  "Pure throttle: how long to wait before the next top-of-book write, given
   when the last one went out. The first quote after a quiet spell goes out
   at once; a busy feed writes at most once per `write-interval-ms`."
  [last-flush-ms now-ms]
  (if (number? last-flush-ms)
    (max 0 (- (+ last-flush-ms write-interval-ms) now-ms))
    0))

(defn- selector-open?
  [store]
  (= :asset-selector (get-in @store [:asset-selector :visible-dropdown])))

(defn- schedule-resync!
  "Which answers a list row shows follows their quotes, so a quote landing
   re-runs the subscription sync — but only while the selector list is on
   screen. With it closed the wanted coins are the open event's answers,
   which no quote changes, so there is nothing to re-diff."
  [store]
  (when-let [resync! (:resync! @hooks)]
    (when (and store (selector-open? store) (not @resync-timer))
      (reset! resync-timer
              (js/setTimeout (fn []
                               (reset! resync-timer nil)
                               (resync!))
                             resync-delay-ms)))))

(defn- apply-pending-tops
  "Drain every pending top into `state` in one projection pass. Drained when
   the frame applies, not when it is queued, so tops that arrive while a
   frame is pending (or while a hidden tab holds frames back) join it."
  [state]
  (let [tops @pending-tops]
    (reset! pending-tops {})
    (market-live-projection/apply-outcome-top-of-book-updates state tops)))

(defn- flush-tops!
  "Write every pending top in ONE store write (one projection pass over the
   catalog), however many messages arrived since the last write."
  [store]
  (reset! flush-timer nil)
  (when (seq @pending-tops)
    (reset! last-flush-ms ((or (:now-ms @hooks) platform/now-ms)))
    (market-projection-runtime/queue-market-projection!
     (cond-> {:store store
              :coalesce-key [:outcome-bbo]
              :apply-update-fn apply-pending-tops}
       (:schedule-frame! @hooks) (assoc :schedule-animation-frame! (:schedule-frame! @hooks))))
    (schedule-resync! store)))

(defn- write-top!
  "Queue `top` for `coin`; the store is written by `flush-tops!`, at most
   once per `write-interval-ms`."
  [store coin top]
  (swap! known-tops assoc coin (select-keys top [:bid :ask]))
  (let [before @pending-tops
        after (swap! pending-tops enqueue-top coin top)]
    (when (and (not (identical? before after))
               (nil? @flush-timer))
      (let [schedule-flush! (or (:schedule-flush! @hooks) platform/set-timeout!)
            now-ms (or (:now-ms @hooks) platform/now-ms)
            delay-ms (next-flush-delay-ms @last-flush-ms (now-ms))]
        (reset! flush-timer
                (or (schedule-flush! #(flush-tops! store) delay-ms) ::scheduled))))))

(declare pump-seeds!)

(defn- finish-seed!
  []
  (swap! runtime update :in-flight #(max 0 (dec (or % 0))))
  (pump-seeds!))

(defn- start-seed!
  [coin]
  (let [{:keys [store request-snapshot!]} @hooks
        seed-generation (get-in @runtime [:generation coin])]
    (if-not (and store (fn? request-snapshot!))
      (finish-seed!)
      (-> (js/Promise.resolve
           (request-snapshot! coin {:priority :low :cache-ttl-ms 1}))
          (.then (fn [snapshot]
                   (when-let [top (snapshot->top snapshot)]
                     (when (accept-seed? @runtime coin seed-generation)
                       (write-top! store coin top)))))
          (.catch (fn [_] nil))
          (.finally finish-seed!)))))

(defn- pump-seeds!
  []
  (let [started (volatile! [])]
    (swap! runtime
           (fn [rt]
             (vreset! started [])
             (loop [rt* rt]
               (let [coin (first (:seed-queue rt*))]
                 (if (and coin (< (:in-flight rt*) max-concurrent-seeds))
                   (let [rt** (update rt* :seed-queue (comp vec rest))]
                     (if (contains? (:coins rt**) coin)
                       (do (vswap! started conj coin)
                           (recur (update rt** :in-flight inc)))
                       (recur rt**)))
                   rt*)))))
    (doseq [coin @started]
      (start-seed! coin))))

(defn create-bbo-data-handler
  [store]
  (fn [message]
    (when-let [{:keys [coin] :as top} (bbo-message->top message)]
      (swap! runtime update :bbo-seen (fnil conj #{}) coin)
      (when store
        (write-top! store coin top)))))

(defn- send-subscription!
  [method coin]
  (ws-client/send-message! {:method method
                            :subscription {:type "bbo" :coin coin}}))

(defn- reapply-known-tops!
  "Queue the last known top of each retained coin whose side lost its quote
   in `state` (a catalog reload); they land in one batched write."
  [store state retained-coins]
  (let [tops @known-tops
        candidates (filter #(contains? tops %) retained-coins)]
    (when (and store (map? state) (seq candidates))
      (doseq [coin (unquoted-coins state candidates)]
        (write-top! store coin (get tops coin))))))

(defn restore-known-tops!
  "After a catalog load: re-apply the last known top of every subscribed
   coin the reloaded catalog holds without a quote (one batched write)."
  [store]
  (when store
    (reapply-known-tops! store @store (:coins @runtime))))

(defn sync-subscriptions!
  "Hold exactly `desired-coins` (already capped and catalog-checked by the
   caller) as `bbo` subscriptions, and seed the new ones that have no top of
   book yet in `state` (the app state the decision reads)."
  ([desired-coins] (sync-subscriptions! desired-coins nil))
  ([desired-coins state]
   (let [{:keys [subscribe unsubscribe] :as diff} (subscription-diff (:coins @runtime) desired-coins)
         seeds (if (map? state) (seed-coins state subscribe) [])]
     (doseq [coin unsubscribe]
       (send-subscription! "unsubscribe" coin))
     (doseq [coin subscribe]
       (send-subscription! "subscribe" coin))
     (swap! runtime apply-subscription-change diff seeds)
     (swap! known-tops #(apply dissoc % unsubscribe))
     (reapply-known-tops! (:store @hooks) state (remove (set subscribe) desired-coins))
     (pump-seeds!))))

(defn get-subscribed-coins
  []
  (:coins @runtime))

(defn runtime-snapshot
  []
  @runtime)

(defn reset-runtime!
  "Tests only."
  []
  (when-let [t @resync-timer] (js/clearTimeout t))
  (reset! resync-timer nil)
  (when-let [t @flush-timer] (when (number? t) (js/clearTimeout t)))
  (reset! flush-timer nil)
  (reset! pending-tops {})
  (reset! known-tops {})
  (reset! last-flush-ms nil)
  (reset! runtime default-runtime))

(defn register-hooks!
  "`request-snapshot!` (coin opts) -> promise of an l2Book info response;
   `resync!` () recomputes and syncs the wanted coins. Tests may also pass
   `schedule-flush!` (f delay-ms), `schedule-frame!` (f) and `now-ms` ()."
  [hooks*]
  (swap! hooks merge hooks*))

(defn init!
  ([store] (init! store {}))
  ([store hooks*]
   (register-hooks! (assoc hooks* :store store))
   (ws-client/register-handler! "bbo" (create-bbo-data-handler store))))
