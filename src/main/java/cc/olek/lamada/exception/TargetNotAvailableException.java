package cc.olek.lamada.exception;

public class TargetNotAvailableException extends RuntimeException {
    public TargetNotAvailableException(String target) {
        super("Target " + target + " is offline");
    }
}
