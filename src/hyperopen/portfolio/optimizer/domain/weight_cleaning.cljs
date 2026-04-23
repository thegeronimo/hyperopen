(ns hyperopen.portfolio.optimizer.domain.weight-cleaning)

(def default-dust-threshold
  0.0005)

(defn- abs-num
  [value]
  (js/Math.abs value))

(defn- scale-to-sum
  [target-sum weights]
  (let [total (reduce + 0 weights)]
    (if (or (zero? total)
            (nil? target-sum))
      weights
      (mapv #(* % (/ target-sum total)) weights))))

(defn- normalization-mode
  [{:keys [long-only? normalization]}]
  (or normalization
      (if (false? long-only?)
        :none
        :sum-to-one)))

(defn- clean-normalize
  [weights {:keys [target-net original-net] :as opts}]
  (case (normalization-mode opts)
    :none weights
    :preserve-net (scale-to-sum (or target-net original-net) weights)
    :sum-to-one (scale-to-sum 1 weights)
    (scale-to-sum 1 weights)))

(defn clean-weights
  [{:keys [instrument-ids weights dust-threshold] :as opts}]
  (let [threshold (or dust-threshold default-dust-threshold)
        original-net (reduce + 0 weights)
        rows (map vector instrument-ids weights)
        kept (filterv (fn [[_ weight]]
                        (>= (abs-num weight) threshold))
                      rows)
        dropped (->> rows
                     (remove (set kept))
                     (mapv (fn [[id weight]]
                             {:instrument-id id
                              :weight weight
                              :reason :dust-threshold})))]
    {:instrument-ids (mapv first kept)
     :weights (clean-normalize (mapv second kept)
                               (assoc opts :original-net original-net))
     :dropped dropped}))
