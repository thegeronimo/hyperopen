(ns hyperopen.vaults.application.list-vm.cache
  (:require [hyperopen.vaults.application.list-vm.filtering :as filtering]
            [hyperopen.vaults.application.list-vm.pagination :as pagination]
            [hyperopen.vaults.application.list-vm.rows :as rows]
            [hyperopen.vaults.application.ui-state :as vault-ui-state]))

(defonce ^:private parsed-vault-rows-cache
  (atom nil))

(defonce ^:private vault-list-model-cache
  (atom nil))

(defn reset-cache! []
  (reset! parsed-vault-rows-cache nil)
  (reset! vault-list-model-cache nil))

(defn- day-bucket
  [now-ms]
  (if (number? now-ms)
    (js/Math.floor (/ now-ms rows/day-ms))
    0))

(defn cached-parsed-rows
  [raw-rows wallet-address equity-by-address snapshot-range now-ms]
  (let [bucket (day-bucket now-ms)
        cache @parsed-vault-rows-cache]
    (if (and (map? cache)
             (identical? raw-rows (:rows cache))
             (= wallet-address (:wallet-address cache))
             (identical? equity-by-address (:equity-by-address cache))
             (= snapshot-range (:snapshot-range cache))
             (= bucket (:day-bucket cache)))
      (:parsed-rows cache)
      (let [parsed-rows (->> raw-rows
                             (keep #(rows/parse-vault-row %
                                                           wallet-address
                                                           equity-by-address
                                                           snapshot-range
                                                           now-ms))
                             vec)]
        (reset! parsed-vault-rows-cache {:rows raw-rows
                                         :wallet-address wallet-address
                                         :equity-by-address equity-by-address
                                         :snapshot-range snapshot-range
                                         :day-bucket bucket
                                         :parsed-rows parsed-rows})
        parsed-rows))))

(defn cached-vault-list-model
  [parsed-rows
   {:keys [query
           snapshot-range
           preview-state
           filters
           sort-state
           user-page-size
           requested-user-page
           page-size-dropdown-open?
           loading?
           refreshing?
           error]}]
  (let [sort-column (vault-ui-state/normalize-vault-sort-column (:column sort-state))
        sort-direction (if (= :asc (:direction sort-state)) :asc :desc)
        cache @vault-list-model-cache]
    (if (and (map? cache)
             (identical? parsed-rows (:parsed-rows cache))
             (= query (:query cache))
             (= snapshot-range (:snapshot-range cache))
             (= filters (:filters cache))
             (= sort-column (:sort-column cache))
             (= sort-direction (:sort-direction cache))
             (= user-page-size (:user-page-size cache))
             (= requested-user-page (:requested-user-page cache))
             (= page-size-dropdown-open? (:page-size-dropdown-open? cache))
             (= preview-state (:preview-state cache))
             (= loading? (:loading? cache))
             (= refreshing? (:refreshing? cache))
             (= error (:error cache)))
      (:model cache)
      (let [visible-rows (->> parsed-rows
                              (filter #(filtering/include-vault-row? % {:query query
                                                                        :filters filters}))
                              (#(filtering/sort-rows % {:column sort-column
                                                        :direction sort-direction}))
                              vec)
            grouped (filtering/partition-user-and-protocol-rows visible-rows)
            user-pagination (pagination/paginate-user-rows (:user-rows grouped)
                                                           user-page-size
                                                           requested-user-page)
            model {:query query
                   :snapshot-range snapshot-range
                   :sort {:column sort-column
                          :direction sort-direction}
                   :filters filters
                   :loading? loading?
                   :refreshing? refreshing?
                   :error error
                   :preview-state preview-state
                   :rows visible-rows
                   :protocol-rows (:protocol-rows grouped)
                   :user-rows (:user-rows grouped)
                   :visible-user-rows (:rows user-pagination)
                   :user-pagination (pagination/user-pagination-model user-pagination
                                                                      user-page-size
                                                                      page-size-dropdown-open?)
                   :visible-count (count visible-rows)
                   :total-visible-tvl (reduce (fn [acc {:keys [tvl]}]
                                                (+ acc (or tvl 0)))
                                              0
                                              visible-rows)}]
        (reset! vault-list-model-cache {:parsed-rows parsed-rows
                                        :query query
                                        :snapshot-range snapshot-range
                                        :filters filters
                                        :sort-column sort-column
                                        :sort-direction sort-direction
                                        :user-page-size user-page-size
                                        :requested-user-page requested-user-page
                                        :page-size-dropdown-open? page-size-dropdown-open?
                                        :preview-state preview-state
                                        :loading? loading?
                                        :refreshing? refreshing?
                                        :error error
                                        :model model})
        model))))
