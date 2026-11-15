package org.harry.simple.entity;

/**
 * 数组与集合里的「非简单类型」元素。
 * 在 XML 里它只能通过 ref 引用，不能直接写成 value。
 */
public class Goods {

    private String name;

    public Goods() {
    }

    public Goods(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return "Goods{" +
                "name='" + name + '\'' +
                '}';
    }
}
