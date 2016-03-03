/*
 * Copyright (c) 2015 FUJI Goro (gfx).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.gfx.android.orma.migration;

import com.github.gfx.android.orma.migration.sqliteparser.CreateTableStatement;
import com.github.gfx.android.orma.migration.sqliteparser.SQLiteComponent;
import com.github.gfx.android.orma.migration.sqliteparser.SQLiteParserUtils;

import org.json.JSONArray;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressLint("Assert")
public class SchemaDiffMigration extends AbstractMigrationEngine {

    public static final String TAG = "SchemaDiffMigration";

    public static final String MIGRATION_STEPS_TABLE = "orma_schema_diff_migration_steps";

    static final String kId = "id";

    static final String kVersionName = "version_name";

    static final String kVersionCode = "version_code";

    static final String kSchemaHash = "schema_hash";

    static final String kSql = "sql";

    static final String kArgs = "args";

    public static final String SCHEMA_DIFF_DDL = "CREATE TABLE IF NOT EXISTS "
            + MIGRATION_STEPS_TABLE + " ("
            + kId + " INTEGER PRIMARY KEY AUTOINCREMENT, "
            + kVersionName + " TEXT NOT NULL, "
            + kVersionCode + " INTEGER NOT NULL, "
            + kSchemaHash + " TEXT NOT NULL, "
            + kSql + " TEXT NULL, "
            + kArgs + " TEXT NULL, "
            + "created_timestamp DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)";

    final String versionName;

    final int versionCode;

    final String schemaHash;

    final SqliteDdlBuilder util = new SqliteDdlBuilder();

    private boolean tableCreated = false;

    public SchemaDiffMigration(@NonNull Context context, @NonNull String schemaHash, @NonNull TraceListener traceListener) {
        super(traceListener);
        this.versionName = extractVersionName(context);
        this.versionCode = extractVersionCode(context);
        this.schemaHash = schemaHash;
    }

    public SchemaDiffMigration(@NonNull Context context, @NonNull String schemaHash) {
        this(context, schemaHash, extractDebuggable(context) ? TraceListener.LOGCAT : TraceListener.EMPTY);
    }

    static private Map<SQLiteComponent, String> parseIndexes(Collection<String> indexes) {
        Map<SQLiteComponent, String> parsedIndexPairs = new HashMap<>();
        for (String createIndexStatement : indexes) {
            parsedIndexPairs.put(SQLiteParserUtils.parseIntoSQLiteComponent(createIndexStatement), createIndexStatement);
        }
        return parsedIndexPairs;
    }

    private static String serializeArgs(@NonNull Object[] args) {
        if (args.length == 0) {
            return "[]";
        }

        JSONArray array = new JSONArray();
        for (Object arg : args) {
            array.put(arg);
        }
        return array.toString();
    }

    public static Map<String, SQLiteMaster> loadMetadata(SQLiteDatabase db, List<? extends MigrationSchema> schemas) {
        Map<String, SQLiteMaster> metadata = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        Set<String> tableNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (MigrationSchema schema : schemas) {
            tableNames.add(schema.getTableName());
        }

        for (Map.Entry<String, SQLiteMaster> entry : SQLiteMaster.loadTables(db).entrySet()) {
            if (tableNames.contains(entry.getKey())) {
                metadata.put(entry.getKey(), entry.getValue());
            }
        }
        return metadata;
    }

    @NonNull
    @Override
    public String getTag() {
        return TAG;
    }

    @Override
    public void start(@NonNull SQLiteDatabase db, @NonNull List<? extends MigrationSchema> schemas) {
        if (isSchemaChanged(db)) {
            Map<String, ? extends MigrationSchema> masterSchemas = loadMetadata(db, schemas);
            List<String> statements = diffAll(masterSchemas, schemas);
            executeStatements(db, statements);
        }
    }

    private boolean isSchemaChanged(SQLiteDatabase db) {
        String oldSchemaHash = fetchDbSchemaHash(db);
        return !schemaHash.equals(oldSchemaHash);
    }

    @Nullable
    private String fetchDbSchemaHash(SQLiteDatabase db) {
        ensureHistoryTableExists(db);
        Cursor cursor = db.query(MIGRATION_STEPS_TABLE, new String[]{kSchemaHash},
                null, null, null, null, kId + " DESC", "1");
        try {
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
            } else {
                return null;
            }
        } finally {
            cursor.close();
        }
    }

    void ensureHistoryTableExists(SQLiteDatabase db) {
        if (!tableCreated) {
            db.execSQL(SCHEMA_DIFF_DDL);
            tableCreated = true;
        }
    }

    @NonNull
    public List<String> diffAll(@NonNull Map<String, ? extends MigrationSchema> srsSchemas,
            @NonNull List<? extends MigrationSchema> dstSchemas) {
        List<String> statements = new ArrayList<>();

        // NOTE: ignore tables which exist only in database
        for (MigrationSchema dstSchema : dstSchemas) {
            MigrationSchema srcSchema = srsSchemas.get(dstSchema.getTableName());
            if (srcSchema == null) {
                statements.add(dstSchema.getCreateTableStatement());
                statements.addAll(dstSchema.getCreateIndexStatements());
            } else {
                List<String> tableDiffStatements = tableDiff(srcSchema.getCreateTableStatement(),
                        dstSchema.getCreateTableStatement());

                if (tableDiffStatements.isEmpty()) {
                    statements.addAll(indexDiff(srcSchema.getCreateIndexStatements(), dstSchema.getCreateIndexStatements()));
                } else {
                    // This table needs re-create, where all the indexes are also dropped.
                    statements.addAll(tableDiffStatements);
                    statements.addAll(dstSchema.getCreateIndexStatements());
                }
            }
        }
        return statements;
    }

    /**
     * @param srcIndexes Set of "CREATED INDEX" statements which the DB has
     * @param dstIndexes Set of "CREATE INDEX" statements which the code has
     * @return List of "CREATED INDEX" statements to apply to DB
     */
    @NonNull
    public List<String> indexDiff(@NonNull Collection<String> srcIndexes, @NonNull Collection<String> dstIndexes) {
        LinkedHashMap<SQLiteComponent, String> unionIndexes = new LinkedHashMap<>();

        Map<SQLiteComponent, String> srcIndexesPairs = parseIndexes(srcIndexes);
        unionIndexes.putAll(srcIndexesPairs);

        Map<SQLiteComponent, String> dstIndexesPairs = parseIndexes(dstIndexes);
        unionIndexes.putAll(dstIndexesPairs);

        List<String> createIndexStatements = new ArrayList<>();
        for (Map.Entry<SQLiteComponent, String> createIndexStatement : unionIndexes.entrySet()) {
            boolean existsInDst = dstIndexesPairs.containsKey(createIndexStatement.getKey());
            boolean existsInSrc = srcIndexesPairs.containsKey(createIndexStatement.getKey());

            if (existsInDst && existsInSrc) {
                // okay, nothing to do
            } else if (existsInDst) {
                createIndexStatements.add(createIndexStatement.getValue());
            } else {
                String statement = buildDropIndexStatement(createIndexStatement.getValue());
                if (!TextUtils.isEmpty(statement)) {
                    createIndexStatements.add(buildDropIndexStatement(createIndexStatement.getValue()));
                }
            }
        }
        return createIndexStatements;
    }

    public List<String> tableDiff(String from, String to) {
        if (from.equals(to)) {
            return Collections.emptyList();
        }

        CreateTableStatement fromTable = SQLiteParserUtils.parseIntoCreateTableStatement(from);
        CreateTableStatement toTable = SQLiteParserUtils.parseIntoCreateTableStatement(to);

        Set<CreateTableStatement.ColumnDef> toColumns = new LinkedHashSet<>();
        Set<SQLiteComponent.Name> toColumnNames = new LinkedHashSet<>();
        List<CreateTableStatement.ColumnDef> intersectionColumns = new ArrayList<>();

        List<SQLiteComponent.Name> intersectionColumnNames = new ArrayList<>();

        for (CreateTableStatement.ColumnDef column : toTable.getColumns()) {
            toColumns.add(column);
            toColumnNames.add(column.getName());
        }

        for (CreateTableStatement.ColumnDef column : fromTable.getColumns()) {
            if (toColumns.contains(column)) {
                intersectionColumns.add(column);
            }

            if (toColumnNames.contains(column.getName())) {
                intersectionColumnNames.add(column.getName());
            }
        }

        if (intersectionColumns.size() != toTable.getColumns().size() ||
                intersectionColumns.size() != fromTable.getColumns().size() ||
                !fromTable.getConstraints().equals(toTable.getConstraints())) {
            trace("from: %s", from);
            trace("to:   %s", to);
            return util.buildRecreateTable(fromTable, toTable, intersectionColumnNames, intersectionColumnNames);
        } else {
            return Collections.emptyList();
        }
    }

    public String buildDropIndexStatement(String createIndexStatement) {
        assert !TextUtils.isEmpty(createIndexStatement);
        // TODO: use SQLiteParser if it get rid of ANTLR4 (ANTLR4-based parser is too slow).
        Pattern indexNamePattern = Pattern.compile(
                "CREATE \\s+ INDEX (?:\\s+ IF \\s+ NOT \\s+ EXISTS)? \\s+ (\\S+) \\s+ ON .+",
                Pattern.CASE_INSENSITIVE | Pattern.COMMENTS | Pattern.DOTALL);

        Matcher matcher = indexNamePattern.matcher(createIndexStatement);
        if (matcher.matches()) {
            String indexName = SqliteDdlBuilder.ensureNotEscaped(matcher.group(1));
            return "DROP INDEX IF EXISTS " + SqliteDdlBuilder.ensureEscaped(indexName);
        } else {
            return "";
        }
    }

    public void saveStep(SQLiteDatabase db, @Nullable String sql, @NonNull Object... args) {
        ensureHistoryTableExists(db);

        ContentValues values = new ContentValues();
        values.put(kVersionName, versionName);
        values.put(kVersionCode, versionCode);
        values.put(kSchemaHash, schemaHash);
        values.put(kSql, sql);
        values.put(kArgs, serializeArgs(args));
        db.insertOrThrow(MIGRATION_STEPS_TABLE, null, values);
    }

    public void executeStatements(final SQLiteDatabase db, final List<String> statements) {
        if (statements.isEmpty()) {
            return;
        }

        transaction(db, new Runnable() {
            @Override
            public void run() {
                for (String statement : statements) {
                    trace("%s", statement);
                    db.execSQL(statement);
                    saveStep(db, statement);
                }
            }
        });
    }
}
