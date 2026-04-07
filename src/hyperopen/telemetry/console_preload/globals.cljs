(ns hyperopen.telemetry.console-preload.globals
  (:require [hyperopen.system :as app-system]))

(defn install!
  [global]
  (let [cljs-root (or (aget global "cljs") #js {})
        cljs-core-root (or (aget cljs-root "core") #js {})
        hyperopen-root (or (aget global "hyperopen") #js {})
        hyperopen-system-root (or (aget hyperopen-root "system") #js {})]
    (aset cljs-core-root "PersistentArrayMap" cljs.core/PersistentArrayMap)
    (aset cljs-core-root "PersistentVector" cljs.core/PersistentVector)
    (aset cljs-core-root "assoc_in" cljs.core/assoc-in)
    (aset cljs-core-root "deref" cljs.core/deref)
    (aset cljs-core-root "js__GT_clj" cljs.core/js->clj)
    (aset cljs-core-root "keyword" cljs.core/keyword)
    (aset cljs-core-root "reset_BANG_" cljs.core/reset!)
    (aset cljs-root "core" cljs-core-root)
    (aset global "cljs" cljs-root)
    (aset hyperopen-system-root "store" app-system/store)
    (aset hyperopen-root "system" hyperopen-system-root)
    (aset global "hyperopen" hyperopen-root)))
