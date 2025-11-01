package com.example.stocktrading.service;

import com.example.stocktrading.model.Trade;
import com.example.stocktrading.repository.TradeRepository;
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
