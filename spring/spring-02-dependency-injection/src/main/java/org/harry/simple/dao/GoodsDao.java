package org.harry.simple.dao;

import org.harry.simple.entity.Goods;

import java.util.List;

/**
 * 商品查询接口，用来给连接池找一个真实的调用方。
 * 有了它，MyPool 才不会是一个孤立对象。
 */
public interface GoodsDao {

    List<Goods> findAll();
}
