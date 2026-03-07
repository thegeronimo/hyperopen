(ns hyperopen.views.vaults.vm
  (:require [clojure.string :as str]
            [hyperopen.vaults.domain.ui-state :as vault-ui-state]
            [hyperopen.vaults.infrastructure.routes :as vault-routes]))

(def ^:private day-ms
  (* 24 60 60 1000))

(def ^:private protocol-vault-names
  #{"hyperliquidity provider (hlp)"
    "liquidator"})

(defn- optional-number
  [value]
  (cond
    (number? value)
    (when (js/isFinite value)
      value)

    (string? value)
    (let [trimmed (str/trim value)]
      (when (seq trimmed)
        (let [parsed (js/Number trimmed)]
          (when (js/isFinite parsed)
            parsed))))

    :else
    nil))

(defn- normalize-address
  [value]
  (some-> value str str/trim str/lower-case))

(defn vault-route?
  [path]
  (not= :other (:kind (vault-routes/parse-vault-route path))))

(defn vault-detail-route?
  [path]
  (= :detail (:kind (vault-routes/parse-vault-route path))))

(defn selected-vault-address
  [path]
  (:vault-address (vault-routes/parse-vault-route path)))

(defn- snapshot-point-value
  [entry]
  (cond
    (number? entry) entry

    (and (sequential? entry)
         (>= (count entry) 2))
    (optional-number (second entry))

    (map? entry)
    (or (optional-number (:value entry))
        (optional-number (:pnl entry))
        (optional-number (:account-value entry))
        (optional-number (:accountValue entry)))

    :else
    nil))

(defn- normalize-percent-value
  [value]
  (let [n (or (optional-number value) 0)]
    (if (<= (js/Math.abs n) 1)
      (* 100 n)
      n)))

(defn- snapshot-range-keys
  [snapshot-range]
  (case (vault-ui-state/normalize-vault-snapshot-range snapshot-range)
    :day [:day :week :month :all-time]
    :week [:week :month :all-time :day]
    :month [:month :week :all-time :day]
    :three-month [:all-time :month :week :day]
    :six-month [:all-time :month :week :day]
    :one-year [:all-time :month :week :day]
    :two-year [:all-time :month :week :day]
    :all-time [:all-time :month :week :day]
    [:month :week :all-time :day]))

(defn- snapshot-series-for-range
  [snapshot-by-key snapshot-range]
  (or (some (fn [snapshot-key]
              (let [snapshot-values (get snapshot-by-key snapshot-key)]
                (when (sequential? snapshot-values)
                  (let [normalized-values (->> snapshot-values
                                               (keep snapshot-point-value)
                                               (mapv normalize-percent-value))]
                    (when (seq normalized-values)
                      normalized-values)))))
            (snapshot-range-keys snapshot-range))
      []))

(defn- snapshot-last-value
  [values]
  (if (sequential? values)
    (or (some->> values
                 (keep snapshot-point-value)
                 seq
                 last)
        0)
    0))

(defn- normalize-age-days
  [create-time-ms now-ms]
  (if (and (number? create-time-ms)
           (number? now-ms)
           (>= now-ms create-time-ms))
    (js/Math.floor (/ (- now-ms create-time-ms) day-ms))
    0))

(defn- normalize-search-query
  [value]
  (-> (or value "")
      str
      str/trim
      str/lower-case))

(defn- row-search-text
  [{:keys [name leader vault-address]}]
  (str (or name "")
       " "
       (or leader "")
       " "
       (or vault-address "")))

(defn- search-match?
  [row query]
  (let [query* (normalize-search-query query)]
    (or (str/blank? query*)
        (str/includes? (str/lower-case (row-search-text row))
                       query*))))

(defn- parse-vault-row
  [row wallet-address equity-by-address snapshot-range now-ms]
  (let [vault-address (normalize-address (:vault-address row))
        name (or (some-> (:name row) str str/trim)
                 vault-address
                 "Unknown Vault")
        name-token (str/lower-case name)
        leader (normalize-address (:leader row))
        tvl (or (optional-number (:tvl row)) 0)
        apr (normalize-percent-value (:apr row))
        user-equity-row (get equity-by-address vault-address)
        your-deposit (or (optional-number (:equity user-equity-row)) 0)
        snapshot-series (snapshot-series-for-range
                         (or (:snapshot-by-key row) {})
                         snapshot-range)
        snapshot-value (normalize-percent-value (snapshot-last-value snapshot-series))
        is-leading? (and (seq wallet-address)
                         (= wallet-address leader))
        has-deposit? (pos? your-deposit)
        is-other? (and (not is-leading?)
                       (not has-deposit?))
        is-closed? (true? (:is-closed? row))
        create-time-ms (:create-time-ms row)
        relationship-type (get-in row [:relationship :type] :normal)]
    {:name name
     :vault-address vault-address
     :leader leader
     :tvl tvl
     :apr apr
     :your-deposit your-deposit
     :snapshot snapshot-value
     :snapshot-series snapshot-series
     :is-leading? is-leading?
     :has-deposit? has-deposit?
     :is-other? is-other?
     :is-closed? is-closed?
     :is-protocol? (contains? protocol-vault-names name-token)
     :create-time-ms create-time-ms
     :age-days (normalize-age-days create-time-ms now-ms)
     :relationship-type relationship-type}))

(defn- include-by-role-filter?
  [row {:keys [leading? deposited? others?]}]
  (or (and leading? (:is-leading? row))
      (and deposited? (:has-deposit? row))
      (and others? (:is-other? row))))

(defn- include-vault-row?
  [row {:keys [query filters]}]
  (and (not= :child (:relationship-type row))
       (search-match? row query)
       (or (:show-closed? filters)
           (not (:is-closed? row)))
       (include-by-role-filter? row filters)))

(defn- sort-key
  [row column]
  (case column
    :vault (str/lower-case (or (:name row) ""))
    :leader (str/lower-case (or (:leader row) ""))
    :apr (or (:apr row) 0)
    :tvl (or (:tvl row) 0)
    :your-deposit (or (:your-deposit row) 0)
    :age (or (:age-days row) 0)
    :snapshot (or (:snapshot row) 0)
    :tvl))

(defn- compare-rows
  [left right column direction]
  (let [deposit-priority (compare (if (:has-deposit? left) 0 1)
                                  (if (:has-deposit? right) 0 1))]
    (if (not (zero? deposit-priority))
      deposit-priority
      (let [primary (compare (sort-key left column)
                             (sort-key right column))
            primary* (if (= :desc direction) (- primary) primary)]
        (if (zero? primary*)
          (compare (str/lower-case (or (:vault-address left) ""))
                   (str/lower-case (or (:vault-address right) "")))
          primary*)))))

(defn- sort-rows
  [rows {:keys [column direction]}]
  (let [column* (vault-ui-state/normalize-vault-sort-column column)
        direction* (if (= :asc direction) :asc :desc)]
    (sort (fn [left right]
            (compare-rows left right column* direction*))
          rows)))

(defn- partition-user-and-protocol-rows
  [rows]
  (reduce (fn [{:keys [user-rows protocol-rows]} row]
            (if (:is-protocol? row)
              {:user-rows user-rows
               :protocol-rows (conj protocol-rows row)}
              {:user-rows (conj user-rows row)
               :protocol-rows protocol-rows}))
          {:user-rows []
           :protocol-rows []}
          rows))

(defn- paginate-user-rows
  [rows page-size page]
  (let [total-rows (count rows)
        page-count (max 1 (int (js/Math.ceil (/ total-rows page-size))))
        safe-page (vault-ui-state/normalize-vault-user-page page page-count)
        start-idx (* (dec safe-page) page-size)
        end-idx (min total-rows (+ start-idx page-size))
        page-rows (if (< start-idx total-rows)
                    (subvec rows start-idx end-idx)
                    [])]
    {:rows page-rows
     :page safe-page
     :page-count page-count
     :total-rows total-rows}))

(defn- rows-source
  [state]
  (let [rows (or (get-in state [:vaults :merged-index-rows])
                 (get-in state [:vaults :index-rows])
                 [])]
    (if (sequential? rows)
      rows
      [])))

(defn vault-list-vm
  [state]
  (let [wallet-address (normalize-address (get-in state [:wallet :address]))
        query (get-in state [:vaults-ui :search-query] "")
        snapshot-range (vault-ui-state/normalize-vault-snapshot-range
                        (get-in state [:vaults-ui :snapshot-range]))
        user-page-size (vault-ui-state/normalize-vault-user-page-size
                        (get-in state [:vaults-ui :user-vaults-page-size]))
        requested-user-page (vault-ui-state/normalize-vault-user-page
                             (get-in state [:vaults-ui :user-vaults-page]))
        filters {:leading? (true? (get-in state [:vaults-ui :filter-leading?] true))
                 :deposited? (true? (get-in state [:vaults-ui :filter-deposited?] true))
                 :others? (true? (get-in state [:vaults-ui :filter-others?] true))
                 :show-closed? (true? (get-in state [:vaults-ui :filter-closed?] false))}
        sort-state (or (get-in state [:vaults-ui :sort])
                       {:column vault-ui-state/default-vault-sort-column
                        :direction vault-ui-state/default-vault-sort-direction})
        equity-by-address (or (get-in state [:vaults :user-equity-by-address]) {})
        now-ms (.now js/Date)
        parsed-rows (->> (rows-source state)
                         (keep #(parse-vault-row %
                                                wallet-address
                                                equity-by-address
                                                snapshot-range
                                                now-ms)))
        visible-rows (->> parsed-rows
                          (filter #(include-vault-row? % {:query query
                                                          :filters filters}))
                          (#(sort-rows % sort-state))
                          vec)
        grouped (partition-user-and-protocol-rows visible-rows)
        user-pagination (paginate-user-rows (:user-rows grouped)
                                            user-page-size
                                            requested-user-page)
        list-loading? (or (true? (get-in state [:vaults :loading :index?]))
                          (true? (get-in state [:vaults :loading :summaries?])))
        list-error (or (get-in state [:vaults :errors :index])
                       (get-in state [:vaults :errors :summaries]))]
    {:query query
     :snapshot-range snapshot-range
     :sort {:column (vault-ui-state/normalize-vault-sort-column (:column sort-state))
            :direction (if (= :asc (:direction sort-state)) :asc :desc)}
     :filters filters
     :loading? list-loading?
     :error list-error
     :rows visible-rows
     :protocol-rows (:protocol-rows grouped)
     :user-rows (:user-rows grouped)
     :visible-user-rows (:rows user-pagination)
     :user-pagination {:total-rows (:total-rows user-pagination)
                       :page-size user-page-size
                       :page-size-options vault-ui-state/vault-user-page-size-options
                       :page-size-dropdown-open? (true? (get-in state [:vaults-ui :user-vaults-page-size-dropdown-open?]))
                       :page (:page user-pagination)
                       :page-count (:page-count user-pagination)}
     :visible-count (count visible-rows)
     :total-visible-tvl (reduce (fn [acc {:keys [tvl]}]
                                  (+ acc (or tvl 0)))
                                0
                                visible-rows)}))
