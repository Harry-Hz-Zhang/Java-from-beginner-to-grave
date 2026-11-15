package org.harry.simple.entity;

import java.util.Arrays;

/**
 * 练习四：数组里的元素是简单类型。
 * XML 里要用 array 套 value，元素是字符串所以不需要 ref。
 */
public class Person {

    private String[] favoriteFoods;

    public void setFavoriteFoods(String[] favoriteFoods) {
        this.favoriteFoods = favoriteFoods;
    }

    public String[] getFavoriteFoods() {
        return favoriteFoods;
    }

    @Override
    public String toString() {
        // 数组本身没有可读的 toString，必须借助 Arrays.toString
        return "Person{" +
                "favoriteFoods=" + Arrays.toString(favoriteFoods) +
                '}';
    }
}
