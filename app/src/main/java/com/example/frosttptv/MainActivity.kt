package com.example.frosttptv

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.frosttptv.data.LiveCategory
import com.example.frosttptv.data.LiveStream
import com.example.frosttptv.databinding.ActivityMainBinding
import com.example.frosttptv.network.XtreamApiService
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

enum class ContentSection(val label: String) {
    ALL("Todas las secciones"),
    LIVE("TV en vivo"),
    MOVIES("Películas"),
    SERIES("Series"),
    SPORTS("Deportes");

    companion object {
        fun fromName(raw: String?): ContentSection {
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ALL
        }
    }
}

data class SavedPlaylist(
    val id: String,
    val title: String,
    val type: String, // "M3U" or "XTREAM"
    val url: String,
    val username: String = "",
    val password: String = ""
)

data class WatchProgressEntry(
    val positionMs: Long,
    val durationMs: Long
)

data class LocalUserProfile(
    val id: String,
    val username: String,
    var password: String = "",
    val favorites: MutableSet<Int> = mutableSetOf(),
    val playlists: MutableList<SavedPlaylist> = mutableListOf(),
    var activePlaylistId: String? = null,
    val searchHistory: MutableList<String> = mutableListOf(),
    val recentStreamIds: MutableList<Int> = mutableListOf(),
    val watchCounts: MutableMap<Int, Int> = mutableMapOf(),
    val watchProgress: MutableMap<String, WatchProgressEntry> = mutableMapOf(),
    var lastSection: ContentSection = ContentSection.ALL,
    var lastCategoryId: String? = null
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var channelsAdapter: ChannelListAdapter
    private lateinit var recentSectionContainer: LinearLayout

    private var streams: List<LiveStream> = emptyList()
    private var categories: List<LiveCategory> = emptyList()
    private var categoryNameById: Map<String, String> = emptyMap()
    private var directStreamUrls: Map<Int, String> = emptyMap()

    private val users = mutableListOf<LocalUserProfile>()
    private var currentUser: LocalUserProfile? = null

    private var selectedSection: ContentSection = ContentSection.ALL
    private var selectedCategory: String? = null
    private var showingFavorites = false
    private var activeNav = "home"
    private var serverUrl = ""
    private var xtreamUser = ""
    private var xtreamPassword = ""
    private var pendingPlaylistType = "AUTO" // "M3U", "XTREAM", or "AUTO"
    private var searchJob: Job? = null

    private val logoCache by lazy {
        val maxKb = ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt().coerceAtLeast(2048)
        object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    private val prefs by lazy { getSharedPreferences("frostt_preferences", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyRequestedLayoutAdjustments()
        setupRecentSectionContainer()
        setupWindowInsets()
        setupChannelsRecyclerView()
        loadUsersFromPrefs()
        updateProfileLabel()

        binding.btnProfileTop.setOnClickListener { showUserMenuDialog() }
        binding.btnNavProfile.setOnClickListener { showUserMenuDialog() }
        binding.btnManagePlaylists.setOnClickListener { showPlaylistsDialog() }
        binding.btnSavedPlaylistsLogin.setOnClickListener { showCreateUserDialog() }

        binding.btnLogin.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()
            connectWithCredentials(
                url = url,
                user = user,
                pass = pass,
                forcedType = pendingPlaylistType,
                saveAsPlaylist = true
            )
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                scheduleChannelFilter(immediate = false)
            }
            override fun afterTextChanged(s: Editable?) {
                renderSearchHistory()
            }
        })

        binding.etSearch.setOnEditorActionListener { v, _, _ ->
            val queryText = v.text?.toString()?.trim().orEmpty()
            if (queryText.length >= 2) {
                recordSearchTerm(queryText)
            }
            scheduleChannelFilter(immediate = true)
            false
        }

        binding.btnNavHome.setOnClickListener {
            if (currentUser == null) {
                showUserGateDialog()
                return@setOnClickListener
            }
            showingFavorites = false
            selectedSection = ContentSection.ALL
            selectedCategory = null
            persistUserViewState()
            updateNavigation("home")
            renderCategories()
            renderRecentAndMostWatched()
            scheduleChannelFilter(immediate = true)
            binding.tvSectionTitle.text = ContentSection.ALL.label
        }

        binding.btnNavLive.setOnClickListener {
            if (currentUser == null) {
                showUserGateDialog()
                return@setOnClickListener
            }
            showingFavorites = false
            selectedSection = ContentSection.LIVE
            selectedCategory = null
            persistUserViewState()
            updateNavigation("live")
            renderCategories()
            renderRecentAndMostWatched()
            scheduleChannelFilter(immediate = true)
            binding.tvSectionTitle.text = ContentSection.LIVE.label
        }

        binding.btnNavFavorites.setOnClickListener { openFavoritesSection() }

        binding.btnDisconnect.setOnClickListener { disconnect(clearActivePreference = true) }

        val activeUserId = prefs.getString("active_user_id", null)
        val preselectedUser = users.firstOrNull { it.id == activeUserId }
        if (preselectedUser != null && preselectedUser.password.isBlank()) {
            activateUser(preselectedUser)
        } else {
            showUserGateView()
            if (users.isNotEmpty()) {
                showUserGateDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadUsersFromPrefs()
        val activeId = currentUser?.id ?: prefs.getString("active_user_id", null)
        if (activeId != null) {
            currentUser = users.firstOrNull { it.id == activeId }
        }
        renderRecentAndMostWatched()
    }

    private fun applyRequestedLayoutAdjustments() {
        val favCounterView: View = binding.tvFavoriteCount
        val favContainer = findClickableOrCardAncestor(favCounterView, binding.dashboardContainer)
            ?: favCounterView

        favContainer.isClickable = true
        favContainer.isFocusable = true
        favContainer.setOnClickListener { openFavoritesSection() }
        favCounterView.isClickable = true
        favCounterView.setOnClickListener { openFavoritesSection() }

        hideReadyBannerInDashboard(binding.dashboardContainer, favContainer)
    }

    private fun findClickableOrCardAncestor(target: View, stopAt: ViewGroup): View? {
        var current: View? = target
        while (current != null && current !== stopAt) {
            if (current is MaterialCardView || current is androidx.cardview.widget.CardView) {
                return current
            }
            current = current.parent as? View
        }
        return null
    }

    private fun hideReadyBannerInDashboard(root: ViewGroup, favContainer: View) {
        val protectedViews = setOf<View>(
            root,
            favContainer,
            binding.tvFavoriteCount,
            binding.tvChannelCount,
            binding.etSearch,
            binding.categoryContainer,
            binding.tvSectionTitle,
            binding.rvChannels,
            binding.channelsContainer,
            binding.tvEmpty,
            binding.searchHistoryContainer
        )

        fun scan(group: ViewGroup) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child in protectedViews || child.javaClass == ViewGroup::class.java) {
                    if (child is ViewGroup) scan(child)
                    continue
                }
                if (containsReadyText(child)) {
                    val containerToHide = findBannerContainer(child, group) ?: child
                    containerToHide.visibility = View.GONE
                    return
                }
                if (child is ViewGroup) {
                    scan(child)
                }
            }
        }

        scan(root)
    }

    private fun containsReadyText(view: View): Boolean {
        if (view is TextView) {
            val txt = view.text?.toString().orEmpty().lowercase(Locale.ROOT)
            if (txt.contains("todo listo para reproducir")) return true
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsReadyText(view.getChildAt(i))) return true
            }
        }
        return false
    }

    private fun findBannerContainer(target: View, parentGroup: ViewGroup): View? {
        var current: View? = target
        while (current != null && current.parent !== parentGroup) {
            if (current is MaterialCardView || current is androidx.cardview.widget.CardView) {
                return current
            }
            current = current.parent as? View
        }
        return if (current?.parent === parentGroup) current else null
    }

    private fun openFavoritesSection() {
        if (currentUser == null) {
            showUserGateDialog()
            return
        }
        showingFavorites = true
        selectedCategory = null
        updateNavigation("favorites")
        renderCategories()
        renderRecentAndMostWatched()
        scheduleChannelFilter(immediate = true)
        binding.tvSectionTitle.text = "Mis favoritos"
    }

    private fun persistUserViewState() {
        val user = currentUser ?: return
        user.lastSection = selectedSection
        user.lastCategoryId = selectedCategory
        saveUsersToPrefs()
    }

    private fun setupRecentSectionContainer() {
        recentSectionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }
        val insertIndex = binding.dashboardContainer.indexOfChild(binding.etSearch).coerceAtLeast(0)
        binding.dashboardContainer.addView(recentSectionContainer, insertIndex)
    }

    private fun setupChannelsRecyclerView() {
        channelsAdapter = ChannelListAdapter(
            onChannelClick = { stream -> onStreamItemSelected(stream) },
            onFavoriteClick = { stream ->
                val favs = currentUser?.favorites
                if (favs != null) {
                    if (!favs.add(stream.streamId)) favs.remove(stream.streamId)
                    saveUsersToPrefs()
                    channelsAdapter.setFavorites(favs.toSet())
                    binding.tvFavoriteCount.text = favs.size.toString()
                    if (showingFavorites) {
                        scheduleChannelFilter(immediate = true)
                    }
                } else {
                    showUserGateDialog()
                }
            },
            categoryNameResolver = { categoryId ->
                resolveCategoryName(categoryId)
            }
        )

        binding.rvChannels.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = channelsAdapter
            setHasFixedSize(false)
            isNestedScrollingEnabled = true
            setItemViewCacheSize(24)
        }
    }

    private fun resolveCategoryName(categoryId: String?): String {
        if (categoryId.isNullOrBlank()) return "Televisión en vivo"
        return categoryNameById[categoryId] ?: categoryId
    }

    private fun rebuildCategoryLookup() {
        categoryNameById = categories.associate { it.categoryId to it.categoryName }
    }

    private fun setupWindowInsets() {
        val headerBaseTop = binding.topHeader.paddingTop
        val headerBaseStart = binding.topHeader.paddingStart
        val headerBaseEnd = binding.topHeader.paddingEnd
        val headerBaseBottom = binding.topHeader.paddingBottom

        val contentBaseTop = binding.contentContainer.paddingTop
        val contentBaseStart = binding.contentContainer.paddingStart
        val contentBaseEnd = binding.contentContainer.paddingEnd
        val contentBaseBottom = binding.contentContainer.paddingBottom

        val navBaseStart = binding.bottomNavigation.paddingStart
        val navBaseEnd = binding.bottomNavigation.paddingEnd

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.topHeader.updatePadding(
                left = headerBaseStart + bars.left,
                top = headerBaseTop + bars.top,
                right = headerBaseEnd + bars.right,
                bottom = headerBaseBottom
            )
            binding.contentContainer.updatePadding(
                left = contentBaseStart + bars.left,
                top = contentBaseTop,
                right = contentBaseEnd + bars.right,
                bottom = contentBaseBottom + dp(12)
            )
            binding.bottomNavigation.updatePadding(
                left = navBaseStart + bars.left,
                top = 0,
                right = navBaseEnd + bars.right,
                bottom = bars.bottom
            )
            insets
        }

        WindowCompat.getInsetsController(window, binding.mainRoot).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun loadUsersFromPrefs() {
        users.clear()
        val rawUsers = prefs.getString("local_users_json", null)
        if (!rawUsers.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(rawUsers)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                    val username = obj.optString("username").trim()
                    if (username.isNotBlank()) {
                        val favSet = mutableSetOf<Int>()
                        val favArr = obj.optJSONArray("favorites")
                        if (favArr != null) {
                            for (f in 0 until favArr.length()) {
                                favArr.optString(f).toIntOrNull()?.let { favSet.add(it) }
                            }
                        }

                        val plList = mutableListOf<SavedPlaylist>()
                        val plArr = obj.optJSONArray("playlists")
                        if (plArr != null) {
                            for (p in 0 until plArr.length()) {
                                val pObj = plArr.optJSONObject(p) ?: continue
                                val pUrl = pObj.optString("url").trim()
                                if (pUrl.isNotBlank()) {
                                    val pUser = pObj.optString("username")
                                    val pType = pObj.optString("type").ifBlank {
                                        if (pUser.isBlank()) "M3U" else "XTREAM"
                                    }
                                    plList.add(
                                        SavedPlaylist(
                                            id = pObj.optString("id").ifBlank { UUID.randomUUID().toString() },
                                            title = pObj.optString("title").ifBlank { suggestPlaylistTitle(pUrl, pUser, pType) },
                                            type = pType,
                                            url = pUrl,
                                            username = pUser,
                                            password = pObj.optString("password")
                                        )
                                    )
                                }
                            }
                        }

                        val historyList = mutableListOf<String>()
                        val histArr = obj.optJSONArray("searchHistory")
                        if (histArr != null) {
                            for (h in 0 until histArr.length()) {
                                val term = histArr.optString(h).trim()
                                if (term.isNotBlank() && historyList.none { it.equals(term, ignoreCase = true) }) {
                                    historyList.add(term)
                                }
                            }
                        }

                        val recentList = mutableListOf<Int>()
                        val recentArr = obj.optJSONArray("recentStreamIds")
                        if (recentArr != null) {
                            for (r in 0 until recentArr.length()) {
                                recentArr.optString(r).toIntOrNull()?.let { streamId ->
                                    if (!recentList.contains(streamId)) {
                                        recentList.add(streamId)
                                    }
                                }
                            }
                        }

                        val countsMap = mutableMapOf<Int, Int>()
                        val countsObj = obj.optJSONObject("watchCounts")
                        if (countsObj != null) {
                            val keys = countsObj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val streamId = key.toIntOrNull()
                                val count = countsObj.optInt(key, 0)
                                if (streamId != null && count > 0) {
                                    countsMap[streamId] = count
                                }
                            }
                        }

                        val progressMap = mutableMapOf<String, WatchProgressEntry>()
                        val progObj = obj.optJSONObject("watchProgress")
                        if (progObj != null) {
                            val keys = progObj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val item = progObj.optJSONObject(key) ?: continue
                                val pos = item.optLong("positionMs", 0L)
                                val dur = item.optLong("durationMs", 0L)
                                if (pos > 0L) {
                                    progressMap[key] = WatchProgressEntry(pos, dur)
                                }
                            }
                        }

                        val savedSection = ContentSection.fromName(obj.optString("lastSection"))
                        val savedCategoryId = obj.optString("lastCategoryId").takeIf { it.isNotBlank() }

                        users.add(
                            LocalUserProfile(
                                id = id,
                                username = username,
                                password = obj.optString("password"),
                                favorites = favSet,
                                playlists = plList,
                                activePlaylistId = obj.optString("activePlaylistId").ifBlank { plList.firstOrNull()?.id },
                                searchHistory = historyList,
                                recentStreamIds = recentList,
                                watchCounts = countsMap,
                                watchProgress = progressMap,
                                lastSection = savedSection,
                                lastCategoryId = savedCategoryId
                            )
                        )
                    }
                }
            }
        }

        if (users.isNotEmpty()) {
            val legacyFavs = prefs.getStringSet("favorites", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }
            if (legacyFavs.isNotEmpty() && users.first().favorites.isEmpty()) {
                users.first().favorites.addAll(legacyFavs)
                saveUsersToPrefs()
            }
        }
    }

    private fun saveUsersToPrefs() {
        val arr = JSONArray()
        users.forEach { u ->
            val favArr = JSONArray()
            u.favorites.forEach { favArr.put(it.toString()) }

            val plArr = JSONArray()
            u.playlists.forEach { p ->
                plArr.put(
                    JSONObject().apply {
                        put("id", p.id)
                        put("title", p.title)
                        put("type", p.type)
                        put("url", p.url)
                        put("username", p.username)
                        put("password", p.password)
                    }
                )
            }

            val histArr = JSONArray()
            u.searchHistory.forEach { histArr.put(it) }

            val recentArr = JSONArray()
            u.recentStreamIds.forEach { recentArr.put(it.toString()) }

            val countsObj = JSONObject()
            u.watchCounts.forEach { (streamId, count) ->
                countsObj.put(streamId.toString(), count)
            }

            val progObj = JSONObject()
            u.watchProgress.forEach { (key, entry) ->
                progObj.put(
                    key,
                    JSONObject().apply {
                        put("positionMs", entry.positionMs)
                        put("durationMs", entry.durationMs)
                    }
                )
            }

            arr.put(
                JSONObject().apply {
                    put("id", u.id)
                    put("username", u.username)
                    put("password", u.password)
                    put("favorites", favArr)
                    put("playlists", plArr)
                    put("activePlaylistId", u.activePlaylistId ?: "")
                    put("searchHistory", histArr)
                    put("recentStreamIds", recentArr)
                    put("watchCounts", countsObj)
                    put("watchProgress", progObj)
                    put("lastSection", u.lastSection.name)
                    put("lastCategoryId", u.lastCategoryId ?: "")
                }
            )
        }
        prefs.edit()
            .putString("local_users_json", arr.toString())
            .putString("active_user_id", currentUser?.id)
            .apply()
    }

    private fun showUserGateView() {
        searchJob?.cancel()
        recentSectionContainer.visibility = View.GONE
        binding.dashboardContainer.visibility = View.GONE
        binding.loginContainer.visibility = View.VISIBLE
        binding.etServerUrl.visibility = View.GONE
        binding.etUsername.visibility = View.GONE
        binding.etPassword.visibility = View.GONE

        if (users.isEmpty()) {
            binding.btnLogin.text = "Crear usuario"
            binding.btnLogin.setOnClickListener { showCreateUserDialog() }
            binding.btnSavedPlaylistsLogin.visibility = View.GONE
            binding.tvStatus.setTextColor(getColor(R.color.frost_lilac))
            binding.tvStatus.text = "Aún no hay usuarios creados. Crea tu usuario para comenzar."
        } else {
            binding.btnLogin.text = "Elegir perfil / Iniciar sesión"
            binding.btnLogin.setOnClickListener { showUserGateDialog() }
            binding.btnSavedPlaylistsLogin.visibility = View.VISIBLE
            binding.btnSavedPlaylistsLogin.text = "Crear nuevo usuario"
            binding.btnSavedPlaylistsLogin.setOnClickListener { showCreateUserDialog() }
            binding.tvStatus.setTextColor(getColor(R.color.frost_muted))
            binding.tvStatus.text = "Selecciona un perfil para continuar."
        }
        updateProfileLabel()
    }

    private fun restorePlaylistFormView(mode: String = "AUTO") {
        pendingPlaylistType = mode
        recentSectionContainer.visibility = View.GONE
        binding.dashboardContainer.visibility = View.GONE
        binding.loginContainer.visibility = View.VISIBLE

        binding.etServerUrl.visibility = View.VISIBLE
        if (binding.etServerUrl.text.toString().isBlank()) {
            binding.etServerUrl.setText("http://")
        }

        when (mode) {
            "M3U" -> {
                binding.etServerUrl.hint = "URL de lista M3U (.m3u / get.php)"
                binding.etUsername.visibility = View.GONE
                binding.etPassword.visibility = View.GONE
                binding.btnLogin.text = "Guardar y conectar M3U"
            }
            "XTREAM" -> {
                binding.etServerUrl.hint = "URL del servidor Xtream Code (http://...)"
                binding.etUsername.visibility = View.VISIBLE
                binding.etUsername.hint = "Usuario Xtream Code"
                binding.etPassword.visibility = View.VISIBLE
                binding.etPassword.hint = "Contraseña Xtream Code"
                binding.btnLogin.text = "Guardar y conectar Xtream"
            }
            else -> {
                binding.etServerUrl.hint = "URL del servidor o enlace M3U"
                binding.etUsername.visibility = View.VISIBLE
                binding.etUsername.hint = "Usuario Xtream"
                binding.etPassword.visibility = View.VISIBLE
                binding.etPassword.hint = "Contraseña Xtream"
                binding.btnLogin.text = "Conectar ahora"
            }
        }

        binding.btnLogin.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()
            connectWithCredentials(
                url = url,
                user = user,
                pass = pass,
                forcedType = pendingPlaylistType,
                saveAsPlaylist = true
            )
        }

        binding.btnSavedPlaylistsLogin.visibility = if (currentUser?.playlists?.isNotEmpty() == true) View.VISIBLE else View.GONE
        binding.btnSavedPlaylistsLogin.text = "Listas guardadas"
        binding.btnSavedPlaylistsLogin.setOnClickListener { showPlaylistsDialog() }
    }

    private fun showUserGateDialog() {
        if (users.isEmpty()) {
            showCreateUserDialog()
            return
        }
        val labels = users.map { u ->
            val lock = if (u.password.isNotBlank()) " · Con contraseña" else " · Sin contraseña"
            val plCount = " (${u.playlists.size} listas)"
            "${u.username}$lock$plCount"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Selecciona un perfil")
            .setItems(labels) { _, which ->
                val target = users[which]
                if (target.password.isNotBlank()) {
                    promptPasswordForUser(target)
                } else {
                    activateUser(target)
                }
            }
            .setPositiveButton("Crear usuario") { _, _ -> showCreateUserDialog() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun promptPasswordForUser(target: LocalUserProfile) {
        val passInput = EditText(this).apply {
            hint = "Contraseña de ${target.username}"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(getColor(R.color.white))
            setHintTextColor(getColor(R.color.frost_muted))
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(passInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        }
        AlertDialog.Builder(this)
            .setTitle("Iniciar sesión · ${target.username}")
            .setMessage("Ingresa la contraseña de este usuario.")
            .setView(wrap)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Entrar") { _, _ ->
                val entered = passInput.text.toString()
                if (entered == target.password) {
                    activateUser(target)
                } else {
                    Toast.makeText(this, "Usuario o contraseña incorrectos.", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showCreateUserDialog() {
        val userInput = EditText(this).apply {
            hint = "Nombre de usuario (requerido)"
            setSingleLine(true)
            setTextColor(getColor(R.color.white))
            setHintTextColor(getColor(R.color.frost_muted))
        }
        val passInput = EditText(this).apply {
            hint = "Contraseña (opcional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(getColor(R.color.white))
            setHintTextColor(getColor(R.color.frost_muted))
        }
        val confirmPassInput = EditText(this).apply {
            hint = "Confirmar contraseña (si pusiste una)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(getColor(R.color.white))
            setHintTextColor(getColor(R.color.frost_muted))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(userInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(passInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
            addView(confirmPassInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
        }

        AlertDialog.Builder(this)
            .setTitle("Crear usuario")
            .setMessage("Solo necesitas un nombre de usuario. La contraseña es opcional.")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Crear") { _, _ ->
                val username = userInput.text.toString().trim()
                val pass = passInput.text.toString()
                val confirm = confirmPassInput.text.toString()

                if (username.isBlank()) {
                    Toast.makeText(this, "Escribe un nombre de usuario.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (users.any { it.username.equals(username, ignoreCase = true) }) {
                    Toast.makeText(this, "Ese usuario ya existe.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (pass.isNotEmpty() && pass != confirm) {
                    Toast.makeText(this, "Las contraseñas no coinciden.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newUser = LocalUserProfile(
                    id = UUID.randomUUID().toString(),
                    username = username,
                    password = pass
                )
                users.add(newUser)
                saveUsersToPrefs()
                activateUser(newUser)
                Toast.makeText(this, "Usuario $username creado.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun activateUser(user: LocalUserProfile) {
        currentUser = user
        prefs.edit().putString("active_user_id", user.id).apply()
        updateProfileLabel()

        val activePlaylist = user.playlists.firstOrNull { it.id == user.activePlaylistId }
            ?: user.playlists.firstOrNull()

        if (activePlaylist != null) {
            user.activePlaylistId = activePlaylist.id
            saveUsersToPrefs()
            binding.etServerUrl.setText(activePlaylist.url)
            binding.etUsername.setText(activePlaylist.username)
            binding.etPassword.setText(activePlaylist.password)
            connectWithCredentials(
                url = activePlaylist.url,
                user = activePlaylist.username,
                pass = activePlaylist.password,
                forcedType = activePlaylist.type,
                saveAsPlaylist = false,
                existingId = activePlaylist.id,
                customTitle = activePlaylist.title
            )
        } else {
            streams = emptyList()
            categories = emptyList()
            categoryNameById = emptyMap()
            directStreamUrls = emptyMap()
            selectedSection = user.lastSection
            selectedCategory = user.lastCategoryId
            binding.loginContainer.visibility = View.GONE
            binding.dashboardContainer.visibility = View.VISIBLE
            binding.tvConnectionInfo.text = "Perfil: ${user.username} · Sin listas guardadas · Toca 'Listas' para agregar"
            binding.tvChannelCount.text = "0"
            binding.tvFavoriteCount.text = user.favorites.size.toString()
            renderCategories()
            renderSearchHistory()
            renderRecentAndMostWatched()
            scheduleChannelFilter(immediate = true)
        }
    }

    private fun connectWithCredentials(
        url: String,
        user: String,
        pass: String,
        forcedType: String = "AUTO",
        saveAsPlaylist: Boolean,
        existingId: String? = null,
        customTitle: String? = null
    ) {
        val activeUser = currentUser
        if (activeUser == null) {
            showUserGateDialog()
            return
        }

        val resolvedType = when (forcedType) {
            "M3U", "XTREAM" -> forcedType
            else -> if (url.contains(".m3u", ignoreCase = true) || url.contains("get.php", ignoreCase = true)) "M3U" else "XTREAM"
        }

        val isM3u = resolvedType == "M3U"

        if (url.isBlank() || url.equals("http://", ignoreCase = true) || (!isM3u && (user.isBlank() || pass.isBlank()))) {
            binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
            binding.tvStatus.text = if (isM3u) {
                "Ingresa una URL válida de lista M3U."
            } else {
                "Completa la URL del servidor, usuario y contraseña Xtream."
            }
            return
        }

        binding.btnLogin.isEnabled = false
        binding.btnLogin.text = "Conectando…"
        binding.tvStatus.setTextColor(getColor(android.R.color.white))
        binding.tvStatus.text = "Estamos preparando tu contenido…"

        lifecycleScope.launch {
            try {
                if (isM3u) {
                    val parsed = withContext(Dispatchers.IO) {
                        val base = url.substringBeforeLast("/") + "/"
                        val api = XtreamApiService.create(base)
                        parseM3u(api.downloadPlaylist(url).string())
                    }
                    if (parsed.isEmpty()) {
                        binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                        binding.tvStatus.text = "No encontramos canales en esta lista. Revisa el enlace."
                    } else {
                        val mappedStreams = parsed.map { it.first }
                        val mappedUrls = parsed.associate { it.first.streamId to it.second }
                        val mappedCategories = withContext(Dispatchers.Default) {
                            mappedStreams.mapNotNull { it.categoryId }
                                .distinct()
                                .map { LiveCategory(it, it) }
                        }
                        streams = mappedStreams
                        directStreamUrls = mappedUrls
                        categories = mappedCategories
                        rebuildCategoryLookup()
                        serverUrl = url
                        xtreamUser = ""
                        xtreamPassword = ""
                        if (saveAsPlaylist) {
                            upsertSavedPlaylist(
                                id = existingId,
                                title = customTitle ?: suggestPlaylistTitle(url, "", "M3U"),
                                type = "M3U",
                                url = url,
                                username = "",
                                password = ""
                            )
                        }
                        showDashboard("Lista M3U conectada")
                    }
                } else {
                    val base = url.trimEnd('/') + "/"
                    val api = XtreamApiService.create(base)
                    val auth = api.authenticate(user, pass)
                    if (auth.userInfo?.status.equals("Active", ignoreCase = true)) {
                        val loaded = withContext(Dispatchers.IO) {
                            val liveCats = runCatching { api.getLiveCategories(user, pass) }.getOrDefault(emptyList())
                            val vodCats = runCatching { api.getVodCategories(user, pass) }.getOrDefault(emptyList())
                            val seriesCats = runCatching { api.getSeriesCategories(user, pass) }.getOrDefault(emptyList())

                            val liveChannels = runCatching { api.getLiveStreams(user, pass) }.getOrDefault(emptyList())
                                .map { it.copy(mediaType = "LIVE") }

                            val vodItems = runCatching { api.getVodStreams(user, pass) }.getOrDefault(emptyList())
                                .mapIndexed { idx, vod ->
                                    LiveStream(
                                        streamId = 1_000_000 + vod.streamId + idx,
                                        name = vod.name,
                                        streamIcon = vod.streamIcon,
                                        categoryId = vod.categoryId,
                                        containerExtension = vod.containerExtension ?: "mp4",
                                        mediaType = "VOD"
                                    )
                                }

                            val seriesItems = runCatching { api.getSeries(user, pass) }.getOrDefault(emptyList())
                                .mapIndexed { idx, s ->
                                    LiveStream(
                                        streamId = 2_000_000 + s.seriesId + idx,
                                        name = s.name,
                                        streamIcon = s.cover,
                                        categoryId = s.categoryId,
                                        mediaType = "SERIES",
                                        seriesId = s.seriesId
                                    )
                                }

                            val allCats = (liveCats + vodCats + seriesCats).distinctBy { it.categoryId }
                            allCats to (liveChannels + vodItems + seriesItems)
                        }
                        categories = loaded.first
                        rebuildCategoryLookup()
                        streams = loaded.second
                        directStreamUrls = emptyMap()
                        serverUrl = url
                        xtreamUser = user
                        xtreamPassword = pass
                        if (saveAsPlaylist) {
                            upsertSavedPlaylist(
                                id = existingId,
                                title = customTitle ?: suggestPlaylistTitle(url, user, "XTREAM"),
                                type = "XTREAM",
                                url = url,
                                username = user,
                                password = pass
                            )
                        }
                        showDashboard("Conexión Xtream activa")
                    } else {
                        binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                        binding.tvStatus.text = "La cuenta está inactiva o las credenciales no son válidas."
                    }
                }
            } catch (e: Exception) {
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                binding.tvStatus.text = "No se pudo conectar: ${e.localizedMessage ?: "error desconocido"}"
            } finally {
                binding.btnLogin.isEnabled = true
                binding.btnLogin.text = when (pendingPlaylistType) {
                    "M3U" -> "Guardar y conectar M3U"
                    "XTREAM" -> "Guardar y conectar Xtream"
                    else -> "Conectar ahora"
                }
            }
        }
    }

    private fun suggestPlaylistTitle(url: String, user: String, type: String): String {
        val host = url.removePrefix("https://").removePrefix("http://").substringBefore("/").ifBlank { type }
        return if (type == "XTREAM" && user.isNotBlank()) "$host ($user)" else host
    }

    private fun upsertSavedPlaylist(
        id: String?,
        title: String,
        type: String,
        url: String,
        username: String,
        password: String
    ) {
        val userProfile = currentUser ?: return
        val targetId = id ?: userProfile.playlists.firstOrNull {
            it.url.equals(url, ignoreCase = true) && it.username == username
        }?.id ?: UUID.randomUUID().toString()

        val entry = SavedPlaylist(
            id = targetId,
            title = title.ifBlank { suggestPlaylistTitle(url, username, type) },
            type = type,
            url = url,
            username = username,
            password = password
        )
        userProfile.playlists.removeAll { it.id == targetId }
        userProfile.playlists.add(0, entry)
        userProfile.activePlaylistId = targetId
        saveUsersToPrefs()
    }

    private fun showPlaylistsDialog() {
        val userProfile = currentUser
        if (userProfile == null) {
            showUserGateDialog()
            return
        }

        val options = mutableListOf<String>()
        options.add("Agregar lista (Xtream Code)")
        options.add("Agregar lista (M3U)")
        userProfile.playlists.forEach { item ->
            val marker = if (item.id == userProfile.activePlaylistId) "✓ " else ""
            options.add("$marker${item.title} · ${item.type}")
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Listas de ${userProfile.username}")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showAddPlaylistDialog("XTREAM")
                    1 -> showAddPlaylistDialog("M3U")
                    else -> {
                        val chosen = userProfile.playlists[which - 2]
                        userProfile.activePlaylistId = chosen.id
                        saveUsersToPrefs()
                        binding.etServerUrl.setText(chosen.url)
                        binding.etUsername.setText(chosen.username)
                        binding.etPassword.setText(chosen.password)
                        connectWithCredentials(
                            url = chosen.url,
                            user = chosen.username,
                            pass = chosen.password,
                            forcedType = chosen.type,
                            saveAsPlaylist = false,
                            existingId = chosen.id,
                            customTitle = chosen.title
                        )
                    }
                }
            }
            .setNeutralButton("Cerrar", null)

        if (userProfile.playlists.isNotEmpty()) {
            builder.setNegativeButton("Eliminar activa") { _, _ ->
                val target = userProfile.playlists.firstOrNull { it.id == userProfile.activePlaylistId }
                    ?: userProfile.playlists.firstOrNull()
                if (target != null) {
                    userProfile.playlists.removeAll { it.id == target.id }
                    userProfile.activePlaylistId = userProfile.playlists.firstOrNull()?.id
                    saveUsersToPrefs()
                    Toast.makeText(this, "Lista eliminada", Toast.LENGTH_SHORT).show()
                    if (userProfile.playlists.isEmpty()) {
                        disconnect(clearActivePreference = true)
                    }
                }
            }
        }
        builder.show()
    }

    private fun showAddPlaylistDialog(type: String) {
        val isXtream = type == "XTREAM"
        val nameInput = EditText(this).apply {
            hint = "Nombre de la lista (opcional)"
            setSingleLine(true)
            setTextColor(getColor(R.color.white))
            setHintTextColor(getColor(R.color.frost_muted))
        }
        val urlInput = EditText(this).apply {
            hint = if (isXtream) "URL servidor Xtream (http://...)" else "URL de lista M3U (.m3u / get.php)"
            setText("http://")
            setSingleLine(true)
            setTextColor(getColor(R.color.white))
            setHintTextColor(getColor(R.color.frost_muted))
        }
        val userInput = EditText(this).apply {
            hint = "Usuario Xtream Code"
            setSingleLine(true)
            setTextColor(getColor(R.color.white))
            setHintTextColor(getColor(R.color.frost_muted))
        }
        val passInput = EditText(this).apply {
            hint = "Contraseña Xtream Code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(getColor(R.color.white))
            setHintTextColor(getColor(R.color.frost_muted))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(nameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(urlInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
            if (isXtream) {
                addView(userInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
                addView(passInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
            }
        }

        AlertDialog.Builder(this)
            .setTitle(if (isXtream) "Agregar lista (Xtream Code)" else "Agregar lista (M3U)")
            .setMessage(
                if (isXtream) "Ingresa los datos del servidor Xtream Code."
                else "Ingresa el enlace directo de tu lista M3U."
            )
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Conectar y guardar") { _, _ ->
                val title = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                val user = if (isXtream) userInput.text.toString().trim() else ""
                val pass = if (isXtream) passInput.text.toString().trim() else ""

                binding.loginContainer.visibility = View.VISIBLE
                binding.dashboardContainer.visibility = View.GONE
                restorePlaylistFormView(type)
                binding.etServerUrl.setText(url)
                binding.etUsername.setText(user)
                binding.etPassword.setText(pass)

                connectWithCredentials(
                    url = url,
                    user = user,
                    pass = pass,
                    forcedType = type,
                    saveAsPlaylist = true,
                    customTitle = title.ifBlank { null }
                )
            }
            .show()
    }

    private fun showUserMenuDialog() {
        val active = currentUser
        if (active == null) {
            showUserGateDialog()
            return
        }

        val dialogBuilder = AlertDialog.Builder(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(getColor(R.color.frost_card))
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(),
                    dp(20).toFloat(), dp(20).toFloat(),
                    dp(28).toFloat(), dp(28).toFloat(),
                    dp(28).toFloat(), dp(28).toFloat()
                )
            }
            setPadding(dp(18), dp(14), dp(18), dp(12))
        }

        val headerCard = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.frost_purple))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
        val headerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val tvProfileTitle = TextView(this).apply {
            text = active.username
            textSize = 16f
            setTextColor(getColor(R.color.white))
            setTypeface(typeface, Typeface.BOLD)
        }
        val activePlaylistTitle = active.playlists.firstOrNull { it.id == active.activePlaylistId }?.title
            ?: "Sin lista activa"
        val tvProfileSub = TextView(this).apply {
            text = "${active.playlists.size} listas · ${active.favorites.size} favoritos · $activePlaylistTitle"
            textSize = 11f
            setTextColor(getColor(R.color.white))
            setPadding(0, dp(3), 0, 0)
        }
        headerContent.addView(tvProfileTitle)
        headerContent.addView(tvProfileSub)
        headerCard.addView(headerContent)
        root.addView(headerCard)

        var activeDialog: AlertDialog? = null

        fun addSectionHeader(label: String) {
            val tv = TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(getColor(R.color.frost_lilac))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }
            root.addView(tv)
        }

        fun addGroupedCard(items: List<Triple<String, String, () -> Unit>>) {
            val groupCard = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(getColor(R.color.frost_card))
                strokeWidth = dp(1)
                strokeColor = getColor(R.color.frost_border)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            }
            val listCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            items.forEachIndexed { index, (title, subtitle, action) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setOnClickListener {
                        activeDialog?.dismiss()
                        action()
                    }
                }
                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val tvTitle = TextView(this).apply {
                    text = title
                    textSize = 13f
                    setTextColor(getColor(R.color.white))
                    setTypeface(typeface, Typeface.BOLD)
                }
                val tvSub = TextView(this).apply {
                    text = subtitle
                    textSize = 11f
                    setTextColor(getColor(R.color.frost_muted))
                    setPadding(0, dp(2), 0, 0)
                }
                textCol.addView(tvTitle)
                textCol.addView(tvSub)

                val tvArrow = TextView(this).apply {
                    text = "›"
                    textSize = 18f
                    setTextColor(getColor(R.color.frost_muted))
                }

                row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(tvArrow)
                listCol.addView(row)

                if (index < items.lastIndex) {
                    val divider = View(this).apply {
                        setBackgroundColor(getColor(R.color.frost_border))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    }
                    listCol.addView(divider)
                }
            }

            groupCard.addView(listCol)
            root.addView(groupCard)
        }

        addSectionHeader("CUENTA Y PERFILES")
        addGroupedCard(
            listOf(
                Triple("Cambiar de perfil", "Alternar entre perfiles locales") { showUserGateDialog() },
                Triple("Crear nuevo usuario", "Añadir otro perfil con su propia configuración") { showCreateUserDialog() },
                Triple(
                    if (active.password.isBlank()) "Establecer contraseña" else "Cambiar contraseña",
                    if (active.password.isBlank()) "Sin contraseña actualmente" else "Protegido con contraseña"
                ) { showChangePasswordDialog(active) }
            )
        )

        addSectionHeader("LISTAS Y SESIÓN")
        addGroupedCard(
            listOf(
                Triple("Gestionar listas", "Ver listas guardadas o cambiar lista activa") { showPlaylistsDialog() },
                Triple("Agregar lista Xtream Code", "Conectar servidor con usuario y contraseña") { showAddPlaylistDialog("XTREAM") },
                Triple("Agregar lista M3U", "Conectar enlace directo de lista M3U") { showAddPlaylistDialog("M3U") },
                Triple("Cerrar sesión", "Salir del perfil ${active.username}") {
                    currentUser = null
                    prefs.edit().remove("active_user_id").apply()
                    showUserGateView()
                }
            )
        )

        val closeTextAction = TextView(this).apply {
            text = "Cerrar"
            textSize = 13f
            setTextColor(getColor(R.color.frost_lilac))
            setTypeface(typeface, Typeface.BOLD)
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(8), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
            }
            setOnClickListener { activeDialog?.dismiss() }
        }
        root.addView(closeTextAction)

        activeDialog = dialogBuilder
            .setView(root)
            .create()
        activeDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        activeDialog.show()
    }

    private fun showChangePasswordDialog(userProfile: LocalUserProfile) {
        val currentPassInput = EditText(this).apply {
            hint = "Contraseña actual"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(getColor(R.color.white))
            setHintTextColor(getColor(R.color.frost_muted))
        }
        val newPassInput = EditText(this).apply {
            hint = "Nueva contraseña (dejar vacío para quitarla)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(getColor(R.color.white))
            setHintTextColor(getColor(R.color.frost_muted))
        }
        val confirmPassInput = EditText(this).apply {
            hint = "Confirmar nueva contraseña"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(getColor(R.color.white))
            setHintTextColor(getColor(R.color.frost_muted))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), 0)
            if (userProfile.password.isNotBlank()) {
                addView(currentPassInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
                addView(newPassInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
            } else {
                addView(newPassInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            }
            addView(confirmPassInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
        }

        AlertDialog.Builder(this)
            .setTitle("Contraseña · ${userProfile.username}")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                if (userProfile.password.isNotBlank() && currentPassInput.text.toString() != userProfile.password) {
                    Toast.makeText(this, "La contraseña actual no coincide.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newPass = newPassInput.text.toString()
                val confirm = confirmPassInput.text.toString()
                if (newPass != confirm) {
                    Toast.makeText(this, "Las contraseñas no coinciden.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                userProfile.password = newPass
                saveUsersToPrefs()
                Toast.makeText(this, "Contraseña actualizada.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showDashboard(statusMessage: String) {
        val user = currentUser
        val restoredSection = user?.lastSection ?: ContentSection.ALL
        val restoredCategoryId = user?.lastCategoryId?.takeIf { catId ->
            categories.any { it.categoryId == catId }
        }
        selectedSection = if (restoredCategoryId != null) ContentSection.ALL else restoredSection
        selectedCategory = restoredCategoryId
        showingFavorites = false

        binding.loginContainer.visibility = View.GONE
        binding.dashboardContainer.visibility = View.VISIBLE
        binding.tvStatus.text = ""
        val profileLabel = user?.username?.let { "Perfil: $it · " } ?: ""
        binding.tvConnectionInfo.text = "$profileLabel$statusMessage"
        binding.tvChannelCount.text = streams.size.toString()
        binding.tvFavoriteCount.text = (user?.favorites?.size ?: 0).toString()

        binding.tvSectionTitle.text = if (selectedCategory != null) {
            resolveCategoryName(selectedCategory)
        } else {
            selectedSection.label
        }

        updateNavigation(if (selectedSection == ContentSection.LIVE) "live" else "home")
        renderCategories()
        renderSearchHistory()
        renderRecentAndMostWatched()
        scheduleChannelFilter(immediate = true)
    }

    private fun updateNavigation(target: String) {
        activeNav = target
        val activeColor = getColor(R.color.frost_lilac)
        val inactiveColor = getColor(R.color.frost_muted)
        binding.btnNavHome.setTextColor(if (target == "home") activeColor else inactiveColor)
        binding.btnNavLive.setTextColor(if (target == "live") activeColor else inactiveColor)
        binding.btnNavFavorites.setTextColor(if (target == "favorites") activeColor else inactiveColor)
        binding.btnNavProfile.setTextColor(if (target == "profile") activeColor else inactiveColor)
    }

    private fun classifySection(stream: LiveStream): ContentSection {
        if (stream.mediaType.equals("VOD", ignoreCase = true)) return ContentSection.MOVIES
        if (stream.mediaType.equals("SERIES", ignoreCase = true)) return ContentSection.SERIES

        val catName = resolveCategoryName(stream.categoryId)
        val combined = "${stream.name} $catName ${stream.categoryId.orEmpty()}".lowercase(Locale.ROOT)

        return when {
            combined.contains("sport") || combined.contains("deport") || combined.contains("futbol") ||
                combined.contains("fútbol") || combined.contains("soccer") || combined.contains("liga") ||
                combined.contains("nba") || combined.contains("ufc") || combined.contains("espn") ||
                combined.contains("fox sports") || combined.contains("dazn") || combined.contains("gol") -> ContentSection.SPORTS
            combined.contains("serie") || combined.contains("temporada") || combined.contains("season") ||
                combined.contains("episod") || combined.contains("novela") || combined.contains("anime") -> ContentSection.SERIES
            combined.contains("pelicul") || combined.contains("movie") || combined.contains("cine") ||
                combined.contains("vod") || combined.contains("film") || combined.contains("estreno") -> ContentSection.MOVIES
            else -> ContentSection.LIVE
        }
    }

    private fun renderCategories() {
        binding.categoryContainer.removeAllViews()

        fun addSectionChip(section: ContentSection) {
            val isSelected = !showingFavorites && selectedCategory == null && selectedSection == section
            val chip = MaterialButton(this).apply {
                text = section.label
                isAllCaps = false
                textSize = 11f
                cornerRadius = dp(18)
                minHeight = dp(36)
                minimumHeight = dp(36)
                insetTop = 0
                insetBottom = 0
                setPadding(dp(14), 0, dp(14), 0)
                backgroundTintList = ColorStateList.valueOf(
                    getColor(if (isSelected) R.color.frost_purple else R.color.frost_card)
                )
                setTextColor(getColor(if (isSelected) R.color.white else R.color.frost_muted))
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(
                    getColor(if (isSelected) R.color.frost_lilac else R.color.frost_border)
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(36)
                ).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener {
                    showingFavorites = false
                    selectedSection = section
                    selectedCategory = null
                    persistUserViewState()
                    updateNavigation(if (section == ContentSection.LIVE) "live" else "home")
                    binding.tvSectionTitle.text = section.label
                    renderCategories()
                    scheduleChannelFilter(immediate = true)
                }
            }
            binding.categoryContainer.addView(chip)
        }

        fun addCategoryChip(label: String, categoryId: String) {
            val isSelected = !showingFavorites && selectedCategory == categoryId
            val chip = MaterialButton(this).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                cornerRadius = dp(18)
                minHeight = dp(36)
                minimumHeight = dp(36)
                insetTop = 0
                insetBottom = 0
                setPadding(dp(14), 0, dp(14), 0)
                backgroundTintList = ColorStateList.valueOf(
                    getColor(if (isSelected) R.color.frost_purple else R.color.frost_card)
                )
                setTextColor(getColor(if (isSelected) R.color.white else R.color.frost_muted))
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(
                    getColor(if (isSelected) R.color.frost_lilac else R.color.frost_border)
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(36)
                ).apply {
                    marginEnd = dp(8)
                }
                setOnClickListener {
                    showingFavorites = false
                    selectedCategory = categoryId
                    persistUserViewState()
                    updateNavigation("home")
                    binding.tvSectionTitle.text = label
                    renderCategories()
                    scheduleChannelFilter(immediate = true)
                }
            }
            binding.categoryContainer.addView(chip)
        }

        ContentSection.entries.forEach { addSectionChip(it) }
        categories.take(35).forEach { cat ->
            addCategoryChip(cat.categoryName, cat.categoryId)
        }
    }

    private fun recordWatchEvent(stream: LiveStream) {
        val user = currentUser ?: return
        user.recentStreamIds.removeAll { it == stream.streamId }
        user.recentStreamIds.add(0, stream.streamId)
        while (user.recentStreamIds.size > 15) {
            user.recentStreamIds.removeAt(user.recentStreamIds.lastIndex)
        }
        val nextCount = (user.watchCounts[stream.streamId] ?: 0) + 1
        user.watchCounts[stream.streamId] = nextCount
        saveUsersToPrefs()
        renderRecentAndMostWatched()
    }

    private fun renderRecentAndMostWatched() {
        if (!::binding.isInitialized || !::recentSectionContainer.isInitialized) return
        if (binding.dashboardContainer.visibility != View.VISIBLE) {
            recentSectionContainer.visibility = View.GONE
            return
        }

        val user = currentUser
        if (user == null || streams.isEmpty()) {
            recentSectionContainer.visibility = View.GONE
            return
        }

        val streamById = streams.associateBy { it.streamId }
        val recentItems = user.recentStreamIds.mapNotNull { streamById[it] }.take(8)
        val mostWatchedItems = user.watchCounts.entries
            .sortedByDescending { it.value }
            .mapNotNull { (id, count) -> streamById[id]?.let { it to count } }
            .take(5)

        recentSectionContainer.removeAllViews()
        if (recentItems.isEmpty() && mostWatchedItems.isEmpty()) {
            recentSectionContainer.visibility = View.GONE
            return
        }

        recentSectionContainer.visibility = View.VISIBLE

        if (recentItems.isNotEmpty()) {
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(2), dp(2), dp(6))
            }
            val title = TextView(this).apply {
                text = "Recientes"
                textSize = 12f
                setTextColor(getColor(R.color.white))
                setTypeface(typeface, Typeface.BOLD)
            }
            val btnClear = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "Limpiar"
                isAllCaps = false
                textSize = 11f
                minHeight = dp(30)
                minimumHeight = dp(30)
                insetTop = 0
                insetBottom = 0
                setPadding(dp(6), 0, dp(2), 0)
                setTextColor(getColor(R.color.frost_lilac))
                setOnClickListener {
                    user.recentStreamIds.clear()
                    saveUsersToPrefs()
                    renderRecentAndMostWatched()
                }
            }
            header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(btnClear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))
            recentSectionContainer.addView(header)

            val chipsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val scroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chipsRow)
            }

            recentItems.forEach { item ->
                val card = MaterialCardView(this).apply {
                    radius = dp(14).toFloat()
                    cardElevation = 0f
                    setCardBackgroundColor(getColor(R.color.frost_card))
                    strokeWidth = dp(1)
                    strokeColor = getColor(R.color.frost_border)
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = dp(8)
                    }
                    setOnClickListener { onStreamItemSelected(item) }
                }
                val tv = TextView(this).apply {
                    text = "▶ ${item.name}"
                    textSize = 11f
                    maxLines = 1
                    setTextColor(getColor(R.color.white))
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                card.addView(tv)
                chipsRow.addView(card)
            }
            recentSectionContainer.addView(scroll)
        }

        if (mostWatchedItems.isNotEmpty()) {
            val topTitle = TextView(this).apply {
                text = "Más vistos"
                textSize = 11f
                setTextColor(getColor(R.color.frost_muted))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(2), dp(10), dp(2), dp(6))
            }
            recentSectionContainer.addView(topTitle)

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val topScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(topRow)
            }

            mostWatchedItems.forEach { (item, count) ->
                val card = MaterialCardView(this).apply {
                    radius = dp(14).toFloat()
                    cardElevation = 0f
                    setCardBackgroundColor(getColor(R.color.frost_card))
                    strokeWidth = dp(1)
                    strokeColor = getColor(R.color.frost_border)
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = dp(8)
                    }
                    setOnClickListener { onStreamItemSelected(item) }
                }
                val tv = TextView(this).apply {
                    text = "${item.name} · ${count}x"
                    textSize = 11f
                    maxLines = 1
                    setTextColor(getColor(R.color.frost_lilac))
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                }
                card.addView(tv)
                topRow.addView(card)
            }
            recentSectionContainer.addView(topScroll)
        }
    }

    private fun recordSearchTerm(rawQuery: String) {
        val user = currentUser ?: return
        val clean = rawQuery.trim()
        if (clean.length < 2) return

        user.searchHistory.removeAll { it.equals(clean, ignoreCase = true) }
        user.searchHistory.add(0, clean)
        while (user.searchHistory.size > 12) {
            user.searchHistory.removeAt(user.searchHistory.lastIndex)
        }
        saveUsersToPrefs()
        renderSearchHistory()
    }

    private fun deleteSearchTerm(term: String) {
        val user = currentUser ?: return
        user.searchHistory.removeAll { it.equals(term, ignoreCase = true) }
        saveUsersToPrefs()
        renderSearchHistory()
    }

    private fun confirmClearSearchHistory() {
        val user = currentUser ?: return
        if (user.searchHistory.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("Eliminar historial")
            .setMessage("¿Deseas eliminar todo el historial de búsquedas de ${user.username}?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Confirmar") { _, _ ->
                user.searchHistory.clear()
                saveUsersToPrefs()
                renderSearchHistory()
            }
            .show()
    }

    private fun renderSearchHistory() {
        if (!::binding.isInitialized || binding.dashboardContainer.visibility != View.VISIBLE) return
        val history = currentUser?.searchHistory.orEmpty()
        val currentQuery = binding.etSearch.text?.toString()?.trim().orEmpty()

        binding.searchHistoryContainer.removeAllViews()
        if (history.isEmpty() || currentQuery.isNotEmpty()) {
            binding.searchHistoryContainer.visibility = View.GONE
            return
        }

        binding.searchHistoryContainer.visibility = View.VISIBLE

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(6))
        }
        val lblHistory = TextView(this).apply {
            text = "Búsquedas recientes"
            textSize = 11f
            setTextColor(getColor(R.color.frost_muted))
            setTypeface(typeface, Typeface.BOLD)
        }
        val btnClearAll = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Eliminar todo"
            isAllCaps = false
            textSize = 11f
            minHeight = dp(32)
            minimumHeight = dp(32)
            insetTop = 0
            insetBottom = 0
            setPadding(dp(8), 0, dp(4), 0)
            setTextColor(getColor(R.color.frost_lilac))
            setOnClickListener { confirmClearSearchHistory() }
        }
        headerRow.addView(lblHistory, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(btnClearAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
        binding.searchHistoryContainer.addView(headerRow)

        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val horizontalScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        }

        history.take(8).forEach { term ->
            val itemCard = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(getColor(R.color.frost_card))
                strokeWidth = dp(1)
                strokeColor = getColor(R.color.frost_border)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(34)
                ).apply {
                    marginEnd = dp(8)
                }
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(6), 0)
            }
            val tvTerm = TextView(this).apply {
                text = term
                textSize = 11f
                setTextColor(getColor(R.color.white))
                setOnClickListener {
                    binding.etSearch.setText(term)
                    binding.etSearch.setSelection(term.length)
                    scheduleChannelFilter(immediate = true)
                }
            }
            val btnRemove = TextView(this).apply {
                text = " ×"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.frost_muted))
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { deleteSearchTerm(term) }
            }
            inner.addView(tvTerm)
            inner.addView(btnRemove)
            itemCard.addView(inner)
            chipsRow.addView(itemCard)
        }

        binding.searchHistoryContainer.addView(horizontalScroll)
    }

    private fun scheduleChannelFilter(immediate: Boolean = false) {
        if (!::binding.isInitialized || binding.dashboardContainer.visibility != View.VISIBLE) return
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            if (!immediate) {
                delay(200L)
            }
            renderChannels()
        }
    }

    private fun renderChannels() {
        if (!::binding.isInitialized || binding.dashboardContainer.visibility != View.VISIBLE) return
        val favs = currentUser?.favorites?.toSet() ?: emptySet()
        val query = binding.etSearch.text?.toString()?.trim().orEmpty()
        val sectionFilter = selectedSection
        val catFilter = selectedCategory
        val favOnly = showingFavorites
        val snapshot = streams
        val categoryLookup = categoryNameById

        lifecycleScope.launch {
            val visible = withContext(Dispatchers.Default) {
                val lowerQuery = query.lowercase(Locale.ROOT)
                snapshot.filter { stream ->
                    val catName = stream.categoryId?.let { categoryLookup[it] }.orEmpty()
                    val matchesSearch = lowerQuery.isEmpty() ||
                        stream.name.lowercase(Locale.ROOT).contains(lowerQuery) ||
                        catName.lowercase(Locale.ROOT).contains(lowerQuery) ||
                        (stream.categoryId?.lowercase(Locale.ROOT)?.contains(lowerQuery) == true)

                    val isGlobalSearch = lowerQuery.isNotEmpty() &&
                        sectionFilter == ContentSection.ALL &&
                        catFilter == null &&
                        !favOnly

                    if (isGlobalSearch) {
                        matchesSearch
                    } else {
                        val matchesSection = catFilter != null || favOnly ||
                            sectionFilter == ContentSection.ALL || classifySection(stream) == sectionFilter
                        val matchesCategory = catFilter == null || stream.categoryId == catFilter
                        val matchesFavorites = !favOnly || favs.contains(stream.streamId)
                        matchesSearch && matchesSection && matchesCategory && matchesFavorites
                    }
                }
            }

            binding.channelsContainer.visibility = View.GONE
            binding.tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
            binding.tvEmpty.text = when {
                showingFavorites -> "Aún no has guardado canales.\nPulsa ☆ en un canal para añadirlo aquí."
                query.isNotEmpty() -> "No encontramos programas ni canales con “$query”."
                else -> "No hay canales en esta sección o categoría."
            }

            channelsAdapter.setFavorites(favs)
            channelsAdapter.submitList(visible)
            binding.tvFavoriteCount.text = favs.size.toString()
        }
    }

    private fun onStreamItemSelected(stream: LiveStream) {
        if (stream.mediaType.equals("SERIES", ignoreCase = true) && stream.seriesId != null &&
            serverUrl.isNotBlank() && xtreamUser.isNotBlank() && xtreamPassword.isNotBlank()
        ) {
            openSeriesEpisodesDialog(stream)
            return
        }

        val streamUrl = directStreamUrls[stream.streamId]
            ?.takeIf { it.isNotBlank() }
            ?: buildXtreamStreamUrl(stream)

        if (streamUrl.isBlank()) {
            Toast.makeText(this, "No se encontró una URL válida para este elemento.", Toast.LENGTH_SHORT).show()
            return
        }

        val isVodOrSeries = stream.mediaType.equals("VOD", ignoreCase = true) ||
            classifySection(stream) == ContentSection.MOVIES ||
            classifySection(stream) == ContentSection.SERIES

        launchPlayerWithQueue(
            title = stream.name,
            urls = listOf(streamUrl),
            titles = listOf(stream.name),
            startIndex = 0,
            isSeekableVod = isVodOrSeries,
            trackedStream = stream
        )
    }

    private fun openSeriesEpisodesDialog(seriesItem: LiveStream) {
        val seriesId = seriesItem.seriesId ?: return
        val base = serverUrl.trimEnd('/') + "/"
        val api = XtreamApiService.create(base)

        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    api.getSeriesInfo(xtreamUser, xtreamPassword, seriesId)
                }
                val seasonsMap = info.episodesBySeason.orEmpty()
                if (seasonsMap.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Esta serie no tiene capítulos disponibles.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                data class EpisodeEntry(
                    val seasonLabel: String,
                    val episodeLabel: String,
                    val url: String
                )

                val allEpisodes = mutableListOf<EpisodeEntry>()
                seasonsMap.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }.forEach { seasonKey ->
                    val epList = seasonsMap[seasonKey].orEmpty()
                        .sortedBy { it.episodeNum ?: Int.MAX_VALUE }
                    epList.forEachIndexed { idx, ep ->
                        val epId = ep.id?.trim().orEmpty()
                        if (epId.isNotEmpty()) {
                            val ext = ep.containerExtension?.trim()?.takeIf { it.isNotEmpty() } ?: "mp4"
                            val epUrl = "${serverUrl.trimEnd('/')}/series/$xtreamUser/$xtreamPassword/$epId.$ext"
                            val sNum = ep.season ?: seasonKey.toIntOrNull() ?: 1
                            val eNum = ep.episodeNum ?: (idx + 1)
                            val epTitle = ep.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Capítulo $eNum"
                            allEpisodes.add(
                                EpisodeEntry(
                                    seasonLabel = "T$sNum · E$eNum",
                                    episodeLabel = "T$sNum · E$eNum — $epTitle",
                                    url = epUrl
                                )
                            )
                        }
                    }
                }

                if (allEpisodes.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No se encontraron enlaces de capítulos.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val labels = allEpisodes.map { it.episodeLabel }.toTypedArray()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(seriesItem.name)
                    .setItems(labels) { _, which ->
                        launchPlayerWithQueue(
                            title = allEpisodes[which].episodeLabel,
                            urls = allEpisodes.map { it.url },
                            titles = allEpisodes.map { "${seriesItem.name} · ${it.episodeLabel}" },
                            startIndex = which,
                            isSeekableVod = true,
                            trackedStream = seriesItem
                        )
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "No se pudieron cargar los capítulos: ${e.localizedMessage ?: "error"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun launchPlayerWithQueue(
        title: String,
        urls: List<String>,
        titles: List<String>,
        startIndex: Int,
        isSeekableVod: Boolean,
        trackedStream: LiveStream?
    ) {
        if (trackedStream != null) {
            recordWatchEvent(trackedStream)
        }
        val activeUrl = urls.getOrNull(startIndex).orEmpty()
        val savedPos = currentUser?.watchProgress?.get(activeUrl)?.positionMs ?: 0L

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("EXTRA_STREAM_URL", activeUrl)
            putExtra("EXTRA_CHANNEL_NAME", title)
            putStringArrayListExtra("EXTRA_EPISODE_URLS", ArrayList(urls))
            putStringArrayListExtra("EXTRA_EPISODE_TITLES", ArrayList(titles))
            putExtra("EXTRA_EPISODE_INDEX", startIndex)
            putExtra("EXTRA_IS_VOD", isSeekableVod)
            putExtra("EXTRA_RESUME_POSITION_MS", savedPos)
            putExtra("EXTRA_USER_ID", currentUser?.id ?: "")
        }
        startActivity(intent)
    }

    private fun buildXtreamStreamUrl(stream: LiveStream): String {
        if (serverUrl.isBlank() || xtreamUser.isBlank() || xtreamPassword.isBlank()) return ""
        val base = serverUrl.trimEnd('/')
        val ext = stream.containerExtension?.trim()?.takeIf { it.isNotEmpty() } ?: "mp4"
        return when {
            stream.mediaType.equals("VOD", ignoreCase = true) || classifySection(stream) == ContentSection.MOVIES ->
                "$base/movie/$xtreamUser/$xtreamPassword/${stream.streamId}.$ext"
            stream.mediaType.equals("SERIES", ignoreCase = true) || classifySection(stream) == ContentSection.SERIES ->
                "$base/series/$xtreamUser/$xtreamPassword/${stream.streamId}.$ext"
            else ->
                "$base/live/$xtreamUser/$xtreamPassword/${stream.streamId}.ts"
        }
    }

    private fun parseM3u(rawM3u: String): List<Pair<LiveStream, String>> {
        val result = mutableListOf<Pair<LiveStream, String>>()
        var pendingName = ""
        var pendingGroup: String? = null
        var pendingLogo: String? = null
        var nextId = 1

        val groupRegex = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
        val logoRegex = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE)

        rawM3u.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                pendingGroup = groupRegex.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                pendingLogo = logoRegex.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                pendingName = line.substringAfterLast(",", "").trim()
            } else if (!line.startsWith("#")) {
                val title = pendingName.ifBlank { "Canal $nextId" }
                val stream = LiveStream(
                    streamId = nextId,
                    name = title,
                    streamIcon = pendingLogo,
                    categoryId = pendingGroup
                )
                result.add(stream to line)
                nextId++
                pendingName = ""
                pendingGroup = null
                pendingLogo = null
            }
        }
        return result
    }

    private fun loadStreamLogoIntoView(imageView: ImageView, fallbackView: TextView, iconUrl: String?) {
        val cleanUrl = iconUrl?.trim().orEmpty()
        imageView.tag = cleanUrl

        if (cleanUrl.isEmpty()) {
            imageView.visibility = View.GONE
            fallbackView.visibility = View.VISIBLE
            return
        }

        logoCache.get(cleanUrl)?.let { cachedBitmap ->
            imageView.setImageBitmap(cachedBitmap)
            imageView.visibility = View.VISIBLE
            fallbackView.visibility = View.GONE
            return
        }

        imageView.visibility = View.GONE
        fallbackView.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = runCatching {
                val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 3500
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }.also { conn.disconnect() }
            }.getOrNull()

            if (bitmap != null) {
                logoCache.put(cleanUrl, bitmap)
                withContext(Dispatchers.Main) {
                    if (imageView.tag == cleanUrl) {
                        imageView.setImageBitmap(bitmap)
                        imageView.visibility = View.VISIBLE
                        fallbackView.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun disconnect(clearActivePreference: Boolean) {
        searchJob?.cancel()
        streams = emptyList()
        categories = emptyList()
        categoryNameById = emptyMap()
        directStreamUrls = emptyMap()
        selectedSection = ContentSection.ALL
        selectedCategory = null
        showingFavorites = false
        channelsAdapter.submitList(emptyList())
        recentSectionContainer.visibility = View.GONE

        if (clearActivePreference) {
            currentUser?.activePlaylistId = null
            saveUsersToPrefs()
        }

        if (currentUser != null) {
            restorePlaylistFormView("AUTO")
        } else {
            showUserGateView()
        }
    }

    private fun updateProfileLabel() {
        val active = currentUser
        binding.btnProfileTop.text = active?.username?.firstOrNull()?.uppercaseChar()?.toString() ?: "●"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class ChannelListAdapter(
        private val onChannelClick: (LiveStream) -> Unit,
        private val onFavoriteClick: (LiveStream) -> Unit,
        private val categoryNameResolver: (String?) -> String
    ) : ListAdapter<LiveStream, ChannelListAdapter.ChannelViewHolder>(StreamDiffCallback()) {

        private var favIds: Set<Int> = emptySet()

        fun setFavorites(newFavs: Set<Int>) {
            if (favIds != newFavs) {
                favIds = newFavs
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
            val card = MaterialCardView(parent.context).apply {
                radius = dp(16).toFloat()
                cardElevation = dp(0).toFloat()
                setCardBackgroundColor(getColor(R.color.frost_card))
                strokeWidth = dp(1)
                strokeColor = getColor(R.color.frost_border)
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(9)
                }
            }

            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(11), dp(8), dp(11))
            }

            val badgeHolder = FrameLayout(parent.context).apply {
                background = getDrawable(R.drawable.bg_channel_icon)
            }
            val iconText = TextView(parent.context).apply {
                text = "▶"
                gravity = Gravity.CENTER
                textSize = 17f
                setTextColor(getColor(R.color.frost_lilac))
            }
            val iconImage = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
            }
            badgeHolder.addView(iconText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            badgeHolder.addView(iconImage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(badgeHolder, LinearLayout.LayoutParams(dp(44), dp(44)))

            val textColumn = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(4), 0)
            }
            val title = TextView(parent.context).apply {
                textSize = 14f
                maxLines = 2
                setTextColor(getColor(R.color.white))
                setTypeface(typeface, Typeface.BOLD)
            }
            val subtitle = TextView(parent.context).apply {
                textSize = 11f
                setTextColor(getColor(R.color.frost_muted))
                setPadding(0, dp(3), 0, 0)
            }
            textColumn.addView(title)
            textColumn.addView(subtitle)
            row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val favorite = MaterialButton(parent.context).apply {
                textSize = 19f
                isAllCaps = false
                setTextColor(getColor(R.color.frost_lilac))
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.frost_card))
                cornerRadius = dp(12)
                minWidth = dp(42)
                minimumWidth = dp(42)
                minHeight = dp(42)
                minimumHeight = dp(42)
                insetTop = 0
                insetBottom = 0
                setPadding(0, 0, 0, 0)
            }
            row.addView(favorite, LinearLayout.LayoutParams(dp(44), dp(44)))
            card.addView(row)

            return ChannelViewHolder(card, iconText, iconImage, title, subtitle, favorite)
        }

        override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
            val stream = getItem(position)
            holder.title.text = stream.name
            val catLabel = categoryNameResolver(stream.categoryId)
            val sectionLabel = classifySection(stream).label
            holder.subtitle.text = "$catLabel · $sectionLabel"
            holder.favorite.text = if (favIds.contains(stream.streamId)) "★" else "☆"

            loadStreamLogoIntoView(holder.iconImage, holder.iconText, stream.streamIcon)

            holder.itemView.setOnClickListener { onChannelClick(stream) }
            holder.favorite.setOnClickListener { onFavoriteClick(stream) }
        }

        inner class ChannelViewHolder(
            itemView: View,
            val iconText: TextView,
            val iconImage: ImageView,
            val title: TextView,
            val subtitle: TextView,
            val favorite: MaterialButton
        ) : RecyclerView.ViewHolder(itemView)
    }

    private class StreamDiffCallback : DiffUtil.ItemCallback<LiveStream>() {
        override fun areItemsTheSame(oldItem: LiveStream, newItem: LiveStream): Boolean =
            oldItem.streamId == newItem.streamId

        override fun areContentsTheSame(oldItem: LiveStream, newItem: LiveStream): Boolean =
            oldItem == newItem
    }
}
