(ns hyperopen.portfolio.vault-attribution.series
  "The vault sleeve over time.

   P&L is the primary quantity and value is derived from it:
   value(t) = pnl(t) + deposited-to-date - net-withdrawn-to-date. Every
   transfer group contributes two points 1 ms apart, evaluated at the SAME
   instant, so the interval that carries a cash flow carries exactly zero P&L
   (the portfolio returns code scores any interval with a large flow as 0%).

   Within a holding period the P&L follows the vault's share-price index
   (units bought and sold at the index). The gap between that model and the
   exact ledger P&L (commission, coarse history) is spread additively over the
   period in proportion to capital-time held, i.e. as a constant excess return
   rate, so a small early position never absorbs a large position's drift.
   A vault without usable history is held at running cost basis and its P&L
   is recognised just before each withdrawal and at the end."
  (:require [hyperopen.portfolio.metrics.history :as history]
            [hyperopen.portfolio.vault-attribution.model :as model]))

(defn period-pnls
  "Exact ledger P&L of each holding period, by period index."
  [{:keys [transfers periods equity]}]
  (let [sums (reduce (fn [acc {:keys [kind amount period]}]
                       (update acc period + (if (= :deposit kind) (- amount) amount)))
                     (vec (repeat (count periods) 0))
                     transfers)]
    (mapv (fn [period sum]
            (if (:closed? period) sum (+ sum equity)))
          periods
          sums)))

(defn- with-cost-basis-realized
  "Realized P&L per withdrawal under the cost-basis model; a closing
   withdrawal takes whatever makes its period's P&L exact."
  [transfers p-ends]
  (:rows
   (reduce (fn [{:keys [rows basis realized]} {:keys [kind amount period closes?] :as transfer}]
             (if (= :deposit kind)
               {:rows (conj rows transfer) :basis (+ basis amount) :realized realized}
               (let [cost (min basis (or (:basis transfer) amount))
                     r (if closes?
                         (- (nth p-ends period) realized)
                         (- amount cost))
                     row (assoc transfer :cost-realized r)]
                 (if closes?
                   {:rows (conj rows row) :basis 0 :realized 0}
                   {:rows (conj rows row) :basis (- basis cost) :realized (+ realized r)}))))
           {:rows [] :basis 0 :realized 0}
           transfers)))

(defn prepare-vault
  [vault]
  (let [p-ends (period-pnls vault)]
    (assoc vault
           :p-ends p-ends
           :transfers (with-cost-basis-realized (:transfers vault) p-ends))))

(defn- transfer-groups
  "Transfer times bucketed so that times 1 ms apart share one point pair."
  [times]
  (reduce (fn [groups t]
            (let [group (peek groups)]
              (if (and group (<= (- t (:last group)) 1))
                (conj (pop groups) (assoc group :last t))
                (conj groups {:first t :last t}))))
          []
          (sort (distinct times))))

(defn build-slots
  "Ascending, label-unique sample slots. `:eval` is the instant every vault is
   valued at; `:upto` includes transfers with time <= it; a before-slot's
   `:pending-upto` names the transfers its pair is about to apply."
  [vaults end-ms]
  (let [groups (transfer-groups (mapcat #(map :time-ms (:transfers %)) vaults))
        pair-slots (mapcat (fn [{:keys [first last]}]
                             [{:label first :eval first :upto (dec first) :pending-upto last}
                              {:label (inc last) :eval first :upto last}])
                           groups)
        in-pair? (fn [t] (some #(<= (:first %) t (inc (:last %))) groups))
        history (for [{:keys [index periods]} vaults
                      {:keys [start-ms end-ms]} periods
                      [t] index
                      :when (< start-ms t end-ms)]
                  t)
        samples (->> (conj history end-ms)
                     distinct
                     (remove in-pair?)
                     (filter #(<= % end-ms))
                     (map (fn [t] {:label t :eval t :upto t})))]
    (vec (sort-by :label (concat pair-slots samples)))))

(defn- apply-transfer
  [st {:keys [kind amount requested period closes? cost-realized]} index-now p-ends]
  (if (= :deposit kind)
    (let [st (if (nil? (:period st))
               (assoc st :period period :p-dep 0 :p-mw 0 :w 0 :realized 0)
               st)]
      (-> st
          (update :units + (/ amount index-now))
          (update :dep + amount)
          (update :p-dep + amount)))
    (let [vm (* (:units st) index-now)
          out (if closes? vm (min vm (or requested amount)))
          st (-> st
                 (assoc :units (if closes? 0 (max 0 (- (:units st) (/ out index-now)))))
                 (update :p-mw + out)
                 (update :netw + amount)
                 (update :realized + (or cost-realized 0)))]
      (if (and closes? (some? (:period st)))
        (-> st
            (update :completed + (nth p-ends (:period st)))
            (assoc :period nil :units 0))
        st))))

(defn- raw-rows
  "First pass: model state at each slot."
  [{:keys [transfers index p-ends]} slots]
  (let [index-fn (if (seq index) #(model/index-at index %) (constantly 1))]
    (loop [slots slots
           pending transfers
           st {:units 0 :dep 0 :netw 0 :period nil :p-dep 0 :p-mw 0 :w 0
               :realized 0 :completed 0 :prev-vm 0 :prev-eval nil}
           out []]
      (if-let [{ev :eval, upto :upto, pending-upto :pending-upto} (first slots)]
        (let [i (index-fn ev)
              vm-pre (* (:units st) i)
              st (if (and (:period st) (number? (:prev-eval st)))
                   (update st :w + (* 0.5 (+ (:prev-vm st) vm-pre) (- ev (:prev-eval st))))
                   st)
              [due later] (split-with #(<= (:time-ms %) upto) pending)
              st (reduce #(apply-transfer %1 %2 i p-ends) st due)
              vm (* (:units st) i)
              upcoming (when pending-upto
                         (->> later
                              (take-while #(<= (:time-ms %) pending-upto))
                              (keep :cost-realized)
                              (reduce + 0)))]
          (recur (rest slots)
                 later
                 (assoc st :prev-vm vm :prev-eval ev)
                 (conj out {:eval ev
                            :period (:period st)
                            :pm (when (:period st) (- (+ vm (:p-mw st)) (:p-dep st)))
                            :w (:w st)
                            :dep (:dep st)
                            :netw (:netw st)
                            :completed (:completed st)
                            :realized (:realized st)
                            :pending (or upcoming 0)})))
        out))))

(defn- period-ends
  "Last slot row of each period: where its P&L must be exact."
  [rows]
  (reduce (fn [acc [i row]]
            (if-let [p (:period row)]
              (assoc acc p (assoc row :row-index i))
              acc))
          {}
          (map-indexed vector rows)))

(defn vault-values
  "`[{:value :net-deposited :pnl} ...]`, one per slot."
  [{:keys [index periods p-ends] :as vault} slots]
  (let [rows (raw-rows vault slots)
        ends (period-ends rows)
        indexed? (seq index)]
    (vec
     (map-indexed
      (fn [i {ev :eval, :keys [period pm w dep netw completed realized pending]}]
        (let [period-pnl
              (when (some? period)
                (let [end (get ends period)
                      p-end (nth p-ends period)
                      start-ms (:start-ms (nth periods period))]
                  (if indexed?
                    (let [w-end (:w end)
                          span (- (:eval end) start-ms)
                          frac (cond
                                 (pos? w-end) (/ w w-end)
                                 (pos? span) (/ (- ev start-ms) span)
                                 :else 1)
                          frac (if (= i (:row-index end)) 1 (min 1 (max 0 frac)))]
                      (+ pm (* (- p-end (:pm end)) frac)))
                    (if (and (= i (:row-index end))
                             (not (:closed? (nth periods period))))
                      p-end
                      (+ realized pending)))))
              pnl (+ completed (or period-pnl 0))]
          {:value (- (+ pnl dep) netw)
           :net-deposited (- dep netw)
           :pnl pnl}))
      rows))))

(defn sleeve-series
  "Sum of every vault's values at each slot, ascending by time. Points that
   are not finite are dropped."
  [vaults slots]
  (let [per-vault (mapv #(vault-values % slots) vaults)]
    (->> slots
         (map-indexed
          (fn [i {:keys [label]}]
            (reduce (fn [acc values]
                      (let [{:keys [value net-deposited pnl]} (nth values i)]
                        (-> acc
                            (update :value + value)
                            (update :net-deposited + net-deposited)
                            (update :pnl + pnl))))
                    {:time-ms label :value 0 :net-deposited 0 :pnl 0}
                    per-vault)))
         (filter (fn [{:keys [value net-deposited pnl]}]
                   (and (model/finite value) (model/finite net-deposited) (model/finite pnl))))
         vec)))

(defn- entry
  "Summary entry in the row format of real Hyperliquid portfolio entries:
   `[time-ms number]` rows on identical times, P&L rebased to 0 at the first
   point (as Hyperliquid's own windowed entries and the derived-window code
   in `vm.summary` do)."
  [points]
  (when (and (>= (count points) 2)
             (some #(> (:value %) model/epsilon) points))
    (let [base (:pnl (first points))]
      {:accountValueHistory (mapv (juxt :time-ms :value) points)
       :pnlHistory (mapv (fn [{:keys [time-ms pnl]}] [time-ms (- pnl base)]) points)})))

(defn- window-points
  "Series points after `start-ms`, led by a point interpolated at `start-ms`
   when the series reaches back that far."
  [series start-ms]
  (let [before (last (filter #(<= (:time-ms %) start-ms) series))
        after (filterv #(> (:time-ms %) start-ms) series)
        nxt (first after)
        anchor (cond
                 (nil? before) nil
                 (or (= (:time-ms before) start-ms) (nil? nxt)) (assoc before :time-ms start-ms)
                 :else
                 (let [f (/ (- start-ms (:time-ms before))
                            (- (:time-ms nxt) (:time-ms before)))
                       lerp (fn [k] (+ (k before) (* f (- (k nxt) (k before)))))]
                   {:time-ms start-ms
                    :value (lerp :value)
                    :net-deposited (lerp :net-deposited)
                    :pnl (lerp :pnl)}))]
    (if anchor (into [anchor] after) after)))

(def ^:private window-days
  {:vault-day 1 :vault-week 7 :vault-month 30})

(defn summary-entries
  [series now-ms]
  (reduce-kv (fn [acc k days]
               (if-let [e (entry (window-points series (- now-ms (* days model/day-ms))))]
                 (assoc acc k e)
                 acc))
             (if-let [e (entry series)] {:vault-all-time e} {})
             window-days))

(defn time-weighted-return-pct
  "Cumulative time-weighted return (percent) of the sleeve of the prepared
   `vaults` through `end-ms`, read by the portfolio's own returns code from
   the same all-time entry the sleeve publishes. For one vault this is that
   vault's return while held, P&L pinned to exact ledger dollars. Nil when it
   cannot be computed."
  [vaults end-ms]
  (when (seq vaults)
    (some-> (entry (sleeve-series vaults (build-slots vaults end-ms)))
            history/returns-history-rows-from-summary
            vec
            peek
            second
            model/finite)))

(defn daily-from-ms
  "Earliest time from which every then-held vault is valued from ~daily
   history; points before it are estimates. Nil without vaults."
  [vaults now-ms]
  (let [starts (for [{:keys [periods]} vaults p periods] (:start-ms p))
        limits (for [{:keys [index periods]} vaults
                     :let [dense (model/dense-start-ms index now-ms)]
                     {:keys [start-ms end-ms]} periods
                     :when (or (nil? dense) (< start-ms dense))]
                 (if (number? dense) (min dense end-ms) end-ms))]
    (when (seq starts)
      (apply max (apply min starts) limits))))
