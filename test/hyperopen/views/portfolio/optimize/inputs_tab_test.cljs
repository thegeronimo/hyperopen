(ns hyperopen.views.portfolio.optimize.inputs-tab-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.inputs-tab :as inputs-tab]))

(defn- node-children
  [node]
  (if (map? (second node))
    (drop 2 node)
    (drop 1 node)))

(defn- collect-strings
  [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (node-children node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- node-text
  [node]
  (apply str (collect-strings node)))

(deftest inputs-tab-renders-vault-names-in-universe-audit-test
  (let [vault-address "0x5555555555555555555555555555555555555555"
        vault-id (str "vault:" vault-address)
        view-node (inputs-tab/inputs-tab
                   {:portfolio {:optimizer
                                {:active-scenario {:loaded-id "scn_inputs"
                                                   :status :saved}
                                 :draft {:id "scn_inputs"
                                         :universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}
                                                    {:instrument-id vault-id
                                                     :market-type :vault
                                                     :coin vault-id
                                                     :vault-address vault-address
                                                     :name "Alpha Yield"}]
                                         :objective {:kind :target-volatility}
                                         :return-model {:kind :black-litterman
                                                        :views []}
                                         :risk-model {:kind :diagonal-shrink}
                                         :constraints {:long-only? true}
                                         :execution-assumptions {}}}}})
        text (node-text view-node)]
    (is (str/includes? text "Alpha Yield"))
    (is (not (str/includes? text vault-id)))
    (is (not (str/includes? text vault-address)))))
