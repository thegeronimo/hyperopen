(ns hyperopen.views.trade-view.outcome-mobile-sheet-test
  "On a phone an outcome market's Buy bar is pinned to the bottom and the
   ticket opens as a sheet; closable by button, Escape and backdrop."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.trade-view.layout-state :as layout-state]
            [hyperopen.views.trade-view.shell :as shell]))

(defn- layout-for
  [surface market-type viewport]
  (layout-state/trade-layout-state {:trade-ui {:mobile-surface surface}
                                    :active-market {:market-type market-type}}
                                   viewport
                                   false))

(defn- by-role
  [view role]
  (first (hiccup/find-all-nodes view #(= role (get-in % [1 :data-role])))))

(deftest outcome-market-on-a-phone-shows-the-bar-on-market-surfaces-test
  (let [layout (layout-for :chart :outcome 390)]
    (is (true? (:outcome-bar? layout)))
    (is (false? (:outcome-sheet? layout)))
    (is (false? (:order-entry-panel-visible? layout)))))

(deftest ticket-surface-opens-the-sheet-over-the-chart-test
  (let [layout (layout-for :ticket :outcome 390)]
    (is (true? (:outcome-sheet? layout)))
    (is (false? (:outcome-bar? layout)))
    (is (true? (:order-entry-panel-visible? layout)))
    (is (true? (:chart-panel-visible? layout)) "the chart stays underneath")
    (is (contains? (set (:order-entry-panel-classes layout)) "fixed"))))

(deftest perp-and-desktop-layouts-are-unchanged-test
  (let [perp (layout-for :ticket :perp 390)
        desktop (layout-for :chart :outcome 1440)]
    (is (false? (:outcome-sheet? perp)))
    (is (false? (:outcome-bar? perp)))
    (is (false? (:chart-panel-visible? perp)))
    (is (false? (:outcome-bar? desktop)))
    (is (false? (:outcome-sheet? desktop)))))

(deftest buy-bar-has-both-chances-and-one-buy-action-test
  (let [layout (layout-for :chart :outcome 390)
        bar (shell/render-outcome-mobile-bar {:layout layout}
                                             {:title "Pistons vs Wizards"
                                              :entries [{:label "DET" :text "82%"}
                                                        {:label "WAS" :text "18%"}]})
        buy (by-role bar "outcome-mobile-buy")
        strings (hiccup/collect-strings bar)]
    (is (some #{"DET"} strings))
    (is (some #{"82%"} strings))
    (is (some #{"18%"} strings))
    (is (= [[:actions/set-outcome-ticket-entry {:side :buy :size-input-mode :quote :open-ticket-sheet? true}]]
           (get-in buy [1 :on :click]))
        "one click, one action: open the sheet as a Buy")
    (testing "no bar when the layout says no"
      (is (nil? (shell/render-outcome-mobile-bar {:layout (layout-for :chart :perp 390)}
                                                 {:entries [{:label "x" :text "y"}]}))))))

(deftest ticket-sheet-closes-by-button-backdrop-and-escape-test
  (let [layout (layout-for :ticket :outcome 390)
        view (shell/render-order-entry-panel-shell
              false
              {:layout layout}
              {:order-form-panel-state {}
               :outcome-bar-model {:title "Pistons vs Wizards"}
               :hide-account-equity? true}
              {:render-order-form-panel (fn [_] [:div {:data-role "ticket"} "Buy"])})
        sheet (by-role view "outcome-ticket-sheet")
        close (by-role view "outcome-ticket-sheet-close")
        backdrop (by-role view "outcome-ticket-sheet-backdrop")
        prevented (atom 0)]
    (is (= "dialog" (get-in sheet [1 :role])))
    (is (= "true" (get-in sheet [1 :aria-modal])))
    (is (some? (by-role view "ticket")) "the existing ticket is mounted unchanged")
    (is (= [[:actions/select-trade-mobile-surface :chart]] (get-in close [1 :on :click])))
    (is (= [[:actions/select-trade-mobile-surface :chart]] (get-in backdrop [1 :on :click])))
    (is (fn? (get-in sheet [1 :replicant/on-render])) "focus moves in and is restored by the dialog helper")
    (testing "Escape is handled, other keys are not"
      (let [on-key (get-in sheet [1 :on :keydown])]
        (is (fn? on-key))
        (on-key #js {:key "a" :preventDefault #(swap! prevented inc) :stopPropagation (fn [])})
        (is (zero? @prevented))))))
