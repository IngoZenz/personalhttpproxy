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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;

public class BootUpReceiver extends BroadcastReceiver{

    @Override
    public void onReceive(Context context, Intent intent) {
    	Properties config;
    	if ((config = getConfig()) != null && Boolean.parseBoolean(config.getProperty("AUTOSTART", "false"))) {
			if (Build.VERSION.SDK_INT >= 28) {
				Intent i = new Intent(context, HttpProxyService.class);
				context.startService(i);
			} else {
				HttpProxyActivity.BOOT_START = true;
				Intent i = new Intent(context, HttpProxyActivity.class);
				i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
				context.startActivity(i);
			}
    	}
            
    }
	
    
    public Properties getConfig() {
    	
    File propsFile = new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy/httpproxy.conf");
	 
		try {
			InputStream in = new FileInputStream(propsFile);
			Properties config= new Properties(); 
			config.load(in);
			in.close();
			return config;
		} catch (Exception e ) {
			return null;
		}
    }

}