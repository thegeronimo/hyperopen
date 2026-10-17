(ns hyperopen.vaults.screener.optimizer-handoff-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.vaults.screener.optimizer-handoff :as handoff]
            [hyperopen.views.vaults.optimizer-handoff-sheet :as sheet]))

(defn- row
  [address name tvl]
  {:vault-address address :name name :tvl tvl :relationship {:type :normal}})

(defn- base-state
  []
  {:vaults {:merged-index-rows [(row "0xa" "Alpha" 500000) (row "0xb" "Beta" 300000) (row "0xc" "Gamma" 100000)]
            :saved-views [{:id "v1" :name "Calm" :members ["0xa" "0xb"]
                           :metrics-by-address {"0xa" {:sharpe 2.5}}}]
            :screener {:run {:id 1 :status :done :addresses ["0xa" "0xb" "0xc"]
                             :config {:window :six-month}}
                       :results {"0xa" {:point-count 40 :values {:sharpe 1.5 :max-drawdown -0.1}}
                                 "0xb" {:point-count 40 :values {:sharpe 3.0 :max-drawdown -0.1}}
                                 "0xc" {:point-count 40 :values {:sharpe 0.97 :max-drawdown -0.1}}}}}
   :vaults-ui {:active-saved-view "v1"
               :screener {:criteria [{:metric :sharpe :value "1.0"}
                                     {:metric :max-drawdown :value "25"}]
                          :min-history-points 0}}})

(defn- apply-saves
  [state effects]
  (reduce (fn [s [id & args]]
            (case id
              :effects/save (assoc-in s (first args) (second args))
              :effects/save-many (reduce (fn [s* [path value]] (assoc-in s* path value)) s (first args))
              s))
          state
          effects))

(deftest sources-pick-the-right-vaults-test
  (is (= {:addresses ["0xa" "0xb"] :label "Calm" :view-id "v1"}
         (handoff/source-selection (base-state) :saved-view)))
  (is (= ["0xb" "0xa"] (:addresses (handoff/source-selection (base-state) :screener-pass)))
      "screener sources are ordered best Sharpe first")
  (is (= ["0xb" "0xa" "0xc"] (:addresses (handoff/source-selection (base-state) :screener-near))))
  (is (= ["0xb" "0xa"] (:addresses (handoff/source-selection (base-state) :screener-top)))))

(deftest open-toggle-and-confirm-test
  (let [state (apply-saves (base-state) (handoff/open-vault-optimizer-handoff (base-state) :saved-view))
        sheet* (get-in state handoff/sheet-path)]
    (is (true? (:open? sheet*)))
    (is (= :equal-risk (:objective sheet*)))
    (is (true? (:vault-allocation? sheet*)))
    (testing "dropping a vault and changing the goal"
      (let [state (apply-saves state (handoff/set-vault-optimizer-handoff-field state :toggle-vault "0xb"))
            state (apply-saves state (handoff/set-vault-optimizer-handoff-field state :objective "max-sharpe"))
            effects (handoff/confirm-vault-optimizer-handoff state)
            after (apply-saves state effects)
            draft (get-in after contracts/draft-path)]
        (is (= ["0xa"] (handoff/selected-addresses (get-in state handoff/sheet-path))))
        (is (nil? (get-in after handoff/sheet-path)) "the sheet closes")
        (is (= ["vault:0xa"] (mapv :instrument-id (:universe draft))))
        (is (= {:kind :max-sharpe} (:objective draft)))
        (is (= "Vaults · Calm" (:name draft)))))))

(deftest guards-test
  (is (= [] (handoff/open-vault-optimizer-handoff {} :saved-view)) "no active view, nothing to open")
  (is (= [] (handoff/open-vault-optimizer-handoff (base-state) :bogus)))
  (is (= [] (handoff/confirm-vault-optimizer-handoff (base-state))) "confirm needs an open sheet")
  (is (= [] (handoff/set-vault-optimizer-handoff-field (base-state) :objective :max-sharpe))))

(deftest sheet-renders-rows-and-confirm-navigates-test
  (let [state (apply-saves (base-state) (handoff/open-vault-optimizer-handoff (base-state) :saved-view))
        view (sheet/handoff-sheet state)
        confirm (hiccup/find-by-data-role view "vault-optimizer-handoff-confirm")
        list-node (hiccup/find-by-data-role view "vault-optimizer-handoff-list")]
    (is (= 2 (count (hiccup/node-children list-node))))
    (is (every? #(= :label (first %)) (hiccup/node-children list-node)) "rows are direct children")
    (is (= [[:actions/confirm-vault-optimizer-handoff]
            [:actions/navigate "/portfolio/optimize"]]
           (get-in confirm [1 :on :click]))
        "confirm seeds the draft before navigating, in one click")
    (is (= "Open optimizer · 2 vaults" (hiccup/node-text confirm))))
  (is (nil? (sheet/handoff-sheet (base-state))) "closed sheet renders nothing"))
