package com.harry.fundamentals.ch_11_线程.createThread;


// 这种方式避免了Java单继承的局限性，所以实现Runnable接口方式要通用一些。

// 1. 定义一个类并实现Runnable接口,然后重写Runnable接口的run()方法。
// 2. 通过Thread类创建线程对象，并把Runnable接口的实现类对象作为参数传递。
// 3. 调用线程对象的start()方法开启线程，并调用Runnable实现类的run()方法。

class TestRun implements Runnable {
    @Override
    public void run() {
        for (int i = 0; i < 10; i++) {
            System.out.println("name: " + Thread.currentThread().getName());
        }
    }
}

public class TestRunnable {
    public static void main(String[] args) {
        TestRun testRunnable = new TestRun();
        // 创建线程对象，将tr作为参数传递给Thread类的构造函数
        Thread th1 = new Thread(testRunnable, "线程A");
        Thread th2 = new Thread(testRunnable, "线程B");
        // 启动线程
        th1.start();
        th2.start();
        for(int i = 0; i < 10; i++) {
            System.out.println("线程名称：" + Thread.currentThread().getName());
        }
    }
}
