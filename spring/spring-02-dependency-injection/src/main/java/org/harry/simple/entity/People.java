package org.harry.simple.entity;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 练习六、七、八、九：把四种集合容器放在同一个类里，方便横向对比。
 * <p>
 * names   —— List，有序可重复
 * phones  —— Set，无序不可重复
 * addrs   —— Map，键值对
 * properties —— Properties，键和值都只能是字符串的 Map
 * <p>
 * 四个属性分别由 spring-list.xml、spring-set.xml、spring-map.xml、
 * spring-props.xml 单独注入，互不干扰。
 */
public class People {

    private List<String> names;
    private Set<String> phones;
    private Map<Integer, String> addrs;
    private Properties properties;

    public void setNames(List<String> names) {
        this.names = names;
    }

    public List<String> getNames() {
        return names;
    }

    public void setPhones(Set<String> phones) {
        this.phones = phones;
    }

    public Set<String> getPhones() {
        return phones;
    }

    public void setAddrs(Map<Integer, String> addrs) {
        this.addrs = addrs;
    }

    public Map<Integer, String> getAddrs() {
        return addrs;
    }

    public void setProperties(Properties properties) {
        this.properties = properties;
    }

    public Properties getProperties() {
        return properties;
    }

    @Override
    public String toString() {
        return "People{" +
                "names=" + names +
                ", phones=" + phones +
                ", addrs=" + addrs +
                ", properties=" + properties +
                '}';
    }
}
