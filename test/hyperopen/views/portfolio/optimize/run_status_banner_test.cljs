(ns hyperopen.views.portfolio.optimize.run-status-banner-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [hyperopen.views.portfolio-view :as portfolio-view]
            [hyperopen.views.portfolio.optimize.run-status-banner :as banner]
            [hyperopen.views.portfolio.optimize.test-support
             :refer [click-actions collect-strings data-role-order index-of
                     node-attr node-by-role]]))

(defn- draft
  [overrides]
  (merge {:universe [{:instrument-id "perp:BTC" :market-type :perp :coin "BTC"}
                     {:instrument-id "perp:ETH" :market-type :perp :coin "ETH"}]
          :objective {:kind :minimum-variance}
          :return-model {:kind :historical-mean}
          :risk-model {:kind :ledoit-wolf-dense}
          :constraints {:long-only? true :max-asset-weight 1.0}
          :metadata {}}
         overrides))

(defn- running-progress
  [overrides]
  (merge {:status :running
          :run-id "run-1"
          :started-at-ms 1000
          :now-ms 20400
          :active-step :risk-model
          :overall-percent 40
          :steps [{:id :fetch-returns :label "fetch returns matrix" :detail "2 assets"
                   :status :succeeded :percent 100}
                  {:id :risk-model :label "Ledoit-Wolf estimator" :detail "ledoit wolf dense"
                   :status :running :percent 30}
                  {:id :return-model :label "historical mean estimator" :status :pending :percent 0}
                  {:id :solve :label "QP solve" :detail "OSQP" :status :pending :percent 0}
                  {:id :frontier :label "frontier sweep" :detail "40 points" :status :pending :percent 0}
                  {:id :diagnostics :label "diagnostics + rebalance preview" :status :pending :percent 0}]
          :error nil}
         overrides))

(deftest banner-running-leads-with-honest-step-based-primary-state-test
  (let [node (banner/run-status-banner {:optimization-progress (running-progress {})
                                        :run-state {:status :running :run-id "run-1"}
                                        :draft (draft {})})
        strings (set (collect-strings node))
        meta-text (str/join " " (collect-strings (node-by-role node "portfolio-optimizer-run-banner-meta")))
        headline-text (str/join " " (collect-strings (node-by-role node "portfolio-optimizer-run-banner-headline")))
        details-text (str/join " " (collect-strings (node-by-role node "portfolio-optimizer-run-banner-details")))]
    (is (some? (node-by-role node "portfolio-optimizer-run-banner")))
    (is (= "running" (node-attr node :data-run-tone)))
    (is (contains? strings "Optimizing portfolio"))
    (is (= "Building risk model" headline-text))
    (is (not (str/includes? headline-text "Ledoit-Wolf estimator")))
    (is (str/includes? details-text "Ledoit-Wolf estimator"))
    (is (str/includes? meta-text "Step 2 of 6"))
    (is (str/includes? meta-text "19.4s elapsed"))
    (is (str/includes? meta-text "2 assets"))
    (is (some? (node-by-role node "portfolio-optimizer-run-banner-stepper")))
    (is (some? (node-by-role node "portfolio-optimizer-run-banner-snapshot")))
    (is (some? (node-by-role node "portfolio-optimizer-run-banner-details")))))

(deftest banner-running-trickles-display-percent-as-secondary-motion-test
  (let [node (banner/run-status-banner {:optimization-progress (running-progress {:overall-percent 25
                                                                                  :display-percent 33})
                                        :run-state {:status :running}
                                        :draft (draft {})})
        strings (set (collect-strings node))]
    (is (contains? strings "33%"))
    (is (not (contains? strings "25%")))))

(deftest banner-running-names-return-views-under-black-litterman-test
  (let [node (banner/run-status-banner
              {:optimization-progress (running-progress {:active-step :return-model})
               :run-state {:status :running}
               :draft (draft {:return-model {:kind :black-litterman}})})
        strings (set (collect-strings node))]
    (is (contains? strings "Applying your return views"))
    (is (not (contains? strings "Building return model")))))

(deftest banner-succeeded-announces-transition-to-results-test
  (let [node (banner/run-status-banner
              {:optimization-progress {:status :succeeded :steps [] :overall-percent 100}
               :run-state {:status :succeeded}
               :draft (draft {})})
        strings (set (collect-strings node))]
    (is (= "succeeded" (node-attr node :data-run-tone)))
    (is (contains? strings "Optimization complete"))
    (is (contains? strings "Opening results…"))))

(deftest banner-hard-failure-surfaces-error-and-retry-test
  (let [node (banner/run-status-banner
              {:optimization-progress {:status :failed :steps [] :error {:message "solver diverged"}}
               :run-state {:status :failed :error {:code :solver-failed :message "solver diverged"}}
               :draft (draft {})})
        strings (set (collect-strings node))
        retry (node-by-role node "portfolio-optimizer-run-banner-retry")]
    (is (= "failed" (node-attr node :data-run-tone)))
    (is (contains? strings "Optimization could not complete"))
    (is (contains? strings "solver diverged"))
    (is (some? retry))
    (is (= [[:actions/run-portfolio-optimizer-from-draft]] (click-actions retry)))))

(deftest banner-defers-infeasible-and-unsupported-to-dedicated-ui-test
  (is (nil? (banner/run-status-banner
             {:optimization-progress {:status :failed :steps []}
              :run-state {:status :infeasible :result {:reason :sum-upper-below-target}}
              :draft (draft {})})))
  (is (nil? (banner/run-status-banner
             {:optimization-progress {:status :failed :steps []}
              :run-state {:status :unsupported}
              :draft (draft {})}))))

(deftest banner-hidden-when-no-live-run-test
  (is (nil? (banner/run-status-banner {:optimization-progress {:status :idle}
                                       :run-state {:status :idle}
                                       :draft (draft {})})))
  (is (nil? (banner/run-status-banner {:optimization-progress {:status :idle}
                                       :run-state {:status :succeeded}
                                       :draft (draft {})}))))

(deftest banner-renders-above-setup-surface-and-outside-the-right-rail-test
  (let [view-node (portfolio-view/portfolio-view
                   {:router {:path "/portfolio/optimize/new"}
                    :portfolio {:optimizer
                                {:draft {:universe [{:instrument-id "perp:BTC"
                                                     :market-type :perp
                                                     :coin "BTC"}]
                                         :objective {:kind :minimum-variance}
                                         :return-model {:kind :historical-mean}
                                         :risk-model {:kind :ledoit-wolf-dense}
                                         :constraints {:long-only? true}}
                                 :optimization-progress (running-progress {})}}})
        order (vec (data-role-order view-node))]
    (is (some? (node-by-role view-node "portfolio-optimizer-run-banner")))
    (is (nil? (node-by-role view-node "portfolio-optimizer-setup-preset-row")))
    (is (< (index-of order "portfolio-optimizer-run-banner")
           (index-of order "portfolio-optimizer-setup-surface")))
    (is (< (index-of order "portfolio-optimizer-run-banner")
           (index-of order "portfolio-optimizer-right-rail")))))
