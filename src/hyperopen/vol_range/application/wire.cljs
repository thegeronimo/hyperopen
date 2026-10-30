(ns hyperopen.vol-range.application.wire
  "Pure wire mapping for Vol Range actions (HO-SPEC-014 v2.1 sections 8.3, 10.1).

  The order action is built from the FROZEN canonical legs and their persisted
  CLOIDs, never from UI maps. Field order follows the established signing
  struct (a, b, p, s, r, t, c; type, orders, grouping) because the L1 action
  is msgpacked as-is. Every order is post-only (ALO) and never reduce-only;
  buys come first, then sells, each in rung order.")

(defn- leg-order [l] [(if (= :buy (:side l)) 0 1) (:rung-index l)])

(defn ordered-legs [legs] (vec (sort-by leg-order legs)))

(defn order-action
  "The `order` action for `legs` ({:side :rung-index :price :size :cloid}).
  Nil when any leg lacks a canonical price, size or CLOID (no empty action is
  valid)."
  [asset-id legs]
  (let [legs (ordered-legs legs)]
    (when (and (integer? asset-id) (seq legs)
               (every? #(and (string? (:price %)) (string? (:size %)) (string? (:cloid %))
                             (contains? #{:buy :sell} (:side %)))
                       legs))
      (array-map :type "order"
                 :orders (mapv (fn [{:keys [side price size cloid]}]
                                 (array-map :a asset-id
                                            :b (= :buy side)
                                            :p price
                                            :s size
                                            :r false
                                            :t {:limit {:tif "Alo"}}
                                            :c cloid))
                               legs)
                 :grouping "na"))))

(defn- safe-oid [oid]
  (when (and (string? oid) (re-matches #"\d+" oid))
    (let [n (js/Number oid)]
      (when (js/Number.isSafeInteger n) n))))

(defn cancel-actions
  "Cancel actions for frozen `targets` ({:cloid :oid}): one `cancel` by oid for
  every target whose oid is known, one `cancelByCloid` for the rest. Each
  target appears in exactly one action. Returns [{:action a :targets [...]}]."
  [asset-id targets]
  (let [{by-oid true by-cloid false} (group-by #(some? (safe-oid (:oid %))) targets)]
    (cond-> []
      (seq by-oid)
      (conj {:action (array-map :type "cancel"
                                :cancels (mapv #(array-map :a asset-id :o (safe-oid (:oid %))) by-oid))
             :targets (vec by-oid)})
      (seq by-cloid)
      (conj {:action (array-map :type "cancelByCloid"
                                :cancels (mapv #(array-map :asset asset-id :cloid (:cloid %)) by-cloid))
             :targets (vec by-cloid)}))))

(defn- cyrb53
  "Deterministic 53-bit string hash."
  [s]
  (let [imul (.-imul js/Math)]
    (loop [i 0 h1 (bit-xor 0xdeadbeef 0) h2 (bit-xor 0x41c6ce57 0)]
      (if (< i (count s))
        (let [ch (.charCodeAt s i)]
          (recur (inc i) (imul (bit-xor h1 ch) 2654435761) (imul (bit-xor h2 ch) 1597334677)))
        (let [h1 (bit-xor (imul (bit-xor h1 (unsigned-bit-shift-right h1 16)) 2246822507)
                          (imul (bit-xor h2 (unsigned-bit-shift-right h2 13)) 3266489909))
              h2 (bit-xor (imul (bit-xor h2 (unsigned-bit-shift-right h2 16)) 2246822507)
                          (imul (bit-xor h1 (unsigned-bit-shift-right h1 13)) 3266489909))]
          (+ (* 4294967296 (bit-and 2097151 h2)) (unsigned-bit-shift-right h1 0)))))))

(defn action-digest
  "Local request identity for an action (spec 8.3). Identifies the request in
  the journal; it is NOT a venue idempotency key."
  [action]
  (let [text (binding [*print-length* nil *print-level* nil] (pr-str action))]
    (str "vr-act-" (.toString (cyrb53 text) 16) "-" (count text))))

