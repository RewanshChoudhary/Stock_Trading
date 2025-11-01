package com.example.StockTrading.controller;

import com.example.StockTrading.Dto.UserLoginRequest;
import com.example.StockTrading.Dto.UserRegisterRequest;
import com.example.StockTrading.Dto.UserResponse;

import com.example.StockTrading.model.User;
import com.example.StockTrading.repository.UserRepository;
import com.example.StockTrading.service.UserService;
import com.example.StockTrading.session.SessionStore;
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
    private final UserRepository userRepository;


    @PostMapping("/register")
    public UserResponse register(@RequestBody UserRegisterRequest request) {
        User user = new User();
        user.setUsername(request.getUsername());
user.setPasswordHash(passwordEncoder.encode(request.getPassword()));




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

}
