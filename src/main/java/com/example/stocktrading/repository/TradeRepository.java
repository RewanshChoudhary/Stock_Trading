package com.example.stocktrading.repository;

import com.example.stocktrading.model.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TradeRepository extends JpaRepository<Trade,Long> {

}
