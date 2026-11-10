(ns hyperopen.views.account-positions-outcomes-module
  (:require [hyperopen.views.account-info.tabs.outcomes :as outcomes-tab]
            [hyperopen.views.account-info.tabs.positions :as positions-tab]))

(defn ^:export positions-tab-renderer
  [{:keys [positions
           webdata2
           positions-sort
           perp-dex-states
           position-tpsl-modal
           position-reduce-popover
           position-margin-modal
           positions-state
           mobile-expanded-card]}]
  (if (some? positions)
    (positions-tab/positions-tab-content {:positions positions
                                          :sort-state positions-sort
                                          :tpsl-modal position-tpsl-modal
                                          :reduce-popover position-reduce-popover
                                          :margin-modal position-margin-modal
                                          :positions-state (assoc positions-state
                                                                  :mobile-expanded-card mobile-expanded-card)})
    (positions-tab/positions-tab-content {:webdata2 webdata2
                                          :sort-state positions-sort
                                          :perp-dex-states perp-dex-states
                                          :tpsl-modal position-tpsl-modal
                                          :reduce-popover position-reduce-popover
                                          :margin-modal position-margin-modal
                                          :positions-state (assoc positions-state
                                                                  :mobile-expanded-card mobile-expanded-card)})))

(defn ^:export outcomes-tab-renderer
  [{:keys [outcomes outcome-user-fees read-only?]}]
  (outcomes-tab/outcomes-tab-content {:outcomes outcomes
                                      :user-fees outcome-user-fees
                                      :read-only? read-only?}))

(goog/exportSymbol "hyperopen.views.account_positions_outcomes_module.positions_tab_renderer" positions-tab-renderer)
(goog/exportSymbol "hyperopen.views.account_positions_outcomes_module.outcomes_tab_renderer" outcomes-tab-renderer)
