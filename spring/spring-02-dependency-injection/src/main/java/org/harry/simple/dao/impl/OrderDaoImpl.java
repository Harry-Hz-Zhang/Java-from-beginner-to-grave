package org.harry.simple.dao.impl;

import org.harry.simple.dao.OrderDao;

public class OrderDaoImpl implements OrderDao {
    @Override
    public void save() {
        System.out.println("保存订单成功");
    }
}
