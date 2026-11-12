package com.tyy.aitranslate

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {

    private lateinit var adapter: ProfileAdapter
    private lateinit var emptyView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        emptyView = findViewById(R.id.empty_view)
        adapter = ProfileAdapter(
            onSelect = { profile ->
                Prefs.setActiveProfile(this, profile.id)
                reload()
                toast(getString(R.string.switched_to, profile.name))
            },
            onMenu = { profile -> showProfileMenu(profile) }
        )
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = this@SettingsActivity.adapter
        }
        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            showEditDialog(null)
        }

        findViewById<View>(R.id.row_custom_langs).setOnClickListener {
            showLanguageManager()
        }
        findViewById<View>(R.id.row_prompt_text).setOnClickListener {
            openPrompt(image = false)
        }
        findViewById<View>(R.id.row_prompt_image).setOnClickListener {
            openPrompt(image = true)
        }

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val profiles = Prefs.profiles(this)
        adapter.submit(profiles, Prefs.activeId(this))
        emptyView.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
        refreshSettings()
    }

    private fun refreshSettings() {
        val count = Prefs.customTargets(this).size
        findViewById<TextView>(R.id.setting_langs_summary).text =
            if (count == 0) {
                getString(R.string.custom_target_summary_none)
            } else {
                getString(R.string.custom_target_summary_count, count)
            }
        findViewById<TextView>(R.id.setting_prompt_text_summary).text =
            if (Prefs.promptText(this).isBlank()) {
                getString(R.string.prompt_summary_default)
            } else {
                getString(R.string.prompt_summary_custom)
            }
        findViewById<TextView>(R.id.setting_prompt_image_summary).text =
            if (Prefs.promptImage(this).isBlank()) {
                getString(R.string.prompt_summary_default)
            } else {
                getString(R.string.prompt_summary_custom)
            }
    }

    private fun showLanguageManager() {
        val custom = Prefs.customTargets(this)
        if (custom.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.custom_target_title)
                .setMessage(R.string.custom_target_empty)
                .setPositiveButton(R.string.custom_target_add) { _, _ ->
                    showAddLanguageDialog()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_target_title)
            .setItems(custom.toTypedArray()) { _, which ->
                confirmRemoveTarget(custom[which])
            }
            .setPositiveButton(R.string.custom_target_add) { _, _ ->
                showAddLanguageDialog()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveTarget(name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_target_delete_title)
            .setMessage(getString(R.string.custom_target_delete_message, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                Prefs.removeCustomTarget(this, name)
                refreshSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddLanguageDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val layout = TextInputLayout(this).apply {
            hint = getString(R.string.custom_target_add_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        layout.addView(input)
        container.addView(layout)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_target_add_title)
            .setView(container)
            .setPositiveButton(R.string.custom_target_add, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    layout.error = getString(R.string.custom_target_invalid)
                    return@setOnClickListener
                }
                if (!Prefs.addCustomTarget(this, name)) {
                    layout.error = getString(R.string.custom_target_duplicate)
                    return@setOnClickListener
                }
                refreshSettings()
                toast(getString(R.string.custom_target_added, name))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun openPrompt(image: Boolean) {
        startActivity(
            Intent(this, PromptActivity::class.java)
                .putExtra(PromptActivity.EXTRA_IMAGE, image)
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showProfileMenu(profile: ApiProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(profile.name)
            .setItems(
                arrayOf(getString(R.string.edit), getString(R.string.delete))
            ) { _, which ->
                when (which) {
                    0 -> showEditDialog(profile)
                    1 -> confirmDelete(profile)
                }
            }
            .show()
    }

    private fun confirmDelete(profile: ApiProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_profile)
            .setMessage(getString(R.string.delete_profile_message, profile.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                Prefs.deleteProfile(this, profile.id)
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditDialog(profile: ApiProfile?) {
        val view = layoutInflater.inflate(R.layout.dialog_profile, null)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.input_name_layout)
        val urlLayout = view.findViewById<TextInputLayout>(R.id.input_base_url_layout)
        val modelLayout = view.findViewById<TextInputLayout>(R.id.input_model_layout)
        val nameInput = view.findViewById<TextInputEditText>(R.id.input_name)
        val urlInput = view.findViewById<TextInputEditText>(R.id.input_base_url)
        val keyInput = view.findViewById<TextInputEditText>(R.id.input_api_key)
        val modelInput = view.findViewById<TextInputEditText>(R.id.input_model)

        profile?.let {
            nameInput.setText(it.name)
            urlInput.setText(it.baseUrl)
            keyInput.setText(it.apiKey)
            modelInput.setText(it.model)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (profile == null) R.string.add_api else R.string.edit_api)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                nameLayout.error = null
                urlLayout.error = null
                modelLayout.error = null

                var name = nameInput.text?.toString()?.trim().orEmpty()
                var url = urlInput.text?.toString()?.trim().orEmpty()
                val key = keyInput.text?.toString()?.trim().orEmpty()
                val model = modelInput.text?.toString()?.trim().orEmpty()

                if (url.isEmpty()) {
                    urlLayout.error = getString(R.string.please_enter_url)
                    return@setOnClickListener
                }
                if (model.isEmpty()) {
                    modelLayout.error = getString(R.string.please_enter_model)
                    return@setOnClickListener
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                if (name.isEmpty()) {
                    name = if (model.isNotBlank()) model else getString(R.string.default_profile_name)
                }

                if (profile == null) {
                    val saved = Prefs.addProfile(
                        this,
                        ApiProfile(0L, name, url, key, model)
                    )
                    Prefs.setActiveProfile(this, saved.id)
                } else {
                    Prefs.updateProfile(
                        this,
                        profile.copy(name = name, baseUrl = url, apiKey = key, model = model)
                    )
                }
                reload()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
