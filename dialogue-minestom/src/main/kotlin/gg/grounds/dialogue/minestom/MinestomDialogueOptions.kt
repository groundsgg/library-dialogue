package gg.grounds.dialogue.minestom

import gg.grounds.dialogue.core.runtime.RuntimeOptions
import gg.grounds.dialogue.minestom.asset.AssetBundle
import gg.grounds.dialogue.minestom.asset.MinecraftPackProfile
import gg.grounds.dialogue.minestom.channel.OutputChannels
import gg.grounds.dialogue.presentation.UiLabelSource
import java.time.Duration

@JvmRecord
data class MinestomDialogueOptions(
    val budgets: RuntimeOptions.Budgets,
    val profile: MinecraftPackProfile,
    val assets: AssetBundle,
    val channels: OutputChannels,
    val labels: UiLabelSource,
    val distanceGrace: Duration,
) {
    init {
        require(
            assets.profile() == profile &&
                !distanceGrace.isNegative &&
                distanceGrace <= Duration.ofSeconds(60)
        ) {
            "Invalid minestom dialogue options"
        }
    }
}
