(ns hyperopen.portfolio.vault-attribution-effects
  "Loads everything vault attribution needs for the shown account: the full
   non-funding ledger, then `vaultDetails` for every vault the ledger mentions.
   One failing vault never fails the load; it lands in `:errors-by-vault`.
   The ledger is paged; a ledger cut at the page cap is stored as
   `:ledger-partial?`."
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.api.errors :as api-errors]
            [hyperopen.portfolio.vault-attribution-state :as attribution-state]
            [hyperopen.portfolio.vault-attribution.model :as model]))

(def max-in-flight 4)

(def max-ledger-pages
  ;; Hyperliquid answers at most ~2,000 rows per ledger read.
  25)

(def ^:private vault-delta-types
  model/vault-delta-types)

(defn ledger-vault-addresses
  "Distinct lower-cased vault addresses the ledger mentions in a deposit,
   withdrawal or create row, in first-seen order."
  [rows]
  (->> (if (sequential? rows) rows [])
       (keep (fn [row]
               (let [delta (when (map? row) (:delta row))]
                 (when (and (map? delta)
                            (contains? vault-delta-types (:type delta)))
                   (some-> (:vault delta) str str/trim str/lower-case not-empty)))))
       distinct
       vec))

(defn- map-pool
  "Runs `f` (returning a promise that never rejects) over `items` with at most
   `limit` calls in flight; resolves to the results in item order."
  [limit f items]
  (let [items (vec items)
        n (count items)
        results (atom (vec (repeat n nil)))
        next-index (atom 0)]
    (letfn [(run-worker []
              (let [i @next-index]
                (if (< i n)
                  (do (reset! next-index (inc i))
                      (.then (f (nth items i))
                             (fn [result]
                               (swap! results assoc i result)
                               (run-worker))))
                  (js/Promise.resolve nil))))]
      (-> (js/Promise.all (clj->js (mapv (fn [_] (run-worker))
                                         (range (min limit n)))))
          (.then (fn [_] @results))))))

(defn fetch-ledger-pages!
  "Pages `userNonFundingLedgerUpdates` forward from time 0. Each page starts
   at the newest time read so far (inclusive, so rows sharing it are not
   lost); duplicate rows are dropped. The venue page size is not relied on.

   A page that adds no new row is either the end or a full page whose rows
   all share one time (re-reading from it returns the same page). One probe
   from the next millisecond tells them apart: no new rows ends the read;
   new rows mean some rows at that time were skipped, so the read goes on
   from there with `:partial?` set. Resolves to `{:rows [...] :partial?
   bool}`; `:partial?` also when `max-ledger-pages` reads (probes included)
   still added rows. Rejects when a page fails."
  [request! address]
  (letfn [(step [start seen acc n probing? partial?]
            (-> (request! address start nil {:priority :normal :cache-ttl-ms 0})
                (.then (fn [rows]
                         (let [rows (if (sequential? rows) rows [])
                               fresh (into [] (comp (remove #(contains? seen %)) (distinct)) rows)
                               acc* (into acc fresh)
                               newest (reduce max start (keep #(model/number-in (:time %)) rows))
                               partial?* (or partial? (and probing? (seq fresh)))]
                           (cond
                             (and (empty? fresh) (or probing? (empty? rows)))
                             {:rows acc* :partial? (boolean partial?)}

                             (>= n max-ledger-pages)
                             {:rows acc* :partial? true}

                             (empty? fresh)
                             (step (inc newest) seen acc* (inc n) true partial?)

                             :else
                             (step newest (into seen fresh) acc* (inc n) false partial?*)))))))]
    (step 0 #{} [] 1 false false)))

(defn- account-current?
  [store address]
  (= address (account-context/effective-account-address @store)))

(defn- fetch-vault-details
  "Resolves to `[vault-address {:details d}]`, `[vault-address {:error msg}]`,
   or `[vault-address nil]` when the account changed before the request."
  [{:keys [store address request-vault-details!]} vault-address]
  (if-not (account-current? store address)
    (js/Promise.resolve [vault-address nil])
    (-> (js/Promise.resolve nil)
        (.then (fn [_]
                 (request-vault-details! vault-address
                                         {:user address :priority :low :cache-ttl-ms 0})))
        (.then (fn [details]
                 [vault-address
                  (if (map? details)
                    {:details details}
                    {:error "Vault details unavailable"})]))
        (.catch (fn [err]
                  [vault-address
                   {:error (:message (api-errors/normalize-error err))}])))))

(defn- in-flight?
  [state address]
  (let [slot (get-in state attribution-state/state-path)]
    (boolean (and address
                  (= :loading (:status slot))
                  (= address (:for-address slot))))))

(defn fetch-vault-attribution!
  "Never rejects. State is written through the injected projections, which
   drop writes once the effective account differs from `address`. A second
   call while a load for the same address is in flight does nothing."
  [{:keys [store address request-user-non-funding-ledger-updates! now-ms
           begin-load apply-success apply-error] :as deps}]
  (if-let [address* (when-not (in-flight? @store (account-context/normalize-address address))
                      (account-context/normalize-address address))]
    (let [deps* (assoc deps :address address*)]
      (swap! store begin-load address*)
      (-> (fetch-ledger-pages! request-user-non-funding-ledger-updates! address*)
          (.then
           (fn [{:keys [rows partial?]}]
             (let [ledger-rows (vec rows)
                   ;; Only the account's own transfers name vaults to load.
                   vault-addresses (ledger-vault-addresses
                                    (model/account-rows ledger-rows address*))]
               (if-not (account-current? store address*)
                 (swap! store apply-error address* (js/Error. "Account changed"))
                 (-> (map-pool max-in-flight
                               #(fetch-vault-details deps* %)
                               vault-addresses)
                     (.then
                      (fn [results]
                        (let [entries (keep identity results)]
                          (swap! store apply-success address*
                                 {:ledger-rows ledger-rows
                                  :ledger-partial? partial?
                                  :details-by-vault
                                  (into {} (keep (fn [[v r]]
                                                   (when-let [d (:details r)] [v d])))
                                        entries)
                                  :errors-by-vault
                                  (into {} (keep (fn [[v r]]
                                                   (when-let [e (:error r)] [v e])))
                                        entries)
                                  :loaded-at-ms (now-ms)})))))))))
          (.catch (fn [err]
                    (swap! store apply-error address* err)))
          (.then (fn [_] nil))))
    (js/Promise.resolve nil)))
