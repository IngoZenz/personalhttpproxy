/* 
PersonalHttpProxy 1.5
Copyright (C) 2013-2016 Ingo Zenz

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

package proxy;

import httpproxy.HttpProxyServer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.util.Date;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.StringTokenizer;

import proxy.BlockedHosts;
import util.ExecutionEnvironment;
import util.FileLogger;
import util.Logger;
import util.LoggerInterface;


public class HttpProxy implements Runnable, LoggerInterface
{
	public static final String VERSION = "1.57.5";
	static public boolean debug;
	static public String WORKDIR = "";	
	private static String filterReloadURL;
	private static String filterhostfile;
	private static long filterReloadIntervalDays;
	private static long nextReload;
	private static int okCacheSize = 500;
	private static int filterListCacheSize = 500;
	private static boolean reloadUrlChanged;
	private static boolean validIndex;
	
	private static LoggerInterface TRAFFIC_LOG;

	private static BlockedHosts hostFilter = null;
	private static BlockedUrls urlFilter = null;
	private static Hashtable hostsFilterOverRule = null;
	private static byte[] filterResponse;
	private static Proxy chainedProxy;
	private static HttpProxy MAINLOOP;
	
	
	private static HashSet openConnections;

	private Socket client = null;
	private Socket server = null;
	private ServerSocket acceptSocket;
	private boolean serverStopped = false;
	private boolean listenerStopped = false;
	private boolean closed = false;

	private class AutoFilterUpdater implements Runnable {

		private void waitUntilNextFilterReload() throws InterruptedException {
			// This strange kind of waiting per 10 seconds interval is needed for Android as during device sleep the timer is stopped.
			// This caused the problem that on Android the filter was never updated during runtime but only when restarting the app.
			synchronized (MAINLOOP) {
				while (nextReload > System.currentTimeMillis() && !serverStopped)
					MAINLOOP.wait(10000);
			}
		}
		
		@Override
		public void run() {

			synchronized (MAINLOOP) {
				
				int retry = 0;
				long waitTime;
				
				while (!serverStopped) {
					
					Logger.getLogger().logLine("HTTP proxy: Next filter reload:" + new Date(nextReload));
					try {
						 waitUntilNextFilterReload();
					} catch (InterruptedException e) {
						// nothing to do!
					}

					if (serverStopped)
						break;

					try {
						Logger.getLogger().logLine("HTTP proxy: Reloading hosts filter ...");						
						updateFilter();
						validIndex = false;
						reloadFilter();
						Logger.getLogger().logLine("Reloading hosts filter ... completed!");
						waitTime = filterReloadIntervalDays * 24 * 60 * 60 * 1000;
						nextReload = System.currentTimeMillis() + waitTime;						
						retry = 0;
					} catch (Exception e) {
						Logger.getLogger().logLine("Cannot update hosts filter file!");
						Logger.getLogger().logLine(e.getMessage());	
						if (retry < 10) {
							if (retry < 5)
								waitTime = 60000;
							else 
								waitTime = 3600000; // retry after 1 h
							
							nextReload = System.currentTimeMillis() + waitTime;							
							Logger.getLogger().logLine("Retry at: "+new Date(nextReload));
							retry++;							
						} else {
							Logger.getLogger().logLine("Giving up! Reload skipped!");
							waitTime = filterReloadIntervalDays * 24 * 60 * 60 * 1000;
							nextReload = System.currentTimeMillis() + waitTime;				
							retry = 0;
						}
						
					}

				}
				Logger.getLogger().logLine("HTTP proxy: AutoFilterUpdater stopped!");

			}
		}

	}

	public HttpProxy() {
		
	}

	public HttpProxy(Socket clientSocket) {

		this.client = clientSocket;

	}

	public void close(Socket s) {
		try {
			s.close();
		} catch (Exception e) {
			// Logger.getLogger().logLine("Proxy: Error while closing socket "+e.toString());
		}
	}

	public synchronized void cleanUp(int code, String transmitter) {

		if (closed)
			return;
		
		close(server);
		close(client);
		closed = true;
		synchronized (openConnections) {
			openConnections.remove(server);
		}
		Logger.getLogger().logLine("HTTP proxy: "+transmitter + " closed!");
	}

	public boolean go() {
		try {
			server = connectServer(client.getInetAddress().getHostAddress(),true);
		} catch (Exception e) {
			Logger.getLogger().logLine(e.toString());
			return false;
		}

		Transmitter c = new Transmitter(client, server, this, "Client->Server");
		if (!c.start()) {
			close(server);
			return (false);
		}

		Transmitter s = new Transmitter(server, client, this, "Server->Client");
		if (!s.start()) {
			close(server);
			return (false);
		}

		return true;
	}
	
	public Socket connectServer(String clientID) throws Exception {
		return connectServer(clientID, false);
	}

	private Socket connectServer(String clientID,boolean internal) throws Exception {
		Socket con = new HttpProxyServer(TRAFFIC_LOG, clientID, chainedProxy, hostFilter, urlFilter,  filterResponse);
		if (internal) {
			synchronized (openConnections) {
				openConnections.add(con);
			}
		}
		Logger.getLogger().logLine("HTTP proxy: New connection...");
		return con;
		// return( new HttpProxyServer(null, 8080) );
	}

	ServerSocket openListener(boolean localOnly, int port) throws Exception {
		InetAddress adr = null;
		if (localOnly)
			adr = InetAddress.getByName("127.0.0.1");
		return new ServerSocket(port, 0, adr);
	}

	public HttpProxy createProxy(Socket clientSocket) {
		return new HttpProxy(clientSocket);
	}

	@Override
	public void run() {
		if (this == MAINLOOP)
			initAndRunListenerLoop();

		else if (!go())
			try {
				client.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
			}
	}
	
	public void triggerUpdateFilter() {
		if (filterReloadURL != null) {
			synchronized (MAINLOOP) {
				nextReload = 0;
				MAINLOOP.notifyAll();
			}
		} else
			Logger.getLogger().logLine("HTTP proxy: Setting 'filterAutoUpdateURL' not configured - cannot update filter!");
	}

	private void updateFilter() throws IOException {
		try {	
			ExecutionEnvironment.getEnvironment().wakeLock(); //ensure device stays awake until filter update is completed
			
			OutputStream out = new FileOutputStream(WORKDIR + filterhostfile+".tmp");
			out.write((("# Hosts Filter File from " + filterReloadURL + "\n").getBytes()));
			out.write(("# Last Update:" + new Date() + "\n").getBytes());
			
			StringTokenizer urlTokens = new StringTokenizer(filterReloadURL,";");			
	
			int urlCnt = urlTokens.countTokens();
			for (int i = 0; i < urlCnt; i++) {
				String urlStr = urlTokens.nextToken().trim();
				if (!urlStr.equals("")) {
					Logger.getLogger().logLine("HTTP proxy: Updating filter from " + urlStr + "...");
					out.write(("\n# Load Filter from URL:"+urlStr+"\n").getBytes());
					URL url = new URL(urlStr);
					URLConnection con;
					if (chainedProxy != null)
						con = url.openConnection(chainedProxy);
					else
						con = url.openConnection(Proxy.NO_PROXY);
			
					con.setConnectTimeout(120000);
					con.setReadTimeout(120000);
					
					InputStream in = con.getInputStream();			
					byte[] buf = new byte[10000];
					int r;	
	
					int received = 0;
					int delta = 100000;
					while ((r = in.read(buf)) != -1) {
						out.write(buf, 0, r);
						received = received + r;
						if (received > delta) {
							Logger.getLogger().logLine("Bytes received:" + received);
							delta = delta + 100000;
						}
					}
				}
			}
			
	
			Logger.getLogger().logLine("Updating filter completed!");
	
			out.flush();
			out.close();
			new File(WORKDIR + filterhostfile).delete();
			new File(WORKDIR + filterhostfile+".tmp").renameTo(new File(WORKDIR + filterhostfile));
			
			updateFilterURLinConfFile(filterReloadURL); //update last loaded URL
		} finally {
			ExecutionEnvironment.getEnvironment().releaseWakeLock();			
		}

	}

	private void reloadFilter() throws IOException {
		try {
			ExecutionEnvironment.getEnvironment().wakeLock(); //ensure device stays awake until filter reload is completed		

			File filterfile = new File(WORKDIR + filterhostfile);
	
			if (filterfile.exists() && !reloadUrlChanged) {
				nextReload = filterReloadIntervalDays * 24 * 60 * 60 * 1000 + filterfile.lastModified();
			} else
				nextReload = 0; // reload asap
	
			File indexFile = new File(WORKDIR + filterhostfile+".idx");
			if (indexFile.exists() && validIndex && BlockedHosts.checkIndexVersion(indexFile.getAbsolutePath())) {
				hostFilter = BlockedHosts.loadPersistedIndex(indexFile.getAbsolutePath(), false, okCacheSize, filterListCacheSize, hostsFilterOverRule);
			} else if (filterfile.exists()) {
	
				Logger.getLogger().logLine("Reading filter file and building index...!");
				BufferedReader fin = new BufferedReader(new InputStreamReader(new FileInputStream(filterfile)));
				String entry = null;
				int size = 0;
	
				BlockedHosts hostFilterSet = new BlockedHosts(Math.max(1, (int) (filterfile.length() / 30)), okCacheSize, filterListCacheSize, hostsFilterOverRule);
				int cutFirst = 0;
				boolean first = true;
				while ((entry = fin.readLine()) != null) {
					if (entry.startsWith("# Load Filter from URL:"))
						first = true; //next file - there might be a format change
					if (!entry.startsWith("#") && !entry.equals("") && !entry.startsWith("::1")) {						
						if (first) {
							if (entry.startsWith("127.0.0.1 ") || entry.startsWith("127.0.0.1\t")) {
								cutFirst = 10;
								Logger.getLogger().logLine("Filter file is in 'Hosts' format!");
							} else cutFirst = 0;
							
							first = false;
						}		
						hostFilterSet.prepareInsert(entry.substring(cutFirst).trim());
						size++;
					}
				}
	
				fin.close();
	
				hostFilterSet.finalPrepare();
	
				Logger.getLogger().logLine("Building index for " + size + " entries...!");
	
				fin = new BufferedReader(new InputStreamReader(new FileInputStream(filterfile)));
	
				int processed = 0;
				int uniqueEntries = 0;
				while ((entry = fin.readLine()) != null) {
					if (entry.startsWith("# Load Filter from URL:"))
						first = true; // next file - there might be a format
										// change
					if (!entry.startsWith("#") && !entry.equals("") && !entry.startsWith("::1")) {
						if (first) {
							if (entry.startsWith("127.0.0.1 ") || entry.startsWith("127.0.0.1\t")) {
								cutFirst = 10;
							} else
								cutFirst = 0;

							first = false;
						}
						if (!hostFilterSet.add((entry.substring(cutFirst).trim())))
							;//Logger.getLogger().logLine("Duplicate detected ==>" + entry);
						else uniqueEntries++;
						
						processed++;
						if (processed % 10000 == 0) {
							Logger.getLogger().logLine("Building index for " + processed + "/" + size + " entries completed!");							
						}
					}
				}
	
				fin.close();
				
				
				try {
					if (hostFilter != null)
						hostFilter.lock(1); // Exclusive Lock ==> No reader allowed during update of hostfilter
					
					Logger.getLogger().logLine("Persisting index for " + size + " entries...!");
					Logger.getLogger().logLine("Index contains "+uniqueEntries+" unique entries!");
					
					hostFilterSet.persist(WORKDIR+filterhostfile+".idx");
					hostFilterSet.clear(); //release memory
					
					hostFilterSet = BlockedHosts.loadPersistedIndex(indexFile.getAbsolutePath(), false, okCacheSize, filterListCacheSize, hostsFilterOverRule); //loads only file handles not the whole structure.  
					
					if (hostFilter != null)	{		
						hostFilter.migrateTo(hostFilterSet);
						
					} else
						hostFilter = hostFilterSet;
				} finally {
					hostFilter.unLock(1); //Update done! Release exclusive lock so readers are welcome!
				}
				
	
				validIndex = true;
				Logger.getLogger().logLine("Processing new filter file completed!");
	
			}
		} finally {
			ExecutionEnvironment.getEnvironment().releaseWakeLock();
		}
	}
	
	private void updateFilterURLinConfFile(String url) {
		try {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(WORKDIR + "httpproxy.conf")));
		String ln;
		boolean found = false;
		while ((ln = reader.readLine()) != null) {
			if (ln.startsWith("previousAutoUpdateURL")) {
				found = true;
				ln = "previousAutoUpdateURL = " + url;
			}
			out.write((ln + "\r\n").getBytes());
		}
		if (!found)
			out.write(("previousAutoUpdateURL = " + url + "\r\n").getBytes());

		out.flush();
		reader.close();
		OutputStream fout = new FileOutputStream(WORKDIR + "httpproxy.conf");
		fout.write(out.toByteArray());
		fout.flush();
		fout.close();
		} catch (IOException e) {
			Logger.getLogger().logException(e);
		}
	}
	
	
	private void initStatics() {
		debug = false;		
		filterReloadURL = null;
		filterhostfile = null;
		filterReloadIntervalDays = 4;
		nextReload = 0;
		reloadUrlChanged = false;
		validIndex = true;
		hostFilter = null;
		hostsFilterOverRule = null;
		filterResponse = null;
		chainedProxy = null;
		MAINLOOP=null;
		openConnections = new HashSet();
	}

	public void init() throws Exception {
		boolean filterEnabled = false;
		try {
			Logger.getLogger().logLine("***Initializing PersonalHttpProxy Version "+VERSION+"!***");
			
			Properties props = new Properties();
			FileInputStream in = new FileInputStream(WORKDIR + "httpproxy.conf");
			props.load(in);
			in.close();
			
			//Init traffic Logger			
			try {	
				
				if (props.getProperty("enableTrafficLog", "true").equalsIgnoreCase("true")) {
					TRAFFIC_LOG =  new FileLogger(WORKDIR+"log", 
							props.getProperty("trafficLogName","trafficlog"), 
							Integer.parseInt(props.getProperty("trafficLogSize","1048576").trim()),
							Integer.parseInt(props.getProperty("trafficLogSlotCount","2").trim()),
							"timestamp, client, destination, bytes_received, bytes_sent, request");
					
					Logger.setLogger(TRAFFIC_LOG,"TrafficLogger");
				} 
				else TRAFFIC_LOG= null;
				
			} catch (NumberFormatException nfe) {
				Logger.getLogger().logLine("Cannot parse log configuration!");
				throw nfe;
			}
			
			debug = Boolean.parseBoolean(props.getProperty("debug", "false"));

			int listenPort = Integer.parseInt(props.getProperty("listenPort", "8088"));

			if (listenPort >= 0) {

				boolean localOnly = Boolean.parseBoolean(props.getProperty("localOnly", "true"));

				if (localOnly)
					Logger.getLogger().logLine("HTTP proxy: Only localhost connections to proxy will be accepted");
				else
					Logger.getLogger().logLine("HTTP proxy: Remote connections to proxy will be accepted");

				acceptSocket = openListener(localOnly, listenPort);

				Logger.getLogger().logLine("HTTP proxy: Listening on port " + acceptSocket.getLocalPort()+"!");
			} else
				Logger.getLogger().logLine("HTTP proxy: Listen port is disabled - no listener will be opened!");

			String chainedProxyStr = props.getProperty("chainedProxyHost");
			int chainedProxyPort = Integer.parseInt(props.getProperty("chainedProxyPort", "-1"));

			if (chainedProxyStr != null) {
				InetAddress adr = InetAddress.getByName(chainedProxyStr);
				adr = InetAddress.getByAddress(chainedProxyStr, adr.getAddress());
				chainedProxy = new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress(adr, chainedProxyPort));
			}

			String filterUrlfile = props.getProperty("filterURLsFile");
			if (filterUrlfile != null) {
				filterEnabled = true;
				urlFilter = new BlockedUrls(okCacheSize, filterListCacheSize, null);
				urlFilter.appyList(new FileInputStream(WORKDIR + filterUrlfile));
			}

			filterhostfile = props.getProperty("filterHostsFile");

			if (filterhostfile != null) {
				filterEnabled = true;
				// load filter overrule values

				Iterator entries = props.entrySet().iterator();

				while (entries.hasNext()) {
					Entry entry = (Entry) entries.next();
					String key = (String) entry.getKey();
					if (key.startsWith("filter.")) {
						if (hostsFilterOverRule == null)
							hostsFilterOverRule = new Hashtable();
						hostsFilterOverRule.put(key.substring(7), new Boolean(Boolean.parseBoolean(((String) entry.getValue()).trim())));
					}
				}

				// trigger regular filter update when configured
				filterReloadURL = props.getProperty("filterAutoUpdateURL");
				filterReloadIntervalDays = Integer.parseInt(props.getProperty("reloadIntervalDays", "4"));
				String previousReloadURL = props.getProperty("previousAutoUpdateURL");

				if (filterReloadURL != null)
					reloadUrlChanged = !filterReloadURL.equals(previousReloadURL);

				// Load filter file
				reloadFilter();

				if (filterReloadURL != null) {

					Thread t = new Thread(new AutoFilterUpdater());
					t.setDaemon(true);
					t.start();
				}
			}

			if (filterEnabled) {
				// load response data in case host is filterd
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				bytes.write("HTTP/1.1 200 OK\r\n\r\n".getBytes());

				String filterResponsefile = props.getProperty("filterResponseFile");
				if (filterResponsefile != null) {
					File response = new File(WORKDIR + filterResponsefile);
					if (response.exists()) {
						FileInputStream responsein = new FileInputStream(response);
						byte[] buf = new byte[1024];
						int r = 0;
						while ((r = responsein.read(buf)) != -1)
							bytes.write(buf, 0, r);

						responsein.close();
					}
				}
				bytes.flush();
				filterResponse = bytes.toByteArray();
			}
		} catch (Exception e) {
			throw e;
		}
	}

	public void initMainLoop(String args[]) throws Exception {
		initStatics();
		MAINLOOP = this;
		if (args.length > 0 && args[0].equals("-async"))
			new Thread(this).start(); // asynchronous start

		else
			initAndRunListenerLoop();
	}

	private void initAndRunListenerLoop() {
		try {
			init();
		} catch (Exception e) {
			Logger.getLogger().logException(e);
			return;
		}
		while (!serverStopped && acceptSocket != null) {
			try {
				Socket client = acceptSocket.accept();				
				HttpProxy proxy = createProxy(client);
				new Thread(proxy).start();
			} catch (Exception e0) {
				synchronized (this) {
					if (!serverStopped)
						Logger.getLogger().logLine("HTTP proxy: " + e0.toString());
					listenerStopped = true;
					this.notifyAll();
				}
			}
		}
		listenerStopped = true;
		// server.close();
	}

	public synchronized void stop() {

		serverStopped = true;
		if (hostFilter != null)
			hostFilter.clear();
		if (urlFilter != null)
			hostFilter.clear();
		try {
			if (acceptSocket !=null) {
				int port = acceptSocket.getLocalPort();
				acceptSocket.close();			
	
				while (!listenerStopped) {
					try {
						wait();
					} catch (InterruptedException e) {
						Logger.getLogger().logException(e);
						return;
					}
				}
				Logger.getLogger().logLine("HTTP proxy: Stopped listening on port " + port + "!");
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			Logger.getLogger().logException(e);
		}
				
		this.notifyAll();
		//now close all servers!
		Object[] servers =  openConnections.toArray();
		for (int i = 0; i < servers.length; i++) {
			try {
				((Socket) servers[i]).close();
			} catch (IOException ioe) {
				Logger.getLogger().logException(ioe);
			}
		}
		if (TRAFFIC_LOG!=null) {
			TRAFFIC_LOG.closeLogger();
			Logger.removeLogger("TrafficLogger");	
		}
	}
	

	public static void main(String args[]) {
		try {
			HttpProxy proxy = new HttpProxy();
			Logger.setLogger(proxy);			
			proxy.initMainLoop(args);
		} catch (Exception e) {
			Logger.getLogger().logLine("HTTP proxy: Failed to start: " + e.getMessage());
		}
	}
	
	public static int openConnectionsCount() {
		if (openConnections!= null)
			return openConnections.size();
		else return 0;
		
	}

	@Override
	public void logLine(String txt) {
		System.out.println(txt);
	}

	@Override
	public void logException(Exception e) {
		e.printStackTrace(System.out);

	}

	@Override
	public void log(String txt) {
		System.out.print(txt);
	}

	@Override
	public void closeLogger() {
		// TODO Auto-generated method stub
		
	}

}
