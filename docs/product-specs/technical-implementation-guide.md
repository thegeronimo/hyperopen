---
owner: product
status: supporting
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# Technical Implementation Guide - Hyperliquid Interface

## Data Flow Architecture with Nexus

### Philosophy

The Hyperopen trading interface uses **Nexus** for action-based state management, following a strict separation between pure components and side effects. This architecture ensures:

- **Pure Components**: Components only receive state and declare what actions should happen
- **Predictable State Changes**: All state mutations flow through registered actions and effects
- **Testability**: Actions are pure functions, effects are isolated side effects
- **Data-Driven**: Event handlers declare data structures, not function calls

### Execution Flow

When a user interacts with the interface (e.g., clicking a button), the following execution path occurs:

```
┌─────────────────┐
│   User Action   │
│ (Button Click)  │
│ [[:actions/xyz]]│
└─────────┬───────┘
          │
          ▼
┌─────────────────┐
│   Replicant     │
│  set-dispatch!  │
│  calls nexus    │
└─────────┬───────┘
          │
          ▼
┌─────────────────┐
│     Nexus       │
│ Action Handler  │
│ (pure function) │
│ returns effects │
└─────────┬───────┘
          │
          ▼ [[:effects/save path value]]
┌─────────────────┐
│     Nexus       │
│ Effect Handler  │
│ (side effect)   │
│ updates state   │
└─────────┬───────┘
          │
          ▼ (swap! store ...)
┌─────────────────┐
│   Atom Watch    │
│    Triggers     │
│   Re-render     │
└─────────┬───────┘
          │
          ▼
┌─────────────────┐
│   Replicant     │
│   Re-renders    │
│   Components    │
└─────────────────┘
```

### Core Concepts

#### Actions

Pure functions that receive current state and return a vector of effects:

```clojure
(defn increment-count [state]
  [[:effects/save [:count] (inc (:count state))]])
```

#### Effects

Functions that handle side effects (state updates, API calls, etc.):

```clojure
(defn save [_ store path value]
  (swap! store assoc-in path value))
```

#### Component Declaration

Components declare actions in event handlers as data:

```clojure
[:button {:on {:click [[:actions/increment-count]]}} "Click me!"]
```

### Nexus Integration Setup

```clojure
;; Register actions and effects
(nxr/register-action! :actions/increment-count increment-count)
(nxr/register-effect! :effects/save save)
(nxr/register-system->state! deref)

;; Wire up dispatch with Replicant
(r/set-dispatch! #(nxr/dispatch store %1 %2))

;; Auto re-render on state changes
(add-watch store ::render #(r/render target (app-view %4)))
```

### Benefits for Trading Interface

1. **Real-time Data Handling**: WebSocket updates flow through effects, ensuring consistent state
2. **Form Validation**: Order form validation happens in pure actions before effects
3. **Error Handling**: Nexus provides built-in error collection and handling
4. **Performance**: Efficient re-rendering through Replicant's data-driven approach
5. **Testing**: Each action and effect can be tested independently

### Example: Order Placement Flow

```clojure
;; User clicks "Buy" button
[:button {:on {:click [[:actions/place-order :buy]]}}]

;; Action validates and returns effects
(defn place-order [state side]
  (if (valid-order? state side)
    [[:effects/submit-order state side]
     [:effects/update-ui [:order-status] :pending]]
    [[:effects/show-error "Invalid order parameters"]]))

;; Effects handle the actual work
(defn submit-order [ctx store state side]
  ;; API call, WebSocket message, etc.
  )
```

## Component Architecture Overview

```
src/hyperopen/
├── core.cljs                    # Main app entry point
├── components/
│   ├── layout/
│   │   ├── header.cljs         # Navigation header
│   │   ├── main_grid.cljs      # CSS Grid layout
│   │   └── panel_resizer.cljs  # Resizable panels
│   ├── trading/
│   │   ├── pair_info.cljs      # Trading pair display
│   │   ├── order_book.cljs     # Order book component
│   │   ├── order_forms.cljs    # Buy/sell forms
│   │   └── chart.cljs          # Chart integration
│   ├── account/
│   │   ├── balance.cljs        # Account info panel
│   │   └── positions.cljs      # Position display
│   └── tables/
│       ├── data_table.cljs     # Reusable table component
│       ├── positions_table.cljs
│       ├── orders_table.cljs
│       └── history_table.cljs
├── state/
│   ├── atoms.cljs              # Application state atoms
│   ├── websocket.cljs          # WebSocket client
│   └── market_data.cljs        # Market data management
├── utils/
│   ├── formatting.cljs         # Price/number formatting
│   ├── calculations.cljs       # Trading calculations
│   └── constants.cljs          # App constants
└── styles/
    ├── main.css               # Main CSS file
    ├── components.css         # Component styles
    └── themes.css             # Color themes
```

## Phase-by-Phase Technical Details

### Phase 1: Foundation & Layout Structure

#### 1.1 Dependencies to Add (`deps.edn`)

```clojure
{:deps {org.clojure/clojure {:mvn/version "1.11.1"}
        org.clojure/clojurescript {:mvn/version "1.11.60"}
        cjohansen/replicant {:mvn/version "1.0.0"}
        thheller/shadow-cljs {:mvn/version "2.25.8"}}}
```

#### 1.2 Main Layout Grid (`components/layout/main_grid.cljs`)

```clojure
(ns hyperopen.components.layout.main-grid
  (:require [replicant.dom :as d]))

(defn main-layout [state]
  (d/div {:class "main-layout"}
    ;; Header - fixed top
    (d/header {:class "header-nav"}
      [header-component state])

    ;; Main grid container
    (d/div {:class "trading-grid"}
      ;; Left panel - trading pair info
      (d/div {:class "left-panel"}
        [pair-info-component state])

      ;; Center - chart area
      (d/div {:class "center-panel"}
        [chart-component state])

      ;; Right panel - order book
      (d/div {:class "right-panel"}
        [order-book-component state]
        [order-forms-component state]))

    ;; Bottom panel - data tables
    (d/div {:class "bottom-panel"}
      [data-tables-component state])))
```

#### 1.3 CSS Grid Layout (`styles/main.css`)

```css
.main-layout {
  height: 100vh;
  display: grid;
  grid-template-rows: 60px 1fr auto;
  grid-template-areas:
    "header"
    "trading"
    "tables";
}

.trading-grid {
  display: grid;
  grid-template-columns: 25% 50% 25%;
  grid-template-areas: "left center right";
  gap: 8px;
  padding: 8px;
  min-height: 600px;
}

.left-panel {
  grid-area: left;
}
.center-panel {
  grid-area: center;
}
.right-panel {
  grid-area: right;
}

/* Dark theme variables */
:root {
  --bg-primary: #0b0e11;
  --bg-secondary: #161a1e;
  --text-primary: #ffffff;
  --text-secondary: #8b949e;
  --green: #00d4aa;
  --red: #ff6b6b;
  --border: #30363d;
}
```

### Phase 2: Trading Pair Info Panel

#### 2.1 State Structure (`state/atoms.cljs`)

```clojure
(ns hyperopen.state.atoms
  (:require [clojure.core.async :as async]))

(def app-state
  (atom {:trading-pair {:symbol "PUMP-USD"
                        :price 0.005365
                        :change-24h -13.71
                        :volume-24h 574301484
                        :open-interest 504887555}
         :market-data {:mark-price 0.005365
                       :oracle-price 0.005347
                       :funding-rate 0.0001
                       :next-funding "15:42:47"}
         :account {:equity 0.00
                   :margin-used 0.00
                   :available 0.00}}))
```

#### 2.2 Trading Pair Component (`components/trading/pair_info.cljs`)

```clojure
(ns hyperopen.components.trading.pair-info
  (:require [replicant.dom :as d]
            [hyperopen.utils.formatting :as fmt]))

(defn pair-info-component [state]
  (let [{:keys [symbol price change-24h volume-24h]} (:trading-pair state)]
    (d/div {:class "pair-info-panel"}
      ;; Trading pair header
      (d/div {:class "pair-header"}
        (d/h2 {:class "pair-symbol"} symbol)
        (d/div {:class "pair-controls"}
          (d/button {:class "btn-outline"} "5m")
          (d/button {:class "btn-outline"} "1h")
          (d/button {:class "btn-outline"} "1D")))

      ;; Current price
      (d/div {:class "current-price"}
        (d/span {:class "price-value"} (fmt/format-price price))
        (d/span {:class (str "price-change " (if (> change-24h 0) "positive" "negative"))}
          (str (fmt/format-percentage change-24h) "%")))

      ;; Market stats grid
      (d/div {:class "market-stats"}
        (d/div {:class "stat-row"}
          (d/span {:class "stat-label"} "24h Volume")
          (d/span {:class "stat-value"} (fmt/format-volume volume-24h)))))))
```

### Phase 3: Order Book Component

#### 3.1 Order Book State & Logic (`components/trading/order_book.cljs`)

```clojure
(ns hyperopen.components.trading.order-book
  (:require [replicant.dom :as d]
            [hyperopen.utils.formatting :as fmt]))

(defn generate-mock-order-book []
  {:bids [[0.005365 2333085]
          [0.005364 574537]
          [0.005363 3584291]]
   :asks [[0.005366 513244]
          [0.005367 3457665]
          [0.005368 2340880]]})

(defn calculate-depth [orders total-volume]
  (map (fn [[price size]]
         (let [depth-pct (* (/ size total-volume) 100)]
           [price size depth-pct]))
       orders))

(defn order-book-row [side [price size depth-pct]]
  (d/div {:class (str "order-row " (name side))}
    ;; Depth visualization background
    (d/div {:class "depth-bar"
            :style {:width (str depth-pct "%")
                    :background-color (if (= side :bid) "rgba(0, 212, 170, 0.1)"
                                                       "rgba(255, 107, 107, 0.1)")}}

    ;; Order data
    (d/div {:class "order-data"}
      (d/span {:class "order-size"} (fmt/format-number size))
      (d/span {:class "order-price"} (fmt/format-price price))))))

(defn order-book-component [state]
  (let [{:keys [bids asks]} (generate-mock-order-book)
        total-volume (+ (reduce + (map second bids))
                       (reduce + (map second asks)))
        bids-with-depth (calculate-depth bids total-volume)
        asks-with-depth (calculate-depth asks total-volume)]

    (d/div {:class "order-book"}
      ;; Header
      (d/div {:class "order-book-header"}
        (d/span "Size")
        (d/span "Price (USD)")
        (d/span "Size"))

      ;; Asks (sell orders) - displayed top to bottom
      (d/div {:class "asks-section"}
        (map #(order-book-row :ask %) (reverse asks-with-depth)))

      ;; Spread
      (d/div {:class "spread-section"}
        (d/span {:class "spread-value"}
          (str "Spread: " (fmt/format-price (- (ffirst asks) (ffirst bids))))))

      ;; Bids (buy orders)
      (d/div {:class "bids-section"}
        (map #(order-book-row :bid %) bids-with-depth)))))
```

### Phase 4: Trading Forms

#### 4.1 Order Form Component (`components/trading/order_forms.cljs`)

```clojure
(ns hyperopen.components.trading.order-forms
  (:require [replicant.dom :as d]
            [hyperopen.utils.calculations :as calc]))

(def form-state (atom {:order-type "Limit"
                       :price ""
                       :size ""
                       :side nil}))

(defn calculate-order-value [price size]
  (* (js/parseFloat price) (js/parseFloat size)))

(defn size-percentage-buttons [available-balance]
  (d/div {:class "size-buttons"}
    (for [pct [25 50 75 100]]
      (d/button {:class "pct-btn"
                 :on-click #(swap! form-state assoc :size
                              (calc/calculate-size-from-percentage
                                available-balance pct))}
        (str pct "%")))))

(defn order-form [side]
  (let [{:keys [price size order-type]} @form-state
        order-value (when (and price size)
                      (calculate-order-value price size))]

    (d/div {:class (str "order-form " (name side))}
      ;; Order type tabs
      (d/div {:class "order-type-tabs"}
        (for [type ["Market" "Limit" "Stop" "TP/SL"]]
          (d/button {:class (str "tab " (when (= type order-type) "active"))
                     :on-click #(swap! form-state assoc :order-type type)}
            type)))

      ;; Price input (for limit orders)
      (when (= order-type "Limit")
        (d/div {:class "input-group"}
          (d/label "Price")
          (d/input {:type "number"
                    :step "0.000001"
                    :value price
                    :on-change #(swap! form-state assoc :price (.. % -target -value))})))

      ;; Size input
      (d/div {:class "input-group"}
        (d/label "Size")
        (d/input {:type "number"
                  :value size
                  :on-change #(swap! form-state assoc :size (.. % -target -value))})
        (size-percentage-buttons 1000)) ; Mock available balance

      ;; Order value display
      (when order-value
        (d/div {:class "order-summary"}
          (d/span "Order Value: " (str "$" (.toFixed order-value 2)))))

      ;; Submit button
      (d/button {:class (str "submit-btn " (name side))
                 :on-click #(submit-order! side @form-state)}
        (str (if (= side :buy) "Buy / Long" "Sell / Short"))))))

(defn order-forms-component [state]
  (d/div {:class "order-forms"}
    (order-form :buy)
    (order-form :sell)))
```

### Phase 5: Chart Integration

#### 5.1 Chart Component with Lightweight Charts (`components/trading/chart.cljs`)

```clojure
(ns hyperopen.components.trading.chart
  (:require [replicant.dom :as d]))

;; Note: This assumes you've added lightweight-charts via npm
;; npm install lightweight-charts

(defn create-chart [container-id]
  (let [chart (.createChart js/LightweightCharts container-id
                           #js {:width 800
                                :height 400
                                :layout #js {:backgroundColor "#0b0e11"
                                            :textColor "#ffffff"}
                                :grid #js {:vertLines #js {:color "#30363d"}
                                          :horzLines #js {:color "#30363d"}}})
        candlestick-series (.addCandlestickSeries chart)]

    ;; Mock candlestick data
    (.setData candlestick-series
      #js [#js {:time "2024-01-01" :open 0.005300 :high 0.005400 :low 0.005200 :close 0.005365}
          #js {:time "2024-01-02" :open 0.005365 :high 0.005450 :low 0.005300 :close 0.005380}])

    chart))

(defn chart-component [state]
  (d/div {:class "chart-container"}
    ;; Chart controls
    (d/div {:class "chart-controls"}
      (d/div {:class "timeframe-controls"}
        (for [tf ["1m" "5m" "15m" "1h" "4h" "1D" "1W"]]
          (d/button {:class "tf-btn"} tf)))

      (d/div {:class "chart-tools"}
        (d/button {:class "tool-btn"} "Crosshair")
        (d/button {:class "tool-btn"} "Trend Line")
        (d/button {:class "tool-btn"} "Rectangle")))

    ;; Chart canvas
    (d/div {:id "trading-chart"
            :class "chart-canvas"
            :on-mount #(create-chart "trading-chart")})))
```

### Phase 6: Account Information Panel

#### 6.1 Account Panel Component (`components/account/balance.cljs`)

```clojure
(ns hyperopen.components.account.balance
  (:require [replicant.dom :as d]
            [hyperopen.utils.formatting :as fmt]))

(defn account-info-component [state]
  (let [{:keys [equity margin-used available]} (:account state)]
    (d/div {:class "account-panel"}
      ;; Account equity header
      (d/div {:class "account-header"}
        (d/h3 "Account Equity")
        (d/span {:class "equity-value"} (str "$" (fmt/format-number equity))))

      ;; Cross margin details
      (d/div {:class "margin-section"}
        (d/div {:class "margin-row"}
          (d/span "Available to Trade")
          (d/span (str "$" (fmt/format-number available))))

        (d/div {:class "margin-row"}
          (d/span "Current Position")
          (d/span "0 PUMP"))

        (d/div {:class "margin-row"}
          (d/span "Cross Margin Ratio")
          (d/span {:class "margin-ratio"} "0.00%")))

      ;; Quick actions
      (d/div {:class "account-actions"}
        (d/button {:class "btn-primary"} "Deposit")
        (d/button {:class "btn-outline"} "Withdraw")))))
```

### Phase 7: Data Tables Implementation

#### 7.1 Reusable Table Component (`components/tables/data_table.cljs`)

```clojure
(ns hyperopen.components.tables.data-table
  (:require [replicant.dom :as d]))

(defn sortable-header [column current-sort on-sort]
  (d/th {:class "sortable-header"
         :on-click #(on-sort (:key column))}
    (:label column)
    (when (= (:key column) (:column current-sort))
      (d/span {:class "sort-indicator"}
        (if (= (:direction current-sort) :asc) "↑" "↓")))))

(defn data-table [config data]
  (let [{:keys [columns on-sort current-sort]} config]
    (d/table {:class "data-table"}
      ;; Header
      (d/thead
        (d/tr
          (map #(sortable-header % current-sort on-sort) columns)))

      ;; Body
      (d/tbody
        (map (fn [row]
               (d/tr {:key (:id row)}
                 (map (fn [col]
                        (d/td {:class (:class col)}
                          ((:render col identity) (get row (:key col)))))
                      columns)))
             data)))))

(defn positions-table-component [positions]
  (data-table
    {:columns [{:key :symbol :label "Symbol" :render identity}
               {:key :size :label "Size" :render fmt/format-number}
               {:key :entry-price :label "Entry Price" :render fmt/format-price}
               {:key :mark-price :label "Mark Price" :render fmt/format-price}
               {:key :pnl :label "PnL" :render fmt/format-pnl :class "pnl-cell"}]
     :on-sort #(println "Sorting by" %)
     :current-sort {:column :symbol :direction :asc}}
    positions))
```

### Phase 8: WebSocket Integration

#### 8.1 WebSocket Client (`state/websocket.cljs`)

```clojure
(ns hyperopen.state.websocket
  (:require [clojure.core.async :as async]))

(defonce ws-connection (atom nil))
(defonce reconnect-attempts (atom 0))

(def max-reconnect-attempts 5)
(def base-reconnect-delay 1000)

(defn exponential-backoff [attempt]
  (* base-reconnect-delay (Math/pow 2 attempt)))

(defn handle-message [event]
  (let [data (js/JSON.parse (.-data event))
        msg-type (.-type data)]
    (case msg-type
      "orderbook" (update-order-book! (.-data data))
      "ticker" (update-ticker! (.-data data))
      "trade" (update-trade-history! (.-data data))
      (println "Unknown message type:" msg-type))))

(defn connect-websocket! [url]
  (when-let [ws (js/WebSocket. url)]
    (set! (.-onopen ws)
          (fn [_]
            (println "WebSocket connected")
            (reset! reconnect-attempts 0)))

    (set! (.-onmessage ws) handle-message)

    (set! (.-onclose ws)
          (fn [_]
            (println "WebSocket disconnected")
            (when (< @reconnect-attempts max-reconnect-attempts)
              (let [delay (exponential-backoff @reconnect-attempts)]
                (swap! reconnect-attempts inc)
                (js/setTimeout #(connect-websocket! url) delay)))))

    (set! (.-onerror ws)
          (fn [error]
            (println "WebSocket error:" error)))

    (reset! ws-connection ws)))

(defn subscribe-to-symbol! [symbol]
  (when-let [ws @ws-connection]
    (when (= (.-readyState ws) js/WebSocket.OPEN)
      (.send ws (js/JSON.stringify
                  #js {:type "subscribe"
                       :symbol symbol
                       :channels ["orderbook" "ticker" "trades"]})))))
```

## Testing Strategy

### Unit Tests Structure

```
test/hyperopen/
├── components/
│   ├── order_book_test.cljs
│   ├── order_forms_test.cljs
│   └── chart_test.cljs
├── utils/
│   ├── formatting_test.cljs
│   └── calculations_test.cljs
└── state/
    ├── websocket_test.cljs
    └── market_data_test.cljs
```

### Sample Test (`test/hyperopen/utils/formatting_test.cljs`)

```clojure
(ns hyperopen.utils.formatting-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [hyperopen.utils.formatting :as fmt]))

(deftest format-price-test
  (testing "Price formatting with proper precision"
    (is (= "0.005365" (fmt/format-price 0.005365)))
    (is (= "1.234500" (fmt/format-price 1.2345)))
    (is (= "0.000001" (fmt/format-price 0.000001)))))

(deftest format-percentage-test
  (testing "Percentage formatting"
    (is (= "+5.67" (fmt/format-percentage 5.67)))
    (is (= "-13.71" (fmt/format-percentage -13.71)))
    (is (= "0.00" (fmt/format-percentage 0)))))
```

## Performance Considerations

### State Management Optimization

- Use `swap!` instead of `reset!` for atomic updates
- Implement shouldComponentUpdate-like logic in Replicant
- Batch WebSocket updates to prevent excessive re-renders
- Use `requestAnimationFrame` for smooth price updates

### Bundle Size Optimization

- Tree-shake unused chart library features
- Use dynamic imports for heavy components
- Implement code splitting by routes/features
- Minimize CSS bundle size with purging

---

This technical guide provides the specific implementation details needed to build each phase of the Hyperliquid interface. Each component is designed to be modular and testable, following ClojureScript and Replicant best practices.
