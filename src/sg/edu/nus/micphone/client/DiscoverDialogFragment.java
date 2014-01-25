package sg.edu.nus.micphone.client;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.androidannotations.annotations.Background;
import org.androidannotations.annotations.EFragment;

import sg.edu.nus.micphone.R;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.media.AudioManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdManager.ResolveListener;
import android.net.nsd.NsdServiceInfo;
import android.net.rtp.AudioCodec;
import android.net.rtp.AudioGroup;
import android.net.rtp.AudioStream;
import android.net.rtp.RtpStream;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

@EFragment
public class DiscoverDialogFragment extends DialogFragment {
	private static final String TAG = "DiscoverDialogFragment";
	private static final String SERVICE_TYPE = "_rtp._udp.";
	private static String SERVICE_NAME = "KboxService";

	/** Service discovery */
	private NsdManager mNsdManager;
	private NsdManager.DiscoveryListener mDiscoveryListener;
	private ResolveListener mResolveListener;
	private boolean mPerformingDiscovery = false;

	private static List<NsdServiceInfo> mDiscoveredServicesList;
	private static NsdServiceInfoListAdapter mDiscoveredServicesListAdapter;

	/** Audio management */
	private static AudioStream mMicStream;
	private static AudioGroup mStreamGroup;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Create the list adapter.
		mDiscoveredServicesList = new ArrayList<NsdServiceInfo>();
		mDiscoveredServicesListAdapter = new NsdServiceInfoListAdapter(
				getActivity(), mDiscoveredServicesList);

		// Obtain the Network Service Discovery Manager.
		mNsdManager = (NsdManager) getActivity().getSystemService(
				Context.NSD_SERVICE);

		// Create the discovery listener.
		initializeDiscoveryListener();
		initializeResolveListener();
		startDiscovery();
	}

	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		// Use the builder class for convenient dialog construction.
		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		builder.setTitle(R.string.discovering).setAdapter(
				mDiscoveredServicesListAdapter,
				new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						NsdServiceInfo serviceInfo = mDiscoveredServicesList
								.get(which);
						Log.d(TAG, "Selected server: "
								+ serviceInfo.getHost().getHostAddress());

						// Obtain the endpoint port and host.
						int port = serviceInfo.getPort();
						// AS per RFC 3550
						if (port % 2 == 0) {
							port++;
						} else {
							port--;
						}
						InetAddress host = serviceInfo.getHost();
						beginAudioStream(host, port);
					}
				});

		return builder.create();
	}

	@Override
	public void onDismiss(DialogInterface dialog) {
		super.onDismiss(dialog);
		stopDiscovery();

		// Clear the list of discovered services.
		mDiscoveredServicesList.clear();
	}

	@Background
	protected void beginAudioStream(InetAddress host, int port) {
		Log.d(TAG, "beginAudioStream to " + host.getHostAddress() + " : "
				+ port);
		// Create the microphone stream.
		try {
			AudioManager mAudioManager = (AudioManager) getActivity()
					.getSystemService(Context.AUDIO_SERVICE);

			mMicStream = new AudioStream(InetAddress.getByName("0.0.0.0"));
			Log.d(TAG, "Created AudioStream at " + mMicStream.getLocalAddress());
			mMicStream.setCodec(AudioCodec.GSM_EFR);
			Log.d(TAG, "Set Codec to : " + mMicStream.getCodec());
			mMicStream.setMode(RtpStream.MODE_SEND_ONLY);
			Log.d(TAG, "Set Mode to : " + mMicStream.getMode());
			mMicStream.associate(host, port);
			Log.d(TAG, "Associated MicStream with " + host + ":" + port);

			// Connecting and sending stream.
			if (mAudioManager.isMicrophoneMute()) {
				mAudioManager.setMicrophoneMute(true);
				Log.d(TAG, "Microphone is now On!");
			}

			mStreamGroup = new AudioGroup();
			Log.v(TAG, "StreamGroup created : " + mStreamGroup);
			mStreamGroup.setMode(AudioGroup.MODE_NORMAL);
			Log.v(TAG, "StreamGroup mode set : " + mStreamGroup.getMode());
			
			//mAudioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
			
			mMicStream.join(mStreamGroup);
			Log.d(TAG, "Joined " + mStreamGroup);

		} catch (SocketException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (UnknownHostException uhe) {
			uhe.printStackTrace();
		}
	}

	public void startDiscovery() {
		// Begin to discover network services.
		mNsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD,
				mDiscoveryListener);
		mPerformingDiscovery = true;
	}

	public void stopDiscovery() {
		if (mPerformingDiscovery) {
			mNsdManager.stopServiceDiscovery(mDiscoveryListener);
			mPerformingDiscovery = false;
		}
	}

	public void initializeDiscoveryListener() {
		Log.d(TAG, "Initializing discovery listener");

		// Instantiate a new DiscoveryListener.
		mDiscoveryListener = new NsdManager.DiscoveryListener() {

			@Override
			public void onStopDiscoveryFailed(String serviceType, int errorCode) {
				Log.e(TAG, "Discovery failed: Error code: " + errorCode);
				mNsdManager.stopServiceDiscovery(this);
			}

			@Override
			public void onStartDiscoveryFailed(String serviceType, int errorCode) {
				Log.e(TAG, "Discovery failed: Error code: " + errorCode);
				mNsdManager.stopServiceDiscovery(this);
			}

			@Override
			public void onServiceLost(NsdServiceInfo service) {
				// When the network service is no longer available.
				// Internal bookkeeping code here.
				Log.e(TAG, "Service lost: " + service);
				for (NsdServiceInfo serviceInfo : mDiscoveredServicesList) {
					if (serviceInfo.getServiceName().equals(
							service.getServiceName())) {
						mDiscoveredServicesList.remove(serviceInfo);
					}
				}

				getActivity().runOnUiThread(new Runnable() {
					@Override
					public void run() {
						mDiscoveredServicesListAdapter.notifyDataSetChanged();
					}
				});
			}

			@Override
			public void onServiceFound(NsdServiceInfo service) {
				// A service was found! Do something with it.
				Log.d(TAG, "Service discovery success: " + service);
				if (!service.getServiceType().equals(SERVICE_TYPE)) {
					// Service type is the string containing the protocol and
					// transport layer for this service.
					Log.d(TAG,
							"Unknown service type: " + service.getServiceType());
				} else if (service.getServiceName().startsWith(SERVICE_NAME)) {
					mNsdManager.resolveService(service, mResolveListener);
				}
			}

			@Override
			public void onDiscoveryStopped(String serviceType) {
				Log.i(TAG, "Discovery stopped: " + serviceType);
			}

			// Called as soon as service discovery begins.
			@Override
			public void onDiscoveryStarted(String regType) {
				Log.d(TAG, "Service discovery started");
			}
		};
	}

	public void initializeResolveListener() {
		Log.d(TAG, "Initializing resolve listener");

		// Instantiate a new ResolveListener.
		mResolveListener = new NsdManager.ResolveListener() {

			@Override
			public void onServiceResolved(NsdServiceInfo serviceInfo) {
				Log.e(TAG, "Resolve succeeded: " + serviceInfo);
				mDiscoveredServicesList.add(serviceInfo);
				updateAdapter();
			}

			@Override
			public void onResolveFailed(NsdServiceInfo serviceInfo,
					int errorCode) {
				// Called when the resolve fails. Use the error code to debug.
				Log.e(TAG, "Resolve failed: Error code: " + errorCode);
			}

			/**
			 * Updates the discovered services list. Notifies the adapter that
			 * the data set has changed. This needs to be run on a separate (UI)
			 * thread.
			 */
			private void updateAdapter() {
				getActivity().runOnUiThread(new Runnable() {
					@Override
					public void run() {
						mDiscoveredServicesListAdapter.notifyDataSetChanged();
					}
				});
			}
		};
	}

	/**
	 * A list adapter for displaying discovered services in a ListView.
	 */
	public class NsdServiceInfoListAdapter extends ArrayAdapter<NsdServiceInfo> {
		private Context mContext;
		private List<NsdServiceInfo> mValues;

		public NsdServiceInfoListAdapter(Context context,
				List<NsdServiceInfo> values) {
			super(context, android.R.layout.simple_list_item_1);
			mContext = context;
			mValues = values;
		}

		@Override
		public int getCount() {
			return mValues.size();
		}

		@Override
		public NsdServiceInfo getItem(int position) {
			return mValues.get(position);
		}

		@Override
		public View getView(int positon, View convertView, ViewGroup parent) {
			LayoutInflater inflater = (LayoutInflater) mContext
					.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
			View rowView = inflater.inflate(
					android.R.layout.simple_list_item_1, parent, false);

			TextView textView = (TextView) rowView
					.findViewById(android.R.id.text1);
			NsdServiceInfo serviceInfo = mValues.get(positon);

			// Sample format: KboxService 2 (192.168.1.1:32132)
			// The \032 is actually a space (base 10 ASCII), but it doesn't
			// seem to be processed correctly by Android API.
			textView.setText(serviceInfo.getServiceName().replace("\\032", " ")
					+ " (" + mValues.get(positon).getHost().getHostAddress()
					+ ":" + mValues.get(positon).getPort() + ")");
			return rowView;
		}
	}
}
