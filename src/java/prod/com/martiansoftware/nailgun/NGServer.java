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
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;
import java.util.Map;

/**
 * <p>Listens for new connections from NailGun clients and launches
 * NGSession threads to process them.</p>
 * 
 * <p>This class can be run as a standalone server or can be embedded
 * within larger applications as a means of providing command-line
 * interaction with the application.</p>
 * 
 * @author <a href="http://www.martiansoftware.com/contact.html">Marty Lamb</a>
 */
public class NGServer implements Runnable {

	/**
	 * The default NailGun port (2113)
	 */
	public static final int DEFAULT_PORT = 2113;
	
	private int port = 0;
	private ServerSocket serversocket;
	private boolean shutdown = false;
	private boolean running = false;
	private AliasManager aliasManager;
	
	/**
	 * <code>System.out</code> at the time of the NGServer's creation
	 */
	public final PrintStream out = System.out;

	/**
	 * <code>System.err</code> at the time of the NGServer's creation
	 */
	public final PrintStream err = System.err;
	
	/**
	 * <code>System.in</code> at the time of the NGServer's creation
	 */
	public final InputStream in = System.in;
	
	// a collection of all classes executed by this server so far
	private Map allNailStats = null;
	
	/**
	 * Creates a new NGServer that will listen on the specified port.
	 * This does <b>not</b> cause the server to start listening.  To do
	 * so, create a new <code>Thread</code> wrapping this <code>NGServer</code>
	 * and start it.
	 * @param port the port on which to listen.
	 */
	public NGServer(int port) {
		this.port = port;
		init();
	}
	
	/**
	 * Creates a new NGServer that will listen on the default port
	 * (defined in <code>NGServer.DEFAULT_PORT</code>).
	 * This does <b>not</b> cause the server to start listening.  To do
	 * so, create a new <code>Thread</code> wrapping this <code>NGServer</code>
	 * and start it.
	 */
	public NGServer() {
		this.port = DEFAULT_PORT;
		init();
	}
	
	/**
	 * Sets up the NGServer internals
	 *
	 */
	private void init() {
		this.aliasManager = new AliasManager();
		allNailStats = new java.util.HashMap();
	}

	private NailStats getOrCreateStatsFor(Class nailClass) {
		NailStats result = null;
		synchronized(allNailStats) {
			result = (NailStats) allNailStats.get(nailClass);
			if (result == null) {
				result = new NailStats(nailClass);
				allNailStats.put(nailClass, result);
			}
		}
		return (result);
	}
	
	/**
	 * Provides a means for an NGSession to register nail classes with
	 * the server.  These classes may provide either a main or nailMain method.
	 * When the NGServer shuts down, any classes with a static nailShutdown()
	 * method will have that method called.
	 * 
	 * @param clazz
	 */
	void nailStarted(Class nailClass) {
		NailStats stats = getOrCreateStatsFor(nailClass);
		stats.nailStarted();
//		out.println(stats);
	}
	
	void nailFinished(Class nailClass) {
		NailStats stats = (NailStats) allNailStats.get(nailClass);
		stats.nailFinished();
//		out.println(stats);
	}
	
	/**
	 * Returns a snapshot of this NGServer's nail statistics.  The result is a <code>java.util.Map</code>,
	 * keyed by class name, with <a href="NailStats.html">NailStats</a> objects as values.
	 * @return a snapshot of this NGServer's nail statistics.
	 */
	public Map getNailStats() {
		Map result = new java.util.TreeMap();
		synchronized(allNailStats) {
			for (Iterator i = allNailStats.keySet().iterator(); i.hasNext();) {
				Class nailclass = (Class) i.next();
				result.put(nailclass.getName(), ((NailStats) allNailStats.get(nailclass)).clone());
			}
		}
		return (result);
	}
	
	/**
	 * Returns the AliasManager in use by this NGServer.
	 * @return the AliasManager in use by this NGServer.
	 */
	public AliasManager getAliasManager() {
		return (aliasManager);
	}

	/**
	 * <p>Shuts down the server.  The server will stop listening
	 * and its thread will finish.</p>
	 * 
	 * <p>Any nails that provide a
	 * <pre><code>public static void nailShutdown(NGServer)</code></pre>
	 * method will have this method called with this NGServer as its sole
	 * parameter.</p>
	 * 
	 * @param exitVM if true, this method will also exit the JVM after
	 * calling nailShutdown() on any nails.  This may prevent currently
	 * running nails from exiting gracefully, but may be necessary in order
	 * to perform some tasks, such as shutting down any AWT or Swing threads
	 * implicitly launched by your nails.
	 */
	public void shutdown(boolean exitVM) {
		shutdown = true;
		try {
			serversocket.close();
		} catch (Throwable toDiscard) {}
		
		Class[] argTypes = new Class[1];
		argTypes[0] = NGServer.class;
		Object[] argValues = new Object[1];
		argValues[0] = this;
		
		// make sure that all aliased classes have associated nailstats
		// so they can be shut down.
		for (Iterator i = getAliasManager().getAliases().iterator(); i.hasNext();) {
			Alias alias = (Alias) i.next();
			getOrCreateStatsFor(alias.getAliasedClass());
		}
		
		synchronized(allNailStats) {
			for (Iterator i = allNailStats.values().iterator(); i.hasNext();) {
				NailStats ns = (NailStats) i.next();
				Class nailClass = ns.getNailClass();
				try {
					Method nailShutdown = nailClass.getMethod("nailShutdown", argTypes);
					nailShutdown.invoke(null, argValues);
				} catch (Throwable toDiscard) {}
			}
		}
		
		// restore system streams
		System.setIn(in);
		System.setOut(out);
		System.setErr(err);
		if (exitVM) {
			System.exit(0);
		}
	}
	
	/**
	 * Returns true iff the server is currently running.
	 * @return true iff the server is currently running.
	 */
	public boolean isRunning() {
		return (running);
	}
	
	/**
	 * Returns the port on which this server is (or will be) listening.
	 * @return the port on which this server is (or will be) listening.
	 */
	public int getPort() {
		return (port);
	}
	
	/**
	 * Listens for new connections and launches NGSession threads
	 * to process them.
	 */
	public void run() {
		running = true;
		
		synchronized(System.in) {
			if (!(System.in instanceof ThreadLocalInputStream)) {
				System.setIn(new ThreadLocalInputStream(System.in));
				System.setOut(new ThreadLocalPrintStream(System.out));
				System.setErr(new ThreadLocalPrintStream(System.err));				
			}
		}
		
		try {
			serversocket = new ServerSocket(port);
			
			while (!shutdown) {
				// get the new thread ready to go so the client
				// doesn't have to wait longer than necessary
				NGSession nextSession = new NGSession();
				Thread nextSessionThread = new Thread(nextSession);
				nextSessionThread.setName("NGSession on deck");
				Socket s = serversocket.accept();
				nextSession.init(this, s);
				nextSessionThread.start();
				Thread.yield();
			}
		} catch (Throwable t) {
			// if shutdown is called while the accept() method is blocking,
			// an exception will be thrown that we don't care about.  filter
			// those out.
			if (!shutdown) {
				t.printStackTrace();
			}
		}
		running = false;
	}
	
	private static void usage() {
		
	}
	
	/**
	 * Creates and starts a new <code>NGServer</code>.  A single optional
	 * argument is valid, specifying the port on which this <code>NGServer</code>
	 * should listen.  If omitted, <code>NGServer.DEFAULT_PORT</code> will be used.
	 * @param args a single optional argument specifying the port on which to listen.
	 * @throws NumberFormatException if a non-numeric port is specified
	 */
	public static void main(String[] args) throws NumberFormatException {
		if (args.length > 1) {
			usage();
			return;
		}
		
		int port = DEFAULT_PORT;
		if (args.length != 0) {
			port = Integer.parseInt(args[0]);
		}

		NGServer server = new NGServer(port);
		new Thread(server).start();
		System.out.println("NGServer started on port " + port + ".");
	}

}