(ns hyperopen.views.vaults.detail.format
  (:require [clojure.string :as str]
            [hyperopen.utils.formatting :as fmt]))

(defn finite-number?
  [value]
  (boolean (fmt/finite-number? value)))

(defn- fallback-currency
  [value]
  (let [n (if (== value -0) 0 value)]
    (str "$" (.toFixed n 2))))

(defn format-currency
  ([value]
   (format-currency value {:missing "—"}))
  ([value {:keys [missing]
           :or {missing "—"}}]
   (if (finite-number? value)
     (or (fmt/format-currency value)
         (fallback-currency value))
     missing)))

(defn format-balance-quantity
  ([value]
   (format-balance-quantity value {:missing "—"}))
  ([value {:keys [missing]
           :or {missing "—"}}]
   (if (finite-number? value)
     (let [fixed (.toFixed value 8)
           trimmed (or (some-> fixed
                               (str/replace #"(\.\d*?[1-9])0+$" "$1")
                               (str/replace #"\.0+$" "")
                               str/trim)
                       "0")]
       trimmed)
     missing)))

(defn format-price
  [value]
  (if (finite-number? value)
    (fmt/format-trade-price-plain value)
    "—"))

(defn format-size
  [value]
  (if (finite-number? value)
    (.toFixed value 4)
    "—"))

(defn format-percent
  ([value]
   (format-percent value {:missing "—"}))
  ([value {:keys [missing
                  signed?
                  decimals]
           :or {missing "—"
                signed? true
                decimals 2}}]
   (or (fmt/format-signed-percent value {:signed? signed?
                                         :decimals decimals})
       missing)))

(defn format-funding-rate
  [value]
  (or (fmt/format-signed-percent-from-decimal value {:signed? false
                                                     :decimals 4})
      "—"))

(defn format-time
  [time-ms]
  (or (fmt/format-local-date-time time-ms)
      "—"))

(defn short-hash
  [value]
  (if (and (string? value)
           (> (count value) 12))
    (str (subs value 0 8) "..." (subs value (- (count value) 6)))
    (or value "—")))

(defn normalized-text
  [value]
  (some-> value str str/trim str/lower-case))

(defn resolved-vault-name
  [name-value vault-address]
  (let [name* (some-> name-value str str/trim)]
    (when (and (seq name*)
               (not= (normalized-text name*)
                     (normalized-text vault-address)))
      name*)))

(defn loading-skeleton-block
  [extra-classes]
  [:span {:aria-hidden true
          :class (into ["block"
                        "rounded"
                        "bg-[#1a363b]/80"
                        "animate-pulse"]
                       extra-classes)}])
