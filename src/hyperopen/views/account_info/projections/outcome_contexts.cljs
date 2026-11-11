(ns hyperopen.views.account-info.projections.outcome-contexts
  "Market contexts by outcome coin alias (`#n` side coins, `+n` token names)
   for the outcome rows' mark fallback.

   A context is found under its entry key and under the coin/name it carries
   (directly, under `:funding` or under `:info`). Resolving that per render
   meant walking every perp, spot and active context on every store write for
   a wallet holding outcome shares; the alias index is built once per
   contexts value instead (kept in a WeakMap, so the perp, spot and active
   sources never evict each other), and a lookup costs one `get` per held
   alias."
  (:require [clojure.string :as str]))

(defn- present-text
  [value]
  (some-> value str str/trim not-empty))

(defn- outcome-alias?
  [alias]
  (or (str/starts-with? alias "#")
      (str/starts-with? alias "+")))

(defn- context-entry-keys
  [entry-key ctx]
  (let [funding (:funding ctx)
        info (:info ctx)]
    (->> [entry-key
          (:coin ctx)
          (:name ctx)
          (:coin funding)
          (:name funding)
          (:name info)
          (:coin info)]
         (keep present-text)
         distinct)))

(defn- add-context-entry
  [index entry-key ctx]
  (reduce (fn [index* alias]
            (if (outcome-alias? alias)
              (assoc index* alias ctx)
              index*))
          index
          (context-entry-keys entry-key ctx)))

(defn outcome-alias-index
  "Outcome alias -> context over one contexts value: a map keyed by coin, or
   a seq of contexts carrying `:coin`. A later entry wins an alias."
  [contexts]
  (cond
    (map? contexts) (reduce-kv add-context-entry {} contexts)
    (sequential? contexts) (reduce #(add-context-entry %1 (:coin %2) %2) {} contexts)
    :else {}))

(defonce ^:private index-cache
  (atom (js/WeakMap.)))

(defn- cached-index
  [contexts]
  (if (or (map? contexts) (sequential? contexts))
    (let [cache @index-cache
          cached (.get cache contexts)]
      (or cached
          (let [index (outcome-alias-index contexts)]
            (.set cache contexts index)
            index)))
    {}))

(defn context-lookup
  "Context by alias for the `wanted` outcome aliases, from spot, perp and
   active contexts (a later source wins an alias)."
  [{:keys [active-contexts asset-contexts spot-asset-ctxs]} wanted]
  (reduce (fn [lookup index]
            (reduce (fn [lookup* alias]
                      (if (contains? index alias)
                        (assoc lookup* alias (get index alias))
                        lookup*))
                    lookup
                    wanted))
          {}
          [(cached-index spot-asset-ctxs)
           (cached-index asset-contexts)
           (cached-index active-contexts)]))

(defn reset-cache!
  "Tests only."
  []
  (reset! index-cache (js/WeakMap.)))
