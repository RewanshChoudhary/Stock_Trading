package com.example.StockTrading.service;

import com.example.StockTrading.model.Trade;
import com.example.StockTrading.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TradeService {

    private final TradeRepository tradeRepository;

    public Trade recordTrade(Trade trade) {
        return tradeRepository.save(trade);
    }
}
