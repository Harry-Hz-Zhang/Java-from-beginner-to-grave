package com.harry.fundamentals.ch01.Var;

public class VariableDemo {

    public static void main(String[] args) {
        int age = 18;
        char gen = '男';
        double l = 113.1;
        String name = "harry";
        System.out.println(name + "是一个 " + gen + " 今年 " + age + " 岁");
        System.out.println(l);
    }

    public static int add(int a, int b) {
        return a + b;
    }
}
