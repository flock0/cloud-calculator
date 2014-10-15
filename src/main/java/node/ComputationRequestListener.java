package node;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.print.CancelablePrintJob;

import util.Channel;
import util.Config;
import util.TcpChannel;

public class ComputationRequestListener extends Thread {

	private Config config;
	private ServerSocket serverSocket = null;
	private ExecutorService threadPool;

	public ComputationRequestListener(Config config) {
		this.config = config;
		openServerSocket();
		createThreadPool();
	}

	private void openServerSocket() {
		try {
			serverSocket = new ServerSocket(config.getInt("tcp.port"));
		} catch (IOException e) {
			System.out.println("Couldn't create ServerSocket: " + e.getMessage());
		}
	}

	private void createThreadPool() {
		threadPool = Executors.newCachedThreadPool();
	}

	@Override
	public void run() {
		if (serverSocket != null) {
			try {
				while (true) {
					Socket socket = null;
					Channel nextRequest;

					nextRequest = new TcpChannel(serverSocket.accept());
					threadPool.execute(new SingleRequestHandler(nextRequest, config));

				}
			} catch (SocketException e) {
				System.out.println("Socket shutdown: " + e.getMessage());
			} catch (IOException e) {
				System.out.println("IOException occured: " + e.getMessage());
			}
		}

	}

	public void shutdown() {
		try {
			serverSocket.close();
			shutdownPoolAndAwaitTermination();
			
		} catch (IOException e) {
			// Nothing we can do about that
		}
	}
	
	/**
	 * Shuts down the ExecutorService in two phases,
	 * first by calling shutdown to reject incoming tasks,
	 * and then calling shutdownNow, if necessary, to cancel any lingering tasks.
	 * 
	 * Taken from
	 * http://docs.oracle.com/javase/7/docs/api/java/util/concurrent/ExecutorService.html
	 */
	void shutdownPoolAndAwaitTermination() {
		   threadPool.shutdown(); // Disable new tasks from being submitted
		   try {
		     // Wait a while for existing tasks to terminate
		     if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
		    	 threadPool.shutdownNow(); // Cancel currently executing tasks
		       // Wait a while for tasks to respond to being cancelled
		       if (!threadPool.awaitTermination(5, TimeUnit.SECONDS))
		           System.err.println("Pool did not terminate");
		     }
		   } catch (InterruptedException ie) {
		     // (Re-)Cancel if current thread also interrupted
			   threadPool.shutdownNow();
		     // Preserve interrupt status
		     Thread.currentThread().interrupt();
		   }
		 }

}