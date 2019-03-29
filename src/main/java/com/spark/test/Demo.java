package com.spark.test;

import java.math.BigDecimal;
import java.util.HashMap;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

public class Demo {
	public static void main(String[] args) {
//		Map<String, Map<String, Long>> dateHourCountMap = 
//				new HashMap<String, Map<String, Long>>();
//		
//		Long.valueOf(null);
//		System.out.println(dateHourCountMap.get("aaaa"));
		
		String json = "{\"id\":\"1\"}";
		JSONObject jsonObject = JSONObject.parseObject(json);
		Object key = jsonObject.get("id2");
		System.out.println(key);
		
		
	}
	
	public static void check(){
		String json = "{\"uuid\":\"5a3e22f8-c420-47d0-a720-dd442588bcf5\",\"maps\":{\"bLoadAvg1\": [{\"value\": 5,\"clock\": 1495448824},{\"value\": 2,\"clock\": 1495448828}],\"bLoadAvg5\": [{\"value\": 1,\"clock\": 1495448824},{\"value\":2,\"clock\": 1495448828}]}}";
		
		JSONObject jsonObject = JSONObject.parseObject(json);
		JSONObject object = JSONObject.parseObject(jsonObject.get("maps").toString());
		HashMap<String, Double> metricAvg = new HashMap<String, Double>();
		for(String key : object.keySet()){
			JSONArray array = JSONObject.parseArray(object.get(key).toString());
			System.out.println(array);
			double count = 0;
			for(Object o : array){
				JSONObject js = JSONObject.parseObject(o.toString());
				int value = Integer.valueOf(js.get("value").toString());
				count = count + value;
			}
			System.out.println("count = " + count);
			
			double avg = count/array.size();
			BigDecimal bg = new BigDecimal(avg);
			double avgresult = bg.setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
			metricAvg.put(key, avgresult);
		}
		
		for(String key : metricAvg.keySet()){
			System.out.println(key + ":" + metricAvg.get(key));
		}
	}
}
