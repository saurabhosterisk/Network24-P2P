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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

import com.google.firebase.firestore.FirebaseFirestore
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.repository.FavoritesRepository

class LoginActivity : BaseActivity() {

    companion object {
        // vpn.web24.live is tried first (a relay in front of the main
        // server, for users whose ISP throttles/blocks a direct connection);
        // op.web24.live is the original direct server and stays the
        // fallback so existing behavior is preserved if the relay is ever
        // unreachable. Only new app installs/updates go through this path -
        // op.web24.live itself is unchanged, so older app versions that
        // never pick up this code keep working exactly as before.
        private const val VPN_HOST = "vpn.web24.live"
        private const val VPN_SERVER = "http://vpn.web24.live:8080"
        private const val PRIMARY_SERVER = "http://op.web24.live:8080"
        private const val PROBE_PORT = 8080
        private const val PROBE_TIMEOUT_MS = 3000
    }

    private lateinit var binding: ActivityLoginBinding

    private lateinit var repository: LoginRepository
    private lateinit var prefs: PreferenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = LoginRepository()
        prefs = PreferenceManager(this)


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

    private suspend fun isHostReachable(host: String, port: Int, timeoutMs: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
                true
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Tries vpn.web24.live first (after a fast reachability probe, so a dead
     * relay fails in ~3s instead of hanging on ApiClient's 150s timeout),
     * then falls back to op.web24.live if the relay is unreachable or the
     * actual login call to it fails. Returns the server that was actually
     * used alongside the response, so the caller saves the right one.
     */
    private suspend fun loginWithFallback(
        username: String,
        password: String
    ): Pair<String, retrofit2.Response<com.network24.player.common.models.LoginResponse>> {

        val preferred = if (isHostReachable(VPN_HOST, PROBE_PORT, PROBE_TIMEOUT_MS)) {
            VPN_SERVER
        } else {
            PRIMARY_SERVER
        }

        return try {
            preferred to repository.login(preferred, username, password)
        } catch (e: IOException) {
            if (preferred == VPN_SERVER) {
                PRIMARY_SERVER to repository.login(PRIMARY_SERVER, username, password)
            } else {
                throw e
            }
        }
    }

    private fun login() {

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

                val (server, response) = loginWithFallback(username, password)

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
                        isTrial = userInfo.is_trial == "1"
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

                Toast.makeText(
                    this@LoginActivity,
                    "Unable to connect to server, Please try again.",
                    Toast.LENGTH_LONG
                ).show()

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