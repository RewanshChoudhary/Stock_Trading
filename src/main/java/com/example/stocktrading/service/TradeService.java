package com.example.stocktrading.service;

import com.example.stocktrading.model.Trade;
import com.example.stocktrading.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TradeService {

    private final TradeRepository tradeRepository;

    public Trade recordTrade(Trade trade) {
        return tradeRepository.save(trade);
    }

    public List<Trade> getRecentTrades(Long stockId, int limit) {
        return tradeRepository.findByStockIdOrderByTradeTimeDesc(stockId, PageRequest.of(0, limit));
    }
}
