(ns hyperopen.schema.contracts.action-args-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.schema.contracts :as contracts]))

(deftest assert-action-args-validates-hyperunit-lifecycle-actions-test
  (is (= [{:direction :deposit
           :asset-key :btc
           :operation-id "op_123"}]
         (contracts/assert-action-args!
          :actions/set-hyperunit-lifecycle
          [{:direction :deposit
            :asset-key :btc
            :operation-id "op_123"}]
          {:phase :test})))
  (is (= []
         (contracts/assert-action-args!
          :actions/clear-hyperunit-lifecycle
          []
          {:phase :test})))
  (is (= ["temporary issue"]
         (contracts/assert-action-args!
          :actions/set-hyperunit-lifecycle-error
          ["temporary issue"]
          {:phase :test})))
  (is (= [nil]
         (contracts/assert-action-args!
          :actions/set-hyperunit-lifecycle-error
          [nil]
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"action payload"
       (contracts/assert-action-args!
        :actions/set-hyperunit-lifecycle
        [{:direction :deposit
          :unsupported true}]
        {:phase :test}))))

(deftest assert-action-args-allows-asset-selector-scroll-prefetch-single-or-double-payload-test
  (is (= [5100]
         (contracts/assert-action-args!
          :actions/maybe-increase-asset-selector-render-limit
          [5100]
          {:phase :test})))
  (is (= [5100 1234.5]
         (contracts/assert-action-args!
          :actions/maybe-increase-asset-selector-render-limit
          [5100 1234.5]
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"action payload"
       (contracts/assert-action-args!
        :actions/maybe-increase-asset-selector-render-limit
        [5100 1234.5 9999]
        {:phase :test}))))

(deftest assert-action-args-validates-portfolio-chart-tab-selection-test
  (is (= [:pnl]
         (contracts/assert-action-args!
          :actions/select-portfolio-chart-tab
          [:pnl]
          {:phase :test})))
  (is (= ["accountValue"]
         (contracts/assert-action-args!
          :actions/select-portfolio-chart-tab
          ["accountValue"]
          {:phase :test})))
  (is (thrown-with-msg?
       js/Error
       #"action payload"
       (contracts/assert-action-args!
        :actions/select-portfolio-chart-tab
        [[]]
        {:phase :test}))))

(deftest assert-action-args-validates-portfolio-returns-benchmark-actions-test
  (is (= []
         (contracts/assert-action-args!
          :actions/select-portfolio-returns-benchmark
          []
          {:phase :test})))
  (is (= ["SPY"]
         (contracts/assert-action-args!
          :actions/select-portfolio-returns-benchmark
          ["SPY"]
          {:phase :test})))
  (is (= [""]
         (contracts/assert-action-args!
          :actions/select-portfolio-returns-benchmark
          [""]
          {:phase :test})))
  (is (= []
         (contracts/assert-action-args!
          :actions/clear-portfolio-returns-benchmark
          []
          {:phase :test}))))

(deftest assert-action-args-accepts-order-submission-confirmation-actions-test
  (is (= []
         (contracts/assert-action-args!
          :actions/dismiss-order-submission-confirmation
          []
          {:phase :test})))
  (is (= ["Escape"]
         (contracts/assert-action-args!
          :actions/handle-order-submission-confirmation-keydown
          ["Escape"]
          {:phase :test})))
  (is (= []
         (contracts/assert-action-args!
          :actions/confirm-order-submission
          []
          {:phase :test}))))

(deftest assert-action-args-allows-spectate-mode-actions-with-or-without-address-test
  (is (= []
         (contracts/assert-action-args!
          :actions/open-spectate-mode-modal
          []
          {:phase :test})))
  (is (= [{:left 32 :right 96 :top 18 :bottom 52}]
         (contracts/assert-action-args!
          :actions/open-spectate-mode-modal
          [{:left 32 :right 96 :top 18 :bottom 52}]
          {:phase :test})))
  (is (= [""]
         (contracts/assert-action-args!
          :actions/set-spectate-mode-search
          [""]
          {:phase :test})))
  (is (= [""]
         (contracts/assert-action-args!
          :actions/set-spectate-mode-label
          [""]
          {:phase :test})))
  (is (= []
         (contracts/assert-action-args!
          :actions/start-spectate-mode
          []
          {:phase :test})))
  (is (= ["0x123"]
         (contracts/assert-action-args!
          :actions/start-spectate-mode
          ["0x123"]
          {:phase :test})))
  (is (= []
         (contracts/assert-action-args!
          :actions/add-spectate-mode-watchlist-address
          []
          {:phase :test})))
  (is (= ["0x123"]
         (contracts/assert-action-args!
          :actions/add-spectate-mode-watchlist-address
          ["0x123"]
          {:phase :test})))
  (is (= ["0x123"]
         (contracts/assert-action-args!
          :actions/remove-spectate-mode-watchlist-address
          ["0x123"]
          {:phase :test})))
  (is (= ["0x123"]
         (contracts/assert-action-args!
          :actions/edit-spectate-mode-watchlist-address
          ["0x123"]
          {:phase :test})))
  (is (= []
         (contracts/assert-action-args!
          :actions/clear-spectate-mode-watchlist-edit
          []
          {:phase :test})))
  (is (= ["0x123"]
         (contracts/assert-action-args!
          :actions/copy-spectate-mode-watchlist-address
          ["0x123"]
          {:phase :test})))
  (is (= ["0x123"]
         (contracts/assert-action-args!
          :actions/copy-spectate-mode-watchlist-link
          ["0x123"]
          {:phase :test})))
  (is (= ["0x123"]
         (contracts/assert-action-args!
          :actions/start-spectate-mode-watchlist-address
          ["0x123"]
          {:phase :test}))))
