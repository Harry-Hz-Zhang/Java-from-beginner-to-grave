package org.harry.simple.dao.impl;

import org.harry.simple.dao.GoodsDao;
import org.harry.simple.entity.Goods;
import org.harry.simple.pool.MyPool;

import java.util.List;

/**
 * 通过 set 注入拿到装配好的连接池，证明 dao 层可以直接使用容器里的对象。
 */
public class GoodsDaoImpl implements GoodsDao {

    private MyPool pool;

    public void setPool(MyPool pool) {
        this.pool = pool;
    }

    @Override
    public List<Goods> findAll() {
        System.out.println("[GoodsDaoImpl] 当前使用的连接池：" + pool);
        return List.of(new Goods("西瓜"), new Goods("苹果"));
    }
}
