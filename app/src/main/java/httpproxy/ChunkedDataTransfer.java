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

import java.io.IOException;
import java.io.OutputStream;

public class ChunkedDataTransfer {
	

	
	private OutputStream out;
	private static int TRAILEREND = 218762506;  // \r\n\r\n
	private static short LINEEND = 3338; // \r\n
	private StringBuffer chunkHeader = new StringBuffer(); 
	private int remainingChunkLength = 0;
	private boolean chunkedRequestTermination = false;
	private boolean chunkTermination = false;
	private short last2Bytes = 0;
	private int last4Bytes = 0;
	
	public int lastBytesProcessed;
	
	public ChunkedDataTransfer(OutputStream out) {
		this.out = out;
		
	}
	

	
	public boolean write(int b) throws IOException {
		return 	write(new byte[] {(byte) b},0,1);	
	}
	
	
	private void writeThrough(byte[] buf, int offs, int len) throws IOException {
		if (out != null)
			out.write(buf,offs,len);
	}
	
	public boolean write(byte[] buf, int offs, int length) throws IOException {
		
		boolean allBytesProcessed = false;
		int len = length;
		lastBytesProcessed = 0;
		
		while (!allBytesProcessed) {
		
			if (remainingChunkLength != 0 && ! chunkTermination ) { //receiving chunk
				
				int len2process = Math.min(len, remainingChunkLength);
				remainingChunkLength=remainingChunkLength-len2process;
				writeThrough(buf,offs,len2process);
				if (remainingChunkLength == 0) {
					chunkTermination=true;				
					remainingChunkLength = 2; // next receive empty line
				}	
				offs= offs+len2process;
				len = len-len2process;
				allBytesProcessed = (len == 0);
					
			} else if (chunkTermination) {
                last2Bytes = (short) ((last2Bytes <<8)+ (int)(buf[offs] & 0xff));                
                remainingChunkLength--;
                writeThrough(buf,offs,1);
                offs++;
                len--;
                allBytesProcessed = (len == 0);
                if (remainingChunkLength == 0) {  //termination completed
                    if (last2Bytes != LINEEND) 
                        throw new IOException("Invalid Chunk Termination!"); // Invalid finalization
                    
                    if (out != null)
                        out.flush();
                    
                    chunkHeader = new StringBuffer();                
                    chunkTermination = false;
                    last2Bytes = 0;                                
                }
						
			} else if (chunkHeader != null) { // we are receiving a header
				chunkHeader.append((char) (buf[offs]& 0xff));
				last2Bytes = (short) ((last2Bytes <<8)+ (int) (buf[offs]& 0xff));
				if (last2Bytes == LINEEND) { //header complete
					last2Bytes = 0; //reset								
					try {
						remainingChunkLength = Integer.parseInt(chunkHeader.toString().trim(), 16);
					} catch (NumberFormatException nfe) {
						throw new IOException("Can not parse ChunkHeader to HEX Number:"+chunkHeader.toString().trim());
					}				
					if (remainingChunkLength == 0) { //final chunk					
						chunkedRequestTermination = true;
						last4Bytes = LINEEND; // next receive (possibly empty) trailer
					}
					chunkHeader = null;
				}
				writeThrough(buf,offs,1);
				offs++;
				len--;
				allBytesProcessed = (len == 0);
			} else if(chunkedRequestTermination) { //receive trailer until \r\n\r\n
				last4Bytes = (last4Bytes <<8)+ (int) (buf[offs]& 0xff);
				writeThrough(buf,offs,1);
				offs++;
				len--;
				allBytesProcessed = (len == 0);
				
				if (last4Bytes == TRAILEREND) {
					if (out != null)
						out.flush();
					
					lastBytesProcessed = lastBytesProcessed + length-len;
					return false;
				}			
				
			} else throw new IOException ("Invalid ChunkedDataTransfer State!");
		}
		lastBytesProcessed = lastBytesProcessed + length;
		return true;
	}
	
	
	
	public void initChunkedTransfer() {
		chunkHeader = new StringBuffer(); 
		remainingChunkLength = 0;
		chunkedRequestTermination = false;
		chunkTermination=false;
		chunkTermination = false;
		last2Bytes = 0;
	}


}
