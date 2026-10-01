(ns hyperopen.views.trade-view.hyperevm-funds-cost-test
  "/trade asks for the HyperEVM line on every render, whichever account tab
   is selected. The line must add no HyperCore balance-row build (it prices
   against the rows the equity metrics already built, with the same
   arguments), and it re-prices its rows only when something it shows
   could change: never for an account holding nothing on HyperEVM, and not
   on a tick of a market no held token prices from, such as the active
   perp's mark."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [hyperopen.funding.test-support.hyperevm-transfer :as fixture]
            [hyperopen.views.account-equity-view :as account-equity-view]
            [hyperopen.views.account-equity.hyperevm-line :as hyperevm-line]
            [hyperopen.views.account-info.derived-cache :as derived-cache]
            [hyperopen.views.account-info.projections :as projections]
            [hyperopen.views.account-info.projections.balances-hyperevm :as balances-hyperevm]
            [hyperopen.views.account-info.projections.hyperevm-funds :as hyperevm-funds]
            [hyperopen.views.trade-view :as trade-view]
            [hyperopen.views.trade.test-support :as support]))

(defn- reset-caches!
  []
  (hyperevm-funds/reset-hyperevm-funds-cache!)
  (derived-cache/reset-derived-cache!)
  (account-equity-view/reset-account-equity-metrics-cache!))

(use-fixtures :each
  (fn [f]
    (reset-caches!)
    (f)
    (reset-caches!)))

(defn- trade-state
  "/trade on the Positions tab (not Balances), with the fixture's HyperCore
   snapshot and HyperEVM read."
  [evm-entry]
  (let [evm (fixture/with-evm-entry (fixture/state) evm-entry)]
    (-> (support/active-asset-state)
        (assoc :wallet (:wallet evm)
               :spot (:spot evm)
               :webdata2 (:webdata2 evm)
               :router {:path "/trade"}
               :hyperevm (:hyperevm evm))
        (assoc-in [:account-info :selected-tab] :positions)
        (update-in [:asset-selector :market-by-key]
                   merge (get-in evm [:asset-selector :market-by-key])))))

(defn- exports
  [models line?]
  (cond-> {;; A fresh function per run, so the view's last-call memo starts empty.
           :account-equity-metrics (fn [state] (account-equity-view/account-equity-metrics state))
           :account-equity-view (fn [_state opts]
                                  (swap! models conj (:hyperevm-line opts))
                                  [:div {:data-role "stub-account-equity"}])
           :funding-actions-view (fn [& _args]
                                   [:div {:data-role "stub-funding-actions"}])}
    line? (assoc :hyperevm-line-model hyperevm-line/hyperevm-line-model)))

(defn- perp-tick
  "The active perp's mark moves: a new catalogue object, as
   `market-live-projection/apply-active-asset-ctx-update` makes on every
   active-asset ctx message."
  [state mark]
  (assoc-in state [:asset-selector :market-by-key "perp:BTC" :mark] mark))

(defn- webdata2-tick
  [state account-value]
  (assoc-in state [:webdata2 :clearinghouseState :marginSummary :accountValue] account-value))

(defn- steps
  [state]
  (let [ticked (perp-tick state 64001.0)
        ticked-again (perp-tick ticked 64002.0)
        new-webdata2 (webdata2-tick ticked-again "801")
        hype-moves (assoc-in new-webdata2 [:asset-selector :market-by-key "spot:HYPE" :mark] "50")]
    [state state ticked ticked-again new-webdata2 hype-moves]))

(defn- per-render-counts
  "Render each state on /trade at 1280 px; return, per render, how many
   balance-row builds and HyperEVM row pricings it caused."
  [states models line?]
  (reset-caches!)
  (let [builds (atom 0)
        pricings (atom 0)
        hyperevm-rows balances-hyperevm/hyperevm-rows]
    (binding [derived-cache/*build-balance-rows*
              (fn [& args]
                (swap! builds inc)
                (apply projections/build-balance-rows args))]
      (with-redefs [balances-hyperevm/hyperevm-rows (fn [& args]
                                                      (swap! pricings inc)
                                                      (apply hyperevm-rows args))]
        (support/with-viewport-width
          1280
          (fn []
            (support/with-account-surface-exports
              (exports models line?)
              (fn []
                (mapv (fn [state]
                        (let [before [@builds @pricings]]
                          (trade-view/trade-view state)
                          (mapv - [@builds @pricings] before)))
                      states)))))))))

(deftest the-hyperevm-line-adds-no-balance-row-build-test
  (doseq [[label entry] [["nothing held on HyperEVM" (assoc fixture/evm-entry :native-wei "0" :token-units {})]
                         ["tokens held on HyperEVM" fixture/evm-entry]]]
    (let [states (steps (trade-state entry))
          without-line (mapv first (per-render-counts states (atom []) false))
          with-line (mapv first (per-render-counts states (atom []) true))]
      (is (= without-line with-line) label)
      (is (= [1 0 1 1 1 1] with-line)
          (str label ": the equity metrics' own builds (a catalogue or webdata2 change), shared")))))

(deftest the-line-reprices-only-when-a-held-token-price-input-changes-test
  (let [models (atom [])
        empty-wallet (mapv second (per-render-counts (steps (trade-state (assoc fixture/evm-entry
                                                                                :native-wei "0"
                                                                                :token-units {})))
                                                     (atom [])
                                                     true))
        holder (mapv second (per-render-counts (steps (trade-state fixture/evm-entry)) models true))]
    (is (= [0 0 0 0 0 0] empty-wallet) "nothing held: nothing to price, ever")
    (is (= [1 0 0 0 1 1] holder)
        "priced once, not on a repeat render or the active perp's ticks, again on a webdata2 change and when a held token's own market moves")
    (is (= "$1,808.50" (:value-text (first @models))))
    (is (= "$1,875.00" (:value-text (last @models)))
        "12.5 HYPE now at 50: the line follows the price")))
