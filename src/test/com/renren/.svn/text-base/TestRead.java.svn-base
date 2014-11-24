package com.renren;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.compress.BZip2Codec;
import org.apache.hadoop.io.compress.BlockDecompressorStream;
import org.apache.hadoop.io.compress.CompressionInputStream;
import org.apache.hadoop.io.compress.SnappyCodec;
import org.apache.hadoop.io.compress.bzip2.BZip2DummyDecompressor;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.LineRecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextInputFormat;

public class TestRead {

	public static void main(String[] args) throws IOException {
		// TODO Auto-generated method stub

		System.out.println("begin .........");
		Configuration conf = new Configuration();
		//
		Path inputPath = new Path("hdfs://10.4.19.62:8020/user/spark/bz2/f2.txt.bz2");
		// FileSystem
		FileSystem fs = inputPath.getFileSystem(conf);
		//解码器
		BZip2Codec codec = new BZip2Codec();
		//codec.setConf(conf);
		//创建输入流
		FSDataInputStream inStream = fs.open(inputPath);
		//解压流
		CompressionInputStream compressInStream=codec.createInputStream(inStream);
		
		BufferedReader reader =new BufferedReader(new InputStreamReader(compressInStream));     

		String line;
		while((line=reader.readLine())!=null){
			System.out.println("line="+line);
		}
		fs.close();
		System.out.println("end ........................................");
	}

}
