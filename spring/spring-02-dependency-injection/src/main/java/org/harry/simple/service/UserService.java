package org.harry.simple.service;

public interface UserService {
    // 1. 省略修饰符，编译器默认它是 public abstract，代表规范，不能写 {}
    void save();
    // 2. 显式加上 default 关键字的 public 方法，可以写 {} 作为默认实现
    default void saveWithLog() {
        printLog("开始保存用户");
        save();
    }
    // 3. private 方法对外部实现类不可见，只能在接口内部被调用，因此必须写 {}
    private void printLog(String message) {
        System.out.println("[日志] " + message);
    }
}