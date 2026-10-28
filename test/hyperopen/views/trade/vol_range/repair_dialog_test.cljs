(ns hyperopen.views.trade.vol-range.repair-dialog-test
  "The \"Review rejected orders\" dialog hiccup (HO-SPEC-014 sections 8.6,
  11.4): data roles, checkbox labels, the alert region, a disabled commit with
  nothing selected, Escape-only keyboard handling and class vectors."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.trade.vol-range.repair-dialog :as repair-dialog]))

(def model
  {:grid-id "g"
   :status :ready
   :title "Review rejected orders"
   :identity-line "VRT/USDC · 0x1234…5678 · Post-only"
   :intro "Only the selected orders are sent. The 6 active orders in this ladder are not touched."
   :loading-text nil
   :base-symbol "VRT"
   :legs [{:logical-id "buy-0" :side :buy :side-label "Buy" :selected? true :select-label "Include Buy 7 VRT"
           :rejected-price "76.4" :price "76.16" :size "7" :notional "533.12" :distance "0.8σ"
           :note "Moved away from the book: 76.4 would cross the best ask 76.35."
           :reason "Post only order would have immediately matched"}]
   :unrepairable [{:logical-id "sell-2" :text "Can't repair Sell 10 at 90.8: no post-only price within 50% of the mid."}]
   :bounds [{:label "If all buys fill first" :value "190 VRT"} {:label "If all sells fill first" :value "120 VRT"}
            {:label "Hold at most" :value "190 VRT"} {:label "Keep at least" :value "120 VRT"}]
   :limits-warning nil
   :errors []
   :as-of "Prices as of 14:02:10 UTC"
   :refresh {:enabled? true :busy? false}
   :submit {:label "Place 1 repair order" :enabled? true :busy? false}})

(defn- text [node] (str/join " " (hiccup/collect-strings node)))
(defn- by-role [html role] (hiccup/find-by-data-role html role))

(deftest closed-review-renders-nothing-test
  (is (nil? (repair-dialog/repair-dialog nil))))

(deftest ready-dialog-test
  (let [html (repair-dialog/repair-dialog model)
        dialog (by-role html "vol-ladder-repair-dialog")
        attrs (hiccup/node-attrs dialog)
        checkbox (by-role html "vol-ladder-repair-leg-select")
        leg (by-role html "vol-ladder-repair-leg")
        submit (by-role html "vol-ladder-repair-submit")]
    (is (= "dialog" (:role attrs)))
    (is (true? (:aria-modal attrs)))
    (is (= "vol-ladder-repair-title" (:aria-labelledby attrs)))
    (is (fn? (:replicant/on-render (hiccup/node-attrs html))) "shared dialog focus trap on the mounted node")
    (is (= [[:actions/handle-vol-ladder-repair-keydown [:event/key]]] (get-in attrs [:on :keydown])))
    (is (str/includes? (text dialog) "Review rejected orders"))
    (is (str/includes? (text dialog) "VRT/USDC · 0x1234…5678 · Post-only"))
    (is (str/includes? (text (by-role html "vol-ladder-repair-intro")) "The 6 active orders"))
    (testing "leg row"
      (is (= "buy-0" (:data-logical-id (hiccup/node-attrs leg))))
      (is (= "checkbox" (:type (hiccup/node-attrs checkbox))))
      (is (= "Include Buy 7 VRT" (:aria-label (hiccup/node-attrs checkbox))))
      (is (true? (:checked (hiccup/node-attrs checkbox))))
      (is (= [[:actions/toggle-vol-ladder-repair-leg "buy-0"]] (get-in (hiccup/node-attrs checkbox) [:on :change])))
      (is (= "76.16" (text (by-role html "vol-ladder-repair-price"))))
      (doseq [s ["533.12" "0.8σ" "would cross the best ask 76.35" "immediately matched"]]
        (is (str/includes? (text leg) s) s))
      (doseq [h ["Side" "Rejected at" "New price" "Size (VRT)" "Notional (USDC)" "Distance"]]
        (is (str/includes? (text (by-role html "vol-ladder-repair-legs")) h) h)))
    (is (str/includes? (text (by-role html "vol-ladder-repair-unrepairable")) "Can't repair Sell 10 at 90.8"))
    (is (str/includes? (text (by-role html "vol-ladder-repair-bounds")) "If all buys fill first"))
    (is (nil? (by-role html "vol-ladder-repair-limits-warning")))
    (is (nil? (by-role html "vol-ladder-repair-error")) "no alert without errors")
    (is (nil? (by-role html "vol-ladder-repair-loading")))
    (is (= "Prices as of 14:02:10 UTC" (text (by-role html "vol-ladder-repair-as-of"))))
    (is (= [[:actions/refresh-vol-ladder-repair]] (get-in (hiccup/node-attrs (by-role html "vol-ladder-repair-refresh")) [:on :click])))
    (is (= "Back" (text (by-role html "vol-ladder-repair-close"))))
    (is (= [[:actions/close-vol-ladder-repair]] (get-in (hiccup/node-attrs (by-role html "vol-ladder-repair-close")) [:on :click])))
    (testing "commit"
      (is (= "Place 1 repair order" (text submit)))
      (is (false? (:disabled (hiccup/node-attrs submit))))
      (is (false? (:aria-busy (hiccup/node-attrs submit))))
      (is (= [[:actions/submit-vol-ladder-repair]] (get-in (hiccup/node-attrs submit) [:on :click]))))
    (testing "Replicant class vectors"
      (is (every? #(or (nil? (:class (hiccup/node-attrs %))) (vector? (:class (hiccup/node-attrs %))))
                  (hiccup/find-all-nodes html vector?))))))

(deftest nothing-selected-disables-the-commit-test
  (let [html (repair-dialog/repair-dialog
              (-> model
                  (assoc-in [:legs 0 :selected?] false)
                  (assoc :submit {:label "Place 0 repair orders" :enabled? false :busy? false})))]
    (is (false? (:checked (hiccup/node-attrs (by-role html "vol-ladder-repair-leg-select")))))
    (is (true? (:disabled (hiccup/node-attrs (by-role html "vol-ladder-repair-submit")))))))

(deftest errors-are-an-alert-test
  (let [html (repair-dialog/repair-dialog
              (assoc model
                     :errors ["The market moved since these prices were set. Refresh prices and review again."]
                     :limits-warning "This ladder's inventory limits weren't recorded when it was placed. Only balance checks apply."))
        alert (by-role html "vol-ladder-repair-error")]
    (is (= "alert" (:role (hiccup/node-attrs alert))))
    (is (str/includes? (text alert) "Refresh prices and review again."))
    (is (str/includes? (text (by-role html "vol-ladder-repair-limits-warning")) "weren't recorded"))))

(deftest loading-and-sending-states-test
  (let [html (repair-dialog/repair-dialog
              (assoc model :status :loading :loading-text "Reading the current book…" :legs [] :unrepairable []
                     :bounds [] :as-of nil :refresh {:enabled? false :busy? true}
                     :submit {:label "Place 0 repair orders" :enabled? false :busy? false}))]
    (is (= "Reading the current book…" (text (by-role html "vol-ladder-repair-loading"))))
    (is (nil? (by-role html "vol-ladder-repair-legs")))
    (is (true? (:aria-busy (hiccup/node-attrs (by-role html "vol-ladder-repair-refresh")))))
    (is (true? (:disabled (hiccup/node-attrs (by-role html "vol-ladder-repair-refresh"))))))
  (let [submit (by-role (repair-dialog/repair-dialog
                         (assoc model :submit {:label "Placing…" :enabled? false :busy? true}))
                        "vol-ladder-repair-submit")]
    (is (= "Placing…" (text submit)))
    (is (true? (:aria-busy (hiccup/node-attrs submit))))
    (is (true? (:disabled (hiccup/node-attrs submit))))))
