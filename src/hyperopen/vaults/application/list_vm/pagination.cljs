(ns hyperopen.vaults.application.list-vm.pagination
  (:require [hyperopen.vaults.application.ui-state :as vault-ui-state]))

(defn paginate-user-rows
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

(defn user-pagination-model
  [user-pagination user-page-size page-size-dropdown-open?]
  {:total-rows (:total-rows user-pagination)
   :page-size user-page-size
   :page-size-options vault-ui-state/vault-user-page-size-options
   :page-size-dropdown-open? page-size-dropdown-open?
   :page (:page user-pagination)
   :page-count (:page-count user-pagination)})
