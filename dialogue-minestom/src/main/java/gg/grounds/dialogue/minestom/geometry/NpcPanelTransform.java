package gg.grounds.dialogue.minestom.geometry;

import gg.grounds.dialogue.minestom.MinestomSpeakerBinding.AnchorSnapshot;
import net.minestom.server.coordinate.*;

/** Fixed world scale, upright body-front plane. GUI y increases downwards. */
public record NpcPanelTransform(
    Point origin, Vec right, Vec normal, float bodyfrontYaw, double speechHeight) {
  public static final double UNIT = .025 * .32;

  public static NpcPanelTransform from(AnchorSnapshot anchor, Point eye) {
    if (!anchor.valid()) throw new IllegalArgumentException("Invalid anchor");
    var origin = anchor.feet().add(0, 1.9, 0);
    var toward = eye.sub(origin);
    double bearing =
        Math.hypot(toward.x(), toward.z()) < 1e-9
            ? anchor.bodyYaw()
            : Math.toDegrees(Math.atan2(-toward.x(), toward.z()));
    double offset = Math.clamp(Math.IEEEremainder(bearing - anchor.bodyYaw(), 360) * .25, -15, 15);
    float yaw = (float) (anchor.bodyYaw() + offset);
    double angle = Math.toRadians(yaw);
    return new NpcPanelTransform(
        origin,
        new Vec(Math.cos(angle), 0, Math.sin(angle)),
        new Vec(-Math.sin(angle), 0, Math.cos(angle)),
        yaw,
        72);
  }

  public double scale() {
    return .32;
  }

  public NpcPanelTransform withSpeechHeight(double height) {
    return new NpcPanelTransform(origin, right, normal, bodyfrontYaw, height);
  }

  public Point widgetToWorld(double x, double y) {
    return origin.add(right.mul((x - 160) * UNIT)).add(0, (speechHeight - y) * UNIT, 0);
  }

  public Point worldToWidget(Point p) {
    var d = p.sub(origin).asVec();
    return new Vec(d.dot(right) / UNIT + 160, speechHeight - d.y() / UNIT, d.dot(normal));
  }

  public Pos position() {
    return new Pos(origin, bodyfrontYaw, 0);
  }
}
