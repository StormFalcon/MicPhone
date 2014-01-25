package sg.edu.nus.micphone;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import android.net.rtp.AudioGroup;
import android.net.rtp.AudioStream;
import android.net.rtp.RtpStream;
import android.os.Handler;
import android.util.Log;

/**Construct : ServerConnection(Handler, AudioGroup<singleton>) -> */
public class ServerConnection {

	private static final String TAG = "ServerConnection";

	private AudioGroup m_outAudio;
	private Handler m_UpdateHandler;
	private Socket m_Socket;
	private ReceivingServer m_receiveServer;
	private int m_Port = -1;

	public ServerConnection(Handler handler, AudioGroup outAudio) {
		this.m_UpdateHandler = handler;
		this.m_outAudio = outAudio;
		this.m_receiveServer = new ReceivingServer(m_UpdateHandler);
	}

	public void tearDown() {
		m_receiveServer.tearDown();
	}

	public int getLocalPort() {
		return m_Port;
	}

	public void setLocalPort(int port) {
		m_Port = port;
	}

	private Socket getSocket() {
		return m_Socket;
	}

	private class ReceivingServer {
		ServerSocket m_ServerSocket = null;
		Thread m_Thread = null;

		public ReceivingServer(Handler handler) {
			m_Thread = new Thread(new ServerThread());
			m_Thread.start();
		}

		public void tearDown() {
			m_Thread.interrupt();
			try {
				m_ServerSocket.close();
			} catch (IOException ioe) {
				Log.e(TAG, "Error when closing server socket.");
			}
		}

		class ServerThread implements Runnable {

			@Override
			public void run() {

				try {				
					m_ServerSocket = new ServerSocket(0);
					setLocalPort(m_ServerSocket.getLocalPort());

					while (!Thread.currentThread().isInterrupted()) {
						Log.d(TAG, "ServerSocket Created, awaiting connection");
						m_Socket = m_ServerSocket.accept();
						Log.d(TAG, "Connected.");

						Log.d(TAG, "Joining AudioGroup");
						AudioStream incoming = new AudioStream(
								m_Socket.getInetAddress());
						incoming.setMode(RtpStream.MODE_RECEIVE_ONLY);
						incoming.join(m_outAudio);
						Log.d(TAG, "Joined AudioGroup");
					}
				} catch (IOException e) {
					Log.e(TAG, "Error creating ServerSocket: ", e);
					e.printStackTrace();
				}
			}
		}
	}
}