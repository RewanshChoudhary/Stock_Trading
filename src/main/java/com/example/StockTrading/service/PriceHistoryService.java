package com.example.StockTrading.service;



import com.example.StockTrading.model.PriceHistory;
import com.example.StockTrading.repository.PriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PriceHistoryService {

    private final PriceHistoryRepository priceHistoryRepository;

    public PriceHistory recordPrice(PriceHistory priceHistory) {
        return priceHistoryRepository.save(priceHistory);
    }
}
