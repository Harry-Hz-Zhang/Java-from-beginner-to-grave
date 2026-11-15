package org.harry.simple.pool;

import org.harry.simple.po.DbConfig;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据源与连接参数组装成一个可复用的连接池骨架。
 * 这里 dataSource 的声明类型刻意写成接口 DataSource，而不是 MyDataSource。
 * 这样将来把 MyDataSource 换成 HikariCP 或者 Druid，本类一行代码都不用改，
 * 这正是「面向接口编程」在依赖注入场景下的实际价值。
 */
public class MyPool {

    private DataSource dataSource;
    private DbConfig dbConfig;

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void setDbConfig(DbConfig dbConfig) {
        this.dbConfig = dbConfig;
    }

    public Connection getConnection() throws SQLException {
        // 真实实现要在 JDBC 章节补上，这里先转调数据源
        return dataSource.getConnection();
    }

    @Override
    public String toString() {
        return "MyPool{" +
                "dataSource=" + dataSource +
                ", dbConfig=" + dbConfig +
                '}';
    }
}
