package com.example.StockTrading.service;

import com.example.StockTrading.model.Order;
import com.example.StockTrading.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import static com.example.StockTrading.model.Order.OrderStatus.CANCELLED;

@Service
@RequiredArgsConstructor

public class OrderService {

    private final OrderRepository orderRepository;

    public Order  placeOrder(Order order){
     return orderRepository.save(order);


    }
    public void cancelOrder(Long orderId){
        Order order=orderRepository.findById(orderId)
                .orElseThrow(()-> new RuntimeException("Order not found"));
        order.setStatus(CANCELLED);
        orderRepository.save(order);

    }



}
