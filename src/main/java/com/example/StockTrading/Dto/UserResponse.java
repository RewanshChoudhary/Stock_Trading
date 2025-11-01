package com.example.StockTrading.Dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
@Builder
@Data
@AllArgsConstructor

public class UserResponse {
    private Long id;
    private String username;
    private String email;

}
