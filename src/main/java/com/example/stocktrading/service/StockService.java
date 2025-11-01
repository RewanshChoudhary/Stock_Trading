package com.example.stocktrading.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.example.stocktrading.model.Stock;
import com.example.stocktrading.repository.StockRepository;

@Service
@RequiredArgsConstructor
public class StockService {
  private final StockRepository stockRepository;

  public List<Stock> findAllStocks() {
    return stockRepository.findAll();

  }

  public Stock updatePrice(Long stockId, Double price) {
    Stock stock = stockRepository.findById(stockId)
        .orElseThrow(() -> new RuntimeException("The given id has no stock as such"));
    stock.setCurrentPrice(price);
    return stockRepository.save(stock);

  }

}
