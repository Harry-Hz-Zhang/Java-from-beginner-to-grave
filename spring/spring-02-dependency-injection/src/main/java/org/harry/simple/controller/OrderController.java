package org.harry.simple.controller;

import org.harry.simple.service.OrderService;

public class OrderController {
    private OrderService orderService = null;

    public OrderController(OrderService o) {
        this.orderService = o;
    }
    public void saveOrder() {
        this.orderService.save();
    }
}
