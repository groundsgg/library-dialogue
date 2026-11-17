package gg.grounds.dialogue.minestom.geometry;

import gg.grounds.dialogue.minestom.MinestomSpeakerBinding.AnchorSnapshot;
import gg.grounds.dialogue.presentation.WidgetFrame.Rect;
import net.minestom.server.coordinate.*;

/** Fixed world scale, upright body-front plane and optional hinged answer column. */
public record NpcPanelTransform(
    Point origin, Vec right, Vec normal, float bodyfrontYaw, double speechHeight, Fold fold) {
  public static final double UNIT = .025 * .32;

  public record Fold(double pivot, double degrees) {
    public Fold {
      if (!Double.isFinite(pivot) || !Double.isFinite(degrees) || Math.abs(degrees) > 45)
        throw new IllegalArgumentException("Invalid panel fold");
    }
  }

  public NpcPanelTransform(
      Point origin, Vec right, Vec normal, float bodyfrontYaw, double speechHeight) {
    this(origin, right, normal, bodyfrontYaw, speechHeight, null);
  }

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
    return new NpcPanelTransform(origin, right, normal, bodyfrontYaw, height, fold);
  }

  public NpcPanelTransform withFold(Fold value) {
    return new NpcPanelTransform(origin, right, normal, bodyfrontYaw, speechHeight, value);
  }

  public NpcPanelTransform withChoiceFold(double pivot, double degrees) {
    var value = new Fold(pivot, degrees);
    return withFold(degrees == 0 ? null : value);
  }

  public NpcPanelTransform planeFor(Rect bounds) {
    return fold != null && bounds.x() + bounds.width() / 2 >= fold.pivot() ? choicePlane() : this;
  }

  private NpcPanelTransform choicePlane() {
    double radians = Math.toRadians(fold.degrees()), c = Math.cos(radians), s = Math.sin(radians);
    var r = right.mul(c).add(normal.mul(s));
    var n = normal.mul(c).sub(right.mul(s));
    var hinge = flatPoint(fold.pivot(), speechHeight);
    return new NpcPanelTransform(
        hinge.sub(r.mul((fold.pivot() - 160) * UNIT)),
        r,
        n,
        (float) (bodyfrontYaw + fold.degrees()),
        speechHeight);
  }

  private Point flatPoint(double x, double y) {
    return origin.add(right.mul((x - 160) * UNIT)).add(0, (speechHeight - y) * UNIT, 0);
  }

  public Point widgetToWorld(double x, double y) {
    return fold != null && x >= fold.pivot() ? choicePlane().flatPoint(x, y) : flatPoint(x, y);
  }

  public Point worldToWidget(Point p) {
    var d = p.sub(origin).asVec();
    double x = d.dot(right) / UNIT + 160;
    if (fold != null && x >= fold.pivot()) return choicePlane().worldToWidget(p);
    return new Vec(x, speechHeight - d.y() / UNIT, d.dot(normal));
  }

  public Pos position() {
    return new Pos(origin, bodyfrontYaw, 0);
  }
}
