---
owner: product
status: supporting
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# Hyperliquid Trading Interface - Product Requirements Document

## Overview

Create an open-source trading interface that matches the functionality and UI of Hyperliquid's trading platform. The implementation will be built using ClojureScript with Replicant for data-driven rendering.

## Target Architecture

- **Frontend**: ClojureScript + Replicant + Shadow-CLJS
- **Styling**: CSS-in-JS or CSS modules for component styling
- **State Management**: Replicant's data-driven approach with atom-based state
- **Real-time Data**: WebSocket connections for live market data
- **Build System**: Shadow-CLJS for development and production builds

## Implementation Phases

### Phase 1: Foundation & Layout Structure (2-3 days)

**Goal**: Establish basic application structure and responsive grid layout

#### 1.1 Project Setup & Dependencies

- [ ] Add CSS framework/utility library (Tailwind CSS or similar)
- [ ] Configure development hot-reloading with proper CSS support
- [ ] Set up component structure in `src/hyperopen/`
- [ ] Create basic state management atoms

#### 1.2 Main Layout Grid

- [ ] Create responsive CSS Grid layout matching Hyperliquid structure:
  - Header navigation bar (fixed top)
  - Left panel for trading pair info (25% width)
  - Center chart area (50% width)
  - Right panel for order book (25% width)
  - Bottom section for data tables (full width, collapsible)
- [ ] Implement panel resizing functionality with drag handles
- [ ] Add dark theme CSS variables and base styling

#### 1.3 Header Navigation Component

- [ ] Create navigation bar with menu items: Trade, Vaults, Portfolio, Staking, Referrals, Leaderboard
- [ ] Add "More" dropdown menu
- [ ] Implement "Connect" button (non-functional placeholder)
- [ ] Style to match Hyperliquid's header design

**Deliverable**: Basic responsive layout with header navigation

---

### Phase 2: Trading Pair Info Panel (1-2 days)

**Goal**: Display trading pair information and basic controls

#### 2.1 Trading Pair Header

- [ ] Create component showing current trading pair (e.g., "PUMP-USD")
- [ ] Display current price with proper formatting
- [ ] Show 24h change percentage with color coding (green/red)
- [ ] Add 24h volume and open interest data
- [ ] Implement price precision formatting

#### 2.2 Market Data Display

- [ ] Create funding/countdown timer display
- [ ] Add market statistics grid (Mark price, Oracle price, etc.)
- [ ] Implement auto-updating price ticker
- [ ] Style to match Hyperliquid's trading pair panel

#### 2.3 Quick Controls

- [ ] Add timeframe selector buttons (5m, 1h, 1D, etc.)
- [ ] Create indicators dropdown placeholder
- [ ] Add drawing tools placeholder buttons

**Deliverable**: Functional trading pair information panel with live price display

---

### Phase 3: Order Book Component (2-3 days)

**Goal**: Real-time order book with bid/ask visualization

#### 3.1 Order Book Data Structure

- [ ] Design data structures for bids and asks
- [ ] Create mock data generator for development
- [ ] Implement price level aggregation logic
- [ ] Add order book state management

#### 3.2 Order Book UI

- [ ] Create bid/ask price level rows with proper alignment
- [ ] Implement depth visualization (background bars)
- [ ] Add hover effects and click interactions
- [ ] Style price levels with green/red color coding
- [ ] Display size, price, and cumulative volume columns

#### 3.3 Order Book Features

- [ ] Add spread calculation and display
- [ ] Implement order book zoom/scale controls
- [ ] Add order grouping by price precision
- [ ] Create auto-center functionality around current price

**Deliverable**: Interactive order book component with depth visualization

---

### Phase 4: Trading Forms (2-3 days)

**Goal**: Buy/sell order entry forms with validation

#### 4.1 Order Form Layout

- [ ] Create side-by-side Buy/Long and Sell/Short forms
- [ ] Implement tabbed interface for order types (Market, Limit, Stop, etc.)
- [ ] Add form validation and error handling
- [ ] Style forms to match Hyperliquid design

#### 4.2 Order Form Controls

- [ ] Price input with increment/decrement buttons
- [ ] Size input with percentage buttons (25%, 50%, 75%, 100%)
- [ ] Order type selector (Market, Limit, Stop Limit, etc.)
- [ ] Time-in-force options
- [ ] Reduce Only checkbox and other advanced options

#### 4.3 Order Form Logic

- [ ] Calculate order value and fees
- [ ] Implement form validation rules
- [ ] Add order preview functionality
- [ ] Create submit button with loading states
- [ ] Add form reset and clear functionality

**Deliverable**: Complete order entry forms with validation and calculations

---

### Phase 5: Chart Integration (3-4 days)

**Goal**: Professional trading chart with technical indicators

#### 5.1 Chart Library Integration

- [ ] Evaluate and integrate chart library (TradingView, Lightweight Charts, or custom)
- [ ] Set up candlestick chart with proper styling
- [ ] Implement real-time price updates
- [ ] Configure chart theming to match dark interface

#### 5.2 Chart Features

- [ ] Add timeframe controls (1m, 5m, 15m, 1h, 4h, 1D, 1W)
- [ ] Implement zoom and pan functionality
- [ ] Add crosshair with price/time display
- [ ] Create price scale and time scale styling

#### 5.3 Technical Indicators

- [ ] Add volume bars at bottom
- [ ] Implement moving averages (SMA, EMA)
- [ ] Add RSI indicator option
- [ ] Create indicator management system
- [ ] Add drawing tools (trend lines, support/resistance)

**Deliverable**: Professional trading chart with technical analysis tools

---

### Phase 6: Account Information Panel (1-2 days)

**Goal**: Display account balance, positions, and margin information

#### 6.1 Account Overview

- [ ] Create account equity display with total value
- [ ] Show available balance and margin usage
- [ ] Add PnL calculation and display
- [ ] Implement margin ratio visualization

#### 6.2 Cross Margin Details

- [ ] Display cross margin ratio with visual indicator
- [ ] Show maintenance margin and liquidation price
- [ ] Add margin health color coding
- [ ] Create margin adjustment controls

#### 6.3 Quick Stats

- [ ] Show open positions count
- [ ] Display daily PnL summary
- [ ] Add account leverage information

**Deliverable**: Complete account information sidebar

---

### Phase 7: Data Tables & Bottom Panel (2-3 days)

**Goal**: Tabbed data tables for positions, orders, and trade history

#### 7.1 Tabbed Interface

- [ ] Create tab navigation: Balances, Positions, Open Orders, TWAP, Trade History, Funding History, Order History
- [ ] Implement tab switching with proper state management
- [ ] Add tab indicators for active data (e.g., open positions count)

#### 7.2 Data Table Components

- [ ] Create reusable sortable table component
- [ ] Implement column resizing and sorting
- [ ] Add row selection and bulk actions
- [ ] Style tables to match Hyperliquid design

#### 7.3 Specific Table Implementations

- [ ] **Positions Table**: Show size, entry price, mark price, PnL, margin
- [ ] **Open Orders Table**: Display order type, size, price, filled amount
- [ ] **Balances Table**: Show asset, total, available, reserved amounts
- [ ] **Trade History**: Display time, side, size, price, fee
- [ ] Add real-time updates for all tables

**Deliverable**: Complete bottom panel with all data tables

---

### Phase 8: WebSocket Integration & Real-time Data (2-3 days)

**Goal**: Connect to live market data feeds

#### 8.1 WebSocket Client

- [ ] Create WebSocket connection manager
- [ ] Implement reconnection logic with exponential backoff
- [ ] Add connection status indicators
- [ ] Handle authentication and subscriptions

#### 8.2 Market Data Streams

- [ ] Subscribe to ticker/price feeds
- [ ] Implement order book streaming updates
- [ ] Add trade history streaming
- [ ] Create chart data streaming

#### 8.3 State Management

- [ ] Update Replicant state atoms with real-time data
- [ ] Implement efficient data diffing for performance
- [ ] Add error handling and fallback mechanisms
- [ ] Create data validation and sanitization

**Deliverable**: Live trading interface with real-time market data

---

### Phase 9: Advanced Features & Polish (2-3 days)

**Goal**: Add advanced trading features and UI polish

#### 9.1 Advanced Order Types

- [ ] Implement stop-loss and take-profit orders
- [ ] Add bracket orders functionality
- [ ] Create OCO (One-Cancels-Other) orders
- [ ] Add trailing stop orders

#### 9.2 Hotkeys & Shortcuts

- [ ] Implement keyboard shortcuts for common actions
- [ ] Add order form hotkeys (buy/sell, cancel all)
- [ ] Create chart navigation shortcuts
- [ ] Add focus management for accessibility

#### 9.3 UI Polish

- [ ] Add loading states and skeletons
- [ ] Implement smooth animations and transitions
- [ ] Add toast notifications for actions
- [ ] Create responsive mobile layout
- [ ] Add help tooltips and onboarding

**Deliverable**: Production-ready trading interface

---

### Phase 10: Testing & Documentation (1-2 days)

**Goal**: Comprehensive testing and documentation

#### 10.1 Testing

- [ ] Add unit tests for core components
- [ ] Create integration tests for trading flows
- [ ] Test WebSocket connection handling
- [ ] Add performance testing for real-time updates

#### 10.2 Documentation

- [ ] Update README with setup instructions
- [ ] Create component documentation
- [ ] Add API integration guide
- [ ] Create deployment documentation

**Deliverable**: Tested and documented application ready for production

---

## Technical Considerations

### Performance Requirements

- [ ] Maintain 60fps during active trading
- [ ] Handle 1000+ order book updates per second
- [ ] Minimize memory usage with efficient data structures
- [ ] Optimize bundle size for fast loading

### Accessibility

- [ ] WCAG 2.1 AA compliance
- [ ] Keyboard navigation support
- [ ] Screen reader compatibility
- [ ] High contrast mode support

### Browser Support

- [ ] Chrome 90+
- [ ] Firefox 88+
- [ ] Safari 14+
- [ ] Edge 90+

## Success Metrics

1. **Functional Parity**: 95% of Hyperliquid features implemented
2. **Performance**: Sub-100ms response time for user interactions
3. **Visual Accuracy**: 90%+ visual similarity to Hyperliquid interface
4. **Usability**: Professional traders can execute trades efficiently
5. **Reliability**: 99.9% uptime for real-time data connections

## Risk Mitigation

- **Scope Creep**: Strict adherence to phase boundaries
- **Performance Issues**: Regular performance testing and optimization
- **API Changes**: Flexible data layer architecture
- **Design Complexity**: Start with simplified versions and iterate
- **Real-time Data**: Robust error handling and fallback mechanisms

---

_This PRD serves as a roadmap for building a professional-grade trading interface that matches Hyperliquid's functionality while remaining maintainable and extensible._
