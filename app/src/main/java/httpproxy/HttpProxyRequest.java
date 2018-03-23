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


package httpproxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;



public class HttpProxyRequest extends OutputStream {	
	private static int HEADEREND = 218762506;  // \r\n\r\n
	private HttpProxyServer server;
	private int last4Bytes = 0;
	private ByteArrayOutputStream headerBytes = null;
	boolean ssl = false;
	private long reqContentLength=0;
	private int bytesBodySent=0;
	private boolean chunked;
	private ChunkedDataTransfer chunkedTransfer;
	private OutputStream sslOut;
	private boolean closed = false;
	
	
	public HttpProxyRequest(HttpProxyServer server) {
		this.server = server;
	}
	
	public synchronized void initRequest() {		
		last4Bytes = 0;
		headerBytes = new ByteArrayOutputStream();
		ssl = false;
		server.current_status = HttpProxyServer.READ_REQUEST_HEADER;		
		notifyAll();
	}
	
	
	@Override
	public void write(int b) throws IOException {
		write(new byte[] {(byte) b},0,1);		
	}
	
	@Override
	public synchronized void write(byte[] b) throws IOException {
		write(b,0,b.length);
	}
	

	@Override
	public synchronized void write(byte[] b, int offs, int len) throws IOException {

		server.resetIdleTimeOut();
		boolean processed = false;	
		
		while (!processed) {
			
			int processedBytes = 0;
			
			if (closed)
				throw new IOException ("Server already closed!");			
			
			if (server.current_status == HttpProxyServer.READ_REQUEST_HEADER) {
				
				last4Bytes = (last4Bytes <<8)+ (b[offs] & 0xFF);
				processedBytes=1;		
				headerBytes.write(b[offs] & 0xFF);
				if (last4Bytes == HEADEREND) {
					headerBytes.flush();			
					server.requestHeaderReceived(headerBytes.toByteArray());
					headerBytes=null;			
				}
			} else if (server.current_status== HttpProxyServer.PROCESS_REQUEST_BODY) {
				processed = true;
				processedBytes = writeRequestBody(b,offs,len);
				
			} else if (ssl) {	
				sslOut.write(b,offs, len);				
				processedBytes =len;				
			} else {
				try {					
					wait(); //block Writer until current request is processed					
				} catch (InterruptedException e) {
					throw new IOException(e.getMessage());
				}
			}
			offs = offs+processedBytes;
			len = len - processedBytes;
			processed = (len == 0);
		}
	}
	
	public int writeRequestBody(byte[] b, int offs, int len) throws IOException {
		if (!chunked) {
			int cnt = (int)Math.min(len, reqContentLength-bytesBodySent);
			server.httpSrv_Out.write(b, offs,cnt);
			bytesBodySent =  bytesBodySent+cnt;
			if (bytesBodySent == reqContentLength) {
				server.requestComplete();
			}
			return cnt;
		} else {
			boolean chunkFinal = !chunkedTransfer.write(b, offs,len);
			int processedBytes = chunkedTransfer.lastBytesProcessed;
			if (chunkFinal) { // if no more bytes -> done	
				chunkedTransfer= null; // not needed anymore - GC to take it 
				server.requestComplete();				
			}
			return processedBytes;
		}
	}
	
	
	public synchronized void startSSL() {
	
		ssl = true;
		sslOut=server.httpSrv_Out;
		if (sslOut == null) //sslOut might be null in case connection was already closed before SSL request initialization
			closed = true;
		notifyAll();		
	}
	
	@Override
	public void close() {
		server.close();	
	}
	
	public synchronized void closeRequest() {
		if (closed)
			return;
		closed = true;
		sslOut = null;
		notifyAll();
	}


	
	@Override
	public synchronized void flush() throws IOException {
		if (closed)
			return;
		
		if (!ssl && server.current_status!= HttpProxyServer.PROCESS_REQUEST_BODY)
			return; //content was already flushed in server.requestComplete(). Response is already in process!
		
		if (ssl) 
			sslOut.flush();
		
		else if (server.httpSrv_Out != null)		//Might be no server is connected
			server.httpSrv_Out.flush();
		
	}

	public void initRequestBody(long reqContentLength, boolean chunked) {
		this.chunked = chunked; 
		this.reqContentLength=reqContentLength;
		this.bytesBodySent = 0;	
		if (chunked)
			chunkedTransfer= new ChunkedDataTransfer(server.httpSrv_Out);
	}

}
