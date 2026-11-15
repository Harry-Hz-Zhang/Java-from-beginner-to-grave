package org.harry.simple.test;

import org.harry.simple.entity.Customer;
import org.harry.simple.entity.MathExpr;
import org.harry.simple.entity.MyTime;
import org.harry.simple.entity.Order;
import org.harry.simple.entity.People;
import org.harry.simple.entity.Person;
import org.harry.simple.entity.Student;
import org.harry.simple.entity.User;
import org.harry.simple.entity.Vip;
import org.harry.simple.pool.MyDataSource;
import org.harry.simple.service.CheckoutService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 各种数据类型的注入练习合集。
 * 一个知识点对应一个配置文件，全部放在 resources/xml 目录下，
 * 这里统一加 xml/ 前缀，避免 15 个配置文件把 resources 根目录堆满。
 */
public class InjectionTypesTest {

    private ApplicationContext load(String fileName) {
        return new ClassPathXmlApplicationContext("xml/" + fileName);
    }

    /** 练习一：简单类型 */
    @Test
    public void testSimpleType() {
        User user = load("spring-simple-type.xml").getBean("userBean", User.class);
        System.out.println(user);
        assertEquals(20, user.getAge());
    }

    /** 练习二：数据源经典案例 */
    @Test
    public void testDataSource() {
        MyDataSource dataSource = load("spring-datasource.xml").getBean("dataSource", MyDataSource.class);
        System.out.println(dataSource);
        assertTrue(dataSource.toString().contains("jdbc:mysql://localhost:3306/spring"));
    }

    /** 练习三：级联属性赋值 */
    @Test
    public void testCascade() {
        Student student = load("spring-cascade.xml").getBean("studentBean", Student.class);
        System.out.println(student);
        assertEquals("张三", student.getName());
        assertEquals("高三一班", student.getClazz().getName());
    }

    /** 练习四：数组，元素是简单类型 */
    @Test
    public void testArraySimple() {
        Person person = load("spring-array-simple.xml").getBean("personBean", Person.class);
        System.out.println(person);
        assertEquals(3, person.getFavoriteFoods().length);
        assertEquals("汉堡", person.getFavoriteFoods()[1]);
    }

    /** 练习五：数组，元素是非简单类型 */
    @Test
    public void testArray() {
        Order order = load("spring-array.xml").getBean("orderBean", Order.class);
        System.out.println(order);
        assertEquals(2, order.getGoods().length);
        assertEquals("西瓜", order.getGoods()[0].getName());
    }

    /** 练习六：List 有序可重复 */
    @Test
    public void testList() {
        People people = load("spring-list.xml").getBean("peopleBean", People.class);
        System.out.println(people);
        assertEquals(5, people.getNames().size());
        assertEquals("狼", people.getNames().get(4));
    }

    /** 练习七：Set 无序不可重复 */
    @Test
    public void testSet() {
        People people = load("spring-set.xml").getBean("peopleBean", People.class);
        System.out.println(people);
        assertEquals(3, people.getPhones().size());
        assertTrue(people.getPhones().contains("110"));
    }

    /** 练习八：Map */
    @Test
    public void testMap() {
        People people = load("spring-map.xml").getBean("peopleBean", People.class);
        System.out.println(people);
        assertEquals("上海浦东", people.getAddrs().get(2));
        assertEquals(3, people.getAddrs().size());
    }

    /** 练习九：Properties */
    @Test
    public void testProps() {
        People people = load("spring-props.xml").getBean("peopleBean", People.class);
        System.out.println(people);
        assertEquals("com.mysql.cj.jdbc.Driver", people.getProperties().getProperty("driver"));
        assertEquals("root", people.getProperties().getProperty("username"));
    }

    /** 练习十：null 与空字符串的三种写法 */
    @Test
    public void testNullAndEmpty() {
        ApplicationContext ctx = load("spring-null.xml");

        Vip empty = ctx.getBean("vipEmptyBean", Vip.class);
        Vip nullTag = ctx.getBean("vipNullTagBean", Vip.class);
        Vip nullOmit = ctx.getBean("vipNullOmitBean", Vip.class);

        System.out.println(empty + "\n" + nullTag + "\n" + nullOmit);

        assertEquals("", empty.getEmail());   // 空串，长度为 0
        assertNull(nullTag.getEmail());       // null 标签，就是 null
        assertNull(nullOmit.getEmail());      // 不赋值，默认也是 null
    }

    /** 练习十一：值里含有 XML 特殊符号 */
    @Test
    public void testSpecial() {
        ApplicationContext ctx = load("spring-special.xml");
        MathExpr escaped = ctx.getBean("mathBean", MathExpr.class);
        MathExpr cdata = ctx.getBean("mathCdataBean", MathExpr.class);

        System.out.println(escaped);
        System.out.println(cdata);

        assertEquals("2 < 3 && 3 > 1", escaped.getResult());
        assertEquals("2 < 3 && 3 > 1", cdata.getResult());
    }

    /** 练习十二：p 命名空间 */
    @Test
    public void testP() {
        Customer customer = load("spring-p.xml").getBean("customerBean", Customer.class);
        System.out.println(customer);
        assertEquals("zhangsan", customer.getName());
        assertEquals(20, customer.getAge());
        assertNotNull(customer.getFavoriteGoods());
        assertEquals("西瓜", customer.getFavoriteGoods().getName());
    }

    /** 练习十三：c 命名空间 */
    @Test
    public void testC() {
        MyTime myTime = load("spring-c.xml").getBean("myTimeBean", MyTime.class);
        System.out.println(myTime);
        assertEquals(2008, myTime.getYear());
        assertEquals(8, myTime.getMonth());
        assertEquals(8, myTime.getDay());
    }

    /** 练习十四：util 命名空间，一份配置被两个 bean 复用 */
    @Test
    public void testUtil() {
        ApplicationContext ctx = load("spring-util.xml");

        Properties props = ctx.getBean("jdbcProps", Properties.class);
        System.out.println("配置内容：" + props);
        System.out.println(ctx.getBean("dataSource1"));
        System.out.println(ctx.getBean("dataSource2"));

        assertEquals("root", props.getProperty("username"));
        // 两个对象引用的是同一个 Properties 实例
        assertTrue(ctx.getBean("dataSource1", org.harry.simple.pool.MyDataSource1.class)
                .getProperties() == props);
    }

    /** 练习十五：综合装配，验证整条链路能跑通 */
    @Test
    public void testAssemble() {
        CheckoutService checkoutService = load("spring-assemble.xml")
                .getBean("checkoutService", CheckoutService.class);
        checkoutService.checkout();
        assertNotNull(checkoutService);
    }
}
