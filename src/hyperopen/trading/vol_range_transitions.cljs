(ns hyperopen.trading.vol-range-transitions
  "Pure Vol Range order-form transitions: state -> {:order-form form
  :path-values [[path value] ...]}. The actions namespace routes `:order-form`
  through ownership enforcement and emits everything as one save-many.

  Numeric draft text passes through the locale-aware boundary
  (`hyperopen.utils.parse/normalize-localized-decimal-input`); text that does not
  normalize is kept as typed, because intermediate text is a draft state and
  never numeric zero. Every draft write first rebinds the draft to the current
  scope and discards any frozen review.

  VR-24: transitions only describe state writes; they build no exchange action."
  (:require [clojure.string :as str]
            [hyperopen.config :as config]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.state.trading :as trading]
            [hyperopen.trading.vol-range-draft :as vol-range-draft]
            [hyperopen.utils.parse :as parse-utils]
            [hyperopen.vol-range.application.draft-scope :as draft-scope]
            [hyperopen.vol-range.application.remediations :as remediations]))

(def enum-draft-paths
  {[:anchor :mode] vol-range-draft/anchor-modes
   [:volatility :mode] vol-range-draft/volatility-modes
   [:buy :amount-unit] vol-range-draft/amount-units
   [:sell :amount-unit] vol-range-draft/amount-units})

(def boolean-draft-paths
  #{[:inventory :tilt-enabled?] [:buy :enabled?] [:sell :enabled?]})

(def draft-path-whitelist
  "Every draft path `update-vol-range-draft` may write."
  (-> #{}
      (into (keys enum-draft-paths))
      (into boolean-draft-paths)
      (into vol-range-draft/localized-numeric-draft-paths)))

(def clear-review-path-values
  [[[:vol-range :frozen-review] nil]
   [[:vol-range :frozen-context] nil]
   [[:vol-range :ui :review-open?] false]])

(def ui-flags #{:show-bands? :advanced-open?})

(defn- keywordize [x]
  (cond (keyword? x) x
        (string? x) (keyword (str/lower-case (str/trim x)))
        :else nil))

(defn normalize-path [path]
  (when (vector? path)
    (let [p (mapv keywordize path)]
      (when (every? some? p) p))))

(defn coerce-boolean [value]
  (cond (boolean? value) value
        (= "true" value) true
        (= "false" value) false
        :else nil))

(defn- coerce-text
  "{:value v} for text-like draft input, nil when the input type is unusable.
  Integer rung counts stay numbers; every other number becomes a decimal string."
  [state path value]
  (cond
    (nil? value) {:value ""}
    (number? value) (when-let [d (precision/number->decimal value)]
                      {:value (if (and (= :count (peek path)) (js/Number.isInteger value))
                                value
                                (precision/decimal->string d))})
    (string? value) {:value (if (str/blank? value)
                              value
                              (or (parse-utils/normalize-localized-decimal-input
                                   value (get-in state [:ui :locale]))
                                  value))}
    :else nil))

(defn coerce-draft-value
  "{:value v} for a whitelisted `path` and raw `value`, or nil to reject."
  [state path value]
  (cond
    (contains? enum-draft-paths path)
    (let [k (keywordize value)]
      (when (contains? (get enum-draft-paths path) k) {:value k}))

    (contains? boolean-draft-paths path)
    (let [b (coerce-boolean value)]
      (when (some? b) {:value b}))

    (contains? vol-range-draft/localized-numeric-draft-paths path)
    (coerce-text state path value)

    :else nil))

;; ---------------------------------------------------------------------------
;; Transitions
;; ---------------------------------------------------------------------------

(defn- working-form [state] (trading/order-form-draft state))

(defn- draft-write
  "Transition that stores `new-draft` in the form for the current scope."
  [state new-draft]
  {:order-form (assoc-in (working-form state) [:scale :vol-range] new-draft)
   :path-values (into (vec (draft-scope/rebind-path-values state)) clear-review-path-values)})

(defn select-scale-mode
  "Write [:order-form :scale :mode]. Selecting :volatility-range also binds and
  restores the draft for the current scope."
  [state mode]
  (when-let [m (some-> (keywordize mode) vol-range-draft/scale-modes)]
    (when (or (= :price-range m) (config/vol-range-preview-enabled?))
      (if (= :volatility-range m)
        {:order-form (-> (working-form state)
                         (assoc-in [:scale :mode] m)
                         (assoc-in [:scale :vol-range] (draft-scope/effective-draft state)))
         :path-values (vec (draft-scope/rebind-path-values state))}
        {:order-form (assoc-in (working-form state) [:scale :mode] m)
         :path-values []}))))

(defn update-draft
  "Write one whitelisted draft path. Nil (no-op) for unknown paths or values."
  [state path value]
  (let [path* (normalize-path path)]
    (when (contains? draft-path-whitelist path*)
      (when-let [{coerced :value} (coerce-draft-value state path* value)]
        (draft-write state (assoc-in (draft-scope/effective-draft state) path* coerced))))))

(defn apply-patch
  "Apply remediation or shortcut patch pairs to the draft."
  [state patch]
  (draft-write state (remediations/apply-draft-patch (draft-scope/effective-draft state) patch)))

(defn open-review
  "Freeze `plan` for review. The plan itself is stored unchanged; the book at
  plan time and the freeze time sit in a separate context map."
  [plan context]
  {:path-values [[[:vol-range :frozen-review] plan]
                 [[:vol-range :frozen-context] context]
                 [[:vol-range :ui :review-open?] true]]})

(defn close-review []
  {:path-values [[[:vol-range :ui :review-open?] false]
                 [[:vol-range :frozen-review] nil]
                 [[:vol-range :frozen-context] nil]]})

(defn set-ui-flag [flag value]
  (let [f (keywordize flag)
        b (coerce-boolean value)]
    (when (and (contains? ui-flags f) (some? b))
      {:path-values [[[:vol-range :ui f] b]]})))
