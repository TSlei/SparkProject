package com.spark.test;

/**
 * 单例模式Demo
 * 
 */
public class Singleton {

	// 首先必须有一个私有的静态变量，来引用自己即将被创建出来的单例
	private static Singleton instance = null;
	
	/**
	 * 必须对自己的构造方法使用private进行私有化
	 * 这样才能保证外界的代码不能随意的创建类的实例
	 */
	private Singleton() {
		
	}
	
	/**
	 * 最后，需要有一个共有的，静态方法
	 * (此方法并发性能大幅度降低)
	 * 
	 */
	public static Singleton getInstance() {
		// 两步检查机制
		// 首先第一步，多个线程过来的时候，判断instance是否为null
		if(instance == null) {
			// 在这里，进行多个线程的同步
			// 同一时间，只能有一个线程获取到Singleton Class对象的锁,进入后续的代码
			// 其他线程，都是只能够在原地等待，获取锁
			synchronized(Singleton.class) {
				// 只有第一个获取到锁的线程，进入到这里，会发现是instance是null
				// 然后才会去创建这个单例
				// 此后，线程，哪怕是走到了这一步，也会发现instance已经不是null了
				// 就不会反复创建一个单例
				if(instance == null) {
					instance = new Singleton();
				}
			}
		}
		return instance;
	}
	
}
