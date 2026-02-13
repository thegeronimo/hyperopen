---
owner: product
status: supporting
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# Trading Chart Component - Product Requirements Document

## Project Overview

**Product**: Trading Chart Component for Hyperopen  
**Technology Stack**: ClojureScript + Replicant + Lightweight.Charts  
**Target**: TradingView-style candlestick chart with real-time data integration  
**Timeline**: 3-4 weeks (phased implementation)

## Problem Statement

Currently, Hyperopen displays real-time price data in tables and order books but lacks visual price charting capabilities. Users need to see price movements, trends, and historical data through interactive candlestick charts to make informed trading decisions.

## Product Goals

### Primary Goals

1. **Visual Price Analysis**: Enable users to analyze price movements through candlestick charts
2. **Real-time Updates**: Display live price changes as they happen
3. **Multiple Timeframes**: Support various time intervals (1m, 5m, 15m, 1h, 4h, 1d)
4. **Volume Analysis**: Show trading volume alongside price data
5. **Professional UI**: Match TradingView-style interface standards

### Secondary Goals

1. **Technical Indicators**: Add basic indicators (MA, RSI) in future phases
2. **Drawing Tools**: Enable trend line drawing capabilities
3. **Multi-asset Support**: Switch between different trading pairs
4. **Export Capabilities**: Allow chart screenshots/exports

## Success Metrics

- **Performance**: Chart renders within 500ms for 1000+ candles
- **Real-time Latency**: Price updates appear within 100ms of WebSocket data
- **User Experience**: Smooth interactions (zoom, pan, timeframe switching)
- **Data Accuracy**: 100% consistency between chart and live market data

## Technical Requirements

### Architecture Decision: Lightweight.Charts

**Rationale**:

- Lightweight and performant for financial data
- Native TypeScript support with excellent ClojureScript interop
- Matches TradingView interface exactly
- Built specifically for trading applications
- Active development and community support

### Dependencies

```json
{
  "lightweight-charts": "^5.0.8"
}
```

## Phase 1: Foundation & Static Chart (Week 1)

### 1.1 Component Structure

**Deliverable**: Basic chart component with static data

**Files to Create**:

```
src/hyperopen/views/trading_chart/
├── core.cljs                    ; Main chart component
├── header.cljs                  ; Chart header with price info
├── timeframe_selector.cljs      ; Timeframe controls
├── price_chart.cljs             ; Main candlestick chart
├── volume_chart.cljs            ; Volume chart component
├── indicators/
│   ├── moving_averages.cljs     ; MA indicators
│   ├── rsi.cljs                 ; RSI indicator
│   └── macd.cljs                ; MACD indicator
├── drawing_tools/
│   ├── trend_lines.cljs         ; Trend line drawing
│   ├── fibonacci.cljs           ; Fibonacci retracements
│   └── shapes.cljs              ; Geometric shapes
├── plugins/
│   ├── crosshair.cljs           ; Crosshair functionality
│   ├── zoom_pan.cljs            ; Zoom and pan controls
│   └── export.cljs              ; Chart export/screenshot
└── utils/
    ├── chart_interop.cljs       ; Lightweight.Charts JS interop
    ├── data_processing.cljs     ; Candle data transformations
    └── calculations.cljs        ; Technical analysis calculations
```

**Component Hierarchy**:

```clojure
(defn trading-chart-view [props]
  [:div.chart-container
   (header/chart-header props)
   (timeframe-selector/timeframe-selector props)
   (price-chart/price-chart-canvas props)
   (volume-chart/volume-chart-canvas props)])
```

### 1.2 Modular Architecture Benefits

**Component Organization**:

- **Separation of Concerns**: Each component handles specific functionality
- **Reusability**: Indicators and drawing tools can be reused across different chart instances
- **Maintainability**: Easier to debug and modify individual features
- **Extensibility**: New indicators and tools can be added without affecting core chart
- **Testing**: Individual components can be unit tested in isolation
- **Performance**: Lazy loading of advanced features (indicators, drawing tools)

**Namespace Structure**:

```clojure
;; Main chart component
(ns hyperopen.views.trading-chart.core
  (:require [hyperopen.views.trading-chart.header :as header]
            [hyperopen.views.trading-chart.timeframe-selector :as tf-selector]
            [hyperopen.views.trading-chart.price-chart :as price-chart]
            [hyperopen.views.trading-chart.volume-chart :as volume-chart]))

;; Individual components
(ns hyperopen.views.trading-chart.header)
(ns hyperopen.views.trading-chart.timeframe-selector)
(ns hyperopen.views.trading-chart.price-chart)
(ns hyperopen.views.trading-chart.volume-chart)

;; Technical analysis components
(ns hyperopen.views.trading-chart.indicators.moving-averages)
(ns hyperopen.views.trading-chart.indicators.rsi)
(ns hyperopen.views.trading-chart.indicators.macd)

;; Drawing tools
(ns hyperopen.views.trading-chart.drawing-tools.trend-lines)
(ns hyperopen.views.trading-chart.drawing-tools.fibonacci)
(ns hyperopen.views.trading-chart.drawing-tools.shapes)

;; Utility modules
(ns hyperopen.views.trading-chart.utils.chart-interop)
(ns hyperopen.views.trading-chart.utils.data-processing)
(ns hyperopen.views.trading-chart.utils.calculations)
```

### 1.3 Static Mock Data

**Objective**: Render charts with realistic sample data

**Mock Data Structure**:

```clojure
(def sample-candles
  [{:time 1640995200 :open 46000 :high 47500 :low 45800 :close 47200 :volume 1250000}
   {:time 1641081600 :open 47200 :high 48000 :low 46900 :close 47800 :volume 980000}
   ;; 100+ sample candles covering different market conditions
   ])
```

### 1.4 Chart Integration

**Technical Implementation**:

- Create chart instance in `componentDidMount` equivalent
- Handle chart resizing and responsive behavior
- Implement proper cleanup on component unmount
- Set up dark theme matching existing UI

### 1.5 Basic UI Components

**Header Component**:

```clojure
(defn chart-header [{:keys [coin price change change-pct]}]
  [:div.flex.items-center.justify-between.p-4
   [:div.flex.items-center.space-x-4
    [:img.w-8.h-8 {:src (str "https://app.hyperliquid.xyz/coins/" coin ".svg")}]
    [:h2.text-xl.font-bold coin]
    [:span.text-2xl.font-mono price]
    [:span {:class (if (pos? change) "text-green-500" "text-red-500")}
     (str (if (pos? change) "+" "") change " (" change-pct "%)")]]
   (timeframe-selector)])
```

**Timeframe Selector**:

```clojure
(defn timeframe-selector [{:keys [current-timeframe on-change]}]
  [:div.flex.space-x-2
   (for [tf ["5y" "1y" "6m" "3m" "1m" "5d" "1d"]]
     [:button.btn.btn-sm
      {:class (when (= tf current-timeframe) ["btn-primary"])
       :on {:click [[:actions/change-timeframe tf]]}}
      tf])])
```

## Phase 2: Data Pipeline Integration (Week 2)

### 2.1 WebSocket Candle Data

**Objective**: Replace mock data with real market data

**New WebSocket Module**:

```clojure
;; src/hyperopen/websocket/candles.cljs
(ns hyperopen.websocket.candles
  (:require [hyperopen.websocket.client :as ws-client]))
```

(defn subscribe-candles! [coin timeframe]
(let [subscription {:method "subscribe"
:subscription {:type "candle"
:coin coin
:interval timeframe}}]
(ws-client/send-message! subscription)))

````

### 2.2 Historical Data API

**Endpoint Discovery**: Research Hyperliquid API for historical candle data

- Check existing `webdata2` functionality for historical data
- Implement fallback to REST API if WebSocket doesn't provide history
- Handle rate limiting and caching strategies

**API Integration**:

```clojure
;; In src/hyperopen/api.cljs
(defn fetch-candle-history! [store coin timeframe limit]
  (-> (js/fetch (str "https://api.hyperliquid.xyz/info"
                     "?type=candleSnapshot"
                     "&req=" (js/JSON.stringify #js {:coin coin
                                                     :interval timeframe
                                                     :startTime (- (js/Date.now) (* limit 60000))})))
      (.then #(.json %))
      (.then #(process-candle-data! store coin timeframe %))))
````

### 2.3 State Management Updates

**Store Structure Enhancement**:

```clojure
;; Update store in core.cljs
(defonce store (atom {
  ;; ... existing state
  :charts {
    :btc {:candles {:1m [] :5m [] :15m [] :1h [] :4h [] :1d []}
          :current-timeframe :1h
          :loading false
          :last-update nil}
    :eth {;; same structure}
    }
  :chart-settings {:visible true
                   :height 400
                   :theme :dark}}))
```

### 2.4 Data Processing Pipeline

**Candle Data Transformation**:

```clojure
(defn process-candle-data [raw-data]
  (->> raw-data
       (map #(hash-map :time (/ (:t %) 1000)  ; Convert to seconds
                       :open (js/parseFloat (:o %))
                       :high (js/parseFloat (:h %))
                       :low (js/parseFloat (:l %))
                       :close (js/parseFloat (:c %))
                       :volume (js/parseFloat (:v %))))
       (sort-by :time)))
```

## Phase 3: Real-time Integration (Week 3)

### 3.1 Live Price Updates

**Real-time Candle Updates**:

- Connect existing `activeAssetCtx` price updates to chart
- Implement efficient last-candle updates without full re-render
- Handle WebSocket reconnection scenarios

**Implementation**:

```clojure
(defn update-current-candle! [store coin new-price volume]
  (let [current-time (js/Math.floor (/ (js/Date.now) 60000))  ; 1-minute buckets
        timeframe (get-in @store [:charts coin :current-timeframe])]
    (swap! store update-in [:charts coin :candles timeframe]
           #(update-last-candle % current-time new-price volume))))
```

### 3.2 Chart Performance Optimization

**Efficient Updates**:

- Implement chart update batching to avoid excessive re-renders
- Use Lightweight.Charts' `update()` method for incremental updates
- Optimize for 60fps performance during rapid price changes

**Memory Management**:

- Limit candle history to prevent memory leaks (max 5000 candles per timeframe)
- Implement sliding window for real-time data
- Clean up chart instances properly

### 3.3 User Interaction Handlers

**Chart Events**:

```clojure
(defn setup-chart-events! [chart store coin]
  ;; Handle timeframe changes
  (.subscribeCrosshairMove chart
    #(update-crosshair-data! store %))

  ;; Handle zoom/pan events
  (.subscribeVisibleTimeRangeChange chart
    #(save-viewport-state! store coin %)))
```

## Phase 4: Polish & Advanced Features (Week 4)

### 4.1 Enhanced UI/UX

**Professional Trading Interface**:

- Implement proper loading states during data fetching
- Add error handling for network failures
- Smooth animations for timeframe switching
- Responsive design for different screen sizes

**Accessibility**:

- Keyboard navigation support
- Screen reader compatibility
- High contrast mode option

### 4.2 Advanced Chart Features

**Volume Analysis**:

```clojure
(defn volume-chart-component [{:keys [volume-data]}]
  ;; Separate volume chart below main price chart
  ;; Color-coded volume bars (green/red based on price direction)
  )
```

**Price Precision**:

- Dynamic decimal places based on asset price range
- Proper price formatting for different assets
- Currency symbol handling

### 4.3 Integration with Existing Components

**Seamless Integration**:

- Chart responds to asset selection from existing asset selector
- Sync with order book selected asset
- Maintain state when switching between chart and other views

## Technical Implementation Details

### Chart Configuration

```javascript
// Chart setup in chart_interop.cljs
const chartOptions = {
  layout: {
    backgroundColor: "#1f2937",
    textColor: "#f3f4f6",
  },
  grid: {
    vertLines: { color: "#374151" },
    horzLines: { color: "#374151" },
  },
  crosshair: { mode: 1 },
  timeScale: {
    borderColor: "#4b5563",
    timeVisible: true,
    secondsVisible: false,
  },
};
```

### WebSocket Message Handling

```clojure
(defn handle-candle-message! [store message]
  (when (= (:channel message) "candle")
    (let [{:keys [coin interval data]} (:data message)]
      (update-chart-data! store coin interval data))))
```

### Error Handling Strategy

1. **Network Failures**: Retry with exponential backoff
2. **Data Corruption**: Validate all incoming data before chart updates
3. **Chart Errors**: Graceful fallback to previous valid state
4. **Performance Issues**: Automatic data reduction for large datasets

## Testing Strategy

### Unit Tests

- Chart data transformation functions
- WebSocket message processing
- State management updates
- Price calculation utilities

### Integration Tests

- Chart component rendering with various data states
- WebSocket data flow end-to-end
- User interaction event handling
- Performance benchmarks

### Manual Testing Scenarios

1. **Real-time Updates**: Verify live price updates appear correctly
2. **Timeframe Switching**: Smooth transitions between timeframes
3. **Asset Switching**: Chart updates when selecting different assets
4. **Network Issues**: Graceful handling of connection problems
5. **Large Datasets**: Performance with extended historical data

## Risk Mitigation

### Technical Risks

1. **Performance Degradation**: Implement data virtualization for large datasets
2. **Memory Leaks**: Strict cleanup procedures and monitoring
3. **WebSocket Reliability**: Implement robust reconnection logic
4. **Data Inconsistency**: Add validation layers and fallback mechanisms

### User Experience Risks

1. **Learning Curve**: Provide helpful tooltips and interactive guides
2. **Information Overload**: Clean, focused interface design
3. **Mobile Experience**: Responsive design with touch-friendly controls

## Success Criteria

### MVP Release Criteria

- [ ] Chart renders with real market data
- [ ] Real-time price updates work correctly
- [ ] Multiple timeframes functional
- [ ] Volume chart displays properly
- [ ] Performance meets 500ms render target
- [ ] Integration with existing asset selection

### Full Release Criteria

- [ ] All planned timeframes implemented
- [ ] Comprehensive error handling
- [ ] Mobile responsive design
- [ ] Accessibility compliance
- [ ] Performance optimization complete
- [ ] User testing feedback incorporated

## Future Roadmap

### Phase 5: Technical Indicators (Future)

- Moving averages (SMA, EMA)
- RSI, MACD, Bollinger Bands
- Custom indicator framework

### Phase 6: Advanced Features (Future)

- Drawing tools (trend lines, support/resistance)
- Chart patterns recognition
- Price alerts integration
- Multi-chart layouts

### Phase 7: Analytics (Future)

- Chart interaction analytics
- Performance monitoring
- User behavior insights
- A/B testing framework

## Conclusion

This PRD provides a comprehensive roadmap for implementing a professional-grade trading chart component that matches TradingView standards while integrating seamlessly with the existing Hyperopen architecture. The phased approach ensures steady progress with clear milestones and risk mitigation strategies.

The use of Lightweight.Charts provides the optimal balance of performance, features, and maintainability for this ClojureScript + Replicant application.
