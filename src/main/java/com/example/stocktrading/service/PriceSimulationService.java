package com.example.stocktrading.service;

import com.example.stocktrading.model.Order;
import com.example.stocktrading.model.PriceHistory;
import com.example.stocktrading.model.Stock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PriceSimulationService {

    private final StockService stockService;
    private final OrderService orderService;
    private final PriceHistoryService priceHistoryService;
    private final WebSocketPriceService webSocketPriceService;
    private static final double PRICE_CHANGE_FACTOR = 0.01;
    private static final double MIN_PRICE = 0.01;

    @Scheduled(fixedRate = 10000)
    public void simulatePriceUpdates() {
        List<Stock> stocks = stockService.findAllStocks();
        
        for (Stock stock : stocks) {
            updateStockPrice(stock);
        }
    }

    private void updateStockPrice(Stock stock) {
        int buyCount = orderService.countPendingOrders(stock.getId(), Order.OrderType.BUY);
        int sellCount = orderService.countPendingOrders(stock.getId(), Order.OrderType.SELL);

        double supplyDemandFactor = calculateSupplyDemandFactor(buyCount, sellCount);
        double currentPrice = stock.getCurrentPrice();
        double newPrice = currentPrice * (1 + supplyDemandFactor);
        
        if (newPrice < MIN_PRICE) {
            newPrice = MIN_PRICE;
        }

        stockService.updatePrice(stock.getId(), newPrice);

        PriceHistory priceHistory = new PriceHistory();
        priceHistory.setStock(stock);
        priceHistory.setPrice(newPrice);
        priceHistoryService.recordPrice(priceHistory);

        // Broadcast price update via WebSocket
        webSocketPriceService.broadcastPriceUpdate(stock, currentPrice, newPrice);

        log.debug("Stock {}: {} -> {} (BUY: {}, SELL: {})", 
            stock.getSymbol(), currentPrice, newPrice, buyCount, sellCount);
    }

    private double calculateSupplyDemandFactor(int buyCount, int sellCount) {
        if (buyCount == 0 && sellCount == 0) {
            return 0.0;
        }

        double total = buyCount + sellCount;
        double buyRatio = (double) buyCount / total;
        double sellRatio = (double) sellCount / total;
        
        double imbalance = buyRatio - sellRatio;
        return imbalance * PRICE_CHANGE_FACTOR;
    }
}

