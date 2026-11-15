(ns hyperopen.account.history.twap-actions)

(def ^:private twap-subtabs
  #{:active :history :fill-history})

(defn normalize-twap-subtab
  [value]
  (let [subtab (cond
                 (keyword? value) value
                 (string? value) (keyword value)
                 :else :active)]
    (if (contains? twap-subtabs subtab)
      subtab
      :active)))

(defn select-account-info-twap-subtab
  [_state subtab]
  [[:effects/save [:account-info :twap :selected-subtab]
    (normalize-twap-subtab subtab)]])
