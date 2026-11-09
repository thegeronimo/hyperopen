(ns hyperopen.asset-selector.outcome-actions-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.asset-selector.actions :as actions]))

(defn- path-value
  [effects path]
  (some (fn [effect]
          (when (and (vector? effect)
                     (= :effects/save-many (first effect)))
            (some (fn [[effect-path value]]
                    (when (= path effect-path)
                      value))
                  (second effect))))
        effects))

(defn- action-effect-args
  [effects effect-id]
  (->> effects
       (filter #(= effect-id (first %)))
       (mapv rest)))

(def ^:private question-market
  {:key "question:30"
   :coin "#1610"
   :market-type :outcome
   :outcome-kind :question
   :outcome-sides [{:side-index 0 :coin "#1610"}
                   {:side-index 1 :coin "#1611"}]
   :question-options [{:outcome-id 161
                       :label "Below 61044"
                       :sides [{:side-index 0
                                :side-label "Yes"
                                :coin "#1610"
                                :asset-id 100001610}
                               {:side-index 1
                                :side-label "No"
                                :coin "#1611"
                                :asset-id 100001611}]}
                      {:outcome-id 162
                       :label "61044 to 63535"
                       :sides [{:side-index 0
                                :side-label "Yes"
                                :coin "#1620"
                                :asset-id 100001620}
                               {:side-index 1
                                :side-label "No"
                                :coin "#1621"
                                :asset-id 100001621}]}]
   :outcome-side-aliases {"#1610" {:coin "#1610"
                                   :outcome-id 161
                                   :side-index 0
                                   :sibling-coins ["#1610" "#1611"]}
                          "#1611" {:coin "#1611"
                                   :outcome-id 161
                                   :side-index 1
                                   :sibling-coins ["#1610" "#1611"]}
                          "#1620" {:coin "#1620"
                                   :outcome-id 162
                                   :side-index 0
                                   :sibling-coins ["#1620" "#1621"]}
                          "#1621" {:coin "#1621"
                                   :outcome-id 162
                                   :side-index 1
                                   :sibling-coins ["#1620" "#1621"]}}
   :outcome-subscription-coins ["#1610" "#1611" "#1620" "#1621"]})

(deftest select-question-outcome-side-preserves-clicked-option-coin-test
  (let [market {:key "question:30"
                :coin "#1610"
                :market-type :outcome
                :outcome-kind :question
                :outcome-sides [{:side-index 0 :coin "#1610"}
                                {:side-index 1 :coin "#1611"}]
                :outcome-side-aliases {"#1620" {:coin "#1620"
                                                 :outcome-id 162
                                                 :side-index 0
                                                 :sibling-coins ["#1620" "#1621"]}}
                :outcome-subscription-coins ["#1610" "#1611" "#1620" "#1621"]}
        effects (actions/select-asset
                 {:active-asset nil
                  :router {:path "/trade"}
                  :asset-selector {:market-by-key {"question:30" market}}}
                 "#1620")]
    (is (= "#1620" (:coin (path-value effects [:active-market]))))
    (is (= [[:effects/save [:router :path] "/trade/%231620"]
            [:effects/push-state "/trade?market=%231620"]]
           (subvec effects 2 4)))
    (is (= [[:effects/subscribe-active-asset "#1620"]
            [:effects/subscribe-orderbook "#1620"]
            [:effects/subscribe-trades "#1620"]
            [:effects/sync-active-asset-funding-predictability "#1620"]]
           (subvec effects 4)))))

(deftest select-question-outcome-option-selects-option-coin-for-market-data-test
  (let [effects (actions/select-outcome-option
                 {:active-asset "#1610"
                  :router {:path "/trade"}
                  :order-form {:outcome-side 0
                               :outcome-option-id 161
                               :price "0.10"
                               :size "2"}
                  :order-form-ui {:outcome-option-dropdown-open? true
                                  :outcome-option-query "sp"}
                  :active-market question-market
                  :asset-selector {:market-by-key {"question:30" question-market}}}
                 162)]
    (is (= "#1620" (:coin (path-value effects [:active-market]))))
    (is (= 162 (get (path-value effects [:order-form]) :outcome-option-id)))
    (is (false? (:outcome-option-dropdown-open? (path-value effects [:order-form-ui]))))
    (is (= "" (:outcome-option-query (path-value effects [:order-form-ui]))))
    (is (= [[:effects/save [:router :path] "/trade/%231620"]
            [:effects/push-state "/trade?market=%231620"]]
           (subvec effects 2 4)))
    (is (= [["#1620"]]
           (action-effect-args effects :effects/subscribe-active-asset)))
    (is (= [["#1620"]]
           (action-effect-args effects :effects/subscribe-orderbook)))
    (is (= [["#1620"]]
           (action-effect-args effects :effects/subscribe-trades)))))
