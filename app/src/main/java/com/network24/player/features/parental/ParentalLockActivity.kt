package com.network24.player.features.parental

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.parental.ParentalLock
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.live.adapter.ManageCategoryAdapter
import com.network24.player.features.live.models.LiveCategory
import com.network24.player.features.live.repository.LiveRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Parental Lock. The switches pick the locked categories; the two PIN fields are
 * "new PIN / repeat" when the lock is off, and "current PIN / new PIN (optional)" when it is on.
 * Saved on Main for the whole account, so play.web24.live uses the same lock.
 */
class ParentalLockActivity : BaseActivity() {

    private lateinit var prefs: PreferenceManager
    private lateinit var repository: LiveRepository
    private lateinit var webState: WebStateRepository
    private lateinit var adapter: ManageCategoryAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var pinFirst: EditText
    private lateinit var pinSecond: EditText
    private lateinit var error: TextView
    private lateinit var btnSave: TextView
    private lateinit var btnOff: TextView
    private lateinit var btnLockNow: TextView

    private var categories: List<LiveCategory> = emptyList()
    private val selected = mutableSetOf<String>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val contentRoot = layoutInflater.inflate(R.layout.activity_parental_lock, null, false) as ViewGroup
        setContentView(setupGlobalRightDrawer(contentRoot, contentRoot.findViewById(R.id.btnMore)))

        prefs = PreferenceManager(this)
        repository = LiveRepository(this)
        webState = WebStateRepository(this)

        findViewById<View>(R.id.parentalBack).setOnClickListener { finish() }
        recycler = findViewById(R.id.rvParental)
        progress = findViewById(R.id.progressParental)
        status = findViewById(R.id.parentalStatus)
        pinFirst = findViewById(R.id.pinFirst)
        pinSecond = findViewById(R.id.pinSecond)
        error = findViewById(R.id.parentalError)
        btnSave = findViewById(R.id.btnLockSave)
        btnOff = findViewById(R.id.btnLockOff)
        btnLockNow = findViewById(R.id.btnLockNow)

        // switch on = locked
        adapter = ManageCategoryAdapter(onLabel = "Locked", offLabel = "Not locked") { category, locked ->
            if (locked) selected.add(category.category_id) else selected.remove(category.category_id)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        btnSave.setOnClickListener { save() }
        btnOff.setOnClickListener { turnOff() }
        btnLockNow.setOnClickListener {
            ParentalLock.relock()
            Toast.makeText(this, "Locked", Toast.LENGTH_SHORT).show()
            render()
        }
        load()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            webState.sync(force = true)
            categories = runCatching {
                withContext(Dispatchers.IO) {
                    repository.getCategories(
                        server = prefs.getServer(),
                        username = prefs.getUsername(),
                        password = prefs.getPassword(),
                        forceRefresh = false
                    )
                }
            }.getOrDefault(emptyList())
            progress.visibility = View.GONE
            selected.clear()
            if (ParentalLock.isEnabled(this@ParentalLockActivity)) {
                selected.addAll(ParentalLock.lockedIds(this@ParentalLockActivity))
            } else {
                // suggestion for a first lock: the adult categories
                selected.addAll(categories.filter { Regex("ADULT|XXX|18\\+", RegexOption.IGNORE_CASE).containsMatchIn(it.category_name) }.map { it.category_id })
            }
            // locked (or suggested) categories first, so they are seen without scrolling through all of them
            categories = categories.sortedBy { if (it.category_id in selected) 0 else 1 }
            render()
            recycler.post { recycler.getChildAt(0)?.requestFocus() }
        }
    }

    private fun render() {
        val enabled = ParentalLock.isEnabled(this)
        val allIds = categories.map { it.category_id }.toSet()
        adapter.updateList(categories, allIds - selected)
        status.text = if (enabled) {
            "Parental lock is ON · ${ParentalLock.lockedIds(this).size} locked" +
                (if (ParentalLock.isUnlocked()) " · open on this device right now" else "") +
                ". To change it, enter your current PIN below. The lock also works on play.web24.live."
        } else {
            "Lock categories with a PIN. Their channels are hidden from Live TV, Favorites, Recently Watched and search until the PIN is entered. The lock is saved in your account, so it also works on play.web24.live."
        }
        pinFirst.setText(""); pinSecond.setText("")
        val defaultPin = !ParentalLock.hasCustomPin(this)
        pinFirst.hint = if (enabled) (if (defaultPin) "Current PIN (default ${ParentalLock.DEFAULT_PIN})" else "Current PIN") else "Your own PIN (optional)"
        pinSecond.hint = if (enabled) "New PIN (leave empty to keep it)" else "Repeat your PIN"
        if (!enabled || defaultPin) status.append("\nThe default PIN is ${ParentalLock.DEFAULT_PIN}. You can set your own PIN below (recommended).")
        btnSave.text = if (enabled) "Save changes" else "Turn on"
        btnOff.visibility = if (enabled) View.VISIBLE else View.GONE
        btnLockNow.visibility = if (enabled && ParentalLock.isUnlocked()) View.VISIBLE else View.GONE
        error.visibility = View.GONE
    }

    private fun showError(text: String) {
        error.text = text
        error.visibility = View.VISIBLE
    }

    private fun save() {
        if (busy) return
        val enabled = ParentalLock.isEnabled(this)
        val first = pinFirst.text?.toString()?.trim().orEmpty()
        val second = pinSecond.text?.toString()?.trim().orEmpty()
        val pinOk = Regex("^\\d{4,8}$")
        if (selected.isEmpty()) { showError("Choose at least one category to lock."); return }
        val newPin: String
        val currentPin: String
        if (enabled) {
            if (first.isEmpty()) { showError("Enter your current PIN to save changes."); return }
            if (second.isNotEmpty() && !pinOk.matches(second)) { showError("The new PIN must be 4 to 8 digits."); return }
            currentPin = first; newPin = second
        } else {
            // own PIN is optional: without it the lock starts with the default 0000
            if (first.isNotEmpty() || second.isNotEmpty()) {
                if (!pinOk.matches(first)) { showError("The PIN must be 4 to 8 digits."); return }
                if (first != second) { showError("The two PINs are not the same."); return }
            }
            currentPin = ""; newPin = first
        }
        busy = true
        lifecycleScope.launch {
            val result = webState.setLock(newPin, currentPin, selected.toList())
            busy = false
            result.onSuccess {
                Toast.makeText(
                    this@ParentalLockActivity,
                    if (!enabled && newPin.isEmpty()) "Parental lock is on - the PIN is ${ParentalLock.DEFAULT_PIN}" else "Parental lock saved",
                    Toast.LENGTH_LONG
                ).show()
                selected.clear(); selected.addAll(ParentalLock.lockedIds(this@ParentalLockActivity))
                render()
            }.onFailure { showError(it.message ?: "Could not save. Please try again.") }
        }
    }

    private fun turnOff() {
        if (busy) return
        val current = pinFirst.text?.toString()?.trim().orEmpty()
        if (current.isEmpty()) { showError("Enter your current PIN to turn the lock off."); pinFirst.requestFocus(); return }
        busy = true
        lifecycleScope.launch {
            val result = webState.lockOff(current)
            busy = false
            result.onSuccess {
                Toast.makeText(this@ParentalLockActivity, "Parental lock is off", Toast.LENGTH_SHORT).show()
                render()
            }.onFailure { showError(it.message ?: "Could not save. Please try again.") }
        }
    }
}
