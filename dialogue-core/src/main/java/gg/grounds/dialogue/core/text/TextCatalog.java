package gg.grounds.dialogue.core.text;
import java.util.*;
public record TextCatalog(Map<String,Map<String,String>> locales) {
 public TextCatalog { var copy=new LinkedHashMap<String,Map<String,String>>();locales.forEach((k,v)->copy.put(k,Map.copyOf(v)));locales=Collections.unmodifiableMap(copy); }
}
