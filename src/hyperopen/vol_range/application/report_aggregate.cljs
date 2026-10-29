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
  - 7D and 30D: the sum when every campaign is covered; otherwise no total
    (`:aggregate/incomplete`), naming the uncovered markets and offering
    the covered subset, which is summed (and named) only when the user
    chose it.

  Pure."
  (:require [hyperopen.vol-range.domain.report-equations :as equations]))

(defn- ok? [item] (= :ok (get-in item [:result :status])))

(defn- same-interval? [items]
  (and (apply = (map #(get-in % [:result :a-ms]) items))
       (apply = (map #(get-in % [:result :b-ms]) items))
       (apply = (map :network items))))

(defn aggregate
  "{:status :scope :figures :result :included :excluded :subset-offer?
  :subset? :reason} for `items` [{:symbol :network :result}] (`result` from
  `report-windows/campaign-window-figures`) of one account and `window`.
  `:scope` is :none, :single or :all; `:included` and `:excluded` are
  symbols."
  [{:keys [window items covered-subset?]}]
  (let [items (vec items)
        covered (filterv ok? items)
        uncovered (filterv (complement ok?) items)
        symbols #(mapv :symbol %)
        base {:figures nil :result nil :included [] :excluded [] :subset-offer? false :subset? false :reason nil}]
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

      (and covered-subset? (seq covered))
      (assoc base :status :ok :scope :all :subset? true :subset-offer? true :result (:result (first covered))
             :figures (equations/sum-figures (map #(get-in % [:result :figures]) covered))
             :included (symbols covered) :excluded (symbols uncovered))

      :else
      (assoc base :status :no-total :scope :all :reason :aggregate/incomplete
             :subset-offer? (boolean (seq covered))
             :included (symbols covered) :excluded (symbols uncovered)))))
