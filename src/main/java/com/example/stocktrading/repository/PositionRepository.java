package com.example.stocktrading.repository;

import com.example.stocktrading.model.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PositionRepository extends JpaRepository<Position,Long> {
    
    Optional<Position> findByUserIdAndStockId(Long userId, Long stockId);
}
