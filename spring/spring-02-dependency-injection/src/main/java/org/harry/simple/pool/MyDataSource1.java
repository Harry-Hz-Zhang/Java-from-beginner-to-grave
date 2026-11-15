package org.harry.simple.pool;

import java.util.Properties;

/**
 * 用 util 命名空间注入一份 Properties 配置。
 * <p>
 * 它和 MyDataSource2 的内容几乎一样，讲义之所以写两个几乎相同的类，
 * 就是为了证明同一份 &lt;util:properties&gt; 能被两个不同的 bean 同时引用。
 */
public class MyDataSource1 {

    private Properties properties;

    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    public Properties getProperties() {
        return properties;
    }

    @Override
    public String toString() {
        return "MyDataSource1{" +
                "properties=" + properties +
                '}';
    }
}
