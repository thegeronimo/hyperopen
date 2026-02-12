(ns hyperopen.core.compat
  (:require-macros [hyperopen.core.macros :refer [def-core-compat-exports]])
  (:require [hyperopen.core.public-actions :as public-actions]
            [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.runtime.effect-adapters :as effect-adapters]))

;; Legacy compatibility surface for `hyperopen.core/*`.
;; Keep public aliases here so `hyperopen.core` can stay focused on bootstrapping.
(def-core-compat-exports)
