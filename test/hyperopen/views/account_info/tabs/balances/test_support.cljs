(ns hyperopen.views.account-info.tabs.balances.test-support
  (:require [hyperopen.views.account-info.tabs.balances :as balances-tab]))

(defn render-balances-tab
  ([rows hide-small? sort-state]
   (render-balances-tab rows hide-small? sort-state "" {}))
  ([rows hide-small? sort-state coin-search]
   (render-balances-tab rows hide-small? sort-state coin-search {}))
  ([rows hide-small? sort-state coin-search options]
   (balances-tab/balances-tab-content rows
                                      hide-small?
                                      sort-state
                                      coin-search
                                      options)))
