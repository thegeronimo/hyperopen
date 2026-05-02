(ns hyperopen.views.l2-orderbook.model
  (:require [clojure.string :as str]
            [hyperopen.domain.market.instrument :as instrument]
            [hyperopen.websocket.orderbook-policy :as orderbook-policy]
            [hyperopen.websocket.trades :as trades]
            [hyperopen.utils.formatting :as fmt]))

(defn parse-number [value]
  (cond
    (number? value) value
    (string? value) (let [n (js/parseFloat value)]
                      (when-not (js/isNaN n) n))
    :else nil))

(def orderbook-tabs
  #{:orderbook :trades})

(def ^:private max-render-levels-per-side orderbook-policy/default-max-render-levels-per-side)
(def ^:private desktop-breakpoint-px 1024)
(defn normalize-orderbook-tab [tab]
  (let [tab* (cond
               (keyword? tab) tab
               (string? tab) (keyword tab)
               :else :orderbook)]
    (if (contains? orderbook-tabs tab*) tab* :orderbook)))

(defn- viewport-width-px [viewport-width]
  (let [width (or (when (number? viewport-width) viewport-width)
                  (some-> js/globalThis .-innerWidth))]
    (if (number? width)
      width
      desktop-breakpoint-px)))

(defn desktop-orderbook-layout? [layout]
  (if (boolean? (:desktop-layout? layout))
    (:desktop-layout? layout)
    (>= (viewport-width-px (:viewport-width layout))
        desktop-breakpoint-px)))

(defn format-price
  ([price] (orderbook-policy/format-price price))
  ([price raw] (orderbook-policy/format-price price raw)))

(defn format-percent [value decimals]
  (orderbook-policy/format-percent value decimals))

(defn format-total [total & {:keys [decimals] :or {decimals 0}}]
  (orderbook-policy/format-total total :decimals decimals))

(defn calculate-spread [best-bid best-ask]
  (orderbook-policy/calculate-spread best-bid best-ask))

(defn calculate-cumulative-totals [orders]
  (orderbook-policy/calculate-cumulative-totals orders))

(defn normalize-size-unit [size-unit]
  (if (= size-unit :quote) :quote :base))

(defn base-symbol-from-coin [coin]
  (instrument/base-symbol-from-value coin))

(defn quote-symbol-from-coin [coin]
  (or (instrument/quote-symbol-from-value coin) "USDC"))

(defn resolve-base-symbol [coin market]
  (instrument/resolve-base-symbol coin market "Asset"))

(defn resolve-quote-symbol [coin market]
  (instrument/resolve-quote-symbol coin market "USDC"))

(defn infer-market-type [coin market]
  (instrument/infer-market-type coin market))

(defn midpoint-price [best-bid best-ask]
  (let [bid (some-> best-bid :px parse-number)
        ask (some-> best-ask :px parse-number)]
    (when (and bid ask (> bid 0) (> ask 0))
      (/ (+ bid ask) 2))))

(defn resolve-reference-price [best-bid best-ask market]
  (or (midpoint-price best-bid best-ask)
      (parse-number (:mark market))
      1))

(defn trade-time->ms [value]
  (when-some [n (parse-number value)]
    (if (< n 1000000000000) (* n 1000) n)))

(defn format-trade-time [value]
  (when-let [time-ms (trade-time->ms value)]
    (fmt/format-local-time-hh-mm-ss time-ms)))

(defn trade-side->price-class [side]
  (case (some-> side str str/upper-case)
    "B" "text-green-400"
    "A" "text-red-400"
    "S" "text-red-400"
    "text-gray-100"))

(defn trade-matches-coin? [trade coin]
  (let [trade-coin (or (:coin trade) (:symbol trade) (:asset trade))]
    (if (seq coin)
      (= trade-coin coin)
      true)))

(defn- first-trade-value
  [trade keys]
  (some #(get trade %) keys))

(defn- trade-price-raw
  [trade]
  (first-trade-value trade [:px :price :p]))

(defn- trade-size-raw
  [trade]
  (first-trade-value trade [:sz :size :s]))

(defn- trade-time-raw
  [trade]
  (first-trade-value trade [:time :t :ts :timestamp]))

(defn- trade-coin
  [trade]
  (first-trade-value trade [:coin :symbol :asset]))

(defn- trade-id
  [trade]
  (first-trade-value trade [:tid :id]))

(defn normalize-trade [trade]
  (let [price-raw (trade-price-raw trade)
        size-raw (trade-size-raw trade)]
    {:coin (trade-coin trade)
     :price (parse-number price-raw)
     :price-raw price-raw
     :size (or (parse-number size-raw) 0)
     :size-raw size-raw
     :side (or (:side trade) (:dir trade))
     :time-ms (trade-time->ms (trade-time-raw trade))
     :tid (trade-id trade)}))

(defn format-trade-size [trade]
  (let [raw-size (:size-raw trade)]
    (if (string? raw-size)
      raw-size
      (or (format-total (:size trade) :decimals 8) "0"))))

(defn recent-trades-for-coin [coin]
  (let [cached-trades (trades/get-recent-trades-for-coin coin)]
    (if (seq cached-trades)
      (take 100 cached-trades)
      (->> (trades/get-recent-trades)
           (filter #(trade-matches-coin? % coin))
           (map normalize-trade)
           (sort-by (fn [trade] (or (:time-ms trade) 0)) >)
           (take 100)))))

(defn order-size-for-unit [order size-unit]
  (orderbook-policy/order-size-for-unit order size-unit))

(defn order-total-for-unit [order size-unit]
  (orderbook-policy/order-total-for-unit order size-unit))

(defn get-max-cumulative-total [orders size-unit]
  (orderbook-policy/get-max-cumulative-total orders size-unit))

(defn format-order-size [order size-unit]
  (orderbook-policy/format-order-size order size-unit))

(defn format-order-total [order size-unit]
  (orderbook-policy/format-order-total order size-unit))

(defn cumulative-bar-width [cum-size max-cum-size]
  (orderbook-policy/cumulative-bar-width cum-size max-cum-size))

(defn- strip-cumulative-totals [levels]
  (mapv #(dissoc % :cum-size :cum-value) (or levels [])))

(defn- fallback-render-snapshot [orderbook-data visible-branch]
  (orderbook-policy/build-render-snapshot (:bids orderbook-data)
                                          (:asks orderbook-data)
                                          max-render-levels-per-side
                                          {:visible-branch visible-branch}))

(defn- render-branch-keys [visible-branch]
  (case visible-branch
    :mobile [:mobile-pairs]
    :desktop [:desktop-bids :desktop-asks]
    [:desktop-bids :desktop-asks :mobile-pairs]))

(defn- full-render-snapshot? [render visible-branch]
  (and (map? render)
       (every? #(contains? render %)
               (concat (render-branch-keys visible-branch)
                       [:best-bid
                        :best-ask
                        :spread
                        :max-total-by-unit]))))

(defn- legacy-render-snapshot? [render]
  (and (map? render)
       (every? #(contains? render %)
               [:best-bid :best-ask])
       (or (contains? render :display-bids)
           (contains? render :bids-with-totals))
       (or (contains? render :display-asks)
           (contains? render :asks-with-totals))))

(defn- upgrade-legacy-render-snapshot [render visible-branch]
  (let [display-bids (or (:display-bids render)
                         (strip-cumulative-totals (:bids-with-totals render)))
        display-asks (or (:display-asks render)
                         (strip-cumulative-totals (:asks-with-totals render)))
        snapshot (orderbook-policy/build-render-snapshot display-bids
                                                         display-asks
                                                         max-render-levels-per-side
                                                         {:visible-branch visible-branch})]
    (assoc snapshot
           :best-bid (or (:best-bid render) (:best-bid snapshot))
           :best-ask (or (:best-ask render) (:best-ask snapshot)))))

(defn render-snapshot [orderbook-data visible-branch]
  (let [render (:render orderbook-data)]
    (cond
      (full-render-snapshot? render visible-branch) render
      (legacy-render-snapshot? render) (upgrade-legacy-render-snapshot render visible-branch)
      :else (fallback-render-snapshot orderbook-data visible-branch))))
