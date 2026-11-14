package gg.grounds.dialogue.json;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.math.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.text.TextCatalog;
import gg.grounds.dialogue.core.validation.*;
import gg.grounds.dialogue.core.validation.ValidationReport.*;
/** Bounded strict codec. Serializer classes never escape this module. */
public final class DialogueJsonCodec {
 private final ValidationLimits limits;
 public DialogueJsonCodec(){this(ValidationLimits.defaults());}
 public DialogueJsonCodec(ValidationLimits limits){this.limits=Objects.requireNonNull(limits);}
 public record DecodeResult(List<Problem> problems,ValidatedDialogue validated) { public DecodeResult {problems=List.copyOf(problems);} public boolean isValid(){return validated!=null;} }
 public DecodeResult decode(byte[] definition,Map<String,byte[]> texts,byte[] hostCatalog) {
  try {
   var root=obj(parse(definition,limits.definitionBytes(),"dialogue.json"));
   var d=definition(root);long total=0;var bundles=new LinkedHashMap<String,Map<String,String>>();
   if(texts.size()>limits.locales())throw fail("LOCALE_LIMIT","texts","/","Too many text files");
   for(var source:texts.entrySet()){total+=source.getValue().length;if(total>limits.textPackageBytes())throw fail("TEXT_PACKAGE_LIMIT","texts","/","Text package exceeds budget");var raw=obj(parse(source.getValue(),limits.textFileBytes(),"texts/"+source.getKey()+".json"));var map=new LinkedHashMap<String,String>();raw.entrySet().forEach(e->map.put(e.getKey(),stringValue(e.getValue())));bundles.put(source.getKey(),map);}
   var h=host(obj(parse(hostCatalog,limits.textPackageBytes(),"context/host-catalog.json")));
   var result=new DialogueValidator().validate(d,new TextCatalog(bundles),h,limits);return new DecodeResult(result.problems(),result.validated());
  } catch(DecodeFailure e) {return new DecodeResult(List.of(new Problem(e.code,Severity.ERROR,e.file,e.path,null,e.getMessage())),null);}
    catch(RuntimeException e) {return new DecodeResult(List.of(new Problem("FORMAT_INVALID",Severity.ERROR,"dialogue.json","/",null,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())),null);}
 }
 public byte[] encode(DialogueDefinition d){return ContentFingerprint.definitionBytes(d);}
 private JsonElement parse(byte[] bytes,int max,String file) {
  if(bytes.length>max)throw fail("CONTENT_LIMIT",file,"/","Input byte limit exceeded");
  try {
   String source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
   if(source.startsWith("\ufeff"))throw fail("UTF8_BOM",file,"/","UTF-8 BOM is not accepted");
   var reader=new JsonReader(new StringReader(source));reader.setStrictness(Strictness.STRICT);var result=read(reader,0,file);if(reader.peek()!=JsonToken.END_DOCUMENT)throw fail("TRAILING_DATA",file,reader.getPath(),"Trailing JSON data");return result;
  } catch(DecodeFailure e){throw e;}catch(IOException|RuntimeException e){throw fail("JSON_INVALID",file,"/",e.getMessage()==null?"Invalid JSON":e.getMessage());}
 }
 private JsonElement read(JsonReader r,int depth,String file)throws IOException {
  if(depth>limits.depth())throw fail("JSON_DEPTH",file,r.getPath(),"JSON nesting budget exceeded");
  return switch(r.peek()) {
   case BEGIN_OBJECT -> {var o=new JsonObject();var keys=new HashSet<String>();r.beginObject();while(r.hasNext()){String name=r.nextName();unicode(name,file,r.getPath());if(!keys.add(name))throw fail("JSON_DUPLICATE_KEY",file,r.getPath(),"Duplicate key "+name);o.add(name,read(r,depth+1,file));}r.endObject();yield o;}
   case BEGIN_ARRAY -> {var a=new JsonArray();r.beginArray();while(r.hasNext())a.add(read(r,depth+1,file));r.endArray();yield a;}
   case STRING -> {String s=r.nextString();unicode(s,file,r.getPath());yield new JsonPrimitive(s);}
   case NUMBER -> {String number=r.nextString();if(number.length()>440)throw fail("NUMBER_LIMIT",file,r.getPath(),"Oversized number token");var n=new BigDecimal(number);if(n.precision()>128||Math.abs((long)n.precision()-n.scale()-1)>308)throw fail("NUMBER_LIMIT",file,r.getPath(),"Numeric precision/exponent exceeded");yield new JsonPrimitive(n);}
   case BOOLEAN -> new JsonPrimitive(r.nextBoolean());
   case NULL -> {r.nextNull();yield JsonNull.INSTANCE;}
   default -> throw fail("JSON_INVALID",file,r.getPath(),"Expected a JSON value");
  };
 }
 private static void unicode(String s,String file,String path){if(!DialogueValidator.validUnicode(s))throw fail("UNICODE_INVALID",file,path,"Unpaired Unicode surrogate");}
 private static JsonObject obj(JsonElement e){if(e==null||!e.isJsonObject())throw new IllegalArgumentException("Expected object");return e.getAsJsonObject();}
 private static String stringValue(JsonElement e){if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Expected string");return e.getAsString();}
 private static String s(JsonObject o,String key){return stringValue(o.get(key));}
 private static boolean bool(JsonObject o,String key,boolean fallback){if(!o.has(key))return fallback;var e=o.get(key);if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("Expected boolean: "+key);return e.getAsBoolean();}
 private static BigDecimal number(JsonObject o,String key){var e=o.get(key);if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Expected number: "+key);return e.getAsBigDecimal();}
 private static List<JsonElement> array(JsonObject o,String key){if(!o.has(key)||!o.get(key).isJsonArray())throw new IllegalArgumentException("Expected array: "+key);return o.getAsJsonArray(key).asList();}
 private static List<String> strings(JsonObject o,String key){return array(o,key).stream().map(DialogueJsonCodec::stringValue).toList();}
 private static Set<String> stringSet(JsonObject o,String key){var l=strings(o,key);var s=new LinkedHashSet<>(l);if(s.size()!=l.size())throw new IllegalArgumentException("Duplicate array value: "+key);return s;}
 private static void fields(JsonObject o,String... allowed){var set=Set.of(allowed);for(String key:o.keySet())if(!set.contains(key))throw fail("FIELD_UNKNOWN","dialogue.json","/"+key,"Unknown field "+key);}
 private static JsonObject optionalObject(JsonObject o,String key){return o.has(key)?obj(o.get(key)):new JsonObject();}
 private static Map<String,ArgumentValue> arguments(JsonObject o){var result=new LinkedHashMap<String,ArgumentValue>();optionalObject(o,"arguments").entrySet().forEach(e->{var v=e.getValue();ArgumentValue a;if(v.isJsonNull())a=ArgumentValue.Nil.INSTANCE;else if(v.isJsonPrimitive()&&v.getAsJsonPrimitive().isString())a=new ArgumentValue.Text(v.getAsString());else if(v.isJsonPrimitive()&&v.getAsJsonPrimitive().isBoolean())a=new ArgumentValue.Flag(v.getAsBoolean());else if(v.isJsonPrimitive()&&v.getAsJsonPrimitive().isNumber())a=new ArgumentValue.Number(v.getAsBigDecimal());else throw new IllegalArgumentException("Arguments must be scalars");result.put(e.getKey(),a);});return result;}
 private static Condition condition(JsonElement e){var o=obj(e);fields(o,"key","arguments","negated");return new Condition(s(o,"key"),arguments(o),bool(o,"negated",false));}
 private DialogueDefinition definition(JsonObject o){
  fields(o,"schemaVersion","id","contentVersion","entry","locales","capabilities","interaction","presentation","nodes");if(number(o,"schemaVersion").intValueExact()!=1)throw fail("SCHEMA_VERSION","dialogue.json","/schemaVersion","Unsupported schema major version");
  var builder=DialogueBuilder.dialogue(s(o,"id")).version(s(o,"contentVersion")).entry(s(o,"entry"));var locale=obj(o.get("locales"));fields(locale,"default","supported");builder.locales(s(locale,"default"),strings(locale,"supported").toArray(String[]::new));
  if(o.has("capabilities")){var c=obj(o.get("capabilities"));fields(c,"required","optional");builder.capabilities(new Capabilities(stringSet(c,"required"),stringSet(c,"optional")));}
  if(o.has("interaction")){var i=obj(o.get("interaction"));fields(i,"lookAway","maxDistanceBlocks");var l=obj(i.get("lookAway"));fields(l,"enabled","exitAngleDegrees","exitDelayMs","resetAngleDegrees");builder.interaction(new Interaction(new LookAway(bool(l,"enabled",true),number(l,"exitAngleDegrees").doubleValue(),number(l,"exitDelayMs").longValueExact(),number(l,"resetAngleDegrees").doubleValue()),number(i,"maxDistanceBlocks").doubleValue()));}
  if(o.has("presentation")){var p=obj(o.get("presentation"));fields(p,"focus","nearFraming","vignette","hideHud");builder.presentation(new Presentation(s(p,"focus"),s(p,"nearFraming"),s(p,"vignette"),stringSet(p,"hideHud")));}
  obj(o.get("nodes")).entrySet().forEach(e->builder.node(e.getKey(),node(obj(e.getValue()))));return builder.build();
 }
 private Node node(JsonObject o){return switch(s(o,"type")){
  case "message" -> {fields(o,"type","speaker","text","presentation","choices","next");String gesture=null;if(o.has("presentation")){var p=obj(o.get("presentation"));fields(p,"gesture");gesture=s(p,"gesture");}var choices=new ArrayList<Choice>();if(o.has("choices"))for(var e:array(o,"choices")){var c=obj(e);fields(c,"id","text","to","default","condition");choices.add(new Choice(s(c,"id"),s(c,"text"),s(c,"to"),bool(c,"default",false),c.has("condition")?condition(c.get("condition")):null));}yield new Message(s(o,"speaker"),s(o,"text"),gesture,choices,o.has("next")?s(o,"next"):null);}
  case "branch" -> {fields(o,"type","cases","defaultTo","unavailableTo");var cases=new ArrayList<Case>();for(var e:array(o,"cases")){var c=obj(e);fields(c,"condition","to");cases.add(new Case(condition(c.get("condition")),s(c,"to")));}yield new Branch(cases,s(o,"defaultTo"),s(o,"unavailableTo"));}
  case "action" -> {fields(o,"type","action","arguments","onSuccess","onRejected","onUnavailable","onUncertain");yield new Action(s(o,"action"),arguments(o),s(o,"onSuccess"),s(o,"onRejected"),s(o,"onUnavailable"),s(o,"onUncertain"));}
  case "outcome" -> {fields(o,"type","milestone","delivery","onAcknowledged","onUnavailable","onUncertain");yield new Outcome(s(o,"milestone"),Delivery.valueOf(s(o,"delivery").toUpperCase(Locale.ROOT)),s(o,"onAcknowledged"),s(o,"onUnavailable"),s(o,"onUncertain"));}
  case "end" -> {fields(o,"type","reason");yield new End(s(o,"reason"));}
  default -> throw fail("NODE_TYPE","dialogue.json","/nodes","Unknown node type");
 };}
 private static HostCatalog host(JsonObject o){fields(o,"schemaVersion","generation","speakers","conditions","actions","gestures","capabilities","durableOutcomes");if(number(o,"schemaVersion").intValueExact()!=1)throw new IllegalArgumentException("Unknown host catalogue version");return new HostCatalog(s(o,"generation"),stringSet(o,"speakers"),providers(obj(o.get("conditions"))),providers(obj(o.get("actions"))),stringSet(o,"gestures"),stringSet(o,"capabilities"),bool(o,"durableOutcomes",false));}
 private static Map<String,HostCatalog.ProviderSpec> providers(JsonObject o){var result=new LinkedHashMap<String,HostCatalog.ProviderSpec>();o.entrySet().forEach(e->{var p=obj(e.getValue());fields(p,"arguments","durable");var args=new LinkedHashMap<String,HostCatalog.ArgumentSpec>();optionalObject(p,"arguments").entrySet().forEach(a->{var shape=obj(a.getValue());fields(shape,"type","required","nullable");args.put(a.getKey(),new HostCatalog.ArgumentSpec(HostCatalog.Kind.valueOf(s(shape,"type").toUpperCase(Locale.ROOT)),bool(shape,"required",false),bool(shape,"nullable",false)));});result.put(e.getKey(),new HostCatalog.ProviderSpec(args,bool(p,"durable",false)));});return result;}
 private static DecodeFailure fail(String code,String file,String path,String message){return new DecodeFailure(code,file,path,message);}
 private static final class DecodeFailure extends RuntimeException {final String code,file,path;DecodeFailure(String c,String f,String p,String m){super(m);code=c;file=f;path=p;}}
}
