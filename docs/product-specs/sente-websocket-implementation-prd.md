---
owner: product
status: supporting
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# Sente WebSocket Implementation - Product Requirements Document

## Overview

Implement a custom WebSocket communication layer using native WebSocket API for the Hyperopen trading interface. This provides real-time market data, order management, and account updates by connecting directly to Hyperliquid's WebSocket API.

## Architecture Goals

- **Client-Side**: Native WebSocket client for direct connections to Hyperliquid APIs
- **Protocol**: Custom message format following Hyperliquid API specification
- **State Management**: Integration with existing Replicant/Nexus state system
- **Error Handling**: Robust connection management and reconnection logic
- **API Integration**: Direct connection to Hyperliquid WebSocket endpoints

## Usage Instructions

### Current Implementation Status

✅ **Phase 1.1 COMPLETED**: Basic WebSocket client and trades subscription

The following modules are now available and functional:

#### WebSocket Client (`src/hyperopen/websocket/client.cljs`)

Basic usage:

```clojure
;; Initialize connection to Hyperliquid
(ws-client/init-connection! "wss://api.hyperliquid.xyz/ws")

;; Check connection status
(ws-client/connected?) ; => true/false
(ws-client/get-connection-status) ; => :connected/:connecting/:disconnected

;; Send custom messages (follows Hyperliquid format)
(ws-client/send-message! {:method "subscribe"
                          :subscription {:type "allMids"}})

;; Register custom message handlers
(ws-client/register-handler! "channelName" handler-function)
```

#### Trades Subscription (`src/hyperopen/websocket/trades.cljs`)

Basic usage:

```clojure
;; Initialize trades module (registers handler automatically)
(trades/init!)

;; Subscribe to trades for a symbol
(trades/subscribe-trades! "BTC")   ; BTC trades
(trades/subscribe-trades! "ETH")   ; ETH trades

;; Unsubscribe from trades
(trades/unsubscribe-trades! "BTC")

;; Get data
(trades/get-subscriptions)     ; => #{"BTC" "ETH"}
(trades/get-recent-trades)     ; => [...] (last 100 trades)
(trades/clear-trades!)         ; Clear stored trades
```

#### Integration Example

See `src/hyperopen/core.cljs` for a complete integration example that:

1. Initializes WebSocket connection on app startup
2. Subscribes to BTC trades automatically
3. Logs trade data to browser console

### API Reference

For complete subscription types and message formats, refer to the official Hyperliquid documentation:
**[Hyperliquid WebSocket Subscriptions](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/websocket/subscriptions)**

#### Key Subscription Types Available:

- `trades` - Real-time trade data for specific coins
- `l2Book` - Order book updates
- `candle` - Candlestick data with various intervals
- `allMids` - All mid prices across assets
- `orderUpdates` - User-specific order updates (requires address)
- `userFills` - User-specific fill data (requires address)

#### Message Format:

All Hyperliquid WebSocket messages follow this format:

```json
{
  "method": "subscribe",
  "subscription": {
    "type": "subscriptionType",
    ...additionalParams
  }
}
```

## Implementation Phases

### Phase 1: Core WebSocket Infrastructure (COMPLETED ✅)

**Goal**: Establish basic WebSocket communication framework

#### 1.1 Dependencies & Project Setup (COMPLETED ✅)

- [x] ~~Add Sente dependencies to `deps.edn`~~ (Replaced with native WebSocket)
- [x] ~~Update `shadow-cljs.edn` for proper ClojureScript compilation~~ (No external deps needed)
- [x] Create namespace structure for WebSocket modules
- [x] Add Hyperliquid API endpoint configuration

#### 1.2 Basic WebSocket Client Setup (COMPLETED ✅)

- [x] Create `src/hyperopen/websocket/client.cljs`
- [x] Implement native WebSocket client initialization with connection config
- [x] Set up event handlers for connection state changes
- [x] Add basic logging and error handling
- [x] Create connection status tracking

#### 1.3 Hyperliquid API Integration Setup (COMPLETED ✅)

- [x] Configure Hyperliquid WebSocket endpoints (mainnet/testnet)
- [x] Set up direct WebSocket connection parameters
- [x] Add message routing and handler registration
- [x] Create connection health monitoring

#### 1.4 Message Protocol Design (COMPLETED ✅)

- [x] Implement Hyperliquid API message format compatibility
- [x] Create message serialization/deserialization for JSON
- [x] Add message routing based on channel types
- [x] Implement trades subscription as proof of concept

**Deliverable**: ✅ Basic WebSocket client with Hyperliquid API connection and trades subscription

---

### Phase 2: Market Data WebSocket Module (2-3 days)

**Goal**: Expand real-time market data streaming for multiple trading pairs

#### 2.1 Market Data Client Module

- [ ] Extend `src/hyperopen/websocket/market_data.cljs`
- [ ] Implement subscription management for multiple trading pairs
- [ ] Add market data event handlers (ticker, orderbook, candles)
- [ ] Create data transformation layer for incoming messages
- [ ] Implement rate limiting and data buffering

#### 2.2 Market Data API Integration

- [ ] Create `src/hyperopen/websocket/market_data_api.cljs`
- [ ] Implement Hyperliquid market data WebSocket subscriptions
- [ ] Add data transformation from Hyperliquid format to internal format
- [ ] Create subscription management for multiple trading pairs
- [ ] Add data validation and error handling for API responses

#### 2.3 Market Data Integration

- [ ] Integrate with existing Replicant state management
- [ ] Create market data atoms and update functions
- [ ] Add real-time UI updates for price changes
- [ ] Implement order book depth updates
- [ ] Add candlestick data streaming

#### 2.4 Market Data Features

- [ ] Implement trading pair subscription/unsubscription
- [ ] Add data compression for high-frequency updates
- [ ] Create data persistence for offline scenarios
- [ ] Add market data quality monitoring

**Deliverable**: Real-time market data streaming with UI integration

---

### Phase 3: Order Management WebSocket Module (3-4 days)

**Goal**: Real-time order submission, updates, and management

#### 3.1 Order Client Module

- [ ] Create `src/hyperopen/websocket/orders.cljs`
- [ ] Implement order submission with validation
- [ ] Add order status tracking and updates
- [ ] Create order cancellation functionality
- [ ] Add order history management

#### 3.2 Order API Integration

- [ ] Create `src/hyperopen/websocket/orders_api.cljs`
- [ ] Implement Hyperliquid order submission WebSocket endpoints
- [ ] Add order validation and transformation for API format
- [ ] Create order status tracking from Hyperliquid responses
- [ ] Add order persistence and recovery for local state

#### 3.3 Order Integration

- [ ] Integrate with trading form components
- [ ] Add real-time order status updates in UI
- [ ] Create order confirmation dialogs
- [ ] Implement order error handling and user feedback
- [ ] Add order history display

#### 3.4 Order Features

- [ ] Implement order types (Market, Limit, Stop)
- [ ] Add order modification capabilities
- [ ] Create order book integration for limit orders
- [ ] Add order execution reporting

**Deliverable**: Complete order management system with real-time updates

---

### Phase 4: Account & Position WebSocket Module (2-3 days)

**Goal**: Real-time account balance and position updates

#### 4.1 Account Client Module

- [ ] Create `src/hyperopen/websocket/account.cljs`
- [ ] Implement account balance streaming
- [ ] Add position updates and PnL calculations
- [ ] Create margin level monitoring
- [ ] Add account activity logging

#### 4.2 Account API Integration

- [ ] Create `src/hyperopen/websocket/account_api.cljs`
- [ ] Implement Hyperliquid account data WebSocket subscriptions
- [ ] Add position calculation from Hyperliquid data
- [ ] Create margin requirement calculations from API data
- [ ] Add account security and validation for API responses

#### 4.3 Account Integration

- [ ] Integrate with account information panel
- [ ] Add real-time balance updates
- [ ] Create position PnL visualization
- [ ] Implement margin warning system
- [ ] Add account activity feed

#### 4.4 Account Features

- [ ] Implement cross-margin calculations
- [ ] Add liquidation price monitoring
- [ ] Create account summary statistics
- [ ] Add trading performance metrics

**Deliverable**: Real-time account and position management system

---

### Phase 5: Advanced WebSocket Features (2-3 days)

**Goal**: Enhanced reliability, performance, and user experience

#### 5.1 Connection Management

- [ ] Implement automatic reconnection logic
- [ ] Add connection health monitoring
- [ ] Create connection quality indicators
- [ ] Add offline mode with data caching
- [ ] Implement connection pooling for multiple data sources

#### 5.2 Performance Optimization

- [ ] Add message compression for high-frequency data
- [ ] Implement data batching for multiple updates
- [ ] Create efficient message routing
- [ ] Add memory management for large datasets
- [ ] Optimize WebSocket message size

#### 5.3 Security & Validation

- [ ] Implement message authentication
- [ ] Add rate limiting for client requests
- [ ] Create input validation and sanitization
- [ ] Add DoS protection measures
- [ ] Implement secure WebSocket upgrade process

#### 5.4 Monitoring & Debugging

- [ ] Add comprehensive logging system
- [ ] Create WebSocket connection metrics
- [ ] Implement error tracking and reporting
- [ ] Add performance monitoring dashboards
- [ ] Create debugging tools for message inspection

**Deliverable**: Production-ready WebSocket infrastructure with monitoring

---

### Phase 6: Integration & Testing (2-3 days)

**Goal**: Complete integration with existing UI and comprehensive testing

#### 6.1 UI Integration

- [ ] Update all trading components to use WebSocket data
- [ ] Add loading states for WebSocket operations
- [ ] Implement error handling in UI components
- [ ] Create connection status indicators
- [ ] Add offline mode UI handling

#### 6.2 State Management Integration

- [ ] Integrate WebSocket events with Nexus registry
- [ ] Create WebSocket-specific effects and actions
- [ ] Add state synchronization between modules
- [ ] Implement optimistic updates for better UX
- [ ] Add state persistence for critical data

#### 6.3 Testing

- [ ] Create unit tests for WebSocket modules
- [ ] Add integration tests for client-server communication
- [ ] Implement WebSocket connection testing
- [ ] Add performance testing for high-frequency data
- [ ] Create end-to-end trading flow tests

#### 6.4 Documentation

- [ ] Document WebSocket API and message formats
- [ ] Create developer guide for WebSocket modules
- [ ] Add troubleshooting guide for common issues
- [ ] Document performance tuning guidelines
- [ ] Create deployment and configuration guide

**Deliverable**: Fully integrated and tested WebSocket trading system

---

## Technical Specifications

### Reference Implementations

When implementing Hyperliquid API integration, refer to these established libraries for guidance on API patterns, message formats, and best practices:

- **[@nktkas/hyperliquid](https://github.com/nktkas/hyperliquid)**: TypeScript SDK with comprehensive WebSocket support, message handling, and API integration patterns
- **[hyperliquid-python-sdk](https://github.com/hyperliquid-dex/hyperliquid-python-sdk)**: Python SDK providing reference implementations for order management, market data, and account operations
- **[Official Hyperliquid WebSocket Documentation](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/websocket/subscriptions)**: Complete API reference for all subscription types and message formats

These libraries serve as authoritative references for:

- WebSocket message formats and protocols
- API endpoint structures and authentication
- Error handling and reconnection strategies
- Data transformation and validation patterns
- Order management and market data subscription flows

### Message Format

```clojure
;; Client to Hyperliquid API
{:method "subscribe"
 :subscription {:type "trades"
                :coin "BTC"}}

;; Hyperliquid API to Client
{:channel "trades"
 :data [{:coin "BTC"
         :px "45000.50"
         :sz "0.1"
         :side "A"
         :time 1234567890
         :tid 12345}]}
```

### Namespace Structure

```
src/hyperopen/websocket/
├── client.cljs          # Main WebSocket client (COMPLETED)
├── trades.cljs          # Trades subscription client (COMPLETED)
├── market_data.cljs     # Market data client module (TODO)
├── market_data_api.cljs # Market data API integration (TODO)
├── orders.cljs          # Order management client (TODO)
├── orders_api.cljs      # Order API integration (TODO)
├── account.cljs         # Account client module (TODO)
└── account_api.cljs     # Account API integration (TODO)
```

### Dependencies

```clojure
;; No external dependencies - uses native WebSocket API
;; Current deps.edn remains unchanged from base Replicant/Nexus setup
```

## Success Criteria

1. **Reliability**: 99.9% uptime with automatic reconnection
2. **Performance**: <100ms latency for order operations
3. **Scalability**: Support for 100+ concurrent users
4. **Integration**: Seamless integration with existing Replicant/Nexus architecture
5. **Testing**: 90%+ test coverage for WebSocket modules

## Risk Mitigation

- **Connection Stability**: Implement exponential backoff for reconnections
- **Data Loss**: Add message queuing and persistence
- **Performance**: Monitor message frequency and implement throttling
- **Security**: Validate all incoming messages and implement rate limiting
- **Testing**: Create comprehensive test suite with mock WebSocket servers

## Timeline

- **Total Duration**: 10-14 days
- **Critical Path**: Market Data → Order Management → Account Integration
- **Dependencies**: Each phase builds on the previous phase
- **Parallel Work**: API integration modules can be developed in parallel

## Next Steps

1. ✅ ~~Review and approve this PRD~~
2. ✅ ~~Set up development environment with WebSocket dependencies~~
3. ✅ ~~Begin Phase 1 implementation~~
4. ✅ ~~Create development milestones and checkpoints~~
5. **Continue with Phase 2**: Market Data WebSocket Module
6. Set up monitoring and testing infrastructure
