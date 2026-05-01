(ns hyperopen.views.portfolio.test-support
  (:require [clojure.string :as str]
            [hyperopen.views.trading-chart.test-support.fake-dom :as fake-dom]))

(defn node-children [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn find-first-node [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn find-nodes [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)
          child-matches (mapcat #(find-nodes % pred) children)]
      (if (pred node)
        (cons node child-matches)
        child-matches))

    (seq? node)
    (mapcat #(find-nodes % pred) node)

    :else []))

(defn count-nodes [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)
          self-count (if (pred node) 1 0)]
      (+ self-count
         (reduce + 0 (map #(count-nodes % pred) children))))

    (seq? node)
    (reduce + 0 (map #(count-nodes % pred) node))

    :else 0))

(defn collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn class-values [node]
  (let [class-attr (get-in node [1 :class])]
    (cond
      (vector? class-attr) class-attr
      (seq? class-attr) (vec class-attr)
      (string? class-attr) (str/split class-attr #"\s+")
      :else [])))

(defn button-with-text [node text]
  (find-first-node node #(and (= :button (first %))
                              (contains? (set (collect-strings %)) text))))

(defn px-width [value]
  (some->> value
           (re-matches #"^([0-9]+)px$")
           second
           (#(js/Number.parseInt % 10))))

(defn mount-d3-host!
  [on-render]
  (let [document (fake-dom/make-fake-document)
        host (fake-dom/make-fake-element "div")
        remembered* (atom nil)]
    (aset host "ownerDocument" document)
    (set! (.-clientWidth host) 400)
    (set! (.-clientHeight host) 220)
    (on-render {:replicant/life-cycle :replicant.life-cycle/mount
                :replicant/node host
                :replicant/remember (fn [memory]
                                      (reset! remembered* memory))})
    {:host host
     :remembered remembered*}))

(defn find-dom-node-by-role
  [root data-role]
  (fake-dom/find-dom-node root #(and (= 1 (.-nodeType %))
                                     (= data-role (.getAttribute % "data-role")))))

(def sample-state
  {:account {:mode :classic}
   :portfolio-ui {:summary-scope :all
                  :summary-time-range :month
                  :chart-tab :account-value
                  :summary-scope-dropdown-open? false
                  :summary-time-range-dropdown-open? false
                  :performance-metrics-time-range-dropdown-open? false}
   :portfolio {:summary-by-key {:month {:pnlHistory [[1 10] [2 15]]
                                        :accountValueHistory [[1 100] [2 100]]
                                        :vlm 2255561.85}}
               :user-fees {:userCrossRate 0.00045
                           :userAddRate 0.00015
                           :dailyUserVlm [{:exchange 100
                                           :userCross 70
                                           :userAdd 30}
                                          {:exchange 50
                                           :userCross 20
                                           :userAdd 10}]}}
   :account-info {:selected-tab :balances
                  :loading false
                  :error nil
                  :hide-small-balances? false
                  :balances-sort {:column nil :direction :asc}
                  :positions-sort {:column nil :direction :asc}
                  :open-orders-sort {:column "Time" :direction :desc}}
   :orders {:open-orders []
            :open-orders-snapshot []
            :open-orders-snapshot-by-dex {}
            :fills [{:time (.now js/Date)
                     :sz "2"
                     :px "100"}]
            :fundings []
            :order-history []}
   :webdata2 {}
   :borrow-lend {:total-supplied-usd 0}
   :spot {:meta nil
          :clearinghouse-state nil}
   :perp-dex-clearinghouse {}})

