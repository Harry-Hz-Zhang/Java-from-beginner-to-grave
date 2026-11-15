package org.harry.simple.pool;

import java.util.Properties;

/**
 * 与 MyDataSource1 内容几乎相同，用来演示配置复用。
 */
public class MyDataSource2 {

    private Properties properties;

    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    public Properties getProperties() {
        return properties;
    }

    @Override
    public String toString() {
        return "MyDataSource2{" +
                "properties=" + properties +
                '}';
    }
}
