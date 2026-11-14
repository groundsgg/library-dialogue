package gg.grounds.dialogue.core.validation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import gg.grounds.dialogue.core.definition.*;
import gg.grounds.dialogue.core.definition.DialogueDefinition.*;
import gg.grounds.dialogue.core.text.TextCatalog;
import gg.grounds.dialogue.core.validation.ValidationReport.*;
/** Stateless common validation for authored JSON and Java definitions. */
public final class DialogueValidator {
 public ValidationReport validate(DialogueDefinition d, TextCatalog texts, HostCatalog host, ValidationLimits limits) {
  Objects.requireNonNull(d);Objects.requireNonNull(texts);Objects.requireNonNull(host);Objects.requireNonNull(limits);
  var check=new Check(d,texts,host,limits);check.run();
  return new ValidationReport(check.problems,check.problems.stream().anyMatch(p->p.severity()==Severity.ERROR)?null:new ValidatedDialogue(d,texts,host,limits));
 }
 public static List<String> targets(Node n) {
  return switch(n) {
   case Message m -> m.next()!=null?List.of(m.next()):m.choices().stream().map(Choice::to).toList();
   case Branch b -> { var t=new ArrayList<String>();b.cases().forEach(c->t.add(c.to()));t.add(b.defaultTo());t.add(b.unavailableTo());yield List.copyOf(t); }
   case Action a -> List.of(a.onSuccess(),a.onRejected(),a.onUnavailable(),a.onUncertain());
   case Outcome o -> List.of(o.onAcknowledged(),o.onUnavailable(),o.onUncertain());
   case End e -> List.of();
  };
 }
 public static boolean validUnicode(String s) {
  for(int i=0;i<s.length();i++) { char c=s.charAt(i);if(Character.isHighSurrogate(c)) { if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))return false; } else if(Character.isLowSurrogate(c))return false; }return true;
 }
 private static final class Check {
  final DialogueDefinition d; final TextCatalog texts; final HostCatalog host; final ValidationLimits l; final List<Problem> problems=new ArrayList<>();int args;
  Check(DialogueDefinition d,TextCatalog t,HostCatalog h,ValidationLimits l) {this.d=d;texts=t;host=h;this.l=l;}
  void problem(String code,String path,String node,String message) { problems.add(new Problem(code,Severity.ERROR,"dialogue.json",path,node,message)); }
  void id(String value,String path,String node) { if(value.isBlank()||value.codePointCount(0,value.length())>l.idCodepoints()||!value.matches("[a-zA-Z0-9_.:/-]+"))problem("ID_INVALID",path,node,"Identifier must be a nonempty bounded ASCII content identifier"); }
  void text(String key,String path,String node) { id(key,path,node);for(String tag:d.locales().supported())if(!texts.locales().getOrDefault(tag,Map.of()).containsKey(key))problem("TEXT_MISSING",path,node,"Missing text key "+key+" for "+tag); }
  void condition(Condition c,String path,String node) { if(c!=null) provider(c.key(),c.arguments(),host.conditions(),path,node); }
  void provider(String key,Map<String,ArgumentValue> values,Map<String,HostCatalog.ProviderSpec> specs,String path,String node) {
   id(key,path,node);args+=values.size();var spec=specs.get(key);if(spec==null){problem("PROVIDER_UNKNOWN",path,node,"Unknown provider "+key);return;}
   spec.arguments().forEach((name,s)->{if(s.required()&&!values.containsKey(name))problem("ARGUMENT_REQUIRED",path+"/arguments/"+name,node,"Missing required argument");});
   values.forEach((name,v)-> {var shape=spec.arguments().get(name);if(shape==null){problem("ARGUMENT_UNKNOWN",path+"/arguments/"+name,node,"Undeclared argument");return;}
    boolean valid=switch(v){case ArgumentValue.Text t -> shape.kind()==HostCatalog.Kind.TEXT;case ArgumentValue.Number n -> shape.kind()==HostCatalog.Kind.NUMBER;case ArgumentValue.Flag b -> shape.kind()==HostCatalog.Kind.BOOLEAN;case ArgumentValue.Nil nil -> shape.nullable()||shape.kind()==HostCatalog.Kind.NULL;};
    if(!valid)problem("ARGUMENT_TYPE",path+"/arguments/"+name,node,"Argument has the wrong scalar type");
    if(v instanceof ArgumentValue.Text t && (!validUnicode(t.value())||t.value().codePointCount(0,t.value().length())>l.textCodepoints()))problem("TEXT_LIMIT",path,node,"Invalid or oversized scalar text");
    if(v instanceof ArgumentValue.Number n && (n.value().precision()>128||Math.abs((long)n.value().precision()-n.value().scale()-1)>308))problem("NUMBER_LIMIT",path,node,"Number exceeds precision/exponent bounds");
   });
  }
  void run() {
   id(d.id(),"/id",null);id(d.entry(),"/entry",null);
   if(d.contentVersion().isBlank()||d.contentVersion().length()>128||!validUnicode(d.contentVersion()))problem("CONTENT_VERSION_INVALID","/contentVersion",null,"Content version must be bounded nonempty text");
   if(d.nodes().isEmpty()||d.nodes().size()>l.nodes())problem("NODE_LIMIT","/nodes",null,"Node limit exceeded");
   if(!d.nodes().containsKey(d.entry()))problem("ENTRY_UNKNOWN","/entry",null,"Entry node does not exist");
   var tags=new HashSet<String>();for(String tag:d.locales().supported()) {try{var locale=new Locale.Builder().setLanguageTag(tag).build();if(locale.getLanguage().isEmpty()||!locale.toLanguageTag().equals(tag)||!tags.add(tag))throw new IllegalArgumentException();}catch(RuntimeException e){problem("LOCALE_INVALID","/locales",null,"Invalid or duplicate normalized locale "+tag);}}
   if(tags.isEmpty()||tags.size()>l.locales()||!tags.contains(d.locales().defaultTag()))problem("LOCALE_DEFAULT","/locales",null,"Default must belong to bounded supported locales");
   long bytes=0;for(var bundle:texts.locales().entrySet()){long size=0;for(var t:bundle.getValue().entrySet()){if(!validUnicode(t.getValue())||t.getValue().codePointCount(0,t.getValue().length())>l.textCodepoints())problem("TEXT_LIMIT","/texts/"+bundle.getKey()+"/"+t.getKey(),null,"Invalid or oversized translation");size+=t.getKey().getBytes(StandardCharsets.UTF_8).length+t.getValue().getBytes(StandardCharsets.UTF_8).length;}bytes+=size;if(size>l.textFileBytes())problem("TEXT_FILE_LIMIT","/texts",null,"Text file exceeds budget");}if(bytes>l.textPackageBytes())problem("TEXT_PACKAGE_LIMIT","/texts",null,"Text package exceeds budget");
   for(String c:d.capabilities().required())if(!host.capabilities().contains(c))problem("CAPABILITY_MISSING","/capabilities/required",null,"Required capability unavailable: "+c);
   var look=d.interaction().lookAway();if(!Double.isFinite(look.exitAngleDegrees())||!Double.isFinite(look.resetAngleDegrees())||look.resetAngleDegrees()<0||look.exitAngleDegrees()>180||look.resetAngleDegrees()>=look.exitAngleDegrees()||look.exitDelayMs()<0||look.exitDelayMs()>60000||!Double.isFinite(d.interaction().maxDistanceBlocks())||d.interaction().maxDistanceBlocks()<=0)problem("INTERACTION_INVALID","/interaction",null,"Invalid look/distance policy");
   d.nodes().forEach((name,n)-> {String path="/nodes/"+name;id(name,path,name);for(String target:targets(n))if(!d.nodes().containsKey(target))problem("REFERENCE_UNKNOWN",path,name,"Unknown target "+target);
    switch(n) {
     case Message m -> {if(!host.speakers().contains(m.speaker()))problem("SPEAKER_UNKNOWN",path+"/speaker",name,"Unknown speaker "+m.speaker());text(m.text(),path+"/text",name);if(m.gesture()!=null&&!host.gestures().contains(m.gesture()))problem("GESTURE_UNKNOWN",path+"/presentation/gesture",name,"Unknown gesture");if(m.choices().size()>l.choices())problem("CHOICE_LIMIT",path+"/choices",name,"Too many choices");if(!m.choices().isEmpty()&&m.choices().stream().noneMatch(c->c.condition()==null))problem("CHOICE_ALWAYS_AVAILABLE_REQUIRED",path+"/choices",name,"At least one choice must have no condition");var seen=new HashSet<String>();int defaults=0;for(var c:m.choices()){id(c.id(),path+"/choices",name);if(!seen.add(c.id()))problem("CHOICE_DUPLICATE",path+"/choices",name,"Duplicate choice ID "+c.id());if(c.defaultChoice())defaults++;text(c.text(),path+"/choices/"+c.id()+"/text",name);condition(c.condition(),path+"/choices/"+c.id(),name);}if(defaults>1)problem("CHOICE_DEFAULTS",path+"/choices",name,"Only one default choice allowed");}
     case Branch b -> b.cases().forEach(c->condition(c.condition(),path+"/cases",name));
     case Action a -> provider(a.key(),a.arguments(),host.actions(),path,name);
     case Outcome o -> {id(o.milestone(),path+"/milestone",name);}
     case End e -> {if(!Set.of("completed","cancelled","looked_away","out_of_range","disconnected","host_changed","superseded","host_closed","failed").contains(e.reason()))problem("END_REASON_INVALID",path,name,"Unknown neutral end reason");}
    }
   });
   if(args>l.arguments())problem("ARGUMENT_LIMIT","/nodes",null,"Provider argument budget exceeded");graph();
  }
  void graph() {
   var automatic=new HashSet<String>();d.nodes().forEach((k,n)->{if(!(n instanceof Message)&&!(n instanceof End))automatic.add(k);});
   var indegree=new HashMap<String,Integer>();automatic.forEach(k->indegree.put(k,0));for(String k:automatic)for(String t:targets(d.nodes().get(k)))if(automatic.contains(t))indegree.merge(t,1,Integer::sum);
   var queue=new ArrayDeque<String>();indegree.forEach((k,v)->{if(v==0)queue.add(k);});int removed=0;while(!queue.isEmpty()){String k=queue.remove();removed++;for(String t:targets(d.nodes().get(k)))if(automatic.contains(t)&&indegree.merge(t,-1,Integer::sum)==0)queue.add(t);}if(removed!=automatic.size())problem("AUTOMATIC_CYCLE","/nodes",null,"Automatic-only cycles are not allowed");
   var reverse=new HashMap<String,List<String>>();d.nodes().forEach((k,n)->targets(n).forEach(t->reverse.computeIfAbsent(t,x->new ArrayList<>()).add(k)));
   var canEnd=new HashSet<String>();d.nodes().forEach((k,n)->{if(n instanceof End){canEnd.add(k);queue.add(k);}});while(!queue.isEmpty())for(String p:reverse.getOrDefault(queue.remove(),List.of()))if(canEnd.add(p))queue.add(p);
   if(!canEnd.contains(d.entry()))problem("END_UNREACHABLE","/entry",null,"Entry has no structural path to an end");
   var reachable=new HashSet<String>();queue.add(d.entry());while(!queue.isEmpty()){String k=queue.remove();if(!reachable.add(k)||!d.nodes().containsKey(k))continue;queue.addAll(targets(d.nodes().get(k)));}
   d.nodes().keySet().stream().filter(k->!reachable.contains(k)).forEach(k->problems.add(new Problem("NODE_UNREACHABLE",Severity.WARNING,"dialogue.json","/nodes/"+k,k,"Unreachable node")));
  }
 }
}
