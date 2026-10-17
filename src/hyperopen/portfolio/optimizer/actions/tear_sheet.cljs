(ns hyperopen.portfolio.optimizer.actions.tear-sheet
  "Options of the hypothetical tear sheet (range, chart mode, method,
  benchmarks, tab). Pure: each returns effect vectors."
  (:require [clojure.string :as str]
            [hyperopen.portfolio.optimizer.application.tear-sheet.model :as tear-sheet]
            [hyperopen.portfolio.optimizer.application.tear-sheet.sources :as sources]
            [hyperopen.portfolio.optimizer.coercion :as coercion]
            [hyperopen.portfolio.optimizer.contracts :as contracts]))

(defn- save
  [option value]
  [:effects/save (conj contracts/ui-tear-sheet-path option) value])

(defn- add-benchmark
  [state opts value]
  (let [value* (some-> value str str/trim not-empty)
        compare* (:compare opts)]
    (if (or (nil? value*)
            (some #{value*} compare*)
            (>= (count compare*) tear-sheet/max-compare))
      [(save :benchmark-search "")]
      (into [(save :compare (conj compare* value*))
             (save :benchmark-search "")
             (save :benchmark-open? false)]
            (sources/benchmark-fetch-effects state [value*])))))

(defn set-portfolio-optimizer-tear-sheet-option
  "`option` with its value:

    :range :mode :rebalance :missing :tab   a keyword from the model's sets
    :profit-share? :equal-weight? :benchmark-open? :show-all-weights?  boolean
    :benchmark-search    the compare search text
    :add-benchmark       a benchmark value (market coin or vault value)
    :remove-benchmark    a picked benchmark value
    :benchmark-key       a key from the search box: Enter adds the top
                         match, Escape closes the list

  Unknown options and values are ignored."
  [state option value]
  (let [option* (coercion/normalize-keyword-like option)
        opts (tear-sheet/options state)]
    (cond
      (contains? tear-sheet/boolean-options option*)
      [(save option* (true? value))]

      (= option* :benchmark-search)
      [(save :benchmark-search (str (or value ""))) (save :benchmark-open? true)]

      (= option* :add-benchmark)
      (add-benchmark state opts value)

      (= option* :remove-benchmark)
      [(save :compare (vec (remove #{value} (:compare opts))))]

      (= option* :benchmark-key)
      (case value
        "Enter" (add-benchmark state opts (:top-value (tear-sheet/benchmark-search-model state opts)))
        "Escape" [(save :benchmark-open? false)]
        [])

      :else
      (let [value* (coercion/normalize-keyword-like value)]
        (if (contains? (get tear-sheet/option-values option*) value*)
          [(save option* value*)]
          [])))))
