# Room Database Migration Rules

## The rule

**Every change to a Room entity / DAO that modifies the database schema MUST ship with a `Migration` object.** No exceptions.

`AppModule.provideAppDatabase` does NOT use `fallbackToDestructiveMigration`. If you bump the schema version without adding a corresponding migration, **the app will crash on first launch for any user who upgrades**. This is the desired behaviour — it stops the schema bump from accidentally going out the door.

Pre-`v1.0`, the project used destructive migration. `v1.0` is the line in the sand: from this version onwards, no user data is wiped on update.

## Workflow when changing the schema

1. **Bump the version** in `AppDatabase.kt`:
   ```kotlin
   @Database(
       entities = [...],
       version = 8,                    // was 7
       exportSchema = true             // make sure this is true so Room writes a JSON
   )
   ```
2. **Write a `Migration`**:
   ```kotlin
   val MIGRATION_7_8 = object : Migration(7, 8) {
       override fun migrate(db: SupportSQLiteDatabase) {
           // Example: adding a column
           db.execSQL("ALTER TABLE bookmarks ADD COLUMN colour INTEGER NOT NULL DEFAULT 0")
       }
   }
   ```
3. **Register the migration** in `AppModule.provideAppDatabase`:
   ```kotlin
   return Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
       .addMigrations(MIGRATION_7_8)
       .build()
   ```
4. **Test** by:
   - Running the app on the previous version,
   - Adding some data (a bookmark, a pinned item),
   - Updating to the new version (without uninstalling),
   - Verifying the data survived AND the new column / table works.

## Tips for common schema changes

| Change | SQL idiom |
|---|---|
| Add a nullable column | `ALTER TABLE t ADD COLUMN x TYPE` |
| Add a non-null column with default | `ALTER TABLE t ADD COLUMN x TYPE NOT NULL DEFAULT v` |
| Add an index | `CREATE INDEX IF NOT EXISTS idx_t_x ON t(x)` |
| Rename a column | Not directly supported — copy table, drop original, rename new |
| Drop a column | `ALTER TABLE t DROP COLUMN x` (SQLite ≥ 3.35; on older fall back to copy-table) |
| Add a new table | `CREATE TABLE IF NOT EXISTS new_table (...)` |
| Drop a table | `DROP TABLE IF EXISTS old_table` |

## When you absolutely must wipe data

If a schema change is so structural that no migration is feasible (e.g. you're rebuilding the entire database from scratch), use `fallbackToDestructiveMigrationFrom(N)` to authorise the wipe **only for** specific previous versions. Never use `fallbackToDestructiveMigration(true)` again — that authorises future-you to wipe data on any bump, and future-you will eventually do it.
