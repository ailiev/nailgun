/*   

  Copyright 2004, Martian Software, Inc.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.

*/

package com.martiansoftware.nailgun;

import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.List;
import java.util.Properties;

/**
 * Reads the NailGun stream from the client through the command,
 * then hands off processing to the appropriate class.
 * 
 * @author <a href="http://www.martiansoftware.com/contact.html">Marty Lamb</a>
 */
class NGSession implements Runnable {

	private Socket socket = null;
	private NGServer server = null;
	private AliasManager aliasManager = null;
	
	// signatures of main(String[]) and nailMain(NGContext) for reflection
	private static Class[] mainSignature;
	private static Class[] nailMainSignature;
	
	static {
		mainSignature = new Class[1];
		mainSignature[0] = String[].class;
		
		nailMainSignature = new Class[1];
		nailMainSignature[0] = NGContext.class;
	}
	
	public NGSession() {
		super();
	}
	
	/**
	 * Initializes the NGSession
	 * @param server the NGServer that accepted the connection
	 * @param socket the connection to process
	 */
	public void init(NGServer server, Socket socket) {
		this.server = server;
		this.socket = socket;
		this.aliasManager = server.getAliasManager();
	}

	/**
	 * Runs the nail.
	 */
	public void run() {
	
		try {
			// buffer for reading headers
			byte[] lbuf = new byte[5];
			java.io.DataInputStream sockin = new java.io.DataInputStream(socket.getInputStream());
			java.io.OutputStream sockout = socket.getOutputStream();

			// remember for logging?
			PrintStream sysout = System.out;
			PrintStream syserr = System.err;
			InputStream sysin = System.in;
			
			// client info
			List remoteArgs = new java.util.ArrayList();
			Properties remoteEnv = new Properties();
			
			String cwd = null;
			String command = null;
			while (command == null) {
				sockin.readFully(lbuf);
				long bytesToRead = LongUtils.fromArray(lbuf, 0);
				char chunkType = (char) lbuf[4];
				
				byte[] b = new byte[(int) bytesToRead];
				sockin.readFully(b);
				String line = new String(b, "US-ASCII");

				switch(chunkType) {
					case 'A':	remoteArgs.add(line);
								break;
					case 'E':	int equalsIndex = line.indexOf('=');
								if (equalsIndex > 0) {
									remoteEnv.setProperty(
											line.substring(0, equalsIndex),
											line.substring(equalsIndex + 1));
								}
								String key = line.substring(0, equalsIndex);
					// parse environment into property
								break;
					case 'C':	command = line;
								break;
					case 'D':	cwd = line;
								break;
					default:	// freakout
				}
			}

			Thread.currentThread().setName("NGSession for " + socket.getInetAddress().getHostAddress() + ": " + command);
			
			// can't create NGInputStream until we've received a command.
			InputStream in = new NGInputStream(sockin);
			PrintStream out = new PrintStream(new NGOutputStream(sockout, '1'));
			PrintStream err = new PrintStream(new NGOutputStream(sockout, '2'));
			PrintStream exit = new PrintStream(new NGOutputStream(sockout, 'X'));

			// set streams for server side
			((ThreadLocalInputStream) System.in).init(in);
			((ThreadLocalPrintStream) System.out).init(out);
			((ThreadLocalPrintStream) System.err).init(err);
			

			// TODO: add alias lookup
			try {
				Alias alias = aliasManager.getAlias(command);
				Class cmdclass = null;
				if (alias != null) {
					cmdclass = alias.getAliasedClass();
				} else {
					cmdclass = Class.forName(command);
				}

				Object[] methodArgs = new Object[1];
				Method mainMethod = null;
				String[] cmdlineArgs = (String[]) remoteArgs.toArray(new String[remoteArgs.size()]);
				
				try {
					mainMethod = cmdclass.getMethod("nailMain", nailMainSignature);
					NGContext context = new NGContext();
					context.setArgs(cmdlineArgs);
					context.in = in;
					context.out = out;
					context.err = err;
					context.setCommand(command);
					context.setExitStream(exit);
					context.setNGServer(server);
					context.setEnv(remoteEnv);
					context.setInetAddress(socket.getInetAddress());
					context.setPort(socket.getPort());
					context.setWorkingDirectory(cwd);
					methodArgs[0] = context;
				} catch (NoSuchMethodException toDiscard) {
					// that's ok - we'll just try main(String[]) next.
				}
				
				if (mainMethod == null) {
					mainMethod = cmdclass.getMethod("main", mainSignature);
					methodArgs[0] = cmdlineArgs;
				}
				
				if (mainMethod != null) {
					server.nailStarted(cmdclass);
					try {
						mainMethod.invoke(null, methodArgs);
					} catch (Throwable t) {
						throw(t);
					} finally {
						server.nailFinished(cmdclass);
					}
					exit.println(0);
				}

			} catch (Throwable t) {
//				t.printStackTrace(sysout);
				t.printStackTrace();
				exit.println("-996"); // remote exception constant
			}
			
			socket.close();
			sysout.println("Session ended.");

		} catch (Throwable t) {
			t.printStackTrace();
		}
	}
}
