(ns hyperopen.views.vaults.detail-vm.context
  (:require [clojure.string :as str]
            [hyperopen.vaults.detail.performance :as performance-model]
            [hyperopen.vaults.domain.identity :as vault-identity]))

(defn optional-number
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

    :else nil))

(defn optional-int
  [value]
  (when-let [n (optional-number value)]
    (js/Math.floor n)))

(defn non-blank-text
  [value]
  (let [text (some-> value str str/trim)]
    (when (seq text)
      text)))

(defn normalize-percent-value
  [value]
  (when-let [n (optional-number value)]
    (if (<= (js/Math.abs n) 1)
      (* 100 n)
      n)))

(defn row-by-address
  [state vault-address]
  (some (fn [row]
          (when (= vault-address (vault-identity/normalize-vault-address (:vault-address row)))
            row))
        (or (get-in state [:vaults :merged-index-rows]) [])))

(defn viewer-details-by-address
  [state vault-address viewer-address]
  (when-let [viewer-address* (vault-identity/normalize-vault-address viewer-address)]
    (get-in state [:vaults :viewer-details-by-address vault-address viewer-address*])))

(defn viewer-follower-row
  [details viewer-address]
  (let [viewer-address* (vault-identity/normalize-vault-address viewer-address)
        follower-state (:follower-state details)]
    (or (when (and viewer-address*
                   (= viewer-address*
                      (vault-identity/normalize-vault-address (:user follower-state))))
          follower-state)
        (when viewer-address*
          (some (fn [row]
                  (when (= viewer-address*
                           (vault-identity/normalize-vault-address (:user row)))
                    row))
                (or (:followers details) []))))))

(defn detail-metrics-context
  [state details row user-equity viewer-follower]
  (let [tvl (or (optional-number (:tvl details))
                (optional-number (:tvl row)))
        apr (or (optional-number (:apr details))
                (optional-number (:apr row)))
        return-for-range (fn [snapshot-range]
                           (or (performance-model/summary-cumulative-return-percent
                                state
                                (performance-model/portfolio-summary-by-range details snapshot-range))
                               (performance-model/snapshot-value-by-range row snapshot-range tvl)))
        month-return (return-for-range :month)
        your-deposit (or (optional-number (:equity user-equity))
                         (optional-number (:vault-equity viewer-follower))
                         (optional-number (get-in details [:follower-state :vault-equity])))
        all-time-earned (or (optional-number (:all-time-pnl viewer-follower))
                            (optional-number (get-in details [:follower-state :all-time-pnl])))]
    {:tvl tvl
     :apr apr
     :return-for-range return-for-range
     :month-return month-return
     :your-deposit your-deposit
     :all-time-earned all-time-earned}))

(defn resolve-vault-name [details row vault-address]
  (or (non-blank-text (:name details)) (non-blank-text (:name row)) vault-address "Vault"))
