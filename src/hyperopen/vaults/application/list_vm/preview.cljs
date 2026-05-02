(ns hyperopen.vaults.application.list-vm.preview
  (:require [hyperopen.vaults.application.list-vm.pagination :as pagination]
            [hyperopen.vaults.application.list-vm.rows :as rows]
            [hyperopen.vaults.application.ui-state :as vault-ui-state]))

(def default-startup-preview-protocol-row-limit
  4)

(def default-startup-preview-user-row-limit
  8)

(defn present-rows
  [rows]
  (when (seq rows)
    rows))

(defn live-rows-source
  [state]
  (or (present-rows (get-in state [:vaults :merged-index-rows]))
      (present-rows (get-in state [:vaults :index-rows]))
      []))

(defn startup-preview-record
  [state snapshot-range]
  (let [preview (get-in state [:vaults :startup-preview])
        preview-snapshot-range (vault-ui-state/normalize-vault-snapshot-range
                                (:snapshot-range preview))]
    (when (and (map? preview)
               (= snapshot-range preview-snapshot-range)
               (or (seq (:protocol-rows preview))
                   (seq (:user-rows preview))))
      preview)))

(defn preview-state
  [live-rows preview]
  (let [startup-preview? (boolean (or (seq (:protocol-rows preview))
                                      (seq (:user-rows preview))))
        source (cond
                 (seq live-rows) :live
                 startup-preview? :startup-preview
                 :else nil)]
    {:has-startup-preview? startup-preview?
     :has-live-rows? (boolean (seq live-rows))
     :baseline-visible? (or (boolean (seq live-rows))
                            startup-preview?)
     :previewing? (= source :startup-preview)
     :source source}))

(defn preview-total-visible-tvl
  [preview]
  (or (rows/optional-number (:total-visible-tvl preview))
      (reduce (fn [acc row]
                (+ acc (or (:tvl row) 0)))
              0
              (concat (or (:protocol-rows preview) [])
                      (or (:user-rows preview) [])))))

(defn preview-vault-list-model
  [preview
   {:keys [query
           snapshot-range
           filters
           sort-state
           user-page-size
           requested-user-page
           page-size-dropdown-open?
           preview-state
           loading?
           refreshing?
           error]}]
  (let [sort-column (vault-ui-state/normalize-vault-sort-column (:column sort-state))
        sort-direction (if (= :asc (:direction sort-state)) :asc :desc)
        protocol-rows (vec (or (:protocol-rows preview) []))
        user-rows (vec (or (:user-rows preview) []))
        visible-rows (vec (concat protocol-rows user-rows))
        user-pagination (pagination/paginate-user-rows user-rows
                                                       user-page-size
                                                       requested-user-page)]
    {:query query
     :snapshot-range snapshot-range
     :sort {:column sort-column
            :direction sort-direction}
     :filters filters
     :loading? loading?
     :refreshing? refreshing?
     :error error
     :preview-state preview-state
     :rows visible-rows
     :protocol-rows protocol-rows
     :user-rows user-rows
     :visible-user-rows (:rows user-pagination)
     :user-pagination (pagination/user-pagination-model user-pagination
                                                        user-page-size
                                                        page-size-dropdown-open?)
     :visible-count (count visible-rows)
     :total-visible-tvl (preview-total-visible-tvl preview)}))
