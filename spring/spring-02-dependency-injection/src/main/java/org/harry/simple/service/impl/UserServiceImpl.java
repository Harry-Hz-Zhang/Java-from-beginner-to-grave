package org.harry.simple.service.impl;

import org.harry.simple.dao.UserDao;
import org.harry.simple.service.UserService;

public class UserServiceImpl implements UserService {
    private UserDao userDao;

    // 使用set方式注入，必须提供set方法。
    // 反射机制要调用这个方法给属性赋值的。
    public void setUserDao(UserDao userDao) {
        this.userDao = userDao;
    }

    public void save(){
        System.out.println("开始调用 dao.insert");
        userDao.insert();
        System.out.println("结束调用 dao.insert");
    }
}
