package com.example.stocktrading.service;

import com.example.stocktrading.model.Position;
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
}







