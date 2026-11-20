package example

/** Same published application libraries, explicit native 26.3 effect transport. */
object GroundsPersonalDialogueDemo {
    @JvmStatic
    fun main(args: Array<String>) {
        System.setProperty("dialogue.profile", "grounds-26.3")
        DialogueDemo.run(
            args,
            PersonalDialogueDemo::spawn,
            PersonalDialogueDemo.packAssets(),
            GroundsDialogueDemo.effects(),
        )
    }
}
