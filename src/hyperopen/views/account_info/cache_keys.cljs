(ns hyperopen.views.account-info.cache-keys)

(def ^:private empty-rows-signature
  {:count 0
   :rolling-hash 1
   :xor-hash 0})

(defn- mix-rolling-hash
  [rolling row-hash]
  (let [rolling* (bit-or rolling 0)
        row-hash* (bit-or row-hash 0)]
    (bit-or
     (+ (bit-xor rolling* row-hash*)
        0x9e3779b9
        (bit-shift-left rolling* 6)
        (unsigned-bit-shift-right rolling* 2))
     0)))

(defn rows-signature
  [rows]
  (reduce (fn [{:keys [count rolling-hash xor-hash]} row]
            (let [row-hash (hash row)]
              {:count (inc count)
               :rolling-hash (mix-rolling-hash rolling-hash row-hash)
               :xor-hash (bit-xor (bit-or xor-hash 0) (bit-or row-hash 0))}))
          empty-rows-signature
          (or rows [])))

(defn value-signature
  [value]
  {:hash (hash value)
   :count (when (counted? value)
            (count value))})

(defn- match-state
  [value cached-value cached-signature signature-fn]
  (if (identical? value cached-value)
    {:same-input? true
     :signature (or cached-signature
                    (signature-fn value))}
    (let [signature (signature-fn value)]
      {:same-input? (and (map? cached-signature)
                         (= signature cached-signature))
       :signature signature})))

(defn rows-match-state
  [rows cached-rows cached-rows-signature]
  (match-state rows cached-rows cached-rows-signature rows-signature))

(defn value-match-state
  [value cached-value cached-value-signature]
  (match-state value cached-value cached-value-signature value-signature))
