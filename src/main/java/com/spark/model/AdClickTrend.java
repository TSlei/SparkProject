package com.spark.model;

/**
 * 广告点击趋势
 *
 */
public class AdClickTrend {

	private String date;
	private String hour;
	private String minute;
	private long adId;
	private long clickCount;
	
	public String getDate() {
		return date;
	}
	
	public void setDate(String date) {
		this.date = date;
	}
	
	public String getHour() {
		return hour;
	}
	
	public void setHour(String hour) {
		this.hour = hour;
	}
	
	public String getMinute() {
		return minute;
	}
	public void setMinute(String minute) {
		this.minute = minute;
	}

	public long getClickCount() {
		return clickCount;
	}
	
	public void setClickCount(long clickCount) {
		this.clickCount = clickCount;
	}

	public long getAdId() {
		return adId;
	}

	public void setAdId(long adId) {
		this.adId = adId;
	}
	
}
