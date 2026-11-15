package com.harry.fundamentals.ch_11_线程.createThread;

class TestThread extends Thread {
    public TestThread() {}
    public TestThread(String name) {
        super(name);
    }
    @Override
    public void run() {
        String name = Thread.currentThread().getName();
        for (int i = 0; i < 10; i++) {
            System.out.println(i);
        }
    }
}

public class Test {
    public static void main(String[] args) {
        TestThread t1 = new TestThread("t1");
        TestThread t2 = new TestThread("t2");
        // 启动线程
        t1.start(); // 注意：一个线程对象的start()方法只能被调用一次。
        t2.start();
        for(int i = 0; i < 10; i++) {
            System.out.println("主线程：" + i);
        }
    }
}

/*
* 思考1：线程对象调用run方法和调用start方法区别？
* 线程对象调用run()方法不开启线程，仅是对象调用方法并在主线程中执行。
* 线程对象调用start()开启线程，并让JVM调用run()方法在开启的线程中执行。
* */