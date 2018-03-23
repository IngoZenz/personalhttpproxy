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


import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;

import util.DateRetriever;
import util.Logger;
import util.LoggerInterface;
import util.TimeoutListener;
import util.TimeoutTime;
import util.TimoutNotificator;
import util.conpool.Connection;
import util.http.HttpHeader;

public class HttpProxyServer extends Socket implements TimeoutListener {
		
	public static final byte READ_REQUEST_HEADER = 1;
	public static final byte CONTACT_SERVER = 2;
	public static final byte SEND_REQUEST_HEADER = 3;	
	public static final byte PROCESS_REQUEST_BODY = 4;
	public static final byte READ_RESPONSE_HEADER = 5;
	public static final byte RETURN_RESPONSE_HEADER = 6;
	public static final byte PROCESS_RESPONSE_BODY = 7;
	
	public static final int IDLE_TO=300000;
	public static final int INIT_CON_TO=120000;
	
	private static byte[]TUNNEL_RQ_FILTER_RESP = "HTTP/1.1 403 Forbidden\r\n\r\n".getBytes();
	private  LoggerInterface TRAFFIC_LOG;	
	private TimeoutTime idleTimeout;
	
	private String clientID="<unknkown>";
	private boolean useProxy = false;
	private Proxy chainedProxy = null;
	
	private Set hostFilter = null;
	private Set urlFilter = null;
	private byte[] filterResponse;
	
	public boolean closed = false;
	
	public byte current_status = 0;
	
	private  HttpProxyRequest request;
	private  HttpProxyResponse response;
	private String urlLogEntry=null; 
	protected HttpHeader reqHeader = null;	
	

	private Connection httpServer = null;	
	
	public InputStream httpSrv_In=null;
	public OutputStream httpSrv_Out=null;	
	public int soTimeout = 0;
	
	
	private class NullOutput extends OutputStream {
		@Override
		public void write(int arg0) throws IOException {
		}
	}
	
	
	public HttpProxyServer() {
		current_status = READ_REQUEST_HEADER; //Initial State
		request = new HttpProxyRequest(this);
		request.initRequest();
		response =  new HttpProxyResponse(this);
		idleTimeout = new TimeoutTime(TimoutNotificator.getInstance());
		idleTimeout.setTimeout(IDLE_TO);
		TimoutNotificator.getInstance().register(this);
		TRAFFIC_LOG = Logger.getLogger();
		
	}
	
	public HttpProxyServer(LoggerInterface trafficLogger, String clientID, Proxy chainedProxy, Set hostFilter, Set urlFilter,  byte[] filterResponse) {
		this();		
		
		TRAFFIC_LOG=trafficLogger;
			
		if (chainedProxy != null) {
			useProxy = true;
			this.chainedProxy = chainedProxy;			
		}
		this.clientID = clientID;
		this.hostFilter = hostFilter;	
		this.urlFilter = urlFilter;
		this.filterResponse = filterResponse;	
	}
	
	
	
	public OutputStream getOutputStream() {
		return request;
		
	}
	
	public InputStream getInputStream() {
		return response;
	}
	

	private String getURL4LogEntry() {
		//URL for log Entry ==> cut on first ? as otherwise that might get huge!
		String urlLogEntry = this.getURL(reqHeader);
		int idx = urlLogEntry.indexOf('?');
		
		if (idx != -1) 
			urlLogEntry = urlLogEntry.substring(0, idx);
		
		return urlLogEntry;
	}
	
	public void requestHeaderReceived(byte[] headerBytes)  {
		try {
			reqHeader = new HttpHeader(new ByteArrayInputStream(headerBytes), HttpHeader.REQUEST_HEADER);
			
			if (TRAFFIC_LOG!=null)
				urlLogEntry = getURL4LogEntry(); // reqHeader will be deleted later for saving memory - so we need to keep the URL for the traffic log
			
		} catch (IOException e1) {
			current_status= READ_RESPONSE_HEADER;
			response.startErrorResponse("HTTP/1.1 400 Bad Request!","Request Parsing failed!\r\n"+e1.getMessage());
			return;			
		}
		
		boolean ssl=reqHeader.tunnelMode;
		long reqContentLength = reqHeader.getContentLength();
		boolean chunkedReq = reqHeader.chunkedTransfer();
		
		current_status = CONTACT_SERVER;
		boolean filtered;
		try {
			filtered = contactServer();			
		} catch (IOException e) {
			current_status= READ_RESPONSE_HEADER;
			response.startErrorResponse("HTTP/1.1 503 Server Connect Failed!","Server Connect Failed!\n"+e.getMessage());
			return;			
		}
		try {
			if (!ssl) {			
				current_status = SEND_REQUEST_HEADER;				
				sendRequestHeader();		
				reqHeader = null; //GC to clean up
				if  (reqContentLength > 0 || chunkedReq ) {
					current_status =  PROCESS_REQUEST_BODY;
					request.initRequestBody(reqContentLength, chunkedReq);				
					request.notifyAll(); //notify request to receive request body
				} else {
					requestComplete();
				}	
				
			} else {	
				if (useProxy) {
					sendRequestHeader();
					httpSrv_Out.flush();
				}
				HttpHeader tunnelResponse = null;
				
				current_status= READ_RESPONSE_HEADER;
				
				if (useProxy || filtered) 				
					tunnelResponse = new HttpHeader(httpSrv_In, HttpHeader.RESPONSE_HEADER);
					
				response.startResponse(tunnelResponse, httpSrv_In, true);
				request.startSSL();
			}
		}  catch (IOException e2) {
			current_status= READ_RESPONSE_HEADER;
			response.startErrorResponse("HTTP/1.1 500 Server Error!","Request Sent failed!\n"+e2.getMessage());			
			return;			
		}
	}
	

	public void requestComplete() throws IOException {
		httpSrv_Out.flush();
		current_status= READ_RESPONSE_HEADER;
		response.startResponse(null, httpSrv_In, false);		
	}
 

	private void sendRequestHeader() throws IOException {
		httpSrv_Out.write(reqHeader.getServerRequestHeader(useProxy).getBytes());	
		httpSrv_Out.flush(); // flush as soon as possible to prevent pooled connection to be closed by http server
	}

	
	
	private String getURL(HttpHeader reqHeader){
		String url;
		if (reqHeader.tunnelMode) {
			if (reqHeader.remote_port != 443)		
				url = "https://"+reqHeader.hostEntry+"/";
			else
				url = "https://"+reqHeader.remote_host_name+"/";
		} else {
			if (reqHeader.remote_port != 80)
				url = "http://"+reqHeader.hostEntry+reqHeader.url;
			else 
				url = "http://"+reqHeader.remote_host_name+reqHeader.url;
		}
		return url;
	}
	
	
	private boolean filter(HttpHeader reqHeader) {		
		if (hostFilter != null && hostFilter.contains(reqHeader.remote_host_name)) {
			Logger.getLogger().logLine("FILTERED:"+reqHeader.remote_host_name);
			return true;	
		} else if (urlFilter != null) {
			String url = getURL(reqHeader);
			if (urlFilter.contains(url)) {
				Logger.getLogger().logLine("FILTERED:"+url);
				return true;				
			} else return false;			
			
		} else return false;
			
	}

	protected boolean contactServer() throws IOException {
		
		boolean filter = filter(reqHeader);
		if (filter) {
			httpSrv_Out = new NullOutput();
			
			if (reqHeader.tunnelMode)
				httpSrv_In = new ByteArrayInputStream(TUNNEL_RQ_FILTER_RESP);
			else
				httpSrv_In = new ByteArrayInputStream(filterResponse);				
			
		} else {
			Logger.getLogger().logLine("REQUESTING:"+reqHeader.remote_host_name+" on port "+reqHeader.remote_port);
			if (useProxy)
				httpServer = Connection.connect((InetSocketAddress)chainedProxy.address(),INIT_CON_TO);
			else 
				httpServer = Connection.connect(reqHeader.remote_host_name,reqHeader.remote_port,INIT_CON_TO);	
			
			httpSrv_In =  new BufferedInputStream(httpServer.getInputStream());			
			httpSrv_Out = new BufferedOutputStream ( httpServer.getOutputStream());
			
					
			setHttpServerReadTimeOut(soTimeout);
		}
		return filter;
	}

	 
	protected synchronized void releaseServer(boolean reuse) {

		if (httpSrv_In== null)
			return; // already released by other thread
		
		if (httpServer != null && TRAFFIC_LOG!=null) {
			long traffic[] = httpServer.getTraffic();
			TRAFFIC_LOG.logLine(DateRetriever.getDateString()+", "+clientID+", "+httpServer.getDestination()+", "+traffic[0]+", "+traffic[1]+", "+urlLogEntry);				
		} else if (TRAFFIC_LOG!=null) //no connection - filtered!
			TRAFFIC_LOG.logLine(DateRetriever.getDateString()+", "+clientID+", <no connection / filtered>, 0, 0, "+ urlLogEntry);
		
		if (httpServer != null)
			httpServer.release(reuse);	
		
		httpServer = null;
		httpSrv_In = null;
		httpSrv_Out = null;
	}




	public void responseFinished(boolean reuseServer) {		
		releaseServer(reuseServer);
		urlLogEntry = null; //reset		
		request.initRequest();				
	}
	
	@Override
	public void close() {		
		if (closed)
			return;
		TimoutNotificator.getInstance().unregister(this);
		closed = true;
		
		if (httpServer != null) //connection is not finished we cannot reuse
			releaseServer(false);
		
		response.closeResponse();
		request.closeRequest();

	}
	
	@Override
	public void setSoTimeout(int timeout) throws SocketException {		
		soTimeout = timeout;			
		setHttpServerReadTimeOut(timeout);
	}
	
	protected void setHttpServerReadTimeOut(int to) throws SocketException {
		if (httpServer != null) //might be null when not yet connected or in case of blocked host with filter response 
			httpServer.setSoTimeout(to);		
	}


	@Override
	public void shutdownInput() throws IOException {
		//nothing	
	}

	@Override
	public void shutdownOutput() throws IOException {
		//nothing		
	}

	@Override
	public void timeoutNotification() {
		close();		
	}

	@Override
	public long getTimoutTime() {	
		return idleTimeout.getTimeout();
	}
	
	public void resetIdleTimeOut() {
		idleTimeout.setTimeout(IDLE_TO);
	}
	

}
