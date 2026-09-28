package com.network24.player.features.login.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.network24.player.features.live.repository.CategorySettingsRepository
import com.network24.player.features.dashboard.activity.DashboardActivity
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.databinding.ActivityLoginBinding
import com.network24.player.features.login.repository.LoginRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import androidx.activity.result.contract.ActivityResultContracts
import com.network24.player.core.vpn.TunnelManager
import com.network24.player.features.vpn.repository.VpnProvisioningRepository
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel

import com.google.firebase.firestore.FirebaseFirestore
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.repository.FavoritesRepository

class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding

    private lateinit var repository: LoginRepository
    private lateinit var prefs: PreferenceManager
    private lateinit var vpnRepository: VpnProvisioningRepository
    private var vpnBusy = false

    // Android's one-time "allow this app to set up a VPN" prompt.
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        } else {
            vpnBusy = false
            renderVpn()
            Toast.makeText(this, "VPN permission was not granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (blockedByGate) return

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = LoginRepository()
        prefs = PreferenceManager(this)
        vpnRepository = VpnProvisioningRepository(prefs)
        binding.loginVpnToggle.setOnClickListener { toggleVpn() }
        renderVpn()


        // Login button par focus
        binding.edtUsername.requestFocus()


        // Restore saved credentials
        if (prefs.isRememberMe()) {

            binding.edtUsername.setText(prefs.getUsername())
            binding.edtPassword.setText(prefs.getPassword())
            binding.chkRemember.isChecked = true
        }

        binding.btnLogin.setOnClickListener {

            login()
        }
    }

    private fun login() {

        val server = PreferenceManager.SERVER_URL

        val username = binding.edtUsername.text.toString().trim()

        val password = binding.edtPassword.text.toString().trim()


        setLoading(true)

        if (username.isEmpty()) {

            binding.edtUsername.error = "Enter Username"
            setLoading(false)
            return
        }

        if (password.isEmpty()) {

            binding.edtPassword.error = "Enter Password"
            setLoading(false)
            return
        }


        lifecycleScope.launch {

            try {

                val response = repository.login(server, username, password)

                if (com.network24.player.BuildConfig.DEBUG) {
                    android.util.Log.d("LOGIN", "HTTP Code = ${response.code()}, Successful = ${response.isSuccessful}")
                }

                val body = response.body()


                if (response.isSuccessful &&
                    response.body() != null &&
                    response.body()!!.user_info?.auth == 1
                ) {

                    val userInfo = response.body()!!.user_info!!

                    // Always save user session
                    prefs.saveUserInfo(
                        username = userInfo.username ?: username,
                        status = userInfo.status ?: "Unknown",
                        expiry = userInfo.exp_date?.toLongOrNull() ?: 0L,
                        activeConnections = userInfo.active_cons?.toIntOrNull() ?: 0,
                        maxConnections = userInfo.max_connections?.toIntOrNull() ?: 0,
                        isTrial = userInfo.is_trial == "1",
                        vpnPersistentAccess = userInfo.vpn_access == "1"
                    )

                    prefs.saveLogin(
                        server,
                        username,
                        password,
                        binding.chkRemember.isChecked
                    )

                    try {
                        val userId = userInfo.username ?: username
                        val db = DatabaseProvider.get(this@LoginActivity)
                        val firestore = FirebaseFirestore.getInstance()
                        val favRepo = FavoritesRepository(db.favoritesDao(), firestore)
                        val categorySettingsRepo = CategorySettingsRepository(firestore, prefs)

                        // These are the only normal Firebase reads for the live/favorites state.
                        // Run them together so login does not wait for them one after another.
                        coroutineScope {
                            val favoritesSync = async { favRepo.syncFromCloud(userId) }
                            val categoriesSync = async { categorySettingsRepo.syncFromCloud(userId) }
                            favoritesSync.await()
                            categoriesSync.await()
                        }
                    } catch (_: Exception) {
                        // ignore: login ko block nahi karna
                    }

                    startActivity(
                        Intent(
                            this@LoginActivity,
                            DashboardActivity::class.java
                        )
                    )

                    finish()

                } else {

                    setLoading(false)

                    Toast.makeText(
                        this@LoginActivity,
                        "Invalid Username or Password.",
                        Toast.LENGTH_LONG
                    ).show()

                    if (com.network24.player.BuildConfig.DEBUG) {
                        androidx.appcompat.app.AlertDialog.Builder(this@LoginActivity)
                            .setTitle("Login Debug")
                            .setMessage(
                                """
HTTP: ${response.code()}
Success: ${response.isSuccessful}
Auth: ${body?.user_info?.auth}
Status: ${body?.user_info?.status}
Message: ${body?.user_info?.message}
Body: $body
        """.trimIndent()
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }

            } catch (e: IOException) {

                setLoading(false)

                // The usual reason in the field: the customer's internet
                // provider blocks our server. Point them at the VPN.
                val vpnOn = TunnelManager.currentState(this@LoginActivity) == Tunnel.State.UP
                Toast.makeText(
                    this@LoginActivity,
                    if (vpnOn) "Unable to connect to server, Please try again."
                    else "Unable to connect to server. If your internet provider blocks Network24, turn on VPN and tap Login again.",
                    Toast.LENGTH_LONG
                ).show()
                if (!vpnOn) binding.loginVpnToggle.requestFocus()

            } catch (e: Exception) {

                setLoading(false)

                Toast.makeText(
                    this@LoginActivity,
                    e.localizedMessage ?: "Unknown Error, Try again or Contact Support.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (blockedByGate) return
        renderVpn()
    }

    // ---------------------------------------------------------------- VPN before sign-in

    private fun toggleVpn() {
        if (vpnBusy) return
        if (TunnelManager.currentState(this) == Tunnel.State.UP) {
            stopVpn()
            return
        }
        val username = binding.edtUsername.text.toString().trim()
        val password = binding.edtPassword.text.toString().trim()
        if (username.isEmpty() || password.isEmpty()) {
            // The VPN is set up with the customer's own login.
            if (username.isEmpty()) binding.edtUsername.error = "Enter Username"
            if (password.isEmpty()) binding.edtPassword.error = "Enter Password"
            Toast.makeText(this, "Enter your username and password first, then turn on VPN.", Toast.LENGTH_LONG).show()
            return
        }
        vpnBusy = true
        renderVpn()
        val consent = GoBackend.VpnService.prepare(this)
        if (consent != null) vpnConsent.launch(consent) else startVpn()
    }

    private fun startVpn() {
        val username = binding.edtUsername.text.toString().trim()
        val password = binding.edtPassword.text.toString().trim()
        lifecycleScope.launch {
            val result = vpnRepository.provision(username, password)
            result.onSuccess { tunnel ->
                try {
                    TunnelManager.bringUp(this@LoginActivity, tunnel.config)
                    prefs.setVpnEnabled(true)
                    Toast.makeText(this@LoginActivity, "VPN connected. You can sign in now.", Toast.LENGTH_SHORT).show()
                    binding.btnLogin.requestFocus()
                } catch (e: Exception) {
                    prefs.setVpnEnabled(false)
                    Toast.makeText(this@LoginActivity, "Couldn't Connect to VPN Servers.", Toast.LENGTH_LONG).show()
                }
            }.onFailure { error ->
                prefs.setVpnEnabled(false)
                // Server wording (vpn_api.php) when there is a reply; ours only
                // when neither Main nor the backup route could be reached.
                val message = when {
                    error is IOException -> "Couldn't reach the VPN service. Check your internet connection and try again."
                    error.message.isNullOrBlank() -> "Couldn't Connect to VPN Servers."
                    error.message!!.contains("login", ignoreCase = true) -> "Username or password is incorrect."
                    else -> error.message!!
                }
                Toast.makeText(this@LoginActivity, message, Toast.LENGTH_LONG).show()
            }
            vpnBusy = false
            renderVpn()
        }
    }

    private fun stopVpn() {
        vpnBusy = true
        renderVpn()
        val username = binding.edtUsername.text.toString().trim()
        val password = binding.edtPassword.text.toString().trim()
        lifecycleScope.launch {
            try {
                TunnelManager.bringDown(this@LoginActivity)
            } catch (e: Exception) {
                // Already down.
            }
            vpnRepository.release(username, password)
            prefs.setVpnEnabled(false)
            vpnBusy = false
            renderVpn()
        }
    }

    private fun renderVpn() {
        val on = TunnelManager.currentState(this) == Tunnel.State.UP
        binding.loginVpnSwitch.isChecked = on || vpnBusy
        binding.loginVpnLabel.text = when {
            vpnBusy -> "VPN…"
            on -> "VPN On"
            else -> "VPN"
        }
        binding.loginVpnHint.text = when {
            vpnBusy -> "Connecting to VPN…"
            on -> "VPN connected. Tap Login."
            else -> "Can't sign in? Turn on VPN, then tap Login."
        }
    }

    private fun setLoading(loading: Boolean) {

        if (loading) {

            binding.btnLogin.text = ""
            binding.btnLogin.isEnabled = false
            binding.loginLoadingLayout.visibility = View.VISIBLE

        } else {

            binding.loginLoadingLayout.visibility = View.GONE
            binding.btnLogin.text = "LOGIN"
            binding.btnLogin.isEnabled = true

        }
    }
}