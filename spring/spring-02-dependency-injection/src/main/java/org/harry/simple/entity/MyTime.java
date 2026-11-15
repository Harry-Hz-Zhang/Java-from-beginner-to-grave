package org.harry.simple.entity;

/**
 * 练习十三：用 c 命名空间简化构造方法注入。
 * <p>
 * 这个类故意只留三个参数的构造方法、不提供任何 setter，
 * 用来证明 c 命名空间依赖的是构造方法，而不是 setter。
 */
public class MyTime {

    private int year;
    private int month;
    private int day;

    public MyTime(int year, int month, int day) {
        this.year = year;
        this.month = month;
        this.day = day;
    }

    public int getYear() {
        return year;
    }

    public int getMonth() {
        return month;
    }

    public int getDay() {
        return day;
    }

    @Override
    public String toString() {
        return "MyTime{" +
                "year=" + year +
                ", month=" + month +
                ", day=" + day +
                '}';
    }
}
