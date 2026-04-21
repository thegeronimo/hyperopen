(ns hyperopen.views.spectate-mode-modal-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.spectate-mode-modal :as modal]))

(def ^:private active-address
  "0x1111111111111111111111111111111111111111")

(def ^:private saved-address
  "0x2222222222222222222222222222222222222222")

(def ^:private alternate-address
  "0x3333333333333333333333333333333333333333")

(defn- modal-state
  [ui-overrides]
  {:account-context {:spectate-mode {:active? true
                                      :address active-address}
                     :spectate-ui (merge {:modal-open? true
                                           :anchor {:left 32
                                                    :right 552
                                                    :top 84
                                                    :bottom 124
                                                    :viewport-width 1280
                                                    :viewport-height 900}
                                           :search saved-address
                                           :label "Desk wallet"
                                           :editing-watchlist-address saved-address
                                           :search-error nil}
                                          ui-overrides)
                     :watchlist [{:address active-address
                                  :label "Active wallet"}
                                 {:address saved-address
                                  :label "Desk wallet"}]}
   :wallet {:copy-feedback {:kind :success
                             :message "Spectate link copied"}}})

(defn- by-role
  [view-node role]
  (hiccup/find-by-data-role view-node role))

(defn- all-by-role
  [view-node role]
  (hiccup/find-all-nodes view-node #(= role (get-in % [1 :data-role]))))

(deftest spectate-mode-modal-hides-when-closed-test
  (is (nil? (modal/spectate-mode-modal-view
             (modal-state {:modal-open? false})))))

(deftest spectate-mode-modal-renders-active-editing-watchlist-contract-test
  (let [view-node (modal/spectate-mode-modal-view (modal-state {}))
        strings (set (hiccup/collect-strings view-node))
        root (by-role view-node "spectate-mode-modal-root")
        dialog (by-role view-node "spectate-mode-modal")
        search-input (by-role view-node "spectate-mode-search-input")
        label-input (by-role view-node "spectate-mode-label-input")
        start-button (by-role view-node "spectate-mode-start")
        stop-button (by-role view-node "spectate-mode-stop")
        add-button (by-role view-node "spectate-mode-add-watchlist")
        cancel-edit-button (by-role view-node "spectate-mode-clear-watchlist-edit")
        active-summary (by-role view-node "spectate-mode-active-summary")
        copy-feedback (by-role view-node "spectate-mode-copy-feedback")
        rows (all-by-role view-node "spectate-mode-watchlist-row")
        active-row (first rows)
        saved-row (second rows)]
    (is (some? root))
    (is (= "dialog" (get-in dialog [1 :role])))
    (is (= false (get-in dialog [1 :aria-modal])))
    (is (= saved-address (get-in search-input [1 :value])))
    (is (= "Desk wallet" (get-in label-input [1 :value])))
    (is (= [[:actions/set-spectate-mode-search [:event.target/value]]]
           (get-in search-input [1 :on :input])))
    (is (= [[:actions/set-spectate-mode-label [:event.target/value]]]
           (get-in label-input [1 :on :input])))
    (is (false? (get-in start-button [1 :disabled])))
    (is (= [[:actions/stop-spectate-mode]]
           (get-in stop-button [1 :on :click])))
    (is (false? (get-in add-button [1 :disabled])))
    (is (contains? strings "Stop"))
    (is (contains? strings "Switch"))
    (is (contains? strings "Save Label"))
    (is (= [[:actions/clear-spectate-mode-watchlist-edit]]
           (get-in cancel-edit-button [1 :on :click])))
    (is (contains? (set (hiccup/collect-strings active-summary))
                   "Currently spectating: "))
    (is (contains? (set (hiccup/collect-strings active-summary))
                   active-address))
    (is (contains? (set (hiccup/collect-strings copy-feedback))
                   "Spectate link copied"))
    (is (= 2 (count rows)))
    (is (contains? (hiccup/node-class-set active-row) "bg-base-200/80"))
    (is (contains? (hiccup/node-class-set saved-row) "ring-1"))))

(deftest spectate-mode-modal-disables-invalid-search-actions-and-hides-feedback-test
  (let [view-node (modal/spectate-mode-modal-view
                   (assoc-in (modal-state {:search "not an address"
                                           :label "Pending label"
                                           :editing-watchlist-address nil
                                           :search-error "Enter a valid address."})
                             [:wallet :copy-feedback]
                             {:kind :success :message ""}))
        strings (set (hiccup/collect-strings view-node))
        start-button (by-role view-node "spectate-mode-start")
        add-button (by-role view-node "spectate-mode-add-watchlist")
        label-input (by-role view-node "spectate-mode-label-input")
        error-node (by-role view-node "spectate-mode-search-error")
        feedback-slot (by-role view-node "spectate-mode-copy-feedback-slot")]
    (is (true? (get-in start-button [1 :disabled])))
    (is (true? (get-in add-button [1 :disabled])))
    (is (= "Pending label" (get-in label-input [1 :value])))
    (is (contains? strings "Enter a valid address."))
    (is (some? error-node))
    (is (nil? feedback-slot))))

(deftest spectate-mode-watchlist-row-preserves-action-contract-test
  (let [view-node (modal/spectate-mode-modal-view
                   (assoc-in (modal-state {:search alternate-address
                                           :label ""
                                           :editing-watchlist-address nil})
                             [:account-context :watchlist]
                             [{:address alternate-address
                               :label "Alt desk"}]))
        row (by-role view-node "spectate-mode-watchlist-row")
        spectate-button (by-role row "spectate-mode-watchlist-spectate")
        copy-button (by-role row "spectate-mode-watchlist-copy")
        link-button (by-role row "spectate-mode-watchlist-link")
        edit-button (by-role row "spectate-mode-watchlist-edit")
        remove-button (by-role row "spectate-mode-watchlist-remove")]
    (is (contains? (set (hiccup/collect-strings row)) "Alt desk"))
    (is (= [[:actions/start-spectate-mode-watchlist-address alternate-address]]
           (get-in spectate-button [1 :on :click])))
    (is (= [[:actions/copy-spectate-mode-watchlist-address alternate-address]]
           (get-in copy-button [1 :on :click])))
    (is (= [[:actions/copy-spectate-mode-watchlist-link alternate-address]]
           (get-in link-button [1 :on :click])))
    (is (= [[:actions/edit-spectate-mode-watchlist-address alternate-address]]
           (get-in edit-button [1 :on :click])))
    (is (= [[:actions/remove-spectate-mode-watchlist-address alternate-address]]
           (get-in remove-button [1 :on :click])))))
