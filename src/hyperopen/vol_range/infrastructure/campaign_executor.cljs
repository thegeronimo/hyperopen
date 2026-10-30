(ns hyperopen.vol-range.infrastructure.campaign-executor
  "Start new tracking from now (HO-SPEC-014 v2.1 sections 12.1, 12.6): end a
  market's open tracking campaign and start its successor from a freshly
  reconciled baseline, never stitching the two together.

  Inside the account admission section (so it cannot race a placement's
  baseline capture, which reads the journal in the same section): load the
  journal, refuse while any send attempt for the market is unresolved, then
  an awaited barrier read of the account and a fresh book read, then commit
  the new campaign together with the closure of the previous one in ONE
  journal transaction. A live ladder may keep running; its later fills
  belong to the new campaign by time.

  It never builds an action, signs, sends or reserves anything. Every
  collaborator is injected as `deps`:

  - :post-info!    shared info request function (reads)
  - :journal       {:load-all! :commit!} (committed journal writes)
  - :admission!    the account admission section contract
  - :now-ms!, :random-id! (fn [prefix]), :network

  Resolves to a typed result and never rejects:
  {:status :ok :campaign new :closure closure} or
  {:status :refused|:busy|:coordination-unavailable|:journal-unavailable|:error
   :errors [code ...] :error text}."
  (:require [clojure.string :as str]
            [hyperopen.vol-range.application.campaign :as campaign]
            [hyperopen.vol-range.application.journal-records :as jr]
            [hyperopen.vol-range.infrastructure.reads :as reads]))

(defn- lower [x] (some-> x str str/trim str/lower-case not-empty))
(defn- now [deps] ((:now-ms! deps)))
(defn- error-text [e]
  (cond (instance? js/Error e) (.-message e) (some? e) (str e) :else "unknown error"))

(defn- refused [& codes] {:status :refused :errors (vec codes)})

(defn- operation-resolved?
  "The same rule `executor/placement-blockers` applies: an attempt is settled
  by evidence-based resolution or by a not-sent outcome the delivery path
  proved (`campaign-executor-test` checks the two agree)."
  [grids operation-id]
  (some (fn [g] (let [op (get-in g [:operations operation-id])]
                  (or (= :resolved (:state op))
                      (and (= :not-sent (:state op)) (= :delivery (:not-sent-source op))))))
        grids))

(defn restart-blockers
  "Why tracking for `campaign-key` cannot restart now: unreadable journal
  rows, or a send attempt in that market whose outcome is unresolved. An
  active ladder does not block (its later fills belong to the successor)."
  [{:keys [records unreadable]} campaign-key]
  (let [grids (jr/grids records nil)
        scope-keys (into #{} (comp (filter #(= campaign-key (campaign/campaign-key (:scope %))))
                                   (map :scope-key))
                         grids)
        attempts (->> (jr/unresolved-attempts records nil)
                      (filter #(contains? scope-keys (:scope-key %)))
                      (remove #(operation-resolved? grids (:operation-id %))))]
    (cond-> []
      (seq unreadable) (conj :journal/unreadable-records)
      (seq attempts) (conj :campaign/unresolved-attempt))))

(defn- load-journal! [deps]
  (-> (js/Promise.resolve nil)
      (.then (fn [_] ((get-in deps [:journal :load-all!]))))
      (.then (fn [{:keys [records unreadable]}] {:ok? true :records (vec records) :unreadable (vec unreadable)})
             (fn [e] {:ok? false :error (error-text e)}))))

(defn- commit! [deps records]
  (-> (js/Promise.resolve nil)
      (.then (fn [_] ((get-in deps [:journal :commit!]) records)))
      (.then (fn [_] {:ok? true}) (fn [e] {:ok? false :error (error-text e)}))))

(defn- barrier! [deps account coin]
  (let [barrier-id ((:random-id! deps) "rb")]
    (-> (js/Promise.all
         #js [(reads/read-account-snapshot! (:post-info! deps)
                                            {:account-address account :barrier-id barrier-id
                                             :now-ms! (:now-ms! deps)})
              (reads/read-book! (:post-info! deps) {:coin coin :barrier-id barrier-id :now-ms! (:now-ms! deps)})])
        (.then (fn [arr] (let [[read book] (array-seq arr)] {:read read :book book}))))))

(defn- successor-for [deps previous {:keys [read book]}]
  (campaign/baseline-record
   {:campaign-id ((:random-id! deps) "cmp")
    ;; A campaign's scope is always a spot USDC pair (its key says so).
    :scope (assoc (:scope previous) :market-type :spot)
    :read read
    :bbo (select-keys book [:bid :ask :observed-at-ms])
    :started-by :user-restart
    :supersedes-campaign-id (:campaign-id previous)
    :now-ms (now deps)}))

(defn- restart-in-section!
  [deps account campaign-key]
  (.then
   (load-journal! deps)
   (fn [{:keys [ok? records error] :as journal}]
     (let [previous (when ok? (campaign/open-campaign records campaign-key))
           blockers (when ok? (restart-blockers journal campaign-key))]
       (cond
         (not ok?) {:status :journal-unavailable :error error}
         (nil? previous) (refused :campaign/not-found)
         (not= account (lower (get-in previous [:scope :account-address]))) (refused :campaign/other-account)
         (seq blockers) {:status :refused :errors blockers}
         :else
         (.then
          (barrier! deps account (get-in previous [:scope :coin]))
          (fn [{:keys [read book] :as reads}]
            (let [successor (when (and (:complete? read) (:complete? book)) (successor-for deps previous reads))]
              (cond
                (not (:complete? read)) (refused :guard/account-unavailable)
                (not (:complete? book)) (refused :guard/book-unavailable)
                (= :unavailable (get-in successor [:baseline :p0-source])) (refused :guard/book-unavailable)
                (not= campaign-key (:campaign-key successor)) (refused :campaign/scope-changed)
                ;; The closure ends the previous campaign where the successor
                ;; starts; it must start strictly later.
                (not (< (:t0-ms previous) (:t0-ms successor))) (refused :guard/clock)
                :else
                (let [closure (campaign/closure-record {:campaign previous :successor successor
                                                        :reason :user-restart :now-ms (now deps)})]
                  (.then (commit! deps [successor closure])
                         (fn [{:keys [ok? error]}]
                           (if ok?
                             {:status :ok :campaign successor :closure closure}
                             {:status :journal-unavailable :error error})))))))))))))

(defn- admission-result [{:keys [status value error]}]
  (case status
    :ok value
    :busy {:status :busy}
    :timeout {:status :error :errors [:admission/timeout]
              :error "Timed out waiting for this account; reload before trying again."}
    :unsafe-coordination {:status :coordination-unavailable}
    {:status :error :error (error-text error)}))

(defn start-tracking!
  "End the open campaign for `campaign-key` of `account-address` and start
  its successor from now (see the namespace doc)."
  [deps {:keys [campaign-key account-address]}]
  (let [account (lower account-address)]
    (if-not (and account (string? campaign-key) (seq campaign-key))
      (js/Promise.resolve (refused :campaign/not-found))
      (-> ((:admission! deps) {:network (:network deps) :account-address account}
                              #(restart-in-section! deps account campaign-key))
          (.then admission-result)
          (.catch (fn [e] {:status :error :error (error-text e)}))))))
