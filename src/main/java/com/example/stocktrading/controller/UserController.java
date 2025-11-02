package com.example.stocktrading.controller;

import com.example.stocktrading.dto.UserLoginRequest;
import com.example.stocktrading.dto.UserRegisterRequest;
import com.example.stocktrading.dto.UserResponse;

import com.example.stocktrading.model.User;

import com.example.stocktrading.service.UserService;
import com.example.stocktrading.store.SessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final SessionStore sessionStore;
    private final BCryptPasswordEncoder passwordEncoder;



    @PostMapping("/register")
    public UserResponse register(@RequestBody UserRegisterRequest request) {
        UserRegisterRequest user = new UserRegisterRequest();
        user.setUsername(request.getUsername());
        user.setPassword(request.getPassword());
        user.setEmail(request.getEmail());



        User saved = userService.registerUser(user);

        UserResponse response = UserResponse.builder()
                .id(saved.getId())
                .email(saved.getEmail())
                .username(saved.getUsername())
                .build();

        return response;
    }

    @PostMapping("/login")
    public UserResponse login(@RequestBody UserLoginRequest request) {
        User user = userService.login(request.getUsername(), request.getPassword());
        sessionStore.login(user.getId());

        UserResponse response= UserResponse.builder()
                .username(user.getUsername())
                .id(user.getId())
                .email(user.getEmail())
                .build();

        return response;
    }

    @GetMapping("/me")
    public UserResponse getCurrentUser() {
        Long userId = sessionStore.getCurrentUserId();
        if (userId == null) {
            throw new RuntimeException("Not logged in");
        }

        User user = userService.getUserById(userId);

        UserResponse response = new UserResponse(user.getId(),user.getUsername(),user.getEmail());
        return response;
    }
       @GetMapping("/me/positions")
    public ResponseEntity<List<Position>> getUserPositions() {
        String userId = SessionStore.getCurrentUserId();
        return ResponseEntity.ok(positionService.getUserPositions(userId));
    }

    @GetMapping("/me/orders")
    public ResponseEntity<List<Order>> getUserOrders() {
        String userId = SessionStore.getCurrentUserId();
        return ResponseEntity.ok(orderService.getUserOpenOrders(userId));
    }

    @GetMapping("/me/trades")
    public ResponseEntity<List<Trade>> getUserTrades() {
        String userId = SessionStore.getCurrentUserId();
        return ResponseEntity.ok(tradeService.getUserTrades(userId));
    }
    

}
