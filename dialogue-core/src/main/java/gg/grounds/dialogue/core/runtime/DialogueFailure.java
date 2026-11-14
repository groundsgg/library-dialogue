package gg.grounds.dialogue.core.runtime;
public final class DialogueFailure extends RuntimeException {
 private final String code;
 public DialogueFailure(String code,String message){super(message);this.code=code;}
 public DialogueFailure(String code,Throwable cause){super(code,cause);this.code=code;}
 public String code(){return code;}
}
