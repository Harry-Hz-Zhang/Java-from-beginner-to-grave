package org.harry.simple.entity;

/**
 * 注入的字符串里含有 XML 特殊符号。
 * <p>
 * 讲义里这个类叫 Math，这里改名为 MathExpr。
 * 原因：java.lang.Math 是自动导入的，同包下再定义一个 Math 会遮蔽它，
 * 以后想用 Math.abs 就得写全限定名，初学阶段很容易在这里卡住。
 */
public class MathExpr {

    private String result;

    public void setResult(String result) {
        this.result = result;
    }

    public String getResult() {
        return result;
    }

    @Override
    public String toString() {
        return "MathExpr{" +
                "result='" + result + '\'' +
                '}';
    }
}
