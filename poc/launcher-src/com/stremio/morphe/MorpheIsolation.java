package com.stremio.morphe;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.app.job.JobScheduler;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.provider.BaseColumns;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Account-boundary storage and process handling for the Morphe multi-account patch.
 *
 * Stremio's Rust/core state is namespaced in the core preference file. This class
 * handles Android-side state that otherwise lives outside that namespace.
 */
public final class MorpheIsolation {
    private static final String TAG = "MorpheIsolation";
    private static final String CORE = "core";
    private static final String META = "morphe_profiles";
    private static final String ACTIVE = "morphe.active_slot";
    private static final String DEFAULT_SLOT = "account_a";
    private static final String PROFILE_PREFS_PREFIX = "morphe_account_prefs_";
    private static final String SERVER_SETTINGS_DIRECTORY = "morphe_server_settings";
    private static final String SERVER_SETTINGS_FILE = "server-settings.json";
    private static final String SLOT_STORAGE_DIRECTORY = "morphe_account_storage";
    private static final String SLOT_INITIALIZED_MARKER = ".initialized";
    private static final String LEGACY_SERVER_DIRECTORY = "morphe_legacy_server_settings";
    private static final String LEGACY_MIGRATED = "default_prefs_migrated";
    private static final String SLOT_MARKER = "_morphe_account_slot";
    private static volatile String lastError = "";

    private MorpheIsolation() {}

    /** Returns the Android/default preference store belonging to the active account. */
    public static SharedPreferences profilePreferences(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences core = app.getSharedPreferences(CORE, Context.MODE_PRIVATE);
        String slot = validSlot(core.getString(ACTIVE, DEFAULT_SLOT));
        SharedPreferences target = app.getSharedPreferences(profilePreferencesName(slot), Context.MODE_PRIVATE);
        migrateLegacyDefaultPreferences(app, target);
        if (!slot.equals(target.getString(SLOT_MARKER, null))) {
            if (!target.edit().putString(SLOT_MARKER, slot).commit()) {
                Log.e(TAG, "Could not write account preference marker for " + slot);
            }
        }
        return target;
    }

    public static String profilePreferencesName(String slot) {
        return PROFILE_PREFS_PREFIX + validSlot(slot);
    }

    /** Deletes every Android-side store belonging to one removed account. */
    public static boolean deleteProfilePreferences(Context context, String slot) {
        String safeSlot = validSlot(slot);
        String name = profilePreferencesName(safeSlot);
        boolean deleted = context.deleteSharedPreferences(name);
        File directory = new File(context.getDataDir(), "shared_prefs");
        File xml = new File(directory, name + ".xml");
        File backup = new File(directory, name + ".xml.bak");
        boolean xmlGone = !xml.exists() || xml.delete();
        boolean backupGone = !backup.exists() || backup.delete();
        File serverSettings = accountServerSettings(context, safeSlot);
        boolean serverSettingsGone = !serverSettings.exists() || serverSettings.delete();
        boolean storageGone = deleteRecursively(slotRoot(context, safeSlot));
        boolean externalStorageGone = deleteExternalSlotStorage(context, safeSlot);
        return (deleted || (!xml.exists() && !backup.exists())) && xmlGone && backupGone
                && serverSettingsGone && storageGone && externalStorageGone;
    }

    /**
     * Terminates every Stremio process except the profile chooser, then atomically
     * rotates Android-side storage from the outgoing account to the destination.
     */
    public static boolean switchAccountRuntime(Context context, String outgoingSlot,
                                               String destinationSlot) {
        List<String> failures = new ArrayList<String>();
        long started = SystemClock.elapsedRealtime();
        if (!stopRuntimeServices(context)) failures.add("Stremio runtime service");
        if (!terminateOtherProcesses(context)) failures.add("stale Stremio process");
        long stopped = SystemClock.elapsedRealtime();
        if (failures.isEmpty() && !preserveLegacyServerSettings(context, outgoingSlot)) {
            failures.add("legacy streaming-server settings");
        }
        if (failures.isEmpty()) {
            cancelNotifications(context, failures);
            cancelScheduledJobs(context, failures);
            clearTvRows(context, failures);
        }
        long cleared = SystemClock.elapsedRealtime();
        if (failures.isEmpty() && !rotateStorage(context, outgoingSlot, destinationSlot)) {
            failures.add("account storage rotation");
        }
        long rotated = SystemClock.elapsedRealtime();
        // Job/service cancellation can race with a system-triggered process start.
        // Recheck immediately before the caller is allowed to commit a new slot.
        if (failures.isEmpty() && !terminateOtherProcesses(context)) {
            failures.add("respawned Stremio process");
        }
        Log.i(TAG, "Switch timing stop=" + (stopped - started) + "ms, system="
                + (cleared - stopped) + "ms, storage=" + (rotated - cleared)
                + "ms, total=" + (SystemClock.elapsedRealtime() - started) + "ms");
        setResult(failures);
        return failures.isEmpty();
    }

    /** Reverses a completed storage rotation if the active-slot commit fails. */
    public static boolean rollbackAccountSwitch(Context context, String outgoingSlot,
                                                String destinationSlot) {
        boolean success = rotateStorage(context, destinationSlot, outgoingSlot);
        if (!success) lastError = "account storage rollback";
        return success;
    }

    /** Restores an inactive account after the previously active account was removed. */
    public static boolean restoreAccountStorage(Context context, String slot) {
        List<MoveRecord> moves = new ArrayList<MoveRecord>();
        String safeSlot = validSlot(slot);
        if (!prepareDestinationStorage(context, safeSlot)) return false;
        try {
            for (StorageArea area : storageAreas(context, safeSlot)) {
                if (!isLiveAreaEmpty(area)) throw new IllegalStateException("live " + area.label + " is not empty");
                moveChildren(area.vault, area.live, area.filter, moves);
            }
            markSlotInitialized(context, safeSlot);
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Could not restore account storage for " + safeSlot, error);
            rollbackMoves(moves);
            lastError = "account storage restore";
            return false;
        }
    }

    /** Used when deleting the active account; switching uses switchAccountRuntime. */
    public static boolean closeCurrentRuntime(Context context) {
        List<String> failures = new ArrayList<String>();
        if (!stopRuntimeServices(context)) failures.add("Stremio runtime service");
        if (!terminateOtherProcesses(context)) failures.add("stale Stremio process");
        if (failures.isEmpty()) {
            SharedPreferences core = context.getSharedPreferences(CORE, Context.MODE_PRIVATE);
            preserveLegacyServerSettings(context, validSlot(core.getString(ACTIVE, DEFAULT_SLOT)));
            clearBoundaryData(context, failures);
        }
        if (failures.isEmpty() && !terminateOtherProcesses(context)) failures.add("respawned Stremio process");
        setResult(failures);
        return failures.isEmpty();
    }

    private static boolean stopRuntimeServices(Context context) {
        String[] services = new String[]{
                "com.stremio.common.players.MediaPlaybackService",
                "com.stremio.tv.ServerService",
                "androidx.work.impl.foreground.SystemForegroundService",
                "com.google.android.gms.measurement.AppMeasurementService",
                "com.google.firebase.sessions.SessionLifecycleService"
        };
        boolean success = true;
        for (String service : services) {
            try {
                Intent intent = new Intent();
                intent.setClassName(context.getPackageName(), service);
                context.stopService(intent);
            } catch (RuntimeException error) {
                success = false;
                Log.e(TAG, "Could not stop runtime service " + service, error);
            }
        }
        return success;
    }

    /** Adopts the current live directories as the active account's first container. */
    public static boolean migrateLegacyBoundaryData(Context context) {
        profilePreferences(context);
        List<String> failures = new ArrayList<String>();
        SharedPreferences core = context.getSharedPreferences(CORE, Context.MODE_PRIVATE);
        String activeSlot = validSlot(core.getString(ACTIVE, DEFAULT_SLOT));
        if (!stopRuntimeServices(context)) failures.add("Stremio runtime service");
        if (!terminateOtherProcesses(context)) failures.add("stale Stremio process");
        if (failures.isEmpty() && !preserveLegacyServerSettings(context, activeSlot)) {
            failures.add("legacy streaming-server settings");
        }
        if (failures.isEmpty()) {
            cancelNotifications(context, failures);
            cancelScheduledJobs(context, failures);
            clearTvRows(context, failures);
        }
        if (failures.isEmpty() && !markSlotInitialized(context, activeSlot)) {
            failures.add("active storage marker");
        }
        if (failures.isEmpty() && !terminateOtherProcesses(context)) failures.add("respawned Stremio process");
        setResult(failures);
        return failures.isEmpty();
    }

    /** Clears only the active account after Stremio's core-error recovery action. */
    public static boolean resetActiveAccount(Context context, SharedPreferences core,
                                             SharedPreferences accountPreferences) {
        List<String> failures = new ArrayList<String>();
        String slot = validSlot(core.getString(ACTIVE, DEFAULT_SLOT));
        String prefix = "morphe." + slot + ".";
        SharedPreferences.Editor editor = core.edit();
        for (String key : core.getAll().keySet()) {
            if (key.startsWith(prefix)) editor.remove(key);
        }
        if (!editor.commit()) failures.add("active core namespace");
        if (!accountPreferences.edit().clear().commit()) failures.add("active Android preferences");
        cancelNotifications(context, failures);
        clearTvRows(context, failures);
        setResult(failures);
        return failures.isEmpty();
    }

    public static String getLastError() {
        return lastError;
    }

    private static void migrateLegacyDefaultPreferences(Context context, SharedPreferences target) {
        SharedPreferences metadata = context.getSharedPreferences(META, Context.MODE_PRIVATE);
        if (metadata.getBoolean(LEGACY_MIGRATED, false)) return;

        String legacyName = context.getPackageName() + "_preferences";
        SharedPreferences legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE);
        Map<String, ?> values = legacy.getAll();
        if (!values.isEmpty()) {
            SharedPreferences.Editor editor = target.edit();
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                putPreference(editor, entry.getKey(), entry.getValue());
            }
            if (!editor.commit()) {
                Log.e(TAG, "Could not migrate legacy default preferences");
                return;
            }
        }
        if (!metadata.edit().putBoolean(LEGACY_MIGRATED, true).commit()) {
            Log.e(TAG, "Could not record default-preference migration");
        }
    }

    @SuppressWarnings("unchecked")
    private static void putPreference(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Set) editor.putStringSet(key, new HashSet<String>((Set<String>) value));
    }

    private static boolean terminateOtherProcesses(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        int ownPid = Process.myPid();
        int ownUid = Process.myUid();

        for (int attempt = 0; attempt < 8; attempt++) {
            boolean found = false;
            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes == null) return true;
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.uid == ownUid && process.pid != ownPid) {
                    found = true;
                    Process.killProcess(process.pid);
                }
            }
            if (!found) return true;
            SystemClock.sleep(60L);
        }

        List<ActivityManager.RunningAppProcessInfo> remaining = manager.getRunningAppProcesses();
        if (remaining == null) return true;
        for (ActivityManager.RunningAppProcessInfo process : remaining) {
            if (process.uid == ownUid && process.pid != ownPid) return false;
        }
        return true;
    }

    private interface StorageFilter {
        boolean include(File file);
    }

    private static final StorageFilter INCLUDE_ALL = new StorageFilter() {
        @Override public boolean include(File file) { return true; }
    };

    private static final StorageFilter NON_ACCOUNT_PREFERENCES = new StorageFilter() {
        @Override public boolean include(File file) {
            String name = file.getName();
            if (name.endsWith(".xml.bak")) name = name.substring(0, name.length() - 8);
            else if (name.endsWith(".xml")) name = name.substring(0, name.length() - 4);
            return !CORE.equals(name) && !META.equals(name) && !name.startsWith(PROFILE_PREFS_PREFIX);
        }
    };

    private static final class StorageArea {
        final String label;
        final File live;
        final File vault;
        final StorageFilter filter;

        StorageArea(String label, File live, File vault, StorageFilter filter) {
            this.label = label;
            this.live = live;
            this.vault = vault;
            this.filter = filter;
        }
    }

    private static final class MoveRecord {
        final File source;
        final File destination;

        MoveRecord(File source, File destination) {
            this.source = source;
            this.destination = destination;
        }
    }

    private static boolean rotateStorage(Context context, String outgoingSlot, String destinationSlot) {
        String outgoing = validSlot(outgoingSlot);
        String destination = validSlot(destinationSlot);
        if (outgoing.equals(destination)) return true;
        if (!prepareDestinationStorage(context, destination)) return false;

        List<MoveRecord> moves = new ArrayList<MoveRecord>();
        try {
            List<StorageArea> outgoingAreas = storageAreas(context, outgoing);
            for (StorageArea area : outgoingAreas) {
                if (!isAreaEmpty(area.vault, area.filter)) {
                    throw new IllegalStateException("active vault " + area.label + " is not empty");
                }
                moveChildren(area.live, area.vault, area.filter, moves);
            }

            List<StorageArea> destinationAreas = storageAreas(context, destination);
            for (StorageArea area : destinationAreas) {
                if (!isLiveAreaEmpty(area)) {
                    throw new IllegalStateException("live " + area.label + " was not emptied");
                }
                moveChildren(area.vault, area.live, area.filter, moves);
            }
            if (!markSlotInitialized(context, outgoing) || !markSlotInitialized(context, destination)) {
                throw new IllegalStateException("could not mark account storage initialized");
            }
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Could not rotate account storage from " + outgoing + " to " + destination, error);
            rollbackMoves(moves);
            return false;
        }
    }

    private static List<StorageArea> storageAreas(Context context, String slot) {
        File data = context.getDataDir();
        File root = slotRoot(context, slot);
        List<StorageArea> areas = new ArrayList<StorageArea>();
        areas.add(new StorageArea("files", context.getFilesDir(), new File(root, "files"), INCLUDE_ALL));
        areas.add(new StorageArea("cache", context.getCacheDir(), new File(root, "cache"), INCLUDE_ALL));
        areas.add(new StorageArea("databases", new File(data, "databases"),
                new File(root, "databases"), INCLUDE_ALL));
        areas.add(new StorageArea("no_backup", context.getNoBackupFilesDir(),
                new File(root, "no_backup"), INCLUDE_ALL));
        areas.add(new StorageArea("shared_prefs", new File(data, "shared_prefs"),
                new File(root, "shared_prefs"), NON_ACCOUNT_PREFERENCES));

        if (Build.VERSION.SDK_INT >= 19) {
            File[] externalCaches = context.getExternalCacheDirs();
            if (externalCaches != null) {
                for (int i = 0; i < externalCaches.length; i++) {
                    File external = externalCaches[i];
                    if (external == null || external.getParentFile() == null) continue;
                    File externalVault = new File(new File(new File(external.getParentFile(),
                            SLOT_STORAGE_DIRECTORY), validSlot(slot)), "external_cache_" + i);
                    areas.add(new StorageArea("external_cache_" + i, external, externalVault, INCLUDE_ALL));
                }
            }
        }
        return areas;
    }

    private static boolean prepareDestinationStorage(Context context, String slot) {
        String safeSlot = validSlot(slot);
        File root = slotRoot(context, safeSlot);
        if (!root.isDirectory() && !root.mkdirs()) return false;
        if (isSlotInitialized(context, safeSlot)) return true;

        File legacy = accountServerSettings(context, safeSlot);
        if (!legacy.isFile()) return true;
        File destination = new File(new File(new File(root, "files"), "stremio-server"),
                SERVER_SETTINGS_FILE);
        return copyAtomically(legacy, destination);
    }

    private static boolean preserveLegacyServerSettings(Context context, String activeSlot) {
        File oldDirectory = new File(context.getFilesDir(), SERVER_SETTINGS_DIRECTORY);
        File[] snapshots = oldDirectory.listFiles();
        if (snapshots != null) {
            for (File snapshot : snapshots) {
                if (!snapshot.isFile() || !snapshot.getName().endsWith(".json")) continue;
                File destination = new File(legacyServerRoot(context), snapshot.getName());
                if (!copyAtomically(snapshot, destination)) return false;
            }
        }

        File live = new File(new File(context.getFilesDir(), "stremio-server"), SERVER_SETTINGS_FILE);
        return !live.isFile() || copyAtomically(live, accountServerSettings(context, activeSlot));
    }

    private static void moveChildren(File sourceDirectory, File destinationDirectory,
                                     StorageFilter filter, List<MoveRecord> moves) throws Exception {
        if (!sourceDirectory.exists()) return;
        if (!sourceDirectory.isDirectory()) throw new IllegalStateException(sourceDirectory + " is not a directory");
        if (!destinationDirectory.isDirectory() && !destinationDirectory.mkdirs()) {
            throw new IllegalStateException("could not create " + destinationDirectory);
        }
        File[] children = sourceDirectory.listFiles();
        if (children == null) throw new IllegalStateException("could not list " + sourceDirectory);
        for (File source : children) {
            if (!filter.include(source)) continue;
            File destination = new File(destinationDirectory, source.getName());
            if (destination.exists()) throw new IllegalStateException("destination exists: " + destination);
            if (!source.renameTo(destination)) throw new IllegalStateException("could not move " + source);
            moves.add(new MoveRecord(source, destination));
        }
    }

    private static boolean isLiveAreaEmpty(StorageArea area) {
        return isAreaEmpty(area.live, area.filter);
    }

    private static boolean isAreaEmpty(File directory, StorageFilter filter) {
        if (!directory.exists()) return true;
        File[] children = directory.listFiles();
        if (children == null) return false;
        for (File child : children) if (filter.include(child)) return false;
        return true;
    }

    private static boolean rollbackMoves(List<MoveRecord> moves) {
        boolean success = true;
        for (int i = moves.size() - 1; i >= 0; i--) {
            MoveRecord move = moves.get(i);
            File parent = move.source.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) success = false;
            if (move.source.exists() || !move.destination.renameTo(move.source)) success = false;
        }
        if (!success) Log.e(TAG, "Account storage transaction rollback was incomplete");
        return success;
    }

    private static File slotRoot(Context context, String slot) {
        return new File(new File(context.getDataDir(), SLOT_STORAGE_DIRECTORY), validSlot(slot));
    }

    private static File legacyServerRoot(Context context) {
        return new File(context.getDataDir(), LEGACY_SERVER_DIRECTORY);
    }

    private static boolean deleteExternalSlotStorage(Context context, String slot) {
        if (Build.VERSION.SDK_INT < 19) return true;
        File[] externalCaches = context.getExternalCacheDirs();
        if (externalCaches == null) return true;
        boolean success = true;
        for (File external : externalCaches) {
            if (external == null || external.getParentFile() == null) continue;
            File root = new File(new File(external.getParentFile(), SLOT_STORAGE_DIRECTORY),
                    validSlot(slot));
            if (!deleteRecursively(root)) success = false;
        }
        return success;
    }

    private static boolean isSlotInitialized(Context context, String slot) {
        return new File(slotRoot(context, slot), SLOT_INITIALIZED_MARKER).isFile();
    }

    private static boolean markSlotInitialized(Context context, String slot) {
        File root = slotRoot(context, slot);
        if (!root.isDirectory() && !root.mkdirs()) return false;
        File marker = new File(root, SLOT_INITIALIZED_MARKER);
        if (marker.isFile()) return true;
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(marker, false);
            output.write(validSlot(slot).getBytes("UTF-8"));
            output.getFD().sync();
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Could not mark storage initialized for " + slot, error);
            return false;
        } finally {
            try { if (output != null) output.close(); } catch (Exception ignored) {}
        }
    }

    private static void clearBoundaryData(Context context, List<String> failures) {
        cancelNotifications(context, failures);
        cancelScheduledJobs(context, failures);
        clearTvRows(context, failures);

        if (!purgeContents(context.getCacheDir(), null)) failures.add("internal cache");
        if (Build.VERSION.SDK_INT >= 19) {
            File[] externalCaches = context.getExternalCacheDirs();
            if (externalCaches != null) {
                for (File cache : externalCaches) {
                    if (cache != null && !purgeContents(cache, null)) failures.add("external cache");
                }
            }
        }

        if (!purgeContents(context.getFilesDir(), null)) failures.add("files");
        clearDatabases(context, failures);
        if (!purgeContents(context.getNoBackupFilesDir(), null)) failures.add("no-backup jobs/session data");
        clearNonAccountPreferences(context, failures);
    }

    private static void cancelNotifications(Context context, List<String> failures) {
        try {
            NotificationManager notifications =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notifications != null) notifications.cancelAll();
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not clear notifications", error);
            failures.add("notifications");
        }
    }

    private static void cancelScheduledJobs(Context context, List<String> failures) {
        try {
            JobScheduler jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (jobs != null) jobs.cancelAll();
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not cancel scheduled jobs", error);
            failures.add("scheduled jobs");
        }
    }

    private static void clearTvRows(Context context, List<String> failures) {
        if (Build.VERSION.SDK_INT < 26) return;
        PackageManager packages = context.getPackageManager();
        if (!packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                && !packages.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) return;
        try {
            ContentResolver resolver = context.getContentResolver();
            int previewPrograms = deleteRowsForPackage(resolver, TvContract.PreviewPrograms.CONTENT_URI,
                    context.getPackageName());
            int watchNext = deleteRowsForPackage(resolver, TvContract.WatchNextPrograms.CONTENT_URI,
                    context.getPackageName());
            int channels = deleteRowsForPackage(resolver, TvContract.Channels.CONTENT_URI,
                    context.getPackageName());
            Log.i(TAG, "Cleared TV rows: channels=" + channels + ", preview=" + previewPrograms
                    + ", watchNext=" + watchNext);
        } catch (Exception error) {
            Log.e(TAG, "Could not clear Android TV channels", error);
            failures.add("Android TV channels");
        }
    }

    private static int deleteRowsForPackage(ContentResolver resolver, Uri uri, String packageName) {
        int deleted = 0;
        // TvProvider scopes an unfiltered query to rows owned by the calling package.
        // It explicitly rejects SQL selection clauses for preview/watch-next tables.
        Cursor cursor = resolver.query(uri, new String[]{BaseColumns._ID},
                null, null, null);
        if (cursor == null) throw new IllegalStateException("TV provider returned no cursor for " + uri);
        try {
            int idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID);
            while (cursor.moveToNext()) {
                Uri row = ContentUris.withAppendedId(uri, cursor.getLong(idIndex));
                deleted += resolver.delete(row, null, null);
            }
        } finally {
            cursor.close();
        }
        return deleted;
    }

    private static void clearDatabases(Context context, List<String> failures) {
        String[] databases = context.databaseList();
        if (databases == null) return;
        for (String database : databases) {
            if (!context.deleteDatabase(database)) {
                File path = context.getDatabasePath(database);
                if (path.exists()) failures.add("database " + database);
            }
        }
    }

    private static void clearNonAccountPreferences(Context context, List<String> failures) {
        File directory = new File(context.getDataDir(), "shared_prefs");
        File[] files = directory.listFiles();
        if (files == null) return;
        Set<String> names = new HashSet<String>();
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".xml.bak")) names.add(name.substring(0, name.length() - 8));
            else if (name.endsWith(".xml")) names.add(name.substring(0, name.length() - 4));
        }
        for (String name : names) {
            if (CORE.equals(name) || META.equals(name) || name.startsWith(PROFILE_PREFS_PREFIX)) continue;
            context.deleteSharedPreferences(name);
            File xml = new File(directory, name + ".xml");
            File backup = new File(directory, name + ".xml.bak");
            if ((xml.exists() && !xml.delete()) || (backup.exists() && !backup.delete())) {
                failures.add("shared preferences " + name);
            }
        }
    }

    private static void clearTelemetryFiles(Context context, List<String> failures) {
        File files = context.getFilesDir();
        File[] targets = new File[]{
                new File(files, ".crashlytics.v3"),
                new File(files, "datastore"),
                new File(files, "generatefid.lock")
        };
        for (File target : targets) {
            if (!deleteRecursively(target)) failures.add("telemetry/session file " + target.getName());
        }
    }

    private static void clearServerCache(Context context, List<String> failures) {
        File serverDirectory = new File(context.getFilesDir(), "stremio-server");
        File settings = new File(serverDirectory, SERVER_SETTINGS_FILE);
        Set<String> preserve = new HashSet<String>();
        preserve.add(SERVER_SETTINGS_FILE);
        if (!purgeContents(serverDirectory, preserve)) failures.add("streaming-server cache");

        if (!settings.isFile()) return;
        try {
            StringBuilder value = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(settings));
            try {
                String line;
                while ((line = reader.readLine()) != null) value.append(line);
            } finally {
                reader.close();
            }
            String cacheRoot = new JSONObject(value.toString()).optString("cacheRoot", "");
            if (cacheRoot.isEmpty()) return;
            File root = new File(cacheRoot).getCanonicalFile();
            if (root.equals(serverDirectory.getCanonicalFile())) return;

            boolean owned = isInside(context.getCacheDir(), root)
                    || isInside(context.getFilesDir(), root);
            if (!owned && Build.VERSION.SDK_INT >= 19) {
                File[] externalCaches = context.getExternalCacheDirs();
                if (externalCaches != null) {
                    for (File external : externalCaches) {
                        if (external != null && isInside(external, root)) owned = true;
                    }
                }
            }
            if (!owned) {
                failures.add("streaming cache outside app storage");
                Log.e(TAG, "Refusing to purge non-app cacheRoot: " + root);
            } else if (!purgeContents(root, null)) {
                failures.add("streaming cacheRoot");
            }
        } catch (Exception error) {
            Log.e(TAG, "Could not inspect streaming-server cache", error);
            failures.add("streaming-server settings");
        }
    }

    private static boolean snapshotCurrentServerSettings(Context context) {
        SharedPreferences core = context.getSharedPreferences(CORE, Context.MODE_PRIVATE);
        String slot = validSlot(core.getString(ACTIVE, DEFAULT_SLOT));
        File live = new File(new File(context.getFilesDir(), "stremio-server"), SERVER_SETTINGS_FILE);
        if (!live.isFile()) return true;
        return copyAtomically(live, accountServerSettings(context, slot));
    }

    private static File accountServerSettings(Context context, String slot) {
        File directory = legacyServerRoot(context);
        return new File(directory, validSlot(slot) + ".json");
    }

    private static boolean copyAtomically(File source, File destination) {
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
        File temporary = new File(parent, destination.getName() + ".tmp");
        FileInputStream input = null;
        FileOutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = new FileOutputStream(temporary, false);
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            output.getFD().sync();
            output.close();
            output = null;
            if (destination.exists() && !destination.delete()) return false;
            return temporary.renameTo(destination);
        } catch (Exception error) {
            Log.e(TAG, "Could not copy account streaming-server settings", error);
            return false;
        } finally {
            try { if (input != null) input.close(); } catch (Exception ignored) {}
            try { if (output != null) output.close(); } catch (Exception ignored) {}
            if (temporary.exists() && !temporary.equals(destination)) temporary.delete();
        }
    }

    private static boolean isInside(File parent, File child) throws Exception {
        String parentPath = parent.getCanonicalPath();
        String childPath = child.getCanonicalPath();
        return childPath.startsWith(parentPath + File.separator);
    }

    private static boolean purgeContents(File directory, Set<String> preserve) {
        if (directory == null || !directory.exists()) return true;
        File[] children = directory.listFiles();
        if (children == null) return directory.isDirectory();
        boolean success = true;
        for (File child : children) {
            if (preserve != null && preserve.contains(child.getName())) continue;
            if (!deleteRecursively(child)) success = false;
        }
        return success;
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) {
                if (!deleteRecursively(child)) return false;
            }
        }
        return file.delete() || !file.exists();
    }

    private static String validSlot(String slot) {
        if (slot != null && slot.matches("[a-z0-9_]{1,32}")) return slot;
        return DEFAULT_SLOT;
    }

    private static void setResult(List<String> failures) {
        if (failures.isEmpty()) {
            lastError = "";
            Log.i(TAG, "Account boundary isolated successfully");
        } else {
            StringBuilder message = new StringBuilder();
            for (String failure : failures) {
                if (message.length() > 0) message.append(", ");
                message.append(failure);
            }
            lastError = message.toString();
            Log.e(TAG, "Account boundary cleanup failed: " + lastError);
        }
    }
}
