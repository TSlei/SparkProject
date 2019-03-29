package com.spark.model;

/**
 * 页面切片转化率
 * @author Administrator
 *
 */
public class PageSplitConvertRate {

	private long taskId;
	private String convertRate;
	
	public long getTaskId() {
		return taskId;
	}

	public void setTaskId(long taskId) {
		this.taskId = taskId;
	}

	public String getConvertRate() {
		return convertRate;
	}
	
	public void setConvertRate(String convertRate) {
		this.convertRate = convertRate;
	}
	
}
