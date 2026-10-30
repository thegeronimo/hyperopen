(ns hyperopen.vol-range.application.report-aggregate
  "All tracked markets for Vol Range reporting (HO-SPEC-014 v2.1 section
  12.7, 13.5 row 9).

  Dollar figures are summed across distinct base assets only when they
  agree on account, network, the USDC unit and the exact interval `[a,b)`,
  and every included campaign's headline is allowed. Each campaign's cash
  is its own tracked fills' cash flow, so every fill counts once and no
  campaign carries a copy of the account's USDC balance; the total is the
  plain sum of the campaigns' figures.

  - one market shown: that campaign's result, unchanged;
  - Since tracking began: every campaign has its own `a = T0`, so there is
    no total (`:aggregate/different-windows`);
  - 7D and 30D: the sum when every campaign is covered; while a market's
    hourly close is still loading (`:marks/pending`), no total and no
    subset yet (`:pending`), since that market may well be covered;
    otherwise no total (`:aggregate/incomplete`), naming the markets that
    aren't fully covered (`:excluded`) and those without a completed
    hourly close (`:unpriced`) or whose close couldn't be read
    (`:price-errors`), and offering the covered subset, which is summed
    (and named) only when the user chose it.

  Pure."
  (:require [hyperopen.vol-range.domain.report-equations :as equations]))

(defn- ok? [item] (= :ok (get-in item [:result :status])))

(defn- reason [item] (get-in item [:result :reason]))
(def ^:private price-reasons #{:marks/missing-boundary-close :marks/read-error})

(defn- same-interval? [items]
  (and (apply = (map #(get-in % [:result :a-ms]) items))
       (apply = (map #(get-in % [:result :b-ms]) items))
       (apply = (map :network items))))

(defn aggregate
  "{:status :scope :figures :result :included :excluded :unpriced
  :price-errors :subset-offer? :subset? :reason} for `items` [{:symbol
  :network :result}] (`result` from `report-windows/campaign-window-figures`)
  of one account and `window`. `:scope` is :none, :single or :all;
  `:included`, `:excluded` (not fully covered), `:unpriced` (no completed
  boundary close) and `:price-errors` (the close couldn't be read) are
  symbols."
  [{:keys [window items covered-subset?]}]
  (let [items (vec items)
        covered (filterv ok? items)
        uncovered (filterv (complement ok?) items)
        symbols #(mapv :symbol %)
        with-reason (fn [r] (symbols (filter #(= r (reason %)) uncovered)))
        base {:figures nil :result nil :included [] :excluded [] :unpriced [] :price-errors []
              :subset-offer? false :subset? false :reason nil}
        incomplete {:included (symbols covered)
                    :excluded (symbols (remove #(contains? price-reasons (reason %)) uncovered))
                    :unpriced (with-reason :marks/missing-boundary-close)
                    :price-errors (with-reason :marks/read-error)}]
    (cond
      (empty? items) (assoc base :status :none :scope :none)

      (= 1 (count items))
      (let [r (:result (first items))]
        (assoc base :status (:status r) :scope :single :result r :figures (:figures r) :reason (:reason r)
               :included (symbols items)))

      (= :since-start window)
      (if (every? #(= :window/no-completed-mark (get-in % [:result :reason])) items)
        (assoc base :status :pending :scope :all :reason :window/no-completed-mark)
        (assoc base :status :no-total :scope :all :reason :aggregate/different-windows))

      (not (same-interval? items))
      (assoc base :status :no-total :scope :all :reason :aggregate/different-windows)

      (empty? uncovered)
      (assoc base :status :ok :scope :all :result (:result (first items))
             :figures (equations/sum-figures (map #(get-in % [:result :figures]) items))
             :included (symbols items))

      (some #(= :marks/pending (reason %)) uncovered)
      (assoc base :status :pending :scope :all :reason :marks/pending)

      (and covered-subset? (seq covered))
      (merge base incomplete
             {:status :ok :scope :all :subset? true :subset-offer? true :result (:result (first covered))
              :figures (equations/sum-figures (map #(get-in % [:result :figures]) covered))})

      :else
      (merge base incomplete
             {:status :no-total :scope :all :reason :aggregate/incomplete
              :subset-offer? (boolean (seq covered))}))))
