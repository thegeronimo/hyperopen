(ns hyperopen.vol-range.infrastructure.executor
  "Vol Range lifecycle executor: reconciliation, reload recovery and the
  helpers shared by placement and cancellation (HO-SPEC-014 v2.1 sections
  7.6, 8.6, 9).

  Every collaborator is injected as `deps`:

  - :post-info!       shared info request function (reads)
  - :journal          {:commit! (fn [records] -> Promise) :load-all! (fn [] -> Promise)}
  - :send!            (fn [action opts] -> Promise of a delivery result), the
                      `delivery/sign-and-post-with-delivery!` contract
  - :admission!       `action-coordinator/with-account-admission!` contract
  - :live!            (fn [] -> {:input planner-input :raw-account-mode
                      :read-only? :execution-enabled? :agent-ready?})
  - :now-ms!, :random-id! (fn [prefix]), :cloid-suffixes! (fn [n])
  - :network

  Nothing here retries a send. Functions resolve with typed results and never
  reject."
  (:require [clojure.string :as str]
            [hyperopen.platform.locks :as locks]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.application.lifecycle-recovery :as recovery]
            [hyperopen.vol-range.domain.cloid :as cloid]
            [hyperopen.vol-range.infrastructure.reads :as reads]))

(def action-deadline-ms
  "Spec 8.3: each attempt expires 30 seconds after admission."
  30000)

(def fills-lookback-ms
  "Fills are read from slightly before the grid was created."
  60000)

(defn now [deps] ((:now-ms! deps)))

(defn error-text [e]
  (cond (instance? js/Error e) (.-message e)
        (some? e) (str e)
        :else "unknown error"))

(defn account-of [grid] (get-in grid [:scope :account-address]))

(defn commit!
  "Commit records; resolves {:ok? bool :error text}. Never rejects."
  [deps records]
  (-> (js/Promise.resolve nil)
      (.then (fn [_] ((get-in deps [:journal :commit!]) records)))
      (.then (fn [_] {:ok? true :error nil})
             (fn [e] {:ok? false :error (error-text e)}))))

(defn load!
  "Load the journal; resolves {:ok? :records :unreadable :error}."
  [deps]
  (-> (js/Promise.resolve nil)
      (.then (fn [_] ((get-in deps [:journal :load-all!]))))
      (.then (fn [{:keys [records unreadable]}]
               {:ok? true :records (vec records) :unreadable (vec unreadable) :error nil})
             (fn [e] {:ok? false :records [] :unreadable [] :error (error-text e)}))))

(defn with-releases
  "Release every operation whose reservation can now be released. Returns
  [grid release-records]."
  [deps grid]
  (let [ids (lifecycle/releasable-operations grid)
        at (now deps)]
    [(lifecycle/mark-released grid ids)
     (mapv #(jr/reservation-release-record {:operation-id % :scope (:scope grid) :now-ms at}) ids)]))

(defn stored-grid [records grid-id]
  (some #(when (= grid-id (:grid-id %)) %) (jr/grids records nil)))

(defn grid-lock-name [grid-id] (str "hyperopen:vol-range-grid:" grid-id))

(defn persist-grid!
  "Commit the grid (with any reservation releases) and `extra` records.
  Grid records are read-merge-written under a per-grid lock, so a flow
  holding an older copy can never erase another flow's operation, leg,
  repair or fill (`recovery/merge-grids` is monotonic). When the journal
  cannot be read nothing is written: a blind write could erase another
  flow's state. On failure the returned grid is NOT marked released, so the
  release is retried."
  [deps grid extra]
  (locks/with-exclusive-lock!
   (grid-lock-name (:grid-id grid))
   (fn []
     (-> (load! deps)
         (.then (fn [{:keys [ok? records error]}]
                  (if-not ok?
                    {:ok? false :error error :grid grid}
                    (let [merged (recovery/merge-grids (stored-grid records (:grid-id grid)) grid)
                          [marked releases] (with-releases deps merged)]
                      (-> (commit! deps (into [(jr/grid-record marked (now deps))] (concat releases extra)))
                          (.then (fn [r] (assoc r :grid (if (:ok? r) marked merged)))))))))))))

(defn attempted-operations
  "Operation ids with a committed send or cancel attempt record."
  [records]
  (into #{} (comp (filter #(contains? #{:send-attempt :cancel-attempt} (:kind %))) (map :operation-id))
        records))

(defn refresh-grid!
  "The caller's copy merged with the journal's newest copy, with in-flight
  operations nobody can still be finishing decided
  (`recovery/recover-after-reload`) and any reservation release retried.
  Runs under the grid lock, so the decision uses the attempt records as of
  now. Journal failures leave the caller's copy and write nothing."
  [deps grid]
  (locks/with-exclusive-lock!
   (grid-lock-name (:grid-id grid))
   (fn []
     (-> (load! deps)
         (.then (fn [{:keys [ok? records]}]
                  (if-not ok?
                    grid
                    (let [stored (stored-grid records (:grid-id grid))
                          merged (recovery/merge-grids stored grid)
                          settled (recovery/recover-after-reload merged (now deps) (attempted-operations records))
                          [marked releases] (with-releases deps settled)]
                      (if (and (= marked stored) (empty? releases))
                        marked
                        (-> (commit! deps (into [(jr/grid-record marked (now deps))] releases))
                            (.then (fn [{:keys [ok?]}] (if ok? marked settled)))))))))))))

;; ---------------------------------------------------------------------------
;; Placement blockers (spec 7.6, 8.6)
;; ---------------------------------------------------------------------------

(defn- operation-resolved-in-grids?
  "An attempt is settled by evidence-based resolution or by a not-sent outcome
  that the DELIVERY path proved (its HTTP call never started). A not-sent
  decided by recovery never overrides a committed attempt."
  [grids operation-id]
  (some (fn [g] (let [op (get-in g [:operations operation-id])]
                  (or (= :resolved (:state op))
                      (and (= :not-sent (:state op)) (= :delivery (:not-sent-source op))))))
        grids))

(defn placement-blockers
  "Why a NEW grid (or a repair of `exclude-grid-id`) cannot be placed for
  `scope-key`: unreadable journal rows, any other grid in this market that is
  not closed (one ladder per market, including recovered orders), or a send
  attempt whose outcome is unresolved."
  [{:keys [records unreadable]} scope-key & [exclude-grid-id]]
  (let [grids (remove #(= exclude-grid-id (:grid-id %)) (jr/grids records scope-key))
        all-grids (jr/grids records nil)
        active (remove #(contains? #{:closed :not-placed} (lifecycle/status %)) grids)
        own-ops (set (keys (:operations (stored-grid records exclude-grid-id))))
        attempts (->> (jr/unresolved-attempts records scope-key)
                      (remove #(contains? own-ops (:operation-id %)))
                      (remove #(operation-resolved-in-grids? all-grids (:operation-id %))))]
    (cond-> []
      (seq unreadable) (conj :journal/unreadable-records)
      (some :recovered? active) (conj :grid/recovered-orders)
      (some (complement :recovered?) active) (conj :grid/active-in-market)
      (seq attempts) (conj :grid/unresolved-attempt))))

(defn discovered-orders
  "Live Vol Range-prefixed open orders for this exact market whose CLOID no
  known grid owns (VR-17). The prefix alone never proves ownership."
  [open-orders scope known-grids]
  (let [known (into #{} (mapcat #(keys (:legs %))) known-grids)]
    (->> open-orders
         (filter #(= (:coin scope) (:coin %)))
         (filter #(cloid/vol-range-cloid? (:cloid %)))
         (remove #(contains? known (str/lower-case (str (:cloid %)))))
         vec)))

;; ---------------------------------------------------------------------------
;; Reconciliation (spec 8.6, 9)
;; ---------------------------------------------------------------------------

(defn- read-statuses! [deps grid barrier-id]
  (let [ids (lifecycle/status-lookup-ids grid)]
    (-> (if (seq ids)
          (reads/read-order-statuses! (:post-info! deps) {:account-address (account-of grid) :ids ids
                                                          :barrier-id barrier-id})
          (js/Promise.resolve []))
        (.then (fn [statuses] [(evidence/apply-order-statuses grid statuses (now deps)) (count ids)])))))

(defn- read-fills! [deps grid barrier-id]
  (let [end (now deps)]
    (-> (reads/read-fills-window! (:post-info! deps)
                                  {:account-address (account-of grid) :barrier-id barrier-id
                                   :start-ms (- (or (:fills-through-ms grid) (:created-at-ms grid)) fills-lookback-ms)
                                   :end-ms end :now-ms! (:now-ms! deps)})
        (.then (fn [fills]
                 ;; Fill evidence is "as of" the window end: a balance read
                 ;; requested later includes every fill in it.
                 [(cond-> (evidence/ingest-fills grid {:account-address (account-of grid) :rows (:rows fills)
                                                       :received-at-ms end})
                    (:complete? fills) (lifecycle/note-fills-read end))
                  (:complete? fills)])))))

(defn reconcile!
  "Refresh one grid from authoritative reads, in this order: a raw account
  snapshot (open orders and balances), order status lookups by ORIGINAL ids
  for every leg still unresolved, then the fills window (read last, so a
  complete read covers every state change seen before it). Persists the grid
  and any reservation releases. Resolves to {:grid :status :counts
  :persisted? :reads {...}}. Never rejects."
  [deps grid]
  (let [barrier-id ((:random-id! deps) "rb")
        out (fn [grid persisted? error reads]
              {:grid grid :status (lifecycle/status grid) :counts (lifecycle/counts grid)
               :persisted? persisted? :error error :reads reads})]
    (-> (refresh-grid! deps grid)
        (.then (fn [g]
                 (-> (reads/read-account-snapshot! (:post-info! deps)
                                                   {:account-address (account-of g) :barrier-id barrier-id
                                                    :now-ms! (:now-ms! deps)})
                     (.then (fn [snap]
                              (let [g (cond-> (evidence/apply-orders-snapshot g snap)
                                        (:complete? snap) (lifecycle/note-balance-snapshot (:requested-at-ms snap)))]
                                (-> (read-statuses! deps g barrier-id)
                                    (.then (fn [[g n]]
                                             (-> (read-fills! deps g barrier-id)
                                                 (.then (fn [[g fills-complete?]]
                                                          (-> (persist-grid! deps g [])
                                                              (.then (fn [{:keys [ok? grid error]}]
                                                                       (out grid ok? error
                                                                            {:orders-complete? (:complete? snap)
                                                                             :fills-complete? fills-complete?
                                                                             :status-lookups n}))))))))))))))))
        (.catch (fn [e] (out grid false (error-text e) nil))))))

;; ---------------------------------------------------------------------------
;; Reload recovery (spec 7.6, VR-13, VR-17)
;; ---------------------------------------------------------------------------

(defn recover!
  "Restore the journal before any new grid. Grids for `scope-key` with
  in-flight operations nobody can still be finishing are decided
  (`recovery/recover-after-reload`) and persisted. Then live prefixed orders
  without a manifest become a recovered grid. Resolves to
  {:status :ok|:journal-unavailable :grids [...] :unreadable [...]
   :blockers [...]}."
  [deps {:keys [scope scope-key]}]
  (-> (load! deps)
      (.then
       (fn [{:keys [ok? records unreadable error]}]
         (if-not ok?
           {:status :journal-unavailable :error error :grids [] :unreadable [] :blockers [:journal/unavailable]}
           (let [before (jr/grids records scope-key)]
             ;; Each grid is settled under its lock: in-flight decisions and
             ;; any reservation release a failed write left behind.
             (-> (reduce (fn [p g] (.then p (fn [_] (refresh-grid! deps g))))
                         (js/Promise.resolve nil)
                         before)
                 (.then (fn [_]
                          (reads/read-account-snapshot! (:post-info! deps)
                                                        {:account-address (:account-address scope)
                                                         :barrier-id ((:random-id! deps) "rb")
                                                         :now-ms! (:now-ms! deps)})))
                 ;; Re-read the journal AFTER the snapshot, so a grid another tab
                 ;; committed meanwhile is never mistaken for recovered orders.
                 (.then (fn [snap] (.then (load! deps) (fn [j] [snap j]))))
                 (.then
                  (fn [[snap journal]]
                    (let [records (if (:ok? journal) (:records journal) records)
                          found (when (and (:complete? snap) (:ok? journal))
                                  (discovered-orders (:open-orders snap) scope (jr/grids records nil)))
                          recovered (when (seq found)
                                      (recovery/recovered-grid {:grid-id ((:random-id! deps) "grid")
                                                                 :scope scope :scope-key scope-key
                                                                 :rows found :now-ms (now deps)}))]
                      (-> (if recovered
                            (persist-grid! deps recovered [])
                            (js/Promise.resolve {:ok? true}))
                          (.then (fn [_] (load! deps)))
                          (.then (fn [after]
                                   (let [recs (if (:ok? after) (:records after) records)
                                         grids (cond-> (jr/grids recs scope-key)
                                                 (not (:ok? after)) (into (cond-> [] recovered (conj recovered))))]
                                     {:status :ok
                                      :grids (vec grids)
                                      :unreadable unreadable
                                      :orders-complete? (boolean (:complete? snap))
                                      :blockers (cond-> (placement-blockers {:records recs :unreadable unreadable}
                                                                            scope-key)
                                                  (not (:complete? snap)) (conj :reads/orders-incomplete))}))))))))))))
      (.catch (fn [e] {:status :journal-unavailable :error (error-text e) :grids [] :unreadable []
                       :blockers [:journal/unavailable]}))))
