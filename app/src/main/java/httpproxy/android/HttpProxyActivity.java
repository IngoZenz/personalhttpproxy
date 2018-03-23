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
package httpproxy.android;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;

import proxy.HttpProxy;
import util.AsyncBulkLogger;
import util.Logger;
import util.LoggerInterface;
import util.Utils;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

public class HttpProxyActivity extends Activity implements OnClickListener, LoggerInterface {

	
	protected static boolean BOOT_START = false;
	
	private Button startBtn;
	private Button stopBtn;	
	private Button reloadFilterBtn;
	private static TextView logOutView;
	private static int logSize = 0;
	private static EditText portField;
	private static CheckBox advancedConfigCheck;
	private static CheckBox keepAwakeCheck;
	private static CheckBox enableAutoStartCheck;	
	private static CheckBox enableAdFilterCheck;
	private static EditText advancedConfigField;
	private static LoggerInterface myLogger;
	
	private ScrollView scrollView = null;

	private static boolean appStart = true;
	
	public static String PORTSTR=null;
	public static String FTPPORTSTR=null;	
	public static File WORKPATH=null;	
	
	private static WifiLock wifiLock;
	private static WakeLock wakeLock;	
	
	
	private static Intent SERVICE = null;
	

	private class MyUIThreadLogger implements Runnable {;

		private String m_logStr;

		public MyUIThreadLogger(String logStr) {
			m_logStr = logStr;
		}

		@Override
		public void run() {
			logSize = logSize + m_logStr.length();
			logOutView.append(m_logStr);
			if (logSize >=20000) {
				String logStr = logOutView.getText().toString();
				logStr = logStr.substring(logSize-10000);
				logSize = logStr.length();
				logOutView.setText(logStr);
				
			}
			scrollView.fullScroll(ScrollView.FOCUS_DOWN);
			setTitle("PersonalHttpProxy (Connections:"+HttpProxy.openConnectionsCount()+")");
		}
	}	
	
	private void threadDump() {
		Map<Thread, StackTraceElement[]> dump = Thread.getAllStackTraces();
		Iterator<Thread> threadIt = dump.keySet().iterator();
		while (threadIt.hasNext()) {
			Thread t = threadIt.next();
			StackTraceElement[] stack = dump.get(t);
			Logger.getLogger().logLine(t.toString());
			Logger.getLogger().logLine("********************************************************************");
			
			for (int i = 0; i < stack.length; i++) {
				Logger.getLogger().logLine(stack[i].toString());
			}
		}
	}
	
	
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		setTitle("PersonalHttpProxy (Connections:"+HttpProxy.openConnectionsCount()+")");
		startBtn = (Button) findViewById(R.id.startBtn);
		startBtn.setOnClickListener(this);
		stopBtn = (Button) findViewById(R.id.stopBtn);
		stopBtn.setOnClickListener(this);
		reloadFilterBtn = (Button) findViewById(R.id.filterReloadBtn);
		reloadFilterBtn.setOnClickListener(this);
		
		String uiText = "";

		if (logOutView != null)
			uiText = logOutView.getText().toString();
		logOutView = (TextView) findViewById(R.id.logOutput);
		logOutView.setText(uiText);
		uiText = "";

		scrollView = (ScrollView) findViewById(R.id.ScrollView01);

		if (portField != null)
			uiText = portField.getText().toString();
		portField = (EditText) findViewById(R.id.portField);
		portField.setText(uiText);
		
		boolean checked = enableAdFilterCheck != null && enableAdFilterCheck.isChecked();		
		enableAdFilterCheck = (CheckBox) findViewById(R.id.enableAddFilter);
		enableAdFilterCheck.setChecked(checked);
		enableAdFilterCheck.setOnClickListener(this);
		
		checked = enableAutoStartCheck != null && enableAutoStartCheck.isChecked();
		enableAutoStartCheck = (CheckBox) findViewById(R.id.enableAutoStart);
		enableAutoStartCheck.setChecked(checked);
		enableAutoStartCheck.setOnClickListener(this);
		
		checked = keepAwakeCheck != null && keepAwakeCheck.isChecked();
		keepAwakeCheck = (CheckBox) findViewById(R.id.keepAwakeCheck);
		keepAwakeCheck.setChecked(checked);
		keepAwakeCheck.setOnClickListener(this);
		
		checked = advancedConfigCheck != null && advancedConfigCheck.isChecked();
		advancedConfigCheck = (CheckBox) findViewById(R.id.advancedConfigCheck);
		advancedConfigCheck.setChecked(checked);
		advancedConfigCheck.setOnClickListener(this);

		if (advancedConfigField != null)
			uiText = advancedConfigField.getText().toString();

		advancedConfigField = (EditText) findViewById(R.id.advancedConfigField);
		advancedConfigField.setText(uiText);		

		if (checked) {
			advancedConfigField.setVisibility(View.VISIBLE);
			keepAwakeCheck.setVisibility(View.VISIBLE);
		}
		else {
			advancedConfigField.setVisibility(View.GONE);	
			keepAwakeCheck.setVisibility(View.GONE);
		}

		if (myLogger!= null)
			myLogger.closeLogger();
		
		try {
			Logger.setLogger(new AsyncBulkLogger(this));
		} catch (IOException e) {
			Logger.setLogger(this);
			Logger.getLogger().logException(e);
		}
		myLogger = Logger.getLogger();
		

		if (appStart) {
			if (BOOT_START) {
				
				//send to back
				Intent i = new Intent();
				i.setAction(Intent.ACTION_MAIN);
				i.addCategory(Intent.CATEGORY_HOME);
				this.startActivity(i);
				BOOT_START = false;
			}
			logOutView.setText("");
			Properties config = getConfig();
			if (config != null) {
				portField.setText(config.getProperty("listenPort"));	
				enableAdFilterCheck.setChecked(config.getProperty("filterHostsFile")!=null);
				enableAutoStartCheck.setChecked(Boolean.parseBoolean(config.getProperty("AUTOSTART","false")));
				advancedConfigField.setText(  "# clear field to restore defaults!\n\nfilterAutoUpdateURL = "+config.getProperty("filterAutoUpdateURL", "")+"\n"
											+ "reloadIntervalDays = "+config.getProperty("reloadIntervalDays", "4")+"\n");
				
				logLine("Initializing ...");			
				appStart = false; // now started
				
				handleStart(); //start 
				
			}
		}

	}
	

	private Properties getConfig() {
			
		File propsFile = new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy/httpproxy.conf");
		if (!propsFile.exists()) {
			Logger.getLogger().logLine(propsFile+" not found! - creating default config!");
			createDefaultConfiguration();
			propsFile = new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy/httpproxy.conf");
		} 
		try {
			InputStream in = new FileInputStream(propsFile);
			Properties config= new Properties(); 
			config.load(in);
			in.close();
			
			//check versions, in case different merge existing configuration with defaults
			
			File versionFile = new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy/VERSION.TXT");
			String vStr="";
			if (versionFile.exists()) {
				InputStream vin = new FileInputStream(versionFile);
				vStr = new String(Utils.readFully(vin, 100));
				vin.close();				
			}
			if (!vStr.equals(HttpProxy.VERSION)) {
				//Version Change ==> merge config with new default config
				Logger.getLogger().logLine("Updated version! Previous version:"+vStr+", current version:"+HttpProxy.VERSION);
				createDefaultConfiguration();
				config = mergeAndPersistConfig(config);
			}
			
			return config;
		} catch (Exception e ){
			Logger.getLogger().logException(e);
			return null;
		}
				
	}	
	
	private Properties mergeAndPersistConfig(Properties currentConfig) throws IOException {
		String[] currentKeys = currentConfig.keySet().toArray(new String[0]);		
		BufferedReader defCfgReader = new BufferedReader( new InputStreamReader(this.getAssets().open("httpproxy.conf")));
		File mergedConfig = new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy/httpproxy.conf");
		FileOutputStream mergedout = new FileOutputStream(mergedConfig);
		String ln="";
		while ( (ln = defCfgReader.readLine()) != null) {
			for (int i = 0; i < currentKeys.length; i++) 
				if (ln.startsWith(currentKeys[i])) 
					ln = currentKeys[i]+" = "+currentConfig.getProperty(currentKeys[i],"");
			
			mergedout.write((ln+"\r\n").getBytes());
		}
		defCfgReader.close();
		
		//take over custom properties (such as filter overrules) which are not in def config
		
		Properties defProps = new Properties();		
		defProps.load(this.getAssets().open("httpproxy.conf"));
		boolean first = true;
		for (int i = 0; i < currentKeys.length; i++) {
			if (!defProps.containsKey(currentKeys[i])) {
				if (first)
					mergedout.write(("# Merged custom config from previous config file:\r\n" ).getBytes());
				first = false;
				ln = currentKeys[i]+" = "+currentConfig.getProperty(currentKeys[i],"");
				mergedout.write((ln+"\r\n").getBytes());
			}
		}		
		
		mergedout.flush();
		mergedout.close();
		Logger.getLogger().logLine("Merged configuration 'httpproxy.conf' after update to version "+HttpProxy.VERSION+"!");
		InputStream in = new FileInputStream(mergedConfig);
		Properties config= new Properties(); 
		config.load(in);
		in.close();
		return config;				
	}


	private void createDefaultConfiguration() {
		try {
			
			File f = new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy");
			f.mkdir();
			
			f = new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy/.nomedia");
			if (!f.exists()) f.createNewFile();
			
			f = new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy/httpproxy.conf");
			f.createNewFile();
			FileOutputStream fout = new FileOutputStream(f);
			
			AssetManager assetManager=this.getAssets();
			InputStream defIn = assetManager.open("httpproxy.conf");
			byte[] buf = new byte[1024];
			int r = 0;
			
			while ((r = defIn.read(buf)) != -1)
				fout.write(buf,0,r);
			
			fout.flush();
			fout.close();
			defIn.close();
			
			f = new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy/filter.gif");
			f.createNewFile();
			fout = new FileOutputStream(f);
			defIn = assetManager.open("filter.gif");
			while ((r = defIn.read(buf)) != -1)
				fout.write(buf,0,r);
			
			fout.flush();
			fout.close();
			defIn.close();
			
			f = new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy/FILTERURLS.TXT");
			f.createNewFile();
			fout = new FileOutputStream(f);
			defIn = assetManager.open("FILTERURLS.TXT");
			while ((r = defIn.read(buf)) != -1)
				fout.write(buf,0,r);
			
			fout.flush();
			fout.close();
			defIn.close();
			
			f = new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy/VERSION.TXT");
			f.createNewFile();
			fout = new FileOutputStream(f);
			
			fout.write(HttpProxy.VERSION.getBytes());
			
			fout.flush();
			fout.close();
			
					
			Logger.getLogger().logLine("Default configuration created successfully!");	
		} catch (IOException e) {
			Logger.getLogger().logLine("FAILED creating default Configuration!");	
			Logger.getLogger().logException(e);			
		}
		
	}


	private void persistConfig() {
		try {
			boolean filterAds = enableAdFilterCheck.isChecked();
			
			File propsFile = new File (Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy/httpproxy.conf");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			String ln;
						
			if ((advancedConfigField.getText().toString().trim().equals("")))
					restoreAdvancedConfigDefault();
			else 
				if (!advCfgValid())
					revertAdvancedConfig();
			
			Properties advancedConfigProps = new Properties();
			advancedConfigProps.load(new ByteArrayInputStream(advancedConfigField.getText().toString().getBytes()));
			BufferedReader reader = new BufferedReader( new InputStreamReader(new FileInputStream(propsFile)));
			while ((ln = reader.readLine())!= null) {
				if (ln.trim().startsWith("listenPort"))
					ln = "listenPort = "+portField.getText().toString();
				
				if (ln.trim().startsWith("filterAutoUpdateURL"))
					ln = "filterAutoUpdateURL = "+advancedConfigProps.remove("filterAutoUpdateURL");				
				
				if (ln.trim().startsWith("reloadIntervalDays"))
					ln = "reloadIntervalDays = "+advancedConfigProps.remove("reloadIntervalDays");
				
				if (ln.trim().startsWith("AUTOSTART"))
					ln = "AUTOSTART = "+  enableAutoStartCheck.isChecked();
				
				if (ln.trim().startsWith("#!!!filterHostsFile") && filterAds)
					ln = ln.replace("#!!!filterHostsFile", "filterHostsFile");
				
				if (ln.trim().startsWith("filterHostsFile") && !filterAds)
					ln = ln.replace("filterHostsFile", "#!!!filterHostsFile");
				
				out.write((ln+"\r\n").getBytes());
			}
			
			//read remaining supported properties from advanced config
			Iterator it = advancedConfigProps.keySet().iterator();
			while (it.hasNext()) {	
				String prop = (String) it.next();
				if (prop.equals("reloadIntervalDays")) //new propertry added since first release
					out.write(  (prop+" = "+advancedConfigProps.getProperty(prop,"")+"\r\n").getBytes());
			}
			
			reader.close();
			out.flush();
			out.close();
			FileOutputStream fout = new FileOutputStream(Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy/httpproxy.conf");
			fout.write(out.toByteArray());
			fout.flush();
			fout.close();
			Logger.getLogger().logLine("Config persisted!\nRestart is required in case of configuration changes!");
			
		} catch (Exception e) {
			Logger.getLogger().logException(e);
		}
	}

	


	private boolean advCfgValid() {
		
		
		try {					
			Properties advancedConfigProps = new Properties();
			advancedConfigProps.load(new ByteArrayInputStream(advancedConfigField.getText().toString().getBytes()));
			
			//check filterAutoUpdateURL
			
			String urls = advancedConfigProps.getProperty("filterAutoUpdateURL");
			
			if (urls == null)
				throw new Exception("'filterAutoUpdateURL' property not defined!");
			
			StringTokenizer urlTokens = new StringTokenizer(urls,";");			
			
			int urlCnt = urlTokens.countTokens();
			for (int i = 0; i < urlCnt; i++) {
				String urlStr = urlTokens.nextToken().trim();
				if (!urlStr.equals("")) {
					new URL(urlStr);
				}
			}
			
			//check reloadIntervalDays
			
			try {
				Integer.parseInt(advancedConfigProps.getProperty("reloadIntervalDays"));
			} catch (Exception e0){
				throw new Exception("'reloadIntervalDays' property not defined correctly!");
			}
			
			return true;
			
		} catch (Exception e) {
			Logger.getLogger().logLine("Exception while validating advanced settings:"+e.getMessage());
			Logger.getLogger().logLine("Advanced settings are invalid - will be reverted!");
			return false;
		}
	}


	private void revertAdvancedConfig() throws IOException {
		File propsFile = new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy/httpproxy.conf");	
		InputStream in = new FileInputStream(propsFile);
		restoreAdvancedConfig(in);
		
	}


	private void restoreAdvancedConfigDefault() throws IOException {		
		AssetManager assetManager=this.getAssets();
		InputStream defIn = assetManager.open("httpproxy.conf");
		restoreAdvancedConfig(defIn);
		
	}
	
	private void restoreAdvancedConfig(InputStream in) throws IOException {
		Properties defProps = new Properties();
		defProps.load(in);
		in.close();
		advancedConfigField.setText(  "# clear field to restore defaults!\n\nfilterAutoUpdateURL = "+defProps.getProperty("filterAutoUpdateURL", "")+"\n"
				+ "reloadIntervalDays = "+defProps.getProperty("reloadIntervalDays", "4")+"\n");
	}


	@Override
	public void onClick(View destination) {
		persistConfig();
		
		if (destination == startBtn)
			handleStart();
		if (destination == stopBtn)
			handleStop();
		if (destination == reloadFilterBtn)
			handlefilterReload();
		
		if (destination == advancedConfigCheck) {

			if (advancedConfigCheck.isChecked()) {
				advancedConfigField.setVisibility(View.VISIBLE);
				keepAwakeCheck.setVisibility(View.VISIBLE);
				
			} else {
				advancedConfigField.setVisibility(View.GONE);	
				keepAwakeCheck.setVisibility(View.GONE);
			}
			
		}		
		if (destination == keepAwakeCheck) {
			if (keepAwakeCheck.isChecked()) {
				wifiLock = ((WifiManager) getSystemService(Context.WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL, "personalHttpProxy");
				wifiLock.acquire();
				wakeLock = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "personalHttpProxy");
				wakeLock.acquire();	
				Logger.getLogger().logLine("Aquired WIFI lock and partial wake lock!");
			} else {
				if (wifiLock != null  && wakeLock != null){
					wifiLock.release();
					wakeLock.release();
					wifiLock = null;
					wakeLock = null;
					Logger.getLogger().logLine("Released WIFI lock and partial wake lock!");
				}
			}
			
		}
	}


	private void handlefilterReload() {
		if (HttpProxyService.httpproxy != null)
			HttpProxyService.httpproxy.triggerUpdateFilter();
		else Logger.getLogger().logLine("HTTP proxy is not running!");
	}


	private void handleStop() {	
		if (portField.getText().toString().equals("9999999999")) {
			threadDump();
			return;
		}
		if (SERVICE != null) 
			stopService(SERVICE);
		SERVICE = null;
		appStart = true;
		//finish();	
		System.exit(0);
	}

	private void handleStart() {			
		
		if (SERVICE!=null)
			stopService(SERVICE);
		
		SERVICE = null;
		
		WORKPATH = new File(Environment.getExternalStorageDirectory().getAbsolutePath()+"/PersonalHttpProxy");
		
		PORTSTR = portField.getText().toString();
		if (PORTSTR.equals("")) {
			logLine("Error - empty HTTP Proxy port!");
			return;
		}
		
		if (SERVICE == null)
			SERVICE=new Intent(this,HttpProxyService.class);		
		
		startService(SERVICE);
	}

	@Override
	public void logLine(String txt) {
		runOnUiThread(new MyUIThreadLogger(txt + "\n"));
	}

	@Override
	public void logException(Exception e) {
		StringWriter str = new StringWriter();
		e.printStackTrace(new PrintWriter(str));
		runOnUiThread(new MyUIThreadLogger(str.toString() + "\n"));
	}

	@Override
	public void log(String txt) {
		runOnUiThread(new MyUIThreadLogger(txt));
	}


	@Override
	public void closeLogger() {
		// TODO Auto-generated method stub
		
	}
	
}