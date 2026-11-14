package gg.grounds.dialogue.core.validation;
public record ContentPin(String sha256) { public ContentPin { if(sha256==null||!sha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid SHA-256 pin"); } }
