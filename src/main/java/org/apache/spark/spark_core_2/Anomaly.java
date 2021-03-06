package org.apache.spark.spark_core_2;

import org.apache.spark.api.java.*;
import org.apache.spark.Accumulator;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.api.java.function.VoidFunction;

import scala.Tuple2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

/*Shipping company web site allows their clients to track container by its number. 
Those searches are been recording in the web site logs.
This program browsing web server logs for container numbers and counting the total search per container to find 
the excessive searches (3 times more than the calculated average - can be customized) which points to possible malfunction client search script 
(may leads to website performance impact) or suspected activity for specific container (client is too nervous). 
The results can be a base for the next action. */

public class Anomaly {

	public static void main(String[] args) {
		
		//Web logs are aggregated in Hadoop HDFS folder	
		String logFile = "hdfs://cdh-nn2/user/tom/weblog/*"; 
		    
		//Initiate SparkContext
		SparkConf conf = new SparkConf().setAppName("Simple Application").setMaster("local");
		JavaSparkContext sc = new JavaSparkContext(conf);
		
		Accumulator<Integer> accum = sc.accumulator(0);
		    
		//Set logs output to error only
		Logger rootLogger = Logger.getRootLogger();
		rootLogger.setLevel(Level.ERROR);
		    
		//Create RDD and load all web logs in the folder - each line as string
		JavaRDD<String> logDataRaw = sc.textFile(logFile);
		    
		//Filter out comment lines and output to the new RDD the only lines with container number  
		JavaRDD<String> logData = logDataRaw.filter(new Function<String, Boolean>() {
			public Boolean call(String s) {
		    	if (s.charAt(0) == '#') {
		    		return false;
		    	} else {
		    		if (s.contains("rev=")) {
		    			return true;
		    		} else {
		    		return false;
		    		}
		    	}
		    }
		});
		   
		//Browse each line with container number, split it by space and return as RDD only container numbers
		JavaRDD<String> logLine = logData.map(new Function<String, String>() {
		    public String call(String s) {
		    	return s.split(" ")[5];
		    }
		});
		  
		//As preparation for map-reduce task create RDD with tuple2<container number, 1> 
		JavaPairRDD<String, Integer> containers = logLine.mapToPair(new PairFunction<String, String, Integer>() {

			public Tuple2<String, Integer> call(String container) {
				Tuple2<String, Integer> t = new Tuple2(container,1);
				return t;
			}
		});
		  
		//Reduce by container number and count all "1" for each container - create RDD with pair ,container number, total>
		JavaPairRDD<String, Integer> reducedContainers = containers.reduceByKey(new Function2<Integer, Integer, Integer>() {
			public Integer call(Integer count0, Integer count1) {
				return Integer.valueOf(count0.intValue() + count1.intValue());
			}
		});
		
		reducedContainers.foreach(new VoidFunction<Tuple2<String,Integer>>() {
			public void call(Tuple2<String, Integer> unitCount) {
				accum.add(unitCount._2());
			}
		});
		
		//Count average number of searches.
		int value = accum.value();
		int totalCount = (int) reducedContainers.count();
		int upAverage = (int)3*(value/totalCount);
		
		//Create RDD with containers which have been searched  3 times more than average.  
		JavaPairRDD<String, Integer> anomaly = reducedContainers.filter(new Function<Tuple2<String, Integer>, Boolean>() {
			public Boolean call(Tuple2<String, Integer> anomalyContainer) throws Exception {
				if (anomalyContainer._2() > upAverage) {
					return true;
				} else {
				return false;
				}
			}
		});
		
		int anomalyCount = (int) anomaly.count();
		
		//Print the result to the client's console - for developer purpose only. Can be sent to file as well.
		System.out.printf("Anomaly containers: %d from total containers %d", anomalyCount, totalCount);
		
		//Close SparkContext to avoid the memory leak.
		sc.close();
	}

}
