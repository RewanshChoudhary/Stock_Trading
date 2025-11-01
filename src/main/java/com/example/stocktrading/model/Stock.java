package com.example.stocktrading.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Table(name="stocks")
@Data
@Entity
public class Stock {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String symbol; // e.g. AAPL, TSLA

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Double currentPrice;

    private LocalDateTime createdAt = LocalDateTime.now();
}
