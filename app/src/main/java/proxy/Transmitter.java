
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

package proxy;

import java.io.*;
import java.net.*;

import util.Logger;


public class Transmitter implements Runnable {
	Socket source = null;
	Socket dest = null;
	HttpProxy proxy = null;
	String role = null;

	InputStream in = null;
	OutputStream out = null;

	public Transmitter(Socket source, Socket dest, HttpProxy proxy, String role) {
		this.proxy = proxy;
		this.dest = dest;
		this.source = source;
		this.role = role;
	}

	public boolean start() {
		try {
			in = source.getInputStream();
			out = dest.getOutputStream();
		} catch (Exception e) {
			System.out.println(role + ":Exception during startup " + e.toString());
			return (false);
		}

		Thread transferThread = new Thread(this);
		transferThread.start();
		return (true);
	}

	public void run() {

		try {
			int r = 0;
			byte[] b = new byte[5096];

			while ((r = in.read(b)) != -1) {

				out.write(b, 0, r);
				out.flush();

				if (proxy.debug) {
					for (int i = 0; i < r; i++)
						if (!((b[i] < 64 && b[i] > 32) || (b[i] < 91 && b[i] > 64) || (b[i] < 123 && b[i] > 96)))
							if (b[i] != 10 && b[i] != 13)
								b[i] = 46;
					Logger.getLogger().log(new String(b, 0, r));
				}

			}

			proxy.cleanUp(0, role);
		} catch (IOException eio) {
			proxy.cleanUp(-1, role);
		} catch (Exception e) {
			Logger.getLogger().logException(e);
			proxy.cleanUp(-1, role);
		}
	}

}
