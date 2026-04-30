(ns hyperopen.views.portfolio.optimize.frontier-overlay-markers-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.portfolio.optimizer.fixtures :as fixtures]
            [hyperopen.views.portfolio.optimize.results-panel :as results-panel]))

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

(defn- collect-nodes
  [node pred]
  (cond
    (vector? node)
    (let [children (node-children node)
          matches (when (pred node) [node])]
      (concat matches (mapcat #(collect-nodes % pred) children)))

    (seq? node)
    (mapcat #(collect-nodes % pred) node)

    :else []))

(defn- node-by-role
  [node role]
  (find-first-node node #(= role (get-in % [1 :data-role]))))

(defn- nodes-by-tag
  [node tag]
  (collect-nodes node #(= tag (first %))))

(defn- node-attr
  [node attr]
  (get-in node [1 attr]))

(deftest results-panel-renders-vault-frontier-overlays-with-inline-marker-and-name-test
  (let [vault-address "0x1111111111111111111111111111111111111111"
        vault-id (str "vault:" vault-address)
        vault-label "BTC Basis Carry Vault"
        result (assoc (fixtures/sample-solved-result)
                      :frontier-overlays
                      {:standalone [{:instrument-id vault-id
                                     :label vault-label
                                     :target-weight 0.25
                                     :expected-return 0.2
                                     :volatility 0.18}]
                       :contribution [{:instrument-id vault-id
                                       :label vault-label
                                       :target-weight 0.25
                                       :expected-return 0.05
                                       :volatility 0.06}]})
        standalone-node (results-panel/results-panel
                         {:result result
                          :computed-at-ms 2600}
                         {:objective {:kind :minimum-variance}}
                         {:frontier-overlay-mode :standalone})
        contribution-node (results-panel/results-panel
                           {:result result
                            :computed-at-ms 2600}
                           {:objective {:kind :minimum-variance}}
                           {:frontier-overlay-mode :contribution})
        standalone-group (node-by-role
                          standalone-node
                          (str "portfolio-optimizer-frontier-overlay-standalone-"
                               vault-id))
        standalone-symbol (node-by-role
                           standalone-node
                           (str "portfolio-optimizer-frontier-overlay-symbol-standalone-"
                                vault-id))
        standalone-callout (node-by-role
                            standalone-node
                            (str "portfolio-optimizer-frontier-callout-standalone-"
                                 vault-id))
        standalone-icon (node-by-role
                         standalone-symbol
                         (str "portfolio-optimizer-frontier-vault-icon-standalone-"
                              vault-id))
        standalone-code (node-by-role
                         standalone-symbol
                         (str "portfolio-optimizer-frontier-vault-code-standalone-"
                              vault-id))
        contribution-symbol (node-by-role
                             contribution-node
                             (str "portfolio-optimizer-frontier-overlay-symbol-contribution-"
                                  vault-id))
        contribution-callout (node-by-role
                              contribution-node
                              (str "portfolio-optimizer-frontier-callout-contribution-"
                                   vault-id))
        contribution-icon (node-by-role
                           contribution-symbol
                           (str "portfolio-optimizer-frontier-vault-icon-contribution-"
                                vault-id))
        contribution-code (node-by-role
                           contribution-symbol
                           (str "portfolio-optimizer-frontier-vault-code-contribution-"
                                vault-id))
        standalone-icon-bg (first (nodes-by-tag standalone-icon :rect))
        standalone-label-bg (first (filter #(= 24 (node-attr % :height))
                                           (nodes-by-tag standalone-symbol :rect)))]
    (is (some? standalone-group))
    (is (some? standalone-icon))
    (is (some? contribution-icon))
    (is (= "BCV" (first (collect-strings standalone-code))))
    (is (= "BCV" (first (collect-strings contribution-code))))
    (is (str/starts-with? (node-attr standalone-symbol :transform) "translate("))
    (is (= -15 (node-attr standalone-icon-bg :x)))
    (is (= -15 (node-attr standalone-icon-bg :y)))
    (is (= 30 (node-attr standalone-icon-bg :width)))
    (is (= 30 (node-attr standalone-icon-bg :height)))
    (is (= 6 (node-attr standalone-icon-bg :rx)))
    (is (= 20 (node-attr standalone-label-bg :x)))
    (is (= -12 (node-attr standalone-label-bg :y)))
    (is (= 44 (node-attr standalone-label-bg :width)))
    (is (= 24 (node-attr standalone-label-bg :height)))
    (is (= 5 (node-attr standalone-label-bg :rx)))
    (is (= 13 (node-attr standalone-code :fontSize)))
    (is (= 0 (node-attr standalone-code :y)))
    (is (= "middle" (node-attr standalone-code :dominantBaseline)))
    (is (empty? (collect-nodes standalone-symbol #(= :image (first %))))
        "Vault markers should never request a remote coin SVG from the vault address.")
    (is (empty? (collect-nodes contribution-symbol #(= :image (first %))))
        "Vault contribution markers should use the same inline marker as standalone markers.")
    (is (contains? (set (collect-strings standalone-callout)) vault-label))
    (is (contains? (set (collect-strings contribution-callout)) vault-label))
    (is (not (contains? (set (collect-strings standalone-callout)) vault-id)))
    (is (not (contains? (set (collect-strings contribution-callout)) vault-id)))
    (is (not (str/includes? (node-attr standalone-group :aria-label) vault-id)))
    (is (str/includes? (node-attr standalone-group :aria-label) vault-label))))
