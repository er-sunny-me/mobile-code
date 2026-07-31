package com.termux.app.utils;

import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;

public class SearchableDialogUtils {

    public interface OnModelSelectedListener {
        void onModelSelected(String model);
    }

    public static void showSearchableDialog(Context context, String title, String[] items, OnModelSelectedListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        EditText searchInput = new EditText(context);
        searchInput.setHint("Search models...");
        searchInput.setSingleLine(true);
        layout.addView(searchInput);

        ListView listView = new ListView(context);
        List<String> itemList = Arrays.asList(items);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, itemList);
        listView.setAdapter(adapter);
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        params.topMargin = 16;
        listView.setLayoutParams(params);
        layout.addView(listView);

        builder.setView(layout);
        AlertDialog dialog = builder.create();

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.getFilter().filter(s);
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String selected = adapter.getItem(position);
            if (listener != null) {
                listener.onModelSelected(selected);
            }
            dialog.dismiss();
        });

        dialog.show();
        
        // set dialog height dynamically to 80% screen to avoid full screen takeover
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (context.getResources().getDisplayMetrics().heightPixels * 0.8)
            );
        }
    }
}
