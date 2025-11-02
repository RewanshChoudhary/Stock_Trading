package com.example.stocktrading.repository;

import com.example.stocktrading.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order,Long> {
    List<Order> findByStockIdAndOrderTypeAndStatusIn(Long stockId, Order.OrderType orderType, List<Order.OrderStatus> statuses);
    Optional<Order> findFirstByStockIdAndOrderTypeAndStatusInOrderByPriceAsc(Long stockId, Order.OrderType orderType, List<Order.OrderStatus> statuses);
    Optional<Order> findFirstByStockIdAndOrderTypeAndStatusInOrderByPriceDesc(Long stockId, Order.OrderType orderType, List<Order.OrderStatus> statuses);
    int countByStockIdAndOrderTypeAndStatusIn(Long stockId, Order.OrderType orderType, List<Order.OrderStatus> statuses);
 List<Order> findByUserIdAndStatusIn(Long userId, List<OrderStatus> statuses);
}
