(ns hyperopen.views.leaderboard.vm
  (:require [clojure.string :as str]
            [hyperopen.account.context :as account-context]
            [hyperopen.leaderboard.actions :as leaderboard-actions]))

(def ^:private desktop-breakpoint-px
  1024)

(def ^:private page-size
  10)

(def timeframe-options
  [{:value :day :label "Day"}
   {:value :week :label "Week"}
   {:value :month :label "Month"}
   {:value :all-time :label "All Time"}])

(defonce ^:private leaderboard-vm-cache
  (atom nil))

(defn reset-leaderboard-vm-cache!
  []
  (reset! leaderboard-vm-cache nil))

(defn- viewport-width-px
  []
  (let [width (some-> js/globalThis .-innerWidth)]
    (if (number? width)
      width
      desktop-breakpoint-px)))

(defn- desktop-layout?
  []
  (>= (viewport-width-px) desktop-breakpoint-px))

(defn- current-user-address
  [state]
  (account-context/effective-account-address state))

(defn- timeframe-label
  [timeframe]
  (case timeframe
    :day "Day"
    :week "Week"
    :month "Month"
    :all-time "All Time"
    "Month"))

(defn- query-token
  [query]
  (some-> query str str/trim str/lower-case))

(defn- matches-query?
  [row query]
  (let [query* (query-token query)
        address (:eth-address row)
        display-name (some-> (:display-name row) str/lower-case)]
    (or (str/blank? query*)
        (str/includes? (or address "") query*)
        (str/includes? (or display-name "") query*))))

(defn- derive-row
  [timeframe current-user-address row]
  (let [window (get-in row [:window-performances timeframe]
                       {:pnl 0
                        :roi 0
                        :volume 0})
        address (:eth-address row)]
    {:eth-address address
     :display-name (:display-name row)
     :account-value (or (:account-value row) 0)
     :pnl (or (:pnl window) 0)
     :roi (or (:roi window) 0)
     :volume (or (:volume window) 0)
     :you? (= current-user-address address)}))

(defn- sort-value
  [row column]
  (case column
    :account-value (:account-value row)
    :pnl (:pnl row)
    :roi (:roi row)
    :volume (:volume row)
    0))

(defn- compare-values
  [left right]
  (cond
    (and (number? left) (number? right))
    (compare left right)

    (and (string? left) (string? right))
    (compare left right)

    (nil? left)
    (if (nil? right) 0 -1)

    (nil? right)
    1

    :else
    (compare (str left) (str right))))

(defn- sort-rows
  [rows sort-state]
  (let [column (leaderboard-actions/normalize-leaderboard-sort-column (:column sort-state))
        direction (leaderboard-actions/normalize-leaderboard-sort-direction (:direction sort-state))]
    (sort (fn [left right]
            (let [value-comparison (compare-values (sort-value left column)
                                                   (sort-value right column))
                  comparison (if (= :asc direction)
                               value-comparison
                               (- value-comparison))]
              (if (zero? comparison)
                (compare (:eth-address left) (:eth-address right))
                comparison)))
          rows)))

(defn- rank-rows
  [rows]
  (map-indexed (fn [idx row]
                 (assoc row :rank (inc idx)))
               rows))

(defn- paginate-rows
  [rows requested-page]
  (let [rows* (vec (or rows []))
        total-rows (count rows*)
        page-count (max 1 (int (js/Math.ceil (/ total-rows page-size))))
        safe-page (-> requested-page
                      js/Math.floor
                      (max 1)
                      (min page-count))
        start-idx (* (dec safe-page) page-size)
        end-idx (min total-rows (+ start-idx page-size))]
    {:rows (subvec rows* start-idx end-idx)
     :page safe-page
     :page-count page-count
     :total-rows total-rows}))

(defn- compute-vm
  [state]
  (let [query (get-in state [:leaderboard-ui :query] "")
        timeframe (leaderboard-actions/normalize-leaderboard-timeframe
                   (get-in state [:leaderboard-ui :timeframe]))
        sort (or (get-in state [:leaderboard-ui :sort])
                 {:column leaderboard-actions/default-sort-column
                  :direction leaderboard-actions/default-sort-direction})
        requested-page (max 1 (or (get-in state [:leaderboard-ui :page]) 1))
        raw-rows (or (get-in state [:leaderboard :rows]) [])
        excluded-addresses (or (get-in state [:leaderboard :excluded-addresses]) #{})
        filtered-rows (->> raw-rows
                           (remove #(contains? excluded-addresses (:eth-address %)))
                           (filter #(matches-query? % query))
                           (map #(derive-row timeframe (current-user-address state) %))
                           (#(sort-rows % sort))
                           rank-rows
                           vec)
        pinned-row (first (filter :you? filtered-rows))
        unpinned-rows (if pinned-row
                        (into [] (remove #(= (:eth-address %) (:eth-address pinned-row)) filtered-rows))
                        filtered-rows)
        pagination (paginate-rows unpinned-rows requested-page)]
    {:query query
     :timeframe timeframe
     :timeframe-label (timeframe-label timeframe)
     :timeframe-options timeframe-options
     :sort sort
     :loading? (true? (get-in state [:leaderboard :loading?]))
     :error (get-in state [:leaderboard :error])
     :loaded-at-ms (get-in state [:leaderboard :loaded-at-ms])
     :desktop-layout? (desktop-layout?)
     :pinned-row pinned-row
     :rows (:rows pagination)
     :page (:page pagination)
     :page-count (:page-count pagination)
     :page-size page-size
     :total-rows (count filtered-rows)
     :visible-rows-count (count (:rows pagination))
     :has-results? (pos? (count filtered-rows))}))

(defn leaderboard-vm
  [state]
  (let [cache-key {:rows (get-in state [:leaderboard :rows])
                   :excluded-addresses (get-in state [:leaderboard :excluded-addresses])
                   :query (get-in state [:leaderboard-ui :query])
                   :timeframe (get-in state [:leaderboard-ui :timeframe])
                   :sort (get-in state [:leaderboard-ui :sort])
                   :page (get-in state [:leaderboard-ui :page])
                   :wallet-address (get-in state [:wallet :address])
                   :loading? (get-in state [:leaderboard :loading?])
                   :error (get-in state [:leaderboard :error])
                   :loaded-at-ms (get-in state [:leaderboard :loaded-at-ms])
                   :viewport-width (viewport-width-px)}
        cached @leaderboard-vm-cache]
    (if (= cache-key (:cache-key cached))
      (:value cached)
      (let [value (compute-vm state)]
        (reset! leaderboard-vm-cache {:cache-key cache-key
                                      :value value})
        value))))
