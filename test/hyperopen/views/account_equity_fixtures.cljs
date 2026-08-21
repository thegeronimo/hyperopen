(ns hyperopen.views.account-equity-fixtures
  "Account shapes both account panels have to get right, each expressed once.

   Every fixture is built by one constructor that emits two things from a single
   description: the application-state map `account-equity-metrics` reads, and
   the raw per-dex inputs `account-equity-venue-oracle` reads. Building both
   from one description is what stops a fixture drifting -- if the two were
   written out separately, a typo in one would look like a parity failure in the
   other.

   A fixture may declare `:divergences`, a map of row key to
   `{:venue _ :ours _ :reason _}`. Those are the places we knowingly print
   something other than what the venue prints, and the parity test asserts both
   sides of the divergence rather than skipping the row, so a change that
   accidentally erases one fails loudly.")

;; ---------------------------------------------------------------------------
;; Wire-shape constructors

(defn- summary
  [account-value total-ntl-pos total-raw-usd total-margin-used]
  {:accountValue account-value
   :totalNtlPos total-ntl-pos
   :totalRawUsd total-raw-usd
   :totalMarginUsed total-margin-used})

(defn- position
  "One `assetPositions` entry. `kind` is `:cross` or `:isolated`."
  [{:keys [coin dex kind position-value margin-used upnl max-leverage]}]
  {:type "oneWay"
   :position {:coin (if dex (str dex ":" coin) coin)
              :leverage {:type (name kind) :value 5}
              :maxLeverage (or max-leverage 20)
              :positionValue position-value
              :marginUsed margin-used
              :unrealizedPnl upnl}})

(defn- clearinghouse-state
  [{:keys [margin cross maintenance positions]}]
  (cond-> {}
    margin (assoc :marginSummary margin)
    cross (assoc :crossMarginSummary cross)
    maintenance (assoc :crossMaintenanceMarginUsed maintenance)
    positions (assoc :assetPositions positions)))

;; ---------------------------------------------------------------------------
;; Application state

(defn- spot-meta
  [spot]
  {:tokens (vec (map-indexed (fn [idx {:keys [coin]}]
                               {:index idx :name coin :weiDecimals 6})
                             spot))
   :universe []})

(defn- spot-wire-balances
  [spot]
  (vec (map-indexed (fn [idx {:keys [coin total]}]
                      {:coin coin :token idx :hold "0.0" :total total :entryNtl "0"})
                    spot)))

(defn- perp-market-entry
  [{:keys [dex collateral coin]}]
  (let [coin* (if dex (str dex ":" coin) coin)
        market-key (str "perp:" coin*)]
    [market-key {:key market-key
                 :coin coin*
                 :market-type :perp
                 :dex (or dex "")
                 :base coin
                 :quote collateral}]))

(defn- spot-market-entry
  [prices {:keys [coin]}]
  (let [market-key (str "spot:" coin "/USDC")]
    [market-key {:key market-key
                 :coin (str coin "/USDC")
                 :market-type :spot
                 :base coin
                 :quote "USDC"
                 :mark (get prices coin)
                 :szDecimals 4}]))

(defn- market-by-key
  [{:keys [dexes collateral-only-dexes spot prices]}]
  (into {}
        (concat (map perp-market-entry (concat dexes collateral-only-dexes))
                (->> spot
                     (remove #(= "USDC" (:coin %)))
                     (map #(spot-market-entry prices %))))))

(defn- application-state
  [{:keys [mode dexes spot spot-state?] :as description}]
  (let [base (first (filter #(nil? (:dex %)) dexes))
        named (filter :dex dexes)]
    {:account {:mode mode}
     :webdata2 (cond-> {:spotAssetCtxs []}
                 base (assoc :clearinghouseState (:state base)))
     :spot (if (false? spot-state?)
             {:meta nil :clearinghouse-state nil}
             {:meta (spot-meta spot)
              :clearinghouse-state {:balances (spot-wire-balances spot)}})
     :asset-selector {:market-by-key (market-by-key description)}
     :perp-dex-clearinghouse (into {} (map (juxt :dex :state)) named)}))

;; ---------------------------------------------------------------------------
;; Fixture assembly

(defn build
  "Turn one description into `{:label :mode :state :oracle :divergences}`.

   A description is `{:label :mode :prices :spot :dexes :collateral-only-dexes
   :divergences}`, where `:dexes` are the dexes this wallet has a clearinghouse
   snapshot on (`:dex nil` is the base dex) and `:collateral-only-dexes` are
   dexes that exist on the venue but which this wallet has never traded -- they
   contribute a collateral token and nothing else."
  [{:keys [label mode prices spot dexes collateral-only-dexes divergences]
    :as description}]
  (let [spot* (or spot [])
        dexes* (or dexes [])
        collateral-only* (or collateral-only-dexes [])
        description* (assoc description
                            :spot spot*
                            :dexes dexes*
                            :collateral-only-dexes collateral-only*)]
    {:label label
     :mode mode
     :state (application-state description*)
     :divergences (or divergences {})
     :oracle {:per-dex (mapv (fn [{:keys [collateral state]}]
                               {:collateral collateral
                                :collateral-price (get prices collateral)
                                :state state})
                             dexes*)
              :spot-balances (mapv #(select-keys % [:coin :total]) spot*)
              :collateral-tokens (into #{} (map :collateral)
                                       (concat dexes* collateral-only*))
              :price-by-token prices}}))

;; ---------------------------------------------------------------------------
;; Reusable divergence reasons

(def ^:private no-data-reason
  (str "No clearinghouse snapshot contributed a figure, so there is nothing to "
       "divide or display. The venue falls back to a zero; we render \"--\", "
       "because a confident $0.00 over an account we have no data for is the "
       "failure trading-ui-policy.md forbids."))

(def ^:private flat-quotient-reason
  (str "Cross account value is zero, so both classic quotients divide by zero. "
       "The venue adds 1e-8 to the denominator and prints 0; `safe-div` returns "
       "nil and the row renders \"--\". Deliberate: the same guard would print a "
       "confident 0.00x for any account whose cross equity we failed to read."))

(defn- flat-quotient-divergences
  []
  {:cross-margin-ratio {:venue 0.0 :ours nil :reason flat-quotient-reason}
   :cross-account-leverage {:venue 0.0 :ours nil :reason flat-quotient-reason}})

;; ---------------------------------------------------------------------------
;; The matrix

(def ^:private usd-prices
  {"USDC" 1 "USDH" 1})

(def ^:private classic-flat
  {:label "classic / funded but flat"
   :mode :classic
   :prices usd-prices
   :spot [{:coin "USDC" :total "500.0"}]
   :dexes [{:dex nil
            :collateral "USDC"
            :coin "BTC"
            :state (clearinghouse-state
                    {:margin (summary "0.0" "0.0" "0.0" "0.0")
                     :cross (summary "0.0" "0.0" "0.0" "0.0")
                     :maintenance "0.0"
                     :positions []})}]
   :divergences (flat-quotient-divergences)})

;; The long and the short exist as a pair on purpose: our Balance overstates the
;; venue's by `totalMarginUsed + totalNtlPos + upnl`, so the error's size and
;; sign both move with position direction. A long-only matrix under-detects.
(def ^:private classic-base-cross-long
  {:label "classic / base dex, one cross long"
   :mode :classic
   :prices usd-prices
   :spot [{:coin "USDC" :total "250.0"}]
   :dexes [{:dex nil
            :collateral "USDC"
            :coin "BTC"
            ;; accountValue = totalRawUsd + signed position value: -3000 + 4000.
            :state (clearinghouse-state
                    {:margin (summary "1000.0" "4000.0" "-3000.0" "400.0")
                     :cross (summary "1000.0" "4000.0" "-3000.0" "400.0")
                     :maintenance "100.0"
                     :positions [(position {:coin "BTC"
                                            :kind :cross
                                            :position-value "4000.0"
                                            :margin-used "400.0"
                                            :upnl "200.0"})]})}]})

(def ^:private classic-base-cross-short
  {:label "classic / base dex, one cross short"
   :mode :classic
   :prices usd-prices
   :spot [{:coin "USDC" :total "250.0"}]
   :dexes [{:dex nil
            :collateral "USDC"
            :coin "ETH"
            ;; A short inverts the identity: 7000 - 5000 = 2000.
            :state (clearinghouse-state
                    {:margin (summary "2000.0" "5000.0" "7000.0" "500.0")
                     :cross (summary "2000.0" "5000.0" "7000.0" "500.0")
                     :maintenance "250.0"
                     :positions [(position {:coin "ETH"
                                            :kind :cross
                                            :position-value "5000.0"
                                            :margin-used "500.0"
                                            :upnl "-1500.0"})]})}]})

;; The shape behind `0xb9aebb46919bccbf210537a1f2173690d9ee7af7`: a zeroed base
;; dex alongside a six-figure book on a named dex. Reading only the base dex
;; puts four confident zeros on screen over real liquidation risk.
(def ^:private classic-named-dex-only
  {:label "classic / empty base dex, whole book on a named dex"
   :mode :classic
   :prices usd-prices
   :spot [{:coin "USDC" :total "100.0"}]
   :dexes [{:dex nil
            :collateral "USDC"
            :coin "BTC"
            :state (clearinghouse-state
                    {:margin (summary "0.0" "0.0" "0.0" "0.0")
                     :cross (summary "0.0" "0.0" "0.0" "0.0")
                     :maintenance "0.0"
                     :positions []})}
           {:dex "xyz"
            :collateral "USDC"
            :coin "AAPL"
            :state (clearinghouse-state
                    {:margin (summary "155901.46" "45765.92" "110135.54" "15255.31")
                     :cross (summary "155901.46" "45765.92" "110135.54" "15255.31")
                     :maintenance "1144.15"
                     :positions [(position {:coin "AAPL"
                                            :dex "xyz"
                                            :kind :cross
                                            :position-value "45765.92"
                                            :margin-used "15255.31"
                                            :upnl "6743.65"})]})}]})

;; A dex settling in something other than a dollar. Every figure it reports is
;; denominated in HYPE, so adding it to a USD total without converting is a
;; 40x error on that dex's contribution.
(def ^:private classic-two-collateral-tokens
  {:label "classic / base dex in USDC plus a dex settling in HYPE"
   :mode :classic
   :prices {"USDC" 1 "HYPE" 40}
   :spot [{:coin "USDC" :total "300.0"}
          {:coin "HYPE" :total "10.0"}]
   :dexes [{:dex nil
            :collateral "USDC"
            :coin "BTC"
            :state (clearinghouse-state
                    {:margin (summary "1000.0" "2000.0" "-1000.0" "200.0")
                     :cross (summary "1000.0" "2000.0" "-1000.0" "200.0")
                     :maintenance "50.0"
                     :positions [(position {:coin "BTC"
                                            :kind :cross
                                            :position-value "2000.0"
                                            :margin-used "200.0"
                                            :upnl "120.0"})]})}
           {:dex "hna"
            :collateral "HYPE"
            :coin "GOLD"
            ;; 50 HYPE of equity is $2,000, not $50.
            :state (clearinghouse-state
                    {:margin (summary "50.0" "100.0" "-50.0" "10.0")
                     :cross (summary "50.0" "100.0" "-50.0" "10.0")
                     :maintenance "5.0"
                     :positions [(position {:coin "GOLD"
                                            :dex "hna"
                                            :kind :cross
                                            :position-value "100.0"
                                            :margin-used "10.0"
                                            :upnl "4.0"})]})}]})

(def ^:private classic-with-isolated
  {:label "classic / one cross and one isolated position"
   :mode :classic
   :prices usd-prices
   :spot [{:coin "USDC" :total "150.0"}]
   :dexes [{:dex nil
            :collateral "USDC"
            :coin "BTC"
            ;; marginSummary spans both legs; crossMarginSummary excludes the
            ;; isolated one, which is why the two summaries disagree here.
            :state (clearinghouse-state
                    {:margin (summary "3000.0" "7000.0" "-4000.0" "1200.0")
                     :cross (summary "2000.0" "4000.0" "-2000.0" "400.0")
                     :maintenance "100.0"
                     :positions [(position {:coin "BTC"
                                            :kind :cross
                                            :position-value "4000.0"
                                            :margin-used "400.0"
                                            :upnl "200.0"})
                                 (position {:coin "ETH"
                                            :kind :isolated
                                            :position-value "3000.0"
                                            :margin-used "800.0"
                                            :upnl "-100.0"})]})}]})

(def ^:private unified-all-cross
  {:label "unified / cross book only"
   :mode :unified
   :prices usd-prices
   :spot [{:coin "USDC" :total "1000.0"}]
   :dexes [{:dex nil
            :collateral "USDC"
            :coin "BTC"
            :state (clearinghouse-state
                    {:margin (summary "0.0" "500.0" "0.0" "0.0")
                     :cross (summary "0.0" "500.0" "0.0" "0.0")
                     :maintenance "25.0"
                     :positions [(position {:coin "BTC"
                                            :kind :cross
                                            :position-value "500.0"
                                            :margin-used "100.0"
                                            :upnl "10.0"})]})}]})

;; The case commit `dc2ada72d` introduced: a cross-only lens genuinely reads
;; 0.00x and $0.00 here, which the panel qualifies with the excluded notional
;; rather than folding isolated exposure into the headline figures.
(def ^:private unified-all-isolated
  {:label "unified / every position isolated"
   :mode :unified
   :prices usd-prices
   :spot [{:coin "USDC" :total "1000.0"}]
   :dexes [{:dex nil
            :collateral "USDC"
            :coin "BTC"
            :state (clearinghouse-state
                    {:margin (summary "0.0" "1700.0" "0.0" "0.0")
                     :cross (summary "0.0" "0.0" "0.0" "0.0")
                     :maintenance "0.0"
                     :positions [(position {:coin "BTC"
                                            :kind :isolated
                                            :position-value "700.0"
                                            :margin-used "233.0"
                                            :upnl "5.0"
                                            :max-leverage 40})
                                 (position {:coin "PUMP"
                                            :kind :isolated
                                            :position-value "1000.0"
                                            :margin-used "500.0"
                                            :upnl "-8.0"
                                            :max-leverage 10})]})}]
   :divergences
   {:unified-account-ratio
    {:venue 0.0
     :ours nil
     :reason (str "A portfolio-liquidation ratio describes nothing on a book that "
                  "is entirely isolated -- each position liquidates alone and "
                  "cannot take the portfolio with it. The venue prints 0.00%, "
                  "which reads as \"no risk\" over $1,700 of live exposure; we "
                  "print \"--\" and disclose the excluded notional beneath it.")}}})

(def ^:private unified-mixed
  {:label "unified / cross and isolated side by side"
   :mode :unified
   :prices usd-prices
   :spot [{:coin "USDC" :total "1000.0"}]
   :dexes [{:dex nil
            :collateral "USDC"
            :coin "BTC"
            :state (clearinghouse-state
                    {:margin (summary "0.0" "1700.0" "0.0" "0.0")
                     :cross (summary "0.0" "500.0" "0.0" "0.0")
                     :maintenance "30.0"
                     :positions [(position {:coin "BTC"
                                            :kind :cross
                                            :position-value "500.0"
                                            :margin-used "100.0"
                                            :upnl "12.0"})
                                 (position {:coin "PUMP"
                                            :kind :isolated
                                            :position-value "1200.0"
                                            :margin-used "600.0"
                                            :upnl "-30.0"
                                            :max-leverage 10})]})}]})

;; `flx` settles in USDH. The wallet has never traded there, so it has no
;; clearinghouse snapshot for it, but the venue still counts a USDH holding as
;; collateral -- the denominator spans the whole dex catalogue.
(def ^:private unified-untraded-collateral-token
  {:label "unified / USDH collateral from a dex the wallet never traded"
   :mode :unified
   :prices usd-prices
   :spot [{:coin "USDC" :total "1000.0"}
          {:coin "USDH" :total "200.0"}]
   :dexes [{:dex nil
            :collateral "USDC"
            :coin "BTC"
            :state (clearinghouse-state
                    {:margin (summary "0.0" "600.0" "0.0" "0.0")
                     :cross (summary "0.0" "600.0" "0.0" "0.0")
                     :maintenance "12.0"
                     :positions [(position {:coin "BTC"
                                            :kind :cross
                                            :position-value "600.0"
                                            :margin-used "120.0"
                                            :upnl "0.0"})]})}]
   :collateral-only-dexes [{:dex "flx" :collateral "USDH" :coin "GOLD"}]})

(def ^:private degenerate-no-clearinghouse
  {:label "degenerate / no clearinghouse state and no spot state"
   :mode :classic
   :prices usd-prices
   :spot []
   :spot-state? false
   :dexes []
   :divergences
   (into (zipmap [:account-value :spot :perps :balance :unrealized-pnl :maintenance-margin]
                 (repeat {:venue 0.0 :ours nil :reason no-data-reason}))
         (flat-quotient-divergences))})

(def ^:private degenerate-missing-summaries
  {:label "degenerate / crossMarginSummary only, no assetPositions key"
   :mode :classic
   :prices usd-prices
   :spot [{:coin "USDC" :total "40.0"}]
   :dexes [{:dex nil
            :collateral "USDC"
            :coin "BTC"
            :state (clearinghouse-state
                    {:cross (summary "800.0" "1600.0" "-800.0" "160.0")
                     :maintenance "40.0"})}]
   :divergences
   {:account-value {:venue 40.0
                    :ours 40.0
                    :reason (str "Spot alone. `marginSummary` is absent, so "
                                 "Perps contributes nothing rather than a zero.")}
    :perps {:venue 0.0 :ours nil :reason no-data-reason}
    :balance {:venue 0.0 :ours nil :reason no-data-reason}
    :unrealized-pnl {:venue 0.0
                     :ours nil
                     :reason (str "No `assetPositions` key at all, which is not "
                                  "the same as a flat book. The venue sums an "
                                  "empty list to zero; we have nothing to sum.")}}})

(def ^:private degenerate-numeric-fields
  {:label "degenerate / numeric wire fields instead of strings"
   :mode :classic
   :prices usd-prices
   :spot [{:coin "USDC" :total 75.0}]
   :dexes [{:dex nil
            :collateral "USDC"
            :coin "BTC"
            :state (clearinghouse-state
                    {:margin (summary 1200.0 2400.0 -1200.0 240.0)
                     :cross (summary 1200.0 2400.0 -1200.0 240.0)
                     :maintenance 60.0
                     :positions [(position {:coin "BTC"
                                            :kind :cross
                                            :position-value 2400.0
                                            :margin-used 240.0
                                            :upnl 90.0})]})}]})

(def descriptions
  [classic-flat
   classic-base-cross-long
   classic-base-cross-short
   classic-named-dex-only
   classic-two-collateral-tokens
   classic-with-isolated
   unified-all-cross
   unified-all-isolated
   unified-mixed
   unified-untraded-collateral-token
   degenerate-no-clearinghouse
   degenerate-missing-summaries
   degenerate-numeric-fields])

(def all
  (mapv build descriptions))

(defn classic
  []
  (filterv #(= :classic (:mode %)) all))

(defn unified
  []
  (filterv #(= :unified (:mode %)) all))
