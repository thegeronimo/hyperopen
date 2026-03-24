(ns hyperopen.leaderboard.actions
  (:require [clojure.string :as str]))

(def default-timeframe
  :month)

(def default-sort-column
  :pnl)

(def default-sort-direction
  :desc)

(def default-page
  1)

(def ^:private valid-timeframes
  #{:day :week :month :all-time})

(def ^:private valid-sort-columns
  #{:account-value :pnl :roi :volume})

(def ^:private valid-sort-directions
  #{:asc :desc})

(defn- split-path-from-query-fragment
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

(defn normalize-route-path
  [path]
  (-> path
      split-path-from-query-fragment
      str/trim
      trim-trailing-slashes))

(defn parse-leaderboard-route
  [path]
  (let [path* (normalize-route-path path)]
    (if (= "/leaderboard" (str/lower-case path*))
      {:kind :page
       :path path*}
      {:kind :other
       :path path*})))

(defn leaderboard-route?
  [path]
  (= :page (:kind (parse-leaderboard-route path))))

(defn normalize-leaderboard-timeframe
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :alltime :all-time
                     :all :all-time
                     token)]
    (if (contains? valid-timeframes normalized)
      normalized
      default-timeframe)))

(defn normalize-leaderboard-sort-column
  [value]
  (let [token (cond
                (keyword? value) value
                (string? value) (-> value
                                    str/trim
                                    str/lower-case
                                    (str/replace #"[^a-z0-9]+" "-")
                                    keyword)
                :else nil)
        normalized (case token
                     :accountvalue :account-value
                     :account :account-value
                     :vlm :volume
                     token)]
    (if (contains? valid-sort-columns normalized)
      normalized
      default-sort-column)))

(defn normalize-leaderboard-sort-direction
  [value]
  (let [direction (cond
                    (keyword? value) value
                    (string? value) (-> value str/trim str/lower-case keyword)
                    :else nil)]
    (if (contains? valid-sort-directions direction)
      direction
      default-sort-direction)))

(defn- normalize-page
  [page]
  (let [candidate (cond
                    (number? page) page
                    (string? page) (js/Number (str/trim page))
                    :else js/NaN)]
    (if (and (number? candidate)
             (js/isFinite candidate))
      (max 1 (js/Math.floor candidate))
      default-page)))

(defn load-leaderboard
  [_state]
  [[:effects/save [:leaderboard-ui :page] default-page]
   [:effects/api-fetch-leaderboard]])

(defn load-leaderboard-route
  [state path]
  (if (leaderboard-route? path)
    (load-leaderboard state)
    []))

(defn set-leaderboard-query
  [_state query]
  [[:effects/save-many [[[:leaderboard-ui :query] (str (or query ""))]
                        [[:leaderboard-ui :page] default-page]]]])

(defn set-leaderboard-timeframe
  [_state timeframe]
  [[:effects/save-many [[[:leaderboard-ui :timeframe]
                         (normalize-leaderboard-timeframe timeframe)]
                        [[:leaderboard-ui :page] default-page]]]])

(defn set-leaderboard-sort
  [state sort-column]
  (let [column* (normalize-leaderboard-sort-column sort-column)
        current-sort (or (get-in state [:leaderboard-ui :sort])
                         {:column default-sort-column
                          :direction default-sort-direction})
        current-column (normalize-leaderboard-sort-column (:column current-sort))
        current-direction (normalize-leaderboard-sort-direction (:direction current-sort))
        next-direction (if (= column* current-column)
                         (if (= :asc current-direction) :desc :asc)
                         :desc)]
    [[:effects/save-many [[[:leaderboard-ui :sort]
                           {:column column*
                            :direction next-direction}]
                          [[:leaderboard-ui :page] default-page]]]]))

(defn set-leaderboard-page
  [_state page max-page]
  (let [max-page* (max 1 (normalize-page max-page))
        page* (min max-page* (normalize-page page))]
    [[:effects/save [:leaderboard-ui :page] page*]]))

(defn next-leaderboard-page
  [state max-page]
  (let [current-page (normalize-page (get-in state [:leaderboard-ui :page]))
        max-page* (max 1 (normalize-page max-page))]
    (set-leaderboard-page state (inc current-page) max-page*)))

(defn prev-leaderboard-page
  [state max-page]
  (let [current-page (normalize-page (get-in state [:leaderboard-ui :page]))]
    (set-leaderboard-page state (dec current-page) max-page)))
