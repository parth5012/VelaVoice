package helium314.keyboard.latin.quicktile

import helium314.keyboard.quicktile.DictationTileService as BaseDictationTileService

/**
 * Backward-compatibility alias for [BaseDictationTileService].
 *
 * In VelaBoard 4.1 (versionCode 4007), the Quick Settings tile was declared
 * using relative notation (`.quicktile.DictationTileService`) in AndroidManifest.xml,
 * which resolved against the manifest namespace to `helium314.keyboard.latin.quicktile.DictationTileService`.
 *
 * This class ensures that any lingering system intent, TileService binding, or test referencing
 * the legacy FQCN will find the class instead of failing with ClassNotFoundException.
 */
class DictationTileService : BaseDictationTileService()
