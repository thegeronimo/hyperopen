(ns hyperopen.views.account-info.test-support.hiccup
  (:require [clojure.string :as str]))

(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(defn- classes-from-tag [tag]
  (if (keyword? tag)
    (let [parts (str/split (name tag) #"\.")]
      (if (> (count parts) 1)
        (rest parts)
        []))
    []))

(defn node-class-set [node]
  (let [attrs (when (and (vector? node) (map? (second node)))
                (second node))
        classes (concat (classes-from-tag (first node))
                        (class-values (:class attrs)))]
    (set classes)))

(defn node-children [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn direct-texts [node]
  (->> (node-children node)
       (filter string?)
       set))

(defn collect-strings [node]
  (cond
    (string? node) [node]

    (vector? node)
    (mapcat collect-strings (node-children node))

    (seq? node)
    (mapcat collect-strings node)

    :else []))

(defn find-first-node [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn find-by-data-role [node data-role]
  (find-first-node node #(= data-role (get-in % [1 :data-role]))))

(defn find-all-nodes [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)
          self-match (when (pred node) [node])]
      (into (or self-match [])
            (mapcat #(find-all-nodes % pred) children)))

    (seq? node)
    (mapcat #(find-all-nodes % pred) node)

    :else []))

(defn count-nodes [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)
          self-count (if (pred node) 1 0)]
      (+ self-count (reduce + 0 (map #(count-nodes % pred) children))))

    (seq? node)
    (reduce + 0 (map #(count-nodes % pred) node))

    :else 0))

(defn tab-header-node [tab-content]
  (first (vec (node-children tab-content))))

(defn tab-rows-viewport-node [tab-content]
  (second (vec (node-children tab-content))))

(defn first-viewport-row [tab-content]
  (-> tab-content tab-rows-viewport-node node-children first))

(defn- balance-row-coin [row-node]
  (let [coin-cell (first (vec (node-children row-node)))]
    (first (direct-texts coin-cell))))

(defn balance-tab-coins [tab-content]
  (->> (node-children (tab-rows-viewport-node tab-content))
       (map balance-row-coin)
       vec))

(defn balance-row-contract-cell [row-node]
  (nth (vec (node-children row-node)) 7))
