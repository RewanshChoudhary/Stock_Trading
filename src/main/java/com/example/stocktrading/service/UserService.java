package com.example.stocktrading.service;

import com.example.stocktrading.dto.UserRegisterRequest;
import com.example.stocktrading.model.User;

import com.example.stocktrading.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public User registerUser(UserRegisterRequest req) {
        if (userRepository.existsByUsername(req.getUsername()))
            throw new RuntimeException("Username exists");

        User user = new User();
        user.setUsername(req.getUsername());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setEmail(req.getEmail());
        user.setBalance(10000.0);

        return userRepository.save(user);
    }

    public double getBalance(Long userId){

        if (!userRepository.existsById(userId)) {
            throw new RuntimeException("User not found");

        }

     return userRepository.findById(userId)
             .orElseThrow(()-> new RuntimeException("The user id does not exist"))
             .getBalance();




    }
    public User login(String username, String password) {
        User user = userRepository.findByUsername(username);

        if (user==null){
            throw new RuntimeException("User not found");

        }
        if (!passwordEncoder.matches(password,user.getPasswordHash()))
            throw new RuntimeException("Password does not match");
        return user;

    }
    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public User updateBalance(User user) {
        return userRepository.save(user);
    }

}
