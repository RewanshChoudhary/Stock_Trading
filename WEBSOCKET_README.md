# WebSocket Live Price Updates - Phase 7

## Overview
This implementation provides real-time stock price updates via WebSocket. Every 10 seconds, when prices update, all connected clients receive:
- Stock price changes
- Percentage change
- Last 5 trades for each stock

## Architecture

### Backend Components

1. **WebSocketConfig** (`config/WebSocketConfig.java`)
   - Configures STOMP over WebSocket
   - Endpoint: `/ws/prices`
   - Enables SockJS fallback for browser compatibility

2. **WebSocketPriceService** (`service/WebSocketPriceService.java`)
   - Broadcasts price updates to `/topic/prices`
   - Includes stock details, price changes, and recent trades

3. **PriceSimulationService** (updated)
   - Integrated with WebSocket broadcasting
   - Sends updates on every price tick (10-second intervals)

4. **PriceUpdateMessage** (`dto/PriceUpdateMessage.java`)
   - Data structure for WebSocket messages
   - Contains: stockId, symbol, name, prices, change%, timestamp, recent trades

### Frontend Components

1. **HTML Client** (`src/main/resources/static/index.html`)
   - Beautiful live ticker UI
   - Auto-reconnects on disconnect
   - Color-coded price changes (green up, red down)
   - Shows recent trades

2. **Node.js Console Client** (`websocket-client.js`)
   - Command-line WebSocket client
   - Colored terminal output
   - Real-time price streaming

## How to Run

### 1. Start the Spring Boot Application

```bash
./mvnw clean install
./mvnw spring-boot:run
```

The server will start on port 8080 (or your configured port).

### 2. Access the Web UI

Open your browser and navigate to:
```
http://localhost:8080
```

You should see:
- Connection status indicator
- Live updating stock cards
- Price changes with color indicators
- Recent trades for each stock

### 3. Use the Console Client (Optional)

First, install Node.js dependencies:
```bash
npm install
```

Then run the console client:
```bash
node websocket-client.js localhost 8080
```

Or simply:
```bash
npm run client
```

The console will display:
```
📈 Stock Trading WebSocket Client
=================================

Connecting to: http://localhost:8080/ws/prices

✅ Connected to WebSocket server

📊 Listening for price updates...

────────────────────────────────────────────────────────────────────────────────

[2:30:45 PM] 📈 AAPL - Apple Inc.
  Price: $150.25 → $151.50
  Change: +0.83%
  Recent Trades:
    • 100 shares @ $150.75 (2:30:40 PM)
    • 50 shares @ $150.50 (2:30:35 PM)
────────────────────────────────────────────────────────────────────────────────
```

## WebSocket Message Format

Messages are sent to `/topic/prices` in JSON format:

```json
{
  "stockId": 1,
  "symbol": "AAPL",
  "name": "Apple Inc.",
  "oldPrice": 150.25,
  "newPrice": 151.50,
  "changePercent": 0.83,
  "timestamp": "2025-11-02T07:30:45",
  "recentTrades": [
    {
      "tradeId": 42,
      "price": 150.75,
      "quantity": 100,
      "tradeTime": "2025-11-02T07:30:40"
    }
  ]
}
```

## Testing

### Manual Testing

1. **Start the server** - The price simulation runs every 10 seconds
2. **Open the web UI** - You should see "🟢 Connected"
3. **Place some orders** - Use the order endpoints to create buy/sell orders
4. **Watch prices update** - Every 10 seconds, prices will update based on supply/demand
5. **See trades** - Recent trades will appear in the UI

### Browser Console Testing

Open browser DevTools console and check for:
```javascript
Connected: CONNECTED...
```

### Network Tab Testing

In DevTools Network tab:
- Filter by "WS" (WebSocket)
- Click on the `/ws/prices` connection
- View the "Messages" tab to see real-time data

## Configuration

### Change Update Frequency

In `PriceSimulationService.java`:
```java
@Scheduled(fixedRate = 10000) // Change from 10000ms (10s) to desired interval
```

### Change Number of Recent Trades

In `WebSocketPriceService.java`:
```java
List<Trade> recentTrades = tradeService.getRecentTrades(stock.getId(), 5); // Change from 5
```

### CORS Configuration

WebSocket is configured to allow all origins:
```java
.setAllowedOriginPatterns("*")
```

For production, restrict to specific domains:
```java
.setAllowedOrigins("https://yourdomain.com")
```

## Troubleshooting

### "Connection refused" error
- Ensure the Spring Boot server is running
- Check the port (default 8080)
- Verify firewall settings

### No price updates
- Check if stocks exist in the database
- Verify PriceSimulationService is running (check logs)
- Ensure @EnableScheduling is present in main application class

### WebSocket disconnects frequently
- Check for network stability
- Verify server has sufficient resources
- Review server logs for errors

## Security Considerations

Current implementation is for development. For production:

1. **Add authentication** to WebSocket endpoints
2. **Restrict CORS** to specific origins
3. **Add rate limiting** to prevent abuse
4. **Use WSS** (WebSocket Secure) instead of WS
5. **Validate** all incoming messages

## API Endpoints

- **WebSocket Endpoint**: `ws://localhost:8080/ws/prices`
- **SockJS Fallback**: `http://localhost:8080/ws/prices`
- **Topic**: `/topic/prices` (subscribe to receive updates)

## Dependencies

Backend (already in pom.xml):
- `spring-boot-starter-websocket`
- `spring-messaging`

Frontend (web UI):
- SockJS Client (CDN)
- STOMP.js (CDN)

Console Client:
- sockjs-client
- stompjs
- ws

## Next Steps

Potential enhancements:
- [ ] Add user-specific subscriptions `/user/queue/prices`
- [ ] Implement price alerts
- [ ] Add historical price charts
- [ ] Create mobile app clients
- [ ] Add market depth data
- [ ] Implement order book visualization
