(ns hyperopen.websocket.diagnostics-sanitize
  (:require [clojure.string :as str]))

(def ^:private address-pattern
  #"(?i)^0x[0-9a-f]{40}$")

(def ^:private sensitive-key-substrings
  ["user"
   "address"
   "wallet"
   "token"
   "sig"
   "signature"
   "authorization"
   "auth"
   "api-key"
   "apikey"
   "secret"
   "cookie"
   "session"])

(defn- normalize-key [k]
  (-> (cond
        (keyword? k) (name k)
        (string? k) k
        (nil? k) ""
        :else (str k))
      str/lower-case))

(defn sensitive-key?
  [k]
  (let [key* (normalize-key k)]
    (boolean
      (some #(str/includes? key* %)
            sensitive-key-substrings))))

(defn address-like?
  [value]
  (boolean
    (and (string? value)
         (re-matches address-pattern value))))

(defn- mask-address [value]
  (if (and (string? value)
           (> (count value) 11))
    (str (subs value 0 6) "..." (subs value (- (count value) 5)))
    "<masked>"))

(defn- masked-token [mode]
  (if (= mode :redact) "<redacted>" "<masked>"))

(defn- sanitize-sensitive-string [mode value]
  (cond
    (= mode :reveal)
    value

    (address-like? value)
    (if (= mode :redact)
      "<redacted>"
      (mask-address value))

    :else
    (masked-token mode)))

(defn sanitize-value
  "Sanitize nested diagnostics values.
   Modes:
   - :reveal => passthrough
   - :mask   => mask sensitive values for UI display
   - :redact => redact sensitive values for clipboard/export."
  [mode value]
  (letfn [(walk [key* value*]
            (if (and (not= mode :reveal)
                     (sensitive-key? key*))
              (cond
                (string? value*)
                (sanitize-sensitive-string mode value*)

                (nil? value*)
                nil

                :else
                (masked-token mode))
              (cond
                (map? value*)
                (reduce-kv (fn [acc k v]
                             (assoc acc k (walk k v)))
                           (empty value*)
                           value*)

                (vector? value*)
                (mapv #(walk key* %) value*)

                (sequential? value*)
                (mapv #(walk key* %) value*)

                (string? value*)
                (if (address-like? value*)
                  (sanitize-sensitive-string mode value*)
                  value*)

                :else
                value*)))]
    (walk nil value)))
