package org.harry.simple.po;

/**
 * 数据库连接参数的强类型载体，对应讲义里那份 Properties 配置。
 * <p>
 * 用 Properties 存连接参数时，键名写错要到运行时取值那一刻才会暴露；
 * 换成字段之后，写错属性名在启动容器时就会报错，这也是后面 JDBC 章节真正会用的写法。
 */
public class DbConfig {

    private String driver;
    private String url;
    private String username;
    private String password;

    public void setDriver(String driver) {
        this.driver = driver;
    }

    public String getDriver() {
        return driver;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUsername() {
        return username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPassword() {
        return password;
    }

    @Override
    public String toString() {
        return "DbConfig{" +
                "driver='" + driver + '\'' +
                ", url='" + url + '\'' +
                ", username='" + username + '\'' +
                ", password='" + password + '\'' +
                '}';
    }
}
