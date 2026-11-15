package com.harry.fundamentals.ch01.Var;

public class FloatDetail {
    public static void main(String[] args) {
        // 1. 浮点型常量默认为 double 类型；声明 float 变量时必须在数值后添加 'f' 或 'F'。
        // float num1 = 1.1; // 编译报错：无法将 double 直接赋值给 float

        // float f1 = 1.0;  这是错误的
        float f2 = 1.012341234F;

        float num2 = 1.1F;   // 正确：显式指定为单精度浮点数
        double num3 = 1.1;   // 正确：双精度浮点数
        double num4 = 1.1f;  // 正确：小精度类型（float）可以自动转换为大精度类型（double）

        // 2. 浮点数的十进制表示法（小数点前为 0 时可以省略）
        double num5 = .123;  // 等价于 0.123
        System.out.println("num5 = " + num5);

        // 3. 科学计数法表示：E 或 e 代表以 10 为底的指数运算
        System.out.println("5.12e2 = " + 5.12e2);    // 5.12 * 10^2 = 512.0
        System.out.println("5.12E-2 = " + 5.12E-2);  // 5.12 * 10^-2 = 0.0512

        // 4. float 与 double 的精度对比（double 的有效位数显著高于 float）
        double num9 = 2.1234567851;
        float num10 = 2.1234567851F;
        System.out.println("num9 (double) = " + num9);
        System.out.println("num10 (float) = " + num10);

        // 5. 浮点数陷阱：二进制无法精确表示所有十进制小数，会导致运算产生微小的精度偏差
        double num11 = 2.7;
        double num12 = 8.1 / 3; // 理论上为 2.7，实际存储值接近 2.6999999999999997
        System.out.println("num11 = " + num11);
        System.out.println("num12 = " + num12);

        // 错误对比方式：由于精度损失，直接使用关系运算符比较可能判定为不相等
        if (num11 == num12) {
            System.out.println("num11 == num12 相等");
        } else {
            System.out.println("由于浮点运算精度损失，num11 与 num12 直接比较不相等");
        }
        // 正确对比方式：计算两者差值的绝对值，当差值小于预设精度阈值时判定为相等
        double del = Math.abs(num11 - num12);
        if (del <= 0.00001) {
            System.out.println(del + " num11 == num12");
        }
    }
}
