(ns hyperopen.api.endpoints.leaderboard
  (:require [clojure.string :as str]))

(def default-leaderboard-url
  "https://stats-data.hyperliquid.xyz/Mainnet/leaderboard")

(def ^:private known-window-keys
  #{:day :week :month :all-time})

(defn- non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn- normalize-address
  [value]
  (some-> value non-blank-text str/lower-case))

(defn- parse-optional-num
  [value]
  (let [num (cond
              (number? value) value
              (string? value) (js/Number (str/trim value))
              :else js/NaN)]
    (when (and (number? num)
               (js/isFinite num))
      num)))

(defn- normalize-window-key
  [value]
  (let [token (some-> value
                      non-blank-text
                      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
                      str/lower-case
                      (str/replace #"[^a-z0-9]+" "-")
                      (str/replace #"(^-+)|(-+$)" "")
                      keyword)]
    (if (contains? known-window-keys token)
      token
      nil)))

(defn normalize-window-performance
  [payload]
  (when (map? payload)
    (reduce-kv (fn [acc metric value]
                 (let [metric* (case metric
                                 :pnl :pnl
                                 :roi :roi
                                 :vlm :volume
                                 nil)
                       value* (parse-optional-num value)]
                   (if (and metric* (number? value*))
                     (assoc acc metric* value*)
                     acc)))
               {:pnl 0
                :roi 0
                :volume 0}
               payload)))

(defn normalize-window-performances
  [payload]
  (reduce (fn [acc entry]
            (if (and (sequential? entry)
                     (= 2 (count entry)))
              (let [[window-key value] entry
                    window-key* (normalize-window-key window-key)
                    value* (normalize-window-performance value)]
                (if (and window-key* value*)
                  (assoc acc window-key* value*)
                  acc))
              acc))
          {:day {:pnl 0 :roi 0 :volume 0}
           :week {:pnl 0 :roi 0 :volume 0}
           :month {:pnl 0 :roi 0 :volume 0}
           :all-time {:pnl 0 :roi 0 :volume 0}}
          (if (sequential? payload) payload [])))

(defn normalize-leaderboard-row
  [row]
  (when (map? row)
    (when-let [eth-address (normalize-address (:ethAddress row))]
      {:eth-address eth-address
       :account-value (or (parse-optional-num (:accountValue row)) 0)
       :display-name (non-blank-text (:displayName row))
       :prize (or (parse-optional-num (:prize row)) 0)
       :window-performances (normalize-window-performances (:windowPerformances row))})))

(defn normalize-leaderboard-rows
  [payload]
  (let [rows (cond
               (map? payload) (:leaderboardRows payload)
               (sequential? payload) payload
               :else [])]
    (if (sequential? rows)
      (->> rows
           (keep normalize-leaderboard-row)
           vec)
      [])))

(defn request-leaderboard!
  ([fetch-fn opts]
   (request-leaderboard! fetch-fn default-leaderboard-url opts))
  ([fetch-fn url opts]
   (let [fetch-fn* (or fetch-fn js/fetch)
         init (clj->js (merge {:method "GET"}
                              (:fetch-opts (or opts {}))))]
     (-> (fetch-fn* url init)
         (.then (fn [response]
                  (cond
                    (or (map? response)
                        (sequential? response))
                    (normalize-leaderboard-rows response)

                    (and (some? response)
                         (false? (.-ok response)))
                    (let [status (.-status response)
                          error (js/Error. (str "Leaderboard request failed with HTTP " status))]
                      (aset error "status" status)
                      (js/Promise.reject error))

                    (fn? (some-> response .-json))
                    (-> (.json response)
                        (.then (fn [payload]
                                 (normalize-leaderboard-rows
                                  (js->clj payload :keywordize-keys true)))))

                    :else
                    (js/Promise.resolve []))))))))
