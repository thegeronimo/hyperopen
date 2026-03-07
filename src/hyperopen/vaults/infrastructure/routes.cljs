(ns hyperopen.vaults.infrastructure.routes
  (:require [clojure.string :as str]
            [hyperopen.vaults.domain.identity :as identity]))

(defn- split-route-from-query-fragment
  [path]
  (let [path* (if (string? path) path (str (or path "")))]
    (or (first (str/split path* #"[?#]" 2))
        "")))

(defn- trim-trailing-slashes
  [path]
  (loop [path* path]
    (if (and (> (count path*) 1)
             (str/ends-with? path* "/"))
      (recur (subs path* 0 (dec (count path*))))
      path*)))

(defn normalize-vault-route-path
  [path]
  (-> path
      split-route-from-query-fragment
      str/trim
      trim-trailing-slashes))

(defn parse-vault-route
  [path]
  (let [path* (normalize-vault-route-path path)]
    (cond
      (= path* "/vaults")
      {:kind :list
       :path path*}

      :else
      (if-let [[_ raw-address] (re-matches #"^/vaults/([^/]+)$" path*)]
        {:kind :detail
         :path path*
         :raw-vault-address raw-address
         :vault-address (identity/normalize-vault-address raw-address)}
        {:kind :other
         :path path*}))))
