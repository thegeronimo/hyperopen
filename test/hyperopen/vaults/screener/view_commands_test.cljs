(ns hyperopen.vaults.screener.view-commands-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.vaults.application.list-vm.cache :as list-cache]
            [hyperopen.vaults.screener.view-commands :as view-commands]))

(def now-ms
  (.getTime (js/Date. "2026-10-16T00:00:00.000Z")))

(def day-ms
  (* 24 60 60 1000))

(defn- index-row
  [address tvl]
  {:vault-address address :name address :leader "0xl" :tvl tvl
   :create-time-ms (- now-ms (* 400 day-ms))
   :relationship {:type :normal} :is-closed? false})

(defn- screened-state
  []
  (list-cache/reset-cache!)
  {:vaults-ui {:screener {:criteria [{:metric :sharpe :value "1.0"}]}}
   :vaults {:merged-index-rows [(index-row "0xa" 500000) (index-row "0xb" 200000)]
            :screener {:run {:id 3 :status :done :addresses ["0xa" "0xb"]
                             :config {:min-age-days 180 :min-tvl 100000 :window :all-time}}
                       :results {"0xa" {:point-count 40 :values {:sharpe 2}}
                                 "0xb" {:point-count 40 :values {:sharpe 0.2}}}}}})

(defn- apply-saves
  "Apply :effects/save and :effects/save-many to state, like the runtime does."
  [state effects]
  (reduce (fn [s [id & args]]
            (case id
              :effects/save (assoc-in s (first args) (second args))
              :effects/save-many (reduce (fn [s* [path value]] (assoc-in s* path value)) s (first args))
              s))
          state
          effects))

(deftest open-dialog-suggests-a-name-test
  (is (= [[:effects/save [:vaults-ui :save-view-dialog]
           {:open? true :mode :save :name "Sharpe ≥ 1.0" :scope :pass :membership :update}]]
         (view-commands/open-vault-save-view-dialog (screened-state))))
  (is (= [] (view-commands/open-vault-save-view-dialog {})) "nothing to save without results"))

(deftest save-creates-view-selects-it-and-persists-test
  (let [state (apply-saves (screened-state) (view-commands/open-vault-save-view-dialog (screened-state)))
        state (apply-saves state (view-commands/set-vault-save-view-field state :name "Sharpe 1+"))
        effects (view-commands/save-vault-screener-view state now-ms)
        after (apply-saves state effects)
        [view] (get-in after [:vaults :saved-views])]
    (is (= [:effects/persist-vault-saved-views] (last effects)))
    (is (= "Sharpe 1+" (:name view)))
    (is (= ["0xa"] (:members view)))
    (is (= (:id view) (get-in after [:vaults-ui :active-saved-view])))
    (is (= :list (get-in after [:vaults-ui :view])) "saving lands on the All vaults page")
    (is (nil? (get-in after [:vaults-ui :save-view-dialog])))))

(deftest view-menu-commands-test
  (let [state (-> (screened-state)
                  (assoc-in [:vaults :saved-views]
                            [{:id "v1" :name "One" :membership :update :scope :pass
                              :config {:min-age-days 180 :min-tvl 100000 :window :all-time
                                       :criteria [{:metric :sharpe :value "1.0"}]}
                              :members ["0xa"]}])
                  (assoc-in [:vaults-ui :active-saved-view] "v1"))]
    (testing "refresh starts a run tagged with the view over the view's universe"
      (let [effects (view-commands/vault-saved-view-command state :refresh now-ms)
            run (get-in (apply-saves state effects) [:vaults :screener :run])]
        (is (= [:effects/run-vault-screener 4] (last effects)))
        (is (= "v1" (:saved-view-id run)))
        (is (= ["0xa" "0xb"] (:addresses run)))))
    (testing "a fixed view refreshes only its members"
      (let [state* (assoc-in state [:vaults :saved-views 0 :membership] :fixed)
            run (get-in (apply-saves state* (view-commands/vault-saved-view-command state* :refresh now-ms))
                        [:vaults :screener :run])]
        (is (= ["0xa"] (:addresses run)))))
    (testing "toggle-fixed persists"
      (let [effects (view-commands/vault-saved-view-command state :toggle-fixed now-ms)]
        (is (= :fixed (get-in (apply-saves state effects) [:vaults :saved-views 0 :membership])))
        (is (= [:effects/persist-vault-saved-views] (last effects)))))
    (testing "delete needs a confirm step"
      (let [asked (apply-saves state (view-commands/vault-saved-view-command state :request-delete now-ms))
            deleted (apply-saves asked (view-commands/vault-saved-view-command asked :confirm-delete now-ms))]
        (is (true? (get-in asked [:vaults-ui :saved-view-menu :confirm-delete?])))
        (is (= 1 (count (get-in asked [:vaults :saved-views]))))
        (is (empty? (get-in deleted [:vaults :saved-views])))
        (is (nil? (get-in deleted [:vaults-ui :active-saved-view])))))
    (testing "open in screener copies the recipe into the builder"
      (let [after (apply-saves state (view-commands/vault-saved-view-command state :open-in-screener now-ms))]
        (is (= :screener (get-in after [:vaults-ui :view])))
        (is (= :all-time (get-in after [:vaults-ui :screener :window])))
        (is (= 100000 (get-in after [:vaults-ui :screener :min-tvl])))))
    (testing "rename reuses the dialog"
      (let [after (apply-saves state (view-commands/vault-saved-view-command state :rename now-ms))
            after (apply-saves after (view-commands/set-vault-save-view-field after :name "Renamed"))
            after (apply-saves after (view-commands/save-vault-screener-view after now-ms))]
        (is (= "Renamed" (get-in after [:vaults :saved-views 0 :name])))))))

(deftest select-ignores-unknown-views-test
  (let [state {:vaults {:saved-views [{:id "v1"}]}}]
    (is (= "v1" (get-in (apply-saves state (view-commands/select-vault-saved-view state "v1"))
                        [:vaults-ui :active-saved-view])))
    (is (nil? (get-in (apply-saves state (view-commands/select-vault-saved-view state "nope"))
                      [:vaults-ui :active-saved-view])))))

(deftest load-effects-run-once-per-session-test
  (is (= [[:effects/load-vault-saved-views] [:effects/load-vault-screener-cache-index]]
         (view-commands/load-effects {})))
  (is (= [] (view-commands/load-effects {:vaults {:saved-views-loaded? true
                                                   :screener {:cache-index-loaded? true}}}))))
