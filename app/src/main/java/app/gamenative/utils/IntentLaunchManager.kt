package app.gamenative.utils

import android.content.Context
import android.content.Intent
import com.winlator.container.Container
import com.winlator.container.ContainerData
import org.json.JSONObject
import timber.log.Timber

/**
 * Handles external game launch intents with container configuration overrides.
 */
object IntentLaunchManager {

    private const val EXTRA_APP_ID = "app_id"
    private const val EXTRA_CONTAINER_CONFIG = "container_config"
    private const val ACTION_LAUNCH_GAME = "app.gamenative.LAUNCH_GAME"
    private const val MAX_CONFIG_JSON_SIZE = 50000 // 50KB limit to prevent memory exhaustion

    data class LaunchRequest(
        val appId: Int,
        val containerConfig: ContainerData? = null
    )

    fun parseLaunchIntent(intent: Intent): LaunchRequest? {
        Timber.d("[IntentLaunchManager]: Parsing intent: action=${intent.action}, extras=${intent.extras}")

        if (intent.action != ACTION_LAUNCH_GAME) {
            Timber.d("[IntentLaunchManager]: Intent action '${intent.action}' doesn't match expected action '$ACTION_LAUNCH_GAME'")
            return null
        }

        val appId = intent.getIntExtra(EXTRA_APP_ID, -1)
        Timber.d("[IntentLaunchManager]: Extracted app_id: $appId from intent extras")

        if (appId <= 0) {
            Timber.w("[IntentLaunchManager]: Invalid or missing app_id in launch intent: $appId")
            return null
        }

        val containerConfigJson = intent.getStringExtra(EXTRA_CONTAINER_CONFIG)
        val containerConfig = if (containerConfigJson != null) {
            try {
                parseContainerConfig(containerConfigJson)
            } catch (e: Exception) {
                Timber.e(e, "[IntentLaunchManager]: Failed to parse container configuration JSON")
                null
            }
        } else {
            null
        }

        return LaunchRequest(appId, containerConfig)
    }

    fun applyTemporaryConfigOverride(context: Context, appId: Int, configOverride: ContainerData) {
        try {
            TemporaryConfigStore.setOverride(appId, configOverride)

            if (ContainerUtils.hasContainer(context, appId)) {
                val container = ContainerUtils.getContainer(context, appId)

                // Backup original config before applying override (only once)
                if (TemporaryConfigStore.getOriginalConfig(appId) == null) {
                    val originalConfig = ContainerUtils.toContainerData(container)
                    TemporaryConfigStore.setOriginalConfig(appId, originalConfig)
                }

                // Get the effective config (merge base with override)
                val effectiveConfig = getEffectiveContainerConfig(context, appId)
                if (effectiveConfig != null) {
                    ContainerUtils.applyToContainer(context, container, effectiveConfig, saveToDisk = false)
                    Timber.i("[IntentLaunchManager]: Applied temporary config override for app $appId (in-memory only)")
                }
            } else {
                Timber.i("[IntentLaunchManager]: Stored temporary config override for app $appId (container will be created on launch)")
            }

        } catch (e: Exception) {
            Timber.e(e, "[IntentLaunchManager]: Failed to apply temporary config override for app $appId")
            throw e
        }
    }

    fun getEffectiveContainerConfig(context: Context, appId: Int): ContainerData? {
        return try {
            val baseConfig = if (ContainerUtils.hasContainer(context, appId)) {
                val container = ContainerUtils.getContainer(context, appId)
                ContainerUtils.toContainerData(container)
            } else {
                null
            }

            val override = TemporaryConfigStore.getOverride(appId)

            when {
                override != null && baseConfig != null -> mergeConfigurations(baseConfig, override)
                override != null -> override
                else -> baseConfig
            }
        } catch (e: Exception) {
            Timber.e(e, "[IntentLaunchManager]: Failed to get effective container config for app $appId")
            null
        }
    }

    fun clearTemporaryOverride(appId: Int) {
        TemporaryConfigStore.clearOverride(appId)
        Timber.d("[IntentLaunchManager]: Cleared temporary config override for app $appId")
    }

    fun clearAllTemporaryOverrides() {
        TemporaryConfigStore.clearAll()
        Timber.d("[IntentLaunchManager]: Cleared all temporary config overrides")
    }

    fun restoreOriginalConfiguration(context: Context, appId: Int) {
        try {
            val originalConfig = TemporaryConfigStore.getOriginalConfig(appId)
            if (originalConfig != null && ContainerUtils.hasContainer(context, appId)) {
                val container = ContainerUtils.getContainer(context, appId)
                ContainerUtils.applyToContainer(context, container, originalConfig, saveToDisk = false)
                Timber.i("[IntentLaunchManager]: Restored original configuration for app $appId")
            }
        } catch (e: Exception) {
            Timber.e(e, "[IntentLaunchManager]: Failed to restore original configuration for app $appId")
        }
    }

    fun hasTemporaryOverride(appId: Int): Boolean {
        return TemporaryConfigStore.hasOverride(appId)
    }

    fun getTemporaryOverride(appId: Int): ContainerData? {
        return TemporaryConfigStore.getOverride(appId)
    }

    fun getOriginalConfig(appId: Int): ContainerData? {
        return TemporaryConfigStore.getOriginalConfig(appId)
    }

    fun setOriginalConfig(appId: Int, config: ContainerData) {
        TemporaryConfigStore.setOriginalConfig(appId, config)
    }

    private fun validateContainerConfig(config: ContainerData): List<String> {
        val issues = mutableListOf<String>()

        if (!config.screenSize.matches(Regex("\\d+x\\d+"))) {
            issues.add("Invalid screen size format: ${config.screenSize}. Expected format: WIDTHxHEIGHT (e.g., 1920x1080)")
        }

        if (config.cpuList.isNotEmpty() && !config.cpuList.matches(Regex("\\d+(,\\d+)*"))) {
            issues.add("Invalid CPU list format: ${config.cpuList}. Expected comma-separated numbers (e.g., 0,1,2,3)")
        }

        if (!config.videoMemorySize.matches(Regex("\\d+"))) {
            issues.add("Invalid video memory size: ${config.videoMemorySize}. Expected numeric value in MB")
        }

        if (config.drives.isNotEmpty() && !config.drives.matches(Regex("([A-Z]:([^:]+))*"))) {
            issues.add("Invalid drives format: ${config.drives}. Expected format: LETTER:PATH (e.g., C:/path/to/drive)")
        }

        return issues
    }

    private fun parseContainerConfig(jsonString: String): ContainerData {
        if (jsonString.length > MAX_CONFIG_JSON_SIZE) {
            throw IllegalArgumentException("Container configuration JSON too large (max ${MAX_CONFIG_JSON_SIZE / 1000}KB)")
        }

        val json = JSONObject(jsonString)

        // Only include non-default values to avoid overriding existing container settings
        val config = ContainerData(
            name = if (json.has("name")) json.getString("name") else "",
            screenSize = if (json.has("screenSize")) json.getString("screenSize") else Container.DEFAULT_SCREEN_SIZE,
            envVars = if (json.has("envVars")) json.getString("envVars") else Container.DEFAULT_ENV_VARS,
            graphicsDriver = if (json.has("graphicsDriver")) json.getString("graphicsDriver") else Container.DEFAULT_GRAPHICS_DRIVER,
            graphicsDriverVersion = if (json.has("graphicsDriverVersion")) json.getString("graphicsDriverVersion") else "",
            dxwrapper = if (json.has("dxwrapper")) json.getString("dxwrapper") else Container.DEFAULT_DXWRAPPER,
            dxwrapperConfig = if (json.has("dxwrapperConfig")) json.getString("dxwrapperConfig") else "",
            audioDriver = if (json.has("audioDriver")) json.getString("audioDriver") else Container.DEFAULT_AUDIO_DRIVER,
            wincomponents = if (json.has("wincomponents")) json.getString("wincomponents") else Container.DEFAULT_WINCOMPONENTS,
            drives = if (json.has("drives")) json.getString("drives") else Container.DEFAULT_DRIVES,
            execArgs = if (json.has("execArgs")) json.getString("execArgs") else "",
            executablePath = if (json.has("executablePath")) json.getString("executablePath") else "",
            installPath = if (json.has("installPath")) json.getString("installPath") else "",
            showFPS = if (json.has("showFPS")) json.getBoolean("showFPS") else false,
            launchRealSteam = if (json.has("launchRealSteam")) json.getBoolean("launchRealSteam") else false,
            cpuList = if (json.has("cpuList")) json.getString("cpuList") else Container.getFallbackCPUList(),
            cpuListWoW64 = if (json.has("cpuListWoW64")) json.getString("cpuListWoW64") else Container.getFallbackCPUListWoW64(),
            wow64Mode = if (json.has("wow64Mode")) json.getBoolean("wow64Mode") else true,
            startupSelection = if (json.has("startupSelection")) json.getInt("startupSelection").toByte() else Container.STARTUP_SELECTION_ESSENTIAL.toInt().toByte(),
            box86Version = if (json.has("box86Version")) json.getString("box86Version") else "",
            box64Version = if (json.has("box64Version")) json.getString("box64Version") else "",
            box86Preset = if (json.has("box86Preset")) json.getString("box86Preset") else "",
            box64Preset = if (json.has("box64Preset")) json.getString("box64Preset") else "",
            desktopTheme = if (json.has("desktopTheme")) json.getString("desktopTheme") else "",
            csmt = if (json.has("csmt")) json.getBoolean("csmt") else true,
            videoPciDeviceID = if (json.has("videoPciDeviceID")) json.getInt("videoPciDeviceID") else 1728,
            offScreenRenderingMode = if (json.has("offScreenRenderingMode")) json.getString("offScreenRenderingMode") else "fbo",
            strictShaderMath = if (json.has("strictShaderMath")) json.getBoolean("strictShaderMath") else true,
            videoMemorySize = if (json.has("videoMemorySize")) json.getString("videoMemorySize") else "2048",
            mouseWarpOverride = if (json.has("mouseWarpOverride")) json.getString("mouseWarpOverride") else "disable",
            sdlControllerAPI = if (json.has("sdlControllerAPI")) json.getBoolean("sdlControllerAPI") else true,
            enableXInput = if (json.has("enableXInput")) json.getBoolean("enableXInput") else true,
            enableDInput = if (json.has("enableDInput")) json.getBoolean("enableDInput") else true,
            dinputMapperType = if (json.has("dinputMapperType")) json.getInt("dinputMapperType").toByte() else 1.toByte(),
            disableMouseInput = if (json.has("disableMouseInput")) json.getBoolean("disableMouseInput") else false,
            shaderBackend = if (json.has("shaderBackend")) json.getString("shaderBackend") else "glsl",
            useGLSL = if (json.has("useGLSL")) json.getString("useGLSL") else "enabled"
        )

        val validationIssues = validateContainerConfig(config)
        if (validationIssues.isNotEmpty()) {
            Timber.w("[IntentLaunchManager]: Container configuration validation issues: ${validationIssues.joinToString("; ")}")
        }

        return config
    }

    private fun mergeConfigurations(base: ContainerData, override: ContainerData): ContainerData {
        // Quick return if no actual overrides
        if (override == base) return base

        return ContainerData(
            name = override.name.ifEmpty { base.name },
            screenSize = if (override.screenSize != Container.DEFAULT_SCREEN_SIZE) override.screenSize else base.screenSize,
            envVars = if (override.envVars != Container.DEFAULT_ENV_VARS) override.envVars else base.envVars,
            graphicsDriver = if (override.graphicsDriver != Container.DEFAULT_GRAPHICS_DRIVER) override.graphicsDriver else base.graphicsDriver,
            graphicsDriverVersion = override.graphicsDriverVersion.ifEmpty { base.graphicsDriverVersion },
            dxwrapper = if (override.dxwrapper != Container.DEFAULT_DXWRAPPER) override.dxwrapper else base.dxwrapper,
            dxwrapperConfig = override.dxwrapperConfig.ifEmpty { base.dxwrapperConfig },
            audioDriver = if (override.audioDriver != Container.DEFAULT_AUDIO_DRIVER) override.audioDriver else base.audioDriver,
            wincomponents = if (override.wincomponents != Container.DEFAULT_WINCOMPONENTS) override.wincomponents else base.wincomponents,
            drives = if (override.drives != Container.DEFAULT_DRIVES) override.drives else base.drives,
            execArgs = override.execArgs.ifEmpty { base.execArgs },
            executablePath = override.executablePath.ifEmpty { base.executablePath },
            installPath = override.installPath.ifEmpty { base.installPath },
            showFPS = override.showFPS,
            launchRealSteam = override.launchRealSteam,
            cpuList = if (override.cpuList != Container.getFallbackCPUList()) override.cpuList else base.cpuList,
            cpuListWoW64 = if (override.cpuListWoW64 != Container.getFallbackCPUListWoW64()) override.cpuListWoW64 else base.cpuListWoW64,
            wow64Mode = override.wow64Mode,
            startupSelection = override.startupSelection,
            box86Version = override.box86Version.ifEmpty { base.box86Version },
            box64Version = override.box64Version.ifEmpty { base.box64Version },
            box86Preset = override.box86Preset.ifEmpty { base.box86Preset },
            box64Preset = override.box64Preset.ifEmpty { base.box64Preset },
            desktopTheme = override.desktopTheme.ifEmpty { base.desktopTheme },
            csmt = override.csmt,
            videoPciDeviceID = override.videoPciDeviceID,
            offScreenRenderingMode = override.offScreenRenderingMode,
            strictShaderMath = override.strictShaderMath,
            videoMemorySize = override.videoMemorySize,
            mouseWarpOverride = override.mouseWarpOverride,
            sdlControllerAPI = override.sdlControllerAPI,
            enableXInput = override.enableXInput,
            enableDInput = override.enableDInput,
            dinputMapperType = override.dinputMapperType,
            disableMouseInput = override.disableMouseInput,
            shaderBackend = override.shaderBackend,
            useGLSL = override.useGLSL
        )
    }
}

private object TemporaryConfigStore {
    private val overrides = mutableMapOf<Int, ContainerData>()
    private val originalConfigs = mutableMapOf<Int, ContainerData>()

    fun setOverride(appId: Int, config: ContainerData) {
        overrides[appId] = config
    }

    fun getOverride(appId: Int): ContainerData? {
        return overrides[appId]
    }

    fun clearOverride(appId: Int) {
        overrides.remove(appId)
        originalConfigs.remove(appId)
    }

    fun hasOverride(appId: Int): Boolean {
        return overrides.containsKey(appId)
    }

    fun setOriginalConfig(appId: Int, config: ContainerData) {
        originalConfigs[appId] = config
    }

    fun getOriginalConfig(appId: Int): ContainerData? {
        return originalConfigs[appId]
    }

    fun clearAll() {
        overrides.clear()
        originalConfigs.clear()
    }
}
