package org.harry.simple.test;

import org.harry.simple.bean.User;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class SpringTest {
    @Test
    public void testFirst(){
        // 初始化Spring容器上下文（解析beans.xml文件，创建所有的bean对象）
        ApplicationContext applicationContext = new ClassPathXmlApplicationContext("beans.xml");
        // 根据id获取bean对象
        Object userBean = applicationContext.getBean("userBean");
        System.out.println(userBean);
    }

    /**
     * 测试一下同一个 bean 声明出来是否是一致的
     * YES
     *
     * */
    @Test
    public void testFirstSpringBean() {
        ApplicationContext applicationContext = new ClassPathXmlApplicationContext("beans.xml");
        var a = applicationContext.getBean("userBean");
        var b = applicationContext.getBean("userBean");
        System.out.println("a == b:" + (a == b));
    }

    /**
     * 测试一下 bean xml 中不同 bean id 是否可以重复
     * NO
     *
     * */
    @Test
    public void testFirstSpringBeanId() {
        ApplicationContext applicationContext = new ClassPathXmlApplicationContext("beans.xml");
        System.out.println(applicationContext);
    }

    /**
     * 测试一下是否可以同时载入多个 xml 配置
     * YES
     *
     * */
    @Test
    public void testMutiXML() {
        ApplicationContext applicationContext = new ClassPathXmlApplicationContext("beans.xml", "Spring.xml");
        System.out.println(applicationContext);

        User user = applicationContext.getBean("userBean", User.class); // 这个就是需要强制向下转型时候的写法
    }

    @Test
    void testXmlFile() {
        // spring的配置文件名是随意的。
        // spring配置文件存放的路径也是随意的（前提：使用以下这种方式的话，要求配置文件需要在类路径下）
        ApplicationContext context = new ClassPathXmlApplicationContext("xml/applicationContext.xml");
        Object obj = context.getBean("vipBean");
        System.out.println(obj);
    }
}
