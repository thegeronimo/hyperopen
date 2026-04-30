(ns hyperopen.views.portfolio.optimize.setup-readiness-panel-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.portfolio.optimize.setup-readiness-panel :as panel]))

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

(defn- node-by-role
  [node role]
  (find-first-node node #(= role (get-in % [1 :data-role]))))

(deftest readiness-panel-names-assets-with-incomplete-vault-history-test
  (let [vault-address "0x2222222222222222222222222222222222222222"
        vault-id (str "vault:" vault-address)
        readiness {:reason :incomplete-history
                   :request {:requested-universe [{:instrument-id vault-id
                                                   :market-type :vault
                                                   :coin vault-id
                                                   :vault-address vault-address
                                                   :name "pmaIt"}]}
                   :blocking-warnings [{:code :missing-vault-history
                                        :instrument-id vault-id
                                        :vault-address vault-address
                                        :message "pmaIt: vault details returned no usable return history."}]
                   :warnings [{:code :missing-vault-history
                               :instrument-id vault-id
                               :vault-address vault-address}]}
        view-node (panel/readiness-panel readiness {:status :succeeded})
        strings (set (collect-strings view-node))]
    (is (some? (node-by-role view-node
                             "portfolio-optimizer-readiness-warning")))
    (is (contains? strings "pmaIt: vault details returned no usable return history."))
    (is (contains? strings "missing-vault-history"))))
