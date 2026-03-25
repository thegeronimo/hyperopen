(ns hyperopen.views.asset-selector.test-support
  (:require [clojure.string :as str]))

(def sample-markets
  [{:key "perp:BTC"
    :symbol "BTC-USDC"
    :coin "BTC"
    :base "BTC"
    :market-type :perp
    :category :crypto
    :hip3? false
    :mark 1
    :volume24h 10
    :change24hPct 1}
   {:key "perp:xyz:GOLD"
    :symbol "GOLD-USDC"
    :coin "xyz:GOLD"
    :base "GOLD"
    :market-type :perp
    :category :tradfi
    :hip3? true
    :hip3-eligible? true
    :mark 2
    :volume24h 20
    :change24hPct 2}
   {:key "spot:PURR/USDC"
    :symbol "PURR/USDC"
    :coin "PURR/USDC"
    :base "PURR"
    :market-type :spot
    :category :spot
    :hip3? false
    :mark 0.5
    :volume24h 5
    :change24hPct -1}])

(defn collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings node)
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(defn classes-from-tag [tag]
  (if (keyword? tag)
    (let [parts (str/split (name tag) #"\.")]
      (if (> (count parts) 1)
        (rest parts)
        []))
    []))

(defn collect-all-classes [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (concat (classes-from-tag (first node))
              (class-values (:class attrs))
              (mapcat collect-all-classes children)))

    (seq? node)
    (mapcat collect-all-classes node)

    :else []))

(defn find-first-node [node pred]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn find-node-by-role [node role]
  (find-first-node node
                   (fn [candidate]
                     (let [attrs (when (and (vector? candidate)
                                            (map? (second candidate)))
                                   (second candidate))]
                       (= role (:data-role attrs))))))

(defn node-children [node]
  (let [attrs (when (and (vector? node) (map? (second node))) (second node))]
    (if attrs
      (drop 2 node)
      (drop 1 node))))

(defn count-selectable-asset-rows [node]
  (cond
    (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))
          click-handler (get-in attrs [:on :click])
          row? (and (vector? click-handler)
                    (= :actions/select-asset
                       (-> click-handler first first)))]
      (+ (if row? 1 0)
         (reduce + 0 (map count-selectable-asset-rows children))))

    (map? node)
    0

    (seq? node)
    (reduce + 0 (map count-selectable-asset-rows node))

    :else 0))

(defn selector-props [desktop?]
  {:visible? true
   :desktop? desktop?
   :markets sample-markets
   :selected-market-key "perp:BTC"
   :search-term ""
   :sort-by :name
   :sort-direction :asc
   :favorites #{}
   :favorites-only? false
   :strict? false
   :active-tab :all
   :missing-icons #{}
   :loaded-icons #{}
   :highlighted-market-key nil
   :render-limit 120
   :scroll-top 0})

(defn fake-scroll-node []
  (let [listeners* (atom {})
        host-node #js {}
        node #js {}]
    (set! (.-scrollTop node) 0)
    (aset node "querySelector" (fn [_selector] host-node))
    (aset node "firstElementChild" host-node)
    (aset node "addEventListener" (fn [event-type handler]
                                    (swap! listeners* assoc event-type handler)))
    (aset node "removeEventListener" (fn [event-type _handler]
                                       (swap! listeners* dissoc event-type)))
    {:node node
     :host-node host-node
     :listeners* listeners*}))
