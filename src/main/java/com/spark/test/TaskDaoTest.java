package com.spark.test;

import com.spark.dao.ITaskDao;
import com.spark.dao.factory.DaoFactory;
import com.spark.model.Task;

/**
 * 任务管理DAO测试类
 *
 */
public class TaskDaoTest {
	
	public static void main(String[] args) {
		ITaskDao taskDao = DaoFactory.getTaskDao();
		Task task = taskDao.findById(2);
		System.out.println(task.getTaskName());  
	}
	
}
