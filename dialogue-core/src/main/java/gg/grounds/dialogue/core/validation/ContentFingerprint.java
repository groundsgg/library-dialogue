package gg.grounds.dialogue.core.validation;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.text.TextCatalog;
/** Versioned content fingerprint. No dependency on a JSON implementation. */
public final class ContentFingerprint {
 private ContentFingerprint() {}
 public static ContentPin pin(DialogueDefinition definition,TextCatalog texts) { return hash(canonicalBytes(Map.of("fingerprintVersion",1,"definition",definitionData(definition),"texts",texts.locales()))); }
 public static ContentPin hash(byte[] bytes) { try { return new ContentPin(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))); }catch(NoSuchAlgorithmException e){throw new AssertionError(e);} }
 public static byte[] definitionBytes(DialogueDefinition definition) { return canonicalBytes(definitionData(definition)); }
 private static Map<String,Object> m(Object... pairs) { var map=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)if(pairs[i+1]!=null)map.put((String)pairs[i],pairs[i+1]);return map; }
 public static Map<String,Object> argumentData(Map<String,ArgumentValue> values) {var result=new LinkedHashMap<String,Object>();values.forEach((k,v)->result.put(k,switch(v){case ArgumentValue.Text t->t.value();case ArgumentValue.Flag f->f.value();case ArgumentValue.Number n->n.value();case ArgumentValue.Nil nil->null;}));return result;}
 private static Map<String,Object> condition(Condition c) {return m("key",c.key(),"arguments",argumentData(c.arguments()),"negated",c.negated());}
 public static Map<String,Object> definitionData(DialogueDefinition d) {
  var nodes=new LinkedHashMap<String,Object>();d.nodes().forEach((id,n)->nodes.put(id,switch(n){
   case Message x -> m("type","message","speaker",x.speaker(),"text",x.text(),"presentation",x.gesture()==null?null:m("gesture",x.gesture()),"next",x.next(),"choices",x.choices().isEmpty()?null:x.choices().stream().map(c->m("id",c.id(),"text",c.text(),"to",c.to(),"default",c.defaultChoice(),"condition",c.condition()==null?null:condition(c.condition()))).toList());
   case Branch x -> m("type","branch","cases",x.cases().stream().map(c->m("condition",condition(c.condition()),"to",c.to())).toList(),"defaultTo",x.defaultTo(),"unavailableTo",x.unavailableTo());
   case Action x -> m("type","action","action",x.key(),"arguments",argumentData(x.arguments()),"onSuccess",x.onSuccess(),"onRejected",x.onRejected(),"onUnavailable",x.onUnavailable(),"onUncertain",x.onUncertain());
   case Outcome x -> m("type","outcome","milestone",x.milestone(),"delivery",x.delivery().name().toLowerCase(Locale.ROOT),"onAcknowledged",x.onAcknowledged(),"onUnavailable",x.onUnavailable(),"onUncertain",x.onUncertain());
   case End x -> m("type","end","reason",x.reason());
  }));
  var l=d.interaction().lookAway();var p=d.presentation();
  return m("schemaVersion",1,"id",d.id(),"contentVersion",d.contentVersion(),"entry",d.entry(),"locales",m("default",d.locales().defaultTag(),"supported",d.locales().supported()),"capabilities",m("required",d.capabilities().required().stream().sorted().toList(),"optional",d.capabilities().optional().stream().sorted().toList()),"interaction",m("lookAway",m("enabled",l.enabled(),"exitAngleDegrees",l.exitAngleDegrees(),"exitDelayMs",l.exitDelayMs(),"resetAngleDegrees",l.resetAngleDegrees()),"maxDistanceBlocks",d.interaction().maxDistanceBlocks()),"presentation",m("focus",p.focus(),"nearFraming",p.nearFraming(),"vignette",p.vignette(),"hideHud",p.hideHud().stream().sorted().toList()),"nodes",nodes);
 }
 public static byte[] canonicalBytes(Object value) {var out=new StringBuilder();write(value,out,0);return out.toString().getBytes(StandardCharsets.UTF_8);}
 private static int compareCodePoints(String a,String b) {int x=0,y=0;while(x<a.length()&&y<b.length()){int p=a.codePointAt(x),q=b.codePointAt(y);if(p!=q)return Integer.compare(p,q);x+=Character.charCount(p);y+=Character.charCount(q);}return Integer.compare(a.length()-x,b.length()-y);}
 private static void write(Object v,StringBuilder out,int depth) {
  if(depth>64)throw new IllegalArgumentException("CANONICAL_DEPTH");
  if(v==null){out.append("null");return;}if(v instanceof String s){quote(s,out);return;}if(v instanceof Boolean b){out.append(b);return;}
  if(v instanceof java.lang.Number n){BigDecimal d=n instanceof BigDecimal x?x:new BigDecimal(n.toString());if(d.precision()>128||Math.abs((long)d.precision()-d.scale()-1)>308)throw new IllegalArgumentException("NUMBER_LIMIT");out.append(d.signum()==0?"0":d.stripTrailingZeros().toPlainString());return;}
  if(v instanceof Map<?,?> map){var keys=new ArrayList<String>();for(Object k:map.keySet()){if(!(k instanceof String))throw new IllegalArgumentException("Nonstring map key");keys.add((String)k);}keys.sort(ContentFingerprint::compareCodePoints);out.append('{');boolean first=true;for(String k:keys){if(!first)out.append(',');first=false;quote(k,out);out.append(':');write(map.get(k),out,depth+1);}out.append('}');return;}
  if(v instanceof List<?> list){out.append('[');for(int i=0;i<list.size();i++){if(i>0)out.append(',');write(list.get(i),out,depth+1);}out.append(']');return;}
  throw new IllegalArgumentException("Not a canonical scalar or collection: "+v.getClass().getName());
 }
 private static void quote(String s,StringBuilder out) {if(!DialogueValidator.validUnicode(s))throw new IllegalArgumentException("UNICODE_INVALID");out.append('"');for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\b'->out.append("\\b");case '\f'->out.append("\\f");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}}}out.append('"');}
}
