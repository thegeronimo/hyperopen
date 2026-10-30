(ns hyperopen.vol-range.application.draft-scope
  "In-memory scoped Vol Range drafts (HO-SPEC-014 v2.1 section 7.2, VR-01).

  The order form carries ONE Vol Range draft at `[:order-form :scale :vol-range]`.
  `[:vol-range :draft-binding]` records the scope key that draft's scoped values
  (quantities and limits) belong to; the scoped values of every other scope sit
  in the map at `[:vol-range :drafts]` under their scope key. Account- and market-specific values
  therefore never carry across wallets, subaccounts or markets: reads resolve
  the effective draft for the current scope, and every Vol Range write first
  applies `rebind-path-values`.

  IndexedDB persistence of scoped drafts is deferred to Release A (spec PR 4);
  nothing here touches storage."
  (:require [hyperopen.trading.vol-range-draft :as vol-range-draft]
            [hyperopen.vol-range.application.snapshots :as snapshots]
            [hyperopen.vol-range.contracts :as contracts]))

(defn current-scope-key [state]
  (contracts/scope-key (snapshots/current-scope state)))

(defn frozen-review-current?
  "True when a frozen review exists and was frozen for the CURRENT scope
  (spec 7.2: an account or market change invalidates the review immediately)."
  [state]
  (let [plan (get-in state [:vol-range :frozen-review])]
    (and (map? plan)
         (= (contracts/scope-key (:scope plan)) (current-scope-key state)))))

(defn draft-binding [state]
  (get-in state [:vol-range :draft-binding]))

(defn- form-draft [state]
  (vol-range-draft/normalize-vol-range-draft (get-in state [:order-form :scale :vol-range])))

(defn- stored-scoped-values [state scope-key]
  (get-in state [:vol-range :drafts scope-key]))

(defn- with-scoped-values
  "Draft with its scoped paths taken from `values` (blank when absent)."
  [draft values]
  (reduce (fn [acc path] (assoc-in acc path (get values path "")))
          draft
          vol-range-draft/scoped-draft-paths))

(defn effective-draft
  "The draft for the current scope: the form's draft when it is bound to this
  scope, otherwise the form's unscoped preferences merged with this scope's
  stored values (or blanks)."
  [state]
  (let [scope-key (current-scope-key state)
        draft (form-draft state)]
    (if (= scope-key (draft-binding state))
      draft
      (with-scoped-values draft (stored-scoped-values state scope-key)))))

(defn rebind-path-values
  "[[path value] ...] that stash the bound scope's scoped values, restore the
  current scope's draft into the form and set the binding. Empty when the form
  is already bound to the current scope."
  [state]
  (let [scope-key (current-scope-key state)
        bound (draft-binding state)]
    (if (= scope-key bound)
      []
      (cond-> []
        ;; save-many paths are keyword-only, so the whole :drafts map (keyed by
        ;; scope-key string) is written rather than one string-keyed path.
        bound (conj [[:vol-range :drafts]
                     (assoc (get-in state [:vol-range :drafts]) bound
                            (vol-range-draft/scoped-values (form-draft state)))])
        true (conj [[:order-form :scale :vol-range] (effective-draft state)]
                   [[:vol-range :draft-binding] scope-key])))))
