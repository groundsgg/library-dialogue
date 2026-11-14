package gg.grounds.dialogue.json;
import java.util.*;
import java.util.concurrent.atomic.*;
import gg.grounds.dialogue.core.validation.*;
import gg.grounds.dialogue.core.validation.ValidationReport.*;
/** Atomically installs an entirely valid catalogue. Does not discover or read files. */
public final class DialogueCatalogLoader {
 private final DialogueJsonCodec codec=new DialogueJsonCodec();private final AtomicReference<ValidatedCatalogue> current=new AtomicReference<>();private final AtomicLong requests=new AtomicLong();
 public record ContentSources(Map<String,byte[]> dialogues,Map<String,byte[]> texts,byte[] hostCatalog){public ContentSources {dialogues=copy(dialogues);texts=copy(texts);hostCatalog=hostCatalog.clone();}private static Map<String,byte[]> copy(Map<String,byte[]> source){var m=new LinkedHashMap<String,byte[]>();source.forEach((k,v)->m.put(k,v.clone()));return Collections.unmodifiableMap(m);}public Map<String,byte[]> dialogues(){return copy(dialogues);}public Map<String,byte[]> texts(){return copy(texts);}public byte[] hostCatalog(){return hostCatalog.clone();}}
 public record ValidatedCatalogue(Map<String,ValidatedDialogue> entries,ContentPin contentPin,long generation){public ValidatedCatalogue {entries=Map.copyOf(entries);}}
 public record ReloadResult(List<Problem> problems,ValidatedCatalogue candidate,boolean activated){public ReloadResult {problems=List.copyOf(problems);}public boolean isValid(){return candidate!=null;}}
 public Optional<ValidatedCatalogue> current(){return Optional.ofNullable(current.get());}
 public ReloadResult reload(ContentSources sources){long generation=requests.incrementAndGet();var dialogues=sources.dialogues();var texts=sources.texts();byte[] host=sources.hostCatalog();long size=host.length;for(byte[] b:dialogues.values())size+=b.length;for(byte[] b:texts.values())size+=b.length;if(size>16777216L||dialogues.isEmpty()||dialogues.size()>1024)return new ReloadResult(List.of(new Problem("CATALOGUE_LIMIT",Severity.ERROR,"manifest","/",null,"Catalogue must contain 1–1024 dialogues and at most 16 MiB")),null,false);
  var problems=new ArrayList<Problem>();var entries=new LinkedHashMap<String,ValidatedDialogue>();for(var source:dialogues.entrySet()){var decoded=codec.decode(source.getValue(),texts,host);for(var p:decoded.problems())problems.add(new Problem(p.code(),p.severity(),p.file().equals("dialogue.json")?source.getKey():p.file(),p.path(),p.nodeId(),p.message()));if(decoded.isValid()){String id=decoded.validated().definition().id();if(entries.putIfAbsent(id,decoded.validated())!=null)problems.add(new Problem("DIALOGUE_DUPLICATE",Severity.ERROR,source.getKey(),"/id",null,"Duplicate dialogue ID "+id));}}
  if(problems.stream().anyMatch(p->p.severity()==Severity.ERROR))return new ReloadResult(problems,null,false);var pins=new LinkedHashMap<String,String>();entries.forEach((id,v)->pins.put(id,v.contentPin().sha256()));var pin=ContentFingerprint.hash(ContentFingerprint.canonicalBytes(Map.of("manifestVersion",1,"dialogues",pins)));var candidate=new ValidatedCatalogue(entries,pin,generation);for(;;){var old=current.get();if(old!=null&&old.generation()>generation)return new ReloadResult(problems,candidate,false);if(current.compareAndSet(old,candidate))return new ReloadResult(problems,candidate,true);}
 }
}
