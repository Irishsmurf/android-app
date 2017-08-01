package me.echeung.listenmoe.ui.activities;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.media.AudioManager;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.TabLayout;
import android.support.design.widget.TextInputEditText;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.method.LinkMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import butterknife.ButterKnife;
import butterknife.OnClick;
import me.echeung.listenmoe.R;
import me.echeung.listenmoe.adapters.ViewPagerAdapter;
import me.echeung.listenmoe.constants.ResponseMessages;
import me.echeung.listenmoe.databinding.MainActivityBinding;
import me.echeung.listenmoe.interfaces.AuthListener;
import me.echeung.listenmoe.ui.App;
import me.echeung.listenmoe.util.APIUtil;
import me.echeung.listenmoe.util.AuthUtil;
import me.echeung.listenmoe.util.NetworkUtil;
import me.echeung.listenmoe.util.SDKUtil;

public class MainActivity extends AppCompatActivity {
    public static final String TRIGGER_LOGIN = "trigger_login";
    public static final String AUTH_EVENT = "auth_event";

    private AlertDialog mAboutDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MainActivityBinding binding = DataBindingUtil.setContentView(this, R.layout.main_activity);
        ButterKnife.bind(this);
        binding.setHasNetworkConnection(App.STATE.hasNetworkConnection);

        if (!NetworkUtil.isNetworkAvailable(this)) {
            return;
        }

        // Init app/tab bar
        initAppbar();

        // Sets audio type to media (volume button control)
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        // Invalidate token if needed
        AuthUtil.checkAuthTokenValidity(this);

        // TODO: need to listen for TRIGGER_LOGIN while it's open for notification favorite

        if (getIntent() != null) {
            final String action = getIntent().getAction();

            if (action != null && action.equals(MainActivity.TRIGGER_LOGIN)) {
                showLoginDialog();
            }
        }
    }


    // Connection error view

    @OnClick(R.id.btn_retry)
    public void retry() {
        if (NetworkUtil.isNetworkAvailable(this)) {
            recreate();
            App.getService().connect();
        }
    }


    // Tabs

    /**
     * Initializes everything for the tabs: the adapter, icons, and title handler
     */
    private void initAppbar() {
        // Set up app bar
        setSupportActionBar(findViewById(R.id.appbar));
        getSupportActionBar().setDisplayShowTitleEnabled(false);

        // Set up ViewPager and adapter
        final ViewPager mViewPager = findViewById(R.id.pager);
        final ViewPagerAdapter mViewPagerAdapter = new ViewPagerAdapter(getSupportFragmentManager());
        mViewPager.setAdapter(mViewPagerAdapter);

        // Set up tabs
        final TabLayout mTabLayout = findViewById(R.id.tabs);
        mTabLayout.setupWithViewPager(mViewPager);
        mViewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(mTabLayout));

        final int tabCount = mTabLayout.getTabCount();
        for (int i = 0; i < tabCount; i++) {
            mTabLayout.getTabAt(i).setIcon(mViewPagerAdapter.getIcon(i));
        }
    }


    // Overflow menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        // Toggle visibility of login/logout items based on authentication status
        final boolean authenticated = AuthUtil.isAuthenticated(this);
        menu.findItem(R.id.action_login).setVisible(!authenticated);
        menu.findItem(R.id.action_logout).setVisible(authenticated);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_search:
                if (AuthUtil.isAuthenticated(this)) {
                    startActivity(new Intent(this, SearchActivity.class));
                } else {
                    showLoginDialog(() -> onOptionsItemSelected(item));
                }
                return true;

            case R.id.action_login:
                showLoginDialog();
                return true;

            case R.id.action_logout:
                showLogoutDialog();
                return true;

            case R.id.action_about:
                showAboutDialog();
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void showLoginDialog() {
        showLoginDialog(null);
    }

    public void showLoginDialog(@Nullable final OnLoginListener listener) {
        final View layout = getLayoutInflater().inflate(R.layout.dialog_login, findViewById(R.id.layout_root));
        final TextInputEditText mLoginUser = layout.findViewById(R.id.login_username);
        final TextInputEditText mLoginPass = layout.findViewById(R.id.login_password);

        final AlertDialog loginDialog = new AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle)
                .setTitle(R.string.login)
                .setView(layout)
                .setPositiveButton(R.string.login, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        // Override the positive button listener so it won't automatically be dismissed even with
        // an error
        loginDialog.setOnShowListener(dialog -> {
            final Button button = ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE);
            button.setOnClickListener(view -> {
                final String user = mLoginUser.getText().toString().trim();
                final String pass = mLoginPass.getText().toString().trim();

                if (user.length() == 0 || pass.length() == 0) {
                    return;
                }

                login(user, pass, dialog, listener);
            });
        });

        loginDialog.show();
    }

    /**
     * Logs the user in with the provided credentials.
     *
     * @param user     Username to pass in the request body.
     * @param pass     Password to pass in the request body.
     * @param dialog   Reference to the login dialog so it can be dismissed upon success.
     * @param listener Used to run something after a successful login.
     */
    private void login(final String user, final String pass, final DialogInterface dialog, final OnLoginListener listener) {
        APIUtil.authenticate(this, user, pass, new AuthListener() {
            @Override
            public void onFailure(final String result) {
                runOnUiThread(() -> {
                    String errorMsg = "";

                    switch (result) {
                        case ResponseMessages.INVALID_USER:
                            errorMsg = getString(R.string.error_name);
                            break;
                        case ResponseMessages.INVALID_PASS:
                            errorMsg = getString(R.string.error_pass);
                            break;
                        case ResponseMessages.ERROR:
                            errorMsg = getString(R.string.error_general);
                            break;
                    }

                    Toast.makeText(getBaseContext(), errorMsg, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onSuccess(final String result) {
                runOnUiThread(() -> {
                    dialog.dismiss();
                    invalidateOptionsMenu();

                    if (listener != null) {
                        listener.onLogin();
                    }

                    broadcastAuthEvent();
                });
            }
        });
    }

    private void broadcastAuthEvent() {
        final Intent authEventIntent = new Intent();
        authEventIntent.setAction(MainActivity.AUTH_EVENT);
        sendBroadcast(authEventIntent);
    }

    private void showLogoutDialog() {
        final long tokenAge = AuthUtil.getTokenAge(this);

        new AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle)
                .setTitle(R.string.logout)
                .setMessage(String.format(getString(R.string.logout_confirmation), Math.round((System.currentTimeMillis() / 1000 - tokenAge) / 86400.0)))
                .setPositiveButton(R.string.logout, (dialogInterface, i) -> logout())
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .show();
    }

    /**
     * Logs the user out.
     */
    private void logout() {
        if (!AuthUtil.isAuthenticated(this)) {
            return;
        }

        AuthUtil.clearAuthToken(this);
        Toast.makeText(this, getString(R.string.logged_out), Toast.LENGTH_LONG).show();
        invalidateOptionsMenu();

        broadcastAuthEvent();
    }

    // TODO: actual activity with LICENSEs and stuff?
    private void showAboutDialog() {
        if (mAboutDialog == null) {
            String version;
            try {
                version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                version = "";
            }

            mAboutDialog = new AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle)
                    .setTitle(R.string.about)
                    .setMessage(SDKUtil.fromHtml(getString(R.string.about_content, getString(R.string.app_name), version)))
                    .setPositiveButton(R.string.close, null)
                    .create();
        }

        mAboutDialog.show();

        final TextView textContent = mAboutDialog.findViewById(android.R.id.message);
        textContent.setMovementMethod(LinkMovementMethod.getInstance());
    }

    public interface OnLoginListener {
        void onLogin();
    }
}