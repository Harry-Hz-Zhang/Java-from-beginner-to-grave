package org.harry.simple.entity;

import java.util.Arrays;

/**
 * 练习五：一个订单里有多个商品，属性类型是「对象数组」。
 * XML 里要用 array 套 ref，因为元素不是简单类型。
 */
public class Order {

    private Goods[] goods;

    public Order() {
    }

    public Order(Goods[] goods) {
        this.goods = goods;
    }

    public void setGoods(Goods[] goods) {
        this.goods = goods;
    }

    public Goods[] getGoods() {
        return goods;
    }

    @Override
    public String toString() {
        return "Order{" +
                "goods=" + Arrays.toString(goods) +
                '}';
    }
}
