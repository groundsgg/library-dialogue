package gg.grounds.dialogue.core.validation;
public record ValidationLimits(int definitionBytes,int nodes,int choices,int automaticSteps,int depth,int idCodepoints,int textCodepoints,int locales,int textFileBytes,int textPackageBytes,int arguments) {
 public ValidationLimits { if(definitionBytes<1||nodes<1||choices<1||automaticSteps<1||depth<1||idCodepoints<1||textCodepoints<1||locales<1||textFileBytes<1||textPackageBytes<1||arguments<1) throw new IllegalArgumentException("limits must be positive"); }
 public static ValidationLimits defaults() { return new ValidationLimits(1048576,1024,64,32,32,128,16384,64,4194304,16777216,1024); }
}
