package me.echeung.moemoekyun.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import okhttp3.Request;

/**
 * Helper class for handling authorization-related tasks. Helps with the storage of the auth token
 * and actions requiring it.
 */
public class AuthUtil {

    private static final String USER_TOKEN = "user_token";
    private static final String LAST_AUTH = "last_auth";

    /**
     * Checks if the user has previously logged in (i.e. a token is stored).
     *
     * @param context Android context to fetch SharedPreferences.
     * @return Whether the user is authenticated.
     */
    public static boolean isAuthenticated(final Context context) {
        final SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        return sharedPrefs.getString(USER_TOKEN, null) != null;
    }

    /**
     * Checks how old the stored auth token is. If it's older than 28 days, it becomes invalidated.
     *
     * @param context Android context to fetch SharedPreferences.
     */
    public static void checkAuthTokenValidity(final Context context) {
        if (!AuthUtil.isAuthenticated(context)) {
            return;
        }

        // Check token is valid (max 28 days)
        final long lastAuth = AuthUtil.getTokenAge(context);
        if (Math.round((System.currentTimeMillis() / 1000 - lastAuth) / 86400.0) >= 28) {
            AuthUtil.clearAuthToken(context);
        }
    }

    /**
     * Fetches the stored auth token.
     *
     * @param context Android context to fetch SharedPreferences.
     * @return The user's auth token.
     */
    public static String getAuthToken(final Context context) {
        final SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        return sharedPrefs.getString(USER_TOKEN, null);
    }

    /**
     * Stores the auth token, also tracking the time that it was stored.
     * Android context to fetch SharedPreferences.
     *
     * @param context
     * @param token   The auth token to store, provided via the LISTEN.moe API.
     */
    public static void setAuthToken(final Context context, final String token) {
        final SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        final SharedPreferences.Editor editor = sharedPrefs.edit()
                .putString(USER_TOKEN, token)
                .putLong(LAST_AUTH, System.currentTimeMillis() / 1000);
        editor.apply();
    }

    /**
     * Removes the stored auth token.
     *
     * @param context Android context to fetch SharedPreferences.
     */
    public static void clearAuthToken(final Context context) {
        final SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        final SharedPreferences.Editor editor = sharedPrefs.edit()
                .putString(USER_TOKEN, null)
                .putLong(LAST_AUTH, 0);
        editor.apply();
    }

    /**
     * Checks how old the token is.
     *
     * @param context Android context to fetch SharedPreferences.
     * @return The time in seconds since the stored auth token was stored.
     */
    public static long getTokenAge(final Context context) {
        final SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        return sharedPrefs.getLong(LAST_AUTH, 0);
    }

    /**
     * Creates a Request.Builder for performing requests to the API with the auth token added as a
     * header value.
     *
     * @param context  Android context to fetch SharedPreferences.
     * @param endpoint The API endpoint to hit.
     * @return A Request.Builder object configured with the provided endpoint and the auth token.
     */
    public static Request.Builder createAuthRequest(final Context context, final String endpoint) {
        return new Request.Builder()
                .url(endpoint)
                .addHeader("authorization", AuthUtil.getAuthToken(context));
    }
}
