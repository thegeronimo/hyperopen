---
owner: product
status: supporting
last_reviewed: 2026-02-13
review_cycle_days: 90
source_of_truth: true
---

# Account Information Component - PRD

## Overview

Create a comprehensive account information component that displays trading account data in a tabbed interface. The component will consume data from the global store's `:webdata2` field, specifically focusing on the `:clearinghouseState` data structure to provide users with real-time account insights.

## Product Requirements

### Core Functionality

- **Multi-tab Interface**: Display different aspects of account information in organized tabs
- **Real-time Data**: Consume live data from the global store's `:webdata2` field
- **Responsive Design**: Adapt to different screen sizes while maintaining usability
- **Visual Hierarchy**: Clear presentation of financial data with proper formatting

### Data Sources

- **Primary**: `:webdata2` field from global store
- **Key Structure**: `:clearinghouseState` containing:
  - `:marginSummary` - Account value, total positions, margin usage
  - `:crossMarginSummary` - Cross-margin specific metrics
  - `:assetPositions` - Individual position details
  - `:withdrawable` - Available funds for withdrawal
  - `:crossMaintenanceMarginUsed` - Maintenance margin requirements

### Tab Structure

#### 1. Balances Tab

**Purpose**: Account balance overview with individual asset balances
**Data Display**:

- Asset list with coin symbols (USDC, HYPE, etc.)
- Total Balance per asset
- Available Balance per asset
- USDC Value equivalent
- PNL (ROE %) with color coding (green for positive, red for negative)
- Send/Transfer/Contract action buttons
- Filter toggle for "Hide Small Balances"

#### 2. Positions Tab

**Purpose**: Active trading positions (from `:clearinghouseState/:assetPositions`)
**Data Display**:

- Position list with asset symbols
- Position size (`:szi`)
- Entry price (`:entryPx`)
- Mark price and current P&L (`:unrealizedPnl`)
- Leverage (`:leverage/:value`)
- Margin used per position (`:marginUsed`)
- Liquidation price (`:liquidationPx`)
- Return on equity (`:returnOnEquity`)

#### 3. Open Orders Tab

**Purpose**: Active open orders
**Data Display**:

- Order list with asset symbols
- Order type and size
- Price levels
- Order status and time
- Cancel order functionality

#### 4. Additional Tabs (TWAP, Trade History, Funding History, Order History)

**Purpose**: Historical data and advanced order types
**Data Display**:

- TWAP: Time-weighted average price orders
- Trade History: Completed trades with timestamps
- Funding History: Funding payment records (`:cumFunding` data)
- Order History: Historical order activity

## Technical Implementation

### Phase 1: Foundation (Week 1)

**Objectives**: Basic component structure and data integration

**Tasks**:

1. Create base component file: `src/hyperopen/views/account_info_view.cljs`
2. Implement tab navigation system following existing dropdown patterns
3. Set up data consumption from global store `:webdata2`
4. Create basic layout structure with placeholder content
5. Implement Balances tab with asset balance display
6. Add proper error handling for missing/malformed data

**Acceptance Criteria**:

- Component renders without errors
- Tab navigation works smoothly
- Balances tab displays real data from store
- Handles missing data gracefully
- Follows existing code style patterns [[memory:9125012]]

### Phase 2: Core Tabs Implementation (Week 2)

**Objectives**: Complete remaining tab functionality

**Tasks**:

1. Implement Positions tab with full position details
2. Add Margin & Risk tab with risk calculations
3. Create Funding tab with funding history
4. Implement data formatting utilities for financial values
5. Add loading states and empty state handling
6. Create responsive design for mobile/tablet views

**Acceptance Criteria**:

- All tabs display correct data
- Financial values properly formatted
- Component responsive across devices
- Loading states provide good UX
- Empty states handled gracefully

### Phase 3: Enhancement & Polish (Week 3)

**Objectives**: Advanced features and optimization

**Tasks**:

1. Add visual indicators for profit/loss (green/red coloring)
2. Implement sorting and filtering for positions
3. Add percentage change calculations and displays
4. Create risk level indicators and warnings
5. Optimize performance with memoization if needed
6. Add comprehensive error boundaries

**Acceptance Criteria**:

- Visual feedback for P&L status
- Sortable position lists
- Risk warnings display appropriately
- Performance optimized for large position lists
- Comprehensive error handling

### Phase 4: Advanced Features (Week 4)

**Objectives**: Additional functionality and integrations

**Tasks**:

1. Add historical data views (if available)
2. Implement export functionality for account data
3. Create mini-chart views for position P&L trends
4. Add keyboard navigation support
5. Integrate with existing wallet connection status
6. Add accessibility improvements (ARIA labels, etc.)

**Acceptance Criteria**:

- Historical data displays correctly
- Export functionality works reliably
- Charts enhance data visualization
- Full keyboard navigation support
- Meets accessibility standards

## Design Specifications

### Visual Design

- **Color Scheme**: Follow existing app theme (base-100, base-200, base-300)
- **Typography**: Consistent with existing components
- **Tab Style**: Similar to existing dropdown patterns but horizontal
- **Data Tables**: Clean, scannable layouts with proper alignment
- **Status Indicators**: Green for profits, red for losses, yellow for warnings

### Layout Specifications

- **Container**: Max width consistent with other panels (max-w-7xl)
- **Tab Navigation**: Horizontal tabs at top, mobile-responsive
- **Content Area**: Scrollable if needed, proper spacing
- **Data Formatting**: Right-aligned numbers, consistent decimal places
- **Responsive Breakpoints**: Follow existing Tailwind breakpoints

### Interaction Design

- **Tab Switching**: Smooth transitions, maintain scroll position
- **Data Updates**: Smooth animations for value changes
- **Loading States**: Skeleton screens or spinners
- **Error States**: Clear messaging with retry options

## Data Flow Architecture

### Store Integration

```clojure
;; Expected data structure in store
{:webdata2
 {:clearinghouseState
  {:marginSummary {...}
   :crossMarginSummary {...}
   :assetPositions [...]
   :withdrawable "..."
   :crossMaintenanceMarginUsed "..."
   :time 1758390015383}}}
```

### Component State Management

- Use replicant patterns for reactive updates [[memory:4257399]]
- Follow nexus action/effect separation [[memory:9125012]]
- Implement proper subscription to store changes
- Handle data transformation in utility functions

### Error Handling Strategy

- Graceful degradation for missing data fields
- Clear error messages for connection issues
- Fallback displays for malformed data
- Retry mechanisms for failed data loads

## Integration Points

### Existing Components

- **Header**: No direct integration needed
- **Wallet**: Display connection status context
- **WebSocket**: Consume real-time updates via webdata2
- **Navigation**: Add to main app layout

### Store Dependencies

- `:webdata2` - Primary data source
- `:wallet` - Connection status for context
- `:websocket` - Connection state for error handling

### Utility Functions

- Leverage existing formatting utilities in `utils/formatting.cljs`
- Use data normalization patterns from `utils/data_normalization.cljs`
- Follow interval utilities pattern for time-based data

## Testing Strategy

### Unit Tests

- Data transformation functions
- Formatting utilities
- Error handling edge cases
- Component rendering with mock data

### Integration Tests

- Store data consumption
- Tab navigation functionality
- Responsive layout behavior
- Real-time update handling

### User Acceptance Testing

- Verify all financial calculations
- Test across different account states
- Validate mobile/desktop experiences
- Confirm accessibility compliance

## Success Metrics

### Performance Targets

- Component render time < 100ms
- Tab switching < 50ms
- Data update processing < 200ms
- Memory usage minimal impact

### User Experience Goals

- Zero learning curve for existing users
- Intuitive navigation between data views
- Clear visual feedback for all interactions
- Consistent with existing app patterns

### Business Objectives

- Increase user engagement with account data
- Reduce support queries about account status
- Provide foundation for advanced trading features
- Maintain high app performance standards

## Risk Assessment

### Technical Risks

- **Data Structure Changes**: Monitor webdata2 API for breaking changes
- **Performance Impact**: Large position lists could affect rendering
- **Mobile Constraints**: Limited screen space for complex data tables
- **Real-time Updates**: High-frequency updates could cause performance issues

### Mitigation Strategies

- Implement robust data validation and fallbacks
- Use virtualization for large data sets if needed
- Progressive disclosure for mobile interfaces
- Throttle update frequency if necessary

## Future Enhancements

### Potential Extensions

- Advanced charting integration
- Portfolio analytics and insights
- Risk management tools
- Historical performance tracking
- Export and reporting features

### Scalability Considerations

- Support for multiple account types
- Integration with additional data sources
- Enhanced filtering and search capabilities
- Customizable dashboard layouts

---

**Document Version**: 1.0  
**Last Updated**: September 20, 2025  
**Next Review**: Upon Phase 1 completion
