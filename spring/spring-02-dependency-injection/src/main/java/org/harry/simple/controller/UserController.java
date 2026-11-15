package org.harry.simple.controller;

import org.harry.simple.service.UserService;
//import org.harry.spring.service.impl.UserServiceImpl;
//public class UserController {
//    private UserService userService = new UserServiceImpl();  // 之前的写法，违反了 Ioc
//
//    private void saveUser(){
//        System.out.println("开始调用 userService.save");
//        userService.save();
//        System.out.println("结束调用 userService.save");
//    }
//}

public class UserController {
    private UserService userService;

    public void setUserService(UserService userService) {
        this.userService = userService;
    }

    public void saveUser(){
        System.out.println("开始调用 userService.save");
        userService.save();
        System.out.println("结束调用 userService.save");
    }
}
