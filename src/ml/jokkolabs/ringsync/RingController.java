package ml.jokkolabs.ringsync;

import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.CallLog;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.util.Log;
import android.view.*;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

import java.net.*;
import java.util.Date;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;


public class RingController extends Activity {

	private static final String lt = "RingCont";

	private static final int RESULT_SETTINGS = 1;

	private Thread thread;
	private Boolean run_thread = true;
 	private final Handler uiHandler = new Handler();

 	final Runnable updateRunnable = new Runnable() {
        public void run() {
            updateUI();
        }
    };

	// UI elements
	private TextView connectionStatusText;
	private TextView numberOfCallsTransferedText;
	private Button refreshButton;
	private Button stopButton;

	private static final int CONNECTION_OK = 1;
	private static final int CONNECTION_FAILED = 2;
	private static final int CONNECTION_UNKNOWN = 0;

	private int connection_status;
	private int number_calls_forwarded = CONNECTION_UNKNOWN;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setupUI();

		thread = new Thread() {
	        public void run() {
	            while (run_thread) {
	                try {
	                	uiHandler.post(updateRunnable);
	                    checkConnexionAndRun();
	                    uiHandler.post(updateRunnable);
	                    Log.i(lt, "local Thread sleeping");
	                    Thread.sleep(60000);
	                } catch (InterruptedException e) {
	                    Log.e(lt, "local Thread error", e);
	                }
	            }
	            Log.i(lt, "Thread ended.");
	        }
	    };
	    thread.start();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// required for settings to work
		getMenuInflater().inflate(R.menu.ringcontroller, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Android HW button for settings
		switch (item.getItemId()) {
			case R.id.menu_settings:
				Intent i = new Intent(this, Preferences.class);
				startActivityForResult(i, RESULT_SETTINGS);
				break;
		}
		return true;
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);

		switch (requestCode) {
			case RESULT_SETTINGS:
				// when settings are saved, check VLC status
				setupUI();
				break;
			}

	}

	private void setupUI() {
		Log.i(lt, "Setting Up UI");
		setContentView(R.layout.working_layout);
		connectionStatusText = (TextView)findViewById(R.id.connectionstatustext);
		numberOfCallsTransferedText = (TextView)findViewById(R.id.numberofcallstext);
		refreshButton = (Button)findViewById(R.id.refreshbutton);
		refreshButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				checkConnexionAndRun();
			}
	    });
	    stopButton = (Button)findViewById(R.id.stopbutton);
		stopButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				run_thread = false;
				thread.interrupt();
			}
	    });

	    updateUI();
	}

	private void updateUI() {

		// connection status
		String text;
		int color;

		if (connection_status == CONNECTION_OK) {
			text = "etablie.";
			color = android.graphics.Color.GREEN;
		} else if (connection_status == CONNECTION_FAILED) {
			text = "impossible.";
			color = android.graphics.Color.RED;
		} else {
			text = "Inconnu.";
			color = android.graphics.Color.BLACK;
		}

		connectionStatusText.setText(text);
		connectionStatusText.setTextColor(color);

		// number of calls
		numberOfCallsTransferedText.setText(String.valueOf(number_calls_forwarded));
	}

	private Boolean connectionWorks() {
		return oneWayRequest("");
	}

	private void checkConnexionAndRun()
	{
		if (connectionWorks()) {
			connection_status = CONNECTION_OK;
			getCallLog();
		} else {
			connection_status = CONNECTION_FAILED;
			Log.i(lt, "No Connection.");
		}
	}

	private void getCallLog() {

		Cursor managedCursor = managedQuery(CallLog.Calls.CONTENT_URI, null, null, null, null);

		int id = managedCursor.getColumnIndex(CallLog.Calls._ID);
		int number = managedCursor.getColumnIndex(CallLog.Calls.NUMBER);
        int type = managedCursor.getColumnIndex(CallLog.Calls.TYPE);
        int date = managedCursor.getColumnIndex(CallLog.Calls.DATE);
        // int duration = managedCursor.getColumnIndex(CallLog.Calls.DURATION);

        while (managedCursor.moveToNext()) {
			String calllog_id = managedCursor.getString(id);
			String phNumber = managedCursor.getString(number);
            String callType = managedCursor.getString(type);
            String callDate = managedCursor.getString(date);
            Date callDayTime = new Date(Long.valueOf(callDate));
            // String callDuration = managedCursor.getString(duration);
            int typeCode = Integer.parseInt(callType);
            if (typeCode == CallLog.Calls.MISSED_TYPE) {

            	Boolean submited = oneWayRequest('/' + phNumber + '/' + callDate.toString());
            	if (submited) {
            		Log.i(lt, "Accepted event. -- number: " + phNumber + " - timestamp: " + callDayTime.toString());

            		// update general counter
            		number_calls_forwarded = number_calls_forwarded + 1;
            		uiHandler.post(updateRunnable);

            		// delete call from call log.
	        		getContentResolver().delete(
	                        CallLog.Calls.CONTENT_URI,
    	                    CallLog.Calls._ID + "= ? ",
        	                new String[] { String.valueOf(calllog_id) });
	        		Log.i(lt, "Deleted event #" + String.valueOf(calllog_id));
            	} else {
            		Log.i(lt, "Server refused event. Keep it. -- number: " + phNumber + " - timestamp: " + callDayTime.toString());
            	}
            }
            uiHandler.post(updateRunnable);
        }
        uiHandler.post(updateRunnable);
	}

	/*
	 * Makes an HTTP GET request to the specified path
	 * on the VLC URL.
	 * Returns true if request successful (HTTP 200, 201, 202)
	 */
	private Boolean oneWayRequest(String url_query) {
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
		String gateway_url = sharedPrefs.getString("gatewayUrl", "NULL");
		if (gateway_url == "NULL")
			return false;

		URI url = null;
		int code = 0;
		try {
			url = new URI(gateway_url + url_query);
			HttpGet httpGet = new HttpGet(url);
			HttpClient httpclient = new DefaultHttpClient();
			HttpResponse response = httpclient.execute(httpGet);
			code = response.getStatusLine().getStatusCode ();
		} catch (Exception e) { // URISyntaxException | ClientProtocolException | IOException
			e.printStackTrace();
			return false;
		}

		return ( code == HttpStatus.SC_OK || code == HttpStatus.SC_ACCEPTED || code == HttpStatus.SC_CREATED);
	}

}
