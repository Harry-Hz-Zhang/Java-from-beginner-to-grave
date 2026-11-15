package com.harry.fundamentals.ch01.Var;

public class ConvertDetail {
    public static void main(String[] args) {
        autoConvert();
    }

    static void autoConvert(){
        // 细节 1：当多种类型的数据进行混合运算时，系统首先自动将所有数据提升为容量最大的数据类型，随后再执行计算。
        int n1 = 10;
        // float d1 = n1 + 1.1; // 编译报错：浮点字面量 1.1 默认为 double 类型，运算结果被提升为 double 类型，无法直接赋值给 float 变量。
        double d1 = n1 + 1.1;   // 编译正确：整个加法表达式的结果类型为 double，与接收变量类型相匹配。
        float d2 = n1 + 1.1F;   // 编译正确：因为 1.1F 是 float 类型，运算结果被提升为 float 类型。

        // 细节 2：当把容量或精度大的数据类型赋值给容量或精度小的数据类型时，编译器会直接报错；反之则会自动进行类型转换。
        // int n2 = 1.1; // 编译报错：系统无法将 double 类型自动转换为 int 类型。

        // 细节 3：byte、short 与 char 之间互不相通，不会发生相互自动转换。
        // 当把具体的常数赋值给 byte 时，编译器会首先判断该数值是否在 byte 的有效取值范围（-128 到 127）内。
        byte b1 = 10; // 编译正确：整型字面量 10 处于 byte 类型的有效数值范围内。

        int n2 = 1;
        // byte b2 = n2; // 编译报错：当把变量赋值给目标类型时，编译器只根据变量声明的 int 类型进行安全检查，不会判定运行时的具体数值。
        // char c1 = b1; // 编译报错：byte 类型不能自动转换为 char 类型。

        // 细节 4：byte、short 与 char 能够一起参与算术计算，但在计算开始时它们均会首先被提升为 int 类型。
        byte b2 = 1;
        byte b3 = 2;
        short s1 = 1;
        short s2 = (short)(b2 + s1); // 编译报错：byte 与 short 运算后的结果已被自动提升为 int 类型，不能直接赋给 short 变量。
//        int s2 = b2 + s1;      // 编译正确：运算结果类型为 int，与接收变量匹配。
        // byte b4 = b2 + b3;  // 编译报错：即使两个操作数全部为 byte 类型，相加时的运算结果也会被统一提升为 int 类型。

        // 细节 5：布尔类型（boolean）属于独立类型，不参与任何数值类型的自动转换。
        boolean pass = true;
        // int num100 = pass; // 编译报错：boolean 类型与数值类型互不兼容，不能相互转换。

        // 细节 6：自动提升原则表明表达式的最终结果类型会自动提升为所有操作数中容量最大的类型。
        byte b4 = 1;
        short s3 = 100;
        int num200 = 1;
        float num300 = 1.1F;
        // 在下方表达式中，容量最大的操作数是 float 类型的 num300，因此整个表达式的计算结果为 float 类型。
        // 随后在赋值给 double 变量时，系统会将该 float 结果自动提升为 double 类型。
        double num500 = b4 + s3 + num200 + num300;
        System.out.println("num500 的最终计算结果为: " + num500);
    }

    static void froceConvert(){
        int x = (int)1.5*4 + (int)(1.5*4);


        char c1 = 100; //ok
        int m = 100; //ok
        //char c2 = m; //错误
        char c3 = (char)m; //ok
        System.out.println(c3);//100 对应的字符, d 字符
    }
}
