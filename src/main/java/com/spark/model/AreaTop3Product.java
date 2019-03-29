package com.spark.model;

/**
 * 各区域top3热门商品
 * @author Administrator
 *
 */
public class AreaTop3Product {

	private long taskId;
	private String area;
	private String areaLevel;
	private long productId;
	private String cityInfos;
	private long clickCount;
	private String productName;
	private String productStatus;
	
	public long getTaskId() {
		return taskId;
	}
	
	public void setTaskId(long taskId) {
		this.taskId = taskId;
	}
	
	public long getProductId() {
		return productId;
	}
	
	public void setProductId(long productId) {
		this.productId = productId;
	}
	
	public String getArea() {
		return area;
	}
	
	public void setArea(String area) {
		this.area = area;
	}
	
	public String getAreaLevel() {
		return areaLevel;
	}
	
	public void setAreaLevel(String areaLevel) {
		this.areaLevel = areaLevel;
	}

	public String getCityInfos() {
		return cityInfos;
	}
	
	public void setCityInfos(String cityInfos) {
		this.cityInfos = cityInfos;
	}
	
	public long getClickCount() {
		return clickCount;
	}
	
	public void setClickCount(long clickCount) {
		this.clickCount = clickCount;
	}
	
	public String getProductName() {
		return productName;
	}
	
	public void setProductName(String productName) {
		this.productName = productName;
	}
	
	public String getProductStatus() {
		return productStatus;
	}
	
	public void setProductStatus(String productStatus) {
		this.productStatus = productStatus;
	}
	
}
