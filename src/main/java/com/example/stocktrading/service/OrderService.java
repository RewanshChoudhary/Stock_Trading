package com.example.stocktrading.service;

import com.example.stocktrading.model.*;
import com.example.stocktrading.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Optional;

import static com.example.stocktrading.model.Order.OrderStatus.*;

@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final TradeService tradeService;
    private final PositionService positionService;
    private final UserService userService;
    private final StockService stockService;

    public Order placeOrder(Order order) {
        if (order.getRemainingQty() == null) {
            order.setRemainingQty(order.getQuantity());
        }
        if (order.getStatus() == null) {
            order.setStatus(PENDING);
        }

        Order savedOrder = orderRepository.save(order);
        attemptMatching(savedOrder);
        return savedOrder;
    }

    private void attemptMatching(Order incomingOrder) {
        if (incomingOrder.getOrderType() == Order.OrderType.BUY) {
            matchBuyOrder(incomingOrder);
        } else {
            matchSellOrder(incomingOrder);
        }
    }

    private void matchBuyOrder(Order buyOrder) {
        while (buyOrder.getRemainingQty() > 0 && buyOrder.getStatus() != FILLED && buyOrder.getStatus() != CANCELLED) {
            Optional<Order> sellOrderOpt = orderRepository
                    .findFirstByStockIdAndOrderTypeAndStatusInOrderByPriceAsc(
                            buyOrder.getStock().getId(),
                            Order.OrderType.SELL,
                            Arrays.asList(PENDING, PARTIAL)
                    );

            if (sellOrderOpt.isEmpty()) {
                break;
            }

            Order sellOrder = sellOrderOpt.get();
            if (sellOrder.getPrice() > buyOrder.getPrice()) {
                break;
            }

            executeTrade(buyOrder, sellOrder, true);
        }

        if (buyOrder.getRemainingQty() == 0) {
            buyOrder.setStatus(FILLED);
        } else if (buyOrder.getRemainingQty() < buyOrder.getQuantity()) {
            buyOrder.setStatus(PARTIAL);
        }

        orderRepository.save(buyOrder);
    }

    private void matchSellOrder(Order sellOrder) {
        while (sellOrder.getRemainingQty() > 0 && sellOrder.getStatus() != FILLED && sellOrder.getStatus() != CANCELLED) {
            Optional<Order> buyOrderOpt = orderRepository
                    .findFirstByStockIdAndOrderTypeAndStatusInOrderByPriceDesc(
                            sellOrder.getStock().getId(),
                            Order.OrderType.BUY,
                            Arrays.asList(PENDING, PARTIAL)
                    );

            if (buyOrderOpt.isEmpty()) {
                break;
            }

            Order buyOrder = buyOrderOpt.get();
            if (buyOrder.getPrice() < sellOrder.getPrice()) {
                break;
            }

            executeTrade(buyOrder, sellOrder, false);
        }

        if (sellOrder.getRemainingQty() == 0) {
            sellOrder.setStatus(FILLED);
        } else if (sellOrder.getRemainingQty() < sellOrder.getQuantity()) {
            sellOrder.setStatus(PARTIAL);
        }

        orderRepository.save(sellOrder);
    }

    private void executeTrade(Order buyOrder, Order sellOrder, boolean initiatedByBuyer) {
        int tradeQty = Math.min(buyOrder.getRemainingQty(), sellOrder.getRemainingQty());
        double tradePrice = initiatedByBuyer ? sellOrder.getPrice() : buyOrder.getPrice();

        buyOrder.setRemainingQty(buyOrder.getRemainingQty() - tradeQty);
        sellOrder.setRemainingQty(sellOrder.getRemainingQty() - tradeQty);

        Trade trade = new Trade();
        trade.setBuyOrder(buyOrder);
        trade.setSellOrder(sellOrder);
        trade.setStock(buyOrder.getStock());
        trade.setPrice(tradePrice);
        trade.setQuantity(tradeQty);
        tradeService.recordTrade(trade);

        updateUserBalance(buyOrder.getUser(), tradePrice * tradeQty, true);
        updateUserBalance(sellOrder.getUser(), tradePrice * tradeQty, false);

        updatePosition(buyOrder.getUser(), buyOrder.getStock(), tradeQty, tradePrice, true);
        updatePosition(sellOrder.getUser(), sellOrder.getStock(), tradeQty, tradePrice, false);

        stockService.updatePrice(buyOrder.getStock().getId(), tradePrice);

        orderRepository.save(buyOrder);
        orderRepository.save(sellOrder);
    }

    private void updateUserBalance(User user, double amount, boolean isBuyer) {
        if (isBuyer) {
            user.setBalance(user.getBalance() - amount);
        } else {
            user.setBalance(user.getBalance() + amount);
        }
        userService.updateBalance(user);
    }

    private void updatePosition(User user, Stock stock, int qty, double price, boolean isBuyer) {
        Position position = positionService.getOrCreatePosition(user.getId(), stock.getId(), user, stock);

        if (isBuyer) {
            double totalCost = position.getQuantity() * position.getAvgPrice() + qty * price;
            position.setQuantity(position.getQuantity() + qty);
            position.setAvgPrice(totalCost / position.getQuantity());
        } else {
            position.setQuantity(position.getQuantity() - qty);
            if (position.getQuantity() <= 0) {
                position.setQuantity(0);
                position.setAvgPrice(0.0);
            }
        }

        positionService.updatePosition(position);
    }

    public void cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));
        order.setStatus(CANCELLED);
        orderRepository.save(order);
    }

    public int countPendingOrders(Long stockId, Order.OrderType orderType) {
        return orderRepository.countByStockIdAndOrderTypeAndStatusIn(
            stockId, 
            orderType, 
            Arrays.asList(PENDING, PARTIAL)
        );
    }
}
