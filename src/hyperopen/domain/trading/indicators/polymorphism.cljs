(ns hyperopen.domain.trading.indicators.polymorphism)

(defmulti validate-param-value
  "Dispatch indicator parameter validation by schema :kind."
  (fn [kind _spec _value _context]
    kind))

(defmethod validate-param-value :default
  [_ _ _ _]
  true)

(defmulti series-operation
  "Dispatch indicator series behavior by [operation series-type]."
  (fn [operation series-type & _]
    [operation series-type]))

(defmethod series-operation :default
  [_ _ & _]
  nil)

(defmulti marker-operation
  "Dispatch indicator marker behavior by [operation kind]."
  (fn [operation kind & _]
    [operation kind]))

(defmethod marker-operation :default
  [_ _ & _]
  nil)
