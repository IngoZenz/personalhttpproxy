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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;

import util.LRUCache;
import util.Utils;

public class BlockedUrls implements Set {

	private static int MAXPREFIX_LEN=20;
	private LRUCache okCache;
	private LRUCache filterListCache;
	private Hashtable urlFilterOverRule;

	private int sharedLocks = 0;
	private boolean exclusiveLock = false;

	private Vector blockedURLs;

	public BlockedUrls(int okCacheSize, int filterListCacheSize, Hashtable urlFilterOverRule) {
		okCache = new LRUCache(okCacheSize);
		filterListCache = new LRUCache(filterListCacheSize);
		this.urlFilterOverRule = urlFilterOverRule;
		blockedURLs = new Vector();
	}
	
	public void appyList(InputStream in) throws IOException {
		lock(1);
		try {
			clear();
			BufferedReader rin = new BufferedReader(new InputStreamReader(in));
			String entry;
			while ((entry = rin.readLine()) != null) {
				entry = entry.trim();
				if (!entry.startsWith("#") && !entry.equals("")) 
					blockedURLs.addElement(entry.trim().split("\\*", -1));				
			}
			rin.close();	
		} finally {
			unLock(1);
		}
	}

	synchronized public void lock(int type) {
		if (type == 0) {
			while (exclusiveLock) {
				try {
					wait();
				} catch (Exception e) {
					// ignore
				}
			}
			sharedLocks++;
		} else if (type == 1) {
			while (!(sharedLocks == 0) || (exclusiveLock)) {
				try {
					wait();
				} catch (InterruptedException e) {
					// ignore
				}
			}

			exclusiveLock = true;
		}
	}

	synchronized public void unLock(int type) {
		if (type == 0) {
			if (sharedLocks > 0) {
				sharedLocks--;
				if (sharedLocks == 0) {
					notifyAll();
				}
			}
		} else if (type == 1) {
			if (exclusiveLock) {
				exclusiveLock = false;
				notifyAll();
			}
		}
	}

	@Override
	public boolean add(Object host) {
		throw new UnsupportedOperationException("Not supported!");
	}
	
	private String prefix(String str, int len) {
		if (str == null)
			return null;
		else return str.substring(0, Math.min(str.length(), len));
	}
	
	private boolean isPrefix(String str, String prefix, int len) {
		return prefix(str,len).equals(prefix);		
	}
	
	
	@Override
	public boolean contains(Object object) {
		
		// Positive and negative URL match caches are used as wildcard matching is expensive.
		// Optimized Caching as URLs can get pretty long.
		// URL match data is cached with the longhash as key and a URL prefix (maximum length MAXPREFIX_LEN) as value.
		// When checking, it is checked if the cache contains an entry (longhash, prefix).
		// should be pretty safe - however there might be very rare cases of false positives ==> ignored for now!

		try {
			lock(0); // shared read lock ==> block Updates of the structure

			String url = (String) object;
			
			//in OK Cache ==> Not filtered
			if (isPrefix(url, (String)okCache.get(Utils.getLongStringHash(url)), MAXPREFIX_LEN)) 
				return false;
			//else in negative cache ==> Filtered
			else if (isPrefix(url, (String) filterListCache.get(Utils.getLongStringHash(url)),MAXPREFIX_LEN))
				return true;
			//else check wildcards and update caches
			else if (containsMatch(url)) {
				filterListCache.put(Utils.getLongStringHash(url), prefix(url,MAXPREFIX_LEN));
				return true;
			} else {
				okCache.put(Utils.getLongStringHash(url), prefix(url,MAXPREFIX_LEN));
				return false;
			}
		} finally {
			unLock(0);
		}
	}

	private boolean containsMatch(String url) {

		if (urlFilterOverRule != null) {
			Object val = urlFilterOverRule.get(url);
			if (val != null)
				return ((Boolean) val).booleanValue();
		}
		Iterator it = blockedURLs.iterator();
		while (it.hasNext()) {
			String[] fixedParts = (String[]) it.next();
			if (wildCardMatch(fixedParts, url))
				return true;
		}
		return false;
	}

	public static boolean wildCardMatch(String[] fixedParts, String url) {

		// Iterate over the parts.
		for (int i = 0; i < fixedParts.length; i++) {
			String part = fixedParts[i];
			
			int idx = -1;
			if (i < fixedParts.length-1)
				idx = url.indexOf(part);
			else
				idx = url.lastIndexOf(part);
				
			
			if (i == 0 && !part.equals("") && idx != 0) {
				// i == 0 ==> we are on the first fixed part
				// first fixed part is not empty ==> Matching String must start with first fixed part
				// if not, no match!
				return false;
			}
			
			if (i == fixedParts.length-1 && !part.equals("") && idx + part.length() != url.length()) {
				// i == last part 
				// last part is not empty ==> Matching String must end with last part
				// if not, no match
				return false;
			}

			// part not detected in the text.
			if (idx == -1) {
				return false;
			}

			// Move ahead, towards the right of the text.
			url = url.substring(idx + part.length());

		}

		return true;
	}

	public void clear() {
		blockedURLs.clear();		
		filterListCache.clear();
		okCache.clear();
	}

	@Override
	public boolean addAll(Collection arg0) {
		throw new UnsupportedOperationException("Not supported!");
	}

	@Override
	public boolean containsAll(Collection arg0) {
		throw new UnsupportedOperationException("Not supported!");
	}

	@Override
	public boolean isEmpty() {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Not supported!");
	}

	@Override
	public Iterator iterator() {
		throw new UnsupportedOperationException("Not supported!");
	}

	@Override
	public boolean remove(Object object) {
		throw new UnsupportedOperationException("Not supported!");
	}

	@Override
	public boolean removeAll(Collection arg0) {
		throw new UnsupportedOperationException("Not supported!");
	}

	@Override
	public boolean retainAll(Collection arg0) {
		throw new UnsupportedOperationException("Not supported!");
	}

	@Override
	public int size() {
		throw new UnsupportedOperationException("Not supported!");
	}

	@Override
	public Object[] toArray() {
		throw new UnsupportedOperationException("Not supported!");
	}

	@Override
	public Object[] toArray(Object[] array) {
		throw new UnsupportedOperationException("Not supported!");
	}

}
