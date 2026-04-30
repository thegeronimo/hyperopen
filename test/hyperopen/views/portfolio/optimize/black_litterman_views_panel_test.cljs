(ns hyperopen.views.portfolio.optimize.black-litterman-views-panel-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.black-litterman-views-panel :as bl-panel]
            [hyperopen.views.portfolio-view :as portfolio-view]))

(defn- node-children
  [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- find-first-node
  [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)]
      (or (when (pred node) node)
          (some #(find-first-node % pred) children)))

    (seq? node)
    (some #(find-first-node % pred) node)

    :else nil))

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- node-by-role
  [node role]
  (find-first-node node #(= role (get-in % [1 :data-role]))))

(defn- click-actions
  [node]
  (get-in node [1 :on :click]))

(defn- input-actions
  [node]
  (get-in node [1 :on :input]))

(defn- change-actions
  [node]
  (get-in node [1 :on :change]))

(defn- node-text
  [node]
  (apply str (collect-strings node)))

(deftest portfolio-optimizer-workspace-renders-black-litterman-editor-only-for-bl-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"
                                                     :symbol "BTC-USDC"}
                                                    {:instrument-id "perp:ETH"
                                                     :market-type :perp
                                                     :coin "ETH"
                                                     :symbol "ETH-USDC"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :black-litterman
                                                        :views [{:id "view-1"
                                                                 :kind :relative
                                                                 :long-instrument-id "perp:BTC"
                                                                 :short-instrument-id "perp:ETH"
                                                                 :return 0.04
                                                                 :confidence 0.8
                                                                 :confidence-variance 0.2
                                                                 :weights {"perp:BTC" 1
                                                                           "perp:ETH" -1}}]}
                                         :risk-model {:kind :diagonal-shrink}}
                                 :history-data {:candle-history-by-coin
                                                {"BTC" [{:time 1000 :close "100"}
                                                        {:time 2000 :close "110"}]
                                                 "ETH" [{:time 1000 :close "50"}
                                                        {:time 2000 :close "55"}]}
                                                :funding-history-by-coin {}}
                                 :market-cap-by-coin {"BTC" 900
                                                      "ETH" 100}
                                 :runtime {:as-of-ms 2500}}}})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-black-litterman-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-black-litterman-prior-panel")))
    (is (some? (node-by-role view-node "portfolio-optimizer-black-litterman-view-row-0")))
    (is (= [[:actions/add-portfolio-optimizer-black-litterman-view :absolute]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-black-litterman-add-absolute-view"))))
    (is (= [[:actions/add-portfolio-optimizer-black-litterman-view :relative]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-black-litterman-add-relative-view"))))
    (is (= false
           (get-in (node-by-role
                    view-node
                    "portfolio-optimizer-black-litterman-add-relative-view")
                   [1 :disabled])))
    (is (= [[:actions/set-portfolio-optimizer-black-litterman-view-parameter
             "view-1"
             :return
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-black-litterman-view-0-return-input"))))
    (is (= [[:actions/set-portfolio-optimizer-black-litterman-view-parameter
             "view-1"
             :confidence
             [:event.target/value]]]
           (input-actions
            (node-by-role view-node
                          "portfolio-optimizer-black-litterman-view-0-confidence-input"))))
    (is (= [[:actions/set-portfolio-optimizer-black-litterman-view-parameter
             "view-1"
             :short-instrument-id
             [:event.target/value]]]
           (change-actions
            (node-by-role view-node
                          "portfolio-optimizer-black-litterman-view-0-short-instrument-input"))))
    (is (= [[:actions/remove-portfolio-optimizer-black-litterman-view "view-1"]]
           (click-actions
            (node-by-role view-node
                          "portfolio-optimizer-black-litterman-view-0-remove"))))
    (is (contains? strings "Black-Litterman Views"))
    (is (contains? strings "Prior Source"))
    (is (contains? strings "market-cap"))
    (is (contains? strings "Implied Prior Weights"))
    (is (contains? strings "Relative View"))
    (is (contains? strings "Spread Return"))
    (is (contains? strings "Confidence"))))

(deftest black-litterman-panel-renders-vault-names-in-options-and-prior-test
  (let [vault-address "0x3333333333333333333333333333333333333333"
        vault-id (str "vault:" vault-address)
        draft {:universe [{:instrument-id "perp:BTC"
                           :market-type :perp
                           :coin "BTC"
                           :symbol "BTC-USDC"}
                          {:instrument-id vault-id
                           :market-type :vault
                           :coin vault-id
                           :vault-address vault-address
                           :name "Alpha Yield"}]
               :return-model {:kind :black-litterman
                              :views [{:id "view-1"
                                       :kind :absolute
                                       :instrument-id vault-id
                                       :return 0.04
                                       :confidence 0.8}]}}
        prior {:source :equal-weight
               :weights-by-instrument {"perp:BTC" 0.5
                                       vault-id 0.5}}
        panel (bl-panel/black-litterman-views-panel draft prior)
        prior-panel (node-by-role panel "portfolio-optimizer-black-litterman-prior-panel")
        instrument-select (node-by-role panel
                                        "portfolio-optimizer-black-litterman-view-0-instrument-input")
        prior-text (node-text prior-panel)
        select-text (node-text instrument-select)]
    (is (str/includes? prior-text "Alpha Yield"))
    (is (not (str/includes? prior-text vault-id)))
    (is (str/includes? select-text "Alpha Yield"))
    (is (not (str/includes? select-text vault-id)))))
