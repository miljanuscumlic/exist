/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001,  Wolfgang M. Meier (meier@ifs.tu-darmstadt.de)
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Library General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Library General Public License for more details.
 *
 *  You should have received a copy of the GNU Library General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 *
 *  $Id:
 */
package org.exist;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

import org.exist.storage.BrokerPool;
import org.exist.util.Configuration;
import org.exist.xmldb.ShutdownListener;
import org.mortbay.jetty.Server;

/**
 * This class provides a main method to start Jetty with eXist. It registers shutdown
 * handlers to cleanly shut down the database and the webserver.
 * 
 * @author wolf
 */
public class JettyStart {

	public static void main(String[] args) {
		if (args.length == 0) {
			System.out.println("No configuration file specified!");
			return;
		}
		
		// configure database
		String home = System.getProperty("exist.home");
		if (home == null)
			home = System.getProperty("user.dir");
		System.out.println("Configuring eXist from " + home + File.separatorChar + "conf.xml");
		try {
			Configuration config = new Configuration("conf.xml", home);
			BrokerPool.configure(1, 5, config);
		} catch (Exception e) {
			System.err.println("configuration error: " + e.getMessage());
			e.printStackTrace();
			return;
		}

		// start Jetty
		final Server server;
		try {
			server = new Server(args[0]);
			BrokerPool.getInstance().registerShutdownListener(new ShutdownListenerImpl(server));
			server.start();

			// register a shutdown hook for the server
			Thread hook = new Thread() {
				public void run() {
					setName("Shutdown");
					BrokerPool.stopAll(true);
					try {
						server.stop();
					} catch (InterruptedException e) {
					}
					try {
						Thread.sleep(1000);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			};
			Runtime.getRuntime().addShutdownHook(hook);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * This class gets called after the database received a shutdown request.
	 *  
	 * @author wolf
	 */
	private static class ShutdownListenerImpl implements ShutdownListener {
		private Server server;

		public ShutdownListenerImpl(Server server) {
			this.server = server;
		}

		public void shutdown(String dbname, int remainingInstances) {
			System.err.println("Database shutdown: stopping server in 1sec ...");
			if (remainingInstances == 0) {
				// give the webserver a 1s chance to complete open requests
				Timer timer = new Timer();
				timer.schedule(new TimerTask() {
					public void run() {
						try {
							// stop the server
							server.stop();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
						System.exit(0);
					}
				}, 1000);
			}
		}
	}
}