package org.harry.simple.entity;

/**
 * 练习三的被引用对象。
 * <p>
 * Spring 注入属性的固定流程是「先反射创建对象，再反射调用 setter」，
 * 所以这里必须保留无参构造方法，否则容器连 Clazz 实例都造不出来。
 */
public class Clazz {

    private String name;

    public Clazz() {
    }

    public Clazz(String name) {
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
        return "Clazz{" +
                "name='" + name + '\'' +
                '}';
    }
}
