package com.harry.lab.sql;

/**
 * LIKE 模式串的转义工具，与生产 infrastructure/repository/SqlSearch.java 第 6 到 9 行一致。
 *
 * <p>第一，用户输入的 % 和 _ 在 LIKE 里有通配含义，不转义就等于让用户自己写通配符，
 * 轻则结果不对，重则触发全表扫描。
 *
 * <p>第二，转义字符选感叹号，因为它比反斜杠少一层 Java 字符串转义，读起来清楚。
 * 先把感叹号自身替换成两个感叹号，再转义 % 和 _，顺序不能反。
 *
 * <p>第三，SQL 侧必须同时写 ESCAPE '!'，否则数据库不知道哪个字符是转义符。
 */
final class SqlSearch {

    private SqlSearch() {
    }

    static String contains(String query) {
        return "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
