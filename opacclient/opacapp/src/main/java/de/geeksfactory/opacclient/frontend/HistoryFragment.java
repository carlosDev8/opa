/**
 * Copyright (C) 2013 by Raphael Michel under the MIT license:
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package de.geeksfactory.opacclient.frontend;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;

import org.joda.time.Days;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ActivityOptionsCompat;
import androidx.cursoradapter.widget.SimpleCursorAdapter;
import androidx.fragment.app.Fragment;
import androidx.loader.app.LoaderManager.LoaderCallbacks;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;
import de.geeksfactory.opacclient.OpacClient;
import de.geeksfactory.opacclient.R;
import de.geeksfactory.opacclient.frontend.OpacActivity.AccountSelectedListener;
import de.geeksfactory.opacclient.objects.Account;
import de.geeksfactory.opacclient.objects.AccountItem;
import de.geeksfactory.opacclient.searchfields.SearchField;
import de.geeksfactory.opacclient.searchfields.SearchField.Meaning;
import de.geeksfactory.opacclient.searchfields.SearchQuery;
import de.geeksfactory.opacclient.storage.HistoryDataSource;
import de.geeksfactory.opacclient.storage.HistoryDatabase;
import de.geeksfactory.opacclient.storage.HistoryItem;
import de.geeksfactory.opacclient.storage.JsonSearchFieldDataSource;
import de.geeksfactory.opacclient.utils.CompatibilityUtils;

public class HistoryFragment extends Fragment implements
        LoaderCallbacks<Cursor>, AccountSelectedListener {

    private static final String STATE_ACTIVATED_POSITION = "activated_position";
    private static final String STATE_SORT_DIRECTION = "sort_direction";
    private static final String STATE_SORT_OPTION = "sort_option";

    private static final String JSON_LIBRARY_NAME = "library_name";
    private static final String JSON_HISTORY_LIST = "history_list";
    private static final int REQUEST_CODE_EXPORT = 123;
    private static final int REQUEST_CODE_IMPORT = 124;

    private static int REQUEST_CODE_DETAIL = 1; // siehe AccountFragment.REQUEST_DETAIL
    private static int LOADER_ID = 1; // !=0 wie bei Star

    protected View view;
    protected OpacClient app;
    private ItemListAdapter adapter;
    private Callback callback;
    private ListView listView;
    private int activatedPosition = ListView.INVALID_POSITION;
    private TextView tvWelcome;
    private TextView tvHistoryHeader;
    private HistoryItem historyItem;

    private boolean showMediatype = true;
    private boolean showCover = true;

    private enum EnumSortDirection {

        DESC("DESC", R.string.sort_direction_desc), ASC("ASC", R.string.sort_direction_asc);

        final String sqlText;
        final int textId;

        private EnumSortDirection(String sqlText, int textId) {
            this.sqlText = sqlText;
            this.textId = textId;
        }

        public EnumSortDirection swap() {
            if (this == ASC) {
                return DESC;
            } else {
                return ASC;
            }
        }
    }

    EnumSortDirection currentSortDirection = null;

    private enum EnumSortOption {

        AUTOR(R.id.action_sort_author, R.string.sort_history_author,
                HistoryDatabase.HIST_COL_AUTHOR, EnumSortDirection.ASC),
        TITLE(R.id.action_sort_title, R.string.sort_history_title, HistoryDatabase.HIST_COL_TITLE,
                EnumSortDirection.ASC),
        FIRST_DATE(R.id.action_sort_firstDate, R.string.sort_history_firstDate,
                HistoryDatabase.HIST_COL_FIRST_DATE),
        LAST_DATE(R.id.action_sort_lastDate, R.string.sort_history_lastDate,
                HistoryDatabase.HIST_COL_LAST_DATE),
        PROLONG_COUNT(R.id.action_sort_prolongCount, R.string.sort_history_prolongCount,
                HistoryDatabase.HIST_COL_PROLONG_COUNT),
        DURATION(R.id.action_sort_duration, R.string.sort_history_duration,
                "julianday(lastDate) - julianday(firstDate)");

        final int menuId;
        final int textId;
        final String column;
        final EnumSortDirection initialSortDirection;

        private EnumSortOption(int menuId, int textId, String column) {
            // SortDirection Default ist DESC
            this(menuId, textId, column, EnumSortDirection.DESC);
        }

        private EnumSortOption(int menuId, int textId, String column,
                EnumSortDirection sortDirection) {
            this.menuId = menuId;
            this.textId = textId;
            this.column = column;
            this.initialSortDirection = sortDirection;
        }

        public static EnumSortOption fromMenuId(int menuId) {
            for (EnumSortOption value : EnumSortOption.values()) {
                if (value.menuId == menuId) {
                    return value;
                }
            }
            return null;
        }
    }

    private EnumSortOption currentSortOption = null;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {

        setHasOptionsMenu(true);

        view = inflater.inflate(R.layout.fragment_history, container, false);
        app = (OpacClient) getActivity().getApplication();

        adapter = new ItemListAdapter();

        listView = (ListView) view.findViewById(R.id.lvHistory);
        tvWelcome = (TextView) view.findViewById(R.id.tvHistoryWelcome);
        tvHistoryHeader = (TextView) view.findViewById(R.id.tvHistoryHeader);

        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view,
                    int position, long id) {
                HistoryItem item = (HistoryItem) view.findViewById(R.id.ivDelete)
                                                     .getTag();
                if (item.getId() == null || item.getId().equals("null")
                        || item.getId().equals("")) {

                    SharedPreferences sp = PreferenceManager
                            .getDefaultSharedPreferences(getActivity());
                    List<SearchQuery> query = new ArrayList<>();
                    List<SearchField> fields = new JsonSearchFieldDataSource(
                            app).getSearchFields(app.getLibrary().getIdent());
                    if (fields != null) {
                        SearchField title_field = null, free_field = null;
                        for (SearchField field : fields) {
                            if (field.getMeaning() == Meaning.TITLE) {
                                title_field = field;
                            } else if (field.getMeaning() == Meaning.FREE) {
                                free_field = field;
                            } else if (field.getMeaning() == Meaning.HOME_BRANCH) {
                                query.add(new SearchQuery(field, sp.getString(
                                        OpacClient.PREF_HOME_BRANCH_PREFIX
                                                + app.getAccount().getId(),
                                        null)));
                            }
                        }
                        if (title_field != null) {
                            query.add(new SearchQuery(title_field, item
                                    .getTitle()));
                        } else if (free_field != null) {
                            query.add(new SearchQuery(free_field, item
                                    .getTitle()));
                        }
                        app.startSearch(getActivity(), query);
                    } else {
                        Toast.makeText(getActivity(), R.string.no_search_cache,
                                Toast.LENGTH_LONG).show();
                    }
                } else {
//                  callback.showDetail(item.getMNr());
                    showDetailActivity(item, view);
                }
            }
        });
        listView.setClickable(true);
        listView.setTextFilterEnabled(true);

        SharedPreferences sp = PreferenceManager
                .getDefaultSharedPreferences(getContext());
        String sortOptionS = sp.getString(STATE_SORT_OPTION, null);
        if (sortOptionS != null) {
            currentSortOption = EnumSortOption.valueOf(sortOptionS);
        }
        String sortDirectionS = sp.getString(STATE_SORT_DIRECTION, null);
        if (sortDirectionS != null) {
            currentSortDirection = EnumSortDirection.valueOf(sortDirectionS);
        }

        getActivity().getSupportLoaderManager()
                     .initLoader(LOADER_ID, null, this);
        listView.setAdapter(adapter);

        if (savedInstanceState != null) {
            restoreState(savedInstanceState);
        }

        setActivateOnItemClick(((OpacActivity) getActivity()).isTablet());

        return view;
    }

    @Override
    public void onPause() {

        if (getContext() != null) {
            SharedPreferences sp = PreferenceManager
                    .getDefaultSharedPreferences(getContext());
            SharedPreferences.Editor editor = sp.edit();

            if (currentSortOption != null) {
                editor.putString(STATE_SORT_OPTION, currentSortOption.name());
            }
            if (currentSortDirection != null) {
                editor.putString(STATE_SORT_DIRECTION, currentSortDirection.name());
            }
            editor.apply();
        }

        super.onPause();
    }


    public void storeState(Bundle outState) {

        if (outState == null) return;

        if (activatedPosition != AdapterView.INVALID_POSITION) {
            // Serialize and persist the activated item position.
            outState.putInt(STATE_ACTIVATED_POSITION, activatedPosition);
        }

        if (currentSortDirection != null) {
            outState.putString(STATE_SORT_DIRECTION, currentSortDirection.name());
        }
        if (currentSortOption != null) {
            outState.putString(STATE_SORT_OPTION, currentSortOption.name());
        }
    }
    // Restores the previously serialized state (position and sorting)
    public void restoreState(Bundle savedInstanceState) {

        if (savedInstanceState == null) return;

        // Restores the previously serialized item position
        if (savedInstanceState.containsKey(STATE_ACTIVATED_POSITION)) {
            setActivatedPosition(savedInstanceState
                    .getInt(STATE_ACTIVATED_POSITION));
        }

        // Restore the previously serialized sorting of the items
        if (savedInstanceState.containsKey(STATE_SORT_DIRECTION)) {
            currentSortDirection = EnumSortDirection.valueOf(savedInstanceState
                    .getString(STATE_SORT_DIRECTION));
        }
        if (savedInstanceState.containsKey(STATE_SORT_OPTION)) {
            currentSortOption = EnumSortOption.valueOf(savedInstanceState
                    .getString(STATE_SORT_OPTION));
        }
    }
    private void updateHeader() {
        // getString needs context
        if (getContext() == null) {
            return;
        }

        String text = null;
        int countItems = adapter.getCount();
        if (currentSortOption == null) {
            text = getString(R.string.history_header, countItems);
        } else {
            String sortColumnText = getString(currentSortOption.textId);
            String sortDirectionText = getString(currentSortDirection.textId);
            text = getString(R.string.history_header_sort, countItems,
                    sortColumnText, sortDirectionText);
        }
        tvHistoryHeader.setText(text);
    }

    private void showDetailActivity(AccountItem item, View view) {
        Intent intent = new Intent(getContext(), AccountItemDetailActivity.class);
        intent.putExtra(AccountItemDetailActivity.EXTRA_ITEM, item);
        ActivityOptionsCompat options = ActivityOptionsCompat
                .makeSceneTransitionAnimation(getActivity(), view,
                        getString(R.string.transition_background));

        ActivityCompat
                .startActivityForResult(getActivity(), intent, REQUEST_CODE_DETAIL,
                        options.toBundle());
    }

    @Override
    public void onCreateOptionsMenu(android.view.Menu menu,
            MenuInflater inflater) {
        inflater.inflate(R.menu.activity_history, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == R.id.action_export) {
            share();
            return true;
        } else if (item.getItemId() == R.id.action_export_to_storage) {
            exportToStorage();
            return true;
        } else if (item.getItemId() == R.id.action_import_from_storage) {
            importFromStorage();
            return true;
        } else if (item.getItemId() == R.id.action_remove_all) {
            removeAll();
            return true;
        } else {
            EnumSortOption sortOption = EnumSortOption.fromMenuId(item.getItemId());
            if (sortOption != null) {
                sort(sortOption);
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    private void sort(EnumSortOption sortOption) {

        if (currentSortOption == sortOption) {
            // bereits nach dieser Spalte sortiert
            // d.h. ASC/DESC swappen
            currentSortDirection = currentSortDirection.swap();
        } else {
            currentSortOption = sortOption;
            currentSortDirection = sortOption.initialSortDirection;
        }

        // Loader restarten
        getActivity().getSupportLoaderManager().restartLoader(LOADER_ID, null, this);

        // Header aktualisieren
        // updateHeader(); unnötig, wird via onLoadFinished aufgerufen
    }

    @Override
    public void accountSelected(Account account) {
        getActivity().getSupportLoaderManager().restartLoader(LOADER_ID, null, this);
    }

    public void remove(HistoryItem item) {
        HistoryDataSource data = new HistoryDataSource(getActivity());
        historyItem = item;
        showSnackBar();
        data.remove(item);
    }

    public void removeAll() {
        DialogInterface.OnClickListener dialogClickListener =
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case DialogInterface.BUTTON_POSITIVE:
                                // Yes button clicked
                                HistoryDataSource data = new HistoryDataSource(getActivity());
                                String bib = app.getLibrary().getIdent();
                                data.removeAll(bib);
                                break;

                            case DialogInterface.BUTTON_NEGATIVE:
                                // No button clicked
                                break;
                        }
                    }
                };
        AlertDialog.Builder builder = new AlertDialog.Builder(
                getActivity());
        builder.setMessage(R.string.history_remove_all_sure)
               .setPositiveButton(R.string.yes, dialogClickListener)
               .setNegativeButton(R.string.no, dialogClickListener)
               .show();
    }

    //Added code to show SnackBar when clicked on Remove button in Favorites screen
    private void showSnackBar() {
        Snackbar snackbar =
                Snackbar.make(view, getString(R.string.history_removed), Snackbar.LENGTH_LONG);
        snackbar.setAction(R.string.history_removed_undo, new OnClickListener() {

            @Override
            public void onClick(View view) {
                HistoryDataSource data = new HistoryDataSource(getActivity());
                // String bib = app.getLibrary().getIdent();
                data.insertHistoryItem(historyItem);
            }
        });
        snackbar.show();
    }

    @Override
    public Loader<Cursor> onCreateLoader(int arg0, Bundle arg1) {
        if (app.getLibrary() != null) {
            String sortOrder = null;
            if (currentSortOption != null) {
                sortOrder = currentSortOption.column + " " + currentSortDirection.sqlText;
            }

            HistoryDataSource data = new HistoryDataSource(getActivity());
            showMediatype = (data.getCountItemsWithMediatype() > 0);
            showCover = (data.getCountItemsWithCover() > 0);
            // Hinweis: listitem_history_item ivCover.visibility default ist GONE

            return new CursorLoader(getActivity(),
                    app.getHistoryProviderHistoryUri(), HistoryDatabase.COLUMNS,
                    HistoryDatabase.HIST_WHERE_LIB, new String[]{app
                    .getLibrary().getIdent()}, sortOrder);
        } else {
            return null;
        }
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) {
        adapter.swapCursor(cursor);
        if (cursor.getCount() == 0) {
            tvWelcome.setVisibility(View.VISIBLE);
        } else {
            tvWelcome.setVisibility(View.GONE);
            updateHeader();
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> arg0) {
        adapter.swapCursor(null);
    }

    protected void share() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.addFlags(CompatibilityUtils.getNewDocumentIntentFlag());

        HistoryDataSource data = new HistoryDataSource(getActivity());
        boolean withMediatype = (0<data.getCountItemsWithMediatype());

        StringBuilder text = new StringBuilder();

        // Bibliothek
//      text.append(app.getLibrary().getIdent());
//      text.append(";");
//      text.append(app.getLibrary().getTitle());
        text.append(app.getLibrary().getDisplayName());
        text.append("\n");

        // Überschriftszeile
        appendColumn(text, getString(R.string.title));
        appendColumn(text, getString(R.string.author));
        if (withMediatype) {
            appendColumn(text, getString(R.string.mediatype));
        }
        // endColumn(text, getString(R.string.accountdata_lent_home_branch));
        appendColumn(text, getString(R.string.branch));
        appendColumn(text, getString(R.string.history_first_date));
        appendColumn(text, getString(R.string.history_last_date));
        appendColumn(text, getString(R.string.history_prolongCount));
        text.append("\n");

        // für Start- und Endedatum
        DateTimeFormatter fmt = DateTimeFormat.shortDate();

        // Je Item eine Zeile
        List<HistoryItem> items = data.getAllItems(app.getLibrary().getIdent());
        for (HistoryItem item : items) {

            appendColumn(text, item.getTitle());
            appendColumn(text, item.getAuthor());
            if (withMediatype) {
                if (item.getMediaType() == null) {
                    appendColumn(text, "");
                } else {
                    appendColumn(text, item.getMediaType().toString());
                }
            }
            appendColumn(text, item.getHomeBranch());
            appendColumn(text, fmt.print(item.getFirstDate()));
            appendColumn(text, fmt.print(item.getLastDate()));
            appendColumn(text, Integer.toString(item.getProlongCount()));

            text.append("\n");
        }

        intent.putExtra(Intent.EXTRA_TEXT, text.toString().trim());
        startActivity(Intent.createChooser(intent, getResources().getString(R.string.share)));
    }

    private void appendColumn(StringBuilder text, String value) {
        if (value != null) {
            text.append(value);
        }
        text.append(";");
    }

    public void exportToStorage() {
        Intent intent = null;
        //android 4.4+; use Storage Access Framework
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // Create a file with the requested MIME type.
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE,
                    "webopac_history_" + app.getLibrary().getIdent() + ".json");
            startActivityForResult(intent, REQUEST_CODE_EXPORT);
        } else {        // <android 4.4; share json as text
            intent = new Intent();
            intent.setAction(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, getEncodedHistoryItemObjects().toString());
            Intent chooser =
                    Intent.createChooser(intent, getString(R.string.export_history_to_storage));
            startActivity(chooser);
        }
    }

    /**
     * Mainly to handle exceptions as well as the regular use cases
     */
    private void showExportError() {
        Snackbar.make(getView(), R.string.failed_exporting_file,
                Snackbar.LENGTH_SHORT).show();
    }

    private void showImportError() {
        Snackbar.make(getView(), R.string.failed_importing_file,
                Snackbar.LENGTH_SHORT).show();
    }


    private void showImportErrorNoPickerApp() {
        Snackbar.make(getView(), R.string.failed_importing_file_picker_app,
                Snackbar.LENGTH_SHORT).show();
    }


    private void showImportWrongFormatError() {
        Snackbar.make(getView(), R.string.failed_importing_file_format,
                Snackbar.LENGTH_SHORT).show();
    }

    private JSONObject getEncodedHistoryItemObjects() {
        JSONObject history = new JSONObject();
        try {
            HistoryDataSource data = new HistoryDataSource(getActivity());
            JSONArray items = data.getAllItemsAsJson(app.getLibrary().getIdent());
            /*
            JSONArray items = new JSONArray();
            List<HistoryItem> libItems = data.getAllItems(app.getLibrary().getIdent());
            for (HistoryItem libItem : libItems) {
                JSONObject item = new JSONObject();
                item.put(JSON_ITEM_MNR, libItem.getMNr());
                item.put(JSON_ITEM_TITLE, libItem.getTitle());
                item.put(JSON_ITEM_MEDIATYPE, libItem.getMediaType());
                items.put(item);
            }
            */
            history.put(JSON_LIBRARY_NAME, app.getLibrary().getIdent());
            history.put(JSON_HISTORY_LIST, items);
        } catch (JSONException e) {
            showExportError();
        }
        return history;
    }

    public void importFromStorage() {
        //Use SAF
        Intent intent;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
        } else {    //let user use a custom picker
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
        }
        try {
            startActivityForResult(intent, REQUEST_CODE_IMPORT);
        } catch (ActivityNotFoundException e) {
            showImportErrorNoPickerApp();//No picker app installed!
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (requestCode == REQUEST_CODE_EXPORT && resultCode == Activity.RESULT_OK) {
            Log.i("HistoryItemFragment", intent.toString());
            Uri uri = intent.getData();
            try {
                OutputStream os = getActivity().getContentResolver().openOutputStream(uri);
                if (os != null) {
                    JSONObject history = getEncodedHistoryItemObjects();
                    PrintWriter pw = new PrintWriter(os, true);
                    pw.write(history.toString());
                    pw.close();
                    os.close();
                } else {
                    showExportError();
                }
            } catch (FileNotFoundException e) {
                showExportError();
            } catch (IOException e) {
                showExportError();
            }
        } else if (requestCode == REQUEST_CODE_IMPORT && resultCode == Activity.RESULT_OK) {
            Uri uri = intent.getData();
            InputStream is = null;
            try {
                HistoryDataSource dataSource = new HistoryDataSource(getActivity());
                is = getActivity().getContentResolver().openInputStream(uri);
                if (is != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder builder = new StringBuilder();
                    String line = "";

                    char[] chars = new char[1];
                    reader.read(chars);
                    if (chars[0] != '{') {
                        throw new WrongFileFormatException();
                    }
                    builder.append(chars);

                    while ((line = reader.readLine()) != null) {
                        builder.append(line);
                    }

                    String list = builder.toString();
                    JSONObject savedList = new JSONObject(list);
                    String bib = savedList.getString(JSON_LIBRARY_NAME);

                    //disallow import if from different library than current library
                    if (bib != null && !bib.equals(app.getLibrary().getIdent())) {
                        Snackbar.make(getView(), R.string.info_different_library,
                                Snackbar.LENGTH_SHORT).show();
                        return;
                    }

                    int countUpdate = 0;
                    int countInsert = 0;
                    JSONArray items = savedList.getJSONArray(JSON_HISTORY_LIST);
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject entry = items.getJSONObject(i);
                        HistoryDataSource.ChangeType ct = dataSource.insertOrUpdate(bib, entry);
                        switch (ct) {
                            case UPDATE:
                                countUpdate++;
                                break;
                            case INSERT:
                                countInsert++;
                                break;
                        }
                    }
                    if (countInsert > 0 || countUpdate > 0) {
                        adapter.notifyDataSetChanged();
                        Snackbar.make(getView(),
                                getString(R.string.info_history_updated_count, countInsert,
                                        countUpdate),
                                Snackbar.LENGTH_LONG).show();
                    } else {
                        Snackbar.make(getView(), R.string.info_history_updated,
                                Snackbar.LENGTH_SHORT).show();
                    }
                } else {
                    showImportError();
                }
            } catch (JSONException | IOException e) {
                showImportError();
            } catch (WrongFileFormatException e) {
                showImportWrongFormatError();
            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException e) {
                    }
                }
            }
        } else if ((requestCode == REQUEST_CODE_DETAIL) && (intent != null)) {
            String data = intent.getStringExtra(AccountItemDetailActivity.EXTRA_DATA);
            switch (resultCode) {
                case AccountItemDetailActivity.RESULT_PROLONG:
                    // TODO implement prolong from History
                    // prolong(data);
                    break;
                case AccountItemDetailActivity.RESULT_BOOKING:
                    // TODO implement booking from History
                    // bookingStart(data);
                    break;
            }
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            callback = (Callback) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement HistoryFragment.Callback");
        }
    }

    @Override
    public void onResume() {
        getActivity().getSupportLoaderManager().restartLoader(LOADER_ID, null, this);
        super.onResume();
    }

    /**
     * Turns on activate-on-click mode. When this mode is on, list items will be given the
     * 'activated' state when touched.
     */
    private void setActivateOnItemClick(boolean activateOnItemClick) {
        // When setting CHOICE_MODE_SINGLE, ListView will automatically
        // give items the 'activated' state when touched.
        listView.setChoiceMode(activateOnItemClick ? AbsListView.CHOICE_MODE_SINGLE
                : AbsListView.CHOICE_MODE_NONE);
    }

    private void setActivatedPosition(int position) {
        if (position == AdapterView.INVALID_POSITION) {
            listView.setItemChecked(activatedPosition, false);
        } else {
            listView.setItemChecked(position, true);
        }

        activatedPosition = position;
    }

    // siehe https://stackoverflow.com/questions/15313598/how-to-correctly-save-instance-state-of-fragments-in-back-stack
    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (savedInstanceState != null) {
            //Restore the fragment's state here
            restoreState(savedInstanceState);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        storeState(outState);
    }

    public interface Callback {
        public void showDetail(String mNr);

        public void removeFragment();
    }

    private class ItemListAdapter extends SimpleCursorAdapter {

        public ItemListAdapter() {
            super(getActivity(), R.layout.listitem_history_item, null,
                    new String[]{"bib"}, null, 0);
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            HistoryItem item = HistoryDataSource.cursorToItem(cursor);

            TextView tvTitleAndAuthor = (TextView) view.findViewById(R.id.tvTitleAndAuthor);

            // von AccountAdapter:
            // Overview (Title/Author, Status/Deadline, Branch)
            SpannableStringBuilder builder = new SpannableStringBuilder();
            if (item.getTitle() != null) {
                builder.append(item.getTitle());
                builder.setSpan(new StyleSpan(Typeface.BOLD), 0, item.getTitle().length(), 0);
                if (!TextUtils.isEmpty(item.getAuthor())) builder.append(". ");
            }
            if (!TextUtils.isEmpty(item.getAuthor())) {
                builder.append(item.getAuthor().split("¬\\[", 2)[0]);
            }
            setTextOrHide(builder, tvTitleAndAuthor);
            // statt von StarFragment
            /*
            if (item.getTitle() != null) {
                tvTitleAndAuthor.setText(Html.fromHtml(item.getTitle()));
            } else {
                tvTitleAndAuthor.setText("");
            }
            */

            // Spalte Cover ausblenden, wenn alle HistoryItems ohne MediaType sind
            ImageView ivCover = (ImageView) view.findViewById(R.id.ivCover);
            if ( showCover ) {
                ivCover.setVisibility(View.VISIBLE);
            } else {
                ivCover.setVisibility(View.GONE);
            }

            // Spalte Mediatype ausblenden, wenn alle HistoryItems ohne MediaType sind
            ImageView ivMediaType = (ImageView) view.findViewById(R.id.ivMediaType);
            if ( showMediatype ) {
                ivMediaType.setVisibility(View.VISIBLE);
            } else {
                ivMediaType.setVisibility(View.GONE);
            }

            TextView tvStatus = (TextView) view.findViewById(R.id.tvStatus);
            TextView tvBranch = (TextView) view.findViewById(R.id.tvBranch);

            DateTimeFormatter fmt = DateTimeFormat.shortDate();

            builder = new SpannableStringBuilder();
            if (item.getFirstDate() != null) {
                int start = builder.length();
                builder.append(fmt.print(item.getFirstDate()));
                // setSpan with a span argument is not supported before API 21
                /*
                builder.setSpan(new ForegroundColorSpan(textColorPrimary),
                        start, start + fmt.print(item.getDeadline()).length(), 0);
                 */
                int countDays = 0;
                if (item.getLastDate() != null) {
                    builder.append(" – ");
                    builder.append(fmt.print(item.getLastDate()));
                    Days daysBetween = Days.daysBetween(item.getFirstDate(), item.getLastDate());
                    countDays = 1 + daysBetween.getDays();
                }
                String status = "?";
                int resId = 0;
                String fmtFirstDate = fmt.print(item.getFirstDate());
                if (countDays == 1) {
                    resId = item.isLending() ? R.string.history_status_lending_1 :
                            R.string.history_status_finished_1;
                } else {
                    resId = item.isLending() ? R.string.history_status_lending :
                            R.string.history_status_finished;
                }
                status = getString(resId, fmtFirstDate, countDays);
                setTextOrHide(status, tvStatus);
            }
            // setTextOrHide(builder, tvStatus);

            if (item.getHomeBranch() != null) {
                setTextOrHide(Html.fromHtml(item.getHomeBranch()), tvBranch);
            }

            tvBranch.getViewTreeObserver()
                    .addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            tvBranch.getViewTreeObserver().removeOnPreDrawListener(this);
                            // place tvBranch next to or below tvStatus to prevent overlapping
                            RelativeLayout.LayoutParams lp =
                                    (RelativeLayout.LayoutParams) tvBranch.getLayoutParams();
                            if (tvStatus.getPaint().measureText(tvStatus.getText().toString()) <
                                    tvStatus.getWidth() / 2 - 4) {
                                lp.addRule(RelativeLayout.BELOW, 0);  //removeRule only since API 17
                                lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                            } else {
                                lp.addRule(RelativeLayout.ALIGN_PARENT_TOP, 0);
                                lp.addRule(RelativeLayout.BELOW, R.id.tvStatus);
                            }
                            tvBranch.setLayoutParams(lp);
                            return true;
                        }
                    });

            ImageView ivType = (ImageView) view.findViewById(R.id.ivMediaType);
            if (item.getMediaType() != null) {
                ivType.setImageResource(ResultsAdapter.getResourceByMediaType(item.getMediaType()));
            } else {
                ivType.setImageBitmap(null);
            }

            ImageView ivDelete = (ImageView) view.findViewById(R.id.ivDelete);
            if (ivDelete != null) {
                ivDelete.setFocusableInTouchMode(false);
                ivDelete.setFocusable(false);
                ivDelete.setTag(item);
                ivDelete.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View arg0) {
                        HistoryItem item = (HistoryItem) arg0.getTag();
                        remove(item);
                        callback.removeFragment();
                    }
                });
            }
        }
    }

    protected static void setTextOrHide(CharSequence value, TextView tv) {
        if (!TextUtils.isEmpty(value)) {
            tv.setVisibility(View.VISIBLE);
            tv.setText(value);
        } else {
            tv.setVisibility(View.GONE);
        }
    }

    private class WrongFileFormatException extends Exception {
    }
}
