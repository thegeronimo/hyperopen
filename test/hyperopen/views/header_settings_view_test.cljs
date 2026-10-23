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

(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) [class-attr]
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(defn- class-token-set [node]
  (set (class-values (get-in node [1 :class]))))

(deftest header-renders-passkey-row-with-inline-popover-hint-test
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
        row-text (set (collect-strings passkey-row))]
    (is (some? passkey-row))
    (is (contains? row-text "Lock trading with passkey"))
    (is (contains? row-text "Require passkey for sensitive actions."))
    (is (nil? (find-node-by-role view "trading-settings-local-protection-mode-row-tooltip-trigger")))))

(deftest header-renders-passkey-disabled-state-as-tooltip-copy-test
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
        row-text (set (collect-strings passkey-row))]
    (is (some? passkey-row))
    (is (contains? row-text "Lock trading with passkey"))
    (is (contains? row-text "Require passkey for sensitive actions."))
    (is (contains? (class-token-set passkey-row) "is-disabled"))))

(deftest header-renders-standard-settings-as-compact-hint-rows-test
  (let [view (header-view/header-view {:wallet {:connected? false
                                                :agent {:storage-mode :local}}
                                       :router {:path "/trade"}
                                       :header-ui {:settings-open? true
                                                   :settings-confirmation nil}
                                       :trading-settings {:fill-alerts-enabled? true
                                                          :animate-orderbook? true
                                                          :show-chart-trades? true
                                                          :confirm-open-orders? true
                                                          :confirm-close-position? false}})
        remember-row (find-node-by-role view "trading-settings-storage-mode-row")
        open-order-safety-row (find-node-by-role view "trading-settings-open-order-safety-mode-row")
        strict-tooltip (find-node-by-role view "trading-settings-open-order-safety-mode-row-option-strict-tooltip")
        extended-tooltip (find-node-by-role view "trading-settings-open-order-safety-mode-row-option-extended-tooltip")
        off-tooltip (find-node-by-role view "trading-settings-open-order-safety-mode-row-option-off-tooltip")
        open-orders-row (find-node-by-role view "trading-settings-confirm-open-orders-row")
        close-position-row (find-node-by-role view "trading-settings-confirm-close-position-row")
        fill-alerts-row (find-node-by-role view "trading-settings-fill-alerts-row")
        sound-row (find-node-by-role view "trading-settings-sound-on-fill-row")
        animate-orderbook-row (find-node-by-role view "trading-settings-animate-orderbook-row")
        chart-trades-row (find-node-by-role view "trading-settings-chart-trades-row")]
    (is (contains? (set (collect-strings remember-row)) "Stay signed in across browser restarts."))
    (is (contains? (set (collect-strings open-order-safety-row)) "Account/vault-wide offline cancel behavior."))
    (is (contains? (class-token-set strict-tooltip) "ts-choice-tooltip"))
    (is (contains? (set (collect-strings strict-tooltip))
                   "Cancels open orders if Hyperopen stops refreshing for about 1 minute."))
    (is (contains? (set (collect-strings extended-tooltip))
                   "Keeps the dead-man switch, but gives this account or vault about 4 hours offline before canceling."))
    (is (contains? (set (collect-strings off-tooltip))
                   "Clears Hyperliquid scheduled cancel. GTC orders stay live until filled, manually canceled, or rejected."))
    (is (contains? (set (collect-strings open-orders-row)) "Show a preview before placing."))
    (is (contains? (set (collect-strings close-position-row)) "Show a preview before closing."))
    (is (contains? (set (collect-strings fill-alerts-row)) "Toast when any order fills."))
    (is (contains? (set (collect-strings sound-row)) "Plays a short chime on fill."))
    (is (contains? (set (collect-strings animate-orderbook-row)) "Animate row changes in the book."))
    (is (contains? (set (collect-strings chart-trades-row)) "Show your buys, sells and position history on the price chart."))))

(deftest header-renders-group-kickers-and-right-aligned-hints-test
  (let [view (header-view/header-view {:wallet {:connected? false
                                                :agent {:storage-mode :local}}
                                       :router {:path "/trade"}
                                       :header-ui {:settings-open? true
                                                   :settings-confirmation nil}
                                       :trading-settings {:fill-alerts-enabled? true
                                                          :animate-orderbook? true
                                                          :show-chart-trades? true
                                                          :confirm-open-orders? true
                                                          :confirm-close-position? false}})]
    (is (contains? (set (collect-strings (find-node-by-role view "trading-settings-confirmations-section")))
                   "Ask before you trade"))
    (is (contains? (set (collect-strings (find-node-by-role view "trading-settings-open-orders-section")))
                   "Exchange safety"))
    (is (contains? (set (collect-strings (find-node-by-role view "trading-settings-alerts-section")))
                   "Feedback when fills land"))
    (is (contains? (set (collect-strings (find-node-by-role view "trading-settings-display-section")))
                   "Visual chrome"))
    (is (contains? (set (collect-strings (find-node-by-role view "trading-settings-session-section")))
                   "Sign-in behavior"))))
