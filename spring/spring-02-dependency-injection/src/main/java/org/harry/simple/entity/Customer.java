package org.harry.simple.entity;

/**
 * 练习十二：用 p 命名空间简化 set 注入。
 * <p>
 * 比讲义多了一个 favoriteGoods 属性，用来演示 p 命名空间注入非简单类型的写法
 * p:favoriteGoods-ref="..."。讲义正文只给了结论没给例子，这里补上。
 */
public class Customer {

    private String name;
    private int age;
    private Goods favoriteGoods;

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public int getAge() {
        return age;
    }

    public void setFavoriteGoods(Goods favoriteGoods) {
        this.favoriteGoods = favoriteGoods;
    }

    public Goods getFavoriteGoods() {
        return favoriteGoods;
    }

    @Override
    public String toString() {
        return "Customer{" +
                "name='" + name + '\'' +
                ", age=" + age +
                ", favoriteGoods=" + favoriteGoods +
                '}';
    }
}
