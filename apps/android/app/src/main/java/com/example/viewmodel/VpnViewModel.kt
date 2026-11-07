package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.BackendEndpointConfig
import com.example.model.LocationMode
import com.example.model.UserProfile
import com.example.model.VpnConnectionState
import com.example.model.VpnLocation
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import com.example.model.VpnSettings
import com.example.network.DefinitiveFailure
import com.example.network.REACHABILITY_DEVICE_NETWORK
import com.example.network.REACHABILITY_OK
import com.example.network.REACHABILITY_SERVER_UNREACHABLE
import com.example.network.REACHABILITY_TUNNEL_FAILED
import com.example.network.PathLadder
import com.example.network.VpnBackendRepository
import com.example.network.shouldFallBackToCdn
import com.example.network.shouldEndSession
import com.example.ui.components.VpnTrafficSample
import com.example.update.AppUpdateInfo
import com.example.update.UpdateInstaller
import com.example.update.UpdateState
import com.example.vpn.ParsedQuotaInfo
import com.example.vpn.ShieldVpnService
import com.example.vpn.VpnRuntime
import com.example.vpn.VpnUriParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

data class VpnUiState(
    val isAuthenticated: Boolean = false,
    val isAuthLoading: Boolean = false,
    val authErrorMessage: String? = null,
    val userProfile: UserProfile = UserProfile(
        uid = "",
        displayName = "",
        email = "",
        authToken = "",
        planTitle = "",
        usedBytes = 0L,
        totalBytes = 0L,
        expiryDaysRemaining = 0
    ),
    val connectionState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val servers: List<VpnServer> = emptyList(),
    val selectedServerId: String? = null,
    val locations: List<VpnLocation> = emptyList(),
    val selectedLocationId: String? = null,
    val locationMode: LocationMode = LocationMode.AUTO,
    val isLocationSheetOpen: Boolean = false,
    val isLocationsLoading: Boolean = false,
    val sessionSeconds: Long = 0L,
    val liveThroughputBytesPerSec: Long = 0L,
    val liveDownloadBytesPerSec: Long = 0L,
    val liveUploadBytesPerSec: Long = 0L,
    val sessionTotalBytes: Long = 0L,
    val trafficSamples: List<VpnTrafficSample> = emptyList(),
    val settings: VpnSettings = VpnSettings(),
    val isProfileSheetOpen: Boolean = false,
    val isPasteDialogOpen: Boolean = false,
    val updateState: UpdateState = UpdateState.Idle,
    val updatePromptVisible: Boolean = false,
    val installedVersionName: String = "",
    val statusBannerMessage: String? = null
) {
    val selectedServer: VpnServer?
        get() = servers.find { it.id == selectedServerId } ?: servers.firstOrNull()

    val selectedLocation: VpnLocation?
        get() = locations.find { it.id == selectedLocationId } ?: locations.firstOrNull()
}

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VpnBackendRepository()
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(VpnUiState())
    val uiState: StateFlow<VpnUiState> = _uiState.asStateFlow()

    private var telemetryJob: Job? = null
    private var connectWatchdogJob: Job? = null
    private var fleetHeartbeatJob: Job? = null
    private var warmCacheJob: Job? = null
    private var profileWatchJob: Job? = null

    init {
        startFleetHeartbeat()
        val savedMode = prefs.getString(KEY_LOCATION_MODE, null)
            ?.let { runCatching { LocationMode.valueOf(it) }.getOrNull() }
            ?: LocationMode.AUTO
        _uiState.update {
            it.copy(
                locationMode = savedMode,
                installedVersionName = UpdateInstaller.installedVersionName(application)
            )
        }
        observeRuntime()
        restoreSession()
    }

    private fun observeRuntime() {
        viewModelScope.launch {
            VpnRuntime.state.collect { runtimeState ->
                _uiState.update { state ->
                    when (runtimeState) {
                        VpnConnectionState.CONNECTED -> state.copy(connectionState = VpnConnectionState.CONNECTED)
                        VpnConnectionState.CONNECTING -> state.copy(connectionState = VpnConnectionState.CONNECTING)
                        VpnConnectionState.DISCONNECTED -> state.copy(
                            connectionState = VpnConnectionState.DISCONNECTED,
                            sessionSeconds = 0L,
                            liveThroughputBytesPerSec = 0L,
                            liveDownloadBytesPerSec = 0L,
                            liveUploadBytesPerSec = 0L,
                            sessionTotalBytes = 0L,
                            trafficSamples = emptyList()
                        )
                    }
                }
                if (runtimeState == VpnConnectionState.CONNECTED) {
                    connectWatchdogJob?.cancel()
                    connectWatchdogJob = null
                    startSessionTrafficLoop()
                    startWarmCacheLoop()
                    startProfileWatch()
                } else if (runtimeState == VpnConnectionState.DISCONNECTED) {
                    connectWatchdogJob?.cancel()
                    connectWatchdogJob = null
                    stopSessionTrafficLoop()
                    stopWarmCacheLoop()
                    stopProfileWatch()
                }
            }
        }

        viewModelScope.launch {
            VpnRuntime.message.collect { message ->
                if (!message.isNullOrBlank()) {
                    _uiState.update { it.copy(statusBannerMessage = message) }
                    VpnRuntime.clearMessage()
                    clearBannerAfterDelay()
                }
            }
        }
    }

    private fun restoreSession() {
        val token = prefs.getString(KEY_TOKEN, null).orEmpty()
        val username = prefs.getString(KEY_USERNAME, null).orEmpty()
        if (token.isBlank()) return

        _uiState.update { it.copy(isAuthLoading = true, authErrorMessage = null) }
        viewModelScope.launch {
            val placeholder = _uiState.value.userProfile.copy(
                uid = prefs.getString(KEY_UID, "").orEmpty(),
                displayName = prefs.getString(KEY_DISPLAY_NAME, username).orEmpty(),
                email = username,
                username = username,
                authToken = token
            )
            val result = repository.fetchProfileAndNodesFromServer(
                config = _uiState.value.settings.backendConfig,
                currentProfile = placeholder
            )
            result.onSuccess { (profile, serverNodes) ->
                persistSession(profile)
                val selected = prefs.getString(KEY_SELECTED_TAG, null)
                    ?.takeIf { tag -> serverNodes.any { it.id == tag } }
                    ?: serverNodes.firstOrNull()?.id
                _uiState.update { state ->
                    state.copy(
                        isAuthenticated = true,
                        isAuthLoading = false,
                        authErrorMessage = null,
                        userProfile = profile,
                        servers = serverNodes,
                        selectedServerId = selected
                    )
                }
                refreshLocations()
            }.onFailure { error ->
                val message = error.localizedMessage.orEmpty()
                if (shouldEndSession(error)) {
                    clearPersistedSession()
                    _uiState.update {
                        it.copy(isAuthLoading = false, authErrorMessage = "Your session expired. Please sign in again.")
                    }
                } else {
                    _uiState.update {
                        it.copy(isAuthLoading = false, authErrorMessage = "Could not restore session: $message")
                    }
                }
            }
        }
    }

    fun login(email: String, password: String) {
        val cleanUsername = email.trim()
        if (cleanUsername.isBlank() || password.isBlank()) {
            _uiState.update {
                it.copy(isAuthLoading = false, authErrorMessage = "Please enter your username and password.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authErrorMessage = null) }
            val config = _uiState.value.settings.backendConfig
            repository.loginWithServer(config, cleanUsername, password)
                .onSuccess { (profile, serverNodes) ->
                    persistSession(profile)
                    val selected = serverNodes.firstOrNull()?.id
                    _uiState.update { state ->
                        state.copy(
                            isAuthenticated = true,
                            isAuthLoading = false,
                            userProfile = profile,
                            servers = serverNodes,
                            selectedServerId = selected,
                            authErrorMessage = null,
                            statusBannerMessage = if (profile.status != "active") statusMessage(profile.status) else null
                        )
                    }
                    refreshLocations()
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        state.copy(
                            isAuthenticated = false,
                            isAuthLoading = false,
                            authErrorMessage = error.localizedMessage ?: "Authentication failed."
                        )
                    }
                }
        }
    }

    fun registerProfile(displayName: String, email: String, password: String) {
        // Registration is intentionally disabled on the backend (accounts are created by admins).
        _uiState.update {
            it.copy(
                isAuthLoading = false,
                authErrorMessage = "Registration is disabled. Please contact support to create an account."
            )
        }
    }

    fun updateUserProfile(displayName: String, email: String) {
        val current = _uiState.value.userProfile
        viewModelScope.launch {
            val result = repository.updateProfileOnServer(
                config = _uiState.value.settings.backendConfig,
                currentProfile = current,
                newDisplayName = displayName,
                newEmail = email
            )
            result.onSuccess { updated ->
                persistSession(updated)
                _uiState.update {
                    it.copy(
                        userProfile = updated,
                        isProfileSheetOpen = false
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(statusBannerMessage = error.localizedMessage ?: "Failed to update profile")
                }
                clearBannerAfterDelay()
            }
        }
    }

    fun logout() {
        connectWatchdogJob?.cancel()
        connectWatchdogJob = null
        stopVpnService()
        stopSessionTrafficLoop()
        clearPersistedSession()
        _uiState.update {
            it.copy(
                isAuthenticated = false,
                isProfileSheetOpen = false,
                userProfile = UserProfile(
                    uid = "",
                    displayName = "",
                    email = "",
                    authToken = "",
                    planTitle = "",
                    usedBytes = 0L,
                    totalBytes = 0L,
                    expiryDaysRemaining = 0
                ),
                servers = emptyList(),
                selectedServerId = null,
                connectionState = VpnConnectionState.DISCONNECTED,
                authErrorMessage = null,
                statusBannerMessage = null
            )
        }
    }

    fun importFromClipboardText(rawClipboardText: String?) {
        val cleaned = rawClipboardText?.trim().orEmpty()
        if (cleaned.isEmpty()) {
            _uiState.update { it.copy(isPasteDialogOpen = true) }
            return
        }

        viewModelScope.launch {
            if (cleaned.startsWith("http://", ignoreCase = true) ||
                cleaned.startsWith("https://", ignoreCase = true)
            ) {
                val (httpServers, quotaInfo) = repository.fetchSubscriptionUrlIfHttp(cleaned)
                if (httpServers.isNotEmpty()) {
                    applyImportedServers(httpServers, quotaInfo)
                    return@launch
                }
            }

            val parsedServers = VpnUriParser.parseClipboardPayload(cleaned)
            if (parsedServers.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isPasteDialogOpen = true,
                        statusBannerMessage = "No valid VPN config found"
                    )
                }
                clearBannerAfterDelay()
                return@launch
            }

            val quotaInfo = VpnUriParser.extractQuotaFromPayload(cleaned)
            applyImportedServers(parsedServers, quotaInfo)
        }
    }

    private fun applyImportedServers(imported: List<VpnServer>, quotaInfo: ParsedQuotaInfo?) {
        _uiState.update { state ->
            val existingRawUris = state.servers.map { it.rawUri }.toSet()
            val newUnique = imported.filter { it.rawUri !in existingRawUris }
            val merged = if (newUnique.isNotEmpty()) newUnique + state.servers else imported + state.servers

            val updatedProfile = if (quotaInfo != null) {
                state.userProfile.copy(
                    usedBytes = quotaInfo.usedBytes,
                    totalBytes = quotaInfo.totalBytes
                )
            } else {
                state.userProfile
            }

            state.copy(
                servers = merged,
                selectedServerId = imported.first().id,
                userProfile = updatedProfile,
                isPasteDialogOpen = false
            )
        }
    }

    fun triggerConnect() {
        val state = _uiState.value
        if (state.connectionState != VpnConnectionState.DISCONNECTED) return
        if (!state.isAuthenticated || state.userProfile.authToken.isBlank()) {
            showBanner("Please sign in first")
            return
        }
        if (state.userProfile.status != "active") {
            showBanner(statusMessage(state.userProfile.status))
            return
        }

        _uiState.update {
            it.copy(
                connectionState = VpnConnectionState.CONNECTING,
                statusBannerMessage = null
            )
        }

        connectWatchdogJob?.cancel()
        connectWatchdogJob = viewModelScope.launch {
            // Informational only: stopping the service here used to send ACTION_DISCONNECT, which erased the saved profile and ended a session the user never asked to end.
            delay(CONNECT_NUDGE_DELAY_MS)
            if (_uiState.value.connectionState == VpnConnectionState.CONNECTING) {
                showBanner("Still connecting. The app keeps retrying on its own.")
                clearBannerAfterDelay()
            }
        }

        viewModelScope.launch {
            // Which box this connection uses. Manual means the country the user picked; automatic
            // means the box that completes an HTTPS round trip fastest. Boxes are measured in
            // parallel, so an address that is filtered costs one probe timeout in total rather than
            // one per country, and a box that never answers can never be selected.
            //
            // A handshake, not a SYN, is the unit of comparison: a box measured at 83 ms of handshake
            // cost was also seen letting that connection through and then losing the TLS handshake
            // after it. Ranking by SYN alone would call that the fastest server in the fleet.
            val boxes = state.locations.filter { it.host.isNotBlank() }
            val target: VpnLocation? = when {
                boxes.isEmpty() -> null
                state.locationMode == LocationMode.MANUAL ->
                    boxes.find { it.id == state.selectedLocationId } ?: boxes.first()
                else -> {
                    val ranked = coroutineScope {
                        boxes.map { box ->
                            async { box to repository.measureBoxRoundTripMs(repository.boxProbeUrl(box)) }
                        }.awaitAll()
                    }
                    val fastest = ranked.filter { it.second >= 0 }.minByOrNull { it.second }
                    if (fastest != null) {
                        Log.i(
                            "PTunnelVM",
                            "Auto-select ${fastest.first.country} (${fastest.second}ms) of " +
                                ranked.joinToString { "${it.first.country}=${if (it.second >= 0) "${it.second}ms" else "dead"}" }
                        )
                    } else {
                        // The numbers go in the log because "nobody answered" is a sentence the
                        // operator cannot act on; which box timed out and which was refused is.
                        Log.w(
                            "PTunnelVM",
                            "Auto-select: no box finished a handshake, falling back to the first; " +
                                ranked.joinToString { "${it.first.country}=${if (it.second >= 0) "${it.second}ms" else "no answer"}" }
                        )
                    }
                    fastest?.first ?: boxes.find { it.id == state.selectedLocationId } ?: boxes.first()
                }
            }
            if (target != null && target.id != state.selectedLocationId) {
                // The country shown in the UI must be the one actually carrying the tunnel.
                _uiState.update { it.copy(selectedLocationId = target.id) }
            }

            val boxConfig = _uiState.value.settings.backendConfig
            val config = target?.apiBase?.takeIf { it.isNotBlank() }
                ?.let { boxConfig.copy(apiBaseUrl = it) }
                ?: boxConfig
            // The chosen box must be tried first: a background refresh against another box would
            // otherwise keep the tunnel there and leave the country label lying.
            repository.preferEndpoint(target?.apiBase)
            var fetched = repository.fetchXrayConfig(config, state.userProfile, "multi")
            if (fetched.isFailure &&
                (repository.refreshBootstrap() || repository.refreshBootstrapFromDns())
            ) {
                // The out-of-band channel handed us new entry points; retry before giving up.
                fetched = repository.fetchXrayConfig(config, state.userProfile, "multi")
            }

            val live = fetched.getOrNull()
            if (live != null) {
                syncLabelToServedEndpoint()
                // Keep the last profile that worked the server's verification.
                prefs.edit().putString(KEY_CACHED_CONFIG, live).apply()
                startTunnelWith(live)
                return@launch
            }

            // The live profile is gone. Diagnose *now*, before reaching for the cached profile:
            // the cache dials the very same server address, so it cannot survive a block, and
            // consulting it first would leave this whole block unreachable in the field.
            connectWatchdogJob?.cancel()
            connectWatchdogJob = null

            // Find out *whose* problem it is before blaming the server: a device whose own link is
            // down must not push the fleet onto a slower fallback path, and its user deserves an
            // accurate message.
            val failure = fetched.exceptionOrNull()
            val verdict = if (failure is DefinitiveFailure) {
                null
            } else {
                repository.classifyReachability()
            }
            if (verdict != null && verdict != REACHABILITY_DEVICE_NETWORK) {
                repository.reportReachability(verdict)
            }

            // Only the ambiguous verdict costs an extra round trip, and it is capped: this is
            // already a failure path and the user is waiting on a spinner.
            val fleetVerdict = if (verdict == REACHABILITY_TUNNEL_FAILED) {
                withTimeoutOrNull(FLEET_VERDICT_TIMEOUT_MS) { repository.fetchFleetVerdict() }
            } else {
                null
            }

            // Our own address looks blocked. Switch to the Cloudflare-fronted path, which is the
            // one route that survives the server IP being filtered.
            if (shouldFallBackToCdn(verdict, fleetVerdict) &&
                switchToCdnFallback(config, state.userProfile)
            ) {
                return@launch
            }

            val cached = cachedConfig()
            if (cached.isNullOrBlank()) {
                val message = when (verdict) {
                    REACHABILITY_DEVICE_NETWORK -> "Your internet connection is down."
                    REACHABILITY_SERVER_UNREACHABLE -> "Server unreachable. We will keep retrying."
                    else -> failure?.localizedMessage ?: "Unable to load VPN configuration"
                }
                _uiState.update {
                    it.copy(
                        connectionState = VpnConnectionState.DISCONNECTED,
                        statusBannerMessage = message
                    )
                }
                return@launch
            }

            // Last resort: the cached profile dials our address directly, so it can still get the
            // user online when only the API or the hostname is being blocked.
            startTunnelWith(cached)
        }
    }

    /**
     * Moves the app onto the Cloudflare-fronted profile. Returns false when even that profile
     * cannot be fetched, so the caller can fall through to its normal error handling.
     */
    private suspend fun switchToCdnFallback(
        config: BackendEndpointConfig,
        profile: UserProfile
    ): Boolean {
        val cdnProfile = loadCdnFallback(config, profile) ?: return false
        prefs.edit().putString(KEY_CACHED_CDN_CONFIG, cdnProfile).apply()
        startTunnelWith(cdnProfile)
        return true
    }

    /**
     * The fallback profile: the CDN-fronted tunnel dialled at the best-scored Cloudflare edge
     * address. Returns null when even that cannot be fetched, in which case the cached profile
     * remains the user's best shot.
     */
    private suspend fun loadCdnFallback(
        config: BackendEndpointConfig,
        profile: UserProfile
    ): String? {
        val fetched = repository.fetchXrayConfig(config, profile, "cdn")
        val raw = fetched.getOrNull() ?: return null
        val edge = repository.scoredCdnAddresses().firstOrNull() ?: return raw
        return repository.withCdnAddress(raw, edge)
    }

    /** Hands a profile to the VPN service. */
    private fun startTunnelWith(configJson: String) {
        try {
            val intent = Intent(getApplication(), ShieldVpnService::class.java).apply {
                action = ShieldVpnService.ACTION_CONNECT
                putExtra(ShieldVpnService.EXTRA_XRAY_CONFIG, configJson)
            }
            ContextCompat.startForegroundService(getApplication(), intent)
        } catch (error: Exception) {
            connectWatchdogJob?.cancel()
            connectWatchdogJob = null
            _uiState.update {
                it.copy(
                    connectionState = VpnConnectionState.DISCONNECTED,
                    statusBannerMessage = "Unable to start VPN service: ${error.localizedMessage}"
                )
            }
        }
    }

    /**
     * The last profile the server handed us. When the control channel is blocked (poisoned DNS,
     * a filtered hostname, an unreachable API) the app still connects with this instead of
     * refusing to do anything at all.
     */
    private fun cachedConfig(): String? =
        prefs.getString(KEY_CACHED_CONFIG, null)?.takeIf { it.isNotBlank() }

    fun onVpnPermissionDenied() {
        _uiState.update {
            it.copy(
                connectionState = VpnConnectionState.DISCONNECTED,
                statusBannerMessage = "VPN permission is required to connect"
            )
        }
    }

    fun triggerDisconnect() {
        connectWatchdogJob?.cancel()
        connectWatchdogJob = null
        stopVpnService()
        stopSessionTrafficLoop()
        _uiState.update {
            it.copy(
                connectionState = VpnConnectionState.DISCONNECTED,
                sessionSeconds = 0L,
                liveThroughputBytesPerSec = 0L,
                liveDownloadBytesPerSec = 0L,
                liveUploadBytesPerSec = 0L,
                sessionTotalBytes = 0L,
                trafficSamples = emptyList()
            )
        }
    }

    fun selectServer(serverId: String) {
        val previous = _uiState.value.selectedServerId
        if (previous == serverId) return
        _uiState.update { it.copy(selectedServerId = serverId) }
        prefs.edit().putString(KEY_SELECTED_TAG, serverId).apply()

        if (_uiState.value.connectionState == VpnConnectionState.CONNECTED) {
            triggerDisconnect()
            showBanner("Server changed. Tap connect to reconnect.")
        }
    }

    fun refreshLocations() {
        if (_uiState.value.isLocationsLoading) return
        _uiState.update { it.copy(isLocationsLoading = true) }
        viewModelScope.launch {
            repository.fetchLocations(_uiState.value.settings.backendConfig)
                .onSuccess { locations ->
                    val saved = prefs.getString(KEY_LOCATION_ID, null)
                    val selected = saved?.takeIf { id -> locations.any { it.id == id } }
                        ?: locations.firstOrNull()?.id
                    _uiState.update {
                        it.copy(
                            locations = locations,
                            selectedLocationId = selected,
                            isLocationsLoading = false
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isLocationsLoading = false) }
                }
        }
    }

    fun setLocationMode(mode: LocationMode) {
        prefs.edit().putString(KEY_LOCATION_MODE, mode.name).apply()
        _uiState.update { it.copy(locationMode = mode) }
    }

    fun selectLocation(locationId: String) {
        prefs.edit().putString(KEY_LOCATION_ID, locationId).apply()
        _uiState.update {
            it.copy(
                selectedLocationId = locationId,
                locationMode = LocationMode.MANUAL,
                isLocationSheetOpen = false
            )
        }
    }

    fun setLocationSheetOpen(open: Boolean) {
        _uiState.update { it.copy(isLocationSheetOpen = open) }
    }

    fun setProfileSheetOpen(open: Boolean) {
        _uiState.update { it.copy(isProfileSheetOpen = open) }
    }

    fun setPasteDialogOpen(open: Boolean) {
        _uiState.update { it.copy(isPasteDialogOpen = open) }
    }

    /**
     * Runs every time the app is entered (process start and each foreground resume).
     * A background check never downloads on its own: when a newer build exists it raises the
     * update prompt so the user decides whether to download and install.
     */
    /**
     * Fleet heartbeat.
     *
     * The server's verdict is assembled from client reports, and until now clients only spoke when
     * something had broken — so three networks having a bad minute could outvote a hundred healthy
     * ones and push everybody onto the fallback path. One small POST on connect and then hourly
     * while connected; nothing is sent when disconnected, and the only identity the server keeps is
     * the coarse /24 it derives from the source address itself.
     */
    /**
     * Points the location label at the box whose endpoint actually served the last profile.
     *
     * The label is a promise about where traffic goes; a failover during reconnect must move it, or
     * the country on screen is the country of the profile that just died.
     */
    private fun syncLabelToServedEndpoint() {
        val served = repository.servedEndpoint ?: return
        _uiState.value.locations
            .find { it.apiBase.isNotBlank() && it.apiBase == served }
            ?.takeIf { it.id != _uiState.value.selectedLocationId }
            ?.let { actual ->
                Log.i("PTunnelVM", "Label updated: ${actual.country} served the profile")
                _uiState.update { it.copy(selectedLocationId = actual.id) }
            }
    }

    private fun startFleetHeartbeat() {
        fleetHeartbeatJob?.cancel()
        fleetHeartbeatJob = viewModelScope.launch {
            var wasConnected = false
            var lastReportAt = 0L
            while (isActive) {
                val connected = _uiState.value.connectionState == VpnConnectionState.CONNECTED
                val now = System.currentTimeMillis()
                // Checked every tick, not only on the observed edge: a reconnect that fails over
                // can pass through CONNECTING entirely between two ticks, and the country label must
                // never be left on the box that just stopped carrying the traffic.
                if (connected) syncLabelToServedEndpoint()
                if (connected &&
                    (!wasConnected || now - lastReportAt >= FLEET_HEARTBEAT_INTERVAL_MS)
                ) {
                    lastReportAt = now
                    repository.reportReachability(REACHABILITY_OK)
                }
                wasConnected = connected
                delay(FLEET_HEARTBEAT_TICK_MS)
            }
        }
    }

    fun checkForUpdatesOnEntry() {
        val state = _uiState.value.updateState
        if (state is UpdateState.Checking || state is UpdateState.Downloading) return
        if (state is UpdateState.ReadyToInstall) {
            _uiState.update { it.copy(updatePromptVisible = true) }
            return
        }
        viewModelScope.launch {
            repository.fetchLatestApp(_uiState.value.settings.backendConfig)
                .onSuccess { info ->
                    val installedCode = UpdateInstaller.installedVersionCode(getApplication())
                    if (info != null && info.versionCode > installedCode) {
                        _uiState.update {
                            it.copy(
                                updateState = UpdateState.Available(info),
                                updatePromptVisible = true
                            )
                        }
                    } else {
                        _uiState.update { it.copy(updateState = UpdateState.Idle) }
                    }
                }
            // Entry checks stay silent on failure; only the manual check reports errors.
        }
    }

    fun dismissUpdatePrompt() {
        _uiState.update { it.copy(updatePromptVisible = false) }
    }

    fun checkForUpdates() {
        val current = _uiState.value.updateState
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return
        _uiState.update { it.copy(updateState = UpdateState.Checking) }
        viewModelScope.launch {
            repository.fetchLatestApp(_uiState.value.settings.backendConfig)
                .onSuccess { info ->
                    val app = getApplication<Application>()
                    val installedCode = UpdateInstaller.installedVersionCode(app)
                    if (info != null && info.versionCode > installedCode) {
                        _uiState.update {
                            it.copy(updateState = UpdateState.Available(info))
                        }
                    } else {
                        _uiState.update {
                            it.copy(updateState = UpdateState.UpToDate(UpdateInstaller.installedVersionName(app)))
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(updateState = UpdateState.Failed(error.localizedMessage ?: "Update check failed"))
                    }
                }
        }
    }

    fun downloadUpdate() {
        val current = _uiState.value.updateState
        if (current is UpdateState.ReadyToInstall) {
            installUpdate()
            return
        }
        val info = (current as? UpdateState.Available)?.info ?: return
        startUpdateDownload(info)
    }

    private fun startUpdateDownload(info: AppUpdateInfo) {
        if (_uiState.value.updateState is UpdateState.Downloading) return
        _uiState.update { it.copy(updateState = UpdateState.Downloading(info, 0)) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val destination = java.io.File(
                java.io.File(app.cacheDir, "updates"),
                "ptunnel-${info.versionCode}.apk"
            )
            repository.downloadApk(
                url = info.url,
                viaTunnel = _uiState.value.connectionState == VpnConnectionState.CONNECTED,
                destination = destination,
                expectedSha256 = info.sha256,
                expectedSize = info.sizeBytes,
                onProgress = { percent ->
                    _uiState.update { state ->
                        val downloading = state.updateState
                        if (downloading is UpdateState.Downloading) {
                            state.copy(updateState = UpdateState.Downloading(downloading.info, percent))
                        } else {
                            state
                        }
                    }
                }
            ).onSuccess { file ->
                _uiState.update { state ->
                    state.copy(
                        updateState = UpdateState.ReadyToInstall(info, file),
                        // Keep the dialog closed when the download was started from the profile
                        // sheet, which already shows the install action inline.
                        updatePromptVisible = state.updatePromptVisible || !state.isProfileSheetOpen
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(updateState = UpdateState.Failed(error.localizedMessage ?: "Update download failed"))
                }
            }
        }
    }

    fun installUpdate() {
        val ready = _uiState.value.updateState as? UpdateState.ReadyToInstall ?: return
        val app = getApplication<Application>()
        if (!UpdateInstaller.canInstall(app)) {
            UpdateInstaller.openInstallPermissionSettings(app)
            showBanner("Allow installs from PTunnel, then tap Install again")
            clearBannerAfterDelay()
            return
        }
        runCatching { UpdateInstaller.install(app, ready.file) }
            .onFailure {
                _uiState.update { it.copy(updateState = UpdateState.Failed("Could not open the Android installer.")) }
            }
    }

    fun openDownloadPage() {
        val app = getApplication<Application>()
        val info = (_uiState.value.updateState as? UpdateState.Available)?.info
            ?: (_uiState.value.updateState as? UpdateState.ReadyToInstall)?.info
        runCatching { UpdateInstaller.openDownloadPage(app, info?.url ?: DOWNLOAD_PAGE_URL) }
            .onFailure { showBanner("Could not open the download page") }
    }

    fun updateProtocol(protocol: VpnProtocol) {
        _uiState.update { it.copy(settings = it.settings.copy(protocol = protocol)) }
    }

    fun toggleKillSwitch(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(killSwitchEnabled = enabled)) }
    }

    fun toggleSplitTunneling(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(splitTunnelingEnabled = enabled)) }
    }

    fun toggleZeroKnowledgeDns(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(zeroKnowledgeDnsEnabled = enabled)) }
    }

    fun updateBackendEndpointConfig(newConfig: BackendEndpointConfig) {
        _uiState.update {
            it.copy(
                settings = it.settings.copy(backendConfig = newConfig),
                statusBannerMessage = "Backend endpoint & port settings saved"
            )
        }
        clearBannerAfterDelay()
    }

    fun toggleShowServerPing(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(showServerPing = enabled)) }
    }

    fun toggleCompactServerList(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(compactServerList = enabled)) }
    }

    fun refreshVpnServers() {
        val snapshot = _uiState.value
        if (snapshot.userProfile.authToken.isBlank()) {
            showBanner("Not authenticated with server")
            return
        }
        viewModelScope.launch {
            repository.fetchProfileAndNodesFromServer(
                config = snapshot.settings.backendConfig,
                currentProfile = snapshot.userProfile
            ).onSuccess { (profile, serverNodes) ->
                persistSession(profile)
                _uiState.update { state ->
                    val merged = if (serverNodes.isNotEmpty()) serverNodes else state.servers
                    state.copy(
                        userProfile = profile,
                        servers = merged,
                        selectedServerId = state.selectedServerId?.takeIf { id -> merged.any { it.id == id } }
                            ?: merged.firstOrNull()?.id
                    )
                }
                if (serverNodes.isNotEmpty()) refreshServerPings()
                if (profile.status != "active") showBanner(statusMessage(profile.status))
                clearBannerAfterDelay()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(statusBannerMessage = error.localizedMessage ?: "Failed to refresh servers")
                }
                clearBannerAfterDelay()
            }
        }
    }

    fun refreshServerPings() {
        val currentServers = _uiState.value.servers
        if (currentServers.isEmpty()) {
            showBanner("No active servers loaded from server")
            return
        }
        viewModelScope.launch {
            currentServers.forEach { server ->
                val ping = measureServerPing(server)
                _uiState.update { state ->
                    state.copy(
                        servers = state.servers.map {
                            if (it.id == server.id) it.copy(pingMs = ping) else it
                        }
                    )
                }
            }
        }
    }

    private suspend fun measureServerPing(server: VpnServer): Int {
        return VpnUriParser.measureRealTcpPing(host = server.host, port = server.port)
    }

    private fun startSessionTrafficLoop() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = viewModelScope.launch {
            var lastRxBytes = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            var lastTxBytes = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
            var tickCounter = 0
            var smoothedRxRate = 0L
            var smoothedTxRate = 0L

            while (isActive) {
                delay(500L)
                if (_uiState.value.connectionState != VpnConnectionState.CONNECTED) break
                tickCounter++

                val currentRxBytes = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
                val currentTxBytes = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
                val deltaRx = if (currentRxBytes >= lastRxBytes && lastRxBytes > 0L) currentRxBytes - lastRxBytes else 0L
                val deltaTx = if (currentTxBytes >= lastTxBytes && lastTxBytes > 0L) currentTxBytes - lastTxBytes else 0L
                lastRxBytes = currentRxBytes
                lastTxBytes = currentTxBytes

                val rawRxRate = deltaRx * 2L
                val rawTxRate = deltaTx * 2L
                smoothedRxRate = ((smoothedRxRate * 55L + rawRxRate * 45L) / 100L).coerceAtLeast(0L)
                smoothedTxRate = ((smoothedTxRate * 55L + rawTxRate * 45L) / 100L).coerceAtLeast(0L)

                val windowTransferredBytes = (deltaRx + deltaTx).coerceAtLeast(0L)
                val combinedThroughputPerSec = smoothedRxRate + smoothedTxRate

                _uiState.update { state ->
                    if (state.connectionState != VpnConnectionState.CONNECTED) return@update state
                    val nextSec = if (tickCounter % 2 == 0) state.sessionSeconds + 1L else state.sessionSeconds
                    val nextSessionBytes = state.sessionTotalBytes + windowTransferredBytes
                    val newSample = VpnTrafficSample(
                        label = formatSampleTimestamp(nextSec),
                        downloadBytesPerSec = smoothedRxRate,
                        uploadBytesPerSec = smoothedTxRate,
                        totalSessionBytes = nextSessionBytes
                    )
                    state.copy(
                        sessionSeconds = nextSec,
                        liveThroughputBytesPerSec = combinedThroughputPerSec,
                        liveDownloadBytesPerSec = smoothedRxRate,
                        liveUploadBytesPerSec = smoothedTxRate,
                        sessionTotalBytes = nextSessionBytes,
                        trafficSamples = (state.trafficSamples + newSample).takeLast(14)
                    )
                }

                if (tickCounter % 60 == 0) {
                    refreshProfileSilently()
                }
            }
        }
    }

    private fun stopSessionTrafficLoop() {
        telemetryJob?.cancel()
        telemetryJob = null
    }

    /**
     * Keeps a CDN-fronted profile warm while the tunnel is healthy.
     *
     * The Cloudflare path is the one route that survives our own address being filtered, and it is
     * useless if the app has to fetch it at the exact moment the block lands. Refreshing it in the
     * background means the recovery ladder can dial it from cache with no round trip.
     */
    private fun startWarmCacheLoop() {
        warmCacheJob?.cancel()
        warmCacheJob = viewModelScope.launch {
            while (isActive) {
                val snapshot = _uiState.value
                if (snapshot.userProfile.authToken.isBlank()) return@launch
                if (snapshot.connectionState != VpnConnectionState.CONNECTED) {
                    // Being idle is not the end of the loop. Ending here would leave the app with a
                    // cold CDN cache for the rest of the session, which is exactly the moment the
                    // fallback profile is needed.
                    delay(WARM_CACHE_RETRY_MS)
                    continue
                }

                val profile = repository.fetchXrayConfig(
                    snapshot.settings.backendConfig,
                    snapshot.userProfile,
                    CDN_TAG
                ).getOrNull()
                if (!profile.isNullOrBlank()) {
                    // Pin the best-scored edge at cache time: the cached copy must not depend on
                    // resolving a name the local resolver may be lying about.
                    val edge = repository.scoredCdnAddresses().firstOrNull()
                    val ready = if (edge != null) repository.withCdnAddress(profile, edge) else profile
                    prefs.edit().putString(KEY_CACHED_CDN_CONFIG, ready).apply()
                }
                delay(WARM_CACHE_INTERVAL_MS)
            }
        }
    }

    /**
     * Reapplies the profile the server is serving, without waiting for the user to toggle anything.
     *
     * The transport rotation lives in the profile, not the APK, so a server-side fix reaches every
     * install on its next fetch — but a session that is already connected never fetches again. Measured
     * from the server over three hours: Iranian /24s were still dialling trojan and vmess 471 times, the
     * transports that measure 2 KB/s on this path, purely because those sessions were holding a profile
     * from before the rotation changed. A rebuild is only paid when the profile actually differs.
     */
    private fun startProfileWatch() {
        profileWatchJob?.cancel()
        profileWatchJob = viewModelScope.launch {
            while (isActive) {
                delay(PROFILE_WATCH_INTERVAL_MS)
                val snapshot = _uiState.value
                if (snapshot.connectionState != VpnConnectionState.CONNECTED) continue
                if (snapshot.userProfile.authToken.isBlank()) continue
                val running = prefs.getString(KEY_CACHED_CONFIG, null).orEmpty()
                val fresh = repository.fetchXrayConfig(
                    snapshot.settings.backendConfig,
                    snapshot.userProfile,
                    "multi"
                ).getOrNull().orEmpty()
                if (!PathLadder.profileDiffers(running, fresh)) continue
                Log.i(
                    "PTunnelVM",
                    "Server profile changed (${fresh.length} vs ${running.length} bytes); rebuilding onto it"
                )
                prefs.edit().putString(KEY_CACHED_CONFIG, fresh).apply()
                startTunnelWith(fresh)
            }
        }
    }

    private fun stopProfileWatch() {
        profileWatchJob?.cancel()
        profileWatchJob = null
    }

    private fun stopWarmCacheLoop() {
        warmCacheJob?.cancel()
        warmCacheJob = null
    }

    private suspend fun refreshProfileSilently() {
        val snapshot = _uiState.value
        if (snapshot.userProfile.authToken.isBlank()) return
        repository.fetchProfileAndNodesFromServer(
            config = snapshot.settings.backendConfig,
            currentProfile = snapshot.userProfile
        ).onSuccess { (profile, serverNodes) ->
            persistSession(profile)
            _uiState.update { state ->
                state.copy(
                    userProfile = profile,
                    servers = if (serverNodes.isNotEmpty()) serverNodes else state.servers,
                    selectedServerId = state.selectedServerId ?: serverNodes.firstOrNull()?.id
                )
            }
            if (profile.status != "active") {
                showBanner(statusMessage(profile.status))
                triggerDisconnect()
            }
        }.onFailure { error ->
            if (shouldEndSession(error)) {
                logout()
            }
        }
    }

    private fun persistSession(profile: UserProfile) {
        prefs.edit()
            .putString(KEY_TOKEN, profile.authToken)
            .putString(KEY_USERNAME, profile.username.ifBlank { profile.email })
            .putString(KEY_UID, profile.uid)
            .putString(KEY_DISPLAY_NAME, profile.displayName)
            .apply()
    }

    private fun clearPersistedSession() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USERNAME)
            .remove(KEY_UID)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_SELECTED_TAG)
            .remove(KEY_LOCATION_ID)
            .remove(KEY_CACHED_CONFIG)
            .remove(KEY_CACHED_CDN_CONFIG)
            .apply()
    }

    private fun stopVpnService() {
        try {
            val intent = Intent(getApplication(), ShieldVpnService::class.java).apply {
                action = ShieldVpnService.ACTION_DISCONNECT
            }
            getApplication<Application>().startService(intent)
        } catch (_: Exception) {
        }
    }

    private fun showBanner(message: String) {
        _uiState.update { it.copy(statusBannerMessage = message) }
    }

    private fun statusMessage(status: String): String = when (status) {
        "disabled" -> "This account is disabled. Contact support."
        "expired" -> "Your plan has expired. Please renew your account."
        "quota_exceeded" -> "Your data package is exhausted. Please renew your account."
        else -> "Account is not active"
    }

    private fun formatSampleTimestamp(sessionSec: Long): String {
        val m = (sessionSec / 60L) % 60L
        val s = sessionSec % 60L
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun clearBannerAfterDelay() {
        viewModelScope.launch {
            delay(3000L)
            _uiState.update { it.copy(statusBannerMessage = null) }
        }
    }

    companion object {
        private const val PREFS_NAME = "ptunnel_session"
        private const val KEY_TOKEN = "token"
        private const val KEY_USERNAME = "username"
        private const val KEY_UID = "uid"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_SELECTED_TAG = "selected_tag"
        private const val KEY_LOCATION_ID = "location_id"
        private const val KEY_LOCATION_MODE = "location_mode"
        private const val KEY_CACHED_CONFIG = "cached_xray_config"
        private const val KEY_CACHED_CDN_CONFIG = "cached_cdn_config"
        private const val DOWNLOAD_PAGE_URL = "https://86-54-42-124.nip.io/dl"

        /** How often the heartbeat loop looks at the connection state. */
        private const val FLEET_HEARTBEAT_TICK_MS = 30_000L

        /** How often a connected client actually reports. One tiny POST an hour per client. */
        private const val FLEET_HEARTBEAT_INTERVAL_MS = 60 * 60 * 1000L

        /** Upper bound on consulting the fleet verdict, so a failure path never stalls. */
        private const val FLEET_VERDICT_TIMEOUT_MS = 3_000L

        /** Backend tag of the Cloudflare-fronted profile, kept warm while the tunnel is healthy. */
        private const val CDN_TAG = "cdn"

        private const val WARM_CACHE_INTERVAL_MS = 30 * 60 * 1000L

        /** How often a live session asks whether the server has a new profile for it. */
        private const val PROFILE_WATCH_INTERVAL_MS = 20 * 60 * 1000L

        /** How long the warm cache waits while the tunnel is down before looking again. */
        private const val WARM_CACHE_RETRY_MS = 60 * 1000L

        /** Informational only: the service, not this timer, decides when a session ends. */
        private const val CONNECT_NUDGE_DELAY_MS = 22_000L
    }
}
