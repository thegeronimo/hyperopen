(ns hyperopen.vaults.application.list-vm.filtering
  (:require [clojure.string :as str]
            [hyperopen.vaults.application.list-vm.rows :as rows]
            [hyperopen.vaults.application.ui-state :as vault-ui-state]))

(defn- normalize-search-query
  [value]
  (-> (or value "")
      str
      str/trim
      str/lower-case))

(defn- search-match?
  [row query]
  (let [query* (normalize-search-query query)]
    (or (str/blank? query*)
        (str/includes? (or (:search-text row)
                           (str/lower-case (rows/row-search-text row)))
                       query*))))

(defn- include-by-role-filter?
  [row {:keys [leading? deposited? others?]}]
  (or (and leading? (:is-leading? row))
      (and deposited? (:has-deposit? row))
      (and others? (:is-other? row))))

(defn include-vault-row?
  [row {:keys [query filters]}]
  (and (not= :child (:relationship-type row))
       (search-match? row query)
       (or (:show-closed? filters)
           (not (:is-closed? row)))
       (include-by-role-filter? row filters)))

(defn- sort-key
  [row column]
  (case column
    :vault (or (:vault-sort-key row)
               (str/lower-case (or (:name row) "")))
    :leader (or (:leader-sort-key row)
                (str/lower-case (or (:leader row) "")))
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

(defn sort-rows
  [rows {:keys [column direction]}]
  (let [column* (vault-ui-state/normalize-vault-sort-column column)
        direction* (if (= :asc direction) :asc :desc)]
    (sort (fn [left right]
            (compare-rows left right column* direction*))
          rows)))

(defn partition-user-and-protocol-rows
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
