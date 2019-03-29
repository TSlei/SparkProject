package com.spark.test;

import java.util.Arrays;

import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

public class test2 {

	public static void main(String[] args) {
		StructType schema = DataTypes.createStructType(Arrays.asList(DataTypes.createStructField("data", DataTypes.StringType, true)));
	}
}
