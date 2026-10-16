(ns hyperopen.vaults.screener.saved-views
  "Pure saved-view policy. A saved view keeps two things: the member list (the
  vaults that qualified when it was saved or last refreshed) and the recipe
  (the screen config that produced it), plus each member's metric values from
  that run. The All vaults page filters to the members without fetching; a
  refresh re-runs the recipe and either replaces the members (`:update`) or
  only refreshes the numbers of a fixed list (`:fixed`)."
  (:require [clojure.string :as str]
            [hyperopen.vaults.screener.model :as model]))

(def scopes
  #{:pass :pass-and-near})

(def memberships
  #{:update :fixed})

(defn- token
  [value]
  (cond
    (keyword? value) value
    (string? value) (some-> value str/trim not-empty (str/replace #"^:" "") keyword)
    :else nil))

(defn normalize-scope
  [value]
  (if (contains? scopes (token value)) (token value) :pass))

(defn normalize-membership
  [value]
  (if (contains? memberships (token value)) (token value) :update))

(defn normalize-name
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      (subs text 0 (min 60 (count text))))))

(defn- op-text
  [metric]
  (if (= :min (:direction (model/metric-def-by-key metric))) "≥" "≤"))

(defn suggested-name
  "A readable default name built from the criteria, e.g.
  \"Sharpe ≥ 1.0, Max drawdown ≤ 25%\"."
  [config]
  (let [parts (->> (:criteria (model/normalize-config config))
                   (keep (fn [{:keys [metric value]}]
                           (when (model/parse-number value)
                             (let [{:keys [label unit]} (model/metric-def-by-key metric)]
                               (str label " " (op-text metric) " " value
                                    (case unit :percent "%" :days " days" ""))))))
                   (take 3))]
    (if (seq parts) (str/join ", " parts) "Saved vaults")))

(defn view-config
  "The recipe a view keeps: the run's universe and window (the results were
  computed with them) and the live thresholds the user is looking at."
  [run-config ui-config]
  (model/normalize-config
   (merge (model/normalize-config run-config)
          (select-keys (model/normalize-config ui-config) [:criteria :min-history-points]))))

(defn- qualifies?
  [scope evaluation]
  (contains? (if (= scope :pass-and-near) #{:pass :near} #{:pass})
             (:status evaluation)))

(defn qualifying-addresses
  "Addresses in `order` whose results qualify under `config` and `scope`."
  [order results config scope]
  (let [config* (model/normalize-config config)]
    (vec (filter (fn [address]
                   (when-let [result (get results address)]
                     (qualifies? scope (model/evaluate config* result))))
                 order))))

(defn- metrics-for
  [results addresses]
  (into {}
        (keep (fn [address]
                (when-let [values (get-in results [address :values])]
                  [address (select-keys values model/metric-keys)])))
        addresses))

(defn build-view
  [{:keys [id name scope membership run results ui-config now-ms]}]
  (let [config (view-config (:config run) ui-config)
        scope* (normalize-scope scope)
        members (qualifying-addresses (:addresses run) results config scope*)]
    {:id id
     :name (or (normalize-name name) (suggested-name config))
     :scope scope*
     :membership (normalize-membership membership)
     :config config
     :members members
     :metrics-by-address (metrics-for results members)
     :created-at-ms now-ms
     :refreshed-at-ms now-ms
     :last-diff nil}))

(defn refresh-view
  "Apply a finished refresh run to a view."
  [view {:keys [addresses]} results now-ms]
  (let [members* (if (= :fixed (:membership view))
                   (vec (:members view))
                   (qualifying-addresses addresses results (:config view) (:scope view)))
        before (set (:members view))
        after (set members*)]
    (assoc view
           :members members*
           :metrics-by-address (merge (when (= :fixed (:membership view))
                                        (select-keys (:metrics-by-address view) members*))
                                      (metrics-for results members*))
           :refreshed-at-ms now-ms
           :last-diff {:joined (vec (filter (complement before) members*))
                       :left (vec (filter (complement after) (:members view)))})))

(defn diff-summary
  [{:keys [joined left]}]
  (let [n-joined (count joined)
        n-left (count left)
        part (fn [n verb] (str n (if (= 1 n) " vault " " vaults ") verb))]
    (cond
      (and (zero? n-joined) (zero? n-left)) "no change in members"
      (zero? n-left) (str (part n-joined "joined") ", none left")
      (zero? n-joined) (str "none joined, " (part n-left "left"))
      :else (str (part n-joined "joined") ", " n-left " left"))))

(defn- normalize-address
  [value]
  (some-> value name str/trim str/lower-case not-empty))

(defn normalize-view
  "Coerce a view read back from storage (string keys and keywords come back as
  strings)."
  [view]
  (when (and (map? view) (some? (:id view)))
    (let [members (vec (keep normalize-address (:members view)))]
      {:id (str (:id view))
       :name (or (normalize-name (:name view)) "Saved vaults")
       :scope (normalize-scope (:scope view))
       :membership (normalize-membership (:membership view))
       :config (model/normalize-config (:config view))
       :members members
       :metrics-by-address (into {}
                                 (keep (fn [[address values]]
                                         (when-let [address* (normalize-address address)]
                                           [address* (into {}
                                                           (keep (fn [[k v]]
                                                                   (when-let [k* (model/normalize-metric-key k)]
                                                                     (when (number? v) [k* v]))))
                                                           values)])))
                                 (:metrics-by-address view))
       :created-at-ms (when (number? (:created-at-ms view)) (:created-at-ms view))
       :refreshed-at-ms (when (number? (:refreshed-at-ms view)) (:refreshed-at-ms view))
       :last-diff (when (map? (:last-diff view))
                    {:joined (vec (keep normalize-address (get-in view [:last-diff :joined])))
                     :left (vec (keep normalize-address (get-in view [:last-diff :left])))})})))

(defn normalize-views
  [views]
  (->> (if (sequential? views) views [])
       (keep normalize-view)
       (reduce (fn [acc view]
                 (if (some #(= (:id view) (:id %)) acc) acc (conj acc view)))
               [])))

(defn view-by-id
  [views id]
  (some #(when (= id (:id %)) %) views))

(defn upsert-view
  [views view]
  (let [views* (vec views)]
    (if (some #(= (:id view) (:id %)) views*)
      (mapv #(if (= (:id view) (:id %)) view %) views*)
      (conj views* view))))

(defn remove-view
  [views id]
  (vec (remove #(= id (:id %)) views)))
