(ns hyperopen.views.account-info.tabs.open-orders-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.account-info.test-support.hiccup :as hiccup]
            [hyperopen.views.account-info.tabs.open-orders :as open-orders-tab]))

(deftest open-orders-sortable-header-uses-secondary-text-and-hover-affordance-test
  (let [header-node (open-orders-tab/sortable-open-orders-header "Time" {:column "Time" :direction :asc})
        sort-icon-node (second (vec (hiccup/node-children header-node)))]
    (is (contains? (hiccup/node-class-set header-node) "text-trading-text-secondary"))
    (is (contains? (hiccup/node-class-set header-node) "hover:text-trading-text"))
    (is (= [[:actions/sort-open-orders "Time"]]
           (get-in header-node [1 :on :click])))
    (is (= "↑" (last sort-icon-node)))))

(deftest open-orders-static-headers-use-secondary-text-style-test
  (let [open-orders [{:oid 101
                      :coin "HYPE"
                      :side "B"
                      :sz "2.0"
                      :orig-sz "2.0"
                      :px "100.0"
                      :type "Limit"
                      :time 1700000000000
                      :reduce-only true
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}]
        content (open-orders-tab/open-orders-tab-content open-orders {:column "Time" :direction :desc})
        header-node (hiccup/tab-header-node content)]
    (doseq [label ["Reduce Only" "Trigger Conditions" "TP/SL"]
            :let [label-node (hiccup/find-first-node header-node
                                                     #(and (= :div (first %))
                                                           (contains? (hiccup/direct-texts %) label)))
                  label-classes (hiccup/node-class-set label-node)]]
      (is (some? label-node))
      (is (contains? label-classes "text-trading-text-secondary"))
      (is (contains? label-classes "min-h-6"))
      (is (contains? label-classes "w-full")))))

(deftest open-orders-cancel-all-header-renders-as-action-button-for-visible-rows-test
  (let [btc-row {:oid 101
                 :coin "BTC"
                 :side "B"
                 :sz "1.0"
                 :orig-sz "1.0"
                 :px "100.0"
                 :type "Limit"
                 :time 1700000000000
                 :reduce-only false
                 :is-trigger false
                 :trigger-condition nil
                 :is-position-tpsl false}
        sol-short-older {:oid 202
                         :coin "SOL"
                         :side "A"
                         :sz "2.0"
                         :orig-sz "2.0"
                         :px "90.0"
                         :type "Limit"
                         :time 1700000001000
                         :reduce-only false
                         :is-trigger false
                         :trigger-condition nil
                         :is-position-tpsl false}
        sol-short-newer {:oid 303
                         :coin "SOL"
                         :side "S"
                         :sz "3.0"
                         :orig-sz "3.0"
                         :px "91.0"
                         :type "Limit"
                         :time 1700000002000
                         :reduce-only false
                         :is-trigger false
                         :trigger-condition nil
                         :is-position-tpsl false}
        content (open-orders-tab/open-orders-tab-content [btc-row sol-short-older sol-short-newer]
                                                         {:column "Time" :direction :desc}
                                                         {:direction-filter :short
                                                          :coin-search "sol"})
        header-node (hiccup/tab-header-node content)
        cancel-button (hiccup/find-first-node header-node
                                              #(and (= :button (first %))
                                                    (contains? (hiccup/direct-texts %) "Cancel All")))
        cancel-button-classes (hiccup/node-class-set cancel-button)]
    (is (some? cancel-button))
    (is (= "Cancel all visible open orders"
           (get-in cancel-button [1 :aria-label])))
    (is (contains? cancel-button-classes "text-trading-red"))
    (is (contains? cancel-button-classes "min-h-6"))
    (is (contains? cancel-button-classes "w-full"))
    (is (contains? cancel-button-classes "hover:text-[#f2b8c5]"))
    (is (= [[:actions/confirm-cancel-visible-open-orders [sol-short-newer
                                                           sol-short-older]
             :event.currentTarget/bounds]]
           (get-in cancel-button [1 :on :click])))))

(deftest open-orders-tab-content-read-only-mode-omits-cancel-affordances-test
  (let [row {:oid 101
             :coin "BTC"
             :side "B"
             :sz "1.0"
             :orig-sz "1.0"
             :px "100.0"
             :type "Limit"
             :time 1700000000000
             :reduce-only false
             :is-trigger false
             :trigger-condition nil
             :is-position-tpsl false}
        content (open-orders-tab/open-orders-tab-content [row]
                                                         {:column "Time" :direction :desc}
                                                         {:read-only? true})
        header-strings (set (hiccup/collect-strings (hiccup/tab-header-node content)))
        row-node (hiccup/first-viewport-row content)
        row-strings (set (hiccup/collect-strings row-node))
        row-buttons (hiccup/find-all-nodes row-node #(= :button (first %)))]
    (is (not (contains? header-strings "Cancel All")))
    (is (not (contains? row-strings "Cancel")))
    (is (= 1 (count row-buttons)))))

(deftest open-orders-tab-content-renders-cancel-error-feedback-test
  (let [row {:oid 101
             :coin "BTC"
             :side "B"
             :sz "1.0"
             :orig-sz "1.0"
             :px "100.0"
             :type "Limit"
             :time 1700000000000
             :reduce-only false
             :is-trigger false
             :trigger-condition nil
             :is-position-tpsl false}
        content (open-orders-tab/open-orders-tab-content [row]
                                                         {:column "Time" :direction :desc}
                                                         {:cancel-error "Missing asset or order id."})
        error-node (hiccup/find-by-data-role content "open-orders-cancel-error")
        error-classes (hiccup/node-class-set error-node)]
    (is (some? error-node))
    (is (= "alert" (get-in error-node [1 :role])))
    (is (= "assertive" (get-in error-node [1 :aria-live])))
    (is (contains? error-classes "text-trading-red"))
    (is (contains? (set (hiccup/collect-strings error-node))
                   "Missing asset or order id."))))

(deftest open-orders-cancel-visible-confirmation-renders-dismiss-and-submit-actions-test
  (let [btc-row {:oid 101
                 :coin "BTC"
                 :side "B"
                 :sz "1.0"
                 :orig-sz "1.0"
                 :px "100.0"
                 :type "Limit"
                 :time 1700000000000
                 :reduce-only false
                 :is-trigger false
                 :trigger-condition nil
                 :is-position-tpsl false}
        content (open-orders-tab/open-orders-tab-content [btc-row]
                                                         {:column "Time" :direction :desc}
                                                         {:cancel-visible-confirmation
                                                          {:open? true
                                                           :orders [btc-row]
                                                           :anchor {:left 940
                                                                    :right 1012
                                                                    :top 118
                                                                    :bottom 142
                                                                    :viewport-width 1440
                                                                    :viewport-height 900}}})
        dialog (hiccup/find-by-data-role content "open-orders-cancel-visible-confirmation")
        backdrop (hiccup/find-by-data-role content "open-orders-cancel-visible-confirmation-backdrop")
        close-button (hiccup/find-by-data-role content "open-orders-cancel-visible-confirmation-close")
        cancel-button (hiccup/find-by-data-role content "open-orders-cancel-visible-confirmation-cancel")
        submit-button (hiccup/find-by-data-role content "open-orders-cancel-visible-confirmation-submit")
        count-pill (hiccup/find-by-data-role content "open-orders-cancel-visible-confirmation-count")
        message-node (hiccup/find-by-data-role content "open-orders-cancel-visible-confirmation-message")]
    (is (some? dialog))
    (is (= "Cancel Visible Orders?"
           (first (hiccup/direct-texts
                   (hiccup/find-by-data-role content
                                             "open-orders-cancel-visible-confirmation-title")))))
    (is (= "1 visible open order"
           (first (hiccup/direct-texts count-pill))))
    (is (str/includes? (first (hiccup/direct-texts message-node))
                       "currently shown in Open Orders"))
    (is (= [[:actions/close-cancel-visible-open-orders-confirmation]]
           (get-in backdrop [1 :on :click])))
    (is (= [[:actions/close-cancel-visible-open-orders-confirmation]]
           (get-in close-button [1 :on :click])))
    (is (= [[:actions/close-cancel-visible-open-orders-confirmation]]
           (get-in cancel-button [1 :on :click])))
    (is (= [[:actions/submit-cancel-visible-open-orders-confirmation]]
           (get-in submit-button [1 :on :click])))
    (is (= [[:actions/handle-cancel-visible-open-orders-confirmation-keydown [:event/key]]]
           (get-in dialog [1 :on :keydown])))
    (is (string? (get-in dialog [1 :style :left])))
    (is (string? (get-in dialog [1 :style :top])))))

(deftest open-orders-grid-template-expands-the-coin-track-without-collapsing-tail-columns-test
  (let [open-orders [{:oid 101
                      :coin "HYPE"
                      :side "B"
                      :sz "2.0"
                      :orig-sz "2.0"
                      :px "100.0"
                      :type "Limit"
                      :time 1700000000000
                      :reduce-only true
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}]
        content (open-orders-tab/open-orders-tab-content open-orders {:column "Time" :direction :desc})
        header-grid-class (some #(when (str/starts-with? % "grid-cols-[") %)
                                (hiccup/node-class-set (hiccup/tab-header-node content)))
        row-grid-class (some #(when (str/starts-with? % "grid-cols-[") %)
                             (hiccup/node-class-set (hiccup/first-viewport-row content)))]
    (is (some? header-grid-class))
    (is (= header-grid-class row-grid-class))
    (is (str/includes? header-grid-class
                       "minmax(90px,1.15fr)"))
    (is (str/includes? header-grid-class
                       "minmax(76px,0.82fr)_minmax(112px,1.15fr)_minmax(64px,0.72fr)_minmax(72px,0.74fr)"))))

(deftest open-orders-rows-center-grid-items-vertically-test
  (let [open-orders [{:oid 101
                      :coin "HYPE"
                      :side "B"
                      :sz "2.0"
                      :orig-sz "2.0"
                      :px "100.0"
                      :type "Limit"
                      :time 1700000000000
                      :reduce-only true
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}]
        content (open-orders-tab/open-orders-tab-content open-orders {:column "Time" :direction :desc})
        row-node (hiccup/first-viewport-row content)
        row-classes (hiccup/node-class-set row-node)]
    (is (contains? row-classes "grid"))
    (is (contains? row-classes "items-center"))))

(deftest open-orders-columns-use-left-alignment-test
  (let [open-orders [{:oid 101
                      :coin "HYPE"
                      :side "B"
                      :sz "2.0"
                      :orig-sz "2.0"
                      :px "100.0"
                      :type "Limit"
                      :time 1700000000000
                      :reduce-only true
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}]
        content (open-orders-tab/open-orders-tab-content open-orders {:column "Time" :direction :desc})
        header-node (hiccup/tab-header-node content)
        header-cells (vec (hiccup/node-children header-node))
        row-node (hiccup/first-viewport-row content)
        row-cells (vec (hiccup/node-children row-node))]
    (doseq [idx (range (count header-cells))]
      (is (not (contains? (hiccup/node-class-set (nth header-cells idx)) "text-right"))))
    (doseq [idx (range (count row-cells))]
      (is (not (contains? (hiccup/node-class-set (nth row-cells idx)) "text-right")))
      (is (not (contains? (hiccup/node-class-set (nth row-cells idx)) "num-right"))))
    (doseq [idx (range (count header-cells))]
      (is (contains? (hiccup/node-class-set (nth header-cells idx)) "text-left")))
    (doseq [idx (range (count row-cells))]
      (is (contains? (hiccup/node-class-set (nth row-cells idx)) "text-left")))))

(deftest open-orders-coin-labels-are-bold-and-side-colored-test
  (let [open-orders [{:oid 101
                      :coin "xyz:NVDA"
                      :side "B"
                      :sz "1.0"
                      :orig-sz "1.0"
                      :px "100.0"
                      :type "Limit"
                      :time 1700000001000
                      :reduce-only false
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}
                     {:oid 102
                      :coin "PUMP"
                      :side "A"
                      :sz "2.0"
                      :orig-sz "2.0"
                      :px "99.5"
                      :type "Limit"
                      :time 1700000000000
                      :reduce-only false
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}]
        content (open-orders-tab/open-orders-tab-content open-orders {:column "Time" :direction :desc})
        long-coin-base (hiccup/find-first-node content #(and (= :span (first %))
                                                             (contains? (hiccup/node-class-set %) "whitespace-nowrap")
                                                             (contains? (hiccup/direct-texts %) "NVDA")))
        short-coin-base (hiccup/find-first-node content #(and (= :span (first %))
                                                              (contains? (hiccup/node-class-set %) "whitespace-nowrap")
                                                              (contains? (hiccup/direct-texts %) "PUMP")))]
    (is (some? long-coin-base))
    (is (some? short-coin-base))
    (is (not (contains? (hiccup/node-class-set long-coin-base) "truncate")))
    (is (not (contains? (hiccup/node-class-set short-coin-base) "truncate")))
    (is (contains? (hiccup/node-class-set long-coin-base) "font-semibold"))
    (is (contains? (hiccup/node-class-set short-coin-base) "font-semibold"))
    (is (= "rgb(151, 252, 228)"
           (get-in long-coin-base [1 :style :color])))
    (is (= "rgb(234, 175, 184)"
           (get-in short-coin-base [1 :style :color])))))

(deftest open-orders-tab-content-resolves-raw-market-ids-through-market-by-key-test
  (let [open-orders [{:oid 101
                      :coin "@107"
                      :side "B"
                      :sz "1.0"
                      :orig-sz "1.0"
                      :px "100.0"
                      :type "Limit"
                      :time 1700000001000
                      :reduce-only false
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}]
        market-by-key {"spot:@107" {:coin "@107"
                                    :market-type :spot
                                    :symbol "AAPL/USDC"
                                    :base "AAPL"
                                    :quote "USDC"}}
        content (open-orders-tab/open-orders-tab-content open-orders
                                                         {:column "Time" :direction :desc}
                                                         {:coin-search "aa"
                                                          :market-by-key market-by-key})
        text-content (set (hiccup/collect-strings content))
        row-node (hiccup/first-viewport-row content)
        coin-cell (nth (vec (hiccup/node-children row-node)) 2)
        coin-button (hiccup/find-first-node coin-cell #(= :button (first %)))]
    (is (contains? text-content "AAPL"))
    (is (not (contains? text-content "@107")))
    (is (some? coin-button))
    (is (= [[:actions/select-asset "@107"]]
           (get-in coin-button [1 :on :click])))))

(deftest open-orders-row-cancel-action-renders-text-button-without-btn-chrome-test
  (let [open-orders [{:oid 101
                      :coin "HYPE"
                      :side "B"
                      :sz "1.0"
                      :orig-sz "1.0"
                      :px "100.0"
                      :type "Limit"
                      :time 1700000001000
                      :reduce-only false
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}]
        content (open-orders-tab/open-orders-tab-content open-orders {:column "Time" :direction :desc})
        row-node (hiccup/first-viewport-row content)
        cancel-cell (nth (vec (hiccup/node-children row-node)) 11)
        action-button (hiccup/find-first-node cancel-cell #(= :button (first %)))
        button-classes (hiccup/node-class-set action-button)]
    (is (some? action-button))
    (is (contains? (set (hiccup/collect-strings cancel-cell)) "Cancel"))
    (is (contains? button-classes "inline-flex"))
    (is (not (contains? button-classes "btn")))
    (is (not (contains? button-classes "btn-spectate")))))

(deftest open-orders-coin-cell-dispatches-select-asset-action-test
  (let [open-orders [{:oid 101
                      :coin "xyz:NVDA"
                      :side "B"
                      :sz "1.0"
                      :orig-sz "1.0"
                      :px "100.0"
                      :type "Limit"
                      :time 1700000001000
                      :reduce-only false
                      :is-trigger false
                      :trigger-condition nil
                      :is-position-tpsl false}]
        content (open-orders-tab/open-orders-tab-content open-orders {:column "Time" :direction :desc})
        row-node (hiccup/first-viewport-row content)
        coin-cell (nth (vec (hiccup/node-children row-node)) 2)
        coin-button (hiccup/find-first-node coin-cell #(= :button (first %)))]
    (is (some? coin-button))
    (is (= [[:actions/select-asset "xyz:NVDA"]]
           (get-in coin-button [1 :on :click])))))
