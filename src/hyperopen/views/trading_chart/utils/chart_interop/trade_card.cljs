(ns hyperopen.views.trading-chart.utils.chart-interop.trade-card
  "DOM for the fill-details card shown when a trade badge on the chart is
  hovered or pinned. Pure construction from one chart-trades group; the
  trade-layer controller owns when and where it shows."
  (:require [clojure.string :as str]))

(def ^:private max-fill-rows 8)

(def ^:private month-names
  ["Jan" "Feb" "Mar" "Apr" "May" "Jun" "Jul" "Aug" "Sep" "Oct" "Nov" "Dec"])

(defn- pad2 [n]
  (if (< n 10) (str "0" n) (str n)))

(defn format-utc-time
  "13:04:05 (UTC) for a millisecond timestamp."
  [time-ms]
  (let [d (js/Date. time-ms)]
    (str (pad2 (.getUTCHours d)) ":" (pad2 (.getUTCMinutes d)) ":" (pad2 (.getUTCSeconds d)))))

(defn format-candle-label
  "`Oct 18, 2026` for a daily-or-longer candle open (UTC midnight), else
  `Oct 18, 2026 14:00 UTC`."
  [time-sec]
  (let [d (js/Date. (* 1000 time-sec))
        date (str (nth month-names (.getUTCMonth d)) " " (.getUTCDate d) ", " (.getUTCFullYear d))]
    (if (and (zero? (.getUTCHours d)) (zero? (.getUTCMinutes d)))
      date
      (str date " " (pad2 (.getUTCHours d)) ":" (pad2 (.getUTCMinutes d)) " UTC"))))

(defn format-size
  "Up to 6 decimals, trailing zeros trimmed, thousands grouped."
  [value]
  (if (and (number? value) (js/isFinite value))
    (.toLocaleString value "en-US" #js {:maximumFractionDigits 6})
    "—"))

(defn- format-usd
  [value signed?]
  (if (and (number? value) (js/isFinite value))
    (let [abs-text (.toLocaleString (js/Math.abs value) "en-US"
                                    #js {:style "currency" :currency "USD"
                                         :minimumFractionDigits 2 :maximumFractionDigits 2})]
      (cond
        (and signed? (pos? value)) (str "+" abs-text)
        (neg? value) (str "-" abs-text)
        :else abs-text))
    "—"))

(defn- format-fee
  [fees fee-token]
  (cond
    (= :mixed fee-token) "Mixed tokens"
    (and (string? fee-token) (not= "USDC" (str/upper-case fee-token)))
    (str (format-size fees) " " fee-token)
    :else (format-usd fees false)))

(defn title-text
  [{:keys [side-kind sign size]} coin-label]
  (str (case side-kind
         :buy "Bought "
         :sell "Sold "
         (if (= 1 sign) "Liquidated (buy) " "Liquidated (sell) "))
       (format-size size)
       (when (seq coin-label) (str " " coin-label))))

(defn position-text
  [position perp? coin-label]
  (cond
    (not (and (number? position) (js/isFinite position))) "—"
    (not perp?) (str (format-size position) (when (seq coin-label) (str " " coin-label)))
    (zero? position) "Flat"
    :else (str (if (pos? position) "Long " "Short ")
               (format-size (js/Math.abs position))
               (when (seq coin-label) (str " " coin-label)))))

(defn- el
  [^js doc tag classes & children]
  (let [node (.createElement doc tag)]
    (when (seq classes)
      (set! (.-className node) (str/join " " classes)))
    (doseq [child children]
      (cond
        (string? child) (.appendChild node (.createTextNode doc child))
        (some? child) (.appendChild node child)))
    node))

(defn- stat
  [doc label value]
  (el doc "div" ["flex" "flex-col" "gap-0.5" "min-w-0"]
      (el doc "span" ["text-xs" "text-ho-text-muted"] label)
      (el doc "span" ["font-semibold" "text-ho-text" "truncate"] value)))

(defn- side-tone-classes
  [{:keys [side-kind]}]
  (case side-kind
    :buy ["bg-trading-green"]
    :sell ["bg-trading-red"]
    ["bg-ho-warn"]))

(defn build-card-content
  "Returns the card's child DOM nodes for `group`, top to bottom.
  opts: {:coin-label :perp? :pinned? :format-price :on-open-history}"
  [^js doc group {:keys [coin-label perp? pinned? format-price on-open-history]}]
  (let [format-price* (or format-price #(.toLocaleString % "en-US" #js {:maximumFractionDigits 6}))
        {:keys [side-kind count avg-price notional fees fee-token closed-pnl position-after time fills]} group
        badge (el doc "span" (into ["inline-flex" "items-center" "justify-center" "w-5" "h-5"
                                    "rounded-full" "text-xs" "font-bold" "text-ho-bg"]
                                   (side-tone-classes group))
                  (case side-kind :buy "B" :sell "S" "L"))
        header (el doc "div" ["flex" "items-center" "gap-2" "min-w-0"]
                   badge
                   (el doc "div" ["flex" "flex-col" "min-w-0"]
                       (el doc "span" ["text-sm" "font-semibold" "text-ho-text"]
                           (title-text group coin-label))
                       (el doc "span" ["text-ho-text-muted"] (format-candle-label time))))
        stats (el doc "div" ["grid" "grid-cols-3" "gap-2"]
                  (stat doc "Avg price" (format-price* avg-price))
                  (stat doc "Notional" (format-usd notional false))
                  (stat doc "Fees" (format-fee fees fee-token)))
        table (el doc "div" ["flex" "flex-col" "gap-1" "rounded-md" "bg-ho-surface-raised" "p-2"]
                  (el doc "div" ["grid" "grid-cols-[4.5rem_1fr_1fr_3rem]" "gap-1.5" "text-xs" "text-ho-text-muted"]
                      (el doc "span" [] "Time (UTC)")
                      (el doc "span" [] "Size")
                      (el doc "span" [] "Price")
                      (el doc "span" [] "Type")))
        _ (doseq [{:keys [time-ms size price maker?]} (take max-fill-rows fills)]
            (.appendChild table
                          (el doc "div" ["grid" "grid-cols-[4.5rem_1fr_1fr_3rem]" "gap-1.5" "text-xs"]
                              (el doc "span" ["text-ho-text-muted"] (format-utc-time time-ms))
                              (el doc "span" ["text-ho-text"] (format-size size))
                              (el doc "span" ["text-ho-text"] (format-price* price))
                              (el doc "span" ["text-ho-text-muted"] (if maker? "Maker" "Taker")))))
        _ (when (> count max-fill-rows)
            (.appendChild table (el doc "span" ["text-xs" "text-ho-text-muted"]
                                    (str "+" (- count max-fill-rows) " more fills"))))
        footer (el doc "div" ["flex" "flex-col" "gap-1" "border-t" "border-ho-border" "pt-2"]
                   (el doc "div" ["flex" "justify-between" "gap-3"]
                       (el doc "span" ["text-ho-text-muted"] (if perp? "Position after" "Balance after"))
                       (el doc "span" ["text-ho-text"] (position-text position-after perp? coin-label)))
                   (when (and (number? closed-pnl) (not (zero? closed-pnl)))
                     (el doc "div" ["flex" "justify-between" "gap-3"]
                         (el doc "span" ["text-ho-text-muted"] "Realized PnL")
                         (el doc "span" ["font-semibold" (if (pos? closed-pnl) "text-trading-green" "text-trading-red")]
                             (format-usd closed-pnl true)))))
        link (when (fn? on-open-history)
               (let [button (el doc "button" ["self-start" "text-xs" "text-primary" "hover:underline"
                                              "focus:outline-none" "focus-visible:ring-2"
                                              "focus-visible:ring-slate-500/70"]
                                "Open in Trade History →")]
                 (set! (.-type button) "button")
                 (.setAttribute button "data-role" "chart-trade-card-history-link")
                 (.addEventListener button "click" (fn [e]
                                                     (.stopPropagation e)
                                                     (on-open-history)))
                 button))
        hint (when-not pinned?
               (el doc "span" ["text-xs" "text-ho-text-dim"] "Click the marker to pin"))]
    (filterv some? [header stats table footer link hint])))
