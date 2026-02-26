(ns hyperopen.portfolio.actions
  (:require [clojure.string :as str]))

(def default-summary-scope
  :all)

(def default-summary-time-range
  :month)

(def default-chart-tab
  :pnl)

(def ^:private summary-scope-options
  #{:all :perps})

(def ^:private summary-time-range-options
  #{:day :week :month :all-time})

(def ^:private chart-tab-options
  #{:account-value :pnl :returns})

(defn- normalize-keyword-like
  [value]
  (let [text (cond
               (keyword? value) (name value)
               (string? value) (str/trim value)
               :else nil)]
    (when (seq text)
      (-> text
          (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
          str/lower-case
          (str/replace #"[_\s]+" "-")
          keyword))))

(defn normalize-summary-scope
  [value]
  (let [token (normalize-keyword-like value)
        normalized (case token
                     :perp :perps
                     token)]
    (if (contains? summary-scope-options normalized)
      normalized
      default-summary-scope)))

(defn normalize-summary-time-range
  [value]
  (let [token (normalize-keyword-like value)
        normalized (case token
                     :alltime :all-time
                     token)]
    (if (contains? summary-time-range-options normalized)
      normalized
      default-summary-time-range)))

(defn normalize-portfolio-chart-tab
  [value]
  (let [token (normalize-keyword-like value)
        normalized (case token
                     :accountvalue :account-value
                     :account :account-value
                     :return :returns
                     token)]
    (if (contains? chart-tab-options normalized)
      normalized
      default-chart-tab)))

(defn- selector-visibility-path-values
  [open-dropdown]
  [[[:portfolio-ui :summary-scope-dropdown-open?] (= open-dropdown :scope)]
   [[:portfolio-ui :summary-time-range-dropdown-open?] (= open-dropdown :time-range)]])

(defn- selector-projection-effect
  ([open-dropdown]
   (selector-projection-effect open-dropdown []))
  ([open-dropdown extra-path-values]
   [:effects/save-many (into (vec extra-path-values)
                             (selector-visibility-path-values open-dropdown))]))

(defn toggle-portfolio-summary-scope-dropdown
  [state]
  (let [current-visible? (boolean (get-in state [:portfolio-ui :summary-scope-dropdown-open?]))
        open-dropdown (when-not current-visible? :scope)]
    [(selector-projection-effect open-dropdown)]))

(defn toggle-portfolio-summary-time-range-dropdown
  [state]
  (let [current-visible? (boolean (get-in state [:portfolio-ui :summary-time-range-dropdown-open?]))
        open-dropdown (when-not current-visible? :time-range)]
    [(selector-projection-effect open-dropdown)]))

(defn select-portfolio-summary-scope
  [_state scope]
  [(selector-projection-effect nil [[[:portfolio-ui :summary-scope]
                                     (normalize-summary-scope scope)]])])

(defn select-portfolio-summary-time-range
  [_state time-range]
  [(selector-projection-effect nil [[[:portfolio-ui :summary-time-range]
                                     (normalize-summary-time-range time-range)]])])

(defn select-portfolio-chart-tab
  [_state chart-tab]
  [[:effects/save
    [:portfolio-ui :chart-tab]
    (normalize-portfolio-chart-tab chart-tab)]])
