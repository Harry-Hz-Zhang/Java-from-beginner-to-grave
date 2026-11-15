import org.harry.simple.controller.OrderController;
import org.harry.simple.controller.UserController;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class SpringTest {
    @Test
    public void test1(){
//        UserController userController = new UserController();
//        userController.saveUser();

        ApplicationContext applicationContext = new ClassPathXmlApplicationContext("applicationContext.xml");
        UserController userController = applicationContext.getBean("userControllerBean", UserController.class);
        userController.saveUser();
    }

    @Test
    public void test2(){
        ApplicationContext applicationContext = new ClassPathXmlApplicationContext("applicationContext2.xml");
        OrderController orderController = applicationContext.getBean("orderControllerBean", OrderController.class);
        orderController.saveOrder();
    }
}
