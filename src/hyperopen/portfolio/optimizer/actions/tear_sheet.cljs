(ns hyperopen.portfolio.optimizer.actions.tear-sheet
  "Options of the hypothetical tear sheet (range, chart mode, method,
  benchmarks, tab). Pure: each returns effect vectors."
  (:require [hyperopen.portfolio.optimizer.application.tear-sheet.model :as tear-sheet]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(def ^:private benchmark-keys
  (set (map :key tear-sheet/benchmark-defs)))

(defn set-portfolio-optimizer-tear-sheet-option
  "`option` is :range, :mode, :rebalance, :missing or :tab with a keyword
  value; :profit-share? with a boolean; or :toggle-benchmark with a
  benchmark key. Unknown options and values are ignored."
  [state option value]
  (let [option* (coercion/normalize-keyword-like option)
        value* (if (boolean? value) value (coercion/normalize-keyword-like value))
        opts (tear-sheet/options state)]
    (cond
      (= option* :profit-share?)
      [[:effects/save (conj contracts/ui-tear-sheet-path :profit-share?) (true? value)]]

      (and (= option* :toggle-benchmark) (contains? benchmark-keys value*))
      [[:effects/save
        (conj contracts/ui-tear-sheet-path :benchmarks)
        (let [on (:benchmarks opts)]
          (if (contains? on value*) (disj on value*) (conj on value*)))]]

      (contains? (get tear-sheet/option-values option*) value*)
      [[:effects/save (conj contracts/ui-tear-sheet-path option*) value*]]

      :else
      [])))
