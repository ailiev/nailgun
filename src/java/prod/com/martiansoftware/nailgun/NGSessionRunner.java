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

import java.net.Socket;
import java.util.List;

/**
 * Provides Thread pooling functionality.
 * 
 * @author <a href="http://www.martiansoftware.com/contact.html">Marty Lamb</a>
 */
class NGSessionRunner {

	// number of threads to start
	int poolSize = 0;
	// referenct to server we're working for
	NGServer server = null;
	// have we been shut down?
	boolean done = false;
	// there may well be a better data structure for this
	List queue = new java.util.ArrayList();
	
	/**
	 * Creates a new NGSessionRunner operating for the specified server, with
	 * the specified number of threads
	 * @param server the server to work for
	 * @param poolSize the number of threads to launch
	 */
	NGSessionRunner(NGServer server, int poolSize) {
		this.server = server;
		this.poolSize = poolSize;
		
		for (int i = 0; i < poolSize; ++i) {
			NGSession session = new NGSession(this, server, i + 1);
			Thread sessionThread = new Thread(session);
			sessionThread.start();
		}
	}

	/**
	 * Shuts down the pool.  If there are any jobs waiting, they're currently
	 * allowed to run.
	 */
	void shutdown() {
		done = true;
		synchronized(queue) {
			queue.notifyAll();
		}
	}

	/**
	 * Submits a socket for processing by the workers
	 * @param socket the connected socket to process
	 */
	void startSessionFor(Socket socket) {
		synchronized(queue) {
			queue.add(socket);
			queue.notify();
		}
	}
	
	/**
	 * Returns the next socket to process, or null if the pool has
	 * been shut down.  This will block until something is available.
	 * @return the next socket to process, or null if the pool has
	 * been shut down.
	 */
	Socket getSocket() {
		Socket result = null;
		
		while (!done) {
			synchronized(queue) {
				if (queue.size() == 0) {
					try {
						queue.wait();
					} catch (InterruptedException e) {
						return (null);
					}
				} else {
					result = (Socket) queue.remove(0);
					break;
				}
			}
		}
		return (result);
	}
	
}
