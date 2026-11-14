package gg.grounds.dialogue.presentation;
import java.util.*;
import gg.grounds.dialogue.core.text.*;
/** Full-text layout remains fixed as the core reveals its grapheme prefix. */
public final class TextLayout {
 public record TextLimits(double maxWidth,int maxLines){public TextLimits {if(!Double.isFinite(maxWidth)||maxWidth<=0||maxLines<1||maxLines>1024)throw new IllegalArgumentException("Invalid text layout bounds");}}
 public record Line(int startGrapheme,List<String> graphemes,List<Double> advances,double width){public Line {graphemes=List.copyOf(graphemes);advances=List.copyOf(advances);}}
 public record TextLayoutResult(List<Line> lines,double width,double height,String problem){public TextLayoutResult {lines=List.copyOf(lines);}public boolean isValid(){return problem==null;}public List<String> visible(int revealed){if(revealed<0)throw new IllegalArgumentException("Negative reveal");return lines.stream().map(line->String.join("",line.graphemes().subList(0,Math.min(line.graphemes().size(),Math.max(0,revealed-line.startGrapheme()))))).toList();}}
 private static boolean newline(String g){return g.equals("\n")||g.equals("\r")||g.equals("\r\n");}
 public TextLayoutResult layout(ResolvedText text,FontMetrics metrics,TextLimits limits){var clusters=Graphemes.split(text.plain());var widths=new ArrayList<Double>();double lineHeight=metrics.lineHeight();if(!Double.isFinite(lineHeight)||lineHeight<=0)return invalid("FONT_METRICS_INVALID");
  for(String g:clusters){if(newline(g)){widths.add(0d);continue;}double width;try{width=metrics.width(g);}catch(IllegalArgumentException e){return invalid("MISSING_GLYPH");}if(!Double.isFinite(width)||width<0)return invalid("FONT_METRICS_INVALID");if(width>limits.maxWidth())return invalid("LAYOUT_OVERFLOW");widths.add(width);}
  var lines=new ArrayList<Line>();int start=0;while(start<clusters.size()){if(lines.size()>=limits.maxLines())return invalid("LAYOUT_OVERFLOW");int end=start,lastSpace=-1;double width=0;while(end<clusters.size()&&!newline(clusters.get(end))&&width+widths.get(end)<=limits.maxWidth()){width+=widths.get(end);if(clusters.get(end).isBlank())lastSpace=end;end++;}if(end<clusters.size()&&!newline(clusters.get(end))&&lastSpace>=start){end=lastSpace+1;width=0;for(int i=start;i<end;i++)width+=widths.get(i);}lines.add(new Line(start,clusters.subList(start,end),widths.subList(start,end),width));start=end;if(start<clusters.size()&&newline(clusters.get(start)))start++;}
  if(clusters.isEmpty()||newline(clusters.getLast())){if(lines.size()>=limits.maxLines())return invalid("LAYOUT_OVERFLOW");lines.add(new Line(clusters.size(),List.of(),List.of(),0));}
  return new TextLayoutResult(lines,lines.stream().mapToDouble(Line::width).max().orElse(0),lines.size()*lineHeight,null);
 }
 private static TextLayoutResult invalid(String problem){return new TextLayoutResult(List.of(),0,0,problem);}
}
