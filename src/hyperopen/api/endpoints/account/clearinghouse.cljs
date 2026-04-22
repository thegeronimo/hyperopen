(ns hyperopen.api.endpoints.account.clearinghouse
  (:require [clojure.string :as str]
            [hyperopen.api.request-policy :as request-policy]))

(defn request-spot-clearinghouse-state!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve nil)
    (let [requested-address (some-> address str str/lower-case)
          opts* (request-policy/apply-info-request-policy
                 :spot-clearinghouse-state
                 (merge {:priority :high
                         :dedupe-key [:spot-clearinghouse-state requested-address]}
                        opts))]
      (post-info! {"type" "spotClearinghouseState"
                   "user" address}
                  opts*))))

(defn normalize-user-abstraction-mode
  [abstraction]
  (let [abstraction* (some-> abstraction str str/trim)]
    (case abstraction*
      "unifiedAccount" :unified
      "portfolioMargin" :unified
      ;; `dexAbstraction` is a standard (non-unified) account shape.
      "dexAbstraction" :classic
      "default" :classic
      "disabled" :classic
      :classic)))

(defn request-user-abstraction!
  [post-info! address opts]
  (if-not address
    (js/Promise.resolve nil)
    (let [requested-address (some-> address str str/lower-case)
          opts* (request-policy/apply-info-request-policy
                 :user-abstraction
                 (merge {:priority :high
                         :dedupe-key [:user-abstraction requested-address]}
                        opts))]
      (post-info! {"type" "userAbstraction"
                   "user" address}
                  opts*))))

(defn request-clearinghouse-state!
  [post-info! address dex opts]
  (if-not address
    (js/Promise.resolve nil)
    (let [requested-address (some-> address str str/lower-case)
          requested-dex* (some-> dex str str/trim)
          requested-dex (when (seq requested-dex*)
                          requested-dex*)
          dedupe-dex (some-> requested-dex str/lower-case)
          body (cond-> {"type" "clearinghouseState"
                        "user" address}
                 requested-dex (assoc "dex" requested-dex))
          opts* (request-policy/apply-info-request-policy
                 :clearinghouse-state
                 (merge {:priority :high
                         :dedupe-key [:clearinghouse-state requested-address dedupe-dex]}
                        opts))]
      (post-info! body opts*))))
