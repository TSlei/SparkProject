package com.spark.model;

/**
 * 用户广告点击量
 *
 */
public class AdUserClickCount {

	private String date;
	private long userId;
	private long adId;
	private long clickCount;
	
	public String getDate() {
		return date;
	}
	
	public void setDate(String date) {
		this.date = date;
	}

	public long getUserId() {
		return userId;
	}

	public void setUserId(long userId) {
		this.userId = userId;
	}

	public long getAdId() {
		return adId;
	}

	public void setAdId(long adId) {
		this.adId = adId;
	}

	public long getClickCount() {
		return clickCount;
	}
	
	public void setClickCount(long clickCount) {
		this.clickCount = clickCount;
	}
	
}
