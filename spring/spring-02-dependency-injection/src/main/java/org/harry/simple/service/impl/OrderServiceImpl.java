package org.harry.simple.service.impl;

import org.harry.simple.dao.OrderDao;
import org.harry.simple.dao.UserDao;
import org.harry.simple.service.OrderService;

public class OrderServiceImpl implements OrderService {
    private OrderDao orderDao = null;
    private UserDao userDao = null;

    // 通过反射机制调用构造方法给属性赋值
    public OrderServiceImpl(OrderDao orderDao, UserDao userDao) {
        this.orderDao = orderDao;
        this.userDao = userDao;
    }

    @Override
    public void save() {
        System.out.println("开始调用 orderService.save()");
        orderDao.save();
        System.out.println("结束调用 orderService.save()");
    }
}
