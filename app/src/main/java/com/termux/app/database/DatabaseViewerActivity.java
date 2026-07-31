package com.termux.app.database;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;

public class DatabaseViewerActivity extends AppCompatActivity {

    private String dbPath;
    private SQLiteDatabase database;
    private Spinner spinnerTables;
    private TableLayout tableData;
    private TextView tvDbFilename;
    private TextView tvRowCount;
    private ImageButton btnBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_database_viewer);

        dbPath = getIntent().getStringExtra("db_path");
        if (dbPath == null) {
            Toast.makeText(this, "No database path provided", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        openDatabase();
    }

    private void initViews() {
        spinnerTables = findViewById(R.id.spinner_db_tables);
        tableData = findViewById(R.id.table_db_data);
        tvDbFilename = findViewById(R.id.tv_db_filename);
        tvRowCount = findViewById(R.id.tv_db_row_count);
        btnBack = findViewById(R.id.btn_db_back);

        java.io.File file = new java.io.File(dbPath);
        tvDbFilename.setText(file.getName());

        btnBack.setOnClickListener(v -> finish());
    }

    private void openDatabase() {
        try {
            database = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY);
            loadTables();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to open database: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void loadTables() {
        List<String> tables = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = database.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null);
            if (cursor.moveToFirst()) {
                do {
                    tables.add(cursor.getString(0));
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) cursor.close();
        }

        if (tables.isEmpty()) {
            Toast.makeText(this, "No tables found", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.spinner_item_white, tables);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_white);
        spinnerTables.setAdapter(adapter);

        spinnerTables.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // Ensure text color is visible in spinner
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(Color.WHITE);
                }
                loadTableData(tables.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void loadTableData(String tableName) {
        tableData.removeAllViews();
        Cursor cursor = null;
        try {
            cursor = database.rawQuery("SELECT * FROM " + tableName + " LIMIT 100", null);
            
            // Header
            TableRow headerRow = new TableRow(this);
            String[] columnNames = cursor.getColumnNames();
            for (String colName : columnNames) {
                headerRow.addView(createCell(colName, true));
            }
            tableData.addView(headerRow);

            // Data rows
            int rowCount = 0;
            if (cursor.moveToFirst()) {
                do {
                    TableRow dataRow = new TableRow(this);
                    for (int i = 0; i < columnNames.length; i++) {
                        String value = cursor.getString(i);
                        dataRow.addView(createCell(value != null ? value : "NULL", false));
                    }
                    tableData.addView(dataRow);
                    rowCount++;
                } while (cursor.moveToNext());
            }
            
            tvRowCount.setText(rowCount + (rowCount == 100 ? "+ rows (Limit 100)" : " rows"));

        } catch (Exception e) {
            Toast.makeText(this, "Error loading table: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private TextView createCell(String text, boolean isHeader) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(24, 16, 24, 16);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setMaxWidth(600);
        
        if (isHeader) {
            tv.setTextColor(Color.parseColor("#FFFFFF"));
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            tv.setBackgroundResource(R.drawable.bg_db_header_cell);
        } else {
            tv.setTextColor(Color.parseColor("#E0E0E0"));
            if (text.equals("NULL")) {
                tv.setTextColor(Color.parseColor("#808080"));
                tv.setTypeface(null, android.graphics.Typeface.ITALIC);
            }
            tv.setBackgroundResource(R.drawable.bg_db_cell);
        }
        
        return tv;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (database != null && database.isOpen()) {
            database.close();
        }
    }
}
