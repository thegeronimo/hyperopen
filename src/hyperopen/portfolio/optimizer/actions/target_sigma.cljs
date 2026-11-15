(ns hyperopen.portfolio.optimizer.actions.target-sigma
  (:require [hyperopen.portfolio.optimizer.actions.common :as common]
            [hyperopen.portfolio.optimizer.actions.draft-options :as draft-options]
            [hyperopen.portfolio.optimizer.actions.run :as run-actions]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(def min-sigma 0.04)
(def max-sigma 100)

(defn- percent->decimal
  [value]
  (when-let [parsed (common/parse-number-value value)]
    (/ parsed 100)))

(defn- clamped-sigma
  [value]
  (when-let [sigma (percent->decimal value)]
    (when (pos? sigma)
      (-> sigma (max min-sigma) (min max-sigma)))))

(defn set-portfolio-optimizer-objective-parameter-percent
  [_state parameter-key value]
  (let [parameter-key* (common/normalize-keyword-like parameter-key)
        value* (when (contains? draft-options/numeric-objective-parameter-keys
                                parameter-key*)
                 (if (= :target-volatility parameter-key*)
                   (clamped-sigma value)
                   (percent->decimal value)))]
    (if (some? value*)
      (common/save-draft-path-values
       [[(conj contracts/draft-objective-path parameter-key*) value*]])
      [])))

(defn set-portfolio-optimizer-target-sigma-draft
  [_state value]
  (if-let [sigma (clamped-sigma value)]
    [[:effects/save contracts/ui-target-sigma-draft-path sigma]]
    []))

(defn reset-portfolio-optimizer-target-sigma-draft
  [_state]
  [[:effects/save contracts/ui-target-sigma-draft-path nil]])

(defn set-portfolio-optimizer-objective-menu-target-sigma
  [_state value]
  (if-let [sigma (clamped-sigma value)]
    [[:effects/save contracts/ui-objective-menu-target-sigma-path sigma]]
    []))

(defn rerun-portfolio-optimizer-at-target-sigma
  [state]
  (let [sigma (common/parse-number-value
               (get-in state contracts/ui-target-sigma-draft-path))
        objective {:kind :target-volatility
                   :target-volatility sigma}]
    (if (and (some? sigma) (pos? sigma))
      (let [effects (conj (common/save-draft-path-values
                           [[contracts/draft-objective-path objective]])
                          [:effects/save contracts/ui-target-sigma-draft-path nil])
            state* (-> state
                       (assoc-in contracts/draft-objective-path objective)
                       (assoc-in contracts/ui-target-sigma-draft-path nil))]
        (into effects
              (run-actions/run-portfolio-optimizer-from-draft state*)))
      [])))
