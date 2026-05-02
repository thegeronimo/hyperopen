(ns hyperopen.views.portfolio.optimize.black-litterman-views-panel-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.views.portfolio.optimize.black-litterman-views-panel :as bl-panel]
            [hyperopen.test-support.hiccup :as hiccup]
            [hyperopen.views.portfolio-view :as portfolio-view]))

(defn- deep-merge
  [& maps]
  (apply merge-with
         (fn [left right]
           (if (and (map? left)
                    (map? right))
             (deep-merge left right)
             right))
         maps))

(defn- click-actions
  [node]
  (get-in node [1 :on :click]))

(defn- optimizer-view
  [& overrides]
  (portfolio-view/portfolio-view
   (apply deep-merge
          {:router {:path "/portfolio/optimize/new"}
           :portfolio
           {:optimizer
            {:draft
             {:universe [{:instrument-id "perp:BTC"
                          :market-type :perp
                          :coin "BTC"
                          :symbol "BTC-USDC"}
                         {:instrument-id "perp:ETH"
                          :market-type :perp
                          :coin "ETH"
                          :symbol "ETH-USDC"}
                         {:instrument-id "perp:SOL"
                          :market-type :perp
                          :coin "SOL"
                          :symbol "SOL-USDC"}
                         {:instrument-id "perp:HYPE"
                          :market-type :perp
                          :coin "HYPE"
                          :symbol "HYPE-USDC"}]
              :objective {:kind :max-sharpe}
              :return-model {:kind :black-litterman
                             :views [{:id "view-1"
                                      :kind :absolute
                                      :instrument-id "perp:HYPE"
                                      :return 0.45
                                      :confidence 0.75
                                      :horizon :1y
                                      :notes "Momentum conviction"}
                                     {:id "view-2"
                                      :kind :relative
                                      :instrument-id "perp:ETH"
                                      :comparator-instrument-id "perp:SOL"
                                      :direction :outperform
                                      :return 0.05
                                      :confidence 0.5
                                      :horizon :6m
                                      :notes "Pair view"}]}
              :risk-model {:kind :diagonal-shrink}}}}
           :portfolio-ui
           {:optimizer
            {:black-litterman-editor
             {:selected-kind :absolute
              :drafts {:absolute {:instrument-id "perp:HYPE"
                                  :return-text "45"
                                  :confidence :high
                                  :horizon :1y
                                  :notes "Momentum conviction"}
                       :relative {:instrument-id "perp:ETH"
                                  :comparator-instrument-id "perp:SOL"
                                  :direction :outperform
                                  :return-text "5"
                                  :confidence :medium
                                  :horizon :6m
                                  :notes "Pair view"}}
              :editing-view-id nil
              :clear-confirmation-open? false}}}}
          overrides)))

(deftest portfolio-optimizer-workspace-renders-edit-views-panel-copy-preview-and-add-action-test
  (let [view-node (optimizer-view)
        panel (hiccup/find-by-data-role view-node "portfolio-optimizer-black-litterman-panel")
        save-button (hiccup/find-by-data-role view-node
                                              "portfolio-optimizer-black-litterman-save-view")
        panel-text (hiccup/node-text panel)
        strings (set (hiccup/collect-strings panel))]
    (is (some? panel))
    (is (contains? strings "EDIT VIEWS"))
    (is (contains? strings "Tell the model what you believe"))
    (is (contains? strings "ACTIVE VIEWS (2/10)"))
    (is (str/includes? panel-text "HYPE expected return +45% annualized"))
    (is (= [[:actions/save-portfolio-optimizer-black-litterman-editor-view]]
           (click-actions save-button)))
    (is (contains? strings "+ Add view"))
    (is (str/includes? (str/lower-case panel-text)
                       "views adjust expected returns only"))
    (is (= 0 (hiccup/count-nodes panel #(= :select (first %)))))))

(deftest portfolio-optimizer-workspace-renders-save-mode-and-cancel-edit-action-test
  (let [view-node (optimizer-view
                   {:portfolio-ui
                    {:optimizer
                     {:black-litterman-editor
                      {:editing-view-id "view-2"
                       :selected-kind :relative}}}})
        panel (hiccup/find-by-data-role view-node "portfolio-optimizer-black-litterman-panel")
        save-button (hiccup/find-by-data-role view-node
                                              "portfolio-optimizer-black-litterman-save-view")
        cancel-button (hiccup/find-by-data-role view-node
                                                "portfolio-optimizer-black-litterman-cancel-edit")
        strings (set (hiccup/collect-strings panel))]
    (is (contains? strings "Save changes"))
    (is (= [[:actions/save-portfolio-optimizer-black-litterman-editor-view]]
           (click-actions save-button)))
    (is (= [[:actions/cancel-portfolio-optimizer-black-litterman-edit]]
           (click-actions cancel-button)))))

(deftest black-litterman-panel-expands-single-absolute-asset-selector-test
  (let [absolute-view (optimizer-view)
        absolute-grid (hiccup/find-by-data-role
                       absolute-view
                       "portfolio-optimizer-black-litterman-editor-instrument-grid")
        absolute-classes (hiccup/node-class-set absolute-grid)
        relative-view (optimizer-view
                       {:portfolio-ui
                        {:optimizer
                         {:black-litterman-editor
                          {:selected-kind :relative}}}})
        relative-grid (hiccup/find-by-data-role
                      relative-view
                      "portfolio-optimizer-black-litterman-editor-instrument-grid")
        relative-classes (hiccup/node-class-set relative-grid)]
    (is (contains? absolute-classes "grid-cols-1"))
    (is (not (contains? absolute-classes "sm:grid-cols-2")))
    (is (not (contains? absolute-classes "2xl:grid-cols-2")))
    (is (contains? relative-classes "sm:grid-cols-2"))
    (is (contains? relative-classes "2xl:grid-cols-2"))
    (is (some? (hiccup/find-by-data-role
                relative-grid
                "portfolio-optimizer-black-litterman-editor-comparator-options")))))

(deftest portfolio-optimizer-workspace-wires-active-view-cards-and-clear-confirmation-test
  (let [view-node (optimizer-view
                   {:portfolio-ui
                    {:optimizer
                     {:black-litterman-editor
                      {:clear-confirmation-open? true}}}})
        panel (hiccup/find-by-data-role view-node "portfolio-optimizer-black-litterman-panel")
        active-card (hiccup/find-by-data-role panel
                                              "portfolio-optimizer-black-litterman-active-view-view-1")
        remove-button (hiccup/find-by-data-role panel
                                                "portfolio-optimizer-black-litterman-active-view-view-1-remove")
        clear-button (hiccup/find-by-data-role panel
                                               "portfolio-optimizer-black-litterman-clear-all")
        confirm-button (hiccup/find-by-data-role panel
                                                 "portfolio-optimizer-black-litterman-clear-confirm")
        cancel-button (hiccup/find-by-data-role panel
                                                "portfolio-optimizer-black-litterman-clear-cancel")
        panel-text (hiccup/node-text panel)]
    (is (str/includes? panel-text "ETH > SOL by 5% annualized"))
    (is (= [[:actions/edit-portfolio-optimizer-black-litterman-view "view-1"]]
           (click-actions active-card)))
    (is (= [[:actions/remove-portfolio-optimizer-black-litterman-view "view-1"]]
           (click-actions remove-button)))
    (is (= [[:actions/request-clear-portfolio-optimizer-black-litterman-views]]
           (click-actions clear-button)))
    (is (= [[:actions/confirm-clear-portfolio-optimizer-black-litterman-views]]
           (click-actions confirm-button)))
    (is (= [[:actions/cancel-clear-portfolio-optimizer-black-litterman-views]]
           (click-actions cancel-button)))))

(deftest black-litterman-panel-renders-vault-names-in-editor-and-active-views-test
  (let [vault-address "0x3333333333333333333333333333333333333333"
        vault-id (str "vault:" vault-address)
        draft {:universe [{:instrument-id "perp:BTC"
                           :market-type :perp
                           :coin "BTC"
                           :symbol "BTC-USDC"}
                          {:instrument-id vault-id
                           :market-type :vault
                           :coin vault-id
                           :vault-address vault-address
                           :name "Alpha Yield"}]
               :return-model {:kind :black-litterman
                              :views [{:id "view-1"
                                       :kind :absolute
                                       :instrument-id vault-id
                                       :return 0.04
                                       :confidence 0.8
                                       :horizon :3m}]}}
        editor-state {:selected-kind :absolute
                      :drafts {:absolute {:instrument-id vault-id
                                          :return-text "4"
                                          :confidence :high
                                          :horizon :3m
                                          :notes ""}}}
        panel (bl-panel/black-litterman-views-panel draft nil editor-state)
        panel-text (hiccup/node-text panel)]
    (is (str/includes? panel-text "Alpha Yield"))
    (is (not (str/includes? panel-text vault-id)))))
