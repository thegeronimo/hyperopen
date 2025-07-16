# Sente WebSocket Implementation - Product Requirements Document

## Overview

Implement a custom WebSocket communication layer using Sente for the Hyperopen trading interface. This will replace external WebSocket libraries with a robust, Clojure-native solution that provides real-time market data, order management, and account updates.

## Architecture Goals

- **Client-Side**: Sente client for direct WebSocket connections to Hyperliquid APIs
- **Protocol**: Custom message format for Hyperliquid API communication
- **State Management**: Integration with existing Replicant/Nexus state system
- **Error Handling**: Robust connection management and reconnection logic
- **API Integration**: Direct connection to Hyperliquid WebSocket endpoints

## Implementation Phases

### Phase 1: Core Sente Infrastructure (2-3 days)

**Goal**: Establish basic Sente client-server communication framework

#### 1.1 Dependencies & Project Setup

- [ ] Add Sente dependencies to `deps.edn`:
  ```clojure
  com.taoensso/sente {:mvn/version "1.17.0"}
  com.taoensso/timbre {:mvn/version "6.2.2"}
  ```
- [ ] Update `shadow-cljs.edn` for proper ClojureScript compilation
- [ ] Create namespace structure for WebSocket modules
- [ ] Add Hyperliquid API endpoint configuration

#### 1.2 Basic Sente Client Setup

- [ ] Create `src/hyperopen/websocket/client.cljs`
- [ ] Implement Sente client initialization with connection config
- [ ] Set up event handlers for connection state changes
- [ ] Add basic logging and error handling
- [ ] Create connection status indicator component

#### 1.3 Hyperliquid API Integration Setup

- [ ] Create `src/hyperopen/websocket/api_config.cljs`
- [ ] Configure Hyperliquid WebSocket endpoints (mainnet/testnet)
- [ ] Set up API authentication and connection parameters
- [ ] Add environment-specific configuration management
- [ ] Create connection health monitoring

#### 1.4 Message Protocol Design

- [ ] Define message format for Hyperliquid API communication
- [ ] Create message type constants and validation
- [ ] Implement message serialization/deserialization for Hyperliquid format
- [ ] Add message routing based on Hyperliquid event types

**Deliverable**: Basic Sente client with Hyperliquid API connection management

---

### Phase 2: Market Data WebSocket Module (3-4 days)

**Goal**: Real-time market data streaming for trading pairs

#### 2.1 Market Data Client Module

- [ ] Create `src/hyperopen/websocket/market_data.cljs`
- [ ] Implement subscription management for trading pairs
- [ ] Add market data event handlers (ticker, orderbook, trades)
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
- [ ] Add trade history streaming

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

These libraries serve as authoritative references for:

- WebSocket message formats and protocols
- API endpoint structures and authentication
- Error handling and reconnection strategies
- Data transformation and validation patterns
- Order management and market data subscription flows

### Message Format

```clojure
;; Client to Hyperliquid API
{:type :subscribe
 :channel "ticker"
 :symbol "BTC-USD"}

;; Hyperliquid API to Client
{:type :ticker
 :data {:symbol "BTC-USD"
        :price 45000.50
        :change 2.5
        :volume 1234.56}}
```

### Namespace Structure

```
src/hyperopen/websocket/
├── client.cljs          # Main Sente client
├── api_config.cljs      # Hyperliquid API configuration
├── market_data.cljs     # Market data client module
├── market_data_api.cljs # Market data API integration
├── orders.cljs          # Order management client
├── orders_api.cljs      # Order API integration
├── account.cljs         # Account client module
├── account_api.cljs     # Account API integration
└── protocol.cljc        # Shared message protocol
```

### Dependencies

```clojure
;; Client-side only
com.taoensso/sente {:mvn/version "1.17.0"}
com.taoensso/timbre {:mvn/version "6.2.2"}
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

1. Review and approve this PRD
2. Set up development environment with Sente dependencies
3. Begin Phase 1 implementation
4. Create development milestones and checkpoints
5. Set up monitoring and testing infrastructure
