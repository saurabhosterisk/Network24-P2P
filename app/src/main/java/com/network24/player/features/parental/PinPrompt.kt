package com.network24.player.features.parental

import android.app.Dialog
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.network24.player.R
import com.network24.player.core.parental.ParentalLock
import kotlinx.coroutines.launch

/** Asks for the parental lock PIN (checked on Main); on success everything is open for 60 minutes. */
object PinPrompt {

    fun ask(activity: AppCompatActivity, onUnlocked: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (ParentalLock.isUnlocked()) { onUnlocked(); return }

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_pin, null)
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
        }
        dialog.setCancelable(true)

        val input = view.findViewById<EditText>(R.id.pinInput)
        val error = view.findViewById<TextView>(R.id.pinError)
        val positive = view.findViewById<TextView>(R.id.pinPositive)
        var busy = false

        fun showError(text: String) {
            error.text = text
            error.visibility = View.VISIBLE
        }

        fun submit() {
            if (busy) return
            val pin = input.text?.toString()?.trim().orEmpty()
            if (!Regex("^\\d{4,8}$").matches(pin)) { showError("The PIN is 4 to 8 digits."); return }
            busy = true
            positive.alpha = 0.5f
            activity.lifecycleScope.launch {
                val result = WebStateRepository(activity).verifyPin(pin)
                busy = false
                positive.alpha = 1f
                result.onSuccess {
                    ParentalLock.unlock()
                    dialog.dismiss()
                    Toast.makeText(activity, "Unlocked for 60 minutes", Toast.LENGTH_SHORT).show()
                    onUnlocked()
                }.onFailure {
                    input.setText("")
                    showError(it.message ?: "That PIN is not right.")
                    input.requestFocus()
                }
            }
        }

        positive.setOnClickListener { submit() }
        view.findViewById<TextView>(R.id.pinNegative).setOnClickListener { dialog.dismiss() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        dialog.show()
        input.requestFocus()
    }
}
