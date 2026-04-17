(ns hyperopen.views.notifications-view-trade-confirmation-test
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is]]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.notifications-view :as notifications-view]))

(defn- fill-prop
  [id side symbol qty price ts]
  {:id id
   :side side
   :symbol symbol
   :qty qty
   :price price
   :orderType "limit"
   :ts ts})

(deftest notifications-view-renders-trade-confirmation-toast-variants-test
  (let [fills [(fill-prop "fill-1" :buy "HYPE" 0.25 44.20 1800000000000)
               (fill-prop "fill-2" :buy "HYPE" 0.30 44.30 1800000003300)
               (fill-prop "fill-3" :sell "SOL" 1.00 198.10 1800000006600)
               (fill-prop "fill-4" :buy "BTC" 0.01 65124.00 1800000009900)]
        view-node (notifications-view/notifications-view
                   {:ui {:toasts [{:id "pill"
                                   :kind :success
                                   :toast-surface :trade-confirmation
                                   :variant :pill
                                   :fills [(first fills)]}
                                  {:id "detailed"
                                   :kind :success
                                   :toast-surface :trade-confirmation
                                   :variant :detailed
                                   :fills [(assoc (first fills)
                                                 :qty 4.23
                                                 :orderType "market"
                                                 :slippagePct -0.02)]}
                                  {:id "stack"
                                   :kind :success
                                   :toast-surface :trade-confirmation
                                   :variant :stack
                                   :fills fills}
                                  {:id "consolidated"
                                   :kind :success
                                   :toast-surface :trade-confirmation
                                   :variant :consolidated
                                   :fills (mapv #(assoc % :side :buy :symbol "HYPE") fills)}]}})
        region (hiccup/find-by-data-role view-node "global-toast-region")
        pill-node (hiccup/find-by-data-role view-node "PillToast")
        detailed-node (hiccup/find-by-data-role view-node "DetailedToast")
        stack-node (hiccup/find-by-data-role view-node "ToastStack")
        consolidated-node (hiccup/find-by-data-role view-node "ConsolidatedToast")
        expand-buttons (hiccup/find-all-nodes
                        view-node
                        #(= "trade-toast-expand" (get-in % [1 :data-role])))
        close-buttons (hiccup/find-all-nodes
                       view-node
                       #(= "trade-toast-dismiss" (get-in % [1 :data-role])))]
    (is (= "status" (:role (second region))))
    (is (= "polite" (:aria-live (second region))))
    (is (contains? (hiccup/node-class-set pill-node) "o-toast"))
    (is (contains? (hiccup/node-class-set detailed-node) "detailed"))
    (is (contains? (hiccup/node-class-set stack-node) "o-stack"))
    (is (contains? (hiccup/node-class-set consolidated-node) "o-consol"))
    (is (contains? (hiccup/node-class-set pill-node) "pointer-events-auto"))
    (is (contains? (hiccup/node-class-set detailed-node) "pointer-events-auto"))
    (is (contains? (hiccup/node-class-set stack-node) "pointer-events-auto"))
    (is (contains? (hiccup/node-class-set consolidated-node) "pointer-events-auto"))
    (is (contains? (set (hiccup/collect-strings detailed-node)) "Avg Price"))
    (is (contains? (set (hiccup/collect-strings view-node)) "+1 more fills · collapse into blotter"))
    (is (= #{:button} (set (map first close-buttons))))
    (is (= #{:button} (set (map first expand-buttons))))
    (is (= [[:actions/expand-order-feedback-toast "stack"]]
           (get-in (first expand-buttons) [1 :on :click])))))

(deftest notifications-view-renders-expanded-trade-confirmation-blotter-test
  (let [fills [(fill-prop "fill-1" :buy "HYPE" 0.25 44.20 1800000000000)
               (fill-prop "fill-2" :buy "HYPE" 0.30 44.30 1800000003300)
               (fill-prop "fill-3" :sell "SOL" 1.00 198.10 1800000006600)
               (fill-prop "fill-4" :sell "SOL" 2.00 198.20 1800000009900)]
        view-node (notifications-view/notifications-view
                   {:ui {:toasts [{:id "blotter"
                                   :kind :success
                                   :toast-surface :trade-confirmation
                                   :variant :stack
                                   :expanded? true
                                   :fills fills}]}})
        blotter-node (hiccup/find-by-data-role view-node "BlotterCard")
        collapse-button (hiccup/find-by-data-role view-node "trade-toast-collapse")
        rendered-strings (set (hiccup/collect-strings blotter-node))
        rendered-text (str/join " " (hiccup/collect-strings blotter-node))]
    (is (some? blotter-node))
    (is (contains? (hiccup/node-class-set blotter-node) "o-blotter"))
    (is (contains? (hiccup/node-class-set blotter-node) "pointer-events-auto"))
    (is (contains? rendered-strings "Activity · 4 fills"))
    (is (re-find #"Bought\s+0\.55\s+HYPE" rendered-text))
    (is (re-find #"Sold\s+3\s+SOL" rendered-text))
    (is (= :button (first collapse-button)))
    (is (= [[:actions/collapse-order-feedback-toast "blotter"]]
           (get-in collapse-button [1 :on :click])))))
