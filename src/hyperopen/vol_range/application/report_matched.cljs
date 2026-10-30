(ns hyperopen.vol-range.application.report-matched
  "The Gross spread on matched fills disclosure of the Vol Range report view
  model (HO-SPEC-014 v2.1 section 12.5): per shown market with headline
  figures, the matched quantity and pair count, the gross matched spread,
  unmatched buys and Sold inventory not reaccumulated at the end mark, the
  fee and rebate impact at that mark, the reconciliation to Ladder
  contribution versus hold, and the fees actually paid by token.
  Formatting only: the numbers come from `report-matching`. Pure."
  (:require [clojure.string :as str]
            [hyperopen.domain.trading.precision :as precision]
            [hyperopen.vol-range.application.display :as display]
            [hyperopen.vol-range.application.report-copy :as copy]
            [hyperopen.vol-range.application.report-headline :as headline]
            [hyperopen.vol-range.domain.report-matching :as matching]))

(defn- scale [s] (let [dot (.indexOf s ".")] (if (neg? dot) 0 (- (count s) dot 1))))

(defn- price-text [x]
  (when-let [d (display/parse x)]
    (let [s (precision/decimal->string d)]
      (if (<= (scale s) 2) (display/fixed s 2) (display/exact s)))))

(defn- fees-text [fees]
  (if (seq fees)
    (str/join " · " (for [[token amount] (sort-by key fees)] (str (display/exact amount) " " token)))
    "—"))

(defn- row [id label value] {:id id :label label :value (some-> value headline/usdc-text)})

(defn- market-view
  "One market's rows, or nil without an end mark."
  [{:keys [campaign result events market symbol]}]
  (let [m (matching/match-window {:campaign campaign :events events :a-ms (:a-ms result) :b-ms (:b-ms result)
                                  :p-end (get-in result [:b-mark :price])})
        contribution (get-in result [:figures :contribution])]
    (when m
      {:campaign-id (:campaign-id campaign)
       :market market
       :rows (cond-> [(row :matched-qty (copy/matched-qty-text (display/exact (:matched-qty m)) symbol (:pairs m)) nil)
                      (row :gross copy/matched-gross (:gross m))
                      (row :unmatched-buys (copy/unmatched-buys-text (price-text (:p-end m))) (:unmatched-buys m))
                      (row :unmatched-sells copy/matched-unmatched-sells (:unmatched-sells m))
                      (row :fee-impact copy/matched-fee-impact (:fee-impact m))]
               (matching/reconciles-with? m contribution) (conj (row :total copy/matched-total (:total m))))
       :fees-paid (copy/fees-paid-text (fees-text (:fees-by-token m)))})))

(defn matched-view
  "{:open? :title :note :markets} for `items` [{:campaign :result :events
  :market :symbol}] whose window figures are shown (`:status :ok`), or nil
  when there is none."
  [{:keys [items open?]}]
  (let [markets (into [] (keep market-view) (filter #(= :ok (get-in % [:result :status])) items))]
    (when (seq markets)
      {:open? (boolean open?) :title copy/matched-title :note copy/matched-note :markets markets})))
