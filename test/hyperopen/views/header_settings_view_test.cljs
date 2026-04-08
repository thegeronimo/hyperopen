(ns hyperopen.views.header-settings-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.header-view :as header-view]))

(defn- find-node [pred node]
  (cond
    (vector? node)
    (or (when (pred node) node)
        (some #(find-node pred %) (rest node)))
    (seq? node)
    (some #(find-node pred %) node)
    :else nil))

(defn- find-node-by-role [node role]
  (find-node #(and (vector? %) (= role (get-in % [1 :data-role]))) node))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (rest node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(deftest header-renders-passkey-row-tooltip-instead-of-inline-helper-copy-when-available-test
  (let [view (header-view/header-view {:wallet {:connected? false
                                                :agent {:storage-mode :local
                                                        :status :ready
                                                        :local-protection-mode :plain
                                                        :passkey-supported? true}}
                                       :router {:path "/trade"}
                                       :header-ui {:settings-open? true
                                                   :settings-confirmation nil}
                                       :trading-settings {:fill-alerts-enabled? true}})
        passkey-row (find-node-by-role view "trading-settings-local-protection-mode-row")
        tooltip-trigger (find-node-by-role view "trading-settings-local-protection-mode-row-tooltip-trigger")
        tooltip (find-node-by-role view "trading-settings-local-protection-mode-row-tooltip")
        row-text (set (collect-strings passkey-row))]
    (is (some? passkey-row))
    (is (some? tooltip-trigger))
    (is (some? tooltip))
    (is (contains? row-text "Lock trading with passkey"))
    (is (not (contains? row-text "Add a passkey unlock after restart.")))
    (is (contains? (set (collect-strings tooltip))
                   "Protect the remembered trading session with a passkey so the key is not resumed automatically after a browser restart."))))

(deftest header-renders-unlock-first-helper-when-passkey-downgrade-is-disabled-test
  (let [view (header-view/header-view {:wallet {:connected? false
                                                :agent {:status :locked
                                                        :storage-mode :local
                                                        :local-protection-mode :passkey
                                                        :passkey-supported? true}}
                                       :router {:path "/trade"}
                                       :header-ui {:settings-open? true
                                                   :settings-confirmation nil}
                                       :trading-settings {:fill-alerts-enabled? true}})
        passkey-row (find-node-by-role view "trading-settings-local-protection-mode-row")
        tooltip-trigger (find-node-by-role view "trading-settings-local-protection-mode-row-tooltip-trigger")
        row-text (set (collect-strings passkey-row))]
    (is (some? passkey-row))
    (is (nil? tooltip-trigger))
    (is (contains? row-text "Lock trading with passkey"))
    (is (contains? row-text "Unlock trading before turning off passkey protection."))))
