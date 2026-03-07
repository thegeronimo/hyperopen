(ns tools.crap.complexity
  (:require [clojure.string :as str]
            [edamame.core :as ed]
            [tools.crap.filesystem :as fs]))

(def parse-opts
  {:all true
   :features #{:cljs}
   :auto-resolve {:current 'user}
   :current 'user
   :readers {'js identity
             'inst identity
             'uuid identity}
   :row-key :row
   :col-key :col
   :end-row-key :end-row
   :end-col-key :end-col})

(def defn-heads
  #{"defn" "defn-" "defn*"})

(def fn-heads
  #{"fn" "fn*"})

(def branching-heads
  #{"if" "if-not" "if-let" "if-some" "when" "when-not" "when-let" "when-some" "when-first"})

(def cond-heads
  #{"cond" "cond->" "cond->>"})

(def comprehension-heads
  #{"for" "doseq"})

(declare decision-count)

(defn head-name
  [form]
  (when (seq? form)
    (let [head (first form)]
      (when (instance? clojure.lang.Named head)
        (name head)))))

(defn collection-children
  [form]
  (cond
    (map? form) (concat (keys form) (vals form))
    (set? form) form
    (coll? form) form
    :else nil))

(defn count-cond-branches
  [form]
  (let [clauses (rest form)]
    (->> (partition 2 2 [] clauses)
         (filter #(= 2 (count %)))
         (map first)
         (remove #{:else})
         count)))

(defn count-threaded-cond-branches
  [form]
  (let [clauses (drop 2 form)]
    (->> (partition 2 2 [] clauses)
         (filter #(= 2 (count %)))
         (map first)
         (remove #{:else})
         count)))

(defn count-condp-branches
  [form]
  (loop [remaining (drop 3 form)
         branches 0]
    (cond
      (empty? remaining) branches
      (= 1 (count remaining)) branches
      (= :>> (second remaining)) (recur (drop 3 remaining) (inc branches))
      :else (recur (drop 2 remaining) (inc branches)))))

(defn count-case-branches
  [form]
  (loop [remaining (drop 2 form)
         branches 0]
    (cond
      (empty? remaining) branches
      (= 1 (count remaining)) branches
      :else (recur (drop 2 remaining) (inc branches)))))

(defn count-comprehension-branches
  [bindings]
  (+ 1
     (count (filter #{:when :while} bindings))))

(defn local-decision-count
  [form]
  (if-not (seq? form)
    0
    (let [head (head-name form)]
      (cond
        (branching-heads head) 1
        (= head "cond") (count-cond-branches form)
        (#{"cond->" "cond->>"} head) (count-threaded-cond-branches form)
        (= head "condp") (count-condp-branches form)
        (= head "case") (count-case-branches form)
        (#{"and" "or"} head) (max 0 (- (count form) 2))
        (= head "catch") 1
        (comprehension-heads head) (count-comprehension-branches (second form))
        :else 0))))

(defn decision-count
  [form]
  (+ (local-decision-count form)
     (reduce + 0 (map decision-count (collection-children form)))))

(defn arity-complexity
  [{:keys [body]}]
  (+ 1 (reduce + 0 (map decision-count body))))

(defn drop-doc-and-attr
  [forms]
  (let [forms (seq forms)
        forms (if (string? (first forms)) (next forms) forms)
        forms (if (map? (first forms)) (next forms) forms)]
    forms))

(defn extract-arities
  [tail]
  (let [tail (seq tail)]
    (cond
      (empty? tail) []
      (vector? (first tail)) [{:args (first tail)
                               :body (rest tail)}]
      :else (->> tail
                 (filter seq?)
                 (filter #(vector? (first %)))
                 (map (fn [clause]
                        {:args (first clause)
                         :body (rest clause)}))
                 vec))))

(defn extract-defn-arities
  [form]
  (-> form rest rest drop-doc-and-attr extract-arities))

(defn extract-fn-arities
  [form]
  (let [tail (rest form)
        tail (if (symbol? (first tail)) (rest tail) tail)]
    (extract-arities tail)))

(defn callable-record
  [current-ns form callable-name arities extra]
  {:file nil
   :line (:row (meta form))
   :end-line (:end-row (meta form))
   :namespace (str current-ns)
   :var callable-name
   :display-name (str current-ns "/" callable-name)
   :arity-count (count arities)
   :complexity (reduce + 0 (map arity-complexity arities))
   :metadata extra})

(defn defn-record
  [current-ns form]
  (let [[_ fn-name] form
        arities (extract-defn-arities form)]
    (when (seq arities)
      (callable-record current-ns form (name fn-name) arities {}))))

(defn def-record
  [current-ns form]
  (let [[_ var-name value] form]
    (when (and value
               (seq? value)
               (fn-heads (head-name value)))
      (let [arities (extract-fn-arities value)]
        (when (seq arities)
          (callable-record current-ns form (name var-name) arities {}))))))

(defn defmethod-record
  [current-ns form]
  (let [[_ multimethod dispatch-value & body-tail] form
        arities (extract-arities body-tail)
        dispatch-label (pr-str dispatch-value)
        callable-name (str (name multimethod) "[" dispatch-label "]")]
    (when (seq arities)
      (callable-record current-ns
                       form
                       callable-name
                       arities
                       {:dispatch dispatch-label
                        :multimethod (name multimethod)}))))

(defn callable-record-from-form
  [current-ns form]
  (let [head (head-name form)]
    (cond
      (defn-heads head) (defn-record current-ns form)
      (= head "def") (def-record current-ns form)
      (= head "defmethod") (defmethod-record current-ns form)
      :else nil)))

(defn current-namespace
  [forms]
  (some (fn [form]
          (when (= "ns" (head-name form))
            (str (second form))))
        forms))

(defn analyze-file
  [root file]
  (let [text (slurp file)
        forms (ed/parse-string-all text parse-opts)
        current-ns (or (current-namespace forms)
                       (throw (ex-info (str "Missing ns form in " file) {})))
        rel-file (fs/relativize root file)]
    (->> forms
         (keep #(callable-record-from-form current-ns %))
         (mapv #(assoc % :file rel-file)))))
