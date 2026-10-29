(ns hyperopen.views.trade.vol-range.replace-dialog-test
  "The \"Update ladder\" dialog and ticket banner hiccup (HO-SPEC-014
  sections 10.2, 11.4): dialog attributes and focus hook, the step indicator
  marked by text, the compare table, the exact intermediate message,
  unchecked rejected members, Escape-only keyboard handling, no order action
  anywhere and class vectors."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.trade.vol-range.replace-dialog :as replace-dialog]))

(def ^:private abandon {:label "Stop updating" :enabled? true :busy? false :aria-label nil})

(def preview-model
  {:grid-id "g1"
   :phase :preview
   :stale-text nil
   :title "Update ladder"
   :identity-line "VRT/USDC · 0x1234…5678 · Post-only"
   :steps [{:label "1 Preview" :current? true} {:label "2 Cancel old ladder" :current? false}
           {:label "3 Review replacement" :current? false}]
   :explainer "Updating takes two separate steps."
   :compare [{:field :anchor :label "Anchor" :current "80 · Custom anchor" :proposed "80 · Custom anchor"}
             {:field :buys :label "Buys" :current "3 orders · 74 to 69.2 · 33 VRT" :proposed nil}]
   :settings-note nil
   :quantity-note "Proposed quantities are the unfilled remainder of this ladder. Edit the ticket to change them."
   :phase-text nil
   :blocking []
   :message nil
   :remaining-text nil
   :filled-text nil
   :included-text nil
   :rejected []
   :actions {:cancel-old {:label "Cancel current ladder to update" :enabled? true :busy? false
                          :aria-label "Cancel the 6 open orders of the VRT/USDC ladder"}
             :edit {:label "Edit in ticket" :enabled? true :busy? false :aria-label nil}
             :cancel-remaining nil :check nil :use-quantities nil :abandon abandon}})

(def canceled-model
  (assoc preview-model
         :phase :canceled
         :steps [{:label "1 Preview" :current? false} {:label "2 Cancel old ladder" :current? false}
                 {:label "3 Review replacement" :current? true}]
         :explainer nil :compare [] :quantity-note nil
         :message "Old ladder canceled. Review the updated quantities before placing the replacement."
         :remaining-text "Unfilled remainder: buys 40 VRT, sells 27 VRT."
         :filled-text "Filled while canceling: sells 3 VRT."
         :rejected [{:logical-id "buy-0" :label "Include Buy 7 at 76.4" :selected? false :enabled? true}]
         :actions {:cancel-old nil :edit nil :cancel-remaining nil :check nil
                   :use-quantities {:label "Review replacement" :enabled? true :busy? false :aria-label nil}
                   :abandon abandon}))

(defn- text [node] (str/join " " (hiccup/collect-strings node)))
(defn- by-role [html role] (hiccup/find-by-data-role html role))
(defn- click [html role] (get-in (hiccup/node-attrs (by-role html role)) [:on :click]))

(deftest closed-renders-nothing-test
  (is (nil? (replace-dialog/replace-dialog nil)))
  (is (nil? (replace-dialog/replace-banner nil))))

(deftest preview-dialog-test
  (let [html (replace-dialog/replace-dialog preview-model)
        dialog (by-role html "vol-range-replace-dialog")
        attrs (hiccup/node-attrs dialog)
        steps (by-role html "vol-range-replace-steps")
        current (hiccup/find-first-node steps #(= "step" (:aria-current (hiccup/node-attrs %))))]
    (is (= "dialog" (:role attrs)))
    (is (true? (:aria-modal attrs)))
    (is (= "vol-range-replace-title" (:aria-labelledby attrs)))
    (is (fn? (:replicant/on-render (hiccup/node-attrs html))) "shared focus hook on the mounted node")
    (is (= [[:actions/handle-vol-ladder-update-keydown [:event/key]]] (get-in attrs [:on :keydown])))
    (is (str/includes? (text dialog) "Update ladder"))
    (testing "the current step is marked by text, not color only"
      (is (= "1 Preview (current step)" (text current))))
    (is (= "Updating takes two separate steps." (text (by-role html "vol-range-replace-explainer"))))
    (testing "compare table"
      (let [table (by-role html "vol-range-replace-compare")
            rows (hiccup/find-all-nodes table #(= "vol-range-replace-compare-row" (:data-role (hiccup/node-attrs %))))]
        (doseq [h ["Current ladder" "Proposed"]] (is (str/includes? (text table) h) h))
        (is (= ["anchor" "buys"] (mapv #(:data-field (hiccup/node-attrs %)) rows)))
        (is (str/includes? (text (second rows)) "—") "a missing value shows a dash")))
    (is (nil? (by-role html "vol-range-replace-settings-note")))
    (is (str/includes? (text (by-role html "vol-range-replace-quantity-note")) "unfilled remainder"))
    (testing "controls"
      (let [cancel (by-role html "vol-range-replace-cancel-old")]
        (is (= "Cancel current ladder to update" (text cancel)))
        (is (= "Cancel the 6 open orders of the VRT/USDC ladder" (:aria-label (hiccup/node-attrs cancel))))
        (is (= [[:actions/cancel-vol-ladder-for-update]] (click html "vol-range-replace-cancel-old"))))
      (is (= [[:actions/close-vol-ladder-update]] (click html "vol-range-replace-edit")))
      (is (= [[:actions/close-vol-ladder-update]] (click html "vol-range-replace-dismiss")))
      (is (= [[:actions/stop-vol-ladder-update]] (click html "vol-range-replace-abandon")))
      (is (nil? (by-role html "vol-range-replace-use-quantities"))))
    (testing "no order action, no form, and Replicant class vectors"
      (is (not (re-find #":actions/(place|submit|confirm)" (pr-str html))))
      (is (empty? (hiccup/find-all-nodes html #(= :form (first %)))))
      (is (every? #(or (nil? (:class (hiccup/node-attrs %))) (vector? (:class (hiccup/node-attrs %))))
                  (hiccup/find-all-nodes html vector?))))))

(deftest settings-note-test
  (let [html (replace-dialog/replace-dialog (assoc preview-model :settings-note "This ladder's original settings weren't recorded; the proposal uses the ticket's current settings."))]
    (is (str/includes? (text (by-role html "vol-range-replace-settings-note")) "weren't recorded"))))

(deftest canceling-and-blocked-test
  (let [html (replace-dialog/replace-dialog
              (assoc preview-model :phase :canceling :explainer nil :compare [] :quantity-note nil
                     :phase-text "Canceling the old ladder…"
                     :actions {:cancel-old nil :edit nil :cancel-remaining nil :check nil :use-quantities nil
                               :abandon abandon}))
        phase (by-role html "vol-range-replace-phase")]
    (is (= "Canceling the old ladder…" (text phase)))
    (is (true? (:aria-busy (hiccup/node-attrs phase))))
    (is (nil? (by-role html "vol-range-replace-cancel-old"))))
  (let [html (replace-dialog/replace-dialog
              (assoc preview-model :phase :blocked :explainer nil :compare [] :quantity-note nil
                     :blocking ["2 old orders are still open."]
                     :actions {:cancel-old nil :edit nil :use-quantities nil :abandon abandon
                               :cancel-remaining {:label "Cancel remaining orders" :enabled? true :busy? false
                                                  :aria-label nil}
                               :check {:label "Check again" :enabled? false :busy? true :aria-label nil}}))]
    (is (str/includes? (text (by-role html "vol-range-replace-blocking")) "2 old orders are still open."))
    (is (= [[:actions/cancel-vol-ladder-for-update]] (click html "vol-range-replace-cancel-remaining")))
    (is (= [[:actions/check-vol-ladder-update]] (click html "vol-range-replace-check")))
    (is (true? (:aria-busy (hiccup/node-attrs (by-role html "vol-range-replace-check")))))
    (is (true? (:disabled (hiccup/node-attrs (by-role html "vol-range-replace-check")))))
    (is (nil? (by-role html "vol-range-replace-use-quantities")) "no Review replacement while blocked")))

(deftest canceled-dialog-test
  (let [html (replace-dialog/replace-dialog canceled-model)
        checkbox (by-role html "vol-range-replace-rejected-select")
        cattrs (hiccup/node-attrs checkbox)
        label (hiccup/find-first-node html #(= :label (first %)))]
    (is (= "Old ladder canceled. Review the updated quantities before placing the replacement."
           (text (by-role html "vol-range-replace-message"))))
    (is (= "Unfilled remainder: buys 40 VRT, sells 27 VRT." (text (by-role html "vol-range-replace-remaining"))))
    (is (= "Filled while canceling: sells 3 VRT." (text (by-role html "vol-range-replace-filled"))))
    (is (str/includes? (text (by-role html "vol-range-replace-rejected")) "Orders that were never placed"))
    (is (str/includes? (text (by-role html "vol-range-replace-rejected")) "not included unless you select them"))
    (is (false? (:checked cattrs)) "unchecked by default")
    (is (= [[:actions/toggle-vol-ladder-update-rejected "buy-0"]] (get-in cattrs [:on :change])))
    (is (= (:id cattrs) (:for (hiccup/node-attrs label))))
    (is (= "Include Buy 7 at 76.4" (text label)))
    (is (= [[:actions/review-vol-ladder-replacement]] (click html "vol-range-replace-use-quantities")))
    (is (nil? (by-role (replace-dialog/replace-dialog (assoc canceled-model :filled-text nil))
                       "vol-range-replace-filled"))
        "omitted when nothing filled")))

(deftest banner-test
  (let [preview {:phase :preview :title "Updating the VRT/USDC ladder · step 1 of 3" :message nil :lineage nil
                 :replaces-footer? true :open-update? true :refresh-balances nil :busy? false}
        html (replace-dialog/replace-banner preview)
        attrs (hiccup/node-attrs html)]
    (is (= "vol-range-replace-banner" (:data-role attrs)))
    (is (= "status" (:role attrs)))
    (is (str/includes? (text html) "Updating the VRT/USDC ladder · step 1 of 3"))
    (is (= [[:actions/open-vol-ladder-update]] (click html "vol-range-replace-open")))
    (is (= [[:actions/stop-vol-ladder-update]] (click html "vol-range-replace-banner-stop")))
    (is (nil? (by-role html "vol-range-replace-refresh-balances"))))
  (let [html (replace-dialog/replace-banner
              {:phase :ready :title "Updating the VRT/USDC ladder · step 3 of 3"
               :message "Old ladder canceled. Review the updated quantities before placing the replacement."
               :lineage "Replacing the ladder created 2026-10-28 14:02 UTC"
               :replaces-footer? false :open-update? false :refresh-balances {:enabled? true :busy? false}
               :busy? false})]
    (is (str/includes? (text (by-role html "vol-range-replace-banner-message")) "Old ladder canceled."))
    (is (= "Replacing the ladder created 2026-10-28 14:02 UTC" (text (by-role html "vol-range-replace-lineage"))))
    (is (= [[:actions/check-vol-ladder-update]] (click html "vol-range-replace-refresh-balances")))
    (is (nil? (by-role html "vol-range-replace-open")))))
