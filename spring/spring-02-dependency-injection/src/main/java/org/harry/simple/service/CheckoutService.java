package org.harry.simple.service;

/**
 * 结算服务，是整条装配链的终点。
 * 命名刻意避开已有的 OrderService，避免和之前那套构造注入的例子混在一起。
 */
public interface CheckoutService {

    void checkout();
}
