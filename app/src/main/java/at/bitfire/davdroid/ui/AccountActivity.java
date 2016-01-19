/*
 * Copyright © 2013 – 2016 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.davdroid.ui;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.AlertDialog;
import android.app.LoaderManager;
import android.content.AsyncTaskLoader;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.CardView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import at.bitfire.davdroid.Constants;
import at.bitfire.davdroid.DavService;
import at.bitfire.davdroid.R;
import at.bitfire.davdroid.model.CollectionInfo;
import at.bitfire.davdroid.model.ServiceDB.Collections;
import at.bitfire.davdroid.model.ServiceDB.OpenHelper;
import at.bitfire.davdroid.model.ServiceDB.Services;
import lombok.Cleanup;

public class AccountActivity extends AppCompatActivity implements Toolbar.OnMenuItemClickListener, ServiceConnection, DavService.RefreshingStatusListener, LoaderManager.LoaderCallbacks<AccountActivity.AccountInfo> {

    public static final String EXTRA_ACCOUNT_NAME = "account_name";

    private String accountName;
    private AccountInfo accountInfo;
    private DavService.InfoBinder davService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        accountName = getIntent().getStringExtra(EXTRA_ACCOUNT_NAME);
        if (accountName == null)
            // invalid account name
            finish();
        setTitle(accountName);

        setContentView(R.layout.activity_account);

        Toolbar toolbar = (Toolbar)findViewById(R.id.carddav_menu);
        toolbar.inflateMenu(R.menu.carddav_actions);
        toolbar.setOnMenuItemClickListener(this);

        toolbar = (Toolbar)findViewById(R.id.caldav_menu);
        toolbar.inflateMenu(R.menu.caldav_actions);
        toolbar.setOnMenuItemClickListener(this);

        getLoaderManager().initLoader(0, getIntent().getExtras(), this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindService(new Intent(this, DavService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unbindService(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_account, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.delete_account:
                new AlertDialog.Builder(AccountActivity.this)
                        .setIcon(R.drawable.ic_error_dark)
                        .setTitle(R.string.account_delete_confirmation_title)
                        .setMessage(R.string.account_delete_confirmation_text)
                        .setNegativeButton(android.R.string.no, null)
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                deleteAccount();
                            }
                        })
                        .show();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.refresh_address_books:
                Intent intent = new Intent(this, DavService.class);
                intent.setAction(DavService.ACTION_REFRESH_COLLECTIONS);
                intent.putExtra(DavService.EXTRA_DAV_SERVICE_ID, accountInfo.carddav.id);
                startService(intent);
                break;
            case R.id.create_address_book:
                // TODO
                break;
            case R.id.refresh_calendars:
                intent = new Intent(this, DavService.class);
                intent.setAction(DavService.ACTION_REFRESH_COLLECTIONS);
                intent.putExtra(DavService.EXTRA_DAV_SERVICE_ID, accountInfo.caldav.id);
                startService(intent);
                break;
        }
        return false;
    }


    /* SERVICE CONNECTION */

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        davService = (DavService.InfoBinder)service;
        davService.addRefreshingStatusListener(this, true);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        davService.removeRefreshingStatusListener(this);
        davService = null;
    }

    @Override
    public void onDavRefreshStatusChanged(long id, boolean refreshing) {
        getLoaderManager().restartLoader(0, getIntent().getExtras(), this);
    }


    /* LOADERS AND LOADED DATA */

    public static class AccountInfo {
        ServiceInfo carddav, caldav;

        public static class ServiceInfo {
            long id;
            boolean refreshing;

            List<CollectionInfo> collections;
        }
    }

    @Override
    public Loader<AccountInfo> onCreateLoader(int id, Bundle args) {
        return new AccountLoader(this, args.getString(EXTRA_ACCOUNT_NAME));
    }

    @Override
    public void onLoadFinished(Loader<AccountInfo> loader, AccountInfo info) {
        accountInfo = info;

        CardView card = (CardView)findViewById(R.id.carddav);
        if (info.carddav != null) {
            ProgressBar progress = (ProgressBar)findViewById(R.id.carddav_refreshing);
            progress.setVisibility(info.carddav.refreshing ? View.VISIBLE : View.GONE);

            ListView list = (ListView)findViewById(R.id.address_books);
            AddressBookAdapter adapter = new AddressBookAdapter(this);
            adapter.addAll(info.carddav.collections);
            list.setAdapter(adapter);
        } else
            card.setVisibility(View.GONE);

        card = (CardView)findViewById(R.id.caldav);
        if (info.caldav != null) {
            ProgressBar progress = (ProgressBar)findViewById(R.id.caldav_refreshing);
            progress.setVisibility(info.caldav.refreshing ? View.VISIBLE : View.GONE);

            ListView list = (ListView)findViewById(R.id.calendars);
            CalendarAdapter adapter = new CalendarAdapter(this);
            adapter.addAll(info.caldav.collections);
            list.setAdapter(adapter);
        } else
            card.setVisibility(View.GONE);
    }

    @Override
    public void onLoaderReset(Loader<AccountInfo> loader) {
    }

    private static class AccountLoader extends AsyncTaskLoader<AccountInfo> implements DavService.RefreshingStatusListener, ServiceConnection {
        private final String accountName;
        private final OpenHelper dbHelper;
        private DavService.InfoBinder davService;

        public AccountLoader(Context context, String accountName) {
            super(context);
            this.accountName = accountName;
            dbHelper = new OpenHelper(context);
        }

        @Override
        protected void onStartLoading() {
            getContext().bindService(new Intent(getContext(), DavService.class), this, Context.BIND_AUTO_CREATE);
        }

        @Override
        protected void onStopLoading() {
            davService.removeRefreshingStatusListener(this);
            getContext().unbindService(this);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            davService = (DavService.InfoBinder)service;
            forceLoad();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            davService = null;
        }

        @Override
        public void onDavRefreshStatusChanged(long id, boolean refreshing) {
            forceLoad();
        }

        @Override
        public AccountInfo loadInBackground() {
            AccountInfo info = new AccountInfo();
            try {
                SQLiteDatabase db = dbHelper.getReadableDatabase();

                @Cleanup Cursor cursor = db.query(
                        Services._TABLE,
                        new String[] { Services.ID, Services.SERVICE },
                        Services.ACCOUNT_NAME + "=?", new String[] { accountName },
                        null, null, null);
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    String service = cursor.getString(1);
                    if (Services.SERVICE_CARDDAV.equals(service)) {
                        info.carddav = new AccountInfo.ServiceInfo();
                        info.carddav.id = id;
                        info.carddav.refreshing = davService.isRefreshing(id);
                        info.carddav.collections = readCollections(db, id);

                    } else if (Services.SERVICE_CALDAV.equals(service)) {
                        info.caldav = new AccountInfo.ServiceInfo();
                        info.caldav.id = id;
                        info.caldav.refreshing = davService.isRefreshing(id);
                        info.caldav.collections = readCollections(db, id);
                    }
                }
            } finally {
                dbHelper.close();
            }
            return info;
        }

        private List<CollectionInfo> readCollections(SQLiteDatabase db, long service) {
            List<CollectionInfo> collections = new LinkedList<>();
            @Cleanup Cursor cursor = db.query(Collections._TABLE, Collections._COLUMNS, Collections.SERVICE_ID + "=?", new String[]{String.valueOf(service)}, null, null, null);
            while (cursor.moveToNext()) {
                ContentValues values = new ContentValues();
                DatabaseUtils.cursorRowToContentValues(cursor, values);
                collections.add(CollectionInfo.fromDB(values));
            }
            return collections;
        }
    }


    /* LIST ADAPTERS */

    public static class AddressBookAdapter extends ArrayAdapter<CollectionInfo> {
        public AddressBookAdapter(Context context) {
            super(context, R.layout.account_address_book_item);
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null)
                v = LayoutInflater.from(getContext()).inflate(R.layout.account_address_book_item, parent, false);

            CollectionInfo info = getItem(position);

            TextView tv = (TextView)v.findViewById(R.id.title);
            tv.setText(TextUtils.isEmpty(info.displayName) ? info.url : info.displayName);

            tv = (TextView)v.findViewById(R.id.description);
            if (TextUtils.isEmpty(info.description))
                tv.setVisibility(View.GONE);
            else {
                tv.setVisibility(View.VISIBLE);
                tv.setText(info.description);
            }

            return v;
        }
    }

    public static class CalendarAdapter extends ArrayAdapter<CollectionInfo> {
        public CalendarAdapter(Context context) {
            super(context, R.layout.account_calendar_item);
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null)
                v = LayoutInflater.from(getContext()).inflate(R.layout.account_calendar_item, parent, false);

            CollectionInfo info = getItem(position);

            TextView tv = (TextView)v.findViewById(R.id.title);
            tv.setText(TextUtils.isEmpty(info.displayName) ? info.url : info.displayName);

            tv = (TextView)v.findViewById(R.id.description);
            if (TextUtils.isEmpty(info.description))
                tv.setVisibility(View.GONE);
            else {
                tv.setVisibility(View.VISIBLE);
                tv.setText(info.description);
            }

            tv = (TextView)v.findViewById(R.id.events);
            tv.setVisibility(info.supportsVEVENT ? View.VISIBLE : View.GONE);

            tv = (TextView)v.findViewById(R.id.tasks);
            tv.setVisibility(info.supportsVTODO ? View.VISIBLE : View.GONE);

            return v;
        }
    }


    /* USER ACTIONS */

    protected void deleteAccount() {
        Account account = new Account(accountName, Constants.ACCOUNT_TYPE);
        AccountManager accountManager = AccountManager.get(this);

        if (Build.VERSION.SDK_INT >= 22)
            accountManager.removeAccount(account, this, new AccountManagerCallback<Bundle>() {
                @Override
                public void run(AccountManagerFuture<Bundle> future) {
                    try {
                        if (future.getResult().getBoolean(AccountManager.KEY_BOOLEAN_RESULT))
                            finish();
                    } catch(OperationCanceledException|IOException|AuthenticatorException e) {
                        Constants.log.error("Couldn't remove account", e);
                    }
                }
            }, null);
        else
            accountManager.removeAccount(account, new AccountManagerCallback<Boolean>() {
                @Override
                public void run(AccountManagerFuture<Boolean> future) {
                    try {
                        if (future.getResult())
                            finish();
                    } catch (OperationCanceledException|IOException|AuthenticatorException e) {
                        Constants.log.error("Couldn't remove account", e);
                    }
                }
            }, null);
    }

}
