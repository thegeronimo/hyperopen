(ns hyperopen.core.compat
  (:require-macros [hyperopen.core.macros :refer [def-core-compat-exports]])
  (:require [hyperopen.core.public-actions :as public-actions]
            [hyperopen.runtime.action-adapters :as action-adapters]
            [hyperopen.runtime.effect-adapters :as effect-adapters]))

;; Legacy compatibility surface that used to be available via `hyperopen.core/*`.
;; Keep public aliases here so `hyperopen.core` stays focused on bootstrapping.
(def-core-compat-exports)
