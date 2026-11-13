package com.nuvio.app.features.player

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val SUBTITLE_DEVICE_PREFERENCES_VERSION = 1
private const val SUBTITLE_DEVICE_PREFERENCES_PAIR_VERSION = 2

/**
 * A versioned, profile-scoped record stored only in this application installation.
 *
 * Colors are stored as ARGB hex strings because Compose Color is not serializable.
 * SubtitleAppearance is already serializable and keeps custom-font IDs local to the device.
 */
@Serializable
private data class SubtitleDevicePreferencesRecord(
    val version: Int = SUBTITLE_DEVICE_PREFERENCES_VERSION,
    val textColor: String = SubtitleStyleState.DEFAULT.textColor.toStorageHexString(),
    val backgroundColor: String = SubtitleStyleState.DEFAULT.backgroundColor.toStorageHexString(),
    val outlineColor: String = SubtitleStyleState.DEFAULT.outlineColor.toStorageHexString(),
    val outlineEnabled: Boolean = SubtitleStyleState.DEFAULT.outlineEnabled,
    val outlineWidth: Int = SubtitleStyleState.DEFAULT.outlineWidth,
    val bold: Boolean = SubtitleStyleState.DEFAULT.bold,
    val fontSizeSp: Int = SubtitleStyleState.DEFAULT.fontSizeSp,
    val bottomOffset: Int = SubtitleStyleState.DEFAULT.bottomOffset,
    val stripSdh: Boolean = SubtitleStyleState.DEFAULT.stripSdh,
    val useForcedSubtitles: Boolean = SubtitleStyleState.DEFAULT.useForcedSubtitles,
    val showOnlyPreferredLanguages: Boolean = SubtitleStyleState.DEFAULT.showOnlyPreferredLanguages,
    val appearance: SubtitleAppearance = SubtitleAppearance(),
)

@Serializable
private data class SubtitleDevicePreferencesPair(
    val version: Int = SUBTITLE_DEVICE_PREFERENCES_PAIR_VERSION,
    val landscape: SubtitleDevicePreferencesRecord = SubtitleStyleState.DEFAULT.toDevicePreferencesRecord(),
    val portrait: SubtitleDevicePreferencesRecord = SubtitleStyleState.PORTRAIT_DEFAULT.toDevicePreferencesRecord(),
)

private val subtitleDevicePreferencesJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private fun SubtitleStyleState.toDevicePreferencesRecord() = SubtitleDevicePreferencesRecord(
    textColor = textColor.toStorageHexString(),
    backgroundColor = backgroundColor.toStorageHexString(),
    outlineColor = outlineColor.toStorageHexString(),
    outlineEnabled = outlineEnabled,
    outlineWidth = outlineWidth,
    bold = bold,
    fontSizeSp = fontSizeSp,
    bottomOffset = bottomOffset,
    stripSdh = stripSdh,
    useForcedSubtitles = useForcedSubtitles,
    showOnlyPreferredLanguages = showOnlyPreferredLanguages,
    appearance = appearance.normalized(),
)

private fun SubtitleDevicePreferencesRecord.toSubtitleStyleState(): SubtitleStyleState {
    val defaults = SubtitleStyleState.DEFAULT
    return SubtitleStyleState(
        textColor = subtitleColorFromStorage(textColor) ?: defaults.textColor,
        backgroundColor = subtitleColorFromStorage(backgroundColor) ?: defaults.backgroundColor,
        outlineColor = subtitleColorFromStorage(outlineColor) ?: defaults.outlineColor,
        outlineEnabled = outlineEnabled,
        outlineWidth = outlineWidth.coerceAtLeast(0),
        bold = bold,
        fontSizeSp = fontSizeSp.coerceIn(subtitleFontSizeRangeSp),
        bottomOffset = bottomOffset.coerceIn(0, 200),
        stripSdh = stripSdh,
        useForcedSubtitles = useForcedSubtitles,
        showOnlyPreferredLanguages = showOnlyPreferredLanguages,
        appearance = appearance.normalized(),
    )
}

/** Behavior flags are shared by both orientations; only appearance and position are separate. */
internal fun SubtitleStyleState.withSharedSubtitleBehaviorFrom(other: SubtitleStyleState): SubtitleStyleState = copy(
    stripSdh = other.stripSdh,
    useForcedSubtitles = other.useForcedSubtitles,
    showOnlyPreferredLanguages = other.showOnlyPreferredLanguages,
)

private fun SubtitleStyleState.normalizedForDevice(): SubtitleStyleState = copy(
    fontSizeSp = fontSizeSp.coerceIn(subtitleFontSizeRangeSp),
    bottomOffset = bottomOffset.coerceIn(0, 200),
    outlineWidth = outlineWidth.coerceAtLeast(0),
    appearance = appearance.normalized(),
)

internal fun encodeSubtitleDevicePreferences(style: SubtitleStyleState): String =
    subtitleDevicePreferencesJson.encodeToString(style.toDevicePreferencesRecord())

internal fun encodeSubtitleDevicePreferencesPair(
    landscape: SubtitleStyleState,
    portrait: SubtitleStyleState,
): String = subtitleDevicePreferencesJson.encodeToString(
    SubtitleDevicePreferencesPair(
        landscape = landscape.toDevicePreferencesRecord(),
        portrait = portrait.toDevicePreferencesRecord(),
    ),
)

private fun decodeSubtitleDevicePreferencesRecord(value: String): SubtitleStyleState? =
    runCatching {
        val record = subtitleDevicePreferencesJson.decodeFromString<SubtitleDevicePreferencesRecord>(value)
        record.takeIf { decoded -> decoded.version in 1..SUBTITLE_DEVICE_PREFERENCES_VERSION }
            ?.toSubtitleStyleState()
    }.getOrNull()

/**
 * Decodes either the current two-orientation record or a legacy single-style record.
 * Legacy records are treated as the landscape style and receive the portrait defaults.
 */
internal fun decodeSubtitleDevicePreferencesPair(
    value: String?,
): Pair<SubtitleStyleState, SubtitleStyleState>? {
    val raw = value ?: return null
    return runCatching {
        val jsonObject = subtitleDevicePreferencesJson.parseToJsonElement(raw) as? JsonObject
            ?: return@runCatching null
        val version = (jsonObject["version"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1
        if (version < SUBTITLE_DEVICE_PREFERENCES_PAIR_VERSION) {
            val landscape = decodeSubtitleDevicePreferencesRecord(raw) ?: return@runCatching null
            landscape to SubtitleStyleState.PORTRAIT_DEFAULT.withSharedSubtitleBehaviorFrom(landscape)
        } else {
            val landscape = jsonObject["landscape"]?.toString()
                ?.let(::decodeSubtitleDevicePreferencesRecord)
                ?: return@runCatching null
            val portrait = jsonObject["portrait"]?.toString()
                ?.let(::decodeSubtitleDevicePreferencesRecord)
                ?: SubtitleStyleState.PORTRAIT_DEFAULT
            landscape to portrait.withSharedSubtitleBehaviorFrom(landscape)
        }
    }.getOrNull()
}

internal fun decodeSubtitleDevicePreferences(value: String?): SubtitleStyleState? =
    decodeSubtitleDevicePreferencesPair(value)?.first

/**
 * Keeps subtitle appearance preferences local to this installation and profile.
 *
 * Existing profile-scoped player settings are migrated on first read. The legacy values
 * remain untouched so account synchronization and account cleanup cannot destroy the
 * migration source before the new record has been persisted.
 */
internal object SubtitleDevicePreferencesRepository {
    fun loadOrMigratePair(
        profileId: Int,
        legacyStyle: SubtitleStyleState,
    ): Pair<SubtitleStyleState, SubtitleStyleState> {
        decodeSubtitleDevicePreferencesPair(SubtitleDevicePreferencesStorage.load(profileId))?.let {
            return normalizePair(it)
        }

        val landscape = legacyStyle.normalizedForDevice()
        val portrait = SubtitleStyleState.PORTRAIT_DEFAULT
            .withSharedSubtitleBehaviorFrom(landscape)
            .normalizedForDevice()
        SubtitleDevicePreferencesStorage.saveForMigration(
            profileId,
            encodeSubtitleDevicePreferencesPair(landscape, portrait),
        )
        return decodeSubtitleDevicePreferencesPair(SubtitleDevicePreferencesStorage.load(profileId))
            ?.let(::normalizePair)
            ?: (landscape to portrait)
    }

    fun loadOrMigrate(profileId: Int, legacyStyle: SubtitleStyleState): SubtitleStyleState =
        loadOrMigratePair(profileId, legacyStyle).first

    /**
     * Migrates a legacy profile once. Called for every profile before account cleanup,
     * because the platform cleaner removes the whole legacy player-settings namespace.
     */
    fun migrateLegacy(profileId: Int, legacyStyle: SubtitleStyleState) {
        if (decodeSubtitleDevicePreferencesPair(SubtitleDevicePreferencesStorage.load(profileId)) != null) return
        val landscape = legacyStyle.normalizedForDevice()
        val portrait = SubtitleStyleState.PORTRAIT_DEFAULT
            .withSharedSubtitleBehaviorFrom(landscape)
            .normalizedForDevice()
        SubtitleDevicePreferencesStorage.saveForMigration(
            profileId,
            encodeSubtitleDevicePreferencesPair(landscape, portrait),
        )
    }

    fun load(profileId: Int): SubtitleStyleState? =
        decodeSubtitleDevicePreferencesPair(SubtitleDevicePreferencesStorage.load(profileId))?.first

    fun loadPortrait(profileId: Int): SubtitleStyleState? =
        decodeSubtitleDevicePreferencesPair(SubtitleDevicePreferencesStorage.load(profileId))?.second

    fun removeProfile(profileId: Int) {
        SubtitleDevicePreferencesStorage.remove(profileId)
    }

    fun save(profileId: Int, style: SubtitleStyleState) {
        val current = currentPair(profileId)
        val landscape = style.normalizedForDevice()
        val portrait = current.second
            .withSharedSubtitleBehaviorFrom(landscape)
            .normalizedForDevice()
        SubtitleDevicePreferencesStorage.save(
            profileId,
            encodeSubtitleDevicePreferencesPair(landscape, portrait),
        )
    }

    fun savePortrait(profileId: Int, style: SubtitleStyleState) {
        val current = currentPair(profileId)
        val landscape = current.first.normalizedForDevice()
        val portrait = style
            .withSharedSubtitleBehaviorFrom(landscape)
            .normalizedForDevice()
        SubtitleDevicePreferencesStorage.save(
            profileId,
            encodeSubtitleDevicePreferencesPair(landscape, portrait),
        )
    }

    fun savePair(
        profileId: Int,
        landscapeStyle: SubtitleStyleState,
        portraitStyle: SubtitleStyleState,
    ) {
        val landscape = landscapeStyle.normalizedForDevice()
        val portrait = portraitStyle
            .withSharedSubtitleBehaviorFrom(landscape)
            .normalizedForDevice()
        SubtitleDevicePreferencesStorage.save(
            profileId,
            encodeSubtitleDevicePreferencesPair(landscape, portrait),
        )
    }

    private fun currentPair(profileId: Int): Pair<SubtitleStyleState, SubtitleStyleState> =
        decodeSubtitleDevicePreferencesPair(SubtitleDevicePreferencesStorage.load(profileId))
            ?: (SubtitleStyleState.DEFAULT to SubtitleStyleState.PORTRAIT_DEFAULT)

    private fun normalizePair(
        pair: Pair<SubtitleStyleState, SubtitleStyleState>,
    ): Pair<SubtitleStyleState, SubtitleStyleState> {
        val landscape = pair.first.normalizedForDevice()
        val portrait = pair.second
            .withSharedSubtitleBehaviorFrom(landscape)
            .normalizedForDevice()
        return landscape to portrait
    }
}

internal expect object SubtitleDevicePreferencesStorage {
    fun load(profileId: Int): String?
    fun save(profileId: Int, value: String)
    fun saveForMigration(profileId: Int, value: String)
    fun remove(profileId: Int)
}
