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

(deftest header-renders-standard-settings-as-tooltip-rows-without-inline-helper-copy-test
  (let [view (header-view/header-view {:wallet {:connected? false
                                                :agent {:storage-mode :local}}
                                       :router {:path "/trade"}
                                       :header-ui {:settings-open? true
                                                   :settings-confirmation nil}
                                       :trading-settings {:fill-alerts-enabled? true
                                                          :animate-orderbook? true
                                                          :show-fill-markers? false
                                                          :confirm-open-orders? true
                                                          :confirm-close-position? false}})
        remember-row (find-node-by-role view "trading-settings-storage-mode-row")
        open-orders-row (find-node-by-role view "trading-settings-confirm-open-orders-row")
        close-position-row (find-node-by-role view "trading-settings-confirm-close-position-row")
        fill-alerts-row (find-node-by-role view "trading-settings-fill-alerts-row")
        animate-orderbook-row (find-node-by-role view "trading-settings-animate-orderbook-row")
        fill-markers-row (find-node-by-role view "trading-settings-fill-markers-row")
        inline-helper-copy? #(some? (find-node (fn [node]
                                                 (contains? (class-token-set node) "max-w-[16rem]"))
                                               %))]
    (is (some? (find-node-by-role view "trading-settings-storage-mode-row-tooltip-trigger")))
    (is (some? (find-node-by-role view "trading-settings-confirm-open-orders-row-tooltip-trigger")))
    (is (some? (find-node-by-role view "trading-settings-confirm-close-position-row-tooltip-trigger")))
    (is (some? (find-node-by-role view "trading-settings-fill-alerts-row-tooltip-trigger")))
    (is (some? (find-node-by-role view "trading-settings-animate-orderbook-row-tooltip-trigger")))
    (is (some? (find-node-by-role view "trading-settings-fill-markers-row-tooltip-trigger")))
    (is (false? (inline-helper-copy? remember-row)))
    (is (false? (inline-helper-copy? open-orders-row)))
    (is (false? (inline-helper-copy? close-position-row)))
    (is (false? (inline-helper-copy? fill-alerts-row)))
    (is (false? (inline-helper-copy? animate-orderbook-row)))
    (is (false? (inline-helper-copy? fill-markers-row)))))

(deftest header-renders-standard-settings-tooltip-copy-test
  (let [view (header-view/header-view {:wallet {:connected? false
                                                :agent {:storage-mode :local}}
                                       :router {:path "/trade"}
                                       :header-ui {:settings-open? true
                                                   :settings-confirmation nil}
                                       :trading-settings {:fill-alerts-enabled? true
                                                          :animate-orderbook? true
                                                          :show-fill-markers? false
                                                          :confirm-open-orders? true
                                                          :confirm-close-position? false}})]
    (is (contains? (set (collect-strings (find-node-by-role view "trading-settings-storage-mode-row-tooltip")))
                   "Keep trading enabled across browser restarts on this device."))
    (is (contains? (set (collect-strings (find-node-by-role view "trading-settings-confirm-open-orders-row-tooltip")))
                   "Ask before sending a new order from the trade form."))
    (is (contains? (set (collect-strings (find-node-by-role view "trading-settings-confirm-close-position-row-tooltip")))
                   "Ask before submitting from the close-position popover."))
    (is (contains? (set (collect-strings (find-node-by-role view "trading-settings-fill-alerts-row-tooltip")))
                   "Show fill alerts while Hyperopen is open."))
    (is (contains? (set (collect-strings (find-node-by-role view "trading-settings-animate-orderbook-row-tooltip")))
                   "Smooth bid and ask depth changes as the book updates."))
    (is (contains? (set (collect-strings (find-node-by-role view "trading-settings-fill-markers-row-tooltip")))
                   "Show buy and sell markers for the active asset on the chart."))))
