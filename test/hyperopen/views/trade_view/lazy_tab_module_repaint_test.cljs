(ns hyperopen.views.trade-view.lazy-tab-module-repaint-test
  "Guards the repaint path for lazily-loaded account tab modules.

   The account panel is memoized on a fixed slice of app-db. When a tab's code
   chunk finishes downloading, the loader writes to :account-tab-modules and
   nothing else. If that key is missing from the panel's slice the memo key is
   unchanged, the panel hands back its cached hiccup (spinner included), and the
   table only appears when some unrelated key changes -- on a quiet websocket,
   never. These tests drive the panel across a chunk arrival and assert it
   actually re-renders."
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.account-tab-modules :as account-tab-modules]
            [hyperopen.views.account-info.tab-registry :as tab-registry]
            [hyperopen.views.asset-selector-view :as asset-selector-view]
            [hyperopen.views.trade-view :as trade-view]
            [hyperopen.views.trade.test-support :as support]))

(def ^:private lazy-tabs
  (filterv account-tab-modules/lazy-tab? tab-registry/available-tabs))

(defn- tab-state
  [tab]
  (-> (support/active-asset-state)
      (assoc-in [:account-info :selected-tab] tab)
      (assoc :account-tab-modules (account-tab-modules/default-state))))

(defn- counting-account-info-exports
  [calls]
  {:account-info-view (fn [& _args]
                        (swap! calls inc)
                        [:div {:data-role "stub-account-info"}])})

(deftest account-panel-repaints-when-lazy-tab-chunk-lands-test
  (support/with-viewport-width
    1280
    (fn []
      (let [calls (atom 0)
            loading (account-tab-modules/mark-account-tab-module-loading
                     (tab-state :open-orders)
                     :open-orders)
            loaded (account-tab-modules/mark-account-tab-module-loaded loading :open-orders)]
        (support/with-account-surface-exports
          (counting-account-info-exports calls)
          (fn []
            (trade-view/trade-view loading)
            ;; Load-bearing: without this the test would still pass if the panel
            ;; stopped memoizing altogether, which is a different regression.
            (is (= 1 @calls) "first render should build the panel exactly once")
            (trade-view/trade-view loaded)
            (is (= 2 @calls)
                "account panel must re-render when the open-orders chunk arrives")))))))

(deftest every-lazy-tab-chunk-arrival-repaints-the-account-panel-test
  (is (= [:positions :outcomes :open-orders :twap :trade-history :funding-history :order-history]
         lazy-tabs)
      "lazy tab set changed; confirm the new tab's module state reaches the panel slice")
  (support/with-viewport-width
    1280
    (fn []
      (doseq [tab lazy-tabs]
        (let [calls (atom 0)
              loading (account-tab-modules/mark-account-tab-module-loading (tab-state tab) tab)
              loaded (account-tab-modules/mark-account-tab-module-loaded loading tab)]
          (support/with-account-surface-exports
            (counting-account-info-exports calls)
            (fn []
              (trade-view/trade-view loading)
              (trade-view/trade-view loaded)
              (is (= 2 @calls)
                  (str "no repaint after chunk arrival for tab " tab)))))))))

(deftest account-info-view-state-tracks-lazy-tab-module-progress-test
  (let [select-state @#'trade-view/account-info-view-state
        loading (account-tab-modules/mark-account-tab-module-loading
                 (tab-state :positions)
                 :positions)
        loaded (account-tab-modules/mark-account-tab-module-loaded loading :positions)]
    (is (not= (select-state loading)
              (select-state loaded))
        "the memoized account panel slice must observe lazy tab module progress")))

(deftest account-panel-repaints-when-chunk-lands-during-selector-scroll-test
  (support/with-viewport-width
    1280
    (fn []
      (let [calls (atom 0)
            freeze?* (atom false)
            dropdown-open #(assoc-in % [:asset-selector :visible-dropdown] :asset-selector)
            loading (-> (tab-state :open-orders)
                        dropdown-open
                        (account-tab-modules/mark-account-tab-module-loading :open-orders))
            loaded (account-tab-modules/mark-account-tab-module-loaded loading :open-orders)]
        (with-redefs [asset-selector-view/asset-list-freeze-active? (fn [] @freeze?*)]
          (support/with-account-surface-exports
            (counting-account-info-exports calls)
            (fn []
              ;; Seed the frozen snapshot while the freeze is inactive.
              (trade-view/trade-view loading)
              (is (= 1 @calls))
              (reset! freeze?* true)
              (trade-view/trade-view loaded)
              (is (= 2 @calls)
                  "selector-scroll freeze must not hold back a chunk that just arrived"))))))))
