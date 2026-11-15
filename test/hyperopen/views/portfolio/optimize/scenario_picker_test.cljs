(ns hyperopen.views.portfolio.optimize.scenario-picker-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.views.portfolio.optimize.scenario-picker :as scenario-picker]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [click-actions collect-strings node-by-role]]))

(def ^:private address "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(defn- state-with
  [{:keys [open? index wallet-address]}]
  (cond-> {}
    wallet-address (assoc-in [:wallet :address] wallet-address)
    (some? open?) (assoc-in contracts/ui-scenario-menu-open-path open?)
    index (assoc-in contracts/scenario-index-path index)))

(def ^:private saved-summary
  {:id "scn_01"
   :name "Core Hedge"
   :status :saved
   :expected-return 0.18
   :volatility 0.42
   :updated-at-ms 1700000000000})

(deftest strip-renders-trigger-and-save-test
  (let [strip (scenario-picker/scenario-strip (state-with {}) {})]
    (is (= [[:actions/toggle-portfolio-optimizer-scenario-menu]]
           (click-actions
            (node-by-role strip "portfolio-optimizer-scenario-menu-trigger"))))
    (is (= [[:actions/open-portfolio-optimizer-scenario-save-modal]]
           (click-actions
            (node-by-role strip "portfolio-optimizer-header-save-scenario"))))
    (is (nil? (node-by-role strip "portfolio-optimizer-scenario-menu")))))

(deftest strip-save-disabled-while-saving-test
  (let [strip (scenario-picker/scenario-strip (state-with {}) {:saving-scenario? true})]
    (is (= true
           (get-in (node-by-role strip "portfolio-optimizer-header-save-scenario")
                   [1 :disabled])))))

(deftest open-menu-lists-saved-scenarios-with-actions-test
  (let [strip (scenario-picker/scenario-strip
               (state-with {:open? true
                            :wallet-address address
                            :index {:ordered-ids ["scn_01"]
                                    :by-id {"scn_01" saved-summary}}})
               {})
        row (node-by-role strip "portfolio-optimizer-scenario-row-scn_01")]
    (is (some? (node-by-role strip "portfolio-optimizer-scenario-menu")))
    (is (some? row))
    (is (contains? (set (collect-strings row)) "Core Hedge"))
    (is (= [[:actions/close-portfolio-optimizer-scenario-menu]
            [:actions/navigate "/portfolio/optimize/scn_01"]]
           (click-actions row)))
    (is (some? (node-by-role strip "portfolio-optimizer-scenario-duplicate-scn_01")))
    (is (some? (node-by-role strip "portfolio-optimizer-scenario-archive-scn_01")))
    (is (= [[:actions/new-portfolio-optimizer-scenario]]
           (click-actions
            (node-by-role strip "portfolio-optimizer-scenario-menu-new"))))))

(deftest open-menu-hides-archived-scenarios-test
  (let [strip (scenario-picker/scenario-strip
               (state-with {:open? true
                            :wallet-address address
                            :index {:ordered-ids ["scn_02" "scn_01"]
                                    :by-id {"scn_01" saved-summary
                                            "scn_02" {:id "scn_02"
                                                      :name "Old Idea"
                                                      :status :archived}}}})
               {})]
    (is (some? (node-by-role strip "portfolio-optimizer-scenario-row-scn_01")))
    (is (nil? (node-by-role strip "portfolio-optimizer-scenario-row-scn_02")))))

(deftest open-menu-empty-states-test
  (let [no-wallet (scenario-picker/scenario-strip (state-with {:open? true}) {})
        with-wallet (scenario-picker/scenario-strip
                     (state-with {:open? true :wallet-address address})
                     {})]
    (is (contains? (set (collect-strings
                         (node-by-role no-wallet
                                       "portfolio-optimizer-scenario-menu-empty")))
                   "Connect a wallet to save and load scenarios."))
    (is (contains? (set (collect-strings
                         (node-by-role with-wallet
                                       "portfolio-optimizer-scenario-menu-empty")))
                   "No saved scenarios yet. Configure the setup and use Save scenario to keep it."))))
