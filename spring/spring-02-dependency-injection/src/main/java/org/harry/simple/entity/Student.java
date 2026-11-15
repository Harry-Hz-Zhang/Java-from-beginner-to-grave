package org.harry.simple.entity;

/**
 * 练习三：级联属性赋值。
 * <p>
 * 两个要点：
 * 1. getClazz() 不是可有可无的普通 getter，它是 XML 里能写 clazz.name 的前提，
 *    Spring 必须先通过它把 Clazz 引用取出来，才能继续往 Clazz 里赋值。
 * 2. XML 里 clazz 的注入必须写在 clazz.name 之前，顺序颠倒会拿到空引用。
 */
public class Student {

    private String name;
    private Clazz clazz;

    public Student() {
    }

    public Student(String name, Clazz clazz) {
        this.name = name;
        this.clazz = clazz;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setClazz(Clazz clazz) {
        this.clazz = clazz;
    }

    /** 级联赋值的硬性前提：少了这个方法，XML 写 clazz.name 会直接报错。 */
    public Clazz getClazz() {
        return clazz;
    }

    @Override
    public String toString() {
        return "Student{" +
                "name='" + name + '\'' +
                ", clazz=" + clazz +
                '}';
    }
}
