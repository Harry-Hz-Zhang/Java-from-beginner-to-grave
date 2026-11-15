package org.harry.simple.entity;

/**
 * 练习十：区分「空字符串」与「null」两种注入结果。
 * 同一个类配三个 bean，分别验证 value=""、&lt;null/&gt;、以及根本不写属性。
 */
public class Vip {

    private String email;

    public void setEmail(String email) {
        this.email = email;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public String toString() {
        return "Vip{" +
                "email='" + email + '\'' +
                '}';
    }
}
