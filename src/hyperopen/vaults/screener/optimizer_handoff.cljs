(ns hyperopen.vaults.screener.optimizer-handoff
  "Send a set of vaults — the active saved view or screener results — to the
  portfolio optimizer. The review sheet lets the user drop vaults, pick a goal
  and choose the allocation rules; confirming seeds a fresh optimizer draft.
  The button that confirms also dispatches `:actions/navigate` to the optimizer
  in the same click, after this action's effects."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.actions.vault-handoff :as vault-handoff]
            [hyperopen.vaults.screener.commands :as commands]
            [hyperopen.vaults.screener.model :as model]
            [hyperopen.vaults.screener.view-commands :as view-commands]))

(def sheet-path
  [:vaults-ui :optimizer-handoff])

(def optimizer-route
  "/portfolio/optimize")

(def sources
  #{:saved-view :screener-pass :screener-near :screener-top})

(def top-count
  5)

(defn- keyword-token
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (str/replace value #"^:" ""))
    :else nil))

(defn- screener-addresses
  "Run addresses whose current evaluation qualifies, best Sharpe first."
  [state statuses]
  (let [config (commands/current-config state)
        results (get-in state commands/results-path)
        rows (->> (get-in state (conj commands/run-path :addresses))
                  (keep (fn [address]
                          (when-let [result (get results address)]
                            (when (contains? statuses (:status (model/evaluate config result)))
                              {:vault-address address :values (:values result)})))))]
    (mapv :vault-address (model/sort-results rows {:metric :sharpe :direction :desc}))))

(defn source-selection
  "`{:addresses :label :view-id}` for a hand-off source, or nil."
  [state source]
  (case source
    :saved-view (when-let [view (view-commands/active-view state)]
                  {:addresses (vec (:members view))
                   :label (:name view)
                   :view-id (:id view)})
    :screener-pass {:addresses (screener-addresses state #{:pass})
                    :label "Screener · passing vaults"}
    :screener-near {:addresses (screener-addresses state #{:pass :near})
                    :label "Screener · passing + near misses"}
    :screener-top {:addresses (vec (take top-count (screener-addresses state #{:pass})))
                   :label (str "Screener · top " top-count " by Sharpe")}
    nil))

(defn open-vault-optimizer-handoff
  [state source]
  (let [source* (keyword-token source)
        selection (when (contains? sources source*) (source-selection state source*))]
    (if (seq (:addresses selection))
      [[:effects/save sheet-path (assoc selection
                                        :open? true
                                        :source source*
                                        :excluded #{}
                                        :objective :equal-risk
                                        :vault-allocation? true)]]
      [])))

(defn set-vault-optimizer-handoff-field
  [state field value]
  (let [sheet (get-in state sheet-path)
        field* (keyword-token field)]
    (if-not (:open? sheet)
      []
      (case field*
        :toggle-vault (let [address (some-> value str str/lower-case)
                            excluded (set (:excluded sheet))]
                        [[:effects/save (conj sheet-path :excluded)
                          (if (contains? excluded address)
                            (disj excluded address)
                            (conj excluded address))]])
        :objective (let [objective (keyword-token value)]
                     (if (contains? vault-handoff/objectives objective)
                       [[:effects/save (conj sheet-path :objective) objective]]
                       []))
        :vault-allocation? [[:effects/save (conj sheet-path :vault-allocation?) (true? value)]]
        []))))

(defn close-vault-optimizer-handoff
  [_state]
  [[:effects/save sheet-path nil]])

(defn selected-addresses
  [sheet]
  (let [excluded (set (:excluded sheet))]
    (vec (remove excluded (:addresses sheet)))))

(defn confirm-vault-optimizer-handoff
  [state]
  (let [sheet (get-in state sheet-path)
        addresses (selected-addresses sheet)]
    (if (and (:open? sheet) (seq addresses))
      (into [[:effects/save sheet-path nil]]
            (vault-handoff/seed-portfolio-optimizer-from-vaults
             state
             {:addresses addresses
              :name (str "Vaults · " (:label sheet))
              :source-label (:label sheet)
              :view-id (:view-id sheet)
              :objective (:objective sheet)
              :vault-allocation? (:vault-allocation? sheet)}))
      [])))
