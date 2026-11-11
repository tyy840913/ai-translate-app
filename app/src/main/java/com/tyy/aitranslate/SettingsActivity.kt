package com.tyy.aitranslate

import android.os.Bundle
import android.view.View
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
    }

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
