package com.example.StockTrading.session;

import org.springframework.stereotype.Component;

@Component
public class SessionStore {
    private Long currentUserId; // holds logged-in user temporarily

    public void login(Long userId) {
        this.currentUserId = userId;
    }

    public Long getCurrentUserId() {
        return this.currentUserId;
    }

    public void logout() {
        this.currentUserId = null;
    }
}
