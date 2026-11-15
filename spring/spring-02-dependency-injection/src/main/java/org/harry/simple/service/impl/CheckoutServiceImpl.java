package org.harry.simple.service.impl;

import org.harry.simple.dao.GoodsDao;
import org.harry.simple.entity.Goods;
import org.harry.simple.entity.Order;
import org.harry.simple.service.CheckoutService;

import java.util.List;

/**
 * 注入 GoodsDao，再把查到的商品组装成 Order。
 * 这一层存在的意义是把 pool、dao、entity 三者串起来，验证它们都在同一个容器里。
 */
public class CheckoutServiceImpl implements CheckoutService {

    private GoodsDao goodsDao;

    public void setGoodsDao(GoodsDao goodsDao) {
        this.goodsDao = goodsDao;
    }

    @Override
    public void checkout() {
        List<Goods> goodsList = goodsDao.findAll();
        Order order = new Order(goodsList.toArray(new Goods[0]));
        System.out.println("[CheckoutServiceImpl] 下单成功：" + order);
    }
}
