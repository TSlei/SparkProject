package com.spark.test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;

public class ReplaceSOH {
	public static void main(String args[]) throws Exception {
		File file = new File("D:\\user_visit_action.txt");
		String outputfile = "D:\\user_visit_action2.txt";
		BufferedReader br = new BufferedReader(new FileReader(file));
		StringBuffer stringBuffer = new StringBuffer();
		
		String s = null;
		while ((s = br.readLine()) != null) {
			String[] fields = s.split("");
			for (int i = 0; i < fields.length; i++) {
				if(i==fields.length-1){
//					System.out.print(fields[i] + "\r");
					stringBuffer.append(fields[i] + "\r");
				}else{
//					System.out.print(fields[i] + "\t");
					stringBuffer.append(fields[i] + "\t");
				}
			}

		}
		System.out.println(stringBuffer.toString());
		method2(outputfile, stringBuffer.toString());
		br.close();
		
	}

	public static void method2(String file, String conent) {
		BufferedWriter out = null;
		try {
			out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true)));
			out.write(conent);
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				out.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
