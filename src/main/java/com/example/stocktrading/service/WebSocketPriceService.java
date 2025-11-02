package com.example.stocktrading.service;

import com.example.stocktrading.dto.PriceUpdateMessage;
import com.example.stocktrading.model.Stock;
import com.example.stocktrading.model.Trade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebSocketPriceService {

    private final SimpMessagingTemplate messagingTemplate;
    private final TradeService tradeService;

    public void broadcastPriceUpdate(Stock stock, Double oldPrice, Double newPrice) {
        // Fetch recent trades for this stock
        List<Trade> recentTrades = tradeService.getRecentTrades(stock.getId(), 5);
        
        List<PriceUpdateMessage.TradeInfo> tradeInfos = recentTrades.stream()
                .map(trade -> new PriceUpdateMessage.TradeInfo(
                        trade.getId(),
                        trade.getPrice(),
                        trade.getQuantity(),
                        trade.getTradeTime()
                ))
                .collect(Collectors.toList());

        double changePercent = ((newPrice - oldPrice) / oldPrice) * 100;

        PriceUpdateMessage message = new PriceUpdateMessage(
                stock.getId(),
                stock.getSymbol(),
                stock.getName(),
                oldPrice,
                newPrice,
                changePercent,
                LocalDateTime.now(),
                tradeInfos
        );

        messagingTemplate.convertAndSend("/topic/prices", message);
        
        log.debug("Broadcasted price update for {}: {} -> {}", stock.getSymbol(), oldPrice, newPrice);
    }
}
