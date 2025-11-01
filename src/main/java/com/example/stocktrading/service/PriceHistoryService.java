package com.example.stocktrading.service;



import com.example.stocktrading.model.PriceHistory;
import com.example.stocktrading.repository.PriceHistoryRepository;
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
