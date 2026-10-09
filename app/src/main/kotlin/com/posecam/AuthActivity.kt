package com.posecam

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import com.posecam.core.cloud.AuthValidation
import com.posecam.core.sync.CloudSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Email and password sign-in / sign-up. Recording never needs it; uploading does, because every recording is filed under
 * the collector's account (and counted in their history). It is shown once when the app is first used, and again from
 * Recordings whenever the backend stops accepting the token.
 *
 * With [EXTRA_REQUIRED] (first launch) Back leaves the app instead of returning to a recorder nobody is signed in to.
 */
class AuthActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var name: EditText
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var confirm: EditText
    private lateinit var error: TextView
    private lateinit var submit: Button
    private lateinit var toggle: Button
    private lateinit var progress: ProgressBar

    private var signingUp = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)
        keepBelowSystemBars()
        title = findViewById(R.id.authTitle)
        subtitle = findViewById(R.id.authSubtitle)
        name = findViewById(R.id.authName)
        email = findViewById(R.id.authEmail)
        password = findViewById(R.id.authPassword)
        confirm = findViewById(R.id.authConfirm)
        error = findViewById(R.id.authError)
        submit = findViewById(R.id.authSubmit)
        toggle = findViewById(R.id.authToggle)
        progress = findViewById(R.id.authProgress)

        val sync = CloudSync.get(this)
        if (!sync.config.enabled) {
            finish()
            return
        }
        signingUp = savedInstanceState?.getBoolean(STATE_SIGNING_UP) ?: false
        if (savedInstanceState == null) email.setText(sync.auth.current().lastEmail.orEmpty())

        addShowPasswordToggle(password)
        addShowPasswordToggle(confirm)
        submit.setOnClickListener { submit() }
        toggle.setOnClickListener {
            signingUp = !signingUp
            error.visibility = View.GONE
            render()
        }
        confirm.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) submit()
            actionId == EditorInfo.IME_ACTION_DONE
        }
        password.setOnEditorActionListener { _, actionId, _ ->
            val last = !signingUp && actionId == EditorInfo.IME_ACTION_NEXT
            if (last) submit()
            last
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SIGNING_UP, signingUp)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (intent.getBooleanExtra(EXTRA_REQUIRED, false)) finishAffinity() else super.onBackPressed()
    }

    private fun render() {
        title.text = if (signingUp) "Create your account" else "Sign in"
        subtitle.text = if (signingUp) {
            "Recordings you upload are filed under your account, and the app keeps count of them for you."
        } else {
            "Sign in to upload your recordings. You can keep recording without a connection."
        }
        name.visibility = if (signingUp) View.VISIBLE else View.GONE
        confirm.visibility = if (signingUp) View.VISIBLE else View.GONE
        // "Next" on the last visible field would not submit; make the keyboard action match the form.
        password.imeOptions = if (signingUp) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE
        submit.text = if (signingUp) "Create account" else "Sign in"
        toggle.text = if (signingUp) "I already have an account" else "New here? Create an account"
        setBusy(busy)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        submit.isEnabled = !value
        toggle.isEnabled = !value
        progress.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun submit() {
        if (busy) return
        val address = AuthValidation.normalizeEmail(email.text.toString())
        val secret = password.text.toString()
        val displayName = name.text.toString().trim()
        AuthValidation.problem(address, secret, if (signingUp) confirm.text.toString() else null, if (signingUp) displayName else null)?.let {
            showError(it)
            return
        }
        error.visibility = View.GONE
        setBusy(true)
        val sync = CloudSync.get(this)
        scope.launch {
            try {
                val session = if (signingUp) sync.accounts.register(address, secret, displayName) else sync.accounts.login(address, secret)
                sync.onSignedIn(session)
                finish()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                setBusy(false)
                showError(AuthValidation.messageFor(failure))
            }
        }
    }

    /**
     * An eye at the end of a password field: tap to read what was typed, tap again to hide it. Each field has its own
     * eye, and the cursor stays where it was.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun addShowPasswordToggle(field: EditText) {
        var visible = false
        fun apply() {
            val selection = field.selectionEnd
            field.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                if (visible) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            // Changing the input type resets the font; a password field is drawn in the default one.
            field.typeface = android.graphics.Typeface.DEFAULT
            field.setSelection(selection.coerceAtLeast(0))
            field.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility, 0)
            field.contentDescription = getString(R.string.auth_show_password)
        }
        apply()
        field.setOnTouchListener { view, event ->
            val eye = field.compoundDrawablesRelative[2]
            val hit = eye != null && event.action == android.view.MotionEvent.ACTION_UP && run {
                val edge = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) view.paddingStart + eye.intrinsicWidth else view.width - view.paddingEnd - eye.intrinsicWidth
                if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) event.x <= edge else event.x >= edge
            }
            if (hit) {
                visible = !visible
                apply()
                view.performClick()
            }
            hit
        }
    }

    private fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }

    companion object {
        /** Set when no one has ever signed in on this phone: Back exits instead of returning to the recorder. */
        const val EXTRA_REQUIRED = "required"
        private const val STATE_SIGNING_UP = "signingUp"
    }
}
