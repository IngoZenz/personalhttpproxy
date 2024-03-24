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
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;


import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class HttpProxyService extends Service implements ExecutionEnvironmentInterface {

	public static HttpProxy httpproxy = null;
	private static WakeLock wakeLock = null;
	PendingIntent pendingIntent;
	Notification.Builder notibuilder;



	private String getChannel() {
		final String NOTIFICATION_CHANNEL_ID = "personalHTTPProxy";

		NotificationManager mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		if (Build.VERSION.SDK_INT >= 26) {
			mNotificationManager.createNotificationChannel(new NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT));
		}

		return NOTIFICATION_CHANNEL_ID;
	}

	private void updateNotification() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN)
			return;
		try {
			notibuilder.setContentTitle("personalHTTPProxy is running!");
			notibuilder.setSmallIcon(R.drawable.icon);
			((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(5876);
			((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(5876,notibuilder.build());
		} catch (Exception e){
			Logger.getLogger().logException(e);
		}

	}
	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		ExecutionEnvironment.setEnvironment(this);
		try {
			Intent notificationIntent = new Intent(this, HttpProxyActivity.class);
			pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

			if (android.os.Build.VERSION.SDK_INT >= 16) {

				if (android.os.Build.VERSION.SDK_INT >= 26)
					notibuilder = new Notification.Builder(this, getChannel());
				else
					notibuilder = new Notification.Builder(this);

				notibuilder
						.setSmallIcon(R.drawable.icon)
						.setContentIntent(pendingIntent)
						.build();

				updateNotification();
			} else {
				Notification notification = new Notification();
				Intent notificationIntent2 = new Intent(this, HttpProxyActivity.class);
				pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent2, 0);
				try {
					Method deprecatedMethod = notification.getClass().getMethod("setLatestEventInfo", Context.class, CharSequence.class, CharSequence.class, PendingIntent.class);
					deprecatedMethod.invoke(notification, this,"PersonalHttpProxy", "Running", pendingIntent);
				} catch (NoSuchMethodException | IllegalAccessException | IllegalArgumentException
						 | InvocationTargetException e) {
					Logger.getLogger().logException(e);
				}
				//notification.setLatestEventInfo(this, "PersonalHttpProxy", "Running", pendingIntent);
				startForeground(23, notification);
			}
			if (httpproxy != null) {
				Logger.getLogger().logLine("HTTPproxy already running!");		
			} else {			
				try {
					HttpProxy.WORKDIR=new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy").getAbsolutePath()+"/";
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
				((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(5876);
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