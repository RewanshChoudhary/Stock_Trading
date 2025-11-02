package com.example.stocktrading.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PriceUpdateMessage {
    private Long stockId;
    private String symbol;
    private String name;
    private Double oldPrice;
    private Double newPrice;
    private Double changePercent;
    private LocalDateTime timestamp;
    private List<TradeInfo> recentTrades;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TradeInfo {
        private Long tradeId;
        private Double price;
        private Integer quantity;
        private LocalDateTime tradeTime;
    }
}
