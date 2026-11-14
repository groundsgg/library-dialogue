package gg.grounds.dialogue.core.validation;
import java.util.*;
public record ValidationReport(List<Problem> problems, ValidatedDialogue validated) {
 public ValidationReport { problems=List.copyOf(problems); }
 public enum Severity { ERROR, WARNING }
 public record Problem(String code,Severity severity,String file,String path,String nodeId,String message) {}
 public boolean isValid() { return validated!=null && problems.stream().noneMatch(p->p.severity()==Severity.ERROR); }
}
