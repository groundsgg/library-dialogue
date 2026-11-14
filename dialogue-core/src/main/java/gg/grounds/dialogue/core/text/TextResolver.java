package gg.grounds.dialogue.core.text;
import java.util.*;
import java.util.concurrent.*;
import gg.grounds.dialogue.core.definition.ArgumentValue;
@FunctionalInterface public interface TextResolver {
 record TextRequest(String key,Map<String,ArgumentValue> arguments,LocaleContext locale){public TextRequest {arguments=Map.copyOf(arguments);}}
 CompletionStage<ResolvedText> resolve(TextRequest request);
 static TextResolver catalogue(TextCatalog catalog,String defaultTag){return req->{String tag=req.locale().tag();if(!catalog.locales().containsKey(tag))tag=Locale.forLanguageTag(tag).getLanguage();if(!catalog.locales().containsKey(tag))tag=req.locale().fallbackTag();if(!catalog.locales().containsKey(tag))tag=defaultTag;String value=catalog.locales().getOrDefault(tag,Map.of()).get(req.key());return value==null?CompletableFuture.failedFuture(new IllegalArgumentException("TEXT_MISSING: "+req.key())):CompletableFuture.completedFuture(new ResolvedText(value,tag));};}
}
