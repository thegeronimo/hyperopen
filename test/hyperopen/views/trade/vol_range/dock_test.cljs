(ns hyperopen.views.trade.vol-range.dock-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.vol-range.application.dock-model :as dm]
            [hyperopen.vol-range.application.lifecycle :as lifecycle]
            [hyperopen.vol-range.application.lifecycle-evidence :as evidence]
            [hyperopen.vol-range.application.lifecycle-recovery :as recovery]
            [hyperopen.views.account-info-view :as account-info-view]
            [hyperopen.views.trade.vol-range.dock :as dock]))

(def addr "0x1234567890abcdef1234567890abcdef12345678")
(def scope {:network :hyperliquid-mainnet :account-address addr :coin "SYN/USDC" :symbol "SYN"
            :quote-symbol "USDC" :asset-id 10001})
(defn- cloid [n] (str "0x484f56520000000000000000000000" (if (< n 10) (str "0" n) n)))

(defn- legs []
  [{:leg-id "buy-0" :side :buy :rung-index 0 :price "76.4" :size "7" :cloid (cloid 1)}
   {:leg-id "buy-1" :side :buy :rung-index 1 :price "74" :size "9" :cloid (cloid 2)}
   {:leg-id "sell-0" :side :sell :rung-index 0 :price "84" :size "10" :cloid (cloid 3)}])

(defn- sent-grid []
  (-> (lifecycle/new-grid {:grid-id "g1" :scope scope :scope-key "k" :operation-id "op" :plan-id "p"
                           :expires-after-ms 31000 :now-ms 1000 :legs (legs)
                           :model {:anchor {:mode :custom :price "80"} :volatility {:daily-fraction "0.06"}
                                   :tilt {:enabled? true}}})
      (lifecycle/mark-sending "op" "a" 1500)))

(defn- response [g rejected]
  (evidence/apply-placement-delivery
   g "op" {:delivery :response
           :leg-results (map-indexed
                         (fn [i l]
                           (if (contains? rejected (:cloid l))
                             {:cloid (:cloid l) :placement-state :rejected :reason "Post only would match"}
                             {:cloid (:cloid l) :oid (str (+ 100 i)) :placement-state :accepted :order-state :resting}))
                         (legs))}
   2000))

(defn- state-with [grids & [extra]]
  {:wallet {:address addr}
   :vol-range {:execution (merge {:enabled? true :grids (into {} (map (juxt :grid-id identity)) grids)} extra)}})

(defn- panel [state] (dock/dock-panel (dm/dock state)))
(defn- text [node] (str/join " " (hiccup/collect-strings node)))
(defn- rows [html] (hiccup/find-all-nodes html #(= "vol-ladder-row" (:data-role (hiccup/node-attrs %)))))

(deftest empty-state-and-live-region-test
  (let [html (panel (state-with [] {:last {:op :place :status :busy}}))
        live (hiccup/find-by-data-role html "vol-ladder-status-message")]
    (is (= "polite" (get-in live [1 :aria-live])))
    (is (str/includes? (text live) "Another placement"))
    (is (str/includes? (text html) "No Vol Range ladders yet."))
    (is (empty? (rows html))))
  (testing "the live region stays mounted with no message"
    (is (some? (hiccup/find-by-data-role (panel (state-with [] {:enabled? false})) "vol-ladder-status-message")))))

(deftest live-row-test
  (let [html (panel (state-with [(response (sent-grid) #{})]))
        t (text html)
        status (hiccup/find-by-data-role html "vol-ladder-status")
        account (hiccup/find-by-data-role html "vol-ladder-account")]
    (is (= 1 (count (rows html))))
    (is (= "Live" (text status)))
    (is (not (str/includes? (text status) "⚠")))
    (is (str/includes? t "SYN/USDC"))
    (is (= addr (get-in account [1 :title])))
    (is (= "0x1234…5678" (text account)))
    (is (str/includes? (text (hiccup/find-by-data-role html "vol-ladder-counts"))
                       "3 live · 0 filled · 0 rejected · 0 checking · 0 canceled"))
    (is (str/includes? t "Live buy units 16"))
    (is (str/includes? t "Live sell units 10"))
    (is (str/includes? t "Execution detail (not P&L)"))
    (is (str/includes? t "daily volatility 6.00%"))
    (is (str/includes? t "tilt on"))
    (is (nil? (hiccup/find-by-data-role html "vol-ladder-partial")))
    (is (nil? (hiccup/find-by-data-role html "vol-ladder-detail")) "no detail until selected")
    (let [view (hiccup/find-by-data-role html "vol-ladder-view-orders")
          cancel (hiccup/find-by-data-role html "vol-ladder-cancel")
          refresh (hiccup/find-by-data-role html "vol-ladder-refresh")]
      (is (= "View orders" (text view)))
      (is (false? (get-in view [1 :aria-expanded])))
      (is (= [[:actions/select-vol-ladder "g1"]] (get-in view [1 :on :click])))
      (is (= [[:actions/refresh-vol-ladder "g1"]] (get-in refresh [1 :on :click])))
      (is (= [[:actions/cancel-vol-ladder "g1"]] (get-in cancel [1 :on :click])))
      (is (= "Cancel SYN/USDC ladder (3 live orders)" (get-in cancel [1 :aria-label])))
      (is (= "Cancel this ladder" (text cancel))))))

(deftest partial-row-test
  (let [html (panel (state-with [(response (sent-grid) #{(cloid 1)})]))
        status (hiccup/find-by-data-role html "vol-ladder-status")]
    (is (= "Partially deployed" (text status)))
    (is (= "1 order was not placed. 2 other orders remain active."
           (text (hiccup/find-by-data-role html "vol-ladder-partial"))))
    (is (str/includes? (text (hiccup/find-by-data-role html "vol-ladder-counts")) "1 rejected"))))

(deftest checking-row-is-flagged-for-attention-test
  (let [g (evidence/apply-placement-delivery (sent-grid) "op" {:delivery :unknown} 2000)
        html (panel (state-with [g]))
        status (hiccup/find-by-data-role html "vol-ladder-status")]
    (is (str/includes? (text status) "Checking status"))
    (is (str/includes? (text status) "⚠") "non-color cue")
    (is (contains? (hiccup/node-class-set status) "text-ho-warn"))
    (is (not (str/includes? (text html) "Live buy units 0 ·Live")))))

(deftest recovered-row-test
  (let [g (recovery/recovered-grid {:grid-id "r1" :scope scope :scope-key "k" :now-ms 5000
                                    :rows [{:cloid (cloid 9) :oid 5 :side "B" :limitPx "70" :sz "4" :origSz "4"}]})
        html (panel (state-with [g]))
        status (hiccup/find-by-data-role html "vol-ladder-status")]
    (is (str/includes? (text status) "Recovered Vol Range orders"))
    (is (str/includes? (text status) "⚠"))
    (is (str/includes? (text html) "Original settings unavailable"))))

(deftest refreshing-and-canceling-buttons-are-busy-test
  (let [html (panel (state-with [(response (sent-grid) #{})]
                                {:busy {[:cancel "g1"] true [:refresh "g1"] true}}))
        cancel (hiccup/find-by-data-role html "vol-ladder-cancel")
        refresh (hiccup/find-by-data-role html "vol-ladder-refresh")]
    (is (true? (get-in cancel [1 :disabled])))
    (is (true? (get-in cancel [1 :aria-busy])))
    (is (true? (get-in refresh [1 :disabled])))
    (is (true? (get-in refresh [1 :aria-busy])))))

(deftest detail-panel-test
  (let [g (response (sent-grid) #{(cloid 1)})
        html (panel (state-with [g] {:selected-grid-id "g1"}))
        view (hiccup/find-by-data-role html "vol-ladder-view-orders")
        legs (hiccup/find-all-nodes html #(= "vol-ladder-leg" (:data-role (hiccup/node-attrs %))))]
    (is (= "Hide orders" (text view)))
    (is (true? (get-in view [1 :aria-expanded])))
    (is (= 3 (count legs)))
    (let [t (text (hiccup/find-by-data-role html "vol-ladder-detail"))]
      (doseq [h ["Side" "Price" "Size" "Filled" "Remaining" "Status" "Reason"]]
        (is (str/includes? t h) h))
      (is (str/includes? t "Rejected"))
      (is (str/includes? t "Post only would match")))
    (is (some #(and (= :td (first %)) (= "Buy" (text %)) (contains? (hiccup/node-class-set %) "text-ho-buy"))
              (hiccup/find-all-nodes html #(= :td (first %))))
        "buy token color plus text")
    (is (some #(and (= :td (first %)) (= "Sell" (text %)) (contains? (hiccup/node-class-set %) "text-ho-sell"))
              (hiccup/find-all-nodes html #(= :td (first %)))))))

(deftest earlier-attempt-legs-are-deemphasized-test
  (let [g (response (sent-grid) #{})
        g (-> g (update-in [:members "buy-0" :cloids] conj (cloid 20))
              (assoc-in [:legs (cloid 20)] (assoc (get-in g [:legs (cloid 1)]) :cloid (cloid 20) :price "76.2")))
        html (panel (state-with [g] {:selected-grid-id "g1"}))
        old (filter #(= "true" (:data-historical (hiccup/node-attrs %)))
                    (hiccup/find-all-nodes html #(= "vol-ladder-leg" (:data-role (hiccup/node-attrs %)))))]
    (is (= 1 (count old)))
    (is (contains? (hiccup/node-class-set (first old)) "opacity-60"))
    (is (str/includes? (text (first old)) "earlier attempt"))))

(deftest tab-appears-only-when-enabled-or-grids-exist-test
  (is (nil? (dock/extra-tab {})))
  (is (nil? (dock/extra-tab (state-with [] {:enabled? false}))))
  (is (= :vol-ladders (:id (dock/extra-tab (state-with [] {:enabled? true})))))
  (is (= "Vol ladders" (:label (dock/extra-tab (state-with [(sent-grid)] {:enabled? false})))))
  (testing "opts are untouched without the tab and extended with it"
    (is (= {:a 1} (dock/with-tab {:a 1} {})))
    (let [opts (dock/with-tab {:default-panel-classes ["h-full"]} (state-with [] {:enabled? true}))]
      (is (= ["h-full"] (:default-panel-classes opts)))
      (is (= :vol-ladders (:id (first (:extra-tabs opts)))))
      (is (= :vol-ladders (last (:tab-order opts)))))))

(deftest tab-renders-in-the-account-panel-test
  (let [state (assoc (state-with [] {:enabled? true}) :account-info {:selected-tab :vol-ladders})
        html (account-info-view/account-info-panel state (dock/with-tab {:default-panel-classes ["h-full"]} state))
        t (text html)]
    (is (str/includes? t "Vol ladders"))
    (is (str/includes? t "No Vol Range ladders yet."))
    (is (some? (hiccup/find-by-data-role html "vol-ladder-dock")))))

;; ---------------------------------------------------------------------------
;; Repair (spec 8.6, 11.2)
;; ---------------------------------------------------------------------------

(defn- repair-state [grids & [extra]]
  (assoc (state-with grids extra) :vol-range-dock {:current-scope-key "k"}))

(deftest repair-button-only-with-rejected-orders-test
  (let [html (panel (repair-state [(response (sent-grid) #{(cloid 1)})]))
        button (hiccup/find-by-data-role html "vol-ladder-repair")
        attrs (hiccup/node-attrs button)]
    (is (= "Repair 1 rejected order" (text button)))
    (is (= "Repair 1 rejected order in the SYN/USDC ladder" (:aria-label attrs)))
    (is (false? (:disabled attrs)))
    (is (nil? (:aria-describedby attrs)))
    (is (= [[:actions/open-vol-ladder-repair "g1"]] (get-in attrs [:on :click])))
    (is (nil? (hiccup/find-by-data-role html "vol-ladder-market-hint"))))
  (is (= "Repair 2 rejected orders"
         (text (hiccup/find-by-data-role (panel (repair-state [(response (sent-grid) #{(cloid 1) (cloid 3)})]))
                                         "vol-ladder-repair"))))
  (testing "hidden for a live ladder, placement off, a recovered, canceling or unresolved ladder"
    (doseq [[label s] [["live" (repair-state [(response (sent-grid) #{})])]
                       ["placement off" (repair-state [(response (sent-grid) #{(cloid 1)})] {:enabled? false})]
                       ["canceling" (repair-state [(assoc (response (sent-grid) #{(cloid 1)}) :cancel-requested-at-ms 9)])]
                       ["unresolved" (repair-state [(evidence/apply-placement-delivery (sent-grid) "op"
                                                                                       {:delivery :unknown} 2000)])]
                       ["recovered" (repair-state [(assoc (response (sent-grid) #{(cloid 1)}) :recovered? true)])]]]
      (is (nil? (hiccup/find-by-data-role (panel s) "vol-ladder-repair")) label))))

(deftest repair-on-another-market-is-disabled-with-a-hint-test
  (let [html (panel (assoc-in (repair-state [(response (sent-grid) #{(cloid 1)})])
                              [:vol-range-dock :current-scope-key] "other"))
        attrs (hiccup/node-attrs (hiccup/find-by-data-role html "vol-ladder-repair"))
        hint (hiccup/find-by-data-role html "vol-ladder-market-hint")]
    (is (true? (:disabled attrs)))
    (is (= "Open SYN/USDC to repair or update this ladder." (text hint)))
    (is (= (:id (hiccup/node-attrs hint)) (:aria-describedby attrs)))))

(deftest repair-dialog-mounts-in-the-dock-test
  (is (nil? (hiccup/find-by-data-role (panel (repair-state [(response (sent-grid) #{(cloid 1)})]))
                                      "vol-ladder-repair-dialog")))
  (let [s (assoc-in (repair-state [(response (sent-grid) #{(cloid 1)})]) [:vol-range :repair]
                    {:grid-id "g1" :status :loading})
        html (panel s)]
    (is (some? (hiccup/find-by-data-role html "vol-ladder-repair-dialog")))
    (is (= "Reading the current book…" (text (hiccup/find-by-data-role html "vol-ladder-repair-loading"))))
    (is (some? (hiccup/find-by-data-role html "vol-ladder-status-message")) "the live region stays")))

(deftest repair-results-reach-the-live-region-test
  (let [msg (fn [last-result]
              (text (hiccup/find-by-data-role (panel (repair-state [] {:last last-result}))
                                              "vol-ladder-status-message")))]
    (is (= "Repair sent. 3 of 3 orders active."
           (msg {:op :repair :status :live :delivery :response :counts {:members 3 :live 3 :rejected 0}})))
    (is (= "Checking the repair's outcome. Nothing will be resent."
           (msg {:op :repair :status :checking :delivery :unknown})))
    (is (= "" (msg {:op :repair-preview :status :ok})))))

(deftest view-state-carries-the-repair-and-the-ticket-scope-test
  (let [g (response (sent-grid) #{(cloid 1)})
        s (assoc (state-with [g]) :vol-range (merge (:vol-range (state-with [g]))
                                                    {:repair {:grid-id "g1" :status :loading}
                                                     :replace {:grid-id "g1"}
                                                     :drafts {:ignored true}}))
        v (dock/view-state s)]
    (is (= {:grid-id "g1" :status :loading} (get-in v [:vol-range :repair])))
    (is (= {:grid-id "g1"} (get-in v [:vol-range :replace])))
    (is (nil? (get-in v [:vol-range :drafts])) "only the dock's roots")
    (is (string? (get-in v [:vol-range-dock :current-scope-key])))
    (is (not= v (dock/view-state (assoc-in s [:vol-range :repair :status] :ready)))
        "the dock repaints when the review changes")
    (is (nil? (get-in (dock/view-state {:vol-range {:execution {:enabled? true}}}) [:vol-range-dock :current-scope-key]))
        "no scope work without a ladder")))

;; ---------------------------------------------------------------------------
;; Update (spec 10.2, 11.2)
;; ---------------------------------------------------------------------------

(deftest preview-update-button-test
  (let [html (panel (repair-state [(response (sent-grid) #{})]))
        button (hiccup/find-by-data-role html "vol-ladder-preview-update")
        attrs (hiccup/node-attrs button)]
    (is (= "Preview update" (text button)))
    (is (= "Preview an update of the SYN/USDC ladder" (:aria-label attrs)))
    (is (false? (:disabled attrs)))
    (is (= [[:actions/preview-vol-ladder-update "g1"]] (get-in attrs [:on :click]))))
  (testing "hidden for placement off, recovered, canceling and checking ladders"
    (doseq [[label s] [["placement off" (repair-state [(response (sent-grid) #{})] {:enabled? false})]
                       ["recovered" (repair-state [(assoc (response (sent-grid) #{}) :recovered? true)])]
                       ["canceling" (repair-state [(assoc (response (sent-grid) #{}) :cancel-requested-at-ms 9)])]
                       ["unresolved" (repair-state [(evidence/apply-placement-delivery (sent-grid) "op"
                                                                                       {:delivery :unknown} 2000)])]]]
      (is (nil? (hiccup/find-by-data-role (panel s) "vol-ladder-preview-update")) label)))
  (testing "another market: disabled and described by the one shared hint"
    (let [html (panel (assoc-in (repair-state [(response (sent-grid) #{(cloid 1)})])
                                [:vol-range-dock :current-scope-key] "other"))
          update-attrs (hiccup/node-attrs (hiccup/find-by-data-role html "vol-ladder-preview-update"))
          hints (hiccup/find-all-nodes html #(= "vol-ladder-market-hint" (:data-role (hiccup/node-attrs %))))]
      (is (true? (:disabled update-attrs)))
      (is (= 1 (count hints)))
      (is (= (:id (hiccup/node-attrs (first hints))) (:aria-describedby update-attrs)))
      (is (= "Open SYN/USDC to repair or update this ladder." (text (first hints)))))))

(deftest lineage-line-in-both-directions-test
  (let [old (assoc (response (sent-grid) #{}) :created-at-ms 1793196120000)
        new-grid (assoc (response (sent-grid) #{}) :grid-id "g2" :created-at-ms 1793196540000
                        :predecessor {:grid-id "g1" :frozen-cloids [(cloid 1)]})
        html (panel (repair-state [old new-grid]))
        lines (map text (hiccup/find-all-nodes html #(= "vol-ladder-lineage" (:data-role (hiccup/node-attrs %)))))]
    (is (= #{"Replaces the ladder created 2026-10-28 14:02 UTC" "Replaced by the ladder created 2026-10-28 14:09 UTC"}
           (set lines))))
  (is (nil? (hiccup/find-by-data-role (panel (repair-state [(response (sent-grid) #{})])) "vol-ladder-lineage"))))

(deftest update-results-reach-the-live-region-test
  (let [msg (fn [last-result]
              (text (hiccup/find-by-data-role (panel (repair-state [] {:last last-result}))
                                              "vol-ladder-status-message")))]
    (is (= "Old ladder canceled. Review the updated quantities before placing the replacement."
           (msg {:op :replace-cancel :status :ready})))
    (is (= "Update stopped. Your ladder was not changed."
           (msg {:op :replace-stop :status :stopped :cancel-started? false})))))

(deftest order-form-slice-carries-the-ladder-under-update-test
  (let [g1 (response (sent-grid) #{})
        g2 (assoc g1 :grid-id "g2")
        state {:vol-range {:replace {:grid-id "g1" :phase :preview}
                           :execution {:enabled? true :grids {"g1" g1 "g2" g2} :busy {} :last nil}}}
        slice (dock/order-form-slice state)]
    (is (= {"g1" g1} (get-in slice [:vol-range :execution :grids])) "only the ladder under update")
    (is (nil? (get-in (dock/order-form-slice (update state :vol-range dissoc :replace)) [:vol-range :execution :grids])))
    (is (= {"g1" g1} (get-in (dock/order-form-slice
                              (-> state
                                  (update :vol-range dissoc :replace)
                                  (assoc-in [:vol-range :frozen-context :replace :predecessor :grid-id] "g1")))
                             [:vol-range :execution :grids]))
        "a review frozen with lineage keeps its old ladder")))

(def ^:private live-monitoring
  {:account-address addr :status :live :reasons [] :epoch 4 :last-tick-ms 1000
   :resync {:state :idle :attempt 0}})

(deftest monitoring-line-test
  (testing "live: muted text, no warning cue"
    (let [html (panel (state-with [] {:monitoring live-monitoring}))
          line (hiccup/find-by-data-role html "vol-ladders-monitoring")
          attrs (hiccup/node-attrs line)]
      (is (= :p (first line)))
      (is (= "live" (:data-status attrs)))
      (is (nil? (:data-reason attrs)))
      (is (= "Live updates on" (text line)))
      (is (some #{"text-ho-text-muted"} (:class attrs)))
      (is (not (some #{"text-ho-warn"} (:class attrs))))
      (is (nil? (:aria-live attrs)) "not a live region (spec 11.4)")
      (is (nil? (:role attrs)))))
  (testing "stale: warning color plus an aria-hidden glyph"
    (let [html (panel (state-with [] {:monitoring (assoc live-monitoring :status :stale :reasons [:transport-down])}))
          line (hiccup/find-by-data-role html "vol-ladders-monitoring")
          attrs (hiccup/node-attrs line)
          glyph (first (hiccup/find-all-nodes line #(true? (:aria-hidden (hiccup/node-attrs %)))))]
      (is (= "stale" (:data-status attrs)))
      (is (= "transport-down" (:data-reason attrs)))
      (is (some #{"text-ho-warn"} (:class attrs)))
      (is (= "⚠" (text glyph)))
      (is (str/includes? (text line) "Live updates paused while reconnecting. Your orders stay on the exchange."))))
  (testing "resyncing"
    (let [line (hiccup/find-by-data-role
                (panel (state-with [] {:monitoring (assoc live-monitoring :status :resyncing :reasons [:reconnected]
                                                          :resync {:state :running :attempt 0})}))
                "vol-ladders-monitoring")]
      (is (= "resyncing" (:data-status (hiccup/node-attrs line))))
      (is (= "reconnected" (:data-reason (hiccup/node-attrs line))))
      (is (str/includes? (text line) "Catching up on fills and orders…"))))
  (testing "the line sits above the status message"
    (let [html (panel (state-with [] {:monitoring live-monitoring :last {:op :place :status :busy}}))
          roles (keep #(:data-role (hiccup/node-attrs %)) (drop 2 html))]
      (is (= ["vol-ladders-monitoring" "vol-ladder-status-message"] (take 2 roles)))))
  (testing "no line without a trading account"
    (is (nil? (hiccup/find-by-data-role (dock/dock-panel (dm/dock {:vol-range {:execution {:enabled? true}}}))
                                        "vol-ladders-monitoring")))))

(deftest order-form-slice-narrows-the-execution-root-test
  (let [grid (response (sent-grid) #{})
        state {:wallet {:address addr}
               :vol-range {:ui {:review-open? true}
                           :frozen-review {:plan-id "p"}
                           :repair {:grid-id "g1"}
                           :execution {:enabled? true :max-ladder-usdc "150" :grids {"g1" grid}
                                       :selected-grid-id "g1"
                                       :busy {[:place] true [:reconcile-open] true [:sync] true [:cancel "g1"] true}
                                       :last {:op :place :status :busy}
                                       :monitoring live-monitoring}}}
        slice (dock/order-form-slice state)]
    (is (= {:enabled? true :max-ladder-usdc "150" :busy {[:place] true [:cancel "g1"] true}
            :last {:op :place :status :busy} :monitoring (dissoc live-monitoring :last-tick-ms)}
           (get-in slice [:vol-range :execution])))
    (is (= (dissoc (:vol-range state) :execution) (dissoc (:vol-range slice) :execution))
        "every other key under :vol-range passes through")
    (is (= {} (dock/order-form-slice {})))
    (is (= {:vol-range {:ui {}}} (dock/order-form-slice {:vol-range {:ui {}}})) "no execution root in A0")))
