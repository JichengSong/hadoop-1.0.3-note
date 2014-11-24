package com.renren;

import java.util.Random;

import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.MD5Hash;

//import com.google.common.primitives.Bytes;

//implements
public class HashRowKeyGenerator implements RowKeyGenerator {
	private long currentId = 1;
	private long currentTime = System.currentTimeMillis();
	private Random random = new Random();

	public byte[] nextId() {
		try {
			currentTime += random.nextInt(1000);
//			  public static byte [] copy(byte [] bytes, final int offset, final int length) {
//				  1772     if (bytes == null) return null;
//				  1773     byte [] result = new byte[length];
//				  1774     System.arraycopy(bytes, offset, result, 0, length);
//				  1775     return result;
//				  1776   }
//			System.arraycopy(src, srcPos, dest, destPos, length)
//			
//			public static byte[] toBytes(ByteBuffer buf) {
//				 330     ByteBuffer dup = buf.duplicate();
//				 331     dup.position(0);
//				 332     return readBytes(dup);
//				 333   }
			byte[] lowT = new byte[4];
			System.arraycopy(Bytes.toBytes(currentTime), 4, lowT, 0, 4);
			byte[] lowU = new byte[4];
			System.arraycopy(Bytes.toBytes(currentId), 4, lowU, 0, 4);
			//byte[] lowT = Bytes.copy(Bytes.toBytes(currentTime), 4, 4);
			//byte[] lowU = Bytes.copy(Bytes.toBytes(currentId), 4, 4);
			return Bytes.add(MD5Hash.getMD5AsHex(Bytes.add(lowU, lowT))
					.substring(0, 8).getBytes(), Bytes.toBytes(currentId));
		} finally {
			currentId++;
		}
	}
}
