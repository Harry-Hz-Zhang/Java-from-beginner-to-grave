package com.harry.fundamentals.ch01.Var;

public class CharDetail {
    public static void main(String[] args) {
        // 在 Java 语言中，字符型（char）底层实际上保存的是对应的 Unicode 编码整数。
        // 当程序直接输出字符型变量时，控制台会按照字符集输出对应的字符。
        char c1 = 97;
        System.out.println(c1); // 输出字符 'a'

        // 当需要查看字符对应的数值编码时，可以通过将字符强制转换为整数类型（int）来实现。
        char c2 = 'a';
        System.out.println((int)c2); // 输出整数 97

        // 中文字符在 Unicode 字符集中同样拥有对应的十进制编码数值。
        char c3 = '韩';
        System.out.println((int)c3); // 输出字符 '韩' 对应的 Unicode 编码：38889

        // 当把整数数值直接赋值给字符类型时，底层会解析为该数值对应的 Unicode 字符。
        char c4 = 38889;
        System.out.println(c4); // 输出编码对应的中文字符：'韩'

        // 字符类型可以参与数学加减运算，计算时系统会自动使用字符对应的数值进行算术计算。
        System.out.println('a' + 10); // 字符 'a' 转换为整数 97，相加后输出整数 107

        // 字符常量在编译阶段会提前完成常量表达式计算，因此允许直接赋给 char 类型的变量。
        char c5 = 'b' + 1; // 字符 'b' 编码为 98，加上 1 等于 99
        System.out.println((int)c5); // 输出计算后的编码数值：99
        System.out.println(c5);        // 输出编码 99 对应的 ASCII 字符：'c'
    }
}
