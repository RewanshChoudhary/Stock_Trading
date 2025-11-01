package com.example.StockTrading.service;

import com.example.StockTrading.model.User;
import com.example.StockTrading.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;


    public User registerUser(User user){
        if (userRepository.existsByUsername(user.getUsername())) {
            throw new RuntimeException("Username is already in use");

        }
        return  userRepository.save(user);


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


}
