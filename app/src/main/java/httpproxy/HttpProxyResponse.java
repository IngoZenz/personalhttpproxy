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


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

import util.Logger;
import util.http.HttpHeader;


public class HttpProxyResponse extends InputStream  {
	
	private static String HEXFILLSTR ="00000000";
	private static int STATIC_HEADER_LEN=HEXFILLSTR.length();
	private static int MIN_CHUNK_LEN = 512;
	
	private HttpProxyServer server;
	private ByteArrayInputStream headerIn = null;
	private InputStream srvHttp_In = null;	
	private byte[] chunk = null;
	private boolean finalChunk = false;
	private int chunkOffs =0;
	private int chunkCount =0;
	
	
	private long contentLength = 0;
	private boolean chunkedSrvRes = false;
	private boolean chunkedProxyRes = false;
	private int contentSent = 0;
	private ChunkedDataTransfer chunkedTransfer = null;
	
	private boolean responseComplete = false;		
	private boolean connectionClose = false;
	private boolean eof = false;
	private boolean ssl = false;
	private int available = 0;
	private boolean closed = false;
	
	
	public HttpProxyResponse(HttpProxyServer server) {
		this.server = server;
	}


	@Override
	public int read() throws IOException {
		byte[] b = new byte[1];
		int r = read(b);
		if (r == -1)
			return -1;

		return b[0] & 0xFF;
	}
	
	
	@Override
	public int read(byte[] b) throws IOException {
		return read(b,0,b.length);
	}
	
	
	private String getFixedLengthHexString(int number) {
		String hexStr = Integer.toHexString(number);
		hexStr = HEXFILLSTR.substring(0,STATIC_HEADER_LEN-hexStr.length())+hexStr;
		return hexStr;
	}
	
	@Override
	public synchronized int read(byte[] b, int off, int len) throws IOException {
		int r = readInternal(b,off,len);
		server.resetIdleTimeOut();
		return r;		
	}
	
	private synchronized int readInternal(byte[] b, int off, int len) throws IOException {
		
		if (server.closed)
			throw new IOException ("Server already closed!");
		
		if (! (server.current_status == HttpProxyServer.PROCESS_RESPONSE_BODY ||  server.current_status == HttpProxyServer.RETURN_RESPONSE_HEADER)) {
			try {
				wait(server.soTimeout);
				if (! (server.current_status == HttpProxyServer.PROCESS_RESPONSE_BODY ||  server.current_status == HttpProxyServer.RETURN_RESPONSE_HEADER)) {
					if (server.soTimeout != 0)
						throw new SocketTimeoutException();
				}
				if (server.closed)
					throw new IOException ("Server already closed!");
				
			} catch (InterruptedException e) {
				throw new IOException (e.getMessage());
			}
		}		
		if (eof)
			return -1;
		
				
		if (server.current_status == HttpProxyServer.RETURN_RESPONSE_HEADER) {
			int r =  headerIn.read(b, off, Math.min(len, headerIn.available()));
			available = headerIn.available();
			if (headerIn.available() == 0) {				
				server.current_status = HttpProxyServer.PROCESS_RESPONSE_BODY;
				headerIn = null; //GC should take it
				if (contentLength == 0 && !chunkedSrvRes) { // empty Body
					responseComplete= true;					
					server.responseFinished(!connectionClose);
				}
			}
			return r;
		} else if (server.current_status == HttpProxyServer.PROCESS_RESPONSE_BODY) {			
			
			if (chunkedProxyRes) {
				if (chunkOffs == chunkCount) {					
					int chunkSize = getBestSize(len);
					if (len >=chunkSize+STATIC_HEADER_LEN+4) {
						//read directly into buffer without need to copy and return full chunk 
						int count = srvHttp_In.read(b, STATIC_HEADER_LEN+2, chunkSize);
						
						if (count == -1) {
							//final chunk
							System.arraycopy("0\r\n\r\n".getBytes(), 0, b, 0, 5);
							server.responseFinished(chunkedSrvRes && !connectionClose);
							responseComplete= true;
							return 5;
						}
						
						byte[] hexheader=(getFixedLengthHexString(count)+"\r\n").getBytes();
						System.arraycopy(hexheader, 0, b, 0, STATIC_HEADER_LEN+2);
						b[count+STATIC_HEADER_LEN+2]=13;
						b[count+STATIC_HEADER_LEN+3]=10;
						return count+STATIC_HEADER_LEN+4;
					}
					chunk = new byte[chunkSize];
					chunkCount = srvHttp_In.read(chunk);
					if (chunkCount != -1) {						
						byte[] chunkHeader = (getFixedLengthHexString(chunkCount)+"\r\n").getBytes();
						byte[] fullChunk = new byte[chunkHeader.length+chunkCount+2];
						System.arraycopy(chunkHeader, 0, fullChunk, 0, chunkHeader.length);
						System.arraycopy(chunk, 0, fullChunk, chunkHeader.length, chunkCount);
												
						//don't forget \r\n at the end of the chunk
						fullChunk[fullChunk.length-2] = 13;
						fullChunk[fullChunk.length-1] = 10;
						chunk = fullChunk;
							
					} else {					
						//final chunk
						finalChunk = true;
						chunk = "0\r\n\r\n".getBytes();						
					}
					chunkCount = chunk.length;
					chunkOffs = 0;					
					available = chunkCount;
				}
				int count = Math.min(available, len);
				System.arraycopy(chunk, chunkOffs, b, off, count);				
				chunkOffs= chunkOffs+count;
				available = chunkCount-chunkOffs;
				
				if (finalChunk && chunkOffs == chunkCount) {
					server.responseFinished(chunkedSrvRes && !connectionClose);
					responseComplete= true;
				}
				return count;
				
			} else if (chunkedSrvRes) {
				
				int r = srvHttp_In.read(b,off,len);
				available = srvHttp_In.available();
				if (!chunkedTransfer.write(b,off,r)) { // No more bytes, chunked Data completed and terminated
					if (chunkedTransfer.lastBytesProcessed != r)
						throw new IOException (r-chunkedTransfer.lastBytesProcessed+" bytes left in chunked HTTP Response but final chunk received!");
					server.responseFinished(!connectionClose);
					responseComplete= true;
					chunkedTransfer = null; //GC clean up
				}
				return r;
				
			} else if (contentLength > 0) {
			
				int r = srvHttp_In.read(b,off,(int)Math.min(len, contentLength));
				available = srvHttp_In.available();
				contentSent=contentSent+r;
				
				if (contentSent == contentLength) {
					server.responseFinished(!connectionClose);
					responseComplete= true;
				}
				return r;
			} else if (ssl) {
				
				int r = srvHttp_In.read(b,off,len);	
				available = srvHttp_In.available();
				
				if (r == -1) {
					server.responseFinished(false);
					responseComplete= true;
					eof = true;
				}
				return r;
			}
			
		}
		throw new IOException("Invalid state!");	
	}

	
	private int getBestSize(int len) {		
		// get best chunk size so that it fits into a buffer of len bytes including overhead		
		if (len < MIN_CHUNK_LEN)
			return MIN_CHUNK_LEN; //minimum 512 bytes chunk
		
		return (len-STATIC_HEADER_LEN-4);		

	}


	public synchronized void startResponse(HttpHeader header, InputStream in, boolean ssl) {
		
		responseComplete = false;		
		chunkedProxyRes = false;
		chunkedSrvRes = false;
		eof = false;
		contentSent = 0;
		srvHttp_In = in;
		this.ssl = ssl;
		
		HttpHeader resHeader=header;
		server.resetIdleTimeOut();
		if (resHeader == null) {				
			try {
				if (!ssl) {
					try {
						server.setHttpServerReadTimeOut(server.INIT_CON_TO);
						resHeader = new HttpHeader(in, HttpHeader.RESPONSE_HEADER);	
						if (resHeader.getResponseCode()== 100) //continue!  real header next! 
							resHeader = new HttpHeader(in, HttpHeader.RESPONSE_HEADER);	
					} finally {
						server.setHttpServerReadTimeOut(server.soTimeout);
					}
				}
				else 
					resHeader = new HttpHeader("HTTP/1.1 200 Connection established\r\nProxy-agent: Personal-Proxy/1.1", HttpHeader.RESPONSE_HEADER);
				
			} catch (IOException e) {				
				startErrorResponse("HTTP/1.1 500 No valid Reponse from server!","No valid Reponse from server!\r\n"+e.getMessage());			
				return;
			}	
		}
						
		//System.out.println(resHeader.getHeaderString());
		connectionClose = resHeader.getConnectionClose();
		resHeader.setValue("Connection","Keep-Alive"); //proxy supports persistent connections
		this.contentLength= resHeader.getContentLength();
		this.chunkedSrvRes=resHeader.chunkedTransfer();			
		

		if (chunkedSrvRes) {
			chunkedTransfer = new ChunkedDataTransfer(null);
		} else if (contentLength == -1 && !ssl) { //do chunked Response in case there is no contentlength set
			resHeader.setValue("Transfer-Encoding","chunked");
			chunkedProxyRes = true;
			chunkOffs =0;
			chunkCount =0;
			finalChunk = false;		
		}
		
		headerIn = new ByteArrayInputStream (resHeader.getHeaderString().getBytes());		
		available = headerIn.available();
		
		server.current_status= HttpProxyServer.RETURN_RESPONSE_HEADER;
		notifyAll();		
	}
	
	public void close() {
		server.close();		
	}
	
	public synchronized  void closeResponse() {
		if (closed)
			return;
		closed = true;
		notifyAll();
	}


	public void startErrorResponse(String status, String body)  {
		HttpHeader header;
		try {
			header = new HttpHeader(status+"\r\n", HttpHeader.RESPONSE_HEADER);
			header.setValue("Content-Length", ""+body.length());
			header.setValue("Connection", "close");
			startResponse(null, new ByteArrayInputStream((header.getHeaderString()+body).getBytes()), false);	
		} catch (IOException e) {			
			Logger.getLogger().logException(e);
		}			
	}




}
