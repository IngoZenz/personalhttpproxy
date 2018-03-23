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

package util.conpool;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Vector;

import util.Logger;
import util.TimeoutListener;
import util.TimeoutTime;
import util.TimoutNotificator;


public class Connection implements TimeoutListener {

	private Socket socket = null;
	private InputStream socketIn;
	private OutputStream socketOut;
	private PooledConnectionInputStream in;
	private PooledConnectionOutputStream out;
	String poolKey;
	TimeoutTime timeout;
	boolean aquired = true;
	boolean valid = true;

	private static HashMap connPooled = new HashMap();
	private static HashSet connAquired = new HashSet();
	private static int  POOLTIMEOUT_SECONDS = 300;	
	private static TimoutNotificator toNotify = TimoutNotificator.getNewInstance();

	private Connection(String host, int port, int conTimeout) throws IOException {
		InetAddress adr = InetAddress.getByName(host);
		InetSocketAddress sadr = new InetSocketAddress(adr,port);
		poolKey = host + ":" + port;
		initConnection(sadr,conTimeout);	
		timeout = new TimeoutTime(toNotify);
		
	}
	
	
	private Connection(InetSocketAddress sadr, int conTimeout) throws IOException {
		poolKey = sadr.getAddress().getHostAddress() + ":" + sadr.getPort();
		initConnection(sadr,conTimeout);		
		timeout = new TimeoutTime(toNotify);		
	}
	
	private void initConnection(InetSocketAddress sadr, int conTimeout) throws IOException {
		socket = new Socket();
		
		if (conTimeout > 0)				
			socket.connect(sadr,conTimeout);
		else 
			socket.connect(sadr);
		
		socketIn = socket.getInputStream();
		socketOut = socket.getOutputStream();
	}

	
	public static Connection connect(InetSocketAddress sadr, int conTimeout) throws IOException {

		Connection con = poolRemove(sadr.getAddress().getHostAddress(), sadr.getPort());
		if (con == null) {	
			con = new Connection(sadr,conTimeout);
		}		
		con.initStreams();
		connAquired.add(con);
		return con;
	}
	
	public static Connection connect(InetSocketAddress address)	throws IOException {
		return connect(address,-1);
	}
	
	public static Connection connect(String host, int port, int conTimeout) throws IOException {

		Connection con = poolRemove(host, port);
		if (con == null) {			
			con = new Connection(host, port, conTimeout);		
		}		
		con.initStreams();
		connAquired.add(con);
		return con;
	}
	
	public static Connection connect(String host, int port) throws IOException {
		return connect(host,port,-1);
	}
	
	public static void setPoolTimeoutSeconds(int secs) {
		POOLTIMEOUT_SECONDS=secs;
	}

	private void initStreams() {
		in = new PooledConnectionInputStream(socketIn);
		out = new PooledConnectionOutputStream(socketOut);
	}

	public static void invalidate() {
		synchronized (connPooled) {
			Vector[] destinations = (Vector[]) connPooled.values().toArray(new Vector[0]);
			for (int i = 0; i < destinations.length; i++) {
				Connection[] cons = (Connection[]) destinations[i].toArray(new Connection[0]);
				for (int ii = 0; ii < cons.length; ii++)
					cons[ii].release(false);				
			}
			
			Connection[] cons = (Connection[]) connAquired.toArray(new Connection[0]);
			for (int i = 0; i < cons.length; i++)
				cons[i].release(false);
		}
	}
	
	public static void poolReuse(Connection con) {
		synchronized (connPooled) {
			if (!con.aquired)
				throw new IllegalStateException("Inconsistwent Connection State - Cannot release non aquired connection");
			con.aquired=false;
			Vector hostCons = (Vector) connPooled.get(con.poolKey);
			if (hostCons == null) {
				hostCons = new Vector();
				connPooled.put(con.poolKey, hostCons);
			}
			toNotify.register(con);
			con.timeout.setTimeout(POOLTIMEOUT_SECONDS*1000);
			
			hostCons.add(con);
		}

	}

	public static Connection poolRemove(String host, int port) {
		String key = host + ":" + port;
		synchronized (connPooled) {
			Vector hostCons = (Vector) connPooled.get(key);
			if (hostCons == null) {
				return null;
			}
			boolean found = false;
			Connection con = null;
			while (!found && !hostCons.isEmpty()) {
				con = (Connection) hostCons.remove(hostCons.size() - 1);
				if (con.aquired)
					throw new IllegalStateException("Inconsistent Connection State - Cannot take already aquired connection from pool!");
				con.aquired=true;
				toNotify.unregister(con);
				found = con.isAlive();
				if (!found) {
					con.release(false);
					con = null;
				}
			}
			if (hostCons.isEmpty())
				connPooled.remove(key);
			
			return con;

		}
	}
	
	
	private boolean isAlive() {
		// Must only be called when sure that there is no data to read - otherwise Illegal State!
		
		try {
			socket.setSoTimeout(1);
			int r = socketIn.read();
			if (r!=-1) {
				int avail = socketIn.available();
				byte buf[] = new byte [Math.max(avail, 10240)];
				socketIn.read(buf);
				String data = ((char)r)+new String(buf);
				Logger.getLogger().logException(new IllegalStateException("Connection.isAlive received data on pooled connection:\r\n"+data));
			}
			return false;
		} catch (SocketTimeoutException to) {
			try {
				socket.setSoTimeout(0);
				return true;
			} catch (SocketException e) {
				return false;
			}			
		} catch (Exception e) {
			return false;
		}	
	}



	public OutputStream getOutputStream() {
		return out;
	}

	public InputStream getInputStream() {
		return in;
	}

	public void release(boolean reuse) {
		
		if (!valid) //a killed connection already released
			return;
		
		connAquired.remove(this);

		if (reuse) {
			in.invalidate();
			out.invalidate();
			try {
				socket.setSoTimeout(0);
			} catch (SocketException e) {
				release(false);
				return;
			}
			poolReuse(this);
		} else  {
			try {
				valid = false;
				socket.shutdownOutput();
				socket.shutdownInput();
				socket.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
			}
		}
	}
	
	public void setSoTimeout(int millis) throws SocketException {
		socket.setSoTimeout(millis);
	}


	@Override
	public void timeoutNotification() {
		
		boolean found = false;
		
		synchronized (connPooled) {
			
			Vector hostCons = (Vector) connPooled.get(poolKey);
			if (hostCons == null) {
				return;
			}	
			found = hostCons.remove(this);	
			if (hostCons.isEmpty())
				connPooled.remove(poolKey);
		}
		if (found) //if false, than connection was just taken by another thread
			release(false);
	}

	@Override
	public long getTimoutTime() {
		// TODO Auto-generated method stub
		return timeout.getTimeout();
	}
	

	
	// return count of received and sent bytes
	public long[] getTraffic() {
		if (!aquired)
			throw new IllegalStateException("Inconsistent Connection State - Connection is not aquired!");
		return new long[] {in.getTraffic(),out.getTraffic()};
	}
	
	
	// return destination (host:port)
	public String getDestination() {
		return poolKey;
	}
	
}
