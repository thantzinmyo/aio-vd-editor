package com.tharunbirla.librecuts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.collection.LruCache
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Parcelable
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tharunbirla.librecuts.databinding.ActivityMainBinding
import com.tharunbirla.librecuts.utils.DraftEntry
import com.tharunbirla.librecuts.utils.DraftRegistry
import com.tharunbirla.librecuts.utils.ErrorCode
import com.tharunbirla.librecuts.utils.ProjectSerializer
import com.tharunbirla.librecuts.utils.setBounceClickListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val draftThumbCache = LruCache<String, Bitmap>(12)
    private val selectVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    Log.e("VideoSelection", "Could not take persistable permission", e)
                }
                Log.d("VideoSelection", "Video selected: $uri")
                navigateToEditingScreen(uri)
            } else {
                Log.e("VideoSelectionError", "No video selected")
            }
        }

    private val openProjectLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    Log.e("ProjectSelection", "Could not take persistable permission for project URI", e)
                }
                Log.d("ProjectSelection", "Project selected: $uri")
                val intent = Intent(this, ProjectImportActivity::class.java).apply {
                    putExtra("PROJECT_URI", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                startActivity(intent)
            } else {
                Log.e("ProjectSelectionError", "No project selected")
            }
        }

    private val selectFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    val prefs = getSharedPreferences("librecuts_prefs", MODE_PRIVATE)
                    prefs.edit().putString("export_directory_uri", uri.toString()).apply()
                    updateExportFolderUI(uri, binding.tvCurrentExportFolder, R.string.str_default_movies_librecuts)
                } catch (e: Exception) {
                    Log.e("FolderSelectionError", "Error securing permission for URI", e)
                    showToast(getString(R.string.toast_failed_to_set_export_folder))
                }
            }
        }

    private val selectAudioFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    val prefs = getSharedPreferences("librecuts_prefs", MODE_PRIVATE)
                    prefs.edit().putString("export_audio_directory_uri", uri.toString()).apply()
                    updateExportFolderUI(uri, binding.tvCurrentAudioExportFolder, R.string.str_default_music_librecuts)
                } catch (e: Exception) {
                    Log.e("FolderSelectionError", "Error securing permission for URI", e)
                    showToast(getString(R.string.toast_failed_to_set_export_folder))
                }
            }
        }

    private val selectSnapshotFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    val prefs = getSharedPreferences("librecuts_prefs", MODE_PRIVATE)
                    prefs.edit().putString("export_snapshot_directory_uri", uri.toString()).apply()
                    updateExportFolderUI(uri, binding.tvCurrentSnapshotExportFolder, R.string.str_default_pictures_librecuts)
                } catch (e: Exception) {
                    Log.e("FolderSelectionError", "Error securing permission for URI", e)
                    showToast(getString(R.string.toast_failed_to_set_export_folder))
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnImport.setBounceClickListener {
            Log.d("ButtonClick", "Launching video selection.")
            selectVideo()
        }

        binding.btnOpenProject.setBounceClickListener {
            Log.d("ButtonClick", "Launching project selection.")
            openProjectLauncher.launch(arrayOf("*/*"))
        }

        // Initialize bottom navigation tab backgrounds
        val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        val ta = obtainStyledAttributes(attrs)
        val inactiveBg = ta.getDrawable(0)
        ta.recycle()
        binding.tabSettings.background = inactiveBg
        binding.tabAbout.background = inactiveBg

        // Setup bottom navigation tab switching
        binding.tabHome.setBounceClickListener {
            switchTab(0)
        }
        
        binding.tabSettings.setBounceClickListener {
            switchTab(1)
        }

        binding.tabAbout.setBounceClickListener {
            switchTab(2)
        }
        
        // Setup Settings Actions
        binding.btnChangeExportFolder.setBounceClickListener {
            selectFolderLauncher.launch(null)
        }
        binding.btnChangeAudioExportFolder.setBounceClickListener {
            selectAudioFolderLauncher.launch(null)
        }
        binding.btnChangeSnapshotExportFolder.setBounceClickListener {
            selectSnapshotFolderLauncher.launch(null)
        }
        binding.btnChangeLanguage.setBounceClickListener {
            showLanguageDialog()
        }
        
        binding.btnCheckForUpdates.setBounceClickListener {
            checkForUpdates()
        }
        
        binding.btnOpenSourceLicenses.setBounceClickListener {
            com.mikepenz.aboutlibraries.LibsBuilder()
                .withActivityTitle(getString(R.string.str_open_source_licenses))
                .withSearchEnabled(true)
                .start(this)
        }
        
        // Initialize Settings UI
        val prefs = getSharedPreferences("librecuts_prefs", MODE_PRIVATE)
        val savedUriString = prefs.getString("export_directory_uri", null)
        if (savedUriString != null) {
            updateExportFolderUI(Uri.parse(savedUriString), binding.tvCurrentExportFolder, R.string.str_default_movies_librecuts)
        } else {
            updateExportFolderUI(null, binding.tvCurrentExportFolder, R.string.str_default_movies_librecuts)
        }

        val savedAudioUriString = prefs.getString("export_audio_directory_uri", null)
        if (savedAudioUriString != null) {
            updateExportFolderUI(Uri.parse(savedAudioUriString), binding.tvCurrentAudioExportFolder, R.string.str_default_music_librecuts)
        } else {
            updateExportFolderUI(null, binding.tvCurrentAudioExportFolder, R.string.str_default_music_librecuts)
        }

        val savedSnapshotUriString = prefs.getString("export_snapshot_directory_uri", null)
        if (savedSnapshotUriString != null) {
            updateExportFolderUI(Uri.parse(savedSnapshotUriString), binding.tvCurrentSnapshotExportFolder, R.string.str_default_pictures_librecuts)
        } else {
            updateExportFolderUI(null, binding.tvCurrentSnapshotExportFolder, R.string.str_default_pictures_librecuts)
        }

        updateLanguageUI()

        // Initialize Haptic Feedback preference
        updateHapticFeedbackUI()

        binding.btnToggleHapticFeedback.setBounceClickListener {
            val current = prefs.getBoolean("haptic_feedback", true)
            prefs.edit().putBoolean("haptic_feedback", !current).apply()
            updateHapticFeedbackUI()
        }

        // Initialize Fullscreen Editor preference
        updateFullscreenEditorUI()

        binding.btnToggleFullscreenEditor.setBounceClickListener {
            val current = prefs.getBoolean("fullscreen_editor", true)
            prefs.edit().putBoolean("fullscreen_editor", !current).apply()
            updateFullscreenEditorUI()
        }

        // Initialize Default Encoder preference
        updateEncoderUI()

        binding.btnChangeDefaultEncoder.setBounceClickListener {
            showEncoderDialog()
        }

        // Set dynamic About version tag
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvAboutVersion.text = "v${pInfo.versionName}"
        } catch (e: Exception) {
            binding.tvAboutVersion.text = "v1.0-beta5"
        }

        // AIO HUB PSM contact buttons (Telegram Channel + Admin)
        binding.btnTelegramChannel.setBounceClickListener {
            openUrl("https://t.me/aiohubpsmshop")
        }
        binding.btnContactAdmin.setBounceClickListener {
            openUrl("https://t.me/DoubleT2245")
        }

        // Onboarding / Welcome Dialog
        val isFirstLaunch = prefs.getBoolean("first_launch_v1", true)
        if (isFirstLaunch) {
            showOnboardingDialog(prefs)
        } else {
            // Existing installers upgrading to this build never saw the
            // onboarding dialog — ask for the permissions now instead.
            requestFirstLaunchPermissionsIfNeeded()
        }

        // Handle shared/intent videos
        handleIntent(intent)

        refreshDrafts()
    }

    override fun onResume() {
        super.onResume()
        // A draft may have been saved or renamed while we were away.
        refreshDrafts()
    }

    // ── Recent projects (CapCut-style drafts history) ──────────────────────

    private fun refreshDrafts() {
        val drafts = DraftRegistry.list(this)
        binding.tvRecentEmpty.visibility = if (drafts.isEmpty()) View.VISIBLE else View.GONE
        binding.gridRecentProjects.removeAllViews()
        for (draft in drafts) {
            val card = layoutInflater.inflate(R.layout.item_draft_card, binding.gridRecentProjects, false)
            val params = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
            }
            card.layoutParams = params
            val tvName = card.findViewById<TextView>(R.id.tvDraftName)
            val tvDate = card.findViewById<TextView>(R.id.tvDraftDate)
            val ivThumb = card.findViewById<ImageView>(R.id.ivDraftThumb)
            val ivPlaceholder = card.findViewById<ImageView>(R.id.ivDraftPlaceholder)
            val btnMore = card.findViewById<ImageButton>(R.id.btnDraftMore)
            tvName.text = draft.name
            tvDate.text = getString(
                R.string.str_draft_last_edited,
                DateUtils.getRelativeTimeSpanString(
                    draft.lastOpenedAt,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                )
            )
            loadDraftThumbnail(draft, ivThumb, ivPlaceholder)
            card.setBounceClickListener { openDraft(draft) }
            btnMore.setBounceClickListener { showDraftMenu(btnMore, draft) }
            binding.gridRecentProjects.addView(card)
        }
    }

    private fun loadDraftThumbnail(draft: DraftEntry, ivThumb: ImageView, ivPlaceholder: ImageView) {
        val key = draft.uri
        ivThumb.tag = key
        draftThumbCache.get(key)?.let { cached ->
            ivThumb.setImageBitmap(cached)
            ivThumb.visibility = View.VISIBLE
            ivPlaceholder.visibility = View.GONE
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            // The thumbnail comes from the source video, not the .lcprj file.
            // Prefer the stored source Uri; fall back to reading the recipe.
            var videoUriString = draft.sourceUri
            if (videoUriString.isNullOrEmpty()) {
                videoUriString = try {
                    contentResolver.openInputStream(Uri.parse(draft.uri))?.use { input ->
                        ProjectSerializer.deserialize(input.bufferedReader()).sourceUri.toString()
                    }
                } catch (e: Exception) {
                    null
                }
            }
            val bitmap = videoUriString?.let { videoUri ->
                try {
                    contentResolver.openFileDescriptor(Uri.parse(videoUri), "r")?.use { pfd ->
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(pfd.fileDescriptor)
                        val frame = retriever.getFrameAtTime(
                            1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        ) ?: retriever.getFrameAtTime()
                        retriever.release()
                        frame?.let { src ->
                            val targetWidth = 480
                            val targetHeight = (targetWidth.toFloat() * src.height / src.width)
                                .toInt().coerceAtLeast(1)
                            Bitmap.createScaledBitmap(src, targetWidth, targetHeight, true)
                        }
                    }
                } catch (e: Exception) {
                    Log.d("Drafts", "Thumbnail failed for $videoUri: ${e.message}")
                    null
                }
            }
            withContext(Dispatchers.Main) {
                if (ivThumb.tag == key && bitmap != null) {
                    draftThumbCache.put(key, bitmap)
                    ivThumb.setImageBitmap(bitmap)
                    ivThumb.visibility = View.VISIBLE
                    ivPlaceholder.visibility = View.GONE
                }
            }
        }
    }

    private fun openDraft(draft: DraftEntry) {
        val uri = Uri.parse(draft.uri)
        lifecycleScope.launch(Dispatchers.IO) {
            // Best effort: re-assert the permission while the grant is
            // still live. A project saved before the permission fix has
            // no persistable grant, so this may throw — the open below
            // is what actually decides.
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // No live grant to keep; opening may still work.
            }
            // The saved file may have been moved or deleted outside the app.
            val exists = try {
                contentResolver.openInputStream(uri)?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                if (!exists) {
                    // Never delete the entry here: a failed open can be a
                    // lost permission on a file that still exists (the
                    // user can re-open it via Open Project). Deleting it
                    // made a recoverable project look permanently lost.
                    // The user can remove it from the ⋮ menu instead.
                    showToast(getString(R.string.str_draft_missing))
                    return@withContext
                }
                DraftRegistry.touch(this@MainActivity, uri)
                val intent = Intent(this@MainActivity, ProjectImportActivity::class.java).apply {
                    putExtra("PROJECT_URI", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                startActivity(intent)
            }
        }
    }

    private fun showDraftMenu(anchor: View, draft: DraftEntry) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.str_draft_rename))
        popup.menu.add(0, 2, 0, getString(R.string.str_draft_delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showRenameDraftDialog(draft)
                2 -> showDeleteDraftDialog(draft)
            }
            true
        }
        popup.show()
    }

    private fun showRenameDraftDialog(draft: DraftEntry) {
        val input = EditText(this).apply {
            setText(draft.name)
            setSelection(draft.name.length)
            hint = draft.name
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.str_draft_rename_title)
            .setView(container)
            .setPositiveButton(R.string.str_draft_rename) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == draft.name) return@setPositiveButton
                val uri = Uri.parse(draft.uri)
                DraftRegistry.rename(this, uri, newName)
                lifecycleScope.launch(Dispatchers.IO) {
                    // Keep the name inside the .lcprj file in sync, best effort.
                    try {
                        val recipe = contentResolver.openInputStream(uri)?.use { stream ->
                            ProjectSerializer.deserialize(stream.bufferedReader())
                        } ?: return@launch
                        val json = ProjectSerializer.serialize(recipe.copy(projectName = newName))
                        contentResolver.openOutputStream(uri, "wt")?.use { out ->
                            out.write(json.toByteArray())
                        }
                    } catch (e: Exception) {
                        Log.d("Drafts", "Could not rewrite project name in file: ${e.message}")
                    }
                    withContext(Dispatchers.Main) {
                        showToast(getString(R.string.str_draft_renamed))
                        refreshDrafts()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteDraftDialog(draft: DraftEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.str_draft_delete_title)
            .setMessage(getString(R.string.str_draft_delete_message, draft.name))
            .setPositiveButton(R.string.str_draft_delete) { _, _ ->
                val uri = Uri.parse(draft.uri)
                DraftRegistry.remove(this, uri)
                try {
                    DocumentsContract.deleteDocument(contentResolver, uri)
                } catch (e: Exception) {
                    Log.d("Drafts", "Could not delete project file, removed from list only: ${e.message}")
                }
                draftThumbCache.remove(draft.uri)
                showToast(getString(R.string.str_draft_deleted))
                refreshDrafts()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateExportFolderUI(uri: Uri?, textView: TextView, defaultStringResId: Int) {
        if (uri == null) {
            textView.text = getString(defaultStringResId)
        } else {
            try {
                val path = uri.lastPathSegment?.split(":")?.lastOrNull()
                if (!path.isNullOrEmpty()) {
                    textView.text = path
                } else {
                    textView.text = getString(R.string.str_custom_directory)
                }
            } catch (e: Exception) {
                textView.text = getString(R.string.str_custom_directory)
            }
        }
    }

    private data class LanguageItem(
        val tag: String,
        val displayName: String
    )

    private fun getAvailableLanguages(): List<LanguageItem> {
        val result = mutableListOf<LanguageItem>()
        result.add(LanguageItem("", getString(R.string.str_system_default)))

        val tags = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeConfig = android.app.LocaleConfig(this)
                val locales = localeConfig.supportedLocales
                if (locales != null) {
                    for (i in 0 until locales.size()) {
                        val locale = locales.get(i)
                        if (locale != null) {
                            tags.add(locale.toLanguageTag())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "LocaleConfig error", e)
            }
        }

        if (tags.isEmpty()) {
            try {
                val resId = resources.getIdentifier("_generated_res_locale_config", "xml", packageName)
                if (resId != 0) {
                    val parser = resources.getXml(resId)
                    var eventType = parser.eventType
                    while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "locale") {
                            val name = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                            if (!name.isNullOrEmpty()) {
                                tags.add(name)
                            }
                        }
                        eventType = parser.next()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "XmlParser _generated_res_locale_config error", e)
            }
        }

        if (tags.isEmpty()) {
            tags.addAll(listOf("en", "de", "et", "sk", "pt-BR"))
        }

        val items = tags.map { tag ->
            val locale = java.util.Locale.forLanguageTag(tag)
            val name = when (tag.lowercase()) {
                "pt-br" -> "Português (Brasil)"
                "zh-cn" -> "中文 (简体)"
                "zh-tw" -> "中文 (繁體)"
                else -> locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            }
            LanguageItem(tag, name)
        }.sortedBy { it.displayName.lowercase() }

        result.addAll(items)
        return result
    }

    private fun updateLanguageUI() {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        if (currentLocales.isEmpty) {
            binding.tvCurrentLanguage.text = getString(R.string.str_system_default)
        } else {
            val locale = currentLocales.get(0)
            val tag = locale?.toLanguageTag() ?: ""
            val name = when (tag.lowercase()) {
                "pt-br" -> "Português (Brasil)"
                else -> locale?.getDisplayName(locale)?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            }
            binding.tvCurrentLanguage.text = name ?: getString(R.string.str_system_default)
        }
    }

    private fun updateHapticFeedbackUI() {
        val prefs = getSharedPreferences("librecuts_prefs", MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("haptic_feedback", true)
        binding.tvCurrentHapticFeedback.text = if (isEnabled) {
            getString(R.string.str_haptic_enabled_desc)
        } else {
            getString(R.string.str_haptic_disabled_desc)
        }
    }

    private fun updateFullscreenEditorUI() {
        val prefs = getSharedPreferences("librecuts_prefs", MODE_PRIVATE)
        val isFullscreen = prefs.getBoolean("fullscreen_editor", true)
        binding.tvCurrentFullscreenEditor.text = if (isFullscreen) {
            getString(R.string.str_fullscreen_enabled_desc)
        } else {
            getString(R.string.str_fullscreen_disabled_desc)
        }
    }

    private fun updateEncoderUI() {
        val prefs = getSharedPreferences("librecuts_prefs", MODE_PRIVATE)
        val defaultEncoder = prefs.getString("default_encoder", "hardware") ?: "hardware"
        if (defaultEncoder == "software") {
            binding.tvCurrentDefaultEncoder.text = getString(R.string.str_encoder_software)
        } else {
            binding.tvCurrentDefaultEncoder.text = getString(R.string.str_encoder_hardware)
        }
    }

    private fun showEncoderDialog() {
        val prefs = getSharedPreferences("librecuts_prefs", MODE_PRIVATE)
        val currentEncoder = prefs.getString("default_encoder", "hardware") ?: "hardware"
        val options = arrayOf(
            getString(R.string.str_encoder_hardware),
            getString(R.string.str_encoder_software)
        )
        val selectedIndex = if (currentEncoder == "software") 1 else 0

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.str_default_encoder)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val chosenEncoder = if (which == 1) "software" else "hardware"
                prefs.edit().putString("default_encoder", chosenEncoder).apply()
                updateEncoderUI()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLanguageDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.language_bottom_sheet_dialog, null)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.btnCloseSheet)?.setBounceClickListener {
            dialog.dismiss()
        }

        val container = view.findViewById<android.widget.LinearLayout>(R.id.layoutLanguageContainer)
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val currentTag = if (currentLocales.isEmpty) "" else (currentLocales.get(0)?.toLanguageTag() ?: "")

        val availableLanguages = getAvailableLanguages()

        for (item in availableLanguages) {
            val itemView = layoutInflater.inflate(R.layout.item_language_selection, container, false)
            val tvName = itemView.findViewById<TextView>(R.id.tvLanguageName)
            val ivCheck = itemView.findViewById<android.widget.ImageView>(R.id.ivCheckLanguage)

            tvName.text = item.displayName

            val isSelected = if (item.tag.isEmpty()) {
                currentTag.isEmpty()
            } else {
                currentTag.equals(item.tag, ignoreCase = true) ||
                (item.tag.length == 2 && currentTag.startsWith(item.tag, ignoreCase = true))
            }

            ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

            itemView.setBounceClickListener {
                val appLocale = if (item.tag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(item.tag)
                }
                AppCompatDelegate.setApplicationLocales(appLocale)
                updateLanguageUI()
                dialog.dismiss()
            }

            container?.addView(itemView)
        }

        dialog.show()
    }

    /**
     * First-launch permissions: ask for everything the app needs (media,
     * microphone, notifications) once, right at the start, instead of
     * interrupting the user in the middle of importing, recording or
     * exporting. Asked only once ever — if the user says no, the features
     * that need them still ask again at the moment of use (media picker,
     * voice-over, export), so nothing is lost.
     */
    private val firstLaunchPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Log.d("MainActivity", "First-launch permissions denied: $denied")
        }
    }

    private fun requestFirstLaunchPermissionsIfNeeded() {
        val prefs = getSharedPreferences("librecuts_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("permissions_requested_first_launch", false)) return
        // Mark as asked before showing anything, so this runs once ever
        // even if the user dismisses the dialogs.
        prefs.edit().putBoolean("permissions_requested_first_launch", true).apply()

        val toRequest = firstLaunchPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isEmpty()) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.str_perm_first_launch_title)
            .setMessage(R.string.str_perm_first_launch_message)
            .setPositiveButton(R.string.str_perm_allow) { _, _ ->
                firstLaunchPermissionsLauncher.launch(toRequest.toTypedArray())
            }
            .setNegativeButton(R.string.str_perm_not_now, null)
            .show()
    }

    /** Same list the media picker asks for at use-time, plus the ones the
     * editor asks for when exporting (notifications) and recording a
     * voice-over (microphone). READ_MEDIA_VISUAL_USER_SELECTED is left
     * out: the system grants it automatically when the user picks
     * limited access in the photo picker. */
    private fun firstLaunchPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.READ_MEDIA_VIDEO)
            list.add(Manifest.permission.READ_MEDIA_IMAGES)
            list.add(Manifest.permission.READ_MEDIA_AUDIO)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= 28) {
                list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        list.add(Manifest.permission.RECORD_AUDIO)
        return list
    }

    private fun switchTab(tabIndex: Int) {
        val activeBg = ContextCompat.getDrawable(this, R.drawable.bg_nav_active_pill)
        val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        val ta = obtainStyledAttributes(attrs)
        val inactiveBg = ta.getDrawable(0)
        ta.recycle()

        val activeColor = ContextCompat.getColor(this, R.color.colorPrimary)
        val inactiveColor = ContextCompat.getColor(this, R.color.inactiveTool)

        // Reset all tabs to inactive
        binding.layoutHomeContent.visibility = View.GONE
        binding.layoutSettingsContent.visibility = View.GONE
        binding.layoutAboutContent.visibility = View.GONE

        binding.tabHome.background = inactiveBg
        binding.ivHome.setColorFilter(inactiveColor)
        binding.tvHomeLabel.setTextColor(inactiveColor)

        binding.tabSettings.background = inactiveBg
        binding.ivSettings.setColorFilter(inactiveColor)
        binding.tvSettingsLabel.setTextColor(inactiveColor)

        binding.tabAbout.background = inactiveBg
        binding.ivAbout.setColorFilter(inactiveColor)
        binding.tvAboutLabel.setTextColor(inactiveColor)

        when (tabIndex) {
            0 -> {
                binding.layoutHomeContent.visibility = View.VISIBLE
                binding.tabHome.background = activeBg
                binding.ivHome.setColorFilter(activeColor)
                binding.tvHomeLabel.setTextColor(activeColor)
            }
            1 -> {
                binding.layoutSettingsContent.visibility = View.VISIBLE
                binding.tabSettings.background = activeBg
                binding.ivSettings.setColorFilter(activeColor)
                binding.tvSettingsLabel.setTextColor(activeColor)
            }
            2 -> {
                binding.layoutAboutContent.visibility = View.VISIBLE
                binding.tabAbout.background = activeBg
                binding.ivAbout.setColorFilter(activeColor)
                binding.tvAboutLabel.setTextColor(activeColor)
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            showToast("Unable to open link")
        }
    }





    private fun selectVideo() {
        Log.d("VideoSelection", "Launching video picker.")
        val picker = com.tharunbirla.librecuts.customviews.MediaPickerBottomSheet().apply {
            initialMediaType = com.tharunbirla.librecuts.customviews.MediaPickerBottomSheet.MediaType.VIDEO
            showCategoryTabs = true
            showAudioTab = false
            onMediaSelectedListener = { uri ->
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    Log.d("VideoSelection", "Could not take persistable permission: ${e.message}")
                }
                navigateToEditingScreen(uri)
            }
            onBrowseSystemFoldersRequested = {
                selectVideoLauncher.launch(arrayOf("video/*", "image/*"))
            }
        }
        picker.show(supportFragmentManager, "MediaPickerBottomSheet")
    }

    private fun navigateToEditingScreen(videoUri: Uri) {
        Log.d("Navigation", "Navigating to editing screen with URI: $videoUri")
        val intent = Intent(this, VideoEditingActivity::class.java).apply {
            putExtra("VIDEO_URI", videoUri)
            data = videoUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            if (type.startsWith("video/") || type.startsWith("image/")) {
                (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { uri ->
                    Log.d("SharedVideo", "Received SEND intent with media URI: $uri")
                    navigateToEditingScreen(uri)
                }
            }
        } else if ((Intent.ACTION_VIEW == action || Intent.ACTION_EDIT == action) && type != null) {
            if (type.startsWith("video/") || type.startsWith("image/")) {
                intent.data?.let { uri ->
                    Log.d("SharedVideo", "Received VIEW/EDIT intent with media URI: $uri")
                    navigateToEditingScreen(uri)
                }
            }
        }
    }

    private fun showOnboardingDialog(prefs: android.content.SharedPreferences) {
        val dialog = android.app.Dialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_welcome_onboarding, null)
        dialog.setContentView(view)
        dialog.setCancelable(false)

        dialog.window?.let { window ->
            // Make dialog window background transparent so our custom layout's background card and shape render perfectly
            window.setBackgroundDrawableResource(android.R.color.transparent)
            
            // Set size parameters
            val lp = window.attributes
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            window.attributes = lp
        }

        // Set version dynamically
        val tvVersion = view.findViewById<TextView>(R.id.tvOnboardingVersion)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "Version ${pInfo.versionName}"
        } catch (e: Exception) {
            tvVersion.text = "Version 1.0-beta4"
        }

        view.findViewById<View>(R.id.layoutTelegramChannel)?.setBounceClickListener {
            openUrl("https://t.me/aiohubpsmshop")
        }

        view.findViewById<View>(R.id.layoutContactAdmin)?.setBounceClickListener {
            openUrl("https://t.me/DoubleT2245")
        }

        view.findViewById<View>(R.id.btnOnboardingGetStarted)?.setBounceClickListener {
            prefs.edit().putBoolean("first_launch_v1", false).apply()
            dialog.dismiss()
            // Right after the welcome screen: ask for the permissions the
            // app needs, instead of interrupting the user mid-task later.
            requestFirstLaunchPermissionsIfNeeded()
        }

        dialog.show()
    }

    private fun showToast(message: String) {
        Log.d("ToastMessage", "Showing toast: $message")
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun checkForUpdates() {
        showToast("Checking for updates in browser...")
        openUrl("https://github.com/thantzinmyo/aio-vd-editor/releases/latest")
    }
}