package de.geeksfactory.opacclient.frontend;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import org.acra.ACRA;
import org.holoeverywhere.app.AlertDialog;
import org.holoeverywhere.widget.ExpandableListView;
import org.holoeverywhere.widget.ExpandableListView.OnChildClickListener;
import org.json.JSONException;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.actionbarsherlock.app.SherlockActivity;

import de.geeksfactory.opacclient.OpacClient;
import de.geeksfactory.opacclient.OpacTask;
import de.geeksfactory.opacclient.R;
import de.geeksfactory.opacclient.objects.Account;
import de.geeksfactory.opacclient.objects.Library;
import de.geeksfactory.opacclient.storage.AccountDataSource;

public class WelcomeActivity extends SherlockActivity {
	protected OpacClient app;
	protected AlertDialog dialog;
	private List<Library> libraries;

	public static int getLayoutResource() {
		return R.layout.welcome_activity;
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		app = (OpacClient) getApplication();
		setContentView(getLayoutResource());

		Button btAddAccount = (Button) findViewById(R.id.btAddAccount);
		btAddAccount.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View arg0) {
				add();
			}
		});
	}

	@Override
	public void onBackPressed() {
		System.exit(0);
		super.onBackPressed();
	}

	public void add() {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		// Get the layout inflater
		LayoutInflater inflater = getLayoutInflater();

		View view = inflater.inflate(R.layout.library_select_dialog, null);

		final ExpandableListView lv = (ExpandableListView) view
				.findViewById(R.id.lvBibs);
		try {
			libraries = ((OpacClient) getApplication()).getLibraries();
		} catch (IOException e) {
			ACRA.getErrorReporter().handleException(e);
		} catch (JSONException e) {
			ACRA.getErrorReporter().handleException(e);
		}
		final LibraryListAdapter la = new LibraryListAdapter(this);
		Collections.sort(libraries);
		for (Library lib : libraries) {
			la.addItem(lib);
		}
		lv.setAdapter(la);
		lv.setOnChildClickListener(new OnChildClickListener() {

			@Override
			public boolean onChildClick(ExpandableListView arg0, View arg1,
					int groupPosition, int childPosition, long arg4) {
				AccountDataSource data = new AccountDataSource(
						WelcomeActivity.this);
				data.open();
				Account acc = new Account();
				acc.setLibrary(la.getChild(groupPosition, childPosition)
						.getIdent());
				acc.setLabel(getString(R.string.default_account_name));
				long insertedid = data.addAccount(acc);
				data.close();
				dialog.dismiss();

				((OpacClient) getApplication()).setAccount(insertedid);

				Intent i = new Intent(WelcomeActivity.this,
						AccountEditActivity.class);
				i.putExtra("id", insertedid);
				i.putExtra("adding", true);
				i.putExtra("welcome", true);
				startActivity(i);
				return false;
			}
		});

		final TextView tvLocateString = (TextView) view
				.findViewById(R.id.tvLocateString);

		final ImageView ivLocationIcon = (ImageView) view
				.findViewById(R.id.ivLocationIcon);
		
		final LinearLayout llLocate = (LinearLayout) view
				.findViewById(R.id.llLocate);

		final LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
		Criteria criteria = new Criteria();
		criteria.setAccuracy(Criteria.ACCURACY_COARSE); // no GPS
		final String provider = locationManager.getBestProvider(criteria,
				true);
		if (provider == null) // no geolocation available
			llLocate.setVisibility(View.GONE);
		
		llLocate.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				tvLocateString.setText(R.string.geolocate_progress);
				ivLocationIcon.setImageResource(R.drawable.ic_locate);
				
				if (provider == null)
					return;
				locationManager.requestLocationUpdates(provider, 0, 0,
						new LocationListener() {
							@Override
							public void onStatusChanged(String provider,
									int status, Bundle extras) {
							}

							@Override
							public void onProviderEnabled(String provider) {
							}

							@Override
							public void onProviderDisabled(String provider) {
							}

							@Override
							public void onLocationChanged(Location location) {
								if (location != null) {
									double lat = location.getLatitude();
									double lon = location.getLongitude();
									float shortest = -1;
									Library closest = null;
									// Find closest library
									for (Library lib : libraries) {
										float[] result = new float[1];
										double[] geo = lib.getGeo();
										if (geo == null)
											continue;
										Location.distanceBetween(lat, lon,
												geo[0], geo[1], result);
										if (shortest == -1
												|| result[0] < shortest) {
											shortest = result[0];
											closest = lib;
										}
									}
									if (closest != null) {
										tvLocateString.setText(getString(
												R.string.geolocate_found,
												closest.getCity()));
										ivLocationIcon
												.setImageResource(R.drawable.ic_located);
										int[] position = la
												.findPosition(closest);
										if (position != null) {
											lv.expandGroup(position[0], false);
											lv.setSelectedChild(position[0],
													position[1], true);
										}
									}
								}
							}
						});
			}
		});

		builder.setView(view).setNegativeButton(R.string.cancel,
				new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int id) {
						dialog.cancel();
					}
				});
		dialog = builder.create();
		dialog.show();
	}

	public class InitTask extends OpacTask<Integer> {
		@Override
		protected Integer doInBackground(Object... arg0) {
			super.doInBackground(arg0);
			try {
				app.getApi().start();
			} catch (java.net.UnknownHostException e) {
				e.printStackTrace();
			} catch (java.net.SocketException e) {
				e.printStackTrace();
			} catch (Exception e) {
				ACRA.getErrorReporter().handleException(e);
			}
			return 0;
		}

		@Override
		protected void onPostExecute(Integer result) {
			dialog.dismiss();
			Intent intent = new Intent(WelcomeActivity.this,
					SearchActivity.class);
			startActivity(intent);
		}
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (dialog != null) {
			if (dialog.isShowing()) {
				dialog.cancel();
			}
		}
	}

}
