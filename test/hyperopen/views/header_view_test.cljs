(ns hyperopen.views.header-view-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.header-view :as header-view]
            [hyperopen.wallet.core :as wallet]))

(defn- find-node [pred node]
  (cond
    (vector? node)
    (or (when (pred node) node)
        (some #(find-node pred %) (rest node)))

    (seq? node)
    (some #(find-node pred %) node)

    :else nil))

(defn- find-node-by-role [node role]
  (find-node #(and (vector? %)
                   (= role (get-in % [1 :data-role])))
             node))

(defn- collect-strings [node]
  (cond
    (string? node) [node]
    (vector? node) (mapcat collect-strings (rest node))
    (seq? node) (mapcat collect-strings node)
    :else []))

(defn- class-values [class-attr]
  (cond
    (nil? class-attr) []
    (string? class-attr) (remove str/blank? (str/split class-attr #"\s+"))
    (sequential? class-attr) (mapcat class-values class-attr)
    :else []))

(def connected-address
  "0x1234567890abcdef1234567890abcdef12345678")

(deftest connected-header-shows-address-dropdown-and-hides-legacy-controls-test
  (let [view (header-view/header-view {:wallet {:connected? true
                                                 :address connected-address}})
        trigger (find-node-by-role view "wallet-menu-trigger")
        all-text (set (collect-strings view))]
    (is (some? trigger))
    (is (contains? (set (collect-strings trigger)) (wallet/short-addr connected-address)))
    (is (not (contains? all-text "Deposit")))
    (is (not (contains? all-text "Withdraw")))
    (is (not (contains? all-text "Connected")))
    (is (not (contains? all-text "Connect Wallet")))))

(deftest disconnected-header-shows-connect-wallet-button-test
  (let [view (header-view/header-view {:wallet {:connected? false
                                                 :connecting? false}})
        connect-btn (find-node-by-role view "wallet-connect-button")
        trigger (find-node-by-role view "wallet-menu-trigger")]
    (is (some? connect-btn))
    (is (= "Connect Wallet" (last connect-btn)))
    (is (nil? trigger))))

(deftest wallet-menu-renders-copy-and-disconnect-controls-test
  (let [view (header-view/header-view {:wallet {:connected? true
                                                 :address connected-address
                                                 :agent {:status :not-ready}}})
        details-node (find-node-by-role view "wallet-menu-details")
        menu-panel (find-node-by-role view "wallet-menu-panel")
        copy-button (find-node-by-role view "wallet-menu-copy")
        storage-mode-toggle (find-node-by-role view "wallet-agent-storage-mode-toggle")
        enable-button (find-node-by-role view "wallet-enable-trading")
        disconnect-button (find-node-by-role view "wallet-menu-disconnect")]
    (is (some? details-node))
    (is (some? menu-panel))
    (is (some? copy-button))
    (is (some? storage-mode-toggle))
    (is (some? enable-button))
    (is (some? disconnect-button))
    (is (= "Disconnect" (last disconnect-button)))))

(deftest wallet-menu-copy-and-disconnect-dispatch-actions-test
  (let [view (header-view/header-view {:wallet {:connected? true
                                                 :address connected-address
                                                 :agent {:status :not-ready
                                                         :storage-mode :session}}})
        copy-button (find-node-by-role view "wallet-menu-copy")
        storage-mode-toggle (find-node-by-role view "wallet-agent-storage-mode-toggle")
        enable-button (find-node-by-role view "wallet-enable-trading")
        disconnect-button (find-node-by-role view "wallet-menu-disconnect")]
    (is (= [[:actions/copy-wallet-address]]
           (get-in copy-button [1 :on :click])))
    (is (= [[:actions/set-agent-storage-mode :local]]
           (get-in storage-mode-toggle [1 :on :click])))
    (is (= [[:actions/enable-agent-trading]]
           (get-in enable-button [1 :on :click])))
    (is (= [[:actions/disconnect-wallet]]
           (get-in disconnect-button [1 :on :click])))))

(deftest wallet-menu-storage-toggle-defaults-to-device-when-mode-is-missing-test
  (let [view (header-view/header-view {:wallet {:connected? true
                                                 :address connected-address
                                                 :agent {:status :not-ready}}})
        storage-mode-toggle (find-node-by-role view "wallet-agent-storage-mode-toggle")
        mode-value (find-node-by-role view "wallet-agent-storage-mode-value")]
    (is (= [[:actions/set-agent-storage-mode :session]]
           (get-in storage-mode-toggle [1 :on :click])))
    (is (contains? (set (collect-strings mode-value)) "Device"))))

(deftest wallet-menu-storage-toggle-dispatches-session-when-local-selected-test
  (let [view (header-view/header-view {:wallet {:connected? true
                                                 :address connected-address
                                                 :agent {:status :not-ready
                                                         :storage-mode :local}}})
        storage-mode-toggle (find-node-by-role view "wallet-agent-storage-mode-toggle")
        mode-value (find-node-by-role view "wallet-agent-storage-mode-value")]
    (is (= [[:actions/set-agent-storage-mode :session]]
           (get-in storage-mode-toggle [1 :on :click])))
    (is (contains? (set (collect-strings mode-value)) "Device"))))

(deftest wallet-menu-hides-enable-button-when-trading-ready-test
  (let [view (header-view/header-view {:wallet {:connected? true
                                                 :address connected-address
                                                 :agent {:status :ready}}})
        status-row (find-node-by-role view "wallet-agent-status")
        enable-button (find-node-by-role view "wallet-enable-trading")]
    (is (some? status-row))
    (is (contains? (set (collect-strings status-row)) "Trading enabled"))
    (is (nil? enable-button))))

(deftest wallet-menu-copy-feedback-renders-success-message-and-icon-test
  (let [view (header-view/header-view {:wallet {:connected? true
                                                 :address connected-address
                                                 :copy-feedback {:kind :success
                                                                 :message "Address copied to clipboard"}}})
        feedback-row (find-node-by-role view "wallet-copy-feedback")
        success-icon (find-node-by-role view "wallet-copy-feedback-success-icon")
        text (set (collect-strings feedback-row))]
    (is (some? feedback-row))
    (is (some? success-icon))
    (is (contains? text "Address copied to clipboard"))))

(deftest wallet-menu-class-attributes-are-tokenized-collections-test
  (let [view (header-view/header-view {:wallet {:connected? true
                                                 :address connected-address}})
        details-node (find-node-by-role view "wallet-menu-details")
        trigger (find-node-by-role view "wallet-menu-trigger")
        chevron (find-node-by-role view "wallet-menu-chevron")
        panel (find-node-by-role view "wallet-menu-panel")]
    (is (sequential? (get-in details-node [1 :class])))
    (is (sequential? (get-in trigger [1 :class])))
    (is (sequential? (get-in chevron [1 :class])))
    (is (sequential? (get-in panel [1 :class])))
    (is (contains? (set (class-values (get-in chevron [1 :class])))
                   "group-open:rotate-180"))))
