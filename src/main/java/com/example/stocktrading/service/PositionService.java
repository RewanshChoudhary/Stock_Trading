package com.example.stocktrading.service;

import com.example.stocktrading.model.Position;
import com.example.stocktrading.model.Stock;
import com.example.stocktrading.model.User;
import com.example.stocktrading.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PositionService {

    private final PositionRepository positionRepository;

    public Position updatePosition(Position position) {
        return positionRepository.save(position);
    }

    public Position getOrCreatePosition(Long userId, Long stockId, User user, Stock stock) {
        return positionRepository.findByUserIdAndStockId(userId, stockId)
                .orElseGet(() -> {
                    Position newPosition = new Position();
                    newPosition.setUser(user);
                    newPosition.setStock(stock);
                    newPosition.setQuantity(0);
                    newPosition.setAvgPrice(0.0);
                    return positionRepository.save(newPosition);
                });
    }
 
}







