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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.List;
import java.util.Properties;

import com.martiansoftware.nailgun.builtins.DefaultNail;

/**
 * Reads the NailGun stream from the client through the command,
 * then hands off processing to the appropriate class.  The NGSession
 * obtains its sockets from an NGSessionRunner, which created this
 * NGSession as part of a pool.
 * 
 * @author <a href="http://www.martiansoftware.com/contact.html">Marty Lamb</a>
 */
class NGSession implements Runnable {

	private NGServer server = null;
	private NGSessionRunner sessionRunner = null;
	private int index = 0;
	
	// signatures of main(String[]) and nailMain(NGContext) for reflection
	private static Class[] mainSignature;
	private static Class[] nailMainSignature;
	
	static {
		mainSignature = new Class[1];
		mainSignature[0] = String[].class;
		
		nailMainSignature = new Class[1];
		nailMainSignature[0] = NGContext.class;
	}
	
	/**
	 * Creates a new NGSession running for the specified NGSessionRunner and
	 * NGServer.
	 * @param sessionRunner The NGSessionRunner we're working for
	 * @param server The NGServer we're working for
	 * @param index this Runnable's index in the thread pool
	 */
	NGSession(NGSessionRunner sessionRunner, NGServer server, int index) {
		super();
		this.sessionRunner = sessionRunner;
		this.server = server;
		this.index = index;
	}

	/**
	 * Runs the nail.
	 */
	public void run() {
	
		updateThreadName(null);
		Socket socket = sessionRunner.getSocket();
		while (socket != null) {
			try {
				// buffer for reading headers
				byte[] lbuf = new byte[5];
				java.io.DataInputStream sockin = new java.io.DataInputStream(socket.getInputStream());
				java.io.OutputStream sockout = socket.getOutputStream();
	
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
	
				updateThreadName(socket.getInetAddress().getHostAddress() + ": " + command);
				
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
					Alias alias = server.getAliasManager().getAlias(command);
					Class cmdclass = null;
					if (alias != null) {
						cmdclass = alias.getAliasedClass();
					} else if (server.allowsNailsByClassName()) {
						cmdclass = Class.forName(command);
					} else {
						cmdclass = server.getDefaultNailClass();
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
						} catch (InvocationTargetException ite) {
							throw(ite.getCause());
						} catch (Throwable t) {
							throw(t);
						} finally {
							server.nailFinished(cmdclass);
						}
						exit.println(0);
					}
	
				} catch (Throwable t) {
					t.printStackTrace();
					exit.println(NGConstants.EXIT_EXCEPTION); // remote exception constant
				}
				
				socket.close();
	
			} catch (Throwable t) {
				t.printStackTrace();
			}

			((ThreadLocalInputStream) System.in).init(null);
			((ThreadLocalPrintStream) System.out).init(null);
			((ThreadLocalPrintStream) System.err).init(null);
			
			updateThreadName(null);
			socket = sessionRunner.getSocket();
		}
	}
	
	/**
	 * Updates the current thread name (useful for debugging).
	 */
	private void updateThreadName(String detail) {
		Thread.currentThread().setName("NGSession " + index + ": " + ((detail == null) ? "(idle)" : detail));
	}
}
