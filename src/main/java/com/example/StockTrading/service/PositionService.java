package com.example.StockTrading.service;

import com.example.StockTrading.model.Position;
import com.example.StockTrading.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PositionService {

    private final PositionRepository positionRepository;

    public Position updatePosition(Position position) {
        return positionRepository.save(position);
    }
}







