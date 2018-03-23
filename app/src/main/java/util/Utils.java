 /* 
 PersonalHttpProxy 1.5
 Copyright (C) 2013-2015 Ingo Zenz

 This program is free software; you can redistribute it and/or
 modify it under the terms of the GNU General Public License
 as published by the Free Software Foundation; either version 2
 of the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software
 Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.

 Find the latest version at http://www.zenz-solutions.de/personalhttpproxy
 Contact:i.z@gmx.net 
 */

package util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

public class Utils {

	public static int byteArrayToInt(byte[] b) 
	{
	    return   b[3] & 0xFF |
	            (b[2] & 0xFF) << 8 |
	            (b[1] & 0xFF) << 16 |
	            (b[0] & 0xFF) << 24;
	}

	public static byte[] intToByteArray(int a)
	{
	    return new byte[] {
	        (byte) ((a >> 24) & 0xFF),
	        (byte) ((a >> 16) & 0xFF),   
	        (byte) ((a >> 8) & 0xFF),   
	        (byte) (a & 0xFF)
	    };
	}
	
	public static byte[] longToByteArray(long value) {
	    return new byte[] {
	        (byte) (value >> 56),
	        (byte) (value >> 48),
	        (byte) (value >> 40),
	        (byte) (value >> 32),
	        (byte) (value >> 24),
	        (byte) (value >> 16),
	        (byte) (value >> 8),
	        (byte) value
	    };
	}
	
	public static void writeLongToByteArray(long value, byte[] b, int offs) {
		b[offs + 0] = (byte)(value >> 56);
		b[offs + 1] = (byte)(value >> 48);
		b[offs + 2] = (byte)(value >> 40);
		b[offs + 3] = (byte)(value >> 32);
		b[offs + 4] = (byte)(value >> 24);
		b[offs + 5] = (byte)(value >> 16);
		b[offs + 6] = (byte)(value >> 8);
		b[offs + 7] = (byte)value;
	}
	
	public static long byteArrayToLong(byte[] b, int offs) {
		 return  (long) (b[7+offs] & 0xFF) |
				 (long) (b[6+offs] & 0xFF) << 8 |
				 (long) (b[5+offs] & 0xFF) << 16 |
				 (long) (b[4+offs] & 0xFF) << 24 |
				 (long) (b[3+offs] & 0xFF) << 32 |
				 (long) (b[2+offs] & 0xFF) << 40 |
				 (long) (b[1+offs] & 0xFF) << 48 |
				 (long) (b[0+offs] & 0xFF) << 56;
	}
	
	
	public static long getLongStringHash (String str) {
		int a = 0;
		int b = 0;
		int len = str.length();	
		byte[] bytes = str.getBytes();
		for (int i = 0; i <len; i++) {
			a = 31*a + (bytes[i]& 0xFF);
			b = 31*b + (bytes[len-i-1]& 0xFF);
		}
		
		return ((long)a << 32) | ((long)b & 0xFFFFFFFFL);		
	}
	
	public static String readLineFromStream(InputStream in, boolean crlf) throws IOException {
		
		int i = 0;
		StringBuffer str = new StringBuffer();
		boolean exit = false;
		int r = -1;
		byte last = 0;
		while (!exit) {
			r = in.read();
			byte b =  (byte) (r & 0xff);
			exit = (r==-1 || ( b == 10 && (!crlf || last == 13 )));			
			if (!exit) {
				str.append((char)b);
				i++;
				last = b;
			}			
		}
		if (i > 0 && last == 13)
			i = i-1;
		
		return str.substring(0, i);					
	}	
	
	
	public static String readLineFromStreamRN(InputStream in) throws IOException {	
		return readLineFromStream(in,true);
	}
	
	public static String readLineFromStream(InputStream in) throws IOException {	
		return readLineFromStream(in,false);
	}
	
		
	public static byte[] readFully(InputStream in, int bufSize) throws IOException {
		ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();	
		int r = 0;
		byte[] buf = new byte[bufSize];
		while ((r = in.read(buf, 0, bufSize)) != -1)
			bytesOut.write(buf, 0, r);	
		return bytesOut.toByteArray();

	}

	public static byte[] serializeObject(Object obj) throws IOException {
		ByteArrayOutputStream objOut = new ByteArrayOutputStream();
		ObjectOutputStream dataOut = new ObjectOutputStream(objOut);
		dataOut.writeObject(obj);
		dataOut.flush();
		dataOut.close();
		return objOut.toByteArray();
	}
	
	public static String getServerTime() {
		Calendar calendar = Calendar.getInstance();
		SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
		dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
		return dateFormat.format(calendar.getTime());
	}
	
	
	public static String[] parseURI(String uri) throws IOException {
		try {
			String url = uri;
			url = url.substring(7);
			int idx = url.indexOf('/');
			if (idx == -1)
				idx = url.length();
			String hostEntry = url.substring(0, idx);
			if (idx == url.length())
				url = "/";
			else
				url = url.substring(idx);
			
			return new String[] {hostEntry,url};
		} catch (Exception e) {
			throw new IOException ("Cannot parse URI '"+uri+"'! - "+e.toString());
		}
	}
	
		public static void deleteFolder(String path) {
		File dir = new File(path);
		if (dir.exists() && dir.isDirectory()) {

			File[] files = dir.listFiles();
			for (int i = 0; i < files.length; i++) {
				if (files[i].isDirectory())
					deleteFolder(files[i].getAbsolutePath());
				else 
					files[i].delete();			
			}
			dir.delete();
		} 
	}

}
