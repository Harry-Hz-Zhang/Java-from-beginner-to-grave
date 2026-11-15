package org.harry.simple.entity;

/**
 * 练习一：给简单类型（int）注入值。
 * <p>
 * 讲义里这个类没有 getter，这里补一个，只为在测试里能断言 age 的取值，不影响注入本身。
 */
public class User {

    private int age;

    // 使用 set 注入，Spring 通过反射调用这个方法。
    // XML 里写的是字符串 "20"，Spring 的类型转换器会先把它转成 int 再传进来。
    public void setAge(int age) {
        this.age = age;
    }

    public int getAge() {
        return age;
    }

    @Override
    public String toString() {
        return "User{" +
                "age=" + age +
                '}';
    }
}
