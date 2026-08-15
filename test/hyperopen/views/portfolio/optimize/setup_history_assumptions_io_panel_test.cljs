(ns hyperopen.views.portfolio.optimize.setup-history-assumptions-io-panel-test
  (:require [cljs.test :refer-macros [deftest is]]
            [hyperopen.portfolio.optimizer.contracts :as contracts]
            [hyperopen.views.portfolio.optimize.setup-history-assumptions :as assumptions-view]
            [hyperopen.views.portfolio.optimize.setup-history-assumptions-io :as assumptions-io]
            [hyperopen.views.portfolio.optimize.test-support :as support]))

(deftest io-toolbar-renders-scoped-exports-and-import-test
  (let [toolbar (assumptions-io/io-toolbar {:asset-count 2 :universe-count 5})
        workflow (support/node-by-role toolbar
                                       "portfolio-optimizer-history-assumptions-export-workflow")
        universe (support/node-by-role toolbar
                                       "portfolio-optimizer-history-assumptions-export-universe")
        import* (support/node-by-role toolbar
                                      "portfolio-optimizer-history-assumptions-import")]
    (is (= [[:actions/export-portfolio-optimizer-history-assumptions :proxy-workflow]]
           (support/click-actions workflow)))
    (is (nil? (support/node-attr workflow :disabled)))
    (is (= [[:actions/export-portfolio-optimizer-history-assumptions :universe]]
           (support/click-actions universe)))
    (is (nil? (support/node-attr universe :disabled)))
    (is (= [[:actions/import-portfolio-optimizer-history-assumptions]]
           (support/click-actions import*)))))

(deftest io-toolbar-disables-each-export-with-its-empty-scope-test
  (let [toolbar (assumptions-io/io-toolbar {:asset-count 0 :universe-count 3})
        workflow (support/node-by-role toolbar
                                       "portfolio-optimizer-history-assumptions-export-workflow")
        universe (support/node-by-role toolbar
                                       "portfolio-optimizer-history-assumptions-export-universe")]
    (is (= true (support/node-attr workflow :disabled)))
    (is (nil? (support/click-actions workflow)))
    (is (nil? (support/node-attr universe :disabled))))
  (let [toolbar (assumptions-io/io-toolbar {:asset-count 0 :universe-count 0})
        universe (support/node-by-role toolbar
                                       "portfolio-optimizer-history-assumptions-export-universe")]
    (is (= true (support/node-attr universe :disabled)))
    (is (nil? (support/click-actions universe)))))

(deftest io-note-renders-message-kind-and-dismiss-test
  (let [note (assumptions-io/io-note {:kind :error :message "Import failed."})]
    (is (= "error" (support/node-attr note :data-kind)))
    (is (some #{"Import failed."} (support/collect-strings note)))
    (is (= [[:actions/dismiss-portfolio-optimizer-history-assumptions-io-note]]
           (support/click-actions
            (support/node-by-role note
                                  "portfolio-optimizer-history-assumptions-io-note-dismiss"))))))

(deftest io-note-absent-without-message-test
  (is (nil? (assumptions-io/io-note nil)))
  (is (nil? (assumptions-io/io-note {:kind :success :message ""}))))

(def ^:private section-state
  {:portfolio {:optimizer
               {:draft {:universe [{:instrument-id "perp:WLFI" :coin "WLFI"
                                    :market-type :perp}]
                        :objective {:kind :minimum-variance}
                        :history-assumptions
                        {"perp:WLFI" {:behavior :proxy
                                      :expected-return 0.0
                                      :volatility 0.8
                                      :max-weight 0.05
                                      :proxy {:instrument-ids ["perp:BTC"]
                                              :relationship-strength :medium
                                              :prior-weights nil}
                                      :metadata {:source :agent-import
                                                 :acknowledged? true
                                                 :rationale "Broad market anchor."}}}}}}
   :portfolio-ui {:optimizer {:history-assumptions-io-note
                              {:kind :success :message "Configured 1 asset"}}}})

(deftest section-renders-toolbar-note-and-rationale-test
  (let [draft (get-in section-state contracts/draft-path)
        section (assumptions-view/history-assumptions-section
                 {:state section-state :draft draft
                  :readiness nil :history-load-state nil})]
    (is (some? (support/node-by-role section
                                     "portfolio-optimizer-history-assumptions-io")))
    (is (some? (support/node-by-role section
                                     "portfolio-optimizer-history-assumptions-io-note")))
    (let [rationale (support/node-by-role
                     section
                     "portfolio-optimizer-history-assumption-rationale-perp:WLFI")]
      (is (some? rationale))
      (is (some #{"Agent rationale: Broad market anchor."}
                (support/collect-strings rationale))))))
