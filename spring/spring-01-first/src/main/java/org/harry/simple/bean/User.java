package org.harry.simple.bean;

public class User {
    // spring 调用的其实是这个无参构造方法
    public User() {
        System.out.println("User的无参数构造方法执行");
    }
//    public User(String name){
//        System.out.println("User的有参数构造方法执行");
//    }
}

/**
 *  关于这里的无参构造方法：
 *  如果是写做这个样子：public class User { }
 *
 *  其实编译器会补充一个构造方法的
 *  触发条件是“一个构造方法都没写”。
 *  只要你自己声明了任意一个构造方法，哪怕只是 Vip(String name)，编译器就再也不补了。
 * */
