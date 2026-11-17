package example;

/** Same published application libraries, explicit native 26.3 effect transport. */
public final class GroundsPersonalDialogueDemo {
  public static void main(String[] args) throws Exception {
    System.setProperty("dialogue.profile", "grounds-26.3");
    DialogueDemo.run(
        args,
        PersonalDialogueDemo::spawn,
        PersonalDialogueDemo.packAssets(),
        GroundsDialogueDemo.effects());
  }
}
