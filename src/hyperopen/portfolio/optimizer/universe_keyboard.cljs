(ns hyperopen.portfolio.optimizer.universe-keyboard
  (:require [clojure.string :as str]))

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text) text)))

(defn- normalize-market-keys
  [market-keys]
  (->> (or market-keys [])
       (keep non-blank-text)
       vec))

(defn- bounded-active-index
  [idx max-count]
  (let [idx* (if (number? idx)
               (js/Math.floor idx)
               0)
        upper (max 0 (dec max-count))]
    (-> idx*
        (max 0)
        (min upper))))

(defn handle-keydown
  [add-instrument-effects state key market-keys]
  (let [market-keys* (normalize-market-keys market-keys)
        candidate-count (count market-keys*)
        active-index (bounded-active-index
                      (get-in state [:portfolio-ui :optimizer :universe-search-active-index])
                      candidate-count)]
    (case key
      "ArrowDown"
      (if (pos? candidate-count)
        [[:effects/save
          [:portfolio-ui :optimizer :universe-search-active-index]
          (mod (inc active-index) candidate-count)]]
        [])

      "ArrowUp"
      (if (pos? candidate-count)
        [[:effects/save
          [:portfolio-ui :optimizer :universe-search-active-index]
          (mod (dec active-index) candidate-count)]]
        [])

      "Enter"
      (if-let [market-key (get market-keys* active-index)]
        (add-instrument-effects state market-key)
        [])

      [])))
