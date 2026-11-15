package com.harry.fundamentals.ch01.Var;

public class IntDetail {
    public static void main(String[] args) {
        // Java 的整形常量默认是 int 类型，除非在后面加上一个 l 就可以变成 long。
        int n1 = 1;  // 4 byte
        // int n2 = 2L; // 这是不对的，内存占用变小了
        long n2 = 2L;  // 这是对的
    }
}
