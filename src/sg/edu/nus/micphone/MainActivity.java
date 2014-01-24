package sg.edu.nus.micphone;

import java.net.InetAddress;

import org.androidannotations.annotations.AfterViews;
import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.OptionsMenu;
import org.androidannotations.annotations.ViewById;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.nsd.NsdManager.ResolveListener;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;

@EActivity(R.layout.activity_main)
@OptionsMenu(R.menu.main)
public class MainActivity extends Activity {
	
	private static final String TAG = "MainActivity";
	
	private ConnectivityManager mConnectivityManager;
	private IntentFilter mConnectivityChangeIntentFilter;
	private BroadcastReceiver mConnectivityChangeReceiver;
	private DialogFragment mConnectWiFiDialogFragment;
	
	@ViewById(R.id.select_server_button)
	protected Button mSelectServerButton; 
	private DialogFragment mDiscoverDialogFragment;
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		// Obtain required system services.
		mConnectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		
		// Register a broadcast receiver for network state changes.
		mConnectivityChangeIntentFilter = new IntentFilter();
		mConnectivityChangeIntentFilter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
		mConnectivityChangeReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				checkConnectivity();
			}
		};
	}
	
	@Override
	public void onResume() {
		super.onResume();
		
		// Start network discovery.
		checkConnectivity();
		
		// Resume receiving network state changes.
		registerReceiver(mConnectivityChangeReceiver, mConnectivityChangeIntentFilter);
	}
	
	@Override
	public void onPause() {
		super.onPause();
		
		// Hide all dialogs.
		if (mConnectWiFiDialogFragment != null) {
			mConnectWiFiDialogFragment.dismiss();
		}
		
		// Stop receiving network state changes.
		unregisterReceiver(mConnectivityChangeReceiver);
	}
	
	@AfterViews
	public void registerButtonEvents() {
		// Select server button.
		mSelectServerButton.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				if (mDiscoverDialogFragment == null) {
					mDiscoverDialogFragment = new DiscoverDialogFragment();
				}

				if (!mDiscoverDialogFragment.isAdded()) {
					mDiscoverDialogFragment.show(getFragmentManager(), "DiscoverDialogFragment");	
				}
			}
		});
	}
	
	public boolean checkConnectivity() {
		// Check if we have WiFi connectivity.
		NetworkInfo networkInfo = mConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
		if (!networkInfo.isConnected()) {
			if (mConnectWiFiDialogFragment == null) {
				mConnectWiFiDialogFragment = new ConnectWiFiDialogFragment();
			}
			
			if (!mConnectWiFiDialogFragment.isAdded()) {
				mConnectWiFiDialogFragment.show(getFragmentManager(), "ConnectWiFiDialogFragment");
			}
			
			return false;
		} else {
			return true;
		}
	}
	
	public static class ConnectWiFiDialogFragment extends DialogFragment {
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			// Use the builder class for convenient dialog construction.
			AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
			builder.setMessage(R.string.must_connect_wifi)
				.setTitle(R.string.wifi_disconnected)
				.setPositiveButton(R.string.connect, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
						startActivity(intent);
					}
				})
				.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						getActivity().finish();
					}
				});
			
			return builder.create();
		}
		
		@Override
		public void onCancel(DialogInterface dialog) {
			getActivity().finish();
		}
	}
	
	public static class DiscoverDialogFragment extends DialogFragment {
		private static final String TAG = "DiscoverDialogFragment";
		private static final String SERVICE_TYPE = "_rtp._udp.";
		
		private NsdManager mNsdManager;
		private NsdManager.DiscoveryListener mDiscoveryListener;
		private NsdServiceInfo mService;
		private ResolveListener mResolveListener;
		private boolean mPerformingDiscovery = false;
		
		@Override
		public void onCreate(Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);
			
			// Obtain the Network Service Discovery Manager.
			mNsdManager = (NsdManager) getActivity().getSystemService(Context.NSD_SERVICE);
			
			// Create the discovery listener.
			initializeDiscoveryListener();
			initializeResolveListener();
			startDiscovery();
		}
		
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			// Use the builder class for convenient dialog construction.
			AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
			builder.setTitle(R.string.discovering);
			return builder.create();
		}
		
		@Override
		public void onDismiss(DialogInterface dialog) {
			super.onDismiss(dialog);
			stopDiscovery();
		}
		
		public void startDiscovery() {
			// Begin to discover network services.
			mNsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);
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
				}
				
				@Override
				public void onServiceFound(NsdServiceInfo service) {
					// A service was found!  Do something with it.
					Log.d(TAG, "Service discovery success: " + service);
					if (!service.getServiceType().equals(SERVICE_TYPE)) {
						// Service type is the string containing the protocol and
		                // transport layer for this service.
						Log.d(TAG, "Unknown service type: " + service.getServiceType());
					} else if (service.getServiceName().equals("KboxServer")) {
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
					
					mService = serviceInfo;
					int port = mService.getPort();
					InetAddress host = mService.getHost();
					
					Log.d(TAG, "Port: " + port);
					Log.d(TAG, "InetAddress: " + host);
				}
				
				@Override
				public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
					// Called when the resolve fails. Use the error code to debug.
					Log.e(TAG, "Resolve failed: Error code: " + errorCode);
				}
			};
		}
	}
	
}