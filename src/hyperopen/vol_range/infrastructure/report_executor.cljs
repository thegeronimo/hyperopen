(ns hyperopen.vol-range.infrastructure.report-executor
  "Vol Range reporting loads and REST backfill (HO-SPEC-014 v2.1 sections
  9.2, 9.4, 12.2, 12.6). Reads and storage only: nothing here builds,
  signs or sends an action, reserves a nonce, or writes the journal.

  Every collaborator is injected as `deps`:

  - :post-info!    shared info request function (reads)
  - :journal       {:load-all! (fn [] -> Promise {:records :unreadable})}
  - :ledger        {:put-records! :load-account! :merge-coverage!
                    :put-balance-check!} (`ledger-store` with its io bound)
  - :spot-meta!    (fn [] -> [:spot :meta]) for classification
  - :now-ms!, :random-id! (fn [prefix]), :network

  `backfill!` reads in this order: the account's balances (a barrier read,
  as `executor/reconcile!` does first), then fills and then ledger updates
  from `report-coverage/read-start` (the earliest point a tracked token
  still needs and the venue can still serve, minus `:fills-overlap-ms`) or
  earlier, from a stored row that no longer decodes, so it is rewritten.
  Rows are classified with the tracked base tokens loaded BEFORE the reads
  were requested; only relevant rows are persisted, and coverage is merged
  only for those tokens and only after the rows were committed. A relevant
  row that cannot be parsed ends what the read proves at its time. Pending
  balance checks are then evaluated. The result is `:incomplete`, with the
  reason as `:error`, when a read did not complete or proved less than it
  read. Functions resolve with typed results and never reject."
  (:require [clojure.string :as str]
            [hyperopen.vol-range.application.campaign :as campaign]
            [hyperopen.vol-range.application.fill-records :as fill-records]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.application.report-attribution :as attribution]
            [hyperopen.vol-range.application.report-coverage :as coverage]
            [hyperopen.vol-range.contracts :as contracts]
            [hyperopen.vol-range.infrastructure.ledger-store :as ledger-store]
            [hyperopen.vol-range.infrastructure.reads :as reads]
            [hyperopen.vol-range.infrastructure.report-reads :as report-reads]))

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))
(defn- now [deps] ((:now-ms! deps)))
(defn- policy [k] (get contracts/report-policy k))
(defn- error-text [e]
  (cond (instance? js/Error e) (.-message e) (some? e) (str e) :else "unknown error"))

(defn- grids-for-account [records account]
  (filterv #(= account (lower (get-in % [:scope :account-address]))) (jr/grids records nil)))

(defn load!
  "Journal campaigns, closures and the attribution index for `account`, and
  its ledger rows. Resolves to {:journal-ok? :storage-ok? :account-address
  :campaigns :closures :attribution :ledger :unreadable :error}; a ledger
  that cannot be read is `:storage-ok? false` (reports show
  `:storage/unavailable`), never an empty ledger. `:unreadable` holds the
  record ids of the account's stored rows that no longer decode; reports
  treat them as unresolved (`report-attribution/campaign-findings`). Never
  rejects."
  [deps account-address]
  (let [account (lower account-address)
        journal (-> (js/Promise.resolve nil)
                    (.then (fn [_] ((get-in deps [:journal :load-all!]))))
                    (.then (fn [j] {:ok? true :records (vec (:records j))})
                           (fn [e] {:ok? false :error (error-text e)})))
        ledger (-> (js/Promise.resolve nil)
                   (.then (fn [_] ((get-in deps [:ledger :load-account!]) account)))
                   (.then (fn [l] {:ok? true :records (:records l) :unreadable (vec (:unreadable l))})
                          (fn [e] {:ok? false :error (error-text e)})))]
    (-> (js/Promise.all #js [journal ledger])
        (.then (fn [arr]
                 (let [[j l] (array-seq arr)
                       {:keys [campaigns closures]} (campaign/campaigns-for-account (:records j) account)]
                   {:journal-ok? (:ok? j)
                    :storage-ok? (:ok? l)
                    :account-address account
                    :campaigns campaigns
                    :closures closures
                    :attribution (attribution/attribution-index (grids-for-account (:records j) account))
                    :ledger (ledger-store/group-records (:records l))
                    :unreadable (vec (:unreadable l))
                    :error (or (:error j) (:error l))}))))))

;; ---------------------------------------------------------------------------
;; Backfill
;; ---------------------------------------------------------------------------

(defn- base-token [c] (some-> (get-in c [:scope :base-token-id]) str))

(defn tracked-base-tokens
  "Base token ids of the account's campaigns (open or closed)."
  [campaigns]
  (into #{} (keep base-token) (vals campaigns)))

(defn- heal-from
  "The earliest venue time of a stored `kind` row (:fill or :ledger-update)
  that no longer decodes and could concern a tracked token, or nil. Reading
  from there again rewrites it from the venue."
  [loaded markets tokens kind]
  (some->> (:unreadable loaded)
           (map #(attribution/unreadable-row markets %))
           (filter #(and (= kind (:kind %)) (number? (:time %)) (some (partial attribution/unreadable-touches? %) tokens)))
           (map :time)
           seq
           (apply min)))

(defn- start-for [deps loaded markets tokens stream]
  (let [overlap (policy :fills-overlap-ms)
        start (coverage/read-start {:campaigns (:campaigns loaded) :closures (:closures loaded)
                                    :coverage (get-in loaded [:ledger :coverage stream])
                                    :overlap overlap :now-ms (now deps)})
        heal (heal-from loaded markets tokens (if (= :fills stream) :fill :ledger-update))]
    (if (number? heal) (min start (max 0 (- heal overlap))) start)))

(defn- earliest-by-token
  "{token-id earliest time} from [[token-ids time] ...]."
  [pairs]
  (reduce (fn [acc [ids t]] (reduce (fn [acc id] (update acc id #(if % (min % t) t))) acc ids)) {} pairs))

(defn- normalize [deps loaded read row]
  (fill-records/normalize-fill {:network (:network deps) :account-address (:account-address loaded)
                                :row row :source :rest :received-at-ms (:received-at-ms read)}))

(defn- unparsed-fills
  "{token-id earliest time} of fill rows that failed normalization and could
  concern a tracked token (`start` when the row's time is unusable)."
  [deps loaded markets tokens read start]
  (earliest-by-token
   (for [row (:rows read)
         :when (nil? (normalize deps loaded read row))
         :let [ids (attribution/unparsed-fill-token-ids markets tokens row)]
         :when (seq ids)]
     [ids (if (number? (:time row)) (:time row) start)])))

(defn- unparsed-updates [tokens read start]
  (earliest-by-token
   (for [row (:rows read)
         :when (attribution/unparsed-ledger-row? row)]
     [tokens (if (number? (:time row)) (:time row) start)])))

(defn- new-fill-records [deps loaded markets tokens read]
  (let [at (now deps)
        known (:fills (:ledger loaded))]
    (->> (:rows read)
         (keep #(normalize deps loaded read %))
         (filter #(attribution/relevant-fill? (attribution/classify-fill markets (:attribution loaded) %) tokens))
         (remove #(contains? known (:fill-key %)))
         (map #(ledger-store/fill-record % at))
         (reduce (fn [acc r] (assoc acc (:record-id r) r)) {})
         vals vec)))

(defn- new-update-records [deps loaded markets tokens read]
  (let [at (now deps)
        known (:updates (:ledger loaded))]
    (->> (:rows read)
         (mapcat #(attribution/ledger-update-records {:markets markets :tracked tokens
                                                      :account-address (:account-address loaded)
                                                      :row % :now-ms at}))
         (remove #(contains? known (:record-id %)))
         (reduce (fn [acc r] (assoc acc (:record-id r) r)) {})
         vals vec)))

(defn- evaluate-checks! [deps loaded markets fills-read]
  (let [{:keys [campaigns closures ledger]} loaded
        checks (:balance-checks ledger)
        latest (into {} (comp (filter #(= :match (:state %))) (map (juxt :campaign-id identity))) checks)]
    (reduce
     (fn [p check]
       (.then p
              (fn [out]
                (let [c (get campaigns (:campaign-id check))
                      found (when c
                              (attribution/campaign-findings
                               {:campaign c :ended-at-ms (get-in closures [(:campaign-id c) :ended-at-ms])
                                :fills (vals (:fills ledger)) :updates (vals (:updates ledger))
                                :markets markets :index (:attribution loaded) :unreadable (:unreadable loaded)}))
                      evaluated (coverage/evaluate-balance-check
                                 check
                                 {:campaign c :events (:events found)
                                  ;; A stored row that no longer decodes may hold a change
                                  ;; of the base: it decides nothing.
                                  :breaks (-> (vec (:breaks found))
                                              (into (coverage/balance-check-breaks c checks))
                                              (into (:unreadable found)))
                                  :coverage {:fills (get-in ledger [:coverage :fills (base-token c)])
                                             :ledger (get-in ledger [:coverage :ledger (base-token c)])}
                                  :fills-read-requested-at-ms (when (:complete? fills-read) (:requested-at-ms fills-read))
                                  :latest-match (get latest (:campaign-id check))
                                  :now-ms (now deps)})]
                  (if (= evaluated check)
                    out
                    (.then ((get-in deps [:ledger :put-balance-check!]) evaluated)
                           (fn [_] (conj out evaluated))))))))
     (js/Promise.resolve [])
     (filter #(= :pending (:state %)) checks))))

(defn- with-merged [loaded new-records merged-coverage new-checks]
  (let [g (ledger-store/group-records (concat new-records merged-coverage))]
    (-> loaded
        (update-in [:ledger :fills] merge (:fills g))
        (update-in [:ledger :updates] merge (:updates g))
        (update-in [:ledger :coverage :fills] merge (get-in g [:coverage :fills]))
        (update-in [:ledger :coverage :ledger] merge (get-in g [:coverage :ledger]))
        (update-in [:ledger :balance-checks]
                   (fn [cs] (into (vec (remove (set (map :record-id new-checks)) cs)) new-checks))))))

(defn- backfill-problem
  "Why this backfill confirmed less than it read for, or nil: a fills or
  ledger read that did not complete, rows that could not be read, or fills
  the venue no longer serves."
  [{:keys [fills ledger-read unparsed deltas]}]
  (cond
    (not (:complete? fills)) (or (:error fills) "the fills read did not complete")
    (not (:complete? ledger-read)) (or (:error ledger-read) "the account updates read did not complete")
    (some seq (vals unparsed)) "some fills or account updates couldn't be read"
    (some #(= :coverage/retention (get-in % [:failure :reason])) deltas)
    "the venue keeps only its most recent 10,000 fills"
    :else nil))

(defn- run-backfill! [deps loaded markets]
  (let [account (:account-address loaded)
        tokens (tracked-base-tokens (:campaigns loaded))
        read-id ((:random-id! deps) "rpt")
        open (remove #(contains? (:closures loaded) (:campaign-id %)) (vals (:campaigns loaded)))
        state (atom {})]
    (-> (reads/read-account-snapshot! (:post-info! deps)
                                      {:account-address account :barrier-id ((:random-id! deps) "rb")
                                       :now-ms! (:now-ms! deps)})
        (.then (fn [snap]
                 (swap! state assoc :snapshot snap)
                 (let [checks (coverage/pending-balance-checks {:snapshot snap :campaigns open
                                                                :existing (get-in loaded [:ledger :balance-checks])
                                                                :account-address account :now-ms (now deps)})]
                   (swap! state assoc :new-checks checks)
                   (reduce (fn [p c] (.then p (fn [_] ((get-in deps [:ledger :put-balance-check!]) c))))
                           (js/Promise.resolve nil) checks))))
        (.then (fn [_]
                 (let [start (start-for deps loaded markets tokens :fills)]
                   (swap! state assoc :fills-start start)
                   (reads/read-fills-window! (:post-info! deps)
                                             {:account-address account :barrier-id read-id :priority :low
                                              :start-ms start :end-ms (now deps) :now-ms! (:now-ms! deps)}))))
        (.then (fn [fills]
                 (swap! state assoc :fills fills)
                 (let [start (start-for deps loaded markets tokens :ledger)]
                   (swap! state assoc :ledger-start start)
                   (report-reads/read-ledger-window! (:post-info! deps)
                                                     {:account-address account :read-id read-id
                                                      :start-ms start :end-ms (now deps) :now-ms! (:now-ms! deps)}))))
        (.then (fn [ledger-read]
                 (swap! state assoc :ledger-read ledger-read)
                 (let [fills (:fills @state)
                       records (into (new-fill-records deps loaded markets tokens fills)
                                     (new-update-records deps loaded markets tokens ledger-read))]
                   (swap! state assoc :records records)
                   ((get-in deps [:ledger :put-records!]) records))))
        (.then (fn [_]
                 (let [{:keys [fills ledger-read fills-start ledger-start]} @state
                       unparsed {:fills (unparsed-fills deps loaded markets tokens fills fills-start)
                                 :ledger (unparsed-updates tokens ledger-read ledger-start)}
                       deltas (into (coverage/fills-read->coverage {:read fills :start-ms fills-start
                                                                    :base-token-ids tokens :network (:network deps)
                                                                    :account-address account
                                                                    :unparsed (:fills unparsed)})
                                    (coverage/ledger-read->coverage {:read ledger-read :start-ms ledger-start
                                                                     :base-token-ids tokens
                                                                     :unparsed (:ledger unparsed)}))]
                   (swap! state assoc :unparsed unparsed :deltas deltas)
                   ((get-in deps [:ledger :merge-coverage!]) account deltas (now deps)))))
        (.then (fn [merged]
                 (let [{:keys [records new-checks fills]} @state
                       loaded* (with-merged loaded records merged new-checks)]
                   (.then (evaluate-checks! deps loaded* markets fills)
                          (fn [evaluated]
                            (let [{:keys [snapshot fills ledger-read] :as st} @state
                                  problem (backfill-problem st)]
                              {:status (if problem :incomplete :ok) :error problem
                               :account-address account
                               :tracked-base-token-ids tokens
                               :persisted (count records)
                               :coverage (vec merged)
                               :balance-checks (into (vec new-checks) evaluated)
                               :reads {:balances {:complete? (boolean (:complete? snapshot))}
                                       :fills {:complete? (boolean (:complete? fills)) :rows (count (:rows fills))
                                               :reason (:reason fills)}
                                       :ledger {:complete? (boolean (:complete? ledger-read))
                                                :rows (count (:rows ledger-read)) :reason (:reason ledger-read)}}}))))))
        (.catch (fn [e]
                  ;; Reads never reject, so a rejection here is a storage failure.
                  {:status (if (contains? @state :snapshot) :storage/unavailable :error)
                   :error (error-text e) :account-address account})))))

(defn backfill!
  "One REST backfill for `account-address` (see the namespace doc).
  Resolves to {:status :ok|:incomplete|:skipped|:error|:storage/unavailable
  ...}. Never
  rejects."
  [deps {:keys [account-address]}]
  (-> (load! deps account-address)
      (.then (fn [loaded]
               (let [markets (attribution/spot-markets ((:spot-meta! deps)))]
                 (cond
                   (not (:journal-ok? loaded)) {:status :error :error (:error loaded)
                                                :account-address (:account-address loaded)}
                   (not (:storage-ok? loaded)) {:status :storage/unavailable :error (:error loaded)
                                                :account-address (:account-address loaded)}
                   (empty? (tracked-base-tokens (:campaigns loaded))) {:status :skipped :reason :no-campaigns
                                                                       :account-address (:account-address loaded)}
                   ;; Without metadata every spot fill would look like an unknown
                   ;; market: classify nothing and claim no coverage.
                   (not (:ready? markets)) {:status :error :error "Spot metadata unavailable"
                                            :account-address (:account-address loaded)}
                   :else (run-backfill! deps loaded markets)))))
      (.catch (fn [e] {:status :error :error (error-text e) :account-address (lower account-address)}))))
