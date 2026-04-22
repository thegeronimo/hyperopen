(ns hyperopen.views.portfolio.vm.summary
  (:require [hyperopen.portfolio.application.summary :as summary]))

(def canonical-summary-key summary/canonical-summary-key)
(def normalize-summary-by-key summary/normalize-summary-by-key)
(def selected-summary-key summary/selected-summary-key)
(def summary-key-candidates summary/summary-key-candidates)
(def all-time-summary-key summary/all-time-summary-key)
(def derived-summary-entry summary/derived-summary-entry)
(def returns-history-context summary/returns-history-context)
(def selected-summary-entry summary/selected-summary-entry)
(def selected-summary-context summary/selected-summary-context)
(def pnl-delta summary/pnl-delta)
(def max-drawdown-ratio summary/max-drawdown-ratio)
