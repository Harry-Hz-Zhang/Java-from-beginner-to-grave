package org.harry.simple.dao.impl;

import org.harry.simple.dao.UserDao;

public class UserDaoImpl implements UserDao {
    @Override
    public void insert() {
        System.out.println("插入数据成功");
    }
}
