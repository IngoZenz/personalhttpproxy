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
package httpproxy.android;


import proxy.HttpProxy;
import util.ExecutionEnvironment;
import util.ExecutionEnvironmentInterface;
import util.Logger;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

public class HttpProxyService extends Service implements ExecutionEnvironmentInterface {

	public static HttpProxy httpproxy = null;
	private static WakeLock wakeLock = null;

	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		ExecutionEnvironment.setEnvironment(this);
		try {
			Notification notification = new Notification();
			Intent notificationIntent = new Intent(this, HttpProxyActivity.class);
			PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
			notification.setLatestEventInfo(this, "PersonalHttpProxy", "Running", pendingIntent);
			startForeground(23, notification);
			if (httpproxy != null) {
				Logger.getLogger().logLine("HTTPproxy already running!");		
			} else {			
				try {
					HttpProxy.WORKDIR=HttpProxyActivity.WORKPATH.getAbsolutePath()+"/";				
					httpproxy = new HttpProxy();					
					httpproxy.initMainLoop(new String[] {"-async"});
					Logger.getLogger().logLine("HTTPproxy started!");
				} catch (Exception e) {
					httpproxy = null;
					Logger.getLogger().logException(e);			
				}
			}	
		} catch (Exception e) {
			Logger.getLogger().logException(e);
		}
	}
	
	@Override 
	public void onDestroy() {
		try {
			if (httpproxy != null)	{		
				httpproxy.stop();
				Logger.getLogger().logLine("HTTPproxy stopped!");
			}		
			httpproxy = null;
		} catch (Exception e) {
			Logger.getLogger().logException(e);
		}
		
	}

	@Override
	public IBinder onBind(Intent intent) {
		// TODO Auto-generated method stub
		return null;
	}

	public int onStartCommand(Intent intent, int flags, int startId) {
		onStart(intent, startId);
		// We want this service to continue running until it is explicitly
		// stopped, so return sticky.
		return START_STICKY;
	}

	@Override
	public void wakeLock() {
		wakeLock = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "My Tag");
		wakeLock.acquire();			
	}

	@Override
	public void releaseWakeLock() {
		WakeLock wl = wakeLock;
		if (wl != null)
			wl.release();		
	}

	@Override
	public String getWorkDir() {
		return HttpProxyActivity.WORKPATH.getAbsolutePath()+"/";
	}
}