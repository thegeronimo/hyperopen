(ns hyperopen.views.asset-selector-view-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.views.asset-selector.test-support :as support]
            [hyperopen.views.asset-selector-view :as view]))

(deftest asset-selector-wrapper-reuses-cached-hiccup-for-equal-props-test
  (let [props (assoc (support/selector-props true)
                     :market-by-key {"perp:BTC" (first support/sample-markets)})
        first-result (view/asset-selector-wrapper props)
        second-result (view/asset-selector-wrapper (into {} props))]
    (is (identical? first-result second-result))))

(deftest asset-selector-dropdown-roots-expose-parity-ids-test
  (let [desktop-view (view/asset-selector-dropdown (support/selector-props true))
        mobile-view (view/asset-selector-dropdown (support/selector-props false))
        desktop-dropdown (support/find-node-by-role desktop-view "asset-selector-desktop-dropdown")
        mobile-dropdown (support/find-node-by-role mobile-view "asset-selector-mobile-overlay")]
    (is (= "asset-selector-desktop" (get-in desktop-dropdown [1 :data-parity-id])))
    (is (= "asset-selector-mobile" (get-in mobile-dropdown [1 :data-parity-id])))))

(deftest asset-selector-loading-state-test
  (let [base-props (support/selector-props true)
        full-view (view/asset-selector-dropdown (assoc base-props :loading? true :phase :full))
        bootstrap-view (view/asset-selector-dropdown (assoc base-props :loading? true :phase :bootstrap))
        full-strings (set (support/collect-strings full-view))
        bootstrap-strings (set (support/collect-strings bootstrap-view))]
    (is (contains? full-strings "Loading markets..."))
    (is (contains? bootstrap-strings "Loading markets (bootstrap)..."))))

(deftest asset-selector-loading-state-suppresses-empty-state-until-markets-arrive-test
  (let [desktop-view (view/asset-selector-dropdown (assoc (support/selector-props true)
                                                          :markets []
                                                          :loading? true
                                                          :phase :full))
        mobile-view (view/asset-selector-dropdown (assoc (support/selector-props false)
                                                         :markets []
                                                         :loading? true
                                                         :phase :full))
        desktop-strings (set (support/collect-strings desktop-view))
        mobile-strings (set (support/collect-strings mobile-view))]
    (is (contains? desktop-strings "Loading markets..."))
    (is (contains? mobile-strings "Loading markets..."))
    (is (not (contains? desktop-strings "No assets found")))
    (is (not (contains? mobile-strings "No assets found")))
    (is (not (contains? desktop-strings "Try adjusting your search")))
    (is (not (contains? mobile-strings "Try adjusting your search")))))

(deftest asset-selector-dropdown-renders-desktop-layout-only-when-desktop-test
  (let [dropdown (view/asset-selector-dropdown (support/selector-props true))
        desktop-dropdown (support/find-node-by-role dropdown "asset-selector-desktop-dropdown")
        mobile-overlay (support/find-node-by-role dropdown "asset-selector-mobile-overlay")
        attrs (second desktop-dropdown)
        strings (set (support/collect-strings dropdown))
        navigate-icon (support/find-first-node
                        desktop-dropdown
                        (fn [candidate]
                          (and (vector? candidate)
                               (= :svg (first candidate))
                               (= "0 0 22 13"
                                  (get-in candidate [1 :viewBox])))))]
    (is (some? desktop-dropdown))
    (is (nil? mobile-overlay))
    (is (= [[:actions/handle-asset-selector-shortcut
             [:event/key]
             [:event/metaKey]
             [:event/ctrlKey]
             ["perp:BTC" "perp:xyz:GOLD" "spot:PURR/USDC"]]]
           (get-in attrs [:on :keydown])))
    (is (contains? strings "⌘K"))
    (is (contains? strings "Navigate"))
    (is (contains? strings "Enter"))
    (is (contains? strings "⌘S"))
    (is (contains? strings "Esc"))
    (is (contains? strings "Open"))
    (is (contains? strings "Select"))
    (is (contains? strings "Favorite"))
    (is (contains? strings "Close"))
    (is (not (contains? strings "Cmd/Ctrl+K")))
    (is (not (contains? strings "Cmd/Ctrl+S")))
    (is (not (contains? strings "Up/Down")))
    (is (some? navigate-icon))))

(deftest asset-selector-dropdown-renders-mobile-layout-only-when-mobile-test
  (let [dropdown (view/asset-selector-dropdown (support/selector-props false))
        desktop-dropdown (support/find-node-by-role dropdown "asset-selector-desktop-dropdown")
        mobile-overlay (support/find-node-by-role dropdown "asset-selector-mobile-overlay")
        mobile-close-button (support/find-node-by-role dropdown "asset-selector-mobile-close")
        mobile-strings (set (support/collect-strings mobile-overlay))]
    (is (nil? desktop-dropdown))
    (is (some? mobile-overlay))
    (is (some? mobile-close-button))
    (is (= [[:actions/close-asset-dropdown]]
           (get-in mobile-close-button [1 :on :click])))
    (is (contains? mobile-strings "Symbol"))
    (is (contains? mobile-strings "Volume"))
    (is (contains? mobile-strings "Open Interest"))
    (is (contains? mobile-strings "Last Price"))
    (is (contains? mobile-strings "24h Change"))
    (is (not (contains? mobile-strings "⌘K")))))

(deftest asset-selector-dropdown-mobile-overlay-uses-fullscreen-shell-test
  (let [dropdown (view/asset-selector-dropdown (support/selector-props false))
        mobile-overlay (support/find-node-by-role dropdown "asset-selector-mobile-overlay")
        classes (set (support/collect-all-classes mobile-overlay))]
    (is (contains? classes "fixed"))
    (is (contains? classes "inset-0"))
    (is (contains? classes "flex-col"))
    (is (contains? classes "lg:hidden"))))
