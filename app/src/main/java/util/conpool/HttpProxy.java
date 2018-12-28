package util.conpool;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;

import util.http.HttpHeader;

public class HttpProxy extends Proxy {
	private String authString;
	private InetSocketAddress proxyAdr;	

	public HttpProxy(InetSocketAddress adr, String authString) {
		super(Proxy.Type.HTTP, adr);
		this.proxyAdr = adr;
		this.authString= authString;		
	}
	
	public HttpProxy(InetSocketAddress adr) {
		this(adr,null);
	}
	
	public void setProxyAuth(String authString){
		this.authString= authString;	
	}

	public Socket openTunnel(String host, int port,  int conTimeout) throws IOException {
		HttpHeader header = new HttpHeader(HttpHeader.REQUEST_HEADER);
		header.setRequest("CONNECT "+host+":"+port+" HTTP/1.1");
		if (authString != null)
			header.setValue("Proxy-Authorization", authString);
		
		String request = header.getServerRequestHeader();
		Socket proxyCon = new Socket();	
		proxyCon.connect(proxyAdr, conTimeout);
		proxyCon.setSoTimeout(conTimeout);
		proxyCon.getOutputStream().write(request.getBytes());
		proxyCon.getOutputStream().flush();
		
		header = new HttpHeader (proxyCon.getInputStream(), HttpHeader.RESPONSE_HEADER);
		if (header.responsecode != 200){
			proxyCon.shutdownInput();
			proxyCon.shutdownOutput();
			proxyCon.close();
			throw new IOException ("Proxy refused Tunnel\n"+header.getResponseMessage());
		}
		proxyCon.setSoTimeout(0); 
		return proxyCon;		
	}

}
