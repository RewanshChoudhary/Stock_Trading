package com.example.stocktrading.repository;

import com.example.stocktrading.model.Trade;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TradeRepository extends JpaRepository<Trade,Long> {
    List<Trade> findByStockIdOrderByTradeTimeDesc(Long stockId, Pageable pageable);
}
