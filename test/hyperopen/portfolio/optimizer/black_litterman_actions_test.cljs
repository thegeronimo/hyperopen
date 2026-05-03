(ns hyperopen.portfolio.optimizer.black-litterman-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.black-litterman-actions :as actions]))

(def ^:private action-vars
  {'set-portfolio-optimizer-black-litterman-editor-type
   (resolve 'hyperopen.portfolio.optimizer.black-litterman-actions/set-portfolio-optimizer-black-litterman-editor-type)
   'set-portfolio-optimizer-black-litterman-editor-field
   (resolve 'hyperopen.portfolio.optimizer.black-litterman-actions/set-portfolio-optimizer-black-litterman-editor-field)
   'save-portfolio-optimizer-black-litterman-editor-view
   (resolve 'hyperopen.portfolio.optimizer.black-litterman-actions/save-portfolio-optimizer-black-litterman-editor-view)
   'edit-portfolio-optimizer-black-litterman-view
   (resolve 'hyperopen.portfolio.optimizer.black-litterman-actions/edit-portfolio-optimizer-black-litterman-view)
   'cancel-portfolio-optimizer-black-litterman-edit
   (resolve 'hyperopen.portfolio.optimizer.black-litterman-actions/cancel-portfolio-optimizer-black-litterman-edit)
   'request-clear-portfolio-optimizer-black-litterman-views
   (resolve 'hyperopen.portfolio.optimizer.black-litterman-actions/request-clear-portfolio-optimizer-black-litterman-views)
   'cancel-clear-portfolio-optimizer-black-litterman-views
   (resolve 'hyperopen.portfolio.optimizer.black-litterman-actions/cancel-clear-portfolio-optimizer-black-litterman-views)
   'confirm-clear-portfolio-optimizer-black-litterman-views
   (resolve 'hyperopen.portfolio.optimizer.black-litterman-actions/confirm-clear-portfolio-optimizer-black-litterman-views)})

(def ^:private views-path
  [:portfolio :optimizer :draft :return-model :views])

(def ^:private dirty-path
  [:portfolio :optimizer :draft :metadata :dirty?])

(def ^:private selected-kind-path
  [:portfolio-ui :optimizer :black-litterman-editor :selected-kind])

(def ^:private editing-view-id-path
  [:portfolio-ui :optimizer :black-litterman-editor :editing-view-id])

(def ^:private clear-confirmation-path
  [:portfolio-ui :optimizer :black-litterman-editor :clear-confirmation-open?])

(def ^:private errors-path
  [:portfolio-ui :optimizer :black-litterman-editor :errors])

(defn- resolve-action
  [sym]
  (get action-vars sym))

(defn- near?
  [expected actual]
  (< (js/Math.abs (- expected actual)) 0.0000001))

(defn- deep-merge
  [& maps]
  (apply merge-with
         (fn [left right]
           (if (and (map? left)
                    (map? right))
             (deep-merge left right)
             right))
         maps))

(defn- effect-values-by-path
  [effects]
  (reduce (fn [acc effect]
            (case (first effect)
              :effects/save
              (assoc acc (second effect) (nth effect 2))

              :effects/save-many
              (reduce (fn [acc [path value]]
                        (assoc acc path value))
                      acc
                      (second effect))

              acc))
          {}
          (or effects [])))

(defn- editor-draft-path
  [kind field]
  [:portfolio-ui :optimizer :black-litterman-editor :drafts kind field])

(defn- sample-view
  [id overrides]
  (merge {:id id
          :kind :absolute
          :instrument-id "perp:BTC"
          :return 0.1
          :confidence 0.5
          :horizon :1y
          :notes ""}
         overrides))

(defn- base-state
  [& overrides]
  (apply deep-merge
         {:portfolio
          {:optimizer
           {:draft
            {:return-model
             {:kind :black-litterman
              :views []}
             :universe [{:instrument-id "perp:BTC"
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
             :metadata {:dirty? false}}}}
          :portfolio-ui
          {:optimizer
           {:black-litterman-editor
            {:selected-kind :absolute
             :drafts {:absolute {:instrument-id "perp:HYPE"
                                 :return-text "45%"
                                 :confidence :high
                                 :horizon :1y
                                 :notes "Momentum conviction"}
                      :relative {:instrument-id "perp:ETH"
                                 :comparator-instrument-id "perp:SOL"
                                 :direction :outperform
                                 :return-text "5"
                                 :confidence :medium
                                 :horizon :6m
                                 :notes "Pair view"}}}}}}
         overrides))

(deftest black-litterman-editor-actions-save-selected-kind-and-draft-fields-test
  (let [set-type (resolve-action 'set-portfolio-optimizer-black-litterman-editor-type)
        set-field (resolve-action 'set-portfolio-optimizer-black-litterman-editor-field)
        type-values (effect-values-by-path
                     (when set-type
                       (set-type (base-state) :relative)))
        absolute-field-values (effect-values-by-path
                               (when set-field
                                 (set-field (base-state)
                                            :return-text
                                            "47.5%")))
        relative-field-values (effect-values-by-path
                               (when set-field
                                 (set-field (base-state
                                             {:portfolio-ui
                                              {:optimizer
                                               {:black-litterman-editor
                                                {:selected-kind :relative}}}})
                                            :comparator-instrument-id
                                            "perp:BTC")))]
    (is (some? set-type))
    (is (some? set-field))
    (is (= :relative
           (get type-values selected-kind-path)))
    (is (= "47.5%"
           (get absolute-field-values
                (editor-draft-path :absolute :return-text))))
    (is (= "perp:BTC"
           (get relative-field-values
                (editor-draft-path :relative :comparator-instrument-id))))))

(deftest black-litterman-editor-save-normalizes-absolute-and-relative-views-test
  (let [save-view (resolve-action 'save-portfolio-optimizer-black-litterman-editor-view)
        absolute-values (effect-values-by-path
                         (when save-view
                           (save-view (base-state))))
        absolute-view (first (get absolute-values views-path))
        relative-state (base-state
                        {:portfolio-ui
                         {:optimizer
                          {:black-litterman-editor
                           {:selected-kind :relative}}}})
        relative-values (effect-values-by-path
                         (when save-view
                           (save-view relative-state)))
        relative-view (first (get relative-values views-path))
        underperform-values (effect-values-by-path
                             (when save-view
                               (save-view
                                (base-state
                                 {:portfolio-ui
                                  {:optimizer
                                   {:black-litterman-editor
                                    {:selected-kind :relative
                                     :drafts
                                     {:relative {:instrument-id "perp:ETH"
                                                 :comparator-instrument-id "perp:SOL"
                                                 :direction :underperform
                                                 :return-text "3"
                                                 :confidence :low
                                                 :horizon :3m
                                                 :notes "Relative fade"}}}}}}))))
        underperform-view (first (get underperform-values views-path))]
    (is (some? save-view))
    (is (= :absolute (:kind absolute-view)))
    (is (= "perp:HYPE" (:instrument-id absolute-view)))
    (is (near? 0.45 (:return absolute-view)))
    (is (= 0.75 (:confidence absolute-view)))
    (is (= :1y (:horizon absolute-view)))
    (is (= "Momentum conviction" (:notes absolute-view)))
    (is (= true (get absolute-values dirty-path)))
    (is (= :relative (:kind relative-view)))
    (is (= "perp:ETH" (:instrument-id relative-view)))
    (is (= "perp:SOL" (:comparator-instrument-id relative-view)))
    (is (= :outperform (:direction relative-view)))
    (is (near? 0.05 (:return relative-view)))
    (is (= 0.5 (:confidence relative-view)))
    (is (= :6m (:horizon relative-view)))
    (is (= :relative (:kind underperform-view)))
    (is (= :underperform (:direction underperform-view)))
    (is (near? 0.03 (:return underperform-view)))
    (is (= 0.25 (:confidence underperform-view)))
    (is (= :3m (:horizon underperform-view)))
    (is (= true (get underperform-values dirty-path)))))

(deftest black-litterman-editor-save-uses-automatic-absolute-return-input-when-untouched-test
  (let [save-view (resolve-action 'save-portfolio-optimizer-black-litterman-editor-view)
        state (base-state
               {:portfolio
                {:optimizer
                 {:draft
                  {:universe [{:instrument-id "perp:BTC"
                               :market-type :perp
                               :coin "BTC"}]
                   :return-model {:kind :black-litterman
                                  :views []}
                   :risk-model {:kind :sample-covariance}}
                  :history-data
                  {:candle-history-by-coin
                   {"BTC" [{:time 1000 :close "100"}
                           {:time 2000 :close "101"}
                           {:time 3000 :close "103.02"}
                           {:time 4000 :close "106.1106"}]}
                   :funding-history-by-coin {}}
                  :market-cap-by-coin {"BTC" 1}
                  :runtime {:as-of-ms 5000}}}
                :portfolio-ui
                {:optimizer
                 {:black-litterman-editor
                  {:selected-kind :absolute
                   :drafts {:absolute {:instrument-id nil
                                       :return-text ""
                                       :return-text-touched? false
                                       :confidence :medium
                                       :horizon :3m
                                       :notes ""}}}}}})
        values (effect-values-by-path
                (when save-view
                  (save-view state)))
        [absolute-view] (get values views-path)]
    (is (some? save-view))
    (is (= :absolute (:kind absolute-view)))
    (is (= "perp:BTC" (:instrument-id absolute-view)))
    (is (near? 0.0365 (:return absolute-view)))))

(deftest black-litterman-editor-save-rejects-invalid-relative-drafts-and-max-ten-views-test
  (let [save-view (resolve-action 'save-portfolio-optimizer-black-litterman-editor-view)
        same-comparator-values (effect-values-by-path
                                (when save-view
                                  (save-view
                                   (base-state
                                    {:portfolio-ui
                                     {:optimizer
                                      {:black-litterman-editor
                                       {:selected-kind :relative
                                        :drafts
                                        {:relative {:instrument-id "perp:ETH"
                                                    :comparator-instrument-id "perp:ETH"
                                                    :direction :outperform
                                                    :return-text "5"
                                                    :confidence :medium
                                                    :horizon :1y
                                                    :notes ""}}}}}}))))
        negative-spread-values (effect-values-by-path
                                (when save-view
                                  (save-view
                                   (base-state
                                    {:portfolio-ui
                                     {:optimizer
                                      {:black-litterman-editor
                                       {:selected-kind :relative
                                        :drafts
                                        {:relative {:instrument-id "perp:ETH"
                                                    :comparator-instrument-id "perp:SOL"
                                                    :direction :underperform
                                                    :return-text "-2"
                                                    :confidence :medium
                                                    :horizon :1y
                                                    :notes ""}}}}}}))))
        ten-active-views (mapv (fn [idx]
                                 (sample-view
                                  (str "view-" idx)
                                  {:instrument-id "perp:BTC"
                                   :return (+ 0.01 idx)}))
                               (range 10))
        full-book-effects (when save-view
                            (save-view
                             (base-state
                              {:portfolio
                               {:optimizer
                                {:draft
                                 {:return-model
                                  {:views ten-active-views}}}}})))
        full-book-values (effect-values-by-path full-book-effects)]
    (is (some? save-view))
    (is (= "Choose a different comparator asset."
           (get same-comparator-values
                (conj errors-path :comparator-instrument-id))))
    (is (not (contains? same-comparator-values views-path)))
    (is (= "Spread must be positive. Use direction to express underperformance."
           (get negative-spread-values
                (conj errors-path :return-text))))
    (is (not (contains? negative-spread-values views-path)))
    (is (not (contains? full-book-values views-path)))
    (is (not (contains? full-book-values dirty-path)))))

(deftest black-litterman-editor-save-preserves-view-id-while-editing-test
  (let [save-view (resolve-action 'save-portfolio-optimizer-black-litterman-editor-view)
        effects (when save-view
                  (save-view
                   (base-state
                    {:portfolio
                     {:optimizer
                      {:draft
                       {:return-model
                        {:views [(sample-view "view-1" {:notes "Original note"})]}}}}
                     :portfolio-ui
                     {:optimizer
                      {:black-litterman-editor
                       {:editing-view-id "view-1"
                        :selected-kind :absolute
                        :drafts
                        {:absolute {:instrument-id "perp:BTC"
                                    :return-text "12"
                                    :confidence :medium
                                    :horizon :1y
                                    :notes "Updated note"}}}}}})))
        saved-values (effect-values-by-path effects)
        [edited-view] (get saved-values views-path)]
    (is (some? save-view))
    (is (= ["view-1"] (mapv :id (get saved-values views-path))))
    (is (near? 0.12 (:return edited-view)))
    (is (= "Updated note" (:notes edited-view)))
    (is (= true (get saved-values dirty-path)))))

(deftest black-litterman-editor-remove-and-clear-actions-update-active-views-test
  (let [request-clear (resolve-action 'request-clear-portfolio-optimizer-black-litterman-views)
        cancel-clear (resolve-action 'cancel-clear-portfolio-optimizer-black-litterman-views)
        confirm-clear (resolve-action 'confirm-clear-portfolio-optimizer-black-litterman-views)
        existing-views [(sample-view "view-1" {})
                        (sample-view "view-2" {:instrument-id "perp:ETH"
                                               :return 0.08})]
        state (base-state
               {:portfolio
                {:optimizer
                 {:draft
                  {:return-model
                   {:views existing-views}}}}})
        remove-values (effect-values-by-path
                       (actions/remove-portfolio-optimizer-black-litterman-view
                        state
                        "view-1"))
        request-values (effect-values-by-path
                        (when request-clear
                          (request-clear state)))
        cancel-values (effect-values-by-path
                       (when cancel-clear
                         (cancel-clear
                          (base-state
                           {:portfolio-ui
                            {:optimizer
                             {:black-litterman-editor
                              {:clear-confirmation-open? true}}}}))))
        confirm-values (effect-values-by-path
                        (when confirm-clear
                          (confirm-clear
                           (base-state
                            {:portfolio
                             {:optimizer
                              {:draft
                               {:return-model
                                {:views existing-views}}}}
                            :portfolio-ui
                            {:optimizer
                             {:black-litterman-editor
                              {:editing-view-id "view-2"
                               :clear-confirmation-open? true}}}}))))]
    (is (some? request-clear))
    (is (some? cancel-clear))
    (is (some? confirm-clear))
    (is (= ["view-2"] (mapv :id (get remove-values views-path))))
    (is (= true (get remove-values dirty-path)))
    (is (= true (get request-values clear-confirmation-path)))
    (is (= false (get cancel-values clear-confirmation-path)))
    (is (= [] (get confirm-values views-path)))
    (is (= false (get confirm-values clear-confirmation-path)))
    (is (= nil (get confirm-values editing-view-id-path)))
    (is (= true (get confirm-values dirty-path)))))
